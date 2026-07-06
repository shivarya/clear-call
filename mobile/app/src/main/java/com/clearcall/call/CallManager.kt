package com.clearcall.call

import android.content.Context
import android.telecom.DisconnectCause
import android.util.Log
import com.clearcall.audio.NoiseSuppression
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.clearcall.net.ApiException
import com.clearcall.push.IncomingCallNotification
import com.clearcall.service.OngoingCallService
import com.clearcall.telecom.CallConnection
import com.clearcall.telecom.TelecomHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single orchestrator for the whole call lifecycle. Every path — our own UI, Telecom
 * callbacks (Bluetooth button, Android Auto), and FCM ring/cancel/declined pushes —
 * funnels through these entry points, which are written to be idempotent so it never
 * matters who triggered them first.
 */
object CallManager {
    private const val TAG = "CallManager"

    private lateinit var appContext: Context
    private lateinit var prefs: Prefs
    private lateinit var apiClient: ApiClient
    private lateinit var telecomHelper: TelecomHelper
    private lateinit var ringtonePlayer: RingtonePlayer

    private var liveKit: LiveKitSessionManager? = null
    private var activeConnection: CallConnection? = null
    private var ringTimeoutJob: Job? = null
    private var liveKitEventsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = Prefs(appContext)
        apiClient = ApiClient(prefs)
        telecomHelper = TelecomHelper(appContext)
        ringtonePlayer = RingtonePlayer(appContext)
        telecomHelper.registerPhoneAccount()
        NoiseSuppression.init(appContext)
    }

    // ---- Outgoing ----

    fun placeOutgoingCall(peerId: Int, peerName: String) {
        if (CallState.phase.value != CallPhase.IDLE) return
        CallState.setError(null)
        scope.launch {
            try {
                val result = apiClient.createCall(peerId)
                CallState.setCurrent(CallInfo(result.callId, result.roomName, peerId, peerName, isIncoming = false))
                CallState.setPhase(CallPhase.DIALING)
                telecomHelper.placeCall(result.callId, result.roomName, peerId, peerName)
                connectLiveKit(result.livekitUrl, result.token)
                ringtonePlayer.startRingback()
                startRingTimeout(result.ringTimeoutSeconds)
            } catch (e: ApiException) {
                CallState.setError(e.message)
                CallState.reset()
            }
        }
    }

    /** Called by CallConnectionService right after Telecom creates the outgoing Connection. */
    fun attachOutgoingConnection(connection: CallConnection) {
        activeConnection = connection
    }

    private fun startRingTimeout(serverTimeoutSeconds: Int) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(serverTimeoutSeconds * 1000L + 3000) // small buffer past the server's own timeout
            if (CallState.phase.value == CallPhase.DIALING) {
                Log.i(TAG, "Ring timed out locally")
                endCurrentCall(reason = "timeout")
            }
        }
    }

    // ---- Incoming ----

    fun handleIncomingRing(callId: Int, roomName: String, callerId: Int, callerName: String) {
        if (CallState.phase.value != CallPhase.IDLE) {
            // Already on a call — the server's busy check should have prevented this, but
            // don't let a race double-ring.
            return
        }
        CallState.setCurrent(CallInfo(callId, roomName, callerId, callerName, isIncoming = true))
        CallState.setPhase(CallPhase.RINGING_INCOMING)
        telecomHelper.addIncomingCall(callId, roomName, callerId, callerName)
    }

    /** Called by CallConnectionService right after Telecom creates the incoming Connection. */
    fun attachIncomingConnection(connection: CallConnection) {
        activeConnection = connection
    }

    /** Telecom asks us to show incoming-call UI now (after addNewIncomingCall settles). */
    fun onShowIncomingUi() {
        ringtonePlayer.startIncomingRingtone()
        CallState.current.value?.let { IncomingCallNotification.show(appContext, it) }
    }

    fun answerCurrentCall() {
        val info = CallState.current.value ?: return
        if (CallState.phase.value != CallPhase.RINGING_INCOMING) return
        ringtonePlayer.stopAll()
        IncomingCallNotification.dismiss(appContext)
        activeConnection?.setActive()
        CallState.setPhase(CallPhase.CONNECTING)
        scope.launch {
            try {
                val result = apiClient.answerCall(info.callId)
                connectLiveKit(result.livekitUrl, result.token, advertiseAnswered = true)
                CallState.setPhase(CallPhase.ACTIVE)
                OngoingCallService.start(appContext)
            } catch (e: ApiException) {
                CallState.setError(e.message)
                teardownConnection(DisconnectCause.ERROR)
            }
        }
    }

    fun declineCurrentCall() {
        val info = CallState.current.value
        if (CallState.phase.value != CallPhase.RINGING_INCOMING) return
        ringtonePlayer.stopAll()
        IncomingCallNotification.dismiss(appContext)
        if (info != null) {
            scope.launch { runCatching { apiClient.declineCall(info.callId) } }
        }
        teardownConnection(DisconnectCause.REJECTED)
    }

    /** Caller canceled or timed out before we answered (FCM 'cancel' push). */
    fun handleRemoteCancel(callId: Int) {
        val info = CallState.current.value ?: return
        if (info.callId != callId || CallState.phase.value != CallPhase.RINGING_INCOMING) return
        ringtonePlayer.stopAll()
        IncomingCallNotification.dismiss(appContext)
        teardownConnection(DisconnectCause.CANCELED)
    }

    /** Callee declined our outgoing call (FCM 'declined' push). */
    fun handleRemoteDecline(callId: Int) {
        val info = CallState.current.value ?: return
        if (info.callId != callId) return
        teardownConnection(DisconnectCause.REJECTED)
    }

    // ---- Shared: hang up an active or in-progress call ----

    /** Single entry point for "end this call" regardless of who/what asked. Idempotent. */
    fun hangup() {
        if (CallState.phase.value == CallPhase.IDLE) return
        endCurrentCall(reason = "canceled")
    }

    private fun endCurrentCall(reason: String) {
        val info = CallState.current.value
        val wasActive = CallState.phase.value == CallPhase.ACTIVE
        if (info != null) {
            scope.launch {
                runCatching {
                    if (wasActive) apiClient.endCall(info.callId) else apiClient.cancelCall(info.callId, reason)
                }
            }
        }
        val cause = if (reason == "timeout") DisconnectCause.MISSED else DisconnectCause.LOCAL
        teardownConnection(cause)
    }

    fun onConnectionFailed() {
        CallState.setError("Could not start the call")
        CallState.reset()
    }

    fun toggleMute() {
        val newMuted = !CallState.muted.value
        liveKit?.setMuted(newMuted)
        CallState.setMuted(newMuted)
    }

    // ---- Internals ----

    private fun connectLiveKit(url: String, token: String, advertiseAnswered: Boolean = false) {
        val session = LiveKitSessionManager(appContext)
        liveKit = session
        liveKitEventsJob?.cancel()
        liveKitEventsJob = scope.launch {
            launch { session.remoteJoined.collect { onRemoteJoined() } }
            launch { session.remoteLeft.collect { hangup() } }
        }
        val nsProcessor = NoiseSuppression.captureProcessorFor(prefs)
        scope.launch {
            try {
                session.connect(url, token, nsProcessor)
                // Callee: advertise acceptance via a participant attribute (cross-platform answer
                // signal for a future iOS caller; Android callers use room-join, see LiveKitSessionManager).
                if (advertiseAnswered) session.advertiseAnswered()
            } catch (e: Exception) {
                Log.e(TAG, "LiveKit connect failed", e)
                CallState.setError("Connection failed: ${e.message}")
                hangup()
            }
        }
    }

    private fun onRemoteJoined() {
        if (CallState.phase.value == CallPhase.DIALING) {
            ringtonePlayer.stopAll()
            ringTimeoutJob?.cancel()
            activeConnection?.setActive()
            CallState.setPhase(CallPhase.ACTIVE)
            OngoingCallService.start(appContext)
        }
    }

    /** Tears down the Telecom connection, LiveKit session, and resets state. Idempotent. */
    private fun teardownConnection(cause: Int) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        liveKitEventsJob?.cancel()
        liveKitEventsJob = null
        ringtonePlayer.stopAll()
        liveKit?.release()
        liveKit = null
        activeConnection?.let {
            runCatching {
                it.setDisconnected(DisconnectCause(cause))
                it.destroy()
            }
        }
        activeConnection = null
        OngoingCallService.stop(appContext)
        CallState.reset()
    }
}
