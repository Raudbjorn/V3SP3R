package com.vesper.flipper.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class LoaderSingleSendTest {
    private fun observedCli(protocol: FlipperProtocol): Boolean = FlipperProtocol::class.java
        .getDeclaredField("observedCommandSessionMode").apply { isAccessible = true }.get(protocol).toString() == "CLI"

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
        clearInvocations(service) // Verify command dispatch only, excluding capability setup queries.
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenAnswer {
            // Command echo is not acknowledgement of the signal's outcome.
            protocol.processIncomingData("loader signal 1\r\n>:".toByteArray())
            true
        }
        val result = protocol.sendLoaderCommandOnce("loader signal 1")
        assertTrue(result is ProtocolResponse.Error)
        assertTrue((result as ProtocolResponse.Error).message.contains("unconfirmed"))
        verify(service, times(1)).sendUsbDataOnly(check { assertArrayEquals("loader signal 1\r\n".toByteArray(), it) })
        verify(service, atLeastOnce()).isRawCliTransportConnected()
        verify(service, atLeastOnce()).isUsbCliSessionSelected()
        verifyNoMoreInteractions(service)
    }

    @Test fun `lost USB dispatch fails without falling back to BLE`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        clearInvocations(service) // Verify command dispatch only, excluding capability setup queries.
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenReturn(false)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, times(1)).sendUsbDataOnly(any())
        verify(service, atLeastOnce()).isRawCliTransportConnected()
        verify(service, atLeastOnce()).isUsbCliSessionSelected()
        verifyNoMoreInteractions(service)
    }

    @Test fun `bare firmware ERROR response remains failure without retransmission`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
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
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }
    @Test fun `confirmed CLI recovery allows loader despite historical RPC capability`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        whenever(service.sendUsbDataOnly(any())).thenAnswer {
            protocol.processIncomingData("Application closed\r\n>:".toByteArray())
            true
        }
        assertTrue(protocol.cliStatus.value.supportsRpc)
        assertFalse(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, times(1)).sendUsbDataOnly(any())
    }

    @Test fun `selected CLI rejects binary dispatch without changing confirmation`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        whenever(service.sendData(any(), any(), any(), any())).thenReturn(false)
        assertTrue(protocol.isConfirmedUsbCliSession())
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<ByteArray> { continuation ->
            FlipperProtocol::class.java.getDeclaredMethod("collectRawBinaryResponseAttempt", ByteArray::class.java,
                Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(protocol, byteArrayOf(1), 10L, true, continuation)
        }
        assertTrue(protocol.isConfirmedUsbCliSession())
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `single RPC collector refuses selected CLI mode without changing confirmation`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        var confirmedAtWrite = true
        whenever(service.sendData(any(), any(), any(), any())).thenAnswer {
            confirmedAtWrite = protocol.isConfirmedUsbCliSession()
            false
        }
        assertTrue(protocol.isConfirmedUsbCliSession())
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            FlipperProtocol::class.java.getDeclaredMethod("collectRpcMainResponsesOnce", ByteArray::class.java,
                Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                .invoke(protocol, byteArrayOf(1), 7, 10L, false, continuation)
        }
        assertTrue(confirmedAtWrite) // No write occurred in explicitly selected CLI mode.
        assertTrue(protocol.isConfirmedUsbCliSession())
        verify(service, never()).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `reset revokes session confirmation even if stale capabilities are restored`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
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
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
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
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        FlipperProtocol::class.java.getDeclaredMethod("markRpcReady", String::class.java)
            .apply { isAccessible = true }.invoke(protocol, "RPC response")
        assertTrue(protocol.cliStatus.value.supportsCli)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `generic session command cannot implicitly enter or confirm CLI`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(false)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        assertTrue(protocol.sendRawCliCommand("start_rpc_session") is ProtocolResponse.Error)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
    }

    @Test fun `multiline generic raw dispatch cannot transition the selected session`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        whenever(service.isUsbCliSessionSelected()).thenReturn(false)
        val text = "version\r\n  start_rpc_session\nhelp"
        assertFalse(protocol.sendRawCli(text))
        assertTrue(observedCli(protocol)) // rejecting text does not invent a mode transition
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
    }

}
