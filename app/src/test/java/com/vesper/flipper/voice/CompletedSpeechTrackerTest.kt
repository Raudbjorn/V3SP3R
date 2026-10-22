package com.vesper.flipper.voice

import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class CompletedSpeechTrackerTest {
    @Test fun `completion of a streaming placeholder speaks once despite unchanged count`() {
        val tracker=CompletedSpeechTracker()
        val message=ChatMessage(role=MessageRole.ASSISTANT,content="hello",status=MessageStatus.STREAMING)
        val loading=ConversationState(messages=listOf(message),isLoading=true)
        assertNull(tracker.completed(loading))
        val done=loading.copy(messages=listOf(message.copy(status=MessageStatus.COMPLETE)),isLoading=false)
        assertEquals(message.id,tracker.completed(done)?.id);assertNull(tracker.completed(done))
    }
    @Test fun `errors tool calls and blank responses do not speak`() {
        for(message in listOf(ChatMessage(role=MessageRole.ASSISTANT,content="error",status=MessageStatus.ERROR),
            ChatMessage(role=MessageRole.ASSISTANT,content="tool",toolCalls=listOf(ToolCall("t","execute_command","{}"))),
            ChatMessage(role=MessageRole.ASSISTANT,content=""))) assertNull(CompletedSpeechTracker().completed(ConversationState(messages=listOf(message))))
    }
}
