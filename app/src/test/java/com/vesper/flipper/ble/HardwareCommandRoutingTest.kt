package com.vesper.flipper.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selected command-construction and routing regressions; physical execution is unverified. */
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

    /** Representative mapped hardware commands, including optional arguments. */
    private val hardwareCommands = listOf(
        "loader open NFC",                       // launch_app
        "loader open Sub-GHz",                   // launch_app
        "loader open NFC /ext/nfc/demo.nfc",       // launch_app + args
        "subghz tx /ext/subghz/garage.sub",      // subghz_transmit
        "ir tx /ext/infrared/tv.ir",             // ir_transmit
        "ir tx /ext/infrared/tv.ir Power",       // ir_transmit + signal
        "nfc emulate /ext/nfc/badge.nfc",        // nfc_emulate
        "rfid emulate /ext/lfrfid/card.rfid",    // rfid_emulate
        "ibutton emulate /ext/ibutton/key.ibtn", // ibutton_emulate
        "badusb run /ext/badusb/payload.txt"     // badusb_execute
    )

    @Test
    fun `selected hardware commands have RPC mappings`() {
        for (command in hardwareCommands) {
            assertTrue(
                "\"$command\" must map to an RPC plan, or it cannot run over BLE",
                protocol.hasRpcAppCommandMapping(command)
            )
        }
    }

    @Test
    fun `selected hardware commands route via RPC on RPC-only firmware`() {
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
        val parse = FlipperProtocol::class.java.getDeclaredMethod("parseRpcCommandPlan", String::class.java)
            .apply { isAccessible = true }
        for (name in listOf("NFC", "Sub-GHz", "Infrared", "Bad USB")) {
            val plan = requireNotNull(parse.invoke(protocol, "loader open \"$name\""))
            val candidates = plan.javaClass.getDeclaredField("appCandidates").apply { isAccessible = true }
                .get(plan) as List<*>
            assertEquals(name, candidates.first())
        }
    }
}
