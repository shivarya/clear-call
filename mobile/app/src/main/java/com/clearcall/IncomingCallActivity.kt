package com.clearcall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.clearcall.ui.CallScreen
import com.clearcall.ui.theme.ClearCallTheme

/**
 * Shown via the incoming-call notification's full-screen intent (locked device, or the app
 * backgrounded). Renders the same [CallScreen] the foreground app uses, reacting to the
 * shared [com.clearcall.call.CallState] — there is no separate incoming-call UI to keep in sync.
 */
class IncomingCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            ClearCallTheme {
                CallScreen(onCallEnded = { finish() })
            }
        }
    }
}
