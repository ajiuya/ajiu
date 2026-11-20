package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.DelegatingCommandEngine
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceExecutionContextTest {
    @get:Rule val temporary = TemporaryFolder()
    private val holder = ExecutionModeHolder()
    private val settings = mockk<ContainerSettingsRepository>()
    private val workspace = mockk<WorkspaceRepository>()
    private val installer = mockk<ContainerInstaller>()
    private val home = mockk<PathHomeResolver>()
    private val remote = mockk<RemoteSftpFileAccess>()
    private lateinit var root: File
    private lateinit var rootfs: File
    private lateinit var mapper: WorkspacePathMapper
    private lateinit var access: DelegatingFileAccess
    private lateinit var factory: WorkspaceExecutionContextFactory
    private var homePath = "/root"

    @Before
    fun prepare() {
        root = temporary.newFolder("workspace")
        rootfs = temporary.newFolder("rootfs")
        val configRoot = temporary.newFolder("config")
        every { workspace.currentPath() } answers { root.absolutePath }
        every { installer.rootfsDirFor(any()) } answers { rootfs }
        every { installer.aicodeDir } returns configRoot
        every { settings.activeProfileIdFlow } returns flowOf(ContainerProfile.BUILTIN_ID)
        val mapperSettings = mockk<ContainerSettingsRepository>()
        val mapperInitialized = CompletableDeferred<Job>()
        every { mapperSettings.customProfilesFlow } returns flowOf(listOf(ContainerProfile.BUILTIN_ALPINE))
        every { mapperSettings.activeProfileIdFlow } returns flow {
            emit(ContainerProfile.BUILTIN_ID)
            mapperInitialized.complete(currentCoroutineContext()[Job]!!)
        }
        every { home.home() } answers { homePath }
        every { home.expandHome(any()) } answers {
            val path = firstArg<String>()
            when {
                path == "~" -> homePath
                path.startsWith("~/") -> homePath + path.removePrefix("~")
                else -> path
            }
        }
        mapper = WorkspacePathMapper(workspace, installer, mapperSettings, home)
        runBlocking { withTimeout(5_000) { mapperInitialized.await().join() } }
        val local = LocalFileAccess(mapper)
        val localFolders = mockk<LocalFolderGrantRepository>(relaxed = true) {
            every { snapshot() } returns LocalFolderSnapshot.EMPTY
        }
        every { settings.activeProfileSelectionRevision } returns 0L
        access = DelegatingFileAccess(holder, local, remote, localFolders)
        factory = WorkspaceExecutionContextFactory(access, holder, local, remote, settings, localFolders)
    }

    @Test
    fun factoryChecksProjectCaptureAndCarriesIdentityAcrossDispatchAndSuspension() = runBlocking {
        var checks = 0
        val project = ProjectMemoryCapture(ProjectMemoryIdentity(
            "local_proot", projectRoot = root.absolutePath, profileId = ContainerProfile.BUILTIN_ID
        )) { checks++ }
        val execution = factory.freeze(project)
        assertEquals(2, checks)
        assertNull(WorkspaceExecutionContext.current())
        withContext(execution) {
            assertSame(execution, WorkspaceExecutionContext.current())
            assertEquals(execution.fileCacheIdentity, access.cacheIdentity())
            withContext(Dispatchers.IO) {
                assertSame(execution, WorkspaceExecutionContext.current())
                access.writeFile("~/workspace/probe", "captured")
                assertEquals("captured", access.readFile("~/workspace/probe"))
            }
        }
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun normallyCompletedNestedScopeDoesNotInvalidateCapturedSource() = runBlocking {
        val execution = factory.freeze()
        withContext(Dispatchers.IO + execution) {
            var nestedJob: Job? = null
            coroutineScope {
                nestedJob = currentCoroutineContext()[Job]
                launch { CompletableDeferred(Unit).await() }.join()
            }
            assertTrue(nestedJob!!.isCompleted)
            assertFalse(nestedJob!!.isCancelled)
            execution.checkCurrent()
            assertEquals(execution.fileCacheIdentity, access.cacheIdentity())
            holder.setMode(ExecutionMode.REMOTE_SSH)
            assertTrue(runCatching { execution.checkCurrent() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test
    fun modeSwitchWhileWaitingForPathLockRejectsWriteWithoutCallingNewBackend() = runBlocking {
        val execution = factory.freeze()
        val lock = Mutex(locked = true)
        val waiting = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                withContext(execution) {
                    waiting.complete(Unit)
                    lock.withLock { access.writeFile("~/workspace/probe", "must not write") }
                }
            }.exceptionOrNull()
        }
        waiting.await()
        holder.setMode(ExecutionMode.REMOTE_SSH)
        lock.unlock()
        assertTrue(result.await() is CancellationException)
        assertFalse(File(root, "probe").exists())
        verify(exactly = 0) { remote.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun workspaceRootfsHomeAndProfileChangesAreRejectedBeforeFileOperations() = runBlocking {
        val oldRoot = root
        val oldRootfs = rootfs
        val execution = factory.freeze()
        root = temporary.newFolder("another-workspace")
        assertTrue(runCatching { withContext(execution) { access.mkdirs("~/workspace/probe") } }.exceptionOrNull() is CancellationException)
        assertFalse(File(root, "probe").exists())
        root = oldRoot
        rootfs = temporary.newFolder("another-rootfs")
        assertTrue(runCatching { withContext(execution) { access.writeFile("/etc/probe", "no") } }.exceptionOrNull() is CancellationException)
        assertFalse(File(rootfs, "etc/probe").exists())
        rootfs = oldRootfs
        homePath = "/home/other"
        assertTrue(runCatching { withContext(execution) { access.writeFile("~/probe", "no") } }.exceptionOrNull() is CancellationException)
        homePath = "/root"
        WorkspacePathMapper::class.java.getDeclaredField("currentProfile").apply { isAccessible = true }
            .set(mapper, ContainerProfile.BUILTIN_ALPINE.copy(id = "other-profile"))
        assertTrue(runCatching { withContext(execution) { access.writeFile("/root/probe", "no") } }.exceptionOrNull() is CancellationException)
        assertFalse(File(oldRootfs, "root/probe").exists())
    }

    @Test
    fun oldProjectRootCannotFreezeNewWorkspaceEvenWhenModeAndProfileMatch() = runBlocking {
        val capture = ProjectMemoryCapture(ProjectMemoryIdentity(
            "local_proot", projectRoot = root.absolutePath, profileId = ContainerProfile.BUILTIN_ID
        )) {}
        root = temporary.newFolder("changed-before-freeze")
        assertTrue(runCatching { factory.freeze(capture) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun persistedProfileAndMapperMustAgreeBeforeFreezing() = runBlocking {
        every { settings.activeProfileIdFlow } returns flowOf("selected-but-not-loaded")
        assertTrue(runCatching { factory.freeze() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun localResolutionThatChangesMappingCannotReturnAFileFromTheNewRoot() = runBlocking {
        val execution = factory.freeze()
        val other = temporary.newFolder("new-profile")
        val changingMapper = mockk<WorkspacePathMapper>()
        every { changingMapper.resolveFile("/etc/probe", true) } answers {
            rootfs = other
            File(other, "etc/probe")
        }
        val changingAccess = LocalFileAccess(changingMapper)
        assertTrue(runCatching {
            withContext(execution) { changingAccess.writeFile("/etc/probe", "must not write") }
        }.exceptionOrNull() is CancellationException)
        assertFalse(File(other, "etc/probe").exists())
    }

    @Test
    fun noContextRetainsDynamicBrowsingAndContextDoesNotRemainOnThread() = runBlocking {
        val execution = factory.freeze()
        withContext(execution) { assertEquals(execution.fileCacheIdentity, access.cacheIdentity()) }
        holder.setMode(ExecutionMode.REMOTE_SSH)
        every { remote.readFile("~/workspace/probe") } returns "new browser source"
        assertEquals("new browser source", access.readFile("~/workspace/probe"))
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun syncAndStreamCommandsReceiveCapturedIdentityAndNoNewBackendAfterSuspension() = runBlocking {
        val localEngine = mockk<LinuxContainerEngine>()
        val remoteEngine = mockk<RemoteSshEngine>()
        val engine = DelegatingCommandEngine(holder, localEngine, remoteEngine)
        val execution = factory.freeze()
        coEvery { localEngine.runCommandSync(any(), any(), any()) } answers {
            WorkspaceExecutionContext.current()!!.fileCacheIdentity
        }
        every { localEngine.runCommandStream(any(), any(), any()) } answers {
            flow { emit(CommandEvent.Line(WorkspaceExecutionContext.current()!!.fileCacheIdentity)) }
        }
        withContext(execution) {
            assertEquals(execution.fileCacheIdentity, engine.runCommandSync("identity", root.path, 1_000))
            assertEquals(listOf(CommandEvent.Line(execution.fileCacheIdentity)), engine.runCommandStream("identity", root.path, 1_000).toList())
        }
        val pending = withContext(execution) { engine.runCommandStream("late", root.path, 1_000) }
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertTrue(runCatching { pending.toList() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { withContext(execution) { engine.runCommandSync("late", root.path, 1_000) } }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { remoteEngine.runCommandSync(any(), any(), any()) }
        verify(exactly = 0) { remoteEngine.runCommandStream(any(), any(), any()) }
        coEvery { remoteEngine.runCommandSync(any(), any(), any()) } returns "dynamic remote"
        assertEquals("dynamic remote", engine.runCommandSync("normal", root.path, 1_000))
    }

    @Test
    fun cancellationClosesCapturedReadWithoutRequiringAnotherRead() = runBlocking {
        val execution = factory.freeze()
        val opened = CompletableDeferred<Unit>()
        var closed = 0
        val job = launch(execution) {
            execution.protectRead(object : ByteArrayInputStream(byteArrayOf(1, 2)) {
                override fun close() { closed++ }
            })
            opened.complete(Unit)
            awaitCancellation()
        }
        opened.await()
        job.cancelAndJoin()
        assertEquals(1, closed)
        assertNull(WorkspaceExecutionContext.current())
    }
}
