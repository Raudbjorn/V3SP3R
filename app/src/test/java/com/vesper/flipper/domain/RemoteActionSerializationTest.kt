package com.vesper.flipper.domain

import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.ExecuteCommand
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RemoteActionSerializationTest {
    @Test fun `input tool wire fields survive command deserialization`() {
        val command = Json.decodeFromString<ExecuteCommand>("""{"action":"input_send","args":{"key":"back","press_type":"long"},"justification":"Navigate","expected_effect":"Back"}""")
        assertEquals(CommandAction.INPUT_SEND, command.action)
        assertEquals("back", command.args.key)
        assertEquals("long", command.args.pressType)
    }
    @Test fun `loader signal integer survives command deserialization`() {
        val command = Json.decodeFromString<ExecuteCommand>("""{"action":"loader_signal","args":{"signal_id":7},"justification":"Send app signal","expected_effect":"App handles signal"}""")
        assertEquals(CommandAction.LOADER_SIGNAL, command.action)
        assertEquals(7, command.args.signalId)
    }
}
