package com.vesper.flipper.ui.viewmodel

import com.vesper.flipper.ble.FlipperScreenImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Presentation pause is immediate, independent of the device's stop acknowledgement. */
internal class ScreenMirrorPresentation {
    @Volatile var enabled = false
        private set
    private val _frame = MutableStateFlow<FlipperScreenImage?>(null)
    val frame = _frame.asStateFlow()

    fun setEnabled(value: Boolean) { enabled = value }
    fun accept(frame: FlipperScreenImage?) {
        // A disconnect clears a stale device image, including while paused.
        if (enabled || frame == null) _frame.value = frame
    }
}
