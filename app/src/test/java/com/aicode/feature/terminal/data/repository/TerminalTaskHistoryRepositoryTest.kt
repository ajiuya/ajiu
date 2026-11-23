package com.aicode.feature.terminal.data.repository

import com.aicode.feature.terminal.domain.MAX_TERMINAL_OUTPUT_CHARS
import com.aicode.feature.terminal.domain.TERMINAL_HISTORY_CORRUPT_ERROR
import com.aicode.feature.terminal.domain.TERMINAL_HISTORY_IO_ERROR
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskScope
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class TerminalTaskHistoryRepositoryTest {
    private val scope = TerminalTaskScope("profile", "a".repeat(64), "/rootfs", "1:2", "/workspace", "1:3")
    private val files = MemoryFiles()
    private val repository = TerminalTaskHistoryRepository(files, Dispatchers.Unconfined) { 500 }

    @Test
    fun reopenFreezesOldRunningAsUnknownButKeepsKnownResultWithoutReplayingWork() = runBlocking {
        val running = record(1)
        val finished = record(2)
        repository.create(running)
        repository.snapshot(running.historyId, "partial", 4)
        repository.create(finished)
        repository.finish(finished.historyId, "completed", 8, 23)
        val oldFinished = files.records.getValue(finished.historyId)
        val reopened = TerminalTaskHistoryRepository(files, Dispatchers.Unconfined) { 600 }
        val loaded = reopened.load()
        assertTrue(loaded.loaded)
        assertNull(loaded.error)
        val unknown = reopened.read(running.historyId, scope, "session")!!
        assertEquals(TerminalTaskStatus.UNKNOWN, unknown.status)
        assertEquals("partial", unknown.output)
        assertNull(unknown.exitCode)
        assertNull(unknown.finishedAt)
        assertEquals(unknown, files.records[running.historyId])
        assertEquals(oldFinished, reopened.read(finished.historyId, scope, "session"))
        assertEquals(loaded, reopened.load())
    }

    @Test
    fun lateAndReorderedSnapshotsNeverReplaceNewerOutputOrTerminalFacts() = runBlocking {
        val record = record()
        repository.create(record)
        repository.snapshot(record.historyId, "new", 10)
        repository.snapshot(record.historyId, "stale", 2)
        repository.finish(record.historyId, "stale final", 5, 17)
        repository.snapshot(record.historyId, "late", 100)
        repository.finish(record.historyId, "duplicate", 101, 0)
        val result = repository.read(record.historyId, scope, "session")!!
        assertEquals("new", result.output)
        assertEquals(10L, result.outputRevision)
        assertEquals(17, result.exitCode)
        assertEquals(TerminalTaskStatus.FINISHED, result.status)
    }

    @Test
    fun unknownCannotBePromotedByLateSnapshotOrForcedKillExit() = runBlocking {
        val record = record()
        repository.create(record)
        repository.unknown(record.historyId, "closed by user", 4)
        repository.finish(record.historyId, "forced kill", 6, 0)
        repository.snapshot(record.historyId, "stale running", 100)
        val result = repository.read(record.historyId, scope, "session")!!
        assertEquals(TerminalTaskStatus.UNKNOWN, result.status)
        assertEquals("closed by user", result.output)
        assertNull(result.exitCode)
    }

    @Test
    fun concurrentRevisionsConvergeOnTheNewestSnapshot() = runBlocking {
        val record = record()
        repository.create(record)
        (1..30).reversed().map { revision ->
            async(Dispatchers.Default) { repository.snapshot(record.historyId, "output-$revision", revision.toLong()) }
        }.awaitAll()
        assertEquals("output-30", repository.read(record.historyId, scope, "session")!!.output)
    }

    @Test
    fun outputIsBoundedWithBothEndsAndExplicitTruncation() = runBlocking {
        val record = record()
        repository.create(record)
        repository.snapshot(record.historyId, "head" + "x".repeat(MAX_TERMINAL_OUTPUT_CHARS * 2) + "tail", 1)
        val output = repository.read(record.historyId, scope, "session")!!.output
        assertTrue(output.startsWith("head"))
        assertTrue(output.endsWith("tail"))
        assertTrue(output.contains("truncated"))
        assertTrue(output.length <= MAX_TERMINAL_OUTPUT_CHARS + 64)
    }

    @Test
    fun ordinaryRetentionRemovesOnlyOldFinishedRecords() = runBlocking {
        val running = record(0)
        val unknown = record(1)
        repository.create(running)
        repository.create(unknown)
        repository.unknown(unknown.historyId, "unknown", 0)
        val completed = (2L..54L).map { record(it) }
        for (record in completed) {
            repository.create(record)
            repository.finish(record.historyId, "done", 0, 0)
        }
        val result = repository.list(scope, "session")
        assertEquals(50, result.count { it.status == TerminalTaskStatus.FINISHED })
        assertEquals(running.historyId, result.single { it.status == TerminalTaskStatus.RUNNING }.historyId)
        assertEquals(unknown.historyId, result.single { it.status == TerminalTaskStatus.UNKNOWN }.historyId)
        assertFalse(files.records.containsKey(completed.first().historyId))
        assertEquals(52, files.records.size)
    }

    @Test
    fun listAndReadAreStrictlyBoundToSessionAndEverySourceComponent() = runBlocking {
        val record = record()
        repository.create(record)
        val otherScopes = listOf(
            scope.copy(profileId = "other"), scope.copy(rootfsSourceIdentity = "b".repeat(64)),
            scope.copy(rootfsPath = "/other"), scope.copy(rootfsIdentity = "rebuilt"),
            scope.copy(workspaceRoot = "/other"), scope.copy(workspaceIdentity = "replaced")
        )
        for (other in otherScopes) {
            assertTrue(repository.list(other, "session").isEmpty())
            assertNull(repository.read(record.historyId, other, "session"))
        }
        assertTrue(repository.list(scope, "different-session").isEmpty())
        assertNull(repository.read(record.historyId, scope, "different-session"))
        assertEquals(listOf(record), repository.list(scope, "session"))
    }

    @Test
    fun invalidIdsAndUnboundedSessionsAreRejectedBeforeStorageAccess() = runBlocking {
        for (id in listOf("../outside", "term-1", "A".repeat(36), UUID.randomUUID().toString() + "/x", "a".repeat(10000))) {
            assertTrue(runCatching { repository.read(id, scope, "session") }.exceptionOrNull() is IllegalArgumentException)
        }
        for (session in listOf("", " ", "s\n", "s".repeat(257))) {
            assertTrue(runCatching { repository.list(scope, session) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, files.reads)
        assertFalse(repository.state.value.loaded)
    }

    @Test
    fun corruptStorageAndDirectoryIoAreExplicitErrorsNotEmptySuccessfulLoads() = runBlocking {
        files.readFailure = SerializationException("invalid")
        files.records[record().historyId] = record()
        assertEquals(TERMINAL_HISTORY_CORRUPT_ERROR, repository.load().error)
        assertTrue(runCatching { repository.list(scope, "session") }.exceptionOrNull() is IOException)
        val inaccessible = MemoryFiles().apply { idsFailure = IOException("disk") }
        val result = TerminalTaskHistoryRepository(inaccessible, Dispatchers.Unconfined).load()
        assertTrue(result.loaded)
        assertEquals(TERMINAL_HISTORY_IO_ERROR, result.error)
    }

    @Test
    fun failedCreateDoesNotPublishAFictitiousRunningTask() = runBlocking {
        files.writeFailure = IOException("disk full")
        assertTrue(runCatching { repository.create(record()) }.exceptionOrNull() is IOException)
        assertEquals(TERMINAL_HISTORY_IO_ERROR, repository.state.value.error)
        assertTrue(repository.state.value.records.isEmpty())
        assertTrue(files.records.isEmpty())
    }

    @Test
    fun successfulCreateAfterIoRecoveryClearsOnlyOrphanedCreateErrors() = runBlocking {
        val failed = record(1)
        val next = record(2)
        files.writeFailure = IOException("disk full")
        assertTrue(runCatching { repository.create(failed) }.exceptionOrNull() is IOException)
        assertEquals(TERMINAL_HISTORY_IO_ERROR, repository.state.value.error)
        assertTrue(repository.state.value.records.isEmpty())
        assertFalse(files.records.containsKey(failed.historyId))
        files.writeFailure = null
        repository.create(next)
        assertEquals(listOf(next), repository.state.value.records)
        assertNull(repository.state.value.error)
        assertEquals(listOf(next), repository.list(scope, "session"))
        assertNull(repository.read(failed.historyId, scope, "session"))
        assertFalse(files.records.containsKey(failed.historyId))
        assertEquals(next, files.records[next.historyId])
    }

    @Test
    fun successfulNewTaskAfterFinalIoRecoveryDoesNotEraseUnsavedPreviousResult() = runBlocking {
        val failed = record(1)
        val next = record(2)
        repository.create(failed)
        files.writeFailure = IOException("disk full")
        assertTrue(runCatching { repository.finish(failed.historyId, "observed completion", 3, 47) }
            .exceptionOrNull() is IOException)
        val unsaved = repository.state.value.records.single()
        assertEquals(TerminalTaskStatus.FINISHED, unsaved.status)
        assertEquals(47, unsaved.exitCode)
        assertEquals(TERMINAL_HISTORY_IO_ERROR, unsaved.historyError)
        assertEquals(TerminalTaskStatus.RUNNING, files.records.getValue(failed.historyId).status)
        files.writeFailure = null
        repository.create(next)
        val state = repository.state.value
        assertEquals(next, state.records.single { it.historyId == next.historyId })
        assertEquals(unsaved, state.records.single { it.historyId == failed.historyId })
        assertEquals(TERMINAL_HISTORY_IO_ERROR, state.error)
        assertEquals(next, files.records[next.historyId])
        assertEquals(failed, files.records[failed.historyId])
        assertEquals(setOf(failed.historyId, next.historyId), files.records.keys)
    }

    @Test
    fun failedFinalIoKeepsObservedExitAndRejectsLateRunningSnapshots() = runBlocking {
        val record = record()
        repository.create(record)
        files.writeFailure = IOException("disk full")
        assertTrue(runCatching { repository.finish(record.historyId, "done", 2, 29) }.exceptionOrNull() is IOException)
        val observed = repository.state.value.records.single()
        assertEquals(TerminalTaskStatus.FINISHED, observed.status)
        assertEquals(29, observed.exitCode)
        assertEquals(TERMINAL_HISTORY_IO_ERROR, observed.historyError)
        assertNotNull(repository.state.value.error)
        repository.snapshot(record.historyId, "late", 30)
        assertEquals(observed, repository.state.value.records.single())
        assertEquals(TerminalTaskStatus.RUNNING, files.records.getValue(record.historyId).status)
    }

    @Test
    fun failedRecoveryStillPublishesUnknownNotOldRunning() = runBlocking {
        val record = record()
        files.records[record.historyId] = record
        files.writeFailure = IOException("disk full")
        val state = repository.load()
        assertEquals(TerminalTaskStatus.UNKNOWN, state.records.single().status)
        assertEquals(TERMINAL_HISTORY_IO_ERROR, state.error)
    }

    @Test
    fun listOrderIsStableAndLatestFirst() = runBlocking {
        val records = listOf(record(3), record(1), record(3), record(2))
        records.forEach { repository.create(it) }
        val expected = records.sortedWith(compareByDescending<TerminalTaskRecord> { it.startedAt }.thenBy { it.historyId })
        assertEquals(expected, repository.list(scope, "session"))
        assertEquals(expected, repository.list(scope, "session"))
    }

    private fun record(startedAt: Long = 1): TerminalTaskRecord {
        val id = UUID.randomUUID().toString()
        return TerminalTaskRecord(id, "term-$id", "Build", "session", scope, startedAt, startedAt)
    }

    internal class MemoryFiles : TerminalTaskHistoryFiles {
        val records = linkedMapOf<String, TerminalTaskRecord>()
        var writeFailure: Exception? = null
        var readFailure: Exception? = null
        var idsFailure: Exception? = null
        var reads = 0
        override fun ids(): List<String> = idsFailure?.let { throw it } ?: records.keys.toList()
        override fun read(historyId: String): TerminalTaskRecord {
            reads++
            readFailure?.let { throw it }
            return records.getValue(historyId)
        }
        override fun write(record: TerminalTaskRecord) {
            writeFailure?.let { throw it }
            records[record.historyId] = record
        }
        override fun delete(historyId: String) { records.remove(historyId) }
    }
}
