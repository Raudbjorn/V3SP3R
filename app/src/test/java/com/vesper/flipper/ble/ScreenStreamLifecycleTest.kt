package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.screen.Gui
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream

class ScreenStreamLifecycleTest {
    @Suppress("UNCHECKED_CAST")
    private fun ready(protocol: FlipperProtocol) {
        val flow = FlipperProtocol::class.java.getDeclaredField("_cliStatus").apply { isAccessible = true }
            .get(protocol) as MutableStateFlow<CliCapabilityStatus>
        flow.value = CliCapabilityStatus(level=CliCapabilityLevel.READY, supportsRpc=true)
    }

    @Test fun `acknowledged stream stop retains last frame and restores raw CLI`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        ready(protocol)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenAnswer { call ->
            val bytes = call.getArgument<ByteArray>(0)
            if (bytes.toString(Charsets.UTF_8).startsWith("version")) {
                protocol.processIncomingData("Firmware version: test\r\n".toByteArray())
            } else {
                val request = Flipper.Main.parseDelimitedFrom(ByteArrayInputStream(bytes))
                protocol.processIncomingData(RpcFrameRouter.encode(Flipper.Main.newBuilder()
                    .setCommandId(request.commandId).setCommandStatus(Flipper.CommandStatus.OK).build()))
            }
            true
        }
        assertTrue(protocol.startScreenStream() is ProtocolResponse.Success)
        protocol.processIncomingData(RpcFrameRouter.encode(Flipper.Main.newBuilder()
            .setGuiScreenFrame(Gui.ScreenFrame.newBuilder()
                .setData(ByteString.copyFrom(ByteArray(1024).apply { this[0] = 1 }))
                .setOrientation(Gui.ScreenOrientation.HORIZONTAL)).build()))
        val frame = withTimeout(2000) { protocol.screenFrame.first { it != null } }!!
        assertTrue(frame.pixels[0])
        assertTrue(protocol.stopScreenStream() is ProtocolResponse.Success)
        assertEquals(frame, protocol.screenFrame.value)
        val cli = protocol.sendRawCliCommand("version")
        assertTrue("Confirmed stop must restore CLI collection: $cli", cli is ProtocolResponse.FileContent)
        protocol.onConnectionReset()
        assertNull(protocol.screenFrame.value)
    }

    @Test fun `unacknowledged stop retains routing and rejects raw CLI before write`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        ready(protocol)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenAnswer { call ->
            val request = Flipper.Main.parseDelimitedFrom(ByteArrayInputStream(call.getArgument(0)))
            val status = if (request.hasGuiStopScreenStreamRequest()) Flipper.CommandStatus.ERROR else Flipper.CommandStatus.OK
            protocol.processIncomingData(RpcFrameRouter.encode(Flipper.Main.newBuilder()
                .setCommandId(request.commandId).setCommandStatus(status).build()))
            true
        }
        assertTrue(protocol.startScreenStream() is ProtocolResponse.Success)
        assertTrue(protocol.stopScreenStream() is ProtocolResponse.Error)
        clearInvocations(service)
        val cli = protocol.sendRawCliCommand("version")
        assertTrue(cli is ProtocolResponse.Error)
        assertTrue((cli as ProtocolResponse.Error).message.contains("Pause the screen mirror"))
        verify(service, never()).sendData(any(), any(), any())
        protocol.onConnectionReset()
    }
}
