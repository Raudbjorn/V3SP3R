package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class WriteFailureTrackerTest {
    @Test fun `consuming protocol detail preserves diagnostics until a successful write`() {
        val recorder = TransportTelemetryRecorder { 1L }
        val tracker = WriteFailureTracker(recorder::writeFailure)
        tracker.record("write rejected")
        assertEquals("write rejected", tracker.consume())
        assertNull(tracker.consume())
        tracker.record(null) // next attempt has not succeeded yet
        assertEquals("write rejected", recorder.state.value.lastWriteFailure)
        tracker.succeeded()
        assertNull(recorder.state.value.lastWriteFailure)
    }
}
