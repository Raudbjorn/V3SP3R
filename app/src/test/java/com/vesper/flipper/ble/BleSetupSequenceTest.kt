package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class BleSetupSequenceTest {
    private class Harness(val rejected: String? = null) {
        val events = mutableListOf<String>()
        val failures = mutableListOf<String>()
        private fun start(name: String): Boolean { events += name; return rejected != name }
        val setup = BleSetupSequence(
            requestMtu = { start("mtu") }, discoverServices = { start("services") },
            enableRx = { start("rx") }, enableOverflow = { start("overflow_notify") },
            readOverflow = { start("overflow_read") }, ready = { events += "ready" },
            failed = { failures += it }
        )
        fun throughRx() { setup.start(); setup.mtuFinished(); setup.servicesFinished() }
    }

    @Test fun `each GATT operation waits for the preceding callback`() {
        val h = Harness()
        h.setup.start()
        assertEquals(listOf("mtu"), h.events)
        h.setup.mtuFinished()
        assertEquals(listOf("mtu", "services"), h.events)
        h.setup.servicesFinished()
        assertEquals("rx", h.events.last())
        h.setup.rxFinished(true, true)
        assertEquals("overflow_notify", h.events.last())
        h.setup.overflowNotificationsFinished(true)
        assertEquals("overflow_read", h.events.last())
        assertFalse(h.events.contains("ready"))
        h.setup.overflowReadFinished(true)
        assertEquals("ready", h.events.last())
        assertTrue(h.failures.isEmpty())
    }

    @Test fun `failed subscription cannot advertise ready or start a read`() {
        val h = Harness()
        h.throughRx()
        h.setup.rxFinished(true, true)
        h.setup.overflowNotificationsFinished(false)
        h.setup.overflowReadFinished(true)
        assertEquals(BleSetupSequence.Stage.FAILED, h.setup.stage)
        assertEquals(1, h.failures.size)
        assertFalse(h.events.contains("overflow_read"))
        assertFalse(h.events.contains("ready"))
    }

    @Test fun `missing RX descriptor rejects setup`() {
        val h = Harness("rx")
        h.throughRx()
        assertEquals(BleSetupSequence.Stage.FAILED, h.setup.stage)
        assertFalse(h.events.contains("ready"))
    }

    @Test fun `rejected MTU request continues using default MTU`() {
        val h = Harness("mtu")
        h.setup.start()
        assertEquals(listOf("mtu", "services"), h.events)
        h.setup.servicesFinished()
        h.setup.rxFinished(true, false)
        assertEquals("ready", h.events.last())
    }

    @Test fun `timeout and late callbacks never revive a failed session`() {
        val h = Harness()
        h.setup.start()
        h.setup.timeout()
        h.setup.mtuFinished()
        h.setup.servicesFinished()
        h.setup.rxFinished(true, false)
        assertEquals(listOf("mtu"), h.events)
        assertEquals(1, h.failures.size)
        assertTrue(h.failures.single().contains("MTU"))
    }

    @Test fun `duplicate callbacks cannot start concurrent operations`() {
        val h = Harness()
        h.throughRx()
        h.setup.mtuFinished()
        h.setup.servicesFinished()
        h.setup.rxFinished(true, true)
        h.setup.rxFinished(true, true)
        h.setup.overflowNotificationsFinished(true)
        h.setup.overflowNotificationsFinished(true)
        h.setup.overflowReadFinished(true)
        h.setup.overflowReadFinished(true)
        h.setup.timeout()
        assertEquals(listOf("mtu", "services", "rx", "overflow_notify", "overflow_read", "ready"), h.events)
        assertTrue(h.failures.isEmpty())
    }

    @Test fun `cancelled session ignores remaining callbacks`() {
        val h = Harness()
        h.throughRx()
        h.setup.cancel()
        h.setup.rxFinished(true, false)
        assertFalse(h.events.contains("ready"))
        assertTrue(h.failures.isEmpty())
    }

    @Test fun `invalid initial flow control fails closed`() {
        val h = Harness()
        h.throughRx()
        h.setup.rxFinished(true, true)
        h.setup.overflowNotificationsFinished(true)
        h.setup.overflowReadFinished(false)
        assertEquals(BleSetupSequence.Stage.FAILED, h.setup.stage)
        assertFalse(h.events.contains("ready"))
    }
}
