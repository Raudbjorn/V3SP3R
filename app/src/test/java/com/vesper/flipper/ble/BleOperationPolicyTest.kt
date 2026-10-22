package com.vesper.flipper.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BleOperationPolicyTest {
    @Test fun `no response writes respect ATT capacity without fragmentation`() {
        requireBleWriteFits(20,false,23)
        requireBleWriteFits(244,false,247)
        requireBleWriteFits(512,true,23)
        for ((bytes,mtu) in listOf(21 to 23,245 to 247)) {
            try { requireBleWriteFits(bytes,false,mtu); fail("oversized write accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `queued operation times out before touching target`() = runTest {
        val mutex = Mutex(locked=true)
        var touched = false
        try { mutex.withBoundedGattQueue(50) { touched=true }; fail("queue should time out") }
        catch (_: IllegalStateException) { }
        assertFalse(touched)
        assertTrue(mutex.isLocked)
    }
    @Test fun `cancellation restores remote configuration before returning`() = runTest {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            withRestoredBleSubscription(enable={events += "enable"},restore={delay(10);events += "restore"}) {
                started.complete(Unit); awaitCancellation()
            }
        }
        started.await(); job.cancelAndJoin()
        assertEquals(listOf("enable","restore"),events)
        assertTrue(job.isCancelled)
    }
    @Test fun `restore failure fails successful collection and cleanup has a deadline`() = runTest {
        try {
            withRestoredBleSubscription(50,enable={},restore={awaitCancellation()}) { listOf("sample") }
            fail("unconfirmed restoration reported success")
        } catch (failure: IllegalStateException) { assertTrue(failure.cause is TimeoutCancellationException) }
    }
    @Test fun `enable failure still attempts restoration`() = runTest {
        var restored = false
        try { withRestoredBleSubscription(enable={error("enable rejected")},restore={restored=true}) { Unit } }
        catch (_: IllegalStateException) { }
        assertTrue(restored)
    }
}
