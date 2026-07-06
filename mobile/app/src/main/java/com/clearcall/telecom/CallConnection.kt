package com.clearcall.telecom

import android.telecom.Connection
import com.clearcall.call.CallManager

/**
 * Self-managed [Connection] for one call. The `on*` overrides are what Telecom invokes on
 * OUR behalf when something outside our own UI wants to change the call (a Bluetooth
 * headset button, Android Auto, etc.) — they delegate to the same [CallManager] entry
 * points our own in-app buttons call directly, so there is exactly one place ("CallManager")
 * that owns the actual state transition, regardless of who asked for it.
 */
class CallConnection(
    val callId: Int,
    val roomName: String,
    val peerId: Int,
    val peerName: String,
    val isIncoming: Boolean,
) : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    override fun onShowIncomingCallUi() {
        CallManager.onShowIncomingUi()
    }

    override fun onAnswer() {
        CallManager.answerCurrentCall()
    }

    override fun onReject() {
        CallManager.declineCurrentCall()
    }

    override fun onDisconnect() {
        CallManager.hangup()
    }

    override fun onAbort() {
        CallManager.hangup()
    }
}
