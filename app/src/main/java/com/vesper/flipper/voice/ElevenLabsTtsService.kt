package com.vesper.flipper.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.vesper.flipper.ai.LlmProvider
import com.vesper.flipper.ai.SpeechBackend
import com.vesper.flipper.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks agent replies with the active provider's speech API.
 * OpenRouter streams chat-audio PCM. OpenAI and xAI return raw PCM.
 * Gemini returns base64 PCM from its speech model.
 */
@Singleton
class OpenRouterTtsService @Inject constructor(
    private val settingsStore: SettingsStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private var currentTrack: AudioTrack? = null
    private var currentJob: Job? = null

    /**
     * Speak the given text with the active provider.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return

        stop()

        val selection = settingsStore.readLlmSelection()
        val apiKey = selection.apiKey
        if (apiKey.isNullOrBlank()) {
            _state.value = TtsState.Error("${selection.provider.displayName} API key not configured")
            return
        }

        val voiceId = selection.provider.voiceOrDefault(settingsStore.ttsVoiceId.first()).id

        _state.value = TtsState.Loading

        coroutineScope {
            currentJob = launch(Dispatchers.IO) {
                try {
                    when (selection.provider.speechBackend) {
                        SpeechBackend.OPENROUTER_AUDIO -> streamOpenRouter(apiKey, voiceId, text)
                        SpeechBackend.OPENAI_SPEECH -> speakOpenAi(apiKey, voiceId, text)
                        SpeechBackend.GEMINI_TTS -> speakGemini(apiKey, voiceId, text)
                        SpeechBackend.XAI_TTS -> speakXai(apiKey, voiceId, text)
                        SpeechBackend.NONE ->
                            _state.value = TtsState.Error("This provider has no speech endpoint.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (_state.value !is TtsState.Idle) {
                        _state.value = TtsState.Error(e.message ?: "Speech playback failed")
                    }
                }
            }
        }
    }

    /**
     * Stop current playback immediately.
     */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
        try {
            currentTrack?.apply {
                pause()
                flush()
                release()
            }
        } catch (_: Exception) {}
        currentTrack = null
        _state.value = TtsState.Idle
    }

    /**
     * Check if TTS is available (API key configured and TTS enabled).
     */
    suspend fun isAvailable(): Boolean {
        val enabled = settingsStore.ttsEnabled.first()
        val selection = settingsStore.readLlmSelection()
        return enabled && selection.provider.speechBackend != SpeechBackend.NONE && !selection.apiKey.isNullOrBlank()
    }

    private suspend fun speakOpenAi(apiKey: String, voiceId: String, text: String) {
        val cleaned = cleanTextForSpeech(text)
        if (cleaned.isBlank()) {
            _state.value = TtsState.Idle
            return
        }
        val body = JSONObject().apply {
            put("model", OPENAI_TTS_MODEL)
            put("voice", voiceId)
            put("input", cleaned)
            put("response_format", "pcm")
        }.toString()
        postPcm(
            url = "https://api.openai.com/v1/audio/speech",
            apiKey = apiKey,
            json = body,
            providerName = "OpenAI",
            sampleRate = 24000
        )
    }

    private suspend fun speakXai(apiKey: String, voiceId: String, text: String) {
        val cleaned = cleanTextForSpeech(text)
        if (cleaned.isBlank()) {
            _state.value = TtsState.Idle
            return
        }
        val body = JSONObject().apply {
            put("text", cleaned)
            put("voice_id", voiceId)
            put("language", "auto")
            put("output_format", JSONObject().apply {
                put("codec", "wav")
                put("sample_rate", 24000)
            })
        }.toString()
        val request = Request.Builder()
            .url("https://api.x.ai/v1/tts")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                _state.value = TtsState.Error(speechError("xAI", response.code, bytes.decodeToString()))
                return
            }
            val (pcm, sampleRate) = wavPcm(bytes)
            if (pcm.isEmpty()) {
                _state.value = TtsState.Error("xAI returned no speech audio")
                return
            }
            playPcmStream(ByteArrayInputStream(pcm), sampleRate)
        }
    }

    private suspend fun speakGemini(apiKey: String, voiceId: String, text: String) {
        val cleaned = cleanTextForSpeech(text)
        if (cleaned.isBlank()) {
            _state.value = TtsState.Idle
            return
        }
        val body = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Read the following text aloud with no extra commentary:\n\n$cleaned")
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", org.json.JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceId)
                        })
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_TTS_MODEL:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                _state.value = TtsState.Error(speechError("Gemini", response.code, payload))
                return
            }
            val pcm = decodeGeminiPcm(payload)
            if (pcm == null || pcm.isEmpty()) {
                _state.value = TtsState.Error("Gemini returned no speech audio")
                return
            }
            playPcmStream(ByteArrayInputStream(pcm), 24000)
        }
    }

    private suspend fun postPcm(
        url: String,
        apiKey: String,
        json: String,
        providerName: String,
        sampleRate: Int
    ) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val payload = response.body?.string().orEmpty()
                _state.value = TtsState.Error(speechError(providerName, response.code, payload))
                return
            }
            val stream = response.body?.byteStream()
            if (stream == null) {
                _state.value = TtsState.Error("Empty speech response from $providerName")
                return
            }
            playPcmStream(stream, sampleRate)
        }
    }

    private fun decodeGeminiPcm(payload: String): ByteArray? {
        val root = try {
            JSONObject(payload)
        } catch (_: Exception) {
            return null
        }
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        val combined = java.io.ByteArrayOutputStream()
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val encoded = inline.optString("data")
            if (encoded.isBlank()) continue
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            val pcm = if (
                decoded.size > 44 &&
                decoded[0] == 'R'.code.toByte() &&
                decoded[1] == 'I'.code.toByte() &&
                decoded[2] == 'F'.code.toByte() &&
                decoded[3] == 'F'.code.toByte()
            ) {
                decoded.copyOfRange(44, decoded.size)
            } else {
                decoded
            }
            combined.write(pcm)
        }
        return combined.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * Pulls the PCM payload and sample rate out of a standard WAV file.
     * Returns the original bytes at 24 kHz when the buffer is not a WAV.
     */
    private fun wavPcm(bytes: ByteArray): Pair<ByteArray, Int> {
        if (bytes.size < 44 ||
            bytes[0] != 'R'.code.toByte() ||
            bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() ||
            bytes[3] != 'F'.code.toByte()
        ) {
            return bytes to 24000
        }
        val sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int.coerceAtLeast(8000)
        var index = 12
        while (index + 8 <= bytes.size) {
            val chunkId = String(bytes, index, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(bytes, index + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val dataStart = index + 8
            if (chunkId == "data" && chunkSize > 0 && dataStart < bytes.size) {
                val dataEnd = (dataStart + chunkSize).coerceAtMost(bytes.size)
                return bytes.copyOfRange(dataStart, dataEnd) to sampleRate
            }
            val step = 8 + chunkSize.coerceAtLeast(0)
            if (step <= 0) break
            index += step
        }
        return bytes.copyOfRange(44, bytes.size) to sampleRate
    }

    private fun speechError(providerName: String, code: Int, body: String): String {
        val detail = body.replace(Regex("\\s+"), " ").take(240)
        return if (detail.isBlank()) {
            "$providerName speech error $code"
        } else {
            "$providerName speech error $code: $detail"
        }
    }

    private suspend fun playPcmStream(source: InputStream, sampleRate: Int) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val audioTrack = newPcmTrack(sampleRate, bufferSize)
        currentTrack = audioTrack
        audioTrack.play()
        _state.value = TtsState.Speaking
        try {
            val buffer = ByteArray(bufferSize)
            while (coroutineContext.isActive) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                audioTrack.write(buffer, 0, read)
            }
        } finally {
            source.close()
            releaseTrack(audioTrack)
        }
    }

    private fun newPcmTrack(sampleRate: Int, bufferSize: Int): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun releaseTrack(audioTrack: AudioTrack) {
        try {
            audioTrack.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack.release()
        } catch (_: Exception) {
        }
        if (currentTrack === audioTrack) {
            currentTrack = null
        }
        if (_state.value is TtsState.Speaking) {
            _state.value = TtsState.Idle
        }
    }

    private suspend fun streamOpenRouter(apiKey: String, voiceId: String, text: String) {
        val cleanedText = cleanTextForSpeech(text)
        if (cleanedText.isBlank()) {
            _state.value = TtsState.Idle
            return
        }

        // Build OpenRouter chat completion request with audio output modality
        val requestJson = JSONObject().apply {
            put("model", TTS_MODEL)
            put("stream", true)
            put("modalities", org.json.JSONArray().apply {
                put("text")
                put("audio")
            })
            put("audio", JSONObject().apply {
                put("voice", voiceId)
                put("format", "pcm16")
            })
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Read the following text aloud naturally and expressively. " +
                            "Do not add any commentary, just read the text:\n\n$cleanedText")
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(LlmProvider.OPENROUTER.chatCompletionsUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val body = response.body?.string() ?: "Unknown error"
            _state.value = TtsState.Error(speechError("OpenRouter", response.code, body))
            response.close()
            return
        }

        val inputStream = response.body?.byteStream() ?: run {
            _state.value = TtsState.Error("Empty response from OpenRouter")
            response.close()
            return
        }

        val sampleRate = 24000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val audioTrack = newPcmTrack(sampleRate, bufferSize)
        currentTrack = audioTrack
        audioTrack.play()
        _state.value = TtsState.Speaking

        val reader = BufferedReader(InputStreamReader(inputStream))
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (!coroutineContext.isActive) break

                val currentLine = line ?: continue
                if (!currentLine.startsWith("data: ")) continue
                val data = currentLine.removePrefix("data: ").trim()
                if (data == "[DONE]") break

                try {
                    val chunk = JSONObject(data)
                    val delta = chunk
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?: continue

                    val audioData = delta.optJSONObject("audio")
                        ?.optString("data")
                        ?: continue

                    if (audioData.isNotBlank()) {
                        val pcmBytes = Base64.decode(audioData, Base64.NO_WRAP)
                        audioTrack.write(pcmBytes, 0, pcmBytes.size)
                    }
                } catch (_: Exception) {
                    // Skip malformed chunks
                }
            }

            // Flush remaining audio
            withContext(Dispatchers.IO) {
                audioTrack.write(ByteArray(bufferSize), 0, bufferSize)
            }
        } finally {
            reader.close()
            response.close()
            releaseTrack(audioTrack)
        }
    }

    /**
     * Clean text for speech: remove markdown, code blocks, image descriptions, etc.
     */
    private fun cleanTextForSpeech(text: String): String {
        return text
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`[^`]+`"), "")
            .replace(Regex("\\[Attached image:.*?]"), "")
            .replace(Regex("\\[\\d+ image\\(s\\).*?]"), "")
            .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .take(MAX_SPEECH_CHARS)
    }

    companion object {
        private const val TTS_MODEL = "openai/gpt-audio"
        private const val OPENAI_TTS_MODEL = "gpt-4o-mini-tts"
        private const val GEMINI_TTS_MODEL = "gemini-3.8-flash-tts"
        private const val MAX_SPEECH_CHARS = 4000
    }
}

sealed class TtsState {
    data object Idle : TtsState()
    data object Loading : TtsState()
    data object Speaking : TtsState()
    data class Error(val message: String) : TtsState()
}
