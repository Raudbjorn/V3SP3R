package com.vesper.flipper.ai

import android.util.Log
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.data.readBoundedBytes
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.security.InputValidator
import com.vesper.flipper.security.RateLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat client for the selected LLM provider.
 * Handles tool-calling with the execute_command interface.
 * Includes rate limiting, retry logic, and response validation.
 * The class name is kept so existing call sites stay stable.
 */
@Singleton
class OpenRouterClient {
    private val settingsStore: SettingsStore
    private val client: OkHttpClient

    @Inject constructor(settingsStore: SettingsStore) : this(settingsStore, OkHttpClient.Builder()
        .connectionSpecs(com.vesper.flipper.security.TransportSecurity.tlsOnly)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build())

    internal constructor(settingsStore: SettingsStore, client: OkHttpClient) {
        this.settingsStore = settingsStore
        this.client = client
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    // Rate limiter: 30 requests per minute
    private val rateLimiter = RateLimiter(maxRequests = 30, windowMs = 60_000)
    private val toolUnsupportedModels = ConcurrentHashMap<String, Long>()

    // Retry configuration
    private object RetryConfig {
        const val MAX_RETRIES = 2
        const val INITIAL_DELAY_MS = 700L
    }

    // Use centralized prompt system for consistency and maintainability
    private val baseSystemPrompt = VesperPrompts.SYSTEM_PROMPT

    /**
     * Send a chat completion request with tool calling.
     * Includes rate limiting, retry logic with exponential backoff, and response validation.
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        sessionId: String,
        onEvent: (suspend (ChatStreamEvent) -> Unit)? = null
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        // Check rate limit
        if (!rateLimiter.tryAcquire()) {
            val waitTime = rateLimiter.timeUntilReset() / 1000
            return@withContext ChatCompletionResult.Error(
                "Rate limit exceeded. Please wait ${waitTime}s before trying again."
            )
        }

        val selection = settingsStore.readLlmSelection()
        val provider = selection.provider
        val apiKey = selection.apiKey
            ?: return@withContext ChatCompletionResult.Error("${provider.displayName} API key not configured")

        // Validate API key format
        if (!InputValidator.isValidApiKey(apiKey)) {
            return@withContext ChatCompletionResult.Error("Invalid API key format")
        }

        val model = selection.model

        val compactMessages = trimConversationForRequest(messages, provider == LlmProvider.ANTHROPIC)

        // Image preprocessing is handled by VesperAgent.processAIResponse() before
        // calling chat(). If images somehow survive (e.g. direct chat() call), handle
        // them here as a safety net.
        val hasImages = compactMessages.any { !it.imageAttachments.isNullOrEmpty() }
        val processedMessages = if (hasImages) {
            Log.w(TAG, "chat(): images still present at chat() entry — running safety-net preprocessing")
            preprocessImagesAsText(compactMessages, apiKey, provider)
        } else {
            compactMessages
        }

        // Build system prompt — append smartglasses camera section when glasses are enabled
        val glassesEnabled = settingsStore.glassesEnabled.first()
        val systemPrompt = if (glassesEnabled) {
            baseSystemPrompt + "\n\n" + VesperPrompts.SMARTGLASSES_ADDENDUM
        } else {
            baseSystemPrompt
        }

        val requestMessages = buildList {
            add(OpenRouterMessage.text(role = "system", content = systemPrompt))
            addAll(sanitizeAndBuildRequestMessages(processedMessages, provider == LlmProvider.ANTHROPIC))
        }

        val candidateModels = provider.toolCandidates(model)
        var lastError: ChatCompletionResult.Error? = null
        val attemptedModels = mutableListOf<String>()
        val unsupportedModels = mutableListOf<String>()
        var hasStreamOutput = false
        val trackedEvents: (suspend (ChatStreamEvent) -> Unit)? = onEvent?.let { callback ->
            { event -> hasStreamOutput = true; callback(event) }
        }

        for (candidateModel in candidateModels) {
            attemptedModels.add(candidateModel)

            if (isToolModelTemporarilyBlocked(candidateModel)) {
                unsupportedModels.add(candidateModel)
                continue
            }

            val result = executeWithRetry(
                buildToolCallingRequest(
                    provider = provider,
                    apiKey = apiKey,
                    model = candidateModel,
                    messages = requestMessages,
                    includeGlassesActions = glassesEnabled,
                    stream = onEvent != null,
                    thinkingBudget = if (provider == LlmProvider.ANTHROPIC && settingsStore.extendedThinking.first()) 2048 else 0,
                    effort = settingsStore.reasoningEffort.first()
                ), trackedEvents
            )

            when (result) {
                is ChatCompletionResult.Success -> {
                    toolUnsupportedModels.remove(candidateModel)
                    return@withContext result
                }

                is ChatCompletionResult.Error -> {
                    lastError = result
                    if (hasStreamOutput) return@withContext result

                    if (isToolResultPairingError(result.message)) {
                        return@withContext ChatCompletionResult.Error(
                            "Tool-call history is out of sync for this conversation. Start a new chat session and retry."
                        )
                    }

                    if (isToolUseUnsupportedError(result.message)) {
                        markToolModelUnsupported(candidateModel)
                        unsupportedModels.add(candidateModel)
                        continue
                    }

                    if (isModelAvailabilityError(result.message)) {
                        continue
                    }

                    return@withContext result
                }
            }
        }

        val uniqueUnsupported = unsupportedModels.distinct()
        if (uniqueUnsupported.isNotEmpty()) {
            val attemptedPreview = attemptedModels.distinct().take(MAX_ERROR_MODEL_LIST).joinToString(", ")
            return@withContext ChatCompletionResult.Error(
                "No tool-use endpoints were available for the configured model set. " +
                        "Tried: $attemptedPreview. Select a tool-capable model in Settings."
            )
        }

        lastError ?: ChatCompletionResult.Error("Unable to find a working model for tool execution.")
    }

    /**
     * Pre-process messages that contain images by sending each image to a fast
     * vision model (Gemini Flash) for description, then replacing the image
     * attachments with the text description. This allows the primary model
     * (which may not support images) to understand what the user photographed.
     */
    internal suspend fun preprocessImagesAsText(messages: List<ChatMessage>, apiKey: String): List<ChatMessage> {
        val selection = settingsStore.readLlmSelection()
        return preprocessImagesAsText(messages, selection.apiKey ?: return messages, selection.provider)
    }

    internal suspend fun preprocessImagesAsText(
        messages: List<ChatMessage>,
        apiKey: String,
        provider: LlmProvider
    ): List<ChatMessage> = coroutineScope {
        val imageCount = messages.sumOf { it.imageAttachments?.size ?: 0 }
        Log.d(TAG, "preprocessImagesAsText: processing $imageCount image(s) across ${messages.size} message(s)")

        messages.map { msg ->
            if (msg.imageAttachments.isNullOrEmpty()) return@map msg

            // Process all images in parallel for faster response
            val results = msg.imageAttachments.map { attachment ->
                async { describeImage(provider, apiKey, attachment) }
            }.awaitAll()
            val descriptions = results.filterNotNull()
            val failedCount = results.size - descriptions.size

            Log.d(TAG, "preprocessImagesAsText: ${descriptions.size}/${results.size} image(s) described successfully")

            if (descriptions.isEmpty()) {
                // All image descriptions failed — let the model know images were attached
                Log.e(TAG, "preprocessImagesAsText: ALL ${results.size} image description(s) failed")
                val failNote = "[${msg.imageAttachments.size} image(s) were attached but could not be analyzed. " +
                    "Ask the user to try again or describe what they see.]"
                val fallbackContent = if (msg.content.isNotBlank()) {
                    "$failNote\n\n${msg.content}"
                } else {
                    failNote
                }
                return@map msg.copy(content = fallbackContent, imageAttachments = null)
            }

            val imageContext = buildString {
                descriptions.forEach { desc -> appendLine("[Attached image: $desc]") }
                if (failedCount > 0) {
                    appendLine("[$failedCount additional image(s) could not be analyzed]")
                }
            }.trim()
            val updatedContent = if (msg.content.isNotBlank()) {
                "$imageContext\n\n${msg.content}"
            } else {
                imageContext
            }

            msg.copy(content = updatedContent, imageAttachments = null)
        }
    }

    /**
     * Public entry point for agent-initiated photo analysis (request_photo action).
     * Sends the image to the vision model with a custom prompt and returns the description.
     * Tries multiple vision model candidates for resilience.
     */
    suspend fun describeImageForAgent(
        attachment: ImageAttachment,
        prompt: String
    ): String? = withContext(Dispatchers.IO) {
        val selection = settingsStore.readLlmSelection()
        val provider = selection.provider
        val apiKey = selection.apiKey ?: run {
            Log.w(TAG, "describeImageForAgent: no API key configured")
            return@withContext null
        }

        val visionMessages = listOf(
            OpenRouterMessage.text(
                role = "system",
                content = VISION_SYSTEM_PROMPT
            ),
            OpenRouterMessage.multimodal(
                role = "user",
                text = prompt,
                images = listOf(
                    ImageContent(
                        base64Data = attachment.base64Data,
                        mimeType = attachment.mimeType,
                        detail = "high"
                    )
                )
            )
        )

        for (model in provider.visionModels) {
            try {
                Log.d(TAG, "describeImageForAgent: trying model $model")
                val httpRequest = postChat(
                    provider,
                    apiKey,
                    limitedRequest(provider, model, visionMessages, tools = null, toolChoice = null, maxTokens = 500)
                )

                val result = executeWithRetry(httpRequest)
                when (result) {
                    is ChatCompletionResult.Success -> {
                        val desc = result.content.takeIf { it.isNotBlank() }
                        if (desc != null) {
                            Log.d(TAG, "describeImageForAgent: success with $model")
                            return@withContext desc
                        }
                        Log.w(TAG, "describeImageForAgent: $model returned blank, trying next")
                    }
                    is ChatCompletionResult.Error -> {
                        Log.w(TAG, "describeImageForAgent: $model failed")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "describeImageForAgent: exception with $model")
            }
        }

        Log.e(TAG, "describeImageForAgent: ALL vision models failed")
        null
    }

    /**
     * Send a single image to a fast vision model and get a detailed description.
     * Tries multiple vision model candidates for resilience against model
     * deprecation or temporary outages on OpenRouter.
     */
    private suspend fun describeImage(
        provider: LlmProvider,
        apiKey: String,
        attachment: ImageAttachment
    ): String? = withContext(Dispatchers.IO) {
        val base64Len = attachment.base64Data.length
        Log.d(TAG, "describeImage: starting vision call (mime=${attachment.mimeType}, base64len=$base64Len)")

        val visionMessages = listOf(
            OpenRouterMessage.text(
                role = "system",
                content = VISION_SYSTEM_PROMPT
            ),
            OpenRouterMessage.multimodal(
                role = "user",
                text = "Describe this image in detail. What device, brand, model, or item is shown?",
                images = listOf(
                    ImageContent(
                        base64Data = attachment.base64Data,
                        mimeType = attachment.mimeType,
                        detail = "high"
                    )
                )
            )
        )

        for (model in provider.visionModels) {
            try {
                Log.d(TAG, "describeImage: trying model $model")
                val httpRequest = postChat(
                    provider,
                    apiKey,
                    limitedRequest(provider, model, visionMessages, tools = null, toolChoice = null, maxTokens = 300)
                )

                val result = executeWithRetry(httpRequest)
                when (result) {
                    is ChatCompletionResult.Success -> {
                        val desc = result.content.takeIf { it.isNotBlank() }
                        if (desc != null) {
                            Log.d(TAG, "describeImage: success with $model (${desc.length} chars)")
                            return@withContext desc
                        }
                        Log.w(TAG, "describeImage: $model returned blank content, trying next model")
                    }
                    is ChatCompletionResult.Error -> {
                        Log.w(TAG, "describeImage: $model failed, trying next model")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "describeImage: exception with $model, trying next model")
            }
        }

        Log.e(TAG, "describeImage: ALL vision models failed for image (base64len=$base64Len)")
        return@withContext null
    }

    /**
     * Simple text-only chat without tool calling.
     * Used by ForgeEngine for payload generation where we just need text output.
     * Returns the AI's text response or null on failure.
     */
    suspend fun chatSimple(prompt: String): String? = withContext(Dispatchers.IO) {
        if (!rateLimiter.tryAcquire()) return@withContext null

        val selection = settingsStore.readLlmSelection()
        val apiKey = selection.apiKey ?: return@withContext null
        if (!InputValidator.isValidApiKey(apiKey)) return@withContext null

        val request = limitedRequest(
            provider = selection.provider,
            model = selection.model,
            messages = listOf(OpenRouterMessage.text(role = "user", content = prompt)),
            tools = null,
            toolChoice = null,
            maxTokens = FORGE_RESPONSE_MAX_TOKENS
        )
        val httpRequest = postChat(selection.provider, apiKey, request)

        val result = executeWithRetry(httpRequest)
        when (result) {
            is ChatCompletionResult.Success -> result.content.takeIf { it.isNotBlank() }
            is ChatCompletionResult.Error -> null
        }
    }

    private fun buildToolCallingRequest(
        provider: LlmProvider,
        apiKey: String,
        model: String,
        messages: List<OpenRouterMessage>,
        includeGlassesActions: Boolean = false,
        stream: Boolean = false,
        thinkingBudget: Int = 0,
        effort: String? = null
    ): Request {
        val tool = if (includeGlassesActions) EXECUTE_COMMAND_TOOL else buildToolWithoutGlasses()
        val request = limitedRequest(
            provider = provider,
            model = model,
            messages = messages,
            tools = listOf(tool),
            toolChoice = "auto",
            maxTokens = TOOL_CALL_RESPONSE_MAX_TOKENS
        )
        return postChat(provider, apiKey, request.copy(stream = stream, thinkingBudget = thinkingBudget, effort = effort))
    }

    private fun limitedRequest(
        provider: LlmProvider,
        model: String,
        messages: List<OpenRouterMessage>,
        tools: List<OpenRouterTool>?,
        toolChoice: String?,
        maxTokens: Int
    ): OpenRouterRequest {
        val limits = provider.completionLimits(maxTokens)
        return OpenRouterRequest(
            model = model,
            messages = messages,
            tools = tools,
            toolChoice = toolChoice,
            maxTokens = limits.maxTokens,
            maxCompletionTokens = limits.maxCompletionTokens
        )
    }

    private fun postChat(
        provider: LlmProvider,
        apiKey: String,
        request: OpenRouterRequest
    ): Request {
        return (if (provider == LlmProvider.ANTHROPIC) AnthropicChatProtocol else OpenAiChatProtocol)
            .buildRequest(provider, apiKey, request)
    }

    /**
     * Execute HTTP request with exponential backoff retry for transient failures.
     */
    fun chatStream(messages: List<ChatMessage>, sessionId: String): Flow<ChatStreamEvent> = completionEvents { onEvent ->
        chat(messages, sessionId, onEvent)
    }

    private suspend fun executeStream(request: Request, onEvent: suspend (ChatStreamEvent) -> Unit): ChatCompletionResult {
        return try {
            client.newCall(request).withCancellation { response ->
                if (!response.isSuccessful) streamHttpError(response.code, response.body?.readBoundedBytes(256 * 1024)?.decodeToString().orEmpty()).copy(
                    retryAfterMillis = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 30)?.times(1000)
                )
                else {
                    val body = response.body ?: throw IOException("Empty stream")
                    val native = request.tag(ChatProtocol::class.java) == AnthropicChatProtocol
                    val raw = ChatStreamReader(native).read(body.source(), onEvent)
                    val result = parseResponse(if (native) AnthropicChatProtocol.normalizeResponse(raw) else raw)
                    if (result is ChatCompletionResult.Success && result.content.isBlank() && result.toolCalls.isNullOrEmpty())
                        return@withCancellation ChatCompletionResult.Error("The model returned an empty response")
                    if (native && result is ChatCompletionResult.Success) result.copy(
                        providerContentJson = Json.parseToJsonElement(raw).jsonObject["content"].toString()
                    ) else result
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ChatCompletionResult.Error(e.message ?: "Stream failed", retryable = e is IOException) }
    }

    private suspend fun executeWithRetry(request: Request, onEvent: (suspend (ChatStreamEvent) -> Unit)? = null): ChatCompletionResult {
        if (onEvent != null) return retryStreamBeforeOutput(onEvent) { emit -> executeStream(request, emit) }
        return retryStreamBeforeOutput({}, RetryConfig.MAX_RETRIES, RetryConfig.INITIAL_DELAY_MS) {
            try {
                client.newCall(request).withCancellation { response ->
                    val responseBody = if (response.isSuccessful) response.body?.string()
                        else response.body?.readBoundedBytes(256 * 1024)?.decodeToString()
                    when {
                        response.code == 429 -> ChatCompletionResult.Error(
                            "Rate limited by the API (HTTP 429). Please retry shortly.", retryable = true,
                            retryAfterMillis = (response.header("Retry-After")?.toLongOrNull() ?: 30).coerceIn(0,30) * 1000)
                        !response.isSuccessful -> ChatCompletionResult.Error(
                            "API error: ${response.code} - ${responseBody ?: "Unknown error"}",
                            retryable = response.code in 500..599)
                        responseBody == null -> ChatCompletionResult.Error("Empty response from API")
                        else -> parseResponse(request.tag(ChatProtocol::class.java)?.normalizeResponse(responseBody) ?: responseBody)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (isDnsResolutionFailure(e)) ChatCompletionResult.Error(LlmProvider.dnsFailureMessage(request.url.host))
                else ChatCompletionResult.Error("Request failed: ${e.message}", retryable = true)
            } catch (e: Exception) {
                ChatCompletionResult.Error("Request failed")
            }
        }
    }

    /**
     * Parse and validate the API response.
     */
    internal fun parseResponse(responseBody: String): ChatCompletionResult {
        return try {
            val root = try {
                json.parseToJsonElement(responseBody).jsonObject
            } catch (_: Exception) {
                return ChatCompletionResult.Error("Invalid JSON in response")
            }

            // Check for OpenRouter error envelope before parsing as a successful response.
            // Error responses look like: {"error": {"message": "...", "code": 400}}
            val errorObj = root["error"]?.jsonObject
            if (errorObj != null) {
                val errorMsg = errorObj["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown API error"
                val errorCode = errorObj["code"]?.jsonPrimitive?.intOrNull
                Log.e(TAG, "OpenRouter API error (code=$errorCode)")
                return ChatCompletionResult.Error("API error${errorCode?.let { " $it" } ?: ""}: $errorMsg")
            }

            val apiResponse = json.decodeFromString<OpenRouterResponse>(responseBody)

            // Warn on missing ID but don't reject — some providers omit it for
            // simple completions (e.g. vision-only calls).
            if (apiResponse.id.isBlank()) {
                Log.w(TAG, "Response missing ID")
            }

            val choice = apiResponse.choices.firstOrNull()
                ?: return ChatCompletionResult.Error("No choices in response")

            val message = choice.message

            // Extract content — OpenRouterResponseMessage.textContent handles
            // both plain string and array-of-parts formats transparently.
            val content = stripThinkingTags(message.textContent ?: "")

            // Validate tool calls if present
            val rawToolCalls = message.toolCalls
            if (rawToolCalls.orEmpty().any { it.function.name.isNotBlank() && it.function.name != "execute_command" }) {
                return ChatCompletionResult.Error("Model requested an unsupported tool. Select another tool-capable model and retry.")
            }
            val toolCalls = rawToolCalls?.mapNotNull { tc ->
                // Validate tool call structure — reject blanks but log them
                if (tc.id.isBlank() || tc.function.name.isBlank()) {
                    Log.w(TAG, "Dropping malformed tool call")
                    null
                } else {
                    ToolCall(
                        id = tc.id,
                        name = tc.function.name,
                        arguments = tc.function.arguments
                    )
                }
            }?.take(MAX_TOOL_CALLS_PER_RESPONSE)

            if (!rawToolCalls.isNullOrEmpty() && rawToolCalls.size != toolCalls?.size) {
                return ChatCompletionResult.Error("Model returned malformed or excessive tool calls. Retry the request.")
            }
            if (content.isBlank() && toolCalls.isNullOrEmpty()) {
                val reason = when (choice.finishReason) {
                    "content_filter", "safety", "blocklist" -> "The provider declined this request."
                    "length" -> "The model reached its output limit before producing a response."
                    else -> "The model returned an empty response."
                }
                return ChatCompletionResult.Error(reason + " Retry or check the model in Settings.")
            }

            ChatCompletionResult.Success(
                content = content,
                toolCalls = toolCalls?.takeIf { it.isNotEmpty() },
                model = apiResponse.model,
                tokensUsed = apiResponse.usage?.totalTokens
            )
        } catch (e: Exception) {
            ChatCompletionResult.Error("Failed to parse response")
        }
    }

    internal fun sanitizeAndBuildRequestMessages(
        messages: List<ChatMessage>,
        preserveNativeContent: Boolean = false
    ): List<OpenRouterMessage> {
        val requestMessages = mutableListOf<OpenRouterMessage>()
        val openToolCallIds = linkedSetOf<String>()
        val unresolvedAssistantToolCallIndexes = mutableListOf<Int>()

        fun dropUnresolvedToolCalls() {
            if (openToolCallIds.isEmpty()) return
            unresolvedAssistantToolCallIndexes
                .distinct()
                .sortedDescending()
                .forEach { index ->
                    if (index in requestMessages.indices) {
                        requestMessages.removeAt(index)
                    }
                }
            unresolvedAssistantToolCallIndexes.clear()
            openToolCallIds.clear()
        }

        messages.forEach { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    if (openToolCallIds.isNotEmpty()) {
                        // Provider APIs reject unresolved tool-call chains; drop stale assistant tool calls.
                        dropUnresolvedToolCalls()
                    }
                    if (!msg.imageAttachments.isNullOrEmpty()) {
                        val images = msg.imageAttachments.map { attachment ->
                            ImageContent(
                                base64Data = attachment.base64Data,
                                mimeType = attachment.mimeType
                            )
                        }
                        requestMessages.add(
                            OpenRouterMessage.multimodal(
                                role = "user",
                                text = msg.content,
                                images = images
                            )
                        )
                    } else {
                        requestMessages.add(OpenRouterMessage.text(role = "user", content = msg.content))
                    }
                }

                MessageRole.ASSISTANT -> {
                    val sanitizedToolCalls = msg.toolCalls
                        ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
                        ?.take(MAX_TOOL_CALLS_PER_RESPONSE)

                    if (!sanitizedToolCalls.isNullOrEmpty()) {
                        if (openToolCallIds.isNotEmpty()) {
                            dropUnresolvedToolCalls()
                        }
                        requestMessages.add(
                            OpenRouterMessage(
                                role = "assistant",
                                providerContentJson = if (preserveNativeContent) msg.metadata?.providerContentJson else null,
                                content = if (msg.content.isEmpty()) null else JsonPrimitive(msg.content),
                                toolCalls = sanitizedToolCalls.map { tc ->
                                    OpenRouterToolCall(
                                        id = tc.id,
                                        type = "function",
                                        function = OpenRouterFunction(
                                            name = tc.name,
                                            arguments = tc.arguments
                                        )
                                    )
                                }
                            )
                        )
                        unresolvedAssistantToolCallIndexes.add(requestMessages.lastIndex)
                        openToolCallIds.clear()
                        openToolCallIds.addAll(sanitizedToolCalls.map { it.id })
                    } else if (msg.content.isNotEmpty() ||
                        (preserveNativeContent && !msg.metadata?.providerContentJson.isNullOrBlank())) {
                        if (openToolCallIds.isNotEmpty()) {
                            dropUnresolvedToolCalls()
                        }
                        requestMessages.add(OpenRouterMessage.text(role = "assistant", content = msg.content).copy(providerContentJson = if (preserveNativeContent) msg.metadata?.providerContentJson else null))
                    }
                }

                MessageRole.TOOL -> {
                    val validResults = msg.toolResults
                        .orEmpty()
                        .filter { it.toolCallId.isNotBlank() && openToolCallIds.contains(it.toolCallId) }
                        .take(MAX_TOOL_CALLS_PER_RESPONSE)

                    validResults.forEach { result ->
                        requestMessages.add(
                            OpenRouterMessage(
                                role = "tool",
                                content = JsonPrimitive(result.content),
                                toolCallId = result.toolCallId
                            )
                        )
                        openToolCallIds.remove(result.toolCallId)
                    }
                    if (openToolCallIds.isEmpty()) {
                        unresolvedAssistantToolCallIndexes.clear()
                    }
                }

                MessageRole.SYSTEM -> Unit
            }
        }

        if (openToolCallIds.isNotEmpty()) {
            dropUnresolvedToolCalls()
        }

        return requestMessages
    }

    /**
     * Parse tool call arguments into ExecuteCommand
     */
    fun parseCommand(arguments: String): ExecuteCommand? {
        return parseCommandDetailed(arguments).command
    }

    data class ParsedCommand(
        val command: ExecuteCommand? = null,
        val error: String? = null
    )

    /**
     * Parse tool call arguments and include a diagnostic error on failure.
     */
    fun parseCommandDetailed(arguments: String): ParsedCommand {
        val trimmedArguments = arguments.trim()
        if (trimmedArguments.isEmpty()) {
            return ParsedCommand(
                error = "Tool arguments were empty. Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
            )
        }

        // Fast-path: strict decoding when the model follows schema perfectly.
        runCatching {
            val command = json.decodeFromString<ExecuteCommand>(trimmedArguments)
            val missing = missingRequiredArgs(command.action, command.args)
            return if (missing.isEmpty()) ParsedCommand(command = command) else ParsedCommand(
                error = "Missing required argument(s): ${missing.joinToString()}. Example: ${requiredArgsExample(command.action)}"
            )
        }

        // Attempt to repair common JSON malformations from LLMs before fallback parsing.
        val repairedArguments = repairMalformedJson(trimmedArguments)

        // Fallback: tolerate missing non-critical fields and args shape variations.
        return runCatching {
            val rawElement = json.parseToJsonElement(repairedArguments)

            // Unwrap single-element JSON arrays — some models wrap args in [{...}].
            val rootElement = when {
                rawElement is JsonArray && rawElement.size == 1 -> rawElement[0]
                else -> rawElement
            }

            val root = rootElement as? JsonObject ?: return ParsedCommand(
                error = "Tool arguments must be a JSON object, got ${describeJsonType(rootElement)}. " +
                        "Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
            )
            val actionValue = root["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (actionValue.isEmpty()) {
                return ParsedCommand(
                    error = "Missing required field \"action\". " +
                            "Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
                )
            }
            val action = parseCommandAction(actionValue) ?: return ParsedCommand(
                error = "Unsupported action \"$actionValue\". " +
                        "Supported actions: ${SUPPORTED_ACTIONS.joinToString(", ")}"
            )

            val argsElement = root["args"] ?: root["parameters"]
            val argsObject = when (argsElement) {
                null -> JsonObject(emptyMap())
                is JsonObject -> argsElement
                is JsonArray -> {
                    // Some models wrap args in a single-element array: "args": [{...}]
                    if (argsElement.size == 1 && argsElement[0] is JsonObject) {
                        argsElement[0] as JsonObject
                    } else if (argsElement.isEmpty()) {
                        JsonObject(emptyMap())
                    } else {
                        return ParsedCommand(
                            error = "Field \"args\" must be a JSON object, got array with ${argsElement.size} elements."
                        )
                    }
                }
                is JsonPrimitive -> {
                    val inner = argsElement.contentOrNull.orEmpty()
                    if (inner.isBlank()) {
                        JsonObject(emptyMap())
                    } else {
                        val nested = runCatching { json.parseToJsonElement(inner) }.getOrElse {
                            return ParsedCommand(
                                error = "Field \"args\" must be a JSON object. " +
                                        "Could not parse args string: ${sanitizeErrorMessage(it.message)}"
                            )
                        }
                        nested as? JsonObject ?: return ParsedCommand(
                            error = "Field \"args\" must be a JSON object, got ${describeJsonType(nested)}."
                        )
                    }
                }
                else -> return ParsedCommand(
                    error = "Field \"args\" must be a JSON object, got ${describeJsonType(argsElement)}."
                )
            }

            fun stringArg(primary: String, vararg aliases: String): String? {
                val keys = listOf(primary) + aliases
                return keys.firstNotNullOfOrNull { key ->
                    argsObject[key]?.jsonPrimitive?.contentOrNull
                        ?: root[key]?.jsonPrimitive?.contentOrNull
                }
            }

            fun booleanArg(primary: String, vararg aliases: String): Boolean? {
                val keys = listOf(primary) + aliases
                return keys.firstNotNullOfOrNull { key ->
                    val value = argsObject[key]?.jsonPrimitive
                        ?: root[key]?.jsonPrimitive
                        ?: return@firstNotNullOfOrNull null
                    value.booleanOrNull ?: when (value.contentOrNull?.lowercase()) {
                        "true", "1", "yes" -> true
                        "false", "0", "no" -> false
                        else -> null
                    }
                }
            }

            fun intArg(primary: String, vararg aliases: String): Int? {
                val keys = listOf(primary) + aliases
                return keys.firstNotNullOfOrNull { key ->
                    (argsObject[key]?.jsonPrimitive ?: root[key]?.jsonPrimitive)
                        ?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
                }
            }

            fun longArg(primary: String, vararg aliases: String): Long? {
                val keys = listOf(primary) + aliases
                return keys.firstNotNullOfOrNull { key ->
                    (argsObject[key]?.jsonPrimitive ?: root[key]?.jsonPrimitive)
                        ?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
                }
            }

            fun doubleArg(name: String): Double? = (argsObject[name]?.jsonPrimitive ?: root[name]?.jsonPrimitive)?.contentOrNull?.toDoubleOrNull()
            val args = CommandArgs(
                key = stringArg("key"),
                pressType = stringArg("press_type", "pressType"),
                signalId = intArg("signal_id", "signalId"),
                command = stringArg("command", "cmd", "query", "app_id", "appId", "app", "package", "name"),
                path = stringArg("path", "file_path", "filepath"),
                destinationPath = stringArg("destination_path", "destinationPath", "dest", "destination"),
                content = stringArg("content", "text", "data"),
                newName = stringArg("new_name", "newName"),
                recursive = booleanArg("recursive", "is_recursive") ?: false,
                artifactType = stringArg("artifact_type", "artifactType"),
                artifactData = stringArg("artifact_data", "artifactData", "data_base64"),
                prompt = stringArg("prompt", "description", "forge_prompt"),
                resourceType = stringArg("resource_type", "resourceType", "type"),
                runbookId = stringArg("runbook_id", "runbookId", "runbook"),
                payloadType = stringArg("payload_type", "payloadType"),
                filter = stringArg("filter", "vault_filter"),
                // Hardware control fields
                appName = stringArg("app_name", "appName", "app_name_launch"),
                appArgs = stringArg("app_args", "appArgs", "app_arguments"),
                frequency = longArg("frequency", "freq"),
                protocol = stringArg("protocol"),
                address = stringArg("address"),
                signalName = stringArg("signal_name", "signalName", "signal"),
                enabled = booleanArg("enabled", "on"),
                red = intArg("red", "r"),
                green = intArg("green", "g"),
                blue = intArg("blue", "b"),
                // Repo browsing / download / GitHub search fields
                repoId = stringArg("repo_id", "repoId", "repo"),
                subPath = stringArg("sub_path", "subPath"),
                downloadUrl = stringArg("download_url", "downloadUrl", "url"),
                searchScope = stringArg("search_scope", "searchScope", "scope"),
                // Smartglasses camera
                photoPrompt = stringArg("photo_prompt", "photoPrompt"),
                duration = doubleArg("duration"),
                uuid = stringArg("uuid"),
                hex = booleanArg("hex"),
                withResponse = booleanArg("with_response"),
                target = stringArg("target"),
                vulnType = stringArg("vuln_type"),
                description = stringArg("description"),
                evidence = stringArg("evidence"),
                severity = stringArg("severity"),
                complexity = intArg("complexity"),
                vulnId = stringArg("vuln_id"),
                reproduced = booleanArg("reproduced"),
                notes = stringArg("notes"),
                status = stringArg("status"),
                limit = intArg("limit"),
                riskLevel = stringArg("risk_level"),
                pin = stringArg("pin"),
                value = intArg("value"),
                mode = intArg("mode"),
                keyType = stringArg("key_type"),
                keyData = stringArg("key_data")
            )

            val missingArgs = missingRequiredArgs(action, args)
            if (missingArgs.isNotEmpty()) {
                return ParsedCommand(
                    error = "Action \"${action.name.lowercase()}\" is missing required argument(s): " +
                            "${missingArgs.joinToString(", ")}. Example: ${requiredArgsExample(action)}"
                )
            }

            ParsedCommand(
                command = ExecuteCommand(
                    action = action,
                    args = args,
                    justification = root["justification"]?.jsonPrimitive?.contentOrNull
                        ?: "Tool call requested by AI",
                    expectedEffect = root["expected_effect"]?.jsonPrimitive?.contentOrNull
                        ?: "Execute requested operation safely."
                )
            )
        }.getOrElse {
            ParsedCommand(
                error = "Could not parse tool arguments JSON: ${sanitizeErrorMessage(it.message)}. " +
                        "Expected format: $EXPECTED_TOOL_ARGUMENTS_FORMAT"
            )
        }
    }

    private fun parseCommandAction(value: String): CommandAction? {
        val normalized = value
            .trim()
            // Convert camelCase to snake_case before lowercasing:
            // "forgePayload" → "forge_Payload" → "forge_payload"
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        return when (normalized) {
            "input_send" -> CommandAction.INPUT_SEND
            "loader_list" -> CommandAction.LOADER_LIST
            "loader_close" -> CommandAction.LOADER_CLOSE
            "loader_signal" -> CommandAction.LOADER_SIGNAL
            "list_directory" -> CommandAction.LIST_DIRECTORY
            "read_file" -> CommandAction.READ_FILE
            "write_file" -> CommandAction.WRITE_FILE
            "create_directory" -> CommandAction.CREATE_DIRECTORY
            "delete" -> CommandAction.DELETE
            "move" -> CommandAction.MOVE
            "rename" -> CommandAction.RENAME
            "copy" -> CommandAction.COPY
            "get_device_info" -> CommandAction.GET_DEVICE_INFO
            "get_storage_info" -> CommandAction.GET_STORAGE_INFO
            "search_faphub", "faphub_search", "find_faphub" -> CommandAction.SEARCH_FAPHUB
            "install_faphub_app", "install_faphub", "faphub_install" -> CommandAction.INSTALL_FAPHUB_APP
            "push_artifact" -> CommandAction.PUSH_ARTIFACT
            "execute_cli", "execute_command", "run_command", "cli_command", "command", "send_command" ->
                CommandAction.EXECUTE_CLI
            "forge_payload", "forge", "craft_payload", "create_payload" -> CommandAction.FORGE_PAYLOAD
            "search_resources", "browse_resources", "find_resources" -> CommandAction.SEARCH_RESOURCES
            "browse_repo", "browse_repository", "list_repo", "repo_browse", "repo_contents" -> CommandAction.BROWSE_REPO
            "download_resource", "download_file", "fetch_resource", "get_resource" -> CommandAction.DOWNLOAD_RESOURCE
            "github_search", "search_github", "gh_search", "find_on_github" -> CommandAction.GITHUB_SEARCH
            "list_vault", "vault", "scan_vault", "inventory" -> CommandAction.LIST_VAULT
            "run_runbook", "runbook", "diagnostic" -> CommandAction.RUN_RUNBOOK
            "launch_app", "open_app", "start_app", "loader_open" -> CommandAction.LAUNCH_APP
            "subghz_transmit", "subghz_tx", "sub_ghz_transmit", "transmit_subghz" -> CommandAction.SUBGHZ_TRANSMIT
            "ir_transmit", "infrared_transmit", "transmit_ir" -> CommandAction.IR_TRANSMIT
            "nfc_emulate", "emulate_nfc" -> CommandAction.NFC_EMULATE
            "rfid_emulate", "emulate_rfid", "lfrfid_emulate" -> CommandAction.RFID_EMULATE
            "ibutton_emulate", "emulate_ibutton" -> CommandAction.IBUTTON_EMULATE
            "badusb_execute", "run_badusb", "execute_badusb", "badusb_run" -> CommandAction.BADUSB_EXECUTE
            "ble_spam", "blespam", "ble_advertisement_spam" -> CommandAction.BLE_SPAM
            "led_control", "set_led", "led" -> CommandAction.LED_CONTROL
            "vibro_control", "vibro", "vibration" -> CommandAction.VIBRO_CONTROL
            "load_skill" -> CommandAction.LOAD_SKILL
            "ble_scan_targets" -> CommandAction.BLE_SCAN_TARGETS
            "ble_enumerate" -> CommandAction.BLE_ENUMERATE
            "ble_read_char" -> CommandAction.BLE_READ_CHAR
            "ble_write_char" -> CommandAction.BLE_WRITE_CHAR
            "ble_subscribe" -> CommandAction.BLE_SUBSCRIBE
            "vuln_submit" -> CommandAction.VULN_SUBMIT
            "vuln_validate" -> CommandAction.VULN_VALIDATE
            "vuln_list" -> CommandAction.VULN_LIST
            "vuln_classify" -> CommandAction.VULN_CLASSIFY
            "audit_query" -> CommandAction.AUDIT_QUERY
            "gpio_read" -> CommandAction.GPIO_READ
            "gpio_set" -> CommandAction.GPIO_SET
            "gpio_mode" -> CommandAction.GPIO_MODE
            "apps_list" -> CommandAction.APPS_LIST
            "get_system_info" -> CommandAction.GET_SYSTEM_INFO
            "nfc_detect" -> CommandAction.NFC_DETECT
            "rfid_read" -> CommandAction.RFID_READ
            "rfid_write" -> CommandAction.RFID_WRITE
            "ir_receive" -> CommandAction.IR_RECEIVE
            "badusb_validate" -> CommandAction.BADUSB_VALIDATE
            "badusb_diff" -> CommandAction.BADUSB_DIFF
            "request_photo", "take_photo", "capture_photo", "photo", "snap_photo" -> CommandAction.REQUEST_PHOTO
            else -> null
        }
    }

    private fun describeJsonType(element: JsonElement): String {
        return when {
            element is JsonObject -> "object"
            element is JsonArray -> "array"
            element == JsonNull -> "null"
            element is JsonPrimitive && element.isString -> "string"
            element is JsonPrimitive -> "primitive"
            else -> "unknown"
        }
    }

    /**
     * Attempt to repair common JSON malformations produced by LLMs.
     * Handles: trailing commas, unclosed strings/braces/brackets, and
     * markdown code fences wrapping JSON.
     *
     * All transformations are string-context-aware to avoid corrupting
     * values inside JSON strings (e.g. a path containing ",}").
     */
    private fun repairMalformedJson(input: String): String {
        // Guard against excessively large inputs
        if (input.length > MAX_REPAIRABLE_JSON_LENGTH) return input

        var s = input.trim()

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            s = if (firstNewline > 0) s.substring(firstNewline + 1) else s.removePrefix("```")
            if (s.endsWith("```")) {
                s = s.dropLast(3).trimEnd()
            }
        }

        // Single-pass walk: track string context, trailing commas, and bracket depth.
        var inString = false
        var escaped = false
        val stack = mutableListOf<Char>()
        val sb = StringBuilder(s.length + 16)
        // Buffer to hold a comma + optional whitespace so we can drop it
        // if the next non-whitespace char is } or ].
        var pendingComma: StringBuilder? = null

        for (ch in s) {
            if (inString) {
                pendingComma?.let { sb.append(it); pendingComma = null }
                sb.append(ch)
                if (escaped) {
                    escaped = false
                } else when (ch) {
                    '\\' -> escaped = true
                    '"' -> inString = false
                }
            } else {
                when (ch) {
                    ',' -> {
                        // Buffer the comma — flush it only if not followed by } or ]
                        if (pendingComma == null) pendingComma = StringBuilder()
                        pendingComma!!.append(ch)
                    }
                    '}', ']' -> {
                        // Drop any buffered trailing comma before a closer
                        pendingComma = null
                        sb.append(ch)
                        if (ch == '}' && stack.lastOrNull() == '{') stack.removeAt(stack.lastIndex)
                        else if (ch == ']' && stack.lastOrNull() == '[') stack.removeAt(stack.lastIndex)
                    }
                    else -> {
                        if (ch.isWhitespace() && pendingComma != null) {
                            // Accumulate whitespace into the pending comma buffer
                            // so ",  }" correctly drops the comma + whitespace.
                            pendingComma!!.append(ch)
                        } else {
                            // Non-whitespace after comma — flush buffer, it's not trailing
                            pendingComma?.let { sb.append(it); pendingComma = null }
                            sb.append(ch)
                            when (ch) {
                                '"' -> inString = true
                                '{' -> stack.add('{')
                                '[' -> stack.add('[')
                            }
                        }
                    }
                }
            }
        }

        // Flush any trailing buffered comma (will be stripped if we close brackets)
        pendingComma?.let { sb.append(it) }

        // If JSON already parses cleanly after comma repair, return early.
        val commaRepaired = sb.toString()
        runCatching { json.parseToJsonElement(commaRepaired) }.onSuccess { return commaRepaired }

        // Close an unterminated string
        if (inString) sb.append('"')

        // Remove a dangling comma/whitespace at the end before closing brackets
        while (sb.isNotEmpty() && (sb.last() == ',' || sb.last().isWhitespace())) {
            sb.deleteCharAt(sb.length - 1)
        }

        // Close unclosed braces/brackets in reverse order.
        // Limit to a reasonable depth to avoid synthesizing deeply nested structures
        // from adversarial input.
        val unclosedCount = stack.size.coerceAtMost(MAX_REPAIR_BRACKET_DEPTH)
        for (i in (stack.size - unclosedCount) until stack.size) {
            val idx = stack.size - 1 - (i - (stack.size - unclosedCount))
            when (stack[idx]) {
                '{' -> sb.append('}')
                '[' -> sb.append(']')
            }
        }

        return sb.toString()
    }

    private fun sanitizeErrorMessage(message: String?): String {
        return message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.take(180)
            ?.takeIf { it.isNotEmpty() }
            ?: "invalid format"
    }

    internal fun missingRequiredArgs(action: CommandAction, args: CommandArgs): List<String> {
        return when (action) {
            CommandAction.INPUT_SEND -> listOfNotNull(if (args.key.isNullOrBlank()) "key" else null)
            CommandAction.LOADER_SIGNAL -> listOfNotNull(if (args.signalId == null) "signal_id" else null)
            CommandAction.LOADER_LIST, CommandAction.LOADER_CLOSE -> emptyList()
            CommandAction.LIST_DIRECTORY -> emptyList()
            CommandAction.READ_FILE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.WRITE_FILE -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.content.isNullOrBlank()) "content" else null
            )
            CommandAction.CREATE_DIRECTORY -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.DELETE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.MOVE -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.destinationPath.isNullOrBlank()) "destination_path" else null
            )
            CommandAction.RENAME -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.newName.isNullOrBlank()) "new_name" else null
            )
            CommandAction.COPY -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.destinationPath.isNullOrBlank()) "destination_path" else null
            )
            CommandAction.GET_DEVICE_INFO -> emptyList()
            CommandAction.GET_STORAGE_INFO -> emptyList()
            CommandAction.SEARCH_FAPHUB -> listOfNotNull(if (args.command.isNullOrBlank()) "command" else null)
            CommandAction.INSTALL_FAPHUB_APP -> listOfNotNull(if (args.command.isNullOrBlank()) "command" else null)
            CommandAction.PUSH_ARTIFACT -> listOfNotNull(
                if (args.path.isNullOrBlank()) "path" else null,
                if (args.artifactData.isNullOrBlank()) "artifact_data" else null
            )
            CommandAction.EXECUTE_CLI -> {
                if (args.command.isNullOrBlank() && args.content.isNullOrBlank()) {
                    listOf("command")
                } else {
                    emptyList()
                }
            }
            CommandAction.FORGE_PAYLOAD -> {
                if (args.prompt.isNullOrBlank() && args.command.isNullOrBlank()) {
                    listOf("prompt")
                } else {
                    emptyList()
                }
            }
            CommandAction.SEARCH_RESOURCES -> emptyList()  // query and resource_type are optional
            CommandAction.BROWSE_REPO -> {
                if (args.repoId.isNullOrBlank() && args.command.isNullOrBlank()) {
                    listOf("repo_id")
                } else {
                    emptyList()
                }
            }
            CommandAction.DOWNLOAD_RESOURCE -> listOfNotNull(
                if (args.downloadUrl.isNullOrBlank()) "download_url" else null,
                if (args.path.isNullOrBlank()) "path" else null
            )
            CommandAction.GITHUB_SEARCH -> {
                if (args.command.isNullOrBlank()) {
                    listOf("command")
                } else {
                    emptyList()
                }
            }
            CommandAction.LIST_VAULT -> emptyList()  // filter is optional
            CommandAction.RUN_RUNBOOK -> {
                if (args.runbookId.isNullOrBlank() && args.command.isNullOrBlank()) {
                    listOf("runbook_id")
                } else {
                    emptyList()
                }
            }
            CommandAction.LAUNCH_APP -> {
                if (args.appName.isNullOrBlank() && args.command.isNullOrBlank()) {
                    listOf("app_name")
                } else {
                    emptyList()
                }
            }
            CommandAction.SUBGHZ_TRANSMIT -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.IR_TRANSMIT -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.NFC_EMULATE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.RFID_EMULATE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.IBUTTON_EMULATE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.BADUSB_EXECUTE -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null)
            CommandAction.BLE_SPAM -> emptyList()  // no required args
            CommandAction.LED_CONTROL -> emptyList()  // defaults to 0,0,0
            CommandAction.VIBRO_CONTROL -> emptyList()  // defaults to on
            CommandAction.LOAD_SKILL -> listOfNotNull(if (args.command.isNullOrBlank()) "command" else null)
            CommandAction.BLE_SCAN_TARGETS -> emptyList()
            CommandAction.BLE_ENUMERATE -> listOfNotNull(if (args.address.isNullOrBlank()) "address" else null)
            CommandAction.BLE_READ_CHAR -> listOfNotNull(if (args.address.isNullOrBlank()) "address" else null, if (args.uuid.isNullOrBlank()) "uuid" else null)
            CommandAction.BLE_WRITE_CHAR -> listOfNotNull(if (args.address.isNullOrBlank()) "address" else null, if (args.uuid.isNullOrBlank()) "uuid" else null, if (args.content.isNullOrBlank()) "content" else null)
            CommandAction.BLE_SUBSCRIBE -> listOfNotNull(if (args.address.isNullOrBlank()) "address" else null, if (args.uuid.isNullOrBlank()) "uuid" else null)
            CommandAction.VULN_SUBMIT -> listOfNotNull(if (args.target.isNullOrBlank()) "target" else null, if (args.vulnType.isNullOrBlank()) "vuln_type" else null, if (args.description.isNullOrBlank()) "description" else null, if (args.evidence.isNullOrBlank()) "evidence" else null, if (args.severity.isNullOrBlank()) "severity" else null, if (args.complexity == null) "complexity" else null)
            CommandAction.VULN_VALIDATE -> listOfNotNull(if (args.vulnId.isNullOrBlank()) "vuln_id" else null, if (args.reproduced == null) "reproduced" else null)
            CommandAction.VULN_LIST -> emptyList()
            CommandAction.VULN_CLASSIFY -> listOfNotNull(if (args.vulnType.isNullOrBlank()) "vuln_type" else null)
            CommandAction.AUDIT_QUERY -> emptyList()
            CommandAction.GPIO_READ -> listOfNotNull(if (args.pin.isNullOrBlank()) "pin" else null)
            CommandAction.GPIO_SET -> listOfNotNull(if (args.pin.isNullOrBlank()) "pin" else null, if (args.value == null) "value" else null)
            CommandAction.GPIO_MODE -> listOfNotNull(if (args.pin.isNullOrBlank()) "pin" else null, if (args.mode == null) "mode" else null)
            CommandAction.APPS_LIST -> emptyList()
            CommandAction.GET_SYSTEM_INFO -> emptyList()
            CommandAction.NFC_DETECT -> emptyList()
            CommandAction.RFID_READ -> emptyList()
            CommandAction.RFID_WRITE -> listOfNotNull(if (args.keyType.isNullOrBlank()) "key_type" else null, if (args.keyData.isNullOrBlank()) "key_data" else null)
            CommandAction.IR_RECEIVE -> emptyList()
            CommandAction.BADUSB_VALIDATE -> listOfNotNull(if (args.content.isNullOrBlank()) "content" else null)
            CommandAction.BADUSB_DIFF -> listOfNotNull(if (args.path.isNullOrBlank()) "path" else null, if (args.content.isNullOrBlank()) "content" else null)
            CommandAction.REQUEST_PHOTO -> emptyList()  // prompt is optional (has default)
        }
    }

    private fun requiredArgsExample(action: CommandAction): String {
        return when (action) {
            CommandAction.INPUT_SEND -> """{"action":"input_send","args":{"key":"back","press_type":"short"}}"""
            CommandAction.LOADER_LIST -> """{"action":"loader_list","args":{}}"""
            CommandAction.LOADER_CLOSE -> """{"action":"loader_close","args":{}}"""
            CommandAction.LOADER_SIGNAL -> """{"action":"loader_signal","args":{"signal_id":1}}"""
            CommandAction.LIST_DIRECTORY -> """{"action":"list_directory","args":{"path":"/ext"}}"""
            CommandAction.READ_FILE -> """{"action":"read_file","args":{"path":"/ext/file.txt"}}"""
            CommandAction.WRITE_FILE ->
                """{"action":"write_file","args":{"path":"/ext/file.txt","content":"..."}}"""
            CommandAction.CREATE_DIRECTORY ->
                """{"action":"create_directory","args":{"path":"/ext/new_dir"}}"""
            CommandAction.DELETE ->
                """{"action":"delete","args":{"path":"/ext/file.txt","recursive":false}}"""
            CommandAction.MOVE ->
                """{"action":"move","args":{"path":"/ext/src.txt","destination_path":"/ext/dst.txt"}}"""
            CommandAction.RENAME ->
                """{"action":"rename","args":{"path":"/ext/src.txt","new_name":"dst.txt"}}"""
            CommandAction.COPY ->
                """{"action":"copy","args":{"path":"/ext/src.txt","destination_path":"/ext/dst.txt"}}"""
            CommandAction.GET_DEVICE_INFO -> """{"action":"get_device_info","args":{}}"""
            CommandAction.GET_STORAGE_INFO -> """{"action":"get_storage_info","args":{}}"""
            CommandAction.SEARCH_FAPHUB ->
                """{"action":"search_faphub","args":{"command":"edge"}}"""
            CommandAction.INSTALL_FAPHUB_APP ->
                """{"action":"install_faphub_app","args":{"command":"quac"}}"""
            CommandAction.PUSH_ARTIFACT ->
                """{"action":"push_artifact","args":{"path":"/ext/file.bin","artifact_data":"<base64>"}}"""
            CommandAction.EXECUTE_CLI ->
                """{"action":"execute_cli","args":{"command":"storage list /ext"}}"""
            CommandAction.FORGE_PAYLOAD ->
                """{"action":"forge_payload","args":{"prompt":"Create a BadUSB script that opens notepad","payload_type":"bad_usb"}}"""
            CommandAction.SEARCH_RESOURCES ->
                """{"action":"search_resources","args":{"command":"infrared remotes","resource_type":"IR_REMOTE"}}"""
            CommandAction.LIST_VAULT ->
                """{"action":"list_vault","args":{"filter":"SUB_GHZ"}}"""
            CommandAction.RUN_RUNBOOK ->
                """{"action":"run_runbook","args":{"runbook_id":"link_health"}}"""
            CommandAction.LAUNCH_APP ->
                """{"action":"launch_app","args":{"app_name":"Sub-GHz"}}"""
            CommandAction.SUBGHZ_TRANSMIT ->
                """{"action":"subghz_transmit","args":{"path":"/ext/subghz/signal.sub"}}"""
            CommandAction.IR_TRANSMIT ->
                """{"action":"ir_transmit","args":{"path":"/ext/infrared/remote.ir","signal_name":"Power"}}"""
            CommandAction.NFC_EMULATE ->
                """{"action":"nfc_emulate","args":{"path":"/ext/nfc/card.nfc"}}"""
            CommandAction.RFID_EMULATE ->
                """{"action":"rfid_emulate","args":{"path":"/ext/lfrfid/tag.rfid"}}"""
            CommandAction.IBUTTON_EMULATE ->
                """{"action":"ibutton_emulate","args":{"path":"/ext/ibutton/key.ibtn"}}"""
            CommandAction.BADUSB_EXECUTE ->
                """{"action":"badusb_execute","args":{"path":"/ext/badusb/script.txt"}}"""
            CommandAction.BLE_SPAM ->
                """{"action":"ble_spam","args":{}}"""
            CommandAction.LED_CONTROL ->
                """{"action":"led_control","args":{"red":255,"green":0,"blue":0}}"""
            CommandAction.VIBRO_CONTROL ->
                """{"action":"vibro_control","args":{"enabled":true}}"""
            CommandAction.BROWSE_REPO ->
                """{"action":"browse_repo","args":{"repo_id":"irdb","sub_path":"TVs/Samsung"}}"""
            CommandAction.DOWNLOAD_RESOURCE ->
                """{"action":"download_resource","args":{"download_url":"https://raw.githubusercontent.com/...","path":"/ext/infrared/remote.ir"}}"""
            CommandAction.GITHUB_SEARCH ->
                """{"action":"github_search","args":{"command":"Samsung TV remote extension:ir","search_scope":"code"}}"""
            CommandAction.LOAD_SKILL -> """{"action":"load_skill","args":{"command":"signal-analysis"}}"""
            CommandAction.BLE_SCAN_TARGETS -> """{"action":"ble_scan_targets","args":{}}"""
            CommandAction.BLE_ENUMERATE -> """{"action":"ble_enumerate","args":{"address":"AA:BB:CC:DD:EE:FF"}}"""
            CommandAction.BLE_READ_CHAR -> """{"action":"ble_read_char","args":{"address":"AA:BB:CC:DD:EE:FF","uuid":"00002a19-0000-1000-8000-00805f9b34fb"}}"""
            CommandAction.BLE_WRITE_CHAR -> """{"action":"ble_write_char","args":{"address":"AA:BB:CC:DD:EE:FF","uuid":"00002a19-0000-1000-8000-00805f9b34fb","content":"01"}}"""
            CommandAction.BLE_SUBSCRIBE -> """{"action":"ble_subscribe","args":{"address":"AA:BB:CC:DD:EE:FF","uuid":"00002a19-0000-1000-8000-00805f9b34fb"}}"""
            CommandAction.VULN_SUBMIT -> """{"action":"vuln_submit","args":{"target":"owned-device","vuln_type":"info_disclosure","description":"Observed banner","evidence":"Version shown in authorized test","severity":"low","complexity":1}}"""
            CommandAction.VULN_VALIDATE -> """{"action":"vuln_validate","args":{"vuln_id":"existing-session-finding-id","reproduced":false}}"""
            CommandAction.VULN_LIST -> """{"action":"vuln_list","args":{}}"""
            CommandAction.VULN_CLASSIFY -> """{"action":"vuln_classify","args":{"vuln_type":"info_disclosure"}}"""
            CommandAction.AUDIT_QUERY -> """{"action":"audit_query","args":{}}"""
            CommandAction.GPIO_READ -> """{"action":"gpio_read","args":{"pin":"PA7"}}"""
            CommandAction.GPIO_SET -> """{"action":"gpio_set","args":{"pin":"PA7","value":0}}"""
            CommandAction.GPIO_MODE -> """{"action":"gpio_mode","args":{"pin":"PA7","mode":0}}"""
            CommandAction.APPS_LIST -> """{"action":"apps_list","args":{}}"""
            CommandAction.GET_SYSTEM_INFO -> """{"action":"get_system_info","args":{}}"""
            CommandAction.NFC_DETECT -> """{"action":"nfc_detect","args":{}}"""
            CommandAction.RFID_READ -> """{"action":"rfid_read","args":{}}"""
            CommandAction.RFID_WRITE -> """{"action":"rfid_write","args":{"key_type":"EM4100","key_data":"0102030405"}}"""
            CommandAction.IR_RECEIVE -> """{"action":"ir_receive","args":{}}"""
            CommandAction.BADUSB_VALIDATE -> """{"action":"badusb_validate","args":{"content":"REM reviewed script"}}"""
            CommandAction.BADUSB_DIFF -> """{"action":"badusb_diff","args":{"path":"/ext/badusb/demo.txt","content":"REM replacement"}}"""
            CommandAction.REQUEST_PHOTO ->
                """{"action":"request_photo","args":{"prompt":"Describe what you see"}}"""
        }
    }

    /**
     * Format command result for tool response
     */
    fun formatResult(result: CommandResult): String {
        return json.encodeToString(result)
    }

    /**
     * Simple message sending without tool calling.
     * Used for AI features like payload generation, analysis, etc.
     */
    suspend fun sendMessage(
        message: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        customSystemPrompt: String? = null
    ): Result<String> {
        val messages = buildList {
            addAll(conversationHistory)
            add(
                ChatMessage(
                    role = MessageRole.USER,
                    content = message
                )
            )
        }
        return sendMessagesWithoutTools(messages, customSystemPrompt)
    }

    /**
     * Message sending without tools, keeping multimodal/user history support.
     */
    suspend fun sendMessagesWithoutTools(
        messages: List<ChatMessage>,
        customSystemPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        // Check rate limit
        if (!rateLimiter.tryAcquire()) {
            return@withContext Result.failure(Exception("Rate limit exceeded"))
        }

        val selection = settingsStore.readLlmSelection()
        val apiKey = selection.apiKey
            ?: return@withContext Result.failure(Exception("${selection.provider.displayName} API key not configured"))
        if (!InputValidator.isValidApiKey(apiKey)) {
            return@withContext Result.failure(Exception("Invalid API key format"))
        }

        val compactMessages = trimConversationForRequest(messages, selection.provider == LlmProvider.ANTHROPIC)
        val requestMessages = buildList {
            add(OpenRouterMessage.text(
                role = "system",
                content = customSystemPrompt ?: baseSystemPrompt
            ))
            addAll(sanitizeAndBuildRequestMessages(compactMessages, selection.provider == LlmProvider.ANTHROPIC))
        }

        val request = limitedRequest(
            provider = selection.provider,
            model = selection.model,
            messages = requestMessages,
            tools = null,
            toolChoice = null,
            maxTokens = DEFAULT_RESPONSE_MAX_TOKENS
        )
        val httpRequest = postChat(selection.provider, apiKey, request)

        when (val result = executeWithRetry(httpRequest)) {
            is ChatCompletionResult.Success -> Result.success(result.content)
            is ChatCompletionResult.Error -> Result.failure(Exception(result.message))
        }
    }

    private fun isDnsResolutionFailure(error: Throwable): Boolean {
        if (error is UnknownHostException) return true
        val message = error.message.orEmpty()
        return message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address associated with hostname", ignoreCase = true)
    }

    private fun isToolUseUnsupportedError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("no endpoints found that support tool use") ||
                normalized.contains("tool use is not supported") ||
                normalized.contains("does not support tool use") ||
                normalized.contains("function calling is not supported") ||
                normalized.contains("tool calling is not supported") ||
                normalized.contains("endpoint does not support tools") ||
                normalized.contains("does not support function")
    }

    private fun isToolResultPairingError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("tool use block must have a corresponding tool use block in previous message") ||
                normalized.contains("tool_result blocks") ||
                normalized.contains("tool_use blocks") ||
                normalized.contains("tool use ids were found without tool_result blocks")
    }

    private fun isModelAvailabilityError(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("model not found") ||
                normalized.contains("provider not found") ||
                normalized.contains("not available for your account") ||
                normalized.contains("you are not allowed to use this model") ||
                normalized.contains("access denied") ||
                normalized.contains("model_not_found") ||
                normalized.contains("does not exist") ||
                normalized.contains("invalid model") ||
                normalized.contains("unknown model")
    }

    private fun isToolModelTemporarilyBlocked(model: String): Boolean {
        val blockedAt = toolUnsupportedModels[model] ?: return false
        val now = System.currentTimeMillis()
        if (now - blockedAt >= TOOL_UNSUPPORTED_CACHE_MS) {
            toolUnsupportedModels.remove(model)
            return false
        }
        return true
    }

    private fun markToolModelUnsupported(model: String) {
        toolUnsupportedModels[model] = System.currentTimeMillis()
    }

    private fun trimConversationForRequest(messages: List<ChatMessage>, preserveCompaction: Boolean): List<ChatMessage> {
        return ChatContextWindow.trim(messages, MAX_CONTEXT_MESSAGES, preserveCompaction)
    }

    companion object {
        private const val TAG = "OpenRouterClient"
        private const val EXPECTED_TOOL_ARGUMENTS_FORMAT =
            """{"action":"<action>","args":{...},"justification":"...","expected_effect":"..."}"""
        private const val MAX_ERROR_MODEL_LIST = 6
        private const val MAX_TOOL_CALLS_PER_RESPONSE = 1
        private const val TOOL_UNSUPPORTED_CACHE_MS = 5 * 60 * 1000L
        private const val MAX_CONTEXT_MESSAGES = 24
        private const val TOOL_CALL_RESPONSE_MAX_TOKENS = 1024
        private const val FORGE_RESPONSE_MAX_TOKENS = 6144
        private const val DEFAULT_RESPONSE_MAX_TOKENS = 720
        /** Max input size for JSON repair to prevent resource exhaustion. */
        private const val MAX_REPAIRABLE_JSON_LENGTH = 100_000

        private val THINKING_TAG_REGEX = Regex("<think>[\\s\\S]*?</think>\\s*")

        /**
         * Drop inline <think>...</think> reasoning that MiniMax (and some
         * OpenRouter reasoning models) emit in the content field.
         */
        fun stripThinkingTags(content: String): String {
            if (!content.contains("<think>")) return content
            return content.replace(THINKING_TAG_REGEX, "").trim()
        }
        /** Max unclosed brackets the repair function will close. */
        private const val MAX_REPAIR_BRACKET_DEPTH = 10

        private val SUPPORTED_ACTIONS = listOf(
            "list_directory",
            "read_file",
            "write_file",
            "create_directory",
            "delete",
            "move",
            "rename",
            "copy",
            "get_device_info",
            "get_storage_info",
            "search_faphub",
            "install_faphub_app",
            "push_artifact",
            "execute_cli",
            "forge_payload",
            "search_resources",
            "browse_repo",
            "download_resource",
            "github_search",
            "list_vault",
            "run_runbook",
            "launch_app",
            "subghz_transmit",
            "ir_transmit",
            "nfc_emulate",
            "rfid_emulate",
            "ibutton_emulate",
            "badusb_execute",
            "ble_spam",
            "led_control",
            "vibro_control",
            "request_photo", "input_send", "loader_list", "loader_close", "loader_signal", "load_skill",
            "ble_scan_targets", "ble_enumerate", "ble_read_char", "ble_write_char", "ble_subscribe"
        )

        // Shared vision system prompt used by both image preprocessing and agent-initiated photo capture.
        private const val VISION_SYSTEM_PROMPT =
            "You are a visual analysis assistant for a Flipper Zero companion app. " +
            "Describe what you see in the image in detail. Focus on: brand names, model numbers, " +
            "device types (TV, AC, car, remote control, gate, etc.), any visible text or labels, " +
            "and any details that would help identify the correct IR/RF/NFC protocol or signal. " +
            "Be specific and concise."

        private val EXECUTE_COMMAND_TOOL = OpenRouterTool(
            type = "function",
            function = OpenRouterToolFunction(
                name = "execute_command",
                description = "Execute a Flipper Zero action. The 'action' enum lists available handlers. Use 'args' for action-specific parameters. Typed hardware handlers gpio_read/gpio_set/gpio_mode/apps_list/get_system_info/nfc_detect/rfid_read/rfid_write/ir_receive require the explicitly selected USB CLI connection mode with a successful read-only handshake. RPC mode is the default; ask the user to disconnect, select USB CLI on Device, and reconnect if needed. Never switch a live session or retry a refused command automatically. Findings, audit queries and authoring validation do not require that session.",
                parameters = JsonObject(mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(mapOf(
                        "action" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "enum" to JsonArray(listOf(
                                JsonPrimitive("list_directory"),
                                JsonPrimitive("read_file"),
                                JsonPrimitive("write_file"),
                                JsonPrimitive("create_directory"),
                                JsonPrimitive("delete"),
                                JsonPrimitive("move"),
                                JsonPrimitive("rename"),
                                JsonPrimitive("copy"),
                                JsonPrimitive("get_device_info"),
                                JsonPrimitive("get_storage_info"),
                                JsonPrimitive("search_faphub"),
                                JsonPrimitive("install_faphub_app"),
                                JsonPrimitive("push_artifact"),
                                JsonPrimitive("execute_cli"),
                                JsonPrimitive("forge_payload"),
                                JsonPrimitive("search_resources"),
                                JsonPrimitive("list_vault"),
                                JsonPrimitive("run_runbook"),
                                JsonPrimitive("launch_app"),
                                JsonPrimitive("subghz_transmit"),
                                JsonPrimitive("ir_transmit"),
                                JsonPrimitive("nfc_emulate"),
                                JsonPrimitive("rfid_emulate"),
                                JsonPrimitive("ibutton_emulate"),
                                JsonPrimitive("badusb_execute"),
                                JsonPrimitive("ble_spam"),
                                JsonPrimitive("led_control"),
                                JsonPrimitive("vibro_control"),
                                JsonPrimitive("browse_repo"),
                                JsonPrimitive("download_resource"),
                                JsonPrimitive("github_search"),
                                JsonPrimitive("input_send"),
                                JsonPrimitive("loader_list"),
                                JsonPrimitive("loader_close"),
                                JsonPrimitive("loader_signal"),
                                JsonPrimitive("load_skill"),
                                JsonPrimitive("ble_scan_targets"),JsonPrimitive("ble_enumerate"),JsonPrimitive("ble_read_char"),JsonPrimitive("ble_write_char"),JsonPrimitive("ble_subscribe"),
                                JsonPrimitive("vuln_submit"),JsonPrimitive("vuln_validate"),JsonPrimitive("vuln_list"),JsonPrimitive("vuln_classify"),JsonPrimitive("audit_query"),JsonPrimitive("gpio_read"),JsonPrimitive("gpio_set"),JsonPrimitive("gpio_mode"),JsonPrimitive("apps_list"),JsonPrimitive("get_system_info"),JsonPrimitive("nfc_detect"),JsonPrimitive("rfid_read"),JsonPrimitive("rfid_write"),JsonPrimitive("ir_receive"),JsonPrimitive("badusb_validate"),JsonPrimitive("badusb_diff"),
                                JsonPrimitive("request_photo")
                            )),
                            "description" to JsonPrimitive("The action to perform on the Flipper Zero (request_photo requires smart glasses)")
                        )),
                        "args" to JsonObject(mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(mapOf(
                                "command" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive(
                                        "Primary text argument. For execute_cli: raw CLI command; for search_faphub/search_resources: query; for install_faphub_app: app id; for run_runbook: runbook id; for browse_repo: repo id (if repo_id not set); for load_skill: bundled read-only methodology ID; for audit_query: optional exact audit action type filter."
                                    )
                                )),
                                "key" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("GUI key: up/down/left/right/ok/back"))),
                                "press_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("press/release/short/long/repeat; defaults short"))),
                                "signal_id" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Nonnegative app signal id; USB CLI only"))),
                                "path" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("File or directory path on Flipper")
                                )),
                                "destination_path" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Destination path for move/copy")
                                )),
                                "content" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Content to write to file, or BLE characteristic bytes/text for ble_write_char (encoding controlled by hex). Catalog installation does not accept a direct URL override.")
                                )),
                                "download_url" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Direct source URL for download_resource (from browse_repo results). Catalog installs use an exact application ID via command and verified firmware-compatible builds, not URL overrides.")
                                )),
                                "new_name" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("New name for rename operation")
                                )),
                                "recursive" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("boolean"),
                                    "description" to JsonPrimitive("Whether to delete recursively")
                                )),
                                "artifact_type" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Type of artifact: fap, config, data")
                                )),
                                "artifact_data" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Base64-encoded artifact data")
                                )),
                                "prompt" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Natural language prompt for forge_payload. Describe what you want to create.")
                                )),
                                "payload_type" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "enum" to JsonArray(listOf("sub_ghz", "infrared", "nfc", "rfid", "bad_usb", "ibutton").map(::JsonPrimitive)),
                                    "description" to JsonPrimitive("Canonical forge format: sub_ghz/infrared/nfc/rfid/bad_usb/ibutton. Historical subghz/ir/badusb aliases remain accepted.")
                                )),
                                "resource_type" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Resource type filter for search_resources: IR_REMOTE, SUB_GHZ, BAD_USB, NFC_FILES, EVIL_PORTAL, MUSIC, ANIMATIONS, GPIO_TOOLS")
                                )),
                                "runbook_id" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Runbook identifier for run_runbook: link_health, input_smoke_test, recover_scan")
                                )),
                                "filter" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Type filter for list_vault: SUB_GHZ, INFRARED, NFC, RFID, BAD_USB, IBUTTON")
                                )),
                                "app_name" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("App name for launch_app (e.g. 'Sub-GHz', 'Infrared', 'NFC', 'Snake')")
                                )),
                                "app_args" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Arguments for launch_app or ble_spam (e.g. 'stop')")
                                )),
                                "frequency" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("Reserved metadata; file-based SubGHz transmission uses the frequency stored in the file, not this field")
                                )),
                                "protocol" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Reserved metadata; file-based transmit/emulation uses the protocol stored in the file, not this field")
                                )),
                                "address" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Phone BLE target MAC address, required for enumerate/read/write/subscribe. File-based radio transmit/emulation uses the address stored in the file, not this field")
                                )),
                                "signal_name" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Signal name within an IR file for ir_transmit (e.g. 'Power', 'Vol_up')")
                                )),
                                "enabled" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("boolean"),
                                    "description" to JsonPrimitive("Enable/disable for vibro_control")
                                )),
                                "red" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("Red LED value 0-255 for led_control")
                                )),
                                "green" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("Green LED value 0-255 for led_control")
                                )),
                                "blue" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("integer"),
                                    "description" to JsonPrimitive("Blue LED value 0-255 for led_control")
                                )),
                                "repo_id" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Repository ID for browse_repo (e.g. 'irdb', 'subghz_bruteforce'). Use search_resources first to find IDs.")
                                )),
                                "sub_path" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Sub-path within repo for browse_repo (e.g. 'TVs/Samsung', 'ACs/LG')")
                                )),
                                "search_scope" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Scope for github_search: 'repositories' (find repos) or 'code' (find files). Default: 'code'. Use 'code' with file extensions like 'extension:ir' or 'extension:sub'.")
                                )),
                                "duration" to JsonObject(mapOf("type" to JsonPrimitive("number"), "description" to JsonPrimitive("Phone BLE actions: seconds, default 5, range 0.5..60. Scan/listen duration for ble_scan_targets/ble_subscribe; connection and operation timeout for enumerate/read/write. Subscription connection timeout is 10 seconds; restoration adds at most 3 seconds."))),
                                "uuid" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Full characteristic UUID required for ble_read_char, ble_write_char and ble_subscribe; must identify exactly one characteristic instance."))),
                                "hex" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("ble_write_char content encoding: true (default) means hexadecimal byte pairs; false means UTF-8 text. Maximum 512 bytes."))),
                                "with_response" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("ble_write_char only: true (default) waits for a GATT write response. False sends without a peripheral response and rejects data exceeding negotiated MTU minus 3; no fragmentation."))),
                                "target" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required nonblank target for vuln_submit (maximum 512 characters); optional Unicode case-insensitive target substring for current-session vuln_list."))),
                                "vuln_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required vulnerability type for submit/classify (maximum 128 characters). Classification uses exact normalized rule keys; with vuln_id it must match the stored finding type."))),
                                "description" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required nonblank finding description for vuln_submit, maximum 8192 characters."))),
                                "evidence" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required nonblank observed evidence for vuln_submit, maximum 65536 characters. Submission does not establish reproduction."))),
                                "severity" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required for vuln_submit or optional exact filter for vuln_list: critical/high/medium/low."))),
                                "complexity" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Required for vuln_submit: integer 1..10."))),
                                "vuln_id" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required current-session finding ID for vuln_validate; optional for vuln_classify. ID-targeted classification persists severity and requires explicit approval."))),
                                "reproduced" to JsonObject(mapOf("type" to JsonPrimitive("boolean"), "description" to JsonPrimitive("Required for vuln_validate: true confirms; false marks inconclusive and preserves earlier history."))),
                                "notes" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Optional validation notes, maximum 8192 characters. History is capped at 100 entries and 64 KiB of JSON; full histories reject mutation."))),
                                "status" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Optional exact current-session vuln_list filter: submitted/confirmed/inconclusive/rejected/false_positive."))),
                                "limit" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("audit_query maximum returned entries: 1..100, default 20. Always scoped to the calling session."))),
                                "risk_level" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Optional audit_query risk filter: LOW/MEDIUM/HIGH/BLOCKED, case-insensitive."))),
                                "pin" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required for GPIO actions: MCU pin name matching P[A-H][0-9]{1,2}, for example PA7. Requires explicitly selected and confirmed USB CLI; verify physical wiring."))),
                                "value" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Required gpio_set value: 0 or 1. Typed hardware action uses one USB CLI send after normal risk approval."))),
                                "mode" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Required gpio_mode value: 0 or 1 according to firmware CLI. Confirm firmware semantics before changing hardware mode."))),
                                "key_type" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required rfid_write key type: 1..32 letters, digits, underscore or hyphen. Firmware must support the requested type."))),
                                "key_data" to JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("Required rfid_write key bytes: 1..32 hexadecimal byte pairs, no separators. Explicit confirmed USB CLI only; no retries."))),
                                "photo_prompt" to JsonObject(mapOf(
                                    "type" to JsonPrimitive("string"),
                                    "description" to JsonPrimitive("Vision analysis prompt for request_photo. Describe what you want to identify (e.g. 'Identify the TV brand and model', 'What device is this?'). Falls back to 'prompt' if not set.")
                                ))
                            ))
                        )),
                        "justification" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Optional. Only include for MEDIUM/HIGH risk actions.")
                        )),
                        "expected_effect" to JsonObject(mapOf(
                            "type" to JsonPrimitive("string"),
                            "description" to JsonPrimitive("Optional. Only include for MEDIUM/HIGH risk actions.")
                        ))
                    )),
                    "required" to JsonArray(listOf(
                        JsonPrimitive("action"),
                        JsonPrimitive("args")
                    ))
                ))
            )
        )

    }

    /**
     * Build a copy of the tool schema WITHOUT glasses-only actions
     * (request_photo). This prevents the model from calling request_photo
     * when the user has smart glasses disabled, which would fail and
     * confuse normal photo workflows.
     */
    private fun buildToolWithoutGlasses(): OpenRouterTool {
        val fullParams = EXECUTE_COMMAND_TOOL.function.parameters
        val properties = fullParams["properties"] as? JsonObject ?: return EXECUTE_COMMAND_TOOL
        val actionProp = properties["action"] as? JsonObject ?: return EXECUTE_COMMAND_TOOL
        val actionEnum = actionProp["enum"] as? JsonArray ?: return EXECUTE_COMMAND_TOOL

        // 1. Strip request_photo from the action enum
        val filteredEnum = JsonArray(actionEnum.filter {
            it is JsonPrimitive && it.contentOrNull != "request_photo"
        })
        val filteredActionProp = JsonObject(actionProp.toMutableMap().apply {
            put("enum", filteredEnum)
            put("description", JsonPrimitive("The action to perform on the Flipper Zero"))
        })

        // 2. Strip photo_prompt from args.properties (it's nested inside the args object)
        val argsProp = properties["args"] as? JsonObject
        val filteredArgsProp = if (argsProp != null) {
            val argsInnerProps = argsProp["properties"] as? JsonObject
            if (argsInnerProps != null) {
                val filteredArgsInner = JsonObject(argsInnerProps.toMutableMap().apply {
                    remove("photo_prompt")
                })
                JsonObject(argsProp.toMutableMap().apply {
                    put("properties", filteredArgsInner)
                })
            } else argsProp
        } else null

        // 3. Reassemble top-level properties
        val filteredProperties = JsonObject(properties.toMutableMap().apply {
            put("action", filteredActionProp)
            if (filteredArgsProp != null) put("args", filteredArgsProp)
        })
        val filteredParams = JsonObject(fullParams.toMutableMap().apply {
            put("properties", filteredProperties)
        })

        return OpenRouterTool(
            type = "function",
            function = OpenRouterToolFunction(
                name = EXECUTE_COMMAND_TOOL.function.name,
                description = EXECUTE_COMMAND_TOOL.function.description,
                parameters = filteredParams
            )
        )
    }
}

