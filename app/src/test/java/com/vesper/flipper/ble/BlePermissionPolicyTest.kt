package com.vesper.flipper.ble

import android.Manifest
import org.junit.Assert.*
import org.junit.Test

class BlePermissionPolicyTest {
    @Test fun modernBleDoesNotRequireLocationOrNotifications() {
        for (sdk in listOf(31, 33, 34, 35)) {
            assertTrue(BlePermissionPolicy.granted(sdk) {
                it == Manifest.permission.BLUETOOTH_SCAN || it == Manifest.permission.BLUETOOTH_CONNECT
            })
        }
    }
    @Test fun everyEssentialPermissionMustBeGranted() {
        for (sdk in listOf(30, 31, 34)) {
            for (denied in BlePermissionPolicy.required(sdk)) {
                assertFalse(BlePermissionPolicy.granted(sdk) { it != denied })
            }
        }
    }
    @Test fun legacyScanningRequiresLocation() {
        assertTrue(BlePermissionPolicy.required(30).contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }
}
