package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.LlmProvider
import com.vesper.flipper.data.ModelInfo
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.Permission
import com.vesper.flipper.domain.service.PermissionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val extendedThinking: Boolean = false,
    val reasoningEffort: String = "medium",
    val provider: LlmProvider = LlmProvider.OPENROUTER,
    val apiKey: String = "",
    val selectedModel: String = SettingsStore.DEFAULT_MODEL,
    val aiMaxIterations: Int = SettingsStore.DEFAULT_AI_MAX_ITERATIONS,
    val autoConnect: Boolean = true,
    val defaultProjectPath: String = "/ext/apps_data/vesper",
    val permissionDuration: Long = Permission.DURATION_15_MINUTES,
    val hapticFeedback: Boolean = true,
    val darkMode: Boolean = true,
    val autoApproveMedium: Boolean = false,
    val autoApproveHigh: Boolean = false,
    val auditRetentionDays: Int = 30,
    val activePermissions: List<Permission> = emptyList(),
    // TTS (via OpenRouter — uses same API key)
    val ttsEnabled: Boolean = false,
    val ttsVoiceId: String = SettingsStore.DEFAULT_TTS_VOICE,
    val ttsAutoSpeak: Boolean = false,
    // Smart Glasses
    val glassesEnabled: Boolean = false,
    val glassesBridgeUrl: String = "",
    val glassesAutoSend: Boolean = true,
    val glassesAutoConnect: Boolean = false,
    val glassesSailorMouth: Boolean = false,
    val glassesMuted: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val openRouterModelCatalog: OpenRouterModelCatalog,
    private val permissionService: PermissionService
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _availableModels = MutableStateFlow(SettingsStore.FALLBACK_MODELS)
    val availableModels: StateFlow<List<ModelInfo>> = _availableModels.asStateFlow()

    private val _isRefreshingModels = MutableStateFlow(false)
    val isRefreshingModels: StateFlow<Boolean> = _isRefreshingModels.asStateFlow()
    private var modelRefreshGeneration = 0
    private var modelRefreshJob: Job? = null
    private val _credentialError = MutableStateFlow<String?>(null)
    val credentialError: StateFlow<String?> = _credentialError.asStateFlow()
    private var settingsJob: Job? = null
    private var pendingApiKey: Pair<LlmProvider, String>? = null
    private var credentialRevision = 0

    private fun reportCredentialFailure(error: Exception) {
        if (error is CancellationException) throw error
        _credentialError.value = "Could not access or save your API key securely. Retry when storage is available."
    }

    fun retryCredentialStorage() {
        _credentialError.value = null
        val pending = pendingApiKey
        if (pending != null) saveApiKey(pending.first, pending.second)
        loadSettings()
        refreshAvailableModels()
    }

    init {
        viewModelScope.launch { settingsStore.extendedThinking.collect { value -> _state.update { it.copy(extendedThinking = value) } } }
        viewModelScope.launch { settingsStore.reasoningEffort.collect { value -> _state.update { it.copy(reasoningEffort = value) } } }
        loadSettings()
        observePermissions()
        refreshAvailableModels()
    }

    private fun loadSettings() {
        settingsJob?.cancel()
        settingsJob = viewModelScope.launch {
            combine(
                settingsStore.apiKey,
                settingsStore.selectedModel,
                settingsStore.aiMaxIterations,
                settingsStore.autoConnect,
                settingsStore.defaultProjectPath,
                settingsStore.permissionDuration,
                settingsStore.hapticFeedback,
                settingsStore.darkMode,
                settingsStore.auditRetentionDays
            ) { values ->
                SettingsState(
                    apiKey = (values[0] as? String) ?: "",
                    selectedModel = values[1] as String,
                    aiMaxIterations = values[2] as Int,
                    autoConnect = values[3] as Boolean,
                    defaultProjectPath = values[4] as String,
                    permissionDuration = values[5] as Long,
                    hapticFeedback = values[6] as Boolean,
                    darkMode = values[7] as Boolean,
                    auditRetentionDays = values[8] as Int
                )
            }.combine(
                combine(
                    settingsStore.autoApproveMedium,
                    settingsStore.autoApproveHigh,
                    settingsStore.ttsEnabled,
                    settingsStore.ttsVoiceId,
                    settingsStore.ttsAutoSpeak
                ) { values ->
                    TtsSettingsBundle(
                        autoApproveMedium = values[0] as Boolean,
                        autoApproveHigh = values[1] as Boolean,
                        ttsEnabled = values[2] as Boolean,
                        ttsVoiceId = values[3] as String,
                        ttsAutoSpeak = values[4] as Boolean
                    )
                }
            ) { base, tts ->
                base.copy(
                    autoApproveMedium = tts.autoApproveMedium,
                    autoApproveHigh = tts.autoApproveHigh,
                    ttsEnabled = tts.ttsEnabled,
                    ttsVoiceId = tts.ttsVoiceId,
                    ttsAutoSpeak = tts.ttsAutoSpeak
                )
            }.combine(
                combine(
                    settingsStore.glassesEnabled,
                    settingsStore.glassesBridgeUrl,
                    settingsStore.glassesAutoSend,
                    settingsStore.glassesAutoConnect,
                    settingsStore.glassesSailorMouth,
                    settingsStore.glassesMuted
                ) { values ->
                    GlassesSettingsBundle(
                        glassesEnabled = values[0] as Boolean,
                        glassesBridgeUrl = (values[1] as? String) ?: "",
                        glassesAutoSend = values[2] as Boolean,
                        glassesAutoConnect = values[3] as Boolean,
                        glassesSailorMouth = values[4] as Boolean,
                        glassesMuted = values[5] as Boolean
                    )
                }
            ) { base, glasses ->
                base.copy(
                    glassesEnabled = glasses.glassesEnabled,
                    glassesBridgeUrl = glasses.glassesBridgeUrl,
                    glassesAutoSend = glasses.glassesAutoSend,
                    glassesAutoConnect = glasses.glassesAutoConnect,
                    glassesSailorMouth = glasses.glassesSailorMouth,
                    glassesMuted = glasses.glassesMuted
                )
            }.combine(settingsStore.llmSelection) { settings, selection ->
                settings.copy(provider = selection.provider, apiKey = selection.apiKey.orEmpty(), selectedModel = selection.model)
            }.catch { error -> reportCredentialFailure(error as? Exception ?: throw error) }.collect { settings ->
                _state.update { it.copy(
                    provider = settings.provider,
                    apiKey = pendingApiKey?.takeIf { it.first == settings.provider }?.second ?: settings.apiKey,
                    selectedModel = settings.selectedModel,
                    aiMaxIterations = settings.aiMaxIterations,
                    autoConnect = settings.autoConnect,
                    defaultProjectPath = settings.defaultProjectPath,
                    permissionDuration = settings.permissionDuration,
                    autoApproveMedium = settings.autoApproveMedium,
                    autoApproveHigh = settings.autoApproveHigh,
                    hapticFeedback = settings.hapticFeedback,
                    darkMode = settings.darkMode,
                    auditRetentionDays = settings.auditRetentionDays,
                    ttsEnabled = settings.ttsEnabled,
                    ttsVoiceId = settings.ttsVoiceId,
                    ttsAutoSpeak = settings.ttsAutoSpeak,
                    glassesEnabled = settings.glassesEnabled,
                    glassesBridgeUrl = settings.glassesBridgeUrl,
                    glassesAutoSend = settings.glassesAutoSend,
                    glassesAutoConnect = settings.glassesAutoConnect,
                    glassesSailorMouth = settings.glassesSailorMouth,
                    glassesMuted = settings.glassesMuted
                )}
                val providerModelIds = settings.provider.models.map { it.id }.toSet()
                if (_availableModels.value.none { it.id in providerModelIds }) {
                    _availableModels.value = settings.provider.models
                }
            }
        }
    }

    private fun observePermissions() {
        viewModelScope.launch {
            permissionService.activePermissions.collect { permissions ->
                _state.update { it.copy(activePermissions = permissions) }
            }
        }
    }

    fun setReasoningEffort(value: String) { viewModelScope.launch { settingsStore.setReasoningEffort(value) } }

    fun setExtendedThinking(enabled: Boolean) { viewModelScope.launch { settingsStore.setExtendedThinking(enabled) } }

    fun setProvider(provider: LlmProvider) {
        viewModelScope.launch {
            try {
                settingsStore.setProvider(provider)
                val selection = settingsStore.readLlmSelection()
                _state.update {
                    it.copy(provider = selection.provider, apiKey = selection.apiKey.orEmpty(), selectedModel = selection.model)
                }
                _availableModels.value = provider.models
                refreshAvailableModels()
            } catch (error: Exception) {
                reportCredentialFailure(error)
            }
        }
    }

    fun setApiKey(key: String) = saveApiKey(_state.value.provider, key)

    private fun saveApiKey(provider: LlmProvider, key: String) {
        val revision = ++credentialRevision
        pendingApiKey = provider to key
        _state.update { if (it.provider == provider) it.copy(apiKey = key) else it }
        viewModelScope.launch {
            try {
                settingsStore.setApiKey(key, provider)
                if (revision == credentialRevision) {
                    pendingApiKey = null
                    _credentialError.value = null
                    _state.update { if (it.provider == provider) it.copy(apiKey = key) else it }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (revision == credentialRevision) reportCredentialFailure(error)
            }
        }
    }

    fun setSelectedModel(model: String) {
        val provider = _state.value.provider
        viewModelScope.launch {
            settingsStore.setSelectedModel(model, provider)
            _state.update { if (it.provider == provider) it.copy(selectedModel = model) else it }
        }
    }

    fun setAiMaxIterations(value: Int) {
        viewModelScope.launch {
            settingsStore.setAiMaxIterations(value)
            _state.update { it.copy(aiMaxIterations = value) }
        }
    }

    fun refreshAvailableModels() {
        val generation = ++modelRefreshGeneration
        modelRefreshJob?.cancel()
        modelRefreshJob = viewModelScope.launch {
            _isRefreshingModels.value = true
            try {
                val selection = settingsStore.readLlmSelection()
                openRouterModelCatalog.fetchModels(selection.provider, selection.apiKey)
                    .onSuccess { models ->
                        if (generation != modelRefreshGeneration) return@onSuccess
                        val curatedDefault = selection.provider.models.filter { it.id == selection.provider.defaultModel }
                        _availableModels.value = (models + curatedDefault).distinctBy { it.id }
                    }
                    .onFailure {
                        if (generation != modelRefreshGeneration) return@onFailure
                        _availableModels.value = selection.provider.models
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == modelRefreshGeneration) reportCredentialFailure(error)
            } finally {
                if (generation == modelRefreshGeneration) _isRefreshingModels.value = false
            }
        }
    }

    fun getModelDisplayName(modelId: String): String {
        val providerModels = _state.value.provider.models
        val displayList = (_availableModels.value + providerModels).distinctBy { it.id }
        return SettingsStore.getModelDisplayName(modelId, displayList)
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoConnect(enabled)
            _state.update { it.copy(autoConnect = enabled) }
        }
    }

    fun setDefaultProjectPath(path: String) {
        viewModelScope.launch {
            settingsStore.setDefaultProjectPath(path)
            _state.update { it.copy(defaultProjectPath = path) }
        }
    }

    fun setPermissionDuration(durationMs: Long) {
        viewModelScope.launch {
            settingsStore.setPermissionDuration(durationMs)
            _state.update { it.copy(permissionDuration = durationMs) }
        }
    }

    fun setAutoApproveMedium(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoApproveMedium(enabled)
            _state.update { it.copy(autoApproveMedium = enabled) }
        }
    }

    fun setAutoApproveHigh(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setAutoApproveHigh(enabled)
            _state.update { it.copy(autoApproveHigh = enabled) }
        }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setHapticFeedback(enabled)
            _state.update { it.copy(hapticFeedback = enabled) }
        }
    }

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setDarkMode(enabled)
            _state.update { it.copy(darkMode = enabled) }
        }
    }

    fun setAuditRetentionDays(days: Int) {
        viewModelScope.launch {
            settingsStore.setAuditRetentionDays(days)
            _state.update { it.copy(auditRetentionDays = days) }
        }
    }

    fun revokePermission(permissionId: String) {
        permissionService.revokePermission(permissionId)
    }

    fun revokeAllPermissions() {
        permissionService.revokeAll()
    }

    fun grantProjectPermission() {
        val path = _state.value.defaultProjectPath
        permissionService.grantProjectScope(path)
    }

    // TTS settings
    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTtsEnabled(enabled)
            _state.update { it.copy(ttsEnabled = enabled) }
        }
    }

    fun setTtsVoiceId(voiceId: String) {
        viewModelScope.launch {
            settingsStore.setTtsVoiceId(voiceId)
            _state.update { it.copy(ttsVoiceId = voiceId) }
        }
    }

    fun setTtsAutoSpeak(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setTtsAutoSpeak(enabled)
            _state.update { it.copy(ttsAutoSpeak = enabled) }
        }
    }

    // Smart Glasses settings
    fun setGlassesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesEnabled(enabled)
            _state.update { it.copy(glassesEnabled = enabled) }
        }
    }

    fun setGlassesBridgeUrl(url: String) {
        viewModelScope.launch {
            settingsStore.setGlassesBridgeUrl(url.ifBlank { null })
            _state.update { it.copy(glassesBridgeUrl = url) }
        }
    }

    fun setGlassesAutoSend(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesAutoSend(enabled)
            _state.update { it.copy(glassesAutoSend = enabled) }
        }
    }

    fun setGlassesAutoConnect(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesAutoConnect(enabled)
            _state.update { it.copy(glassesAutoConnect = enabled) }
        }
    }

    fun setGlassesSailorMouth(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesSailorMouth(enabled)
            _state.update { it.copy(glassesSailorMouth = enabled) }
        }
    }

    fun setGlassesMuted(muted: Boolean) {
        viewModelScope.launch {
            settingsStore.setGlassesMuted(muted)
            _state.update { it.copy(glassesMuted = muted) }
        }
    }
}

private data class TtsSettingsBundle(
    val autoApproveMedium: Boolean,
    val autoApproveHigh: Boolean,
    val ttsEnabled: Boolean,
    val ttsVoiceId: String,
    val ttsAutoSpeak: Boolean
)

private data class GlassesSettingsBundle(
    val glassesEnabled: Boolean,
    val glassesBridgeUrl: String,
    val glassesAutoSend: Boolean,
    val glassesAutoConnect: Boolean,
    val glassesSailorMouth: Boolean,
    val glassesMuted: Boolean
)
