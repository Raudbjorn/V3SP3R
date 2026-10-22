package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.screen.Gui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream

class GuiGestureProtocolTest {
    private data class Delivery(val type: Gui.InputType, val retry: Boolean)

    @Suppress("UNCHECKED_CAST")
    private fun ready(protocol: FlipperProtocol) {
        val status = FlipperProtocol::class.java.getDeclaredField("_cliStatus").apply { isAccessible = true }
            .get(protocol) as MutableStateFlow<CliCapabilityStatus>
        status.value = CliCapabilityStatus(level = CliCapabilityLevel.READY, supportsRpc = true)
        // A recently confirmed unlocked desktop skips the unrelated lock probe.
        FlipperProtocol::class.java.getDeclaredField("lastDesktopLockProbeAtMs").apply { isAccessible = true }
            .setLong(protocol, System.currentTimeMillis())
    }

    private suspend fun gesture(uncertainAction: Boolean): Pair<ProtocolResponse, List<Delivery>> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        ready(protocol)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        val deliveries = mutableListOf<Delivery>()
        whenever(service.sendData(any(), any(), any(), any())).thenAnswer { call ->
            val request = Flipper.Main.parseDelimitedFrom(ByteArrayInputStream(call.getArgument(0)))
            assertTrue("Gesture must remain on correlated GUI RPC", request.hasGuiSendInputEventRequest())
            val type = request.guiSendInputEventRequest.type
            deliveries += Delivery(type, call.getArgument(3))
            if (uncertainAction && type == Gui.InputType.SHORT) {
                // A write can have reached the device even when its acknowledgement is lost.
                false
            } else {
                protocol.processIncomingData(RpcFrameRouter.encode(Flipper.Main.newBuilder()
                    .setCommandId(request.commandId).setCommandStatus(Flipper.CommandStatus.OK).build()))
                true
            }
        }
        return try {
            protocol.sendGuiInputEvent(Gui.InputKey.OK, Gui.InputType.SHORT) to deliveries.toList()
        } finally {
            protocol.onConnectionReset()
        }
    }

    @Test fun `acknowledged gesture delivers press and action once while release may retry`() = runBlocking<Unit> {
        val (response, deliveries) = gesture(uncertainAction = false)
        assertTrue("$response", response is ProtocolResponse.Success)
        assertEquals(listOf(Delivery(Gui.InputType.PRESS, false), Delivery(Gui.InputType.SHORT, false),
            Delivery(Gui.InputType.RELEASE, true)), deliveries)
    }

    @Test fun `uncertain action is not replayed and sends cleanup release through same collector`() = runBlocking<Unit> {
        val (response, deliveries) = gesture(uncertainAction = true)
        assertTrue("$response", response is ProtocolResponse.Error)
        assertTrue((response as ProtocolResponse.Error).message.contains("SHORT not confirmed"))
        assertEquals(listOf(Delivery(Gui.InputType.PRESS, false), Delivery(Gui.InputType.SHORT, false),
            Delivery(Gui.InputType.RELEASE, true)), deliveries)
    }
}
