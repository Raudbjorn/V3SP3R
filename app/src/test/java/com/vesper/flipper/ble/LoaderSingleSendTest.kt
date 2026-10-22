package com.vesper.flipper.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class LoaderSingleSendTest {
    @Suppress("UNCHECKED_CAST")
    private fun status(protocol: FlipperProtocol, supportsRpc: Boolean = false, confirmed: Boolean = true) {
        val flow = FlipperProtocol::class.java.getDeclaredField("_cliStatus").apply { isAccessible = true }
            .get(protocol) as MutableStateFlow<CliCapabilityStatus>
        flow.value = CliCapabilityStatus(level = CliCapabilityLevel.READY, supportsCli = true, supportsRpc = supportsRpc)
        if (confirmed) FlipperProtocol::class.java.getDeclaredMethod("markCliReady", String::class.java)
            .apply { isAccessible = true }.invoke(protocol, "hardware_model : Flipper Zero")
    }

    @Test fun `ambiguous loader signal is written once without generic transport retry`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenAnswer {
            // Command echo is not acknowledgement of the signal's outcome.
            protocol.processIncomingData("loader signal 1\r\n>:".toByteArray())
            true
        }
        val result = protocol.sendLoaderCommandOnce("loader signal 1")
        assertTrue(result is ProtocolResponse.Error)
        assertTrue((result as ProtocolResponse.Error).message.contains("unconfirmed"))
        verify(service, times(1)).sendUsbDataOnly(check { assertArrayEquals("loader signal 1\r\n".toByteArray(), it) })
        verify(service).isRawCliTransportConnected()
        verifyNoMoreInteractions(service)
    }

    @Test fun `lost USB dispatch fails without falling back to BLE`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenReturn(false)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, times(1)).sendUsbDataOnly(any())
        verify(service).isRawCliTransportConnected()
        verifyNoMoreInteractions(service)
    }

    @Test fun `bare firmware ERROR response remains failure without retransmission`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenAnswer {
            protocol.processIncomingData("ERROR busy\r\n>:".toByteArray())
            true
        }
        val result = protocol.sendLoaderCommandOnce("loader signal 1")
        assertTrue(result is ProtocolResponse.Error)
        verify(service, times(1)).sendUsbDataOnly(any())
    }

    @Test fun `remembered capabilities without a confirmed current mode cannot dispatch loader`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true, confirmed = false)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }
    @Test fun `confirmed CLI recovery allows loader despite historical RPC capability`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenAnswer {
            protocol.processIncomingData("Application closed\r\n>:".toByteArray())
            true
        }
        assertTrue(protocol.cliStatus.value.supportsRpc)
        assertFalse(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, times(1)).sendUsbDataOnly(any())
    }

    @Test fun `RPC dispatch invalidates CLI even when the write fails`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenReturn(false)
        assertTrue(protocol.isConfirmedUsbCliSession())
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<ByteArray> { continuation ->
            FlipperProtocol::class.java.getDeclaredMethod("collectRawBinaryResponseAttempt", ByteArray::class.java,
                Long::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(protocol, byteArrayOf(1), 10L, continuation)
        }
        assertFalse(protocol.isConfirmedUsbCliSession())
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `reset revokes session confirmation even if stale capabilities are restored`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        status(protocol)
        assertTrue(protocol.isConfirmedUsbCliSession())
        protocol.onConnectionReset()
        status(protocol, confirmed = false)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `local CLI error text cannot confirm an unknown session`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(false)
        assertTrue(protocol.sendCliCommand("version") is ProtocolResponse.Error)
        assertFalse(protocol.isConfirmedUsbCliSession())
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `confirmed RPC mode overrides remembered CLI capability`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        FlipperProtocol::class.java.getDeclaredMethod("markRpcReady", String::class.java)
            .apply { isAccessible = true }.invoke(protocol, "RPC response")
        assertTrue(protocol.cliStatus.value.supportsCli)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `RPC session command text is not evidence of CLI readiness`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenAnswer {
            protocol.processIncomingData("Starting RPC session\r\n".toByteArray())
            true
        }
        assertTrue(protocol.sendRawCliCommand("start_rpc_session") is ProtocolResponse.FileContent)
        assertFalse(protocol.isConfirmedUsbCliSession())
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }

}
