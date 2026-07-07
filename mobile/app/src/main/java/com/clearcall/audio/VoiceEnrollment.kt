package com.clearcall.audio

import android.content.Context
import android.util.Log
import com.clearcall.core.Prefs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Storage + quality-gate logic for the enrolled "voice to keep". Everything stays on-device:
 * the raw sample WAV lives in app-private storage (so embeddings can be recomputed after an
 * encoder upgrade without re-enrollment) and the d-vector lives in [Prefs].
 */
object VoiceEnrollment {
    private const val TAG = "VoiceEnrollment"

    const val TARGET_SECONDS = 20
    const val MIN_VOICED_SECONDS = 12

    // Quality-gate tunables.
    private const val VOICED_FRAME_RMS = 0.01f // 30 ms frame counts as voiced above this
    private const val MAX_CLIPPED_RATIO = 0.01f
    private const val MIN_HALVES_COSINE = 0.70f

    fun wavFile(context: Context): File =
        File(File(context.filesDir, "voice_enrollment"), "target_voice.wav")

    data class QualityResult(
        val ok: Boolean,
        val voicedSeconds: Int,
        val message: String?,
    )

    /**
     * Cheap-first quality gate: enough *voiced* audio, not clipped, and self-consistent
     * (the embeddings of the two halves must agree — catches noisy/multi-talker samples,
     * which would poison the gate's reference).
     */
    suspend fun checkQuality(samples: FloatArray): QualityResult {
        val frame = SpeakerEncoder.SAMPLE_RATE * 30 / 1000
        var voicedFrames = 0
        var clipped = 0
        var i = 0
        while (i + frame <= samples.size) {
            var sumSq = 0f
            for (j in i until i + frame) {
                val s = samples[j]
                sumSq += s * s
                if (abs(s) >= 0.985f) clipped++
            }
            if (sqrt(sumSq / frame) >= VOICED_FRAME_RMS) voicedFrames++
            i += frame
        }
        val voicedSeconds = voicedFrames * 30 / 1000
        if (voicedSeconds < MIN_VOICED_SECONDS) {
            return QualityResult(false, voicedSeconds, "Only ${voicedSeconds}s of speech captured — keep talking until the ring completes.")
        }
        if (samples.isNotEmpty() && clipped.toFloat() / samples.size > MAX_CLIPPED_RATIO) {
            return QualityResult(false, voicedSeconds, "The recording is distorted — hold the phone a little further away and try again.")
        }
        val half = samples.size / 2
        val embA = SpeakerEncoder.embed(samples.copyOfRange(0, half))
        val embB = SpeakerEncoder.embed(samples.copyOfRange(half, samples.size))
        if (embA == null || embB == null) {
            return QualityResult(false, voicedSeconds, "Couldn't analyze the recording — try again.")
        }
        val consistency = SpeakerEncoder.cosine(embA, embB)
        Log.i(TAG, "Quality gate: voiced=${voicedSeconds}s clipped=$clipped halves-cosine=$consistency")
        if (consistency < MIN_HALVES_COSINE) {
            return QualityResult(false, voicedSeconds, "Too noisy — try again somewhere quiet, with only one person speaking.")
        }
        return QualityResult(true, voicedSeconds, null)
    }

    /** Embed the full sample, persist name + d-vector + encoder version, and keep the WAV. */
    suspend fun save(context: Context, prefs: Prefs, name: String, samples: FloatArray): Boolean {
        val embedding = SpeakerEncoder.embed(samples) ?: return false
        val file = wavFile(context)
        runCatching { writeWav(file, samples) }
            .onFailure { Log.w(TAG, "Couldn't keep enrollment WAV (recompute-on-upgrade disabled)", it) }
        prefs.targetVoiceEmbedding = embedding
        prefs.targetVoiceName = name
        prefs.targetVoiceModelVersion = SpeakerEncoder.VERSION
        Log.i(TAG, "Enrolled \"$name\" (dim=${embedding.size}, ${samples.size / SpeakerEncoder.SAMPLE_RATE}s sample)")
        return true
    }

    /**
     * If the stored embedding was produced by an older encoder and the enrollment WAV is still
     * around, silently recompute it — users never re-enroll because we shipped a better model.
     */
    suspend fun recomputeIfStale(context: Context, prefs: Prefs) {
        if (prefs.targetVoiceEmbedding == null) return
        if (prefs.targetVoiceModelVersion == SpeakerEncoder.VERSION) return
        val file = wavFile(context)
        if (!file.exists()) return
        val samples = runCatching { readWav(file) }.getOrNull() ?: return
        val embedding = SpeakerEncoder.embed(samples) ?: return
        prefs.targetVoiceEmbedding = embedding
        prefs.targetVoiceModelVersion = SpeakerEncoder.VERSION
        Log.i(TAG, "Recomputed stored embedding for encoder ${SpeakerEncoder.VERSION}")
    }

    fun clear(context: Context, prefs: Prefs) {
        prefs.clearTargetVoice()
        wavFile(context).delete()
    }

    // ---- Minimal 16-bit PCM mono WAV I/O ----

    private fun writeWav(file: File, samples: FloatArray) {
        file.parentFile?.mkdirs()
        val dataSize = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val rate = SpeakerEncoder.SAMPLE_RATE
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buf.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        file.writeBytes(buf.array())
    }

    private fun readWav(file: File): FloatArray? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() <= 44) return null
            val header = ByteArray(44)
            raf.readFully(header)
            val dataSize = (raf.length() - 44).toInt()
            val bytes = ByteArray(dataSize)
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(dataSize / 2)
            for (i in out.indices) out[i] = bb.short / 32768f
            return out
        }
    }
}
