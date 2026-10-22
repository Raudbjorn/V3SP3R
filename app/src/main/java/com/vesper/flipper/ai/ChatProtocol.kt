package com.vesper.flipper.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Wire protocol boundary shared by compatible and native providers. */
interface ChatProtocol {
    fun buildRequest(provider: LlmProvider, apiKey: String, request: OpenRouterRequest): Request
    fun normalizeResponse(body: String): String = body
}

object OpenAiChatProtocol : ChatProtocol {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    override fun buildRequest(provider: LlmProvider, apiKey: String, request: OpenRouterRequest): Request {
        require(provider != LlmProvider.ANTHROPIC) { "Anthropic requires the native Messages protocol" }
        val builder = Request.Builder().url(provider.chatCompletionsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
        if (provider.sendsAttributionHeaders) {
            builder.header("HTTP-Referer", "https://vesper.flipper.app")
                .header("X-Title", "Vesper Flipper Control")
        }
        return builder.post(json.encodeToString(request).toRequestBody("application/json".toMediaType())).build()
    }
}
