package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
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

class GitRepositoryChrootSetupTest {
    @Test
    fun installedGitIsVerifiedWithoutInstallingBeforeCloneOrInit() = runTest {
        for (shellPath in listOf("/bin/sh", "/usr/bin/fish", "/bin/csh")) {
            for (clone in listOf(true, false)) {
                val fixture = Fixture(shellPath = shellPath)
                fixture.probeResult = CommandResult("/usr/bin/git", 0)

                fixture.runWrite(clone)

                assertEquals(listOf(PROBE, VERSION, writeCommand(clone)), fixture.calls.map { it.command })
                fixture.assertSingleExecution()
            }
        }
    }

    @Test
    fun missingGitIsInstalledAndVerifiedBeforeCloneInTheSameFrozenOperation() = runTest {
        val fixture = Fixture(shellPath = "/usr/bin/fish")
        val operation = fixture.repository.captureOperation()
        val url = "https://example.com/team/repo'with space.git"

        val result = fixture.repository.cloneRepo("  $url  ", operation)

        assertEquals("write completed", result)
        assertEquals(4, fixture.calls.size)
        assertEquals(PROBE, fixture.calls[0].command)
        assertTrue(fixture.calls[1].isInstall)
        assertEquals(600_000L, fixture.calls[1].timeoutMs)
        assertEquals(VERSION, fixture.calls[2].command)
        assertEquals("git -c core.quotepath=false clone 'https://example.com/team/repo'\\''with space.git' .", fixture.calls[3].command)
        assertEquals(600_000L, fixture.calls[3].timeoutMs)
        assertEquals(operation.owner, fixture.contexts.single().runtimeOwner)
        assertEquals("/usr/bin/fish", operation.runtimeProfile?.shellPath)
        fixture.assertSingleExecution()
    }

    @Test
    fun missingGitIsInstalledAndVerifiedBeforeInitializingMain() = runTest {
        val fixture = Fixture(shellPath = "/bin/csh")

        assertEquals("write completed", fixture.repository.initRepo())

        assertEquals(4, fixture.calls.size)
        assertEquals(PROBE, fixture.calls[0].command)
        assertTrue(fixture.calls[1].isInstall)
        assertEquals(VERSION, fixture.calls[2].command)
        assertEquals(writeCommand(clone = false), fixture.calls[3].command)
        fixture.assertSingleExecution()
    }

    @Test
    fun installationFailureOrUnknownExitStopsCloneAndInitWithoutHidingTheOutput() = runTest {
        for (clone in listOf(true, false)) {
            for (exitCode in listOf(23, null)) {
                val fixture = Fixture()
                fixture.installResult = CommandResult("Package download failed: network unavailable", exitCode)

                val failure = runCatching { fixture.runWrite(clone) }.exceptionOrNull()

                assertTrue(failure is GitCommandFailureException)
                assertEquals(fixture.installResult.output, (failure as GitCommandFailureException).output)
                assertEquals(2, fixture.calls.size)
                assertEquals(PROBE, fixture.calls[0].command)
                assertTrue(fixture.calls[1].isInstall)
                fixture.assertSingleExecution()
            }
        }
    }

    @Test
    fun failedVerificationAfterSuccessfulInstallationNeverRunsClone() = runTest {
        val fixture = Fixture()
        fixture.versionResult = CommandResult("/bin/sh: 1: git: not found", 127)

        val failure = runCatching { fixture.repository.cloneRepo(URL) }.exceptionOrNull()

        assertTrue(failure is GitCommandFailureException)
        assertEquals(fixture.versionResult.output, (failure as GitCommandFailureException).output)
        assertEquals(3, fixture.calls.size)
        assertTrue(fixture.calls[1].isInstall)
        assertEquals(VERSION, fixture.calls.last().command)
        fixture.assertSingleExecution()
    }

