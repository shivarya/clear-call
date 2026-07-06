package com.clearcall.audio

import android.content.Context
import android.util.Log

/**
 * Tier B **target-speaker extraction** — seam / stub for now.
 *
 * Goal (like Teams' voice isolation / Google VoiceFilter-Lite, but target-agnostic): keep only
 * the voice that matches a **provided reference sample** and remove everyone/everything else —
 * background chatter, other people talking near the mic, and noise. The target is defined by a
 * d-vector [targetEmbedding] computed from *any* speaker's sample (see [TargetVoiceProfile]),
 * not fixed to the phone's owner: whoever's sample you enroll is the voice that gets filtered in.
 *
 * The end state: ONE generic speaker-conditioned separation model trained once developer-side;
 * at runtime it takes the mic frame **plus** the target d-vector and masks out non-target audio.
 * Inference is 100% on-device; users never need a GPU. Until that model ships, this composes
 * [DfnNoiseProcessor], so "isolate a voice" still gives real (general) suppression rather than
 * nothing. When the model lands, only [processHop] changes to feed [targetEmbedding] into it —
 * the bridge, settings, and profile storage are already wired. See the plan (P4) / CLAUDE.md.
 */
class SpeakerConditionedProcessor(
    context: Context,
    attenuationLimitDb: Float,
) : NoiseProcessor {

    override val name = "target-speaker"

    private val fallback = DfnNoiseProcessor(context, attenuationLimitDb)

    @Volatile private var targetEmbedding: FloatArray? = null

    /**
     * Set the d-vector of the voice to keep (from an enrolled reference sample). Passing null
     * means "no target selected" → the stub simply runs general suppression.
     */
    fun setTargetEmbedding(embedding: FloatArray?) {
        targetEmbedding = embedding
        Log.i(TAG, "Target voice d-vector set (dim=${embedding?.size ?: 0}); DFN3 fallback until the extraction model ships")
    }

    override val hopSize: Int get() = fallback.hopSize

    override fun initialize(sampleRateHz: Int, numChannels: Int) = fallback.initialize(sampleRateHz, numChannels)

    override fun processHop(hop: FloatArray) {
        // TODO(P4-real): run the target-speaker separation model conditioned on [targetEmbedding]
        // here — keep only the matching speaker. Until then, general suppression via DFN3 so
        // "isolate a voice" is never worse than Tier A.
        fallback.processHop(hop)
    }

    override fun reset() = fallback.reset()
    override fun release() = fallback.release()

    companion object {
        private const val TAG = "TargetSpeaker"
    }
}
