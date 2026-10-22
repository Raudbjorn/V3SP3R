package com.vesper.flipper.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStreamCliIsolationTest {
    @Test fun `raw CLI fails explicitly before transport use while screen routing is active`() = runBlocking {
        val protocol = FlipperProtocol()
        protocol.setScreenFrameRouting(true)
        val result = protocol.sendRawCliCommand("loader close")
        assertTrue(result is ProtocolResponse.Error)
        assertTrue((result as ProtocolResponse.Error).message.contains("Pause the screen mirror"))
        protocol.onConnectionReset()
    }
}
