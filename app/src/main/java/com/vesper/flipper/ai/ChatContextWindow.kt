package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.ChatMessage
import com.vesper.flipper.domain.model.MessageRole
import kotlinx.serialization.json.*

internal object ChatContextWindow {
    /** Start on a user turn, preserving paired tools and the latest server compaction anchor. */
    fun trim(messages: List<ChatMessage>, limit: Int): List<ChatMessage> {
        if (messages.size <= limit) return messages
        var start = messages.size - limit
        val anchor = messages.indexOfLast { message ->
            message.metadata?.providerContentJson?.let { raw -> runCatching {
                Json.parseToJsonElement(raw).jsonArray.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content in setOf("compaction", "compaction_summary")
                }
            }.getOrDefault(false) } ?: false
        }
        if (anchor >= 0) start = minOf(start, anchor)
        while (start > 0 && messages[start].role != MessageRole.USER) start--
        return messages.drop(start)
    }
}
