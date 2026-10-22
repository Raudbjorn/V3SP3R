package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class RpcRequestIdTest {
    @Test fun `first RPC requests have distinct nonzero correlation IDs`() {
        val protocol = FlipperProtocol()
        val next = FlipperProtocol::class.java.getDeclaredMethod("nextRpcProbeCommandId").apply { isAccessible = true }
        val first = next.invoke(protocol) as Int
        val second = next.invoke(protocol) as Int
        assertTrue(first > 0)
        assertTrue(second > 0)
        assertNotEquals(first, second)
    }
    @Test fun `RPC IDs wrap to one after the largest positive ID`() {
        val protocol = FlipperProtocol()
        FlipperProtocol::class.java.getDeclaredField("currentRequestId").apply { isAccessible = true }.setInt(protocol, Int.MAX_VALUE)
        val next = FlipperProtocol::class.java.getDeclaredMethod("nextRpcProbeCommandId").apply { isAccessible = true }
        assertEquals(Int.MAX_VALUE, next.invoke(protocol))
        assertEquals(1, next.invoke(protocol))
        assertEquals(2, next.invoke(protocol))
    }

    @Test fun `connection reset drops the previous binary response collector`() {
        val protocol = FlipperProtocol()
        val field = FlipperProtocol::class.java.getDeclaredField("rawBinaryCollector").apply { isAccessible = true }
        val constructor = field.type.getDeclaredConstructor().apply { isAccessible = true }
        field.set(protocol, constructor.newInstance())
        protocol.onConnectionReset()
        assertNull(field.get(protocol))
    }
}
