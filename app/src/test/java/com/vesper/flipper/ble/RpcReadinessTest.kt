package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class RpcReadinessTest {
    private fun ready(protocol: FlipperProtocol, now: Long): Boolean =
        FlipperProtocol::class.java.getDeclaredMethod("isRemoteFastPathReady", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(protocol, now) as Boolean

    @Test fun `outbound traffic alone cannot confirm RPC readiness`() {
        val protocol = FlipperProtocol()
        FlipperProtocol::class.java.getDeclaredField("lastRpcActivityAtMs").apply { isAccessible = true }.setLong(protocol, 1000L)
        assertFalse(ready(protocol, 1001L))
    }

    @Test fun `verified RPC observation allows stale capability flags until reset or expiry`() {
        val protocol = FlipperProtocol()
        FlipperProtocol::class.java.getDeclaredField("lastVerifiedRpcActivityAtMs").apply { isAccessible = true }.setLong(protocol, 1000L)
        assertTrue(ready(protocol, 1001L))
        assertFalse(ready(protocol, 20000L))
        protocol.onConnectionReset()
        assertFalse(ready(protocol, 1001L))
    }
}
