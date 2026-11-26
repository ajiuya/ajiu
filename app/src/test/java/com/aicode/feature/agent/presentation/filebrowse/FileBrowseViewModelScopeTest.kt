package com.aicode.feature.agent.presentation.filebrowse

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.SubAgentViewModelQueueFixture
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.InternalLocalFolderAccess
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.LocalFolderUnavailableReason
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isLocalFolderPath
import com.aicode.feature.workspace.domain.localRootPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FileBrowseViewModelScopeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: SubAgentViewModelQueueFixture

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE).edit().clear().commit()
        fixture = SubAgentViewModelQueueFixture(context)
    }

    @After
    fun tearDown() {
        try {
            fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun manualRefreshRecapturesANotReadySourceAtTheSameWorkspaceAndFreezesTheListing() = runTest(dispatcher) {
        val ready = AtomicBoolean()
        val freezes = AtomicInteger()
        val listings = AtomicInteger()
        coEvery { fixture.executionFactory.freeze(null, any(), any()) } coAnswers {
            freezes.incrementAndGet()
            if (!ready.get()) throw CancellationException("Workspace execution source is not ready")
            val owner = secondArg<RuntimeOwner?>()
            val checkSource = thirdArg<(() -> Unit)?>()
            val mode = fixture.executionModeHolder.currentMode()
            val modeRevision = fixture.executionModeHolder.modeSelectionRevision
            checkSource?.invoke()
            WorkspaceExecutionContext(mode, "queue-fixture", fixture.browseFileAccess, null, null, runtimeOwner = owner) {
                checkSource?.invoke()
                if (!ready.get() || fixture.executionModeHolder.currentMode() != mode ||
                    fixture.executionModeHolder.modeSelectionRevision != modeRevision) {
                    throw CancellationException("Fixture source changed")
                }
            }
        }
        every { fixture.browseFileAccess.listFiles(any()) } answers {
            assertNotNull(WorkspaceExecutionContext.current())
            assertEquals("queue-fixture", WorkspaceExecutionContext.current()?.fileCacheIdentity)
            listings.incrementAndGet()
            listOf(FileEntry("restored", false, 0, 0))
        }
        every { fixture.browseFileAccess.exists(any()) } returns false
        // 真实 IO 等待期间虚拟时间可跨过订阅宽限期，持续订阅表示文件页仍可见。
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.viewModel.browseState.collect {}
        }
        fixture.viewModel.setWorkspace("/work")
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { fixture.viewModel.browseState.first { it is FileBrowseState.Error } }
        }
        assertEquals(0, listings.get())
        val before = freezes.get()
        ready.set(true)
        fixture.viewModel.refreshBrowse()
        runCurrent()
        val recovered = withContext(Dispatchers.Default) {
            withTimeout(5_000) { fixture.viewModel.browseState.first { it is FileBrowseState.Success } }
        } as FileBrowseState.Success
        assertTrue(freezes.get() > before)
        assertEquals(1, listings.get())
        assertTrue(recovered.nodes.any { it.entry.name == "restored" })
    }

    @Test
    fun pendingChrootKeepsGrantedHostTreeUsableAndAuthorizationRebuildsTheNormalScope() = runTest(dispatcher) {
        val source = HostBrowseSource()
        val authorized = AtomicBoolean()
        val hostCaptures = AtomicInteger()
        val defaultIo = AtomicInteger()
        val guestIo = AtomicInteger()
        val hostWatching = CompletableDeferred<Unit>()
        val sourceChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val guest = mockk<FileAccessProvider>()
        every { fixture.projectIdentityResolver.environmentChanges } returns sourceChanges
        every { guest.listFiles(WorkspacePathMapper.CONTAINER_ROOT) } returns listOf(FileEntry("authorized-guest", false, 1, 1))
        every { guest.listFiles("/") } returns emptyList()
        every { guest.exists("${WorkspacePathMapper.CONTAINER_ROOT}/.gitignore") } returns false
        fixture.setLocalFolderSnapshot(source.snapshot)
        configureHostCapture(source, hostCaptures)
        configureCapturedIo(defaultIo, guestIo)
        coEvery { fixture.executionFactory.freeze(null, any(), any()) } coAnswers {
            if (!authorized.get()) throw LocalRuntimeAuthorizationRequired()
            val owner = checkNotNull(secondArg<RuntimeOwner?>())
            val checkSource = thirdArg<(() -> Unit)?>()
            val runtime = LocalRuntimeCapture(owner, source.profile.id, 0, source.profile.chrootInstance!!,
                "/work", "/root", "authorized-guest", Any()) { checkSource?.invoke() }
            WorkspaceExecutionContext(fixture.executionModeHolder.currentMode(), "authorized-guest", guest, null, null,
                source.snapshot, owner, runtime) { checkSource?.invoke() }
        }
        every { fixture.fileChangeHub.watchWorkspace(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { fixture.fileChangeHub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } answers {
            assertEquals(source.directory.canonicalFile, firstArg<File>())
            assertEquals(source.grant.rootPath, secondArg<String>())
            hostWatching.complete(Unit)
            emptyFlow()
        }
        val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.viewModel.browseState.collect {}
        }
        try {
            fixture.viewModel.setWorkspace("/work")
            runCurrent()
            awaitNodes { state ->
                state.nodes.any { it.path == source.grant.rootPath } &&
                    state.nodes.any { it.path == WorkspacePathMapper.CONTAINER_ROOT && it.hasError }
            }
            fixture.viewModel.toggleExpand(source.grant.rootPath)
            fixture.viewModel.toggleExpand("/")
            runCurrent()
            val expanded = awaitNodes { state ->
                state.nodes.any { it.path == source.path } && state.nodes.any { it.path == "/" && it.hasError }
            }
            assertFalse(expanded.nodes.any { it.path == "${WorkspacePathMapper.CONTAINER_ROOT}/authorized-guest" })
            withContext(Dispatchers.Default) { withTimeout(5_000) { hostWatching.await() } }
            val captured = fixture.viewModel.captureUserFile(source.path)
            assertTrue(captured.fileAccess is InternalLocalFolderAccess)
            assertNull(captured.localRuntimeCapture)
            assertEquals("phone-data", captured.useCaptured { captured.fileAccess.readFile(source.path) })
            captured.useCaptured { captured.fileAccess.writeFile(source.path, "updated-phone", overwrite = true) }
            assertEquals("updated-phone", File(source.directory, "note.txt").readText())
            val created = CompletableDeferred<Boolean>()
            fixture.viewModel.createBrowseFile(source.grant.rootPath, "created.txt") { created.complete(it) }
            runCurrent()
            assertTrue(withContext(Dispatchers.Default) { withTimeout(5_000) { created.await() } })
            awaitNodes { state -> state.nodes.any { it.path == "${source.grant.rootPath}/created.txt" } }
            assertTrue(File(source.directory, "created.txt").isFile)
            assertEquals("", File(source.directory, "created.txt").readText())
            fixture.viewModel.copyBrowseEntry(source.path, "note.txt")
            runCurrent()
            assertEquals(source.path, fixture.viewModel.browseClipboard.value?.sourcePath)
            val oldPicker = fixture.viewModel.prepareBrowseUpload(source.grant.rootPath)
            assertNotNull(oldPicker)
            assertTrue(hostCaptures.get() > 0)
            assertEquals(0, defaultIo.get())
            assertEquals(0, guestIo.get())
            verify(exactly = 0) { fixture.fileChangeHub.watchWorkspace(any(), any(), any(), any(), any()) }

            authorized.set(true)
            sourceChanges.emit(Unit)
            runCurrent()
            val rebuilt = awaitNodes { state ->
                state.nodes.any { it.path == "${WorkspacePathMapper.CONTAINER_ROOT}/authorized-guest" }
            }
            assertFalse(rebuilt.nodes.single { it.path == WorkspacePathMapper.CONTAINER_ROOT }.hasError)
            assertNull(fixture.viewModel.browseClipboard.value)
            assertNotEquals(oldPicker, fixture.viewModel.prepareBrowseUpload(source.grant.rootPath))
            captured.checkCurrent()
            assertTrue(guestIo.get() > 0)
            assertEquals(0, defaultIo.get())
            coVerify(atLeast = 1) { fixture.executionFactory.freeze(null, any(), any()) }
        } finally {
            subscription.cancel()
            runCurrent()
        }
    }

    @Test
    fun missingOrUnavailableGrantsAndNonAuthorizationFailuresNeverEnterTheHostFallback() = runTest(dispatcher) {
        val source = HostBrowseSource()
        val unavailable = source.grant.copy(available = false, reason = LocalFolderUnavailableReason.PERSISTENT_PERMISSION_REQUIRED)
        val unavailableSnapshot = LocalFolderSnapshot(2, listOf(unavailable),
            listOf(CapturedLocalFolderRoot(unavailable, source.directory))) {}
        val scenarios = listOf(
            LocalRuntimeAuthorizationRequired() to LocalFolderSnapshot.EMPTY,
            LocalRuntimeAuthorizationRequired() to unavailableSnapshot,
            IOException("ordinary source failure") to source.snapshot,
            CancellationException("ordinary source cancellation") to source.snapshot
        )
        val hostCaptures = AtomicInteger()
        val defaultIo = AtomicInteger()
        coEvery { fixture.executionFactory.freezeForPaths(any(), any(), any(), any()) } coAnswers {
            hostCaptures.incrementAndGet()
            throw AssertionError("Only an authorization-required source with an available grant may fall back")
        }
        every { fixture.browseFileAccess.listFiles(any()) } answers {
            defaultIo.incrementAndGet()
            throw AssertionError("Unavailable file scopes must not perform default file I/O")
        }
        every { fixture.fileChangeHub.watchWorkspace(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { fixture.fileChangeHub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } returns emptyFlow()
        for ((failure, snapshot) in scenarios) {
            fixture.closeUi()
            runCurrent()
            fixture.setLocalFolderSnapshot(snapshot)
            coEvery { fixture.executionFactory.freeze(null, any(), any()) } throws failure
            val viewModel = fixture.reopenUi()
            val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.browseState.collect {} }
            try {
                viewModel.setWorkspace("/work")
                runCurrent()
                val error = withContext(Dispatchers.Default) {
                    withTimeout(5_000) { viewModel.browseState.first { it is FileBrowseState.Error } }
                } as FileBrowseState.Error
                assertEquals(failure.message, error.detail)
                assertNull(viewModel.browseClipboard.value)
                assertEquals(0, hostCaptures.get())
                assertEquals(0, defaultIo.get())
            } finally {
                subscription.cancel()
                fixture.closeUi()
                runCurrent()
            }
        }
        coVerify(exactly = 0) { fixture.executionFactory.freezeForPaths(any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.fileChangeHub.watchWorkspace(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.fileChangeHub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) }
    }

    private inner class HostBrowseSource {
        val directory: File = temporary.newFolder()
        val grant = LocalFolderGrant("scope-phone", "epoch-one", "Phone files", localRootPath("scope-phone", "epoch-one"), true)
        val path = "${grant.rootPath}/note.txt"
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "test", isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = ChrootInstance("0123456789abcdef01234567", 1, 2, 3))
        private val fileIdentity = checkNotNull(Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java).fileKey())
        val snapshot = LocalFolderSnapshot(1, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory)),
            checkAuthorization = { checkDirectory(it) }, checkFileSource = { checkDirectory(it) }) {
            if (fixture.localFolderSnapshots.value.revision != 1L) throw CancellationException("Phone grant changed")
        }

        init { File(directory, "note.txt").writeText("phone-data") }

        private fun checkDirectory(root: CapturedLocalFolderRoot) {
            if (root.grant != grant || root.directory.canonicalFile != directory.canonicalFile ||
                Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java).fileKey() != fileIdentity) {
                throw CancellationException("Phone grant directory identity changed")
            }
        }
    }

    private fun configureHostCapture(source: HostBrowseSource, captures: AtomicInteger) {
        coEvery { fixture.executionFactory.freezeForPaths(any(), null, any(), any()) } coAnswers {
            captures.incrementAndGet()
            val paths = firstArg<List<String>>()
            assertTrue(paths.isNotEmpty())
            paths.forEach { source.snapshot.rootFor(it) }
            val owner = arg<RuntimeOwner?>(2)
            val checkSource = arg<(() -> Unit)?>(3)
            val mode = fixture.executionModeHolder.currentMode()
            val modeRevision = fixture.executionModeHolder.modeSelectionRevision
            val profile = ContainerProfileSnapshot("test", "test", listOf(source.profile), 0, 0, fixture)
            val host = InternalLocalFolderAccess(source.snapshot)
            checkSource?.invoke()
            WorkspaceExecutionContext(mode, host.cacheIdentity(), host, null, null, source.snapshot, owner,
                profileSnapshot = profile) {
                checkSource?.invoke()
                source.snapshot.checkCurrent()
                if (fixture.executionModeHolder.currentMode() != mode || fixture.executionModeHolder.modeSelectionRevision != modeRevision) {
                    throw CancellationException("Phone file scope changed")
                }
            }
        }
    }

    private fun configureCapturedIo(defaultIo: AtomicInteger, guestIo: AtomicInteger) {
        fun backend(path: String): FileAccessProvider {
            val execution = WorkspaceExecutionContext.current()
            if (execution == null || execution.fileAccess === fixture.browseFileAccess) {
                defaultIo.incrementAndGet()
                throw AssertionError("File I/O must use the explicitly captured backend")
            }
            if (isLocalFolderPath(path) && execution.fileAccess !is InternalLocalFolderAccess) {
                return InternalLocalFolderAccess(execution.localFolderSnapshot, execution)
            }
            if (execution.fileAccess !is InternalLocalFolderAccess) guestIo.incrementAndGet()
            return execution.fileAccess
        }
        every { fixture.browseFileAccess.listFiles(any()) } answers { backend(firstArg()).listFiles(firstArg()) }
        every { fixture.browseFileAccess.exists(any()) } answers { backend(firstArg()).exists(firstArg()) }
        every { fixture.browseFileAccess.readFile(any()) } answers { backend(firstArg()).readFile(firstArg()) }
        every { fixture.browseFileAccess.writeFile(any(), any(), any(), any()) } answers {
            backend(firstArg()).writeFile(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        every { fixture.browseFileAccess.mkdirs(any()) } answers { backend(firstArg()).mkdirs(firstArg()) }
        every { fixture.browseFileAccess.isDirectory(any()) } answers { backend(firstArg()).isDirectory(firstArg()) }
    }

    private suspend fun awaitNodes(predicate: (FileBrowseState.Success) -> Boolean): FileBrowseState.Success =
        withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                fixture.viewModel.browseState.first { it is FileBrowseState.Success && predicate(it) }
            }
        } as FileBrowseState.Success
}
