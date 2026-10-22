package com.vesper.flipper.ai

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderProtocolTest {
    @Test fun `compatible providers send isolated credentials attribution and token fields`() {
        MockWebServer().use { server ->
            server.start()
            for (provider in LlmProvider.entries.filter { it != LlmProvider.ANTHROPIC }) {
                val limits = provider.completionLimits(2048)
                val request = OpenRouterRequest(model = provider.defaultModel,
                    messages = listOf(OpenRouterMessage.text("user", "hello")),
                    tools = null, toolChoice = null,
                    maxTokens = limits.maxTokens, maxCompletionTokens = limits.maxCompletionTokens)
                val original = OpenAiChatProtocol.buildRequest(provider, "key-${provider.id}", request)
                assertEquals(provider.apiHost, original.url.host)
                server.enqueue(MockResponse().setBody("{}"))
                OkHttpClient().newCall(original.newBuilder().url(server.url("/chat")).build()).execute().close()
                val recorded = server.takeRequest()
                assertEquals("Bearer key-${provider.id}", recorded.getHeader("Authorization"))
                assertEquals(provider.sendsAttributionHeaders, recorded.getHeader("HTTP-Referer") != null)
                val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                assertEquals(2048, body[if (provider.usesMaxCompletionTokens) "max_completion_tokens" else "max_tokens"]!!.jsonPrimitive.int)
                assertFalse(body.containsKey(if (provider.usesMaxCompletionTokens) "max_tokens" else "max_completion_tokens"))
            }
        }
    }
    @Test fun `credentials and models use separate slots and speech is capability based`() {
        assertEquals(LlmProvider.entries.size, LlmProvider.entries.map { it.apiKeyPreference }.distinct().size)
        assertEquals(LlmProvider.entries.size, LlmProvider.entries.map { it.modelPreference }.distinct().size)
        assertEquals(SpeechBackend.NONE, LlmProvider.ANTHROPIC.speechBackend)
        for (provider in LlmProvider.entries.filter { it != LlmProvider.OPENROUTER }) {
            assertFalse(provider.toolCandidates(provider.defaultModel).any { it.contains("/") })
        }
    }
    @Test(expected = IllegalArgumentException::class) fun `native provider cannot use compatible wire protocol`() {
        OpenAiChatProtocol.buildRequest(LlmProvider.ANTHROPIC, "secret", OpenRouterRequest(model="claude",messages=emptyList()))
    }
}
