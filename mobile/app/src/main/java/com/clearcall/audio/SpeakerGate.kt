package com.clearcall.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The decision half of "Isolate a voice": watches the (already DFN3-cleaned) uplink and decides,
 * a few times per second, whether the person currently speaking is the enrolled target voice.
 * [SpeakerConditionedProcessor] applies the resulting gain; this class never touches the
 * realtime audio thread beyond [feed]'s copy-and-decimate into a lock-free ring.
 *
 * Design (v1, zero-training): Silero VAD says *is anyone speaking*; if yes, [SpeakerEncoder]
 * embeds the last second of audio and its cosine against the enrolled d-vector drives a
 * hysteresis state machine (open/closed + a hold-open window so brief overlap or low-cosine
 * dips never mute the target). Every failure path fails **open** — worst case the call behaves
 * exactly like plain DFN3. True separation of simultaneous overlap is out of scope for v1
 * (a background talker rides along at DFN3 level while the target speaks).
 */
class SpeakerGate(private val context: Context) {

    // ---- Tunables (validated on-device; overlay shows live cosine to retune) ----
    private companion object {
        const val TAG = "SpeakerGate"
        const val ANALYSIS_RATE = SpeakerEncoder.SAMPLE_RATE // 16 kHz
        const val WINDOW_SAMPLES = ANALYSIS_RATE // 1 s analysis window
        const val TICK_MS = 250L
        const val VAD_WINDOW = 512 // Silero v4 window @16 kHz
        const val VAD_THRESHOLD = 0.5f
        const val COSINE_OPEN = 0.45f
        const val COSINE_CLOSE = 0.30f
        const val HOLD_OPEN_MS = 2_000L // since last strong match
        const val FLOOR_DB = -35f
        const val VAD_MODEL_ASSET = "models/silero_vad.onnx"
    }

    /** Linear gain the processor should ramp toward: 1.0 (open) or the floor (closed). */
    @Volatile var targetGainLinear: Float = 1f
        private set

    val floorLinear: Float = db2lin(FLOOR_DB)

    @Volatile private var target: FloatArray? = null
    @Volatile private var decimFactor = 1 // captureRate / 16k; 0 = unsupported rate → disabled

    // Single-producer (audio thread) lock-free analysis ring, normalized [-1,1] @16 kHz.
    // Torn reads at the write boundary are harmless — it's analysis audio, not the speech path.
    private val ring = FloatArray(WINDOW_SAMPLES * 2)
    @Volatile private var written = 0L
    @Volatile private var lastFeedNanos = 0L

    private var lastMatchAtMs = 0L
    private var open = true

    private var vad: Vad? = null
    private var vadLoadFailed = false
    private val windowBuf = FloatArray(WINDOW_SAMPLES)
    private val vadBuf = FloatArray(VAD_WINDOW)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var ticker = false

    private val stats = NoiseSuppression.stats

    /** Voice to keep; null disables the gate (permanently open). */
    fun setTarget(embedding: FloatArray?) {
        target = embedding
        if (embedding == null) {
            openNow()
        } else {
            startTicker()
        }
    }

    /** Called from [SpeakerConditionedProcessor.initialize]; non-integer rates disable the gate. */
    fun configure(sampleRateHz: Int) {
        decimFactor = if (sampleRateHz % ANALYSIS_RATE == 0) sampleRateHz / ANALYSIS_RATE else 0
        if (decimFactor == 0) {
            Log.w(TAG, "Capture rate $sampleRateHz not a multiple of $ANALYSIS_RATE — gate disabled (fail-open)")
            openNow()
        }
        reset()
    }

    /**
     * Realtime-thread feed: decimate the (int16-scaled) hop into the analysis ring.
     * No allocation, no locks — just array writes and two volatile stores.
     */
    fun feed(hop: FloatArray) {
        val f = decimFactor
        if (f <= 0 || target == null) return
        var w = written
        var i = 0
        while (i < hop.size) {
            // Boxcar average over the decimation group: crude but allocation-free anti-aliasing;
            // fine for analysis (VAD + speaker ID), the speech path is untouched.
            var acc = 0f
            val end = minOf(i + f, hop.size)
            for (j in i until end) acc += hop[j]
            ring[(w % ring.size).toInt()] = (acc / (end - i)) / 32768f
            w++
            i += f
        }
        written = w
        lastFeedNanos = System.nanoTime()
    }

