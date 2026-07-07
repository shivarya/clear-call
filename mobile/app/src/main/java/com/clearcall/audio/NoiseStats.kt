package com.clearcall.audio

/**
 * Live, lock-free view of the noise-suppression pipeline for the debug overlay. Fields are
 * written from the realtime audio thread and read from the UI thread; all are `@Volatile`
 * and independently consistent (a slightly stale/torn snapshot in the overlay is harmless).
 */
class NoiseStats {
    @Volatile var engineName: String = "off"
    @Volatile var sampleRate: Int = 0
    @Volatile var numBands: Int = 0
    @Volatile var numFrames: Int = 0
    @Volatile var hopSize: Int = 0
    @Volatile var ready: Boolean = false
    @Volatile var bypassed: Boolean = false
    @Volatile var autoBypassed: Boolean = false // watchdog/error kill-switch tripped
    @Volatile var hopsProcessed: Long = 0
    @Volatile var lastHopMicros: Long = 0
    @Volatile var avgHopMicros: Long = 0
    @Volatile var p95HopMicros: Long = 0

    // Speaker-gate telemetry (engine "target-speaker" only; see SpeakerGate).
    @Volatile var gateOpen: Boolean = true
    @Volatile var gateVad: Boolean = false
    @Volatile var gateCosine: Float = 0f
    @Volatile var gateGainDb: Float = 0f

    fun snapshot(): NoiseStatsSnapshot = NoiseStatsSnapshot(
        engineName, sampleRate, numBands, numFrames, hopSize, ready, bypassed, autoBypassed,
        hopsProcessed, lastHopMicros, avgHopMicros, p95HopMicros,
        gateOpen, gateVad, gateCosine, gateGainDb,
    )
}

data class NoiseStatsSnapshot(
    val engineName: String,
    val sampleRate: Int,
    val numBands: Int,
    val numFrames: Int,
    val hopSize: Int,
    val ready: Boolean,
    val bypassed: Boolean,
    val autoBypassed: Boolean,
    val hopsProcessed: Long,
    val lastHopMicros: Long,
    val avgHopMicros: Long,
    val p95HopMicros: Long,
    val gateOpen: Boolean,
    val gateVad: Boolean,
    val gateCosine: Float,
    val gateGainDb: Float,
)
