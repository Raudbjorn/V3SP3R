package com.vesper.flipper.domain

import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.data.database.*
import com.vesper.flipper.domain.executor.*
import com.vesper.flipper.domain.model.*
import com.vesper.flipper.domain.service.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class FindingsIsolationTest {
    private class MemoryDao : VulnDao {
        val rows = linkedMapOf<String, VulnFindingEntity>()
        override suspend fun countAll() = rows.size
        override suspend fun upsert(finding: VulnFindingEntity) { rows[finding.id] = finding }
        override suspend fun getById(id: String, sessionId: String) = rows[id]?.takeIf { it.sessionId == sessionId }
        override suspend fun queryFiltered(sessionId: String, severity: String?, status: String?) = rows.values.filter {
            it.sessionId == sessionId && (severity == null || it.severity == severity) && (status == null || it.status == status)
        }
        override suspend fun getAll() = rows.values.toList()
        override suspend fun deleteAll() { rows.clear() }
    }
    private fun request() = VulnTriageService.SubmitRequest("Ä-device", "info_disclosure", "Observed banner", "version evidence", VulnTriageService.Severity.LOW, 1)

    @Test fun sessionsCannotReadOrMutateEachOthersFindingsAndUnicodeSearchWorks() = runBlocking<Unit> {
        val dao = MemoryDao(); val service = VulnTriageService(dao)
        val finding = service.submit(request(), "owner")
        assertTrue(service.list(VulnTriageService.ListFilters(null,null,null), "other").isEmpty())
        assertNull(service.validate(VulnTriageService.ValidateRequest(finding.id,true,null), "other"))
        try { service.classify("info_disclosure",finding.id,"other"); fail("must reject") } catch (_:IllegalArgumentException) {}
        assertEquals("submitted",dao.rows[finding.id]!!.status)
        assertEquals(listOf(finding), service.list(VulnTriageService.ListFilters("ä-DEVICE",null,null), "owner"))
    }

    @Test fun classificationCannotBorrowAnUnrelatedType() = runBlocking<Unit> {
        val dao=MemoryDao(); val service=VulnTriageService(dao); val finding=service.submit(request(),"owner")
        try { service.classify("rce",finding.id,"owner"); fail("must reject") } catch (_:IllegalArgumentException) {}
        assertEquals("low",dao.rows[finding.id]!!.severity)
    }

    @Test fun retentionRefusesNewRecordsWithoutDeletingOldEvidence() = runBlocking<Unit> {
        val dao=MemoryDao(); val service=VulnTriageService(dao)
        repeat(500) { service.submit(request(),"owner") }
        try { service.submit(request(),"owner"); fail("must reject quota") } catch (_:IllegalArgumentException) {}
        assertEquals(500,dao.rows.size)
    }

    @Test fun validationHistoryHasAnAggregateByteBound() = runBlocking<Unit> {
        val dao=MemoryDao(); val service=VulnTriageService(dao); val finding=service.submit(request(),"owner")
        val notes="x".repeat(8192)
        repeat(7) { service.validate(VulnTriageService.ValidateRequest(finding.id,true,notes),"owner") }
        try { service.validate(VulnTriageService.ValidateRequest(finding.id,true,notes),"owner"); fail("must reject full history") } catch (_:IllegalArgumentException) {}
        assertEquals(7,dao.rows[finding.id]!!.reproductionAttempts)
        assertTrue(dao.rows[finding.id]!!.validationNotesJson.toByteArray().size <= 65536)
    }

    @Test fun auditQueriesPassRequiredSessionToDatabase() = runBlocking<Unit> {
        val dao=mock<AuditDao>(); whenever(dao.queryFiltered("owner",20,null,null)).thenReturn(emptyList())
        assertTrue(AuditService(dao).queryRecent("owner",20,null,null).isEmpty())
        verify(dao).queryFiltered("owner",20,null,null)
    }

    @Test fun requiredArgumentsRejectEmptyCallsButAcceptFalseAndZero() {
        val client=OpenRouterClient(mock())
        for(action in listOf(CommandAction.VULN_SUBMIT,CommandAction.VULN_VALIDATE,CommandAction.VULN_CLASSIFY,CommandAction.GPIO_READ,CommandAction.GPIO_SET,CommandAction.GPIO_MODE,CommandAction.RFID_WRITE,CommandAction.BADUSB_VALIDATE,CommandAction.BADUSB_DIFF)) {
            assertFalse(action.name,client.missingRequiredArgs(action,CommandArgs()).isEmpty())
            assertNull(client.parseCommandDetailed("""{"action":"${action.name.lowercase()}","args":{},"justification":"test","expected_effect":"test"}""").command)
            assertNull(client.parseCommandDetailed("""{"action":"${action.name.lowercase()}","args":{}}""").command)
        }
        assertTrue(client.missingRequiredArgs(CommandAction.VULN_VALIDATE,CommandArgs(vulnId="id",reproduced=false)).isEmpty())
        assertTrue(client.missingRequiredArgs(CommandAction.GPIO_SET,CommandArgs(pin="PA7",value=0)).isEmpty())
    }

    @Test fun validationRequiresExplicitApprovalEvenWhenAutoApproveEnabled() = runBlocking<Unit> {
        val triage=mock<VulnTriageService>();val permissions=mock<PermissionService>()
        val executor=CommandExecutor(mock(),RiskAssessor(permissions),permissions,mock(),mock(),mock(),mock {
            on { autoApproveMedium } doReturn flowOf(true)
            on { autoApproveHigh } doReturn flowOf(true)
        },triage)
        val command=ExecuteCommand(CommandAction.VULN_VALIDATE,CommandArgs(vulnId="id",reproduced=true),"Record observation","Change stored evidence")
        val pending=executor.execute(command,"owner")
        assertTrue(pending.requiresConfirmation); verifyNoInteractions(triage)
        val wrong=executor.approve(pending.pendingApprovalId!!,"other")
        assertFalse(wrong.success);verifyNoInteractions(triage)
        assertNotNull(executor.getPendingApproval(pending.pendingApprovalId!!))
    }
}
