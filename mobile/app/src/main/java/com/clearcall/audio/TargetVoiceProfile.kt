package com.clearcall.audio

/**
 * A "voice to keep" profile for target-speaker extraction — seam / stub for now.
 *
 * The user records (or picks) a short reference **sample of the voice they want to isolate** —
 * their own, or anyone's — and a speaker-encoder turns it into a fixed-length d-vector. That
 * d-vector conditions [SpeakerConditionedProcessor] so only the matching speaker passes through.
 * The sample and its d-vector stay on the device (never uploaded); only inference runs locally,
 * so no GPU and no cloud are ever needed.
 *
 * Two pieces are still to build (both developer-side ML, then dropped in here with no caller
 * changes): the **speaker-encoder** ([computeEmbedding]) that turns a sample into a d-vector,
 * and the **target-speaker separation model** used at runtime in [SpeakerConditionedProcessor].
 */
data class TargetVoiceProfile(
    val name: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TargetVoiceProfile && name == other.name && embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = 31 * name.hashCode() + embedding.contentHashCode()

    companion object {
        /** Target d-vector length the encoder/separation models agree on. */
        const val EMBEDDING_DIM = 256

        /**
         * Compute a d-vector from a reference sample of the target voice (16 kHz mono PCM). Not
         * yet implemented — returns null so the pipeline transparently falls back to general
         * suppression. Wire the TFLite speaker-encoder here; persist the result via
         * [com.clearcall.core.Prefs.targetVoiceEmbedding].
         */
        fun computeEmbedding(referenceSample16kMono: FloatArray): FloatArray? {
            // TODO(P4-real): TFLite speaker-encoder inference -> L2-normalized FloatArray(EMBEDDING_DIM)
            return null
        }

        fun isValid(embedding: FloatArray?): Boolean = embedding != null && embedding.size == EMBEDDING_DIM
    }
}
