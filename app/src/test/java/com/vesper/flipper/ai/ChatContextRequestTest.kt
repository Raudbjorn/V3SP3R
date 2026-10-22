package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class ChatContextRequestTest {
    @Test fun `bounded opaque anchor reaches native request but is omitted from compatible request`() {
        val user = ChatMessage(role=MessageRole.USER,content="original request")
        val raw = """[{"type":"compaction","content":"opaque server summary"}]"""
        val anchor = ChatMessage(role=MessageRole.ASSISTANT,content="summary",metadata=MessageMetadata(providerContentJson=raw))
        val history = listOf(user,anchor) + (1..20).map { ChatMessage(role=MessageRole.USER,content="turn $it") }
        val trimmed = ChatContextWindow.trim(history,4,preserveCompaction=true)
        assertEquals("",trimmed[1].content)
        val client = OpenRouterClient(mock())
        val nativeMessages = client.sanitizeAndBuildRequestMessages(trimmed,preserveNativeContent=true)
        assertEquals(raw,nativeMessages.single { it.role=="assistant" }.providerContentJson)
        val encoded = AnthropicChatProtocol.encodeRequest(OpenRouterRequest("claude-sonnet-4-5",nativeMessages))
        val nativeAnchor = encoded["messages"]!!.jsonArray.single { it.jsonObject["role"]!!.jsonPrimitive.content=="assistant" }
        assertEquals(Json.parseToJsonElement(raw),nativeAnchor.jsonObject["content"])
        val compatibleMessages = client.sanitizeAndBuildRequestMessages(trimmed)
        assertTrue(compatibleMessages.none { it.role=="assistant" })
        assertTrue(compatibleMessages.all { it.providerContentJson==null })
    }
}
