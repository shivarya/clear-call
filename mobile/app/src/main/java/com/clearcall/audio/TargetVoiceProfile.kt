package com.clearcall.audio

/**
 * A "voice to keep" profile for the Isolate-a-voice engine.
 *
 * The user records a short reference **sample of the voice they want to isolate** — their own,
 * or anyone's — and [SpeakerEncoder] (WeSpeaker CAM++, on-device) turns it into a fixed-length
 * d-vector. That d-vector conditions [SpeakerConditionedProcessor]'s gate so only the matching
 * speaker passes through. The sample and its d-vector stay on the device (never uploaded).
 *
 * The embedding dimension is whatever the bundled encoder produces ([SpeakerEncoder] logs it at
 * load); both the stored profile and the in-call gate use the same encoder, so they always agree.
 */
data class TargetVoiceProfile(
    val name: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TargetVoiceProfile && name == other.name && embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = 31 * name.hashCode() + embedding.contentHashCode()

    companion object {
        /**
         * Compute a d-vector from a reference sample of the target voice (16 kHz mono [-1, 1]
         * floats). Null on failure — the pipeline then transparently falls back to general
         * suppression. Persist the result via [com.clearcall.core.Prefs.targetVoiceEmbedding]
         * (see [VoiceEnrollment.save], which callers should prefer).
         */
        suspend fun computeEmbedding(referenceSample16kMono: FloatArray): FloatArray? =
            SpeakerEncoder.embed(referenceSample16kMono)

        fun isValid(embedding: FloatArray?): Boolean = embedding != null && embedding.isNotEmpty()
    }
}
