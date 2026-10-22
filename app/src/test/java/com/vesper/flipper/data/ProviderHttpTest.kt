package com.vesper.flipper.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProviderHttpTest {
    @Test fun boundedBodyRejectsDeclaredAndChunkedOverflow() {
        try { ByteArray(17).toResponseBody().readBoundedBytes(16); fail("oversize") } catch (_: IOException) { }
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setChunkedBody("x".repeat(17), 3))
            OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                try { it.body!!.readBoundedBytes(16); fail("oversize") } catch (_: IOException) { }
            }
        }
        assertArrayEquals(byteArrayOf(1,2), byteArrayOf(1,2).toResponseBody().readBoundedBytes(2))
    }
    @Test fun cancellingOwnerInterruptsStalledProviderCall() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val job = launch { call.withCancellableResponse { it.body?.string() } }
            withContext(Dispatchers.IO) { server.takeRequest() }
            job.cancel(); withTimeout(2000) { job.join() }
            assertTrue(call.isCanceled())
        }
    }
}
