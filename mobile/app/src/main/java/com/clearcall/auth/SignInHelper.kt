package com.clearcall.auth

import android.os.Build
import android.util.Log
import com.clearcall.core.AuthState
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.clearcall.net.AuthResult
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

private const val TAG = "SignInHelper"

/** Shared tail end of both the Google and dev-login flows: persist session, register for push. */
suspend fun completeSignIn(prefs: Prefs, apiClient: ApiClient, result: AuthResult) {
    prefs.authToken = result.token
    prefs.userId = result.user.id
    prefs.userName = result.user.name
    prefs.userCode = result.user.userCode
    prefs.userEmail = result.user.email
    AuthState.setSignedIn(true)
    registerDeviceForPush(prefs, apiClient)
}

/**
 * Register this device's FCM token with the backend so the user is reachable for incoming calls.
 * A user with no active device row is uncallable (the server rejects with "Callee has no
 * registered device"). Registration used to run only at sign-in inside a silent runCatching, so a
 * transient FCM/network failure left the device permanently uncallable with no retry — [ClearCallApp]
 * now also calls this on every authenticated app start, and the backend upserts by token, so it
 * self-heals. Logs each step for diagnosis.
 */
suspend fun registerDeviceForPush(prefs: Prefs, apiClient: ApiClient) {
    if (!prefs.isSignedIn) return
    val token = runCatching { FirebaseMessaging.getInstance().token.await() }
        .onFailure { Log.e(TAG, "FCM token fetch failed — device will be uncallable until this succeeds", it) }
        .getOrNull()
    if (token.isNullOrEmpty()) return
    runCatching { apiClient.registerDevice(token, Build.MODEL ?: "Android device") }
        .onSuccess { Log.i(TAG, "Device registered for push (token …${token.takeLast(6)})") }
        .onFailure { Log.e(TAG, "registerDevice API call failed", it) }
}

fun signOut(prefs: Prefs) {
    prefs.clearSession()
    AuthState.setSignedIn(false)
}
