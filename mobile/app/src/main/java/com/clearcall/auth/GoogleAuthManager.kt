package com.clearcall.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.clearcall.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom

/** True once the Google Cloud OAuth web client ID has been wired up (part of the console handoff). */
val googleSignInConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

/** Google Sign-In via Credential Manager — returns a Google ID token for POST /auth/google. */
class GoogleAuthManager(private val context: Context) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(): String {
        check(googleSignInConfigured) { "Google Sign-In isn't configured yet — use the dev login instead." }

        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = credentialManager.getCredential(context, request)
        val credential = GoogleIdTokenCredential.createFrom(result.credential.data)
        return credential.idToken
    }
}
