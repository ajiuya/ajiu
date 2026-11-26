package com.aicode.feature.git.presentation

import android.app.Application
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class GitViewModelFrozenOperationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val engine = mockk<CommandEngine>()
    private val factory = mockk<WorkspaceExecutionContextFactory>()
    private val settings = mockk<ContainerSettingsRepository>()
    private val workspace = mockk<WorkspaceRepository>()
    private val modes = ExecutionModeHolder()
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(null))
    private lateinit var repository: GitRepository
    private lateinit var viewModel: GitViewModel
    private lateinit var root: String
    private val commands = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        root = temporary.newFolder("work").absolutePath
        selection.value = WorkspaceRepository.Selection(Workspace("work", root, WorkspaceType.INTERNAL))
        every { workspace.selection } returns selection
        every { workspace.currentPath() } answers { selection.value.workspace!!.path }
        every { settings.activeProfileSelectionRevision } returns 1L
        every { settings.profileConfigurationRevision } returns 1L
        every { engine.notReadyHint() } returns null
        coEvery { factory.freeze(any(), any(), any()) } answers {
            val owner = secondArg<com.aicode.feature.agent.domain.container.runtime.RuntimeOwner?>()
            val check = thirdArg<(() -> Unit)?>() ?: {}
            check()
            WorkspaceExecutionContext(modes.currentMode(), "git-ui", mockk(), null, null,
                runtimeOwner = owner, checkSource = check)
        }
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } answers {
            commands += firstArg<String>() to secondArg<String?>()
            CommandResult(when {
                firstArg<String>().contains("--is-inside-work-tree") -> "true"
                firstArg<String>().contains("status") -> "## main"
                else -> ""
            }, 0)
        }
        repository = GitRepository(engine, workspace, modes, factory, settings)
    }

    private fun createViewModel() {
        viewModel = GitViewModel(repository, RuntimeEnvironment.getApplication(), mockk(), mockk<AgentWorkflow>())
    }

    @After fun cleanup() { Dispatchers.resetMain() }

    private suspend fun awaitSnapshot() = withContext(Dispatchers.Default) {
        withTimeout(5_000L) { viewModel.state.first { !it.loading && (it.branchesLoaded || it.toast != null) } }
    }

    @Test fun refreshKeepsParallelSnapshotAndBackgroundBranchesOnOneFrozenRepository() = testScope.runTest {
        createViewModel()
        advanceUntilIdle()
        awaitSnapshot()
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.notARepo)
        assertTrue(viewModel.state.value.branchesLoaded)
        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { it.second == root })
        assertTrue(viewModel.state.value.toast.isNullOrBlank())
    }

    @Test fun repositoryRoundTripAfterDisplayedSnapshotRejectsLaterDetailAndDiffLoads() = testScope.runTest {
        createViewModel()
        advanceUntilIdle()
        awaitSnapshot()
        val count = commands.size
        repository.setRepoPath("$root/other")
        repository.setRepoPath(root)
        viewModel.openCommitDetail("hash")
        viewModel.loadCommitFileDiff("hash", "file.kt")
        advanceUntilIdle()
        assertEquals(count, commands.size)
        assertFalse(viewModel.state.value.toast.isNullOrBlank())
        assertNull(viewModel.state.value.commitDetailHash)
        assertFalse(viewModel.state.value.diffVisible)
    }

    @Test fun workspaceRoundTripBeforeQueuedRefreshDoesNotRunACommandForNewSelection() = testScope.runTest {
        createViewModel()
        coVerify(exactly = 0) { factory.freeze(any(), any(), any()) }
        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), any(), any()) }
        assertTrue(commands.isEmpty())
        selection.value = WorkspaceRepository.Selection(Workspace("other", "$root/other", WorkspaceType.INTERNAL))
        selection.value = WorkspaceRepository.Selection(Workspace("work", root, WorkspaceType.INTERNAL))
        advanceUntilIdle()
        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), any(), any()) }
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.toast.isNullOrBlank())
    }

    @Test fun workspaceRoundTripWhileRuntimeCaptureIsSuspendedRejectsDiscoveryBeforeFirstCommand() = testScope.runTest {
        val freezeEntered = CompletableDeferred<Unit>()
        val resumeFreeze = CompletableDeferred<Unit>()
        coEvery { factory.freeze(any(), any(), any()) } coAnswers {
            val owner = secondArg<com.aicode.feature.agent.domain.container.runtime.RuntimeOwner?>()
            val check = thirdArg<(() -> Unit)?>() ?: {}
            freezeEntered.complete(Unit)
            resumeFreeze.await()
            check()
            WorkspaceExecutionContext(modes.currentMode(), "git-ui", mockk(), null, null,
                runtimeOwner = owner, checkSource = check)
        }
        createViewModel()
        runCurrent()
        assertTrue(freezeEntered.isCompleted)
        coVerify(exactly = 1) { factory.freeze(any(), any(), any()) }
        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), any(), any()) }
        assertTrue(commands.isEmpty())
        selection.value = WorkspaceRepository.Selection(Workspace("other", "$root/other", WorkspaceType.INTERNAL))
        selection.value = WorkspaceRepository.Selection(Workspace("work", root, WorkspaceType.INTERNAL))
        resumeFreeze.complete(Unit)
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.freeze(any(), any(), any()) }
        coVerify(exactly = 0) { engine.runCommandSyncUnbounded(any(), any(), any()) }
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.toast.isNullOrBlank())
    }
}
