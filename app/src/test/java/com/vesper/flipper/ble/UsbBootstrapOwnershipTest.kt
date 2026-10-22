package com.vesper.flipper.ble

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class UsbBootstrapOwnershipTest {
    private fun field(service: FlipperBleService, name: String, value: Any?) {
        FlipperBleService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
    private fun service(port: UsbSerialPort, mode: UsbSessionMode): FlipperBleService {
        val service = mock<FlipperBleService>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val telemetry = TransportTelemetryRecorder()
        field(service, "telemetryRecorder", telemetry)
        field(service, "writeFailures", WriteFailureTracker(telemetry))
        field(service, "usbSessionLock", Any())
        field(service, "usbSerialPort", port)
        field(service, "usbSessionMode", mode)
        val transport = FlipperBleService::class.java.getDeclaredField("activeTransport")
        field(service, "activeTransport", transport.type.enumConstants.first { it.toString() == "USB" })
        return service
    }
    @Test fun stalePortAndCliModeCannotPublishRpcBootstrapEffects() {
        val oldPort = mock<UsbSerialPort>()
        val newPort = mock<UsbSerialPort>()
        val service = service(oldPort, UsbSessionMode.RPC)
        var resets = 0
        assertTrue(service.withCurrentUsbRpcPort(oldPort) { resets++ })
        field(service, "usbSerialPort", newPort)
        assertFalse(service.withCurrentUsbRpcPort(oldPort) { resets++ })
        field(service, "usbSessionMode", UsbSessionMode.CLI)
        assertFalse(service.withCurrentUsbRpcPort(newPort) { resets++ })
        assertEquals(1, resets)
    }
    @Test fun disconnectCancelsPublishedBootstrapBeforeClearingPort() {
        val port = mock<UsbSerialPort>()
        val service = service(port, UsbSessionMode.RPC)
        val bootstrap = Job()
        field(service, "usbBootstrapJob", bootstrap)
        FlipperBleService::class.java.getDeclaredMethod("disconnectUsbTransport",
            Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, false, null, true)
        assertTrue(bootstrap.isCancelled)
        assertFalse(service.withCurrentUsbRpcPort(port) { error("Disconnected bootstrap cannot reset protocol") })
        verify(port).close()
    }
}
