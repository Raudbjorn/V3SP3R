package com.vesper.flipper.domain
import com.vesper.flipper.domain.ralph.CampaignTransitions
import org.junit.Assert.*
import org.junit.Test
class CampaignTransitionsTest {
    @Test fun killedOrFailedCampaignsCannotBeResurrectedByLateCompletion() {
        for(state in listOf("cancelled","completed","failed")) {
            assertFalse(CampaignTransitions.allows(state,"queued"))
            assertFalse(CampaignTransitions.allows(state,"executing"))
        }
    }
    @Test fun approvalIsClaimedBeforeExecutionAndCannotBeClaimedTwice() {
        assertTrue(CampaignTransitions.allows("approval","approving"))
        assertFalse(CampaignTransitions.allows("approving","approving"))
        assertFalse(CampaignTransitions.allows("approval","queued"))
        assertTrue(CampaignTransitions.allows("approving","queued"))
    }
    @Test fun disableOrEitherExpiryPreventsApproval() {
        assertTrue(CampaignTransitions.canApprove(true,"approval",99,100,200))
        assertFalse(CampaignTransitions.canApprove(false,"approval",99,100,200))
        assertFalse(CampaignTransitions.canApprove(true,"approval",100,100,200))
        assertFalse(CampaignTransitions.canApprove(true,"approval",100,200,100))
        assertFalse(CampaignTransitions.canApprove(true,"cancelled",99,100,200))
    }
}
