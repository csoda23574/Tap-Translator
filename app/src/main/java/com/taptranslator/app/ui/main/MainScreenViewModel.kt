package com.taptranslator.app.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.taptranslator.app.data.model.OverlayDisplayMode
import com.taptranslator.app.data.model.TranslationTone
import com.taptranslator.app.data.preference.PreferencesManager
import com.taptranslator.app.service.FloatingOverlayService
import com.taptranslator.app.translation.GeminiTranslator
import com.taptranslator.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val apiKey: String = "",
    val isApiKeyExpanded: Boolean = true,
    val selectedModel: String = PreferencesManager.DEFAULT_MODEL,
    val autoDetectedModel: String? = null,
    val isCheckingModel: Boolean = false,
    val sourceLang: String = "ja",
    val targetLang: String = "ko",
    val translationTone: TranslationTone = TranslationTone.GAME,
    val displayMode: OverlayDisplayMode = OverlayDisplayMode.BOTH,
    val isServiceRunning: Boolean = false,
    val isTestingKey: Boolean = false,
    val testResultMessage: String? = null,
    val testSuccess: Boolean? = null
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager.getInstance(application)
    private val translator = GeminiTranslator()

    private val _uiState = MutableStateFlow(
        MainUiState(
            apiKey = prefs.apiKey,
            isApiKeyExpanded = prefs.apiKey.isBlank(),
            selectedModel = prefs.selectedModel,
            sourceLang = prefs.sourceLanguage,
            targetLang = prefs.targetLanguage,
            translationTone = prefs.translationTone,
            displayMode = prefs.overlayDisplayMode,
            isServiceRunning = FloatingOverlayService.isRunning
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val logs: StateFlow<String> = AppLogger.logsFlow

    init {
        // Automatically check and sync latest Flash model if API key is present
        if (prefs.apiKey.isNotBlank()) {
            checkAndSyncLatestFlashModel(prefs.apiKey)
        }
    }

    fun setApiKeyExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isApiKeyExpanded = expanded) }
    }

    fun updateApiKey(newKey: String) {
        prefs.apiKey = newKey
        _uiState.update { it.copy(apiKey = newKey, testResultMessage = null, testSuccess = null) }
        if (newKey.isNotBlank()) {
            checkAndSyncLatestFlashModel(newKey)
        }
    }

    fun checkAndSyncLatestFlashModel(key: String = _uiState.value.apiKey) {
        if (key.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingModel = true) }
            val latestModel = translator.fetchLatestFlashModel(key)
            if (latestModel != null) {
                prefs.selectedModel = latestModel
                _uiState.update {
                    it.copy(
                        selectedModel = latestModel,
                        autoDetectedModel = latestModel,
                        isCheckingModel = false
                    )
                }
            } else {
                _uiState.update { it.copy(isCheckingModel = false) }
            }
        }
    }

    fun updateSelectedModel(model: String) {
        prefs.selectedModel = model
        _uiState.update { it.copy(selectedModel = model) }
    }

    fun updateSourceLang(lang: String) {
        prefs.sourceLanguage = lang
        _uiState.update { it.copy(sourceLang = lang) }
    }

    fun updateTargetLang(lang: String) {
        prefs.targetLanguage = lang
        _uiState.update { it.copy(targetLang = lang) }
    }

    fun swapLanguages() {
        val currentSource = _uiState.value.sourceLang
        val currentTarget = _uiState.value.targetLang
        prefs.sourceLanguage = currentTarget
        prefs.targetLanguage = currentSource
        _uiState.update { it.copy(sourceLang = currentTarget, targetLang = currentSource) }
    }

    fun updateTone(tone: TranslationTone) {
        prefs.translationTone = tone
        _uiState.update { it.copy(translationTone = tone) }
    }

    fun updateDisplayMode(mode: OverlayDisplayMode) {
        prefs.overlayDisplayMode = mode
        _uiState.update { it.copy(displayMode = mode) }
    }

    fun refreshServiceState() {
        _uiState.update { it.copy(isServiceRunning = FloatingOverlayService.isRunning) }
    }

    fun testApiKey() {
        val key = _uiState.value.apiKey
        val model = _uiState.value.selectedModel
        if (key.isBlank()) {
            _uiState.update {
                it.copy(
                    testResultMessage = "API 키를 먼저 입력해주세요.",
                    testSuccess = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTestingKey = true, testResultMessage = null) }
            val result = translator.testApiKey(key, model)
            result.fold(
                onSuccess = { msg ->
                    _uiState.update {
                        it.copy(
                            isTestingKey = false,
                            testResultMessage = msg,
                            testSuccess = true,
                            isApiKeyExpanded = false // Automatically collapse on successful test!
                        )
                    }
                    // Also trigger model sync on success
                    checkAndSyncLatestFlashModel(key)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isTestingKey = false,
                            testResultMessage = "오류: ${err.message}",
                            testSuccess = false
                        )
                    }
                }
            )
        }
    }

    fun clearLogs() {
        AppLogger.clearLogs()
    }

    fun getFullLogs(): String {
        return AppLogger.getFullLogs()
    }
}
