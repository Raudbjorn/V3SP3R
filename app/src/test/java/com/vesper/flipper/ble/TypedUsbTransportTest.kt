package com.vesper.flipper.ble
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

class TypedUsbTransportTest {
    @Test fun observedCliWithoutExplicitSelectionCannotWriteEitherSink() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        confirmCli(protocol)
        assertTrue(protocol.sendTypedUsbCommand("gpio read PA7") is ProtocolResponse.Error)
        assertTrue(protocol.sendLoaderCommandOnce("loader list") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
        verify(service, never()).sendData(any(), any(), any(), any())
    }

    private fun confirmCli(protocol: FlipperProtocol) {
        FlipperProtocol::class.java.getDeclaredMethod("markCliReady", String::class.java)
            .apply { isAccessible = true }.invoke(protocol, "USB CLI ready")
    }

    @Test fun typedCommandRefusesBleWithoutWriting() = runBlocking<Unit> {
        val service=mock<FlipperBleService>()
        whenever(service.usesUsbCommandTransport()).thenReturn(false)
        val protocol=FlipperProtocol().apply { setBleService(service) }
        confirmCli(protocol)
        assertTrue(protocol.sendTypedUsbCommand("gpio read PA7") is ProtocolResponse.Error)
        verify(service,never()).sendUsbDataOnly(any())
        verify(service,never()).sendData(any(),any(),any(),any())
    }
    @Test fun failedUsbWriteNeverRetriesOrFallsBackToBle() = runBlocking<Unit> {
        val service=mock<FlipperBleService>()
        whenever(service.usesUsbCommandTransport()).thenReturn(true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenReturn(false)
        val protocol=FlipperProtocol().apply { setBleService(service) }
        confirmCli(protocol)
        assertTrue(protocol.sendTypedUsbCommand("gpio set PA7 1") is ProtocolResponse.Error)
        verify(service,times(1)).sendUsbDataOnly(any())
        verify(service,never()).sendData(any(),any(),any(),any())
    }
    @Test fun printableFirmwareErrorsAndEchoOnlyAreNotSuccess() = runBlocking<Unit> {
        for (reply in listOf("Unknown command", "Error processing GPIO", ">: gpio set PA7 1\r\n>: ")) {
            val service = mock<FlipperBleService>()
            whenever(service.usesUsbCommandTransport()).thenReturn(true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
            val protocol = FlipperProtocol().apply { setBleService(service) }
            confirmCli(protocol)
            whenever(service.sendUsbDataOnly(any())).thenAnswer {
                protocol.processIncomingData(reply.toByteArray())
                true
            }
            assertTrue(reply, protocol.sendTypedUsbCommand("gpio set PA7 1") is ProtocolResponse.Error)
            verify(service, times(1)).sendUsbDataOnly(any())
            verify(service, never()).sendData(any(), any(), any(), any())
        }
    }
    @Test fun successfulTypedReplyStripsEchoAndPrompt() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        whenever(service.usesUsbCommandTransport()).thenReturn(true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
            confirmCli(protocol)
        whenever(service.sendUsbDataOnly(any())).thenAnswer {
            protocol.processIncomingData(">: gpio read PA7\r\n1\r\n>: ".toByteArray())
            true
        }
        val result = protocol.sendTypedUsbCommand("gpio read PA7")
        org.junit.Assert.assertEquals("1", (result as ProtocolResponse.FileContent).content)
        verify(service, times(1)).sendUsbDataOnly(any())
        verify(service, never()).sendData(any(), any(), any(), any())
    }
    @Test fun unknownAndRpcUsbSessionsNeverReceiveTypedText() = runBlocking<Unit> {
        for (mode in listOf("unknown", "rpc", "combined")) {
            val service = mock<FlipperBleService>()
            whenever(service.usesUsbCommandTransport()).thenReturn(true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
            val protocol = FlipperProtocol().apply { setBleService(service) }
            if (mode == "combined") confirmCli(protocol)
            if (mode != "unknown") {
                FlipperProtocol::class.java.getDeclaredMethod("markRpcReady", String::class.java)
                    .apply { isAccessible = true }.invoke(protocol, "RPC ready")
            }
            assertTrue(mode, protocol.sendTypedUsbCommand("gpio set PA7 1") is ProtocolResponse.Error)
            verify(service, never()).sendUsbDataOnly(any())
            verify(service, never()).sendData(any(), any(), any(), any())
        }
    }
}
