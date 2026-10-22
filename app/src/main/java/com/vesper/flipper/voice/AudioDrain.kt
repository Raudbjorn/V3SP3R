package com.vesper.flipper.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Do not flush/release a streaming track until its queued frames have played. */
internal suspend fun drainAudio(writtenFrames: Long, playedFrames: () -> Long) {
    withTimeout(5000) {
        while (playedFrames() < writtenFrames) delay(10)
    }
}
