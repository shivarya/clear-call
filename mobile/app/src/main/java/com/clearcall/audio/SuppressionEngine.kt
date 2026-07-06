package com.clearcall.audio

/**
 * Which noise-suppression engine runs on the mic uplink. This is the seam for Tier B: adding
 * the personalized "only my voice" model later is a new [NoiseProcessor] impl + this enum
 * entry, with no change to the bridge or call plumbing.
 *
 *  - [OFF]          no processing (LiveKit skips the capture hook)
 *  - [DFN3]         DeepFilterNet3 general noise suppression (Tier A, shipping)
 *  - [PERSONALIZED] speaker-conditioned "only my voice" (Tier B) — falls back to DFN3 until
 *                   the user has enrolled and a personalized model ships
 */
enum class SuppressionEngine {
    OFF,
    DFN3,
    PERSONALIZED,
    ;

    companion object {
        fun fromStorage(value: String?): SuppressionEngine =
            entries.firstOrNull { it.name == value } ?: DFN3
    }
}
