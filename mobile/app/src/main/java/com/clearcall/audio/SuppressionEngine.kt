package com.clearcall.audio

/**
 * Which noise-suppression engine runs on the mic uplink. This is the seam for Tier B: adding
 * the target-speaker model later is a new [NoiseProcessor] impl + this enum entry, with no
 * change to the bridge or call plumbing.
 *
 *  - [OFF]            no processing (LiveKit skips the capture hook)
 *  - [DFN3]           DeepFilterNet3 general noise suppression (Tier A, shipping)
 *  - [TARGET_SPEAKER] target-speaker extraction (Tier B) — keep only the voice matching a
 *                     provided reference sample (any speaker, not just the phone's owner),
 *                     remove everyone/everything else. Falls back to DFN3 until the model ships.
 */
enum class SuppressionEngine {
    OFF,
    DFN3,
    TARGET_SPEAKER,
    ;

    companion object {
        fun fromStorage(value: String?): SuppressionEngine = when (value) {
            null -> DFN3
            "PERSONALIZED" -> TARGET_SPEAKER // legacy name pre-rename
            else -> entries.firstOrNull { it.name == value } ?: DFN3
        }
    }
}
