package com.vesper.flipper.ble

import com.flipperdevices.protobuf.screen.Gui

object GuiRemoteInput {
    fun complementarySequence(type: Gui.InputType): List<Gui.InputType> = when (type) {
        Gui.InputType.SHORT, Gui.InputType.LONG, Gui.InputType.REPEAT ->
            listOf(Gui.InputType.PRESS, type, Gui.InputType.RELEASE)
        Gui.InputType.PRESS, Gui.InputType.RELEASE -> listOf(type)
        else -> emptyList()
    }
    fun mayRetry(type: Gui.InputType) = type == Gui.InputType.RELEASE
    fun backWouldRelock(key: Gui.InputKey, unlockedAtMs: Long, nowMs: Long): Boolean =
        key == Gui.InputKey.BACK && unlockedAtMs > 0 && nowMs - unlockedAtMs in 0L until 3_000L
}

/** Acknowledged GUI gesture delivery, including release after uncertain writes. */
internal object GuiInputDelivery {
    data class Failure(val step: Gui.InputType, val status: com.flipperdevices.protobuf.Flipper.CommandStatus?)

    suspend fun send(type: Gui.InputType, cleanupTimeoutMs: Long = 5_000L, sender: suspend (Gui.InputType) -> com.flipperdevices.protobuf.Flipper.CommandStatus?): Failure? {
        val sequence = GuiRemoteInput.complementarySequence(type)
        require(sequence.isNotEmpty()) { "Unsupported GUI input" }
        var held = false
        var completed = false
        try {
            for (step in sequence) {
                if (step == Gui.InputType.PRESS) held = true
                val status = sender(step)
                if (status != com.flipperdevices.protobuf.Flipper.CommandStatus.OK) return Failure(step, status)
                if (step == Gui.InputType.RELEASE) held = false
            }
            completed = true
            return null
        } finally {
            if (held && (!completed || type != Gui.InputType.PRESS)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    // Preserve the original failure/cancellation if cleanup itself fails.
                    kotlinx.coroutines.withTimeoutOrNull(cleanupTimeoutMs) { runCatching { sender(Gui.InputType.RELEASE) } }
                }
            }
        }
    }
}
