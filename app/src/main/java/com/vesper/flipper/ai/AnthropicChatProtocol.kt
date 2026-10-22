package com.vesper.flipper.ai

import kotlinx.serialization.json.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Native Messages wire format; domain commands still use the shared validator/executor. */
object AnthropicChatProtocol : ChatProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    override fun buildRequest(provider: LlmProvider, apiKey: String, request: OpenRouterRequest): Request {
        require(provider == LlmProvider.ANTHROPIC)
        return Request.Builder().url(provider.chatCompletionsUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .tag(ChatProtocol::class.java, this)
            .post(encodeRequest(request).toString().toRequestBody("application/json".toMediaType())).build()
    }

    internal fun encodeRequest(request: OpenRouterRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("max_tokens", request.maxTokens ?: request.maxCompletionTokens ?: 4096)
        val system = request.messages.filter { it.role == "system" }
        if (system.isNotEmpty()) put("system", JsonArray(system.flatMap { contentBlocks(it.content) }))
        val turns = mutableListOf<Pair<String, MutableList<JsonElement>>>()
        for (message in request.messages.filter { it.role != "system" }) {
            val role = if (message.role == "assistant") "assistant" else "user"
            val blocks = if (message.role == "tool") {
                require(!message.toolCallId.isNullOrBlank()) { "Tool result is missing its tool call ID" }
                listOf(buildJsonObject {
                    put("type", "tool_result"); put("tool_use_id", message.toolCallId)
                    put("content", message.content ?: JsonPrimitive(""))
                })
            } else contentBlocks(message.content) + message.toolCalls.orEmpty().map { call ->
                require(call.id.isNotBlank() && call.function.name.isNotBlank()) { "Malformed tool call" }
                buildJsonObject {
                    put("type", "tool_use"); put("id", call.id); put("name", call.function.name)
                    put("input", json.parseToJsonElement(call.function.arguments).jsonObject)
                }
            }
            if (blocks.isEmpty()) continue
            if (turns.lastOrNull()?.first == role) turns.last().second.addAll(blocks)
            else turns.add(role to blocks.toMutableList())
        }
        put("messages", JsonArray(turns.map { (role, blocks) -> buildJsonObject {
            put("role", role); put("content", JsonArray(blocks))
        } }))
        request.tools?.takeIf { it.isNotEmpty() }?.let { tools ->
            put("tools", JsonArray(tools.map { tool -> buildJsonObject {
                put("name", tool.function.name); put("description", tool.function.description)
                put("input_schema", tool.function.parameters)
            } }))
            put("tool_choice", buildJsonObject { put("type", "auto") })
        }
    }

    private fun contentBlocks(content: JsonElement?): List<JsonElement> = when (content) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> if (content.content.isBlank()) emptyList() else listOf(buildJsonObject {
            put("type", "text"); put("text", content.content)
        })
        is JsonArray -> content.map { element ->
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> block
                "image_url" -> {
                    val url = block["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                    require(url.startsWith("data:image/") && url.contains(";base64,")) { "Only embedded images are supported" }
                    buildJsonObject {
                        put("type", "image")
                        put("source", buildJsonObject {
                            put("type", "base64"); put("media_type", url.substringAfter("data:").substringBefore(';'))
                            put("data", url.substringAfter(";base64,"))
                        })
                    }
                }
                else -> error("Unsupported message content block")
            }
        }
        else -> error("Unsupported message content")
    }

    override fun normalizeResponse(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["type"]?.jsonPrimitive?.content == "error" || root.containsKey("error")) return body
        val blocks = root["content"]?.jsonArray ?: error("Missing Anthropic content")
        val text = StringBuilder()
        val calls = mutableListOf<JsonObject>()
        for (element in blocks) {
            val block = element.jsonObject
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> text.append(block["text"]!!.jsonPrimitive.content)
                "tool_use" -> calls.add(buildJsonObject {
                    val id = block["id"]!!.jsonPrimitive.content
                    val name = block["name"]!!.jsonPrimitive.content
                    require(id.isNotBlank() && name.isNotBlank()) { "Malformed Anthropic tool call" }
                    put("id", id); put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name); put("arguments", block["input"]!!.jsonObject.toString())
                    })
                })
            }
        }
        require(text.isNotBlank() || calls.isNotEmpty()) { "Anthropic returned no text or tool calls" }
        return buildJsonObject {
            put("id", root["id"] ?: JsonPrimitive("anthropic"))
            put("model", root["model"] ?: JsonPrimitive(""))
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", when(root["stop_reason"]?.jsonPrimitive?.content) {
                    "tool_use" -> "tool_calls"; "max_tokens" -> "length"; else -> "stop"
                })
                put("message", buildJsonObject {
                    put("role", "assistant"); put("content", text.toString())
                    if (calls.isNotEmpty()) put("tool_calls", JsonArray(calls))
                })
            }) })
            root["usage"]?.jsonObject?.let { usage -> put("usage", buildJsonObject {
                put("total_tokens", (usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0) +
                    (usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0))
            }) }
        }.toString()
    }
}
