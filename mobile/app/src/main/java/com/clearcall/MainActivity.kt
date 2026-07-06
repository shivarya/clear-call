package com.clearcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.clearcall.call.CallPhase
import com.clearcall.call.CallState
import com.clearcall.core.AuthState
import com.clearcall.core.Prefs
import com.clearcall.ui.CallScreen
import com.clearcall.ui.HistoryScreen
import com.clearcall.ui.HomeScreen
import com.clearcall.ui.SignInScreen
import com.clearcall.ui.theme.ClearCallTheme

private enum class Screen { HOME, HISTORY }

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* UI re-reads granted state as needed; nothing to react to here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AuthState.setSignedIn(Prefs(this).isSignedIn)
        requestNeededPermissions()
        setContent {
            ClearCallTheme {
                AppRoot()
            }
        }
    }

    private fun requestNeededPermissions() {
        val dangerous = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (dangerous.isNotEmpty()) permissionLauncher.launch(dangerous.toTypedArray())
    }
}

@Composable
private fun AppRoot() {
    val signedIn by AuthState.signedIn.collectAsState()
    val phase by CallState.phase.collectAsState()
    var screen by remember { mutableStateOf(Screen.HOME) }

    when {
        !signedIn -> SignInScreen()
        phase != CallPhase.IDLE -> CallScreen(onCallEnded = {})
        screen == Screen.HISTORY -> HistoryScreen(onBack = { screen = Screen.HOME })
        else -> HomeScreen(onOpenHistory = { screen = Screen.HISTORY })
    }
}
