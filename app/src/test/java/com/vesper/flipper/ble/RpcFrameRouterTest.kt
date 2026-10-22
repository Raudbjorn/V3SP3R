package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.screen.Gui
import org.junit.Assert.*
import org.junit.Test

class RpcFrameRouterTest {
    private fun command() = RpcFrameRouter.encode(Flipper.Main.newBuilder().setCommandId(42).build())
    private fun screen(id: Int = 0) = RpcFrameRouter.encode(Flipper.Main.newBuilder()
        .setCommandId(id).setGuiScreenFrame(Gui.ScreenFrame.newBuilder()
            .setData(com.google.protobuf.ByteString.copyFrom(ByteArray(1024) { 0x55 })).build()).build())

    @Test fun `interleaved screen notifications never enter command output`() {
        val result = RpcFrameRouter.peel(screen() + command() + screen())
        assertEquals(2, result.screenFrames.size)
        assertArrayEquals(command(), result.commandFrames)
        assertTrue(result.remainder.isEmpty())
    }
    @Test fun `every split preserves partial frame until completion`() {
        val bytes = screen() + command()
        for (split in bytes.indices) {
            val first = RpcFrameRouter.peel(bytes.copyOfRange(0, split))
            val second = RpcFrameRouter.peel(first.remainder + bytes.copyOfRange(split, bytes.size))
            assertEquals(1, first.screenFrames.size + second.screenFrames.size)
            assertArrayEquals(command(), first.commandFrames + second.commandFrames)
        }
    }
    @Test fun `solicited screen messages remain command responses`() {
        assertArrayEquals(screen(42), RpcFrameRouter.peel(screen(42)).commandFrames)
    }
    @Test fun `corrupt prefix recovery survives transport chunk boundaries`() {
        val first = RpcFrameRouter.peel(ByteArray(12) { 0xff.toByte() })
        assertTrue(first.resynchronizing)
        val second = RpcFrameRouter.peel(first.remainder + command(), resynchronizing = first.resynchronizing)
        assertArrayEquals(command(), second.commandFrames)
        assertTrue(second.remainder.isEmpty())
    }
    @Test fun `large unsigned lengths cannot truncate into a valid frame size`() {
        val result = RpcFrameRouter.peel(byteArrayOf(0x83.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10) + command())
        assertArrayEquals(command(), result.commandFrames)
        assertTrue(result.remainder.isEmpty())
    }
    @Test fun `overflowing length and endless varint cannot pin buffer`() {
        val malformed = ByteArray(12) { 0xff.toByte() }
        val result = RpcFrameRouter.peel(malformed + command())
        assertTrue(result.remainder.size <= 4)
        assertArrayEquals(command(), result.commandFrames)
    }
}
