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

    fun snapshot(): NoiseStatsSnapshot = NoiseStatsSnapshot(
        engineName, sampleRate, numBands, numFrames, hopSize, ready, bypassed, autoBypassed,
        hopsProcessed, lastHopMicros, avgHopMicros, p95HopMicros,
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
)
