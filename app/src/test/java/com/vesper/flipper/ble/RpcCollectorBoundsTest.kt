package com.vesper.flipper.ble

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class RpcCollectorBoundsTest {
    @Test fun `binary collector fails explicitly after capacity rather than returning a successful prefix`() {
        assertOverflow("RawBinaryCollector")
    }
    @Test fun `CLI collector fails explicitly after capacity rather than returning a successful prefix`() {
        assertOverflow("RawCliCollector")
    }
    private fun assertOverflow(name: String) {
        val type = Class.forName("com.vesper.flipper.ble.FlipperProtocol\$$name")
        val collector = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val append = type.getDeclaredMethod("append", ByteArray::class.java).apply { isAccessible = true }
        append.invoke(collector, ByteArray(256 * 1024 + 1) { 65 })
        try {
            type.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(collector)
            fail("An overflowing response must not be returned as successful data")
        } catch (error: InvocationTargetException) {
            assertTrue(error.cause is IllegalStateException)
            assertTrue(error.cause!!.message!!.contains("buffer limit"))
        }
    }
}
