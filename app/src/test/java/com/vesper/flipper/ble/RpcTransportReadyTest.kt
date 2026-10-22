package com.vesper.flipper.ble

import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.mockito.kotlin.*

class RpcTransportReadyTest {
    @Test fun `fresh RPC transport permits mapped command without an unrelated probe`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val protocol = FlipperProtocol().apply { setBleService(service) }
        protocol.onConnectionReset()
        assertEquals(FirmwareTransportMode.UNAVAILABLE,protocol.firmwareCompatibility.value.transportMode)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        protocol.onTransportReady()
        assertFalse(protocol.cliStatus.value.supportsRpc)
        assertEquals(FirmwareTransportMode.RPC_ONLY,protocol.firmwareCompatibility.value.transportMode)
        assertEquals(FirmwareCommandRoute.RPC_APP_BRIDGE,FirmwareCompatibilityLayer.assessCliCommand(
            protocol.firmwareCompatibility.value,"loader open NFC",true).route)
        verify(service,never()).sendData(any(),any(),any())
        // Reset must revoke readiness even if the service has not published Disconnected yet.
        protocol.onConnectionReset()
        assertEquals(FirmwareTransportMode.UNAVAILABLE,protocol.firmwareCompatibility.value.transportMode)
    }
    @Test fun `unclassified connected transport probes but disconnected remains unavailable`() {
        val service=mock<FlipperBleService>()
        val protocol=FlipperProtocol().apply { setBleService(service) }
        protocol.onTransportReady()
        assertEquals(FirmwareTransportMode.UNAVAILABLE,protocol.firmwareCompatibility.value.transportMode)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        protocol.onTransportReady()
        assertEquals(FirmwareTransportMode.PROBING,protocol.firmwareCompatibility.value.transportMode)
    }
    @Test fun `ready notification preserves confirmed explicit CLI mode`() {
        val service=mock<FlipperBleService>()
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        val protocol=FlipperProtocol().apply { setBleService(service) }
        FlipperProtocol::class.java.getDeclaredMethod("markCliReady",String::class.java)
            .apply { isAccessible=true }.invoke(protocol,"Firmware: Flipper")
        protocol.onTransportReady()
        assertEquals(FirmwareTransportMode.CLI_ONLY,protocol.firmwareCompatibility.value.transportMode)
        assertEquals(FirmwareCommandRoute.DIRECT_CLI,FirmwareCompatibilityLayer.assessCliCommand(
            protocol.firmwareCompatibility.value,"loader open NFC",true).route)
    }
}
