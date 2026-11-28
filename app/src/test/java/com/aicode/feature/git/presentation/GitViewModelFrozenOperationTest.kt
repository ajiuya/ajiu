package com.aicode.feature.git.presentation

import android.app.Application
import com.aicode.R
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.git.domain.GitErrorMessage
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
    @Volatile private var probeResult = CommandResult("true", 0)
    @Volatile private var refsOutput = ""
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
            val command = firstArg<String>()
            if (command.contains("--is-inside-work-tree")) probeResult
            else CommandResult(when {
                command.contains("status") -> "## main"
                command.contains("for-each-ref") -> refsOutput
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

    @Test fun refreshReportsDubiousOwnershipInsteadOfNonRepository() = testScope.runTest {
        val output = "fatal: detected dubious ownership in repository at '$root'"
        probeResult = CommandResult(output, 128)
        createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.notARepo)
        assertFalse(state.gitUnavailable)
        assertEquals(output, state.repositoryError)
        assertFalse(state.branchesLoaded)
        assertTrue(commands.none { it.first.contains("for-each-ref") })
        viewModel.consumeToast()
        assertEquals(output, viewModel.state.value.repositoryError)
    }

    @Test fun refreshPreservesLocalPermissionErrorsWithoutCallingThemRemoteAuthenticationFailures() = testScope.runTest {
        val output = "fatal: cannot open '.git/config': Permission denied"
        probeResult = CommandResult(output, 128)
        createViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.notARepo)
        assertEquals(output, viewModel.state.value.repositoryError)
        assertFalse(viewModel.state.value.gitUnavailable)
    }

    @Test fun refreshDoesNotClassifyWordsInsideAPathAsTheRepositoryProbeResult() = testScope.runTest {
        val output = "fatal: detected dubious ownership in repository at '/work/not a git repository'"
        probeResult = CommandResult(output, 128)
        createViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.notARepo)
        assertEquals(output, viewModel.state.value.repositoryError)
    }

    @Test fun refreshReportsUnknownProbeExitInsteadOfNonRepository() = testScope.runTest {
        val output = "fatal: unexpected repository probe failure"
        probeResult = CommandResult(output, 42)
        createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.notARepo)
        assertFalse(state.gitUnavailable)
        assertEquals(output, state.repositoryError)
        assertFalse(state.branchesLoaded)
    }

    @Test fun refreshClearsProbeErrorAndLoadsBranchesAfterRetry() = testScope.runTest {
        probeResult = CommandResult("/bin/sh: git: not found", 127)
        createViewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.repositoryError)
        assertTrue(viewModel.state.value.gitUnavailable)

        probeResult = CommandResult("true", 0)
        refsOutput = "main\u001f0123456789abcdef\u001f*\u001frefs/heads/main"
        viewModel.refresh()
        assertTrue(viewModel.state.value.loading)
        assertNull(viewModel.state.value.repositoryError)
        assertFalse(viewModel.state.value.gitUnavailable)
        advanceUntilIdle()
        awaitSnapshot()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.notARepo)
        assertFalse(state.gitUnavailable)
        assertNull(state.repositoryError)
        assertTrue(state.branchesLoaded)
        assertEquals("main", state.branches.single().name)
        assertTrue(state.branches.single().current)
        assertEquals(root, state.currentRepo)
        assertTrue(state.toast.isNullOrBlank())
    }

    @Test fun refreshRecognizesExplicitNonRepositoryWithoutError() = testScope.runTest {
        probeResult = CommandResult("fatal: not a git repository (or any of the parent directories): .git", 128)
        createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertTrue(state.notARepo)
        assertNull(state.repositoryError)
        assertFalse(state.gitUnavailable)
        assertNull(state.currentRepo)
        assertTrue(state.toast.isNullOrBlank())
    }

    @Test fun refreshDoesNotKeepPreviousNonRepositoryStateWhenProbeFails() = testScope.runTest {
        probeResult = CommandResult("fatal: not a git repository", 128)
        createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.notARepo)

        val output = "fatal: detected dubious ownership in repository at '$root'"
        probeResult = CommandResult(output, 128)
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.notARepo)
        assertFalse(viewModel.state.value.gitUnavailable)
        assertEquals(output, viewModel.state.value.repositoryError)
    }

    @Test fun refreshMarksMissingGitForPreparationAndStillAllowsInitialization() = testScope.runTest {
        val output = "/bin/sh: git: not found"
        probeResult = CommandResult(output, 127)
        createViewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.loading)
        assertFalse(state.notARepo)
        assertTrue(state.gitUnavailable)
        assertEquals(output, state.repositoryError)

        viewModel.initRepo()
        advanceUntilIdle()
        assertTrue(commands.any { it.first == "git -c core.quotepath=false init -b main" })
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.notARepo)
        assertTrue(viewModel.state.value.gitUnavailable)
        assertEquals(output, viewModel.state.value.repositoryError)
    }

    @Test fun successfulActionKeepsActualProbeFailureInsteadOfNonRepository() = testScope.runTest {
        createViewModel()
        advanceUntilIdle()
        awaitSnapshot()
        val output = "fatal: detected dubious ownership in repository at '$root'"
        probeResult = CommandResult(output, 128)

        viewModel.stageAll()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(commands.any { it.first == "git -c core.quotepath=false add -A" })
        assertFalse(state.busy)
        assertFalse(state.notARepo)
        assertFalse(state.gitUnavailable)
        assertEquals(output, state.repositoryError)
        val context = RuntimeEnvironment.getApplication()
        val success = context.getString(R.string.git_toast_action_success, context.getString(R.string.git_action_stage_all))
        assertEquals(context.getString(R.string.git_toast_action_refresh_failed, success), state.toast)
        viewModel.consumeToast()
        assertEquals(output, viewModel.state.value.repositoryError)
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
