package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.data.EditorSettingsRepository
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CodeEditorViewModelTest {
    private val context = mockk<Context>()
    private val settings = mockk<EditorSettingsRepository>()
    private val workspace = mockk<WorkspaceRepository>()
    private val factory = mockk<WorkspaceExecutionContextFactory>()
    private val access = mockk<FileAccessProvider>()
    private val selection = MutableStateFlow(WorkspaceRepository.Selection(null))
    private val owners = Collections.synchronizedList(mutableListOf<RuntimeOwner>())
    private val checks = Collections.synchronizedList(mutableListOf<() -> Unit>())
    private val store = ViewModelStore()
    private lateinit var viewModel: CodeEditorViewModel
    @Volatile private var version = "initial"
    @Volatile private var text = "contents"
    @Volatile private var denied = false

    @Before fun prepare() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkObject(TextMateSetup)
        every { TextMateSetup.ensureInitialized(context) } returns Unit
        every { TextMateSetup.scopeNameFor(any()) } returns null
        every { settings.settingsFlow } returns flowOf(EditorSettings())
        every { workspace.selection } returns selection
        every { access.cacheIdentity() } returns "chroot:fixture"
        every { access.exists(any()) } answers { assertInstalled(); true }
        every { access.isFile(any()) } answers { assertInstalled(); true }
        every { access.fileSize(any()) } answers { assertInstalled(); text.toByteArray().size.toLong() }
        every { access.readBytes(any(), 2L * 1024 * 1024) } answers { assertInstalled(); text.toByteArray() }
        every { access.fingerprint(any()) } answers { assertInstalled(); version }
        every { access.toDisplayPath(any()) } answers { assertInstalled(); firstArg() }
        every { access.writeFile(any(), any(), any(), any()) } answers {
            assertInstalled(); text = secondArg(); version = "written"; Unit
        }
        coEvery { factory.freezeForPaths(any(), any(), any(), any()) } coAnswers {
            val owner = requireNotNull(arg<RuntimeOwner?>(2))
            val check = requireNotNull(arg<(() -> Unit)?>(3))
            owners += owner
            checks += check
            check()
            if (denied) throw LocalRuntimeAuthorizationRequired()
            WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "chroot:fixture", access, null, null,
                runtimeOwner = owner, checkSource = check)
        }
        viewModel = CodeEditorViewModel(context, factory, settings, FileVersionRegistry(), workspace, FileBackendRouting())
        store.put("editor", viewModel)
    }

    @After fun finish() {
        store.clear()
        Dispatchers.resetMain()
        unmockkObject(TextMateSetup)
    }

    private fun assertInstalled() {
        assertEquals(RuntimeOwnerKind.FILE, WorkspaceExecutionContext.current()!!.runtimeOwner!!.kind)
        assertSame(access, WorkspaceExecutionContext.current()!!.fileAccess)
    }

    private suspend fun loaded() = withTimeout(5_000) { viewModel.uiState.first { it !is EditorUiState.Loading } }
    private suspend fun saved(): SaveResult = withTimeout(5_000) {
        viewModel.saveEvents.first().also { viewModel.saving.first { !it } }
    }

    @Test fun loadAndBothSaveModesStayOnTheSameCapturedFileExecution() = runBlocking {
        viewModel.load("~/workspace/file")
        assertEquals(EditorUiState.Success("contents", null), loaded())
        viewModel.save("first")
        assertSame(SaveResult.Success, saved())
        viewModel.saveForce("second")
        assertSame(SaveResult.Success, saved())
        assertEquals("second", text)
        assertEquals(1, owners.size)
        coVerify(exactly = 1) { factory.freezeForPaths(listOf("~/workspace/file"), null, any(), any()) }
        verify(exactly = 0) { access.readBytes(any()) }
        verify(exactly = 0) { access.copyToLocal(any()) }
    }

    @Test fun authorizationFailureDoesNotFallBackAndRetryUsesThePendingOwner() = runBlocking {
        denied = true
        viewModel.load("~/workspace/file")
        assertTrue(loaded() is EditorUiState.Error)
        verify(exactly = 0) { access.exists(any()) }
        val pending = owners.single()
        denied = false
        viewModel.load("~/workspace/file")
        assertTrue(loaded() is EditorUiState.Success)
        assertEquals(listOf(pending, pending), owners.toList())
        checks.first().invoke()
    }

    @Test fun releasingTheUiRevokesThePendingSourceAndReopeningCreatesAnotherOwner() = runBlocking {
        denied = true
        viewModel.load("~/workspace/file")
        assertTrue(loaded() is EditorUiState.Error)
        val pendingOwner = owners.single()
        val pendingCheck = checks.single()
        pendingCheck()

        viewModel.release()
        viewModel.release()

        assertTrue(requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).isActive)
        assertThrows(CancellationException::class.java) { pendingCheck() }
        assertSame(EditorUiState.Loading, viewModel.uiState.value)
        assertFalse(viewModel.saving.value)
        verify(exactly = 0) { access.exists(any()) }
        verify(exactly = 0) { access.writeFile(any(), any(), any(), any()) }

        denied = false
        viewModel.load("~/workspace/file")
        assertEquals(EditorUiState.Success("contents", null), loaded())
        assertNotEquals(pendingOwner, owners.last())
        assertThrows(CancellationException::class.java) { pendingCheck() }
        checks.last().invoke()
    }

    @Test fun releasingALoadedEditorPreventsNormalAndForcedSavesWithoutClearingTheVm() = runBlocking {
        viewModel.load("~/workspace/file")
        assertEquals(EditorUiState.Success("contents", null), loaded())
        val loadedCheck = checks.single()

        viewModel.release()
        viewModel.save("must not write")
        viewModel.saveForce("must not force write")

        assertTrue(requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).isActive)
        assertThrows(CancellationException::class.java) { loadedCheck() }
        assertSame(EditorUiState.Loading, viewModel.uiState.value)
        assertFalse(viewModel.saving.value)
        verify(exactly = 0) { access.writeFile(any(), any(), any(), any()) }
        verify(exactly = 1) { access.fingerprint(any()) }
        coVerify(exactly = 1) { factory.freezeForPaths(any(), any(), any(), any()) }
    }

    @Test fun conflictsStillRequireExplicitForceAndFingerprintFailureCannotBecomeAWrite() = runBlocking {
        viewModel.load("~/workspace/file")
        loaded()
        text = "external"; version = "external"
        viewModel.save("edited")
        assertEquals(SaveResult.Conflict("external"), saved())
        verify(exactly = 0) { access.writeFile(any(), any(), any(), any()) }
        every { access.fingerprint(any()) } throws IOException("Fingerprint unavailable")
        viewModel.save("must not write")
        assertTrue(saved() is SaveResult.Error)
        verify(exactly = 0) { access.writeFile(any(), any(), any(), any()) }
    }

    @Test fun selectionAbaRevokesLoadedSavesAndNewCaptureGetsADifferentOwner() = runBlocking {
        viewModel.load("~/workspace/file")
        loaded()
        val oldOwner = owners.single()
        selection.value = WorkspaceRepository.Selection(null)
        selection.value = WorkspaceRepository.Selection(null)
        viewModel.saveForce("forbidden")
        assertTrue(saved() is SaveResult.Error)
        verify(exactly = 0) { access.writeFile(any(), any(), any(), any()) }
        assertThrows(CancellationException::class.java) { checks.first().invoke() }
        viewModel.load("~/workspace/file")
        assertTrue(loaded() is EditorUiState.Success)
        assertNotEquals(oldOwner, owners.last())
    }

    @Test fun closingTheVmRevokesItsStableSourceEvenAfterTheLoadJobCompleted() = runBlocking {
        viewModel.load("~/workspace/file")
        loaded()
        checks.single().invoke()
        store.clear()
        assertThrows(CancellationException::class.java) { checks.single().invoke() }
        assertFalse(viewModel.saving.value)
    }

    @Test fun replacingAFileCancelsAnInFlightReadAndNeverPublishesItsOldContent() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        every { access.readBytes("~/workspace/old", any()) } answers {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            finished.complete(Unit)
            "old".toByteArray()
        }
        try {
            viewModel.load("~/workspace/old")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            viewModel.load("~/workspace/new")
            assertEquals(EditorUiState.Success("contents", null), loaded())
            release.countDown()
            withTimeout(5_000) { finished.await() }
            delay(30)
            assertEquals(EditorUiState.Success("contents", null), viewModel.uiState.value)
            assertThrows(CancellationException::class.java) { checks.first().invoke() }
            assertNotEquals(owners.first(), owners.last())
        } finally { release.countDown() }
    }
}
