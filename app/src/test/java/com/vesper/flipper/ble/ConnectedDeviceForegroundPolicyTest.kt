package com.vesper.flipper.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectedDeviceForegroundPolicyTest {
    @Test fun usbGrantQualifiesWithoutLocationOrNearbyDevicesConsent() {
        assertTrue(ConnectedDeviceForegroundPolicy.canStart(bleGranted = false, usbGranted = true))
        assertTrue(ConnectedDeviceForegroundPolicy.canStart(bleGranted = true, usbGranted = false))
        assertFalse(ConnectedDeviceForegroundPolicy.canStart(bleGranted = false, usbGranted = false))
    }

    @Test fun requestWaitsForVerifiedUsbGrantBeforeStartingForegroundService() = runTest {
        var granted = false
        val decision = CompletableDeferred<Boolean>()
        var starts = 0
        val result = async {
            ConnectedDeviceForegroundPolicy.requestUsbStart({ granted }, { decision.await() }, { starts++; true })
        }
        runCurrent()
        assertEquals(0, starts)
        assertFalse(result.isCompleted)
        granted = true
        decision.complete(true)
        assertTrue(result.await())
        assertEquals(1, starts)
    }

    @Test fun deniedOrStaleGrantCannotStartService() = runTest {
        var starts = 0
        assertFalse(ConnectedDeviceForegroundPolicy.requestUsbStart({ false }, { false }, { starts++; true }))
        // A stale/forged permission response cannot substitute for UsbManager's current grant.
        assertFalse(ConnectedDeviceForegroundPolicy.requestUsbStart({ false }, { true }, { starts++; true }))
        assertEquals(0, starts)
    }

    @Test fun failedForegroundStartStaysInactiveAndCancellationNeverStartsService() = runTest {
        val lifecycle = ForegroundTransportLifecycle({}, {})
        assertFalse(ConnectedDeviceForegroundPolicy.requestUsbStart({ true }, { error("Already granted") }, {
            lifecycle.promote { throw SecurityException("Foreground start denied") }
        }))
        assertFalse(lifecycle.active)
        assertNull(lifecycle.whileActive { error("Must not open a USB port") })
        var starts = 0
        val decision = CompletableDeferred<Boolean>()
        val request = async {
            ConnectedDeviceForegroundPolicy.requestUsbStart({ false }, { decision.await() }, { starts++; true })
        }
        runCurrent()
        request.cancel()
        decision.complete(true)
        runCurrent()
        assertEquals(0, starts)
    }
}
