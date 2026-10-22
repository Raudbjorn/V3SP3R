package com.vesper.flipper.ai

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class ResponseFailureTest {
    private val client = OpenRouterClient(mock())
    @Test fun `blank and malformed responses are visible errors`() {
        for (body in listOf("{}", "not json", """{"id":"x","choices":[{"message":{"role":"assistant","content":" "},"finish_reason":"length"}]}""")) {
            assertTrue(client.parseResponse(body) is ChatCompletionResult.Error)
        }
    }
    @Test fun `malformed tool response fails visibly even with explanation text`() {
        val result = client.parseResponse("""{"id":"x","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"working","tool_calls":[{"id":"","function":{"name":"execute_command","arguments":"{}"}}]}}]}""")
        assertTrue(result is ChatCompletionResult.Error)
        assertTrue((result as ChatCompletionResult.Error).message.contains("malformed"))
    }
    @Test fun `output exhaustion explains the failure`() {
        val result = client.parseResponse("""{"id":"x","choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}]}""") as ChatCompletionResult.Error
        assertTrue(result.message.contains("output limit"))
    }
    @Test fun `tool only response is valid`() {
        val result = client.parseResponse("""{"id":"x","model":"m","choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"tool_calls":[{"id":"t","type":"function","function":{"name":"execute_command","arguments":"{}"}}]}}]}""")
        assertTrue(result is ChatCompletionResult.Success)
    }
}
