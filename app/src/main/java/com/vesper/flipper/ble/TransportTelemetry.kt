package com.vesper.flipper.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Transport metadata only: device payload contents are never retained here. */
data class TransportTelemetry(
    val transportLabel: String = "None",
    val writeCharacteristicUuid: String? = null,
    val notifyCharacteristicUuid: String? = null,
    val lastWriteTypeLabel: String? = null,
    val lastWriteFailure: String? = null,
    val writeRouteVerified: Boolean = false,
    val lastRxSummary: String? = null,
    val receivedByteCount: Long = 0L,
    val lastUpdatedAtMs: Long = 0L
) {
    companion object {
        fun idle(): TransportTelemetry = TransportTelemetry()
    }
}

internal class TransportTelemetryRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow(TransportTelemetry.idle())
    val state = mutableState.asStateFlow()

    private var session = 0L
    @Synchronized fun sessionId(): Long = session

    @Synchronized fun routeChanged(transport: String, writeUuid: String?, notifyUuid: String?) {
        mutableState.update { previous ->
            if (previous.transportLabel == transport && previous.writeCharacteristicUuid == writeUuid &&
                previous.notifyCharacteristicUuid == notifyUuid) previous
            else {
                session++
                TransportTelemetry(
                    transportLabel = transport, writeCharacteristicUuid = writeUuid,
                    notifyCharacteristicUuid = notifyUuid, lastUpdatedAtMs = clock()
                )
            }
        }
    }

    @Synchronized fun writeAttempt(type: String, expectedSession: Long? = null) {
        if (expectedSession != null && expectedSession != session) return
        mutableState.update { it.copy(lastWriteTypeLabel = type, lastUpdatedAtMs = clock()) }
    }

    @Synchronized fun writeFailure(reason: String?, expectedSession: Long? = null) {
        if (expectedSession != null && expectedSession != session) return
        mutableState.update { it.copy(lastWriteFailure = reason, lastUpdatedAtMs = clock()) }
    }

    /** Shares the route-change lock with the pending error and diagnostic publication. */
    @Synchronized fun inSession(expectedSession: Long?, block: () -> Unit) {
        if (expectedSession != null && expectedSession != session) return
        block()
    }

    @Synchronized fun received(byteCount: Int) {
        require(byteCount >= 0)
        mutableState.update {
            it.copy(lastRxSummary = "$byteCount bytes", receivedByteCount = it.receivedByteCount + byteCount,
                lastUpdatedAtMs = clock())
        }
    }

    @Synchronized fun rpcResponseReceived(expectedSession: Long) {
        if (expectedSession != session) return
        mutableState.update {
            if (it.transportLabel == "None") it
            else it.copy(writeRouteVerified = true, lastUpdatedAtMs = clock())
        }
    }
}

/** Consuming an error message must not erase the user's diagnostic history. */
internal class WriteFailureTracker(private val recorder: TransportTelemetryRecorder) {
    @Volatile var pending: String? = null
        private set

    fun record(reason: String?, expectedSession: Long? = null) = recorder.inSession(expectedSession) {
        pending = reason
        if (reason != null) recorder.writeFailure(reason)
    }

    fun consume(): String? {
        var result: String? = null
        recorder.inSession(null) {
            result = pending
            pending = null
        }
        return result
    }

    fun succeeded(expectedSession: Long? = null) = recorder.inSession(expectedSession) {
        pending = null
        recorder.writeFailure(null)
    }
}
