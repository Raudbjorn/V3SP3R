package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatContextWindowTest {
    @Test fun `window never begins with an orphaned tool result`() {
        val history = listOf(ChatMessage(role=MessageRole.USER,content="request"),
            ChatMessage(role=MessageRole.ASSISTANT,content="",toolCalls=listOf(ToolCall("t","execute_command","{}"))),
            ChatMessage(role=MessageRole.TOOL,content="",toolResults=listOf(ToolResult("t","ok"))),
            ChatMessage(role=MessageRole.ASSISTANT,content="done"))
        assertEquals(history, ChatContextWindow.trim(history,2))
    }
    @Test fun `compaction anchor survives a later context window`() {
        val first = ChatMessage(role=MessageRole.USER,content="first")
        val anchor = ChatMessage(role=MessageRole.ASSISTANT,content="summary",metadata=MessageMetadata(providerContentJson="""[{"type":"compaction","content":"opaque"}]"""))
        val history = listOf(first,anchor) + (1..20).map { ChatMessage(role=MessageRole.USER,content="$it") }
        assertEquals(history, ChatContextWindow.trim(history,4))
    }
}
