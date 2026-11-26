package com.aicode.feature.workspace.domain.chroot

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.ChrootUploadPlan
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.FileTransferService
import com.aicode.feature.workspace.domain.FileUploadInput
import com.aicode.feature.workspace.domain.FileUploadInputs
import com.aicode.feature.workspace.domain.HostTransferBackend
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.TRANSFER_BUFFER_BYTES
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferOperation
import com.aicode.feature.workspace.domain.TransferReader
import com.aicode.feature.workspace.domain.TransferStat
import com.aicode.feature.workspace.domain.TransferResult
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.localRootPath
import com.aicode.feature.workspace.domain.transferVersion
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChrootTransferBackendTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun host(file: File): HostTransferBackend = HostTransferBackend({ relative, _ ->
        if (relative.isEmpty()) file else File(file, relative)
    }) {}

    @Test fun streamedHostFileAndDirectoryCopyUseVerifiedChrootStages() = runBlocking {
        val fixture = Fixture()
        val tree = File(fixture.host, "tree").apply { mkdir() }
        File(tree, "empty").createNewFile()
        File(tree, "nested").mkdir()
        val data = ByteArray(TRANSFER_BUFFER_BYTES * 3 + 7) { (it % 127).toByte() }
        File(tree, "nested/data").writeBytes(data)
        val result = fixture.service.transfer(fixture.execution, fixture.hostPath("tree"), "~/workspace/copied", false) {}
        assertTrue(result is TransferResult.Completed)
        assertTrue(File(fixture.workspace, "copied/empty").isFile)
        assertTrue(data.contentEquals(File(fixture.workspace, "copied/nested/data").readBytes()))
        assertTrue(fixture.writeRequests.isNotEmpty() && fixture.writeRequests.all { it <= TRANSFER_BUFFER_BYTES })
        fixture.assertNoStages()
        assertEquals(fixture.sessions, fixture.closedSessions)
        assertEquals(fixture.sessions, fixture.cleanupChecks)
        assertTrue(tree.exists())
    }

    @Test fun chrootSourceCutRemovesOnlyAfterPublishedHostContentMatches() = runBlocking {
        val fixture = Fixture()
        File(fixture.workspace, "source").writeText("verified bytes")
        val result = fixture.service.transfer(fixture.execution, "~/workspace/source", fixture.hostPath("copy"), true) {}
        assertTrue(result is TransferResult.Completed)
        assertEquals("verified bytes", File(fixture.host, "copy").readText())
        assertFalse(File(fixture.workspace, "source").exists())
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun ordinaryChrootDirectoryCutVerifiesEveryPublishedFileBeforeRemovingTheTree() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.workspace, "tree").apply { mkdir() }
        File(source, "nested").mkdir()
        File(source, "nested/file").writeText("content")
        File(source, "empty").createNewFile()
        val result = fixture.service.transfer(fixture.execution, "~/workspace/tree", fixture.hostPath("copy"), true) {}
        assertTrue(result is TransferResult.Completed)
        assertEquals("content", File(fixture.host, "copy/nested/file").readText())
        assertTrue(File(fixture.host, "copy/empty").isFile)
        assertFalse(source.exists())
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun streamedUploadUsesTheServiceChrootBackendWithoutWholeFileBuffers() = runBlocking {
        val fixture = Fixture()
        val uri = mockk<android.net.Uri>()
        val bytes = ByteArray(TRANSFER_BUFFER_BYTES + 19) { (it % 53).toByte() }
        val source = File(fixture.host, "selected").apply { writeBytes(bytes) }
        every { fixture.uploads.files(listOf(uri), any()) } returns listOf(
            FileUploadInput("content://fixture/file", "upload", host(source))
        )
        val results = fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}
        assertTrue(results.single() is TransferResult.Completed)
        assertTrue(bytes.contentEquals(File(fixture.workspace, "upload").readBytes()))
        assertTrue(fixture.writeRequests.all { it <= TRANSFER_BUFFER_BYTES })
        fixture.assertNoStages()
    }

    @Test fun uploadFilesDoesNotReportCompletedWhenOnlyDirectoryGuardCleanupIsUnknown() = runBlocking {
        val fixture = Fixture()
        val uri = mockk<android.net.Uri>()
        val bytes = ByteArray(TRANSFER_BUFFER_BYTES * 4 + 7) { (it % 59).toByte() }
        val source = File(fixture.host, "selected").apply { writeBytes(bytes) }
        fixture.selectFile(uri, source)
        fixture.cleanupFailureSession = 1
        val results = fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}
        assertTrue(bytes.contentEquals(File(fixture.workspace, "upload").readBytes()))
        assertTrue(source.exists())
        fixture.assertNoStages()
        assertEquals(fixture.sessions, fixture.closedSessions)
        assertEquals(fixture.sessions, fixture.cleanupChecks)
        assertEquals(listOf(1), fixture.failedCleanupSessions)
        assertTrue("Unverified directory cleanup must not deliver success or a ticket: $results",
            results.none { it is TransferResult.Completed || it is TransferResult.Conflict })
        val failure = results.single() as TransferResult.Failed
        assertTrue(failure.reason.contains("cleanup", ignoreCase = true))
        assertTrue(failure.reason.contains("committed", ignoreCase = true))
    }

    @Test fun uploadFilesDoesNotDeliverConflictTicketWhenOnlyDirectoryGuardCleanupIsUnknown() = runBlocking {
        val fixture = Fixture()
        val uri = mockk<android.net.Uri>()
        val source = File(fixture.host, "selected").apply { writeText("new") }
        val target = File(fixture.workspace, "upload").apply { writeText("old") }
        fixture.selectFile(uri, source)
        fixture.cleanupFailureSession = 1
        val results = fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}
        assertEquals("old", target.readText())
        assertEquals("new", source.readText())
        assertEquals(0, fixture.stages)
        assertEquals(0, fixture.beginWrites)
        assertEquals(fixture.sessions, fixture.closedSessions)
        assertEquals(listOf(1), fixture.failedCleanupSessions)
        assertTrue("A ticket must not survive its batch cleanup failure: $results",
            results.none { it is TransferResult.Completed || it is TransferResult.Conflict })
        assertTrue(results.single() is TransferResult.Failed)
    }

    @Test fun uploadFilesRetainsEarlierResultsOnBodyFailureOnlyWhenDirectoryCleanupIsVerified() = runBlocking {
        for (unknownCleanup in listOf(false, true)) {
            for (firstConflict in listOf(false, true)) {
                val fixture = Fixture()
                val first = mockk<android.net.Uri>()
                val second = mockk<android.net.Uri>()
                val source = File(fixture.host, "selected").apply { writeText("new") }
                val target = File(fixture.workspace, "first")
                if (firstConflict) target.writeText("old")
                if (unknownCleanup) fixture.cleanupFailureSession = 1
                every { fixture.uploads.files(listOf(first, second), any()) } answers {
                    val check = secondArg<() -> Unit>()
                    val failedReader = object : TransferReader by host(source) {
                        override fun stat(relative: String): TransferStat? {
                            check()
                            throw IOException("Second document provider failed")
                        }
                    }
                    listOf(FileUploadInput("content://fixture/first", "first", fixture.guardedSource(host(source), check)),
                        FileUploadInput("content://fixture/second", "second", failedReader))
                }
                val results = fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(first, second)) {}
                assertEquals(if (firstConflict) "old" else "new", target.readText())
                assertFalse(File(fixture.workspace, "second").exists())
                fixture.assertNoStages()
                assertEquals(fixture.sessions, fixture.closedSessions)
                assertEquals(fixture.sessions, fixture.cleanupChecks)
                assertEquals(if (unknownCleanup) listOf(1) else emptyList<Int>(), fixture.failedCleanupSessions)
                if (unknownCleanup) {
                    assertTrue("Suppressed cleanup failure must also discard pending results: $results",
                        results.none { it is TransferResult.Completed || it is TransferResult.Conflict })
                    assertTrue(results.single() is TransferResult.Failed)
                } else {
                    assertEquals(2, results.size)
                    assertTrue(if (firstConflict) results.first() is TransferResult.Conflict else results.first() is TransferResult.Completed)
                    assertEquals("Second document provider failed", (results.last() as TransferResult.Failed).reason)
                }
            }
        }
    }

    @Test fun contentDocumentUploadUsesRealSourceGuardsAndBoundedMultiBlockReads() = runBlocking {
        val fixture = Fixture()
        val uri = mockk<android.net.Uri>()
        every { uri.scheme } returns "content"
        every { uri.toString() } returns "content://fixture/document"
        val bytes = ByteArray(TRANSFER_BUFFER_BYTES * 4 + 7) { (it % 61).toByte() }
        val resolver = mockk<android.content.ContentResolver>()
        val context = mockk<android.content.Context>()
        every { context.contentResolver } returns resolver
        every { resolver.query(uri, any<Array<String>>(), null, null, null) } answers {
            val cursor = mockk<android.database.Cursor>(relaxed = true)
            every { cursor.moveToFirst() } returns true
            every { cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) } returns 0
            every { cursor.getColumnIndex(android.provider.OpenableColumns.SIZE) } returns 1
            every { cursor.isNull(any()) } returns false
            every { cursor.getString(0) } returns "upload"
            every { cursor.getLong(1) } returns bytes.size.toLong()
            cursor
        }
        var opened = 0
        var closed = 0
        val requested = mutableListOf<Int>()
        every { resolver.openInputStream(uri) } answers {
            opened++
            val input = bytes.inputStream()
            object : InputStream() {
                override fun read(): Int = throw AssertionError("Transfer must use bounded bulk reads")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    requested += len
                    return input.read(b, off, len)
                }
                override fun close() { closed++; input.close() }
            }
        }
        val service = FileTransferService(FileBackendRouting(), FileUploadInputs(context), WorkspaceFileLock())
        val result = service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single()
        assertTrue(result is TransferResult.Completed)
        assertTrue(bytes.contentEquals(File(fixture.workspace, "upload").readBytes()))
        assertEquals(3, opened)
        assertEquals(opened, closed)
        assertEquals(18, requested.size)
        assertTrue(requested.all { it == TRANSFER_BUFFER_BYTES })
        assertTrue(fixture.writeRequests.all { it <= TRANSFER_BUFFER_BYTES })
        assertEquals(fixture.sessions, fixture.closedSessions)
        fixture.assertNoStages()
    }

    @Test fun uploadFilesDirectoryChecksReuseWorkerAcrossRepeatedSourceCallbacks() = runBlocking {
        assertUploadDirectoryCheckCounts(directory = false)
    }

    @Test fun uploadDirectoryChecksReuseWorkerAcrossRepeatedSourceCallbacks() = runBlocking {
        assertUploadDirectoryCheckCounts(directory = true)
    }

    private suspend fun assertUploadDirectoryCheckCounts(directory: Boolean) {
        val probes = listOf(1, 32, 256).map { repetitions ->
            val fixture = Fixture()
            val uri = mockk<android.net.Uri>()
            val bytes = ByteArray(TRANSFER_BUFFER_BYTES * 4 + 7) { (it % 53).toByte() }
            val source = if (directory) File(fixture.host, "selected").apply {
                mkdir()
                File(this, "nested").mkdir()
                File(this, "nested/data").writeBytes(bytes)
            } else File(fixture.host, "selected").apply { writeBytes(bytes) }
            var measured = DirectoryCheckCounts(0, 0, 0)
            fun input(check: () -> Unit): FileUploadInput {
                measured = fixture.measureDirectoryChecks(repetitions, check)
                return FileUploadInput("content://fixture/selected", "upload", fixture.guardedSource(host(source), check))
            }
            val result = if (directory) {
                every { fixture.uploads.directory(uri, any()) } answers { input(secondArg<() -> Unit>()) }
                fixture.service.uploadDirectory(fixture.execution, "~/workspace", uri) {}
            } else {
                every { fixture.uploads.files(listOf(uri), any()) } answers { listOf(input(secondArg<() -> Unit>())) }
                fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single()
            }
            assertTrue("Upload failed for directory=$directory repetitions=$repetitions: $result", result is TransferResult.Completed)
            val target = File(fixture.workspace, if (directory) "upload/nested/data" else "upload")
            assertTrue(bytes.contentEquals(target.readBytes()))
            assertTrue(fixture.sourceCallbacks > 0)
            assertTrue(fixture.sourceReadRequests.size > 4)
            assertTrue(fixture.sourceReadRequests.all { it in 1..TRANSFER_BUFFER_BYTES })
            assertTrue(fixture.writeRequests.all { it <= TRANSFER_BUFFER_BYTES })
            fixture.assertNoStages()
            assertEquals(fixture.sessions, fixture.closedSessions)
            UploadDirectoryProbe(repetitions, measured, fixture.sessions)
        }
        assertEquals("Directory validation must reuse its worker, not start workers per callback: $probes",
            listOf(0, 0, 0), probes.map { it.measured.sessions })
        assertEquals("Every repeated check must issue both live RESOLVE requests: $probes",
            listOf(2, 64, 512), probes.map { it.measured.resolves })
        assertEquals("Every repeated check must issue both live STAT requests: $probes",
            listOf(2, 64, 512), probes.map { it.measured.stats })
        assertEquals("Only validation frequency changed; unrelated transfer workers must stay constant: $probes",
            1, probes.map { it.totalSessions }.distinct().size)
    }

    private data class DirectoryCheckCounts(val sessions: Int, val resolves: Int, val stats: Int)
    private data class UploadDirectoryProbe(val repetitions: Int, val measured: DirectoryCheckCounts, val totalSessions: Int)

    @Test fun directUploadDirectoryValidationUsesOneWorkerAndFreshRequestsForEveryCheck() = runBlocking {
        for (repetitions in listOf(1, 32, 256)) {
            val fixture = Fixture()
            withContext(fixture.execution) {
                fixture.access.captureUploadDirectory("~/workspace") {}.use { validation ->
                    assertEquals(1, fixture.sessions)
                    assertEquals(0, fixture.closedSessions)
                    assertEquals(DirectoryCheckCounts(0, repetitions * 2, repetitions * 2),
                        fixture.measureDirectoryChecks(repetitions, validation::checkCurrent))
                    assertEquals(1, fixture.sessions)
                }
            }
            assertEquals(1, fixture.closedSessions)
            assertEquals(1, fixture.cleanupChecks)
        }
    }

    @Test fun uploadDirectoryValidationRejectsReplacedDirectoryAndRecreatedLinkEntry() = runBlocking {
        val fixture = Fixture()
        withContext(fixture.execution) {
            fixture.access.captureUploadDirectory("~/workspace") {}.use { validation ->
                fixture.replaceWorkspace()
                assertThrows(IOException::class.java) { validation.checkCurrent() }
                assertEquals(1, fixture.sessions)
            }
            val real = File(fixture.workspace, "real").apply { mkdir() }
            val alias = File(fixture.workspace, "alias")
            Files.createSymbolicLink(alias.toPath(), File("real").toPath())
            fixture.access.captureUploadDirectory("~/workspace/alias") {}.use { validation ->
                Files.move(alias.toPath(), File(fixture.workspace, "saved-alias").toPath())
                Files.createSymbolicLink(alias.toPath(), File("real").toPath())
                assertTrue(real.isDirectory)
                assertThrows(IOException::class.java) { validation.checkCurrent() }
                assertEquals(2, fixture.sessions)
            }
        }
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun uploadDirectoryValidationRejectsChangedResolvedPathsAndReadOnlyOrProcRoots() = runBlocking {
        for (resolved in listOf("/root/workspace/other", "/proc/escape", "/root/../escape")) {
            val fixture = Fixture()
            withContext(fixture.execution) {
                fixture.access.captureUploadDirectory("~/workspace") {}.use { validation ->
                    every { fixture.client.resolve("/root/workspace", true) } returns resolved
                    assertThrows(IOException::class.java) { validation.checkCurrent() }
                    assertEquals(1, fixture.sessions)
                }
            }
            assertEquals(1, fixture.closedSessions)
        }
        val readOnly = Fixture().apply { readOnlyPaths = listOf("/root/workspace") }
        withContext(readOnly.execution) {
            assertThrows(IOException::class.java) { readOnly.access.captureUploadDirectory("~/workspace") {} }
        }
        assertEquals(1, readOnly.sessions)
        assertEquals(1, readOnly.closedSessions)
        val root = Fixture()
        withContext(root.execution) {
            assertThrows(IOException::class.java) { root.access.captureUploadDirectory("/") {} }
            val count = root.sessions
            assertThrows(IOException::class.java) { root.access.captureUploadDirectory("/proc") {} }
            assertEquals(count, root.sessions)
        }
        assertEquals(root.sessions, root.closedSessions)
    }

    @Test fun uploadDirectoryValidationRejectsRevocationConfigurationRootAndSessionChangesWithoutRecapture() = runBlocking {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.identity = "changed" },
            { it.authorized = false },
            { it.sourceCurrent = false },
            { it.runtimeCurrent = false },
            { it.rootGeneration++ },
            { it.sessionCurrent = false },
            { it.openedSessions.single().close() },
        )
        for (change in changes) {
            val fixture = Fixture()
            withContext(fixture.execution) {
                fixture.access.captureUploadDirectory("~/workspace") {}.use { validation ->
                    change(fixture)
                    assertThrows(CancellationException::class.java) { validation.checkCurrent() }
                    assertEquals(1, fixture.sessions)
                }
            }
            assertEquals(1, fixture.closedSessions)
        }
    }

    @Test fun uploadDirectoryValidationCannotBeSharedAcrossOwnersOrOperationJobs() = runBlocking {
        val first = Fixture("first-owner")
        val second = Fixture("second-owner")
        withContext(first.execution) {
            first.access.captureUploadDirectory("~/workspace") {}.use { firstValidation ->
                withContext(second.execution) {
                    assertThrows(CancellationException::class.java) { firstValidation.checkCurrent() }
                    second.access.captureUploadDirectory("~/workspace") {}.use { secondValidation ->
                        secondValidation.checkCurrent()
                        assertEquals(1, first.sessions)
                        assertEquals(1, second.sessions)
                        assertFalse(first.openedSessions.single() === second.openedSessions.single())
                    }
                }
                val otherJob = Job()
                try {
                    withContext(otherJob) {
                        assertThrows(CancellationException::class.java) { firstValidation.checkCurrent() }
                    }
                } finally { otherJob.cancel() }
                firstValidation.checkCurrent()
            }
        }
        assertEquals(1, first.closedSessions)
        assertEquals(1, second.closedSessions)
    }

    @Test fun cancelledUploadDirectoryOwnerClosesWorkerImmediatelyAndNeverReopensIt() = runBlocking {
        val fixture = Fixture()
        val owner = Job()
        try {
            fixture.execution.useCaptured {
                runBlocking(fixture.execution.ownedBlockingContext(owner)) {
                    val validation = fixture.access.captureUploadDirectory("~/workspace") {}
                    owner.cancel()
                    assertEquals(1, fixture.closedSessions)
                    assertThrows(CancellationException::class.java) { validation.checkCurrent() }
                    validation.close()
                    assertEquals(1, fixture.sessions)
                    assertEquals(1, fixture.cleanupChecks)
                }
            }
        } catch (_: CancellationException) { assertTrue(owner.isCancelled) }
        assertEquals(1, fixture.sessions)
        assertEquals(1, fixture.closedSessions)
    }

    @Test fun uploadDirectoryCleanupUnknownIsThrownOrSuppressedWithoutReplacingThePrimaryFailure() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val fixture = Fixture()
            val owner = Job()
            var verified = false
            try {
                fixture.execution.useCaptured {
                    runBlocking(fixture.execution.ownedBlockingContext(owner)) {
                        val validation = fixture.access.captureUploadDirectory("~/workspace") {}
                        fixture.cleanupFailure = true
                        val primary = if (cancelled) CancellationException("Original upload cancelled") else IOException("Original upload failed")
                        if (cancelled) {
                            owner.cancel(primary as CancellationException)
                            assertEquals(1, fixture.closedSessions)
                        }
                        val failure = assertThrows(primary.javaClass) { validation.use { throw primary } }
                        assertSame(primary, failure)
                        val cleanup = failure.suppressed.single()
                        assertTrue(cleanup.message.orEmpty().contains("UNKNOWN"))
                        assertSame(fixture.cleanupProblem, cleanup.cause)
                        assertFalse(cleanup === primary)
                        assertEquals(1, fixture.sessions)
                        assertEquals(1, fixture.cleanupChecks)
                        verified = true
                    }
                }
            } catch (_: CancellationException) { assertTrue(owner.isCancelled) }
            assertTrue(verified)
            assertEquals(1, fixture.closedSessions)
        }
        val fixture = Fixture()
        withContext(fixture.execution) {
            val validation = fixture.access.captureUploadDirectory("~/workspace") {}
            fixture.cleanupFailure = true
            val failure = assertThrows(IOException::class.java) { validation.use {} }
            assertTrue(failure.message.orEmpty().contains("UNKNOWN"))
            assertSame(fixture.cleanupProblem, failure.cause)
        }
        assertEquals(1, fixture.sessions)
        assertEquals(1, fixture.closedSessions)
    }

    @Test fun explicitUploadDirectoryCloseWaitsForTheAlreadyStartedCancellationCleanupResult() = runBlocking {
        val fixture = Fixture()
        val owner = Job()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val explicit = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var verified = false
        try {
            fixture.execution.useCaptured {
                runBlocking(fixture.execution.ownedBlockingContext(owner)) {
                    val validation = fixture.access.captureUploadDirectory("~/workspace") {}
                    fixture.cleanupFailure = true
                    fixture.onClose = {
                        entered.countDown()
                        assertTrue(release.await(2, TimeUnit.SECONDS))
                    }
                    val cancelling = workers.submit { owner.cancel(CancellationException("Original cancellation")) }
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    val closing = workers.submit<Throwable?> {
                        explicit.countDown()
                        runCatching { validation.close() }.exceptionOrNull()
                    }
                    assertTrue(explicit.await(2, TimeUnit.SECONDS))
                    assertFalse(closing.isDone)
                    release.countDown()
                    cancelling.get(2, TimeUnit.SECONDS)
                    val failure = closing.get(2, TimeUnit.SECONDS)
                    assertTrue(failure is IOException)
                    assertTrue(failure!!.message.orEmpty().contains("UNKNOWN"))
                    assertSame(fixture.cleanupProblem, failure.cause)
                    validation.close()
                    assertEquals(1, fixture.sessions)
                    assertEquals(1, fixture.closedSessions)
                    assertEquals(1, fixture.cleanupChecks)
                    verified = true
                }
            }
        } catch (_: CancellationException) { assertTrue(owner.isCancelled) }
        finally {
            release.countDown()
            owner.cancel()
            workers.shutdownNow()
        }
        assertTrue(verified)
    }

    @Test fun sameChrootBackendUsesExistingCopyAndRenameInsteadOfCrossBackendStaging() = runBlocking {
        val fixture = Fixture()
        File(fixture.workspace, "source").writeText("content")
        assertTrue(fixture.service.transfer(fixture.execution, "~/workspace/source", "~/workspace/copy", false) {} is TransferResult.Completed)
        assertEquals("content", File(fixture.workspace, "copy").readText())
        assertTrue(fixture.service.transfer(fixture.execution, "~/workspace/copy", "~/workspace/moved", true) {} is TransferResult.Completed)
        assertEquals("content", File(fixture.workspace, "moved").readText())
        assertFalse(File(fixture.workspace, "copy").exists())
        assertTrue(File(fixture.workspace, "source").exists())
        assertEquals(0, fixture.stages)
    }

    @Test fun changedConfirmationTargetIsPreservedWithoutCreatingAStage() = runBlocking {
        val fixture = Fixture()
        File(fixture.host, "source").writeText("new")
        val target = File(fixture.workspace, "target").apply { writeText("old") }
        val ticket = (fixture.service.transfer(fixture.execution, fixture.hostPath("source"), "~/workspace/target", true) {}
            as TransferResult.Conflict).ticket
        val time = target.lastModified()
        target.writeText("NEW")
        target.setLastModified(time)
        val result = fixture.service.confirm(fixture.execution, ticket) {}
        assertTrue(result is TransferResult.Failed)
        assertEquals("NEW", target.readText())
        assertEquals("new", File(fixture.host, "source").readText())
        assertEquals(0, fixture.stages)
    }

    @Test fun confirmationCanCommitInANewCallWithoutRetainingTheCompletedRequestJob() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.host, "source").apply { writeText("new") }
        val target = File(fixture.workspace, "target").apply { writeText("old") }
        val ticket = withContext(Dispatchers.Default) {
            (fixture.service.transfer(fixture.execution, fixture.hostPath("source"), "~/workspace/target", true) {}
                as TransferResult.Conflict).ticket
        }
        val result = withContext(Dispatchers.Default) { fixture.service.confirm(fixture.execution, ticket) {} }
        assertTrue(result is TransferResult.Completed)
        assertEquals("new", target.readText())
        assertFalse(source.exists())
        fixture.assertNoStages()
    }

    @Test fun chrootUploadConflictClosesItsGuardAndConfirmationRebuildsUnderTheNewCallJob() = runBlocking {
        val fixture = Fixture()
        val uri = mockk<android.net.Uri>()
        val bytes = ByteArray(TRANSFER_BUFFER_BYTES * 4 + 7) { (it % 71).toByte() }
        val source = File(fixture.host, "selected").apply { writeBytes(bytes) }
        val target = File(fixture.workspace, "upload").apply { writeText("old") }
        fixture.selectFile(uri, source)
        val requestJob = Job()
        val ticket = withContext(Dispatchers.Default + requestJob) {
            (fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single() as TransferResult.Conflict).ticket
        }
        assertTrue(ticket.plan is ChrootUploadPlan)
        assertEquals(fixture.sessions, fixture.closedSessions)
        assertEquals(0, fixture.stages)
        assertEquals("old", target.readText())
        val previousSessions = fixture.sessions
        requestJob.cancel()
        val result = withContext(Dispatchers.Default) { fixture.service.confirm(fixture.execution, ticket) {} }
        assertTrue(result is TransferResult.Completed)
        assertTrue(fixture.sessions > previousSessions)
        assertTrue(bytes.contentEquals(target.readBytes()))
        assertTrue(source.exists())
        assertEquals(fixture.sessions, fixture.closedSessions)
        fixture.assertNoStages()
    }

    @Test fun chrootUploadConfirmationRejectsDirectorySourceSelectionContentAndTargetChanges() = runBlocking {
        for (change in listOf("directory", "content", "size", "name", "source", "physicalSource", "target", "targetLink")) {
            val fixture = Fixture()
            val uri = mockk<android.net.Uri>()
            val source = File(fixture.host, "selected").apply { writeText("new") }
            val target = File(fixture.workspace, "upload").apply { writeText("old") }
            fixture.selectFile(uri, source)
            val ticket = (fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single() as TransferResult.Conflict).ticket
            assertEquals(fixture.sessions, fixture.closedSessions)
            when (change) {
                "directory" -> fixture.replaceWorkspace()
                "content" -> {
                    val modified = source.lastModified()
                    source.writeText("NEW")
                    source.setLastModified(modified)
                }
                "size" -> source.writeText("longer")
                "name" -> fixture.selectFile(uri, source, "other")
                "source", "physicalSource" -> every { fixture.uploads.files(listOf(uri), any()) } answers {
                    val label = if (change == "source") "content://fixture/different" else "content://fixture/selected"
                    listOf(FileUploadInput(label, "upload", fixture.guardedSource(host(source), secondArg()),
                        if (change == "physicalSource") source.path else null))
                }
                "target" -> {
                    val modified = target.lastModified()
                    target.writeText("OLD")
                    target.setLastModified(modified)
                }
                "targetLink" -> {
                    Files.move(target.toPath(), File(fixture.workspace, "saved-upload").toPath())
                    File(fixture.workspace, "other").writeText("other")
                    Files.createSymbolicLink(target.toPath(), File("other").toPath())
                }
            }
            val result = fixture.service.confirm(fixture.execution, ticket) {}
            assertTrue("Change $change must be rejected: $result", result is TransferResult.Failed)
            assertEquals(0, fixture.stages)
            assertEquals(0, fixture.beginWrites)
            assertTrue(source.exists())
            assertEquals(fixture.sessions, fixture.closedSessions)
            fixture.assertNoStages()
        }
    }

    @Test fun chrootUploadConfirmationRejectsRevocationSourceConfigurationRootAndDifferentOwner() = runBlocking {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.authorized = false }, { it.sourceCurrent = false }, { it.runtimeCurrent = false },
            { it.identity = "changed" }, { it.rootGeneration++ },
        )
        for (change in changes) {
            val fixture = Fixture()
            val uri = mockk<android.net.Uri>()
            val source = File(fixture.host, "selected").apply { writeText("new") }
            File(fixture.workspace, "upload").writeText("old")
            fixture.selectFile(uri, source)
            val ticket = (fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single() as TransferResult.Conflict).ticket
            val count = fixture.sessions
            change(fixture)
            assertThrows(CancellationException::class.java) { runBlocking { fixture.service.confirm(fixture.execution, ticket) {} } }
            assertEquals(count, fixture.sessions)
            assertEquals(count, fixture.closedSessions)
            assertEquals("old", File(fixture.workspace, "upload").readText())
        }
        val fixture = Fixture("original-owner")
        val uri = mockk<android.net.Uri>()
        val source = File(fixture.host, "selected").apply { writeText("new") }
        File(fixture.workspace, "upload").writeText("old")
        fixture.selectFile(uri, source)
        val ticket = (fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single() as TransferResult.Conflict).ticket
        val other = Fixture("other-owner")
        assertThrows(CancellationException::class.java) { runBlocking { fixture.service.confirm(other.execution, ticket) {} } }
        assertEquals(0, other.sessions)
        assertEquals(0, fixture.stages)
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun chrootUploadConfirmationRequiresBothOriginalAndCurrentScopeTokens() = runBlocking {
        for (invalidateOriginal in listOf(true, false)) {
            val fixture = Fixture()
            val uri = mockk<android.net.Uri>()
            val source = File(fixture.host, "selected").apply { writeText("new") }
            File(fixture.workspace, "upload").writeText("old")
            fixture.selectFile(uri, source)
            var originalCurrent = true
            val ticket = (fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {
                if (!originalCurrent) throw CancellationException("Original picker token changed")
            }.single() as TransferResult.Conflict).ticket
            val count = fixture.sessions
            originalCurrent = !invalidateOriginal
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    fixture.service.confirm(fixture.execution, ticket) {
                        if (!invalidateOriginal) throw CancellationException("Current confirmation token changed")
                    }
                }
            }
            assertEquals(count, fixture.sessions)
            assertEquals(count, fixture.closedSessions)
            assertEquals("old", File(fixture.workspace, "upload").readText())
        }
    }

    @Test fun chrootUploadConfirmationCancellationCannotBecomeCompletedOrKeepALiveGuard() = runBlocking {
        val fixture = Fixture()
        val uri = mockk<android.net.Uri>()
        val source = File(fixture.host, "selected").apply { writeText("new") }
        File(fixture.workspace, "upload").writeText("old")
        fixture.selectFile(uri, source)
        val ticket = (fixture.service.uploadFiles(fixture.execution, "~/workspace", listOf(uri)) {}.single() as TransferResult.Conflict).ticket
        every { fixture.uploads.files(listOf(uri), any()) } answers {
            fixture.authorized = false
            secondArg<() -> Unit>()()
            throw AssertionError("Revoked source must not reach the reader")
        }
        assertThrows(CancellationException::class.java) { runBlocking { fixture.service.confirm(fixture.execution, ticket) {} } }
        assertEquals("old", File(fixture.workspace, "upload").readText())
        assertEquals(0, fixture.stages)
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun sourceHashMismatchKeepsSourceOldTargetAndCleansOwnedStage() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.host, "source").apply { writeText("old") }
        val time = source.lastModified()
        val target = File(fixture.workspace, "target").apply { writeText("keep") }
        withContext(fixture.execution) {
            val reader = host(source)
            val version = transferVersion(reader) {}!!
            val backend = fixture.access.transferBackend("~/workspace/target") {}
            val expected = transferVersion(backend) {}
            source.writeText("new")
            source.setLastModified(time)
            assertThrows(IOException::class.java) {
                TransferOperation(reader, backend, "source", "target", true, {}, {}).run(version, expected)
            }
        }
        assertEquals("new", source.readText())
        assertEquals("keep", target.readText())
        fixture.assertNoStages()
    }

    @Test fun internalHostLinksAreRejectedBeforeAnyChrootStageWrite() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.host, "tree").apply { mkdir() }
        File(source, "first").writeText("keep")
        Files.createSymbolicLink(File(source, "link").toPath(), File("first").toPath())
        val result = fixture.service.transfer(fixture.execution, fixture.hostPath("tree"), "~/workspace/target", true) {}
        assertTrue(result is TransferResult.Failed)
        assertEquals(0, fixture.stages)
        assertEquals(0, fixture.beginWrites)
        assertTrue(Files.isSymbolicLink(File(source, "link").toPath()))
        assertFalse(File(fixture.workspace, "target").exists())
    }

    @Test fun internalChrootLinksAreNotFollowedOrSilentlySkipped() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.workspace, "tree").apply { mkdir() }
        File(source, "first").writeText("keep")
        Files.createSymbolicLink(File(source, "link").toPath(), File("first").toPath())
        val result = fixture.service.transfer(fixture.execution, "~/workspace/tree", fixture.hostPath("target"), true) {}
        assertTrue(result is TransferResult.Failed)
        assertTrue(File(source, "first").exists())
        assertTrue(Files.isSymbolicLink(File(source, "link").toPath()))
        assertFalse(File(fixture.host, "target").exists())
        assertEquals(0, fixture.beginWrites)
    }

    @Test fun exdevRenameFailureDoesNotDeleteSourceOrReplaceExistingTarget() = runBlocking {
        val fixture = Fixture()
        File(fixture.host, "source").writeText("new")
        val target = File(fixture.workspace, "target").apply { writeText("keep") }
        fixture.renameErrno = 18
        val ticket = (fixture.service.transfer(fixture.execution, fixture.hostPath("source"), "~/workspace/target", true) {}
            as TransferResult.Conflict).ticket
        val result = fixture.service.confirm(fixture.execution, ticket) {}
        assertTrue(result is TransferResult.Failed)
        assertEquals("keep", target.readText())
        assertEquals("new", File(fixture.host, "source").readText())
        fixture.assertNoStages()
    }

    @Test fun publishedCopyWithChrootUnlinkFailureReportsCopiedButSourceNotRemoved() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.workspace, "source").apply { writeText("content") }
        fixture.deleteFailure = "/root/workspace/source"
        val result = fixture.service.transfer(fixture.execution, "~/workspace/source", fixture.hostPath("target"), true) {}
        assertTrue(result is TransferResult.CopiedButSourceNotRemoved)
        assertEquals("content", source.readText())
        assertEquals("content", File(fixture.host, "target").readText())
    }

    @Test fun writeFailureAbortsPayloadAndCleansOnlyItsOwnedStage() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.host, "source").apply { writeText("content") }
        val target = File(fixture.workspace, "target").apply { writeText("keep") }
        withContext(fixture.execution) {
            val backend = fixture.access.transferBackend("~/workspace/target") {}
            val reader = host(source)
            val version = transferVersion(reader) {}!!
            val expected = transferVersion(backend) {}
            fixture.failPayloadWrite = true
            assertThrows(IOException::class.java) {
                TransferOperation(reader, backend, "source", "target", true, {}, {}).run(version, expected)
            }
        }
        assertEquals(1, fixture.abortedWrites)
        assertEquals("keep", target.readText())
        assertEquals("content", source.readText())
        fixture.assertNoStages()
    }

    @Test fun cancelledOriginalJobClosesStageWorkerAndDoesNotReopenOneForCleanup() = runBlocking {
        val fixture = Fixture()
        val owner = Job()
        val source = File(fixture.host, "source").apply { writeText("content") }
        val target = File(fixture.workspace, "target").apply { writeText("keep") }
        try {
            fixture.execution.useCaptured {
                runBlocking(fixture.execution.ownedBlockingContext(owner)) {
                    val backend = fixture.access.transferBackend("~/workspace/target") {}
                    val sessionCountBeforeStage = fixture.sessions
                    val stage = backend.createStage(TransferKind.FILE)
                    stage.writeFile("") { it.write(1) }
                    val count = fixture.sessions
                    assertEquals(sessionCountBeforeStage + 1, count)
                    owner.cancel()
                    val failure = assertThrows(IOException::class.java) { stage.close() }
                    assertTrue(failure.message.orEmpty().contains("UNKNOWN"))
                    assertEquals(count, fixture.sessions)
                    assertThrows(CancellationException::class.java) { stage.commit(true) {} }
                }
            }
        } catch (_: CancellationException) {
            assertTrue(owner.isCancelled)
        }
        assertEquals("keep", target.readText())
        assertEquals("content", source.readText())
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun stageOwnershipChangeRefusesToDeleteForeignContent() = runBlocking {
        val fixture = Fixture()
        withContext(fixture.execution) {
            val backend = fixture.access.transferBackend("~/workspace/target") {}
            val stage = backend.createStage(TransferKind.FILE)
            stage.writeFile("") { it.write(1) }
            val owned = fixture.workspace.listFiles()!!.single { it.name.startsWith(".aicode-transfer-") }
            val saved = File(fixture.workspace, "saved")
            Files.move(owned.toPath(), saved.toPath())
            owned.mkdir()
            File(owned, "foreign").writeText("keep")
            assertThrows(IOException::class.java) { stage.close() }
            assertEquals("keep", File(owned, "foreign").readText())
            assertTrue(File(saved, "payload").exists())
        }
    }

    @Test fun unknownWorkerCleanupCannotBeReportedAsACompletedCopy() = runBlocking {
        val fixture = Fixture()
        File(fixture.host, "source").writeText("content")
        fixture.cleanupFailure = true
        val result = fixture.service.transfer(fixture.execution, fixture.hostPath("source"), "~/workspace/target", true) {}
        assertTrue(result is TransferResult.Failed)
        assertTrue(File(fixture.host, "source").exists())
        assertFalse(File(fixture.workspace, "target").exists())
        assertEquals(fixture.sessions, fixture.closedSessions)
    }

    @Test fun cleanupUnknownAfterPublicationKeepsCutSourceAndReportsIncompleteRemoval() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.host, "source").apply { writeText("content") }
        fixture.cleanupFailureAfterPublish = true
        val result = fixture.service.transfer(fixture.execution, fixture.hostPath("source"), "~/workspace/target", true) {}
        assertTrue(result is TransferResult.CopiedButSourceNotRemoved)
        assertEquals("content", source.readText())
        assertEquals("content", File(fixture.workspace, "target").readText())
    }

    @Test fun corruptedStagingDigestCannotPublishOrDeleteTheSource() = runBlocking {
        val fixture = Fixture()
        val source = File(fixture.host, "source").apply { writeText("content") }
        fixture.corruptPayload = true
        val result = fixture.service.transfer(fixture.execution, fixture.hostPath("source"), "~/workspace/target", true) {}
        assertTrue(result is TransferResult.Failed)
        assertEquals("content", source.readText())
        assertFalse(File(fixture.workspace, "target").exists())
        fixture.assertNoStages()
    }

    @Test fun targetAppearingBeforeNoReplaceCommitIsPreserved() = runBlocking {
        val fixture = Fixture()
        val target = File(fixture.workspace, "target")
        withContext(fixture.execution) {
            val backend = fixture.access.transferBackend("~/workspace/target") {}
            val stage = backend.createStage(TransferKind.FILE)
            try {
                stage.writeFile("") { it.write("new".toByteArray()) }
                assertThrows(IOException::class.java) { stage.commit(false) { target.writeText("concurrent") } }
            } finally { stage.close() }
        }
        assertEquals("concurrent", target.readText())
        fixture.assertNoStages()
    }

    private inner class Fixture(ownerScope: String = "file-transfer-fixture") {
        val guest = temporary.newFolder()
        val workspace = File(guest, "root/workspace").apply { mkdirs() }
        val host = temporary.newFolder()
        val client = mockk<ChrootFileWorkerClient>()
        val uploads = mockk<FileUploadInputs>()
        val service = FileTransferService(FileBackendRouting(), uploads, WorkspaceFileLock())
        val owner = RuntimeOwner(RuntimeOwnerKind.FILE, ownerScope)
        val grant = LocalFolderGrant("source", "epoch", "Host", localRootPath("source", "epoch"), true)
        val roots = LocalFolderSnapshot(1, listOf(grant), listOf(CapturedLocalFolderRoot(grant, host))) {}
        val access: ChrootFileAccess
        val execution: WorkspaceExecutionContext
        var sessions = 0
        var closedSessions = 0
        var cleanupChecks = 0
        var stages = 0
        var beginWrites = 0
        var abortedWrites = 0
        var failPayloadWrite = false
        var renameErrno: Int? = null
        var deleteFailure: String? = null
        var cleanupFailure = false
        var cleanupFailureSession: Int? = null
        val failedCleanupSessions = mutableListOf<Int>()
        var cleanupFailureAfterPublish = false
        var payloadPublished = false
        var corruptPayload = false
        var identity = "fixture"
        var authorized = true
        var sourceCurrent = true
        var runtimeCurrent = true
        var sessionCurrent = true
        var rootGeneration = 1L
        var writablePaths = listOf("/root/workspace")
        var readOnlyPaths = emptyList<String>()
        val cleanupProblem = IOException("Worker cleanup is UNKNOWN")
        var onClose: () -> Unit = {}
        val openedSessions = mutableListOf<ChrootFileSession>()
        val writeRequests = mutableListOf<Int>()
        val sourceReadRequests = mutableListOf<Int>()
        var sourceCallbacks = 0
        private var directoryResolves = 0
        private var directoryStats = 0

        init {
            every { client.close() } returns Unit
            every { client.stat(any(), any()) } answers {
                val path = firstArg<String>()
                if (path == "/root/workspace") directoryStats++
                stat(file(path), secondArg())
            }
            every { client.resolve(any(), any()) } answers {
                val path = firstArg<String>()
                if (path == "/root/workspace") directoryResolves++
                val input = file(path)
                val resolved = if (secondArg<Boolean>()) input.canonicalFile
                    else File(input.parentFile.canonicalFile, input.name)
                "/" + guest.toPath().relativize(resolved.toPath()).toString()
            }
            every { client.list(any()) } answers {
                val directory = file(firstArg())
                if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) throw ChrootFileOperationException(20)
                directory.listFiles()!!.map { it.name to stat(it, false) }
            }
            every { client.openRead(any()) } answers {
                val path = file(firstArg())
                if (!Files.isRegularFile(path.toPath(), NOFOLLOW_LINKS)) throw ChrootFileOperationException(22)
                path.inputStream()
            }
            every { client.mkdirs(any()) } answers {
                val path = file(firstArg())
                if (path.name.startsWith(".aicode-transfer-")) stages++
                if (!path.mkdirs() && !path.isDirectory) throw ChrootFileOperationException(20)
            }
            every { client.write(any<String>(), any<InputStream>(), any<Boolean>()) } answers {
                val path = firstArg<String>()
                val source = secondArg<InputStream>()
                val overwrite = thirdArg<Boolean>()
                client.write(path, overwrite) { source.copyTo(it, TRANSFER_BUFFER_BYTES) }
            }
            every { client.write(any<String>(), any<Boolean>(), any<(OutputStream) -> Unit>()) } answers {
                val path = file(firstArg())
                val overwrite = secondArg<Boolean>()
                val writer = thirdArg<(OutputStream) -> Unit>()
                beginWrites++
                val temporary = File.createTempFile(".native-owned-", ".tmp", path.parentFile)
                try {
                    temporary.outputStream().use { output ->
                        writer(object : OutputStream() {
                            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                writeRequests += len
                                output.write(b, off, len)
                                if (failPayloadWrite && path.name == "payload") throw IOException("Fixture upload failed")
                            }
                        })
                    }
                    if (!overwrite && Files.exists(path.toPath(), NOFOLLOW_LINKS)) throw ChrootFileOperationException(17)
                    Files.move(temporary.toPath(), path.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    if (corruptPayload && path.name == "payload") path.writeText("corrupt")
                    path.length()
                } catch (failure: Throwable) {
                    abortedWrites++
                    throw failure
                } finally {
                    temporary.delete()
                }
            }
            every { client.rename(any(), any(), any()) } answers {
                renameErrno?.let { throw ChrootFileOperationException(it) }
                val source = file(firstArg())
                val target = file(secondArg())
                if (!thirdArg<Boolean>() && Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw ChrootFileOperationException(17)
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                if (source.name == "payload") payloadPublished = true
                Unit
            }
            every { client.delete(any(), any()) } answers {
                val path = firstArg<String>()
                if (path == deleteFailure) throw ChrootFileOperationException(13)
                if (secondArg<Boolean>()) throw AssertionError("Transfer cleanup must not recursively remove an unverified tree")
                Files.delete(file(path).toPath())
            }
            access = ChrootFileAccess({ identity }, {
                val sessionNumber = ++sessions
                val capturedIdentity = identity
                val capturedRoot = rootGeneration
                val closed = AtomicBoolean()
                ChrootFileSession(capturedIdentity, client, temporary.root, {
                    if (!sessionCurrent || !authorized || capturedRoot != rootGeneration) throw CancellationException("Fixture session changed")
                }, writablePaths, readOnlyPaths, closeOwner = {
                    if (closed.compareAndSet(false, true)) closedSessions++
                    cleanupChecks++
                    onClose()
                    if (cleanupFailure || cleanupFailureSession == sessionNumber || cleanupFailureAfterPublish && payloadPublished) {
                        failedCleanupSessions += sessionNumber
                        throw cleanupProblem
                    }
                }).also { openedSessions += it }
            })
            val capture = LocalRuntimeCapture(owner, "fixture", 1L, ChrootInstance("instance", 1, 2, 3),
                workspace.path, "/root", "capture", Any()) {
                if (!runtimeCurrent || !authorized || rootGeneration != 1L) throw CancellationException("Fixture runtime capture changed")
            }
            execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", access, null, null, roots, owner, capture) {
                if (!sourceCurrent) throw CancellationException("Fixture workspace source changed")
            }
        }

        fun hostPath(relative: String) = grant.rootPath + "/" + relative

        fun selectFile(uri: android.net.Uri, source: File, name: String = "upload") {
            every { uploads.files(listOf(uri), any()) } answers {
                listOf(FileUploadInput("content://fixture/selected", name, guardedSource(host(source), secondArg())))
            }
        }

        fun replaceWorkspace(): File {
            val saved = File(workspace.parentFile, "workspace-saved")
            Files.move(workspace.toPath(), saved.toPath())
            assertTrue(workspace.mkdir())
            return saved
        }

        fun measureDirectoryChecks(repetitions: Int, check: () -> Unit): DirectoryCheckCounts {
            val started = sessions
            val resolved = directoryResolves
            val stated = directoryStats
            repeat(repetitions) { check() }
            return DirectoryCheckCounts(sessions - started, directoryResolves - resolved, directoryStats - stated)
        }

        fun guardedSource(reader: TransferReader, check: () -> Unit): TransferReader {
            fun checkSource() { sourceCallbacks++; check() }
            return object : TransferReader by reader {
                override fun stat(relative: String): TransferStat? {
                    checkSource()
                    return reader.stat(relative).also { checkSource() }
                }
                override fun list(relative: String): List<String> {
                    checkSource()
                    return reader.list(relative).also { checkSource() }
                }
                override fun openRead(relative: String): InputStream {
                    checkSource()
                    val input = reader.openRead(relative)
                    try {
                        checkSource()
                        return object : InputStream() {
                            private val single = ByteArray(1)
                            override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                checkSource()
                                sourceReadRequests += len
                                return input.read(b, off, len).also { checkSource() }
                            }
                            override fun close() = input.close()
                        }
                    } catch (failure: Throwable) {
                        runCatching { input.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    }
                }
            }
        }

        fun assertNoStages() {
            assertTrue(workspace.listFiles()!!.none { it.name.startsWith(".aicode-transfer-") })
        }

        private fun file(path: String): File = File(guest, path.removePrefix("/"))

        private fun stat(file: File, follow: Boolean): ChrootFileStat {
            val options = if (follow) emptyArray() else arrayOf(NOFOLLOW_LINKS)
            val attrs = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, *options) }
                catch (_: java.nio.file.NoSuchFileException) { throw ChrootFileOperationException(2) }
            val kind = when {
                attrs.isSymbolicLink -> 3
                attrs.isDirectory -> 1
                attrs.isRegularFile -> 2
                else -> throw ChrootFileOperationException(95)
            }
            val inode = (Files.getAttribute(file.toPath(), "unix:ino", *options) as Number).toLong()
            return ChrootFileStat(kind, if (kind == 1) 0x41c0 else 0x8180, attrs.size(), attrs.lastModifiedTime().toMillis(), 1, inode)
        }
    }
}
