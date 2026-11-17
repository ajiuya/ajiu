package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompactionProvenanceTest {
    private class Fixture {
        val dao = mockk<AgentMessageDao>(relaxed = true)
        private val records = mockk<LlmCallRecordDao>(relaxed = true)
        private val metadata = mockk<ModelMetadataService>()
        private val prompts = mockk<SystemPromptProvider>()
        private val settings = mockk<GeneralSettingsRepository>()
        private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
        val compactor = ContextCompactor(dao, metadata, prompts, records, settings, persistence)
        val provider = mockk<AIProvider>(relaxed = true)
        var entities = emptyList<AgentMessageEntity>()
        val requests = mutableListOf<String>()
        var committedIds = emptyList<String>()
        var committedRows = emptyList<AgentMessageEntity>()

        init {
            compactor.streamPolicy = CompactionStreamPolicy(retryDelayMs = 0)
            coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("summary", contextTokens = 16_000)
            coEvery { settings.compactionThresholdPercent() } returns 90
            every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
            every { provider.providerId } returns "provider"
            every { provider.model } returns "summary"
            every { provider.maxOutputTokens } returns 8_000
            every { provider.completeStream(any(), any(), any(), any()) } answers {
                requests.add((secondArg<List<AgentMessage>>().single() as AgentMessage.UserMessage).content)
                flowOf(AIStreamChunk.Final(AIResponse("Short handoff", stopReason = "stop")))
            }
            coEvery { dao.getMessagesBySessionOnce("s") } answers { entities }
            coEvery { dao.commitCompaction("s", any(), any(), any()) } coAnswers { committedIds = secondArg(); committedRows = thirdArg() }
        }

        fun rows(history: List<AgentMessage>) {
            entities = history.filterNot { it is AgentMessage.UserMessage && it.isTransient }.mapIndexed { index, message ->
                AgentMessageEntity(id = message.id, sessionId = "s", role = if (message is AgentMessage.UserMessage) "USER" else "ASSISTANT",
                    content = when (message) { is AgentMessage.UserMessage -> message.content; is AgentMessage.AssistantMessage -> message.content; else -> "" },
                    timestamp = index.toLong(), isCompactionMarker = (message as? AgentMessage.UserMessage)?.isCompactionMarker == true,
                    isContextSummary = (message as? AgentMessage.AssistantMessage)?.isContextSummary == true)
            }
        }
        suspend fun compact(history: List<AgentMessage>) = compactor.compactIfNeeded(history, provider, sessionId = "s", force = true)
        fun assertNoDispatchOrCommit() {
            assertTrue(requests.isEmpty())
            coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        }
    }
    private fun history() = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(20_000)),
        AgentMessage.AssistantMessage(id = "recent-answer", content = "done"),
        AgentMessage.UserMessage(id = "current", content = "continue"),
    )

    @Test fun ordinarySameTextRecapDoesNotReplaceFlaggedPreviousSummary() = runTest {
        val f = Fixture()
        val previous = "  UNIQUE_OLD_FACT_42 path=/project/do-not-drop\n" + "旧".repeat(4_000) + " exact-END"
        val original = listOf(
            AgentMessage.UserMessage(id = "saved-marker", content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true),
            AgentMessage.AssistantMessage(id = "saved-summary", content = previous, isContextSummary = true),
            AgentMessage.UserMessage(id = "ordinary-question", content = CONTEXT_COMPACTION_MARKER),
            AgentMessage.AssistantMessage(id = "ordinary-answer", content = "ONLY_ORDINARY_ANSWER_71"),
        ) + history()
        f.rows(original)
        assertTrue(f.compact(original).compacted)
        assertTrue(f.requests.first().contains("<previous-summary>\n$previous\n</previous-summary>"))
        assertTrue(f.requests.any { it.contains("[user id=ordinary-question]\n$CONTEXT_COMPACTION_MARKER") })
        assertTrue(f.requests.any { it.contains("ONLY_ORDINARY_ANSWER_71") })
        assertTrue("saved-summary" in f.committedIds)
        assertTrue("ordinary-answer" in f.committedIds)
    }

    @Test fun ordinaryLegacyPrefixIsHistoricalMaterialWithoutWhitespaceLoss() = runTest {
        val f = Fixture()
        val ordinary = CONTEXT_SUMMARY_LEGACY_PREFIX + "\n  NOT_A_SUMMARY_UNIQUE_83"
        val original = listOf(AgentMessage.AssistantMessage(id = "ordinary-prefix", content = ordinary)) + history()
        f.rows(original)
        assertTrue(f.compact(original).compacted)
        assertTrue(f.requests.any { it.contains("[assistant id=ordinary-prefix]\n$ordinary") })
        assertTrue(f.requests.none { it.contains("<previous-summary>\n  NOT_A_SUMMARY_UNIQUE_83") })
    }

    @Test fun internalMarkerDoesNotConsumeUnflaggedNeighborAssistant() = runTest {
        val f = Fixture()
        val original = listOf(
            AgentMessage.UserMessage(id = "orphan-marker", content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true),
            AgentMessage.AssistantMessage(id = "ordinary-answer", content = "KEEP_ORPHAN_NEIGHBOR_92"),
        ) + history()
        f.rows(original)
        assertTrue(f.compact(original).compacted)
        assertTrue(f.requests.any { it.contains("KEEP_ORPHAN_NEIGHBOR_92") })
    }

    @Test fun latestOrdinarySameTextQuestionIsRetainedUserInput() {
        val original = listOf(AgentMessage.UserMessage(content = "汉".repeat(20_000)),
            AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER), AgentMessage.AssistantMessage(content = "recent answer"))
        assertEquals(1, CompactionText.selectTailStartIndex(original, 14_400))
        assertEquals(2, CompactionText.adjustSplitIndex(original, 2))
    }

    @Test fun transientHeadStaysInMaterialButNotCommittedIds() = runTest {
        val f = Fixture()
        val temporary = AgentMessage.UserMessage(id = "", content = "TRANSIENT_UNIQUE_15 " + "忆".repeat(2_000), isTransient = true)
        val original = listOf(temporary) + history()
        f.rows(original)
        assertTrue(f.compact(original).compacted)
        assertTrue(f.requests.any { it.contains(temporary.content) })
        assertEquals(listOf("old"), f.committedIds)
    }

    @Test fun ordinaryMissingIdsCannotUsePrefixOrIntersectionToValidate() = runTest {
        for (id in listOf("", "missing-real-message", "project-memory-not-transient")) {
            val f = Fixture()
            val original = listOf(AgentMessage.UserMessage(id = id, content = "汉".repeat(20_000))) + history().takeLast(2)
            f.rows(original.takeLast(2))
            assertSame(original, f.compact(original).messages)
            f.assertNoDispatchOrCommit()
        }
        val f = Fixture()
        val original = listOf(AgentMessage.AssistantMessage(id = "missing-answer", content = "unique missing answer")) + history()
        f.rows(original.drop(1))
        assertSame(original, f.compact(original).messages)
        f.assertNoDispatchOrCommit()
    }

    @Test fun transientTailCannotImpersonatePersistedAnchor() = runTest {
        val f = Fixture()
        val original = listOf(AgentMessage.UserMessage(id = "old", content = "汉".repeat(20_000)),
            AgentMessage.UserMessage(id = "persisted-anchor", content = "temporary", isTransient = true))
        f.entities = listOf(AgentMessageEntity("old", "s", "USER", "old", 1),
            AgentMessageEntity("persisted-anchor", "s", "USER", "database row", 2))
        assertSame(original, f.compact(original).messages)
        f.assertNoDispatchOrCommit()
    }

    @Test fun rowsFromAnotherSessionDoNotValidateHeadOrTail() = runTest {
        for (otherId in listOf("old", "recent-answer")) {
            val f = Fixture()
            val original = history()
            f.rows(original)
            f.entities = f.entities.map { if (it.id == otherId) it.copy(sessionId = "another-session") else it }
            if (otherId == "recent-answer") f.entities = f.entities.filterNot { it.id == "current" }
            assertSame(original, f.compact(original).messages)
            f.assertNoDispatchOrCommit()
        }
    }

    @Test fun syntheticLegacyMarkerIsExcludedWhileFlaggedSummaryKeepsAllContent() = runTest {
        val f = Fixture()
        val previous = "  UNIQUE_LEGACY_FACT_64\n"
        val marker = AgentMessage.UserMessage(id = "synthetic", content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true, isTransient = true)
        val original = listOf(marker, AgentMessage.AssistantMessage(id = "legacy-summary", content = previous, isContextSummary = true)) + history()
        f.rows(original)
        assertTrue(f.compact(original).compacted)
        assertTrue(f.requests.first().contains("<previous-summary>\n$previous\n</previous-summary>"))
        assertTrue("legacy-summary" in f.committedIds)
        assertFalse(marker.id in f.committedIds)
    }

    @Test fun newSummaryCarriesExplicitFlagsAndFullTextInModelAndRows() = runTest {
        val f = Fixture()
        val full = "  FULL_SUMMARY_START\n" + "唯".repeat(3_000) + "\nEXACT_END"
        every { f.provider.completeStream(any(), any(), any(), any()) } returns flowOf(AIStreamChunk.Final(AIResponse(full, stopReason = "stop")))
        val original = history()
        f.rows(original)
        val result = f.compact(original)
        assertTrue(result.compacted)
        val marker = result.messages[0] as AgentMessage.UserMessage
        val summary = result.messages[1] as AgentMessage.AssistantMessage
        assertTrue(marker.isCompactionMarker)
        assertFalse(marker.isTransient)
        assertTrue(summary.isContextSummary)
        assertEquals(full, summary.content)
        assertEquals(listOf(marker.id, summary.id), f.committedRows.map { it.id })
        assertTrue(f.committedRows[0].isCompactionMarker)
        assertTrue(f.committedRows[1].isContextSummary)
        assertEquals(full, f.committedRows[1].content)
    }
}
