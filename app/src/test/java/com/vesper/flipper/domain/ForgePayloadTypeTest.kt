package com.vesper.flipper.domain

import com.vesper.flipper.ai.OpenRouterClient
import com.vesper.flipper.domain.executor.ForgeEngine
import com.vesper.flipper.domain.model.PayloadType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class ForgePayloadTypeTest {
    @Test fun callerCancellationDoesNotProduceFallbackSuccess() = runBlocking<Unit> {
        val client = mock<OpenRouterClient>()
        whenever(client.chatSimple(any())).thenThrow(kotlinx.coroutines.CancellationException("cancelled"))
        try {
            ForgeEngine(client).forge("requested demo", "bad_usb")
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun canonicalTypesAndLegacyAliasesSelectTheActualFormat() = runBlocking<Unit> {
        val client = mock<OpenRouterClient>()
        val engine = ForgeEngine(client)
        for ((names, type) in listOf(
            listOf("bad_usb", "badusb") to PayloadType.BAD_USB,
            listOf("sub_ghz", "subghz") to PayloadType.SUB_GHZ,
            listOf("infrared", "ir") to PayloadType.INFRARED,
            listOf("nfc") to PayloadType.NFC
        )) for (name in names) {
            val offline = engine.fallback("requested demo", name)
            assertEquals(name, type, offline.blueprint.payloadType)
            assertTrue(offline.blueprint.flipperPath.endsWith(type.extension))
            if (type == PayloadType.BAD_USB) assertTrue(offline.blueprint.generatedCode.contains("STRING "))
            val onlineFallback = engine.forge("requested demo", name)
            assertEquals(name, type, onlineFallback.blueprint.payloadType)
        }
        val prompts = argumentCaptor<String>()
        verify(client, times(7)).chatSimple(prompts.capture())
        assertTrue(prompts.allValues.all { !it.contains("DETECTED TYPE: UNKNOWN") })
    }
}
