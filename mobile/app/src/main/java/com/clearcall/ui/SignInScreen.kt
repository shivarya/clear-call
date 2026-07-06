package com.clearcall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clearcall.BuildConfig
import com.clearcall.auth.GoogleAuthManager
import com.clearcall.auth.completeSignIn
import com.clearcall.auth.googleSignInConfigured
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import kotlinx.coroutines.launch

@Composable
fun SignInScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apiClient = remember { ApiClient(prefs) }
    val googleAuth = remember { GoogleAuthManager(context) }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("ClearCall", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Clear calls, on-device — noise removal that runs on your phone, not a server.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }

            if (googleSignInConfigured) {
                Button(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                val idToken = googleAuth.signIn()
                                val result = apiClient.googleLogin(idToken)
                                completeSignIn(prefs, apiClient, result)
                            } catch (e: Exception) {
                                error = e.message ?: "Sign-in failed"
                            } finally {
                                loading = false
                            }
                        }
                    },
                ) { Text("Sign in with Google") }
            } else {
                Text(
                    "Google Sign-In isn't configured yet — use a dev login below for local testing.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text("Dev login (debug builds only)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    completeSignIn(prefs, apiClient, apiClient.devLogin(1))
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) { Text("Login as Dev 1") }
                    OutlinedButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    completeSignIn(prefs, apiClient, apiClient.devLogin(2))
                                } catch (e: Exception) {
                                    error = e.message
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) { Text("Login as Dev 2") }
                }
            }

            if (loading) {
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator()
            }
        }
    }
}
