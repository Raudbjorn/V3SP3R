package com.vesper.flipper.ble

import org.junit.Assert.*
import org.junit.Test

class RpcAppSelectionBoundaryTest {
    @Test fun `hardware arguments cannot override the app selected by the assessed action`() {
        val protocol = FlipperProtocol()
        val commands = listOf("subghz tx /ext/subghz/test.sub", "ir tx /ext/infrared/test.ir",
            "nfc emulate /ext/nfc/test.nfc", "rfid emulate /ext/lfrfid/test.rfid", "ibutton emulate /ext/ibutton/test.ibtn")
        for (command in commands) {
            assertTrue(command, protocol.hasRpcAppCommandMapping(command))
            for (override in listOf("--app \"Bad USB\"", "--app=BadUSB", "--app-name BadUSB", "--app-name=BadUSB")) {
                assertFalse("$command $override", protocol.hasRpcAppCommandMapping("$command $override"))
            }
        }
    }
}
