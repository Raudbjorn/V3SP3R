package com.vesper.flipper.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.UUID

class PhoneBleBoundaryTest {
    @Test fun `duplicate UUID instances in one service are rejected`() {
        val id = UUID.randomUUID()
        val first = mock<BluetoothGattCharacteristic> { on { uuid } doReturn id }
        val second = mock<BluetoothGattCharacteristic> { on { uuid } doReturn id }
        val service = mock<BluetoothGattService> {
            on { uuid } doReturn UUID.randomUUID()
            on { characteristics } doReturn listOf(first, second)
            on { getCharacteristic(id) } doReturn first
        }
        val gatt = mock<BluetoothGatt> { on { services } doReturn listOf(service) }
        val recon = BleReconService(mock())
        val method = BleReconService::class.java.getDeclaredMethod("findCharacteristic", BluetoothGatt::class.java, UUID::class.java)
            .apply { isAccessible = true }
        try { method.invoke(recon, gatt, id); fail("ambiguous UUID accepted") }
        catch (e: java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalArgumentException) }
    }
}
