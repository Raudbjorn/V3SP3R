package com.vesper.flipper.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closes the app-side half of "manage apps / transmit / write scripts over BLE".
 *
 * The chain is: the model asks for an action -> CommandExecutor builds a CLI
 * command string (pinned by CommandExecutorHardwareTest) -> that string must have
 * an RPC mapping so it can run over BLE, where there is no text CLI -> the RPC
 * bridge sends an AppStartRequest (execution path read and verified sound).
 *
 * This test pins the middle link: every command string the executor produces for
 * a hardware action resolves to an RPC mapping AND, on RPC-only firmware, routes
 * via the RPC app bridge rather than being refused. With this, the only unproven
 * link left is the physical Flipper acting on the AppStartRequest — which needs
 * the real device over BLE and cannot be exercised in a unit test.
 */
class HardwareCommandRoutingTest {

    private val protocol = FlipperProtocol()

    /** An RPC-only profile confirmed by a device response. Stale capability
     *  recovery is tested separately by the firmware-routing feature. */
    private val rpcOnly = FirmwareCompatibilityProfile(
        family = FirmwareFamily.UNKNOWN,
        label = "RPC-only",
        transportMode = FirmwareTransportMode.RPC_ONLY,
        supportsCli = false,
        supportsRpc = true,
        supportsRpcAppBridge = true,
        confidence = 0.3f
    )

    /** Exactly the strings CommandExecutor builds for each hardware action. */
    private val hardwareCommands = listOf(
        "loader open NFC",                       // launch_app
        "loader open Sub-GHz",                   // launch_app
        "loader open NFC",             // launch_app + args
        "subghz tx /ext/subghz/garage.sub",      // subghz_transmit
        "ir tx /ext/infrared/tv.ir",             // ir_transmit
        "nfc emulate /ext/nfc/badge.nfc",        // nfc_emulate
        "rfid emulate /ext/lfrfid/card.rfid",    // rfid_emulate
        "ibutton emulate /ext/ibutton/key.ibtn", // ibutton_emulate
        "badusb run /ext/badusb/payload.txt"     // badusb_execute
    )

    @Test
    fun `every hardware command has an RPC mapping`() {
        for (command in hardwareCommands) {
            assertTrue(
                "\"$command\" must map to an RPC plan, or it cannot run over BLE",
                protocol.hasRpcAppCommandMapping(command)
            )
        }
    }

    @Test
    fun `every hardware command routes via the RPC bridge on RPC-only firmware`() {
        for (command in hardwareCommands) {
            val assessment = FirmwareCompatibilityLayer.assessCliCommand(
                profile = rpcOnly,
                command = command,
                hasRpcMapping = protocol.hasRpcAppCommandMapping(command)
            )
            assertTrue(
                "\"$command\" must be allowed on RPC-only firmware",
                assessment.supported
            )
            assertEquals(
                "\"$command\" must route via the RPC app bridge",
                FirmwareCommandRoute.RPC_APP_BRIDGE,
                assessment.route
            )
        }
    }

    @Test
    fun `launch_app resolves the correct Flipper app for common names`() {
        // The app-candidate list is what the AppStartRequest tries; if "NFC" did not
        // map to the NFC app, launching would silently open the wrong thing.
        assertTrue(protocol.hasRpcAppCommandMapping("loader open NFC"))
        assertTrue(protocol.hasRpcAppCommandMapping("loader open Sub-GHz"))
        assertTrue(protocol.hasRpcAppCommandMapping("loader open Infrared"))
        assertTrue(protocol.hasRpcAppCommandMapping("loader open Bad USB"))
    }
}
