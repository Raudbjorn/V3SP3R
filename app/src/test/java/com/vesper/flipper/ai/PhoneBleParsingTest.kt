package com.vesper.flipper.ai

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock

class PhoneBleParsingTest {
    private val client = OpenRouterClient(mock())
    @Test fun `strict and fallback parsers require BLE targets and payload`() {
        for(action in listOf("ble_enumerate","ble_read_char","ble_write_char","ble_subscribe")) {
            for(extra in listOf("",",\"justification\":\"Authorized test\",\"expected_effect\":\"Test\"")) {
                val parsed=client.parseCommandDetailed("{\"action\":\"$action\",\"args\":{}$extra}")
                assertNull(action,parsed.command)
                assertTrue(parsed.error.orEmpty(),parsed.error.orEmpty().contains("address"))
            }
        }
    }
    @Test fun `valid write preserves explicit bytes and mode`() {
        val parsed=client.parseCommandDetailed("""{"action":"ble_write_char","args":{"address":"AA:BB:CC:DD:EE:FF","uuid":"00002a19-0000-1000-8000-00805f9b34fb","content":"01","hex":true,"with_response":false},"justification":"Authorized test","expected_effect":"Test"}""")
        assertNotNull(parsed.error,parsed.command)
        assertEquals("01",parsed.command!!.args.content)
        assertEquals(false,parsed.command!!.args.withResponse)
    }
}
