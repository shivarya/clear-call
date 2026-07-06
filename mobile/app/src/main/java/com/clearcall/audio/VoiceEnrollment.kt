package com.clearcall.audio

/**
 * On-device voice enrollment for Tier B — **seam / stub for now**.
 *
 * Real flow (later): record ~30–60 s of the user's speech, run a small speaker-encoder TFLite
 * model over it to produce a fixed-length d-vector, and store that locally (never uploaded).
 * The d-vector then conditions [SpeakerConditionedProcessor]. Training the *generic* encoder +
 * separation model is a one-time developer-side job (GTX 1070 / rented GPU); the user's device
 * only ever runs inference — no GPU, no training, no cloud.
 *
 * This stub defines the contract and the storage shape so the enrollment UI + real encoder drop
 * in without touching callers. [EMBEDDING_DIM] is the target d-vector size.
 */
object VoiceEnrollment {

    const val EMBEDDING_DIM = 256

    /**
     * Compute a speaker d-vector from enrollment PCM. Not yet implemented — returns null so the
     * pipeline transparently falls back to general suppression. Wire the TFLite speaker-encoder
     * here; the result is persisted via [com.clearcall.core.Prefs.speakerEmbedding].
     */
    fun computeEmbedding(pcm16kMono: FloatArray): FloatArray? {
        // TODO(P4-real): TFLite speaker-encoder inference -> L2-normalized FloatArray(EMBEDDING_DIM)
        return null
    }

    fun isEnrolled(embedding: FloatArray?): Boolean = embedding != null && embedding.size == EMBEDDING_DIM
}
