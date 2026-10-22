package com.vesper.flipper.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommandLockTest {
    @Test fun `transfer may exceed queue deadline`() = runTest {
        val mutex = Mutex()
        val result = mutex.withAcquisitionTimeout(15000, { "busy" }) { delay(20000); "uploaded" }
        assertEquals("uploaded", result)
        assertFalse(mutex.isLocked)
    }
    @Test fun `busy lock remains owned by original operation`() = runTest {
        val mutex = Mutex(locked = true)
        val result = mutex.withAcquisitionTimeout(15000, { "busy" }) { "must not execute" }
        assertEquals("busy", result)
        assertTrue(mutex.isLocked)
        mutex.unlock()
    }
    @Test fun `cancelled operation releases its lock`() = runTest {
        val mutex = Mutex()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withAcquisitionTimeout(15000, { Unit }) { awaitCancellation() }
        }
        assertTrue(mutex.isLocked)
        job.cancelAndJoin()
        assertFalse(mutex.isLocked)
    }
}
