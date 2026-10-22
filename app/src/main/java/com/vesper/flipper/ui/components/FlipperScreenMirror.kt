package com.vesper.flipper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.vesper.flipper.ble.FlipperScreenImage
import com.vesper.flipper.ble.FlipperScreenOrientation

/** Pixel-aligned framebuffer, rotated according to the firmware's orientation. */
@Composable
fun FlipperScreenMirror(frame: FlipperScreenImage?, status: String?) {
    Column {
        val vertical = frame?.orientation == FlipperScreenOrientation.VERTICAL ||
            frame?.orientation == FlipperScreenOrientation.VERTICAL_FLIP
        Canvas(Modifier.fillMaxWidth().aspectRatio(if (vertical) 0.5f else 2f)
            .background(Color(0xFFD5E0C8)).semantics { contentDescription = "Flipper screen mirror" }) {
            if (frame != null) {
                val dw = if (vertical) frame.height else frame.width
                val dh = if (vertical) frame.width else frame.height
                for (y in 0 until frame.height) for (x in 0 until frame.width) {
                    if (!frame.pixels[y * frame.width + x]) continue
                    val (dx, dy) = frame.displayPosition(x, y)
                    drawRect(Color(0xFF1C2418), Offset(dx * size.width / dw, dy * size.height / dh),
                        Size(size.width / dw, size.height / dh))
                }
            }
        }
        Text(status ?: "Enable live screen to mirror your Flipper")
    }
}