    @Test
    fun existingButBrokenGitIsNotReinstalledAndDoesNotRunClone() = runTest {
        val fixture = Fixture()
        fixture.probeResult = CommandResult("/usr/bin/git", 0)
        fixture.versionResult = CommandResult("git: error while loading shared libraries", 127)

        val failure = runCatching { fixture.repository.cloneRepo(URL) }.exceptionOrNull()

        assertTrue(failure is GitCommandFailureException)
        assertEquals(fixture.versionResult.output, (failure as GitCommandFailureException).output)
        assertEquals(listOf(PROBE, VERSION), fixture.calls.map { it.command })
    }

    @Test
    fun configurationChangeDuringProbeOrInstallPreventsAllRemainingCommands() = runTest {
        for (duringInstall in listOf(false, true)) {
            val fixture = Fixture()
            fixture.onCommand = { call ->
                if (call.isInstall == duringInstall) fixture.configurationRevision++
            }

            val failure = runCatching { fixture.repository.cloneRepo(URL) }.exceptionOrNull()

            assertTrue(failure is GitOperationExpiredException)
            assertEquals(if (duringInstall) 2 else 1, fixture.calls.size)
            assertTrue(fixture.calls.none { it.command == VERSION || it.command.contains(" clone ") })
            fixture.assertSingleExecution()
        }
    }

