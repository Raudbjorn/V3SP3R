package com.vesper.flipper.ai

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

/** MiniMax provider behaviour adapted from upstream PR #74 (elder-plinius/V3SP3R). */
class MiniMaxProviderTest {
    private val client = OpenRouterClient(mock())
    @Test fun `only MiniMax asks for reasoning outside content`() {
        val messages = listOf(OpenRouterMessage.text("user", "hello"))
        for (provider in LlmProvider.entries) {
            val request = client.limitedRequest(provider, provider.defaultModel, messages, tools = null, toolChoice = null, maxTokens = 64)
            assertEquals(provider.id, if (provider == LlmProvider.MINIMAX) true else null, request.reasoningSplit)
        }
    }
    @Test fun `split reasoning never reaches content or the stream`() = kotlinx.coroutines.runBlocking {
        val sse = listOf(
            """{"id":"x","model":"MiniMax-M2.7","choices":[{"delta":{"reasoning_content":"plan the listing","reasoning_details":[{"type":"reasoning.text","text":"plan the listing"}]}}]}""",
            """{"choices":[{"delta":{"content":"Here are your SubGHz captures."},"finish_reason":"stop"}]}"""
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        val deltas = mutableListOf<String>()
        val raw = ChatStreamReader(false).read(okio.Buffer().writeUtf8(sse)) { if (it is ChatStreamEvent.TextDelta) deltas += it.text }
        assertEquals(listOf("Here are your SubGHz captures."), deltas)
        assertEquals("Here are your SubGHz captures.", (client.parseResponse(raw) as ChatCompletionResult.Success).content)
    }
    @Test fun `literal think markup in an answer is preserved`() {
        val result = client.parseResponse("""{"id":"x","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Wrap it as <think>notes</think> in the template."}}]}""")
        assertEquals("Wrap it as <think>notes</think> in the template.", (result as ChatCompletionResult.Success).content)
    }

    @Test fun `catalog resolves and stays within MiniMax`() {
        val provider = LlmProvider.fromId("minimax")
        assertEquals(LlmProvider.MINIMAX, provider)
        assertEquals(
            listOf("MiniMax-M2.7", "MiniMax-M2.7-highspeed", "MiniMax-M2.5", "MiniMax-M2.5-highspeed"),
            provider.models.map { it.id }
        )
        assertTrue(provider.models.any { it.id == provider.defaultModel })
        assertTrue(provider.toolCandidates(provider.defaultModel).all { it.startsWith("MiniMax-") })
        assertTrue(provider.acceptsCatalogModel("MiniMax-M2.7"))
        assertFalse(provider.acceptsCatalogModel("speech-02-hd"))
        assertFalse(provider.acceptsCatalogModel("anthropic/claude-sonnet-4.5"))
    }
    @Test fun `text-only provider has no vision or speech`() {
        assertTrue(LlmProvider.MINIMAX.visionModels.isEmpty())
        assertEquals(SpeechBackend.NONE, LlmProvider.MINIMAX.speechBackend)
    }

    @Test fun `requests target the direct endpoint without OpenRouter attribution`() {
        MockWebServer().use { server ->
            server.start()
            val provider = LlmProvider.MINIMAX
            val original = OpenAiChatProtocol.buildRequest(provider, "mm-key", OpenRouterRequest(model = provider.defaultModel,
                messages = listOf(OpenRouterMessage.text("user", "hello"))))
            assertEquals("https://api.minimax.io/v1/chat/completions", original.url.toString())
            server.enqueue(MockResponse().setBody("{}"))
            OkHttpClient().newCall(original.newBuilder().url(server.url("/chat")).build()).execute().close()
            val recorded = server.takeRequest()
            assertEquals("Bearer mm-key", recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader("HTTP-Referer"))
            assertNull(recorded.getHeader("X-Title"))
        }
    }
}
