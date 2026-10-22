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
    @Test fun `owned BLE deadline returns audited failure and consumes approval once`() = runBlocking<Unit> {
        val settings = mock<SettingsStore>()
        whenever(settings.autoApproveHigh).thenReturn(flowOf(true))
        val audit = mock<com.vesper.flipper.domain.service.AuditService>()
        val peripheral = mock<BleReconService>()
        whenever(peripheral.writeCharacteristic(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(com.vesper.flipper.ble.BleOperationTimeoutException()))
        val executor = CommandExecutor(mock(), RiskAssessor(mock()), mock(), audit, mock(), mock(), settings,
            vulnTriage = mock(), appCatalog = mock())
        executor.bleRecon = peripheral
        val command = ExecuteCommand(CommandAction.BLE_WRITE_CHAR, CommandArgs(address="AA:BB:CC:DD:EE:FF",
            uuid="12345678-1234-1234-1234-123456789abc", content="01"), "Requested operation", "Write byte")
        val pending = executor.execute(command, "owner")
        val id = requireNotNull(pending.pendingApprovalId)
        val result = executor.approve(id, "owner")
        assertFalse(result.success)
        assertTrue(result.error!!.contains("outcome may be unknown"))
        assertNull(executor.currentApproval.value)
        assertFalse(executor.approve(id, "owner").success)
        verify(peripheral, times(1)).writeCharacteristic(any(), any(), any(), any(), any())
        val entries = argumentCaptor<AuditEntry>()
        verify(audit, atLeastOnce()).log(entries.capture())
        assertTrue(entries.allValues.any { it.actionType == AuditActionType.COMMAND_FAILED && it.sessionId == "owner" })
    }

    @Test fun `BLE mutations require session owned explicit approval despite automatic high approval`() = runBlocking {
        val settings = mock<SettingsStore>()
        whenever(settings.autoApproveHigh).thenReturn(flowOf(true))
        whenever(settings.autoApproveMedium).thenReturn(flowOf(true))
        val peripheral = mock<BleReconService>()
        val executor = CommandExecutor(mock(), RiskAssessor(mock()), mock(), mock(), mock(), mock(), settings, vulnTriage = mock(), appCatalog = mock())
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
