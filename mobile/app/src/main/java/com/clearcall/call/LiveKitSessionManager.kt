package com.clearcall.call

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Wraps a LiveKit [Room] for the duration of one call. Per the backend's signaling model
 * (server/controllers/callsController.php), "answered" is detected here — not via a push —
 * as the moment the other participant's track appears in the room: the caller sits in the
 * room from the instant it's created (ringback), and [remoteJoined] fires the instant the
 * callee's `POST /calls/{id}/answer` lets them join too.
 */
class LiveKitSessionManager(private val context: Context) {

    private var room: Room? = null
    private var eventsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _remoteJoined = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val remoteJoined: SharedFlow<Unit> = _remoteJoined.asSharedFlow()

    private val _remoteLeft = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val remoteLeft: SharedFlow<Unit> = _remoteLeft.asSharedFlow()

    private val _disconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val disconnected: SharedFlow<Unit> = _disconnected.asSharedFlow()

    suspend fun connect(url: String, token: String, capturePostProcessor: AudioProcessorInterface? = null) {
        val overrides = if (capturePostProcessor != null) {
            LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioProcessorOptions = AudioProcessorOptions(capturePostProcessor = capturePostProcessor),
                ),
            )
        } else {
            LiveKitOverrides()
        }
        val newRoom = LiveKit.create(context.applicationContext, overrides = overrides)
        room = newRoom
        eventsJob = scope.launch {
            newRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> _remoteJoined.tryEmit(Unit)
                    is RoomEvent.ParticipantDisconnected -> _remoteLeft.tryEmit(Unit)
                    is RoomEvent.Disconnected -> _disconnected.tryEmit(Unit)
                    else -> {}
                }
            }
        }
        newRoom.connect(url, token)
        newRoom.localParticipant.setMicrophoneEnabled(true)
    }

    fun setMuted(muted: Boolean) {
        val localParticipant = room?.localParticipant ?: return
        scope.launch { localParticipant.setMicrophoneEnabled(!muted) }
    }

    fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        room?.disconnect()
        room = null
    }

    /** Call when the manager itself is being torn down (not just one call ending). */
    fun release() {
        disconnect()
        scope.cancel()
    }
}
