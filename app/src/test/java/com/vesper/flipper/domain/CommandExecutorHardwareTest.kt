package com.vesper.flipper.domain

import com.vesper.flipper.ble.FlipperFileSystem
import com.vesper.flipper.ble.FlipperProtocol
import com.vesper.flipper.ble.FlipperBleService
import com.vesper.flipper.ble.ProtocolResponse
import com.vesper.flipper.data.SettingsStore
import com.vesper.flipper.domain.executor.CommandExecutor
import com.vesper.flipper.domain.executor.ForgeEngine
import com.vesper.flipper.domain.executor.RiskAssessor
import com.vesper.flipper.domain.model.CommandAction
import com.vesper.flipper.domain.model.CommandArgs
import com.vesper.flipper.domain.model.ExecuteCommand
import com.vesper.flipper.domain.model.RiskAssessment
import com.vesper.flipper.domain.model.RiskLevel
import com.vesper.flipper.domain.service.AuditService
import com.vesper.flipper.domain.service.DiffService
import com.vesper.flipper.domain.service.PermissionService
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever

/** Selected executor command-construction regressions; physical execution is unverified.
 * Normal cases stub low risk to isolate construction; the override boundary case
 * uses medium auto-approval to ensure routing still cannot select a different app.
 */
class CommandExecutorHardwareTest {

    private lateinit var fileSystem: FlipperFileSystem
    private lateinit var riskAssessor: RiskAssessor
    private lateinit var permissionService: PermissionService
    private lateinit var auditService: AuditService
    private lateinit var diffService: DiffService
    private lateinit var forgeEngine: ForgeEngine
    private lateinit var settingsStore: SettingsStore
    private lateinit var executor: CommandExecutor

    @Before
    fun setup() {
        fileSystem = mock()
        riskAssessor = mock()
        permissionService = mock()
        auditService = mock()
        diffService = mock()
        forgeEngine = mock()
        settingsStore = mock()
        whenever(settingsStore.autoApproveMedium).thenReturn(flowOf(false))
        whenever(settingsStore.autoApproveHigh).thenReturn(flowOf(false))
        executor = CommandExecutor(
            fileSystem = fileSystem, riskAssessor = riskAssessor, permissionService = permissionService,
            auditService = auditService, diffService = diffService, forgeEngine = forgeEngine,
            settingsStore = settingsStore, vulnTriage = mock(), appCatalog = mock()
        )
    }

    private fun lowRisk(command: ExecuteCommand) {
        whenever(riskAssessor.assess(command)).thenReturn(
            RiskAssessment(
                level = RiskLevel.LOW,
                reason = "test",
                affectedPaths = emptyList(),
                requiresDiff = false,
                requiresConfirmation = false
            )
        )
    }

    @Test
    fun `launch_app issues loader open with the app name`() = runBlocking {
        val cmd = ExecuteCommand(
            action = CommandAction.LAUNCH_APP,
            args = CommandArgs(appName = "NFC"),
            justification = "t", expectedEffect = "t"
        )
        lowRisk(cmd)
        whenever(fileSystem.executeCli(any())).thenReturn(Result.success("ok"))

        val result = executor.execute(cmd, "s")

        verify(fileSystem).executeCli(eq("loader open \"NFC\""))
        assertTrue(result.success)
    }

    @Test
    fun `launch_app forwards app args`() = runBlocking {
        val cmd = ExecuteCommand(
            action = CommandAction.LAUNCH_APP,
            args = CommandArgs(appName = "NFC", appArgs = "test"),
            justification = "t", expectedEffect = "t"
        )
        lowRisk(cmd)
        whenever(fileSystem.executeCli(any())).thenReturn(Result.success("ok"))

        executor.execute(cmd, "s")

        verify(fileSystem).executeCli(eq("loader open \"NFC\" test"))
        Unit
    }

    @Test
    fun `write_file writes the exact path and content`() = runBlocking {
        val script = "REM transport test\n"
        val cmd = ExecuteCommand(
            action = CommandAction.WRITE_FILE,
            args = CommandArgs(path = "/ext/badusb/demo.txt", content = script),
            justification = "t", expectedEffect = "t"
        )
        lowRisk(cmd)
        whenever(fileSystem.writeFile(any(), any())).thenReturn(Result.success(script.length.toLong()))

        val result = executor.execute(cmd, "s")

        verify(fileSystem).writeFile(eq("/ext/badusb/demo.txt"), eq(script))
        assertTrue(result.success)
    }

