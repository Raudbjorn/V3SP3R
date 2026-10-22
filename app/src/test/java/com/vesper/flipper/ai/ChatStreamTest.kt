package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.MessageMetadata
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChatStreamTest {
    private fun sse(vararg payloads: String) = Buffer().writeUtf8(payloads.joinToString("") { "data: $it\n\n" })

    @Test fun `native streaming joins text fragmented tools and opaque blocks`() = runBlocking {
        val events = mutableListOf<ChatStreamEvent>()
        val raw = ChatStreamReader(true).read(sse(
            """{"type":"message_start","message":{"id":"m","model":"claude","usage":{"input_tokens":12}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"t","name":"execute_command","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"action\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"list_directory\"}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"compaction","content":"summary"}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}""",
            """{"type":"message_stop"}"""
        )) { events.add(it) }
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals("Hello", (events.first() as ChatStreamEvent.TextDelta).text)
        assertEquals(2, events.filterIsInstance<ChatStreamEvent.ToolDelta>().size)
        assertEquals("list_directory", root["content"]!!.jsonArray[1].jsonObject["input"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("summary", root["content"]!!.jsonArray[2].jsonObject["content"]!!.jsonPrimitive.content)
    }
    @Test fun `truncated and error streams never complete`() = runBlocking {
        for (payload in listOf("""{"choices":[{"delta":{"content":"partial"}}]}""", """{"error":{"message":"failed"}}""")) {
            try { ChatStreamReader(false).read(sse(payload)) {}; fail("Expected stream failure") }
            catch (_: IOException) { }
        }
    }
    @Test fun `compatible stream has completed text`() = runBlocking {
        val raw = ChatStreamReader(false).read(sse("""{"model":"gpt","choices":[{"delta":{"content":"Hello"}}]}""", "[DONE]")) {}
        val message = Json.parseToJsonElement(raw).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("Hello", message["content"]!!.jsonPrimitive.content)
    }
    @Test fun `cancellation closes stalled HTTP call promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/stream")).build())
            val task = launch { call.withCancellation { it.body?.string() } }
            withContext(Dispatchers.IO) { server.takeRequest() }
            task.cancel()
            withTimeout(2000) { task.join() }
            assertTrue(call.isCanceled())
        }
    }
    @Test fun `cache markers and opaque content survive persistence and next request`() {
        val blocks = """[{"type":"thinking","thinking":"private","signature":"sig"},{"type":"compaction","content":"summary"},{"type":"text","text":"answer"}]"""
        val metadata = Json.decodeFromString<MessageMetadata>(Json.encodeToString(MessageMetadata(providerContentJson=blocks)))
        val request = OpenRouterRequest("claude-opus-4-5", listOf(OpenRouterMessage.text("system","system"),
            OpenRouterMessage.text("user","hi"), OpenRouterMessage.text("assistant","answer").copy(providerContentJson=metadata.providerContentJson)), thinkingBudget=2048, effort="medium")
        val encoded = AnthropicChatProtocol.encodeRequest(request)
        assertEquals(Json.parseToJsonElement(blocks), encoded["messages"]!!.jsonArray[1].jsonObject["content"])
        assertEquals("ephemeral", encoded["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2048, encoded["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertEquals("medium", encoded["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }
}
