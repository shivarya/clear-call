package com.clearcall.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Raw-PCM microphone capture for voice enrollment: 16 kHz mono float [-1, 1], exactly what
 * [SpeakerEncoder.embed] wants. Uses VOICE_RECOGNITION as the source — it bypasses the device's
 * own AGC/NS processing, giving the encoder the most faithful picture of the voice.
 *
 * Not reusable for calls (LiveKit owns that capture path); this exists only because enrollment
 * happens outside any call.
 */
class EnrollmentRecorder {

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    /** Rolling RMS of the last read chunk, in [0, 1] — drives the UI level meter. */
    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<FloatArray>()

    /** Requires RECORD_AUDIO to be granted (the screen checks before calling). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SpeakerEncoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SpeakerEncoder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, SpeakerEncoder.SAMPLE_RATE), // >= 0.5 s of headroom
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        synchronized(chunks) { chunks.clear() }
        _elapsedSeconds.value = 0
        _rmsLevel.value = 0f
        record = rec
        recording = true
        rec.startRecording()

        thread = Thread {
            val buf = ShortArray(CHUNK_SAMPLES)
            var totalSamples = 0L
            while (recording && totalSamples < MAX_SECONDS.toLong() * SpeakerEncoder.SAMPLE_RATE) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val floats = FloatArray(n)
                var sumSq = 0f
                for (i in 0 until n) {
                    val f = buf[i] / 32768f
                    floats[i] = f
                    sumSq += f * f
                }
                synchronized(chunks) { chunks.add(floats) }
                totalSamples += n
                _rmsLevel.value = sqrt(sumSq / n)
                _elapsedSeconds.value = (totalSamples / SpeakerEncoder.SAMPLE_RATE).toInt()
            }
        }.also { it.name = "EnrollmentRecorder"; it.start() }
        return true
    }

    /** Stop and return everything captured (16 kHz mono floats), or empty if nothing usable. */
    fun stop(): FloatArray {
        recording = false
        thread?.join(1000)
        thread = null
        record?.runCatching { stop() }
        record?.release()
        record = null
        _rmsLevel.value = 0f
        val all = synchronized(chunks) { chunks.toList() }
        val total = all.sumOf { it.size }
        val out = FloatArray(total)
        var pos = 0
        for (c in all) {
            c.copyInto(out, pos)
            pos += c.size
        }
        Log.i(TAG, "Enrollment capture: ${total / SpeakerEncoder.SAMPLE_RATE}s ($total samples)")
        return out
    }

    /** Stop and discard (user backed out mid-recording). */
    fun cancel() {
        recording = false
        thread?.join(500)
        thread = null
        record?.runCatching { stop() }
        record?.release()
        record = null
        synchronized(chunks) { chunks.clear() }
        _rmsLevel.value = 0f
        _elapsedSeconds.value = 0
    }

    companion object {
        private const val TAG = "EnrollmentRecorder"
        private const val CHUNK_SAMPLES = 1600 // 100 ms @ 16 kHz
        const val MAX_SECONDS = 60
    }
}
