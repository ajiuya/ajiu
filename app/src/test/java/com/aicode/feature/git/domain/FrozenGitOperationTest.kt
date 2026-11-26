package com.aicode.feature.git.domain

import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FrozenGitOperationTest {
    private class Fixture {
        val modes = ExecutionModeHolder()
        val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("A", "/work/A", WorkspaceType.INTERNAL)))
        val workspace = mockk<WorkspaceRepository>()
        val engine = mockk<CommandEngine>()
        val factory = mockk<WorkspaceExecutionContextFactory>()
        val settings = mockk<ContainerSettingsRepository>()
        var configurationRevision = 7L
        val contexts = mutableListOf<WorkspaceExecutionContext>()
        val commands = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val repo: GitRepository

        init {
            every { workspace.selection } returns selection
            every { workspace.currentPath() } answers { selection.value.workspace!!.path }
            every { settings.activeProfileSelectionRevision } returns 3L
            every { settings.profileConfigurationRevision } answers { configurationRevision }
            coEvery { factory.freeze(any(), any(), any()) } answers {
                val owner = secondArg<RuntimeOwner?>()
                val check = thirdArg<(() -> Unit)?>() ?: {}
                check()
                WorkspaceExecutionContext(modes.currentMode(), "source", mockk(), null, null,
                    runtimeOwner = owner, checkSource = check).also { contexts += it }
            }
            coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } answers {
                val execution = requireNotNull(WorkspaceExecutionContext.current())
                assertEquals(RuntimeOwnerKind.GIT, execution.runtimeOwner?.kind)
                commands += firstArg<String>() to secondArg<String?>()
                val output = when {
                    firstArg<String>().contains("@{upstream}") -> "fatal: no upstream"
                    firstArg<String>().endsWith(" remote") -> "origin"
                    firstArg<String>().contains("--abbrev-ref HEAD") -> "main"
                    firstArg<String>().contains("status") -> "## main"
                    else -> ""
                }
                CommandResult(output, 0)
            }
            repo = GitRepository(engine, workspace, modes, factory, settings)
        }
    }

    @Test fun pushAndPostActionSnapshotShareOneCapturedRepositoryAndOwner() = runTest {
        val f = Fixture()
        f.repo.setRepoPath("/work/A/repo")
        val operation = f.repo.captureOperation()
        f.repo.push(operation)
        coroutineScope {
            val status = async { f.repo.status(operation) }
            val refs = async { f.repo.loadAllRefs(operation) }
            status.await(); refs.await()
        }
        assertEquals(1, f.contexts.size)
        assertEquals(operation.owner, f.contexts.single().runtimeOwner)
        assertTrue(f.commands.size >= 6)
        assertTrue(f.commands.all { it.second == "/work/A/repo" })
    }

    @Test fun repositoryRoundTripDuringFirstCommandRejectsAllLaterPushCommands() = runTest {
        val f = Fixture()
        f.repo.setRepoPath("/work/A/repo")
        val operation = f.repo.captureOperation()
        coEvery { f.engine.runCommandSyncUnbounded(any(), any(), any()) } answers {
            f.commands += firstArg<String>() to secondArg<String?>()
            f.repo.setRepoPath("/work/A/other")
            f.repo.setRepoPath("/work/A/repo")
            CommandResult("fatal: no upstream", 0)
        }
        try { f.repo.push(operation); fail("Expected expired source") } catch (_: GitOperationExpiredException) { }
        assertEquals(1, f.commands.size)
        assertFalse(f.commands.single().first.contains("push"))
        assertNotEquals(operation.owner, f.repo.captureOperation().owner)
    }

    @Test fun workspaceRoundTripRejectsRefreshAndLaterDiffFromOldSnapshot() = runTest {
        val f = Fixture()
        val operation = f.repo.captureOperation()
        f.repo.status(operation)
        val count = f.commands.size
        f.selection.value = WorkspaceRepository.Selection(Workspace("B", "/work/B", WorkspaceType.INTERNAL))
        f.selection.value = WorkspaceRepository.Selection(Workspace("A", "/work/A", WorkspaceType.INTERNAL))
        try { f.repo.loadAllRefs(operation); fail("Expected stale refresh") } catch (_: GitOperationExpiredException) { }
        try { f.repo.showFileContent("HEAD", "file.kt", operation); fail("Expected stale diff") } catch (_: GitOperationExpiredException) { }
        assertEquals(count, f.commands.size)
        assertNotEquals(operation.owner, f.repo.captureOperation().owner)
    }

    @Test fun profileAndModeChangesRejectEvenBeforeFirstCommand() = runTest {
        val f = Fixture()
        val operation = f.repo.captureOperation()
        f.configurationRevision++
        try { f.repo.stageAll(operation); fail("Expected stale profile") } catch (_: GitOperationExpiredException) { }
        val current = f.repo.captureOperation()
        f.modes.setMode(ExecutionMode.REMOTE_SSH)
        f.modes.setMode(ExecutionMode.LOCAL_PROOT)
        try { f.repo.status(current); fail("Expected stale mode") } catch (_: GitOperationExpiredException) { }
        assertTrue(f.commands.isEmpty())
    }

    @Test fun missingAuthorizationDoesNotTurnDiscoveryIntoNotARepositoryOrRetryInit() = runTest {
        val f = Fixture()
        coEvery { f.factory.freeze(any(), any(), any()) } throws LocalRuntimeAuthorizationRequired()
        for (block in listOf<suspend () -> Any>({ f.repo.discoverRepos() }, { f.repo.initRepo() })) {
            try { block(); fail("Expected owner authorization") } catch (_: LocalRuntimeAuthorizationRequired) { }
        }
        coVerify(exactly = 0) { f.engine.runCommandSyncUnbounded(any(), any(), any()) }
    }

    @Test fun missingWorkspaceCannotCreateAGitOwner() {
        val f = Fixture()
        f.selection.value = WorkspaceRepository.Selection(null)
        try { f.repo.captureOperation(); fail("Expected workspace requirement") } catch (_: IllegalStateException) { }
    }
}
