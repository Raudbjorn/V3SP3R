package com.vesper.flipper.ble

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class UsbSerialSelectionTest {
    @Test fun `missing requested device cannot fall back to another attached serial driver`() {
        val requested = mock<UsbDevice> { on { deviceId } doReturn 7 }
        val other = mock<UsbDevice> { on { deviceId } doReturn 8 }
        val driver = mock<UsbSerialDriver> { on { device } doReturn other }
        val manager = mock<UsbManager> { on { deviceList } doReturn hashMapOf<String, UsbDevice>() }
        val prober = mock<UsbSerialProber> { on { findAllDrivers(manager) } doReturn listOf(driver) }
        val service = Mockito.mock(FlipperBleService::class.java, Mockito.CALLS_REAL_METHODS)
        val resolve = FlipperBleService::class.java.getDeclaredMethod("resolveUsbSerialDriver",
            UsbManager::class.java, UsbDevice::class.java).apply { isAccessible = true }
        Mockito.mockStatic(Log::class.java).use {
            Mockito.mockConstruction(ProbeTable::class.java).use {
                Mockito.mockStatic(UsbSerialProber::class.java).use { staticProber ->
                    staticProber.`when`<UsbSerialProber> { UsbSerialProber.getDefaultProber() }.thenReturn(prober)
                    assertNull(resolve.invoke(service, manager, requested))
                    assertSame(driver, resolve.invoke(service, manager, other))
                    assertSame("Automatic selection remains available without a requested identity", driver,
                        resolve.invoke(service, manager, null))
                }
            }
        }
    }
}
