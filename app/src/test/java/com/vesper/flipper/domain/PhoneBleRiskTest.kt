package com.vesper.flipper.domain
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
class PhoneBleRiskTest {
    @Test fun writesAndNotificationSubscriptionsRequireApproval() {
        val assessor = RiskAssessor(mock())
        for(action in listOf(CommandAction.BLE_WRITE_CHAR,CommandAction.BLE_SUBSCRIBE)) {
            val result=assessor.assess(ExecuteCommand(action,CommandArgs(address="AA:BB:CC:DD:EE:FF"),"Authorized test","State change"))
            assertEquals(RiskLevel.HIGH,result.level)
            assertTrue(result.requiresConfirmation)
        }
    }
    @Test fun readsDoNotDependOnFlipperConnection() {
        val result=RiskAssessor(mock()).assess(ExecuteCommand(CommandAction.BLE_READ_CHAR,CommandArgs(),"Read peripheral","Read only"))
        assertEquals(RiskLevel.LOW,result.level)
    }
}
