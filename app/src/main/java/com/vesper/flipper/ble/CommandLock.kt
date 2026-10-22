package com.vesper.flipper.ble

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T> Mutex.withAcquisitionTimeout(
    timeoutMs: Long,
    onTimeout: () -> T,
    block: suspend () -> T
): T {
    var acquired = false
    try {
        val ready = withTimeoutOrNull(timeoutMs) {
            lock()
            acquired = true
            true
        } ?: false
        if (!ready) return onTimeout()
        return block()
    } finally {
        if (acquired) unlock()
    }
}
