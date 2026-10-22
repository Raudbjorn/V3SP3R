package com.vesper.flipper.ble

import java.io.DataInputStream
import java.io.DataOutputStream

/** Network-order length framing around the unmodified Flipper RPC byte stream. */
internal object DesktopBridgeFraming {
    const val MAX_FRAME_BYTES = 1024 * 1024

    fun read(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_FRAME_BYTES) { "Invalid bridge frame size: $size" }
        return ByteArray(size).also { input.readFully(it) }
    }

    fun write(output: DataOutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) { "Bridge frame exceeds 1 MiB" }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }
}
