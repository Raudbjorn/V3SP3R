package com.vesper.flipper.ble

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class UsbSessionModeTest {
    @Test fun connectionModesCannotBeChangedOnLiveOrConnectingSession() {
        assertTrue(UsbSessionPolicy.canStartFresh(false, false))
        assertFalse(UsbSessionPolicy.canStartFresh(true, false))
        assertFalse(UsbSessionPolicy.canStartFresh(false, true))
    }
    @Test fun cliAndRpcRoutesAreMutuallyExclusive() {
        assertTrue(UsbSessionPolicy.allowsWrite(UsbSessionMode.RPC, false))
        assertFalse(UsbSessionPolicy.allowsWrite(UsbSessionMode.RPC, true))
        assertTrue(UsbSessionPolicy.allowsWrite(UsbSessionMode.CLI, true))
        assertFalse(UsbSessionPolicy.allowsWrite(UsbSessionMode.CLI, false))
    }
    @Test fun cliHandshakeUsesOneReadOnlyCommandAndAcceptsIdentity() = runBlocking<Unit> {
        val port = mock<UsbSerialPort>()
        val identity = "device_info\r\nhardware_model: 1\r\nfirmware_version: 1.4.3\r\n>: ".toByteArray()
        var delivered = false
        whenever(port.read(any(), any())).thenAnswer { call ->
            if (delivered) 0 else {
                identity.copyInto(call.getArgument<ByteArray>(0)); delivered = true; identity.size
            }
        }
        assertTrue(UsbCliHandshake.confirm(port).contains("hardware_model"))
        verify(port, times(1)).write(eq("device_info\r\n".toByteArray()), eq(1000))
        verify(port, atMost(24)).read(any(), eq(120))
    }
    @Test fun cliHandshakeRejectsEchoErrorsAndBinary() {
        for (reply in listOf("device_info\r\n>: ", "Unknown command", "hardware_model: 1\nfirmware_version: 1\nError: failed", "\u0000hardware_model: 1\nfirmware_version: 1")) {
            assertFalse(reply, UsbCliHandshake.isDeviceInfo(reply))
        }
    }
    @Test fun failedHandshakeIsBoundedAndDoesNotTransitionOrRetry() = runBlocking<Unit> {
        val port = mock<UsbSerialPort>()
        whenever(port.read(any(), any())).thenReturn(0)
        try {
            UsbCliHandshake.confirm(port)
            fail("Handshake must fail closed")
        } catch (_: IllegalStateException) { }
        verify(port, times(1)).write(eq("device_info\r\n".toByteArray()), eq(1000))
        verify(port, times(24)).read(any(), eq(120))
    }
    @Test fun oversizedHandshakeStopsBeforeAccumulatingUnboundedData() = runBlocking<Unit> {
        val port = mock<UsbSerialPort>()
        whenever(port.read(any(), any())).thenAnswer { call ->
            call.getArgument<ByteArray>(0).fill('x'.code.toByte())
            1024
        }
        try {
            UsbCliHandshake.confirm(port)
            fail("Oversized identity must be rejected")
        } catch (_: IllegalArgumentException) { }
        verify(port, times(1)).write(any(), eq(1000))
        verify(port, times(9)).read(any(), eq(120))
    }
    @Test fun lateHandshakeAndReaderCannotPublishIntoNewConnection() {
        val owner = ConnectionAttemptOwner()
        val stale = owner.begin()
        owner.invalidate() // disconnect / competing BLE connect
        val fresh = owner.begin()
        var state = "connecting"
        assertTrue(owner.publish(fresh) { state = "fresh connected" })
        for (lateEvent in listOf("handshake success", "reader bytes", "reader failure")) {
            assertFalse(owner.publish(stale) { state = lateEvent })
            assertEquals("fresh connected", state)
        }
        assertFalse(owner.isCurrent(stale))
    }
    @Test fun cancelledHandshakeDoesNotSendOrChangeSession() = runBlocking<Unit> {
        val port = mock<UsbSerialPort>()
        try {
            UsbCliHandshake.confirm(port) { false }
            fail("Cancelled handshake must fail closed")
        } catch (_: IllegalStateException) { }
        verifyNoInteractions(port)
    }
}
