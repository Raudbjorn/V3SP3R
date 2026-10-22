package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.flipperdevices.protobuf.storage.Storage
import com.google.protobuf.ByteString
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class RpcReadCompletionTest {
    private suspend fun collect(protocol: FlipperProtocol, timeoutMs: Long): Any? {
        val method = FlipperProtocol::class.java.getDeclaredMethod(
            "collectRpcMainResponsesOnce", ByteArray::class.java, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Continuation::class.java
        ).apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            method.invoke(protocol, byteArrayOf(1), 7, timeoutMs, continuation)
        }
    }

    @Test fun `unfinished continuation is an explicit failure instead of partial success`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        whenever(service.sendData(any(), any(), any())).thenAnswer {
            protocol.processIncomingData(RpcFrameRouter.encode(Flipper.Main.newBuilder().setCommandId(7).setHasNext(true).build()))
            true
        }
        try {
            collect(protocol, 400L)
            fail("An unfinished continuation must not return partial success")
        } catch (expected: ResponseCollectionException) {
            assertTrue(expected.message!!.contains("incomplete"))
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun ready(protocol: FlipperProtocol) {
        val flow = FlipperProtocol::class.java.getDeclaredField("_cliStatus").apply { isAccessible = true }
            .get(protocol) as MutableStateFlow<CliCapabilityStatus>
        flow.value = CliCapabilityStatus(level = CliCapabilityLevel.READY, supportsRpc = true)
    }

    @Test fun `sized read preserves a fragmented reply arriving beyond old four second budget`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        ready(protocol)
        val bytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        whenever(service.sendData(any(), any(), any())).thenAnswer { invocation ->
            val request = Flipper.Main.parseDelimitedFrom(ByteArrayInputStream(invocation.getArgument(0)))
            val response = RpcFrameRouter.encode(Flipper.Main.newBuilder().setCommandId(request.commandId)
                .setStorageReadResponse(Storage.ReadResponse.newBuilder().setFile(
                    Storage.File.newBuilder().setData(ByteString.copyFrom(bytes)))).build())
            protocol.processIncomingData(response.copyOfRange(0, 2))
            launch {
                delay(4_500)
                response.copyOfRange(2, response.size).toList().chunked(32 * 1024).forEach {
                    protocol.processIncomingData(it.toByteArray())
                }
            }
            true
        }
        val result = withTimeout(10_000) { protocol.readFileBinary("/ext/test.fap", bytes.size.toLong()) }
        assertTrue("Expected full binary, got $result", result is ProtocolResponse.BinaryContent)
        assertArrayEquals(bytes, (result as ProtocolResponse.BinaryContent).data)
        verify(service, times(1)).sendData(any(), any(), any())
    }

    @Test fun `sized read rejects a terminal but truncated file and invalid size hints`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val service = mock<FlipperBleService>()
        protocol.setBleService(service)
        ready(protocol)
        whenever(service.sendData(any(), any(), any())).thenAnswer { invocation ->
            val request = Flipper.Main.parseDelimitedFrom(ByteArrayInputStream(invocation.getArgument(0)))
            val file = Storage.File.newBuilder().setData(ByteString.copyFrom(byteArrayOf(1)))
            val response = Flipper.Main.newBuilder().setCommandId(request.commandId)
                .setStorageReadResponse(Storage.ReadResponse.newBuilder().setFile(file)).build()
            protocol.processIncomingData(RpcFrameRouter.encode(response))
            true
        }
        val result = protocol.readFileBinary("/ext/test.fap", 2)
        assertTrue(result is ProtocolResponse.Error)
        assertTrue((result as ProtocolResponse.Error).message.contains("size mismatch"))
        assertTrue(protocol.readFileBinary("/ext/test.fap", Long.MAX_VALUE) is ProtocolResponse.Error)
        verify(service, times(1)).sendData(any(), any(), any())
    }

}
