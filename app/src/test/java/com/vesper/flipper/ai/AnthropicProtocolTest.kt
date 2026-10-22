package com.vesper.flipper.ai

import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AnthropicProtocolTest {
    @Test fun `native request uses Messages auth schemas and paired tool results`() {
        val messages = listOf(OpenRouterMessage.text("system", "system instruction"),
            OpenRouterMessage.text("user", "list files"),
            OpenRouterMessage("assistant", toolCalls=listOf(OpenRouterToolCall("id1", "function", OpenRouterFunction("execute_command", "{\"action\":\"list_directory\"}")))),
            OpenRouterMessage("tool", JsonPrimitive("files"), toolCallId="id1"))
        val schema = buildJsonObject { put("type", "object") }
        val request = OpenRouterRequest("claude-sonnet-4-5", messages,
            listOf(OpenRouterTool("function", OpenRouterToolFunction("execute_command", "do work", schema))), "auto", 4096)
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}"))
            val original = AnthropicChatProtocol.buildRequest(LlmProvider.ANTHROPIC, "test-key", request)
            assertEquals("/v1/messages", original.url.encodedPath)
            OkHttpClient().newCall(original.newBuilder().url(server.url("/v1/messages")).build()).execute().close()
            val sent = server.takeRequest()
            assertEquals("test-key", sent.getHeader("x-api-key")); assertNull(sent.getHeader("Authorization"))
            assertEquals("2023-06-01", sent.getHeader("anthropic-version"))
            val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("system instruction", body["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
            val turns = body["messages"]!!.jsonArray
            assertEquals("tool_use", turns[1].jsonObject["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("id1", turns[2].jsonObject["content"]!!.jsonArray[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
            assertEquals(schema, body["tools"]!!.jsonArray[0].jsonObject["input_schema"])
        }
    }
    @Test fun `native response normalizes text tool calls and usage`() {
        val result = Json.parseToJsonElement(AnthropicChatProtocol.normalizeResponse("""{"id":"m1","model":"claude","stop_reason":"tool_use","content":[{"type":"text","text":"checking"},{"type":"tool_use","id":"t1","name":"execute_command","input":{"action":"list_directory"}}],"usage":{"input_tokens":12,"output_tokens":3}}""")).jsonObject
        val message = result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("checking", message["content"]!!.jsonPrimitive.content)
        assertEquals("t1", message["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(15, result["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
    }
    @Test(expected=IllegalArgumentException::class) fun `empty native response is rejected`() {
        AnthropicChatProtocol.normalizeResponse("""{"content":[]}""")
    }
    @Test fun `image attachments use native base64 source`() {
        val request = OpenRouterRequest("claude",listOf(OpenRouterMessage.multimodal("user","look",listOf(ImageContent("YWJj","image/png")))))
        val image = AnthropicChatProtocol.encodeRequest(request)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject
        assertEquals("image/png", image["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertEquals("YWJj", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }
}
