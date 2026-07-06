package com.clearcall.audio

/**
 * No-op [NoiseProcessor] — leaves audio untouched. Used as the A/B baseline and as a safe
 * stand-in. (When suppression is simply "off" we install no processor at all rather than
 * this, so LiveKit skips the capture hook entirely.)
 */
class PassthroughProcessor : NoiseProcessor {
    override val name = "passthrough"
    private var hop = 480

    override val hopSize: Int get() = hop

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        hop = (sampleRateHz / 100).coerceAtLeast(1) // 10 ms
    }

    override fun processHop(hop: FloatArray) { /* intentional no-op */ }
    override fun reset() {}
    override fun release() {}
}
