package com.vesper.flipper.domain
import com.vesper.flipper.domain.ralph.*
import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class CampaignPolicyTest {
    private fun plan(action:CommandAction=CommandAction.READ_FILE,path:String="/ext/nfc/test.nfc") = CampaignPlan("Owned inventory","/ext/nfc",100_000,listOf(CampaignPhase("Read",listOf(ExecuteCommand(action,CommandArgs(path=path),"Requested","Read inventory")))))
    @Test fun boundedScopedReadIsAccepted() { CampaignPolicy.validate(plan(),1) }
    @Test fun siblingPrefixAndTraversalAreRejected() {
        assertFalse(CampaignPolicy.inScope("/ext/nfc-other/a","/ext/nfc"))
        assertFalse(CampaignPolicy.inScope("/ext/nfc/../secret","/ext/nfc"))
        assertThrows(IllegalArgumentException::class.java) { CampaignPolicy.validate(plan(path="/ext/nfc-other/a"),1) }
    }
    @Test fun rawAndNestedCommandsAreRejected() {
        for(action in listOf(CommandAction.EXECUTE_CLI,CommandAction.RUN_RUNBOOK)) assertThrows(IllegalArgumentException::class.java) { CampaignPolicy.validate(plan(action),1) }
    }
    @Test fun expiredPlansAndExcessStepsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CampaignPolicy.validate(plan(),100_000) }
        assertThrows(IllegalArgumentException::class.java) { CampaignPolicy.validate(plan().copy(phases=listOf(plan().phases.single().copy(maxSteps=0))),1) }
    }
}
