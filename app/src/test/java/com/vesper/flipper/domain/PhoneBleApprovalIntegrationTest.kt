package com.vesper.flipper.domain

import com.vesper.flipper.ble.BleReconService
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class PhoneBleApprovalIntegrationTest {
    @Test fun `BLE mutations require session owned explicit approval despite automatic high approval`() = runBlocking {
        val settings = mock<SettingsStore>()
        whenever(settings.autoApproveHigh).thenReturn(flowOf(true))
        whenever(settings.autoApproveMedium).thenReturn(flowOf(true))
        val peripheral = mock<BleReconService>()
        val executor = CommandExecutor(mock(), RiskAssessor(mock()), mock(), mock(), mock(), mock(), settings, mock())
        executor.bleRecon = peripheral
        for (action in listOf(CommandAction.BLE_WRITE_CHAR, CommandAction.BLE_SUBSCRIBE)) {
            val args = CommandArgs(address = "AA:BB:CC:DD:EE:FF",
                uuid = "12345678-1234-1234-1234-123456789abc", content = "01", duration = 2.0)
            val result = executor.execute(ExecuteCommand(action, args, "Authorized test", "Peripheral operation"), "owner")
            assertTrue(result.requiresConfirmation)
            val id = requireNotNull(result.pendingApprovalId)
            val approval = requireNotNull(executor.getPendingApproval(id))
            assertTrue(approval.riskAssessment.reason.contains(args.address!!))
            assertTrue(approval.riskAssessment.reason.contains(args.uuid!!))
            assertFalse(executor.approve(id, "another-session").success)
            assertNotNull(executor.getPendingApproval(id))
            executor.reject(id, "owner")
            assertNull(executor.getPendingApproval(id))
            assertFalse(executor.approve(id, "owner").success)
        }
        verifyNoInteractions(peripheral)
    }
}
