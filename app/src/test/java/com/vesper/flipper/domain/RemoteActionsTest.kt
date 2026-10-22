package com.vesper.flipper.domain

import com.flipperdevices.protobuf.screen.Gui
import com.vesper.flipper.ble.*
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class RemoteActionsTest {
    @Test fun `confirmed USB CLI returns loader list output`() = runBlocking<Unit> {
        val protocol: FlipperProtocol = mock()
        val fs = FlipperFileSystem(protocol, mock())
        whenever(protocol.isRawCliTransportConnected()).thenReturn(true)
        whenever(protocol.firmwareCompatibility).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(
            FirmwareCompatibilityProfile(transportMode = FirmwareTransportMode.CLI_ONLY, supportsCli = true)))
        whenever(protocol.cliStatus).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(
            CliCapabilityStatus(level = CliCapabilityLevel.READY, supportsCli = true)))
        whenever(protocol.sendLoaderCommandOnce("loader list")).thenReturn(ProtocolResponse.Success("NFC\nInfrared"))
        assertEquals("NFC\nInfrared", fs.executeLoaderControl("list").getOrThrow())
    }

    @Test fun `input errors return failure without attempting CLI duplicate`() = runBlocking<Unit> {
        val protocol: FlipperProtocol = mock()
        val fs = FlipperFileSystem(protocol, mock())
        whenever(protocol.sendGuiInputEvent(Gui.InputKey.BACK, Gui.InputType.SHORT))
            .thenReturn(ProtocolResponse.Error("No acknowledgement"))
        val result = fs.sendInputEvent("back")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No acknowledgement"))
        verify(protocol, never()).sendCliCommand(any())
    }
    @Test fun `invalid input and unavailable raw CLI fail explicitly`() = runBlocking<Unit> {
        val protocol: FlipperProtocol = mock()
        val fs = FlipperFileSystem(protocol, mock())
        assertTrue(fs.sendInputEvent("back; power off").isFailure)
        assertTrue(fs.sendInputEvent("ok", "unknown").isFailure)
        val result = fs.executeLoaderControl("close")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not supported over BLE RPC"))
        verify(protocol, never()).sendCliCommand(any())
        assertTrue(fs.executeLoaderControl("signal", 1, "x\npower off").isFailure)
    }
}
