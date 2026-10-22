package com.vesper.flipper.domain

import com.flipperdevices.protobuf.screen.Gui
import com.vesper.flipper.ble.*
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class RemoteActionsTest {
    @Test fun `input errors return failure without attempting CLI duplicate`() = runBlocking {
        val protocol: FlipperProtocol = mock()
        val fs = FlipperFileSystem(protocol, mock())
        whenever(protocol.sendGuiInputEvent(Gui.InputKey.BACK, Gui.InputType.SHORT))
            .thenReturn(ProtocolResponse.Error("No acknowledgement"))
        val result = fs.sendInputEvent("back")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No acknowledgement"))
        verify(protocol, never()).sendCliCommand(any())
    }
    @Test fun `invalid input and unavailable raw CLI fail explicitly`() = runBlocking {
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
