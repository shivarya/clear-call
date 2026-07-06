package com.clearcall.auth

import android.os.Build
import com.clearcall.core.AuthState
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.clearcall.net.AuthResult
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/** Shared tail end of both the Google and dev-login flows: persist session, register for push. */
suspend fun completeSignIn(prefs: Prefs, apiClient: ApiClient, result: AuthResult) {
    prefs.authToken = result.token
    prefs.userId = result.user.id
    prefs.userName = result.user.name
    prefs.userCode = result.user.userCode
    prefs.userEmail = result.user.email
    AuthState.setSignedIn(true)
    runCatching {
        val fcmToken = FirebaseMessaging.getInstance().token.await()
        apiClient.registerDevice(fcmToken, Build.MODEL ?: "Android device")
    }
}

fun signOut(prefs: Prefs) {
    prefs.clearSession()
    AuthState.setSignedIn(false)
}
