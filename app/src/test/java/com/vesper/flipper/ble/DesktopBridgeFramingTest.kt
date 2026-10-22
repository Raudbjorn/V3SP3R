package com.vesper.flipper.ble

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class DesktopBridgeFramingTest {
    @Test fun networkOrderRoundTripAndCoalescing() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        DesktopBridgeFraming.write(out, byteArrayOf(1, 2, 3))
        DesktopBridgeFraming.write(out, byteArrayOf())
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3, 0, 0, 0, 0), bytes.toByteArray())
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertArrayEquals(byteArrayOf(1, 2, 3), DesktopBridgeFraming.read(input))
        assertTrue(DesktopBridgeFraming.read(input).isEmpty())
    }
    @Test fun rejectsHugeSignedAndTruncatedFrames() {
        for (size in listOf(-1, DesktopBridgeFraming.MAX_FRAME_BYTES + 1)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            assertThrows(IllegalArgumentException::class.java) { DesktopBridgeFraming.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
        }
        assertThrows(EOFException::class.java) { DesktopBridgeFraming.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 1)))) }
    }
}
