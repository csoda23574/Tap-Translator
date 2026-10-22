package com.taptranslator.app.data.preference

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.taptranslator.app.data.model.OverlayDisplayMode
import com.taptranslator.app.data.model.TranslationTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "tap_translator_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular prefs if Keystore fails on certain devices
        context.getSharedPreferences("tap_translator_prefs", Context.MODE_PRIVATE)
    }

    private val _apiKeyFlow = MutableStateFlow(apiKey)
    val apiKeyFlow: StateFlow<String> = _apiKeyFlow.asStateFlow()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.trim()).apply()
            _apiKeyFlow.value = value.trim()
        }

    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_SELECTED_MODEL, value).apply()

    var sourceLanguage: String
        get() = prefs.getString(KEY_SOURCE_LANG, "ja") ?: "ja"
        set(value) = prefs.edit().putString(KEY_SOURCE_LANG, value).apply()

    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANG, "ko") ?: "ko"
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value).apply()

    var translationTone: TranslationTone
        get() {
            val name = prefs.getString(KEY_TONE, TranslationTone.GAME.name) ?: TranslationTone.GAME.name
            return try {
                TranslationTone.valueOf(name)
            } catch (e: Exception) {
                TranslationTone.GAME
            }
        }
        set(value) = prefs.edit().putString(KEY_TONE, value.name).apply()

    var overlayDisplayMode: OverlayDisplayMode
        get() {
            val name = prefs.getString(KEY_DISPLAY_MODE, OverlayDisplayMode.BOTH.name) ?: OverlayDisplayMode.BOTH.name
            return try {
                OverlayDisplayMode.valueOf(name)
            } catch (e: Exception) {
                OverlayDisplayMode.BOTH
            }
        }
        set(value) = prefs.edit().putString(KEY_DISPLAY_MODE, value.name).apply()

    var overlayBackgroundAlpha: Float
        get() = prefs.getFloat(KEY_BG_ALPHA, 0.85f)
        set(value) = prefs.edit().putFloat(KEY_BG_ALPHA, value).apply()

    var isServiceRunning: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_RUNNING, value).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-1.5-flash"
        const val ALTERNATIVE_MODEL = "gemini-2.0-flash"

        private const val KEY_API_KEY = "key_gemini_api_key"
        private const val KEY_SELECTED_MODEL = "key_selected_model"
        private const val KEY_SOURCE_LANG = "key_source_lang"
        private const val KEY_TARGET_LANG = "key_target_lang"
        private const val KEY_TONE = "key_translation_tone"
        private const val KEY_DISPLAY_MODE = "key_display_mode"
        private const val KEY_BG_ALPHA = "key_bg_alpha"
        private const val KEY_SERVICE_RUNNING = "key_service_running"

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
