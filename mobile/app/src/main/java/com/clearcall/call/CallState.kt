package com.clearcall.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallPhase { IDLE, DIALING, RINGING_INCOMING, CONNECTING, ACTIVE, ENDED }

data class CallInfo(
    val callId: Int,
    val roomName: String,
    val peerId: Int,
    val peerName: String,
    val isIncoming: Boolean,
)

/**
 * Process-wide call state, published by [CallManager] and observed by the UI, the Telecom
 * layer, and the ongoing-call notification alike — mirrors clear-mic-router's RouterState.
 */
object CallState {
    private val _phase = MutableStateFlow(CallPhase.IDLE)
    val phase: StateFlow<CallPhase> = _phase.asStateFlow()

    private val _current = MutableStateFlow<CallInfo?>(null)
    val current: StateFlow<CallInfo?> = _current.asStateFlow()

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setPhase(phase: CallPhase) {
        _phase.value = phase
    }

    fun setCurrent(info: CallInfo?) {
        _current.value = info
    }

    fun setMuted(value: Boolean) {
        _muted.value = value
    }

    fun setSpeakerOn(value: Boolean) {
        _speakerOn.value = value
    }

    fun setError(message: String?) {
        _errorMessage.value = message
    }

    fun reset() {
        _phase.value = CallPhase.IDLE
        _current.value = null
        _muted.value = false
        _speakerOn.value = false
    }
}
