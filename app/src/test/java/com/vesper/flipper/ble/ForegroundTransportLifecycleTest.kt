package com.vesper.flipper.ble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ForegroundTransportLifecycleTest {
    @Test fun boundOnlyOrDeniedPromotionCannotLaunchTransportWork() {
        var starts = 0
        var stops = 0
        var work = 0
        val lifecycle = ForegroundTransportLifecycle({ starts++ }, { stops++ }, Dispatchers.Unconfined)
        lifecycle.scope.launch { work++ }
        assertNull(lifecycle.whileActive { work++; Unit })
        assertFalse(lifecycle.active)
        assertFalse(lifecycle.promote { throw SecurityException("permission revoked") })
        lifecycle.scope.launch { work++ }
        assertEquals(0, work)
        assertEquals(0, starts)
        assertEquals(1, stops)
    }

    @Test fun failedPromotionCancelsExistingWorkAndUnregistersResourcesEvenWhileBound() {
        var registered = false
        var stopped = false
        val lifecycle = ForegroundTransportLifecycle({ registered = true }, { registered = false }, Dispatchers.Unconfined)
        assertTrue(lifecycle.promote {})
        val work = lifecycle.scope.launch { try { awaitCancellation() } finally { stopped = true } }
        assertFalse(lifecycle.promote { throw IllegalStateException("not eligible") })
        assertFalse(lifecycle.active)
        assertFalse(registered)
        assertNull(lifecycle.whileActive { error("Stopped bound instance must not publish a transport") })
        assertTrue(work.isCancelled)
        assertTrue(stopped)
    }

    @Test fun retryRequiresSuccessfulPromotionAndUsesFreshScope() {
        val lifecycle = ForegroundTransportLifecycle({}, {}, Dispatchers.Unconfined)
        assertTrue(lifecycle.promote {})
        val oldScope = lifecycle.scope
        lifecycle.stop()
        assertTrue(lifecycle.promote {})
        var runs = 0
        oldScope.launch { runs++ }
        lifecycle.scope.launch { runs++ }
        assertTrue(lifecycle.active)
        assertEquals(1, runs)
        lifecycle.stop()
    }
}
