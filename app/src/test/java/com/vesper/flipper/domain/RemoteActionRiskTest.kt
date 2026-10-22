package com.vesper.flipper.domain

import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.PermissionService
import org.junit.Assert.*
import org.junit.Test

class RemoteActionRiskTest {
    private val assessor = RiskAssessor(PermissionService())
    @Test fun `input and app signals require approval for unknown foreground actions`() {
        for (action in listOf(CommandAction.INPUT_SEND, CommandAction.LOADER_SIGNAL)) {
            val assessment = assessor.assess(ExecuteCommand(action, CommandArgs(), "test", "test"))
            assertEquals(RiskLevel.HIGH, assessment.level)
            assertTrue(assessment.requiresConfirmation)
        }
    }
    @Test fun `closing app requires approval while listing remains read only`() {
        assertTrue(assessor.assess(ExecuteCommand(CommandAction.LOADER_CLOSE, CommandArgs(), "test", "test")).requiresConfirmation)
        val list = assessor.assess(ExecuteCommand(CommandAction.LOADER_LIST, CommandArgs(), "test", "test"))
        assertEquals(RiskLevel.LOW, list.level)
        assertFalse(list.requiresConfirmation)
    }
}
