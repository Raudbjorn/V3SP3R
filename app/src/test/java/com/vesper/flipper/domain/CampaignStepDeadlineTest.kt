package com.vesper.flipper.domain

import com.vesper.flipper.domain.ralph.executeCampaignStep
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CampaignStepDeadlineTest {
    @Test fun `claim or readiness latency cannot start a command after phase expiry`() = runBlocking<Unit> {
        for (expireDuringClaim in listOf(true, false)) {
            var now = 0L
            var executed = false
            var claimed = false
            try {
                executeCampaignStep(100,
                    claim = { claimed = true; if (expireDuringClaim) now = 100; true },
                    ready = { if (!expireDuringClaim) now = 101; true },
                    now = { now }
                ) { executed = true }
                fail("Expired phase must refuse execution")
            } catch (_: TimeoutCancellationException) { }
            assertTrue(claimed)
            assertFalse(executed)
        }
    }

    @Test fun `stopped campaign or lost claim never invokes executor`() = runBlocking<Unit> {
        for (claimWins in listOf(false, true)) {
            var executed = false
            try {
                executeCampaignStep(100, claim = { claimWins }, ready = { false }, now = { 0 }) { executed = true }
                fail("Unclaimed or stopped campaign must not execute")
            } catch (_: IllegalStateException) { }
            assertFalse(executed)
        }
    }

    @Test fun `live claimed step invokes executor once`() = runBlocking<Unit> {
        var executions = 0
        assertEquals("done", executeCampaignStep(1000, claim = { true }, ready = { true }, now = { 0 }) {
            executions++
            "done"
        })
        assertEquals(1, executions)
    }
}
