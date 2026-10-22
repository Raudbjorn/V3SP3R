package com.vesper.flipper.domain

import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class PhoneBleApprovalDetailsTest {
    @Test fun `write approval identifies target exact bytes and mode`() {
        val args = CommandArgs(address="AA:BB:CC:DD:EE:FF", uuid="12345678-1234-1234-1234-123456789abc", content="Hi", hex=false, withResponse=false)
        val reason = RiskAssessor(mock()).assess(ExecuteCommand(CommandAction.BLE_WRITE_CHAR,args,"Authorized test","Write")).reason
        for (detail in listOf(args.address!!,args.uuid!!,"4869","without response")) assertTrue(detail,reason.contains(detail))
    }
    @Test fun `subscription approval identifies effective duration`() {
        val args = CommandArgs(address="AA:BB:CC:DD:EE:FF",uuid="12345678-1234-1234-1234-123456789abc",duration=12.5)
        val reason = RiskAssessor(mock()).assess(ExecuteCommand(CommandAction.BLE_SUBSCRIBE,args,"Authorized test","Collect")).reason
        for(detail in listOf(args.address!!,args.uuid!!,"12.5 seconds")) assertTrue(detail,reason.contains(detail))
    }
}
