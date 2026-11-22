package com.aicode.feature.workspace.domain

import android.app.Application
import android.net.Uri
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LocalFolderGrantRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MemoryStore()
    private val platform = Platform()
    private val repository = LocalFolderGrantRepository(store, platform, scope)

    @After
    fun close() { scope.cancel() }

    private fun uri(value: String = "content://com.android.externalstorage.documents/tree/primary%3ADownload"): Uri = Uri.parse(value)

    private fun root() = temporary.newFolder().also { platform.directory = it }

    @Test
    fun durableReadWriteAndSuccessfulProbeAreRequiredBeforeAvailable() = runBlocking {
        root()
        platform.hasGrant = false
        val missingGrant = repository.add(uri())
        assertFalse(missingGrant.available)
        assertEquals(LocalFolderUnavailableReason.PERSISTENT_PERMISSION_REQUIRED, missingGrant.reason)
        assertEquals(1, platform.persistCalls)
        platform.hasGrant = true
        platform.failure = LocalFolderUnavailableReason.PROBE_FAILED
        val failedProbe = repository.add(uri())
        assertFalse(failedProbe.available)
        assertEquals(LocalFolderUnavailableReason.PROBE_FAILED, failedProbe.reason)
        assertNotEquals(missingGrant.epoch, failedProbe.epoch)
        platform.failure = null
        val available = repository.add(uri())
        assertTrue(available.available)
        assertTrue(available.rootPath.startsWith("/mnt/.aicode-local-fs/"))
        assertFalse(available.rootPath.contains(platform.directory!!.absolutePath))
        assertEquals(available.rootPath, localRootPath(available.id, available.epoch))
        assertEquals(available.label, platform.directory!!.name)
    }

    @Test
    fun restartLoadsOnlyProbedRecordsAndResumeRevocationRetainsUnavailableRoot() = runBlocking {
        root()
        val original = repository.add(uri())
        val restarted = LocalFolderGrantRepository(store, platform, scope)
        restarted.awaitReady()
        assertEquals(original, restarted.roots.value.single())
        val oldSnapshot = restarted.snapshot()
        platform.hasGrant = false
        restarted.refresh()
        assertFalse(restarted.roots.value.single().available)
        assertThrows(CancellationException::class.java) { oldSnapshot.checkCurrent() }
        assertEquals(original.id, restarted.roots.value.single().id)
        assertEquals(original.rootPath, restarted.roots.value.single().rootPath)
    }

    @Test
    fun operationDetectsRevokedPermissionAndReauthorizationNeverReusesReadProofPath(): Unit = runBlocking {
        root()
        val original = repository.add(uri())
        val captured = repository.snapshot()
        val registry = FileVersionRegistry()
        registry.recordRead("session", original.rootPath + "/file", "sha256:old")
        platform.hasGrant = false
        assertThrows(CancellationException::class.java) { captured.rootFor(original.rootPath) }
        assertFalse(repository.roots.value.single().available)
        platform.hasGrant = true
        repository.refresh()
        val reauthorized = repository.roots.value.single()
        assertEquals(original.id, reauthorized.id)
        assertNotEquals(original.epoch, reauthorized.epoch)
        assertFalse(registry.wasReadSinceRecovery("session", reauthorized.rootPath + "/file"))
        assertThrows(CancellationException::class.java) { repository.snapshot().rootFor(original.rootPath) }
    }

    @Test
    fun explicitReauthorizationRotatesEpochEvenWhenProviderAndDirectoryAreUnchanged() = runBlocking {
        root()
        val original = repository.add(uri())
        val snapshot = repository.snapshot()
        val repeated = repository.add(uri())
        assertEquals(original.id, repeated.id)
        assertNotEquals(original.epoch, repeated.epoch)
        assertThrows(CancellationException::class.java) { snapshot.checkCurrent() }
        assertEquals(1, repository.roots.value.size)
    }

    @Test
    fun detachOnlyChangesRegistryAndCannotReattachAnOldIdentity() = runBlocking {
        val file = File(root(), "keep").apply { writeText("keep") }
        val original = repository.add(uri())
        val snapshot = repository.snapshot()
        repository.remove(original.id)
        assertTrue(repository.roots.value.isEmpty())
        assertEquals("keep", file.readText())
        assertTrue(platform.hasGrant)
        assertThrows(CancellationException::class.java) { snapshot.checkCurrent() }
        val added = repository.add(uri())
        assertNotEquals(original.id, added.id)
        assertNotEquals(original.rootPath, added.rootPath)
    }

    @Test
    fun changedCanonicalSourceIsUnavailableInsteadOfSilentlyFollowingNewDirectory() = runBlocking {
        val one = temporary.newFolder("one")
        val two = temporary.newFolder("two")
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), one.toPath())
        platform.directory = alias
        val original = repository.add(uri())
        val snapshot = repository.snapshot()
        Files.delete(alias.toPath())
        Files.createSymbolicLink(alias.toPath(), two.toPath())
        assertThrows(CancellationException::class.java) { snapshot.rootFor(original.rootPath) }
        assertEquals(LocalFolderUnavailableReason.PATH_UNAVAILABLE, repository.roots.value.single().reason)
        assertEquals(original.epoch, repository.roots.value.single().epoch)
    }

    @Test
    fun unknownProviderIsRejectedBeforeTakingPermissionOrChangingRegistry() = runBlocking {
        val unknown = uri("content://example.opaque.documents/tree/opaque")
        val failure = runCatching { repository.add(unknown) }.exceptionOrNull()
        assertTrue(failure is LocalFolderGrantException)
        assertEquals(LocalFolderUnavailableReason.UNSUPPORTED_PROVIDER, (failure as LocalFolderGrantException).reason)
        assertEquals(0, platform.persistCalls)
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun replacedDirectoryAtTheSameCanonicalPathRequiresNewAuthorizationIdentity() = runBlocking {
        val originalDirectory = root()
        val original = repository.add(uri())
        val captured = repository.snapshot()
        val replacement = File(originalDirectory.parentFile, originalDirectory.name + "-old")
        Files.move(originalDirectory.toPath(), replacement.toPath())
        originalDirectory.mkdir()
        assertThrows(CancellationException::class.java) { captured.rootFor(original.rootPath, verifyAuthorization = false) }
        assertFalse(repository.roots.value.single().available)
        repository.refresh()
        assertFalse(repository.roots.value.single().available)
        val reauthorized = repository.add(uri())
        assertTrue(reauthorized.available)
        assertNotEquals(original.epoch, reauthorized.epoch)
    }

    @Test
    fun systemGrantAbaCannotReuseOldEpochEvenWhenPermissionFlagsAndDirectoryMatch() = runBlocking {
        root()
        val original = repository.add(uri())
        val captured = repository.snapshot()
        platform.sourceGeneration++
        assertThrows(CancellationException::class.java) { captured.rootFor(original.rootPath) }
        assertFalse(repository.roots.value.single().available)
        repository.refresh()
        assertFalse(repository.roots.value.single().available)
        val reauthorized = repository.add(uri())
        assertTrue(reauthorized.available)
        assertNotEquals(original.rootPath, reauthorized.rootPath)
    }

    @Test
    fun childChangesDoNotInvalidateStableDirectorySourceIdentity() = runBlocking {
        val directory = root()
        repository.add(uri())
        val captured = repository.snapshot()
        val child = File(directory, "child").apply { writeText("new") }
        captured.rootFor(captured.roots.single().rootPath)
        child.delete()
        captured.rootFor(captured.roots.single().rootPath)
        assertEquals(captured.revision, repository.revision.value)
    }

    @Test
    fun snapshotRevisionAndDtoListComeFromOneImmutableAtomicValue() = runBlocking {
        root()
        repository.add(uri())
        val first = repository.snapshot()
        assertEquals(first.revision, repository.revision.value)
        assertEquals(first.roots, repository.roots.value)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (first.roots as MutableList<LocalFolderGrant>).clear()
        }
        repository.remove(first.roots.single().id)
        assertEquals(1, first.roots.size)
        assertTrue(repository.snapshot().revision > first.revision)
    }

    @Test
    fun snapshotRevisionChecksAndRemoteRouteChecksDoNotQueryAnyUnrelatedLocalGrant() = runBlocking {
        root()
        repository.add(uri())
        root()
        val secondUri = "content://com.android.externalstorage.documents/tree/primary%3ASecond"
        val second = repository.add(uri(secondUri))
        val captured = repository.snapshot()
        platform.permissionChecks.clear()
        repeat(100) { captured.checkCurrent() }
        val remote = mockk<FileAccessProvider>()
        val execution = WorkspaceExecutionContext(
            com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH, "remote", remote, null, null, captured
        ) {}
        val route = FileBackendRouting().capture(execution, listOf("~/workspace/file"))
        repeat(100) { route.checkCurrent() }
        assertTrue(platform.permissionChecks.isEmpty())
        captured.rootFor(second.rootPath)
        assertEquals(listOf(secondUri), platform.permissionChecks)
        platform.permissionChecks.clear()
        repeat(100) { captured.rootFor(second.rootPath, verifyAuthorization = false) }
        assertTrue(platform.permissionChecks.isEmpty())
    }

    @Test
    fun hostStreamChunksUseStableSourceChecksAndRecheckSystemGrantOnClose() = runBlocking {
        val directory = root()
        val grant = repository.add(uri())
        File(directory, "large").writeBytes(ByteArray(32_000))
        val access = InternalLocalFolderAccess(repository.snapshot())
        val input = access.openRead(grant.rootPath + "/large")
        platform.permissionChecks.clear()
        val buffer = ByteArray(320)
        repeat(100) { assertEquals(buffer.size, input.read(buffer)) }
        assertTrue(platform.permissionChecks.isEmpty())
        input.close()
        assertEquals(1, platform.permissionChecks.size)
    }

    @Test
    fun pathSourceSeparatesChunkChecksFromCacheHitAuthorizationChecks(): Unit = runBlocking {
        root()
        val grant = repository.add(uri())
        val holder = com.aicode.feature.settings.data.repository.ExecutionModeHolder().apply {
            setMode(com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        }
        val remote = mockk<RemoteSftpFileAccess> { every { cacheIdentity() } returns "remote" }
        val access = DelegatingFileAccess(holder, mockk(), remote, repository)
        val source = access.capturePathSource(grant.rootPath + "/file")
        platform.permissionChecks.clear()
        repeat(100) { source.checkCurrent() }
        assertTrue(platform.permissionChecks.isEmpty())
        source.checkAuthorization()
        assertEquals(1, platform.permissionChecks.size)
        platform.hasGrant = false
        assertThrows(CancellationException::class.java) { source.checkAuthorization() }
    }

    @Test
    fun rootsAreUnavailableWhenReadWriteChecksFailOnStartup() = runBlocking {
        root()
        repository.add(uri())
        platform.failure = LocalFolderUnavailableReason.READ_WRITE_REQUIRED
        val restarted = LocalFolderGrantRepository(store, platform, scope)
        restarted.awaitReady()
        assertFalse(restarted.roots.value.single().available)
        assertEquals(LocalFolderUnavailableReason.READ_WRITE_REQUIRED, restarted.roots.value.single().reason)
    }

    @Test
    fun invalidationWriteFailurePreservesMemoryFenceAndRefreshRetriesPersistence(): Unit = runBlocking {
        val failures = mutableListOf<Throwable>()
        val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure ->
            failures.add(failure)
        })
        try {
            root()
            val isolated = LocalFolderGrantRepository(store, platform, persistenceScope)
            val original = isolated.add(uri())
            val captured = isolated.snapshot()
            val previousAttempts = store.writeAttempts
            store.writeFailure = IOException("Storage unavailable")
            platform.hasGrant = false
            assertThrows(CancellationException::class.java) { captured.rootFor(original.rootPath) }
            persistenceScope.coroutineContext[Job]!!.children.toList().joinAll()
            assertTrue(failures.isEmpty())
            assertFalse(isolated.roots.value.single().available)
            assertTrue(isolated.revision.value > captured.revision)
            assertThrows(CancellationException::class.java) { captured.checkCurrent() }
            assertTrue(store.records.single().available)
            assertEquals(previousAttempts + 1, store.writeAttempts)
            store.writeFailure = null
            isolated.refresh()
            assertEquals(previousAttempts + 2, store.writeAttempts)
            assertFalse(store.records.single().available)
            assertEquals(LocalFolderUnavailableReason.PERSISTENT_PERMISSION_REQUIRED, store.records.single().reason)
            assertFalse(isolated.roots.value.single().available)
            assertThrows(CancellationException::class.java) { captured.checkCurrent() }
            assertTrue(failures.isEmpty())
        } finally {
            persistenceScope.cancel()
        }
    }

    private class MemoryStore : LocalFolderGrantStore {
        var records = emptyList<StoredLocalFolderGrant>()
        var writeFailure: IOException? = null
        var writeAttempts = 0
        override suspend fun read(): List<StoredLocalFolderGrant> = records.toList()
        override suspend fun write(roots: List<StoredLocalFolderGrant>) {
            writeAttempts++
            writeFailure?.let { throw it }
            records = roots.toList()
        }
    }

    private class Platform : LocalFolderGrantPlatform {
        var directory: File? = null
        var hasGrant = true
        var persistCalls = 0
        var sourceGeneration = 0L
        val permissionChecks = mutableListOf<String>()
        var failure: LocalFolderUnavailableReason? = null
        override fun resolve(uri: Uri): File? = directory
        override fun resolvePersisted(uri: String): File? = directory
        override fun persistReadWrite(uri: Uri) { persistCalls++ }
        override fun hasReadWrite(uri: String): Boolean { permissionChecks.add(uri); return hasGrant }
        override fun sourceIdentity(root: File, uri: String): String = "$sourceGeneration:" + fileIdentity(root)
        override fun fileIdentity(root: File): String =
            Files.readAttributes(root.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()
        override fun checkDirectory(root: File, probe: Boolean): LocalFolderUnavailableReason? = failure
    }
}
