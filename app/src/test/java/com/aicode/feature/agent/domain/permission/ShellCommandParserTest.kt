package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.permission.ShellCommandParser.Blocker
import com.aicode.feature.agent.domain.permission.ShellCommandParser.BlockerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCommandParserTest {

    @Test
    fun commandSegments_keepInputOutputAndReadWriteRedirectEvidence() {
        val analysis = ShellCommandParser.analyze("cat < '/system/build.prop'; id 2>>/sys/probe; exec 3<>/system/probe")
        assertEquals(listOf(listOf("cat"), listOf("id"), listOf("exec")), analysis.segments)
        assertEquals(
            listOf(
                ShellCommandParser.Redirection("<", "/system/build.prop", true),
                ShellCommandParser.Redirection(">>", "/sys/probe", true),
                ShellCommandParser.Redirection("<>", "/system/probe", true)
            ),
            analysis.commandSegments.flatMap { it.redirections }
        )
    }

    @Test
    fun redirectEvidence_doesNotConfuseQuotedDataWithDynamicTargets() {
        val literal = ShellCommandParser.analyze("printf '%s' '> /system/probe'")
        assertTrue(literal.commandSegments.single().redirections.isEmpty())
        val dynamic = ShellCommandParser.analyze("id >\"\$TARGET\"")
        assertEquals(ShellCommandParser.Redirection(">", "\$TARGET", false), dynamic.commandSegments.single().redirections.single())
        val nullOutput = ShellCommandParser.analyze("id >/dev/'null' 2>&1")
        assertFalse(nullOutput.hasFileOutputRedirect)
        assertEquals(listOf("/dev/null", "1"), nullOutput.commandSegments.single().redirections.map { it.target })
    }

    @Test
    fun redirectionOnlyCommandsRemainVisibleWithoutInventingAnExecutable() {
        val analysis = ShellCommandParser.analyze(">/sys/probe")
        assertTrue(analysis.segments.isEmpty())
        assertTrue(analysis.commandSegments.single().tokens.isEmpty())
        assertEquals(ShellCommandParser.Redirection(">", "/sys/probe", true), analysis.commandSegments.single().redirections.single())
    }

    @Test
    fun hereData_isNotAnExecutableSegmentButRealExpansionsAndFollowingWrappersRemainVisible() {
        for (command in listOf(
            "cat <<'EOF'\ngit push origin main\nEOF",
            "cat <<EOF\ngit push origin main\nEOF",
            "cat <<'EOF'\n\$(git push origin main)\nEOF",
            "cat <<< 'git push origin main'"
        )) assertFalse(command, ShellCommandParser.executableSegments(command).any { ShellCommandParser.matches("git push", it) })

        for (command in listOf(
            "cat <<EOF\n\$(git push origin main)\nEOF",
            "cat <<EOF\n`git push origin main`\nEOF",
            "cat <<'EOF'\nliteral data\nEOF\nsh -c 'git push origin main'"
        )) assertTrue(command, ShellCommandParser.executableSegments(command).any { ShellCommandParser.matches("git push", it) })
    }

    @Test
    fun hereStringAndTabStrippingHereDocumentOperatorsRemainWholeTokens() {
        assertEquals("<<<", ShellCommandParser.analyze("cat <<< '/system/probe'").commandSegments.single().redirections.single().operator)
        val here = ShellCommandParser.analyze("cat <<-'EOF'\n\tcat /system/probe\n\tEOF")
        assertEquals("<<-", here.commandSegments.single().redirections.single().operator)
        assertEquals(listOf(listOf("cat")), here.segments)
    }

    @Test
    fun singleSegment_isAnalyzable_andPrefixIsProgram() {
        val a = ShellCommandParser.analyze("git status -s")
        assertTrue(a.analyzable)
        assertEquals(1, a.segments.size)
        assertEquals(listOf("git", "status", "-s"), a.segments[0])
        assertEquals("git", ShellCommandParser.programPrefix(a.segments[0]))
    }

    @Test
    fun envAssignment_isStrippedFromPrefix() {
        val a = ShellCommandParser.analyze("FOO=bar BAZ=1 git push")
        assertEquals("git", ShellCommandParser.programPrefix(a.segments[0]))
        assertTrue(ShellCommandParser.matches("git", a.segments[0]))
    }

    @Test
    fun operators_splitIntoSegments() {
        val and = ShellCommandParser.analyze("npm install && git push")
        assertTrue(and.analyzable)
        assertEquals(2, and.segments.size)
        assertEquals("npm", ShellCommandParser.programPrefix(and.segments[0]))
        assertEquals("git", ShellCommandParser.programPrefix(and.segments[1]))

        val pipe = ShellCommandParser.analyze("cat a | grep b ; echo c")
        assertEquals(3, pipe.segments.size)
    }

    @Test
    fun commandSubstitution_isNotAnalyzable() {
        assertFalse(ShellCommandParser.analyze("echo \$(whoami)").analyzable)
        assertFalse(ShellCommandParser.analyze("echo `whoami`").analyzable)
        assertFalse(ShellCommandParser.analyze("(cd x && ls)").analyzable)
    }

    @Test
    fun absoluteOutputRedirect_isNotAnalyzable_butRelativeIs() {
        assertFalse(ShellCommandParser.analyze("echo hi > /etc/passwd").analyzable)
        assertFalse(ShellCommandParser.analyze("echo hi >>/sdcard/x").analyzable)
        assertTrue(ShellCommandParser.analyze("echo hi > out.txt").analyzable)
    }

    @Test
    fun literalNullOutputRedirects_areAnalyzableAndDoNotWriteFiles() {
        val commands = listOf(
            "id >/dev/null",
            "id 2>/dev/null",
            "id >> /dev/null",
            "id 2>>'/dev/null'",
            "id >\"/dev/null\"",
            "id > /dev/'null'",
            "id >/dev/nu\\ll",
            "id >|/dev/null",
            "id &>/dev/null",
            "id &>>'/dev/null'",
            "id >&/dev/null",
            "id <>/dev/null"
        )

        for (command in commands) {
            val analysis = ShellCommandParser.analyze(command)

            assertTrue(command, analysis.analyzable)
            assertFalse(command, analysis.hasFileOutputRedirect)
            assertTrue(command, analysis.blockers.isEmpty())
            assertEquals(command, listOf(listOf("id")), analysis.segments)
        }
    }

    @Test
    fun descriptorRedirects_doNotBecomeArgumentsOrCommandSeparators() {
        for (command in listOf(
            "id 2>&1", "2>&1 id", "id 1>&2 2>&-", "id 2>&'1'",
            "id 3>&1-", "id >/dev/null 2>&1", "id 2>&1 >/dev/null", "id 3</dev/null"
        )) {
            val analysis = ShellCommandParser.analyze(command)

            assertTrue(command, analysis.analyzable)
            assertFalse(command, analysis.hasFileOutputRedirect)
            assertEquals(command, listOf(listOf("id")), analysis.segments)
        }
        for (command in listOf("printf '%s' '2'>/dev/null", "printf '%s' 2 >/dev/null", "printf '%s' \\2>/dev/null")) {
            assertEquals(command, listOf(listOf("printf", "%s", "2")), ShellCommandParser.analyze(command).segments)
        }
    }

    @Test
    fun nonNullAbsoluteRedirects_areStillFileWrites() {
        for (target in listOf("/dev/nullsuffix", "'/dev/nullsuffix'", "\"/etc/passwd\"", "/tmp/probe", "/dev/./null", "//dev/null")) {
            val analysis = ShellCommandParser.analyze("id 2>>$target")

            assertFalse(target, analysis.analyzable)
            assertTrue(target, analysis.hasFileOutputRedirect)
            assertTrue(target, analysis.blockers.any { it.kind == BlockerKind.ABSOLUTE_OUTPUT_REDIRECT })
        }
        val relative = ShellCommandParser.analyze("id > probe.txt")
        assertTrue(relative.analyzable)
        assertTrue(relative.hasFileOutputRedirect)
    }

    @Test
    fun dynamicAndMalformedRedirects_areNotTreatedAsNullOrDescriptorCopies() {
        for (command in listOf(
            "id >\$TARGET", "id >\"\${TARGET}\"", "id >/dev/null\$SUFFIX", "id >~/probe",
            "id >/dev/nu*", "id >\$(printf /dev/null)", "id >\"/dev/null\$(printf '')\"",
            "id >`printf /dev/null`", "id 2>&\$FD", "id 2>&/dev/null", "id >&word\$SUFFIX",
            "id >", "id 2>&", "id >'", "id >/dev/null\\", "id >>>/dev/null", "id {fd}>/dev/null"
        )) {
            val analysis = ShellCommandParser.analyze(command)

            assertFalse(command, analysis.analyzable)
            assertTrue(command, analysis.hasFileOutputRedirect)
            assertTrue(command, analysis.blockers.isNotEmpty())
        }
    }

    @Test
    fun groupedAndSubstitutedCommands_propagateRedirectRisk() {
        for (command in listOf(
            "(id 2>/dev/null)", "(id) >/dev/null 2>&1", "{ id 2>/dev/null; }",
            "echo \"\$(id 2>/dev/null)\"", "echo `id 2>/dev/null`", "sh -c '(id 2>/dev/null)'",
            "echo \"\$(echo \"\$(id 2>/dev/null)\")\""
        )) {
            val analysis = ShellCommandParser.analyze(command)

            assertFalse(command, analysis.analyzable)
            assertFalse(command, analysis.hasFileOutputRedirect)
            assertFalse(command, analysis.blockers.any { it.kind == BlockerKind.ABSOLUTE_OUTPUT_REDIRECT })
        }
        for (command in listOf(
            "(id >/tmp/probe) 2>/dev/null", "echo \"\$(id > /etc/passwd)\" 2>/dev/null",
            "echo `id > /tmp/probe` >/dev/null", "sh -c 'id > /etc/passwd' 2>/dev/null",
            "(id > probe.txt) 2>/dev/null", "{ id > probe.txt; } 2>/dev/null",
            "echo \"\$(echo \"\$(id >/tmp/probe)\")\" 2>/dev/null"
        )) {
            val analysis = ShellCommandParser.analyze(command)

            assertFalse(command, analysis.analyzable)
            assertTrue(command, analysis.hasFileOutputRedirect)
        }
    }

    @Test
    fun quotedOrEscapedRedirectCharacters_areOnlyArguments() {
        for (command in listOf("printf '%s' '>/etc/passwd'", "printf '%s' \\>/etc/passwd", "echo \"\$(printf '>')\"")) {
            assertFalse(command, ShellCommandParser.analyze(command).hasFileOutputRedirect)
        }
    }

    @Test
    fun redirects_doNotHideExecutablePrefixesOrDynamicDeletionTargets() {
        for (command in listOf("2>/dev/null sudo id", "(sudo id 2>/dev/null)", "echo \"\$(sudo id 2>/dev/null)\"")) {
            assertTrue(command, ShellCommandParser.requiresRootApproval(command))
        }
        val dynamicRm = ShellCommandParser.analyze("rm -rf \"\$TARGET\" 2>/dev/null")
        assertFalse(dynamicRm.analyzable)
        assertFalse(dynamicRm.hasFileOutputRedirect)
        assertEquals(listOf("\$TARGET"), ShellCommandParser.parseRmInfo(dynamicRm.segments.single()).targetPaths)
        assertEquals(
            listOf("rm", "-rf", "/etc"),
            ShellCommandParser.executableSegments("2>/dev/null rm -rf /etc").single()
        )
    }

    @Test
    fun operatorsInsideQuotes_areNotSplit() {
        val a = ShellCommandParser.analyze("echo \"a && b | c\"")
        assertTrue(a.analyzable)
        assertEquals(1, a.segments.size)
        assertEquals(listOf("echo", "a && b | c"), a.segments[0])
    }

    @Test
    fun unterminatedQuote_isNotAnalyzable() {
        assertFalse(ShellCommandParser.analyze("echo \"abc").analyzable)
    }

    /** 不可静态判定时要能说清是哪个构造触发的，否则上层只能说「无法分析」而 AI 无从修正。 */
    @Test
    fun blockers_nameTheOffendingConstruct() {
        assertEquals(listOf(Blocker(BlockerKind.COMMAND_SUBSTITUTION)), ShellCommandParser.analyze("echo \$(whoami)").blockers)
        assertEquals(listOf(Blocker(BlockerKind.BACKTICK_SUBSTITUTION)), ShellCommandParser.analyze("echo `whoami`").blockers)
        assertEquals(listOf(Blocker(BlockerKind.SUBSHELL_OR_GROUP)), ShellCommandParser.analyze("(cd x && ls)").blockers)
        assertEquals(Blocker(BlockerKind.UNTERMINATED_QUOTE), ShellCommandParser.analyze("echo \"abc").blockers.single())
    }

    @Test
    fun blockers_includeRedirectTarget() {
        assertEquals(
            listOf(Blocker(BlockerKind.ABSOLUTE_OUTPUT_REDIRECT, "/etc/passwd")),
            ShellCommandParser.analyze("echo hi > /etc/passwd").blockers
        )
    }

    /** 多种构造同时出现时逐条记录、不重复；可静态判定的命令没有任何 blocker。 */
    @Test
    fun blockers_areDistinctAndEmptyWhenAnalyzable() {
        val multi = ShellCommandParser.analyze("echo \$(a) \$(b) `c`")

        assertEquals(listOf(Blocker(BlockerKind.COMMAND_SUBSTITUTION), Blocker(BlockerKind.BACKTICK_SUBSTITUTION)), multi.blockers)
        assertTrue(ShellCommandParser.analyze("git status").blockers.isEmpty())
    }

    @Test
    fun blockers_preserveKindsAndRawDetails() {
        val cases = listOf(
            "id > '/etc/hosts'" to Blocker(BlockerKind.ABSOLUTE_OUTPUT_REDIRECT, "/etc/hosts"),
            "id >\"\${TARGET}\"" to Blocker(BlockerKind.DYNAMIC_OUTPUT_REDIRECT, "\${TARGET}"),
            "id 2>&/dev/null" to Blocker(BlockerKind.UNKNOWN_OUTPUT_REDIRECT, ">&/dev/null"),
            "printf '%s' \"\$TARGET\"" to Blocker(BlockerKind.DYNAMIC_ARGUMENT, "\$TARGET"),
            "id 2>" to Blocker(BlockerKind.MISSING_REDIRECT_TARGET, ">"),
            "id {fd}>/dev/null" to Blocker(BlockerKind.UNKNOWN_FILE_DESCRIPTOR, "{fd}"),
            "if true; then id; fi" to Blocker(BlockerKind.COMPOUND_COMMAND),
            "echo hi\\" to Blocker(BlockerKind.UNTERMINATED_ESCAPE)
        )

        for ((command, blocker) in cases) {
            val analysis = ShellCommandParser.analyze(command)

            assertFalse(command, analysis.analyzable)
            assertEquals(command, listOf(blocker), analysis.blockers)
        }
    }

    @Test
    fun blockers_deduplicateByKindAndDetailAcrossNestedCommands() {
        val analysis = ShellCommandParser.analyze("id >/etc/hosts; (id >/etc/hosts; id >/etc/passwd)")

        assertEquals(
            listOf(
                Blocker(BlockerKind.ABSOLUTE_OUTPUT_REDIRECT, "/etc/hosts"),
                Blocker(BlockerKind.SUBSHELL_OR_GROUP),
                Blocker(BlockerKind.ABSOLUTE_OUTPUT_REDIRECT, "/etc/passwd")
            ),
            analysis.blockers
        )
        assertTrue(analysis.hasFileOutputRedirect)
    }

    @Test
    fun matches_isTokenPrefix() {
        assertTrue(ShellCommandParser.matches("git", listOf("git", "status")))
        assertTrue(ShellCommandParser.matches("git", listOf("git")))
        assertTrue(ShellCommandParser.matches("npm install", listOf("npm", "install", "lodash")))
        // 不同子命令不命中更具体的前缀规则
        assertFalse(ShellCommandParser.matches("git status", listOf("git", "push")))
        // 字面首 token：路径形式不被裸程序名规则命中
        assertFalse(ShellCommandParser.matches("node", listOf("/usr/bin/node", "x.js")))
    }

    @Test
    fun requiresRootApproval_detectsDirectAbsoluteAndChainedCommands() {
        val commands = listOf(
            "sudo id",
            "su -c id",
            "/usr/bin/sudo id",
            "/system/bin/su -c id",
            "echo ready && sudo id",
            "echo ready; su -c id",
            "echo ready\n/usr/bin/sudo id",
            "echo \$(sudo id)",
            "echo `su -c id`",
            "(sudo id)",
            "echo \"\$(sudo id)\"",
            "echo <(sudo id)"
        )

        for (command in commands) {
            assertTrue(command, ShellCommandParser.requiresRootApproval(command))
        }
    }

    @Test
    fun requiresRootApproval_unwrapsEnvironmentAssignmentsAndEnvOptions() {
        val commands = listOf(
            "FOO=bar BAZ=1 sudo id",
            "env FOO=bar su -c id",
            "/usr/bin/env -i FOO=bar /usr/bin/sudo id",
            "env -u USER -- sudo id",
            "env --unset USER -C /tmp sudo id",
            "env --chdir=/tmp -a worker su -c id",
            "env -S 'sudo id'",
            "env --split-string='sudo id'"
        )

        for (command in commands) {
            assertTrue(command, ShellCommandParser.requiresRootApproval(command))
        }
    }

    @Test
    fun requiresRootApproval_unwrapsCommandAndExecWrappers() {
        val commands = listOf(
            "command sudo id",
            "command -- /usr/bin/sudo id",
            "exec su -c id",
            "exec -a worker sudo id",
            "nohup sudo id",
            "busybox su -c id",
            "toybox sh -c 'sudo id'",
            "FOO=bar env BAZ=1 command exec /usr/bin/sudo id"
        )

        for (command in commands) {
            assertTrue(command, ShellCommandParser.requiresRootApproval(command))
        }
    }

    @Test
    fun requiresRootApproval_analyzesShellCommandStrings() {
        val commands = listOf(
            "sh -c 'sudo id'",
            "/bin/bash -lc 'su -c id'",
            "bash -c 'echo ready && sudo id'",
            "env FOO=bar sh -c 'command /usr/bin/sudo id'",
            "sh -c \"bash -c 'sudo id'\""
        )

        for (command in commands) {
            assertTrue(command, ShellCommandParser.requiresRootApproval(command))
        }
    }

    @Test
    fun requiresRootApproval_ignoresRootWordsInOrdinaryArguments() {
        val commands = listOf(
            "",
            "echo sudo",
            "echo su",
            "echo 'sudo id && su -c id'",
            "printf '%s' /usr/bin/sudo",
            "sudoer id",
            "env -u sudo echo hello",
            "exec -a sudo echo hello",
            "sh -c 'echo sudo'",
            "bash -lc 'printf su'",
            "sh script.sh sudo",
            "echo '\$(sudo id)'",
            "echo \"(sudo id)\"",
            "command -v sudo"
        )

        for (command in commands) {
            assertFalse(command, ShellCommandParser.requiresRootApproval(command))
        }
    }

    @Test
    fun rememberablePrefix_dispatcher_includesSubcommand() {
        // 子命令分发器：连子命令一起记，使 git pull / git clone 各自独立授权。
        assertEquals("git pull", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git pull").segments[0]))
        assertEquals("git clone", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git clone https://x/y").segments[0]))
        assertEquals("npm install", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("npm install lodash --save").segments[0]))
        assertEquals("cargo build", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("cargo build --release").segments[0]))
    }

    @Test
    fun rememberablePrefix_dispatcher_withLeadingFlag_fallsBackToProgram() {
        // 程序名后紧跟 flag：无法稳定取子命令，退化为仅程序名（不误记带 flag 的怪前缀）。
        assertEquals("git", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git --version").segments[0]))
    }

    @Test
    fun rememberablePrefix_nonDispatcher_isProgramOnly() {
        // 普通程序：仅记程序名，按整程序放行（cat 任意文件）。
        assertEquals("cat", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("cat README.md").segments[0]))
        assertEquals("ls", ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("ls -la /tmp").segments[0]))
    }

    @Test
    fun rememberablePrefix_distinctSubcommandsAreIndependent() {
        // 验证记忆的前缀互不串放：git pull 规则不命中 git push。
        val pullRule = ShellCommandParser.rememberablePrefix(ShellCommandParser.analyze("git pull").segments[0])
        assertEquals("git pull", pullRule)
        assertFalse(ShellCommandParser.matches(pullRule!!, ShellCommandParser.analyze("git push").segments[0]))
    }

    @Test
    fun rmCommand_fineGrainedHandling() {
        // 单文件删除记忆为完整前缀，不记忆为宽泛的 "rm"
        val singleFile = ShellCommandParser.analyze("rm temp.log").segments[0]
        assertEquals("rm temp.log", ShellCommandParser.rememberablePrefix(singleFile))

        // 带选项的单文件删除
        val forceFile = ShellCommandParser.analyze("rm -f cache.tmp").segments[0]
        assertEquals("rm -f cache.tmp", ShellCommandParser.rememberablePrefix(forceFile))

        // 递归删除不可记忆
        val recursiveDir = ShellCommandParser.analyze("rm -rf build").segments[0]
        assertEquals(null, ShellCommandParser.rememberablePrefix(recursiveDir))

        // 通配符删除不可记忆
        val wildcardFile = ShellCommandParser.analyze("rm *.class").segments[0]
        assertEquals(null, ShellCommandParser.rememberablePrefix(wildcardFile))

        // 无目标参数不可记忆
        val noTarget = ShellCommandParser.analyze("rm --help").segments[0]
        assertEquals(null, ShellCommandParser.rememberablePrefix(noTarget))
    }
}
