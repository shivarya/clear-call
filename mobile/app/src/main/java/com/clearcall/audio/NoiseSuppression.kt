package com.clearcall.audio

import android.content.Context
import com.clearcall.core.Prefs
import io.livekit.android.audio.AudioProcessorInterface

/**
 * App-scoped owner of the noise-suppression pipeline. Creates the (expensive, model-loading)
 * [DfnNoiseProcessor] and its [CaptureProcessorBridge] once and reuses them across calls, so
 * only the first call of the app's lifetime pays the DFN3 model-load cost. The single active
 * bridge is exposed so the in-call debug overlay can flip the A/B bypass and read live stats.
 */
object NoiseSuppression {

    val stats = NoiseStats()

    private lateinit var appContext: Context
    private var processor: DfnNoiseProcessor? = null
    private var bridge: CaptureProcessorBridge? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The capture-post processor to hand LiveKit for a new call, or `null` when suppression
     * is disabled (LiveKit then skips the hook entirely). Safe to call before/without [init]
     * for the disabled case.
     */
    fun captureProcessorFor(prefs: Prefs): AudioProcessorInterface? {
        if (!prefs.noiseSuppressionEnabled || !::appContext.isInitialized) {
            stats.engineName = "off"
            return null
        }
        val proc = processor ?: DfnNoiseProcessor(appContext, prefs.attenuationLimitDb).also { processor = it }
        val br = bridge ?: CaptureProcessorBridge(proc, stats).also { bridge = it }
        br.setBypass(false)
        stats.engineName = proc.name
        return br
    }

    /** In-call A/B: `true` = pass raw mic through. No-op if suppression isn't active. */
    fun setBypass(bypass: Boolean) {
        bridge?.setBypass(bypass)
    }

    val isBypassed: Boolean get() = bridge?.isBypassed ?: false

    val isActive: Boolean get() = bridge != null
}
