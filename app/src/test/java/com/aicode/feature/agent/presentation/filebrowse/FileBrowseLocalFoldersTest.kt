package com.aicode.feature.agent.presentation.filebrowse

import android.net.Uri
import com.aicode.feature.agent.presentation.BrowseOperationNotice
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeRoot
import com.aicode.feature.agent.presentation.filebrowse.FileBrowseControllerTest.Fixture
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderUnavailableReason
import com.aicode.feature.workspace.domain.TransferCancellationException
import com.aicode.feature.workspace.domain.TransferConfirmation
import com.aicode.feature.workspace.domain.TransferResult
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.localRootPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowseLocalFoldersTest {
    @Test
    fun sshModeShowsLocalRootsAndReadsOnlyTheExpandedRootThroughHostWatch() = runTest {
        val f = Fixture(this)
        val first = folder("first")
        val second = folder("second")
        f.holder.setMode(ExecutionMode.REMOTE_SSH)
        f.setLocalFolders(listOf(first, second))
        f.tree[first.rootPath] = listOf(file("phone.txt"), dir("nested"))
        f.tree[second.rootPath] = listOf(file("other.txt"))
        f.show()
        assertEquals(FileTreeRoot.LocalFolder(first.id, first.label, true), f.node(first.rootPath).root)
        assertEquals(FileTreeRoot.LocalFolder(second.id, second.label, true), f.node(second.rootPath).root)
        assertFalse(f.node(first.rootPath).isExpanded)
        assertFalse(f.node(second.rootPath).isExpanded)
        assertEquals(mapOf(ROOT to 1), f.calls)
        assertTrue(f.localWatching.isEmpty())
        val before = f.calls.toMap()

        f.open(first.rootPath)

        assertEquals(before.getValue(ROOT), f.calls[ROOT])
        assertEquals(1, f.calls[first.rootPath])
        assertNull(f.calls[second.rootPath])
        assertEquals(listOf("remote", "host"), f.backends)
        assertEquals(setOf(first.rootPath), f.localWatching)
        assertTrue(f.watching.isEmpty())
        assertEquals(1, f.node("${first.rootPath}/phone.txt").depth)
        assertNull(f.node("${first.rootPath}/phone.txt").root)
        verify(exactly = 0) { f.remote.listFiles(first.rootPath) }
        verify(exactly = 0) { f.local.listFiles(first.rootPath) }

        f.tree[first.rootPath] = listOf(file("changed.txt"))
        assertTrue(f.localSignals.getValue(first.rootPath).tryEmit(Unit))
        runCurrent()
        assertEquals(2, f.calls[first.rootPath])
        assertEquals(before.getValue(ROOT), f.calls[ROOT])
        assertTrue("${first.rootPath}/changed.txt" in f.paths())
        assertFalse("${first.rootPath}/phone.txt" in f.paths())
        assertFalse(f.paths().any { it.startsWith("${second.rootPath}/") })
        f.controller.toggle(first.rootPath)
        runCurrent()
        assertTrue(f.localWatching.isEmpty())
        assertFalse(f.node(first.rootPath).isExpanded)
        assertEquals(2, f.calls[first.rootPath])
    }

    @Test
    fun unavailableRootRemainsAnErrorRowWithoutBackendReadsOrAnExpansionBusyLease() = runTest {
        val f = Fixture(this)
        val root = folder("unavailable").copy(available = false, reason = LocalFolderUnavailableReason.READ_WRITE_REQUIRED)
        f.holder.setMode(ExecutionMode.REMOTE_SSH)
        f.setLocalFolders(listOf(root))
        f.tree[root.rootPath] = listOf(file("must-not-read"))
        f.show()
        val before = f.calls.toMap()
        assertEquals(FileTreeRoot.LocalFolder(root.id, root.label, false), f.node(root.rootPath).root)
        assertTrue(f.node(root.rootPath).hasError)

        f.open(root.rootPath)
        f.controller.refresh()
        runCurrent()

        assertTrue(f.node(root.rootPath).hasError)
        assertNull(f.calls[root.rootPath])
        assertEquals(before.getValue(ROOT) + 1, f.calls[ROOT])
        assertFalse(f.paths().any { it.startsWith("${root.rootPath}/") })
        assertTrue(f.localWatching.isEmpty())
        assertNull(f.controller.expandingPath.value)
        verify(exactly = 0) { f.host.listFiles(any()) }
    }

    @Test
    fun localRootContentsDoNotInheritWorkspaceIgnorePatternsOrOpenSiblingRoots() = runTest {
        val f = Fixture(this)
        val first = folder("first")
        val second = folder("second")
        f.gitignore = "*.tmp"
        f.tree[ROOT] = listOf(file("workspace.tmp"))
        f.tree[first.rootPath] = listOf(file("phone.tmp"))
        f.tree[second.rootPath] = listOf(file("other.tmp"))
        f.setLocalFolders(listOf(first, second))
        f.show()
        f.open(first.rootPath)
        assertTrue(f.node("$ROOT/workspace.tmp").ignored)
        assertFalse(f.node("${first.rootPath}/phone.tmp").ignored)
        assertEquals(1, f.ignoreReads)
        assertNull(f.calls[second.rootPath])
        assertEquals(setOf(ROOT), f.watching)
        assertEquals(setOf(first.rootPath), f.localWatching)
    }

    @Test
    fun rootCopyCutDeleteAndRenameAreRejectedWithoutCallingAnyBackendOrTransfer() = runTest {
        for (mode in listOf(ExecutionMode.LOCAL_PROOT, ExecutionMode.REMOTE_SSH)) {
            val f = Fixture(this)
            val root = folder("protected")
            f.holder.setMode(mode)
            f.setLocalFolders(listOf(root))
            f.show()
            val before = f.calls.toMap()
            val results = mutableListOf<Boolean>()
            for (path in listOf(ROOT, "/", root.rootPath)) {
                f.controller.copy(path, "root", cut = false)
                runCurrent()
                assertNull(f.controller.clipboard.value)
                f.controller.copy(path, "root", cut = true)
                runCurrent()
                assertNull(f.controller.clipboard.value)
                f.controller.delete(path) { results += it }
                f.controller.rename(path, "renamed") { results += it }
            }
            runCurrent()
            assertEquals(List(6) { false }, results)
            assertEquals(before, f.calls)
            assertTrue(f.controller.fileOpPaths.value.isEmpty())
            assertFalse(f.controller.transferActive.value)
            verify(exactly = 0) { f.routing.capture(any(), any(), any()) }
            coVerify(exactly = 0) { f.transfers.transfer(any(), any(), any(), any(), any(), any()) }
            for (provider in listOf(f.local, f.remote, f.host)) {
                verify(exactly = 0) { provider.rename(any(), any()) }
                verify(exactly = 0) { provider.deleteRecursively(any()) }
                verify(exactly = 0) { provider.copy(any(), any(), any()) }
                verify(exactly = 0) { provider.move(any(), any(), any()) }
            }
        }
    }

    @Test
    fun latePickerResultAfterWorkspaceRoundTripDoesNotStartAnUpload() = runTest {
        val f = Fixture(this)
        f.show()
        val target = checkNotNull(f.controller.prepareUpload("$ROOT/b", f.selectionRevisions.value))
        f.workspace = "/temporary"
        f.controller.setScope(f.key())
        f.workspace = "/work"
        f.controller.setScope(f.key())
        f.controller.upload(target, listOf(mockk<Uri>()), directory = false)
        runCurrent()
        assertEquals(listOf(BrowseOperationNotice.INVALID_SELECTION), f.notices)
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        coVerify(exactly = 0) { f.transfers.uploadFiles(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.transfers.uploadDirectory(any(), any(), any(), any()) }
    }

    @Test
    fun latePickerResultAfterProfileAbaBeforeScopeResamplingIsRejectedBySelectionRevision() = runTest {
        val f = Fixture(this)
        f.show()
        val target = checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value))
        f.profile.value = "temporary"
        f.selectionRevisions.value++
        f.profile.value = "profile-one"
        f.selectionRevisions.value++
        f.controller.upload(target, listOf(mockk<Uri>()), directory = true)
        runCurrent()
        assertEquals(listOf(BrowseOperationNotice.INVALID_SELECTION), f.notices)
        assertNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value))
        assertFalse(f.controller.transferActive.value)
        coVerify(exactly = 0) { f.transfers.uploadFiles(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.transfers.uploadDirectory(any(), any(), any(), any()) }
        f.controller.updateScope(f.key())
        runCurrent()
        assertNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value))
    }

    @Test
    fun addingAndRevokingRootsExpiresTheOldClipboardAndConfirmationWithoutConfirming() = runTest {
        val f = Fixture(this)
        val first = folder("first")
        val second = folder("second")
        f.setLocalFolders(listOf(first))
        f.tree["$ROOT/b"] = listOf(dir("a"))
        f.show()
        for (roots in listOf(listOf(first, second), listOf(second))) {
            f.controller.copy("$ROOT/a", "a", cut = true)
            f.controller.paste("$ROOT/b") {}
            runCurrent()
            assertNotNull(f.controller.clipboard.value)
            assertEquals("$ROOT/a" to "$ROOT/b/a", f.controller.pasteConflict.value)
            f.setLocalFolders(roots)
            f.controller.updateScope(f.key())
            f.controller.confirmOverwrite()
            runCurrent()
            assertNull(f.controller.clipboard.value)
            assertNull(f.controller.pasteConflict.value)
            assertTrue(f.confirmedTickets.isEmpty())
            assertEquals(0, f.pastes)
        }
        assertFalse(first.rootPath in f.paths())
        assertTrue(second.rootPath in f.paths())
        coVerify(exactly = 0) { f.transfers.confirm(any(), any(), any()) }
    }

    @Test
    fun revokingAnExpandedRootRemovesItsRowsHostWatchAndOldPickerToken() = runTest {
        val f = Fixture(this)
        val root = folder("revoked")
        f.holder.setMode(ExecutionMode.REMOTE_SSH)
        f.setLocalFolders(listOf(root))
        f.tree[root.rootPath] = listOf(file("old"))
        f.show()
        f.open(root.rootPath)
        val target = checkNotNull(f.controller.prepareUpload(root.rootPath, f.selectionRevisions.value))
        assertEquals(setOf(root.rootPath), f.localWatching)
        f.setLocalFolders(emptyList())
        f.controller.updateScope(f.key())
        runCurrent()
        val before = f.calls.toMap()
        assertFalse(f.paths().any { it == root.rootPath || it.startsWith("${root.rootPath}/") })
        assertTrue(f.localWatching.isEmpty())
        assertTrue(f.localSignals.getValue(root.rootPath).tryEmit(Unit))
        f.controller.upload(target, listOf(mockk<Uri>()), directory = false)
        runCurrent()
        assertEquals(before, f.calls)
        assertEquals(listOf(BrowseOperationNotice.INVALID_SELECTION), f.notices)
        coVerify(exactly = 0) { f.transfers.uploadFiles(any(), any(), any(), any()) }
    }

    @Test
    fun uploadConflictsAreConfirmedOneCurrentImmutableTicketAtATime() = runTest {
        val f = Fixture(this)
        val uris = listOf(mockk<Uri>(), mockk<Uri>())
        val tickets = mutableListOf<TransferConfirmation>()
        coEvery { f.transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
            val execution = firstArg<WorkspaceExecutionContext>()
            val checkScope = arg<() -> Unit>(3)
            assertSame(execution, WorkspaceExecutionContext.current())
            assertEquals(uris, thirdArg<List<Uri>>())
            checkScope()
            listOf("one", "two").map { name ->
                val source = "content://fixture/$name"
                val target = "$ROOT/$name"
                val ticket = f.confirmation(execution, source, target, false, checkScope) {
                    TransferResult.Completed(source, target, false)
                }
                tickets += ticket
                TransferResult.Conflict(ticket)
            }
        }
        f.show()
        val target = checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value))
        f.controller.upload(target, uris, directory = false)
        runCurrent()
        assertEquals(2, tickets.size)
        assertEquals(tickets[0].source to tickets[0].target, f.controller.pasteConflict.value)
        assertTrue(f.confirmedTickets.isEmpty())
        assertNull(f.controller.clipboard.value)
        assertFalse(f.controller.transferActive.value)

        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(listOf(tickets[0]), f.confirmedTickets)
        assertEquals(tickets[1].source to tickets[1].target, f.controller.pasteConflict.value)
        coVerify(exactly = 1) { f.transfers.confirm(any(), tickets[0], any()) }
        coVerify(exactly = 0) { f.transfers.confirm(any(), tickets[1], any()) }
        assertTrue(f.notices.isEmpty())
        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(tickets, f.confirmedTickets)
        assertNull(f.controller.pasteConflict.value)
        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(tickets, f.confirmedTickets)
        assertEquals(listOf(BrowseOperationNotice.UPLOAD_COMPLETED), f.notices)
        coVerify(exactly = 1) { f.transfers.confirm(any(), tickets[1], any()) }
    }

    @Test
    fun clearingUploadConflictCancelsTheEntireRemainingQueue() = runTest {
        val f = Fixture(this)
        val tickets = mutableListOf<TransferConfirmation>()
        coEvery { f.transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
            val execution = firstArg<WorkspaceExecutionContext>()
            val checkScope = arg<() -> Unit>(3)
            (1..3).map { index ->
                val source = "content://fixture/$index"
                val target = "$ROOT/$index"
                val ticket = f.confirmation(execution, source, target, false, checkScope) {
                    TransferResult.Completed(source, target, false)
                }
                tickets += ticket
                TransferResult.Conflict(ticket)
            }
        }
        f.show()
        val target = checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value))
        f.controller.upload(target, List(3) { mockk<Uri>() }, directory = false)
        runCurrent()
        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(listOf(tickets[0]), f.confirmedTickets)
        assertEquals(tickets[1].source to tickets[1].target, f.controller.pasteConflict.value)
        f.controller.clearConflict()
        f.controller.confirmOverwrite()
        f.controller.confirmOverwrite()
        runCurrent()
        assertNull(f.controller.pasteConflict.value)
        assertEquals(listOf(tickets[0]), f.confirmedTickets)
        coVerify(exactly = 0) { f.transfers.confirm(any(), tickets[1], any()) }
        coVerify(exactly = 0) { f.transfers.confirm(any(), tickets[2], any()) }
        assertTrue(f.notices.isEmpty())
    }

    @Test
    fun cancelTransferDiscardsUploadConflictQueueWithoutConfirmingAnyTicket() = runTest {
        val f = Fixture(this)
        coEvery { f.transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
            val execution = firstArg<WorkspaceExecutionContext>()
            val checkScope = arg<() -> Unit>(3)
            listOf("one", "two").map { name ->
                val source = "content://fixture/$name"
                val target = "$ROOT/$name"
                TransferResult.Conflict(f.confirmation(execution, source, target, false, checkScope) {
                    TransferResult.Completed(source, target, false)
                })
            }
        }
        f.show()
        f.controller.upload(checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value)),
            listOf(mockk<Uri>(), mockk<Uri>()), directory = false)
        runCurrent()
        assertNotNull(f.controller.pasteConflict.value)
        f.controller.cancelTransfer()
        f.controller.confirmOverwrite()
        runCurrent()
        assertNull(f.controller.pasteConflict.value)
        assertTrue(f.confirmedTickets.isEmpty())
        assertTrue(f.notices.isEmpty())
        coVerify(exactly = 0) { f.transfers.confirm(any(), any(), any()) }
    }

    @Test
    fun directoryPickerUploadsExactlyOneUriUsingTheCapturedTarget() = runTest {
        val f = Fixture(this)
        val uri = mockk<Uri>()
        coEvery { f.transfers.uploadDirectory(any(), any(), any(), any()) } coAnswers {
            val execution = firstArg<WorkspaceExecutionContext>()
            assertSame(execution, WorkspaceExecutionContext.current())
            assertEquals("$ROOT/b", secondArg<String>())
            assertSame(uri, thirdArg<Uri>())
            execution.checkCurrent()
            arg<() -> Unit>(3).invoke()
            f.tree["$ROOT/b"] = listOf(dir("picked"))
            f.tree["$ROOT/b/picked"] = listOf(file("uploaded"))
            TransferResult.Completed("content://fixture/picked", "$ROOT/b/picked", false)
        }
        f.show()
        f.open("$ROOT/b")
        val target = checkNotNull(f.controller.prepareUpload("$ROOT/b", f.selectionRevisions.value))
        f.controller.upload(target, listOf(uri), directory = true)
        runCurrent()
        assertTrue("$ROOT/b/picked" in f.paths())
        assertEquals(listOf(BrowseOperationNotice.UPLOAD_COMPLETED), f.notices)
        assertNull(f.controller.pasteConflict.value)
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        coVerify(exactly = 1) { f.transfers.uploadDirectory(any(), "$ROOT/b", uri, any()) }
        coVerify(exactly = 0) { f.transfers.uploadFiles(any(), any(), any(), any()) }
    }

    @Test
    fun invalidMultipleDirectorySelectionAndEmptyFileSelectionNeverReachTheTransferService() = runTest {
        val f = Fixture(this)
        f.show()
        val target = checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value))
        f.controller.upload(target, emptyList(), directory = false)
        runCurrent()
        assertTrue(f.notices.isEmpty())
        f.controller.upload(target, listOf(mockk<Uri>(), mockk<Uri>()), directory = true)
        runCurrent()
        assertEquals(listOf(BrowseOperationNotice.INVALID_SELECTION), f.notices)
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        coVerify(exactly = 0) { f.transfers.uploadFiles(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.transfers.uploadDirectory(any(), any(), any(), any()) }
    }

    @Test
    fun mixedUploadResultsKeepCompletedRowsAndDoNotReportBatchSuccessAfterAConfirmedConflict() = runTest {
        val f = Fixture(this)
        val tickets = mutableListOf<TransferConfirmation>()
        coEvery { f.transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
            val execution = firstArg<WorkspaceExecutionContext>()
            val checkScope = arg<() -> Unit>(3)
            checkScope()
            execution.checkCurrent()
            f.tree[ROOT] = f.tree.getValue(ROOT) + file("completed")
            val source = "content://fixture/conflict"
            val target = "$ROOT/conflict"
            val ticket = f.confirmation(execution, source, target, false, checkScope) {
                f.tree[ROOT] = f.tree.getValue(ROOT) + file("conflict")
                TransferResult.Completed(source, target, false)
            }
            tickets += ticket
            listOf(TransferResult.Completed("content://fixture/completed", "$ROOT/completed", false),
                TransferResult.Conflict(ticket), TransferResult.Failed("permission denied"))
        }
        f.show()
        f.controller.upload(checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value)),
            List(3) { mockk<Uri>() }, directory = false)
        runCurrent()
        assertTrue("$ROOT/completed" in f.paths())
        assertEquals(listOf(BrowseOperationNotice.FAILED), f.notices)
        assertEquals(listOf("permission denied"), f.reportedFailures.map { it.message })
        assertEquals(tickets.single().source to tickets.single().target, f.controller.pasteConflict.value)
        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(tickets, f.confirmedTickets)
        assertTrue("$ROOT/completed" in f.paths())
        assertTrue("$ROOT/conflict" in f.paths())
        assertNull(f.controller.pasteConflict.value)
        assertEquals(listOf(BrowseOperationNotice.FAILED), f.notices)
        assertFalse(f.controller.transferActive.value)
    }

    @Test
    fun directoryConflictDoesNotOfferOverwriteOrDiscardTheCutSource() = runTest {
        val f = Fixture(this)
        coEvery { f.transfers.transfer(any(), any(), any(), any(), any(), any()) } returns
            TransferResult.DirectoryConflict("$ROOT/a", "$ROOT/b/a")
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        f.open("$ROOT/b")
        val before = f.controller.expanded.value
        val results = mutableListOf<Boolean>()
        f.controller.copy("$ROOT/a", "a", cut = true)
        f.controller.paste("$ROOT/b") { results += it }
        runCurrent()
        assertEquals(listOf(false), results)
        assertEquals(listOf(BrowseOperationNotice.DIRECTORY_CONFLICT), f.notices)
        assertEquals(before, f.controller.expanded.value)
        assertEquals("$ROOT/a", f.controller.clipboard.value?.sourcePath)
        assertNull(f.controller.pasteConflict.value)
        assertTrue("$ROOT/a/deep/leaf" in f.paths())
        assertEquals(0, f.pastes)
        coVerify(exactly = 0) { f.transfers.confirm(any(), any(), any()) }
    }

    @Test
    fun partiallyCompletedCutKeepsSourceExpansionAndReportsTheSourceRemainder() = runTest {
        val f = Fixture(this)
        coEvery { f.transfers.transfer(any(), any(), any(), any(), any(), any()) } coAnswers {
            firstArg<WorkspaceExecutionContext>().checkCurrent()
            arg<() -> Unit>(5).invoke()
            f.tree["$ROOT/b"] = listOf(dir("a"))
            f.tree["$ROOT/b/a"] = listOf(file("copied"))
            TransferResult.CopiedButSourceNotRemoved("$ROOT/a", "$ROOT/b/a", "source removal was partial")
        }
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        f.open("$ROOT/b")
        val before = f.controller.expanded.value
        val results = mutableListOf<Boolean>()
        f.controller.copy("$ROOT/a", "a", cut = true)
        f.controller.paste("$ROOT/b") { results += it }
        runCurrent()
        assertEquals(listOf(false), results)
        assertEquals(before, f.controller.expanded.value)
        assertEquals(before, f.saved["/work"])
        assertTrue("$ROOT/a/deep/leaf" in f.paths())
        assertTrue("$ROOT/b/a" in f.paths())
        assertFalse("$ROOT/b/a" in f.controller.expanded.value)
        assertFalse("$ROOT/b/a/deep" in f.controller.expanded.value)
        assertNull(f.controller.clipboard.value)
        assertEquals(listOf(BrowseOperationNotice.COPIED_SOURCE_REMAINS), f.notices)
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        assertEquals(0, f.pastes)
    }

    @Test
    fun cancellationAfterCutCommitStillReportsPartialWithoutRelocatingSource() = runTest {
        val f = Fixture(this)
        coEvery { f.transfers.transfer(any(), any(), any(), any(), any(), any()) } coAnswers {
            f.tree["$ROOT/b"] = listOf(dir("a"))
            f.tree["$ROOT/b/a"] = listOf(file("committed"))
            throw TransferCancellationException(
                TransferResult.CopiedButSourceNotRemoved("$ROOT/a", "$ROOT/b/a", "cancelled after commit"),
                CancellationException("cancelled")
            )
        }
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        f.open("$ROOT/b")
        val before = f.controller.expanded.value
        val results = mutableListOf<Boolean>()
        f.controller.copy("$ROOT/a", "a", cut = true)
        f.controller.paste("$ROOT/b") { results += it }
        runCurrent()
        assertEquals(listOf(false), results)
        assertEquals(before, f.controller.expanded.value)
        assertTrue("$ROOT/a/deep/leaf" in f.paths())
        assertTrue("$ROOT/b/a" in f.paths())
        assertNull(f.controller.clipboard.value)
        assertEquals(listOf(BrowseOperationNotice.COPIED_SOURCE_REMAINS), f.notices)
        assertTrue(f.reportedFailures.isEmpty())
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
    }

    @Test
    fun cancelTransferCancelsOnlyTheTransferAndPreservesQueuedOrdinaryCreate() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { f.transfers.transfer(any(), any(), any(), any(), any(), any()) } coAnswers {
            firstArg<WorkspaceExecutionContext>().checkCurrent()
            arg<() -> Unit>(5).invoke()
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        f.show()
        val pasteResults = mutableListOf<Boolean>()
        val createResults = mutableListOf<Boolean>()
        f.controller.copy("$ROOT/a", "a", cut = false)
        f.controller.paste("$ROOT/b") { pasteResults += it }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertTrue(f.controller.transferActive.value)
        f.controller.upload(checkNotNull(f.controller.prepareUpload("$ROOT/queued", f.selectionRevisions.value)),
            listOf(mockk<Uri>()), directory = false)
        f.controller.create(ROOT, "ordinary", directory = false) { createResults += it }
        runCurrent()
        assertTrue("$ROOT/ordinary" in f.controller.fileOpPaths.value)
        f.controller.cancelTransfer()
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertTrue(pasteResults.isEmpty())
        assertEquals(listOf(true), createResults)
        assertEquals(1, f.writes)
        assertTrue("$ROOT/ordinary" in f.paths())
        assertTrue("$ROOT/a" in f.paths())
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        assertTrue(f.notices.isEmpty())
        coVerify(exactly = 1) { f.transfers.transfer(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.transfers.uploadFiles(any(), any(), any(), any()) }
    }

    @Test
    fun cancelTransferDoesNotCancelAnAlreadyRunningOrdinaryCreate() = runTest {
        val f = Fixture(this, Dispatchers.IO)
        f.controller.setScope(f.key())
        f.subscribe()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { f.controller.state.first { it is FileBrowseState.Success } }
        }
        val entered = CompletableDeferred<Job>()
        val release = CountDownLatch(1)
        val completed = CompletableDeferred<Boolean>()
        f.onExists = { path ->
            if (path == "$ROOT/ordinary") {
                entered.complete(checkNotNull(WorkspaceExecutionContext.blockingContext()[Job]))
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            f.controller.create(ROOT, "ordinary", directory = false) { completed.complete(it) }
            runCurrent()
            val operation = withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            f.controller.cancelTransfer()
            runCurrent()
            assertTrue(operation.isActive)
            assertEquals(setOf("$ROOT/ordinary"), f.controller.fileOpPaths.value)
            assertFalse(f.controller.transferActive.value)
            release.countDown()
            assertTrue(withContext(Dispatchers.Default) { withTimeout(5_000) { completed.await() } })
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    f.controller.state.first { state ->
                        state is FileBrowseState.Success && state.nodes.any { it.path == "$ROOT/ordinary" }
                    }
                }
            }
            assertEquals(1, f.writes)
            assertTrue(f.notices.isEmpty())
            assertTrue(f.controller.fileOpPaths.value.isEmpty())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun scopeResetDoesNotPublishOldNonCancellableUploadResultsOrConfirmation() = runTest {
        val f = Fixture(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        coEvery { f.transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
            val execution = firstArg<WorkspaceExecutionContext>()
            val checkScope = arg<() -> Unit>(3)
            val source = "content://fixture/late"
            val target = "$ROOT/late"
            val ticket = f.confirmation(execution, source, target, false, checkScope) {
                TransferResult.Completed(source, target, false)
            }
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            returned.complete(Unit)
            listOf(TransferResult.Completed(source, target, false), TransferResult.Conflict(ticket))
        }
        f.show()
        f.controller.upload(checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value)),
            listOf(mockk<Uri>()), directory = false)
        runCurrent()
        assertTrue(entered.isCompleted)
        assertTrue(f.controller.transferActive.value)
        f.workspace = "/new-workspace"
        f.tree[ROOT] = listOf(file("new-only"))
        f.controller.setScope(f.key())
        runCurrent()
        val current = f.controller.state.value
        assertTrue("$ROOT/new-only" in f.paths())
        assertFalse(f.controller.transferActive.value)
        release.complete(Unit)
        runCurrent()
        assertTrue(returned.isCompleted)
        assertSame(current, f.controller.state.value)
        assertFalse("$ROOT/late" in f.paths())
        assertNull(f.controller.pasteConflict.value)
        assertNull(f.controller.clipboard.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        assertTrue(f.notices.isEmpty())
        assertTrue(f.reportedFailures.isEmpty())
        coVerify(exactly = 0) { f.transfers.confirm(any(), any(), any()) }
    }

    @Test
    fun uploadSelectionChangeBeforeCompletionCannotPublishSuccessWhileScopeHasNotResampled() = runTest {
        val f = Fixture(this)
        coEvery { f.transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
            f.selectionRevisions.value++
            listOf(TransferResult.Completed("content://fixture/file", "$ROOT/file", false))
        }
        f.show()
        f.controller.upload(checkNotNull(f.controller.prepareUpload(ROOT, f.selectionRevisions.value)),
            listOf(mockk<Uri>()), directory = false)
        runCurrent()
        assertFalse("$ROOT/file" in f.paths())
        assertTrue(f.notices.isEmpty())
        assertNull(f.controller.pasteConflict.value)
        assertFalse(f.controller.transferActive.value)
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
        coVerify(exactly = 1) { f.transfers.uploadFiles(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.transfers.confirm(any(), any(), any()) }
    }

    companion object {
        private const val ROOT = WorkspacePathMapper.CONTAINER_ROOT
        private fun folder(id: String) = LocalFolderGrant(id, "epoch-$id", "Phone $id", localRootPath(id, "epoch-$id"), true)
        private fun dir(name: String) = FileEntry(name, true, 0, 0)
        private fun file(name: String) = FileEntry(name, false, 1, 1)
    }
}
