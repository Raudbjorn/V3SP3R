package com.vesper.flipper.ble

import android.hardware.usb.UsbDeviceConnection
import kotlinx.coroutines.flow.MutableStateFlow
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
        queuedWrite { service -> route(service, "BLE") }
    }
    @Test fun `queued legacy USB write fails if selected mode changes before writer lock`() = runBlocking<Unit> {
        queuedWrite { service -> set(service, "usbSessionMode", UsbSessionMode.CLI) }
    }
    @Test fun `queued legacy USB write cannot follow a replacement port`() = runBlocking<Unit> {
        val replacement = mock<UsbSerialPort>()
        queuedWrite { service -> set(service, "usbSerialPort", replacement) }
        verifyNoInteractions(replacement)
    }
    private suspend fun queuedWrite(change: (FlipperBleService) -> Unit) = coroutineScope {
        val service = mock<FlipperBleService>(defaultAnswer = Answers.CALLS_REAL_METHODS)
        val port = mock<UsbSerialPort>()
        val mutex = Mutex(locked = true)
        val telemetry = TransportTelemetryRecorder()
        set(service, "telemetryRecorder", telemetry)
        set(service, "writeFailures", WriteFailureTracker(telemetry))
        set(service, "usbSerialPort", port)
        set(service, "usbDeviceConnection", mock<UsbDeviceConnection>())
        set(service, "usbReadJob", Job())
        set(service, "usbSessionMode", UsbSessionMode.RPC)
        set(service, "_connectionState", MutableStateFlow<ConnectionState>(ConnectionState.Connected("USB")))
        set(service, "writeMutex", mutex)
        route(service, "USB")
        val write = async(start = CoroutineStart.UNDISPATCHED) { service.sendLegacyData("version\r\n".toByteArray()) }
        change(service)
        mutex.unlock()
        assertFalse(write.await())
        verifyNoInteractions(port)
    }
    @Test fun `USB selected RPC is RPC only while selected CLI remains distinct`() {
        val service = mock<FlipperBleService>(defaultAnswer = Answers.CALLS_REAL_METHODS)
        route(service, "USB")
        set(service, "usbSessionMode", UsbSessionMode.RPC)
        assertTrue(service.usesRpcOnlyCommandTransport())
        set(service, "usbSessionMode", UsbSessionMode.CLI)
        assertFalse(service.usesRpcOnlyCommandTransport())
        route(service, "BLE")
        assertTrue(service.usesRpcOnlyCommandTransport())
        route(service, "DESKTOP_BRIDGE")
        assertTrue(service.usesRpcOnlyCommandTransport())
    }
}