sealed class ChatCompletionResult {
    data class Success(
        val content: String,
        val toolCalls: List<ToolCall>?,
        val model: String,
        val tokensUsed: Int?,
        val providerContentJson: String? = null
    ) : ChatCompletionResult()

    data class Error(val message: String, val retryable: Boolean = false, val retryAfterMillis: Long? = null) : ChatCompletionResult()
}

// OpenRouter API models

@Serializable
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    val tools: List<OpenRouterTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,
    val stream: Boolean = false,
    @kotlinx.serialization.Transient val thinkingBudget: Int = 0,
    @kotlinx.serialization.Transient val effort: String? = null
)

@Serializable
data class OpenRouterMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenRouterToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @kotlinx.serialization.Transient val providerContentJson: String? = null
) {
    companion object {
        /**
         * Create a message with simple text content
         */
        fun text(role: String, content: String): OpenRouterMessage {
            return OpenRouterMessage(role = role, content = JsonPrimitive(content))
        }

        /**
         * Create a multimodal message with text and images
         */
        fun multimodal(
            role: String,
            text: String,
            images: List<ImageContent>
        ): OpenRouterMessage {
            val contentParts = buildList {
                // Add text part first
                if (text.isNotEmpty()) {
                    add(JsonObject(mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(text)
                    )))
                }
                // Add image parts
                images.forEach { image ->
                    add(JsonObject(mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(mapOf(
                            "url" to JsonPrimitive("data:${image.mimeType};base64,${image.base64Data}"),
                            "detail" to JsonPrimitive(image.detail)
                        ))
                    )))
                }
            }
            return OpenRouterMessage(role = role, content = JsonArray(contentParts))
        }
    }
}

