package com.clearcall.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.util.Log

/** Incoming ringtone (the user's chosen ringtone, looped) and outgoing ringback (standard tone). */
class RingtonePlayer(private val context: Context) {

    private var ringtone: Ringtone? = null
    private var toneGenerator: ToneGenerator? = null

    fun startIncomingRingtone() {
        stopAll()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }.onFailure { Log.w(TAG, "startIncomingRingtone failed", it) }.getOrNull()
    }

    /** Standard telephony ringback ("brr... brr...") heard by the caller while it rings. */
    fun startRingback() {
        stopAll()
        toneGenerator = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.onFailure { Log.w(TAG, "startRingback failed", it) }.getOrNull()
    }

    fun stopAll() {
        ringtone?.stop()
        ringtone = null
        toneGenerator?.apply {
            runCatching { stopTone() }
            release()
        }
        toneGenerator = null
    }

    companion object {
        private const val TAG = "RingtonePlayer"
    }
}
