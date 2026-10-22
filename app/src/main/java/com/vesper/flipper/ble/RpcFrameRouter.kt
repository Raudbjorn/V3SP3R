package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.screen.Gui
import java.io.ByteArrayOutputStream

/**
 * Splits a Flipper RPC byte stream into screen frames and everything else.
 * Screen frames are unsolicited (command id 0) and must not sit in a command
 * collector, or the collector never goes quiet.
 */
object RpcFrameRouter {
    const val MAX_FRAME_SIZE = 256 * 1024

    data class PeelResult(
        val screenFrames: List<Gui.ScreenFrame>,
        /** Complete non-screen RPC frames, still length-prefixed. */
        val commandFrames: ByteArray,
        /** Bytes that were not a recognized RPC frame. */
        val passthrough: ByteArray,
        val remainder: ByteArray,
        val resynchronizing: Boolean = false
    )

    fun encode(main: Flipper.Main): ByteArray {
        val body = main.toByteArray()
        val envelope = ByteArrayOutputStream()
        writeVarint(envelope, body.size.toLong())
        envelope.write(body)
        return envelope.toByteArray()
    }

    fun peel(buffer: ByteArray, maxFrameSize: Int = MAX_FRAME_SIZE, resynchronizing: Boolean = false): PeelResult {
        var seeking = resynchronizing
        val screenFrames = mutableListOf<Gui.ScreenFrame>()
        val commandFrames = ByteArrayOutputStream()
        val passthrough = ByteArrayOutputStream()
        var offset = 0
        while (offset < buffer.size) {
            val lengthRead = readVarint(buffer, offset)
            if (lengthRead == null) {
                return result(screenFrames, commandFrames, passthrough, buffer, offset, seeking)
            }
            val frameLength = lengthRead.value.toInt()
            if (lengthRead.value <= 0 || lengthRead.value > maxFrameSize.toLong()) {
                seeking = true
                passthrough.write(buffer[offset].toInt())
                offset++
                continue
            }
            val frameStart = lengthRead.nextOffset
            val frameEnd = frameStart + frameLength
            if (frameEnd > buffer.size) {
                // After a corrupt prefix only, seek a complete recognized envelope.
                // Never scan inside an otherwise valid fragmented message.
                val recovered = if (seeking) findCompleteFrame(buffer, offset + 1, maxFrameSize) else null
                if (recovered != null) {
                    passthrough.write(buffer, offset, recovered - offset)
                    offset = recovered
                    continue
                }
                return result(screenFrames, commandFrames, passthrough, buffer, offset, seeking)
            }
            val body = buffer.copyOfRange(frameStart, frameEnd)
            val main = runCatching { Flipper.Main.parseFrom(body) }.getOrNull()
            if (main == null || !isRecognizedRpcFrame(main)) {
                seeking = true
                passthrough.write(buffer[offset].toInt())
                offset++
                continue
            }
            if (main.commandId == 0 && main.hasGuiScreenFrame()) {
                screenFrames += main.guiScreenFrame
            } else {
                commandFrames.write(buffer, offset, frameEnd - offset)
            }
            seeking = false
            offset = frameEnd
        }
        return result(screenFrames, commandFrames, passthrough, buffer, buffer.size, seeking)
    }

    private fun result(
        screenFrames: List<Gui.ScreenFrame>,
        commandFrames: ByteArrayOutputStream,
        passthrough: ByteArrayOutputStream,
        buffer: ByteArray,
        remainderStart: Int,
        resynchronizing: Boolean
    ): PeelResult {
        val remainder = if (remainderStart >= buffer.size) {
            ByteArray(0)
        } else {
            buffer.copyOfRange(remainderStart, buffer.size)
        }
        return PeelResult(
            screenFrames,
            commandFrames.toByteArray(),
            passthrough.toByteArray(),
            remainder,
            resynchronizing
        )
    }

    private fun findCompleteFrame(buffer: ByteArray, start: Int, maxFrameSize: Int): Int? {
        for (candidate in start until buffer.size) {
            val size = readVarint(buffer, candidate) ?: continue
            if (size.value <= 0 || size.value > maxFrameSize.toLong()) continue
            val end = size.nextOffset + size.value.toInt()
            if (end > buffer.size) continue
            val main = runCatching { Flipper.Main.parseFrom(buffer.copyOfRange(size.nextOffset, end)) }.getOrNull()
            if (main != null && isRecognizedRpcFrame(main)) return candidate
        }
        return null
    }

    fun isRecognizedRpcFrame(main: Flipper.Main): Boolean {
        if (main.hasGuiScreenFrame()) return true
        if (main.commandId > 0) return true
        return main.contentCase != Flipper.Main.ContentCase.CONTENT_NOT_SET
    }

    private fun writeVarint(stream: ByteArrayOutputStream, value: Long) {
        var current = value
        while (true) {
            if ((current and 0x7FL.inv()) == 0L) {
                stream.write(current.toInt())
                return
            }
            stream.write(((current and 0x7F) or 0x80).toInt())
            current = current ushr 7
        }
    }

    private data class VarintRead(val value: Long, val nextOffset: Int)

    private fun readVarint(bytes: ByteArray, startOffset: Int): VarintRead? {
        var result = 0L
        var shift = 0
        var offset = startOffset
        while (offset < bytes.size && shift < 35) {
            val b = bytes[offset].toInt() and 0xFF
            if (shift == 28 && (b and 0xF8) != 0) return VarintRead(-1, offset + 1)
            result = result or ((b and 0x7F).toLong() shl shift)
            offset++
            if (b and 0x80 == 0) {
                return VarintRead(result, offset)
            }
            shift += 7
        }
        return if (shift >= 35) VarintRead(-1, offset) else null
    }
}
