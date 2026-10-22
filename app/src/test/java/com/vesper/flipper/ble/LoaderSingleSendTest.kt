package com.vesper.flipper.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class LoaderSingleSendTest {
    @Suppress("UNCHECKED_CAST")
    private fun status(protocol: FlipperProtocol, supportsRpc: Boolean = false) {
        val flow = FlipperProtocol::class.java.getDeclaredField("_cliStatus").apply { isAccessible = true }
            .get(protocol) as MutableStateFlow<CliCapabilityStatus>
        flow.value = CliCapabilityStatus(level = CliCapabilityLevel.READY, supportsCli = true, supportsRpc = supportsRpc)
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

    @Test fun `combined RPC CLI capability cannot dispatch a loader command`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        status(protocol, supportsRpc = true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        assertTrue(protocol.sendLoaderCommandOnce("loader close") is ProtocolResponse.Error)
        verify(service, never()).sendUsbDataOnly(any())
    }
}
