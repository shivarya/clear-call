package com.clearcall.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Registers ClearCall as a self-managed calling app so Android's Telecom stack arbitrates
 * audio focus, routes Bluetooth headset buttons, and holds off cellular calls for us —
 * while WE own the incoming/outgoing UI (see [com.clearcall.telecom.CallConnection]).
 */
class TelecomHelper(private val context: Context) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    val phoneAccountHandle: PhoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, CallConnectionService::class.java),
        ACCOUNT_ID,
    )

    fun registerPhoneAccount() {
        val account = PhoneAccount.builder(phoneAccountHandle, "ClearCall")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        runCatching { telecomManager.registerPhoneAccount(account) }
            .onFailure { Log.e(TAG, "registerPhoneAccount failed", it) }
    }

    fun placeCall(callId: Int, roomName: String, peerId: Int, peerName: String) {
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            putInt(EXTRA_CALL_ID, callId)
            putString(EXTRA_ROOM_NAME, roomName)
            putInt(EXTRA_PEER_ID, peerId)
            putString(EXTRA_PEER_NAME, peerName)
        }
        val address = Uri.fromParts(SCHEME, "u$peerId", null)
        runCatching { telecomManager.placeCall(address, extras) }
            .onFailure { Log.e(TAG, "placeCall failed", it) }
    }

    fun addIncomingCall(callId: Int, roomName: String, peerId: Int, peerName: String) {
        val extras = Bundle().apply {
            putInt(EXTRA_CALL_ID, callId)
            putString(EXTRA_ROOM_NAME, roomName)
            putInt(EXTRA_PEER_ID, peerId)
            putString(EXTRA_PEER_NAME, peerName)
        }
        runCatching { telecomManager.addNewIncomingCall(phoneAccountHandle, extras) }
            .onFailure { Log.e(TAG, "addNewIncomingCall failed", it) }
    }

    companion object {
        private const val TAG = "TelecomHelper"
        private const val ACCOUNT_ID = "clearcall_self_managed"
        private const val SCHEME = "clearcall"

        const val EXTRA_CALL_ID = "com.clearcall.CALL_ID"
        const val EXTRA_ROOM_NAME = "com.clearcall.ROOM_NAME"
        const val EXTRA_PEER_ID = "com.clearcall.PEER_ID"
        const val EXTRA_PEER_NAME = "com.clearcall.PEER_NAME"
    }
}
