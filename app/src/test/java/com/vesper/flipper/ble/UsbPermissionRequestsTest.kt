package com.vesper.flipper.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbPermissionRequestsTest {
    @Test fun `queued permission ticket captures mode and supersession cannot change it`() {
        val requests = UsbPermissionRequests()
        val cli = requests.begin(UsbSessionMode.CLI)
        assertEquals(UsbSessionMode.CLI, requests.current(cli.id)!!.mode)
        val rpc = requests.begin()
        assertEquals(UsbSessionMode.RPC, rpc.mode)
        assertEquals(UsbSessionMode.CLI, cli.mode)
        assertNull(requests.current(cli.id))
        assertFalse(requests.startIfCurrent(cli) { error("Old CLI intent must not connect") })
        assertTrue(requests.startIfCurrent(rpc) { requests.current(rpc.id)!!.mode == UsbSessionMode.RPC })
    }

    @Test fun `superseded request cannot start service even if permission completes late`() = runTest {
        val requests = UsbPermissionRequests()
        val old = requests.begin()
        val oldGrant = CompletableDeferred<Boolean>()
        assertTrue(requests.register(old, oldGrant))
        val latest = requests.begin()
        assertTrue(oldGrant.isCancelled)
        assertFalse(requests.startIfCurrent(old) { error("Stale request cannot start") })
        // A late old finally must not erase the new request or its waiter.
        val newGrant = CompletableDeferred<Boolean>()
        assertTrue(requests.register(latest, newGrant))
        requests.clear(old, oldGrant)
        requests.finish(old)
        assertFalse(newGrant.isCancelled)
        assertTrue(requests.startIfCurrent(latest) { true })
    }

    @Test fun `disconnect invalidates completed grants and pending registrations`() = runTest {
        val requests = UsbPermissionRequests()
        val request = requests.begin()
        val grant = CompletableDeferred<Boolean>()
        assertTrue(requests.register(request, grant))
        grant.complete(true)
        requests.clear(request, grant)
        requests.cancel()
        assertFalse(requests.startIfCurrent(request) { error("Disconnect revokes request") })
        val late = CompletableDeferred<Boolean>()
        assertFalse(requests.register(request, late))
        assertTrue(late.isCancelled)
    }

    @Test fun `permission response after supersession cannot pass foreground policy start`() = runTest {
        val requests = UsbPermissionRequests()
        val old = requests.begin()
        val response = CompletableDeferred<Boolean>()
        var granted = false
        var starts = 0
        val attempt = async {
            ConnectedDeviceForegroundPolicy.requestUsbStart({ granted }, { response.await() }, {
                requests.startIfCurrent(old) { starts++; true }
            })
        }
        runCurrent()
        requests.begin()
        granted = true
        response.complete(true)
        assertFalse(attempt.await())
        assertEquals(0, starts)
    }
    @Test fun `queued service intent loses ownership after newer request or disconnect`() {
        val requests = UsbPermissionRequests()
        val queued = requests.begin()
        assertSame(queued, requests.current(queued.id))
        val newer = requests.begin()
        assertNull(requests.current(queued.id))
        assertSame(newer, requests.current(newer.id))
        // Cleanup from the queued old intent cannot retire the newer ticket.
        requests.finish(queued)
        assertSame(newer, requests.current(newer.id))
        requests.cancel()
        assertNull(requests.current(newer.id))
    }

    @Test fun `supersession cancels connecting job and prevents stale publication`() = runTest {
        val requests = UsbPermissionRequests()
        val old = requests.begin()
        val connecting = async { kotlinx.coroutines.awaitCancellation() }
        assertTrue(requests.attachJob(old, connecting))
        val newer = requests.begin()
        assertTrue(connecting.isCancelled)
        assertFalse(requests.startIfCurrent(old) { error("Stale port must not be published") })
        assertTrue(requests.startIfCurrent(newer) { true })
        requests.finish(old)
        assertTrue(requests.isCurrent(newer))
        requests.finish(newer)
    }

    @Test fun `late blocking connection failure cannot erase newer transport state`() {
        val requests = UsbPermissionRequests()
        val old = requests.begin()
        var publishedTransport = "old candidate"
        try {
            // Model a blocking USB call finishing with IOException after a new request publishes.
            val newer = requests.begin()
            requests.startIfCurrent(newer) { publishedTransport = "new connected port"; true }
            throw java.io.IOException("old device detached")
        } catch (_: java.io.IOException) {
            assertFalse(requests.startIfCurrent(old) { publishedTransport = "disconnected"; true })
        } finally {
            requests.finish(old)
        }
        assertEquals("new connected port", publishedTransport)
    }

}
