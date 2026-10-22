package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ChatContextWindowTest {
    @Test fun `long tool turns remain bounded without orphan results`() {
        val user = ChatMessage(role=MessageRole.USER,content="request")
        val rounds = (1..30).flatMap { n -> listOf(
            ChatMessage(role=MessageRole.ASSISTANT,content="",toolCalls=listOf(ToolCall("t$n","execute_command","{}"))),
            ChatMessage(role=MessageRole.TOOL,content="",toolResults=listOf(ToolResult("t$n","ok")))) }
        val kept = ChatContextWindow.trim(listOf(user) + rounds,5)
        assertEquals(5,kept.size); assertEquals(user,kept.first())
        assertEquals("t29",kept[1].toolCalls!!.single().id)
        assertEquals("t30",kept.last().toolResults!!.single().toolCallId)
        assertEquals(listOf(user),ChatContextWindow.trim(listOf(user)+rounds,2))
    }
    @Test fun `old native compaction anchor survives without retaining intervening history`() {
        val first = ChatMessage(role=MessageRole.USER,content="first")
        val anchor = ChatMessage(role=MessageRole.ASSISTANT,content="summary",metadata=MessageMetadata(providerContentJson="""[{"type":"thinking","thinking":"old"},{"type":"compaction","content":"opaque"}]"""))
        val history = listOf(first,anchor) + (1..20).map { ChatMessage(role=MessageRole.USER,content="$it") }
        val kept = ChatContextWindow.trim(history,4,preserveCompaction=true)
        assertEquals(4,kept.size); assertEquals(first,kept[0]); assertEquals(anchor.id,kept[1].id)
        assertEquals("""[{"type":"compaction","content":"opaque"}]""",kept[1].metadata!!.providerContentJson)
        assertEquals(listOf("19","20"),kept.takeLast(2).map { it.content })
        assertFalse(ChatContextWindow.trim(history,4).any { it.id == anchor.id })
    }
    @Test fun `incomplete historical tool groups cannot be replayed alone`() {
        val user=ChatMessage(role=MessageRole.USER,content="request")
        val call=ChatMessage(role=MessageRole.ASSISTANT,content="",toolCalls=listOf(ToolCall("missing","execute_command","{}")))
        assertEquals(listOf(user),ChatContextWindow.trim(listOf(user,call),1))
    }
}
