package com.vesper.flipper.ble

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class RpcCollectorBoundsTest {
    @Test fun `binary collector fails explicitly after capacity rather than returning a successful prefix`() {
        assertOverflow("RawBinaryCollector", 16 * 1024 * 1024)
    }
    @Test fun `CLI collector fails explicitly after capacity rather than returning a successful prefix`() {
        assertOverflow("RawCliCollector", 256 * 1024)
    }
    @Test fun `binary collector retains storage reads larger than one frame limit`() {
        val type = Class.forName("com.vesper.flipper.ble.FlipperProtocol\$RawBinaryCollector")
        val collector = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val append = type.getDeclaredMethod("append", ByteArray::class.java).apply { isAccessible = true }
        val chunk = ByteArray(128 * 1024) { (it % 251).toByte() }
        repeat(3) { append.invoke(collector, chunk) }
        val result = type.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(collector) as ByteArray
        assertArrayEquals(chunk + chunk + chunk, result)
    }
    private fun assertOverflow(name: String, capacity: Int) {
        val type = Class.forName("com.vesper.flipper.ble.FlipperProtocol\$$name")
        val collector = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val append = type.getDeclaredMethod("append", ByteArray::class.java).apply { isAccessible = true }
        append.invoke(collector, ByteArray(capacity + 1) { 65 })
        try {
            type.getDeclaredMethod("snapshot").apply { isAccessible = true }.invoke(collector)
            fail("An overflowing response must not be returned as successful data")
        } catch (error: InvocationTargetException) {
            assertTrue(error.cause is IllegalStateException)
            assertTrue(error.cause!!.message!!.contains("buffer limit"))
        }
    }
}
