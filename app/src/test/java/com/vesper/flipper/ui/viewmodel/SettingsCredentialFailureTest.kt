package com.vesper.flipper.ui.viewmodel

import com.vesper.flipper.ai.LlmProvider
import com.vesper.flipper.ai.LlmSelection
import com.vesper.flipper.data.OpenRouterModelCatalog
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.service.PermissionService
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCredentialFailureTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mock<SettingsStore>()
    private val catalog = mock<OpenRouterModelCatalog>()
    private val permissions = PermissionService()
    private val savedKey = MutableStateFlow<String?>("stored-key")
    private val provider = MutableStateFlow(LlmProvider.OPENROUTER)
    private var failRead = false
    private var failSave = false
    private var viewModel: SettingsViewModel? = null

    @Before fun setup() = runTest(dispatcher) {
        Dispatchers.setMain(dispatcher)
        whenever(store.apiKey).thenReturn(flow {
            if (failRead) error("sensitive-provider-details")
            emitAll(savedKey)
        })
        whenever(store.llmSelection).thenReturn(combine(provider, savedKey) { p, key -> LlmSelection(p,key,p.defaultModel) })
        whenever(store.readLlmSelection()).thenAnswer {
            if (failRead) error("sensitive-provider-details")
            LlmSelection(provider.value, savedKey.value, provider.value.defaultModel)
        }
        whenever(store.setProvider(any())).thenAnswer { provider.value = it.getArgument(0); Unit }
        whenever(store.selectedModel).thenReturn(flowOf(SettingsStore.DEFAULT_MODEL))
        whenever(store.aiMaxIterations).thenReturn(flowOf(8))
        whenever(store.autoConnect).thenReturn(flowOf(true))
        whenever(store.defaultProjectPath).thenReturn(flowOf("/ext"))
        whenever(store.permissionDuration).thenReturn(flowOf(1000L))
        whenever(store.hapticFeedback).thenReturn(flowOf(true))
        whenever(store.darkMode).thenReturn(flowOf(true))
        whenever(store.auditRetentionDays).thenReturn(flowOf(30))
        whenever(store.autoApproveMedium).thenReturn(flowOf(false))
        whenever(store.autoApproveHigh).thenReturn(flowOf(false))
        whenever(store.ttsEnabled).thenReturn(flowOf(false))
        whenever(store.ttsVoiceId).thenReturn(flowOf("voice"))
        whenever(store.ttsAutoSpeak).thenReturn(flowOf(false))
        whenever(store.glassesEnabled).thenReturn(flowOf(false))
        whenever(store.glassesBridgeUrl).thenReturn(flowOf(null))
        whenever(store.glassesAutoSend).thenReturn(flowOf(false))
        whenever(store.glassesAutoConnect).thenReturn(flowOf(false))
        whenever(store.glassesSailorMouth).thenReturn(flowOf(false))
        whenever(store.glassesMuted).thenReturn(flowOf(false))
        whenever(catalog.fetchModels(any(), anyOrNull())).thenReturn(Result.success(emptyList()))
        whenever(store.setApiKey(any(), any())).thenAnswer {
            if (failSave) error("sensitive-provider-details")
            savedKey.value = it.getArgument(0)
            Unit
        }
    }

    @After fun cleanup() {
        viewModel?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun create() = SettingsViewModel(store, catalog, permissions).also { viewModel = it }

    @Test fun migrationFailureIsRecoverableWithoutExposingItsException() = runTest(dispatcher) {
        failRead = true
        val vm = create()
        advanceUntilIdle()
        assertNotNull(vm.credentialError.value)
        assertFalse(vm.credentialError.value!!.contains("sensitive-provider-details"))
        failRead = false
        vm.retryCredentialStorage()
        advanceUntilIdle()
        assertNull(vm.credentialError.value)
        assertEquals("stored-key", vm.state.value.apiKey)
    }

    @Test fun failedSaveKeepsDraftForRetryAndPreservesStoredKey() = runTest(dispatcher) {
        val vm = create()
        advanceUntilIdle()
        failSave = true
        vm.setApiKey("replacement-key")
        advanceUntilIdle()
        assertEquals("stored-key", savedKey.value)
        assertEquals("replacement-key", vm.state.value.apiKey)
        assertNotNull(vm.credentialError.value)
        assertFalse(vm.credentialError.value!!.contains("sensitive-provider-details"))
        failSave = false
        vm.retryCredentialStorage()
        advanceUntilIdle()
        assertEquals("replacement-key", savedKey.value)
        assertEquals("replacement-key", vm.state.value.apiKey)
        assertNull(vm.credentialError.value)
    }
    @Test fun retrySavesToTheProviderCapturedBeforeSwitching() = runTest(dispatcher) {
        val vm = create()
        advanceUntilIdle()
        failSave = true
        vm.setApiKey("replacement-key")
        advanceUntilIdle()
        vm.setProvider(LlmProvider.OPENAI)
        advanceUntilIdle()
        failSave = false
        vm.retryCredentialStorage()
        advanceUntilIdle()
        verify(store, times(2)).setApiKey("replacement-key", LlmProvider.OPENROUTER)
        verify(store, never()).setApiKey("replacement-key", LlmProvider.OPENAI)
        assertEquals(LlmProvider.OPENAI, vm.state.value.provider)
    }
}
