package com.vesper.flipper.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StreamRetryTest {
    @Test fun `transient HTTP failures retry before the first delta`() = runBlocking {
        for (code in listOf(429,503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code))
            server.enqueue(MockResponse().setBody("ok"))
            val events=mutableListOf<ChatStreamEvent>()
            val result=retryStreamBeforeOutput({ events+=it }, initialDelayMillis=0) { emit ->
                OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).withCancellation { response ->
                    if (!response.isSuccessful) streamHttpError(response.code, "")
                    else { emit(ChatStreamEvent.TextDelta("ok")); ChatCompletionResult.Success("ok",null,"model",null) }
                }
            }
            assertTrue(result is ChatCompletionResult.Success); assertEquals(2,server.requestCount); assertEquals(1,events.size)
        }
    }
    @Test fun `partial output forbids replay including tool and thinking events`() = runTest {
        for(event in listOf(ChatStreamEvent.TextDelta("a"),ChatStreamEvent.ToolDelta(0,"{"),ChatStreamEvent.Thinking)) {
            var calls=0
            val result=retryStreamBeforeOutput({},initialDelayMillis=0) { emit -> calls++;emit(event);ChatCompletionResult.Error("EOF",true) }
            assertTrue(result is ChatCompletionResult.Error);assertEquals(1,calls)
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `cancellation interrupts bounded Retry After backoff`() = runTest {
        var calls=0
        val job=launch { retryStreamBeforeOutput({}) { calls++;ChatCompletionResult.Error("429",true,Long.MAX_VALUE) } }
        runCurrent();assertEquals(1,calls);advanceTimeBy(29_999);assertEquals(1,calls)
        job.cancelAndJoin();advanceUntilIdle();assertEquals(1,calls)
    }
}
