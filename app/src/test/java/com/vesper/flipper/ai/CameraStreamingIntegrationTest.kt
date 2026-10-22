package com.vesper.flipper.ai

import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CameraStreamingIntegrationTest {
    private val photo = ToolCall("photo", "execute_command", """{"action":"request_photo","args":{"photo_prompt":"Read label"}}""")
    private val other = ToolCall("other", "execute_command", """{"action":"list_directory","args":{"path":"/ext"}}""")

    @Test fun `common parser rejects mixed photo batches in either order`() {
        val client = OpenRouterClient(mock())
        for (calls in listOf(listOf(photo, other), listOf(other, photo))) {
            val body = buildJsonObject {
                put("id", "response"); put("model", "test")
                putJsonArray("choices") { addJsonObject {
                    put("finish_reason", "tool_calls")
                    putJsonObject("message") {
                        put("role", "assistant"); put("content", "")
                        putJsonArray("tool_calls") { calls.forEach { call -> addJsonObject {
                            put("id", call.id); put("type", "function")
                            putJsonObject("function") { put("name", call.name); put("arguments", call.arguments) }
                        } } }
                    }
                } }
            }.toString()
            val result = client.parseResponse(body)
            assertTrue(result is ChatCompletionResult.Error)
            assertTrue((result as ChatCompletionResult.Error).message.contains("excessive"))
        }
    }

    @Test fun `agent rejects mixed photo batches before any command side effect`() = runBlocking {
        for (calls in listOf(listOf(photo, other), listOf(other, photo))) {
            val client = mock<OpenRouterClient>()
            whenever(client.chatStream(any(), any())).thenReturn(flowOf(ChatStreamEvent.Complete(
                ChatCompletionResult.Success("", calls, "test", null)
            )))
            val executor = mock<CommandExecutor>()
            val settings = mock<SettingsStore> {
                on { aiMaxIterations } doReturn flowOf(2)
                on { lastChatSessionId } doReturn flowOf(null)
            }
            val agent = VesperAgent(client, executor, mock(), mock(), settings)
            val result = agent.sendMessage("Read the label and inspect files")
            assertTrue(result.error.orEmpty().contains("multiple tool calls"))
            assertFalse(result.isLoading)
            assertNull(result.pendingApproval)
            assertFalse(result.messages.any { it.role == MessageRole.TOOL })
            verifyNoInteractions(executor)
            verify(client, never()).parseCommandDetailed(any())
        }
    }

    @Test(timeout = 25_000) fun `camera deadline cancels actual stalled vision body without fallback or replay`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Headers arrive, but the advertised body never does. A blocking read would take 75 seconds.
            server.enqueue(MockResponse().setBody("").setHeader("Content-Length", "100")
                .setHeader("Content-Type", "application/json"))
            val cancelled = AtomicBoolean(false)
            val headersSeen = AtomicBoolean(false)
            val http = localHttp(server).eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
                override fun responseHeadersEnd(call: Call, response: Response) { headersSeen.set(true) }
            }).build()
            val client = OpenRouterClient(providerSettings(), http)
            val permissions = PermissionService()
            val executor = CommandExecutor(
                fileSystem = mock(), riskAssessor = RiskAssessor(permissions),
                permissionService = permissions, auditService = mock(), diffService = mock(),
                forgeEngine = mock(), settingsStore = mock {
                    on { autoApproveMedium } doReturn flowOf(true)
                    on { autoApproveHigh } doReturn flowOf(true)
                }, appCatalog = mock()
            )
            var captures = 0
            executor.setPhotoCaptureCallback(PhotoCaptureCallback { prompt, timeout ->
                assertEquals(15_000L, timeout)
                captures++
                client.describeImageForAgent(ImageAttachment(base64Data = "AA=="), prompt)
            })
            val command = ExecuteCommand(CommandAction.REQUEST_PHOTO, CommandArgs(photoPrompt = "Read label"),
                justification = "Read label", expectedEffect = "Describe label")
            val id = requireNotNull(executor.execute(command, "owner").pendingApprovalId)
            val start = System.nanoTime()
            val result = executor.approve(id, "owner")
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("timed out"))
            assertTrue("Deadline took ${elapsedMs}ms", elapsedMs < 19_000)
            assertTrue(headersSeen.get())
            assertTrue(cancelled.get())
            assertEquals(1, server.requestCount)
            assertFalse(executor.approve(id, "owner").success)
            assertEquals(1, captures)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `nonstream requests retain bounded retry for rate limits and server failures`() = runBlocking {
        for (status in listOf(429, 503)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("busy"))
                server.enqueue(MockResponse().setBody("""{"id":"response","model":"test","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""))
                val client = OpenRouterClient(providerSettings(), localHttp(server).build())
                assertEquals("ok", client.chatSimple("hello"))
                assertEquals(2, server.requestCount)
            }
        }
    }

    private suspend fun providerSettings(): SettingsStore = mock<SettingsStore>().also {
        whenever(it.readLlmSelection()).thenReturn(LlmSelection(LlmProvider.OPENAI, "test-provider-key", LlmProvider.OPENAI.defaultModel))
    }

    private fun localHttp(server: MockWebServer) = OkHttpClient.Builder()
        .readTimeout(75, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/vision")).build()) }
}
