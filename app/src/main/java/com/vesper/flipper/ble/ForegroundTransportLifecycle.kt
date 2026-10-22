package com.vesper.flipper.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** A bound instance is inert until foreground promotion succeeds. */
internal class ForegroundTransportLifecycle(
    private val onStarted: () -> Unit,
    private val onStopped: () -> Unit,
    private val context: CoroutineContext = Dispatchers.IO
) {
    @Volatile var active = false
        private set
    @Volatile var scope = newScope().also { it.cancel() }
        private set

    private fun newScope() = CoroutineScope(context + SupervisorJob())

    @Synchronized fun promote(startForeground: () -> Unit): Boolean = try {
        startForeground()
        if (!active) {
            scope = newScope()
            active = true
            onStarted()
        }
        true
    } catch (_: SecurityException) {
        stop(); false
    } catch (_: IllegalStateException) {
        stop(); false
    }

    /** Serialize transport creation/publication with cleanup after a failed promotion. */
    @Synchronized fun <T> whileActive(block: () -> T): T? = if (active) block() else null

    @Synchronized fun stop() {
        active = false
        scope.cancel()
        onStopped()
    }
}
