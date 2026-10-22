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
    @Test fun exportRejectsUnsupportedFrequencyAndExpandedRawEnvelope() {
        val project = SignalAlchemist.importSubGhzRaw(raw("1 -1"))!!
        for (frequency in listOf(0L, -1L, 1L, 279999999L, 930000001L, 1000000001L)) {
            assertNull(SignalAlchemist.importSubGhzRaw(raw("1 -1").replace("433920000", frequency.toString())))
            assertThrows(IllegalArgumentException::class.java) { SignalAlchemist.exportToFlipperFormat(project.copy(frequency = frequency)) }
        }
        val repeated = project.copy(layers = listOf(project.layers[0].copy(rawTimings = List(10000) { if (it % 2 == 0) 1 else -1 }, timing = TimingConfig(repeatCount = 2))))
        assertThrows(IllegalArgumentException::class.java) { SignalAlchemist.exportToFlipperFormat(repeated) }
        val long = project.copy(layers = listOf(project.layers[0].copy(rawTimings = listOf(10000000, -10000000), timing = TimingConfig(repeatCount = 100))))
        assertThrows(IllegalArgumentException::class.java) { SignalAlchemist.exportToFlipperFormat(long) }
        assertThrows(IllegalArgumentException::class.java) { SignalAlchemist.exportToFlipperFormat(project.copy(modulation = ModulationType.CUSTOM)) }
    }

    @Test fun unknownHeadersAreRejectedWithoutAccumulation() {
        assertNull(SignalAlchemist.importSubGhzRaw(raw("1 -1") + "\nArbitrary: metadata"))
        assertNull(SignalAlchemist.importSubGhzRaw(raw("1 -1") + "\nPreset: FuriHalSubGhzPresetOok650Async"))
    }

    @Test fun consecutiveLevelDurationsRoundTripWithoutChangingSignal() {
        val project = SignalAlchemist.importSubGhzRaw(raw("100 200 -300\nRAW_Data: -400 500"))!!
        val reopened = SignalAlchemist.importSubGhzRaw(SignalAlchemist.exportToFlipperFormat(project))!!
        assertEquals(listOf(100, 200, -300, -400, 500), SignalAlchemist.synthesize(reopened))
    }

}
