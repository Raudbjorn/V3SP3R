package com.vesper.flipper.data

import com.vesper.flipper.domain.model.DeviceInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

class FlipperAppCatalogTest {
    private val info = DeviceInfo("test", "mntm-012", "7", 90, false, "f7", "87.1")
    private fun elf() = ByteArray(100).apply {
        byteArrayOf(127,69,76,70,1,1).copyInto(this)
        this[18] = 40
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun detail(hash: String, api: String = "87.1") = """{"category_id":"tools","current_version":{"_id":"version1","name":"Test","version":"1.0","current_build":{"sdk":{"target":"f7","api":"$api"},"fap_hash":"$hash"}}}"""

    @Test fun `uses device SDK for metadata and build and checks official hash`() = runBlocking {
        MockWebServer().use { server ->
            val bytes = elf()
            server.enqueue(MockResponse().setBody(detail(hash(bytes))))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            server.enqueue(MockResponse().setBody("""[{"_id":"tools","name":"Tools"}]"""))
            val result = FlipperAppCatalog(OkHttpClient(), server.url("/api/v0").toString()).download("test_app", info)
            assertArrayEquals(bytes, result.bytes)
            assertEquals("tools", result.category)
            assertEquals("/api/v0/application/test_app?target=f7&api=87.1", server.takeRequest().path)
            assertEquals("/api/v0/application/version/version1/build/compatible?target=f7&api=87.1", server.takeRequest().path)
        }
    }
    @Test fun `rejects HTML and mismatched checksums`() {
        for (bytes in listOf("<html>not an app</html>".toByteArray(), elf())) {
            try { FlipperAppCatalog.validateBinary(bytes, "0".repeat(64)); fail("Must reject invalid download") }
            catch (_: IOException) { }
        }
    }
    @Test fun `does not download build for incompatible API`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(detail(hash(elf()), "88.2")))
            try { FlipperAppCatalog(OkHttpClient(), server.url("/api/v0").toString()).download("test_app", info); fail("Must reject SDK mismatch") }
            catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `rejects newer minor and malformed SDK without downloading`() = runBlocking {
        for (api in listOf("87.2", "87.invalid", "87")) MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(detail(hash(elf()), api)))
            try { FlipperAppCatalog(OkHttpClient(), server.url("/api/v0").toString()).download("test_app", info); fail("Must reject SDK mismatch") }
            catch (_: IOException) { }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `cancelling a slow response body releases catalog work promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]").setBodyDelay(4, TimeUnit.SECONDS))
            val catalog = FlipperAppCatalog(OkHttpClient(), server.url("/api/v0").toString())
            val load = launch { catalog.apps() }
            while (server.requestCount == 0) delay(10)
            delay(100) // Headers arrive before the deliberately delayed body.
            withTimeout(2000) { load.cancelAndJoin() }
            assertTrue(load.isCancelled)
        }
    }
    @Test fun `canonical category uses the UI directory`() {
        assertEquals("subghz", FlipperAppCatalog.categoryDirectory("Sub-GHz"))
        assertEquals("tools", FlipperAppCatalog.categoryDirectory("Tools"))
        assertEquals("misc", FlipperAppCatalog.categoryDirectory("Unknown category"))
    }
    @Test fun `metadata normalizes combined and split SDK representations`() {
        assertEquals("f7", FirmwareCatalogMetadata.target("F7"))
        assertEquals("f7", FirmwareCatalogMetadata.target("7"))
        assertEquals("87.1", FirmwareCatalogMetadata.api(mapOf("firmware_api" to "87.1")))
        assertEquals("87.1", FirmwareCatalogMetadata.api(mapOf("firmware_api.major" to "87", "firmware_api.minor" to "1")))
        assertNull(FirmwareCatalogMetadata.target("fF7"))
        assertNull(FirmwareCatalogMetadata.api(mapOf("firmware_api" to "invalid")))
    }
    @Test fun `unknown device API does not guess or fetch`() = runBlocking {
        MockWebServer().use { server ->
            try { FlipperAppCatalog(OkHttpClient(), server.url("/api/v0").toString()).download("test_app", info.copy(firmwareApi = null)); fail("Must require device SDK") }
            catch (_: IOException) { }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun `live official calculator build matches device SDK`() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("VESPER_LIVE_CATALOG") == "1")
        val catalog = FlipperAppCatalog()
        val apps = catalog.apps()
        assertTrue(apps.any { it.id == "calculator" })
        val result = catalog.download("calculator", info)
        assertTrue(result.bytes.size > 52)
        assertEquals("tools", result.category)
    }

}
