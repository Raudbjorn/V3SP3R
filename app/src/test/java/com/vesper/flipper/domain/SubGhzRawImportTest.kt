package com.vesper.flipper.domain

import com.vesper.flipper.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class SubGhzRawImportTest {
    private fun raw(pulses: String) = """
        Filetype: Flipper SubGhz RAW File
        Version: 1
        Frequency: 433920000
        Preset: FuriHalSubGhzPresetOok650Async
        Protocol: RAW
        RAW_Data: $pulses
    """.trimIndent()

    @Test fun preservesUnevenMultilinePulsesAndExport() {
        val project = SignalAlchemist.importSubGhzRaw(raw("123 -456\nRAW_Data: 789 -1000"))!!
        assertEquals(listOf(123, -456, 789, -1000), SignalAlchemist.synthesize(project))
        val roundTrip = SignalAlchemist.importSubGhzRaw(SignalAlchemist.exportToFlipperFormat(project))!!
        assertEquals(project.frequency, roundTrip.frequency)
        assertEquals(SignalAlchemist.synthesize(project), SignalAlchemist.synthesize(roundTrip))
    }

    @Test fun rejectsMalformedZeroOverflowAndHugeInputs() {
        for (pulses in listOf("123 nope -456", "0 -100", "-2147483648 100", "2147483648 -100", "10000001 -100", "1")) {
            assertNull(pulses, SignalAlchemist.importSubGhzRaw(raw(pulses)))
        }
        assertNull(SignalAlchemist.importSubGhzRaw(raw("1 -1 ".repeat(8193))))
        assertNull(SignalAlchemist.importSubGhzRaw(" ".repeat(SignalAlchemist.MAX_RAW_CHARACTERS + 1)))
        assertNull(SignalAlchemist.importSubGhzRaw(raw("1 -1").replace("433920000", "-1")))
        assertNull(SignalAlchemist.importSubGhzRaw(raw("1 -1").replace("Ook650Async", "Custom")))
    }

    @Test fun previewUsesTimeAndIncludesTailAfterManyShortPulses() {
        val project = SignalAlchemist.importSubGhzRaw(raw("1 -1 ".repeat(1000) + "9998000"))!!
        val preview = SignalAlchemist.generateWaveformPreview(project, 300)
        assertEquals(300, preview.size)
        assertEquals(1f, preview.last())
    }

    @Test fun editedRawLayerExportsEditsAndHonorsDisable() {
        val project = SignalAlchemist.importSubGhzRaw(raw("123 -456"))!!
        val edited = project.copy(layers = listOf(project.layers[0].copy(rawTimings = listOf(321, -654))))
        assertEquals(listOf(321, -654), SignalAlchemist.synthesize(edited))
        assertTrue(SignalAlchemist.synthesize(project.copy(layers = listOf(project.layers[0].copy(enabled = false)))).isEmpty())
    }

    @Test fun repeatExpansionIsBounded() {
        val project = SignalAlchemist.importSubGhzRaw(raw("1 -1 ".repeat(8192)))!!
        val huge = project.copy(layers = listOf(project.layers[0].copy(timing = TimingConfig(repeatCount = 100))))
        assertThrows(IllegalArgumentException::class.java) { SignalAlchemist.synthesize(huge) }
    }
}
