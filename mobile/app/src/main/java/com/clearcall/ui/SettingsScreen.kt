package com.clearcall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.clearcall.audio.SuppressionEngine
import com.clearcall.core.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var nsEnabled by remember { mutableStateOf(prefs.noiseSuppressionEnabled) }
    var engine by remember { mutableStateOf(prefs.suppressionEngine) }
    var atten by remember { mutableFloatStateOf(prefs.attenuationLimitDb) }

    // Re-check battery-optimization exemption whenever we return to this screen (the user may
    // have toggled it in the system dialog we launch).
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) batteryExempt = isIgnoringBatteryOptimizations(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Noise removal", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Clean your mic on-device (DeepFilterNet). Applies to your next call.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = nsEnabled,
                            onCheckedChange = {
                                nsEnabled = it
                                prefs.noiseSuppressionEnabled = it
                                engine = prefs.suppressionEngine // resync (OFF or restored engine)
                            },
                        )
                    }

                    if (nsEnabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = engine == SuppressionEngine.DFN3,
                                onClick = { engine = SuppressionEngine.DFN3; prefs.suppressionEngine = engine },
                                label = { Text("General") },
                            )
                            FilterChip(
                                selected = engine == SuppressionEngine.TARGET_SPEAKER,
                                onClick = { engine = SuppressionEngine.TARGET_SPEAKER; prefs.suppressionEngine = engine },
                                label = { Text("Isolate a voice · beta") },
                            )
                        }
                        if (engine == SuppressionEngine.TARGET_SPEAKER) {
                            val target = prefs.targetVoiceName
                            Text(
                                if (target != null) "Keeping only: $target" else "No voice sample added yet.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Record a short sample of the voice to keep (yours or anyone's) — the app removes " +
                                    "every other voice and background. Uses general suppression until the model ships.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(enabled = false, onClick = {}) { Text("Add a voice sample (coming soon)") }
                        }
                        Text("Strength: ${atten.toInt()} dB", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = atten,
                            onValueChange = { atten = it },
                            onValueChangeFinished = { prefs.attenuationLimitDb = atten },
                            valueRange = 20f..100f,
                            steps = 7,
                        )
                    }
                }
            }

            if (!batteryExempt) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Reliable ringing", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Allow ClearCall to ignore battery optimizations so incoming calls ring " +
                                "promptly even when the app is in the background.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                            Text("Allow")
                        }
                    }
                }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife") // intentional: a calling app has a legitimate need to ring in the background
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    runCatching { context.startActivity(intent) }
}
