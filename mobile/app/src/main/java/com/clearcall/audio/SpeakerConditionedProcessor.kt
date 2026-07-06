package com.clearcall.audio

import android.content.Context
import android.util.Log

/**
 * Tier B "only my voice" suppression — **seam / stub for now**.
 *
 * The end state (VoiceFilter-Lite style): one generic speaker-conditioned model trained once,
 * developer-side; each user enrolls ~30–60 s of speech on-device once ([VoiceEnrollment]),
 * producing a small d-vector [speakerEmbedding] that conditions the model at runtime to keep
 * only the enrolled speaker and remove everyone/everything else. Inference is 100% on-device;
 * users never need a GPU.
 *
 * Until that model ships, this composes [DfnNoiseProcessor] so selecting "Personalized" still
 * gives real (general) suppression rather than nothing. When the personalized model lands, only
 * [processHop] changes to feed the embedding into it — the bridge, settings, and enrollment are
 * already wired. See the plan (P4) / CLAUDE.md.
 */
class SpeakerConditionedProcessor(
    context: Context,
    attenuationLimitDb: Float,
) : NoiseProcessor {

    override val name = "personalized"

    private val fallback = DfnNoiseProcessor(context, attenuationLimitDb)

    @Volatile private var speakerEmbedding: FloatArray? = null

    /** Condition on the enrolled speaker's d-vector. No-op in the stub beyond storing it. */
    fun setSpeakerEmbedding(embedding: FloatArray?) {
        speakerEmbedding = embedding
        Log.i(TAG, "Speaker embedding set (dim=${embedding?.size ?: 0}); using DFN3 fallback until the personalized model ships")
    }

    override val hopSize: Int get() = fallback.hopSize

    override fun initialize(sampleRateHz: Int, numChannels: Int) = fallback.initialize(sampleRateHz, numChannels)

    override fun processHop(hop: FloatArray) {
        // TODO(P4-real): run the speaker-conditioned model with [speakerEmbedding] here.
        // Until then, general suppression via DFN3 so "Personalized" is never worse than Tier A.
        fallback.processHop(hop)
    }

    override fun reset() = fallback.reset()
    override fun release() = fallback.release()

    companion object {
        private const val TAG = "SpeakerNoise"
    }
}
