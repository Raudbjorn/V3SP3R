package com.vesper.flipper.domain

import com.vesper.flipper.ble.FlipperFileSystem
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
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the command strings the app actually sends to the Flipper for the three
 * things the user reported broken: launching apps, writing scripts, and
 * transmitting/running signals.
 *
 * This verifies the half that does NOT need hardware — that execute() reaches the
 * FlipperFileSystem with the correct request. Whether the physical Flipper then
 * acts on it needs a real device over BLE; these tests guarantee that the app is
 * asking for the right thing, so a hardware failure can be isolated to the
 * transport rather than to command construction.
 *
 * All actions are stubbed LOW-risk so execute() runs the action directly rather
 * than parking it for confirmation (the confirmation gate itself is covered by
 * CommandExecutorTest).
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
            fileSystem, riskAssessor, permissionService,
            auditService, diffService, forgeEngine, settingsStore
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

        verify(fileSystem).executeCli(eq("loader open NFC"))
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

        verify(fileSystem).executeCli(eq("loader open NFC test"))
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
}