    @Test
    fun cancellationDuringInstallationStopsCloneAndPreservesCancellation() = runTest {
        val fixture = Fixture()
        val cancelled = CancellationException("Git dependency installation cancelled")
        fixture.onCommand = { call -> if (call.isInstall) throw cancelled }

        val failure = runCatching { fixture.repository.cloneRepo(URL) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertSame(cancelled, generateSequence(failure) { it.cause }.lastOrNull())
        assertEquals(2, fixture.calls.size)
        assertTrue(fixture.calls.last().isInstall)
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun unknownProbeExitAndTruncatedInstallOutputStopBeforeWriting() = runTest {
        val unknownProbe = Fixture()
        unknownProbe.probeResult = CommandResult("Probe timed out", null)
        val probeFailure = runCatching { unknownProbe.repository.cloneRepo(URL) }.exceptionOrNull()
        assertTrue(probeFailure is GitCommandFailureException)
        assertEquals("Probe timed out", (probeFailure as GitCommandFailureException).output)
        assertEquals(listOf(PROBE), unknownProbe.calls.map { it.command })

        val truncatedInstall = Fixture()
        truncatedInstall.installResult = CommandResult("Partial package output", 0, outputTruncated = true)
        val installFailure = runCatching { truncatedInstall.repository.cloneRepo(URL) }.exceptionOrNull()
        assertTrue(installFailure is GitOutputTooLargeException)
        assertEquals(2, truncatedInstall.calls.size)
        assertTrue(truncatedInstall.calls.last().isInstall)
    }

    @Test
    fun prootAndRemoteSshKeepTheirExistingCloneAndInitCommandsWithoutSetup() = runTest {
        for (mode in ExecutionMode.entries) {
            for (clone in listOf(true, false)) {
                val fixture = Fixture(chroot = false, mode = mode)

                fixture.runWrite(clone)

                assertEquals(listOf(writeCommand(clone)), fixture.calls.map { it.command })
                fixture.assertSingleExecution()
            }
        }
    }

    @Test
    fun repositoryDiscoveryAndRefreshNeverInstallGitInChroot() = runTest {
        val fixture = Fixture()
        fixture.gitResult = CommandResult("/bin/sh: 1: git: not found", 127)
        val operation = fixture.repository.captureOperation()

        assertFalse(fixture.repository.isRepo(operation))
        assertFalse(fixture.repository.isGitRepo(WORKSPACE, operation))
        assertTrue(fixture.repository.discoverRepos(operation).isEmpty())
        fixture.repository.status(operation)

        assertTrue(fixture.calls.isNotEmpty())
        assertTrue(fixture.calls.none { it.isInstall || it.command == PROBE || it.command == VERSION })
        fixture.assertSingleExecution()
    }

    private class Fixture(
        chroot: Boolean = true,
        mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        shellPath: String = "/bin/sh"
    ) {
        val modes = ExecutionModeHolder().apply { setMode(mode) }
        val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("test", WORKSPACE, WorkspaceType.INTERNAL)))
        val workspace = mockk<WorkspaceRepository>()
        val settings = mockk<ContainerSettingsRepository>()
        val engine = mockk<CommandEngine>()
        val factory = mockk<WorkspaceExecutionContextFactory>()
        val contexts = mutableListOf<WorkspaceExecutionContext>()
        val calls = mutableListOf<Call>()
        var configurationRevision = 7L
        var probeResult = CommandResult("", 1)
        var installResult = CommandResult("Git packages installed", 0)
        var versionResult = CommandResult("git version 2.47.3", 0)
        var gitResult = CommandResult("write completed", 0)
        var onCommand: (Call) -> Unit = {}
        val repository: GitRepository

        init {
            every { workspace.selection } returns selection
            every { settings.activeProfileSelectionRevision } returns 3L
            every { settings.profileConfigurationRevision } answers { configurationRevision }
            val profile = ContainerProfile.BUILTIN_ALPINE.copy(
                localRuntime = if (chroot) LocalContainerRuntime.CHROOT else LocalContainerRuntime.PROOT,
                shellPath = shellPath
            )
            coEvery { factory.freeze(any(), any(), any()) } answers {
                val owner = requireNotNull(secondArg<RuntimeOwner?>())
                val checkSource = requireNotNull(thirdArg<(() -> Unit)?>())
                checkSource()
                val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 3L, configurationRevision, settings)
                WorkspaceExecutionContext(
                    modes.currentMode(), "git-setup-test", mockk(), null, null,
                    runtimeOwner = owner, profileSnapshot = snapshot, checkSource = checkSource
                ).also { contexts += it }
            }
            coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } coAnswers {
                val execution = requireNotNull(WorkspaceExecutionContext.current())
                assertSame(contexts.single(), execution)
                assertSame(execution, currentCoroutineContext()[WorkspaceExecutionContext])
                assertEquals(RuntimeOwnerKind.GIT, execution.runtimeOwner?.kind)
                val call = Call(firstArg(), secondArg(), thirdArg())
                if (call.isInstall) {
                    assertTrue(call.command.startsWith("/bin/sh -c 'set -e; "))
                    assertTrue(call.command.endsWith(" esac'"))
                    assertFalse(call.command.contains('\n'))
                }
                calls += call
                onCommand(call)
                when {
                    call.command == PROBE -> probeResult
                    call.isInstall -> installResult
                    call.command == VERSION -> versionResult
                    else -> gitResult
                }
            }
            repository = GitRepository(engine, workspace, modes, factory, settings)
        }

        suspend fun runWrite(clone: Boolean): String =
            if (clone) repository.cloneRepo(URL) else repository.initRepo()

        fun assertSingleExecution() {
            assertEquals(1, contexts.size)
            assertTrue(calls.all { it.cwd == WORKSPACE })
            coVerify(exactly = 1) { factory.freeze(any(), any(), any()) }
            assertNull(WorkspaceExecutionContext.current())
        }
    }

    private data class Call(val command: String, val cwd: String?, val timeoutMs: Long) {
        val isInstall get() = command.contains(". /root/.aicode/lib/env-common.sh")
    }

    private companion object {
        const val WORKSPACE = "/work/git-setup-test"
        const val URL = "https://example.com/team/repo.git"
        const val PROBE = "/bin/sh -c 'command -v git'"
        const val VERSION = "/bin/sh -c 'git --version'"

        fun writeCommand(clone: Boolean): String = if (clone) {
            "git -c core.quotepath=false clone $URL ."
        } else {
            "git -c core.quotepath=false init -b main"
        }
    }
}
