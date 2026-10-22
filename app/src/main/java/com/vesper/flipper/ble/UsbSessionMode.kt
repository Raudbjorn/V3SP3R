package com.vesper.flipper.ble

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** Selected only for a fresh USB connection; RPC remains the default. */
enum class UsbSessionMode { RPC, CLI }

object UsbSessionPolicy {
    fun canStartFresh(connected: Boolean, connecting: Boolean) = !connected && !connecting
    fun allowsWrite(mode: UsbSessionMode, typedCli: Boolean) =
        if (typedCli) mode == UsbSessionMode.CLI else mode == UsbSessionMode.RPC
}

/** One bounded, read-only handshake. Never sends an RPC frame or session transition. */
object UsbCliHandshake {
    suspend fun confirm(port: UsbSerialPort, stillCurrent: () -> Boolean = { true }): String = withContext(Dispatchers.IO) {
        check(stillCurrent()) { "USB connection attempt cancelled" }
        port.write("device_info\r\n".toByteArray(Charsets.UTF_8), 1000)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        var quietReads = 0
        for (attempt in 0 until 24) {
            coroutineContext.ensureActive()
            check(stillCurrent()) { "USB connection attempt cancelled" }
            val count = port.read(buffer, 120)
            require(count in 0..buffer.size) { "Invalid USB CLI read length" }
            if (count > 0) {
                require(bytes.size() + count <= 8192) { "USB CLI handshake exceeded response limit" }
                bytes.write(buffer, 0, count)
                quietReads = 0
            } else quietReads++
            val text = bytes.toString(Charsets.UTF_8.name())
            if (isDeviceInfo(text) && quietReads >= 2) return@withContext text
        }
        error("USB CLI handshake unconfirmed. Disconnect and reconnect in the desired USB mode.")
    }

    internal fun isDeviceInfo(text: String): Boolean {
        if (text.any { it.code < 32 && it !in "\r\n\t" }) return false
        val lines = text.lineSequence().map { it.trim().removePrefix(">:").trim() }.toList()
        if (lines.any { Regex("(?i)^(error|unknown command|unsupported|failed)\\b").containsMatchIn(it) }) return false
        return lines.any { Regex("^hardware_[a-z_]+\\s*:\\s*\\S+", RegexOption.IGNORE_CASE).containsMatchIn(it) } &&
            lines.any { Regex("^firmware_[a-z_]+\\s*:\\s*\\S+", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    }
}

/** Serializes publication against disconnect/new-connect intent, including late reader callbacks. */
class ConnectionAttemptOwner {
    private var generation = 0L
    @Synchronized fun begin(): Long = ++generation
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun isCurrent(token: Long): Boolean = token == generation
    @Synchronized fun publish(token: Long, action: () -> Unit): Boolean {
        if (token != generation) return false
        action()
        return true
    }
}
