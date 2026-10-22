package com.vesper.flipper.ai

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class ResponseFailureTest {
    private val client = OpenRouterClient(mock())
    @Test fun `blank and malformed responses are visible errors`() {
        for (body in listOf("{}", "not json", """{"id":"x","choices":[{"message":{"content":" "},"finish_reason":"length"}]}""")) {
            assertTrue(client.parseResponse(body) is ChatCompletionResult.Error)
        }
    }
    @Test fun `tool only response is valid`() {
        val result = client.parseResponse("""{"id":"x","model":"m","choices":[{"message":{"content":null,"tool_calls":[{"id":"t","type":"function","function":{"name":"execute_command","arguments":"{}"}}]}}]}""")
        assertTrue(result is ChatCompletionResult.Success)
    }
}
