package com.vesper.flipper.data

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException

internal suspend fun <T> Call.withCancellableResponse(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@withCancellableResponse
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            call.execute().use { response -> currentCoroutineContext().ensureActive(); block(response) }
        }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        throw error
    } finally { cancellation.cancel() }
}

internal fun ResponseBody.readBoundedBytes(limit: Int): ByteArray {
    require(limit > 0)
    if (contentLength() > limit) throw IOException("Provider response exceeded size limit")
    val source = source()
    if (source.request(limit.toLong() + 1)) throw IOException("Provider response exceeded size limit")
    return source.readByteArray()
}
