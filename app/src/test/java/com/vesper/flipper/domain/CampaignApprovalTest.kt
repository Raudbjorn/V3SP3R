package com.vesper.flipper.domain

import com.vesper.flipper.domain.ralph.*
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class CampaignApprovalTest {
    private val command = ExecuteCommand(CommandAction.WRITE_FILE, CommandArgs(path="/ext/nfc/test", content=""), "Requested", "Create empty file")

    @Test fun processLostExpiredOrDifferentApprovalCannotAuthorizeCommand() {
        val pending = PendingApproval(id="shown", command=command, riskAssessment=mock(), expiresAt=100)
        assertTrue(approvalAvailable(pending, "shown", command, 99))
        assertFalse(approvalAvailable(null, "shown", command, 99))
        assertFalse(approvalAvailable(pending, "shown", command, 100))
        assertFalse(approvalAvailable(pending, "next", command, 99))
        assertFalse(approvalAvailable(pending, "shown", command.copy(args=command.args.copy(content="different")), 99))
    }

    @Test fun expiredDeadlineNeverStartsApprovalAndStillRetiresClaim() = runBlocking {
        for (remaining in listOf(0L, -1L)) {
            var executed = false
            var retired = false
            try {
                executeCampaignApproval(remaining, onInterrupted={ retired=true }) { executed=true }
                fail("Expired approval should time out")
            } catch (_: TimeoutCancellationException) { }
            assertFalse("Must not execute with remaining=$remaining", executed)
            assertTrue("Must retire claim with remaining=$remaining", retired)
        }
    }

    @Test fun timeoutPersistsFailureBeforeReturningAndCannotContinue() = runBlocking {
        var state = "approving"
        var continued = false
        try {
            executeCampaignApproval(20, onInterrupted={ delay(1); state="failed" }) { awaitCancellation() }
            continued = true
        } catch (_: TimeoutCancellationException) { }
        assertEquals("failed", state)
        assertFalse(continued)
        assertFalse(CampaignTransitions.allows(state, "queued"))
    }

    @Test fun callerCancellationStillCompletesDurableFailureCallback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var persisted = false
        val task = launch {
            executeCampaignApproval(10_000, onInterrupted={ delay(1); persisted=true }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        task.cancelAndJoin()
        assertTrue(persisted)
    }

    @Test fun successfulApprovalDoesNotMarkOutcomeUncertain() = runBlocking {
        var failed = false
        assertEquals("complete", executeCampaignApproval(1000, onInterrupted={ failed=true }) { "complete" })
        assertFalse(failed)
    }
}
