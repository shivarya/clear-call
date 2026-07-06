package com.clearcall.audio

import android.content.Context
import android.util.Log
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * [NoiseProcessor] backed by DeepFilterNet3 (`io.github.kaleyravideo:android-deepfilternet`).
 *
 * DeepFilterNet3 is a **48 kHz** model: its `processFrame` consumes a fixed `frameLength`
 * (observed 960 = 20 ms @ 48 kHz) of float samples in place. WebRTC's capture rate, however,
 * varies by device (16 kHz was observed on the emulator, real hardware is often 48 kHz). So
 * this processor resamples the capture-rate hop up to 48 kHz for the model and back down
 * again, using an integer up/down factor. Rates that don't divide 48 kHz evenly (e.g. 44.1 k)
 * can't use an integer factor, so the processor reports `hopSize = -1` and the bridge keeps
 * the audio untouched (safe bypass) rather than degrade it.
 *
 * The native model loads asynchronously, so [hopSize] stays `-1` until the load completes.
 * [initialize] is idempotent — the native state is created once and reused across calls.
 */
class DfnNoiseProcessor(
    context: Context,
    private val attenuationLimitDb: Float,
) : NoiseProcessor {

    override val name = "dfn3"
    private val appContext = context.applicationContext

    private var dfn: NativeDeepFilterNet? = null
    private var captureRate = 48000

    // Set once the model loads and the capture rate is known:
    @Volatile private var captureHop = -1 // samples per processHop at the capture rate
    private var upFactor = 1              // captureRate * upFactor == 48000
    private var modelFrame = 0            // native frameLength (48 kHz samples)
    private var dfnBuffer: ByteBuffer? = null
    private var dfnFloats: FloatBuffer? = null
    private var up = FloatArray(0)        // 48 kHz scratch fed to the model

    override val hopSize: Int get() = captureHop

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        captureRate = sampleRateHz
        if (dfn != null) {
            configureForRate() // rate may have changed since the last call
            return
        }
        Log.i(TAG, "Initializing DeepFilterNet3 (rate=$sampleRateHz ch=$numChannels atten=${attenuationLimitDb}dB)")
        val net = NativeDeepFilterNet(appContext, attenuationLimitDb)
        dfn = net
        net.onModelLoaded { loaded ->
            modelFrame = loaded.frameLength.toInt()
            loaded.setAttenuationLimit(attenuationLimitDb)
            if (modelFrame > 0) {
                val buf = ByteBuffer.allocateDirect(modelFrame * 4).order(ByteOrder.nativeOrder())
                dfnBuffer = buf
                dfnFloats = buf.asFloatBuffer()
                up = FloatArray(modelFrame)
                Log.i(TAG, "DeepFilterNet3 model loaded: frameLength=$modelFrame samples (48 kHz)")
                configureForRate()
            } else {
                Log.w(TAG, "DeepFilterNet3 reported non-positive frameLength=$modelFrame; staying bypassed")
            }
        }
    }

    private fun configureForRate() {
        if (modelFrame <= 0) return
        if (captureRate <= 0 || MODEL_RATE % captureRate != 0) {
            Log.w(TAG, "Capture rate ${captureRate}Hz doesn't divide ${MODEL_RATE}Hz evenly — bypassing DFN3")
            captureHop = -1
            return
        }
        upFactor = MODEL_RATE / captureRate
        if (modelFrame % upFactor != 0) {
            Log.w(TAG, "Model frame $modelFrame not divisible by upFactor $upFactor — bypassing DFN3")
            captureHop = -1
            return
        }
        captureHop = modelFrame / upFactor // e.g. 960/3 = 320 samples per 20 ms at 16 kHz
        Log.i(TAG, "DFN3 configured: captureRate=$captureRate upFactor=$upFactor captureHop=$captureHop modelFrame=$modelFrame")
    }

    override fun processHop(hop: FloatArray) {
        val net = dfn ?: return
        val floats = dfnFloats ?: return
        val buf = dfnBuffer ?: return
        val n = captureHop
        if (n <= 0) return

        if (upFactor == 1) {
            // Native 48 kHz path — feed straight through.
            floats.clear()
            floats.put(hop, 0, n)
        } else {
            // Upsample the capture-rate hop to 48 kHz (linear interpolation) into `up`.
            upsample(hop, n, up, modelFrame, upFactor)
            floats.clear()
            floats.put(up, 0, modelFrame)
        }

        buf.rewind()
        net.processFrame(buf) // denoises in place at 48 kHz

        if (upFactor == 1) {
            floats.rewind()
            floats.get(hop, 0, n)
        } else {
            floats.rewind()
            floats.get(up, 0, modelFrame)
            downsample(up, modelFrame, hop, n, upFactor) // decimate 48 kHz -> capture rate
        }
    }

    /** Live-tune the attenuation limit (higher dB = more aggressive noise removal). */
    fun setAttenuationLimit(db: Float) {
        dfn?.setAttenuationLimit(db)
    }

    override fun reset() { /* native keeps its own overlap-add ring; nothing per-hop to clear */ }

    override fun release() {
        dfn?.release()
        dfn = null
        dfnBuffer = null
        dfnFloats = null
        captureHop = -1
    }

    companion object {
        private const val TAG = "DfnNoise"
        private const val MODEL_RATE = 48000

        /** Linear upsample of [inLen] samples by integer [factor] into [outLen] == inLen*factor. */
        private fun upsample(input: FloatArray, inLen: Int, output: FloatArray, outLen: Int, factor: Int) {
            var o = 0
            for (i in 0 until inLen) {
                val cur = input[i]
                val next = if (i + 1 < inLen) input[i + 1] else cur
                for (f in 0 until factor) {
                    val t = f.toFloat() / factor
                    output[o++] = cur + (next - cur) * t
                    if (o >= outLen) return
                }
            }
        }

        /** Decimate [inLen] samples by integer [factor] into [outLen] == inLen/factor (take every factor-th). */
        private fun downsample(input: FloatArray, inLen: Int, output: FloatArray, outLen: Int, factor: Int) {
            var i = 0
            for (o in 0 until outLen) {
                output[o] = input[i]
                i += factor
                if (i >= inLen) i = inLen - 1
            }
        }
    }
}
