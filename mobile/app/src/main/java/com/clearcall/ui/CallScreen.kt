package com.clearcall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clearcall.BuildConfig
import com.clearcall.call.CallManager
import com.clearcall.call.CallPhase
import com.clearcall.call.CallState

@Composable
fun CallScreen(onCallEnded: () -> Unit) {
    val phase by CallState.phase.collectAsState()
    val info by CallState.current.collectAsState()
    val muted by CallState.muted.collectAsState()
    val error by CallState.errorMessage.collectAsState()

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
}

private fun phaseLabel(phase: CallPhase): String = when (phase) {
    CallPhase.IDLE -> ""
    CallPhase.DIALING -> "Calling…"
    CallPhase.RINGING_INCOMING -> "Incoming call"
    CallPhase.CONNECTING -> "Connecting…"
    CallPhase.ACTIVE -> "Connected"
    CallPhase.ENDED -> "Call ended"
}
