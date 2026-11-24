package com.aicode.feature.agent.domain.workflow

import androidx.room.InvalidationTracker
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.CompactionSourceFingerprint
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ModelInputSnapshot
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CompactionCheckpointResumeTest {
    private data class Sent(val system: String, val content: String, val ordinal: Int) {
        val key: Pair<String, String> get() = system to content
        val leaf: Boolean get() = "<history-material " in content
        val material: String get() = content.substringAfter("<history-material ").substringAfter(">\n")
            .removeSuffix("\n</history-material>")
        val level: Int get() = content.substringAfter("<summary-material level=\"").substringBefore('"').toInt()
        val mergeText: String get() = Regex("<summary-unit index=\"\\d+\">\\n([\\s\\S]*?)\\n</summary-unit>")
            .findAll(content).joinToString("") { it.groupValues[1].substringAfter('\n').removeSuffix("\n[unit continues]") }
    }

    private fun stopped(content: String = "summary") = AIResponse(content, stopReason = "stop", inputTokens = 23, outputTokens = 7)
    private fun complete(content: String = "summary") = flowOf(AIStreamChunk.Final(stopped(content)))
    private fun invalid() = flowOf(AIStreamChunk.Final(AIResponse("REJECTED_BODY", stopReason = "incomplete")))
    private fun history(size: Int = 14_000) = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(size)),
        AgentMessage.AssistantMessage(id = "recent", content = "recent answer"),
        AgentMessage.UserMessage(id = "goal", content = "continue"),
    )

    private data class Commit(
        val sessionId: String,
        val headIds: List<String>,
        val rows: List<AgentMessageEntity>,
        val summaryId: String,
        val expectedSource: Map<String, String>?,
        val expectedGeneration: Long?,
    )

    private class Fixture(scope: TestScope, materialTokens: Int = 4_096) {
        val dao = mockk<AgentMessageDao>(relaxed = true)
        val recordsDao = mockk<LlmCallRecordDao>(relaxed = true)
        val metadata = mockk<ModelMetadataService>()
        val prompts = mockk<SystemPromptProvider>()
        val settings = mockk<GeneralSettingsRepository>()
        val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
        val provider = mockk<AIProvider>(relaxed = true)
        val compactor = ContextCompactor(dao, metadata, prompts, recordsDao, settings, persistence)
        val store = CompactionCheckpoints(now = { scope.testScheduler.currentTime })
        val requests = mutableListOf<Sent>()
        val accepted = mutableListOf<Sent>()
        val records = mutableListOf<LlmCallRecordEntity>()
        val commits = mutableListOf<Commit>()
        val rows = mutableMapOf<String, List<AgentMessageEntity>>()
        var providerId = "provider"
        var model = "summary"
        var endpoint = "https://model.invalid/v1"
        var temperature: Float? = null
        var prompt = "{{INSTRUCTION}}"
        var generation = 1L
        private var outputLimit: Int? = 8_000
        private var timestamp = 1_000L
        var respond: (Sent) -> Flow<AIStreamChunk> = { flowOf(AIStreamChunk.Final(AIResponse("summary", stopReason = "stop"))) }
        var onCommit: suspend (Commit) -> Unit = {}

        init {
            ContextCompactor::class.java.getDeclaredField("checkpoints").apply { isAccessible = true }.set(compactor, store)
            compactor.streamPolicy = CompactionStreamPolicy(materialTokens = materialTokens, retryDelayMs = 0)
            every { provider.providerId } answers { providerId }
            every { provider.model } answers { model }
            every { provider.baseUrl } answers { endpoint }
            every { provider.useFullUrl } returns false
            every { provider.useResponseApi } returns false
            every { provider.customHeaders } returns emptyMap()
            every { provider.temperature } answers { temperature }
            every { provider.reasoningCapability } returns null
            every { provider.maxOutputTokens } answers { outputLimit }
            every { provider.maxOutputTokens = any() } answers { outputLimit = firstArg() }
            coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("summary", contextTokens = 16_000)
            coEvery { settings.compactionThresholdPercent() } returns 90
            every { prompts.resolvePrompt(any()) } answers { prompt }
            every { persistence.nextTimestamp() } answers { timestamp++ }
            coEvery { persistence.matchesCompactionSource(any(), any()) } returns true
            coEvery { dao.compactionGeneration(any()) } answers { generation }
            coEvery { dao.getMessagesBySessionOnce(any()) } answers { rows[firstArg()].orEmpty() }
            coEvery { recordsDao.insert(any()) } answers { records += firstArg<LlmCallRecordEntity>() }
            coEvery { dao.commitCompaction(any(), any(), any(), any(), any(), any()) } coAnswers {
                val commit = Commit(firstArg(), secondArg(), thirdArg(), arg(3), arg(4), arg(5))
                commits += commit
                onCommit(commit)
            }
            every { provider.completeStream(any(), any(), any(), "none") } answers {
                val sent = Sent(firstArg(), (secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content, requests.size + 1)
                requests += sent
                flow {
                    respond(sent).collect { chunk ->
                        if (chunk is AIStreamChunk.Final && chunk.response.stopReason == "stop") accepted += sent
                        emit(chunk)
                    }
                }
            }
        }

        fun persist(messages: List<AgentMessage>, sessionId: String = "s") {
            rows[sessionId] = messages.mapIndexed { index, message ->
                AgentMessageEntity(message.id, sessionId,
                    if (message is AgentMessage.UserMessage) "USER" else "ASSISTANT",
                    when (message) {
                        is AgentMessage.UserMessage -> message.content
                        is AgentMessage.AssistantMessage -> message.content
                        else -> error("Unexpected fixture message")
                    }, index.toLong())
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun work(sessionId: String = "s"): CompactionCheckpoints.Work =
            (CompactionCheckpoints::class.java.getDeclaredField("entries").apply { isAccessible = true }
                .get(store) as Map<String, CompactionCheckpoints.Work>).getValue(sessionId)

        suspend fun run(messages: List<AgentMessage>, sessionId: String = "s", onEvent: suspend (AgentEvent) -> Unit = {}) =
            compactor.compactIfNeeded(messages, provider, sessionId = sessionId, force = true, onEvent = onEvent)
    }

    @Test fun rollingResumeReplaysEightCommittedShrunkBlocksAndDoesNotDuplicateStatistics() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        f.respond = { sent -> when (sent.ordinal) {
            1, 2 -> flowOf(AIStreamChunk.Final(AIResponse("REJECTED_TRUNCATION", stopReason = "length")))
            11 -> invalid()
            else -> complete("SUMMARY_${sent.ordinal}")
        } }
        assertSame(original, f.run(original).messages)
        assertEquals(8, f.work().completedCount)
        assertEquals(11, f.requests.size)
        assertEquals(11, f.records.size)
        val failed = f.requests.last()
        val cached = (0 until 8).map { f.work().block(it)!! }
        assertTrue(cached.all { it.materialBudget <= 1_024 })
        assertEquals(f.accepted.map { CompactionFingerprint().add(it.system).add(it.content).finish() }, cached.map { it.request })
        f.respond = { complete("FRESH_${it.ordinal}") }
        val result = f.run(original)
        assertTrue(result.compacted)
        assertEquals(failed.key, f.requests[11].key)
        assertEquals(1, f.requests.count { it.key == f.requests[2].key })
        assertEquals(f.requests.size, f.records.size)
        assertEquals(8, f.records.take(11).count { it.status == "success" })
        assertTrue(f.requests.none { "REJECTED_TRUNCATION" in it.content || "REJECTED_BODY" in it.content })
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(original.first())), f.accepted.map(Sent::material))
        assertEquals(0, f.store.size())
        assertEquals(1, f.commits.size)
    }

    @Test fun upstreamOutcomeUnknownRetriesFailedBlockWithoutRepeatingCompletedPrefix() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        f.respond = { sent -> if (sent.ordinal == 2) flow {
            emit(AIStreamChunk.TextDelta("REJECTED_PARTIAL"))
            throw StreamApiException("upstream_ws_outcome_unknown", "private upstream detail")
        } else complete("SUMMARY_${sent.ordinal}") }
        val events = mutableListOf<AgentEvent>()
        val result = f.run(original, onEvent = { events += it })
        assertTrue(result.compacted)
        assertEquals(f.requests[1].key, f.requests[2].key)
        assertEquals(1, f.requests.count { it.key == f.requests.first().key })
        assertEquals("compaction_upstream_interrupted", f.records[1].errorMessage)
        assertEquals("error", f.records[1].status)
        assertEquals("success", f.records[2].status)
        assertEquals(f.requests.size, f.records.size)
        assertTrue(events.none { it is AgentEvent.CompactionFailed })
        assertTrue(events.filterIsInstance<AgentEvent.CompactionProgress>().any {
            it.progress.lastErrorCode == "compaction_upstream_interrupted"
        })
        assertTrue(f.requests.none { "REJECTED_PARTIAL" in it.content || "private upstream detail" in it.content })
        assertEquals(1, f.commits.size)
        assertTrue(f.commits.single().rows.none { "REJECTED_PARTIAL" in it.content })
        assertEquals(0, f.store.size())
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(original.first())), f.accepted.map(Sent::material))
    }

    @Test fun smallWindowLargePromptCachesConfiguredLimitInsteadOfEffectiveBudget() = runTest {
        val f = Fixture(this, materialTokens = 65_536)
        val original = history()
        f.persist(original)
        f.prompt = "Custom handoff rules. " + "规".repeat(7_000) + " {{INSTRUCTION}}"
        f.respond = { if (it.ordinal == 3) invalid() else complete("COMMITTED_${it.ordinal}") }
        assertFalse(f.run(original).compacted)
        assertEquals(2, f.work().completedCount)
        assertEquals(listOf(65_536, 65_536), (0..1).map { f.work().block(it)!!.materialBudget })
        assertTrue(f.accepted.all { CompactionText.tokens(it.material) < 10_000 })
        val failed = f.requests.last().key
        f.respond = { complete("FRESH_${it.ordinal}") }
        assertTrue(f.run(original).compacted)
        assertEquals(failed, f.requests[3].key)
        assertEquals(1, f.requests.count { it.key == f.requests.first().key })
        assertEquals(f.requests.size, f.records.size)
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(original.first())), f.accepted.map(Sent::material))
    }

    @Test fun missingStopOrMissingFinalResendsFailedBlockWithoutPublishingItsBody() = runTest {
        for ((code, bad) in listOf<Pair<String, () -> Flow<AIStreamChunk>>>(
            "compaction_missing_stop" to { flowOf(AIStreamChunk.Final(AIResponse("REJECTED_BODY"))) },
            "compaction_no_final" to { flow { emit(AIStreamChunk.TextDelta("REJECTED_PARTIAL")) } },
        )) {
            val f = Fixture(this)
            val original = history()
            f.persist(original)
            f.respond = { if (it.ordinal == 2) bad() else complete("COMMITTED_SUMMARY") }
            val events = mutableListOf<AgentEvent>()
            assertFalse(f.run(original, onEvent = { events += it }).compacted)
            assertEquals(code, events.filterIsInstance<AgentEvent.CompactionFailed>().single().reason)
            assertEquals(1, f.work().completedCount)
            assertEquals(2, f.records.size)
            assertEquals(code, f.records.last().errorMessage)
            val failed = f.requests.last().key
            f.respond = { complete("FRESH") }
            assertTrue(f.run(original).compacted)
            assertEquals(failed, f.requests[2].key)
            assertEquals(1, f.requests.count { it.key == f.requests.first().key })
            assertTrue(f.requests.none { "REJECTED_BODY" in it.content || "REJECTED_PARTIAL" in it.content })
            assertEquals(f.requests.size, f.records.size)
        }
    }

    @Test fun sessionProviderModelPromptSourceGenerationEndpointAndParameterChangesNeverReuseWork() = runTest {
        for (change in listOf("session", "provider", "model", "prompt", "model-source", "persisted-source", "generation", "endpoint", "parameter")) {
            val f = Fixture(this)
            var original = history()
            f.persist(original)
            f.respond = { if (it.ordinal == 2) invalid() else complete("OLD_SUMMARY") }
            assertFalse("initial $change", f.run(original).compacted)
            val first = f.requests.first()
            val oldWork = f.work()
            var sessionId = "s"
            when (change) {
                "session" -> { sessionId = "other"; f.persist(original, sessionId) }
                "provider" -> f.providerId = "other-provider"
                "model" -> f.model = "other-model"
                "prompt" -> f.prompt = "Changed handoff template. {{INSTRUCTION}}"
                "model-source" -> original = original.map { if (it is AgentMessage.UserMessage && it.id == "old") it.copy(content = "新" + it.content.drop(1)) else it }
                "persisted-source" -> f.rows["s"] = f.rows.getValue("s").map { if (it.id == "old") it.copy(modelInputSnapshotJson = "{\"snapshot\":\"changed\"}") else it }
                "generation" -> f.generation++
                "endpoint" -> f.endpoint = "https://other.invalid/v1"
                "parameter" -> f.temperature = 0.7f
            }
            f.respond = { complete("NEW_SUMMARY") }
            assertTrue("changed $change", f.run(original, sessionId).compacted)
            val restarted = f.requests[2]
            assertFalse("$change reused previous summary", "OLD_SUMMARY" in restarted.content)
            assertTrue("$change did not restart first block", "<history-material block=\"1\">" in restarted.content)
            if (change !in listOf("prompt", "model-source")) assertEquals(change, first.key, restarted.key)
            assertEquals(change, f.requests.size, f.records.size)
            if (change == "session") assertSame(oldWork, f.work("s")) else assertEquals(0, f.store.size())
        }
    }

    @Test fun switchingBackToAnOldModelCannotResurrectItsDiscardedPrefix() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        f.respond = { if (it.ordinal % 2 == 0) invalid() else complete("MODEL_${f.model}") }
        assertFalse(f.run(original).compacted)
        val originalWork = f.work()
        f.model = "other-model"
        assertFalse(f.run(original).compacted)
        assertNotSame(originalWork, f.work())
        f.model = "summary"
        f.respond = { complete("FRESH") }
        assertTrue(f.run(original).compacted)
        assertEquals(f.requests[0].key, f.requests[4].key)
        assertEquals(f.requests.size, f.records.size)
    }

    @Test fun expiredWorkStartsFromFirstBlockAtExactTtlBoundary() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        f.respond = { if (it.ordinal == 2) invalid() else complete("OLD_SUMMARY") }
        assertFalse(f.run(original).compacted)
        advanceTimeBy(60 * 60_000L)
        f.respond = { complete("FRESH") }
        assertTrue(f.run(original).compacted)
        assertEquals(f.requests.first().key, f.requests[2].key)
        assertEquals(f.requests.size, f.records.size)
    }

    @Test fun cancelWithoutActiveOwnerStillDiscardsRetryableCheckpoint() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        f.respond = { if (it.ordinal == 2) invalid() else complete("OLD_SUMMARY") }
        assertFalse(f.run(original).compacted)
        assertEquals(1, f.store.size())
        assertFalse(f.compactor.cancelSession("s"))
        assertEquals(0, f.store.size())
        f.respond = { complete("FRESH") }
        assertTrue(f.run(original).compacted)
        assertEquals(f.requests.first().key, f.requests[2].key)
    }

    @Test fun activeCancellationDropsCommittedPrefixAndPartialCurrentBlock() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        val entered = CompletableDeferred<Unit>()
        f.respond = { if (it.ordinal == 2) flow {
            emit(AIStreamChunk.TextDelta("REJECTED_PARTIAL"))
            entered.complete(Unit)
            awaitCancellation()
        } else complete("OLD_SUMMARY") }
        val job = async { f.run(original) }
        entered.await()
        assertEquals(1, f.work().completedCount)
        assertTrue(f.compactor.cancelSession("s"))
        try {
            job.await()
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertTrue(job.isCancelled)
        }
        assertEquals(0, f.store.size())
        assertTrue(f.commits.isEmpty())
        assertEquals(2, f.records.size)
        assertEquals("compaction_cancelled", f.records.last().errorMessage)
        f.respond = { complete("FRESH") }
        assertTrue(f.run(original).compacted)
        assertEquals(f.requests.first().key, f.requests[2].key)
    }

    @Test fun successfulCommitClearsCacheEvenWhenHistoryInvalidationThrows() = runTest {
        for (invalidationFails in listOf(false, true)) {
            val f = Fixture(this)
            val original = history()
            f.persist(original)
            f.respond = { if (it.ordinal == 2) invalid() else complete("OLD_SUMMARY") }
            assertFalse(f.run(original).compacted)
            if (invalidationFails) every { f.persistence.invalidateHistory("s") } throws IllegalStateException("cache unavailable")
            f.respond = { complete("FRESH") }
            assertTrue(f.run(original).compacted)
            assertEquals(1, f.commits.size)
            assertEquals("saved work must be removed even when invalidation fails", 0, f.store.size())
            val before = f.requests.size
            assertTrue(f.run(original).compacted)
            assertEquals(f.requests.first().key, f.requests[before].key)
        }
    }

    @Test fun sourceMismatchBeforePreparingRequestsClearsCheckpointAndNeverDispatchesOrSaves() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        f.respond = { if (it.ordinal == 2) invalid() else complete("OLD_SUMMARY") }
        assertFalse(f.run(original).compacted)
        assertEquals(1, f.store.size())
        val requestsBefore = f.requests.size
        val entities = f.rows.getValue("s")
        coEvery { f.persistence.matchesCompactionSource(any(), any()) } returns false
        try {
            f.run(original)
            fail("A changed source must propagate cancellation")
        } catch (expected: CancellationException) {
            assertTrue(expected.message.orEmpty().contains("source", ignoreCase = true))
        }
        coVerify(exactly = 2) { f.persistence.matchesCompactionSource(listOf(original.first()), entities) }
        assertEquals(requestsBefore, f.requests.size)
        assertEquals(requestsBefore, f.records.size)
        assertTrue(f.commits.isEmpty())
        assertEquals(0, f.store.size())
        verify(exactly = 0) { f.persistence.invalidateHistory(any()) }
    }

    @Test fun sourceChangedBeforeSavePassesFrozenContentFingerprintAndGenerationToDao() = runTest {
        val f = Fixture(this)
        val original = history()
        f.persist(original)
        val frozen = f.rows.getValue("s").first()
        val expected = mapOf("old" to CompactionSourceFingerprint.of(frozen))
        f.onCommit = { commit ->
            assertEquals("s", commit.sessionId)
            assertEquals(listOf("old"), commit.headIds)
            assertEquals(expected, commit.expectedSource)
            assertEquals(1L, commit.expectedGeneration)
            assertEquals(listOf(1_000L, 1_001L), commit.rows.map { it.timestamp })
            assertTrue(commit.rows[0].isCompactionMarker)
            assertTrue(commit.rows[1].isContextSummary)
            assertEquals(commit.rows[1].id, commit.summaryId)
            assertEquals("summary", commit.rows[1].content)
            assertNotEquals(expected.getValue("old"), CompactionSourceFingerprint.of(f.rows.getValue("s").first()))
            throw CancellationException("Compaction source changed")
        }
        try {
            f.run(original, onEvent = { event ->
                if (event is AgentEvent.CompactionProgress && event.progress.phase == CompactionPhase.SAVING) {
                    f.rows["s"] = f.rows.getValue("s").map { if (it.id == "old") it.copy(content = "same ID, changed after prepare") else it }
                    f.generation = 2
                }
            })
            fail("DAO source CAS rejection must propagate")
        } catch (expectedCancellation: CancellationException) {
            assertEquals("Compaction source changed", expectedCancellation.message)
        }
        assertEquals(1, f.commits.size)
        assertEquals(0, f.store.size())
        verify(exactly = 0) { f.persistence.invalidateHistory(any()) }
        coVerify(exactly = 1) { f.dao.commitCompaction("s", listOf("old"), any(), any(), expected, 1L) }
    }

    private fun realPersistence(): MessagePersistenceUseCase {
        val tracker = mockk<InvalidationTracker>(relaxed = true)
        val database = mockk<AgentDatabase> { every { invalidationTracker } returns tracker }
        return MessagePersistenceUseCase(mockk<AgentMessageDao>(relaxed = true), database)
    }

    @Test fun realSourceReplayMatchesPlainUserButRejectsSameIdContentAndTimestampOrderChanges() = runTest {
        val persistence = realPersistence()
        val rows = listOf(AgentMessageEntity("one", "s", "USER", "first", 1),
            AgentMessageEntity("two", "s", "USER", "second", 2))
        val messages = listOf(AgentMessage.UserMessage("one", "first"), AgentMessage.UserMessage("two", "second"))
        assertTrue(persistence.matchesCompactionSource(messages, rows))
        assertFalse(persistence.matchesCompactionSource(messages, rows.map { if (it.id == "one") it.copy(content = "changed") else it }))
        assertFalse(persistence.matchesCompactionSource(messages,
            rows.map { if (it.id == "one") it.copy(timestamp = 3) else it }.sortedBy { it.timestamp }))
        assertFalse(persistence.matchesCompactionSource(messages, rows.map { if (it.id == "one") it.copy(isCompacted = true) else it }))
        assertFalse(persistence.matchesCompactionSource(messages, rows.map { if (it.id == "one") it.copy(isContextExcluded = true) else it }))
    }

    @Test fun realSourceReplayUsesFrozenModelInputInsteadOfDisplayedUserBody() = runTest {
        val persistence = realPersistence()
        val snapshot = ModelInputSnapshot(content = "actual model request")
        val row = AgentMessageEntity("user", "s", "USER", "displayed body", 1,
            modelInputSnapshotJson = Json.encodeToString(snapshot))
        val messages = snapshot.messages("user", emptyList(), persisted = true)
        assertTrue(persistence.matchesCompactionSource(messages, listOf(row)))
        assertTrue(persistence.matchesCompactionSource(messages, listOf(row.copy(content = "different display, same frozen input"))))
        assertFalse(persistence.matchesCompactionSource(listOf(AgentMessage.UserMessage("user", "displayed body")), listOf(row)))
        val changed = row.copy(modelInputSnapshotJson = Json.encodeToString(snapshot.copy(content = "changed model request")))
        assertFalse(persistence.matchesCompactionSource(messages, listOf(changed)))
    }

    @Test fun realSourceReplayRejectsChangedOrNewlyEmittedProjectMemoryWithSameOwnerBody() = runTest {
        val persistence = realPersistence()
        val snapshot = ModelInputSnapshot(content = "unchanged owner", projectMemoryContext = "OLD_MEMORY")
        fun row(value: ModelInputSnapshot) = AgentMessageEntity("owner", "s", "USER", "display", 1,
            modelInputSnapshotJson = Json.encodeToString(value))
        val messages = snapshot.messages("owner", emptyList(), persisted = true)
        assertTrue(persistence.matchesCompactionSource(messages, listOf(row(snapshot))))
        assertFalse(persistence.matchesCompactionSource(messages, listOf(row(snapshot.copy(projectMemoryContext = "NEW_MEMORY")))))
        val notEmitted = snapshot.copy(emitProjectMemory = false)
        assertTrue(persistence.matchesCompactionSource(notEmitted.messages("owner", emptyList(), persisted = true), listOf(row(notEmitted))))
        assertFalse(persistence.matchesCompactionSource(notEmitted.messages("owner", emptyList(), persisted = true), listOf(row(snapshot))))
        val prefix = messages.first() as AgentMessage.UserMessage
        assertFalse(persistence.matchesCompactionSource(listOf(prefix.copy(modelInputOwnerId = "other"), messages.last()), listOf(row(snapshot))))
    }

    @Test fun realSourceReplayMatchesPairedAssistantCallsAndCompleteModelToolProjection() = runTest {
        val persistence = realPersistence()
        val call = ToolCall("call", "writeFile", emptyMap())
        val orphan = ToolCall("orphan", "readFile", emptyMap())
        val transport = """{"status":"success","data":{"status":"written","path":"/repo/a.kt","created":"true","added_lines":3,"removed_lines":0,"lines_written":3}}"""
        val projection = requireNotNull(modelToolResultText("writeFile", transport))
        assertTrue(projection.contains("/repo/a.kt"))
        val rows = listOf(
            AgentMessageEntity("assistant", "s", "ASSISTANT", "writing", 1, toolCallsJson = Json.encodeToString(listOf(call, orphan))),
            AgentMessageEntity("tool_call", "s", "TOOL", transport, 2, toolCallId = "call", toolName = "writeFile"),
        )
        val assistant = AgentMessage.AssistantMessage("assistant", "writing", toolCalls = listOf(call))
        val tool = AgentMessage.ToolResultMessage("call", "writeFile", "live transport envelope", modelResult = projection)
        val messages = listOf(assistant, tool)
        assertTrue(persistence.matchesCompactionSource(messages, rows))
        assertFalse(persistence.matchesCompactionSource(listOf(assistant.copy(toolCalls = listOf(call, orphan)), tool), rows))
        assertFalse(persistence.matchesCompactionSource(listOf(assistant, tool.copy(modelResult = projection + " changed")), rows))
        assertFalse(persistence.matchesCompactionSource(messages, rows.map { if (it.role == "TOOL") it.copy(content = transport.replace("/repo/a.kt", "/repo/b.kt")) else it }))
    }

    @Test fun sourceFingerprintChangesForSameIdModelMaterialAndPersistenceStateMutations() {
        val row = AgentMessageEntity("same", "s", "ASSISTANT", "body", 1)
        val original = CompactionSourceFingerprint.of(row)
        for (changed in listOf(
            row.copy(content = "changed"), row.copy(timestamp = 2), row.copy(toolCallsJson = "[]"),
            row.copy(toolCallId = "call"), row.copy(toolName = "readFile"), row.copy(reasoning = "reasoning"),
            row.copy(signature = "signature"), row.copy(attachmentsJson = "[]"), row.copy(thinkingBlocksJson = "[]"),
            row.copy(modelInputSnapshotJson = "{}"), row.copy(isCompacted = true), row.copy(isContextExcluded = true),
            row.copy(isContextSummary = true), row.copy(isCompactionMarker = true), row.copy(compactedBySummaryId = "summary"),
        )) assertNotEquals(changed.toString(), original, CompactionSourceFingerprint.of(changed))
    }

    private inner class HierarchyProbe(
        scope: TestScope,
        val source: List<String> = List(16) { "汉".repeat(1_000) },
        val cap: Int = 1_024,
        totalTimeoutMs: Long = 35 * 60_000L,
        private val leafTemplate: String = "leaf",
        private val mergeTemplate: String = "merge",
    ) {
        private val policy = CompactionStreamPolicy(materialTokens = cap, totalTimeoutMs = totalTimeoutMs, retryDelayMs = 0)
        private val store = CompactionCheckpoints(now = { scope.testScheduler.currentTime })
        val work = store.open("s", "hierarchical-work")
        val requests = mutableListOf<Sent>()
        val accepted = mutableListOf<Pair<Sent, String>>()
        val records = mutableListOf<CompactionStreamAttempt>()
        private val provider = mockk<AIProvider>(relaxed = true)
        private val now = { scope.testScheduler.currentTime }
        lateinit var session: CompactionStreamSession
        var respond: suspend (Sent) -> AIResponse = { stopped(if (it.leaf) "LEAF_${it.ordinal}" else "MERGE_${it.ordinal}") }

        init {
            every { provider.completeStream(any(), any(), any(), "none") } answers {
                val sent = Sent(firstArg(), (secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content, requests.size + 1)
                requests += sent
                flow {
                    emit(AIStreamChunk.TextDelta("UNCOMMITTED_PARTIAL"))
                    val response = respond(sent)
                    if (response.stopReason == "stop") accepted += sent to response.content
                    emit(AIStreamChunk.Final(response))
                }
            }
        }

        suspend fun run(): HierarchicalCompaction.Result {
            session = CompactionStreamSession(policy, now).also { it.restore(work) { store.updated("s", work) } }
            return session.withDeadline { HierarchicalCompaction.summarize(
                source = source, previousSummary = null, system = "system", leafTemplate = leafTemplate, mergeTemplate = mergeTemplate,
                summaryBudget = 16_000, contextKnown = true, configuredLimit = cap, outputLimit = 8_192, longHistory = false,
                streamPolicy = policy, streamSession = session, provider = provider,
                onStreamUpdate = { _, _ -> }, onAttempt = { records += it },
                onLeafSuccess = { _, _ -> }, onMergeSuccess = { _, _ -> },
            ) }
        }
    }

    private suspend fun failure(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: CompactionStreamException) {
            assertEquals(code, error.code)
        }
    }

    @Test fun hierarchyLargeLeafPromptReplaysConfiguredCapWhenEffectiveBudgetIsSmaller() = runTest {
        val probe = HierarchyProbe(this, source = List(8) { "汉".repeat(5_000) }, cap = 65_536,
            leafTemplate = "规".repeat(7_000) + " {{INSTRUCTION}}")
        probe.respond = { if (it.ordinal == 6) AIResponse("REJECTED_BODY", stopReason = "incomplete") else stopped("SUMMARY_${it.ordinal}") }
        failure("compaction_invalid_response") { probe.run() }
        assertEquals(5, probe.work.completedCount)
        assertEquals(16_384, probe.work.block(0)!!.materialBudget)
        assertTrue(probe.accepted.filter { it.first.leaf }.all { CompactionText.tokens(it.first.material) < 10_000 })
        val failed = probe.requests.last().key
        probe.respond = { stopped("FRESH_${it.ordinal}") }
        val result = probe.run()
        assertEquals(failed, probe.requests[6].key)
        assertEquals(8, result.leafBlocks)
        assertEquals(probe.requests.size, probe.records.size)
        CompactionSummaryFixture.assertComplete(probe.source, probe.accepted.filter { it.first.leaf }.map { it.first.material })
    }

    @Test fun hierarchyLargeMergePromptReplaysConfiguredLimitWhenEffectiveBudgetIsSmaller() = runTest {
        val probe = HierarchyProbe(this, source = List(8) { "汉".repeat(1_000) },
            mergeTemplate = "规".repeat(7_000) + " {{INSTRUCTION}}")
        probe.respond = { when {
            it.leaf -> stopped("LEAF_${it.ordinal}_" + "摘".repeat(3_000))
            it.ordinal == 10 -> AIResponse("REJECTED_BODY", stopReason = "incomplete")
            else -> stopped("MERGE_${it.ordinal}")
        } }
        failure("compaction_invalid_response") { probe.run() }
        assertEquals(9, probe.work.completedCount)
        assertEquals(65_536, probe.work.block(8)!!.materialBudget)
        val failed = probe.requests.last().key
        probe.respond = { stopped("FRESH_${it.ordinal}") }
        val result = probe.run()
        assertEquals(failed, probe.requests[10].key)
        assertEquals(8, result.leafBlocks)
        assertEquals(probe.requests.size, probe.records.size)
        assertEquals(probe.accepted.filter { it.first.leaf }.joinToString("") { it.second },
            probe.accepted.filter { !it.first.leaf && it.first.level == 1 }.joinToString("") { it.first.mergeText })
    }

    @Test fun hierarchyInvalidMergeReusesSixteenLeavesAndThreeCommittedMerges() = runTest {
        val probe = HierarchyProbe(this)
        probe.respond = { if (it.ordinal == 20) AIResponse("REJECTED_BODY", stopReason = "incomplete")
            else stopped(if (it.leaf) "LEAF_${it.ordinal}" else "MERGE_${it.ordinal}") }
        failure("compaction_invalid_response") { probe.run() }
        assertEquals(19, probe.work.completedCount)
        assertEquals(20, probe.records.size)
        val failed = probe.requests.last().key
        probe.respond = { stopped("FRESH_${it.ordinal}") }
        val result = probe.run()
        assertEquals(failed, probe.requests[20].key)
        assertEquals(16, result.leafBlocks)
        assertEquals(5, result.completedMerges)
        assertEquals(2, result.mergeLevels)
        assertEquals(22, probe.requests.size)
        assertEquals(22, probe.records.size)
        assertEquals(22, probe.session.attempts)
        assertEquals(21, probe.work.completedCount)
        assertTrue(probe.requests.none { "REJECTED_BODY" in it.content || "UNCOMMITTED_PARTIAL" in it.content })
    }

    @Test fun hierarchyTotalTimeoutKeepsOnlyCommittedLeavesAndMergesAndResendsCurrentMerge() = runTest {
        val probe = HierarchyProbe(this, totalTimeoutMs = 100)
        probe.respond = {
            if (it.ordinal == 20) { delay(100); awaitCancellation() }
            stopped(if (it.leaf) "LEAF_${it.ordinal}" else "MERGE_${it.ordinal}")
        }
        failure("compaction_total_timeout") { probe.run() }
        assertEquals(100L, testScheduler.currentTime)
        assertEquals(19, probe.work.completedCount)
        assertEquals("compaction_total_timeout", probe.records.last().errorCode)
        val failed = probe.requests.last().key
        probe.respond = { stopped("FRESH_${it.ordinal}") }
        val result = probe.run()
        assertEquals(failed, probe.requests[20].key)
        assertEquals(16, result.leafBlocks)
        assertEquals(5, result.completedMerges)
        assertEquals(22, probe.requests.size)
        assertEquals(22, probe.records.size)
        assertEquals(22, probe.session.attempts)
        assertTrue(probe.records.last().errorCode == null)
    }

    @Test fun hierarchyShrunkLeafCacheReplaysExactRequestsAndPreservesAllSource() = runTest {
        val probe = HierarchyProbe(this, source = List(8) { "汉".repeat(1_000) })
        probe.respond = { when (it.ordinal) {
            1 -> AIResponse("REJECTED_TRUNCATION", stopReason = "length")
            8 -> AIResponse("REJECTED_BODY", stopReason = "incomplete")
            else -> stopped(if (it.leaf) "LEAF_${it.ordinal}" else "MERGE_${it.ordinal}")
        } }
        failure("compaction_invalid_response") { probe.run() }
        assertEquals(6, probe.work.completedCount)
        val firstMaterial = "[history-unit 1, character-offset 0]\n" + probe.source.first() + "\n\n"
        assertEquals(firstMaterial, probe.requests.first().material)
        assertEquals(1_013, CompactionText.tokens(firstMaterial))
        assertEquals(506, probe.work.block(0)!!.materialBudget)
        val failed = probe.requests.last().key
        val cached = (0 until probe.work.completedCount).map { probe.work.block(it)!!.request }
        probe.respond = { stopped("FRESH_${it.ordinal}") }
        val result = probe.run()
        assertEquals(failed, probe.requests[8].key)
        assertTrue(result.leafBlocks > 8)
        assertEquals(cached, (0 until cached.size).map { probe.work.block(it)!!.request })
        assertEquals(probe.requests.size, probe.records.size)
        CompactionSummaryFixture.assertComplete(probe.source, probe.accepted.filter { it.first.leaf }.map { it.first.material })
        assertTrue(probe.requests.none { "REJECTED_TRUNCATION" in it.content || "REJECTED_BODY" in it.content })
    }

    @Test fun hierarchyShrunkMergeCacheReplaysExactRequestsAndPreservesOrderedSummaryFragments() = runTest {
        val probe = HierarchyProbe(this, source = List(9) { "汉".repeat(1_000) })
        probe.respond = { when {
            it.leaf -> stopped("LEAF_${it.ordinal}_" + "摘".repeat(3_000))
            it.ordinal == 10 -> AIResponse("REJECTED_TRUNCATION", stopReason = "length")
            it.ordinal == 12 -> AIResponse("REJECTED_BODY", stopReason = "incomplete")
            else -> stopped("MERGE_${it.ordinal}")
        } }
        failure("compaction_invalid_response") { probe.run() }
        assertEquals(10, probe.work.completedCount)
        val shrunk = probe.work.block(9)!!
        assertTrue(shrunk.materialBudget < 12_000)
        val failed = probe.requests.last().key
        probe.respond = { stopped("FRESH_${it.ordinal}") }
        val result = probe.run()
        assertEquals(failed, probe.requests[12].key)
        assertEquals(shrunk, probe.work.block(9))
        assertEquals(9, result.leafBlocks)
        assertTrue(result.completedMerges > 3)
        var nodes = probe.accepted.filter { it.first.leaf }.map { it.second }
        assertTrue(nodes.last().startsWith("LEAF_9_"))
        for (level in 1..result.mergeLevels) {
            val produced = probe.accepted.filter { !it.first.leaf && it.first.level == level }
            val next = mutableListOf<String>()
            var merge = 0
            for (group in nodes.chunked(4)) {
                if (group.size == 1) {
                    next += group.single()
                    continue
                }
                val expected = group.joinToString("")
                var consumed = 0
                while (consumed < expected.length) {
                    assertTrue("Missing merge for level $level at character $consumed", merge < produced.size)
                    val (sent, output) = produced[merge++]
                    val material = sent.mergeText
                    assertTrue("Empty merge material at level $level", material.isNotEmpty())
                    assertTrue("Merge overran its group at level $level", consumed + material.length <= expected.length)
                    assertEquals(expected.substring(consumed, consumed + material.length), material)
                    consumed += material.length
                    next += output
                }
                assertEquals(expected.length, consumed)
            }
            assertEquals("Unexpected extra merge at level $level", produced.size, merge)
            nodes = next
        }
        assertEquals(listOf(result.summary), nodes)
        assertEquals(probe.requests.size, probe.records.size)
        assertEquals(probe.requests.size, probe.session.attempts)
        assertTrue(probe.requests.none { "REJECTED_TRUNCATION" in it.content || "REJECTED_BODY" in it.content })
    }
}
