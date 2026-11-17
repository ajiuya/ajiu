package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.context.CompactionProgress
import com.aicode.feature.agent.domain.context.CompactionStreamState
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CompactionHierarchySelectionTest {
    private data class Request(val system: String, val messages: List<AgentMessage>, val progress: CompactionProgress) {
        val content: String get() = (messages.single() as AgentMessage.UserMessage).content
        val isLeaf: Boolean get() = "<history-material " in content
        val level: Int get() = content.substringAfter("<summary-material level=\"").substringBefore('"').toInt()
        val material: String get() = content.substringAfter("<history-material ").substringAfter(">\n")
            .removeSuffix("\n</history-material>")
    }

    @Test fun known128kModelUsesIndependentLeavesAndSourceOrderedMergeLevels() = runTest { assertHierarchy(128_000) }
    @Test fun unknownSummaryModelUsesConservativeHierarchyDespiteLargeMainWindow() = runTest { assertHierarchy(0) }

    private suspend fun assertHierarchy(summaryContext: Int) {
        val metadata = mockk<ModelMetadataService>()
        val prompts = mockk<SystemPromptProvider>()
        val settings = mockk<GeneralSettingsRepository>()
        val provider = mockk<AIProvider>(relaxed = true)
        val main = mockk<AIProvider>(relaxed = true)
        val compactor = ContextCompactor(mockk<AgentMessageDao>(relaxed = true), metadata, prompts,
            mockk<LlmCallRecordDao>(relaxed = true), settings, mockk<MessagePersistenceUseCase>(relaxed = true))
        compactor.streamPolicy = CompactionStreamPolicy(retryDelayMs = 0)
        every { provider.providerId } returns "summary-provider"
        every { provider.model } returns "summary"
        every { provider.maxOutputTokens } returns 32_768
        every { main.providerId } returns "main-provider"
        every { main.model } returns "large-chat"
        val summaryMetadata = ModelMetadata("summary", contextTokens = summaryContext, outputTokens = 32_768)
        coEvery { metadata.resolve("main-provider", any(), any()) } returns ModelMetadata("large-chat", contextTokens = 1_000_000)
        coEvery { metadata.resolve("summary-provider", any(), any()) } returns summaryMetadata
        coEvery { settings.compactionThresholdPercent() } returns 90
        every { prompts.resolvePrompt(any()) } answers { "${firstArg<String>()} {{INSTRUCTION}}" }
        val progress = mutableListOf<CompactionProgress>()
        val requests = mutableListOf<Request>()
        every { provider.completeStream(any(), any(), any(), "none") } answers {
            val request = Request(firstArg(), secondArg(), progress.last())
            requests += request
            flowOf(AIStreamChunk.Final(AIResponse(if (request.isLeaf) "LEAF_${requests.size}" else "MERGE_${requests.size}", stopReason = "stop")))
        }
        val previous = "OLD_SUMMARY_UNIQUE"
        val old = AgentMessage.UserMessage(id = "old", content = "汉".repeat(200_000))
        val tail = listOf(AgentMessage.AssistantMessage(id = "recent-answer", content = "recent answer"),
            AgentMessage.UserMessage(id = "goal", content = "continue the task"))
        val original = listOf(
            AgentMessage.UserMessage(id = "marker", content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true),
            AgentMessage.AssistantMessage(id = "summary", content = previous, isContextSummary = true), old,
        ) + tail
        val result = compactor.compactIfNeeded(original, provider, force = true, windowProvider = main,
            onEvent = { if (it is AgentEvent.CompactionProgress) progress += it.progress })
        assertTrue(result.compacted)
        tail.forEachIndexed { index, message -> assertSame(message, result.messages[index + 2]) }
        val budget = ModelContextPolicy.effectiveInputBudget(summaryMetadata)
        assertTrue(requests.all { CompactionText.estimateRequest(it.system, emptyList(), it.messages) <= budget })
        val leaves = requests.filter(Request::isLeaf)
        val merges = requests.filterNot(Request::isLeaf)
        assertTrue(leaves.size > 4)
        assertTrue(merges.any { it.level > 1 })
        assertTrue(requests.take(leaves.size).all(Request::isLeaf))
        assertTrue(leaves.all { previous !in it.content && "LEAF_" !in it.content && "<previous-summary>" !in it.content })
        val cap = if (summaryContext > 0) 29_952 else 16_384
        assertTrue(leaves.all { CompactionText.tokens(it.material) <= cap })
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(old)), leaves.map(Request::material))
        assertEquals(1, merges.sumOf { Regex(previous).findAll(it.content).count() })
        merges.forEachIndexed { index, request ->
            assertEquals(CompactionPhase.MERGING, request.progress.phase)
            assertEquals(CompactionStreamState.WAITING, request.progress.streamState)
            assertEquals(request.level, request.progress.mergeLevel)
            assertEquals(index, request.progress.completedMerges)
        }
        val merging = progress.filter { it.phase == CompactionPhase.MERGING }
        merging.distinctBy(CompactionProgress::mergeLevel).forEach { start ->
            assertEquals(CompactionStreamState.NONE, start.streamState)
            assertEquals(merges.count { it.level < start.mergeLevel }, start.completedMerges)
        }
        assertTrue(merging.all { it.percent == 90 && it.completedCharacters == it.totalCharacters })
        assertEquals(merges.size, progress.last().completedMerges)
        assertEquals(CompactionPhase.COMPLETED, progress.last().phase)
        verify { provider.maxOutputTokens = 8_192 }
        verify { provider.maxOutputTokens = 32_768 }
        verify(exactly = 0) { main.completeStream(any(), any(), any(), any()) }
    }
}
