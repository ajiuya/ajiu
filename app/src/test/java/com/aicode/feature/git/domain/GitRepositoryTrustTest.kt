package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRepositoryTrustTest {
    @Test
    fun chrootReadsWritesAndBothProbesUseTheSameExactCwdTrustWithAnExplicitPosixShell() = runTest {
        for (shellPath in listOf("/bin/sh", "/usr/bin/fish", "/bin/csh")) {
            val fixture = Fixture(shellPath = shellPath)
            val operation = fixture.repository.captureOperation()

            assertTrue(fixture.repository.isRepo(operation))
            assertTrue(fixture.repository.isGitRepo(SUBDIRECTORY, operation))
            fixture.repository.hasRemote(operation)
            fixture.repository.commit("trusted message", operation = operation)

            assertEquals(listOf(
                trustedGit("rev-parse --is-inside-work-tree", probe = true, shellPath = shellPath),
                trustedGit("rev-parse --is-inside-work-tree", probe = true, shellPath = shellPath),
                trustedGit("remote", shellPath = shellPath),
                trustedGit("commit -m 'trusted message'", shellPath = shellPath)
            ), fixture.calls.map { it.command })
            assertEquals(listOf(WORKSPACE, SUBDIRECTORY, WORKSPACE, WORKSPACE), fixture.calls.map { it.cwd })
            assertTrue(fixture.calls.all { it.timeoutMs == CommandEngine.DEFAULT_TIMEOUT_MS })
            assertTrue(fixture.calls.none { it.command.contains('*') || it.command.contains("export ") || it.command.contains("git config") })
            assertEquals(operation.owner, fixture.contexts.single().runtimeOwner)
            fixture.assertSingleExecution()
        }
    }

    @Test
    fun apostropheAndBackslashUseTheFrozenLocalFishEncodingOnlyForTheOuterScript() = runTest {
        for (shellPath in listOf("/bin/sh", "/usr/bin/fish", "fish", "/bin/tcsh")) {
            val fixture = Fixture(shellPath = shellPath)
            val operation = fixture.repository.captureOperation()
            assertTrue(fixture.repository.isRepo(operation))
            fixture.profile = fixture.profile.copy(shellPath = "/bin/different-shell")

            fixture.repository.stage("folder/it's\\file.txt", operation)

            val prefix = """git_worktree=${'$'}(pwd -P) && test -n "${'$'}git_worktree" && git -c "safe.directory=${'$'}git_worktree" -c core.quotepath=false"""
            val expected = if (shellPath.substringAfterLast('/') == "fish") {
                """/bin/sh -c '$prefix add -- \'folder/it\'\\\'\'s\\file.txt\''"""
            } else {
                trustedGit("add -- 'folder/it'\\''s\\file.txt'")
            }
            assertEquals(expected, fixture.calls.last().command)
            assertEquals(shellPath, operation.runtimeProfile?.shellPath)
            fixture.assertSingleExecution()
        }
    }

    @Test
    fun selectedSubrepositoryAndExplicitProbeCwdArePassedToTheEngineWithoutMappingOrInterpolation() = runTest {
        val workspacePath = "/storage/emulated/0/owned workspace/it's \$(id)"
        val fixture = Fixture(workspacePath = workspacePath)
        val selected = "$workspacePath/selected repository"
        val candidate = "$workspacePath/other repository"
        fixture.repository.setRepoPath(selected)
        val operation = fixture.repository.captureOperation()

        assertTrue(fixture.repository.isRepo(operation))
        assertTrue(fixture.repository.isGitRepo(candidate, operation))
        fixture.repository.stage("folder/it's \$(id); file", operation)

        assertEquals(listOf(selected, candidate, selected), fixture.calls.map { it.cwd })
        assertEquals(trustedGit("rev-parse --is-inside-work-tree", probe = true), fixture.calls[0].command)
        assertEquals(trustedGit("rev-parse --is-inside-work-tree", probe = true), fixture.calls[1].command)
        assertEquals(trustedGit("add -- 'folder/it'\\''s \$(id); file'"), fixture.calls[2].command)
        assertTrue(fixture.calls.none { it.command.contains(workspacePath) || it.command.contains(" -C ") })
        fixture.assertSingleExecution()
    }

    @Test
    fun subsequentCommandsUseTheAlreadyFrozenRuntimeProfile() = runTest {
        for (chroot in listOf(true, false)) {
            val fixture = Fixture(chroot = chroot)
            val operation = fixture.repository.captureOperation()
            assertTrue(fixture.repository.isRepo(operation))
            fixture.profile = fixture.profile.copy(
                localRuntime = if (chroot) LocalContainerRuntime.PROOT else LocalContainerRuntime.CHROOT
            )

            fixture.repository.stageAll(operation)

            val expected = if (chroot) trustedGit("add -A") else "git -c core.quotepath=false add -A"
            assertEquals(expected, fixture.calls.last().command)
            assertEquals(chroot, operation.runtimeProfile?.isChroot)
            fixture.assertSingleExecution()
        }
    }

    @Test
    fun prootAndSshUseEnglishProbesUnderChineseProfilesWithoutChangingOtherCommands() = runTest {
        for (mode in ExecutionMode.entries) {
            for (chroot in listOf(true, false)) {
                if (mode == ExecutionMode.LOCAL_PROOT && chroot) continue
                val fixture = Fixture(chroot = chroot, mode = mode, shellPath = "/usr/bin/fish")
                fixture.profile = fixture.profile.copy(
                    env = fixture.profile.env + mapOf("LANG" to "zh_CN.UTF-8", "LC_ALL" to "zh_CN.UTF-8")
                )
                val operation = fixture.repository.captureOperation()

                assertTrue(fixture.repository.isRepo(operation))
                assertTrue(fixture.repository.isGitRepo(SUBDIRECTORY, operation))
                fixture.repository.hasRemote(operation)
                fixture.repository.stage("path with spaces", operation)

                assertEquals(listOf(
                    "/bin/sh -c 'LC_ALL=C git -c core.quotepath=false rev-parse --is-inside-work-tree'",
                    "/bin/sh -c 'LC_ALL=C git rev-parse --is-inside-work-tree'",
                    "git -c core.quotepath=false remote",
                    "git -c core.quotepath=false add -- 'path with spaces'"
                ), fixture.calls.map { it.command })
                fixture.result = CommandResult("fatal: not a git repository (or any of the parent directories): .git", 128)
                assertFalse(fixture.repository.isRepo(operation))
                assertFalse(fixture.repository.isGitRepo(SUBDIRECTORY, operation))
                assertEquals(fixture.calls.take(2).map { it.command }, fixture.calls.takeLast(2).map { it.command })
                assertEquals("zh_CN.UTF-8", operation.runtimeProfile?.env?.get("LC_ALL"))
                fixture.assertSingleExecution()
            }
        }
    }

    @Test
    fun successfulProbeAcceptsOnlyExplicitTrueOrFalse() = runTest {
        for (value in listOf(true, false)) {
            val fixture = Fixture()
            fixture.result = CommandResult(" $value\r\n", 0)
            val operation = fixture.repository.captureOperation()

            assertEquals(value, fixture.probe(direct = true, operation = operation))
            assertEquals(value, fixture.probe(direct = false, operation = operation))
            fixture.assertSingleExecution()
        }
    }

    @Test
    fun trueOrFalseOutputWithNonzeroOrUnknownExitIsNotAProbeResult() = runTest {
        for (exitCode in listOf(1, 127, 128, null)) {
            for (output in listOf("true\n", "false\n")) {
                assertProbeFailure(CommandResult(output, exitCode))
            }
        }
    }

    @Test
    fun explicitNotRepositoryOrWorktreeAtExit128ReturnsFalse() = runTest {
        for (output in listOf(
            "fatal: not a git repository (or any of the parent directories): .git\n",
            "fatal: not a git worktree\n"
        )) {
            val fixture = Fixture()
            fixture.result = CommandResult(output, 128)
            val operation = fixture.repository.captureOperation()

            assertFalse(fixture.probe(direct = true, operation = operation))
            assertFalse(fixture.probe(direct = false, operation = operation))
            fixture.assertSingleExecution()
        }
    }

    @Test
    fun notRepositoryTextWithAnotherExitCodeStillFails() = runTest {
        for (exitCode in listOf(0, 1, 127, null)) {
            assertProbeFailure(CommandResult("fatal: not a git repository", exitCode))
        }
    }

    @Test
    fun onlyAnExplicitMissingGitAtExit127UsesTheUnavailableFailure() = runTest {
        for (output in listOf(
            "/bin/sh: 1: git: not found\n",
            "bash: line 1: git: command not found\n",
            "git: Command not found.\n",
            "git not found\n"
        )) {
            assertProbeFailure(CommandResult(output, 127), GitUnavailableException::class.java)
            assertProbeFailure(CommandResult(output, 1))
        }
    }

    @Test
    fun unknownExit127AndMissingOtherToolsAreOrdinaryFailures() = runTest {
        for (output in listOf(
            "Git execution failed unexpectedly",
            "/bin/sh: 1: another-tool: not found",
            "git: error while loading shared libraries",
            "/work/git: not found",
            "fatal: cannot access '/work/git: not found'"
        )) {
            assertProbeFailure(CommandResult(output, 127))
        }
    }

    @Test
    fun ownershipPermissionAndCorruptionErrorsArePreservedInsteadOfNotARepository() = runTest {
        for (output in listOf(
            "fatal: detected dubious ownership in repository at '/root/workspace'\n",
            "fatal: detected dubious ownership in repository at '/work/not a git repository/owned'\n",
            "fatal: detected dubious ownership in repository at '/work/repo\nfatal: not a git repository/owned'\n",
            "fatal: cannot open '/work/not a git worktree/.git/config': Permission denied\n",
            "fatal: cannot open '.git/config': Permission denied\n",
            "fatal: bad config line 1 in file .git/config\n",
            "fatal: loose object is corrupt\n"
        )) {
            assertProbeFailure(CommandResult(output, 128))
        }
    }

    @Test
    fun timeoutUnknownExitAndUnexpectedSuccessfulOutputFailWithoutLosingOutput() = runTest {
        for (result in listOf(
            CommandResult("Command timed out", null),
            CommandResult("pwd: failed to resolve current directory", 23),
            CommandResult("unexpected probe output", 0),
            CommandResult("", null),
            CommandResult("", 0)
        )) {
            assertProbeFailure(result)
        }
    }

    @Test
    fun truncatedOutputAlwaysRetainsItsOriginalFailureType() = runTest {
        for (result in listOf(
            CommandResult("true\n", 0, outputTruncated = true),
            CommandResult("false\n", 0, outputTruncated = true),
            CommandResult("fatal: not a git repository", 128, outputTruncated = true),
            CommandResult("git: not found", 127, outputTruncated = true)
        )) {
            assertProbeFailure(result, GitOutputTooLargeException::class.java)
        }
    }

    @Test
    fun engineFailuresCancellationAndAuthorizationContinueToPropagate() = runTest {
        for (direct in listOf(true, false)) {
            for (failure in listOf(
                IllegalStateException("Remote unavailable"),
                CancellationException("Probe cancelled"),
                LocalRuntimeAuthorizationRequired(),
                GitOperationExpiredException()
            )) {
                val fixture = Fixture(mode = ExecutionMode.REMOTE_SSH)
                fixture.onCommand = { throw failure }

                val observed = runCatching { fixture.probe(direct) }.exceptionOrNull()

                assertSame(failure, generateSequence(observed) { it.cause }.lastOrNull())
                assertEquals(1, fixture.calls.size)
                fixture.assertSingleExecution()
            }
        }
    }

    @Test
    fun missingAuthorizationDuringFreezeNeverDispatchesAProbe() = runTest {
        for (direct in listOf(true, false)) {
            val fixture = Fixture()
            val failure = LocalRuntimeAuthorizationRequired()
            coEvery { fixture.factory.freeze(any(), any(), any()) } throws failure

            assertSame(failure, runCatching { fixture.probe(direct) }.exceptionOrNull())
            assertTrue(fixture.calls.isEmpty())
            coVerify(exactly = 0) { fixture.engine.runCommandSyncUnbounded(any(), any(), any()) }
        }
    }

    @Test
    fun changedSourcesRejectBothProbesBeforeFreezingOrDispatching() = runTest {
        val changes = listOf<(Fixture) -> Unit>(
            { it.configurationRevision++ },
            { it.profileRevision++ },
            { it.selection.value = WorkspaceRepository.Selection(Workspace("other", "/work/other", WorkspaceType.INTERNAL)) },
            { it.repository.setRepoPath(SUBDIRECTORY) },
            { it.modes.setMode(ExecutionMode.REMOTE_SSH) }
        )
        for (direct in listOf(true, false)) {
            for (change in changes) {
                val fixture = Fixture()
                val operation = fixture.repository.captureOperation()
                change(fixture)

                assertTrue(runCatching { fixture.probe(direct, operation) }.exceptionOrNull() is GitOperationExpiredException)
                coVerify(exactly = 0) { fixture.factory.freeze(any(), any(), any()) }
                coVerify(exactly = 0) { fixture.engine.runCommandSyncUnbounded(any(), any(), any()) }
            }
        }
    }

    @Test
    fun sourceChangeDuringFreezePreventsProbeDispatch() = runTest {
        for (direct in listOf(true, false)) {
            val fixture = Fixture()
            fixture.onFreeze = { fixture.configurationRevision++ }

            assertTrue(runCatching { fixture.probe(direct) }.exceptionOrNull() is GitOperationExpiredException)
            assertEquals(1, fixture.contexts.size)
            assertTrue(fixture.calls.isEmpty())
            coVerify(exactly = 0) { fixture.engine.runCommandSyncUnbounded(any(), any(), any()) }
        }
    }

    @Test
    fun sourceChangeDuringProbeRejectsItsResultAndAllLaterCommands() = runTest {
        for (direct in listOf(true, false)) {
            val fixture = Fixture()
            val operation = fixture.repository.captureOperation()
            fixture.onCommand = { fixture.configurationRevision++ }

            assertTrue(runCatching { fixture.probe(direct, operation) }.exceptionOrNull() is GitOperationExpiredException)
            assertTrue(runCatching { fixture.repository.stageAll(operation) }.exceptionOrNull() is GitOperationExpiredException)
            assertEquals(1, fixture.calls.size)
            fixture.assertSingleExecution()
        }
    }

    private suspend fun assertProbeFailure(
        result: CommandResult,
        type: Class<out GitCommandFailureException> = GitCommandFailureException::class.java
    ) {
        val fixture = Fixture()
        fixture.result = result
        val operation = fixture.repository.captureOperation()
        for (direct in listOf(true, false)) {
            val failure = runCatching { fixture.probe(direct, operation) }.exceptionOrNull()
            assertEquals(type, failure?.javaClass)
            if (!result.outputTruncated && result.output.isNotBlank()) {
                assertEquals(result.output, (failure as GitCommandFailureException).output)
            }
        }
        fixture.assertSingleExecution()
    }

    private class Fixture(
        chroot: Boolean = true,
        mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        shellPath: String = "/bin/sh",
        workspacePath: String = WORKSPACE
    ) {
        val modes = ExecutionModeHolder().apply { setMode(mode) }
        val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("test", workspacePath, WorkspaceType.INTERNAL)))
        val workspace = mockk<WorkspaceRepository>()
        val settings = mockk<ContainerSettingsRepository>()
        val engine = mockk<CommandEngine>()
        val factory = mockk<WorkspaceExecutionContextFactory>()
        val contexts = mutableListOf<WorkspaceExecutionContext>()
        val calls = mutableListOf<Call>()
        var configurationRevision = 7L
        var profileRevision = 3L
        var profile = ContainerProfile.BUILTIN_ALPINE.copy(
            localRuntime = if (chroot) LocalContainerRuntime.CHROOT else LocalContainerRuntime.PROOT,
            shellPath = shellPath
        )
        var result = CommandResult("true\n", 0)
        var onFreeze: () -> Unit = {}
        var onCommand: () -> Unit = {}
        val repository: GitRepository

        init {
            every { workspace.selection } returns selection
            every { settings.activeProfileSelectionRevision } answers { profileRevision }
            every { settings.profileConfigurationRevision } answers { configurationRevision }
            coEvery { factory.freeze(any(), any(), any()) } answers {
                val owner = requireNotNull(secondArg<RuntimeOwner?>())
                val check = requireNotNull(thirdArg<(() -> Unit)?>())
                check()
                val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), profileRevision, configurationRevision, settings)
                WorkspaceExecutionContext(
                    modes.currentMode(), "git-trust-test", mockk(), null, null,
                    runtimeOwner = owner, profileSnapshot = snapshot, checkSource = check
                ).also {
                    contexts += it
                    onFreeze()
                }
            }
            coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } coAnswers {
                val execution = requireNotNull(WorkspaceExecutionContext.current())
                assertSame(contexts.single(), execution)
                assertSame(execution, currentCoroutineContext()[WorkspaceExecutionContext])
                assertEquals(RuntimeOwnerKind.GIT, execution.runtimeOwner?.kind)
                calls += Call(firstArg(), secondArg(), thirdArg())
                onCommand()
                result
            }
            repository = GitRepository(engine, workspace, modes, factory, settings)
        }

        suspend fun probe(direct: Boolean, operation: FrozenGitOperation = repository.captureOperation()): Boolean =
            if (direct) repository.isRepo(operation) else repository.isGitRepo(SUBDIRECTORY, operation)

        fun assertSingleExecution() {
            assertEquals(1, contexts.size)
            coVerify(exactly = 1) { factory.freeze(any(), any(), any()) }
            assertNull(WorkspaceExecutionContext.current())
        }
    }

    private data class Call(val command: String, val cwd: String?, val timeoutMs: Long)

    private companion object {
        const val WORKSPACE = "/work/git-trust-test"
        const val SUBDIRECTORY = "$WORKSPACE/child"

        fun trustedGit(args: String, probe: Boolean = false, shellPath: String = "/bin/sh"): String {
            val locale = if (probe) "LC_ALL=C " else ""
            val script = "git_worktree=$(pwd -P) && test -n \"\$git_worktree\" && ${locale}git -c \"safe.directory=\$git_worktree\" -c core.quotepath=false $args"
            val quoted = if (shellPath.substringAfterLast('/') == "fish") {
                script.replace("\\", "\\\\").replace("'", "\\'")
            } else {
                script.replace("'", "'\\''")
            }
            return "/bin/sh -c '$quoted'"
        }
    }
}
