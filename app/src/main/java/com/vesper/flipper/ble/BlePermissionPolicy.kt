package com.vesper.flipper.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Notification consent and modern location consent are independent of BLE access. */
object BlePermissionPolicy {
    fun required(sdk: Int): List<String> = if (sdk >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun granted(sdk: Int, check: (String) -> Boolean): Boolean = required(sdk).all(check)

    fun hasPermissions(context: Context): Boolean = granted(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
