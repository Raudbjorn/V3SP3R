package com.vesper.flipper.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the transport gate that decides whether a hardware command may run.
 *
 * The bug this fixes: on BLE-only firmware (Momentum reports "RPC-only") the
 * capability probe cannot reach the serial CLI, so supportsRpc is derived as
 * false even though RPC works — and every connection reset clears it to false
 * too. The old gate refused any command in that state, so transmit / emulate /
 * launch were blocked before the RPC bridge was ever tried, surfacing as
 * "automation transport unavailable" while RPC was in fact live.
 *
 * The rule now: a command with an RPC mapping is routed via the bridge whenever
 * the transport is not explicitly torn down, regardless of the probe-derived
 * flag. These tests fail against the old behaviour and pass against the fix.
 */
class FirmwareCompatibilityLayerTest {

    private fun profile(
        mode: FirmwareTransportMode,
        cli: Boolean = false,
        rpc: Boolean = false
    ) = FirmwareCompatibilityProfile(
        label = "Test",
        transportMode = mode,
        supportsCli = cli,
        supportsRpc = rpc
    )

    // ── The regression itself ────────────────────────────────────────────────

    @Test
    fun `mapped command runs over RPC when supportsRpc is stale-false on RPC-only firmware`() {
        // The exact broken state: RPC-only, and the probe left supportsRpc false.
        val assessment = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile(FirmwareTransportMode.RPC_ONLY, cli = false, rpc = false),
            command = "subghz tx /ext/subghz/garage.sub",
            hasRpcMapping = true
        )
        assertTrue(
            "A mapped command must route through the RPC bridge, not be refused",
            assessment.supported
        )
        assertEquals(FirmwareCommandRoute.RPC_APP_BRIDGE, assessment.route)
    }

    @Test
    fun `mapped command runs even with both capability flags false`() {
        // Both flags false is the post-reset state the gate used to hard-block.
        val assessment = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile(FirmwareTransportMode.PROBING, cli = false, rpc = false),
            command = "loader open NFC",
            hasRpcMapping = true
        )
        assertTrue(assessment.supported)
    }

    // ── What must still be refused ───────────────────────────────────────────

    @Test
    fun `mapped command is refused only when the transport is torn down`() {
        val assessment = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile(FirmwareTransportMode.UNAVAILABLE),
            command = "subghz tx /ext/subghz/garage.sub",
            hasRpcMapping = true
        )
        assertFalse("A disconnected device cannot run anything", assessment.supported)
        assertEquals(FirmwareCommandRoute.UNSUPPORTED, assessment.route)
    }

    @Test
    fun `unmapped command with no CLI and no RPC is refused`() {
        // led / vibro have no RPC equivalent on Flipper, so on RPC-only they are
        // genuinely unsupported — the gate must still say so rather than pretend.
        val assessment = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile(FirmwareTransportMode.RPC_ONLY, cli = false, rpc = true),
            command = "led 255 0 0",
            hasRpcMapping = false
        )
        assertFalse(assessment.supported)
        assertEquals(FirmwareCommandRoute.UNSUPPORTED, assessment.route)
    }

    @Test
    fun `unmapped command still runs over a working CLI`() {
        // On USB (CLI available) an unmapped command uses the direct CLI path.
        val assessment = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile(FirmwareTransportMode.CLI_AND_RPC, cli = true, rpc = true),
            command = "led 255 0 0",
            hasRpcMapping = false
        )
        assertTrue(assessment.supported)
        assertEquals(FirmwareCommandRoute.DIRECT_CLI, assessment.route)
    }

    @Test
    fun `empty command is refused`() {
        val assessment = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile(FirmwareTransportMode.RPC_ONLY, rpc = true),
            command = "   ",
            hasRpcMapping = false
        )
        assertFalse(assessment.supported)
    }
    @Test fun `CLI-only firmware keeps its supported direct route for mapped commands`() {
        val result = FirmwareCompatibilityLayer.assessCliCommand(
            profile(FirmwareTransportMode.CLI_ONLY, cli = true), "loader open NFC", true
        )
        assertEquals(FirmwareCommandRoute.DIRECT_CLI, result.route)
    }

    @Test fun `unavailable transport is refused even when capability flags are stale true`() {
        val result = FirmwareCompatibilityLayer.assessCliCommand(
            profile(FirmwareTransportMode.UNAVAILABLE, cli = true, rpc = true), "loader open NFC", true
        )
        assertFalse(result.supported)
    }
    @Test fun `dual mode retains direct CLI for mapped commands without RPC preference`() {
        val result = FirmwareCompatibilityLayer.assessCliCommand(
            profile(FirmwareTransportMode.CLI_AND_RPC, cli = true, rpc = true), "loader open NFC", true
        )
        assertEquals(FirmwareCommandRoute.DIRECT_CLI, result.route)
    }

}
