package com.clearcall.push

import android.os.Build
import com.clearcall.call.CallManager
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Data-only pushes for call signaling (ring/cancel/declined) — see
 * server/controllers/callsController.php. [com.clearcall.ClearCallApp.onCreate] always runs
 * before this, even on a cold start woken purely by FCM, so [CallManager] is guaranteed ready.
 */
class CallMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val prefs = Prefs(this)
        if (!prefs.isSignedIn) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ApiClient(prefs).registerDevice(token, Build.MODEL ?: "Android device") }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "ring" -> {
                val callId = data["callId"]?.toIntOrNull() ?: return
                val roomName = data["roomName"] ?: return
                val callerId = data["callerId"]?.toIntOrNull() ?: return
                val callerName = data["callerName"] ?: "Unknown"
                CallManager.handleIncomingRing(callId, roomName, callerId, callerName)
            }
            "cancel" -> data["callId"]?.toIntOrNull()?.let { CallManager.handleRemoteCancel(it) }
            "declined" -> data["callId"]?.toIntOrNull()?.let { CallManager.handleRemoteDecline(it) }
        }
    }
}
