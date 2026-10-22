package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class RpcProductionRoutingTest {
    private fun ready(protocol: FlipperProtocol): Boolean = FlipperProtocol::class.java
        .getDeclaredMethod("isRemoteFastPathReady", Long::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(protocol, System.currentTimeMillis()) as Boolean

    @Test fun `connected BLE publishes RPC route after a failed capability probe`() {
        val service = mock<FlipperBleService>()
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        FlipperProtocol::class.java.getDeclaredMethod("markCliUnavailable", String::class.java)
            .apply { isAccessible = true }.invoke(protocol, "probe failed")
        val profile = protocol.firmwareCompatibility.value
        assertEquals(FirmwareTransportMode.RPC_ONLY, profile.transportMode)
        assertFalse(profile.supportsRpc)
        assertEquals(FirmwareCommandRoute.RPC_APP_BRIDGE,
            FirmwareCompatibilityLayer.assessCliCommand(profile, "loader open NFC", true).route)
        whenever(service.isCommandTransportConnected()).thenReturn(false)
        protocol.onConnectionReset()
        assertEquals(FirmwareTransportMode.UNAVAILABLE, protocol.firmwareCompatibility.value.transportMode)
    }

    @Test fun `reset invalidates evidence waiting to publish`() {
        val protocol = FlipperProtocol()
        protocol.onConnectionReset()
        val accepted = FlipperProtocol::class.java.getDeclaredMethod("recordVerifiedRpcResponse", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(protocol, 0) as Boolean
        assertFalse(accepted)
        assertFalse(ready(protocol))
    }

    private suspend fun collect(protocol: FlipperProtocol): List<Flipper.Main> = suspendCoroutineUninterceptedOrReturn { c ->
        FlipperProtocol::class.java.getDeclaredMethod("collectRpcMainResponsesOnce", ByteArray::class.java,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }.invoke(protocol, byteArrayOf(1), 7, 350L, c)
    }
    private fun response(id: Int, next: Boolean = false) = ByteArrayOutputStream().also {
        Flipper.Main.newBuilder().setCommandId(id).setCommandStatus(Flipper.CommandStatus.OK)
            .setHasNext(next).build().writeDelimitedTo(it)
    }.toByteArray()

    @Test fun `correlated collection establishes verified readiness`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val sent = CompletableDeferred<Unit>()
        whenever(service.sendData(any(), any(), any())).thenAnswer { sent.complete(Unit); true }
        val protocol = FlipperProtocol().apply { setBleService(service) }
        val result = async { collect(protocol) }
        sent.await()
        protocol.processIncomingData(response(7))
        assertEquals(1, result.await().size)
        assertTrue(ready(protocol))
        protocol.onConnectionReset()
        assertFalse(ready(protocol))
    }

    @Test fun `chunked write acknowledgement establishes verified readiness`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val protocol = FlipperProtocol().apply { setBleService(service) }
        var chunks = 0
        whenever(service.sendData(any(), any(), any())).thenAnswer { call ->
            val request = Flipper.Main.parseDelimitedFrom(call.getArgument<ByteArray>(0).inputStream())
            assertTrue(request.hasStorageWriteRequest())
            chunks++
            if (!request.hasNext) protocol.processIncomingData(response(request.commandId))
            true
        }
        val result: ProtocolResponse? = suspendCoroutineUninterceptedOrReturn { c ->
            FlipperProtocol::class.java.getDeclaredMethod("sendRpcWriteChunkedLocked", String::class.java,
                ByteArray::class.java, kotlin.coroutines.Continuation::class.java)
                .apply { isAccessible = true }.invoke(protocol, "/ext/test.txt", ByteArray(2048), c)
        }
        assertTrue(result is ProtocolResponse.Success)
        assertTrue(chunks > 1)
        assertTrue(ready(protocol))
    }
}
