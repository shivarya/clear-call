package com.clearcall.core

import android.content.Context

/** Tiny SharedPreferences wrapper for session state — mirrors clear-mic-router's core/Prefs.kt. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("clear_call", Context.MODE_PRIVATE)

    var authToken: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(value) = sp.edit().putString(KEY_TOKEN, value).apply()

    var userId: Int
        get() = sp.getInt(KEY_USER_ID, 0)
        set(value) = sp.edit().putInt(KEY_USER_ID, value).apply()

    var userName: String?
        get() = sp.getString(KEY_USER_NAME, null)
        set(value) = sp.edit().putString(KEY_USER_NAME, value).apply()

    var userCode: String?
        get() = sp.getString(KEY_USER_CODE, null)
        set(value) = sp.edit().putString(KEY_USER_CODE, value).apply()

    var userEmail: String?
        get() = sp.getString(KEY_USER_EMAIL, null)
        set(value) = sp.edit().putString(KEY_USER_EMAIL, value).apply()

    val isSignedIn: Boolean get() = !authToken.isNullOrEmpty()

    // ---- Noise suppression (P2) ----

    /** Whether on-device DeepFilterNet3 noise removal runs on the mic uplink. Default on. */
    var noiseSuppressionEnabled: Boolean
        get() = sp.getBoolean(KEY_NS_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_NS_ENABLED, value).apply()

    /** DFN3 attenuation limit in dB (higher = more aggressive). Default 100. */
    var attenuationLimitDb: Float
        get() = sp.getFloat(KEY_NS_ATTEN, 100f)
        set(value) = sp.edit().putFloat(KEY_NS_ATTEN, value).apply()

    fun clearSession() {
        sp.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_CODE)
            .remove(KEY_USER_EMAIL)
            .apply()
    }

    companion object {
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_CODE = "user_code"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_NS_ENABLED = "ns_enabled"
        private const val KEY_NS_ATTEN = "ns_attenuation_db"
    }
}
