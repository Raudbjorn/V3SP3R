package com.vesper.flipper.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** Linearizes permission completion/start against a newer request or disconnect. */
internal class UsbPermissionRequests {
    class Ticket internal constructor(val id: Long)
    private val lock = Any()
    private var current: Ticket? = null
    private var nextId = 0L
    private var connectionJob: Job? = null
    private var waiter: CompletableDeferred<Boolean>? = null

    fun begin(): Ticket = synchronized(lock) {
        cancelLocked()
        Ticket(++nextId).also { current = it }
    }

    fun current(id: Long): Ticket? = synchronized(lock) { current?.takeIf { it.id == id } }
    fun isCurrent(ticket: Ticket): Boolean = synchronized(lock) { current === ticket }

    fun attachJob(ticket: Ticket, job: Job): Boolean = synchronized(lock) {
        if (current !== ticket) {
            job.cancel()
            false
        } else {
            connectionJob = job
            true
        }
    }

    fun register(ticket: Ticket, pending: CompletableDeferred<Boolean>): Boolean = synchronized(lock) {
        if (current !== ticket) {
            pending.cancel()
            false
        } else {
            waiter?.cancel()
            waiter = pending
            true
        }
    }

    fun clear(ticket: Ticket, pending: CompletableDeferred<Boolean>) = synchronized(lock) {
        if (current === ticket && waiter === pending) waiter = null
    }

    fun startIfCurrent(ticket: Ticket, start: () -> Boolean): Boolean = synchronized(lock) {
        current === ticket && start()
    }

    fun finish(ticket: Ticket) = synchronized(lock) {
        if (current === ticket) {
            connectionJob = null // Successful completion must not cancel its caller.
            cancelLocked()
        }
    }

    fun cancel() = synchronized(lock) { cancelLocked() }

    private fun cancelLocked() {
        current = null
        connectionJob?.cancel()
        connectionJob = null
        waiter?.cancel()
        waiter = null
    }
}
