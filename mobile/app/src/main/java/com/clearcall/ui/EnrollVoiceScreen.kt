package com.clearcall.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.clearcall.audio.EnrollmentRecorder
import com.clearcall.audio.VoiceEnrollment
import com.clearcall.core.Prefs
import kotlinx.coroutines.launch

private enum class EnrollStep { IDLE, RECORDING, CHECKING, FAILED, SAVING }

/**
 * One-time "record the voice to keep" flow for the Isolate-a-voice engine: read a short
 * paragraph aloud (~20 s), pass a local quality gate, and store the name + d-vector via
 * [VoiceEnrollment]. The sample never leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollVoiceScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val recorder = remember { EnrollmentRecorder() }

    var step by remember { mutableStateOf(EnrollStep.IDLE) }
    var name by remember { mutableStateOf(prefs.targetVoiceName ?: prefs.userName ?: "My voice") }
    var failMessage by remember { mutableStateOf<String?>(null) }
    val elapsed by recorder.elapsedSeconds.collectAsState()
    val rms by recorder.rmsLevel.collectAsState()

    // Same pattern as ScanQrScreen: hand-rolled router, so back must not finish the Activity,
    // and the permission is normally granted at app start but re-requested here if revoked.
    BackHandler(onBack = { recorder.cancel(); onBack() })
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* grant/deny is re-checked when Record is tapped */ }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    fun stopAndCheck() {
        step = EnrollStep.CHECKING
        val samples = recorder.stop()
        scope.launch {
            val quality = VoiceEnrollment.checkQuality(samples)
            if (!quality.ok) {
                failMessage = quality.message
                step = EnrollStep.FAILED
                return@launch
            }
            step = EnrollStep.SAVING
            val saved = VoiceEnrollment.save(context, prefs, name.trim().ifEmpty { "My voice" }, samples)
            if (saved) {
                onDone()
            } else {
                failMessage = "Couldn't analyze the recording — try again."
                step = EnrollStep.FAILED
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record a voice sample") },
                navigationIcon = {
                    IconButton(onClick = { recorder.cancel(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "ClearCall will keep only this voice on your calls and quiet everyone else. " +
                    "Record about ${VoiceEnrollment.TARGET_SECONDS} seconds in a quiet room — " +
                    "the sample stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Read this aloud, naturally:", fontWeight = FontWeight.SemiBold)
                    Text(
                        "“Hi! I’m recording a short sample of my voice so my calls stay clear. " +
                            "I’ll speak the way I usually do, at my normal pace and volume. " +
                            "The quick brown fox jumps over the lazy dog while bright vixens leap about. " +
                            "One, two, three, four, five, six, seven, eight, nine, ten — and that " +
                            "should be plenty of talking to recognize me.”",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Whose voice is this?") },
                singleLine = true,
                enabled = step == EnrollStep.IDLE || step == EnrollStep.FAILED,
                modifier = Modifier.fillMaxWidth(),
            )

            when (step) {
                EnrollStep.IDLE, EnrollStep.FAILED -> {
                    failMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        FilledIconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else if (recorder.start()) {
                                    failMessage = null
                                    step = EnrollStep.RECORDING
                                } else {
                                    failMessage = "Microphone unavailable — is another app using it?"
                                }
                            },
                            modifier = Modifier.size(72.dp),
                        ) { Icon(Icons.Filled.Mic, contentDescription = "Record") }
                    }
                    Text(
                        if (step == EnrollStep.FAILED) "Tap to try again" else "Tap to start recording",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                EnrollStep.RECORDING -> {
                    Text(
                        "${elapsed}s / ${VoiceEnrollment.TARGET_SECONDS}s",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    LinearProgressIndicator(
                        progress = { (elapsed.toFloat() / VoiceEnrollment.TARGET_SECONDS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LinearProgressIndicator(
                        progress = { (rms * 8f).coerceIn(0f, 1f) }, // mic level meter
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        FilledIconButton(
                            onClick = { stopAndCheck() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.size(72.dp),
                        ) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
                    }
                    Text(
                        if (elapsed >= VoiceEnrollment.TARGET_SECONDS) "That's enough — tap stop."
                        else "Keep reading…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }

                EnrollStep.CHECKING, EnrollStep.SAVING -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(
                            if (step == EnrollStep.CHECKING) "  Checking the recording…" else "  Saving…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (step == EnrollStep.IDLE || step == EnrollStep.FAILED) {
                TextButton(onClick = { recorder.cancel(); onBack() }) { Text("Cancel") }
            }
        }
    }
}
