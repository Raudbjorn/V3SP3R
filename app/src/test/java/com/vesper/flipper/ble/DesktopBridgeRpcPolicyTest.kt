package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class DesktopBridgeRpcPolicyTest {
    @Test fun `desktop bridge diagnostics send protobuf ping without text CLI or reset`() = runBlocking {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenReturn(false)
        val protocol = FlipperProtocol()
        protocol.setBleService(service)
        val result = protocol.probeCliAvailability(force = true)
        assertFalse(result.supportsCli)
        val packets = argumentCaptor<ByteArray>()
        verify(service, atLeastOnce()).sendData(packets.capture(), any(), any())
        assertTrue(packets.allValues.isNotEmpty())
        for (packet in packets.allValues) {
            val message = Flipper.Main.parseDelimitedFrom(packet.inputStream())
            assertTrue(message.hasSystemPingRequest())
        }
        verify(service, never()).restartSerialRpc()
        Unit
    }
    @Test fun `raw CLI is rejected on desktop bridge without sending bytes`() = runBlocking {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        assertFalse(protocol.sendRawCli("version"))
        verify(service, never()).sendData(any(), any(), any())
        Unit
    }

    @Test fun `USB retains raw CLI support`() = runBlocking {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(false)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        assertTrue(protocol.sendRawCli("version"))
        val bytes = argumentCaptor<ByteArray>()
        verify(service).sendData(bytes.capture(), any(), any())
        assertArrayEquals("version\r\n".toByteArray(), bytes.firstValue)
        Unit
    }
    @Test fun `failed desktop bridge storage query never falls back to legacy framing`() = runBlocking {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(true)
        whenever(service.sendData(any(), any(), any())).thenReturn(false)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        assertTrue(protocol.listDirectory("/ext") is ProtocolResponse.Error)
        val packets = argumentCaptor<ByteArray>()
        verify(service, atLeastOnce()).sendData(packets.capture(), any(), any())
        for (packet in packets.allValues) {
            val message = Flipper.Main.parseDelimitedFrom(packet.inputStream())
            assertTrue(message.hasSystemPingRequest() || message.hasStorageListRequest())
        }
        verify(service, never()).restartSerialRpc()
        Unit
    }
}
