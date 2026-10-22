package com.vesper.flipper.data

import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Uses the same firmware-specific build endpoint as Flipper Lab. */
class FlipperAppCatalog(
    private val client: OkHttpClient,
    private val baseUrl: String
) {
    @Inject constructor() : this(
        OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build(),
        "https://catalog.flipperzero.one/api/v0"
    )
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun apps(): List<FapApp> = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val categories = json.parseToJsonElement(fetch("$baseUrl/category", deadline).decodeToString())
            .jsonArray.associate { it.jsonObject.text("_id") to it.jsonObject.text("name") }
        val result = mutableListOf<FapApp>()
        for (page in 0 until 20) {
            val rows = json.parseToJsonElement(fetch("$baseUrl/application?limit=100&offset=${page * 100}", deadline).decodeToString()).jsonArray
            rows.forEach { row ->
                val obj = row.jsonObject
                val version = obj["current_version"] as? JsonObject ?: return@forEach
                val id = obj.text("alias")
                if (!SAFE_ID.matches(id)) return@forEach
                val categoryName = categories[obj.text("category_id")] ?: "Misc"
                result += FapApp(
                    id = id, name = version.text("name"), description = version.text("short_description"),
                    category = FapCategory.entries.firstOrNull { it.displayName == categoryName } ?: FapCategory.MISC,
                    author = obj.text("author"), version = version.text("version"),
                    downloadUrl = "https://lab.flipper.net/apps/$id",
                    iconUrl = version.text("icon_uri").ifBlank { null },
                    downloads = obj.text("downloads").toIntOrNull() ?: 0
                )
            }
            if (rows.size < 100) return@withContext result
        }
        throw IOException("App catalog exceeded its page limit; retry later")
    }

    suspend fun download(alias: String, info: DeviceInfo): CatalogBinary = withContext(Dispatchers.IO) {
        require(SAFE_ID.matches(alias)) { "Use an exact app ID from the catalog" }
        val target = info.firmwareTarget?.takeIf { it.matches(Regex("f[0-9]+")) }
            ?: throw IOException("Flipper did not report its hardware target. Unlock it and reconnect.")
        val api = info.firmwareApi?.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+")) }
            ?: throw IOException("Flipper did not report its app API version. Unlock it and reconnect.")
        fun compatible(path: String) = "$baseUrl/$path".toHttpUrl().newBuilder()
            .addQueryParameter("target", target).addQueryParameter("api", api).build().toString()
        val detail = json.parseToJsonElement(fetch(compatible("application/$alias")).decodeToString()).jsonObject
        val version = detail["current_version"] as? JsonObject
            ?: throw IOException("No compatible version of $alias for $target / API $api")
        val build = version["current_build"] as? JsonObject
            ?: throw IOException("No compatible build of $alias for $target / API $api")
        val sdk = build["sdk"] as? JsonObject ?: throw IOException("Catalog build has no compatibility metadata")
        if (sdk.text("target") != target || !compatibleApi(sdk.text("api"), api)) {
            throw IOException("Catalog returned an incompatible build; nothing was installed")
        }
        val versionId = version.text("_id")
        require(SAFE_ID.matches(versionId)) { "Invalid catalog version ID" }
        val url = compatible("application/version/$versionId/build/compatible")
        val bytes = fetch(url)
        validateBinary(bytes, build.text("fap_hash"))
        val categories = json.parseToJsonElement(fetch("$baseUrl/category").decodeToString()).jsonArray
        val category = categories.firstOrNull { it.jsonObject.text("_id") == detail.text("category_id") }
            ?.jsonObject?.text("name") ?: "Tools"
        require(category.matches(Regex("[A-Za-z0-9 -]+"))) { "Invalid catalog category" }
        CatalogBinary(alias, version.text("name"), version.text("version"), bytes, url, categoryDirectory(category))
    }

    private suspend fun fetch(url: String, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)): ByteArray {
        if (System.nanoTime() >= deadline) throw IOException("Catalog operation timed out")
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Vesper/1").build())
        call.timeout().deadlineNanoTime(deadline)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            if (!response.isSuccessful) throw IOException("Catalog HTTP ${response.code}: no file installed")
                            val body = response.body ?: throw IOException("Empty catalog response")
                            if (body.contentLength() > MAX_BYTES) throw IOException("Catalog response is too large")
                            body.byteStream().use { stream ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = stream.read(buffer)
                                    if (count < 0) break
                                    if (out.size() + count > MAX_BYTES) throw IOException("Catalog response is too large")
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        internal fun categoryDirectory(label: String): String =
            (FapCategory.entries.firstOrNull { it.displayName.equals(label, true) } ?: FapCategory.MISC).name.lowercase()
        private fun compatibleApi(required: String, device: String): Boolean {
            fun parse(value: String): List<Int>? = value.split('.').takeIf { it.size == 2 }
                ?.map { it.toIntOrNull() ?: return null }?.takeIf { it.all { n -> n >= 0 } }
            val need = parse(required) ?: return false
            val have = parse(device) ?: return false
            return need[0] == have[0] && need[1] <= have[1]
        }
        private val SAFE_ID = Regex("[A-Za-z0-9_-]+")
        private const val MAX_BYTES = 10 * 1024 * 1024
        internal fun validateBinary(bytes: ByteArray, expectedSha256: String) {
            if (bytes.size < 52 || !bytes.take(4).toByteArray().contentEquals(byteArrayOf(127, 69, 76, 70)) ||
                bytes[4] != 1.toByte() || bytes[5] != 1.toByte() || bytes[18] != 40.toByte() || bytes[19] != 0.toByte()) {
                throw IOException("Download is not a 32-bit ARM ELF app; nothing was installed")
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!expectedSha256.matches(Regex("[a-fA-F0-9]{64}")) || !actual.equals(expectedSha256, true)) {
                throw IOException("App checksum does not match the catalog; nothing was installed")
            }
        }
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

data class CatalogBinary(val id: String, val name: String, val version: String, val bytes: ByteArray, val source: String, val category: String)

