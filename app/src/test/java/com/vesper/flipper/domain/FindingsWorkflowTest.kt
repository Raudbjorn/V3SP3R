package com.vesper.flipper.domain
import com.vesper.flipper.domain.service.VulnTriageService
import com.vesper.flipper.data.database.*
import com.vesper.flipper.domain.executor.TypedDeviceCommands
import com.vesper.flipper.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
class FindingsWorkflowTest {
    @Test fun submissionRequiresEvidenceAndBoundedComplexity() = runBlocking {
        val dao=mock<VulnDao>(); val service=VulnTriageService(dao)
        try { service.submit(VulnTriageService.SubmitRequest("device","info_disclosure","Observed banner","",VulnTriageService.Severity.LOW,1), "session-a"); fail("must reject") } catch(_:IllegalArgumentException) { }
        verifyNoInteractions(dao)
    }
    @Test fun classificationDoesNotTreatFragmentsAsEvidence() = runBlocking {
        val service=VulnTriageService(mock())
        assertEquals(VulnTriageService.Severity.MEDIUM,service.classify("r",null,"session-a").severity)
        assertEquals(VulnTriageService.Severity.CRITICAL,service.classify("rce",null,"session-a").severity)
    }
    @Test fun failedReproductionIsInconclusiveNotDisproof() = runBlocking {
        val dao=mock<VulnDao>();val service=VulnTriageService(dao)
        val entity=VulnFindingEntity("full-id",1,"owned-device","info_disclosure","Observed","banner","low",1,"confirmed")
        whenever(dao.getById("full-id", "session-a")).thenReturn(entity)
        val updated=service.validate(VulnTriageService.ValidateRequest("full-id",false,"Could not reproduce"), "session-a")!!
        assertEquals("inconclusive",updated.status);assertEquals(1,updated.reproductionAttempts)
        assertTrue(updated.validationNotesJson.contains("Could not reproduce"))
    }
    @Test fun typedTemplatesRejectInjectedArguments() {
        assertThrows(IllegalArgumentException::class.java) { TypedDeviceCommands.render(CommandAction.GPIO_SET,CommandArgs(pin="PA7; reboot",value=1)) }
        assertThrows(IllegalArgumentException::class.java) { TypedDeviceCommands.render(CommandAction.GPIO_SET,CommandArgs(pin="PA7",value=2)) }
        assertEquals("gpio set PA7 1",TypedDeviceCommands.render(CommandAction.GPIO_SET,CommandArgs(pin="PA7",value=1)))
    }
}
