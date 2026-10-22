package com.vesper.flipper.ai

import com.vesper.flipper.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class ModelToolValidationTest {
    private fun parse(name: String): ChatCompletionResult {
        val client = OpenRouterClient(mock<SettingsStore>())
        val method = OpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java)
        method.isAccessible = true
        return method.invoke(client, """{"id":"response-1","model":"test","choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"$name","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""") as ChatCompletionResult
    }

    @Test fun `rejects invented tool names before accepting a completion`() {
        val result = parse("execute_shell")
        assertTrue(result is ChatCompletionResult.Error)
        assertTrue((result as ChatCompletionResult.Error).message.contains("unsupported tool"))
    }

    @Test fun `accepts the advertised command tool`() {
        val result = parse("execute_command")
        assertTrue(result is ChatCompletionResult.Success)
        assertEquals("execute_command", (result as ChatCompletionResult.Success).toolCalls!!.single().name)
    }
}
