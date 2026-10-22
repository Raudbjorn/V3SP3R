package com.vesper.flipper.voice

import com.vesper.flipper.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class VoiceCredentialFailureTest {
    @Test fun speechReportsCredentialFailureWithoutThrowingOrExposingDetails() = runTest {
        val store = mock<SettingsStore>()
        whenever(store.readLlmSelection()).thenThrow(IllegalStateException("sensitive-provider-details"))
        val service = OpenRouterTtsService(store)
        service.speak("hello")
        val failure = service.state.value as TtsState.Error
        assertFalse(failure.message.contains("sensitive-provider-details"))
        assertTrue(failure.message.contains("Settings"))
    }

    @Test fun availabilityReturnsFalseWhenCredentialStorageFails() = runTest {
        val store = mock<SettingsStore>()
        whenever(store.ttsEnabled).thenReturn(flowOf(true))
        whenever(store.readLlmSelection()).thenThrow(IllegalStateException("sensitive-provider-details"))
        val service = OpenRouterTtsService(store)
        assertFalse(service.isAvailable())
        assertTrue(service.state.value is TtsState.Error)
    }

    @Test fun credentialCancellationPropagatesFromBothPublicEntryPoints() = runTest {
        val store = mock<SettingsStore>()
        whenever(store.ttsEnabled).thenReturn(flowOf(true))
        whenever(store.readLlmSelection()).thenThrow(CancellationException("cancel"))
        val service = OpenRouterTtsService(store)
        try { service.speak("hello"); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        try { service.isAvailable(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(service.state.value is TtsState.Error)
    }
}
