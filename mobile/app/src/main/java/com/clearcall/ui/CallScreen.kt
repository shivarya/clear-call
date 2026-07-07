package com.clearcall.ui

import android.media.AudioDeviceInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clearcall.BuildConfig
import com.clearcall.call.CallManager
import com.clearcall.call.CallPhase
import com.clearcall.call.CallState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(onCallEnded: () -> Unit) {
    val phase by CallState.phase.collectAsState()
    val info by CallState.current.collectAsState()
    val muted by CallState.muted.collectAsState()
    val phoneMicMode by CallState.phoneMicMode.collectAsState()
    val audioOutputs by CallState.audioOutputs.collectAsState()
    val selectedAudioId by CallState.selectedAudioOutputId.collectAsState()
    val error by CallState.errorMessage.collectAsState()

    var showAudioSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(phase) {
        if (phase == CallPhase.IDLE) onCallEnded()
    }

    Scaffold { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                info?.peerName ?: "Unknown",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(phaseLabel(phase), style = MaterialTheme.typography.bodyLarge)
            if (phoneMicMode) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Phone mic · earbuds audio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(64.dp))

            if (phase == CallPhase.RINGING_INCOMING) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    FilledIconButton(
                        onClick = { CallManager.declineCurrentCall() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Filled.CallEnd, contentDescription = "Decline") }
                    FilledIconButton(
                        onClick = { CallManager.answerCurrentCall() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Filled.Call, contentDescription = "Answer") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    if (phase == CallPhase.ACTIVE) {
                        FilledIconButton(
                            onClick = { CallManager.toggleMute() },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = if (muted) "Unmute" else "Mute",
                            )
                        }
                        if (!phoneMicMode && audioOutputs.isNotEmpty()) {
                            val selected = audioOutputs.firstOrNull { it.id == selectedAudioId }
                            val routed = selected?.type != null &&
                                selected.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                            FilledIconButton(
                                onClick = { showAudioSheet = true },
                                colors = if (routed) {
                                    IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)
                                } else {
                                    IconButtonDefaults.filledIconButtonColors()
                                },
                                modifier = Modifier.size(56.dp),
                            ) {
                                Icon(audioOutputIcon(selected?.type), contentDescription = "Audio output")
                            }
                        }
                    }
                    FilledIconButton(
                        onClick = { CallManager.hangup() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.size(64.dp),
                    ) { Icon(Icons.Filled.CallEnd, contentDescription = "Hang up") }
                }
            }

            if (BuildConfig.DEBUG && phase == CallPhase.ACTIVE) {
                Spacer(Modifier.height(32.dp))
                NoiseDebugOverlay()
            }
        }
    }

    if (showAudioSheet) {
        ModalBottomSheet(onDismissRequest = { showAudioSheet = false }, sheetState = sheetState) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "Audio output",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                audioOutputs.forEach { out ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = out.id == selectedAudioId,
                                onClick = {
                                    CallManager.selectAudioOutput(out.id)
                                    showAudioSheet = false
                                },
                            )
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(audioOutputIcon(out.type), contentDescription = null)
                        Text(out.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (out.id == selectedAudioId) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun audioOutputIcon(type: Int?) = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Icons.Filled.VolumeUp
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> Icons.Filled.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    -> Icons.Filled.Headset
    AudioDeviceInfo.TYPE_HEARING_AID -> Icons.Filled.Hearing
    else -> Icons.Filled.PhoneInTalk // earpiece / default
}

private fun phaseLabel(phase: CallPhase): String = when (phase) {
    CallPhase.IDLE -> ""
    CallPhase.DIALING -> "Calling…"
    CallPhase.RINGING_INCOMING -> "Incoming call"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.ACTIVE -> "Connected"
    CallPhase.ENDED -> "Call ended"
}
