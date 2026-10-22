package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.domain.model.SignalCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class OracleProviderRoutingTest {
    @Test fun publicAnalysisUsesSharedProviderClientInsteadOfAnOpenRouterCredentialRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val files = mock<FlipperFileSystem>()
        val client = mock<OpenRouterClient>()
        whenever(files.listDirectory(any())).thenReturn(Result.success(emptyList()))
        whenever(client.chatSimple(any())).thenReturn("PROTOCOL IDENTIFICATION\nPrinceton signal")
        val vm = SpectralOracleViewModel(files, client)
        try {
            vm.selectSignal(SignalCapture.SubGhz("test", "/ext/subghz/test.sub", 0, frequency=433920000,
                protocol="Princeton", preset="OOK", bitLength=24, rawData=null, keyData="00"))
            vm.analyzeSignal()
            advanceUntilIdle()
            verify(client).chatSimple(any())
            verifyNoMoreInteractions(client)
            assertNotNull(vm.analysisResult.value)
            assertNull(vm.error.value)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
