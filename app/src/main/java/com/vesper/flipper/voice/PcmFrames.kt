package com.vesper.flipper.voice

import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** HTTP read boundaries need not align to PCM16 sample boundaries. */
internal suspend fun transferPcm16(
    source: InputStream,
    maxBytes: Int = SpeechAudio.MAX_AUDIO_BYTES,
    write: suspend (ByteArray, Int, Int) -> Int
): Long {
    val buffer = ByteArray(8192)
    var carry = 0
    var received = 0L
    var written = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = source.read(buffer, carry, buffer.size - carry)
        if (read < 0) { check(carry == 0) { "Incomplete PCM sample" }; return written }
        if (read == 0) continue
        received += read
        check(received <= maxBytes) { "Speech audio exceeded size limit" }
        val total = carry + read
        val complete = total - total % 2
        var offset = 0
        while (offset < complete) {
            currentCoroutineContext().ensureActive()
            val count = write(buffer, offset, complete - offset)
            check(count > 0 && count <= complete - offset && count % 2 == 0) { "Audio output failed" }
            offset += count; written += count
        }
        carry = total - complete
        if (carry != 0) buffer[0] = buffer[complete]
    }
}
