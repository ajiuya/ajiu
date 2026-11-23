package com.aicode.feature.agent.data.local.dao

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

abstract class AgentMessageRewindCases {
    protected abstract val testContext: Context
    private lateinit var database: AgentDatabase
    private val dao get() = database.agentMessageDao()
    private val sessions get() = database.chatSessionDao()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            testContext, AgentDatabase::class.java
        ).allowMainThreadQueries().build()
        sessions.upsert(session("session", 1_000, 3))
        sessions.upsert(session("other", 2_000, 4))
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun rewindingRecentOrdinaryMessagePreservesAllEarlierCompactionLayers() = runBlocking {
        dao.insertAll(listOf(row("original", 1), row("middle", 15), row("recent", 30), row("future", 31)))
        dao.commitCompaction("session", listOf("original"), compactionRows("older", 10), "older")
        dao.commitCompaction("session", listOf("older-marker", "older", "middle"), compactionRows("newer", 20), "newer")
        sessions.upsert(sessions.getById("session")!!.copy(lastInputTokens = 900))
        val before = rows()

        dao.rewindConversation("session", 30)

        assertEquals(before.filterValues { it.timestamp < 30 }, rows())
        assertTrue(dao.getMessageById("original")!!.isCompacted)
        assertTrue(dao.getMessageById("older")!!.isCompacted)
        assertFalse(dao.getMessageById("newer")!!.isCompacted)
        assertNull(dao.getMessageById("recent"))
        assertNull(dao.getMessageById("future"))
        assertEquals(0, sessions.getById("session")!!.lastInputTokens)
    }

    @Test
    fun crossingOneSummaryRestoresOnlyItsCoverageAndKeepsUnrelatedEarlierSummary() = runBlocking {
        val olderSource = row("older-source", 1)
        val newerSource = row("newer-source", 15)
        val retained = row("retained", 16)
        dao.insertAll(listOf(olderSource, newerSource, retained, row("at-cutoff", 20), row("future", 25)))
        dao.commitCompaction("session", listOf("older-source"), compactionRows("older", 10), "older")
        dao.commitCompaction("session", listOf("newer-source"), compactionRows("newer", 20), "newer")
        val before = rows()

        dao.rewindConversation("session", 20)

        assertEquals(newerSource, dao.getMessageById("newer-source"))
        assertEquals(olderSource.copy(isCompacted = true, compactedBySummaryId = "older"), dao.getMessageById("older-source"))
        assertEquals(before.getValue("older-marker"), dao.getMessageById("older-marker"))
        assertEquals(before.getValue("older"), dao.getMessageById("older"))
        assertEquals(retained, dao.getMessageById("retained"))
        assertNull(dao.getMessageById("newer-marker"))
        assertNull(dao.getMessageById("newer"))
        assertNull(dao.getMessageById("at-cutoff"))
        assertNull(dao.getMessageById("future"))
        assertEquals(setOf("older-source", "older-marker", "older", "newer-source", "retained"), rows().keys)
    }

    @Test
    fun deletingSeveralNewerLayersReactivatesPreviousSummaryAndMarkerButNotTheirOriginalSources() = runBlocking {
        val original = row("original", 1)
        val middle = row("middle", 15)
        dao.insertAll(listOf(original, middle, row("later", 25)))
        dao.commitCompaction("session", listOf("original"), compactionRows("first", 10), "first")
        dao.commitCompaction("session", listOf("first-marker", "first", "middle"), compactionRows("second", 20), "second")
        dao.commitCompaction("session", listOf("second-marker", "second", "later"), compactionRows("third", 30), "third")

        dao.rewindConversation("session", 20)

        assertEquals(original.copy(isCompacted = true, compactedBySummaryId = "first"), dao.getMessageById("original"))
        compactionRows("first", 10).forEach { assertEquals(it, dao.getMessageById(it.id)) }
        assertEquals(middle, dao.getMessageById("middle"))
        assertEquals(setOf("original", "first-marker", "first", "middle"), rows().keys)
        assertTrue(rows().values.all { it.timestamp < 20 })

        dao.rewindConversation("session", 10)

        assertEquals(mapOf(original.id to original), rows())
    }

    @Test
    fun rewindNeverRestoresExcludedMessagesOrFollowsAnotherSessionsSummary() = runBlocking {
        val source = row("source", 1).copy(isCompacted = true, compactedBySummaryId = "summary")
        val excluded = row("excluded", 2).copy(isCompacted = true, compactedBySummaryId = "summary", isContextExcluded = true)
        val excludedOnly = row("excluded-only", 3).copy(isContextExcluded = true)
        val foreignOwner = row("foreign-owner", 4).copy(isCompacted = true, compactedBySummaryId = "foreign-summary")
        val foreignSource = row("foreign-source", 1, "other").copy(isCompacted = true, compactedBySummaryId = "summary")
        dao.insertAll(listOf(source, excluded, excludedOnly, foreignOwner, foreignSource) +
            compactionRows("summary", 10) + compactionRows("foreign-summary", 10, "other") + row("foreign-future", 30, "other"))
        val foreignBefore = rows("other")

        dao.rewindConversation("session", 10)

        assertEquals(source.copy(isCompacted = false, compactedBySummaryId = null), dao.getMessageById("source"))
        assertEquals(excluded, dao.getMessageById("excluded"))
        assertEquals(excludedOnly, dao.getMessageById("excluded-only"))
        assertEquals(foreignOwner, dao.getMessageById("foreign-owner"))
        assertEquals(foreignBefore, rows("other"))
        assertEquals(2_000, sessions.getById("other")!!.lastInputTokens)
    }

    @Test
    fun legacyNullOwnershipStaysUnchangedWhenItsEarlierSummarySurvives() = runBlocking {
        val legacySource = row("legacy-source", 1).copy(isCompacted = true)
        val legacyRows = listOf(legacySource) + compactionRows("legacy-summary", 10)
        dao.insertAll(legacyRows + row("recent", 20))

        dao.rewindConversation("session", 20)

        assertEquals(legacyRows.associateBy { it.id }, rows())
    }

    @Test
    fun deletingLastLegacySummaryDoesNotInventOwnershipForNullOrDanglingSources() = runBlocking {
        val legacySource = row("legacy-source", 1).copy(isCompacted = true)
        val danglingSource = row("dangling-source", 2).copy(isCompacted = true, compactedBySummaryId = "missing-summary")
        val knownSource = row("known-source", 3).copy(isCompacted = true, compactedBySummaryId = "legacy-summary")
        val beforeCutoffMarker = compactionRows("legacy-summary", 9).first()
        val cutoffSummary = compactionRows("legacy-summary", 9).last()
        dao.insertAll(listOf(legacySource, danglingSource, knownSource, beforeCutoffMarker, cutoffSummary))

        dao.rewindConversation("session", 10)

        assertEquals(legacySource, dao.getMessageById("legacy-source"))
        assertEquals(danglingSource, dao.getMessageById("dangling-source"))
        assertEquals(knownSource.copy(isCompacted = false, compactedBySummaryId = null), dao.getMessageById("known-source"))
        assertEquals(beforeCutoffMarker, dao.getMessageById(beforeCutoffMarker.id))
        assertNull(dao.getMessageById("legacy-summary"))
        assertEquals(4, rows().size)
    }

    @Test
    fun rewindUsesSqlSummaryRelationsBeyondNineHundredDeletedSummaries() = runBlocking {
        val originals = (0..900).map { row("source-$it", 1) }
        dao.insertAll(originals.mapIndexed { index, source ->
            source.copy(isCompacted = true, compactedBySummaryId = "summary-$index")
        } + originals.indices.map { index ->
            row("summary-$index", 20).copy(role = "ASSISTANT", isContextSummary = true)
        })

        dao.rewindConversation("session", 10)

        assertEquals(originals.associateBy { it.id }, rows())
    }

    @Test
    fun sourceBodyChangeUnderTheSameIdRejectsCommitWithoutWritingAnyCompactionRows() = runBlocking {
        val original = row("head", 1)
        dao.insert(original)
        val expected = mapOf(original.id to CompactionSourceFingerprint.of(original))
        dao.updateMessageContent(original.id, "Edited after summarization started")
        val before = rows()

        val failure = runCatching {
            dao.commitCompaction("session", listOf("head"), compactionRows(), "summary", expected, 3)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(before, rows())
        assertNull(dao.getMessageById("summary-marker"))
        assertNull(dao.getMessageById("summary"))
        assertEquals(1_000, sessions.getById("session")!!.lastInputTokens)
    }

    @Test
    fun replayMetadataAndTimestampChangesUnderTheSameIdRejectCommit() = runBlocking {
        val original = row("head", 1)
        val expected = mapOf(original.id to CompactionSourceFingerprint.of(original))
        val changes = listOf(
            original.copy(timestamp = 2),
            original.copy(role = "ASSISTANT"),
            original.copy(toolCallsJson = "[]"),
            original.copy(toolCallId = "call"),
            original.copy(toolName = "readFile"),
            original.copy(reasoning = "Changed reasoning"),
            original.copy(signature = "Changed signature"),
            original.copy(attachmentsJson = "[]"),
            original.copy(thinkingBlocksJson = "[]"),
            original.copy(modelInputSnapshotJson = "{}"),
            original.copy(isContextSummary = true),
            original.copy(isCompactionMarker = true),
            original.copy(compactedBySummaryId = "changed-owner")
        )
        for (changed in changes) {
            dao.insert(changed)

            val failure = runCatching {
                dao.commitCompaction("session", listOf("head"), compactionRows(), "summary", expected)
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(mapOf(changed.id to changed), rows())
            assertEquals(1_000, sessions.getById("session")!!.lastInputTokens)
        }
    }

    @Test
    fun unchangedSourceAndGenerationCommitExactlyTheFrozenHead() = runBlocking {
        val original = row("head", 1).copy(
            role = "ASSISTANT", toolCallsJson = "[]", reasoning = "Reasoning", signature = "Signature",
            thinkingBlocksJson = "[]", modelInputSnapshotJson = "{}"
        )
        val retained = row("retained", 30)
        val foreign = row("foreign", 1, "other")
        dao.insertAll(listOf(original, retained, foreign))
        val generation = dao.compactionGeneration("session")
        assertEquals(3L, generation)
        assertNull(dao.compactionGeneration("missing"))
        val expected = mapOf(original.id to CompactionSourceFingerprint.of(original))

        dao.commitCompaction("session", listOf("head", "head"), compactionRows(), "summary", expected, generation)

        assertEquals(original.copy(isCompacted = true, compactedBySummaryId = "summary"), dao.getMessageById("head"))
        compactionRows().forEach { assertEquals(it, dao.getMessageById(it.id)) }
        assertEquals(retained, dao.getMessageById("retained"))
        assertEquals(foreign, dao.getMessageById("foreign"))
        assertEquals(0, sessions.getById("session")!!.lastInputTokens)
        assertEquals(2_000, sessions.getById("other")!!.lastInputTokens)
    }

    @Test
    fun missingOrExtraExpectedSourceKeysRejectCommitBeforeAnyWrite() = runBlocking {
        val original = row("head", 1)
        dao.insert(original)
        val fingerprint = CompactionSourceFingerprint.of(original)
        for (expected in listOf(emptyMap(), mapOf("head" to fingerprint, "extra" to fingerprint))) {
            val failure = runCatching {
                dao.commitCompaction("session", listOf("head"), compactionRows(), "summary", expected)
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(mapOf(original.id to original), rows())
            assertEquals(1_000, sessions.getById("session")!!.lastInputTokens)
        }
    }

    @Test
    fun staleGenerationRejectsEvenUnchangedSourceAndDeletedSessionsHaveNoGeneration() = runBlocking {
        val original = row("head", 1)
        dao.insert(original)
        val expected = mapOf(original.id to CompactionSourceFingerprint.of(original))
        val generation = dao.compactionGeneration("session")
        sessions.upsert(sessions.getById("session")!!.copy(executionGeneration = 4))
        val failure = runCatching {
            dao.commitCompaction("session", listOf("head"), compactionRows(), "summary", expected, generation)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(mapOf(original.id to original), rows())
        assertEquals(1_000, sessions.getById("session")!!.lastInputTokens)
        sessions.delete("session")
        assertNull(dao.compactionGeneration("session"))
        val deletedFailure = runCatching {
            dao.commitCompaction("session", listOf("head"), compactionRows(), "summary", expected, generation)
        }.exceptionOrNull()
        assertTrue(deletedFailure is CancellationException)
        assertEquals(mapOf(original.id to original), rows())
    }

    @Test
    fun sourceCasCommitHandlesMoreThanNineHundredIdsInBoundedChunks() = runBlocking {
        val originals = (0..900).map { row("head-$it", it.toLong()) }
        dao.insertAll(originals)
        val expected = originals.associate { it.id to CompactionSourceFingerprint.of(it) }

        dao.commitCompaction("session", originals.map { it.id }, compactionRows(timestamp = 1_000), "summary", expected, 3)

        assertEquals(903, rows().size)
        for (original in originals) {
            assertEquals(original.copy(isCompacted = true, compactedBySummaryId = "summary"), dao.getMessageById(original.id))
        }
        compactionRows(timestamp = 1_000).forEach { assertEquals(it, dao.getMessageById(it.id)) }
        assertEquals(0, sessions.getById("session")!!.lastInputTokens)
    }

    @Test
    fun lateCommitFailureRollsBackHeadMarkingAndBothInsertedRows() = runBlocking {
        val original = row("head", 1)
        dao.insert(original)
        val before = rows()
        rejectTokenReset()

        val failure = runCatching {
            dao.commitCompaction("session", listOf("head"), compactionRows(), "summary",
                mapOf(original.id to CompactionSourceFingerprint.of(original)), 3)
        }.exceptionOrNull()

        assertTrue(failure is SQLiteException)
        assertTrue(failure?.message.orEmpty().contains("forced token reset failure"))
        assertEquals(before, rows())
        assertNull(dao.getMessageById("summary-marker"))
        assertNull(dao.getMessageById("summary"))
        assertEquals(1_000, sessions.getById("session")!!.lastInputTokens)
    }

    @Test
    fun lateRewindFailureRollsBackRestorationDeletionAndTokenReset() = runBlocking {
        dao.insertAll(listOf(row("source", 1), row("at-cutoff", 10), row("future", 30)))
        dao.commitCompaction("session", listOf("source"), compactionRows(), "summary")
        sessions.upsert(sessions.getById("session")!!.copy(lastInputTokens = 1_000))
        val before = rows()
        rejectTokenReset()

        val failure = runCatching { dao.rewindConversation("session", 10) }.exceptionOrNull()

        assertTrue(failure is SQLiteException)
        assertTrue(failure?.message.orEmpty().contains("forced token reset failure"))
        assertEquals(before, rows())
        assertTrue(dao.getMessageById("source")!!.isCompacted)
        assertEquals(1_000, sessions.getById("session")!!.lastInputTokens)
    }

    private fun rejectTokenReset() {
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_token_reset BEFORE UPDATE OF lastInputTokens ON chat_sessions
            WHEN NEW.id = 'session'
            BEGIN SELECT RAISE(ABORT, 'forced token reset failure'); END
        """.trimIndent())
    }

    private suspend fun rows(sessionId: String = "session"): Map<String, AgentMessageEntity> =
        dao.getMessagesBySessionOnce(sessionId).associateBy { it.id }

    private fun compactionRows(
        summaryId: String = "summary",
        timestamp: Long = 10,
        sessionId: String = "session"
    ): List<AgentMessageEntity> = listOf(
        row("$summaryId-marker", timestamp, sessionId).copy(content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true),
        row(summaryId, timestamp + 1, sessionId).copy(role = "ASSISTANT", content = "Summary", isContextSummary = true)
    )

    private fun row(id: String, timestamp: Long, sessionId: String = "session"): AgentMessageEntity =
        AgentMessageEntity(id, sessionId, "USER", "Original", timestamp)

    private fun session(id: String, lastInputTokens: Int, generation: Long): ChatSessionEntity =
        ChatSessionEntity(id, "Session", 1, 1, lastInputTokens = lastInputTokens, executionGeneration = generation)
}
