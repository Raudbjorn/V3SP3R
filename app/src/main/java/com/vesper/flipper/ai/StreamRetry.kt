package com.vesper.flipper.ai

import kotlinx.coroutines.delay

/** A request may be replayed only before any observable stream output. */
internal suspend fun retryStreamBeforeOutput(
    onEvent: suspend (ChatStreamEvent) -> Unit,
    maxAttempts: Int = 2,
    initialDelayMillis: Long = 700,
    request: suspend (suspend (ChatStreamEvent) -> Unit) -> ChatCompletionResult
): ChatCompletionResult {
    require(maxAttempts > 0)
    var emitted = false
    var backoff = initialDelayMillis.coerceIn(0, 10_000)
    repeat(maxAttempts) { attempt ->
        val result = request { event -> emitted = true; onEvent(event) }
        if (result !is ChatCompletionResult.Error || !result.retryable || emitted || attempt == maxAttempts - 1) return result
        delay((result.retryAfterMillis ?: backoff).coerceIn(0, 30_000))
        backoff = (backoff * 2).coerceAtMost(10_000)
    }
    error("Unreachable retry state")
}
