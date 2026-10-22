package com.vesper.flipper.voice

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechAudioTest {
    private fun int(value:Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun chunk(name:String, bytes:ByteArray) = name.toByteArray() + int(bytes.size) + bytes + if(bytes.size%2==1) byteArrayOf(0) else byteArrayOf()
    private fun wave():ByteArray {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16).array()
        val payload = "WAVE".toByteArray() + chunk("JUNK",byteArrayOf(9)) + chunk("fmt ",fmt) + chunk("data",byteArrayOf(1,2,3,4))
        return "RIFF".toByteArray()+int(payload.size)+payload
    }
    @Test fun wavUsesLocatedFormatAndSkipsOddPaddedChunks() {
        val (pcm, rate) = SpeechAudio.wavPcm(wave())
        assertEquals(24000,rate);assertArrayEquals(byteArrayOf(1,2,3,4),pcm)
    }
    @Test fun rejectsTruncatedAndOversizedWaveChunks() {
        for (bytes in listOf(wave().dropLast(1).toByteArray(), wave().also { int(Int.MAX_VALUE).copyInto(it,16) })) {
            try { SpeechAudio.wavPcm(bytes);fail("Invalid WAV accepted") } catch (_:IllegalArgumentException) { }
        }
    }
    @Test fun geminiHandlesWavMetadataAndRejectsOversizeEncodedAudio() {
        fun payload(encoded:String) = """{"candidates":[{"content":{"parts":[{"inlineData":{"data":"$encoded"}}]}}]}"""
        assertArrayEquals(byteArrayOf(1,2,3,4),SpeechAudio.geminiPcm(payload(Base64.getEncoder().encodeToString(wave()))))
        try { SpeechAudio.geminiPcm(payload("A".repeat(SpeechAudio.MAX_AUDIO_BYTES*4/3+32)));fail("oversize") } catch (_:IllegalArgumentException) { }
    }
    @Test fun drainWaitsForAllQueuedFramesAndCancellationInterruptsIt() = runTest {
        var played=0L
        val drain=launch { drainAudio(10) { played } }
        runCurrent();assertFalse(drain.isCompleted)
        played=10;advanceTimeBy(10);runCurrent();assertTrue(drain.isCompleted)
        val cancelled=launch { drainAudio(10) { 0 } }
        runCurrent();cancelled.cancelAndJoin();assertTrue(cancelled.isCancelled)
    }
}
