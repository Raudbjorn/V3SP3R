package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class TransportTelemetryTest {
    @Test fun `unsolicited receive never verifies the write route and reconnect clears evidence`() {
        val recorder = TransportTelemetryRecorder { 100L }
        recorder.routeChanged("BLE", "tx", "rx")
        recorder.writeAttempt("no_response")
        recorder.writeFailure("Gatt rejected write")
        recorder.received(7)
        assertEquals("Gatt rejected write", recorder.state.value.lastWriteFailure)
        assertFalse(recorder.state.value.writeRouteVerified)
        assertEquals("7 bytes", recorder.state.value.lastRxSummary)
        recorder.rpcResponseReceived(recorder.sessionId())
        assertTrue(recorder.state.value.writeRouteVerified)
        recorder.routeChanged("None", null, null)
        recorder.rpcResponseReceived(recorder.sessionId()) // late completion from the old session
        assertFalse(recorder.state.value.writeRouteVerified)
        assertNull(recorder.state.value.lastRxSummary)
        assertNull(recorder.state.value.lastWriteFailure)
        assertNull(recorder.state.value.lastWriteTypeLabel)
    }

    @Test fun `old session cannot verify a reconnect using identical characteristics`() {
        val recorder = TransportTelemetryRecorder()
        recorder.routeChanged("BLE", "tx", "rx")
        val oldSession = recorder.sessionId()
        recorder.routeChanged("None", null, null)
        recorder.routeChanged("BLE", "tx", "rx")
        recorder.rpcResponseReceived(oldSession)
        assertFalse(recorder.state.value.writeRouteVerified)
        recorder.rpcResponseReceived(recorder.sessionId())
        assertTrue(recorder.state.value.writeRouteVerified)
    }

    @Test fun `successful retry clears failure while preserving received byte count`() {
        val recorder = TransportTelemetryRecorder { 42L }
        recorder.routeChanged("USB", null, null)
        recorder.received(12)
        recorder.received(5)
        recorder.writeFailure("timeout")
        recorder.writeFailure(null)
        assertNull(recorder.state.value.lastWriteFailure)
        assertEquals(17L, recorder.state.value.receivedByteCount)
        assertEquals(42L, recorder.state.value.lastUpdatedAtMs)
    }
}
