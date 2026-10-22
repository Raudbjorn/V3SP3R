package com.vesper.flipper.ui.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.viewModelScope
import com.vesper.flipper.ble.*
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.glasses.BridgeState
import com.vesper.flipper.glasses.GlassesIntegration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenMirrorViewModelTest {
    private data class Fixture(val vm: DeviceViewModel, val files: FlipperFileSystem,
        val frames: MutableStateFlow<FlipperScreenImage?>)

    private suspend fun fixture(): Fixture {
        val files = mock<FlipperFileSystem>()
        val manager = mock<BleServiceManager>()
        val settings = mock<SettingsStore>()
        val glasses = mock<GlassesIntegration>()
        val frames = MutableStateFlow<FlipperScreenImage?>(null)
        whenever(files.screenFrame).thenReturn(frames)
        whenever(files.firmwareCompatibility).thenReturn(MutableStateFlow(FirmwareCompatibilityProfile()))
        whenever(files.autotuneStatus).thenReturn(MutableStateFlow(CommandPipelineAutotuneStatus()))
        whenever(files.getDeviceInfo()).thenReturn(Result.failure(IllegalStateException("No metadata in fixture")))
        whenever(files.getStorageInfo()).thenReturn(Result.failure(IllegalStateException("No metadata in fixture")))
        whenever(files.startScreenStream()).thenReturn(Result.success("Started"))
        whenever(files.stopScreenStream()).thenReturn(Result.success("Paused"))
        whenever(manager.connectionState).thenReturn(MutableStateFlow(ConnectionState.Connected("Test device")))
        whenever(manager.connectedDevice).thenReturn(MutableStateFlow(null))
        whenever(manager.discoveredDevices).thenReturn(MutableStateFlow(emptyList()))
        whenever(manager.cliCapabilityStatus).thenReturn(MutableStateFlow(CliCapabilityStatus()))
        whenever(settings.glassesEnabled).thenReturn(flowOf(false))
        whenever(settings.glassesBridgeUrl).thenReturn(flowOf(null))
        whenever(glasses.bridgeState).thenReturn(MutableStateFlow(BridgeState.Disconnected))
        return Fixture(DeviceViewModel(settings, files, manager, glasses), files, frames)
    }

    private fun frame(id: Long) = FlipperScreenImage(id, 128, 64, BooleanArray(8192), FlipperScreenOrientation.HORIZONTAL)

    @Test fun `background lifecycle freezes frame before stop acknowledgement and foreground can resume`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        try {
            val f = fixture().also { fixture = it }
            runCurrent()
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle get() = registry
            }
            owner.registry.addObserver(ScreenMirrorLifecycleObserver { active ->
                if (!active) f.vm.setScreenMirrorEnabled(false)
            })
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            runCurrent()
            f.vm.setScreenMirrorEnabled(true)
            runCurrent()
            f.frames.value = frame(1)
            runCurrent()
            assertEquals(1L, f.vm.screenFrame.value?.id)
            clearInvocations(f.files)
            val stopAcknowledged = CompletableDeferred<Unit>()
            whenever(f.files.stopScreenStream()).doSuspendableAnswer {
                stopAcknowledged.await()
                Result.success("Paused")
            }
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals("Paused", f.vm.screenStreamStatus.value)
            f.frames.value = frame(2)
            runCurrent()
            verify(f.files).stopScreenStream()
            assertEquals("Late frames must not replace the paused image", 1L, f.vm.screenFrame.value?.id)
            stopAcknowledged.complete(Unit)
            runCurrent()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            // DeviceScreen's foreground LaunchedEffect reapplies the still-enabled switch.
            f.vm.setScreenMirrorEnabled(true)
            runCurrent()
            f.frames.value = frame(3)
            runCurrent()
            assertEquals(3L, f.vm.screenFrame.value?.id)
            f.vm.setScreenMirrorEnabled(false)
            runCurrent()
            f.frames.value = null
            runCurrent()
            assertNull("Disconnect clears the image even while paused", f.vm.screenFrame.value)
        } finally {
            fixture?.vm?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun `late start completion cannot undo a newer pause`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixture: Fixture? = null
        try {
            val f = fixture().also { fixture = it }
            runCurrent()
            val started = CompletableDeferred<Unit>()
            whenever(f.files.startScreenStream()).doSuspendableAnswer {
                started.await()
                Result.success("Started")
            }
            f.vm.setScreenMirrorEnabled(true)
            runCurrent()
            f.frames.value = frame(1)
            runCurrent()
            f.vm.setScreenMirrorEnabled(false)
            f.frames.value = frame(2)
            runCurrent()
            assertEquals(1L, f.vm.screenFrame.value?.id)
            assertEquals("Paused", f.vm.screenStreamStatus.value)
            started.complete(Unit)
            runCurrent()
            verify(f.files).stopScreenStream()
            assertEquals("Paused", f.vm.screenStreamStatus.value)
            assertEquals(1L, f.vm.screenFrame.value?.id)
        } finally {
            fixture?.vm?.viewModelScope?.cancel()
            Dispatchers.resetMain()
        }
    }
}
