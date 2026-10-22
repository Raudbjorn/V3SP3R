package com.vesper.flipper.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.vesper.flipper.ai.LlmSelection
import com.vesper.flipper.ai.LlmProvider
import com.vesper.flipper.ai.SpeechBackend
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.data.withCancellableResponse
import com.vesper.flipper.data.readBoundedBytes
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
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

    private val playbackMutex = Mutex()
    private val playbackEpoch = AtomicLong()

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    @Volatile private var currentTrack: AudioTrack? = null
    @Volatile private var currentJob: Job? = null

    /**
     * Speak the given text with the active provider.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return

        stop()
        val epoch = playbackEpoch.get()

        val selection = readSelectionOrReportError(epoch) ?: return
        if (epoch != playbackEpoch.get()) return
        val apiKey = selection.apiKey
        if (apiKey.isNullOrBlank()) {
            _state.value = TtsState.Error("${selection.provider.displayName} API key not configured")
            return
        }

        val voiceId = selection.provider.voiceOrDefault(settingsStore.ttsVoiceId.first()).id

        if (epoch != playbackEpoch.get()) return
        if (selection.provider.speechBackend == SpeechBackend.NONE) return
        _state.value = TtsState.Loading

        coroutineScope {
            currentJob = launch(Dispatchers.IO) {
                playbackMutex.withLock {
                if (epoch != playbackEpoch.get()) return@withLock
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
    }

    /**
     * Stop current playback immediately.
     */
    fun stop() {
        playbackEpoch.incrementAndGet()
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
        val selection = readSelectionOrReportError() ?: return false
        return enabled && selection.provider.speechBackend != SpeechBackend.NONE && !selection.apiKey.isNullOrBlank()
    }

    private suspend fun readSelectionOrReportError(epoch: Long? = null): LlmSelection? = try {
        settingsStore.readLlmSelection()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (epoch == null || epoch == playbackEpoch.get())
            _state.value = TtsState.Error("Could not access your API key securely. Retry credential storage in Settings.")
        null
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
        client.newCall(request).withCancellableResponse { response ->
            val bytes = response.body?.readBoundedBytes(SpeechAudio.MAX_RESPONSE_BYTES) ?: ByteArray(0)
            if (!response.isSuccessful) {
                _state.value = TtsState.Error(speechError("xAI", response.code, bytes.decodeToString()))
                return@withCancellableResponse
            }
            val (pcm, sampleRate) = SpeechAudio.wavPcm(bytes)
            if (pcm.isEmpty()) {
                _state.value = TtsState.Error("xAI returned no speech audio")
                return@withCancellableResponse
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

        client.newCall(request).withCancellableResponse { response ->
            val payload = response.body?.readBoundedBytes(SpeechAudio.MAX_RESPONSE_BYTES)?.decodeToString().orEmpty()
            if (!response.isSuccessful) {
                _state.value = TtsState.Error(speechError("Gemini", response.code, payload))
                return@withCancellableResponse
            }
            val pcm = SpeechAudio.geminiPcm(payload)
            if (pcm == null || pcm.isEmpty()) {
                _state.value = TtsState.Error("Gemini returned no speech audio")
                return@withCancellableResponse
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

        client.newCall(request).withCancellableResponse { response ->
            if (!response.isSuccessful) {
                val payload = response.body?.readBoundedBytes(SpeechAudio.MAX_RESPONSE_BYTES)?.decodeToString().orEmpty()
                _state.value = TtsState.Error(speechError(providerName, response.code, payload))
                return@withCancellableResponse
            }
            val stream = response.body?.byteStream()
            if (stream == null) {
                _state.value = TtsState.Error("Empty speech response from $providerName")
                return@withCancellableResponse
            }
            playPcmStream(stream, sampleRate)
        }
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
        coroutineContext.ensureActive()
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
            var written = 0L
            while (coroutineContext.isActive) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                var offset = 0
                while (offset < read) {
                    coroutineContext.ensureActive()
                    val count = audioTrack.write(buffer, offset, read - offset)
                    check(count > 0) { "Audio output failed" }
                    offset += count; written += count
                    check(written <= SpeechAudio.MAX_AUDIO_BYTES) { "Speech audio exceeded size limit" }
                }
            }
            drainAudio(written / 2) { audioTrack.playbackHeadPosition.toLong() and 0xffffffffL }
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
            if (_state.value is TtsState.Speaking) _state.value = TtsState.Idle
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

        client.newCall(request).withCancellableResponse { response ->
        if (!response.isSuccessful) {
            val body = response.body?.readBoundedBytes(64 * 1024)?.decodeToString() ?: "Unknown error"
            _state.value = TtsState.Error(speechError("OpenRouter", response.code, body))
            response.close()
            return@withCancellableResponse
        }

        val inputStream = response.body?.byteStream() ?: run {
            _state.value = TtsState.Error("Empty response from OpenRouter")
            response.close()
            return@withCancellableResponse
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

        val reader = response.body!!.source()
        var written = 0L
        try {
            while (!reader.exhausted()) {
                coroutineContext.ensureActive()
                val currentLine = reader.readUtf8LineStrict(2L * 1024 * 1024)
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
                        require(written + pcmBytes.size <= SpeechAudio.MAX_AUDIO_BYTES) { "Speech audio exceeded size limit" }
                        val count = audioTrack.write(pcmBytes, 0, pcmBytes.size)
                        check(count == pcmBytes.size) { "Incomplete audio output" }
                        written += count
                    }
                } catch (_: org.json.JSONException) {
                    // Ignore malformed JSON frames; resource limits and cancellation still fail the request.
                }
            }

            drainAudio(written / 2) { audioTrack.playbackHeadPosition.toLong() and 0xffffffffL }
        } finally {
            reader.close()
            response.close()
            releaseTrack(audioTrack)
        }
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
        private const val GEMINI_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private const val MAX_SPEECH_CHARS = 4000
    }
}

sealed class TtsState {
    data object Idle : TtsState()
    data object Loading : TtsState()
    data object Speaking : TtsState()
    data class Error(val message: String) : TtsState()
}
