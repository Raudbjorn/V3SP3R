package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class BleRpcTransportTest {
    @Test fun `BLE diagnostics send protobuf ping without text CLI or reset`() = runBlocking {
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
    @Test fun `raw CLI is rejected on BLE without sending bytes`() = runBlocking {
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
        whenever(service.sendLegacyData(any())).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        assertTrue(protocol.sendRawCli("version"))
        val bytes = argumentCaptor<ByteArray>()
        verify(service).sendLegacyData(bytes.capture())
        assertArrayEquals("version\r\n".toByteArray(), bytes.firstValue)
        Unit
    }
    @Test fun `failed BLE storage query never falls back to legacy framing`() = runBlocking<Unit> {
        rejectedStorageDoesNotUseLegacy(disconnectAfterResponse = false)
    }

    @Test fun `disconnect after storage failure cannot permit a legacy reconnect fallback`() = runBlocking<Unit> {
        rejectedStorageDoesNotUseLegacy(disconnectAfterResponse = true)
    }

    private suspend fun rejectedStorageDoesNotUseLegacy(disconnectAfterResponse: Boolean) {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        var storageRequests = 0
        whenever(service.sendData(any(), any(), any())).thenAnswer { call ->
            val request = Flipper.Main.parseDelimitedFrom(call.getArgument<ByteArray>(0).inputStream())
            val storage = request.hasStorageListRequest()
            assertTrue(request.hasSystemPingRequest() || storage)
            if (storage) {
                storageRequests++
                if (disconnectAfterResponse) whenever(service.usesRpcOnlyCommandTransport()).thenReturn(false)
            }
            val reply = Flipper.Main.newBuilder().setCommandId(request.commandId)
                .setCommandStatus(if (storage) Flipper.CommandStatus.ERROR_NOT_IMPLEMENTED else Flipper.CommandStatus.OK).build()
            protocol.processIncomingData(ByteArrayOutputStream().also { reply.writeDelimitedTo(it) }.toByteArray())
            true
        }
        assertTrue(protocol.listDirectory("/ext") is ProtocolResponse.Error)
        assertEquals(1, storageRequests)
        verify(service, never()).sendLegacyData(any())
        verify(service, never()).restartSerialRpc()
    }
}
