package com.clearcall.audio

import android.content.Context
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * TEMPORARY debug-only sanity check for the bundled ML models (remove after device bring-up).
 * Verifies on-device that the speaker encoder and Silero VAD load and produce coherent output:
 *  - encoder dim + determinism (identical input → cosine ≈ 1)
 *  - distinct inputs → clearly lower cosine
 *  - VAD returns a probability without crashing
 */
object MlSelfTest {
    private const val TAG = "MlSelfTest"

    suspend fun run(context: Context) {
        val rate = SpeakerEncoder.SAMPLE_RATE
        val secs = 2
        val rnd = Random(42)

        // Harmonic "voice-ish" buzz vs white noise — not speech, but plenty to check plumbing.
        val buzz = FloatArray(rate * secs) { i ->
            val t = i.toFloat() / rate
            var s = 0f
            for (h in 1..8) s += (1f / h) * sin(2f * PI.toFloat() * 120f * h * t)
            0.3f * s * (0.6f + 0.4f * sin(2f * PI.toFloat() * 3f * t)) // slow AM envelope
        }
        val noise = FloatArray(rate * secs) { (rnd.nextFloat() * 2f - 1f) * 0.3f }

        val e1 = SpeakerEncoder.embed(buzz)
        val e2 = SpeakerEncoder.embed(buzz)
        val e3 = SpeakerEncoder.embed(noise)
        if (e1 == null || e2 == null || e3 == null) {
            Log.e(TAG, "FAIL: encoder returned null (e1=${e1 != null} e2=${e2 != null} e3=${e3 != null})")
            return
        }
        val same = SpeakerEncoder.cosine(e1, e2)
        val diff = SpeakerEncoder.cosine(e1, e3)
        Log.i(TAG, "encoder dim=${e1.size}  cos(same input)=$same (expect ~1.0)  cos(buzz,noise)=$diff (expect < same)")

        runCatching {
            val vad = com.k2fsa.sherpa.onnx.Vad(
                context.assets,
                com.k2fsa.sherpa.onnx.VadModelConfig(
                    sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                        model = "models/silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.25f,
                        minSpeechDuration = 0.1f,
                        windowSize = 512,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = rate,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
            val w = FloatArray(512)
            buzz.copyInto(w, 0, rate, rate + 512)
            val pBuzz = vad.compute(w)
            vad.reset()
            noise.copyInto(w, 0, rate, rate + 512)
            val pNoise = vad.compute(w)
            vad.release()
            Log.i(TAG, "VAD ok: p(buzz)=$pBuzz p(noise)=$pNoise (plumbing check only — not speech)")
        }.onFailure { Log.e(TAG, "FAIL: VAD load/compute", it) }

        // Full realtime-chain plumbing: DFN3 + gate feed + gain ramp through the actual
        // processor, 16 kHz hops at int16 scale, like the bridge would drive it.
        runCatching {
            val proc = SpeakerConditionedProcessor(context, 100f)
            proc.setTargetEmbedding(e1)
            proc.initialize(rate, 1)
            var waitedMs = 0
            while (proc.hopSize <= 0 && waitedMs < 8000) {
                kotlinx.coroutines.delay(100)
                waitedMs += 100
            }
            if (proc.hopSize <= 0) {
                Log.w(TAG, "chain check skipped: DFN model not ready after ${waitedMs}ms")
            } else {
                val hop = FloatArray(proc.hopSize)
                var totalNanos = 0L
                repeat(150) { i ->
                    for (j in hop.indices) hop[j] = buzz[(i * hop.size + j) % buzz.size] * 20000f
                    val t0 = System.nanoTime()
                    proc.processHop(hop)
                    totalNanos += System.nanoTime() - t0
                }
                Log.i(
                    TAG,
                    "chain ok: hop=${proc.hopSize} avg=${totalNanos / 150 / 1000}µs/hop " +
                        "gate=${if (NoiseSuppression.stats.gateOpen) "OPEN" else "CLOSED"} (expect OPEN — synthetic audio isn't speech to the VAD)",
                )
            }
            proc.release()
        }.onFailure { Log.e(TAG, "FAIL: processor chain", it) }
        Log.i(TAG, "self-test done")
    }
}
