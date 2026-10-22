package com.vesper.flipper.voice

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PcmFramesTest {
    @Test fun `odd network chunks preserve exact PCM frames and partial writes`() = runBlocking {
        val bytes=ByteArray(100) { it.toByte() }
        val source=object: ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b,off,minOf(3,len))
        }
        val output=ByteArrayOutputStream()
        assertEquals(100L,transferPcm16(source) { buffer, offset, length ->
            assertEquals(0,length%2);val count=minOf(2,length);output.write(buffer,offset,count);count
        })
        assertArrayEquals(bytes,output.toByteArray())
    }
    @Test fun `incomplete final frame and overlimit payload fail before invalid write`() = runBlocking {
        for ((bytes,limit) in listOf(byteArrayOf(1,2,3) to 10,byteArrayOf(1,2,3,4) to 2)) {
            var written=0
            try { transferPcm16(ByteArrayInputStream(bytes),limit) { _,_,length -> assertEquals(0,length%2);written+=length;length };fail("Expected failure") }
            catch(_:IllegalStateException) { assertTrue(written<=2) }
        }
    }
}
