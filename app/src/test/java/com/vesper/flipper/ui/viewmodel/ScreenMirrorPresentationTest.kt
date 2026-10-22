package com.vesper.flipper.ui.viewmodel

import com.vesper.flipper.ble.FlipperScreenImage
import com.vesper.flipper.ble.FlipperScreenOrientation
import org.junit.Assert.*
import org.junit.Test

class ScreenMirrorPresentationTest {
    private fun frame(id: Long) = FlipperScreenImage(id, 128, 64, BooleanArray(8192), FlipperScreenOrientation.HORIZONTAL)
    @Test fun `pause keeps last image even when device continues sending frames`() {
        val presentation = ScreenMirrorPresentation()
        presentation.setEnabled(true)
        presentation.accept(frame(1))
        presentation.setEnabled(false)
        presentation.accept(frame(2))
        assertEquals(1L, presentation.frame.value!!.id)
        presentation.setEnabled(true)
        presentation.accept(frame(3))
        assertEquals(3L, presentation.frame.value!!.id)
    }
    @Test fun `disconnect clears image while paused`() {
        val presentation = ScreenMirrorPresentation()
        presentation.setEnabled(true)
        presentation.accept(frame(1))
        presentation.setEnabled(false)
        presentation.accept(null)
        assertNull(presentation.frame.value)
    }
}
