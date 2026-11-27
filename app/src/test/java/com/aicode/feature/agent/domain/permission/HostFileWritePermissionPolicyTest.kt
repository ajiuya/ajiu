package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine.EvalResult
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
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product-policy regressions, first frozen/red against the pre-change public API. */
class HostFileWritePermissionPolicyTest {
    private companion object {
        const val SESSION = "write-policy-session"
        const val HOST_WRITE_PREFIX = "host-write-session-v1:"
    }

    private class Fixture(includeWorkspace: Boolean = true) {
        val mode = ExecutionModeHolder()
        val settings = mockk<ContainerSettingsRepository>()
        val workspaceSelections = MutableStateFlow(WorkspaceRepository.Selection(Workspace("project", "/root/workspace/project")))
        val workspaces = mockk<WorkspaceRepository>()
        var rootState = RootAccessState(true, 42, "root-authority")
        var shizukuIdentity: ShizukuIdentity? = ShizukuIdentity(2000, "shell-connection")
        val root = mockk<RootCommandManager> { coEvery { accessSnapshot() } answers { rootState } }
        val shizuku = mockk<ShizukuManager> { every { accessSnapshot() } answers { shizukuIdentity } }
        val grants = mockk<SessionPermissionGrantRepository> { coEvery { isAllowed(any(), any()) } returns false }
        var effectiveRules: List<PermissionRule> = emptyList()
        val rules = mockk<PermissionRulesRepository> { coEvery { loadEffectiveForCurrentProject() } answers { effectiveRules } }
        val formatArgs = mutableMapOf<Int, List<String>>()
        val context = mockk<Context> {
            every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers {
                formatArgs[firstArg<Int>()] = secondArg<Array<Any?>>().map { it.toString() }
                "resource:${firstArg<Int>()}"
            }
        }
        private val repositoryIdentity = Any()
        var selection = 1L
        var configuration = 1L
        var profile = ContainerProfile.BUILTIN_ALPINE.copy(
            id = "chroot-profile", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("instance", 10, 20, 30)
        )
        val engine = ToolPermissionPolicyEngine(rules, grants, context, root, shizuku, mode, settings, if (includeWorkspace) workspaces else null)

