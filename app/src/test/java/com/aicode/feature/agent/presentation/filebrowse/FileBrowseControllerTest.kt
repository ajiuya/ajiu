package com.aicode.feature.agent.presentation.filebrowse

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.presentation.BrowseOperationNotice
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.agent.presentation.FileTreeRoot
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.DelegatingFileAccess
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.FileTransferService
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.RouteCapture
import com.aicode.feature.workspace.domain.TransferConfirmation
import com.aicode.feature.workspace.domain.TransferResult
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isLocalFolderPath
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowseControllerTest {
    @Test
    fun expandingReadsOnlyThatDirectoryAndCollapsingOnlyReordersMemory() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        assertEquals(mapOf(ROOT to 1, "$ROOT/a" to 1, "$ROOT/a/deep" to 1), f.calls)
        f.controller.toggle("$ROOT/a")
        runCurrent()
        assertEquals(3, f.calls.values.sum())
        assertEquals(1, f.controller.cachedDirectoryCount)
        assertEquals(setOf(ROOT), f.watching)
        assertEquals(setOf(ROOT), f.controller.expanded.value)
        assertFalse(f.paths().any { it.startsWith("$ROOT/a/") })
        assertEquals(setOf(ROOT), f.saved["/work"])
    }

    @Test
    fun reopeningACachedDirectoryStillRechecksOnlyItsOwnListing() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.controller.toggle("$ROOT/a")
        runCurrent()
        f.tree["$ROOT/a"] = listOf(file("new"))
        f.open("$ROOT/a")
        assertEquals(1, f.calls[ROOT])
        assertEquals(2, f.calls["$ROOT/a"])
        assertTrue("$ROOT/a/new" in f.paths())
    }

    @Test
    fun dirtyDirectoryDoesNotRelistSiblingsOrRereadRootGitignore() = runTest {
        val f = Fixture(this)
        f.gitignore = "*.tmp"
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/b")
        val before = f.calls.toMap()
        f.tree["$ROOT/a"] = listOf(file("fresh.tmp"))
        f.signal("$ROOT/a")
        runCurrent()
        assertEquals(before[ROOT], f.calls[ROOT])
        assertEquals(before["$ROOT/b"], f.calls["$ROOT/b"])
        assertEquals(before.getValue("$ROOT/a") + 1, f.calls["$ROOT/a"])
        assertEquals(1, f.ignoreReads)
        assertTrue(f.node("$ROOT/a/fresh.tmp").ignored)
    }

    @Test
    fun rootInvalidationReloadsGitignoreAndRestylesCachedChildrenWithoutReadingThem() = runTest {
        val f = Fixture(this)
        f.gitignore = "old.txt"
        f.tree["$ROOT/a"] = listOf(file("old.txt"), file("new.txt"))
        f.show()
        f.open("$ROOT/a")
        assertTrue(f.node("$ROOT/a/old.txt").ignored)
        f.gitignore = "new.txt"
        f.signal(ROOT)
        runCurrent()
        assertEquals(2, f.ignoreReads)
        assertEquals(1, f.calls["$ROOT/a"])
        assertFalse(f.node("$ROOT/a/old.txt").ignored)
        assertTrue(f.node("$ROOT/a/new.txt").ignored)
    }

    @Test
    fun sortingDeduplicatesNamesAndKeepsTheWorkspaceAndContainerRoots() = runTest {
        val f = Fixture(this)
        f.tree[ROOT] = listOf(file("z"), dir("b"), file("Alpha"), dir("a"), file("Alpha"))
        f.tree["/"] = listOf(file("z"), dir("etc"), file("A"), file("A"))
        f.show()
        f.open("/")
        assertEquals(listOf(ROOT, "$ROOT/a", "$ROOT/b", "$ROOT/Alpha", "$ROOT/z", "/", "/etc", "/A", "/z"), f.paths())
        assertEquals(FileTreeRoot.Workspace, f.node(ROOT).root)
        assertEquals(FileTreeRoot.Container("Linux", "alpine", false), f.node("/").root)
        assertEquals(f.paths().size, f.paths().distinct().size)
    }

    @Test
    fun containerRootFailureIsAnErrorRowAndDoesNotReplaceTheWorkspaceTree() = runTest {
        val f = Fixture(this)
        f.failures += "/"
        f.show()
        f.open("/")
        assertTrue(f.node("/").hasError)
        assertTrue("$ROOT/a" in f.paths())
        assertEquals(1, f.calls[ROOT])
    }

    @Test
    fun initialWorkspaceFailureReportsErrorAndManualRefreshCanRecover() = runTest {
        val f = Fixture(this)
        f.failures += ROOT
        f.show()
        assertTrue(f.controller.state.value is FileBrowseState.Error)
        f.failures.clear()
        f.controller.refresh()
        runCurrent()
        assertTrue(f.controller.state.value is FileBrowseState.Success)
        assertEquals(2, f.calls[ROOT])
    }

    @Test
    fun failedRefreshPreservesCachedRowsExpandedDirectoriesAndIgnorePatterns() = runTest {
        val f = Fixture(this)
        f.gitignore = "*.tmp"
        f.tree["$ROOT/a"] = listOf(dir("deep"), file("keep.tmp"))
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        val before = f.paths()
        f.failures += setOf(ROOT, "$ROOT/a")
        f.controller.refresh()
        runCurrent()
        assertEquals(before, f.paths())
        assertTrue(f.node(ROOT).hasError)
        assertTrue(f.node("$ROOT/a").hasError)
        assertTrue("$ROOT/a/deep" in f.controller.expanded.value)
        assertTrue(f.node("$ROOT/a/keep.tmp").ignored)
    }

    @Test
    fun ignoreReadFailurePreservesPatternsAndOnlyConfirmedAbsenceClearsThem() = runTest {
        val f = Fixture(this)
        f.tree[ROOT] = listOf(file("keep.tmp"))
        f.gitignore = "*.tmp"
        f.show()
        f.ignoreFailure = true
        f.controller.refresh()
        runCurrent()
        assertTrue(f.node("$ROOT/keep.tmp").ignored)
        f.ignoreFailure = false
        f.gitignore = null
        f.controller.refresh()
        runCurrent()
        assertFalse(f.node("$ROOT/keep.tmp").ignored)
    }

    @Test
    fun onlySuccessfulParentListingPrunesMissingExpandedDescendantsAndTheirWatches() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        f.tree[ROOT] = listOf(dir("b"))
        f.failures += ROOT
        f.signal(ROOT)
        runCurrent()
        assertTrue("$ROOT/a/deep" in f.controller.expanded.value)
        f.failures.clear()
        f.signal(ROOT)
        runCurrent()
        assertEquals(setOf(ROOT), f.controller.expanded.value)
        assertEquals(setOf(ROOT), f.watching)
    }

    @Test
    fun persistedExpandedDescendantsAboveTheDirtyLimitDoNotRepeatAFailingParentRead() = runTest {
        val f = Fixture(this)
        val descendants = (0 until FileBrowseController.MAX_DIRTY_DIRECTORIES + 1).map { "$ROOT/a/child-$it" }
        f.saved["/work"] = setOf(ROOT, "$ROOT/a") + descendants
        f.tree["$ROOT/a"] = descendants.map { dir(it.substringAfterLast('/')) }
        f.failures += "$ROOT/a"
        var guardReached = false
        f.onList = {
            if (f.calls.values.sum() > 10) {
                guardReached = true
                f.controller.setScope(null)
            }
        }
        f.show()
        assertFalse(guardReached)
        assertEquals(mapOf(ROOT to 1, "$ROOT/a" to 1), f.calls)
        assertTrue(f.node("$ROOT/a").hasError)
        assertEquals(setOf(ROOT, "$ROOT/a") + descendants, f.controller.expanded.value)
        repeat(3) {
            f.controller.refresh()
            runCurrent()
        }
        assertFalse(guardReached)
        assertEquals(mapOf(ROOT to 4, "$ROOT/a" to 4), f.calls)
        assertTrue(f.node("$ROOT/a").hasError)
        assertEquals(0, f.controller.pendingDirectoryCount)
        assertFalse(f.controller.pendingFullRefresh)
        runCurrent()
        assertEquals(mapOf(ROOT to 4, "$ROOT/a" to 4), f.calls)
    }

    @Test
    fun profileChangeAtTheSameWorkspacePathIsolatesCacheAndWatches() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.profile.value = "other-profile"
        f.tree[ROOT] = listOf(file("other-profile-only"))
        f.controller.setScope(f.key())
        runCurrent()
        assertEquals(listOf(ROOT, "$ROOT/other-profile-only", "/"), f.paths())
        assertEquals(setOf(ROOT), f.watching)
        assertEquals(2, f.calls[ROOT])
    }

    @Test
    fun switchingModeAtTheSamePathUsesTheNewFrozenBackendAndDropsLocalWatches() = runTest {
        val f = Fixture(this)
        f.show()
        f.holder.setMode(ExecutionMode.REMOTE_SSH)
        f.tree[ROOT] = listOf(file("remote-only"))
        f.controller.setScope(f.key())
        runCurrent()
        assertTrue("$ROOT/remote-only" in f.paths())
        assertTrue(f.watching.isEmpty())
        assertEquals(listOf("local", "remote"), f.backends)
    }

    @Test
    fun reconnectAtTheSameRemotePathDropsTheOldGeneration() = runTest {
        val f = Fixture(this)
        f.holder.setMode(ExecutionMode.REMOTE_SSH)
        f.show()
        val first = f.key()
        f.remoteIdentity = RemoteConnectionIdentity(mockk(), "/home/test")
        f.tree[ROOT] = listOf(file("new-generation"))
        f.controller.setScope(f.key())
        runCurrent()
        assertFalse(first.fileIdentity == f.key().fileIdentity)
        assertTrue("$ROOT/new-generation" in f.paths())
        assertEquals(2, f.calls[ROOT])
    }

    @Test
    fun scopeRoundTripBeforeOwnerRunsStillClearsClipboardAndDoesOneFreshRead() = runTest {
        val f = Fixture(this)
        f.show()
        f.controller.copy("$ROOT/a", "a", cut = false)
        runCurrent()
        val original = f.key()
        f.controller.setScope(original.copy(profileId = "temporary"))
        f.controller.setScope(original)
        runCurrent()
        assertNull(f.controller.clipboard.value)
        assertEquals(2, f.calls[ROOT])
    }

    @Test
    fun invalidationBurstIsBoundedAndOverflowMergesIntoOneFullVisibleRefresh() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/b")
        val before = f.calls.toMap()
        repeat(20_000) { f.controller.invalidateDirectory("$ROOT/event-$it") }
        assertTrue(f.controller.pendingDirectoryCount <= FileBrowseController.MAX_DIRTY_DIRECTORIES)
        assertTrue(f.controller.pendingFullRefresh)
        runCurrent()
        assertEquals(before.mapValues { it.value + 1 }, f.calls)
    }

    @Test
    fun invalidationArrivingDuringReadRemainsDirtyForTheNextBatch() = runTest {
        val f = Fixture(this)
        f.show()
        var invalidated = false
        f.onList = { path ->
            if (path == ROOT && !invalidated) {
                invalidated = true
                repeat(500) { f.controller.invalidateDirectory(ROOT) }
            }
        }
        f.controller.refresh()
        runCurrent()
        assertEquals(3, f.calls[ROOT])
    }

    @Test
    fun noSubscriberMeansNoWatchOrScanAndResumeRechecksOnceAfterTheGracePeriod() = runTest {
        val f = Fixture(this)
        f.controller.setScope(f.key())
        runCurrent()
        assertTrue(f.calls.isEmpty())
        assertTrue(f.watching.isEmpty())
        f.show()
        f.hide()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(setOf(ROOT), f.watching)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(f.watching.isEmpty())
        val before = f.calls.toMap()
        repeat(500) { f.controller.refresh() }
        runCurrent()
        assertEquals(before, f.calls)
        f.tree[ROOT] = listOf(file("after-hide"))
        f.show(resetScope = false)
        assertEquals(2, f.calls[ROOT])
        assertTrue("$ROOT/after-hide" in f.paths())
    }

    @Test
    fun scopeChangeWhileHiddenImmediatelyDropsTheOldTreeWithoutDoingIo() = runTest {
        val f = Fixture(this)
        f.show()
        f.hide()
        advanceTimeBy(5_000)
        runCurrent()
        val before = f.calls.toMap()
        f.profile.value = "second"
        f.controller.setScope(f.key())
        runCurrent()
        assertEquals(FileBrowseState.Loading, f.controller.state.value)
        assertEquals(before, f.calls)
        assertTrue(f.watching.isEmpty())
        f.tree[ROOT] = listOf(file("second-only"))
        f.show(resetScope = false)
        assertTrue("$ROOT/second-only" in f.paths())
    }

    @Test
    fun persistedCollapsedRootsRenderWithoutListingAndRefreshDoesNotOpenThem() = runTest {
        val f = Fixture(this)
        f.saved["/work"] = emptySet()
        f.show()
        assertEquals(listOf(ROOT, "/"), f.paths())
        assertTrue(f.calls.isEmpty())
        f.controller.refresh()
        runCurrent()
        assertTrue(f.calls.isEmpty())
        assertEquals(0, f.ignoreReads)
        f.open("/")
        assertEquals(mapOf("/" to 1), f.calls)
        assertEquals(0, f.ignoreReads)
        f.open(ROOT)
        assertEquals(1, f.calls[ROOT])
    }

    @Test
    fun collapsingRootsPrunesDescendantCachesButKeepsTheirOwnFailureFallback() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        f.open("/")
        assertEquals(4, f.controller.cachedDirectoryCount)
        f.controller.toggle(ROOT)
        f.controller.toggle("/")
        runCurrent()
        assertEquals(listOf(ROOT, "/"), f.paths())
        assertEquals(2, f.controller.cachedDirectoryCount)
        val before = f.calls.toMap()
        f.controller.refresh()
        runCurrent()
        assertEquals(before, f.calls)
        f.failures += ROOT
        f.open(ROOT)
        assertTrue("$ROOT/a" in f.paths())
        assertTrue(f.node(ROOT).hasError)
        assertEquals(2, f.controller.cachedDirectoryCount)
    }

    @Test
    fun completedWritesWhileHiddenOnlyMarkParentsDirtyUntilTheNextSubscription() = runTest {
        val f = Fixture(this)
        f.show()
        f.hide()
        advanceTimeBy(5_000)
        runCurrent()
        val before = f.calls.toMap()
        val results = mutableListOf<Boolean>()
        f.controller.create(ROOT, "first", directory = false) { results += it }
        f.controller.create(ROOT, "second", directory = false) { results += it }
        runCurrent()
        assertEquals(listOf(true, true), results)
        assertEquals(2, f.writes)
        assertEquals(before, f.calls)
        assertTrue(f.watching.isEmpty())
        f.show(resetScope = false)
        assertTrue("$ROOT/first" in f.paths())
        assertTrue("$ROOT/second" in f.paths())
        assertEquals(before.getValue(ROOT) + 1, f.calls[ROOT])
    }

    @Test
    fun labelAndOsChangesOnlyReplaceRootMetadata() = runTest {
        val f = Fixture(this)
        f.show()
        val before = f.calls.toMap()
        val root = FileTreeRoot.Container("Renamed", "debian", false)
        f.controller.setRoot(root)
        runCurrent()
        assertEquals(root, f.node("/").root)
        assertEquals(before, f.calls)
    }

    @Test
    fun resamplingTheSameSourceAndChangingRootMetadataDoNotInvalidateTheCache() = runTest {
        val f = Fixture(this)
        f.show()
        val before = f.calls.toMap()
        f.profileName = "New label"
        f.controller.updateScope(f.key())
        f.controller.setRoot(FileTreeRoot.Container(f.profileName, "debian", false))
        runCurrent()
        assertEquals(before, f.calls)
        assertEquals("New label", f.node("/").entry.name)
    }

    @Test
    fun metadataChangeDuringInitialReadRetriesOnlyTheMissingDirectoryOnce() = runTest {
        val f = Fixture(this)
        var changed = false
        f.onList = {
            if (!changed) {
                changed = true
                f.profileName = "Renamed while reading"
                f.controller.updateScope(f.key())
                f.controller.setRoot(FileTreeRoot.Container(f.profileName, null, false))
            }
        }
        f.show()
        assertTrue(f.controller.state.value is FileBrowseState.Success)
        assertEquals(2, f.calls[ROOT])
        assertNull(f.controller.expandingPath.value)
    }

    @Test
    fun repeatedSameSourceCancellationIsBoundedAndDoesNotLeaveLoadingOrExpansionBusy() = runTest {
        val f = Fixture(this)
        var changes = 0
        f.onList = { f.profileName = "Changing label ${++changes}" }
        f.show()
        assertEquals(2, f.calls[ROOT])
        assertTrue(f.controller.state.value is FileBrowseState.Error)
        assertNull(f.controller.expandingPath.value)
        f.onList = {}
        f.controller.refresh()
        runCurrent()
        assertTrue(f.controller.state.value is FileBrowseState.Success)
        assertEquals(3, f.calls[ROOT])
    }

    @Test
    fun cachedDirectoryDirtyRefreshRetriesAfterSameSourceMetadataCancellation() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        val before = f.calls.toMap()
        val scope = f.key()
        f.tree["$ROOT/a"] = listOf(file("fresh"))
        var changed = false
        f.onList = { path ->
            if (path == "$ROOT/a" && !changed) {
                changed = true
                f.profileName = "Renamed during refresh"
                assertEquals(scope, f.key())
                f.controller.updateScope(f.key())
            }
        }
        f.controller.invalidateDirectory("$ROOT/a")
        runCurrent()
        assertEquals(before.getValue("$ROOT/a") + 2, f.calls["$ROOT/a"])
        assertEquals(before[ROOT], f.calls[ROOT])
        assertTrue("$ROOT/a/fresh" in f.paths())
        assertFalse(f.node("$ROOT/a").hasError)
    }

    @Test
    fun repeatedCachedRefreshCancellationKeepsRowsAndMarksErrorUntilManualRetry() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        val before = f.calls.toMap()
        var changes = 0
        f.onList = { path ->
            if (path == "$ROOT/a") f.profileName = "Changing during refresh ${++changes}"
        }
        f.tree["$ROOT/a"] = listOf(file("fresh"))
        f.controller.invalidateDirectory("$ROOT/a")
        runCurrent()
        assertEquals(before.getValue("$ROOT/a") + 2, f.calls["$ROOT/a"])
        assertEquals(before[ROOT], f.calls[ROOT])
        assertTrue("$ROOT/a/deep" in f.paths())
        assertFalse("$ROOT/a/fresh" in f.paths())
        assertTrue(f.node("$ROOT/a").hasError)
        assertNull(f.controller.expandingPath.value)
        runCurrent()
        assertEquals(before.getValue("$ROOT/a") + 2, f.calls["$ROOT/a"])
        f.onList = {}
        f.controller.refresh()
        runCurrent()
        assertTrue("$ROOT/a/fresh" in f.paths())
        assertFalse(f.node("$ROOT/a").hasError)
    }

    @Test
    fun createAndDeleteInvalidateOnlyTheAffectedParentAndRootPathsJoinCorrectly() = runTest {
        val f = Fixture(this)
        f.tree["/"] = emptyList()
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/b")
        f.open("/")
        var success: Boolean? = null
        f.controller.create("$ROOT/a", "fresh", directory = false) { success = it }
        runCurrent()
        assertEquals(true, success)
        assertTrue("$ROOT/a/fresh" in f.paths())
        assertEquals(1, f.calls[ROOT])
        assertEquals(1, f.calls["$ROOT/b"])
        f.controller.delete("$ROOT/a/fresh") { success = it }
        runCurrent()
        assertEquals(true, success)
        assertFalse("$ROOT/a/fresh" in f.paths())
        f.controller.create("/", "top", directory = true) { success = it }
        runCurrent()
        assertTrue("/top" in f.paths())
        f.controller.rename("/top", "renamed") { success = it }
        runCurrent()
        assertEquals(true, success)
        assertTrue("/renamed" in f.paths())
        assertFalse(f.paths().any { it.startsWith("//") })
    }

    @Test
    fun renameMigratesExpandedKeysCachedDescendantsWatchesAndPersistence() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        val before = f.calls.toMap()
        var success: Boolean? = null
        f.controller.rename("$ROOT/a", "renamed") { success = it }
        runCurrent()
        assertEquals(true, success)
        assertEquals(setOf(ROOT, "$ROOT/renamed", "$ROOT/renamed/deep"), f.controller.expanded.value)
        assertTrue("$ROOT/renamed/deep/leaf" in f.paths())
        assertEquals(f.controller.expanded.value, f.saved["/work"])
        assertEquals(f.controller.expanded.value, f.watching)
        assertEquals(before.getValue(ROOT) + 1, f.calls[ROOT])
        assertNull(f.calls["$ROOT/renamed"])
        assertEquals(before["$ROOT/a"], f.calls["$ROOT/a"])
    }

    @Test
    fun moveMigratesExpansionAndOnlyRelistsTheTwoParents() = runTest {
        val f = Fixture(this)
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/a/deep")
        f.open("$ROOT/b")
        val before = f.calls.toMap()
        f.controller.copy("$ROOT/a", "a", cut = true)
        f.controller.paste("$ROOT/b") {}
        runCurrent()
        assertEquals(setOf(ROOT, "$ROOT/b", "$ROOT/b/a", "$ROOT/b/a/deep"), f.controller.expanded.value)
        assertTrue("$ROOT/b/a/deep/leaf" in f.paths())
        assertEquals(before.getValue(ROOT) + 1, f.calls[ROOT])
        assertEquals(before.getValue("$ROOT/b") + 1, f.calls["$ROOT/b"])
        assertNull(f.calls["$ROOT/b/a"])
        assertNull(f.controller.clipboard.value)
        assertEquals(f.controller.expanded.value, f.saved["/work"])
    }

    @Test
    fun renameReplaysAnInterruptedDirtyChildRefreshAtItsNewPath() = runTest {
        verifyInterruptedRefreshRelocation(move = false)
    }

    @Test
    fun moveReplaysAnInterruptedDirtyChildRefreshAtItsNewPath() = runTest {
        verifyInterruptedRefreshRelocation(move = true)
    }

    private suspend fun TestScope.verifyInterruptedRefreshRelocation(move: Boolean) {
        val f = Fixture(this, Dispatchers.IO)
        suspend fun awaitPath(path: String) = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                f.controller.state.first { state ->
                    state is FileBrowseState.Success && state.nodes.any { it.path == path }
                }
            }
        }
        f.controller.setScope(f.key())
        f.subscribe()
        awaitPath("$ROOT/a")
        f.open("$ROOT/a")
        awaitPath("$ROOT/a/deep")
        f.open("$ROOT/a/deep")
        awaitPath("$ROOT/a/deep/leaf")
        f.open("$ROOT/b")
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { f.controller.expandingPath.first { it == null } }
        }
        val before = f.calls.toMap()
        val source = "$ROOT/a"
        val target = if (move) "$ROOT/b/a" else "$ROOT/renamed"
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean()
        f.onList = { path ->
            if (path == "$source/deep" && blocked.compareAndSet(false, true)) {
                val cancellation = checkNotNull(WorkspaceExecutionContext.current()).onCancellation { release.countDown() }
                try {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    cancellation?.dispose()
                }
            }
        }
        val duringRelocation = {
            f.tree["$source/deep"] = listOf(file("fresh"))
            f.controller.invalidateDirectory("$source/deep")
        }
        f.onRename = duringRelocation
        f.onPaste = duringRelocation
        try {
            f.controller.invalidateDirectory("$source/deep")
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            if (move) {
                f.controller.copy(source, "a", cut = true)
                f.controller.paste("$ROOT/b") {}
            } else f.controller.rename(source, "renamed") {}
            runCurrent()
            awaitPath("$target/deep/fresh")
            assertEquals(before.getValue("$source/deep") + 1, f.calls["$source/deep"])
            assertEquals(1, f.calls["$target/deep"])
            assertEquals(before["$source"], f.calls[source])
            assertEquals(before.getValue(ROOT) + 1, f.calls[ROOT])
            assertEquals(before.getValue("$ROOT/b") + if (move) 1 else 0, f.calls["$ROOT/b"])
            assertFalse("$source/deep/leaf" in f.paths())
            assertTrue("$target/deep" in f.controller.expanded.value)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun clipboardAndOverwriteConfirmationExpireAcrossWorkspaceProfileModeAndReconnect() = runTest {
        val f = Fixture(this)
        f.show()
        for (kind in listOf("workspace", "profile", "mode", "reconnect")) {
            f.tree["$ROOT/b"] = listOf(dir("a"))
            f.controller.copy("$ROOT/a", "a", cut = true)
            f.controller.paste("$ROOT/b") {}
            runCurrent()
            assertNotNull(f.controller.pasteConflict.value)
            when (kind) {
                "workspace" -> f.workspace = "/another-workspace"
                "profile" -> f.profile.value = "second"
                "mode" -> f.holder.setMode(ExecutionMode.REMOTE_SSH)
                "reconnect" -> f.remoteIdentity = RemoteConnectionIdentity(mockk(), "/home/test")
            }
            f.controller.setScope(f.key())
            f.controller.confirmOverwrite()
            runCurrent()
            assertNull(f.controller.clipboard.value)
            assertNull(f.controller.pasteConflict.value)
            assertEquals(0, f.pastes)
        }
    }

    @Test
    fun mountMappingChangeWithTheSameSourceIdentityExpiresClipboardAndOverwriteConfirmation() = runTest {
        val f = Fixture(this)
        f.extraBindings = listOf("/host/a:/mnt/project")
        f.tree["$ROOT/b"] = listOf(dir("a"))
        f.show()
        f.controller.copy("$ROOT/a", "a", cut = true)
        f.controller.paste("$ROOT/b") {}
        runCurrent()
        assertNotNull(f.controller.pasteConflict.value)
        val original = f.key()
        f.extraBindings = listOf("/host/b:/mnt/project")
        val next = f.key()
        assertEquals(original.fileIdentity, next.fileIdentity)
        assertEquals(original.profileId, next.profileId)
        assertFalse(original.localMapping == next.localMapping)
        f.tree[ROOT] = listOf(file("new-mapping"))
        f.controller.updateScope(next)
        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(0, f.pastes)
        assertNull(f.controller.clipboard.value)
        assertNull(f.controller.pasteConflict.value)
        assertTrue("$ROOT/new-mapping" in f.paths())
        assertFalse("$ROOT/a" in f.paths())
    }

    @Test
    fun frozenOperationRejectsANewMountMappingBeforeTheOwnerResamplesItsScope() = runTest {
        val f = Fixture(this)
        f.extraBindings = listOf("/host/a:/mnt/project")
        f.show()
        f.extraBindings = listOf("/host/b:/mnt/project")
        val results = mutableListOf<Boolean>()
        f.controller.create(ROOT, "must-not-write", directory = false) { results += it }
        runCurrent()
        assertTrue(results.isEmpty())
        assertEquals(0, f.writes)
        assertFalse("$ROOT/must-not-write" in f.paths())
    }

    @Test
    fun completingPasteDoesNotClearANewerClipboardSelection() = runTest {
        val f = Fixture(this)
        f.show()
        f.onPaste = {
            f.controller.copy("$ROOT/b", "b", cut = false)
        }
        f.controller.copy("$ROOT/a", "a", cut = false)
        f.controller.paste("$ROOT/b") {}
        runCurrent()
        assertEquals(1, f.pastes)
        assertEquals("$ROOT/b", f.controller.clipboard.value?.sourcePath)
    }

    @Test
    fun replacingClipboardInvalidatesAnEarlierOverwriteConfirmation() = runTest {
        val f = Fixture(this)
        f.tree["$ROOT/b"] = listOf(dir("a"))
        f.show()
        f.controller.copy("$ROOT/a", "a", cut = false)
        f.controller.paste("$ROOT/b") {}
        runCurrent()
        assertNotNull(f.controller.pasteConflict.value)
        f.controller.copy("$ROOT/b", "b", cut = false)
        f.controller.confirmOverwrite()
        runCurrent()
        assertEquals(0, f.pastes)
        assertEquals("$ROOT/b", f.controller.clipboard.value?.sourcePath)
    }

    @Test
    fun failedOverwriteKeepsClipboardAndExpansionButReconcilesPossiblePartialEffects() = runTest {
        val f = Fixture(this)
        f.tree["$ROOT/b"] = listOf(dir("a"))
        f.tree["$ROOT/b/a"] = listOf(file("old"))
        f.show()
        f.open("$ROOT/a")
        f.open("$ROOT/b")
        f.open("$ROOT/b/a")
        f.controller.copy("$ROOT/a", "a", cut = true)
        f.controller.paste("$ROOT/b") {}
        runCurrent()
        val before = f.calls.toMap()
        f.onPaste = {
            f.tree["$ROOT/b/a"] = listOf(file("partly-written"))
            throw IOException("partial overwrite")
        }
        f.controller.confirmOverwrite()
        runCurrent()
        assertNotNull(f.controller.clipboard.value)
        assertTrue("$ROOT/a" in f.controller.expanded.value)
        assertTrue("$ROOT/b/a/partly-written" in f.paths())
        assertEquals(before.getValue(ROOT) + 1, f.calls[ROOT])
        assertEquals(before.getValue("$ROOT/b") + 1, f.calls["$ROOT/b"])
        assertEquals(before.getValue("$ROOT/b/a") + 1, f.calls["$ROOT/b/a"])
    }

    @Test
    fun changingBackendInsideAWriteCannotRedirectItsNextCallOrReportCancellationAsFalse() = runTest {
        val f = Fixture(this)
        f.show()
        f.onExists = { path ->
            if (path == "$ROOT/new") f.holder.setMode(ExecutionMode.REMOTE_SSH)
        }
        val results = mutableListOf<Boolean>()
        f.controller.create(ROOT, "new", directory = false) { results += it }
        runCurrent()
        assertTrue(results.isEmpty())
        assertEquals(0, f.writes)
        assertFalse("$ROOT/new" in f.paths())
        assertTrue(f.controller.fileOpPaths.value.isEmpty())
    }

    @Test
    fun scopeRoundTripDuringFreezeRejectsTheOldWriteBeforeItsBlockStarts() = runTest {
        val f = Fixture(this)
        f.show()
        var changed = false
        f.onCapture = {
            if (!changed) {
                changed = true
                val original = f.key()
                f.controller.setScope(original.copy(profileId = "temporary"))
                f.controller.setScope(original)
            }
        }
        val results = mutableListOf<Boolean>()
        f.controller.create(ROOT, "must-not-write", directory = false) { results += it }
        runCurrent()
        assertTrue(changed)
        assertEquals(0, f.writes)
        assertTrue(results.isEmpty())
        assertFalse("$ROOT/must-not-write" in f.paths())
    }

    @Test
    fun lateBlockingReadCannotPublishIntoANewScopeAndDoesNotDelayItsLoad() = runTest {
        val f = Fixture(this, Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val returned = CompletableDeferred<Unit>()
        val blocked = AtomicBoolean()
        f.onList = { path ->
            if (path == ROOT && blocked.compareAndSet(false, true)) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                returned.complete(Unit)
            }
        }
        f.controller.setScope(f.key())
        f.subscribe()
        try {
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            f.profile.value = "new-profile"
            f.tree[ROOT] = listOf(file("new-only"))
            f.controller.setScope(f.key())
            runCurrent()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { f.controller.state.first { it is FileBrowseState.Success } }
            }
            assertTrue("$ROOT/new-only" in f.paths())
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { returned.await() } }
            runCurrent()
            assertTrue("$ROOT/new-only" in f.paths())
            assertFalse("$ROOT/a" in f.paths())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun manualRefreshKeepsThePreviousSnapshotAndUnsubscriptionCancelsItsBlockedIo() = runTest {
        val f = Fixture(this, Dispatchers.IO)
        f.controller.setScope(f.key())
        f.subscribe()
        val previous = withContext(Dispatchers.Default) {
            withTimeout(5_000) { f.controller.state.first { it is FileBrowseState.Success } }
        }
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean()
        f.onList = { path ->
            if (path == ROOT && blocked.compareAndSet(false, true)) {
                val lease = checkNotNull(WorkspaceExecutionContext.current()).onCancellation {
                    cancelled.complete(Unit)
                    release.countDown()
                }
                try {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    lease?.dispose()
                }
            }
        }
        try {
            f.controller.refresh()
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            assertSame(previous, f.controller.state.value)
            f.hide()
            advanceTimeBy(5_000)
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { cancelled.await() } }
            runCurrent()
            assertTrue(f.watching.isEmpty())
            assertSame(previous, f.controller.state.value)
            f.tree[ROOT] = listOf(file("resumed"))
            f.show(resetScope = false)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    f.controller.state.first { state ->
                        state is FileBrowseState.Success && state.nodes.any { it.path == "$ROOT/resumed" }
                    }
                }
            }
            assertEquals(3, f.calls[ROOT])
        } finally {
            release.countDown()
        }
    }

    @Test
    fun oldWriteCleanupCannotClearANewScopeWriteOrExpansion() = runTest {
        val f = Fixture(this, Dispatchers.IO)
        f.controller.setScope(f.key())
        f.subscribe()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { f.controller.state.first { it is FileBrowseState.Success } }
        }
        f.controller.copy("$ROOT/a", "a", cut = false)
        runCurrent()
        val oldEntered = CompletableDeferred<Job>()
        val oldRelease = CountDownLatch(1)
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CountDownLatch(1)
        val newCompleted = CompletableDeferred<Boolean>()
        var switched = false
        f.onPaste = {
            switched = true
            oldEntered.complete(checkNotNull(WorkspaceExecutionContext.blockingContext()[Job]))
            check(oldRelease.await(5, TimeUnit.SECONDS))
        }
        f.onExists = { path ->
            if (path == "$ROOT/next") {
                newEntered.complete(Unit)
                check(newRelease.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            f.controller.paste("$ROOT/b") {}
            runCurrent()
            val oldIo = withContext(Dispatchers.Default) { withTimeout(5_000) { oldEntered.await() } }
            f.profile.value = "new-profile"
            f.controller.setScope(f.key())
            f.controller.create(ROOT, "next", directory = false) { newCompleted.complete(it) }
            f.controller.toggle("$ROOT/a")
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { newEntered.await() } }
            assertTrue("$ROOT/next" in f.controller.fileOpPaths.value)
            assertEquals("$ROOT/a", f.controller.expandingPath.value)
            assertTrue("$ROOT/a" in f.controller.expanded.value)

            oldRelease.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { oldIo.join() } }
            runCurrent()
            assertTrue("$ROOT/next" in f.controller.fileOpPaths.value)
            assertEquals("$ROOT/a", f.controller.expandingPath.value)
            assertTrue("$ROOT/a" in f.controller.expanded.value)

            newRelease.countDown()
            assertTrue(withContext(Dispatchers.Default) { withTimeout(5_000) { newCompleted.await() } })
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    f.controller.state.first { state ->
                        state is FileBrowseState.Success && state.nodes.any { it.path == "$ROOT/a/deep" } &&
                            state.nodes.any { it.path == "$ROOT/next" }
                    }
                }
            }
            assertTrue(switched)
            assertTrue("$ROOT/next" in f.paths())
            assertNull(f.controller.clipboard.value)
            assertTrue(f.controller.fileOpPaths.value.isEmpty())
            assertEquals(1, f.writes)
        } finally {
            oldRelease.countDown()
            newRelease.countDown()
        }
    }

    internal class Fixture(val test: TestScope, io: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler)) {
        val holder = ExecutionModeHolder()
        val profile = MutableStateFlow("profile-one")
        val selectionRevisions = MutableStateFlow(0L)
        private val configurationRevisions = MutableStateFlow(0L)
        private val settingsIdentity = Any()
        val localFolderSnapshots = MutableStateFlow(LocalFolderSnapshot.EMPTY)
        var profileName = "Linux"
        var extraBindings = emptyList<String>()
        var workspace = "/work"
        var remoteIdentity = RemoteConnectionIdentity(mockk(), "/home/test")
        val tree = ConcurrentHashMap<String, List<FileEntry>>(mapOf(
            ROOT to listOf(dir("a"), dir("b")),
            "$ROOT/a" to listOf(dir("deep")),
            "$ROOT/a/deep" to listOf(file("leaf")),
            "$ROOT/b" to emptyList(),
            "/" to listOf(dir("etc"))
        ))
        val failures = ConcurrentHashMap.newKeySet<String>()
        val calls = ConcurrentHashMap<String, Int>()
        val backends: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val watching = mutableSetOf<String>()
        val signals = mutableMapOf<String, MutableSharedFlow<Unit>>()
        val localWatching = mutableSetOf<String>()
        val localSignals = mutableMapOf<String, MutableSharedFlow<Unit>>()
        val saved = mutableMapOf<String, Set<String>>()
        val notices = mutableListOf<BrowseOperationNotice>()
        val reportedFailures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
        val confirmedTickets = mutableListOf<TransferConfirmation>()
        var gitignore: String? = null
        var ignoreReads = 0
        var ignoreFailure = false
        var pastes = 0
        var writes = 0
        var onCapture: () -> Unit = {}
        var onList: (String) -> Unit = {}
        var onExists: (String) -> Unit = {}
        var onPaste: () -> Unit = {}
        var onRename: () -> Unit = {}
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        val host = mockk<FileAccessProvider>()
        private val settings = mockk<ContainerSettingsRepository>()
        private val grants = mockk<LocalFolderGrantRepository>()
        private val access = spyk(DelegatingFileAccess(holder, local, remote, grants))
        private val factory = WorkspaceExecutionContextFactory(access, holder, local, remote, settings, grants)
        val routing = mockk<FileBackendRouting>()
        val transfers = mockk<FileTransferService>()
        private val confirmations = mutableMapOf<TransferConfirmation, FixtureConfirmation>()
        private var collection: Job? = null
        val controller: FileBrowseController

        init {
            every { settings.activeProfileIdFlow } returns profile
            every { settings.activeProfileSelectionRevision } answers { selectionRevisions.value }
            every { settings.activeProfileSelectionRevisions } returns selectionRevisions
            every { settings.profileConfigurationRevision } answers { configurationRevisions.value }
            every { settings.profileConfigurationRevisions } returns configurationRevisions
            coEvery { settings.captureProfileSnapshot() } coAnswers {
                ContainerProfileSnapshot(profile.value, profile.value, listOf(currentProfile()),
                    selectionRevisions.value, configurationRevisions.value, settingsIdentity)
            }
            every { settings.checkProfileSnapshot(any()) } answers {
                val snapshot = firstArg<ContainerProfileSnapshot>()
                if (snapshot.repositoryIdentity !== settingsIdentity || snapshot.selectedProfileId != profile.value ||
                    snapshot.selectionRevision != selectionRevisions.value || snapshot.configurationRevision != configurationRevisions.value ||
                    snapshot.profiles != listOf(currentProfile())) {
                    throw CancellationException("Container profile configuration changed")
                }
            }
            every { grants.snapshot() } answers { localFolderSnapshots.value }
            every { grants.snapshots } returns localFolderSnapshots
            coEvery { grants.awaitReady() } returns Unit
            every { local.captureExecutionMapping(any()) } answers {
                onCapture()
                mapping(firstArg())
            }
            every { local.checkExecutionMapping(any()) } answers {
                if (firstArg<WorkspacePathMapper.ExecutionMapping>() != mapping()) {
                    throw CancellationException("Local workspace execution mapping changed")
                }
            }
            every { local.cacheIdentity() } answers { "local:$workspace:${profile.value}" }
            every { remote.cacheIdentity() } answers { "remote:$workspace:${remoteIdentity.generation}" }
            every { remote.captureExecutionSource() } answers {
                RemoteSftpFileAccess.ExecutionSource(remoteIdentity, workspace,
                    com.aicode.feature.workspace.domain.RemoteAgentConfigProfile("fixture", 0))
            }
            every { remote.checkExecutionSource(any()) } answers {
                val source = firstArg<RemoteSftpFileAccess.ExecutionSource>()
                if (source.identity !== remoteIdentity || source.workspaceRoot != workspace) throw CancellationException("Reconnected")
            }
            configure(local, "local")
            configure(remote, "remote")
            configure(host, "host")
            every { access.listFiles(match { isLocalFolderPath(it) }) } answers { host.listFiles(firstArg()) }
            every { access.exists(match { isLocalFolderPath(it) }) } answers { host.exists(firstArg()) }
            every { access.writeFile(match { isLocalFolderPath(it) }, any(), any(), any()) } answers {
                host.writeFile(firstArg(), secondArg(), thirdArg(), arg(3))
            }
            every { access.mkdirs(match { isLocalFolderPath(it) }) } answers { host.mkdirs(firstArg()) }
            every { access.isDirectory(match { isLocalFolderPath(it) }) } answers { host.isDirectory(firstArg()) }
            every { access.rename(match { isLocalFolderPath(it) }, any()) } answers { host.rename(firstArg(), secondArg()) }
            every { access.deleteRecursively(match { isLocalFolderPath(it) }) } answers { host.deleteRecursively(firstArg()) }
            every { routing.capture(any(), any(), any()) } answers {
                val execution = firstArg<WorkspaceExecutionContext>()
                val checkScope = thirdArg<() -> Unit>()
                val check = { execution.checkCurrent(); checkScope() }
                check()
                val capture = mockk<RouteCapture>()
                every { capture.guardMutablePath(any()) } answers { check() }
                every { capture.checkCurrent() } answers { check() }
                capture
            }
            coEvery { transfers.transfer(any(), any(), any(), any(), any(), any()) } coAnswers {
                val execution = firstArg<WorkspaceExecutionContext>()
                val source = secondArg<String>()
                val target = thirdArg<String>()
                val cut = arg<Boolean>(3)
                val providedConfirmation = arg<TransferConfirmation?>(4)
                val checkScope = arg<() -> Unit>(5)
                checkScope()
                execution.checkCurrent()
                if (providedConfirmation != null) {
                    completeConfirmation(execution, providedConfirmation, checkScope)
                } else if (access.exists(target)) {
                    TransferResult.Conflict(confirmation(execution, source, target, cut, checkScope) {
                        performTransfer(source, target, cut, overwrite = true)
                    })
                } else {
                    performTransfer(source, target, cut, overwrite = false)
                }
            }
            coEvery { transfers.confirm(any(), any(), any()) } coAnswers {
                completeConfirmation(firstArg(), secondArg(), thirdArg())
            }
            coEvery { transfers.uploadFiles(any(), any(), any(), any()) } coAnswers {
                firstArg<WorkspaceExecutionContext>().checkCurrent()
                arg<() -> Unit>(3).invoke()
                emptyList()
            }
            coEvery { transfers.uploadDirectory(any(), any(), any(), any()) } coAnswers {
                firstArg<WorkspaceExecutionContext>().checkCurrent()
                arg<() -> Unit>(3).invoke()
                TransferResult.Failed("No directory upload configured")
            }
            controller = FileBrowseController(test.backgroundScope, access, factory, routing, transfers,
                currentSelectionRevision = { selectionRevisions.value },
                watch = { path -> flow {
                    watching += path
                    try { emitAll(signals.getOrPut(path) { MutableSharedFlow(extraBufferCapacity = 1) }) }
                    finally { watching -= path }
                } },
                watchLocalFolder = { path -> flow {
                    localWatching += path
                    try { emitAll(localSignals.getOrPut(path) { MutableSharedFlow(extraBufferCapacity = 1) }) }
                    finally { localWatching -= path }
                } },
                loadExpansion = { saved[it] ?: setOf(ROOT) },
                saveExpansion = { workspace, paths -> saved[workspace] = paths },
                onFailure = { reportedFailures += it }, onNotice = { notices += it }, ioDispatcher = io)
            controller.setRoot(FileTreeRoot.Container("Linux", "alpine", false))
        }

        fun key() = FileBrowseScope(workspace, profile.value, holder.currentMode(), access.cacheIdentity(),
            if (holder.currentMode() == ExecutionMode.LOCAL_PROOT) mapping().let { it.copy(profile = it.profile.copy(name = "")) } else null,
            localFolders = localFolderSnapshots.value.roots,
            localFolderRevision = localFolderSnapshots.value.revision,
            selectionRevision = selectionRevisions.value)

        fun setLocalFolders(roots: List<LocalFolderGrant>) {
            val revision = localFolderSnapshots.value.revision + 1
            localFolderSnapshots.value = LocalFolderSnapshot(revision, roots, emptyList()) {
                if (localFolderSnapshots.value.revision != revision) throw CancellationException("Local folder grants changed")
            }
        }

        fun confirmation(
            execution: WorkspaceExecutionContext,
            source: String,
            target: String,
            cut: Boolean,
            checkScope: () -> Unit,
            onConfirm: () -> TransferResult
        ): TransferConfirmation {
            val ticket = mockk<TransferConfirmation>()
            every { ticket.source } returns source
            every { ticket.target } returns target
            every { ticket.cut } returns cut
            confirmations[ticket] = FixtureConfirmation(execution, source, target, cut, checkScope, onConfirm)
            return ticket
        }

        private fun completeConfirmation(
            execution: WorkspaceExecutionContext,
            ticket: TransferConfirmation,
            checkScope: () -> Unit
        ): TransferResult {
            val captured = checkNotNull(confirmations[ticket])
            checkScope()
            captured.checkScope()
            captured.execution.checkCurrent()
            execution.checkCurrent()
            assertEquals(captured.execution.fileCacheIdentity, execution.fileCacheIdentity)
            assertEquals(captured.execution.mode, execution.mode)
            assertEquals(captured.execution.localFolderSnapshot.revision, execution.localFolderSnapshot.revision)
            assertEquals(captured.source, ticket.source)
            assertEquals(captured.target, ticket.target)
            assertEquals(captured.cut, ticket.cut)
            assertSame(execution, WorkspaceExecutionContext.current())
            confirmedTickets += ticket
            return captured.onConfirm()
        }

        private fun performTransfer(source: String, target: String, cut: Boolean, overwrite: Boolean): TransferResult {
            if (cut) access.move(source, target, overwrite) else access.copy(source, target, overwrite)
            return TransferResult.Completed(source, target, cut)
        }

        private data class FixtureConfirmation(
            val execution: WorkspaceExecutionContext,
            val source: String,
            val target: String,
            val cut: Boolean,
            val checkScope: () -> Unit,
            val onConfirm: () -> TransferResult
        )
        private fun currentProfile() = ContainerProfile.BUILTIN_ALPINE.copy(
            id = profile.value, name = profileName, extraBindings = extraBindings.toList()
        )
        private fun mapping(snapshot: ContainerProfileSnapshot? = null) = WorkspacePathMapper.ExecutionMapping(
            snapshot?.activeProfile ?: currentProfile(), File(workspace),
            File("/rootfs/${snapshot?.activeProfile?.id ?: profile.value}"), File("/config"), "/root",
            configurationRevision = snapshot?.configurationRevision ?: configurationRevisions.value,
            selectionRevision = snapshot?.selectionRevision ?: selectionRevisions.value
        )
        fun subscribe() {
            collection = test.backgroundScope.launch { controller.state.collect {} }
        }
        fun show(resetScope: Boolean = true) {
            if (resetScope) controller.setScope(key())
            subscribe()
            test.runCurrent()
        }
        fun hide() { collection?.cancel(); collection = null; test.runCurrent() }
        fun open(path: String) { controller.toggle(path); test.runCurrent() }
        fun signal(path: String) { assertTrue(signals.getValue(path).tryEmit(Unit)) }
        fun paths() = (controller.state.value as FileBrowseState.Success).nodes.map { it.path }
        fun node(path: String): FileTreeNode = (controller.state.value as FileBrowseState.Success).nodes.single { it.path == path }

        private fun configure(provider: FileAccessProvider, backend: String) {
            fun checkFrozen() {
                val execution = checkNotNull(WorkspaceExecutionContext.current())
                if (backend != "host") {
                    assertEquals(if (backend == "local") ExecutionMode.LOCAL_PROOT else ExecutionMode.REMOTE_SSH, execution.mode)
                } else {
                    execution.localFolderSnapshot.checkCurrent()
                }
                execution.checkCurrent()
            }
            every { provider.listFiles(any()) } answers {
                checkFrozen()
                val path = firstArg<String>()
                val entries = tree[path].orEmpty().toList()
                calls.merge(path, 1, Int::plus)
                backends += backend
                onList(path)
                if (path in failures) throw IOException("failed $path")
                entries
            }
            every { provider.exists(any()) } answers {
                checkFrozen()
                val path = firstArg<String>()
                onExists(path)
                if (path == "$ROOT/.gitignore") gitignore != null else tree.containsKey(path) || find(path) != null
            }
            every { provider.readFile(any()) } answers {
                checkFrozen()
                ignoreReads++
                if (ignoreFailure) throw IOException("ignore failed")
                checkNotNull(gitignore)
            }
            every { provider.writeFile(any(), any(), any(), any()) } answers {
                checkFrozen(); writes++; add(firstArg(), directory = false)
            }
            every { provider.mkdirs(any()) } answers {
                checkFrozen(); add(firstArg(), directory = true)
            }
            every { provider.isDirectory(any()) } answers { checkFrozen(); tree.containsKey(firstArg<String>()) }
            every { provider.rename(any(), any()) } answers {
                checkFrozen(); onRename(); transfer(firstArg(), secondArg(), cut = true)
            }
            every { provider.deleteRecursively(any()) } answers { checkFrozen(); remove(firstArg()) }
            every { provider.copy(any(), any(), any()) } answers {
                checkFrozen(); pastes++; onPaste(); transfer(firstArg(), secondArg(), cut = false)
            }
            every { provider.move(any(), any(), any()) } answers {
                checkFrozen(); pastes++; onPaste(); transfer(firstArg(), secondArg(), cut = true)
            }
        }

        private fun find(path: String) = tree[parent(path)]?.find { it.name == path.substringAfterLast('/') }
        private fun add(path: String, directory: Boolean) {
            val name = path.substringAfterLast('/')
            val parent = parent(path)
            tree[parent] = tree[parent].orEmpty().filterNot { it.name == name } + FileEntry(name, directory, 0, 0)
            if (directory) tree[path] = emptyList()
        }
        private fun remove(path: String) {
            tree[parent(path)] = tree[parent(path)].orEmpty().filterNot { it.name == path.substringAfterLast('/') }
            tree.keys.filter { it == path || it.startsWith("$path/") }.forEach(tree::remove)
        }
        private fun transfer(source: String, target: String, cut: Boolean) {
            val directory = tree.containsKey(source)
            val descendants = tree.filterKeys { it == source || it.startsWith("$source/") }
            remove(target)
            add(target, directory)
            descendants.forEach { (path, entries) -> tree[target + path.removePrefix(source)] = entries }
            if (cut) remove(source)
        }
        private fun parent(path: String) = path.substringBeforeLast('/').ifEmpty { "/" }
    }

    companion object {
        private const val ROOT = WorkspacePathMapper.CONTAINER_ROOT
        private fun dir(name: String) = FileEntry(name, true, 0, 0)
        private fun file(name: String) = FileEntry(name, false, 1, 1)
    }
}
