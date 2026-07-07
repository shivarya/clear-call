package com.clearcall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearcall.audio.NoiseSuppression
import kotlinx.coroutines.delay

/**
 * Debug-only overlay for the noise-suppression pipeline: live probe format + timing stats and
 * a **mid-call A/B bypass switch** to flip DFN3 on/off and hear the difference. Only shown in
 * debug builds (gated by the caller).
 */
@Composable
fun NoiseDebugOverlay(modifier: Modifier = Modifier) {
    var snap by remember { mutableStateOf(NoiseSuppression.stats.snapshot()) }
    var bypass by remember { mutableStateOf(NoiseSuppression.isBypassed) }

    LaunchedEffect(Unit) {
        while (true) {
            snap = NoiseSuppression.stats.snapshot()
            delay(500)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Noise suppression (debug)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(
                buildString {
                    append("engine=${snap.engineName}  ready=${snap.ready}\n")
                    append("rate=${snap.sampleRate}  bands=${snap.numBands}  frames=${snap.numFrames}  hop=${snap.hopSize}\n")
                    append("hops=${snap.hopsProcessed}  last=${snap.lastHopMicros}µs  avg=${snap.avgHopMicros}µs  p95=${snap.p95HopMicros}µs\n")
                    append("bypass=${snap.bypassed}  autoBypass=${snap.autoBypassed}")
                    if (snap.engineName == "target-speaker") {
                        append("\ngate=${if (snap.gateOpen) "OPEN" else "CLOSED"}  vad=${if (snap.gateVad) 1 else 0}")
                        append("  cos=${"%.2f".format(snap.gateCosine)}  g=${"%.1f".format(snap.gateGainDb)}dB")
                    }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A/B bypass (raw mic)", fontSize = 12.sp)
                Switch(
                    checked = bypass,
                    onCheckedChange = {
                        bypass = it
                        NoiseSuppression.setBypass(it)
                    },
                )
            }
        }
    }
}
