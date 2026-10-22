package com.vesper.flipper.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipperProtocolCommandMappingTest {
    @Test fun `interactive app launch preserves empty arguments and quoted app name`() {
        val protocol = FlipperProtocol()
        val parse = FlipperProtocol::class.java.getDeclaredMethod("parseRpcCommandPlan", String::class.java)
            .apply { isAccessible = true }
        val plan = parse.invoke(protocol, "loader open \"JavaScript Runner\"")!!
        val args = plan.javaClass.getDeclaredMethod("getAppArgs").apply { isAccessible = true }.invoke(plan)
        val candidates = plan.javaClass.getDeclaredMethod("getAppCandidates").apply { isAccessible = true }.invoke(plan) as List<*>
        assertEquals("", args)
        assertTrue(candidates.contains("JavaScript Runner"))
        val explicit = parse.invoke(protocol, "loader open NFC RPC")!!
        assertEquals("RPC", explicit.javaClass.getDeclaredMethod("getAppArgs").apply { isAccessible = true }.invoke(explicit))
    }


    @Test
    fun `ble spam space alias resolves rpc mapping and preferred route`() {
        val protocol = FlipperProtocol()

        assertTrue(protocol.hasRpcAppCommandMapping("ble spam apple_airpods"))
        assertTrue(protocol.hasRpcAppCommandMapping("ble spam stop"))

        val profile = FirmwareCompatibilityProfile(
            family = FirmwareFamily.UNKNOWN,
            label = "test",
            transportMode = FirmwareTransportMode.CLI_AND_RPC,
            supportsCli = true,
            supportsRpc = true,
            supportsRpcAppBridge = true,
            confidence = 1f
        )
        val compatibility = FirmwareCompatibilityLayer.assessCliCommand(
            profile = profile,
            command = "ble spam apple_airpods",
            hasRpcMapping = true
        )
        assertTrue(compatibility.supported)
        assertEquals(FirmwareCommandRoute.RPC_APP_BRIDGE, compatibility.route)
    }

    @Test
    fun `ble scan space alias resolves rpc mapping`() {
        val protocol = FlipperProtocol()

        assertTrue(protocol.hasRpcAppCommandMapping("ble scan"))
        assertTrue(protocol.hasRpcAppCommandMapping("ble scan quick"))
    }
}
