package com.aicode.core.watch

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SharedSnapshotPollerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun equivalentScopesShareOneTwoSecondJobAndLastReleaseStopsIt() = runTest {
        var scans = 0
        val poller = SharedSnapshotPoller<SnapshotScope, Int>(backgroundScope, 2000, { ++scans })
        val key = SnapshotScope("/same", true, IgnoreRules.of(WatchFilter(setOf("build")), listOf("*.tmp")))
        val first = mutableListOf<Pair<Int, Int>>()
        val second = mutableListOf<Pair<Int, Int>>()
        val a = poller.subscribe(key) { before, after -> first += before to after }
        val b = poller.subscribe(key.copy(rules = IgnoreRules.of(WatchFilter(setOf("build")), listOf("*.tmp")))) { before, after -> second += before to after }
        runCurrent()
        assertEquals(1, scans)
        advanceTimeBy(1999); runCurrent()
        assertTrue(first.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(1 to 2), first)
        assertEquals(first, second)
        a.close(); a.close()
        advanceTimeBy(2000); runCurrent()
        assertEquals(1, first.size)
        assertEquals(2, second.size)
        b.close()
        advanceTimeBy(6000); runCurrent()
        assertEquals(3, scans)
        poller.subscribe(key) { _, _ -> }.use { runCurrent(); assertEquals(4, scans) }
    }

    @Test fun rootsDepthAndIgnoreStructuresNeverShare() = runTest {
        var scans = 0
        val poller = SharedSnapshotPoller<SnapshotScope, Int>(backgroundScope, 2000, { ++scans })
        val key = SnapshotScope("/one", true, IgnoreRules.of(WatchFilter(), emptyList()))
        val leases = listOf(key, key.copy(root = "/two"), key.copy(recursive = false),
            key.copy(rules = IgnoreRules.of(WatchFilter(extraPatterns = listOf("build")), emptyList())))
            .map { poller.subscribe(it) { _, _ -> } }
        runCurrent()
        assertEquals(4, scans)
        leases.forEach { it.close() }
    }

    @Test fun ruleKeyCopiesMutableInputsAndPreservesPatternOrder() {
        val names = mutableSetOf("build")
        val patterns = mutableListOf("*.tmp")
        val git = mutableListOf("out")
        val frozen = IgnoreRules.of(WatchFilter(names, patterns), git)
        val hash = frozen.hashCode()
        names.add("src"); patterns.add("cache"); git.clear()
        assertEquals(hash, frozen.hashCode())
        assertEquals(frozen, IgnoreRules.of(WatchFilter(setOf("build"), listOf("*.tmp")), listOf("out")))
        assertFalse(frozen.isIgnoredDir(listOf("src")))
        assertFalse(frozen == IgnoreRules.of(WatchFilter(extraPatterns = listOf("out", "*.tmp")), emptyList()))
    }

    @Test fun cancellingOrThrowingConsumerCannotCancelAnotherConsumer() = runTest {
        var scans = 0
        var delivered = 0
        var errors = 0
        val poller = SharedSnapshotPoller<String, Int>(backgroundScope, 2000, { ++scans }, { errors++ })
        val broken = poller.subscribe("same") { _, _ -> throw CancellationException("consumer stopped") }
        val healthy = poller.subscribe("same") { _, _ -> delivered++ }
        runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(1, delivered)
        assertEquals(1, errors)
        broken.close()
        advanceTimeBy(2000); runCurrent()
        assertEquals(2, delivered)
        healthy.close()
    }

    @Test fun failedScanRetriesWithoutResettingTheLastGoodSnapshot() = runTest {
        var scans = 0
        val changes = mutableListOf<Pair<Int, Int>>()
        val poller = SharedSnapshotPoller<String, Int>(backgroundScope, 2000, {
            if (++scans == 2) error("temporary read failure")
            scans
        })
        val lease = poller.subscribe("same") { before, after -> changes += before to after }
        runCurrent(); advanceTimeBy(4000); runCurrent()
        assertEquals(listOf(1 to 3), changes)
        lease.close()
    }

    @Test fun realSnapshotKeepsDepthPruningAndReportsIncompleteRatherThanPretendingDeletion() {
        val root = temporary.newFolder()
        File(root, "src").mkdir()
        File(root, "src/main.kt").writeText("one")
        File(root, "build").mkdir()
        File(root, "build/output").writeText("ignored")
        val key = SnapshotScope(root.path, true, IgnoreRules.of(WatchFilter(setOf("build")), emptyList()))
        val full = snapshotOfScope(key, 100)
        assertTrue(full.complete)
        assertEquals(setOf("src", "src/main.kt", "build"), full.entries.keys)
        assertEquals(setOf("src", "build"), snapshotOfScope(key.copy(recursive = false), 100).entries.keys)
        assertFalse(snapshotOfScope(key, 1).complete)
    }

    @Test fun boundedEventAccumulatorDeduplicatesAndAlwaysRetainsOverflowSignal() {
        val queue = PendingFileChanges(2)
        repeat(100) { queue.offer("a", ChangeKind.CREATED) }
        queue.offer("a", ChangeKind.DELETED)
        queue.offer("b", ChangeKind.MODIFIED)
        queue.offer("c", ChangeKind.CREATED)
        val (paths, truncated) = queue.drain(2)
        assertEquals(mapOf("a" to ChangeKind.MODIFIED, "b" to ChangeKind.MODIFIED), paths)
        assertTrue(truncated)
        queue.invalidate()
        assertTrue(queue.drain(2).second)
        assertFalse(queue.drain(2).second)
        queue.offer("a", ChangeKind.CREATED); queue.offer("b", ChangeKind.CREATED)
        assertTrue(queue.drain(1).second)
    }
}
