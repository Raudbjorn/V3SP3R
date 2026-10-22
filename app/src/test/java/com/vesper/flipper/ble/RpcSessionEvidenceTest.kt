package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class RpcSessionEvidenceTest {
    private suspend fun collect(protocol: FlipperProtocol): List<Flipper.Main> = suspendCoroutineUninterceptedOrReturn { continuation ->
        FlipperProtocol::class.java.getDeclaredMethod("collectRpcMainResponsesOnce", ByteArray::class.java,
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }.invoke(protocol, byteArrayOf(1), 7, 350L, continuation)
    }
    private fun response(next: Boolean) = ByteArrayOutputStream().also {
        Flipper.Main.newBuilder().setCommandId(7).setCommandStatus(Flipper.CommandStatus.OK)
            .setHasNext(next).build().writeDelimitedTo(it)
    }.toByteArray()

    @Test fun `old continuation cannot verify a reconnected route`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val sent = CompletableDeferred<Unit>()
        whenever(service.sendData(any(), any(), any())).thenAnswer { sent.complete(Unit); true }
        val protocol = FlipperProtocol().apply { setBleService(service) }
        val collector = FlipperProtocol::class.java.getDeclaredField("rawBinaryCollector").apply { isAccessible = true }
        val result = async { collect(protocol) }
        sent.await()
        val initial = collector.get(protocol)
        protocol.processIncomingData(response(true))
        withTimeout(1500L) { while (collector.get(protocol) == null || collector.get(protocol) === initial) delay(5L) }
        protocol.onConnectionReset()
        assertTrue(result.await().isEmpty())
        verify(service, never()).recordRpcResponseReceived(any())
    }

    @Test fun `correlated response in current session verifies route`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val sent = CompletableDeferred<Unit>()
        whenever(service.sendData(any(), any(), any())).thenAnswer { sent.complete(Unit); true }
        val protocol = FlipperProtocol().apply { setBleService(service) }
        val result = async { collect(protocol) }
        sent.await()
        protocol.processIncomingData(response(false))
        assertEquals(1, result.await().size)
        verify(service).recordRpcResponseReceived(any())
    }
}
