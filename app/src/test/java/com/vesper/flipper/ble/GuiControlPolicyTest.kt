package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper
import com.flipperdevices.protobuf.screen.Gui
import org.junit.Assert.*
import org.junit.Test

class GuiControlPolicyTest {
    @Test fun `uncertain action releases key and reports failure`() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<Gui.InputType>()
        val failure = GuiInputDelivery.send(Gui.InputType.SHORT) { step ->
            events += step
            if (step == Gui.InputType.SHORT) null else Flipper.CommandStatus.OK
        }
        assertEquals(Gui.InputType.SHORT, failure!!.step)
        assertEquals(listOf(Gui.InputType.PRESS, Gui.InputType.SHORT, Gui.InputType.RELEASE), events)
    }
    @Test fun `cancellation releases held key without swallowing cancellation`() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<Gui.InputType>()
        try {
            GuiInputDelivery.send(Gui.InputType.LONG) { step ->
                events += step
                if (step == Gui.InputType.LONG) throw kotlinx.coroutines.CancellationException("cancelled")
                Flipper.CommandStatus.OK
            }
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(listOf(Gui.InputType.PRESS, Gui.InputType.LONG, Gui.InputType.RELEASE), events)
        }
    }
    @Test fun `explicit successful press remains held until requested release`() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<Gui.InputType>()
        assertNull(GuiInputDelivery.send(Gui.InputType.PRESS) { step -> events += step; Flipper.CommandStatus.OK })
        assertEquals(listOf(Gui.InputType.PRESS), events)
    }

    @Test fun `screen lock empty RPC replies distinguish locked unlocked unsupported`() {
        assertEquals(DesktopLockStatus.Reading.Locked, DesktopLockStatus.interpret(Flipper.CommandStatus.OK, false, false))
        assertEquals(DesktopLockStatus.Reading.Unlocked, DesktopLockStatus.interpret(Flipper.CommandStatus.ERROR, false, true))
        assertEquals(DesktopLockStatus.Reading.Unsupported, DesktopLockStatus.interpret(Flipper.CommandStatus.ERROR_NOT_IMPLEMENTED, false, false))
        assertTrue(GuiRemoteInput.backWouldRelock(Gui.InputKey.BACK, true))
        assertFalse(GuiRemoteInput.backWouldRelock(Gui.InputKey.OK, true))
    }
    @Test fun `actions run while held and finish released`() {
        for (type in listOf(Gui.InputType.SHORT, Gui.InputType.LONG, Gui.InputType.REPEAT)) {
            assertEquals(listOf(Gui.InputType.PRESS, type, Gui.InputType.RELEASE), GuiRemoteInput.complementarySequence(type))
        }
        assertTrue(GuiRemoteInput.complementarySequence(Gui.InputType.UNRECOGNIZED).isEmpty())
    }
    @Test fun `flow control uses documented byte order and rejects malformed capacity`() {
        assertEquals(1024, SerialFlowControl.remainingBytes(byteArrayOf(0, 0, 4, 0)))
        assertEquals(0, SerialFlowControl.remainingBytes(ByteArray(4)))
        assertNull(SerialFlowControl.remainingBytes(byteArrayOf(0, 4, 0, 0)))
        assertNull(SerialFlowControl.remainingBytes(ByteArray(3)))
        assertNull(SerialFlowControl.remainingBytes(ByteArray(5)))
        assertNull(SerialFlowControl.remainingBytes(ByteArray(4) { 0xff.toByte() }))
        assertEquals(400L, SerialFlowControl.waitTimeoutMs(20, 2000))
        assertEquals(2000L, SerialFlowControl.waitTimeoutMs(512, 2000))
    }
}
