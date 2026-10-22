package com.vesper.flipper.voice

import com.vesper.flipper.domain.model.*

/** Completion can replace a streaming placeholder without changing the message count. */
internal class CompletedSpeechTracker {
    private var sessionId: String? = null
    private val seen = linkedSetOf<String>()
    fun completed(state: ConversationState): ChatMessage? {
        if (sessionId != state.sessionId) { seen.clear(); sessionId = state.sessionId }
        if (state.isLoading) return null
        val message = state.messages.lastOrNull() ?: return null
        if (message.role != MessageRole.ASSISTANT || message.status != MessageStatus.COMPLETE ||
            message.content.isBlank() || !message.toolCalls.isNullOrEmpty() || !seen.add(message.id)) return null
        while (seen.size > 512) seen.remove(seen.first())
        return message
    }
}
