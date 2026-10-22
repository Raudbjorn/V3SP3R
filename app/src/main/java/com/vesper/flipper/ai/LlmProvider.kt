package com.vesper.flipper.ai

import com.vesper.flipper.data.ModelInfo

private val OPENAI_STYLE_VOICES = listOf(
    LlmVoice("shimmer", "Shimmer", "Soft, warm"),
    LlmVoice("coral", "Coral", "Friendly, natural"),
    LlmVoice("nova", "Nova", "Energetic"),
    LlmVoice("sage", "Sage", "Calm"),
    LlmVoice("alloy", "Alloy", "Neutral"),
    LlmVoice("ballad", "Ballad", "Warm, expressive"),
    LlmVoice("fable", "Fable", "Storytelling"),
    LlmVoice("echo", "Echo", "Smooth"),
    LlmVoice("ash", "Ash", "Confident"),
    LlmVoice("onyx", "Onyx", "Deep")
)

/**
 * A chat provider Vesper can call with one long-lived API key.
 *
 * Compatible providers speak the OpenAI chat-completions shape the agent already
 * uses (messages, tools, tool_choice). Speech is a separate call because
 * Gemini and xAI do not return audio from that chat endpoint.
 */
enum class LlmProvider(
    val id: String,
    val displayName: String,
    val summary: String,
    val keyLabel: String,
    val keyHint: String,
    val chatCompletionsUrl: String,
    val modelsUrl: String,
    val apiHost: String,
    val defaultModel: String,
    val models: List<ModelInfo>,
    val visionModels: List<String>,
    val toolFallbackModels: List<String>,
    val knownNonToolModels: Set<String>,
    val speechBackend: SpeechBackend,
    val speechSummary: String,
    val voices: List<LlmVoice>,
    /** OpenRouter attribution headers. Other providers reject or ignore them. */
    val sendsAttributionHeaders: Boolean,
    /**
     * OpenAI reasoning models reject max_tokens. max_completion_tokens is
     * accepted by the current OpenAI chat models, including GPT-4.1.
     */
    val usesMaxCompletionTokens: Boolean,
    /** GPT-6 rejects a custom temperature. Other providers still accept 0.3. */
    val allowsSamplingTemperature: Boolean
) {
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        summary = "Many models through one key",
        keyLabel = "OpenRouter API Key",
        keyHint = "Paste your OpenRouter key (starts with sk-or-).",
        chatCompletionsUrl = "https://openrouter.ai/api/v1/chat/completions",
        modelsUrl = "https://openrouter.ai/api/v1/models",
        apiHost = "openrouter.ai",
        defaultModel = "anthropic/claude-sonnet-4.5",
        models = listOf(
            ModelInfo("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5", "Tool-capable model"),
            ModelInfo("openai/gpt-oss-120b", "GPT-OSS 120B", "Tool-capable model"),
            ModelInfo("google/gemini-2.5-flash", "Gemini 2.5 Flash", "Tool-capable model"),
            ModelInfo("qwen/qwen3-coder", "Qwen3 Coder", "Tool-capable model"),
            ModelInfo("deepseek/deepseek-r1-0528", "DeepSeek R1 0528", "Tool-capable model"),
            ModelInfo("moonshotai/kimi-k2", "Kimi K2", "Tool-capable model"),
            ModelInfo("z-ai/glm-4.5", "GLM 4.5", "Tool-capable model")
        ),
        visionModels = listOf(
            "google/gemini-2.0-flash-001",
            "google/gemini-2.5-flash",
            "openai/gpt-4o-mini"
        ),
        toolFallbackModels = listOf(
            "anthropic/claude-sonnet-4.5",
            "openai/gpt-oss-120b",
        ),
        knownNonToolModels = setOf("google/gemini-2.5-flash-image-preview"),
        speechBackend = SpeechBackend.OPENROUTER_AUDIO,
        speechSummary = "Reads replies through OpenRouter audio. Uses this provider's API key.",
        voices = OPENAI_STYLE_VOICES,
        sendsAttributionHeaders = true,
        usesMaxCompletionTokens = false,
        allowsSamplingTemperature = true
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        summary = "Gemini API key from AI Studio",
        keyLabel = "Gemini API Key",
        keyHint = "Paste a key from Google AI Studio (starts with AIza).",
        chatCompletionsUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        modelsUrl = "https://generativelanguage.googleapis.com/v1beta/openai/models",
        apiHost = "generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        models = listOf(
            ModelInfo("gemini-2.5-flash", "gemini-2.5-flash", "Tool-capable model"),
            ModelInfo("gemini-2.5-pro", "gemini-2.5-pro", "Tool-capable model"),
            ModelInfo("gemini-2.5-flash-lite", "gemini-2.5-flash-lite", "Tool-capable model"),
            ModelInfo("gemini-2.0-flash", "gemini-2.0-flash", "Tool-capable model"),
        ),
        visionModels = listOf(
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        ),
        toolFallbackModels = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro"
        ),
        knownNonToolModels = emptySet(),
        speechBackend = SpeechBackend.GEMINI_TTS,
        speechSummary = "Reads replies with Gemini speech. Uses this provider's API key.",
        voices = listOf(
            LlmVoice("Kore", "Kore", "Firm, clear"),
            LlmVoice("Puck", "Puck", "Upbeat"),
            LlmVoice("Charon", "Charon", "Informative"),
            LlmVoice("Zephyr", "Zephyr", "Bright"),
            LlmVoice("Fenrir", "Fenrir", "Excitable"),
            LlmVoice("Leda", "Leda", "Youthful"),
            LlmVoice("Orus", "Orus", "Steady"),
            LlmVoice("Aoede", "Aoede", "Breezy")
        ),
        sendsAttributionHeaders = false,
        usesMaxCompletionTokens = false,
        allowsSamplingTemperature = true
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        summary = "GPT models and OpenAI speech",
        keyLabel = "OpenAI API Key",
        keyHint = "Paste your OpenAI API key (starts with sk-).",
        chatCompletionsUrl = "https://api.openai.com/v1/chat/completions",
        modelsUrl = "https://api.openai.com/v1/models",
        apiHost = "api.openai.com",
        defaultModel = "gpt-4.1",
        models = listOf(
            ModelInfo("gpt-4.1", "gpt-4.1", "Tool-capable model"),
            ModelInfo("gpt-4.1-mini", "gpt-4.1-mini", "Tool-capable model"),
        ),
        visionModels = listOf(
            "gpt-4.1-mini",
            "gpt-4.1",
            "gpt-4.1"
        ),
        toolFallbackModels = listOf(
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1",
            "gpt-4.1"
        ),
        knownNonToolModels = emptySet(),
        speechBackend = SpeechBackend.OPENAI_SPEECH,
        speechSummary = "Reads replies with OpenAI speech. Uses this provider's API key.",
        voices = OPENAI_STYLE_VOICES,
        sendsAttributionHeaders = false,
        usesMaxCompletionTokens = true,
        allowsSamplingTemperature = false
    ),
    XAI(
        id = "xai",
        displayName = "xAI",
        summary = "Grok models and xAI speech",
        keyLabel = "xAI API Key",
        keyHint = "Paste your xAI API key from the xAI console (starts with xai-).",
        chatCompletionsUrl = "https://api.x.ai/v1/chat/completions",
        modelsUrl = "https://api.x.ai/v1/models",
        apiHost = "api.x.ai",
        defaultModel = "grok-4",
        models = listOf(
            ModelInfo("grok-4", "grok-4", "Tool-capable model"),
            ModelInfo("grok-4-fast-non-reasoning", "grok-4-fast-non-reasoning", "Tool-capable model"),
            ModelInfo("grok-4-fast-reasoning", "grok-4-fast-reasoning", "Tool-capable model"),
            ModelInfo("grok-3", "grok-3", "Tool-capable model"),
            ModelInfo("grok-3-mini", "grok-3-mini", "Tool-capable model"),
        ),
        visionModels = listOf(
            "grok-4",
            "grok-4-fast-non-reasoning",
            "grok-3"
        ),
        toolFallbackModels = listOf(
            "grok-4",
            "grok-3",
            "grok-3-mini",
            "grok-4-fast-non-reasoning"
        ),
        knownNonToolModels = emptySet(),
        speechBackend = SpeechBackend.XAI_TTS,
        speechSummary = "Reads replies with xAI speech. Uses this provider's API key.",
        voices = listOf(
            LlmVoice("eve", "Eve", "Clear default"),
            LlmVoice("ara", "Ara", "Warm, expressive"),
            LlmVoice("rex", "Rex", "Direct"),
            LlmVoice("orion", "Orion", "Cinematic"),
            LlmVoice("luna", "Luna", "Gentle"),
            LlmVoice("iris", "Iris", "Friendly"),
            LlmVoice("perseus", "Perseus", "Confident"),
            LlmVoice("helios", "Helios", "Energetic")
        ),
        sendsAttributionHeaders = false,
        usesMaxCompletionTokens = false,
        allowsSamplingTemperature = true
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        summary = "Cloud Claude API",
        keyLabel = "Anthropic API Key",
        keyHint = "Paste your Anthropic key (starts with sk-ant-).",
        chatCompletionsUrl = "https://api.anthropic.com/v1/messages",
        modelsUrl = "https://api.anthropic.com/v1/models",
        apiHost = "api.anthropic.com",
        defaultModel = "claude-sonnet-4-5",
        models = listOf(
            ModelInfo("claude-sonnet-4-5", "claude-sonnet-4-5", "Tool-capable model"),
            ModelInfo("claude-haiku-4-5", "claude-haiku-4-5", "Tool-capable model"),
            ModelInfo("claude-opus-4-1", "claude-opus-4-1", "Tool-capable model"),
        ),
        visionModels = listOf(
            "claude-sonnet-4-5",
            "claude-haiku-4-5",
            "claude-opus-4-1"
        ),
        toolFallbackModels = listOf(
            "claude-sonnet-4-5",
            "claude-haiku-4-5",
            "claude-sonnet-4-5",
            "claude-opus-4-1"
        ),
        knownNonToolModels = emptySet(),
        speechBackend = SpeechBackend.NONE,
        speechSummary = "Claude chat only. This provider has no speech endpoint.",
        voices = listOf(LlmVoice("none", "None", "Speech is not available")),
        sendsAttributionHeaders = false,
        usesMaxCompletionTokens = false,
        allowsSamplingTemperature = false
    );

    val apiKeyPreference: String
        get() = when (this) {
            OPENROUTER -> "openrouter_api_key"
            GEMINI -> "gemini_api_key"
            OPENAI -> "openai_api_key"
            XAI -> "xai_api_key"
            ANTHROPIC -> "anthropic_api_key"
        }

    val modelPreference: String
        get() = when (this) {
            OPENROUTER -> "selected_model"
            GEMINI -> "selected_model_gemini"
            OPENAI -> "selected_model_openai"
            XAI -> "selected_model_xai"
            ANTHROPIC -> "selected_model_anthropic"
        }

    fun voiceOrDefault(storedId: String): LlmVoice {
        return voices.find { it.id == storedId } ?: voices.first()
    }

    fun completionLimits(maxTokens: Int): CompletionLimits {
        return if (usesMaxCompletionTokens) {
            CompletionLimits(maxTokens = null, maxCompletionTokens = maxTokens)
        } else {
            CompletionLimits(maxTokens = maxTokens, maxCompletionTokens = null)
        }
    }

    /**
     * Selected model first, then this provider's tool-capable fallbacks.
     * Never crosses into another provider's model ids.
     */
    fun toolCandidates(selectedModel: String): List<String> {
        val catalogIds = if (this == OPENROUTER) models.map { it.id } else emptyList()
        return (listOf(selectedModel) + toolFallbackModels + catalogIds)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { modelId ->
                knownNonToolModels.any { blocked -> modelId.equals(blocked, ignoreCase = true) }
            }
            .distinct()
            .take(MAX_TOOL_CANDIDATES)
    }

    fun normalizeModelId(raw: String): String {
        return raw.removePrefix("models/").trim()
    }

    fun acceptsCatalogModel(rawId: String): Boolean {
        val id = normalizeModelId(rawId).lowercase()
        if (id.isBlank()) return false
        return when (this) {
            OPENROUTER -> id.contains("/")
            GEMINI -> id.startsWith("gemini-") &&
                    BLOCKED_MODEL_MARKERS.none { id.contains(it) } &&
                    id != "gemini-robotics-er-2-preview"
            OPENAI -> (id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4")) &&
                    BLOCKED_MODEL_MARKERS.none { id.contains(it) }
            XAI -> id.startsWith("grok-") &&
                    BLOCKED_MODEL_MARKERS.none { id.contains(it) } &&
                    !id.contains("imagine") &&
                    !id.contains("voice")
            ANTHROPIC -> id.startsWith("claude-") &&
                    BLOCKED_MODEL_MARKERS.none { id.contains(it) }
        }
    }

    companion object {
        const val MAX_TOOL_CANDIDATES = 10

        fun fromId(id: String?): LlmProvider {
            return entries.find { it.id == id } ?: OPENROUTER
        }

        fun dnsFailureMessage(host: String): String {
            return "Cannot resolve $host (DNS/network issue). Verify internet access, disable broken Private DNS/VPN, then retry."
        }

        private val BLOCKED_MODEL_MARKERS = listOf(
            "embed",
            "whisper",
            "tts",
            "transcribe",
            "realtime",
            "image",
            "audio",
            "moderation",
            "dall-e",
            "sora",
            "veo",
            "lyria",
            "imagen",
            "omni",
            "live",
            "robot",
            "native-audio",
            "computer-use",
            "deep-research",
            "antigravity"
        )

        private val DATED_SNAPSHOT = Regex("-\\d{4}-\\d{2}-\\d{2}")

        fun mergeCatalog(provider: LlmProvider, liveIds: List<String>): List<ModelInfo> {
            val curated = provider.models.distinctBy { it.id }
            val curatedIds = curated.map { it.id }.toSet()
            val extras = liveIds
                .map { provider.normalizeModelId(it) }
                .filter { provider.acceptsCatalogModel(it) }
                .filter { it !in curatedIds }
                .filterNot { DATED_SNAPSHOT.containsMatchIn(it) }
                .distinct()
                .take(6)
                .map { id -> ModelInfo(id, id, "Available on ${provider.displayName}") }
            return curated + extras
        }
    }
}

enum class SpeechBackend {
    OPENROUTER_AUDIO,
    OPENAI_SPEECH,
    GEMINI_TTS,
    XAI_TTS,
    NONE
}

data class LlmVoice(
    val id: String,
    val name: String,
    val description: String
)

data class CompletionLimits(
    val maxTokens: Int?,
    val maxCompletionTokens: Int?
)

data class LlmSelection(
    val provider: LlmProvider,
    val apiKey: String?,
    val model: String
)
