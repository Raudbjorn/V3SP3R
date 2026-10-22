package com.vesper.flipper.data

import com.vesper.flipper.ai.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches all tool-capable OpenRouter models, ordered by provider and recency.
 */
@Singleton
class OpenRouterModelCatalog @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(provider: LlmProvider, apiKey: String?): Result<List<ModelInfo>> {
        if (provider == LlmProvider.OPENROUTER) {
            return fetchToolModels()
        }
        if (apiKey.isNullOrBlank()) {
            return Result.success(provider.models)
        }
        return fetchDirectCatalog(provider, apiKey)
    }

    private suspend fun fetchDirectCatalog(
        provider: LlmProvider,
        apiKey: String
    ): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(provider.modelsUrl).header("Accept", "application/json")
            if (provider == LlmProvider.ANTHROPIC) {
                builder.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01")
            } else builder.header("Authorization", "Bearer $apiKey")
            val request = builder.build()

            client.newCall(request).withCancellableResponse { response ->
                if (!response.isSuccessful) {
                    return@withCancellableResponse Result.failure(
                        IOException("${provider.displayName} models API error: ${response.code}")
                    )
                }
                val body = response.body?.readBoundedBytes(2 * 1024 * 1024)?.decodeToString()
                    ?: return@withCancellableResponse Result.failure(IOException("Empty model catalog response"))
                val root = json.parseToJsonElement(body).jsonObject
                val rows = root["data"]?.jsonArray ?: root["models"]?.jsonArray
                    ?: return@withCancellableResponse Result.success(provider.models)
                val liveIds = rows.mapNotNull { element ->
                    val modelObj = element.jsonObject
                    modelObj.string("id") ?: modelObj.string("name")
                }
                Result.success(LlmProvider.mergeCatalog(provider, liveIds))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /** Eligible live OpenRouter tool models; static choices only when no eligible entries remain. */
    suspend fun fetchToolModels(): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(MODELS_URL)
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).withCancellableResponse { response ->
                if (!response.isSuccessful) {
                    return@withCancellableResponse Result.failure(
                        IOException("OpenRouter models API error: ${response.code}")
                    )
                }

                val body = response.body?.readBoundedBytes(2 * 1024 * 1024)?.decodeToString()
                    ?: return@withCancellableResponse Result.failure(IOException("Empty model catalog response"))

                Result.success(parseCatalog(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    internal fun parseCatalog(body: String): List<ModelInfo> {
                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"]?.jsonArray
                    ?: throw IOException("Invalid model catalog payload")

                // Only models that can call tools. This is not a preference — the app
                // works by having the model invoke execute_command, so a model without
                // tool support cannot do anything here and picking one produces a
                // conversation where nothing ever happens. It was previously possible
                // to select exactly that.
                val usable = data.mapNotNull { element ->
                    // Skip anything that is not a well-formed model object rather
                    // than letting one malformed entry abort the whole fetch.
                    val modelObj = (element as? JsonObject) ?: return@mapNotNull null
                    val id = modelObj.string("id") ?: return@mapNotNull null

                    val supportsTools = modelObj["supported_parameters"]?.let { params ->
                        runCatching { params.jsonArray.any { it.jsonPrimitive.content == "tools" } }
                            .getOrDefault(false)
                    } ?: false
                    if (!supportsTools) return@mapNotNull null

                    // :free and :beta are rate-limited or unstable aliases of a model
                    // that is already in the list under its real id.
                    if (id.contains(":free", true) || id.contains(":beta", true) ||
                        id.contains(":extended", true) || id.endsWith(":batch")
                    ) return@mapNotNull null

                    val supportsImages = (modelObj["architecture"] as? JsonObject)
                        ?.get("input_modalities")?.let { mods ->
                            runCatching { mods.jsonArray.any { it.jsonPrimitive.content == "image" } }
                                .getOrDefault(false)
                        } ?: false

                    RemoteModel(
                        id = id,
                        name = modelObj.string("name") ?: id.substringAfter("/"),
                        created = modelObj.long("created") ?: 0L,
                        supportsImages = supportsImages
                    )
                }

                if (usable.isEmpty()) {
                    return SettingsStore.FALLBACK_MODELS
                }

                // Known vendors first and in a deliberate order, everything else after,
                // newest first within each. The old code kept ONE model per vendor —
                // the most recently created — which is not the same as the best one: a
                // vendor's newest release is often a small or experimental variant while
                // its flagship, published a month earlier, never appeared at all. That
                // is why the list was both short and full of odd choices.
                val vendorRank = MAJOR_MANUFACTURERS.withIndex()
                    .associate { (index, m) -> m.providerId to index }

                val ordered = usable
                    .sortedWith(
                        compareBy<RemoteModel> { vendorRank[providerFromId(it.id)] ?: Int.MAX_VALUE }
                            .thenBy { providerFromId(it.id) }
                            .thenByDescending { it.created }
                    )
                    .map { model ->
                        ModelInfo(
                            id = model.id,
                            displayName = model.name,
                            description = if (model.supportsImages) "Tools · images" else "Tools"
                        )
                    }

                return ordered
    }

    private fun providerFromId(modelId: String): String = modelId.substringBefore("/")

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (!value.isString) return null
        val content = value.content
        return content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.long(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.longOrNull
    }

    companion object {
        private const val MODELS_URL = "https://openrouter.ai/api/v1/models"

        private data class Manufacturer(
            val providerId: String,
            val displayName: String
        )

        private data class RemoteModel(
            val id: String,
            val name: String,
            val created: Long,
            val supportsImages: Boolean = false
        )

        private val MAJOR_MANUFACTURERS = listOf(
            Manufacturer("google", "Google"),
            Manufacturer("anthropic", "Anthropic"),
            Manufacturer("openai", "OpenAI"),
            Manufacturer("x-ai", "xAI"),
            Manufacturer("qwen", "Qwen"),
            Manufacturer("deepseek", "DeepSeek"),
            Manufacturer("moonshotai", "Moonshot"),
            Manufacturer("meta-llama", "Meta"),
            Manufacturer("minimax", "MiniMax")
        )
    }
}
