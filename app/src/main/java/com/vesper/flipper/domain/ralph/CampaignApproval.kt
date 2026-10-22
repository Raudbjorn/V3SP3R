package com.vesper.flipper.domain.ralph

import com.vesper.flipper.domain.model.ExecuteCommand
import com.vesper.flipper.domain.model.PendingApproval
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal fun approvalAvailable(pending: PendingApproval?, id: String?, command: ExecuteCommand, now: Long): Boolean =
    id != null && pending != null && pending.id == id && pending.command == command && now < pending.expiresAt

/** Persist an uncertain terminal outcome even when the caller's coroutine has been cancelled. */
internal suspend fun <T> executeCampaignApproval(
    timeoutMs: Long,
    onInterrupted: suspend () -> Unit,
    execute: suspend () -> T
): T = try {
    withTimeout(timeoutMs) { execute() }
} catch (failure: Exception) {
    withContext(NonCancellable) { onInterrupted() }
    throw failure
}
