package com.clearcall.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * App-scoped wrapper around the sherpa-onnx speaker-embedding extractor (WeSpeaker CAM++,
 * Apache-2.0, model bundled in assets). Turns a 16 kHz mono [-1, 1] float sample into an
 * L2-normalized d-vector — the "voice fingerprint" used by enrollment and by the in-call
 * [SpeakerGate]. Inference is 100% on-device and never runs on the realtime audio thread.
 */
object SpeakerEncoder {
    private const val TAG = "SpeakerEncoder"
    private const val MODEL_ASSET = "models/wespeaker_en_voxceleb_CAM++.onnx"

    /**
     * Identifies the encoder that produced a stored embedding. Bump when [MODEL_ASSET] changes:
     * [VoiceEnrollment.recomputeIfStale] then re-derives the embedding from the kept enrollment
     * WAV, so users never have to re-record after a model upgrade.
     */
    const val VERSION = "wespeaker-en-voxceleb-campp-v1"

    const val SAMPLE_RATE = 16_000

    private lateinit var appContext: Context
    private var extractor: SpeakerEmbeddingExtractor? = null

    // The extractor and its native streams are not thread-safe; serialize all access.
    private val mutex = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Compute the L2-normalized embedding of [samples16k] (16 kHz mono floats in [-1, 1]),
     * or null on any failure (missing model asset, bad input, native error).
     */
    suspend fun embed(samples16k: FloatArray): FloatArray? = withContext(Dispatchers.Default) {
        if (samples16k.isEmpty()) return@withContext null
        mutex.withLock {
            val ex = ensureLoaded() ?: return@withLock null
            runCatching {
                val stream = ex.createStream()
                try {
                    stream.acceptWaveform(samples16k, SAMPLE_RATE)
                    stream.inputFinished()
                    if (!ex.isReady(stream)) return@runCatching null
                    l2Normalize(ex.compute(stream))
                } finally {
                    stream.release()
                }
            }.onFailure { Log.e(TAG, "embed failed", it) }.getOrNull()
        }
    }

    /** Cosine similarity of two embeddings; 0 on dimension mismatch (treat as "no match"). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }

    private fun ensureLoaded(): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        if (!::appContext.isInitialized) {
            Log.w(TAG, "init() not called yet")
            return null
        }
        return runCatching {
            SpeakerEmbeddingExtractor(
                appContext.assets,
                SpeakerEmbeddingExtractorConfig(MODEL_ASSET, 2, false, "cpu"),
            )
        }.onSuccess {
            extractor = it
            Log.i(TAG, "Speaker encoder loaded ($MODEL_ASSET), dim=${it.dim()}")
        }.onFailure {
            Log.e(TAG, "Speaker encoder failed to load ($MODEL_ASSET)", it)
        }.getOrNull()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        if (n > 0f) {
            val inv = 1f / sqrt(n)
            for (i in v.indices) v[i] *= inv
        }
        return v
    }
}
