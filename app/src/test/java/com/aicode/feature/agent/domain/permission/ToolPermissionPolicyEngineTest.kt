package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine.Verdict
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPermissionPolicyEngineTest {

    private val formatArgs = mutableMapOf<Int, List<String>>()
    private val rootManager = mockk<RootCommandManager> {
        coEvery { accessSnapshot() } returns RootAccessState(false, 0)
    }
    private val grants = mockk<SessionPermissionGrantRepository> {
        coEvery { isAllowed(any(), any()) } returns false
    }
    private val context = mockk<Context> {
        every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
        every { getString(any(), *anyVararg()) } answers {
            formatArgs[firstArg<Int>()] = secondArg<Array<Any?>>().map { it.toString() }
            "resource:${firstArg<Int>()}"
        }
    }

    private fun engine(vararg rules: PermissionRule): ToolPermissionPolicyEngine {
        val repository = mockk<PermissionRulesRepository> {
            coEvery { loadEffectiveForCurrentProject() } returns rules.toList()
        }
        return ToolPermissionPolicyEngine(repository, grants, context, rootManager)
    }

    private fun commandArgs(
        command: String,
        toolName: String = "Bash",
        elevate: Boolean = false
    ): Map<String, JsonElement> = buildMap {
        put("command", JsonPrimitive(command))
        if (toolName == "terminal") put("action", JsonPrimitive("start"))
        if (elevate) put("elevate", JsonPrimitive(true))
    }

    @Test
    fun ordinaryTools_withoutDeny_areAutomaticallyAllowed() = runTest {
        val evaluator = engine()
        val calls = listOf(
            "readFile" to emptyMap(),
            "writeFile" to emptyMap(),
            "editFile" to emptyMap(),
            "mcp__server__write" to emptyMap(),
            "task" to mapOf("action" to JsonPrimitive("read")),
            "task" to mapOf("action" to JsonPrimitive("del")),
            "terminal" to mapOf("action" to JsonPrimitive("read")),
            "terminal" to mapOf("action" to JsonPrimitive("close")),
            "customTool" to emptyMap()
        )

        for ((toolName, args) in calls) {
            val result = evaluator.evaluate(toolName, args)

            assertEquals("$toolName $args", Verdict.ALLOW, result.verdict)
            assertNull(result.sessionGrant)
            assertNull(result.denyReason)
        }
    }

    @Test
    fun ordinaryShellCommands_withoutDeny_areAutomaticallyAllowed() = runTest {
        val evaluator = engine()
        val commands = listOf(
            "ls -la",
            "git push origin main",
            "ls && rm file.txt",
            "rm -rf ~/workspace/build",
            "echo \$(whoami)",
            "echo `date`",
            "ls \$(pwd)",
            "(echo hello)"
        )

        for (toolName in listOf("Bash", "terminal")) {
            for (command in commands) {
                val result = evaluator.evaluate(toolName, commandArgs(command, toolName))

                assertEquals("$toolName: $command", Verdict.ALLOW, result.verdict)
                assertNull(result.sessionGrant)
            }
        }
    }

    @Test
    fun nullRedirects_allowOrdinaryGroupedAndReadOnlySubstitutionProbes() = runTest {
        val commands = listOf(
            "uname -a; id 2>/dev/null; command -v sh 2>/dev/null",
            "id >/dev/null", "id 2>>'/dev/null'", "id >\"/dev/null\"",
            "id >/dev/'null'", "id &>/dev/null", "id &>>/dev/null", "id >&/dev/null",
            "(uname -a; id 2>/dev/null)", "(id) >/dev/null 2>&1", "{ id 2>/dev/null; }",
            "(id 2>&1)", "(id 2>&-)", "(id >/dev/null 2>&1)",
            "echo \"\$(id 2>/dev/null)\"", "echo `id 2>/dev/null`", "ls \$(pwd) 2>/dev/null",
            "sh -c '(id 2>/dev/null)'", "(printf '%s' '>')", "echo \"\$(printf '>')\"",
            "if true; then id 2>/dev/null; fi", "echo \"\$(echo \"\$(id 2>/dev/null)\")\""
        )
        val evaluator = engine()

        for (toolName in listOf("Bash", "terminal")) {
            for (command in commands) {
                val result = evaluator.evaluate(toolName, commandArgs(command, toolName), "s1")

                assertEquals("$toolName: $command", Verdict.ALLOW, result.verdict)
                assertNull(result.denyReason)
                assertNull(result.sessionGrant)
            }
        }
        assertEquals(Verdict.ALLOW, evaluator.evaluate(
            "terminal",
            mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("(id 2>/dev/null)")),
            "s1"
        ).verdict)
    }

    @Test
    fun nullRedirects_doNotMaskFileWritesDynamicTargetsOrUnknownRedirects() = runTest {
        coEvery { grants.isAllowed(any(), any()) } returns true
        val commands = listOf(
            "id >/dev/nullsuffix", "id 2>>'/dev/nullsuffix'", "id >\"/tmp/probe\"", "id <>/etc/passwd",
            "id >/etc/passwd 2>/dev/null", "id 2>/dev/null >/tmp/probe",
            "id >/dev/null; id > /etc/passwd", "(id > /tmp/probe) 2>/dev/null",
            "(id > probe.txt) 2>/dev/null", "{ id > probe.txt; } 2>/dev/null",
            "echo \"\$(id > /etc/passwd)\" 2>/dev/null", "echo `id >/tmp/probe` 2>/dev/null",
            "sh -c 'id >/etc/passwd' 2>/dev/null", "id >\$TARGET 2>/dev/null",
            "id >/dev/null\$SUFFIX", "id >\"/dev/null\$(printf '')\"", "id >`printf /dev/null`",
            "id 2>&\$FD >/dev/null", "id 2>&/dev/null", "id >/dev/null >", "id >/dev/null\\",
            "id {fd}>/dev/null", "if true; then id > probe.txt; fi 2>/dev/null",
            "echo \"\$(echo \"\$(id >/tmp/probe)\")\" 2>/dev/null"
        )
        val evaluator = engine()

        for (toolName in listOf("Bash", "terminal")) {
            for (command in commands) {
                val result = evaluator.evaluate(toolName, commandArgs(command, toolName), "s1")

                assertEquals("$toolName: $command", Verdict.DENY, result.verdict)
                assertEquals("resource:${R.string.tool_safety_denied_with_elevation}", result.denyReason)
                assertNull(result.sessionGrant)
            }
        }
    }

    @Test
    fun nullRedirects_doNotMaskDangerousDeletionsOrDestructiveSubstitutions() = runTest {
        coEvery { grants.isAllowed(any(), any()) } returns true
        val evaluator = engine()
        for (command in listOf(
            "rm -rf / 2>/dev/null", "2>/dev/null rm -rf /etc", "(rm -rf /etc 2>/dev/null)",
            "id >/dev/null; rm -rf /root/workspace", "rm -rf \$(pwd)/build 2>/dev/null",
            "rm -rf `pwd`/build 2>/dev/null", "rm -rf \"\$TARGET\" 2>/dev/null",
            "'/bin/rm' -rf \"\$TARGET\" 2>/dev/null", "(rm -rf build) 2>/dev/null",
            "echo \"\$(rm -rf /tmp/build)\" 2>/dev/null", "echo `rm -rf /tmp/build` >/dev/null",
            "(dd if=input of=output) 2>/dev/null", "(find . -delete) >/dev/null",
            "{ rm -rf /etc; } 2>/dev/null", "if true; then rm -rf build 2>/dev/null; fi",
            "echo \"\$(id)\"; rm -rf build 2>/dev/null"
        )) {
            assertEquals(command, Verdict.DENY, evaluator.evaluate("Bash", commandArgs(command), "s1").verdict)
        }
    }

    @Test
    fun nullRedirects_preserveExplicitDenyPriorityAcrossShellTools() = runTest {
        coEvery { rootManager.accessSnapshot() } returns RootAccessState(true, 42)
        coEvery { grants.isAllowed(any(), any()) } returns true
        for (toolName in listOf("Bash", "terminal", "Shizuku", "Root")) {
            val evaluator = engine(PermissionRule(toolName, "id", PermissionDecision.DENY))
            for (command in listOf(
                "id 2>/dev/null", "2>/dev/null id", "(id 2>/dev/null)", "echo \"\$(id 2>/dev/null)\"",
                "id >/etc/passwd 2>/dev/null", "{ id 2>/dev/null; }", "if true; then id 2>/dev/null; fi"
            )) {
                val result = evaluator.evaluate(toolName, commandArgs(command, toolName, elevate = true), "s1")

                assertEquals("$toolName: $command", Verdict.DENY, result.verdict)
                assertEquals("resource:${R.string.tool_command_rule_denied_detail}", result.denyReason)
                assertNull(result.sessionGrant)
            }
        }
    }

    @Test
    fun nullRedirects_preserveRootShizukuAndExactSessionApprovals() = runTest {
        val evaluator = engine()
        val command = "sudo id 2>/dev/null"
        val approval = evaluator.evaluate("Bash", commandArgs(command), "s1")
        assertEquals(Verdict.ASK, approval.verdict)
        assertEquals("root:$command", approval.sessionGrant)

        coEvery { grants.isAllowed("s1", "root:$command") } returns true
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs(command), "s1").verdict)
        assertEquals(Verdict.ASK, evaluator.evaluate("Bash", commandArgs(command), "s2").verdict)
        val otherCommand = "sudo id 2>>/dev/null"
        val other = evaluator.evaluate("Bash", commandArgs(otherCommand), "s1")
        assertEquals(Verdict.ASK, other.verdict)
        assertEquals("root:$otherCommand", other.sessionGrant)

        val shizuku = evaluator.evaluate("Shizuku", commandArgs("id 2>/dev/null"), "s1")
        assertEquals(Verdict.ASK, shizuku.verdict)
        assertEquals("tool:Shizuku", shizuku.sessionGrant)
        coEvery { grants.isAllowed("s1", "tool:Shizuku") } returns true
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Shizuku", commandArgs("id 2>/dev/null"), "s1").verdict)
        assertEquals(Verdict.DENY, evaluator.evaluate("Shizuku", commandArgs(command, elevate = true), "s1").verdict)

        coEvery { rootManager.accessSnapshot() } returns RootAccessState(true, 42)
        coEvery { grants.isAllowed(any(), any()) } returns true
        for (rootCommand in listOf("id 2>/dev/null", "rm -rf /data 2>/dev/null")) {
            val result = evaluator.evaluate("Root", commandArgs(rootCommand, elevate = true), "s1")
            assertEquals(rootCommand, Verdict.ASK, result.verdict)
            assertEquals(42L, result.rootGeneration)
            assertNull(result.sessionGrant)
        }
        assertEquals(Verdict.DENY, evaluator.evaluate("Root", commandArgs("rm -rf /data 2>/dev/null"), "s1").verdict)
    }

    @Test
    fun legacyAllowRules_doNotChangeOrdinaryOrPrivilegedVerdicts() = runTest {
        val evaluator = engine(
            PermissionRule("writeFile", "*", PermissionDecision.ALLOW),
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW),
            PermissionRule("Bash", "sudo", PermissionDecision.ALLOW),
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW),
            PermissionRule("Shizuku", "*", PermissionDecision.ALLOW)
        )

        assertEquals(Verdict.ALLOW, evaluator.evaluate("writeFile", emptyMap()).verdict)
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs("git pull origin main")).verdict)
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs("git clone repository")).verdict)
        assertEquals(Verdict.ASK, evaluator.evaluate("Bash", commandArgs("sudo id"), "s1").verdict)
        assertEquals(Verdict.ASK, evaluator.evaluate("Shizuku", commandArgs("pm list packages"), "s1").verdict)
        assertEquals(Verdict.DENY, evaluator.evaluate("Bash", commandArgs("rm -rf /"), "s1").verdict)
    }

    @Test
    fun wholeToolDeny_overridesAllowSessionGrantsAndElevation() = runTest {
        coEvery { grants.isAllowed(any(), any()) } returns true
        val cases = listOf(
            "Bash" to commandArgs("sudo id", elevate = true),
            "terminal" to commandArgs("sudo id", "terminal", elevate = true),
            "terminal" to mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("su -c id")),
            "terminal" to mapOf("action" to JsonPrimitive("read")),
            "Shizuku" to commandArgs("pm list packages", elevate = true),
            "readFile" to emptyMap(),
            "writeFile" to emptyMap(),
            "mcp__server__write" to emptyMap(),
            "task" to mapOf("action" to JsonPrimitive("del"))
        )

        for ((toolName, args) in cases) {
            val result = engine(
                PermissionRule(toolName, "*", PermissionDecision.ALLOW),
                PermissionRule(toolName, "*", PermissionDecision.DENY)
            ).evaluate(toolName, args, "s1")

            assertEquals("$toolName $args", Verdict.DENY, result.verdict)
            assertNotNull(result.denyReason)
            assertNull(result.sessionGrant)
        }
    }

    @Test
    fun wholeToolDeny_appliesWhenShellCommandIsMissingOrEmpty() = runTest {
        for (toolName in listOf("Bash", "terminal", "Shizuku")) {
            val evaluator = engine(PermissionRule(toolName, "*", PermissionDecision.DENY))
            val missingCommand = if (toolName == "terminal") {
                mapOf("action" to JsonPrimitive("start"))
            } else {
                emptyMap()
            }

            assertEquals(toolName, Verdict.DENY, evaluator.evaluate(toolName, missingCommand).verdict)
            assertEquals(toolName, Verdict.DENY, evaluator.evaluate(toolName, commandArgs("", toolName)).verdict)
        }
    }

    @Test
    fun commandSegmentDeny_overridesRootShizukuAndElevationGrants() = runTest {
        coEvery { grants.isAllowed(any(), any()) } returns true
        val cases = listOf(
            Triple("Bash", "git push", "sudo id && git push origin main"),
            Triple("terminal", "git push", "sudo id && git push origin main"),
            Triple("Shizuku", "pm", "ls && pm list packages"),
            Triple("Bash", "rm", "rm -rf /"),
            Triple("terminal", "rm", "rm -rf \$(pwd)/build")
        )

        for ((toolName, pattern, command) in cases) {
            val result = engine(
                PermissionRule(toolName, "*", PermissionDecision.ALLOW),
                PermissionRule(toolName, pattern, PermissionDecision.DENY)
            ).evaluate(toolName, commandArgs(command, toolName, elevate = true), "s1")

            assertEquals("$toolName: $command", Verdict.DENY, result.verdict)
            assertNull(result.sessionGrant)
        }
    }

    @Test
    fun terminalSend_checksInputCommandAgainstDenyRules() = runTest {
        val result = engine(PermissionRule("terminal", "git push", PermissionDecision.DENY)).evaluate(
            "terminal",
            mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("echo ready; git push origin main")),
            "s1"
        )

        assertEquals(Verdict.DENY, result.verdict)
    }

    @Test
    fun terminalActions_checkOnlyTheCommandFieldThatWillBeExecuted() = runTest {
        val evaluator = engine(PermissionRule("terminal", "git push", PermissionDecision.DENY))
        for ((action, executableKey, ignoredKey) in listOf(
            Triple("start", "command", "input"),
            Triple("send", "input", "command")
        )) {
            val denied = evaluator.evaluate(
                "terminal",
                mapOf(
                    "action" to JsonPrimitive(action),
                    executableKey to JsonPrimitive("git push origin main"),
                    ignoredKey to JsonPrimitive("echo ready")
                ),
                "s1"
            )
            val allowed = evaluator.evaluate(
                "terminal",
                mapOf(
                    "action" to JsonPrimitive(action),
                    executableKey to JsonPrimitive("echo ready"),
                    ignoredKey to JsonPrimitive("sudo id")
                ),
                "s1"
            )

            assertEquals(action, Verdict.DENY, denied.verdict)
            assertEquals(action, Verdict.ALLOW, allowed.verdict)
        }
    }

    @Test
    fun denyRules_areScopedToToolAndCommandTokenPrefix() = runTest {
        val evaluator = engine(
            PermissionRule("Bash", "git push", PermissionDecision.DENY),
            PermissionRule("writeFile", "*", PermissionDecision.DENY)
        )

        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs("git pull origin main")).verdict)
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs("echo 'git push origin main'")).verdict)
        assertEquals(Verdict.ALLOW, evaluator.evaluate("terminal", commandArgs("git push origin main", "terminal")).verdict)
        assertEquals(Verdict.ALLOW, evaluator.evaluate("readFile", emptyMap()).verdict)
    }

    @Test
    fun shellDeny_reportsMatchedRuleAndOffendingSegment() = runTest {
        val result = engine(PermissionRule("Bash", "git push", PermissionDecision.DENY)).evaluate(
            "Bash",
            commandArgs("ls && git push origin main")
        )

        assertEquals(Verdict.DENY, result.verdict)
        assertEquals("resource:${R.string.tool_command_rule_denied_detail}", result.denyReason)
        assertEquals(
            listOf("Bash(git push)", "git push origin main"),
            formatArgs[R.string.tool_command_rule_denied_detail]
        )
    }

    @Test
    fun shizuku_defaultsToAskAndOffersToolScopedSessionGrant() = runTest {
        val result = engine().evaluate("Shizuku", commandArgs("pm list packages"), "s1")

        assertEquals(Verdict.ASK, result.verdict)
        assertEquals("tool:Shizuku", result.sessionGrant)
        assertNotNull(result.approvalNote)
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun shizukuGrant_allowsDifferentCommandsOnlyInGrantedSession() = runTest {
        coEvery { grants.isAllowed("s1", "tool:Shizuku") } returns true
        val evaluator = engine()

        assertEquals(Verdict.ALLOW, evaluator.evaluate("Shizuku", commandArgs("pm list packages"), "s1").verdict)
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Shizuku", commandArgs("am get-current-user"), "s1").verdict)
        assertEquals(Verdict.ASK, evaluator.evaluate("Shizuku", commandArgs("pm list packages"), "s2").verdict)
    }

    @Test
    fun rootCommands_includingWrappers_offerExactCommandSessionGrant() = runTest {
        val commands = listOf(
            "sudo id",
            "su -c id",
            "/usr/bin/sudo id",
            "/system/bin/su -c id",
            "FOO=bar sudo id",
            "env FOO=bar sudo id",
            "command sudo id",
            "exec su -c id",
            "sh -c 'sudo id'",
            "/bin/bash -lc 'su -c id'"
        )
        val evaluator = engine()

        for (toolName in listOf("Bash", "terminal")) {
            for (command in commands) {
                val result = evaluator.evaluate(toolName, commandArgs(command, toolName), "s1")

                assertEquals("$toolName: $command", Verdict.ASK, result.verdict)
                assertEquals("root:$command", result.sessionGrant)
                assertEquals("resource:${R.string.tool_root_confirmation_title}", result.askTitle)
                assertEquals("resource:${R.string.tool_root_confirmation_reason}", result.approvalNote)
            }
        }
    }

    @Test
    fun terminalSend_rootCommandOffersExactInputGrant() = runTest {
        val result = engine().evaluate(
            "terminal",
            mapOf("action" to JsonPrimitive("send"), "input" to JsonPrimitive("su -c id")),
            "s1"
        )

        assertEquals(Verdict.ASK, result.verdict)
        assertEquals("root:su -c id", result.sessionGrant)
    }

    @Test
    fun rootGrant_isScopedToSessionAndExactFullCommand() = runTest {
        coEvery { grants.isAllowed("s1", "root:sudo id") } returns true
        val evaluator = engine()

        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs("sudo id"), "s1").verdict)
        for (command in listOf("sudo id -u", "sudo id && echo extra", "sudo  id", "sudo id ")) {
            val result = evaluator.evaluate("Bash", commandArgs(command), "s1")

            assertEquals(command, Verdict.ASK, result.verdict)
            assertEquals("root:$command", result.sessionGrant)
        }
        assertEquals(Verdict.ASK, evaluator.evaluate("Bash", commandArgs("sudo id"), "s2").verdict)
    }

    @Test
    fun sudoAndSuAsOrdinaryArguments_doNotRequireApproval() = runTest {
        val evaluator = engine()
        for (command in listOf("echo sudo", "echo su", "echo sudoer", "printf '%s' 'sudo id'", "sh -c 'echo sudo'")) {
            val result = evaluator.evaluate("Bash", commandArgs(command), "s1")

            assertEquals(command, Verdict.ALLOW, result.verdict)
            assertNull(result.sessionGrant)
        }
    }

    @Test
    fun catastrophicRm_protectsRootSystemHomeAndWorkspaceRoots() = runTest {
        val commandsByReason = listOf(
            R.string.tool_safety_delete_root to listOf("rm -rf /", "rm -rf /*", "rm -rf /foo/../"),
            R.string.tool_safety_delete_wildcard to listOf("rm -rf .", "rm -rf ../*"),
            R.string.tool_safety_delete_system to listOf(
                "rm -rf /etc", "rm -rf /usr/bin", "rm -rf /srv", "rm -rf /mnt", "rm -rf \${HOME}/foo",
                "rm -rf //etc", "rm -rf /./etc", "rm -rf /etc/../etc", "rm -rf /tmp/../etc",
                "rm -rf /et*", "rm -rf /usr*"
            ),
            R.string.tool_safety_delete_tmp to listOf("rm -rf /tmp", "rm -rf /tmp/*"),
            R.string.tool_safety_delete_home to listOf("rm -rf ~", "rm -rf \$HOME", "rm -rf ~/workspace/.."),
            R.string.tool_safety_delete_workspace to listOf(
                "rm -rf workspace", "rm -rf workspace/*", "rm -rf ~/workspace", "rm -rf ~/workspace/*",
                "rm -rf /root/workspace"
            )
        )
        val evaluator = engine()

        for ((reason, commands) in commandsByReason) {
            for (command in commands) {
                val result = evaluator.evaluate("Bash", commandArgs(command), "s1")

                assertEquals(command, Verdict.DENY, result.verdict)
                assertEquals("resource:${R.string.tool_safety_denied_with_elevation}", result.denyReason)
                assertEquals(listOf("resource:$reason"), formatArgs[R.string.tool_safety_denied_with_elevation])
                assertNull(result.sessionGrant)
            }
        }
    }

    @Test
    fun ordinaryRm_allowsWorkspaceSubdirectoriesAndTemporaryBuildDirectories() = runTest {
        val evaluator = engine()
        for (command in listOf("rm file.txt", "rm -rf ~/workspace/build", "rm -rf /root/workspace/app/src", "rm -rf workspace/build", "rm -rf /tmp/build")) {
            assertEquals(command, Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs(command)).verdict)
        }
    }

    @Test
    fun opaqueDestructiveCommands_withoutElevationAreDenied() = runTest {
        val evaluator = engine()
        for (command in listOf("rm -rf \$(pwd)/build", "rm -rf `pwd`/build", "(rm -rf build)", "cat > /etc/passwd")) {
            val result = evaluator.evaluate("Bash", commandArgs(command), "s1")

            assertEquals(command, Verdict.DENY, result.verdict)
            assertEquals("resource:${R.string.tool_safety_denied_with_elevation}", result.denyReason)
            assertTrue(command, formatArgs[R.string.tool_safety_dynamic_command]?.single()?.isNotBlank() == true)
            assertNull(result.sessionGrant)
        }
    }

    @Test
    fun opaqueDestructiveDeny_reportsTriggeringConstruct() = runTest {
        val result = engine().evaluate("Bash", commandArgs("rm -rf \$(pwd)/build"))

        assertEquals(Verdict.DENY, result.verdict)
        assertEquals("resource:${R.string.tool_safety_denied_with_elevation}", result.denyReason)
        assertEquals(listOf("resource:${R.string.tool_safety_blocker_command_substitution}"), formatArgs[R.string.tool_safety_dynamic_command])
        verify(exactly = 1) { context.getString(R.string.tool_safety_blocker_command_substitution) }
    }

    @Test
    fun opaqueDestructiveDeny_localizesEveryBlockerKindAndItsRawDetail() = runTest {
        val cases = listOf(
            Triple("rm -rf \$(pwd)/build", R.string.tool_safety_blocker_command_substitution, emptyList<String>()),
            Triple("rm -rf `pwd`/build", R.string.tool_safety_blocker_backtick_substitution, emptyList()),
            Triple("(rm -rf build)", R.string.tool_safety_blocker_subshell_or_group, emptyList()),
            Triple("id >/etc/passwd", R.string.tool_safety_blocker_absolute_output_redirect, listOf("/etc/passwd")),
            Triple("id >\"\${TARGET}\"", R.string.tool_safety_blocker_dynamic_output_redirect, listOf("\${TARGET}")),
            Triple("id 2>&/dev/null", R.string.tool_safety_blocker_unknown_output_redirect, listOf(">&/dev/null")),
            Triple("rm -rf \"\$TARGET\"", R.string.tool_safety_blocker_dynamic_argument, listOf("\$TARGET")),
            Triple("id >", R.string.tool_safety_blocker_missing_redirect_target, listOf(">")),
            Triple("id {fd}>/dev/null", R.string.tool_safety_blocker_unknown_file_descriptor, listOf("{fd}")),
            Triple("{ rm -rf build; }", R.string.tool_safety_blocker_compound_command, emptyList()),
            Triple("rm -rf build\\", R.string.tool_safety_blocker_unterminated_escape, emptyList()),
            Triple("rm -rf 'build", R.string.tool_safety_blocker_unterminated_quote, emptyList())
        )
        val evaluator = engine()

        for ((command, resource, expectedArgs) in cases) {
            formatArgs.clear()
            val result = evaluator.evaluate("Bash", commandArgs(command), "s1")

            assertEquals(command, Verdict.DENY, result.verdict)
            assertEquals(command, "resource:${R.string.tool_safety_denied_with_elevation}", result.denyReason)
            assertEquals(command, listOf("resource:$resource"), formatArgs[R.string.tool_safety_dynamic_command])
            if (expectedArgs.isEmpty()) {
                verify(exactly = 1) { context.getString(resource) }
                assertNull(command, formatArgs[resource])
            } else {
                verify(exactly = 1) { context.getString(resource, expectedArgs.single()) }
                assertEquals(command, expectedArgs, formatArgs[resource])
            }
            assertNull(command, result.sessionGrant)
        }
    }

    @Test
    fun opaqueDestructiveDeny_joinsLocalizedBlockersWithNewlines() = runTest {
        val result = engine().evaluate("Bash", commandArgs("id >/etc/hosts; (id >/etc/passwd)"))

        assertEquals(Verdict.DENY, result.verdict)
        assertEquals(
            listOf(
                "resource:${R.string.tool_safety_blocker_absolute_output_redirect}\n" +
                    "resource:${R.string.tool_safety_blocker_subshell_or_group}\n" +
                    "resource:${R.string.tool_safety_blocker_absolute_output_redirect}"
            ),
            formatArgs[R.string.tool_safety_dynamic_command]
        )
        verify(exactly = 1) { context.getString(R.string.tool_safety_blocker_absolute_output_redirect, "/etc/hosts") }
        verify(exactly = 1) { context.getString(R.string.tool_safety_blocker_absolute_output_redirect, "/etc/passwd") }
        verify(exactly = 1) { context.getString(R.string.tool_safety_blocker_subshell_or_group) }
    }

    @Test
    fun privilegedGrant_doesNotBypassOpaqueDestructiveProtection() = runTest {
        coEvery { grants.isAllowed("s1", "tool:Shizuku") } returns true
        coEvery { grants.isAllowed("s1", "root:sudo cat > /etc/passwd") } returns true
        val evaluator = engine()

        assertEquals(Verdict.DENY, evaluator.evaluate("Shizuku", commandArgs("rm -rf \$(pwd)/build"), "s1").verdict)
        assertEquals(Verdict.DENY, evaluator.evaluate("Bash", commandArgs("sudo cat > /etc/passwd"), "s1").verdict)
    }

    @Test
    fun elevation_asksAndOffersExactCommandGrantForCatastrophicAndOpaqueCommands() = runTest {
        val evaluator = engine()
        for (command in listOf("rm -rf /", "rm -rf \$(pwd)/build", "cat > /etc/passwd")) {
            val result = evaluator.evaluate("Bash", commandArgs(command, elevate = true), "s1")

            assertEquals(command, Verdict.ASK, result.verdict)
            assertEquals("elevate:$command", result.sessionGrant)
            assertNotNull(result.askTitle)
            assertNotNull(result.approvalNote)
            assertNull(result.denyReason)
        }
    }

    @Test
    fun elevationGrant_requiresSameSessionExactCommandAndExplicitElevation() = runTest {
        val pairs = listOf(
            "rm -rf /" to "rm -rf /etc",
            "rm -rf \$(pwd)/build" to "rm -rf \$(pwd)/build-other",
            "cat > /etc/passwd" to "cat > /etc/hosts",
            "cat > /etc/passwd 2>/dev/null" to "cat > /etc/hosts 2>/dev/null",
            "rm -rf \$(pwd)/build 2>/dev/null" to "rm -rf \$(pwd)/build-other 2>/dev/null"
        )
        val evaluator = engine()

        for ((command, otherCommand) in pairs) {
            coEvery { grants.isAllowed("s1", "elevate:$command") } returns true

            assertEquals(command, Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs(command, elevate = true), "s1").verdict)
            assertEquals(command, Verdict.DENY, evaluator.evaluate("Bash", commandArgs(command), "s1").verdict)
            assertEquals(command, Verdict.ASK, evaluator.evaluate("Bash", commandArgs(command, elevate = true), "s2").verdict)
            val other = evaluator.evaluate("Bash", commandArgs(otherCommand, elevate = true), "s1")
            assertEquals(otherCommand, Verdict.ASK, other.verdict)
            assertEquals("elevate:$otherCommand", other.sessionGrant)
        }
    }

    @Test
    fun inlineRootCommands_requireApproval() = runTest {
        val evaluator = engine()
        for (command in listOf("echo \$(sudo id)", "echo `su -c id`", "(sudo id)", "echo \"\$(sudo id)\"")) {
            val result = evaluator.evaluate("Bash", commandArgs(command), "s1")
            assertEquals(command, Verdict.ASK, result.verdict)
            assertEquals("root:$command", result.sessionGrant)
        }
    }

    @Test
    fun wrappedCatastrophicCommands_requireExplicitElevationBeforeAnyGrant() = runTest {
        val evaluator = engine()
        val commands = listOf(
            "sudo rm -rf /etc",
            "sudo -u root rm -rf /etc",
            "env FOO=bar command sudo rm -rf /etc",
            "sh -c 'rm -rf /etc'",
            "su -c 'rm -rf /etc'",
            "echo \$(rm -rf /etc)",
            "(rm -rf /etc)"
        )
        for (command in commands) {
            coEvery { grants.isAllowed("s1", "root:$command") } returns true
            coEvery { grants.isAllowed("s1", "elevate:$command") } returns true
            assertEquals(command, Verdict.DENY, evaluator.evaluate("Bash", commandArgs(command), "s1").verdict)
            assertEquals(command, Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs(command, elevate = true), "s1").verdict)
            val approval = evaluator.evaluate("Bash", commandArgs(command, elevate = true), "s2")
            assertEquals(command, Verdict.ASK, approval.verdict)
            assertEquals("elevate:$command", approval.sessionGrant)
        }
    }

    @Test
    fun wrappedAndInlineCommandSegments_respectDeny() = runTest {
        val evaluator = engine(PermissionRule("Bash", "git push", PermissionDecision.DENY))
        for (command in listOf("sudo git push origin main", "sh -c 'git push origin main'", "echo \$(git push origin main)")) {
            assertEquals(command, Verdict.DENY, evaluator.evaluate("Bash", commandArgs(command), "s1").verdict)
        }
        assertEquals(Verdict.ALLOW, evaluator.evaluate("Bash", commandArgs("echo 'git push origin main'"), "s1").verdict)
    }

    @Test
    fun hostRootChannel_closedDeniesEvenWithPriorSessionGrants() = runTest {
        coEvery { grants.isAllowed(any(), any()) } returns true
        val result = engine().evaluate("Root", commandArgs("id", elevate = true), "s1")
        assertEquals(Verdict.DENY, result.verdict)
        assertEquals("resource:${R.string.root_access_disabled}", result.denyReason)
    }

    @Test
    fun hostRootChannel_alwaysAsksOnceAndCarriesEnableGeneration() = runTest {
        coEvery { rootManager.accessSnapshot() } returns RootAccessState(true, 42)
        coEvery { grants.isAllowed(any(), any()) } returns true
        val result = engine().evaluate("Root", commandArgs("id"), "s1")
        assertEquals(Verdict.ASK, result.verdict)
        assertNull(result.sessionGrant)
        assertEquals(42L, result.rootGeneration)
    }

    @Test
    fun hostRootDenyRulesOverrideTheEnabledChannel() = runTest {
        coEvery { rootManager.accessSnapshot() } returns RootAccessState(true, 42)
        val evaluator = engine(PermissionRule("Root", "id", PermissionDecision.DENY))
        assertEquals(Verdict.DENY, evaluator.evaluate("Root", commandArgs("id -u", elevate = true), "s1").verdict)
    }

    @Test
    fun hostRootDestructiveAndroidPathsRequireElevationAndFreshApproval() = runTest {
        coEvery { rootManager.accessSnapshot() } returns RootAccessState(true, 9)
        coEvery { grants.isAllowed(any(), any()) } returns true
        for (path in listOf("/data", "/sdcard", "/storage/emulated/0", "/system", "/vendor")) {
            val command = "rm -rf $path"
            assertEquals(path, Verdict.DENY, engine().evaluate("Root", commandArgs(command), "s1").verdict)
            val result = engine().evaluate("Root", commandArgs(command, elevate = true), "s1")
            assertEquals(path, Verdict.ASK, result.verdict)
            assertNull(result.sessionGrant)
            assertEquals(9L, result.rootGeneration)
        }
    }

    @Test
    fun shizukuCannotReuseSessionGrantForExplicitHostRoot() = runTest {
        coEvery { grants.isAllowed(any(), any()) } returns true
        for (command in listOf(
            "su -c id", "sudo id", "echo \$(su -c id)",
            "if true; then /system/bin/su -c id; fi",
            "if true; then /system/bin/su</dev/null -c id; fi",
            "for n in 1; do su -c id; done",
            "sh -c 'if true; then su -c id; fi'"
        )) {
            assertEquals(command, Verdict.DENY, engine().evaluate("Shizuku", commandArgs(command, elevate = true), "s1").verdict)
        }
    }

    @Test
    fun approvalWithoutSessionId_neverOffersSessionGrant() = runTest {
        val evaluator = engine()
        val calls = listOf(
            "Shizuku" to commandArgs("pm list packages"),
            "Bash" to commandArgs("sudo id"),
            "terminal" to commandArgs("su -c id", "terminal"),
            "Bash" to commandArgs("rm -rf /", elevate = true),
            "Bash" to commandArgs("rm -rf \$(pwd)/build", elevate = true)
        )

        for (sessionId in listOf(null, "", "  ")) {
            for ((toolName, args) in calls) {
                val result = evaluator.evaluate(toolName, args, sessionId)

                assertEquals("$toolName $args session=$sessionId", Verdict.ASK, result.verdict)
                assertNull(result.sessionGrant)
            }
        }
    }
}
