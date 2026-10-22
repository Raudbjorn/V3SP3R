package com.vesper.flipper.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RpcIngressBoundsTest {
    @Test fun `routing transition discards queued bytes from the preceding mode`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val lock = FlipperProtocol::class.java.getDeclaredField("ingressStateLock").apply { isAccessible = true }.get(protocol)!!
        val type = Class.forName("com.vesper.flipper.ble.FlipperProtocol\$RawBinaryCollector")
        val collector = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        synchronized(lock) {
            repeat(4) { protocol.processIncomingData("old CLI output".toByteArray()) }
            protocol.setScreenFrameRouting(true)
            FlipperProtocol::class.java.getDeclaredField("rawBinaryCollector").apply { isAccessible = true }.set(protocol, collector)
        }
        val ack = RpcFrameRouter.encode(com.flipperdevices.protobuf.Flipper.Main.newBuilder().setCommandId(7).build())
        protocol.processIncomingData(ack)
        val hasData = type.getDeclaredMethod("hasData").apply { isAccessible = true }
        kotlinx.coroutines.withTimeout(2_000) {
            while (hasData.invoke(collector) != true) kotlinx.coroutines.delay(10)
        }
        val bytes = type.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(collector) as ByteArray
        assertArrayEquals(ack, bytes)
        protocol.onConnectionReset()
    }
    @Test fun `queue overload fails commands explicitly until connection reset`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        val lock = FlipperProtocol::class.java.getDeclaredField("ingressStateLock").apply { isAccessible = true }.get(protocol)!!
        synchronized(lock) {
            // Keep the parser out while more than the bounded queue capacity arrives.
            repeat(100) { protocol.processIncomingData(byteArrayOf(8, 1)) }
        }
        val result = protocol.readFileBinary("/ext/test.bin")
        assertTrue(result is ProtocolResponse.Error)
        assertTrue((result as ProtocolResponse.Error).message.contains("capacity"))
        protocol.onConnectionReset()
        val failure = FlipperProtocol::class.java.getDeclaredField("ingressFailure").apply { isAccessible = true }
        assertNull(failure.get(protocol))
    }
    @Test fun `single oversized notification fails closed without retaining it`() = runBlocking<Unit> {
        val protocol = FlipperProtocol()
        protocol.processIncomingData(ByteArray(64 * 1024 + 1))
        val result = protocol.readFileBinary("/ext/test.bin")
        assertTrue(result is ProtocolResponse.Error)
        assertTrue((result as ProtocolResponse.Error).message.contains("capacity"))
        protocol.onConnectionReset()
    }
}
