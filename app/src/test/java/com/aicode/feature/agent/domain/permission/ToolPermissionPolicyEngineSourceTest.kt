package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine.PermissionNamespace
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine.Verdict
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.shizuku.ShizukuIdentity
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPermissionPolicyEngineSourceTest {
    private class Fixture {
        val mode = ExecutionModeHolder()
        val settings = mockk<ContainerSettingsRepository>()
        val root = mockk<RootCommandManager> { coEvery { accessSnapshot() } returns RootAccessState(true, 42, "authority") }
        val shizuku = mockk<ShizukuManager> { every { accessSnapshot() } returns ShizukuIdentity(2000, "shell") }
        val grants = mockk<SessionPermissionGrantRepository> { coEvery { isAllowed(any(), any()) } returns true }
        val rules = mockk<PermissionRulesRepository> { coEvery { loadEffectiveForCurrentProject() } returns emptyList() }
        val context = mockk<Context> {
            every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers { "resource:${firstArg<Int>()}" }
        }
        val repositoryIdentity = Any()
        var selection = 1L
        var configuration = 1L
        var profile = ContainerProfile.BUILTIN_ALPINE.copy(
            id = "chroot", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("instance", 1, 2, 3)
        )
        val engine = ToolPermissionPolicyEngine(rules, grants, context, root, shizuku, mode, settings)

        fun snapshot() = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), selection, configuration, repositoryIdentity)

        init {
            coEvery { settings.captureProfileSnapshot() } answers { snapshot() }
            every { settings.checkProfileSnapshot(any()) } answers {
                val captured = firstArg<ContainerProfileSnapshot>()
                if (captured.repositoryIdentity !== repositoryIdentity || captured.selectionRevision != selection || captured.configurationRevision != configuration) {
                    throw CancellationException("Profile changed")
                }
            }
        }

        fun execution(capturedProfile: ContainerProfileSnapshot = snapshot(), checkSource: () -> Unit = {}): WorkspaceExecutionContext =
            WorkspaceExecutionContext(
                mode.currentMode(), "frozen-cache", mockk(), null, null,
                profileSnapshot = capturedProfile, checkSource = checkSource
            )
    }

    private fun commandArgs(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test fun initialAndActualFrozenGuestEvaluationHaveEqualValueSourcesWithoutRootOperations() = runTest {
        val fixture = Fixture()
        val first = fixture.engine.evaluate("Bash", commandArgs("id"), "s1")
        val second = fixture.engine.evaluate("Bash", commandArgs("id"), "s1", fixture.execution())
        assertEquals(Verdict.ALLOW, first.verdict)
        assertEquals(first.permissionSource, second.permissionSource)
        assertEquals(PermissionNamespace.CHROOT_GUEST, first.permissionSource?.namespace)
        assertEquals(first.hostSystemTargets, second.hostSystemTargets)
        assertNull(first.sessionGrant)
        coVerify(exactly = 0) { fixture.root.accessSnapshot() }
        coVerify(exactly = 1) { fixture.settings.captureProfileSnapshot() }
    }

    @Test fun guestPathsAndHostReadsAllowWhileHostWritesAndOpaqueScriptsNeedReview() = runTest {
        val fixture = Fixture()
        for (tool in listOf("Bash", "terminal")) {
            for (command in listOf("cat /etc/passwd", "touch /usr/bin/probe", "rm -rf /etc", "sudo id", "cat /proc/sys/kernel/hostname")) {
                val args = commandArgs(command) + if (tool == "terminal") mapOf("action" to JsonPrimitive("start")) else emptyMap()
                val result = fixture.engine.evaluate(tool, args, "s1", fixture.execution())
                assertEquals("$tool: $command", Verdict.ALLOW, result.verdict)
                assertTrue(result.hostSystemUncertainties.isEmpty())
                assertNull(result.hostWriteRootGeneration)
            }
            for (command in listOf("touch /sys/probe", "dd of=/dev/block/boot", "apt-get install -y make", "sh /root/opaque.sh")) {
                val args = commandArgs(command) + if (tool == "terminal") mapOf("action" to JsonPrimitive("start")) else emptyMap()
                val result = fixture.engine.evaluate(tool, args + ("elevate" to JsonPrimitive(true)), "s1", fixture.execution())
                assertEquals("$tool: $command", Verdict.ASK, result.verdict)
                // This legacy fixture has no WorkspaceRepository/frozen workspace: once-only review.
                assertNull(result.sessionGrant)
                assertTrue(result.hostSystemTargets.isNotEmpty() || result.hostSystemUncertainties.isNotEmpty())
                assertEquals(42L, result.hostWriteRootGeneration)
                assertEquals("authority", result.hostWriteRootAuthorityId)
                assertNull(result.rootGeneration)
            }
        }
        val send = fixture.engine.evaluate("terminal", mapOf(
            "action" to JsonPrimitive("send"), "input" to JsonPrimitive("cat /sys/probe"),
            "command" to JsonPrimitive("echo ignored"), "tab_id" to JsonPrimitive("term-1")
        ), "s1", fixture.execution())
        assertEquals(Verdict.ASK, send.verdict)
        assertEquals(setOf("READ:/sys/probe"), send.hostSystemTargets)
        assertTrue(send.hostSystemUncertainties.contains("interactive-terminal-input"))
    }

    @Test fun structuredFilePathFieldsCannotAvoidHostInterfaceReview() = runTest {
        val fixture = Fixture()
        val cases: List<Pair<String, Map<String, JsonElement>>> = listOf(
            "readFile" to mapOf("path" to JsonPrimitive("/proc/sys/kernel/hostname")),
            "writeFile" to mapOf("path" to JsonPrimitive("/sys/probe"), "content" to JsonPrimitive("value")),
            "editFile" to mapOf("path" to JsonPrimitive("/sys/probe")),
            "sendFile" to mapOf("paths" to JsonArray(listOf(JsonPrimitive("/sys/probe")))),
            "viewImage" to mapOf("images" to JsonArray(listOf(JsonPrimitive("/sys/probe")))),
            "list" to mapOf("args" to JsonPrimitive("-la /proc/sys")),
            "search" to mapOf("args" to JsonPrimitive("-n -e pattern /sys")),
            "generateImage" to mapOf("output_path" to JsonPrimitive("/sys/probe"))
        )
        for ((tool, args) in cases) {
            val result = fixture.engine.evaluate(tool, args, "s1", fixture.execution())
            val writing = tool in setOf("writeFile", "editFile", "generateImage")
            assertEquals(tool, if (writing) Verdict.ASK else Verdict.ALLOW, result.verdict)
            assertNull(result.sessionGrant)
            assertTrue(result.hostSystemTargets.isNotEmpty())
            assertEquals(if (writing) 42L else null, result.hostWriteRootGeneration)
        }
        val guest = fixture.engine.evaluate("writeFile", mapOf(
            "path" to JsonPrimitive("/etc/guest"), "content" to JsonPrimitive("/sys/probe")
        ), "s1", fixture.execution())
        assertEquals(Verdict.ALLOW, guest.verdict)
        assertTrue(guest.hostSystemTargets.isEmpty())
    }

    @Test fun explicitDenyWinsForCommandsAndTypedFileToolsEvenInChroot() = runTest {
        val fixture = Fixture()
        for ((tool, args) in listOf("Bash" to commandArgs("cat /etc/passwd"), "readFile" to mapOf("path" to JsonPrimitive("/sys/probe")))) {
            coEvery { fixture.rules.loadEffectiveForCurrentProject() } returns listOf(PermissionRule(tool, "*", PermissionDecision.DENY))
            val result = fixture.engine.evaluate(tool, args, "s1", fixture.execution())
            assertEquals(Verdict.DENY, result.verdict)
            assertFalse(result.channelUnavailable)
            assertNull(result.sessionGrant)
        }
    }

    @Test fun selectionConfigurationInstanceAndModeAbaChangeSourceValues() = runTest {
        val fixture = Fixture()
        val first = fixture.engine.evaluate("Bash", commandArgs("id"), "s1").permissionSource
        fixture.selection += 2
        val selected = fixture.engine.evaluate("Bash", commandArgs("id"), "s1").permissionSource
        assertNotEquals(first, selected)
        fixture.configuration++
        val configured = fixture.engine.evaluate("Bash", commandArgs("id"), "s1").permissionSource
        assertNotEquals(selected, configured)
        fixture.profile = fixture.profile.copy(chrootInstance = fixture.profile.chrootInstance!!.copy(installGeneration = 4))
        val installed = fixture.engine.evaluate("Bash", commandArgs("id"), "s1").permissionSource
        assertNotEquals(configured, installed)
        fixture.mode.setMode(ExecutionMode.REMOTE_SSH)
        fixture.mode.setMode(ExecutionMode.LOCAL_PROOT)
        val restoredMode = fixture.engine.evaluate("Bash", commandArgs("id"), "s1").permissionSource
        assertNotEquals(installed, restoredMode)
        assertEquals(1L, first?.profileSelectionRevision)
        assertEquals(1L, first?.profileConfigurationRevision)
        assertEquals(3L, first?.chrootInstance?.installGeneration)
    }

    @Test fun frozenProfileIsNeverReplacedWithCurrentlySelectedProfile() = runTest {
        val fixture = Fixture()
        val captured = fixture.execution()
        fixture.selection++
        fixture.profile = ContainerProfile.BUILTIN_ALPINE
        assertTrue(runCatching { fixture.engine.evaluate("Bash", commandArgs("id"), "s1", captured) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { fixture.settings.captureProfileSnapshot() }
    }

    @Test fun invalidActualWecAndModeChangesRejectRatherThanReusingEarlierAllow() = runTest {
        val fixture = Fixture()
        assertTrue(runCatching {
            fixture.engine.evaluate("Bash", commandArgs("id"), "s1", fixture.execution(checkSource = { throw CancellationException("Source changed") }))
        }.exceptionOrNull() is CancellationException)
        val captured = fixture.execution()
        fixture.mode.setMode(ExecutionMode.REMOTE_SSH)
        assertTrue(runCatching { fixture.engine.evaluate("Bash", commandArgs("id"), "s1", captured) }.exceptionOrNull() is CancellationException)
    }

    @Test fun modeAbaDuringProfileCaptureRejectsTornPermissionSource() = runTest {
        val fixture = Fixture()
        coEvery { fixture.settings.captureProfileSnapshot() } answers {
            fixture.mode.setMode(ExecutionMode.REMOTE_SSH)
            fixture.mode.setMode(ExecutionMode.LOCAL_PROOT)
            fixture.snapshot()
        }
        assertTrue(runCatching { fixture.engine.evaluate("Bash", commandArgs("id"), "s1") }.exceptionOrNull() is CancellationException)
    }

    @Test fun missingSelectedProfileCannotSilentlyFallBackToGuestAllow() = runTest {
        val fixture = Fixture()
        val missing = ContainerProfileSnapshot("missing", "missing", listOf(ContainerProfile.BUILTIN_ALPINE), 1L, 1L, fixture.repositoryIdentity)
        coEvery { fixture.settings.captureProfileSnapshot() } returns missing
        assertTrue(runCatching { fixture.engine.evaluate("Bash", commandArgs("id"), "s1") }.exceptionOrNull() is CancellationException)
    }

    @Test fun frozenExecutionWithoutProfileNeverFallsBackToSelectedSettings() = runTest {
        val fixture = Fixture()
        val execution = WorkspaceExecutionContext(
            ExecutionMode.LOCAL_PROOT, "unknown-cache", mockk(), null, null, checkSource = {}
        )
        assertTrue(runCatching { fixture.engine.evaluate("Bash", commandArgs("id"), "s1", execution) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { fixture.settings.captureProfileSnapshot() }
    }

    @Test fun channelUnavailableOnlyMarksActualHostChannelLossNotExplicitRuleDenials() = runTest {
        val fixture = Fixture()
        for (state in listOf(RootAccessState(false, 43, "authority"), RootAccessState(true, 44, ""), RootAccessState(true, 44, " \t"))) {
            coEvery { fixture.root.accessSnapshot() } returns state
            val result = fixture.engine.evaluate("Root", commandArgs("id"), "s1")
            assertEquals(Verdict.DENY, result.verdict)
            assertTrue(result.channelUnavailable)
            assertNull(result.rootGeneration)
            assertNull(result.rootAuthorityId)
            assertNull(result.sessionGrant)
        }
        every { fixture.shizuku.accessSnapshot() } returns null
        val disconnected = fixture.engine.evaluate("Shizuku", commandArgs("id"), "s1")
        assertEquals(Verdict.DENY, disconnected.verdict)
        assertTrue(disconnected.channelUnavailable)
        assertNull(disconnected.shizukuIdentity)
        assertNull(disconnected.sessionGrant)

        for (tool in listOf("Root", "Shizuku")) {
            for (pattern in listOf("*", "id")) {
                coEvery { fixture.rules.loadEffectiveForCurrentProject() } returns listOf(PermissionRule(tool, pattern, PermissionDecision.DENY))
                val explicitDeny = fixture.engine.evaluate(tool, commandArgs("id"), "s1")
                assertEquals(Verdict.DENY, explicitDeny.verdict)
                assertFalse(explicitDeny.channelUnavailable)
            }
        }
    }

    @Test fun hostChannelsDoNotAcquireGuestSemanticsAndRetainTrueAuthorityMetadata() = runTest {
        val fixture = Fixture()
        val execution = fixture.execution()
        val root = fixture.engine.evaluate("Root", commandArgs("cat /etc/hosts"), "s1", execution)
        val shell = fixture.engine.evaluate("Shizuku", commandArgs("cat /etc/hosts"), "s1", execution)
        assertEquals(Verdict.ALLOW, root.verdict)
        assertEquals(Verdict.ALLOW, shell.verdict)
        assertFalse(root.channelUnavailable)
        assertFalse(shell.channelUnavailable)
        assertEquals(PermissionNamespace.HOST, root.permissionSource?.namespace)
        assertEquals(root.permissionSource, shell.permissionSource)
        assertEquals(42L, root.rootGeneration)
        assertEquals("authority", root.rootAuthorityId)
        assertEquals(ShizukuIdentity(2000, "shell"), shell.shizukuIdentity)
        coVerify(exactly = 0) { fixture.settings.captureProfileSnapshot() }
    }

    @Test fun prootAndRemotePreserveLegacyRootAndHighRiskRules() = runTest {
        val fixture = Fixture()
        fixture.profile = ContainerProfile.BUILTIN_ALPINE
        for (mode in listOf(ExecutionMode.LOCAL_PROOT, ExecutionMode.REMOTE_SSH)) {
            fixture.mode.setMode(mode)
            coEvery { fixture.grants.isAllowed(any(), any()) } returns false
            val root = fixture.engine.evaluate("Bash", commandArgs("sudo id"), "s1")
            val unsafe = fixture.engine.evaluate("Bash", commandArgs("cat > /etc/passwd"), "s1")
            assertEquals(Verdict.ASK, root.verdict)
            assertEquals("root:sudo id", root.sessionGrant)
            assertEquals(Verdict.DENY, unsafe.verdict)
            assertEquals(if (mode == ExecutionMode.LOCAL_PROOT) PermissionNamespace.PROOT_GUEST else PermissionNamespace.REMOTE, root.permissionSource?.namespace)
        }
    }
}
