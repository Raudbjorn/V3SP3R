package com.vesper.flipper.ai

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeneratedToolContractTest {
    @Test fun publishedSchemaMatchesRuntimeAndNativeProviderArgumentShapes() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "docs/execute_command_schema.json").isFile }
        val generated = Json.parseToJsonElement(File(root, "docs/execute_command_schema.json").readText()).jsonObject
        val field = OpenRouterClient::class.java.getDeclaredField("EXECUTE_COMMAND_TOOL").apply { isAccessible = true }
        val tool = field.get(null) as OpenRouterTool
        val runtime = tool.function.parameters
        fun actions(schema: JsonObject) = schema.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("enum").jsonArray.toSet()
        fun args(schema: JsonObject) = schema.getValue("properties").jsonObject.getValue("args").jsonObject.getValue("properties").jsonObject
        assertEquals(actions(generated), actions(runtime))
        assertEquals(args(generated).keys, args(runtime).keys)
        for ((name, specification) in args(generated)) {
            assertEquals(name, specification.jsonObject["type"], args(runtime).getValue(name).jsonObject["type"])
        }
        val native = AnthropicChatProtocol.encodeRequest(OpenRouterRequest("claude-test", emptyList(), tools=listOf(tool)))
            .getValue("tools").jsonArray.single().jsonObject.getValue("input_schema")
        assertEquals(runtime, native)
    }
}
