package com.vesper.flipper.ble

import com.flipperdevices.protobuf.screen.Gui

/**
 * One decoded Flipper framebuffer.
 *
 * The wire buffer is always the 128×64 panel: 8-pixel vertical pages, least
 * significant bit at the top of the page. [orientation] is how the firmware
 * asked the client to rotate that panel.
 */
data class FlipperScreenImage(
    val id: Long,
    val width: Int,
    val height: Int,
    val pixels: BooleanArray,
    val orientation: FlipperScreenOrientation
) {
    fun displayPosition(x: Int, y: Int): Pair<Int, Int> = when (orientation) {
        FlipperScreenOrientation.HORIZONTAL -> x to y
        FlipperScreenOrientation.HORIZONTAL_FLIP -> (width - 1 - x) to (height - 1 - y)
        FlipperScreenOrientation.VERTICAL -> (height - 1 - y) to x
        FlipperScreenOrientation.VERTICAL_FLIP -> y to (width - 1 - x)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlipperScreenImage) return false
        return id == other.id &&
            width == other.width &&
            height == other.height &&
            orientation == other.orientation &&
            pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        result = 31 * result + orientation.hashCode()
        return result
    }
}

enum class FlipperScreenOrientation {
    HORIZONTAL,
    HORIZONTAL_FLIP,
    VERTICAL,
    VERTICAL_FLIP
}

object FlipperScreenDecoder {
    const val WIDTH = 128
    const val HEIGHT = 64
    const val FRAME_BYTES = WIDTH * HEIGHT / 8

    fun decode(data: ByteArray, orientation: Gui.ScreenOrientation): FlipperScreenImage? {
        if (data.size != FRAME_BYTES) return null
        val pixels = BooleanArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            val pageStart = (y / 8) * WIDTH
            val bit = y % 8
            val rowStart = y * WIDTH
            for (x in 0 until WIDTH) {
                val value = data[pageStart + x].toInt() and 0xFF
                pixels[rowStart + x] = ((value shr bit) and 0x1) == 1
            }
        }
        return FlipperScreenImage(
            id = 0L,
            width = WIDTH,
            height = HEIGHT,
            pixels = pixels,
            orientation = orientation.toScreenOrientation()
        )
    }

    private fun Gui.ScreenOrientation.toScreenOrientation(): FlipperScreenOrientation {
        return when (this) {
            Gui.ScreenOrientation.HORIZONTAL_FLIP -> FlipperScreenOrientation.HORIZONTAL_FLIP
            Gui.ScreenOrientation.VERTICAL -> FlipperScreenOrientation.VERTICAL
            Gui.ScreenOrientation.VERTICAL_FLIP -> FlipperScreenOrientation.VERTICAL_FLIP
            Gui.ScreenOrientation.HORIZONTAL,
            Gui.ScreenOrientation.UNRECOGNIZED -> FlipperScreenOrientation.HORIZONTAL
        }
    }
}
