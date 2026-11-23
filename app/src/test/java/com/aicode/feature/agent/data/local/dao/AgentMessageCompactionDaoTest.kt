package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentMessageCompactionDaoTest {
    private val rows = linkedMapOf<String, AgentMessageEntity>()
    private val sessions = mutableSetOf("session", "other")
    private val generations = mapOf("session" to 3L, "other" to 4L)
    private val inputTokens = mutableMapOf("session" to 1_000, "other" to 2_000)
    private val queries = mockk<AgentMessageDao>(relaxed = true)
    private val dao = object : AgentMessageDao by queries {
        override suspend fun commitCompaction(
            sessionId: String,
            headIds: List<String>,
            messages: List<AgentMessageEntity>,
            summaryId: String,
            expectedSource: Map<String, String>?,
            expectedGeneration: Long?
        ) = super<AgentMessageDao>.commitCompaction(sessionId, headIds, messages, summaryId, expectedSource, expectedGeneration)

        override suspend fun rewindConversation(sessionId: String, cutoff: Long) =
            super<AgentMessageDao>.rewindConversation(sessionId, cutoff)
    }

    @Before
    fun setUp() {
        coEvery { queries.sessionExists(any()) } answers { firstArg<String>() in sessions }
        coEvery { queries.sessionGenerationMatches(any(), any()) } answers {
            val sessionId = firstArg<String>()
            val expected = secondArg<Long?>()
            sessionId in sessions && (expected == null || generations[sessionId] == expected)
        }
        coEvery { queries.getMessageById(any()) } answers { rows[firstArg<String>()] }
        coEvery { queries.countActiveCompactionMessages(any(), any()) } answers {
            val sessionId = firstArg<String>()
            val ids = secondArg<List<String>>().toSet()
            rows.values.count { it.sessionId == sessionId && it.id in ids && !it.isCompacted && !it.isContextExcluded }
        }
        coEvery { queries.insertAll(any()) } answers {
            firstArg<List<AgentMessageEntity>>().forEach { rows[it.id] = it }
        }
        coEvery { queries.markMessagesCompactedByIds(any(), any(), any()) } answers {
            val sessionId = firstArg<String>()
            val ids = secondArg<List<String>>()
            val summaryId = thirdArg<String>()
            rows.replaceAll { id, row ->
                if (row.sessionId == sessionId && id in ids && !row.isContextExcluded) {
                    row.copy(isCompacted = true, compactedBySummaryId = summaryId)
                } else row
            }
        }
        coEvery { queries.resetLastInputTokens(any()) } answers {
            val sessionId = firstArg<String>()
            if (sessionId in sessions) inputTokens[sessionId] = 0
        }
        coEvery { queries.deleteCompactionMessages(any()) } answers {
            val sessionId = firstArg<String>()
            rows.entries.removeAll { it.value.sessionId == sessionId && (it.value.isCompactionMarker || it.value.isContextSummary) }
            Unit
        }
        coEvery { queries.restoreCompactedMessagesBefore(any(), any()) } answers {
            val sessionId = firstArg<String>()
            val cutoff = secondArg<Long>()
            val removedSummaryIds = rows.values.filter {
                it.sessionId == sessionId && it.timestamp >= cutoff && it.isContextSummary
            }.map { it.id }.toSet()
            rows.replaceAll { _, row ->
                if (row.sessionId == sessionId && row.timestamp < cutoff && row.isCompacted &&
                    !row.isContextExcluded && row.compactedBySummaryId in removedSummaryIds
                ) row.copy(isCompacted = false, compactedBySummaryId = null) else row
            }
        }
        coEvery { queries.deleteMessagesFromTimestamp(any(), any()) } answers {
            val sessionId = firstArg<String>()
            val cutoff = secondArg<Long>()
            rows.entries.removeAll { it.value.sessionId == sessionId && it.value.timestamp >= cutoff }
            Unit
        }
    }

    @Test
    fun deletedSessionRejectsCommitBeforeAnyReadOrWrite() = runTest {
        val original = row("head")
        rows[original.id] = original
        sessions.remove("session")
        val before = rows.toMap()

        val failure = runCatching { commit(listOf("head")) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(before, rows)
        assertEquals(1_000, inputTokens["session"])
        coVerify(exactly = 0) { queries.countActiveCompactionMessages(any(), any()) }
        coVerify(exactly = 0) { queries.getMessageById(any()) }
        assertNoWrites()
    }

    @Test
    fun fullyDeletedSessionCannotBeResurrectedByLateSummary() = runTest {
        sessions.remove("session")

        val failure = runCatching { commit(listOf("deleted-head")) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(rows.isEmpty())
        assertNoWrites()
    }

    @Test
    fun headMustStillExistInTheSameSession() = runTest {
        rows["foreign"] = row("foreign").copy(sessionId = "other")
        val before = rows.toMap()

        for (id in listOf("missing", "foreign")) {
            val failure = runCatching { commit(listOf(id)) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(before, rows)
        }
        assertNoWrites()
    }

    @Test
    fun previouslyCompactedOrExcludedHeadCannotBeCommittedAgain() = runTest {
        rows["compacted"] = row("compacted").copy(isCompacted = true, compactedBySummaryId = "previous-summary")
        rows["excluded"] = row("excluded").copy(isContextExcluded = true)
        val before = rows.toMap()

        for (id in rows.keys.toList()) {
            val failure = runCatching { commit(listOf(id)) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(before, rows)
        }
        assertNoWrites()
    }

    @Test
    fun newMessagesCannotWriteIntoAnotherSession() = runTest {
        rows["head"] = row("head")
        val before = rows.toMap()
        val messages = compactionRows().map { it.copy(sessionId = "other") }

        val failure = runCatching { commit(listOf("head"), messages) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, rows)
        assertNoWrites()
    }

    @Test
    fun newMarkerOrSummaryCannotReplaceAnyExistingMessage() = runTest {
        rows["head"] = row("head")
        for (collisionId in listOf("marker", "summary")) {
            for (sessionId in listOf("session", "other")) {
                val collision = row(collisionId).copy(sessionId = sessionId, content = "Keep original body")
                rows[collisionId] = collision
                val before = rows.toMap()

                val failure = runCatching { commit(listOf("head")) }.exceptionOrNull()

                assertTrue(failure is CancellationException)
                assertEquals(before, rows)
                rows.remove(collisionId)
            }
        }
        assertNoWrites()
    }

    @Test
    fun duplicatedNewIdsAreRejectedBeforeCompactingTheHead() = runTest {
        rows["head"] = row("head")
        val before = rows.toMap()
        val summary = compactionRows().last()

        val failure = runCatching { commit(listOf("head"), listOf(summary, summary)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, rows)
        assertNoWrites()
    }

    @Test
    fun summaryIdMustIdentifyAnExplicitAssistantSummaryInThePayload() = runTest {
        rows["head"] = row("head")
        val before = rows.toMap()
        val summary = compactionRows().last()
        for (invalid in listOf(summary.copy(id = "different"), summary.copy(role = "USER"), summary.copy(isContextSummary = false))) {
            val failure = runCatching { commit(listOf("head"), listOf(invalid)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(before, rows)
        }
        assertNoWrites()
    }

    @Test
    fun successfulCommitMarksExactHeadWithoutDeletingOrRewritingOriginalData() = runTest {
        val original = row("head").copy(
            role = "ASSISTANT", content = " \nOriginal body", reasoning = "private reasoning", signature = "signature",
            thinkingBlocksJson = "[]", toolCallsJson = "[]", inputTokens = 123, outputTokens = 456,
            turnDurationMs = 789, contextInputTokens = 100, contextInputBudget = 1_000, contextBreakdownJson = "{}"
        )
        val tool = row("tool_call", 2).copy(
            role = "TOOL", toolCallId = "call", toolName = "readFile", toolArgs = "{}",
            toolActionTitle = "Read file", toolStartedAtMs = 10_000, toolDurationMs = 125
        )
        val retained = row("retained", 3)
        val excluded = row("excluded", 4).copy(isContextExcluded = true)
        val foreign = row("foreign").copy(sessionId = "other")
        listOf(original, tool, retained, excluded, foreign).forEach { rows[it.id] = it }
        val inserted = compactionRows()

        commit(listOf("head", "tool_call"), inserted)

        assertEquals(original.copy(isCompacted = true, compactedBySummaryId = "summary"), rows["head"])
        assertEquals(tool.copy(isCompacted = true, compactedBySummaryId = "summary"), rows["tool_call"])
        assertEquals(retained, rows["retained"])
        assertEquals(excluded, rows["excluded"])
        assertEquals(foreign, rows["foreign"])
        inserted.forEach { assertEquals(it, rows[it.id]) }
        assertEquals(7, rows.size)
        assertEquals(0, inputTokens["session"])
        assertEquals(2_000, inputTokens["other"])
        coVerify(exactly = 1) { queries.markMessagesCompactedByIds("session", listOf("head", "tool_call"), "summary") }
        coVerify(exactly = 1) { queries.insertAll(inserted) }
        coVerify(exactly = 1) { queries.resetLastInputTokens("session") }
        coVerify(exactly = 0) { queries.deleteBySession(any()) }
        coVerify(exactly = 0) { queries.deleteMessageById(any()) }
        coVerify(exactly = 0) { queries.deleteCompactionMessages(any()) }
    }

    @Test
    fun largeHeadUsesBoundedCountQueriesAndChunkedMarkingForDistinctIds() = runTest {
        val head = (0..900).map { "head-$it" }
        head.forEach { rows[it] = row(it) }

        commit(head + head.take(25))

        assertTrue(head.all { rows.getValue(it).isCompacted })
        coVerify(exactly = 2) { queries.countActiveCompactionMessages(any(), any()) }
        coVerify(exactly = 1) { queries.countActiveCompactionMessages("session", head.take(900)) }
        coVerify(exactly = 1) { queries.countActiveCompactionMessages("session", head.drop(900)) }
        coVerify(exactly = 1) { queries.markMessagesCompactedByIds("session", head.take(900), "summary") }
        coVerify(exactly = 1) { queries.markMessagesCompactedByIds("session", head.drop(900), "summary") }
        coVerify(exactly = 1) { queries.getMessageById("marker") }
        coVerify(exactly = 1) { queries.getMessageById("summary") }
        coVerify(exactly = 2) { queries.getMessageById(any()) }
        coVerify(exactly = 0) { queries.getMessagesBySessionOnce(any()) }
        assertEquals(903, rows.size)
    }

    @Test
    fun inactiveHeadInTheSecondCountChunkRejectsBeforeAnyWriteOrFullRowRead() = runTest {
        val head = (0..900).map { "head-$it" }
        head.forEach { rows[it] = row(it) }
        rows[head.last()] = rows.getValue(head.last()).copy(isContextExcluded = true)
        val before = rows.toMap()

        val failure = runCatching { commit(head) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(before, rows)
        coVerify(exactly = 1) { queries.countActiveCompactionMessages("session", head.take(900)) }
        coVerify(exactly = 1) { queries.countActiveCompactionMessages("session", head.drop(900)) }
        coVerify(exactly = 0) { queries.getMessageById(any()) }
        coVerify(exactly = 0) { queries.getMessagesBySessionOnce(any()) }
        assertNoWrites()
    }

    @Test
    fun rewindRestoresOnlyRemovedSummaryCoverageAndKeepsEarlierCompactions() = runTest {
        val earlier = row("head", 1)
        val retained = row("retained", 3)
        val excluded = row("excluded", 2).copy(isContextExcluded = true, isCompacted = true, compactedBySummaryId = "older")
        val foreign = row("foreign", 4).copy(sessionId = "other")
        val olderSource = row("older-source", 0).copy(isCompacted = true, compactedBySummaryId = "older")
        val olderMarker = row("older-marker", 1).copy(isCompactionMarker = true)
        val olderSummary = row("older", 2).copy(role = "ASSISTANT", isContextSummary = true)
        listOf(earlier, retained, excluded, foreign, olderSource, olderMarker, olderSummary).forEach { rows[it.id] = it }
        commit(listOf("head"))

        dao.rewindConversation("session", 3)

        assertEquals(earlier, rows["head"])
        assertFalse(rows.getValue("head").isCompacted)
        assertNull(rows["retained"])
        assertNull(rows["marker"])
        assertNull(rows["summary"])
        assertEquals(excluded, rows["excluded"])
        assertEquals(foreign, rows["foreign"])
        assertEquals(olderSource, rows["older-source"])
        assertEquals(olderMarker, rows["older-marker"])
        assertEquals(olderSummary, rows["older"])
        assertEquals(6, rows.size)
        coVerify(exactly = 0) { queries.deleteCompactionMessages(any()) }
        coVerify(exactly = 1) { queries.restoreCompactedMessagesBefore("session", 3) }
        coVerify(exactly = 1) { queries.deleteMessagesFromTimestamp("session", 3) }
    }

    @Test
    fun expectedSourceMustCoverExactlyTheDistinctHeadIds() = runTest {
        val original = row("head")
        rows[original.id] = original
        val fingerprint = CompactionSourceFingerprint.of(original)
        val before = rows.toMap()

        for (expected in listOf(emptyMap(), mapOf("head" to fingerprint, "extra" to fingerprint))) {
            val failure = runCatching { commit(listOf("head"), expectedSource = expected) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(before, rows)
        }

        coVerify(exactly = 0) { queries.countActiveCompactionMessages(any(), any()) }
        coVerify(exactly = 0) { queries.getMessageById(any()) }
        assertNoWrites()
    }

    @Test
    fun sameIdWithChangedSourceIsRejectedBeforeAnyWrite() = runTest {
        val original = row("head")
        val expected = mapOf(original.id to CompactionSourceFingerprint.of(original))
        for (changed in listOf(
            original.copy(content = "Changed body"),
            original.copy(timestamp = 2),
            original.copy(modelInputSnapshotJson = "{\"version\":1}")
        )) {
            rows[original.id] = changed
            val before = rows.toMap()

            val failure = runCatching { commit(listOf("head"), expectedSource = expected) }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(before, rows)
        }
        assertNoWrites()
    }

    @Test
    fun unchangedExpectedSourceCommitsWithDuplicateHeadIdsAndUiTimingUpdates() = runTest {
        val original = row("head")
        val expected = mapOf(original.id to CompactionSourceFingerprint.of(original))
        val withTiming = original.copy(toolActionTitle = "Updated title", toolDurationMs = 100)
        rows[original.id] = withTiming

        commit(listOf("head", "head"), expectedSource = expected, expectedGeneration = 3)

        assertEquals(withTiming.copy(isCompacted = true, compactedBySummaryId = "summary"), rows["head"])
        compactionRows().forEach { assertEquals(it, rows[it.id]) }
        assertEquals(0, inputTokens["session"])
        coVerify(exactly = 1) { queries.countActiveCompactionMessages("session", listOf("head")) }
        coVerify(exactly = 1) { queries.getMessageById("head") }
        coVerify(exactly = 0) { queries.getMessagesBySessionOnce(any()) }
    }

    @Test
    fun changedGenerationRejectsCommitBeforeSourceReadsOrWrites() = runTest {
        val original = row("head")
        rows[original.id] = original
        val before = rows.toMap()

        val failure = runCatching {
            commit(listOf("head"), expectedSource = mapOf("head" to CompactionSourceFingerprint.of(original)), expectedGeneration = 2)
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(before, rows)
        coVerify(exactly = 0) { queries.countActiveCompactionMessages(any(), any()) }
        coVerify(exactly = 0) { queries.getMessageById(any()) }
        assertNoWrites()
    }

    @Test
    fun fingerprintIsStableAndCoversEveryReplayFieldIncludingTimestamp() {
        val original = row("head")
        val fingerprint = CompactionSourceFingerprint.of(original)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(fingerprint == CompactionSourceFingerprint.of(original.copy()))
        val changes = listOf(
            "id" to original.copy(id = "another"),
            "sessionId" to original.copy(sessionId = "other"),
            "role" to original.copy(role = "ASSISTANT"),
            "content" to original.copy(content = "Changed"),
            "timestamp" to original.copy(timestamp = 2),
            "toolCallsJson" to original.copy(toolCallsJson = "[]"),
            "toolCallId" to original.copy(toolCallId = "call"),
            "toolName" to original.copy(toolName = "readFile"),
            "reasoning" to original.copy(reasoning = "Reasoning"),
            "signature" to original.copy(signature = "Signature"),
            "attachmentsJson" to original.copy(attachmentsJson = "[]"),
            "thinkingBlocksJson" to original.copy(thinkingBlocksJson = "[]"),
            "modelInputSnapshotJson" to original.copy(modelInputSnapshotJson = "{}"),
            "isCompacted" to original.copy(isCompacted = true),
            "isContextExcluded" to original.copy(isContextExcluded = true),
            "isContextSummary" to original.copy(isContextSummary = true),
            "isCompactionMarker" to original.copy(isCompactionMarker = true),
            "compactedBySummaryId" to original.copy(compactedBySummaryId = "older")
        )
        for ((field, changed) in changes) {
            assertTrue("Fingerprint must include $field", fingerprint != CompactionSourceFingerprint.of(changed))
        }
        val uiOnly = original.copy(
            toolActionTitle = "Title", toolStartedAtMs = 1, toolDurationMs = 2, turnDurationMs = 3
        )
        assertTrue(fingerprint == CompactionSourceFingerprint.of(uiOnly))
    }

    @Test
    fun fingerprintLengthPrefixesSeparateNullEmptyAndAdjacentUtf8Fields() {
        val original = row("head")
        assertTrue(CompactionSourceFingerprint.of(original) != CompactionSourceFingerprint.of(original.copy(reasoning = "")))
        val first = original.copy(toolCallId = "调用", toolName = "工具")
        val second = original.copy(toolCallId = "调", toolName = "用工具")
        assertTrue(CompactionSourceFingerprint.of(first) != CompactionSourceFingerprint.of(second))
    }

    private fun assertNoWrites() {
        coVerify(exactly = 0) { queries.markMessagesCompactedByIds(any(), any(), any()) }
        coVerify(exactly = 0) { queries.insertAll(any()) }
        coVerify(exactly = 0) { queries.resetLastInputTokens(any()) }
    }

    private suspend fun commit(
        headIds: List<String>,
        messages: List<AgentMessageEntity> = compactionRows(),
        expectedSource: Map<String, String>? = null,
        expectedGeneration: Long? = null
    ) {
        dao.commitCompaction("session", headIds, messages, "summary", expectedSource, expectedGeneration)
    }

    private fun compactionRows(): List<AgentMessageEntity> = listOf(
        row("marker", 10).copy(content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true),
        row("summary", 11).copy(role = "ASSISTANT", content = "Summary", isContextSummary = true)
    )

    private fun row(id: String, timestamp: Long = 1): AgentMessageEntity =
        AgentMessageEntity(id = id, sessionId = "session", role = "USER", content = "Original", timestamp = timestamp)
}