        fun snapshot() = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), selection, configuration, repositoryIdentity)

        fun execution(includeFrozenWorkspace: Boolean = true): WorkspaceExecutionContext {
            val selection = workspaceSelections.value
            val path = selection.workspace?.path.orEmpty()
            return WorkspaceExecutionContext(
                mode.currentMode(), "frozen-cache", mockk(), null, null,
                profileSnapshot = snapshot(),
                runtimeWorkspaceSource = if (includeFrozenWorkspace) WorkspacePathMapper.WorkspaceSource(File(path), path, selection) else null,
                checkSource = {}
            )
        }

        init {
            every { workspaces.selection } returns workspaceSelections
            coEvery { settings.captureProfileSnapshot() } answers { snapshot() }
            every { settings.checkProfileSnapshot(any()) } answers {
                val captured = firstArg<ContainerProfileSnapshot>()
                if (captured.repositoryIdentity !== repositoryIdentity || captured.selectionRevision != selection || captured.configurationRevision != configuration) {
                    throw CancellationException("Profile changed")
                }
            }
        }
    }

    private fun commandArgs(command: String, tool: String = "Bash"): Map<String, JsonElement> = buildMap {
        put("command", JsonPrimitive(command))
        if (tool == "terminal") put("action", JsonPrimitive("start"))
    }

    private fun assertWriteAsk(result: EvalResult, label: String): String {
        assertEquals(label, Verdict.ASK, result.verdict)
        assertFalse("$label is an approval, not channel loss", result.channelUnavailable)
        assertNotNull("$label must expose a session-wide source grant", result.sessionGrant)
        // assertNotNull above is deliberate: an unimplemented key must fail as an assertion, not !!.
        val grant = result.sessionGrant.orEmpty()
        assertTrue("$label uses a versioned host-write grant", grant.startsWith(HOST_WRITE_PREFIX))
        assertNotNull("$label has an approval title", result.askTitle)
        assertNotNull("$label explains the review", result.approvalNote)
        return grant
    }

    @Test fun readWritePolicyRegression_hostChannelSystemReadsAllowWithTrueAuthority() = runTest {
        val fixture = Fixture()
        for (tool in listOf("Root", "Shizuku")) {
            for (command in listOf("cat /system/build.prop", "cat < /system/build.prop", "cat /sys/class/power_supply/battery/status")) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION)
                assertEquals("$tool: $command", Verdict.ALLOW, result.verdict)
                assertNull(result.sessionGrant)
                assertEquals(PermissionNamespace.HOST, result.permissionSource?.namespace)
                assertTrue(result.hostSystemTargets.isNotEmpty())
                if (tool == "Root") {
                    assertEquals(42L, result.rootGeneration)
                    assertEquals("root-authority", result.rootAuthorityId)
                    assertNull(result.shizukuIdentity)
                } else {
                    assertEquals(fixture.shizukuIdentity, result.shizukuIdentity)
                    assertNull(result.rootGeneration)
                    assertNull(result.rootAuthorityId)
                }
            }
        }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_chrootHostInterfaceReadsAllowWithoutRootLookup() = runTest {
        val fixture = Fixture()
        fixture.rootState = RootAccessState(false, 43, "")
        for (tool in listOf("Bash", "terminal")) {
            val result = fixture.engine.evaluate(tool, commandArgs("cat /proc/sys/kernel/hostname", tool), SESSION, fixture.execution())
            assertEquals(tool, Verdict.ALLOW, result.verdict)
            assertEquals(setOf("READ:/proc/sys/kernel/hostname"), result.hostSystemTargets)
            assertEquals(PermissionNamespace.CHROOT_GUEST, result.permissionSource?.namespace)
            assertNull(result.rootGeneration)
            assertNull(result.rootAuthorityId)
            assertNull(result.hostWriteRootGeneration)
            assertNull(result.hostWriteRootAuthorityId)
            assertNull(result.sessionGrant)
        }
        coVerify(exactly = 0) { fixture.root.accessSnapshot() }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_chrootInteractiveSendAndKeyAllowOrdinaryInputWithoutRootLookup() = runTest {
        val fixture = Fixture()
        val cases = listOf(
            mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("cat /sys/probe"),
                "command" to JsonPrimitive("touch /sys/ignored"), "tab_id" to JsonPrimitive("term-1")),
            mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("id"), "tab_id" to JsonPrimitive("term-1")),
            mapOf("action" to JsonPrimitive("key"), "key" to JsonPrimitive("enter"), "tab_id" to JsonPrimitive("term-1"))
        )
        fixture.rootState = RootAccessState(false, 43, "")
        for (args in cases) {
            val result = fixture.engine.evaluate("terminal", args, SESSION, fixture.execution())
            assertEquals(Verdict.ALLOW, result.verdict)
            assertNull(result.hostWriteRootGeneration)
            assertNull(result.hostWriteRootAuthorityId)
            assertTrue(result.ordinaryGuestUncertainties.contains("interactive-input"))
            assertTrue(result.hostSystemUncertainties.isEmpty())
            assertNull(result.rootGeneration)
            assertNull(result.rootAuthorityId)
            assertNull(result.sessionGrant)
        }
        coVerify(exactly = 0) { fixture.root.accessSnapshot() }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_structuredHostReadsAllowButWritesOfferSessionScope() = runTest {
        val fixture = Fixture()
        val reads: List<Pair<String, Map<String, JsonElement>>> = listOf(
            "readFile" to mapOf("path" to JsonPrimitive("/proc/sys/kernel/hostname")),
            "sendFile" to mapOf("paths" to JsonArray(listOf(JsonPrimitive("/sys/probe")))),
            "viewImage" to mapOf("images" to JsonArray(listOf(JsonPrimitive("/sys/probe")))),
            "list" to mapOf("args" to JsonPrimitive("-la /proc/sys")),
            "search" to mapOf("args" to JsonPrimitive("-n -e pattern /sys"))
        )
        for ((tool, args) in reads) {
            val result = fixture.engine.evaluate(tool, args, SESSION, fixture.execution())
            assertEquals(tool, Verdict.ALLOW, result.verdict)
            assertNull(result.sessionGrant)
            assertTrue(result.hostSystemTargets.isNotEmpty())
        }
        val writes: List<Pair<String, Map<String, JsonElement>>> = listOf(
            "writeFile" to mapOf("path" to JsonPrimitive("/sys/probe"), "content" to JsonPrimitive("value")),
            "editFile" to mapOf("path" to JsonPrimitive("/sys/probe")),
            "generateImage" to mapOf("output_path" to JsonPrimitive("/sys/probe"))
        )
        for ((tool, args) in writes) {
            val result = fixture.engine.evaluate(tool, args, SESSION, fixture.execution())
            assertWriteAsk(result, tool)
            assertTrue(result.hostSystemTargets.any { it.startsWith("WRITE:") })
            assertNull("CHROOT file access is not a Root command permit", result.rootGeneration)
            assertNull(result.rootAuthorityId)
        }
    }

    @Test fun readWritePolicyRegression_explicitHostWritesAskWithSessionGrantAcrossChannels() = runTest {
        val fixture = Fixture()
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            for (command in listOf("touch /sys/probe", "printf value > /proc/sys/kernel/hostname", "dd of=/dev/block/boot")) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                assertWriteAsk(result, "$tool: $command")
                assertTrue(result.hostSystemTargets.any { it.startsWith("WRITE:") || it.startsWith("READ_WRITE:") })
                if (tool in setOf("Bash", "terminal")) {
                    assertEquals(42L, result.hostWriteRootGeneration)
                    assertEquals("root-authority", result.hostWriteRootAuthorityId)
                    assertNull(result.rootGeneration)
                    assertNull(result.rootAuthorityId)
                } else {
                    assertNull(result.hostWriteRootGeneration)
                    assertNull(result.hostWriteRootAuthorityId)
                }
            }
        }
    }

    @Test fun readWritePolicyRegression_unknownGuestScriptsAndDynamicWritersAllowWhileHostChannelsAsk() = runTest {
        val fixture = Fixture()
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            for (command in listOf(
                "sh /root/opaque-script.sh",
                "python3 /root/opaque-script.py",
                "touch \"\$TARGET\"",
                "printf value > \"\$TARGET\"",
                "cd \"\$DIRECTORY\"; touch relative-probe"
            )) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                if (tool in setOf("Root", "Shizuku")) {
                    assertWriteAsk(result, "$tool: $command")
                } else {
                    assertEquals("$tool: $command", Verdict.ALLOW, result.verdict)
                    assertTrue("diagnostics survive guest ALLOW", result.ordinaryGuestUncertainties.isNotEmpty())
                    assertTrue(result.hostSystemUncertainties.isEmpty())
                    assertNull(result.sessionGrant)
                    assertNull(result.hostWriteRootGeneration)
                }
            }
        }
    }

    @Test fun chrootPolicy_guestBuildsScriptsRelativeWritesAndTextAllowWithoutApprovalAuthority() = runTest {
        val fixture = Fixture()
        fixture.rootState = RootAccessState(false, 43, "")
        val commands = listOf(
            "apt update", "apt-get install -y make", "npm install", "git status", "make all", "./gradlew assembleDebug",
            "python3 -c 'print(1)'", "node build.js", "awk '{print}' input.txt", "sh /root/opaque.sh",
            "./custom-build.sh", "touch relative", "printf x >build/output", "echo x >\"\$TARGET\"",
            "cd \"\$DIRECTORY\" && touch result", "touch /etc/guest", "touch /usr/local/guest", "mkdir /var/lib/guest",
            "echo '/system'", "printf '%s' '/sys/kernel'", "echo /system && npm install",
            "sh -c 'echo /system; npm install'", "cat /system/build.prop", "cp /system/source ./copy",
            "printf x >/dev/null", "python3 -c 'open(\"/dev/null\", \"w\").write(\"x\")'"
        )
        for (tool in listOf("Bash", "terminal")) {
            for (command in commands) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                assertEquals("$tool: $command", Verdict.ALLOW, result.verdict)
                assertEquals(PermissionNamespace.CHROOT_GUEST, result.permissionSource?.namespace)
                assertNull(result.sessionGrant)
                assertNull(result.approvalNote)
                assertNull(result.hostWriteRootGeneration)
                assertNull(result.hostWriteRootAuthorityId)
                assertNull(result.rootGeneration)
            }
        }
        for (path in listOf("relative/output", "/etc/guest", "/usr/local/guest", "/var/lib/guest")) {
            val result = fixture.engine.evaluate("writeFile", mapOf(
                "path" to JsonPrimitive(path), "content" to JsonPrimitive("touch /system/probe")
            ), SESSION, fixture.execution())
            assertEquals(path, Verdict.ALLOW, result.verdict)
        }
        coVerify(exactly = 0) { fixture.root.accessSnapshot() }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun chrootPolicy_hostHintedWritesAndOpaqueCodeStillRequireBoundReview() = runTest {
        val fixture = Fixture()
        val commands = listOf(
            "touch /system/probe", "cp /tmp/input /vendor/probe", "exec 3<>/system/probe",
            "ln /system/source /tmp/link", "echo x >/system/\$NAME", "touch /data/system/\$NAME",
            "cd /system/\$PART && touch probe", "python3 -c 'open(\"/system/probe\", \"w\").write(\"x\")'",
            "node -e 'require(\"fs\").writeFileSync(\"/sys/kernel/probe\", \"x\")'",
            "awk 'BEGIN {print 1 > \"/proc/sys/kernel/probe\"}'", "unknown-program --output=/system/probe",
            "./custom-script /vendor/probe", "sh /root/opaque.sh /system/probe", "python3 build.py /dev/block/boot",
            "sh -c 'echo x >\"\$1\"' sh /system/probe", "sh <<'EOF'\necho x >/system/probe\nEOF",
            "env BASH_ENV=/system/startup bash -c id", "sed 'w /system/probe' input"
        )
        for (tool in listOf("Bash", "terminal")) {
            for (command in commands) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                assertWriteAsk(result, "$tool: $command")
                assertEquals(42L, result.hostWriteRootGeneration)
                assertEquals("root-authority", result.hostWriteRootAuthorityId)
                assertNull(result.rootGeneration)
                assertNull(result.rootAuthorityId)
            }
        }
        val typed = fixture.engine.evaluate("writeFile", mapOf("path" to JsonPrimitive("/system/\$LITERAL")), SESSION)
        assertWriteAsk(typed, "typed Android path")
        assertEquals(setOf("WRITE:/system/\$LITERAL"), typed.hostSystemTargets)
    }

    @Test fun chrootPolicy_interactiveHostHintsStillAskAndExplicitDenyStillWins() = runTest {
        val fixture = Fixture()
        for (input in listOf("echo x >/system/\$NAME", "touch /sys/probe", "open('/system/probe', 'w')")) {
            val args = mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive(input), "tab_id" to JsonPrimitive("term-1"))
            assertWriteAsk(fixture.engine.evaluate("terminal", args, SESSION), input)
        }
        for ((tool, args, pattern) in listOf(
            Triple("Bash", commandArgs("npm install"), "npm"),
            Triple("terminal", mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("npm install")), "npm"),
            Triple("terminal", mapOf("action" to JsonPrimitive("key"), "key" to JsonPrimitive("enter")), "*")
        )) {
            fixture.effectiveRules = listOf(PermissionRule(tool, pattern, PermissionDecision.DENY))
            coEvery { fixture.grants.isAllowed(any(), any()) } returns true
            assertEquals(tool, Verdict.DENY, fixture.engine.evaluate(tool, args, SESSION).verdict)
        }
    }

    @Test fun chrootPolicy_guestDiagnosticsRemainFrozenAndDoNotPolluteHostWriteApprovalNote() = runTest {
        val fixture = Fixture()
        val args = commandArgs("npm install; touch /system/probe")
        val initial = fixture.engine.evaluate("Bash", args, SESSION)
        val actual = fixture.engine.evaluate("Bash", args, SESSION, fixture.execution())
        assertWriteAsk(initial, "guest build followed by host write")
        assertEquals(initial, actual)
        assertEquals(setOf("unknown-executable"), initial.ordinaryGuestUncertainties)
        assertTrue(initial.hostSystemUncertainties.isEmpty())
        assertEquals("resource:${R.string.tool_host_system_file_confirmation_reason}", initial.approvalNote)
        assertEquals(listOf("WRITE:/system/probe"), fixture.formatArgs[R.string.tool_host_system_file_confirmation_reason])
        assertTrue(runCatching { (initial.ordinaryGuestUncertainties as MutableSet<String>).clear() }.isFailure)
    }

    @Test fun reviewFix_terminalSendCaseAndWhitespaceCannotAvoidHostReview() = runTest {
        val fixture = Fixture()
        for (action in listOf("send", "SEND", " send ", "\tSeNd\n")) {
            val args = mapOf(
                "action" to JsonPrimitive(action), "input" to JsonPrimitive("touch /sys/probe"),
                "command" to JsonPrimitive("echo ignored"), "tab_id" to JsonPrimitive("term-1")
            )
            val result = fixture.engine.evaluate("terminal", args, SESSION, fixture.execution())
            assertWriteAsk(result, action)
            assertEquals(setOf("WRITE:/sys/probe"), result.hostSystemTargets)
            assertEquals(42L, result.hostWriteRootGeneration)
            assertEquals("root-authority", result.hostWriteRootAuthorityId)
            val guest = fixture.engine.evaluate("terminal", args + ("input" to JsonPrimitive("npm install")), SESSION, fixture.execution())
            assertEquals(action, Verdict.ALLOW, guest.verdict)
            assertTrue(guest.hostSystemUncertainties.isEmpty())
            assertTrue(guest.ordinaryGuestUncertainties.contains("interactive-input"))
            assertNull(guest.hostWriteRootGeneration)
        }
    }

    @Test fun reviewFix_leadingEnvironmentHostHintsBindCodeReviewButNotPrintedText() = runTest {
        val fixture = Fixture()
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            for (command in listOf(
                "TARGET=/sys/probe sh -c 'echo x >\"\$TARGET\"'",
                "TARGET=/system/probe python3 -c 'import os; open(os.environ[\"TARGET\"], \"w\").write(\"x\")'",
                "TARGET=/vendor/probe sh /root/opaque.sh", "TARGET=/system/probe ./opaque-script"
            )) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                assertWriteAsk(result, "$tool: $command")
                assertTrue(result.hostSystemUncertainties.isNotEmpty())
                if (tool in setOf("Bash", "terminal")) assertEquals(42L, result.hostWriteRootGeneration)
            }
        }
        for (tool in listOf("Bash", "terminal")) {
            for (command in listOf(
                "TARGET=/system/probe echo ordinary", "TARGET=/system/probe echo x >relative",
                "TARGET=/tmp/probe sh -c 'echo x >\"\$TARGET\"'"
            )) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                assertEquals("$tool: $command", Verdict.ALLOW, result.verdict)
                assertTrue(result.hostSystemUncertainties.isEmpty())
                assertNull(result.hostWriteRootGeneration)
            }
        }
    }

    @Test fun reviewFix_hereDocumentReviewUsesOnlyItsOwnBodyWhileHostChannelsStayStrict() = runTest {
        val fixture = Fixture()
        val cases = listOf(
            "echo /system; python3 <<'EOF'\nprint(1)\nEOF" to false,
            "cat <<'TEXT'; python3 <<'CODE'\n/system/probe\nTEXT\nprint(1)\nCODE" to false,
            "python3 <<'CODE'; cat <<'TEXT'\nprint(1)\nCODE\n/system/probe\nTEXT" to false,
            "cat <<'TEXT'; sh <<'CODE'\nordinary data\nTEXT\necho x >/system/probe\nCODE" to true,
            "python3 <<'EOF'\nopen('/system/probe', 'w')\nEOF" to true
        )
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            for ((command, hostHinted) in cases) {
                val result = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION, fixture.execution())
                if (hostHinted || tool in setOf("Root", "Shizuku")) {
                    assertWriteAsk(result, "$tool: $command")
                    assertTrue(result.hostSystemUncertainties.isNotEmpty())
                } else {
                    assertEquals("$tool: $command", Verdict.ALLOW, result.verdict)
                    assertTrue(result.hostSystemUncertainties.isEmpty())
                    assertTrue(result.ordinaryGuestUncertainties.isNotEmpty())
                    assertNull(result.hostWriteRootGeneration)
                }
            }
        }
    }

    @Test fun readWritePolicyRegression_sameSourceSharesCapturedKeyAcrossWritesAndUnknownCommands() = runTest {
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            val fixture = Fixture()
            val capturedKeys = mutableListOf<String>()
            coEvery { fixture.grants.isAllowed(SESSION, capture(capturedKeys)) } returns false
            val opaqueScript = if (tool in setOf("Root", "Shizuku")) "sh /root/opaque-script.sh" else "sh /root/opaque-script.sh /system/probe"
            val commands = listOf("touch /sys/probe-one", "printf value > /proc/sys/kernel/hostname", opaqueScript)
            val keys = commands.map { command ->
                assertWriteAsk(fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION), "$tool: $command")
            }
            assertEquals("one lookup per source approval", keys, capturedKeys)
            assertEquals("commands and targets do not split a source-wide grant", 1, keys.toSet().size)
            val key = keys.first()
            coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
            for (command in listOf("touch /sys/probe-two", "printf next > /proc/sys/kernel/domainname", "sh /root/another-opaque-script.sh")) {
                val allowed = fixture.engine.evaluate(tool, commandArgs(command, tool), SESSION)
                assertEquals("$tool: $command", Verdict.ALLOW, allowed.verdict)
                assertNull(allowed.sessionGrant)
                assertFalse(allowed.channelUnavailable)
            }
        }
    }

    @Test fun readWritePolicyRegression_scopeKeyIsHashedAndNeverContainsCommandOrTargetSecrets() = runTest {
        val fixture = Fixture()
        val secret = "private-command-marker-not-a-source-field"
        val result = fixture.engine.evaluate("Root", commandArgs("printf '$secret' > /sys/private-target-marker"), SESSION)
        val key = assertWriteAsk(result, "secret-bearing command")
        assertFalse(key.contains(secret))
        assertFalse(key.contains("private-target-marker"))
        assertFalse(key.contains("root-authority"))
        assertTrue("scope material is a hash, not concatenated identifiers", key.removePrefix(HOST_WRITE_PREFIX).matches(Regex("[0-9a-fA-F]{64}")))
    }

    @Test fun readWritePolicyRegression_differentToolsAndSessionsDoNotInheritSourceGrant() = runTest {
        val fixture = Fixture()
        val rootKey = assertWriteAsk(fixture.engine.evaluate("Root", commandArgs("touch /sys/probe"), SESSION), "Root")
        coEvery { fixture.grants.isAllowed(SESSION, rootKey) } returns true
        assertEquals(Verdict.ALLOW, fixture.engine.evaluate("Root", commandArgs("touch /sys/next"), SESSION).verdict)
        // Session identity is separately scoped by the repository lookup; it need not be in the hash.
        assertWriteAsk(fixture.engine.evaluate("Root", commandArgs("touch /sys/next"), "another-session"), "another session")
        val keys = mutableSetOf(rootKey)
        for (tool in listOf("Shizuku", "Bash", "terminal")) {
            val key = assertWriteAsk(fixture.engine.evaluate(tool, commandArgs("touch /sys/probe", tool), SESSION), tool)
            assertTrue("$tool must have its own scope", keys.add(key))
        }
        for (tool in listOf("writeFile", "editFile")) {
            val key = assertWriteAsk(fixture.engine.evaluate(tool, mapOf("path" to JsonPrimitive("/sys/probe")), SESSION), tool)
            assertTrue("$tool must have its own scope", keys.add(key))
        }
    }

    @Test fun readWritePolicyRegression_rootGenerationAndAuthorityChangesRequireFreshGrant() = runTest {
        val mutations: List<Pair<String, (RootAccessState) -> RootAccessState>> = listOf(
            "generation" to { state -> state.copy(generation = state.generation + 1) },
            "authority" to { state -> state.copy(authorityId = "replacement-authority") }
        )
        for ((label, mutate) in mutations) {
            val fixture = Fixture()
            val key = assertWriteAsk(fixture.engine.evaluate("Root", commandArgs("touch /sys/probe"), SESSION), "before $label")
            coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
            fixture.rootState = mutate(fixture.rootState)
            val changedKey = assertWriteAsk(fixture.engine.evaluate("Root", commandArgs("touch /sys/next"), SESSION), "after $label")
            assertNotEquals(label, key, changedKey)
        }
    }

    @Test fun readWritePolicyRegression_shizukuUidAndConnectionChangesRequireFreshGrant() = runTest {
        for (identity in listOf(ShizukuIdentity(0, "shell-connection"), ShizukuIdentity(2000, "replacement-connection"))) {
            val fixture = Fixture()
            val key = assertWriteAsk(fixture.engine.evaluate("Shizuku", commandArgs("touch /sys/probe"), SESSION), "original Shizuku")
            coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
            fixture.shizukuIdentity = identity
            val changedKey = assertWriteAsk(fixture.engine.evaluate("Shizuku", commandArgs("touch /sys/next"), SESSION), "changed Shizuku")
            assertNotEquals(key, changedKey)
        }
    }

    @Test fun readWritePolicyRegression_chrootProfileRevisionsInstanceAndModeAbaRequireFreshGrant() = runTest {
        val mutations: List<Pair<String, Fixture.() -> Unit>> = listOf(
            "selection revision" to { selection += 2 },
            "configuration revision" to { configuration++ },
            "profile identity" to { profile = profile.copy(id = "another-profile") },
            "instance identity" to { profile = profile.copy(chrootInstance = ChrootInstance("another-instance", 10, 20, 30)) },
            "instance device" to { profile = profile.copy(chrootInstance = ChrootInstance("instance", 11, 20, 30)) },
            "instance inode" to { profile = profile.copy(chrootInstance = ChrootInstance("instance", 10, 21, 30)) },
            "install generation" to { profile = profile.copy(chrootInstance = ChrootInstance("instance", 10, 20, 31)) },
            "mode ABA" to { mode.setMode(ExecutionMode.REMOTE_SSH); mode.setMode(ExecutionMode.LOCAL_PROOT) }
        )
        for ((label, mutate) in mutations) {
            val fixture = Fixture()
            val key = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION), "before $label")
            coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
            fixture.mutate()
            val changedKey = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("touch /sys/next"), SESSION), "after $label")
            assertNotEquals(label, key, changedKey)
        }
    }

    @Test fun readWritePolicyRegression_chrootHostWritesAlsoBindCurrentRootGenerationAndAuthority() = runTest {
        for (state in listOf(RootAccessState(true, 43, "root-authority"), RootAccessState(true, 42, "replacement-authority"))) {
            val fixture = Fixture()
            val key = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION), "original CHROOT authority")
            coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
            fixture.rootState = state
            val result = fixture.engine.evaluate("Bash", commandArgs("touch /sys/next"), SESSION)
            val changedKey = assertWriteAsk(result, "changed CHROOT authority")
            assertNotEquals(key, changedKey)
            assertNull("guest command must not acquire Root-command generation metadata", result.rootGeneration)
            assertNull(result.rootAuthorityId)
        }
    }

    @Test fun readWritePolicyRegression_chrootRootOffOnCannotResurrectOldSourceGrant() = runTest {
        val fixture = Fixture()
        val key = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION), "before Root off")
        coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
        fixture.rootState = RootAccessState(false, 43, "root-authority")
        val disabled = fixture.engine.evaluate("Bash", commandArgs("touch /sys/next"), SESSION)
        assertEquals(Verdict.DENY, disabled.verdict)
        assertTrue(disabled.channelUnavailable)
        assertNull(disabled.sessionGrant)
        fixture.rootState = RootAccessState(true, 44, "root-authority")
        val newKey = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("sh /root/opaque-script.sh /system/probe"), SESSION), "after Root on")
        assertNotEquals(key, newKey)
    }

    @Test fun readWritePolicyRegression_trueHostSourcesDoNotChangeWhenGuestSelectionChanges() = runTest {
        for (tool in listOf("Root", "Shizuku")) {
            val fixture = Fixture()
            val key = assertWriteAsk(fixture.engine.evaluate(tool, commandArgs("touch /sys/probe", tool), SESSION), "original $tool")
            fixture.selection += 2
            fixture.configuration++
            fixture.profile = ContainerProfile.BUILTIN_ALPINE
            fixture.mode.setMode(ExecutionMode.REMOTE_SSH)
            val changedWorkspaceKey = assertWriteAsk(fixture.engine.evaluate(tool, commandArgs("touch /sys/next", tool), SESSION), "same host $tool")
            assertEquals(key, changedWorkspaceKey)
            coVerify(exactly = 0) { fixture.settings.captureProfileSnapshot() }
            verify(exactly = 0) { fixture.settings.checkProfileSnapshot(any()) }
        }
    }

    @Test fun readWritePolicyRegression_explicitDenyPrecedesAnyBroadGrantAndAuthorityLookup() = runTest {
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            for (pattern in listOf("*", "touch")) {
                val fixture = Fixture()
                fixture.effectiveRules = listOf(PermissionRule(tool, pattern, PermissionDecision.DENY))
                coEvery { fixture.grants.isAllowed(any(), any()) } returns true
                val result = fixture.engine.evaluate(tool, commandArgs("touch /sys/probe", tool), SESSION)
                assertEquals("$tool: $pattern", Verdict.DENY, result.verdict)
                assertFalse(result.channelUnavailable)
                assertNull(result.sessionGrant)
                coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
                coVerify(exactly = 0) { fixture.root.accessSnapshot() }
                verify(exactly = 0) { fixture.shizuku.accessSnapshot() }
            }
        }
        val fixture = Fixture()
        fixture.effectiveRules = listOf(PermissionRule("writeFile", "*", PermissionDecision.DENY))
        coEvery { fixture.grants.isAllowed(any(), any()) } returns true
        assertEquals(Verdict.DENY, fixture.engine.evaluate("writeFile", mapOf("path" to JsonPrimitive("/sys/probe")), SESSION).verdict)
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_readonlyAllowAndBroadGrantNeverBypassRealHostAuthority() = runTest {
        for (state in listOf(RootAccessState(false, 43, "root-authority"), RootAccessState(true, 44, ""), RootAccessState(true, 44, " \t"))) {
            val fixture = Fixture()
            fixture.rootState = state
            coEvery { fixture.grants.isAllowed(any(), any()) } returns true
            for (command in listOf("cat /system/build.prop", "touch /sys/probe", "sh /root/opaque-script.sh")) {
                val result = fixture.engine.evaluate("Root", commandArgs(command), SESSION)
                assertEquals(command, Verdict.DENY, result.verdict)
                assertTrue(result.channelUnavailable)
                assertNull(result.sessionGrant)
            }
            coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
        }
        val fixture = Fixture()
        fixture.shizukuIdentity = null
        coEvery { fixture.grants.isAllowed(any(), any()) } returns true
        for (command in listOf("cat /system/build.prop", "touch /sys/probe", "sh /root/opaque-script.sh")) {
            val result = fixture.engine.evaluate("Shizuku", commandArgs(command), SESSION)
            assertEquals(command, Verdict.DENY, result.verdict)
            assertTrue(result.channelUnavailable)
            assertNull(result.shizukuIdentity)
        }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_chrootHostWritesAndHostLinkedUnknownRejectUnavailableRootBeforeGrant() = runTest {
        for (state in listOf(RootAccessState(false, 43, "root-authority"), RootAccessState(true, 44, ""))) {
            val fixture = Fixture()
            fixture.rootState = state
            coEvery { fixture.grants.isAllowed(any(), any()) } returns true
            for (command in listOf("touch /sys/probe", "sh /root/opaque-script.sh /system/probe", "echo x >/system/\$NAME")) {
                val result = fixture.engine.evaluate("Bash", commandArgs(command), SESSION)
                assertEquals(command, Verdict.DENY, result.verdict)
                assertTrue(result.channelUnavailable)
                assertNull(result.sessionGrant)
                assertNull(result.rootGeneration)
                assertNull(result.rootAuthorityId)
            }
            coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
        }
    }

    @Test fun readWritePolicyRegression_provenGuestOperationsRemainIndependentOfRootAccess() = runTest {
        val fixture = Fixture()
        fixture.rootState = RootAccessState(false, 43, "")
        for (command in listOf("id", "cat /etc/passwd", "touch /usr/bin/guest-probe", "rm -rf /etc/guest-probe")) {
            val result = fixture.engine.evaluate("Bash", commandArgs(command), SESSION)
            assertEquals(command, Verdict.ALLOW, result.verdict)
            assertTrue(result.hostSystemTargets.isEmpty())
            assertNull(result.sessionGrant)
        }
        val typedGuest = fixture.engine.evaluate("writeFile", mapOf(
            "path" to JsonPrimitive("/etc/guest-probe"), "content" to JsonPrimitive("/sys/this-is-only-content")
        ), SESSION)
        assertEquals(Verdict.ALLOW, typedGuest.verdict)
        assertTrue(typedGuest.hostSystemTargets.isEmpty())
        coVerify(exactly = 0) { fixture.root.accessSnapshot() }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_absentSessionCannotAcquireOrUseBroadScopeGrant() = runTest {
        val fixture = Fixture()
        coEvery { fixture.grants.isAllowed(any(), any()) } returns true
        for (session in listOf(null, "", "  ")) {
            for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
                val result = fixture.engine.evaluate(tool, commandArgs("touch /sys/probe", tool), session)
                assertEquals("$tool session=$session", Verdict.ASK, result.verdict)
                assertNull(result.sessionGrant)
            }
        }
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_legacyExactAndChannelGrantsNeverAuthorizeNewHostScope() = runTest {
        val fixture = Fixture()
        val command = "touch /sys/probe"
        val legacy = setOf("root:$command", "elevate:$command", "tool:Root", "tool:Shizuku", "tool:Shizuku:2000:shell-connection")
        val capturedKeys = mutableListOf<String>()
        coEvery { fixture.grants.isAllowed(SESSION, capture(capturedKeys)) } answers { secondArg<String>() in legacy }
        for (tool in listOf("Root", "Shizuku", "Bash", "terminal")) {
            assertWriteAsk(fixture.engine.evaluate(tool, commandArgs(command, tool) + ("elevate" to JsonPrimitive(true)), SESSION), tool)
        }
        assertEquals(4, capturedKeys.size)
        assertTrue(capturedKeys.all { it.startsWith(HOST_WRITE_PREFIX) && it !in legacy })
    }

    @Test fun readWritePolicyRegression_initialAndActualChrootWriteAssessmentsRetainEqualFrozenProof() = runTest {
        val fixture = Fixture()
        val args = commandArgs("touch /sys/probe")
        val initial = fixture.engine.evaluate("Bash", args, SESSION)
        val actual = fixture.engine.evaluate("Bash", args, SESSION, fixture.execution())
        assertWriteAsk(initial, "initial write")
        assertWriteAsk(actual, "actual write")
        assertEquals(initial.permissionSource, actual.permissionSource)
        assertEquals(initial.sessionGrant, actual.sessionGrant)
        assertEquals(initial.hostSystemTargets, actual.hostSystemTargets)
        assertEquals(initial.hostSystemUncertainties, actual.hostSystemUncertainties)
        assertEquals(initial.hostWriteRootGeneration, actual.hostWriteRootGeneration)
        assertEquals(initial.hostWriteRootAuthorityId, actual.hostWriteRootAuthorityId)
        assertNull(initial.rootGeneration)
        assertNull(actual.rootGeneration)
    }

    @Test fun readWritePolicyRegression_scopeFramingDoesNotCollideAcrossAdjacentIdentityFields() = runTest {
        val fixture = Fixture()
        var livePath = "/root/workspace/a:b"
        val workspace = mockk<Workspace> { every { path } answers { livePath } }
        fixture.workspaceSelections.value = WorkspaceRepository.Selection(workspace)
        fixture.rootState = fixture.rootState.copy(authorityId = "c")
        val first = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION), "colon in workspace path")
        livePath = "/root/workspace/a"
        fixture.rootState = fixture.rootState.copy(authorityId = "b:c")
        val second = assertWriteAsk(fixture.engine.evaluate("Bash", commandArgs("touch /sys/next"), SESSION), "colon in authority")
        // Naive path + ':' + authority yields the same text for these two different sources.
        assertNotEquals(first, second)
    }

    @Test fun readWritePolicyRegression_knownWriteApprovalNoteNeverAsksForReadEvidence() = runTest {
        val fixture = Fixture()
        val result = fixture.engine.evaluate("Root", commandArgs("cat /system/build.prop; touch /sys/probe"), SESSION)
        assertWriteAsk(result, "read and write evidence")
        assertTrue(result.hostSystemUncertainties.isEmpty())
        assertEquals("resource:${R.string.tool_host_system_file_confirmation_reason}", result.approvalNote)
        assertEquals(listOf("WRITE:/sys/probe"), fixture.formatArgs[R.string.tool_host_system_file_confirmation_reason])
        assertTrue(result.hostSystemTargets.contains("READ:/system/build.prop"))
    }

    @Test fun readWritePolicyRegression_chrootWorkspaceSelectionAbaRequiresFreshGrant() = runTest {
        val fixture = Fixture()
        val original = fixture.workspaceSelections.value
        val first = fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION)
        val key = assertWriteAsk(first, "original workspace selection")
        coEvery { fixture.grants.isAllowed(SESSION, key) } returns true
        fixture.workspaceSelections.value = WorkspaceRepository.Selection(Workspace("other", "/root/workspace/other"))
        fixture.workspaceSelections.value = WorkspaceRepository.Selection(original.workspace)
        val changed = fixture.engine.evaluate("Bash", commandArgs("sh /root/opaque-script.sh /system/probe"), SESSION)
        assertNotEquals(key, assertWriteAsk(changed, "workspace selection ABA"))
        assertEquals(first.permissionSource?.workspacePath, changed.permissionSource?.workspacePath)
        assertNotEquals(first.permissionSource?.workspaceSelectionId, changed.permissionSource?.workspaceSelectionId)
    }

    @Test fun readWritePolicyRegression_chrootWorkspaceChangeDuringCaptureRejectsTornSource() = runTest {
        val fixture = Fixture()
        coEvery { fixture.settings.captureProfileSnapshot() } answers {
            val original = fixture.workspaceSelections.value
            fixture.workspaceSelections.value = WorkspaceRepository.Selection(Workspace("other", "/root/workspace/other"))
            fixture.workspaceSelections.value = WorkspaceRepository.Selection(original.workspace)
            fixture.snapshot()
        }
        assertTrue(runCatching {
            fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION)
        }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_profileOrWorkspaceChangeDuringGrantLookupRejectsAllow() = runTest {
        for (changeProfile in listOf(false, true)) {
            val fixture = Fixture()
            coEvery { fixture.grants.isAllowed(any(), any()) } answers {
                if (changeProfile) fixture.configuration++ else {
                    fixture.workspaceSelections.value = WorkspaceRepository.Selection(fixture.workspaceSelections.value.workspace)
                }
                true
            }
            assertTrue(runCatching {
                fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION)
            }.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun readWritePolicyRegression_frozenWorkspaceCannotBeReplacedWithCurrentSelection() = runTest {
        val fixture = Fixture()
        val frozen = fixture.execution()
        fixture.workspaceSelections.value = WorkspaceRepository.Selection(Workspace("other", "/root/workspace/other"))
        assertTrue(runCatching {
            fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION, frozen)
        }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_incompleteChrootScopeOffersOnlyOnceWithoutGrantLookup() = runTest {
        val missingWorkspace = Fixture(includeWorkspace = false)
        val missingInstance = Fixture().also { it.profile = it.profile.copy(chrootInstance = null) }
        val missingPath = Fixture().also { it.workspaceSelections.value = WorkspaceRepository.Selection(null) }
        for (fixture in listOf(missingWorkspace, missingInstance, missingPath)) {
            coEvery { fixture.grants.isAllowed(any(), any()) } returns true
            val result = fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION)
            assertEquals(Verdict.ASK, result.verdict)
            assertNull(result.sessionGrant)
            assertEquals(42L, result.hostWriteRootGeneration)
            assertFalse(result.channelUnavailable)
            coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
        }
        val fixture = Fixture()
        coEvery { fixture.grants.isAllowed(any(), any()) } returns true
        val noFrozenWorkspace = fixture.engine.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION, fixture.execution(includeFrozenWorkspace = false))
        assertEquals(Verdict.ASK, noFrozenWorkspace.verdict)
        assertNull(noFrozenWorkspace.sessionGrant)
        val withoutConfigurationProvider = ToolPermissionPolicyEngine(
            fixture.rules, fixture.grants, fixture.context, fixture.root, fixture.shizuku,
            fixture.mode, null, fixture.workspaces
        )
        val noConfiguration = withoutConfigurationProvider.evaluate("Bash", commandArgs("touch /sys/probe"), SESSION, fixture.execution())
        assertEquals(Verdict.ASK, noConfiguration.verdict)
        assertNull(noConfiguration.sessionGrant)
        coVerify(exactly = 0) { fixture.grants.isAllowed(any(), any()) }
    }

    @Test fun readWritePolicyRegression_uncertaintyEvidenceIsFrozenAndApprovalNoteExcludesReadTargets() = runTest {
        val fixture = Fixture()
        val result = fixture.engine.evaluate("Root", commandArgs("cat /system/build.prop; sh /root/opaque-script.sh"), SESSION)
        assertWriteAsk(result, "unknown script with read evidence")
        assertTrue(result.hostSystemUncertainties.isNotEmpty())
        val frozen = result.hostSystemUncertainties.toSet()
        assertTrue(runCatching {
            @Suppress("UNCHECKED_CAST")
            (result.hostSystemUncertainties as MutableSet<String>).add("injected-uncertainty")
        }.isFailure)
        assertEquals(frozen, result.hostSystemUncertainties)
        assertEquals("resource:${R.string.tool_host_system_file_uncertain_reason}", result.approvalNote)
        val noteEvidence = fixture.formatArgs[R.string.tool_host_system_file_uncertain_reason].orEmpty().joinToString()
        assertTrue(noteEvidence.isNotBlank())
        assertFalse(noteEvidence.contains("READ:/system/build.prop"))
        assertTrue(result.hostSystemTargets.contains("READ:/system/build.prop"))
    }

    @Test fun readWritePolicyRegression_hostTargetEvidenceIsFrozen() = runTest {
        val fixture = Fixture()
        val result = fixture.engine.evaluate("Root", commandArgs("touch /sys/probe"), SESSION)
        assertEquals(setOf("WRITE:/sys/probe"), result.hostSystemTargets)
        assertTrue(runCatching {
            @Suppress("UNCHECKED_CAST")
            (result.hostSystemTargets as MutableSet<String>).add("WRITE:/sys/injected")
        }.isFailure)
        assertEquals(setOf("WRITE:/sys/probe"), result.hostSystemTargets)
    }
}
