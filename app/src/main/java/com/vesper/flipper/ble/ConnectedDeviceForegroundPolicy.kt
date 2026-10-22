package com.vesper.flipper.ble

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Connected-device FGS eligibility and permission-only USB preparation; never opens a transport. */
internal object ConnectedDeviceForegroundPolicy {
    fun canStart(bleGranted: Boolean, usbGranted: Boolean) = bleGranted || usbGranted

    suspend fun requestUsbStart(
        hasPermission: () -> Boolean,
        requestPermission: suspend () -> Boolean,
        startService: () -> Boolean
    ): Boolean {
        if (!hasPermission() && !requestPermission()) return false
        currentCoroutineContext().ensureActive()
        // Recheck the actual grant after the permission response, including detach/revocation races.
        return hasPermission() && startService()
    }
}
