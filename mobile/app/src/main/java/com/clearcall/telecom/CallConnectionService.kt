package com.clearcall.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.clearcall.call.CallManager

class CallConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras
        val connection = CallConnection(
            callId = extras?.getInt(TelecomHelper.EXTRA_CALL_ID) ?: -1,
            roomName = extras?.getString(TelecomHelper.EXTRA_ROOM_NAME) ?: "",
            peerId = extras?.getInt(TelecomHelper.EXTRA_PEER_ID) ?: -1,
            peerName = extras?.getString(TelecomHelper.EXTRA_PEER_NAME) ?: "Unknown",
            isIncoming = false,
        )
        connection.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(connection.peerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setDialing()
        CallManager.attachOutgoingConnection(connection)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection {
        val extras = request.extras
        val connection = CallConnection(
            callId = extras?.getInt(TelecomHelper.EXTRA_CALL_ID) ?: -1,
            roomName = extras?.getString(TelecomHelper.EXTRA_ROOM_NAME) ?: "",
            peerId = extras?.getInt(TelecomHelper.EXTRA_PEER_ID) ?: -1,
            peerName = extras?.getString(TelecomHelper.EXTRA_PEER_NAME) ?: "Unknown",
            isIncoming = true,
        )
        connection.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(connection.peerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()
        CallManager.attachIncomingConnection(connection)
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ) {
        CallManager.onConnectionFailed()
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ) {
        CallManager.onConnectionFailed()
    }
}