/**
 * Image content for multimodal messages
 */
data class ImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    val detail: String = "auto" // "low", "high", or "auto"
)

@Serializable
data class OpenRouterTool(
    val type: String,
    val function: OpenRouterToolFunction
)

@Serializable
data class OpenRouterToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class OpenRouterToolCall(
    val id: String = "",
    val type: String = "function",
    val function: OpenRouterFunction = OpenRouterFunction()
)

/**
 * Custom serializer for [OpenRouterFunction.arguments].
 * Some models return `arguments` as a JSON string (correct per OpenAI spec),
 * while others return it as a raw JSON object. This serializer accepts both
 * forms and always produces a String for downstream parsing.
 */
private object ArgumentsAsStringSerializer : kotlinx.serialization.KSerializer<String> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "ArgumentsAsString",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String {
        val input = decoder as kotlinx.serialization.json.JsonDecoder
        val element = input.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> element.contentOrNull ?: ""
            else -> element.toString()
        }
    }

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@Serializable
data class OpenRouterFunction(
    val name: String = "",
    @Serializable(with = ArgumentsAsStringSerializer::class)
    val arguments: String = ""
)

@Serializable
data class OpenRouterResponse(
    val id: String = "",
    val model: String = "",
    val choices: List<OpenRouterChoice> = emptyList(),
    val usage: OpenRouterUsage? = null
)

@Serializable
data class OpenRouterChoice(
    val message: OpenRouterResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String?
)

@Serializable
data class OpenRouterResponseMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenRouterToolCall>? = null
) {
    /**
     * Extract the text content regardless of whether it arrived as a plain
     * string or as an array of content parts (e.g. Gemini via OpenRouter).
     */
    val textContent: String?
        get() = when {
            content == null -> null
            content is JsonPrimitive -> content.jsonPrimitive.contentOrNull
            content is JsonArray -> {
                content.jsonArray.mapNotNull { part ->
                    part.jsonObject.takeIf {
                        it["type"]?.jsonPrimitive?.contentOrNull == "text"
                    }?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("\n").ifBlank { null }
            }
            else -> null
        }
}

@Serializable
data class OpenRouterUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int
)
