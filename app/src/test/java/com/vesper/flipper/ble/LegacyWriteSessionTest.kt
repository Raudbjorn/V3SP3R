package com.vesper.flipper.ble

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers
import org.mockito.kotlin.*

class LegacyWriteSessionTest {
    private fun set(service: FlipperBleService, field: String, value: Any) {
        FlipperBleService::class.java.getDeclaredField(field).apply { isAccessible = true }.set(service, value)
    }
    private fun route(service: FlipperBleService, name: String) {
        val field = FlipperBleService::class.java.getDeclaredField("activeTransport").apply { isAccessible = true }
        field.set(service, field.type.enumConstants.first { it.toString() == name })
    }
    @Test fun `queued legacy USB write fails if transport changes before writer lock`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>(defaultAnswer = Answers.CALLS_REAL_METHODS)
        val port = mock<UsbSerialPort>()
        val mutex = Mutex(locked = true)
        set(service, "usbSerialPort", port)
        set(service, "writeMutex", mutex)
        route(service, "USB")
        doReturn(true).whenever(service).usesUsbCommandTransport()
        val write = async(start = CoroutineStart.UNDISPATCHED) { service.sendLegacyData("version\r\n".toByteArray()) }
        route(service, "BLE")
        mutex.unlock()
        assertFalse(write.await())
        verifyNoInteractions(port)
    }
}