    /** Between-call state drop (also on capture-rate change). Fail-open until re-evaluated. */
    fun reset() {
        written = 0
        openNow()
    }

    fun close() {
        scope.cancel()
        vad?.release()
        vad = null
    }

    // ---- Decision loop (Dispatchers.Default; never the audio thread) ----

    private fun startTicker() {
        if (ticker) return
        ticker = true
        scope.launch {
            Log.i(TAG, "Gate ticker started (window=${WINDOW_SAMPLES / ANALYSIS_RATE}s tick=${TICK_MS}ms " +
                    "open≥$COSINE_OPEN close≤$COSINE_CLOSE hold=${HOLD_OPEN_MS}ms floor=${FLOOR_DB}dB)")
            while (isActive) {
                delay(TICK_MS)
                runCatching { tick() }.onFailure {
                    Log.e(TAG, "Gate tick failed — failing open", it)
                    openNow()
                }
            }
        }
    }

    private suspend fun tick() {
        val tgt = target ?: return
        // Idle when no call audio is flowing (processor is app-scoped, ticker outlives calls).
        if (System.nanoTime() - lastFeedNanos > 1_000_000_000L) return
        val w = written
        if (w < WINDOW_SAMPLES) return

        // Copy out the most recent 1 s window, oldest-first.
        val start = w - WINDOW_SAMPLES
        for (k in 0 until WINDOW_SAMPLES) {
            windowBuf[k] = ring[((start + k) % ring.size).toInt()]
        }

        val speechProb = vadSpeechProb(windowBuf)
        stats.gateVad = speechProb >= VAD_THRESHOLD
        if (speechProb < VAD_THRESHOLD) return // silence/noise-only: hold current state

        val emb = SpeakerEncoder.embed(windowBuf) ?: return
        val cos = SpeakerEncoder.cosine(emb, tgt)
        stats.gateCosine = cos

        val nowMs = System.currentTimeMillis()
        if (cos >= COSINE_OPEN) {
            lastMatchAtMs = nowMs
            if (!open) Log.i(TAG, "Gate OPEN (cos=$cos)")
            setOpen(true)
        } else if (cos <= COSINE_CLOSE && nowMs - lastMatchAtMs > HOLD_OPEN_MS) {
            if (open) Log.i(TAG, "Gate CLOSED (cos=$cos)")
            setOpen(false)
        } // in between: hysteresis, keep current state
    }

    /** Max Silero speech probability over the window's 512-sample sub-windows. */
    private fun vadSpeechProb(window: FloatArray): Float {
        val v = ensureVad() ?: return 1f // VAD unavailable → treat as speech (fail toward evaluating)
        v.reset()
        var maxProb = 0f
        var i = 0
        while (i + VAD_WINDOW <= window.size) {
            window.copyInto(vadBuf, 0, i, i + VAD_WINDOW)
            val p = v.compute(vadBuf)
            if (p > maxProb) maxProb = p
            i += VAD_WINDOW
        }
        return maxProb
    }

    private fun ensureVad(): Vad? {
        vad?.let { return it }
        if (vadLoadFailed) return null
        return runCatching {
            Vad(
                context.assets,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VAD_MODEL_ASSET,
                        threshold = VAD_THRESHOLD,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.1f,
                        windowSize = VAD_WINDOW,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = ANALYSIS_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
        }.onSuccess {
            vad = it
            Log.i(TAG, "Silero VAD loaded ($VAD_MODEL_ASSET)")
        }.onFailure {
            vadLoadFailed = true
            Log.e(TAG, "Silero VAD failed to load — gate will evaluate every tick", it)
        }.getOrNull()
    }

    private fun openNow() = setOpen(true)

    private fun setOpen(value: Boolean) {
        open = value
        targetGainLinear = if (value) 1f else floorLinear
        stats.gateOpen = value
    }

    private fun db2lin(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()
}
