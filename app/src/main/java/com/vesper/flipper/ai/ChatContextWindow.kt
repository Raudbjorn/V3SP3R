package com.vesper.flipper.ai

import com.vesper.flipper.domain.model.*
import kotlinx.serialization.json.*

internal object ChatContextWindow {
    /** Bounded turns: a tool call and all of its results are retained or omitted together. */
    fun trim(messages: List<ChatMessage>, limit: Int, preserveCompaction: Boolean = false): List<ChatMessage> {
        require(limit > 0)
        if (messages.size <= limit) return messages
        val ordinary = boundedTurns(messages, limit)
        if (!preserveCompaction || limit < 3) return ordinary
        val anchorIndex = messages.indexOfLast { compactionBlocks(it).isNotEmpty() }
        if (anchorIndex < 0 || ordinary.any { it.id == messages[anchorIndex].id }) return ordinary
        val user = messages.take(anchorIndex).lastOrNull { it.role == MessageRole.USER } ?: return ordinary
        val anchor = messages[anchorIndex]
        val suffix = boundedTurns(messages.drop(anchorIndex + 1), limit - 2)
        if (suffix.isEmpty()) return ordinary
        // Preserve the opaque server anchor, without replaying obsolete tool calls or thinking.
        return listOf(user, anchor.copy(content = "", toolCalls = null, toolResults = null,
            imageAttachments = null, metadata = anchor.metadata?.copy(
                providerContentJson = JsonArray(compactionBlocks(anchor)).toString()))) + suffix
    }

    private fun compactionBlocks(message: ChatMessage): List<JsonElement> = runCatching {
        message.metadata?.providerContentJson?.let { raw -> Json.parseToJsonElement(raw).jsonArray.filter {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content in setOf("compaction", "compaction_summary")
        } }.orEmpty()
    }.getOrDefault(emptyList())

    private fun boundedTurns(messages: List<ChatMessage>, limit: Int): List<ChatMessage> {
        val starts = messages.indices.filter { messages[it].role == MessageRole.USER }
        val selected = mutableListOf<List<ChatMessage>>()
        var remaining = limit
        for (turn in starts.indices.reversed()) {
            val start = starts[turn]
            val end = starts.getOrNull(turn + 1) ?: messages.size
            val groups = mutableListOf<List<ChatMessage>>()
            var index = start + 1
            while (index < end) {
                val message = messages[index]
                if (message.role == MessageRole.ASSISTANT && !message.toolCalls.isNullOrEmpty()) {
                    val ids = message.toolCalls.map { it.id }.toSet()
                    var next = index + 1
                    while (next < end && messages[next].role == MessageRole.TOOL) next++
                    val results = messages.subList(index + 1, next)
                    val resultIds = results.flatMap { it.toolResults.orEmpty() }.map { it.toolCallId }
                    if (resultIds.toSet() == ids && resultIds.size == ids.size) groups += listOf(message) + results
                    index = next
                } else {
                    if (message.role != MessageRole.TOOL) groups += listOf(message)
                    index++
                }
            }
            val wholeTurn = listOf(messages[start]) + groups.flatten()
            if (wholeTurn.size <= remaining) {
                selected.add(0, wholeTurn); remaining -= wholeTurn.size
            } else if (selected.isEmpty()) {
                // One long turn cannot disable the bound. Keep the prompt and newest complete groups.
                val tail = mutableListOf<List<ChatMessage>>()
                var budget = remaining - 1
                for (group in groups.asReversed()) if (group.size <= budget) {
                    tail.add(0, group); budget -= group.size
                }
                selected.add(listOf(messages[start]) + tail.flatten())
                remaining = budget
                break
            } else break
            if (remaining == 0) break
        }
        return selected.flatten()
    }
}
