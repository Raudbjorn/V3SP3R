package com.vesper.flipper.ble

import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a socket and its associated service state under one monitor. */
internal class DesktopBridgeSession {
    private var epoch = 0L
    private var socket: Socket? = null
    @Synchronized fun generation(): Long = epoch
    @Synchronized fun current(): Socket? = socket

    @Synchronized fun install(expectedGeneration: Long, candidate: Socket, onInstall: () -> Unit): Boolean {
        if (epoch != expectedGeneration) {
            candidate.close()
            return false
        }
        runCatching { socket?.close() }
        socket = candidate
        onInstall()
        return true
    }

    @Synchronized fun close(expected: Socket? = null, expectedGeneration: Long? = null, onClose: () -> Unit): Boolean {
        if (expected != null && socket !== expected) return false
        if (expectedGeneration != null && epoch != expectedGeneration) return false
        epoch++
        runCatching { socket?.close() }
        socket = null
        onClose()
        return true
    }

    @Synchronized fun <T> ifCurrent(expected: Socket, action: () -> T): T? =
        if (socket === expected) action() else null
}

/** Closing the exact socket interrupts blocking JVM writes; coroutine cancellation alone cannot. */
internal object DesktopBridgeIo {
    private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "desktop-bridge-deadline").apply { isDaemon = true }
    }

    fun <T> withDeadline(socket: Socket, timeoutMs: Long = 5_000, action: () -> T): T {
        val expired = AtomicBoolean(false)
        val timeout = deadlines.schedule({
            expired.set(true)
            runCatching { socket.close() }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val result = action()
            if (expired.get()) throw SocketTimeoutException("Desktop Bridge I/O timed out")
            return result
        } finally {
            timeout.cancel(false)
        }
    }

    fun authenticate(socket: Socket, pairingCode: String) = withDeadline(socket) {
        require(pairingCode.matches(Regex("[0-9a-fA-F]{64}"))) { "Enter the 64-character pairing code from the host" }
        val token = pairingCode.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        socket.getOutputStream().write(token)
        socket.getOutputStream().flush()
        require(socket.getInputStream().read() == 1) { "Bridge pairing failed" }
    }
}
