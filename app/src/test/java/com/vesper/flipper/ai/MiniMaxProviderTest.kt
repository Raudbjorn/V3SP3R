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
    private fun strip(content: String) = OpenRouterClient.stripThinkingTags(content)

    @Test fun `strip thinking tags - single block`() {
        assertEquals("Here is my response.", strip("<think>Let me analyze this request...</think>Here is my response."))
    }
    @Test fun `strip thinking tags - multiline block`() {
        val input = "<think>\nStep 1: Check the file system\nStep 2: Look for SubGHz captures\n</think>\nI found 3 SubGHz captures on your Flipper."
        assertEquals("I found 3 SubGHz captures on your Flipper.", strip(input))
    }
    @Test fun `strip thinking tags - content without tags is untouched`() {
        assertEquals("  no tags, whitespace kept  ", strip("  no tags, whitespace kept  "))
        assertEquals("", strip(""))
    }
    @Test fun `strip thinking tags - multiple blocks and literal angle brackets`() {
        assertEquals("Response part 1. Response part 2.", strip("<think>First</think>Response part 1. <think>Second</think>Response part 2."))
        assertEquals("The file format is <IR signals file>.", strip("<think>reasoning</think>The file format is <IR signals file>."))
    }

    @Test fun `parsed responses drop inline reasoning`() {
        val result = client.parseResponse("""{"id":"x","model":"MiniMax-M2.7","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"<think>The user wants to list files</think>Here are your SubGHz captures."}}]}""")
        assertEquals("Here are your SubGHz captures.", (result as ChatCompletionResult.Success).content)
    }
    @Test fun `reasoning-only response is a visible error`() {
        val result = client.parseResponse("""{"id":"x","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"<think>All reasoning, no output</think>"}}]}""")
        assertTrue(result is ChatCompletionResult.Error)
    }
    @Test fun `reasoning plus tool call keeps the tool call`() {
        val result = client.parseResponse("""{"id":"x","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"<think>list it</think>","tool_calls":[{"id":"call_001","type":"function","function":{"name":"execute_command","arguments":"{\"action\":\"list_directory\",\"args\":{\"path\":\"/ext/subghz\"}}"}}]}}]}""")
        val success = result as ChatCompletionResult.Success
        assertEquals("", success.content)
        assertEquals("call_001", success.toolCalls!!.single().id)
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
