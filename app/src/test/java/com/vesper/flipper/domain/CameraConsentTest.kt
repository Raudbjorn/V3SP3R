package com.vesper.flipper.domain

import com.vesper.flipper.ai.PhotoCaptureCallback
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.CommandArgs
import com.vesper.flipper.domain.model.ExecuteCommand
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CameraConsentTest {
    private lateinit var executor: CommandExecutor
    private var captures = 0
    private val photo = ExecuteCommand(
        action = CommandAction.REQUEST_PHOTO,
        args = CommandArgs(photoPrompt = "Read this label"),
        justification = "Inspect label",
        expectedEffect = "Describe label"
    )

    @Before
    fun setup() {
        val permissions = PermissionService()
        executor = CommandExecutor(
            fileSystem = mock(), riskAssessor = RiskAssessor(permissions),
            permissionService = permissions, auditService = mock(), diffService = mock(),
            forgeEngine = mock(), settingsStore = mock {
                on { autoApproveMedium } doReturn flowOf(true)
                on { autoApproveHigh } doReturn flowOf(true)
            }
        )
        executor.setPhotoCaptureCallback(PhotoCaptureCallback { prompt, timeout ->
            assertEquals("Read this label", prompt)
            assertEquals(15_000L, timeout)
            captures++
            "A label"
        })
    }

    @Test
    fun `auto approve settings never authorize camera or cloud analysis`() = runTest {
        val pending = executor.execute(photo, "owner")
        assertTrue(pending.requiresConfirmation)
        assertEquals(0, captures)
        val approval = executor.getPendingApproval(requireNotNull(pending.pendingApprovalId))!!
        assertEquals(RiskLevel.HIGH, approval.riskAssessment.level)
        assertTrue(approval.riskAssessment.requiresConfirmation)
        assertTrue(approval.riskAssessment.reason.contains("cloud analysis"))
        val result = executor.approve(approval.id, "owner")
        assertTrue(result.success)
        assertEquals("A label", result.data?.content)
        assertEquals(1, captures)
        assertFalse(executor.approve(approval.id, "owner").success)
        assertEquals(1, captures)
        assertTrue(executor.execute(photo, "owner").requiresConfirmation)
        assertEquals(1, captures)
    }

    @Test
    fun `denial consumes consent without capturing`() = runTest {
        val id = requireNotNull(executor.execute(photo, "owner").pendingApprovalId)
        assertFalse(executor.reject(id, "owner").success)
        assertNull(executor.getPendingApproval(id))
        assertFalse(executor.approve(id, "owner").success)
        assertEquals(0, captures)
    }

    @Test
    fun `wrong session cannot approve reject or consume owner consent`() = runTest {
        val id = requireNotNull(executor.execute(photo, "owner").pendingApprovalId)
        assertFalse(executor.approve(id, "other").success)
        assertFalse(executor.reject(id, "other").success)
        assertEquals(0, captures)
        assertEquals(id, executor.currentApproval.value?.id)
        assertNotNull(executor.getPendingApproval(id))
        assertTrue(executor.approve(id, "owner").success)
        assertEquals(1, captures)
    }

    @Test
    fun `concurrent approval consumes token once before capture completes`() = runTest {
        executor.setPhotoCaptureCallback(PhotoCaptureCallback { _, _ ->
            captures++
            delay(100)
            "A label"
        })
        val id = requireNotNull(executor.execute(photo, "owner").pendingApprovalId)
        val first = async { executor.approve(id, "owner") }
        val second = async { executor.approve(id, "owner") }
        assertEquals(1, listOf(first.await(), second.await()).count { it.success })
        assertEquals(1, captures)
    }

    @Test
    fun `capture is cancelled after fifteen seconds and cannot replay`() = runTest {
        var cancelled = false
        executor.setPhotoCaptureCallback(PhotoCaptureCallback { _, _ ->
            captures++
            try {
                delay(15_001)
                "Too late"
            } finally {
                cancelled = true
            }
        })
        val id = requireNotNull(executor.execute(photo, "owner").pendingApprovalId)
        val result = executor.approve(id, "owner")
        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("timed out"))
        assertEquals(15_000L, testScheduler.currentTime)
        assertTrue(cancelled)
        assertFalse(executor.approve(id, "owner").success)
        assertEquals(1, captures)
    }

    @Test
    fun `disconnected camera fails after approval without reusable consent`() = runTest {
        executor.setPhotoCaptureCallback(null)
        val id = requireNotNull(executor.execute(photo, "owner").pendingApprovalId)
        assertFalse(executor.approve(id, "owner").success)
        assertNull(executor.getPendingApproval(id))
        assertEquals(0, captures)
    }
}
