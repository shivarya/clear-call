package com.clearcall.audio

import android.util.Log
import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapts a [NoiseProcessor] into LiveKit's capture-post hook ([AudioProcessorInterface]).
 *
 * WebRTC hands us 10 ms float frames (`numFrames` samples) after its own AEC/AGC; the
 * processor consumes fixed [NoiseProcessor.hopSize] hops. Ring buffers reconcile the two
 * (a no-op when they match, e.g. 480==480). A **failure here must never break a call**, so:
 *  - any exception in the processor → permanent bypass for the rest of the call,
 *  - a hop that overruns its 10 ms realtime budget for a sustained streak → auto-bypass
 *    ("device too slow"),
 *  - non-mono / band-split / not-yet-ready frames pass through untouched.
 *
 * The first frame's format is logged once — that log **is** the P2 "probe": it reports the
 * real `sampleRate / numBands / numFrames` and the model's `hopSize`, which must be
 * confirmed on a real device (the emulator can't produce meaningful audio).
 */
class CaptureProcessorBridge(
    private val processor: NoiseProcessor,
    private val stats: NoiseStats,
) : AudioProcessorInterface {

    private val userBypass = AtomicBoolean(false)
    @Volatile private var permanentBypass = false

    private var channels = 1
    private var loggedFormat = false

    // Realtime scratch/state — sized once on the first frame (off steady state), then reused.
    private var scratch = FloatArray(0)
    private val inRing = FloatRing()
    private val outRing = FloatRing()
    private var hopBuf = FloatArray(0)
    private var capacityHop = -1
    private var capacityFrames = -1

    // Watchdog + timing window (p95 over the last 64 hops).
    private var slowStreak = 0
    private val window = LongArray(64)
    private var windowIdx = 0
    private var windowCount = 0
    private var microsSum = 0L

    override fun isEnabled(): Boolean = !userBypass.get() && !permanentBypass

    override fun getName(): String = "clearcall-ns"

    /** Mid-call A/B toggle. `true` = pass raw mic through (suppression off). */
    fun setBypass(bypass: Boolean) {
        userBypass.set(bypass)
        stats.bypassed = bypass || permanentBypass
    }

    val isBypassed: Boolean get() = userBypass.get() || permanentBypass

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {
        channels = numChannels
        stats.sampleRate = sampleRateHz
        stats.engineName = processor.name
        try {
            processor.initialize(sampleRateHz, numChannels)
        } catch (t: Throwable) {
            fail("initialize", t)
        }
    }

    override fun resetAudioProcessing(newRate: Int) {
        stats.sampleRate = newRate
        inRing.clear()
        outRing.clear()
        runCatching { processor.reset() }
    }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val hop = processor.hopSize
        if (!loggedFormat) {
            Log.i(
                TAG,
                "PROBE capture format: numBands=$numBands numFrames=$numFrames " +
                    "channels=$channels capacityBytes=${buffer.capacity()} engine=${processor.name} hopSize=$hop",
            )
            loggedFormat = true
        }
        stats.numBands = numBands
        stats.numFrames = numFrames
        stats.hopSize = hop
        stats.ready = hop > 0

        // Passthrough guards — never touch the audio in any of these cases.
        if (permanentBypass || userBypass.get() || numBands != 1 || channels != 1 || hop <= 0) {
            return
        }

        ensureCapacity(numFrames, hop)
        val floats = buffer.asFloatBuffer()

        // Pull this 10 ms frame in, run whole hops through the processor, buffer the output.
        floats.rewind()
        floats.get(scratch, 0, numFrames)
        inRing.write(scratch, numFrames)

        while (inRing.available() >= hop) {
            inRing.read(hopBuf, hop)
            val t0 = System.nanoTime()
            try {
                processor.processHop(hopBuf)
            } catch (t: Throwable) {
                fail("processHop", t)
                return
            }
            recordHop((System.nanoTime() - t0) / 1000L)
            outRing.write(hopBuf, hop)
        }

        // Emit only once enough processed audio has accumulated; until then the original
        // frame stands (a bounded warm-up latency, zero when numFrames == hop).
        if (outRing.available() >= numFrames) {
            outRing.read(scratch, numFrames)
            floats.rewind()
            floats.put(scratch, 0, numFrames)
        }
    }

    private fun ensureCapacity(numFrames: Int, hop: Int) {
        if (numFrames == capacityFrames && hop == capacityHop) return
        capacityFrames = numFrames
        capacityHop = hop
        val cap = (numFrames + hop) * 4 + hop // generous, avoids wrap edge cases
        scratch = FloatArray(maxOf(numFrames, hop))
        hopBuf = FloatArray(hop)
        inRing.ensureCapacity(cap)
        outRing.ensureCapacity(cap)
    }

    private fun recordHop(micros: Long) {
        stats.lastHopMicros = micros
        stats.hopsProcessed++

        // Rolling window for avg + p95.
        if (windowCount < window.size) {
            window[windowIdx] = micros
            windowIdx = (windowIdx + 1) % window.size
            microsSum += micros
            windowCount++
        } else {
            microsSum += micros - window[windowIdx]
            window[windowIdx] = micros
            windowIdx = (windowIdx + 1) % window.size
        }
        stats.avgHopMicros = microsSum / windowCount
        stats.p95HopMicros = percentile95()

        // Watchdog: a hop that blows the ~10 ms realtime budget, sustained, means this device
        // can't keep up — bail out for the rest of the call rather than glitch the audio.
        val budgetMicros = (capacityHop.toLong() * 1000L) / 48L // ~ hop duration in µs (hop/48kHz)
        if (micros > maxOf(budgetMicros, 10_000L)) {
            if (++slowStreak >= 50) {
                Log.w(TAG, "Auto-bypass: NS too slow (${micros}µs/hop, budget≈${budgetMicros}µs)")
                permanentBypass = true
                stats.autoBypassed = true
                stats.bypassed = true
            }
        } else {
            slowStreak = 0
        }
    }

    private fun percentile95(): Long {
        val n = windowCount
        if (n == 0) return 0
        val copy = window.copyOf(n)
        copy.sort()
        return copy[((n - 1) * 95) / 100]
    }

    private fun fail(where: String, t: Throwable) {
        Log.e(TAG, "Noise suppression failed in $where — bypassing for this call", t)
        permanentBypass = true
        stats.autoBypassed = true
        stats.bypassed = true
    }

    companion object {
        private const val TAG = "NoiseBridge"
    }
}
