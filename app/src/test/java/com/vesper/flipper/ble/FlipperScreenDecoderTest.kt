package com.vesper.flipper.ble

import com.flipperdevices.protobuf.screen.Gui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipperScreenDecoderTest {

    @Test
    fun `vertical page bit 0 is the top pixel of that column`() {
        val data = ByteArray(FlipperScreenDecoder.FRAME_BYTES)
        data[0] = 0x01

        val image = FlipperScreenDecoder.decode(data, Gui.ScreenOrientation.HORIZONTAL)!!

        assertTrue(image.pixels[0])
        assertFalse(image.pixels[1])
        assertFalse(image.pixels[FlipperScreenDecoder.WIDTH])
        assertEquals(FlipperScreenOrientation.HORIZONTAL, image.orientation)
    }

    @Test
    fun `bit 1 of a page is the pixel one row down`() {
        val data = ByteArray(FlipperScreenDecoder.FRAME_BYTES)
        data[3] = 0x02

        val image = FlipperScreenDecoder.decode(data, Gui.ScreenOrientation.VERTICAL)!!

        assertTrue(image.pixels[FlipperScreenDecoder.WIDTH + 3])
        assertFalse(image.pixels[3])
        assertEquals(FlipperScreenOrientation.VERTICAL, image.orientation)
    }

    @Test fun `all orientations rotate panel corners correctly`() {
        val expected = listOf(0 to 0, 127 to 63, 63 to 0, 0 to 127)
        val orientations = listOf(Gui.ScreenOrientation.HORIZONTAL, Gui.ScreenOrientation.HORIZONTAL_FLIP,
            Gui.ScreenOrientation.VERTICAL, Gui.ScreenOrientation.VERTICAL_FLIP)
        orientations.forEachIndexed { index, orientation ->
            val image = FlipperScreenDecoder.decode(ByteArray(1024), orientation)!!
            assertEquals(expected[index], image.displayPosition(0, 0))
        }
        assertNull(FlipperScreenDecoder.decode(ByteArray(1025), Gui.ScreenOrientation.HORIZONTAL))
    }

    @Test
    fun `a short buffer is not a frame`() {
        assertNull(
            FlipperScreenDecoder.decode(
                ByteArray(FlipperScreenDecoder.FRAME_BYTES - 1),
                Gui.ScreenOrientation.HORIZONTAL
            )
        )
    }
}
