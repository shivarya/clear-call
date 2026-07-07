package com.clearcall.audio

import android.content.Context
import android.util.Log
import kotlin.math.exp

/**
 * Tier B "Isolate a voice" — v1: DFN3 noise removal + an enrolled-speaker **gate**.
 *
 * Goal (like Teams' voice isolation, but target-agnostic): keep the voice that matches a
 * **provided reference sample** — the enrolled d-vector [setTargetEmbedding], any speaker —
 * and quiet everyone else. v1 does this by *gating in time*, entirely with existing on-device
 * models and zero training: [DfnNoiseProcessor] removes non-speech noise (fullband, unchanged),
 * then [SpeakerGate] decides a few times per second whether the current talker is the target
 * and this class ramps the uplink gain toward open (0 dB) or closed (≈ −35 dB) accordingly.
 *
 * Honest v1 limits: an interferer's first ~half-second leaks before the gate closes, and
 * simultaneous overlap is not separated (while the target talks, a background talker passes at
 * DFN3 level). A trained target-speaker *separation* model can later replace the gate in this
 * same seam (see docs/VOICE_ISOLATION_TIER_B_PLAN.md). All decisions run off the realtime
 * thread; [processHop] only feeds the analysis ring and applies a smoothed gain — with no
 * target enrolled (or on any gate failure) it behaves exactly like plain DFN3.
 */
class SpeakerConditionedProcessor(
    context: Context,
    attenuationLimitDb: Float,
) : NoiseProcessor {

    override val name = "target-speaker"

    private val fallback = DfnNoiseProcessor(context, attenuationLimitDb)
    private val gate = SpeakerGate(context)

    @Volatile private var targetEmbedding: FloatArray? = null

    // Per-sample exponential gain smoothing (attack = opening, release = closing); coefficients
    // depend on the capture rate, set in initialize(). Gain is scale-agnostic (int16-range floats).
    private var currentGain = 1f
    private var attackAlpha = 0.001f
    private var releaseAlpha = 0.0001f

    /**
     * Set the d-vector of the voice to keep (from the enrolled reference sample). Null means
     * "no target selected" → the gate stays open and this runs as plain general suppression.
     */
    fun setTargetEmbedding(embedding: FloatArray?) {
        targetEmbedding = embedding
        gate.setTarget(embedding)
        Log.i(TAG, if (embedding != null) {
            "Target voice set (dim=${embedding.size}) — speaker gate armed"
        } else {
            "No target voice — general suppression only"
        })
    }

    override val hopSize: Int get() = fallback.hopSize

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        fallback.initialize(sampleRateHz, numChannels)
        gate.configure(sampleRateHz)
        attackAlpha = alphaFor(ATTACK_SECONDS, sampleRateHz)
        releaseAlpha = alphaFor(RELEASE_SECONDS, sampleRateHz)
        currentGain = 1f
    }

    override fun processHop(hop: FloatArray) {
        fallback.processHop(hop)
        if (targetEmbedding == null) return
        gate.feed(hop)
        val target = gate.targetGainLinear
        var g = currentGain
        if (g == target) {
            if (target < 1f) applyConstant(hop, target)
            return
        }
        val alpha = if (target > g) attackAlpha else releaseAlpha
        for (i in hop.indices) {
            g += alpha * (target - g)
            hop[i] *= g
        }
        // Snap when close enough so the steady state takes the cheap path above.
        if (kotlin.math.abs(target - g) < 0.001f) g = target
        currentGain = g
        NoiseSuppression.stats.gateGainDb = lin2db(g)
    }

    private fun applyConstant(hop: FloatArray, gain: Float) {
        for (i in hop.indices) hop[i] *= gain
    }

    override fun reset() {
        fallback.reset()
        gate.reset()
        currentGain = 1f
    }

    override fun release() {
        fallback.release()
        gate.close()
    }

    private fun alphaFor(tauSeconds: Float, sampleRate: Int): Float =
        1f - exp(-1f / (tauSeconds * sampleRate))

    private fun lin2db(lin: Float): Float =
        if (lin <= 0f) -120f else (20.0 * Math.log10(lin.toDouble())).toFloat()

    companion object {
        private const val TAG = "TargetSpeaker"
        private const val ATTACK_SECONDS = 0.020f // opening: fast, don't clip the target's words
        private const val RELEASE_SECONDS = 0.250f // closing: gentle, no pumping
    }
}
