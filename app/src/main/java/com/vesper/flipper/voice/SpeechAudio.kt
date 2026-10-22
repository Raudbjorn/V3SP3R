package com.vesper.flipper.voice

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

internal object SpeechAudio {
    const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024

    fun wavPcm(bytes: ByteArray): Pair<ByteArray, Int> {
        require(bytes.size in 12..MAX_RESPONSE_BYTES) { "Invalid WAV size" }
        fun id(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun uint(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        fun ushort(offset: Int) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        require(id(0) == "RIFF" && id(8) == "WAVE") { "Expected WAV audio" }
        val end = 8L + uint(4)
        require(end in 12..bytes.size.toLong()) { "Truncated WAV audio" }
        var position = 12L
        var sampleRate: Int? = null
        var data: IntRange? = null
        while (position + 8 <= end) {
            val offset = position.toInt()
            val size = uint(offset + 4)
            val start = position + 8
            require(start + size <= end) { "Truncated WAV chunk" }
            when (id(offset)) {
                "fmt " -> {
                    require(size >= 16 && ushort(start.toInt()) == 1 && ushort(start.toInt() + 2) == 1 &&
                        ushort(start.toInt() + 14) == 16 && ushort(start.toInt() + 12) == 2) { "Expected mono 16-bit PCM" }
                    val rate = uint(start.toInt() + 4)
                    require(rate in 8000..48000 && uint(start.toInt() + 8) == rate * 2) { "Unsupported WAV sample rate" }
                    sampleRate = rate.toInt()
                }
                "data" -> {
                    require(size in 1..MAX_AUDIO_BYTES.toLong() && size % 2 == 0L) { "Invalid PCM size" }
                    data = start.toInt() until (start + size).toInt()
                }
            }
            position = start + size + (size and 1)
            require(position <= end) { "Missing WAV chunk padding" }
        }
        require(position == end && sampleRate != null && data != null) { "WAV format or audio chunk missing" }
        return bytes.copyOfRange(data.first, data.last + 1) to sampleRate
    }

    fun geminiPcm(payload: String): ByteArray? {
        val root = Json.parseToJsonElement(payload).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return null
        val output = ByteArrayOutputStream()
        for (part in parts) {
            val encoded = part.jsonObject["inlineData"]?.jsonObject?.get("data")?.jsonPrimitive?.content.orEmpty()
            if (encoded.isBlank()) continue
            val remaining = MAX_AUDIO_BYTES - output.size()
            require(encoded.length.toLong() <= ((remaining.toLong() + 2) / 3) * 4 + 16) { "Speech audio exceeded size limit" }
            val bytes = Base64.getMimeDecoder().decode(encoded)
            require(bytes.size <= remaining) { "Speech audio exceeded size limit" }
            val pcm = if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
                val (data, rate) = wavPcm(bytes)
                require(rate == 24000) { "Unexpected Gemini audio sample rate" }
                data
            } else bytes
            require(pcm.size % 2 == 0) { "Invalid PCM alignment" }
            output.write(pcm)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }
}
