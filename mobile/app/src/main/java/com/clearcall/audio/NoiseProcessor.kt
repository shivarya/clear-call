package com.clearcall.audio

/**
 * A fixed-size mono audio denoiser plugged into the LiveKit capture path.
 *
 * Threading contract: [initialize] and [release] run on a normal thread, but [processHop]
 * and [reset] run on the realtime WebRTC audio thread — implementations must **not**
 * allocate, lock, or block there. [hopSize] may return `<= 0` while an engine is still
 * warming up (e.g. an async model load); the bridge passes audio through untouched until
 * it turns positive.
 */
interface NoiseProcessor {
    /** Short identifier for logs/overlay, e.g. "dfn3" or "off". */
    val name: String

    /** Exact number of mono samples each [processHop] call consumes/produces, or `<= 0` if not ready. */
    val hopSize: Int

    /** Called when the capture format is known (once per session, before any [processHop]). */
    fun initialize(sampleRateHz: Int, numChannels: Int)

    /**
     * Denoise exactly [hopSize] mono samples in place. Samples are WebRTC's float scale
     * (roughly the int16 range, i.e. ~-32768..32767), not normalized [-1,1].
     */
    fun processHop(hop: FloatArray)

    /** Drop any accumulated state between calls (not a full teardown). */
    fun reset()

    /** Release native resources. After this the processor must not be used again. */
    fun release()
}
