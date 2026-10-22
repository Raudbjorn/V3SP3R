package com.vesper.flipper.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Firmware serial flow control is an unsigned 32-bit big-endian free-byte count. */
object SerialFlowControl {
    const val MAX_REASONABLE_BYTES = 64 * 1024
    const val CONTROL_WRITE_MAX_BYTES = 96
    fun remainingBytes(value: ByteArray): Int? {
        if (value.size != 4) return null
        val available = ByteBuffer.wrap(value).order(ByteOrder.BIG_ENDIAN).int
        return available.takeIf { it in 0..MAX_REASONABLE_BYTES }
    }
    // Small controls fail promptly at zero credits; never bypass a known capacity.
    fun waitTimeoutMs(payloadSize: Int, normalTimeoutMs: Long): Long =
        if (payloadSize in 1..CONTROL_WRITE_MAX_BYTES) minOf(400L, normalTimeoutMs) else normalTimeoutMs
}
