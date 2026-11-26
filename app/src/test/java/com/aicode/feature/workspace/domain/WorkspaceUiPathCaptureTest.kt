package com.aicode.feature.workspace.domain

import android.net.Uri
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceUiPathCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    private class MemoryGrantStore(initial: List<StoredLocalFolderGrant>) : LocalFolderGrantStore {
        private var records = initial.toList()
        override suspend fun read(): List<StoredLocalFolderGrant> = records.toList()
        override suspend fun write(roots: List<StoredLocalFolderGrant>) { records = roots.toList() }
    }

    private class FolderPlatform(private val directory: File) : LocalFolderGrantPlatform {
        var hasGrant = true
        val permissionChecks = AtomicInteger()
        override fun resolve(uri: Uri): File? = throw AssertionError("No Android picker is used by this fixture")
        override fun resolvePersisted(uri: String): File? = directory.takeIf { uri == GRANT_URI }
        override fun persistReadWrite(uri: Uri): Unit = throw AssertionError("No Android permission is requested by this fixture")
        override fun hasReadWrite(uri: String): Boolean {
            check(uri == GRANT_URI)
            permissionChecks.incrementAndGet()
            return hasGrant
        }
        override fun sourceIdentity(root: File, uri: String): String {
            check(uri == GRANT_URI)
            return fileIdentity(root)
        }
        override fun fileIdentity(root: File): String = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java)
            .fileKey()?.toString() ?: throw IOException("The fixture directory identity is unavailable")
        override fun checkDirectory(root: File, probe: Boolean): LocalFolderUnavailableReason? =
            if (root.isDirectory && root.canRead() && root.canWrite()) null else LocalFolderUnavailableReason.READ_WRITE_REQUIRED
    }

    private class Fixture(val directory: File, workspace: File) : AutoCloseable {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val mode = ExecutionModeHolder()
        val settings = ContainerSettingsRepository(RootMemoryDataStore())
        val owner = RuntimeOwner(RuntimeOwnerKind.FILE, "ui-path-fixture")
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(
            id = "ui-chroot", isBuiltin = false, createdAt = 1,
            localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 1, 2, 3)
        )
        val platform = FolderPlatform(directory)
        private val store = MemoryGrantStore(listOf(StoredLocalFolderGrant(
            "ui-grant", "epoch-one", GRANT_URI, "Phone files", directory.absolutePath, directory.canonicalPath,
            platform.sourceIdentity(directory, GRANT_URI), platform.fileIdentity(directory), available = true
        )))
        val grants = LocalFolderGrantRepository(store, platform, scope)
        val runtime = mockk<LocalRuntimeCommands>()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        val dynamic = mockk<FileAccessProvider>()
        private val workspaceSource = WorkspacePathMapper.WorkspaceSource(workspace.canonicalFile, workspace.absolutePath,
            WorkspaceRepository.Selection(Workspace(workspace.name, workspace.absolutePath)))
        val factory = WorkspaceExecutionContextFactory(dynamic, mode, local, remote, settings, grants, runtime)
        val root: String get() = grants.roots.value.single().rootPath
        val path: String get() = "$root/note.txt"

        init {
            every { local.captureWorkspaceSource() } returns workspaceSource
            every { local.checkWorkspaceSource(workspaceSource) } returns Unit
            coEvery { runtime.capture(profile, any(), workspace.canonicalPath, owner, any(), any()) } coAnswers {
                assertEquals(settings.profileConfigurationRevision, secondArg<Long>())
                assertTrue(arg<String>(4).isNotBlank())
                arg<() -> Unit>(5).invoke()
                throw LocalRuntimeAuthorizationRequired()
            }
        }

        suspend fun prepare() {
            File(directory, "note.txt").writeText("host-only")
            File(directory, "sub").mkdirs()
            File(directory, "sub/second.txt").writeText("second")
            settings.upsertCustomProfile(profile)
            settings.setActiveProfile(profile.id)
            grants.awaitReady()
            assertTrue(grants.roots.value.single().available)
            assertEquals(profile, settings.captureProfileSnapshot().activeProfile)
        }

        fun assertNoRuntime() {
            coVerify(exactly = 0) { runtime.capture(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { runtime.fileAccess(any()) }
            verify(exactly = 0) { local.captureExecutionMapping(any()) }
            verify(exactly = 0) { local.captureWorkspaceSource() }
            verify(exactly = 0) { remote.captureExecutionSource() }
            verify(exactly = 0) { dynamic.cacheIdentity() }
        }

        override fun close() { scope.cancel() }
    }

    private suspend fun fixture(): Fixture {
        val fixture = Fixture(temporary.newFolder(), temporary.newFolder())
        try {
            fixture.prepare()
            return fixture
        } catch (failure: Throwable) {
            fixture.close()
            throw failure
        }
    }

    private suspend fun <T : Throwable> rejects(type: Class<T>, block: suspend () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (!type.isInstance(failure)) throw failure
            return type.cast(failure)
        }
        throw AssertionError("Expected ${type.simpleName}")
    }

    @Test
    fun allGrantedHostPathsReadWithoutAnOwnerOrChrootLeaseAndNeverCaptureRuntime() = runBlocking {
        fixture().use { f ->
            val paths = listOf(f.path, "${f.root}/sub/second.txt")
            val execution = f.factory.freezeForPaths(paths)
            assertEquals(ExecutionMode.LOCAL_PROOT, execution.mode)
            assertTrue(execution.profileSnapshot!!.activeProfile.isChroot)
            assertTrue(execution.fileAccess is InternalLocalFolderAccess)
            assertNull(execution.runtimeOwner)
            assertNull(execution.localRuntimeCapture)
            assertNull(execution.localMapping)
            assertNull(execution.runtimeWorkspaceSource)
            assertNull(execution.remoteSource)
            assertSame(f.grants.snapshot(), execution.localFolderSnapshot)
            execution.useCaptured {
                assertSame(execution, WorkspaceExecutionContext.current())
                assertArrayEquals("host-only".toByteArray(), execution.fileAccess.openRead(paths[0]).use { it.readBytes() })
                assertEquals("second", execution.fileAccess.readFile(paths[1]))
            }
            assertNull(WorkspaceExecutionContext.current())
            f.assertNoRuntime()
        }
    }

    @Test
    fun nonHostAndMixedPathsStillRequireTheChrootOwnerAndAuthorizationBeforeAnyBackend() = runBlocking {
        fixture().use { f ->
            var attemptedCaptures = 0
            for (paths in listOf(listOf("~/workspace/file.txt"), listOf(f.path, "~/workspace/file.txt"))) {
                rejects(LocalRuntimeAuthorizationRequired::class.java) { f.factory.freezeForPaths(paths) }
                coVerify(exactly = attemptedCaptures) { f.runtime.capture(any(), any(), any(), any(), any(), any()) }
                rejects(LocalRuntimeAuthorizationRequired::class.java) {
                    f.factory.freezeForPaths(paths, runtimeOwner = f.owner)
                }
                attemptedCaptures++
                coVerify(exactly = attemptedCaptures) { f.runtime.capture(f.profile, any(), any(), f.owner, any(), any()) }
            }
            assertEquals(2, attemptedCaptures)
            verify(exactly = 0) { f.runtime.fileAccess(any()) }
            verify(exactly = 0) { f.local.captureExecutionMapping(any()) }
            verify(exactly = 0) { f.remote.captureExecutionSource() }
            verify(exactly = 0) { f.dynamic.cacheIdentity() }
        }
    }

    @Test
    fun revokedGrantNewEpochModeAbaAndConfigurationRevisionInvalidateCapturedHostAccess() = runBlocking {
        fixture().use { f ->
            val originalPath = f.path
            val first = f.factory.freezeForPaths(listOf(originalPath))
            f.platform.hasGrant = false
            assertThrows(CancellationException::class.java) {
                first.useCaptured { first.fileAccess.openRead(originalPath) }
            }
            assertFalse(f.grants.roots.value.single().available)
            assertThrows(CancellationException::class.java) { first.checkCurrent() }
            f.platform.hasGrant = true
            f.grants.refresh()
            assertTrue(f.grants.roots.value.single().available)
            assertNotEquals(originalPath, f.path)
            rejects(CancellationException::class.java) { f.factory.freezeForPaths(listOf(originalPath)) }
            val reauthorized = f.factory.freezeForPaths(listOf(f.path))
            assertNotEquals(first.fileCacheIdentity, reauthorized.fileCacheIdentity)
            assertEquals("host-only", reauthorized.useCaptured { reauthorized.fileAccess.readFile(f.path) })
            f.mode.setMode(ExecutionMode.REMOTE_SSH)
            f.mode.setMode(ExecutionMode.LOCAL_PROOT)
            assertThrows(CancellationException::class.java) { reauthorized.checkCurrent() }
            val afterMode = f.factory.freezeForPaths(listOf(f.path))
            val revision = f.settings.profileConfigurationRevision
            f.settings.upsertCustomProfile(f.profile.copy(env = mapOf("EDITOR" to "vim")))
            assertTrue(f.settings.profileConfigurationRevision > revision)
            assertThrows(CancellationException::class.java) { afterMode.checkCurrent() }
            val afterConfiguration = f.factory.freezeForPaths(listOf(f.path))
            assertEquals("host-only", afterConfiguration.useCaptured { afterConfiguration.fileAccess.readFile(f.path) })
            f.assertNoRuntime()
        }
    }

    @Test
    fun malformedOrUnknownGrantPathsAndHostTraversalCannotFallBackToTheRuntimeOrEscape() = runBlocking {
        fixture().use { f ->
            for (path in listOf("/mnt/.aicode-local-fs", "/mnt/.aicode-local-fs/ui-grant", " ${f.path}", "${f.root}/sub//second.txt")) {
                rejects(IOException::class.java) { f.factory.freezeForPaths(listOf(path)) }
            }
            for (path in listOf("${localRootPath("ui-grant", "old-epoch")}/note.txt", "${localRootPath("unknown", "epoch-one")}/note.txt")) {
                rejects(CancellationException::class.java) { f.factory.freezeForPaths(listOf(path)) }
            }
            val outside = temporary.newFolder()
            val secret = File(outside, "private.txt").apply { writeText("outside-grant") }
            Files.createSymbolicLink(File(f.directory, "escape").toPath(), outside.toPath())
            for (path in listOf("${f.root}/../private.txt", "${f.root}/escape/private.txt")) {
                val execution = f.factory.freezeForPaths(listOf(path))
                assertTrue(execution.fileAccess is InternalLocalFolderAccess)
                assertThrows(LocalFolderContainmentException::class.java) {
                    execution.useCaptured { execution.fileAccess.openRead(path) }
                }
            }
            assertEquals("outside-grant", secret.readText())
            f.assertNoRuntime()
        }
    }

    @Test
    fun cancellingTheOriginalOperationClosesReturnedHostStreamBeforeAnyFurtherRead() = runBlocking {
        fixture().use { f ->
            withTimeout(5_000) {
                val returned = CompletableDeferred<Pair<WorkspaceExecutionContext, InputStream>>()
                val operation = launch {
                    val execution = f.factory.freezeForPaths(listOf(f.path))
                    val originalJob = currentCoroutineContext()[Job]
                    val input = execution.useCaptured {
                        assertSame(originalJob, WorkspaceExecutionContext.blockingContext()[Job])
                        execution.fileAccess.openRead(f.path)
                    }
                    returned.complete(execution to input)
                    awaitCancellation()
                }
                var input: InputStream? = null
                try {
                    val (execution, capturedInput) = returned.await()
                    input = capturedInput
                    assertEquals('h'.code, capturedInput.read())
                    val beforeCancel = f.platform.permissionChecks.get()
                    operation.cancelAndJoin()
                    assertTrue(operation.isCancelled)
                    assertEquals(beforeCancel + 1, f.platform.permissionChecks.get())
                    assertThrows(CancellationException::class.java) { execution.checkCurrent() }
                    assertThrows(CancellationException::class.java) { capturedInput.read() }
                    capturedInput.close()
                    capturedInput.close()
                    assertEquals(beforeCancel + 1, f.platform.permissionChecks.get())
                } finally {
                    operation.cancelAndJoin()
                    input?.close()
                }
                assertNull(WorkspaceExecutionContext.current())
                f.assertNoRuntime()
            }
        }
    }

    private companion object {
        const val GRANT_URI = "content://com.android.externalstorage.documents/tree/primary%3AFixture"
    }
}
