package com.clearcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.clearcall.call.CallPhase
import com.clearcall.call.CallState
import com.clearcall.core.AuthState
import com.clearcall.core.Prefs
import com.clearcall.security.BiometricGate
import com.clearcall.ui.CallScreen
import com.clearcall.ui.HomeScreen
import com.clearcall.ui.LockedScreen
import com.clearcall.ui.ScanQrScreen
import com.clearcall.ui.SignInScreen
import com.clearcall.ui.theme.ClearCallTheme

private enum class Screen { HOME, SCAN }

class MainActivity : FragmentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* UI re-reads granted state as needed; nothing to react to here */ }

    private val unlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AuthState.setSignedIn(Prefs(this).isSignedIn)
        requestNeededPermissions()
        // Fail open: a device with no biometric/screen lock enrolled can't be gated,
        // so proceed unlocked rather than locking the user out permanently.
        if (!BiometricGate.isAvailable(this)) unlocked.value = true
        setContent {
            ClearCallTheme {
                if (unlocked.value) AppRoot() else LockedScreen(onUnlock = ::authenticate)
            }
        }
        if (BiometricGate.isAvailable(this)) authenticate()
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

    private fun authenticate() {
        BiometricGate.authenticate(
            activity = this,
            onSuccess = { unlocked.value = true },
            onError = { /* stays locked; user can retry via LockedScreen's button */ },
        )
    }
}

@ExperimentalGetImage
@Composable
private fun AppRoot() {
    val signedIn by AuthState.signedIn.collectAsState()
    val phase by CallState.phase.collectAsState()
    var screen by remember { mutableStateOf(Screen.HOME) }
    var scannedCode by remember { mutableStateOf<String?>(null) }

    when {
        !signedIn -> SignInScreen()
        phase != CallPhase.IDLE -> CallScreen(onCallEnded = {})
        screen == Screen.SCAN -> ScanQrScreen(
            onBack = { screen = Screen.HOME },
            onCodeScanned = { code -> scannedCode = code; screen = Screen.HOME },
        )
        else -> HomeScreen(
            onOpenScan = { screen = Screen.SCAN },
            prefillCode = scannedCode,
            onPrefillConsumed = { scannedCode = null },
        )
    }
}
