package com.clearcall.core

import android.content.Context
import com.clearcall.audio.SuppressionEngine

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

    // ---- Noise suppression (P2 / P4 seam) ----

    /** Which suppression engine runs on the mic uplink. Migrates from the legacy on/off flag. */
    var suppressionEngine: SuppressionEngine
        get() {
            val stored = sp.getString(KEY_NS_ENGINE, null)
            if (stored != null) return SuppressionEngine.fromStorage(stored)
            // Legacy migration: pre-P4 builds only had a boolean.
            return if (sp.getBoolean(KEY_NS_ENABLED, true)) SuppressionEngine.DFN3 else SuppressionEngine.OFF
        }
        set(value) = sp.edit().putString(KEY_NS_ENGINE, value.name).apply()

    /** Convenience on/off used by the UI toggle; off = [SuppressionEngine.OFF]. */
    var noiseSuppressionEnabled: Boolean
        get() = suppressionEngine != SuppressionEngine.OFF
        set(value) {
            if (value) {
                if (suppressionEngine == SuppressionEngine.OFF) suppressionEngine = SuppressionEngine.DFN3
            } else {
                suppressionEngine = SuppressionEngine.OFF
            }
        }

    /** DFN3 attenuation limit in dB (higher = more aggressive). Default 100. */
    var attenuationLimitDb: Float
        get() = sp.getFloat(KEY_NS_ATTEN, 100f)
        set(value) = sp.edit().putFloat(KEY_NS_ATTEN, value).apply()

    /**
     * With earbuds connected, capture from the phone's own mic instead of the earbud mic
     * (media-mode audio: buds keep playing via A2DP, no SCO). Earbud mics pick up far more
     * environment noise than the phone's mic + DFN3; default on.
     */
    var phoneMicWithBuds: Boolean
        get() = sp.getBoolean(KEY_PHONE_MIC_BUDS, true)
        set(value) = sp.edit().putBoolean(KEY_PHONE_MIC_BUDS, value).apply()

    /** Name of the enrolled "voice to keep" for Tier B target-speaker extraction (any speaker). */
    var targetVoiceName: String?
        get() = sp.getString(KEY_TGT_NAME, null)
        set(value) = sp.edit().putString(KEY_TGT_NAME, value).apply()

    /** d-vector of the target voice, computed on-device from its sample; local-only, never uploaded. */
    var targetVoiceEmbedding: FloatArray?
        get() {
            val s = sp.getString(KEY_TGT_EMB, null) ?: return null
            return runCatching { s.split(",").map { it.toFloat() }.toFloatArray() }.getOrNull()
        }
        set(value) {
            if (value == null) sp.edit().remove(KEY_TGT_EMB).apply()
            else sp.edit().putString(KEY_TGT_EMB, value.joinToString(",")).apply()
        }

    /** Encoder version that produced [targetVoiceEmbedding] — enables recompute-on-upgrade. */
    var targetVoiceModelVersion: String?
        get() = sp.getString(KEY_TGT_EMB_VER, null)
        set(value) = sp.edit().putString(KEY_TGT_EMB_VER, value).apply()

    /** Forget the enrolled voice (callers should also delete the kept WAV — see VoiceEnrollment.clear). */
    fun clearTargetVoice() {
        sp.edit()
            .remove(KEY_TGT_NAME)
            .remove(KEY_TGT_EMB)
            .remove(KEY_TGT_EMB_VER)
            .apply()
    }

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
        private const val KEY_NS_ENGINE = "ns_engine"
        private const val KEY_NS_ATTEN = "ns_attenuation_db"
        private const val KEY_TGT_NAME = "target_voice_name"
        private const val KEY_TGT_EMB = "target_voice_embedding"
        private const val KEY_TGT_EMB_VER = "target_voice_embed_ver"
        private const val KEY_PHONE_MIC_BUDS = "phone_mic_with_buds"
    }
}
