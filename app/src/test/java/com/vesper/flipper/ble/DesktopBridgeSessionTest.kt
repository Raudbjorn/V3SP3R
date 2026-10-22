package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopBridgeSessionTest {
    private class TrackedSocket : Socket() {
        var closedByOwner = false
        override fun close() { closedByOwner = true }
    }

    @Test fun replacementClosesOldSocketAndStaleFailureCannotClearNewState() {
        val owner = DesktopBridgeSession()
        val first = TrackedSocket()
        val second = TrackedSocket()
        var state = ""
        owner.install(owner.generation(), first) { state = "first" }
        owner.install(owner.generation(), second) { state = "second" }
        assertTrue(first.closedByOwner)
        assertFalse(owner.close(first) { state = "disconnected" })
        assertSame(second, owner.current())
        assertFalse(second.closedByOwner)
        assertEquals("second", state)
        assertNull(owner.ifCurrent(first) { error("stale response delivered") })
    }

    @Test fun disconnectRejectsInFlightConnectionAndItsDelayedFailure() {
        val owner = DesktopBridgeSession()
        val generation = owner.generation()
        owner.close { }
        val candidate = TrackedSocket()
        assertFalse(owner.install(generation, candidate) { error("stale state installed") })
        assertTrue(candidate.closedByOwner)
        assertFalse(owner.close(expectedGeneration = generation) { error("stale failure published") })
    }

    @Test fun closeStillClearsOwnershipWhenSocketCloseThrows() {
        val owner = DesktopBridgeSession()
        val broken = object : Socket() { override fun close() { throw java.io.IOException("closed") } }
        owner.install(owner.generation(), broken) { }
        var cleared = false
        owner.close { cleared = true }
        assertTrue(cleared)
        assertNull(owner.current())
    }

    @Test fun deadlineClosesSocketAndUnblocksStalledIo() {
        val released = CountDownLatch(1)
        val socket = object : Socket() {
            override fun close() { released.countDown() }
            override fun getOutputStream() = object : OutputStream() {
                override fun write(value: Int) { check(released.await(2, TimeUnit.SECONDS)) }
            }
        }
        try {
            DesktopBridgeIo.withDeadline(socket, 25) { socket.getOutputStream().write(1) }
            fail("Expected bounded timeout")
        } catch (_: SocketTimeoutException) { }
        assertEquals(0, released.count)
    }

    @Test fun pairingUsesExactTokenAndRequiresHostAcknowledgment() {
        val output = ByteArrayOutputStream()
        var ack = 1
        val socket = object : Socket() {
            override fun getOutputStream() = output
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf(ack.toByte()))
        }
        DesktopBridgeIo.authenticate(socket, "ab".repeat(32))
        assertArrayEquals(ByteArray(32) { 0xab.toByte() }, output.toByteArray())
        ack = 0
        try { DesktopBridgeIo.authenticate(socket, "ab".repeat(32)); fail("Unpaired connection accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
