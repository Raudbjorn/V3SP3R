package com.vesper.flipper.ble

/** Serializes asynchronous GATT setup; no command can run before all required callbacks. */
internal class BleSetupSequence(
    private val requestMtu: () -> Boolean,
    private val discoverServices: () -> Boolean,
    private val enableRx: () -> Boolean,
    private val enableOverflow: () -> Boolean,
    private val readOverflow: () -> Boolean,
    private val ready: () -> Unit,
    private val failed: (String) -> Unit
) {
    enum class Stage { IDLE, MTU, SERVICES, RX, OVERFLOW_NOTIFY, OVERFLOW_READ, READY, FAILED }
    var stage = Stage.IDLE
        private set

    @Synchronized fun start() {
        if (stage != Stage.IDLE) return
        stage = Stage.MTU
        // MTU negotiation is optional. If Android rejects starting it, use the default MTU.
        if (!requestMtu()) mtuFinished()
    }

    @Synchronized fun mtuFinished() {
        if (stage != Stage.MTU) return
        begin(Stage.SERVICES, discoverServices)
    }

    @Synchronized fun servicesFinished() {
        if (stage != Stage.SERVICES) return
        begin(Stage.RX, enableRx)
    }

    @Synchronized fun rxFinished(success: Boolean, hasOverflow: Boolean) {
        if (stage != Stage.RX) return
        if (!success) return fail("RX notification subscription failed")
        if (hasOverflow) begin(Stage.OVERFLOW_NOTIFY, enableOverflow) else finish()
    }

    @Synchronized fun overflowNotificationsFinished(success: Boolean) {
        if (stage != Stage.OVERFLOW_NOTIFY) return
        if (!success) return fail("Flow-control notification subscription failed")
        begin(Stage.OVERFLOW_READ, readOverflow)
    }

    @Synchronized fun overflowReadFinished(success: Boolean) {
        if (stage != Stage.OVERFLOW_READ) return
        if (!success) return fail("Initial flow-control capacity read failed")
        finish()
    }

    @Synchronized fun timeout() {
        if (stage != Stage.READY && stage != Stage.FAILED && stage != Stage.IDLE) {
            fail("Bluetooth setup timed out during $stage; reconnect the unlocked Flipper")
        }
    }

    @Synchronized fun cancel() { stage = Stage.FAILED }

    @Synchronized fun fail(message: String) {
        if (stage == Stage.READY || stage == Stage.FAILED) return
        stage = Stage.FAILED
        failed(message)
    }

    private fun begin(next: Stage, operation: () -> Boolean) {
        stage = next
        if (!operation()) fail("Android could not start Bluetooth setup step $next")
    }

    private fun finish() {
        stage = Stage.READY
        ready()
    }
}