    @Test
    fun `subghz_transmit issues subghz tx for the file`() = runBlocking {
        val cmd = ExecuteCommand(
            action = CommandAction.SUBGHZ_TRANSMIT,
            args = CommandArgs(path = "/ext/subghz/garage.sub"),
            justification = "t", expectedEffect = "t"
        )
        lowRisk(cmd)
        whenever(fileSystem.executeCli(any())).thenReturn(Result.success("ok"))

        executor.execute(cmd, "s")

        verify(fileSystem).executeCli(eq("subghz tx /ext/subghz/garage.sub"))
        Unit
    }

    @Test
    fun `badusb_execute issues badusb run for the script`() = runBlocking {
        val cmd = ExecuteCommand(
            action = CommandAction.BADUSB_EXECUTE,
            args = CommandArgs(path = "/ext/badusb/payload.txt"),
            justification = "t", expectedEffect = "t"
        )
        lowRisk(cmd)
        whenever(fileSystem.executeCli(any())).thenReturn(Result.success("ok"))

        executor.execute(cmd, "s")

        verify(fileSystem).executeCli(eq("badusb run /ext/badusb/payload.txt"))
        Unit
    }
    @Test fun `quoted multiword app name and remaining arguments survive executor routing`() = runBlocking<Unit> {
        val cmd = ExecuteCommand(CommandAction.LAUNCH_APP,
            CommandArgs(appName = "JavaScript Runner", appArgs = "/ext/apps_scripts/example.js"), "test", "open")
        lowRisk(cmd)
        whenever(fileSystem.executeCli(any())).thenReturn(Result.success("ok"))
        assertTrue(executor.execute(cmd, "s").success)
        val command = argumentCaptor<String>()
        verify(fileSystem).executeCli(command.capture())
        assertEquals("loader open \"JavaScript Runner\" /ext/apps_scripts/example.js", command.firstValue)
        val protocol = FlipperProtocol()
        val parse = FlipperProtocol::class.java.getDeclaredMethod("parseRpcCommandPlan", String::class.java).apply { isAccessible = true }
        val plan = parse.invoke(protocol, command.firstValue)!!
        assertEquals("JavaScript Runner", (plan.javaClass.getDeclaredMethod("getAppCandidates").apply { isAccessible = true }.invoke(plan) as List<*>).first())
        assertEquals("/ext/apps_scripts/example.js", plan.javaClass.getDeclaredMethod("getAppArgs").apply { isAccessible = true }.invoke(plan))
    }

    @Test fun `IR signal name survives executor command construction`() = runBlocking<Unit> {
        val cmd = ExecuteCommand(CommandAction.IR_TRANSMIT,
            CommandArgs(path = "/ext/infrared/tv.ir", signalName = "Power"), "test", "transmit")
        lowRisk(cmd)
        whenever(fileSystem.executeCli(any())).thenReturn(Result.success("ok"))
        assertTrue(executor.execute(cmd, "s").success)
        verify(fileSystem).executeCli("ir tx /ext/infrared/tv.ir Power")
    }

    @Test fun `autoapproved medium transmit cannot select a high risk app through arguments`() = runBlocking<Unit> {
        val service = mock<FlipperBleService>()
        whenever(service.usesRpcOnlyCommandTransport()).thenReturn(true)
        whenever(service.isCommandTransportConnected()).thenReturn(true)
        val protocol = FlipperProtocol().apply { setBleService(service) }
        FlipperProtocol::class.java.getDeclaredMethod("markRpcReady", String::class.java)
            .apply { isAccessible = true }.invoke(protocol, "Correlated RPC response in fixture")
        val responses = mutableListOf<ProtocolResponse>()
        whenever(settingsStore.autoApproveMedium).thenReturn(flowOf(true))
        whenever(fileSystem.executeCli(any())).thenAnswer { call ->
            val response = runBlocking { protocol.sendCliCommand(call.getArgument(0)) }
            responses += response
            assertTrue(response is ProtocolResponse.Error)
            Result.failure<String>(IllegalStateException((response as ProtocolResponse.Error).message))
        }
        for (option in listOf("--app \"Bad USB\"", "--app=BadUSB", "--app-name BadUSB", "--app-name=BadUSB")) {
            val cmd = ExecuteCommand(CommandAction.SUBGHZ_TRANSMIT,
                CommandArgs(path = "/ext/subghz/test.sub $option"), "test", "transmit")
            whenever(riskAssessor.assess(cmd)).thenReturn(RiskAssessment(RiskLevel.MEDIUM,
                "Transmit", emptyList(), requiresDiff = false, requiresConfirmation = true))
            assertFalse(executor.execute(cmd, "s").success)
        }
        assertEquals(4, responses.size)
        assertTrue(responses.all { it is ProtocolResponse.Error && it.message.contains("No RPC action mapping") })
        verify(fileSystem, times(4)).executeCli(any())
        verify(service, never()).sendData(any(), any(), any(), any())
        verify(service, never()).sendUsbDataOnly(any())
    }
}
