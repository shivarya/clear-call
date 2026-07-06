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
    private var processor: NoiseProcessor? = null
    private var bridge: CaptureProcessorBridge? = null
    private var currentEngine: SuppressionEngine = SuppressionEngine.OFF

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The capture-post processor to hand LiveKit for a new call, or `null` when suppression
     * is disabled (LiveKit then skips the hook entirely). Rebuilds the processor if the selected
     * engine changed since the last call. Safe to call before/without [init] for the disabled case.
     */
    fun captureProcessorFor(prefs: Prefs): AudioProcessorInterface? {
        val engine = if (::appContext.isInitialized) prefs.suppressionEngine else SuppressionEngine.OFF
        if (engine == SuppressionEngine.OFF) {
            stats.engineName = "off"
            return null
        }
        if (processor == null || currentEngine != engine) {
            processor?.release()
            processor = when (engine) {
                SuppressionEngine.PERSONALIZED -> SpeakerConditionedProcessor(appContext, prefs.attenuationLimitDb).apply {
                    setSpeakerEmbedding(prefs.speakerEmbedding)
                }
                else -> DfnNoiseProcessor(appContext, prefs.attenuationLimitDb)
            }
            bridge = CaptureProcessorBridge(processor!!, stats)
            currentEngine = engine
        }
        val br = bridge!!
        br.setBypass(false)
        stats.engineName = processor!!.name
        return br
    }

    /** In-call A/B: `true` = pass raw mic through. No-op if suppression isn't active. */
    fun setBypass(bypass: Boolean) {
        bridge?.setBypass(bypass)
    }

    val isBypassed: Boolean get() = bridge?.isBypassed ?: false

    val isActive: Boolean get() = bridge != null
}
