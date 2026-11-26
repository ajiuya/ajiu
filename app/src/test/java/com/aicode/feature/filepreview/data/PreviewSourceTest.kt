package com.aicode.feature.filepreview.data

import android.content.Context
import com.aicode.feature.filepreview.domain.PreviewLimits
import com.aicode.feature.filepreview.domain.PreviewRequest
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.RootRead
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.WorkspaceRootRead
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PreviewSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()
    private val workspace = mockk<WorkspaceRepository>()
    private val executionFactory = mockk<WorkspaceExecutionContextFactory>()
    private val mapper = mockk<WorkspacePathMapper>()
    private val remote = mockk<RemoteSftpFileAccess>()
    private val currentWorkspace = AtomicReference("initial")
    private val environment = AtomicReference("initial")
    private val sourceOwner = Job()
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var factory: PreviewSourceFactory
    private lateinit var execution: WorkspaceExecutionContext

    @Before
    fun prepare() {
        root = temporary.newFolder("workspace")
        cache = temporary.newFolder("cache")
        File(root, "site").mkdir()
        File(root, "site/index.html").writeText("<h1>entry</h1>")
        File(root, "site/style.css").writeText("body {}")
        every { context.cacheDir } returns cache
        every { workspace.currentPath() } answers { currentWorkspace.get() }
        every { workspace.selection } returns kotlinx.coroutines.flow.MutableStateFlow(WorkspaceRepository.Selection(null))
        every { mapper.resolveFile(any(), any()) } answers {
            val path = firstArg<String>()
            if (path == "~/workspace") root else {
                if (!path.startsWith("~/workspace/")) throw IOException("Unexpected logical path")
                File(root, path.removePrefix("~/workspace/"))
            }
        }
        execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "frozen", mockk<FileAccessProvider>(), null, null) {
            if (environment.get() != "initial") throw CancellationException("Execution changed")
        }
        coEvery { executionFactory.freezeForPaths(any(), any(), any(), any()) } returns execution
        factory = PreviewSourceFactory(context, workspace, executionFactory, WorkspaceRootRead(mapper, remote))
    }

    @After
    fun finish() {
        factory.close()
        sourceOwner.cancel()
    }

    private suspend fun PreviewSourceFactory.open(request: PreviewRequest): PreviewSource = open(request, sourceOwner)

    @Test
    fun authorizationRetryUsesTheSameFileOwnerAndNeverReadsAnUnauthorizedBackend(): Unit = runBlocking {
        val owners = mutableListOf<com.aicode.feature.agent.domain.container.runtime.RuntimeOwner>()
        val checks = mutableListOf<() -> Unit>()
        coEvery { executionFactory.freezeForPaths(any(), any(), any(), any()) } answers {
            owners += requireNotNull(arg<com.aicode.feature.agent.domain.container.runtime.RuntimeOwner?>(2))
            checks += requireNotNull(arg<(() -> Unit)?>(3))
            if (owners.size == 1) throw com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired()
            execution
        }
        assertTrue(runCatching { factory.open(PreviewRequest("site/index.html")) }.exceptionOrNull() is IOException)
        verify(exactly = 0) { mapper.resolveFile(any(), any()) }
        factory.open(PreviewRequest("site/index.html")).use {
            assertEquals(owners.first(), owners.last())
            assertEquals(com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.FILE, owners.first().kind)
            checks.first().invoke()
        }
        factory.close()
        assertThrows(CancellationException::class.java) { checks.first().invoke() }
    }

    @Test
    fun completedOpeningDoesNotOwnTheLeaseButTheVmScopeStillCancelsIt(): Unit = runBlocking {
        var check: (() -> Unit)? = null
        coEvery { executionFactory.freezeForPaths(any(), any(), any(), any()) } answers {
            check = arg(3)
            execution
        }
        val source = async { factory.open(PreviewRequest("site/index.html")) }.await()
        try {
            requireNotNull(check).invoke()
            sourceOwner.cancel()
            assertThrows(CancellationException::class.java) { requireNotNull(check).invoke() }
            assertThrows(CancellationException::class.java) { source.openRead() }
        } finally { source.close() }
    }

    @Test
    fun equalWorkspacePathWithAnotherSelectionEpochRevokesTheOldPreview() = runBlocking {
        val selection = kotlinx.coroutines.flow.MutableStateFlow(WorkspaceRepository.Selection(null))
        every { workspace.selection } returns selection
        val source = factory.open(PreviewRequest("site/index.html"))
        try {
            selection.value = WorkspaceRepository.Selection(null)
            selection.value = WorkspaceRepository.Selection(null)
            assertThrows(CancellationException::class.java) { source.checkCurrent() }
            assertFalse(source.valid.value)
        } finally { source.close() }
    }

    @Test
    fun relativeEntryUsesNormalizedLogicalPathAndItsOriginalParent() = runBlocking {
        factory.open(PreviewRequest("site/./index.html")).use { source ->
            assertEquals("~/workspace/site/index.html", source.entryPath)
            assertEquals(source.entryPath, source.displayPath)
            assertTrue(source.relatedFilesAvailable)
            assertEquals(File(root, "site/index.html").length(), source.size)
            assertEquals("<h1>entry</h1>", source.openRead().bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun resourceResolutionDecodesOnceWithoutTreatingPlusAsSpace() = runBlocking {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            assertEquals("~/workspace/site/a b+c.css", source.resolve("a%20b+c.css"))
            assertEquals("~/workspace/site/assets/image.png", source.resolve("./assets/image.png"))
            assertEquals("~/workspace/site/style.css", source.resolve("../style.css", "~/workspace/site/assets/main.css"))
        }
    }

    @Test
    fun anchorsArePreservedAndReadAsTheSameFile() = runBlocking {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            assertEquals(source.entryPath + "#heading", source.resolve("#heading"))
            assertEquals(source.entryPath + "#heading", source.resolve("index.html#heading"))
            assertEquals(source.size, source.openRead(source.entryPath + "#heading").use { it.readBytes().size.toLong() })
        }
    }

    @Test
    fun schemesAbsoluteRootsAndLexicalEscapesAreRejectedBeforeOpening() = runBlocking {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            listOf("../outside", "a/../../outside", "/etc/passwd", "/", "~/", "~", "//server/a", "http://host/a", "javascript:alert(1)", "file:///etc/passwd").forEach {
                assertThrows(IOException::class.java) { source.resolve(it) }
            }
        }
    }

    @Test
    fun encodedTraversalDoubleEncodingAndMalformedEncodingAreRejected() = runBlocking {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            listOf("%2e%2e/outside", "%252e%252e/outside", "%2fetc/passwd", "bad%", "a%5Cb", "a%00b", "a%3fb", "a%23b").forEach {
                assertThrows(IOException::class.java) { source.resolve(it) }
            }
        }
    }

    @Test
    fun callerCannotSupplyAnEscapingOrAmbiguousBasePath() = runBlocking {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            listOf("~/workspace/other/main.css", "~/workspace/site/../main.css", "~/workspace/site/%2e%2e/main.css").forEach {
                assertThrows(IOException::class.java) { source.resolve("next.css", it) }
            }
        }
    }

    @Test
    fun missingDirectoryAndInvalidEntriesFailExplicitly() = runBlocking {
        listOf("site/missing", "site", "", "/", "~/", "http://host/file").forEach { path ->
            assertTrue(runCatching { factory.open(PreviewRequest(path)) }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun reportedEntrySizeOverTheFileLimitIsRejected() = runBlocking {
        RandomAccessFile(File(root, "site/large.bin"), "rw").use { it.setLength(PreviewLimits.FILE_BYTES + 1) }
        assertTrue(runCatching { factory.open(PreviewRequest("site/large.bin")) }.exceptionOrNull() is IOException)
    }

    @Test
    fun localEntryReturnsOnlyACanonicallyCheckedReadOnlySourceReference() = runBlocking {
        val entry = File(root, "site/index.html").canonicalFile
        val source = factory.open(PreviewRequest("site/index.html"))
        assertEquals(entry, source.localFile())
        source.close()
        assertTrue(entry.isFile)
        assertEquals("<h1>entry</h1>", entry.readText())
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun recordedLocalPathMatchesTheFrozenCanonicalEntryWithoutBecomingTheSource() = runBlocking {
        val entry = File(root, "site/index.html")
        val recorded = File(entry.parentFile, "./index.html")
        factory.open(PreviewRequest("site/index.html", recordedLocalPath = recorded.path)).use { source ->
            assertTrue(source.relatedFilesAvailable)
            assertEquals("~/workspace/site/index.html", source.entryPath)
            assertEquals(entry.canonicalFile, source.localFile())
            assertEquals("<h1>entry</h1>", source.openRead().bufferedReader().use { it.readText() })
        }
        coVerify(exactly = 1) { executionFactory.freezeForPaths(any(), any(), any(), any()) }
    }

    @Test
    fun recordedLocalPathFromAnotherRootCannotReadTheCurrentSameNamedFile() = runBlocking {
        val recorded = File(temporary.newFolder("old-profile"), "index.html").apply { writeText("old private contents") }
        val reader = mockk<WorkspaceRootRead>()
        val read = mockk<RootRead>()
        every { reader.capture("~/workspace/site", execution, any()) } returns read
        every { read.localFile("~/workspace/site/index.html") } returns File(root, "site/index.html")
        val checkedFactory = PreviewSourceFactory(context, workspace, executionFactory, reader)
        val failure = runCatching {
            checkedFactory.open(PreviewRequest("site/index.html", recordedLocalPath = recorded.path))
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Recorded local file does not match the current preview source", failure?.message)
        verify(exactly = 1) { read.localFile("~/workspace/site/index.html") }
        verify(exactly = 0) { read.size(any()) }
        verify(exactly = 0) { read.openRead(any(), any()) }
        verify(exactly = 0) { remote.openRead(any<String>()) }
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun recordedLocalPathCannotAuthorizeAnIncomparableRemoteSourceOrFallback() = runBlocking {
        val recorded = File(root, "site/index.html")
        val remoteExecution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "remote", remote, null, null) {}
        coEvery { executionFactory.freezeForPaths(any(), any(), any(), any()) } returns remoteExecution
        val reader = mockk<WorkspaceRootRead>()
        val read = mockk<RootRead>()
        every { reader.capture("~/workspace/site", remoteExecution, any()) } returns read
        every { read.localFile("~/workspace/site/index.html") } returns null
        val checkedFactory = PreviewSourceFactory(context, workspace, executionFactory, reader)
        val failure = runCatching {
            checkedFactory.open(PreviewRequest("site/index.html", recordedLocalPath = recorded.path))
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals("Recorded local file does not match the current preview source", failure?.message)
        coVerify(exactly = 1) { executionFactory.freezeForPaths(any(), any(), any(), any()) }
        verify(exactly = 1) { read.localFile("~/workspace/site/index.html") }
        verify(exactly = 0) { read.size(any()) }
        verify(exactly = 0) { read.openRead(any(), any()) }
        verify(exactly = 0) { remote.openRootRead(any(), any(), any()) }
        verify(exactly = 0) { remote.openRead(any<String>()) }
    }

    @Test
    fun cachedAttachmentPreservesTheOriginalLogicalPathWithoutFreezingCurrentBackend() = runBlocking {
        val cached = File(cache, "download.copy").apply { writeText("snapshot") }
        factory.open(PreviewRequest("~/workspace/report.md", cachedPath = cached.path)).use { source ->
            assertEquals("~/workspace/report.md", source.entryPath)
            assertEquals(source.entryPath, source.displayPath)
            assertFalse(source.relatedFilesAvailable)
            assertEquals("snapshot", source.openRead().bufferedReader().use { it.readText() })
            assertEquals(cached.canonicalFile, source.localFile())
        }
        coVerify(exactly = 0) { executionFactory.freezeForPaths(any(), any(), any(), any()) }
        verify(exactly = 0) { workspace.currentPath() }
    }

    @Test
    fun cachedAttachmentCannotReadOtherSharedCacheFilesOrLogicalSiblings() = runBlocking {
        val cached = File(cache, "one.md").apply { writeText("one") }
        File(cache, "secret.txt").writeText("private")
        factory.open(PreviewRequest("site/report.md", cachedPath = cached.path)).use { source ->
            assertThrows(IOException::class.java) { source.resolve("secret.txt") }
            assertThrows(IOException::class.java) { source.openRead("~/workspace/site/secret.txt") }
            assertTrue(runCatching { source.localFile("secret.txt") }.exceptionOrNull() is IOException)
        }
    }

    @Test
    fun invalidCachedPathNeverFallsBackToTheCurrentWorkspace() = runBlocking {
        listOf(File(cache, "missing"), File(root, "site/index.html"), cache).forEach { file ->
            assertTrue(runCatching { factory.open(PreviewRequest("site/index.html", cachedPath = file.path)) }.exceptionOrNull() is IOException)
        }
        coVerify(exactly = 0) { executionFactory.freezeForPaths(any(), any(), any(), any()) }
    }

    @Test
    fun cachedFinalSymlinkIsNotAcceptedAsAnExistingSnapshotFile() = runBlocking {
        val real = File(cache, "real.md").apply { writeText("real") }
        val link = File(cache, "link.md")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        assertTrue(runCatching { factory.open(PreviewRequest("site/file.md", cachedPath = link.path)) }.exceptionOrNull() is IOException)
    }

    @Test
    fun cachedSnapshotIsIndependentOfLaterWorkspaceAndExecutionChanges() = runBlocking {
        val cached = File(cache, "saved.md").apply { writeText("snapshot") }
        factory.open(PreviewRequest("site/saved.md", cachedPath = cached.path)).use { source ->
            currentWorkspace.set("another")
            environment.set("another")
            source.checkCurrent()
            assertTrue(source.valid.value)
            assertEquals("snapshot", source.openRead().bufferedReader().use { it.readText() })
        }
        assertTrue(cached.isFile)
    }

    @Test
    fun missingSnapshotInvalidatesTheSessionWithoutRetryingAnotherBackend() = runBlocking {
        val cached = File(cache, "gone.md").apply { writeText("snapshot") }
        factory.open(PreviewRequest("site/gone.md", cachedPath = cached.path)).use { source ->
            assertTrue(cached.delete())
            assertThrows(IOException::class.java) { source.checkCurrent() }
            assertFalse(source.valid.value)
        }
        coVerify(exactly = 0) { executionFactory.freezeForPaths(any(), any(), any(), any()) }
    }

    @Test
    fun workspaceChangeInvalidatesAnIdleOpenPreviewWithoutAnotherRead() = runBlocking<Unit> {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            currentWorkspace.set("different")
            withTimeout(5_000) { source.valid.first { !it } }
            assertThrows(CancellationException::class.java) { source.checkCurrent() }
        }
    }

    @Test
    fun mappingChangeCannotReadFromTheNewBackendAndFlipsValidity() = runBlocking {
        factory.open(PreviewRequest("site/index.html")).use { source ->
            val input = source.openRead()
            environment.set("different")
            assertThrows(CancellationException::class.java) { input.read() }
            assertFalse(source.valid.value)
            verify(exactly = 0) { remote.openRead(any<String>()) }
        }
    }

    @Test
    fun closeClosesAllReturnedStreamsEvenAfterTheirOpeningCoroutineCompleted() = runBlocking<Unit> {
        val closes = AtomicInteger()
        val rootRead = copyRoot { object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun close() { closes.incrementAndGet() }
        } }
        val source = detached(rootRead)
        val first = source.openRead()
        val second = source.openRead()
        source.close()
        source.close()
        awaitCleanup { closes.get() == 2 }
        assertEquals(2, closes.get())
        assertFalse(source.valid.value)
        assertThrows(IOException::class.java) { first.read() }
        assertThrows(IOException::class.java) { second.read() }
        assertThrows(CancellationException::class.java) { source.openRead() }
    }

    @Test
    fun growingStreamCannotReturnABytePastTheFileLimit() {
        val supplied = AtomicInteger()
        val rootRead = copyRoot { object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                supplied.addAndGet(len)
                return len
            }
        } }
        detached(rootRead).use { source ->
            val input = source.openRead()
            val buffer = ByteArray(1024 * 1024)
            var received = 0L
            assertThrows(IOException::class.java) {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                }
            }
            assertEquals(PreviewLimits.FILE_BYTES, received)
            assertEquals(PreviewLimits.FILE_BYTES + 1, supplied.get().toLong())
        }
    }

    @Test
    fun ordinaryReadIsNotArtificiallyLimitedToTheResourceBudget() {
        val bytes = ByteArray(PreviewLimits.RESOURCE_BYTES + 1)
        detached(copyRoot { bytes.inputStream() }).use { source ->
            val total = source.openRead().use { input ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    count += length
                }
                count
            }
            assertEquals(bytes.size.toLong(), total)
        }
    }

    @Test
    fun remoteCopyIsPrivateReusedWithinTheSessionAndRemovedAtClose() = runBlocking {
        val bytes = "remote contents".toByteArray()
        val rootRead = copyRoot { bytes.inputStream() }
        every { rootRead.size("~/workspace/site/file.bin") } returns bytes.size.toLong()
        val source = detached(rootRead, bytes.size.toLong())
        val copy = source.localFile()
        assertEquals("remote contents", copy.readText())
        assertTrue(copy.toPath().startsWith(cache.toPath()))
        assertSame(copy, source.localFile())
        verify(exactly = 1) { rootRead.openRead(any(), any()) }
        source.close()
        awaitCleanup { !copy.exists() && cache.listFiles().isNullOrEmpty() }
        assertFalse(copy.exists())
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun totalSessionCopyBudgetRejectsAnotherCopyBeforeOpeningItsStream() = runBlocking {
        val bytes = "one".toByteArray()
        val rootRead = copyRoot { bytes.inputStream() }
        every { rootRead.size("~/workspace/site/file.bin") } returns bytes.size.toLong()
        every { rootRead.size("~/workspace/site/second.bin") } returns PreviewLimits.FILE_BYTES
        detached(rootRead, bytes.size.toLong()).use { source ->
            source.localFile()
            assertTrue(runCatching { source.localFile("second.bin") }.exceptionOrNull() is IOException)
            verify(exactly = 0) { rootRead.openRead("~/workspace/site/second.bin", any()) }
        }
    }

    @Test
    fun failedCopyDoesNotLeaveAPartialFileAndRetainsItsIOException() = runBlocking {
        val failure = IOException("remote read failed")
        val rootRead = copyRoot { object : InputStream() { override fun read(): Int = throw failure } }
        detached(rootRead).use { source ->
            val reported = runCatching { source.localFile() }.exceptionOrNull()
            assertTrue(reported is IOException)
            assertEquals(failure.message, reported?.message)
            assertTrue(generateSequence(reported) { it.cause }.any { it === failure })
            assertTrue(cache.walkTopDown().none { it.isFile })
        }
        awaitCleanup { cache.listFiles().isNullOrEmpty() }
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun earlyEndOfFileDoesNotReturnAPartialCopy() = runBlocking {
        val rootRead = copyRoot { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
        detached(rootRead).use { source ->
            val failure = runCatching { source.localFile() }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("ended before its reported size"))
            assertTrue(cache.walkTopDown().none { it.isFile })
        }
        awaitCleanup { cache.listFiles().isNullOrEmpty() }
    }

    @Test
    fun cancelledCopyCleansOnlyItsOwnResourcesAndKeepsTheSessionUsable() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val attempts = AtomicInteger()
        val rootRead = copyRoot { "other file".byteInputStream() }
        every { rootRead.size("~/workspace/site/existing.bin") } returns "other file".toByteArray().size.toLong()
        every { rootRead.size("~/workspace/site/file.bin") } returns "retry contents".toByteArray().size.toLong()
        every { rootRead.openRead("~/workspace/site/file.bin", any()) } answers {
            if (attempts.incrementAndGet() == 1) object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    assertTrue(released.await(5, TimeUnit.SECONDS))
                    throw IOException("closed")
                }
                override fun close() { released.countDown() }
            } else "retry contents".byteInputStream()
        }
        val source = detached(rootRead)
        try {
            val existing = source.localFile("existing.bin")
            val otherInput = source.openRead("other.bin")
            val copy = async(kotlinx.coroutines.Dispatchers.IO) { source.localFile() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            copy.cancelAndJoin()
            assertTrue(copy.isCancelled)
            assertTrue(runCatching { copy.await() }.exceptionOrNull() is CancellationException)
            assertTrue(source.valid.value)
            source.checkCurrent()
            assertEquals(listOf(existing), cache.walkTopDown().filter { it.isFile }.toList())
            assertEquals("other file", existing.readText())
            assertEquals("other file", otherInput.bufferedReader().use { it.readText() })
            assertSame(existing, source.localFile("existing.bin"))
            val retried = source.localFile()
            assertEquals("retry contents", retried.readText())
            assertEquals(2, attempts.get())
            assertTrue(source.valid.value)
        } finally {
            source.close()
            released.countDown()
            awaitCleanup { cache.listFiles().isNullOrEmpty() }
        }
    }

    @Test
    fun closeWakesABlockedSynchronousReadOnAnotherThread() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val rootRead = copyRoot { object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                assertTrue(released.await(5, TimeUnit.SECONDS))
                throw IOException("closed")
            }
            override fun close() { released.countDown() }
        } }
        val source = detached(rootRead)
        val input = source.openRead()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit<Throwable?> { runCatching { input.read() }.exceptionOrNull() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            source.close()
            assertTrue(task.get(5, TimeUnit.SECONDS) is Exception)
            assertFalse(source.valid.value)
        } finally {
            source.close()
            released.countDown()
            executor.shutdownNow()
        }
    }

    private suspend fun awaitCleanup(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(10) }
    }

    private fun copyRoot(input: () -> InputStream): RootRead = mockk<RootRead>().also { rootRead ->
        every { rootRead.checkCurrent() } returns Unit
        every { rootRead.forOperation(any()) } returns rootRead
        every { rootRead.localFile(any()) } returns null
        every { rootRead.size(any()) } returns 16L
        every { rootRead.openRead(any(), any()) } answers { input() }
    }

    private fun detached(rootRead: RootRead, size: Long = 16): PreviewSource = PreviewSource(
        PreviewPaths.entry("~/workspace/site/file.bin"), size, Job(), null, rootRead, null, cache,
    ) {}
}
