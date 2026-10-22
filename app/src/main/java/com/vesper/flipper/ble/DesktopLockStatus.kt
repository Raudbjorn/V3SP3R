package com.vesper.flipper.ble

import com.flipperdevices.protobuf.Flipper

/**
 * `desktop_is_locked` answers with an empty message. OK means the lock flag
 * is set. ERROR means it is clear. A [hasDesktopStatus] body is the separate
 * status subscription, not this reply.
 */
object DesktopLockStatus {
    enum class Reading {
        Locked,
        Unlocked,
        Unsupported
    }

    fun interpret(
        commandStatus: Flipper.CommandStatus,
        hasDesktopStatus: Boolean,
        lockedFlag: Boolean
    ): Reading {
        if (hasDesktopStatus) {
            return if (lockedFlag) Reading.Locked else Reading.Unlocked
        }
        return when (commandStatus) {
            Flipper.CommandStatus.OK -> Reading.Locked
            Flipper.CommandStatus.ERROR -> Reading.Unlocked
            else -> Reading.Unsupported
        }
    }
}
