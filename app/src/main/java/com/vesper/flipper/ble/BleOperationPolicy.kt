package com.vesper.flipper.ble

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal fun requireBleWriteFits(bytes: Int, withResponse: Boolean, mtu: Int) {
    require(bytes in 1..512) { "BLE payload must be 1..512 bytes" }
    require(withResponse || bytes <= mtu.coerceIn(23, 517) - 3) {
        "No-response payload exceeds negotiated ATT capacity (${mtu.coerceIn(23, 517) - 3} bytes); no bytes sent"
    }
}

internal suspend fun <T> Mutex.withBoundedGattQueue(timeoutMs: Long, block: suspend () -> T): T {
    val owner = Any()
    try {
        val acquired = withTimeoutOrNull(timeoutMs) { lock(owner); true } ?: false
        check(acquired) { "Phone BLE queue busy; no connection attempted" }
        return block()
    } finally {
        // Timeout/cancellation can win after ownership is granted but before lock returns.
        if (holdsLock(owner)) unlock(owner)
    }
}

/** Restore remote configuration even on cancellation; cleanup itself has a fixed deadline. */
internal suspend fun <T> withRestoredBleSubscription(
    restoreTimeoutMs: Long = 3_000L,
    enable: suspend () -> Unit,
    restore: suspend () -> Unit,
    collect: suspend () -> T,
): T {
    var primary: Throwable? = null
    try {
        enable()
        return collect()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try { withContext(NonCancellable) { withTimeout(restoreTimeoutMs) { restore() } } }
        catch (failure: Throwable) {
            if (primary != null) primary.addSuppressed(failure)
            else throw IllegalStateException("Remote notification configuration could not be restored", failure)
        }
    }
}

internal class BleOperationTimeoutException : java.io.IOException(
    "Phone BLE operation timed out; outcome may be unknown. Inspect the target before retrying."
)

/** Consume only this operation's deadline; enclosing cancellation must still propagate. */
internal suspend fun <T> withBleOperationTimeout(timeoutMs: Long, block: suspend () -> T): T {
    data class Completed<T>(val value: T)
    val completed = withTimeoutOrNull(timeoutMs) { Completed(block()) }
        ?: throw BleOperationTimeoutException()
    return completed.value
}
