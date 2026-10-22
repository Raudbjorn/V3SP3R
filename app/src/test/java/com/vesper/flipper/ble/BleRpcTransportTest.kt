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
        whenever(service.sendData(any(), any(), any(), any())).thenReturn(false)
        val protocol = FlipperProtocol()
        protocol.setBleService(service)
        val result = protocol.probeCliAvailability(force = true)
        assertFalse(result.supportsCli)
        val packets = argumentCaptor<ByteArray>()
        verify(service, atLeastOnce()).sendData(packets.capture(), any(), any(), any())
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
        verify(service, never()).sendData(any(), any(), any(), any())
        Unit
    }

    @Test fun `selected USB CLI does not permit generic raw text`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        whenever(service.isUsbCliSessionSelected()).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        assertFalse(protocol.sendRawCli("version"))
        verify(service, never()).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
    }
    @Test fun `failed BLE storage query never falls back to legacy framing`() = runBlocking<Unit> {
        rejectedStorageDoesNotUseLegacy(disconnectAfterResponse = false)
    }

    @Test fun `disconnect after storage failure cannot permit a legacy reconnect fallback`() = runBlocking<Unit> {
        rejectedStorageDoesNotUseLegacy(disconnectAfterResponse = true)
    }

    @Test fun `late failed recovery cannot transition a newly confirmed USB CLI session`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.isRawCliTransportConnected()).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        whenever(service.sendData(any(), any(), any(), any())).thenAnswer {
            protocol.onConnectionReset()
            whenever(service.isUsbCliSessionSelected()).thenReturn(true)
            whenever(service.usesRpcOnlyCommandTransport()).thenReturn(false)
            protocol.confirmExplicitUsbCliSession("hardware_model : Flipper Zero\r\nfirmware_version : 1.0")
            false
        }
        val result = protocol.recoverCliFromRpcSession()
        assertTrue(result.supportsCli)
        assertTrue(protocol.isConfirmedUsbCliSession())
        verify(service, times(1)).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
        verify(service, never()).sendLegacyData(any())
    }

    @Test fun `generic raw rejection never probes or invalidates a replacement CLI session`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val protocol = FlipperProtocol().apply { setBleService(service) }
        var replaced = false
        fun replaceWithCli() {
            protocol.onConnectionReset()
            whenever(service.isUsbCliSessionSelected()).thenReturn(true)
            whenever(service.isRawCliTransportConnected()).thenReturn(true)
            whenever(service.isCommandTransportConnected()).thenReturn(true)
            protocol.confirmExplicitUsbCliSession("hardware_model: 1\nfirmware_version: 1.4.3")
            replaced = true
        }
        // Former generic raw handling waited here and then cleared the new session
        // after its failed probe. The refused API must never enter that wait.
        whenever(service.awaitCommandTransportReady(any())).thenAnswer { replaceWithCli(); true }
        val result = protocol.sendRawCliCommand("version")
        if (!replaced) replaceWithCli()
        assertTrue(result is ProtocolResponse.Error)
        assertTrue(protocol.isConfirmedUsbCliSession())
        verify(service, never()).awaitCommandTransportReady(any())
        verify(service, never()).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
    }

    private suspend fun rejectedStorageDoesNotUseLegacy(disconnectAfterResponse: Boolean) {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.awaitCommandTransportReady(any())).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        var storageRequests = 0
        whenever(service.sendData(any(), any(), any(), any())).thenAnswer { call ->
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
