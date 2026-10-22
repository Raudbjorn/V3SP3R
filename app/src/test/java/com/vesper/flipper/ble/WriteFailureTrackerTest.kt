package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class WriteFailureTrackerTest {
    @Test fun `consuming protocol detail preserves diagnostics until a successful write`() {
        val recorder = TransportTelemetryRecorder { 1L }
        val tracker = WriteFailureTracker(recorder)
        tracker.record("write rejected")
        assertEquals("write rejected", tracker.consume())
        assertNull(tracker.consume())
        tracker.record(null) // next attempt has not succeeded yet
        assertEquals("write rejected", recorder.state.value.lastWriteFailure)
        tracker.succeeded()
        assertNull(recorder.state.value.lastWriteFailure)
    }
    @Test fun `old write completion preserves the replacement session diagnostic`() {
        val recorder = TransportTelemetryRecorder { 1L }
        val tracker = WriteFailureTracker(recorder)
        recorder.routeChanged("USB", null, null)
        val oldSession = recorder.sessionId() // captured before blocking port write
        recorder.routeChanged("None", null, null)
        recorder.routeChanged("Desktop Bridge", null, null)
        val currentSession = recorder.sessionId()
        recorder.writeAttempt("desktop_serial", currentSession)
        tracker.record("current route failed", currentSession)

        tracker.succeeded(oldSession) // old blocking write returns after reconnect
        assertEquals("current route failed", tracker.pending)
        assertEquals("current route failed", recorder.state.value.lastWriteFailure)
        tracker.record("old port closed", oldSession)
        recorder.writeAttempt("serial", oldSession)
        assertEquals("current route failed", recorder.state.value.lastWriteFailure)
        assertEquals("desktop_serial", recorder.state.value.lastWriteTypeLabel)
        assertEquals("current route failed", tracker.consume()) // old completion cannot replace a new pending reason

        tracker.succeeded(currentSession)
        assertNull(recorder.state.value.lastWriteFailure)
    }
}
