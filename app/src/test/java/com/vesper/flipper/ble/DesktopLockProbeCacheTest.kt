package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class DesktopLockProbeCacheTest {
    @Test fun `acknowledged unsupported probe is cached but timeout does not erase support knowledge`() {
        val protocol = FlipperProtocol()
        val method = FlipperProtocol::class.java.getDeclaredMethod("applyDesktopLockReading", DesktopLockStatus.Reading::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        val support = FlipperProtocol::class.java.getDeclaredField("desktopLockProbeSupported").apply { isAccessible = true }
        val checked = FlipperProtocol::class.java.getDeclaredField("lastDesktopLockProbeAtMs").apply { isAccessible = true }
        method.invoke(protocol, null, 100L)
        assertNull(support.get(protocol))
        assertEquals(0L, checked.getLong(protocol))
        method.invoke(protocol, DesktopLockStatus.Reading.Unsupported, 200L)
        assertEquals(false, support.get(protocol))
        assertEquals(200L, checked.getLong(protocol))
        method.invoke(protocol, null, 300L)
        assertEquals(false, support.get(protocol))
        assertEquals(200L, checked.getLong(protocol))
        method.invoke(protocol, DesktopLockStatus.Reading.Unknown, 350L)
        assertEquals(200L, checked.getLong(protocol))
        assertEquals(false, method.invoke(protocol, DesktopLockStatus.Reading.Unlocked, 400L))
        assertEquals(true, support.get(protocol))
    }
}
