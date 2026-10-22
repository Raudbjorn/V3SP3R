package com.vesper.flipper.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException

sealed interface ChatStreamEvent {
    data class TextDelta(val text: String) : ChatStreamEvent
    data class ToolDelta(val index: Int, val arguments: String) : ChatStreamEvent
    data object Thinking : ChatStreamEvent
    data class Complete(val result: ChatCompletionResult) : ChatStreamEvent
}

/** Network callbacks run on IO; channelFlow permits those cross-context emissions. */
internal fun completionEvents(request: suspend (suspend (ChatStreamEvent) -> Unit) -> ChatCompletionResult): Flow<ChatStreamEvent> = channelFlow {
    val result = request { send(it) }
    send(ChatStreamEvent.Complete(result))
}

/** Retain structured provider failures so the existing model fallback policy can classify them. */
internal fun streamHttpError(status: Int, body: String): ChatCompletionResult.Error {
    val detail = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        (root["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.take(1000).orEmpty()
    return ChatCompletionResult.Error("Streaming API error $status" + if (detail.isBlank()) "" else ": $detail", retryable = status == 429 || status in 500..599)
}

/** Cancels the underlying socket even while a blocking body read is in progress. */
internal suspend fun <T> Call.withCancellation(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@withCancellation
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { withContext(Dispatchers.IO) { call.execute().use { block(it) } } }
    catch (e: Exception) { currentCoroutineContext().ensureActive(); throw e }
    finally { watcher.cancel() }
}

/** SSE framing and accumulation. No tool is executable until the terminal event. */
internal class ChatStreamReader(private val native: Boolean) {
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val arguments = mutableMapOf<Int, StringBuilder>()
    private val tools = sortedMapOf<Int, JsonObject>()
    private val text = StringBuilder()
    private var model = ""
    private var id = "stream"
    private var stop = "stop"
    private var usage = buildJsonObject {}
    private val openBlocks = mutableSetOf<Int>()
    private var terminal = false
    private var totalChars = 0

    suspend fun read(source: BufferedSource, emit: suspend (ChatStreamEvent) -> Unit): String {
        val data = StringBuilder()
        while (!terminal) {
            currentCoroutineContext().ensureActive()
            if (source.exhausted()) break
            val line = source.readUtf8LineStrict(2_000_000)
            totalChars += line.length
            if (totalChars > 2_000_000) throw IOException("Stream exceeded response limit")
            if (line.isEmpty()) {
                if (data.isNotEmpty()) consume(data.toString().removeSuffix("\n"), emit)
                data.setLength(0)
            } else if (line.startsWith("data:")) data.append(line.substring(5).removePrefix(" ")).append('\n')
        }
        if (!terminal && data.isNotEmpty()) consume(data.toString().trimEnd('\n'), emit)
        if (!terminal) throw IOException("Stream ended before completion; retry the request")
        if (!native) {
            require(tools.size <= 1) { "Provider returned more tool calls than supported" }
            tools.forEach { (index, tool) ->
                require(!tool["id"]?.jsonPrimitive?.content.isNullOrBlank()) { "Tool call is missing its ID" }
                require(!tool["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content.isNullOrBlank()) { "Tool call is missing its name" }
                Json.parseToJsonElement(arguments[index].toString()).jsonObject
            }
        }
        return if (native) buildJsonObject {
            put("id", id); put("model", model); put("stop_reason", stop); put("usage", usage)
            put("content", JsonArray(blocks.values.toList()))
        }.toString() else buildJsonObject {
            put("id", id); put("model", model)
            if (usage.isNotEmpty()) put("usage", usage)
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", stop)
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", text.toString())
                    if (tools.isNotEmpty()) put("tool_calls", JsonArray(tools.map { (index, tool) ->
                        JsonObject(tool + ("function" to JsonObject(tool["function"]!!.jsonObject +
                            ("arguments" to JsonPrimitive(arguments[index].toString())))))
                    }))
                })
            }) })
        }.toString()
    }

    private suspend fun consume(data: String, emit: suspend (ChatStreamEvent) -> Unit) {
        if (data == "[DONE]") { terminal = true; return }
        val event = Json.parseToJsonElement(data).jsonObject
        if (event.containsKey("error") || event["type"]?.jsonPrimitive?.content == "error")
            throw IOException("Provider reported an error while streaming")
        if (!native) {
            event["model"]?.jsonPrimitive?.content?.let { model = it }
            event["id"]?.jsonPrimitive?.content?.let { id = it }
            (event["usage"] as? JsonObject)?.let { usage = it }
            for (choice in event["choices"]?.jsonArray.orEmpty()) {
                val obj = choice.jsonObject
                obj["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { stop = it }
                val delta = obj["delta"]?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                    text.append(it); emit(ChatStreamEvent.TextDelta(it))
                }
                for (part in delta["tool_calls"]?.jsonArray.orEmpty()) {
                    val call = part.jsonObject; val index = call["index"]!!.jsonPrimitive.int
                    val old = tools[index] ?: buildJsonObject { put("type", "function") }
                    val functionDelta = (call["function"] as? JsonObject).orEmpty()
                    val function = JsonObject((old["function"] as? JsonObject).orEmpty() + functionDelta.filterKeys { it != "arguments" })
                    tools[index] = JsonObject(old + call.filterKeys { it != "index" && it != "function" } + ("function" to function))
                    val fragment = functionDelta["arguments"]?.jsonPrimitive?.content.orEmpty()
                    arguments.getOrPut(index) { StringBuilder() }.append(fragment)
                    emit(ChatStreamEvent.ToolDelta(index, fragment))
                }
            }
            return
        }
        val index = event["index"]?.jsonPrimitive?.intOrNull ?: 0
        when (event["type"]?.jsonPrimitive?.content) {
            "message_start" -> {
                val message = event["message"]!!.jsonObject
                id = message["id"]!!.jsonPrimitive.content; model = message["model"]!!.jsonPrimitive.content
                usage = message["usage"]?.jsonObject ?: buildJsonObject {}
            }
            "content_block_start" -> {
                require(openBlocks.add(index)) { "Duplicate open content block" }
                val block = event["content_block"]!!.jsonObject; blocks[index] = block
                if (block["type"]?.jsonPrimitive?.content == "thinking") emit(ChatStreamEvent.Thinking)
                if (block["type"]?.jsonPrimitive?.content == "text") {
                    block["text"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }?.let { emit(ChatStreamEvent.TextDelta(it)) }
                }
            }
            "content_block_delta" -> {
                val delta = event["delta"]!!.jsonObject
                val block = blocks[index] ?: throw IOException("Delta has no content block")
                when (delta["type"]!!.jsonPrimitive.content) {
                    "text_delta" -> {
                        val value = delta["text"]!!.jsonPrimitive.content
                        blocks[index] = JsonObject(block + ("text" to JsonPrimitive((block["text"]?.jsonPrimitive?.content ?: "") + value)))
                        emit(ChatStreamEvent.TextDelta(value))
                    }
                    "input_json_delta" -> {
                        val value = delta["partial_json"]!!.jsonPrimitive.content
                        arguments.getOrPut(index) { StringBuilder() }.append(value)
                        emit(ChatStreamEvent.ToolDelta(index, value))
                    }
                    "compaction_delta", "compaction_summary_delta" -> {
                        val value = delta["content"]?.jsonPrimitive?.content ?: delta["text"]?.jsonPrimitive?.content.orEmpty()
                        blocks[index] = JsonObject(block + ("content" to JsonPrimitive((block["content"]?.jsonPrimitive?.content ?: "") + value)))
                    }
                    "thinking_delta", "signature_delta" -> {
                        val key = if (delta["type"]!!.jsonPrimitive.content == "thinking_delta") "thinking" else "signature"
                        blocks[index] = JsonObject(block + (key to JsonPrimitive((block[key]?.jsonPrimitive?.content ?: "") + delta[key]!!.jsonPrimitive.content)))
                    }
                    else -> throw IOException("Unsupported stream delta; retry without streaming")
                }
            }
            "content_block_stop" -> {
                require(openBlocks.remove(index)) { "Content block closed without a start" }
                arguments[index]?.let { value ->
                    val input = Json.parseToJsonElement(value.toString()).jsonObject
                    blocks[index] = JsonObject(blocks[index]!! + ("input" to input))
                }
            }
            "message_delta" -> {
                stop = event["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull ?: stop
                usage = JsonObject(usage + (event["usage"] as? JsonObject).orEmpty())
            }
            "message_stop" -> { require(openBlocks.isEmpty()) { "Stream ended with unfinished content" }; terminal = true }
        }
    }
}
