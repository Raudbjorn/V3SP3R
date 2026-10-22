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
            Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }.invoke(protocol, byteArrayOf(1), 7, 350L, true, continuation)
    }
    private fun response(next: Boolean) = ByteArrayOutputStream().also {
        Flipper.Main.newBuilder().setCommandId(7).setCommandStatus(Flipper.CommandStatus.OK)
            .setHasNext(next).build().writeDelimitedTo(it)
    }.toByteArray()

    @Test fun `old continuation cannot verify a reconnected route`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val sent = CompletableDeferred<Unit>()
        whenever(service.sendData(any(), any(), any(), any())).thenAnswer { sent.complete(Unit); true }
        val protocol = FlipperProtocol().apply { setBleService(service) }
        val collector = FlipperProtocol::class.java.getDeclaredField("rawBinaryCollector").apply { isAccessible = true }
        whenever(service.rpcTelemetrySessionId()).thenReturn(17L)
        val result = async { runCatching { collect(protocol) } }
        sent.await()
        val initial = collector.get(protocol)
        protocol.processIncomingData(response(true))
        val size = initial.javaClass.getDeclaredMethod("size").apply { isAccessible = true }
        withTimeout(1500L) { while ((size.invoke(initial) as Int) == 0) delay(5L) }
        assertSame("continuations retain the same collector", initial, collector.get(protocol))
        protocol.onConnectionReset()
        whenever(service.rpcTelemetrySessionId()).thenReturn(18L)
        val failure = result.await().exceptionOrNull()
        assertTrue(failure.toString(), failure is ResponseCollectionException)
        assertTrue(failure!!.message.orEmpty().contains("Connection reset"))
        verify(service, never()).recordRpcResponseReceived(any())
    }

    @Test fun `correlated response publishes only the captured session token`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        val sent = CompletableDeferred<Unit>()
        whenever(service.sendData(any(), any(), any(), any())).thenAnswer { sent.complete(Unit); true }
        val protocol = FlipperProtocol().apply { setBleService(service) }
        whenever(service.rpcTelemetrySessionId()).thenReturn(23L)
        val result = async { collect(protocol) }
        sent.await()
        // The transport token is captured before waiting, never looked up at publication.
        whenever(service.rpcTelemetrySessionId()).thenReturn(24L)
        protocol.processIncomingData(response(false))
        assertEquals(1, result.await().size)
        verify(service).recordRpcResponseReceived(23L)
    }
}
