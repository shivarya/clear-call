package com.clearcall

import android.app.Application
import com.clearcall.audio.SpeakerEncoder
import com.clearcall.audio.VoiceEnrollment
import com.clearcall.auth.registerDeviceForPush
import com.clearcall.call.CallManager
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClearCallApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Must run before anything else — an FCM ring push can cold-start this process with
        // no Activity involved, and CallMessagingService needs CallManager ready immediately.
        CallManager.init(this)
        SpeakerEncoder.init(this)
        // If a newer bundled encoder made the stored voice embedding stale, silently rebuild it
        // from the kept enrollment WAV (no-op in the common case — just two pref reads).
        appScope.launch { VoiceEnrollment.recomputeIfStale(this@ClearCallApp, Prefs(this@ClearCallApp)) }
        // Re-register this device for push on every start (idempotent) so a sign-in-time
        // registration that failed transiently self-heals — otherwise the user stays uncallable.
        appScope.launch {
            val prefs = Prefs(this@ClearCallApp)
            registerDeviceForPush(prefs, ApiClient(prefs))
        }
        if (BuildConfig.DEBUG) {
            appScope.launch { com.clearcall.audio.MlSelfTest.run(this@ClearCallApp) }
        }
    }
}
