package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.context.CompactionProgress
import com.aicode.feature.agent.domain.context.CompactionStreamState
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.provider.EnrichedHttpException
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ContextCompactorTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val metadata = mockk<ModelMetadataService>()
    private val prompts = mockk<SystemPromptProvider>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)
    private val compactor = ContextCompactor(dao, metadata, prompts, records, settings, persistence)
    private var outputLimit: Int? = 8_000
    private var retries = 150
    private var firstByte = 300_000L
    private var idle = 0L
    private val originalSwitcher: suspend (Throwable, String, Set<String>) -> KeySwitchOutcome? = { _, _, _ -> null }
    private var switcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = originalSwitcher

    private fun prepare(context: Int = 16_000) {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { provider.maxOutputTokens } answers { outputLimit }
        every { provider.maxOutputTokens = any() } answers { outputLimit = firstArg() }
        every { provider.maxNetworkRetries } answers { retries }
        every { provider.maxNetworkRetries = any() } answers { retries = firstArg() }
        every { provider.firstByteTimeoutMs } answers { firstByte }
        every { provider.firstByteTimeoutMs = any() } answers { firstByte = firstArg() }
        every { provider.streamIdleTimeoutMs } answers { idle }
        every { provider.streamIdleTimeoutMs = any() } answers { idle = firstArg() }
        every { provider.keySwitcher } answers { switcher }
        every { provider.keySwitcher = any() } answers { switcher = firstArg() }
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(id = "summary", contextTokens = context)
        coEvery { settings.compactionThresholdPercent() } returns 90
        every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
        every { provider.completeStream(any(), any(), any(), any()) } returns complete()
        every { persistence.nextTimestamp() } returnsMany listOf(300L, 301L)
        compactor.streamPolicy = CompactionStreamPolicy(retryDelayMs = 0)
    }

    private fun complete(text: String = "Concise handoff") = flowOf(AIStreamChunk.Final(AIResponse(text, stopReason = "stop")))
    private fun history(size: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(size)),
        AgentMessage.AssistantMessage(id = "answer", content = "done"),
        AgentMessage.UserMessage(id = "goal", content = "continue the task")
    )
    private fun persistedHistory() {
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "answer", sessionId = "session", role = "ASSISTANT", content = "done", timestamp = 60),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
    }
    private fun List<AgentEvent>.progress(): List<CompactionProgress> = filterIsInstance<AgentEvent.CompactionProgress>().map { it.progress }
    private fun assertRestored(expectedOutput: Int? = 8_000) {
        assertEquals(expectedOutput, provider.maxOutputTokens)
        assertEquals(150, provider.maxNetworkRetries)
        assertEquals(300_000L, provider.firstByteTimeoutMs)
        assertEquals(0L, provider.streamIdleTimeoutMs)
        assertSame(originalSwitcher, provider.keySwitcher)
    }
    private fun material(request: List<AgentMessage>): String {
        val content = (request.single() as AgentMessage.UserMessage).content
        val header = Regex("<history-material [^>]*>\\n").find(content)!!.value
        return content.substringAfter(header).removeSuffix("\n</history-material>")
    }

    @Test fun projectionUsesModelResultAndPreservesBothEnds() {
        val message = AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "not sent to model",
            modelResult = "BEGIN" + "x".repeat(3_000) + "END")
        val text = CompactionText.project(message)
        assertTrue(text.contains("BEGIN"))
        assertTrue(text.endsWith("END"))
        assertFalse(text.contains("not sent to model"))
        assertEquals(3_008, message.modelResult!!.length)
    }

    @Test fun toolProjectionKeepsUnicodeBoundariesAtBothOmittedEdges() {
        val source = "a".repeat(999) + "🙂" + "b".repeat(1_000) + "🚀" + "c".repeat(999)
        val message = AgentMessage.ToolResultMessage(id = "unicode", toolName = "readFile", result = source)
        val text = CompactionText.project(message)
        assertTrue(text.contains("a".repeat(999) + "\n[tool output middle omitted; 1004 characters]\n"))
        assertTrue(text.endsWith("c".repeat(999)))
        assertFalse(text.any { it.isHighSurrogate() || it.isLowSurrogate() })
        assertEquals(source, message.result)
    }

    @Test fun projectionRemovesMediaAndProtocolThinkingForEveryRole() {
        val image = AgentImage("image/png", "secret-media")
        val text = listOf(
            AgentMessage.UserMessage(content = "data:image/png;base64,c2VjcmV0", images = listOf(image)),
            AgentMessage.AssistantMessage(content = "answer", reasoning = "private-thinking", signature = "signature",
                thinkingBlocksJson = "snapshot", images = listOf(image)),
            AgentMessage.ToolResultMessage(toolName = "image", result = "{\"images\":[{\"base64Data\":\"secret-media\"}],\"value\":\"kept\"}", images = listOf(image))
        ).joinToString { CompactionText.project(it) }
        for (secret in listOf("secret-media", "c2VjcmV0", "private-thinking", "signature", "snapshot")) assertFalse(text.contains(secret))
        assertTrue(text.contains("kept"))
    }

    @Test fun splitKeepsAllToolResultsWithAssistant() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "goal"),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("one", "read", emptyMap()), ToolCall("two", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "one", toolName = "read", result = "one"),
            AgentMessage.ToolResultMessage(id = "two", toolName = "read", result = "two")
        )
        assertEquals(1, CompactionText.adjustSplitIndex(messages, 3))
        assertEquals(2, CompactionText.units(messages).size)
    }

    @Test fun splitOnlyPairsExplicitlyFlaggedSummaryAndMarker() {
        val marker = AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true)
        val summary = AgentMessage.AssistantMessage(content = "summary", isContextSummary = true)
        assertEquals(0, CompactionText.adjustSplitIndex(listOf(marker, summary), 1))
        assertEquals(1, CompactionText.adjustSplitIndex(listOf(marker.copy(isCompactionMarker = false), summary), 1))
        assertEquals(1, CompactionText.adjustSplitIndex(listOf(marker, summary.copy(isContextSummary = false)), 1))
    }

    @Test fun cursorNeverDiscardsOversizedOrLeadingAssistantMaterial() {
        val cursor = CompactionText.Cursor(listOf("汉".repeat(1_000), "LAST"))
        val chunks = mutableListOf<String>()
        repeat(32) { if (!cursor.finished) chunks.add(cursor.next(100)) }
        assertTrue(cursor.finished)
        assertEquals(1_000, chunks.sumOf { it.count { char -> char == '汉' } })
        assertTrue(chunks.last().contains("LAST"))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 100 })
        assertTrue(CompactionText.units(listOf(AgentMessage.AssistantMessage(content = "leading"))).single().contains("leading"))
    }

    @Test fun cursorIncrementalBudgetMatchesWholeTextCounting() {
        val units = List(120) { if (it % 2 == 0) "ascii-$it" else "混合-$it" }
        val cursor = CompactionText.Cursor(units)
        val chunks = mutableListOf<String>()
        while (!cursor.finished) chunks.add(cursor.next(160))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 160 })
        CompactionSummaryFixture.assertComplete(units, chunks)
    }

    @Test fun zeroBudgetDoesNotConsumeCursor() {
        val cursor = CompactionText.Cursor(listOf("source"))
        try { cursor.next(0); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertEquals(0L, cursor.consumedCharacters)
        assertEquals(6L, cursor.totalCharacters)
        assertFalse(cursor.finished)
    }

    @Test fun emptyAndBelowThresholdHistoriesDoNotStartAttempt() = runTest {
        prepare()
        val events = mutableListOf<AgentEvent>()
        assertFalse(compactor.compactIfNeeded(emptyList(), provider, onEvent = { events.add(it) }).compacted)
        assertFalse(compactor.compactIfNeeded(history(100), provider, onEvent = { events.add(it) }).compacted)
        assertTrue(events.isEmpty())
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun multipleBlocksMergePreviousAndRetainLatestGoalWithStableAttemptId() = runTest {
        prepare()
        val requests = mutableListOf<List<AgentMessage>>()
        every { provider.completeStream(any(), capture(requests), any(), "none") } returns complete()
        val events = mutableListOf<AgentEvent>()
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        assertTrue(result.compacted)
        assertTrue(requests.size > 1)
        assertTrue((requests[1].single() as AgentMessage.UserMessage).content.contains("<previous-summary>\nConcise handoff"))
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(original.first())), requests.map(::material))
        assertSame(original.last(), result.messages.last())
        val start = events.first() as AgentEvent.CompactionStarted
        UUID.fromString(start.attemptId)
        val ids = events.mapNotNull { when (it) {
            is AgentEvent.CompactionStarted -> it.attemptId
            is AgentEvent.CompactionProgress -> it.attemptId
            is AgentEvent.CompactionFailed -> it.attemptId
            is AgentEvent.CompactionFinished -> it.attemptId
            else -> null
        } }
        assertTrue(ids.all { it == start.attemptId })
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertEquals(CompactionPhase.COMPLETED, events.progress().last().phase)
        assertEquals(100, events.progress().last().percent)
        assertTrue(events.progress().zipWithNext().all { (a, b) -> a.percent <= b.percent })
        assertRestored()
        coVerify(exactly = requests.size) { records.insert(match { it.status == "success" && it.reasoningEffort == "none" }) }
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test fun separateAttemptsHaveDifferentIds() = runTest {
        prepare()
        val events = mutableListOf<AgentEvent>()
        repeat(2) { compactor.compactIfNeeded(history(), provider, force = true, onEvent = { events.add(it) }) }
        val ids = events.filterIsInstance<AgentEvent.CompactionStarted>().map { it.attemptId }
        assertEquals(2, ids.distinct().size)
    }

    @Test fun calibratedCurrentUsageTriggersBeforeSending() = runTest {
        prepare()
        val original = history(100)
        val events = mutableListOf<AgentEvent>()
        compactor.compactIfNeeded(original, provider, currentInputTokens = 99_999, onEvent = { events.add(it) })
        assertEquals(99_999, events.filterIsInstance<AgentEvent.CompactionStarted>().single().estimatedTokens)
    }

    @Test fun mainTriggerKeepsFourCharEstimateInsteadOfSummaryThreeCharEstimate() = runTest {
        prepare()
        val original = listOf(AgentMessage.UserMessage(content = "a".repeat(45_000)), AgentMessage.UserMessage(content = "next"))
        assertTrue(ContextTokenEstimator.estimate("", original, emptyList()) < 12_960)
        assertTrue(CompactionText.estimateRequest("", emptyList(), original) > 14_400)
        val events = mutableListOf<AgentEvent>()
        assertFalse(compactor.compactIfNeeded(original, provider, onEvent = { events.add(it) }).compacted)
        assertTrue(events.isEmpty())
        compactor.compactIfNeeded(original, provider, currentInputTokens = 100, onEvent = { events.add(it) })
        assertTrue(events.isEmpty())
    }

    @Test fun mainTailSelectionAndRetainedBudgetKeepFourCharEstimate() = runTest {
        prepare()
        val original = listOf(
            AgentMessage.UserMessage(content = "汉".repeat(20_000)),
            AgentMessage.UserMessage(content = "a".repeat(6_000)),
            AgentMessage.AssistantMessage(content = "done"),
        )
        assertEquals(1, CompactionText.selectTailStartIndex(original, 14_400))
        val system = "a".repeat(44_000)
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, systemPrompt = system, onEvent = { events.add(it) })
        assertTrue(result.compacted)
        assertSame(original[1], result.messages[2])
        assertEquals(CompactionPhase.COMPLETED, events.progress().last().phase)
    }

    @Test fun automaticCompactionSkipsWhenFixedOverheadReachesThreshold() = runTest {
        prepare(30_000)
        val events = mutableListOf<AgentEvent>()
        val original = history()
        assertFalse(compactor.compactIfNeeded(original, provider, systemPrompt = "汉".repeat(25_000),
            currentInputTokens = 25_000, onEvent = { events.add(it) }).compacted)
        assertTrue(events.isEmpty())
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun retainedToolDefinitionsAreCountedBeforeDispatch() = runTest {
        prepare()
        coEvery { settings.compactionThresholdPercent() } returns 50
        val tool = mockk<AgentTool>(relaxed = true)
        every { tool.name } returns "read"
        every { tool.description } returns "汉".repeat(7_200)
        every { tool.toModelJsonSchema() } returns mapOf("type" to "object")
        val original = history(5_000)
        val events = mutableListOf<AgentEvent>()
        assertTrue(ContextTokenEstimator.estimate("", original, listOf(tool)) < 14_400)
        assertFalse(compactor.compactIfNeeded(original, provider, tools = listOf(tool), onEvent = { events.add(it) }).compacted)
        assertTrue(events.isEmpty())
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun projectionKeepsPathsWithMediaFieldNames() {
        val path = "/project/images/base64Data/What did we do so far?.kt"
        val plain = CompactionText.project(AgentMessage.UserMessage(content = path))
        val json = CompactionText.project(AgentMessage.ToolResultMessage(toolName = "read", result = "{\"path\":\"$path\",\"images\":[{\"base64Data\":\"omitted\"}]}"))
        assertTrue(plain.contains(path))
        assertTrue(json.contains(path))
        assertFalse(json.contains("omitted"))
    }

    @Test fun finalMainBudgetFailureKeepsNinetyAndDoesNotCommit() = runTest {
        prepare()
        val events = mutableListOf<AgentEvent>()
        val original = history()
        every { provider.completeStream(any(), any(), any(), any()) } returns complete("摘".repeat(1_000))
        assertSame(original, compactor.compactIfNeeded(original, provider, force = true,
            systemPrompt = "汉".repeat(13_500), onEvent = { events.add(it) }).messages)
        assertEquals(CompactionPhase.FAILED, events.progress().last().phase)
        assertEquals(90, events.progress().last().percent)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test fun summaryBeyondSoftTargetIsReturnedWhole() = runTest {
        prepare(200_000)
        val full = "唯".repeat(3_500) + " exact-important-id-END"
        every { provider.completeStream(any(), any(), any(), any()) } returns complete(full)
        val result = compactor.compactIfNeeded(history(40_000), provider, force = true)
        assertTrue(result.compacted)
        assertEquals(full, (result.messages[1] as AgentMessage.AssistantMessage).content)
    }

    @Test fun finalMainBudgetUsesFourCharsNotSummaryRequestEstimate() = runTest {
        prepare()
        val system = "a".repeat(50_000)
        every { provider.completeStream(any(), any(), any(), any()) } returns complete("a".repeat(3_000))
        val result = compactor.compactIfNeeded(history(), provider, force = true, systemPrompt = system)
        assertTrue(result.compacted)
        assertTrue(ContextTokenEstimator.estimate(system, result.messages, emptyList()) <= 14_400)
        assertTrue(CompactionText.estimateRequest(system, emptyList(), result.messages) > 14_400)
    }

    @Test fun invalidResponsesDoNotCommitAndCountOnlyActualCalls() = runTest {
        prepare()
        for (response in listOf(AIResponse(""), AIResponse("partial", stopReason = "length"), AIResponse("blocked", stopReason = "refusal"))) {
            every { provider.completeStream(any(), any(), any(), any()) } returns flowOf(AIStreamChunk.Final(response))
            val original = history()
            assertSame(original, compactor.compactIfNeeded(original, provider, force = true).messages)
        }
        coVerify(exactly = 8) { records.insert(match { it.status == "error" }) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        assertRestored()
    }

    @Test fun missingFinalNeverCountsPartialUsageOrAdvancesSource() = runTest {
        prepare()
        every { provider.completeStream(any(), any(), any(), any()) } returns flow { emit(AIStreamChunk.TextDelta("private partial")) }
        val original = history()
        val events = mutableListOf<AgentEvent>()
        assertSame(original, compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) }).messages)
        assertEquals(0L, events.progress().last().completedCharacters)
        assertEquals("compaction_no_final", events.progress().last().lastErrorCode)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        coVerify(exactly = 1) { records.insert(match { it.status == "error" && it.inputTokens == 0 && it.outputTokens == 0 }) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test fun unreachableSmallWindowFailsBeforeAnyModelRequest() = runTest {
        prepare(2_000)
        val original = history(100_000)
        val events = mutableListOf<AgentEvent>()
        assertSame(original, compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) }).messages)
        assertEquals("compaction_budget_unreachable", events.progress().last().lastErrorCode)
        assertEquals(0L, events.progress().last().completedCharacters)
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun missingPersistedAnchorFailsBeforeModel() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns emptyList()
        val original = history()
        val events = mutableListOf<AgentEvent>()
        assertSame(original, compactor.compactIfNeeded(original, provider, sessionId = "session", force = true, onEvent = { events.add(it) }).messages)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun commitUsesHeadIdsFlagsAndTriggerTimestamps() = runTest {
        prepare(); persistedHistory()
        val result = compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true)
        assertTrue(result.compacted)
        assertTrue((result.messages[0] as AgentMessage.UserMessage).isCompactionMarker)
        assertTrue((result.messages[1] as AgentMessage.AssistantMessage).isContextSummary)
        coVerify(exactly = 1) { dao.commitCompaction("session", listOf("old"), match {
            it.map { row -> row.timestamp } == listOf(300L, 301L) && it[0].isCompactionMarker && it[1].isContextSummary
        }, any()) }
        verify(exactly = 1) { persistence.invalidateHistory("session") }
    }

    @Test fun emptyAdjustedHeadAndOversizedTailFailWithoutDispatch() = runTest {
        prepare()
        for (original in listOf(
            listOf(AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("call", "read", emptyMap()))),
                AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "result")),
            history() + AgentMessage.UserMessage(content = "汉".repeat(30_000)),
        )) {
            val events = mutableListOf<AgentEvent>()
            assertSame(original, compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) }).messages)
            assertEquals(CompactionPhase.FAILED, events.progress().last().phase)
            assertTrue(events.last() is AgentEvent.CompactionFinished)
        }
        verify(exactly = 0) { provider.completeStream(any(), any(), any(), any()) }
    }

    @Test fun promptAndTimestampPreparationFailuresAlwaysFinish() = runTest {
        prepare(); persistedHistory()
        every { persistence.nextTimestamp() } throws IllegalArgumentException("private preprocessing detail")
        val events = mutableListOf<AgentEvent>()
        assertFalse(compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true, onEvent = { events.add(it) }).compacted)
        assertTrue(events.first() is AgentEvent.CompactionStarted)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertEquals("compaction_failed", events.filterIsInstance<AgentEvent.CompactionFailed>().single().reason)
        assertFalse(compactor.isSessionCompacting("session"))
        every { prompts.resolvePrompt(any()) } throws IllegalArgumentException("private prompt detail")
        events.clear()
        assertFalse(compactor.compactIfNeeded(history(), provider, force = true, onEvent = { events.add(it) }).compacted)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertFalse(events.joinToString().contains("private"))
        assertRestored()
    }

    @Test fun transactionFailureIsFailedAtNinetyFiveAndNeverInvalidates() = runTest {
        prepare(); persistedHistory()
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } throws IllegalStateException("private transaction detail")
        val events = mutableListOf<AgentEvent>()
        val original = history()
        assertSame(original, compactor.compactIfNeeded(original, provider, sessionId = "session", force = true, onEvent = { events.add(it) }).messages)
        assertEquals(CompactionPhase.FAILED, events.progress().last().phase)
        assertEquals(95, events.progress().last().percent)
        verify(exactly = 0) { persistence.invalidateHistory(any()) }
        assertFalse(compactor.isSessionCompacting("session"))
    }

    @Test fun noBenefitIsUnchangedWithoutSavingEvenIfTerminalCollectorThrows() = runTest {
        prepare()
        every { provider.completeStream(any(), any(), any(), any()) } returns complete("summary".repeat(100))
        val events = mutableListOf<AgentEvent>()
        val original = history(100)
        assertSame(original, compactor.compactIfNeeded(original, provider, force = true, onEvent = {
            events.add(it)
            if (it is AgentEvent.CompactionProgress && it.progress.phase == CompactionPhase.UNCHANGED) throw IllegalStateException("closed")
        }).messages)
        assertEquals(CompactionPhase.UNCHANGED, events.progress().last().phase)
        assertEquals(100, events.progress().last().percent)
        assertTrue(events.none { it is AgentEvent.CompactionFailed })
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test fun streamBypassDisablesHiddenRetriesAndRestoresEveryProperty() = runTest {
        prepare()
        every { provider.completeStream(any(), any(), any(), "none") } returns flow {
            assertEquals(0, provider.maxNetworkRetries)
            assertNull(provider.keySwitcher)
            assertEquals(120_000L, provider.firstByteTimeoutMs)
            assertEquals(120_000L, provider.streamIdleTimeoutMs)
            assertEquals(1_600, provider.maxOutputTokens)
            emit(AIStreamChunk.Final(AIResponse("summary", stopReason = "stop")))
        }
        assertTrue(compactor.compactIfNeeded(history(), provider, force = true).compacted)
        assertRestored()
    }

    @Test fun hardOutputLimitUsesMinimumOfEightKProviderMetadataAndReserve() = runTest {
        prepare(200_000)
        for ((configured, metadataLimit, expected) in listOf(
            Triple<Int?, Int?, Int>(null, null, 8_192), Triple(8_000, null, 8_000),
            Triple(256, null, 256), Triple(8_000, 2_048, 2_048),
        )) {
            provider.maxOutputTokens = configured
            coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("summary", contextTokens = 200_000, outputTokens = metadataLimit)
            every { provider.completeStream(any(), any(), any(), "none") } returns flow {
                assertEquals(expected, provider.maxOutputTokens)
                emit(AIStreamChunk.Final(AIResponse("summary", stopReason = "stop")))
            }
            assertTrue(compactor.compactIfNeeded(history(40_000), provider, force = true).compacted)
            assertRestored(configured)
        }
    }

    @Test fun unsafeStreamFailureOnlyEmitsFixedCodeAndStillRestores() = runTest {
        prepare()
        every { provider.completeStream(any(), any(), any(), any()) } returns flow {
            emit(AIStreamChunk.TextDelta("private partial")); throw IllegalArgumentException("private credential detail")
        }
        val events = mutableListOf<AgentEvent>()
        assertFalse(compactor.compactIfNeeded(history(), provider, force = true, onEvent = { events.add(it) }).compacted)
        assertEquals("compaction_failed", events.progress().last().lastErrorCode)
        assertFalse(events.joinToString().contains("private"))
        assertRestored()
    }

    @Test fun failedCollectorStillGetsIndependentFinishCleanup() = runTest {
        prepare()
        val events = mutableListOf<AgentEvent>()
        assertFalse(compactor.compactIfNeeded(history(), provider, force = true, onEvent = {
            events.add(it)
            if (it !is AgentEvent.CompactionFinished) throw IllegalStateException("collector closed")
        }).compacted)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertRestored()
    }

    @Test fun cancelSessionTargetsActualAttemptAndNeverCommitsPendingSource() = runTest {
        prepare(); persistedHistory()
        val entered = CompletableDeferred<Unit>()
        every { provider.completeStream(any(), any(), any(), any()) } returns flow { entered.complete(Unit); awaitCancellation() }
        val events = mutableListOf<AgentEvent>()
        assertFalse(compactor.cancelSession("session"))
        val job = async { compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true, onEvent = { events.add(it) }) }
        entered.await()
        assertTrue(compactor.isSessionCompacting("session"))
        assertFalse(compactor.cancelSession("other"))
        assertTrue(compactor.cancelSession("session"))
        try { job.await(); fail("Must propagate cancellation") } catch (_: CancellationException) { }
        assertEquals(CompactionPhase.CANCELLED, events.progress().last().phase)
        assertEquals(0L, events.progress().last().completedCharacters)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertFalse(compactor.isSessionCompacting("session"))
        assertRestored()
        coVerify(exactly = 1) { records.insert(match { it.errorMessage == "compaction_cancelled" }) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test fun providerCancellationAndClosedCollectorPreserveOriginalCancellation() = runTest {
        prepare()
        every { provider.completeStream(any(), any(), any(), any()) } throws CancellationException("model cancelled")
        val events = mutableListOf<AgentEvent>()
        try {
            compactor.compactIfNeeded(history(), provider, force = true, onEvent = {
                events.add(it)
                if (it is AgentEvent.CompactionFinished || it is AgentEvent.CompactionProgress && it.progress.phase == CompactionPhase.CANCELLED) {
                    throw IllegalStateException("collector closed")
                }
            })
            fail("Must propagate cancellation")
        } catch (e: CancellationException) { assertEquals("model cancelled", e.message) }
        assertEquals(CompactionPhase.CANCELLED, events.progress().last().phase)
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertRestored()
        coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
    }

    @Test fun cancellationAtPreparingAndValidatingCannotCommit() = runTest {
        prepare(); persistedHistory()
        for (phase in listOf(CompactionPhase.PREPARING, CompactionPhase.VALIDATING, CompactionPhase.SAVING)) {
            val events = mutableListOf<AgentEvent>()
            val job = async {
                compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true, onEvent = {
                    events.add(it)
                    if (it is AgentEvent.CompactionProgress && it.progress.phase == phase) {
                        currentCoroutineContext().job.cancel(CancellationException("cancelled at phase"))
                    }
                })
            }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(CompactionPhase.CANCELLED, events.progress().last().phase)
            assertTrue(events.last() is AgentEvent.CompactionFinished)
        }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        assertRestored()
    }

    @Test fun validationAndAtomicSaveCloseCancelSlotButRetainOwner() = runTest {
        prepare(); persistedHistory()
        val phases = mutableListOf<CompactionPhase>()
        assertTrue(compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true, onEvent = {
            if (it is AgentEvent.CompactionProgress && it.progress.phase in listOf(CompactionPhase.VALIDATING, CompactionPhase.SAVING)) {
                phases += it.progress.phase
                assertTrue(compactor.isSessionCompacting("session"))
                assertFalse(compactor.cancelSession("session"))
            }
        }).compacted)
        assertEquals(listOf(CompactionPhase.VALIDATING, CompactionPhase.SAVING), phases)
        assertFalse(compactor.isSessionCompacting("session"))
    }

    @Test fun committedSummarySurvivesInvalidationAndCompletionCollectorFailures() = runTest {
        prepare(); persistedHistory()
        every { persistence.invalidateHistory("session") } throws IllegalStateException("private cache detail")
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true, onEvent = {
            events.add(it)
            if (it is AgentEvent.CompactionFinished || it is AgentEvent.CompactionProgress && it.progress.phase == CompactionPhase.COMPLETED) {
                throw IllegalStateException("collector closed")
            }
        })
        assertTrue(result.compacted)
        assertEquals(CompactionPhase.COMPLETED, events.progress().last().phase)
        assertEquals(100, events.progress().last().percent)
        assertTrue(events.none { it is AgentEvent.CompactionFailed })
        assertTrue(events.last() is AgentEvent.CompactionFinished)
    }

    @Test fun outerCancellationDuringAtomicCommitPreservesSavedFact() = runTest {
        prepare(); persistedHistory()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } coAnswers { entered.complete(Unit); release.await() }
        val events = mutableListOf<AgentEvent>()
        val job = async { compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true, onEvent = { events.add(it) }) }
        entered.await()
        assertFalse(compactor.cancelSession("session"))
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(CompactionPhase.COMPLETED, events.progress().last().phase)
        assertTrue(events.progress().none { it.phase == CompactionPhase.CANCELLED })
        assertTrue(events.none { it is AgentEvent.CompactionFailed })
        assertTrue(events.last() is AgentEvent.CompactionFinished)
        assertRestored()
    }

    @Test fun savingSessionStaysExclusiveUntilCleanupCompletes() = runTest {
        prepare(); persistedHistory()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } coAnswers { entered.complete(Unit); release.await() }
        val job = async { compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true) }
        entered.await()
        val error = runCatching { compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true) }.exceptionOrNull()
        assertEquals("compaction_busy", (error as CompactionStreamException).code)
        assertFalse(compactor.cancelSession("session"))
        release.complete(Unit)
        assertTrue(job.await().compacted)
    }

    @Test fun retryOn499ShrinksCandidateFromSameCheckpointAndPreservesAllSource() = runTest {
        prepare(200_000)
        val requests = mutableListOf<List<AgentMessage>>()
        var calls = 0
        every { provider.completeStream(any(), capture(requests), any(), any()) } returns flow {
            if (++calls == 1) throw EnrichedHttpException("private body", 499, "private", IllegalStateException())
            emit(AIStreamChunk.Final(AIResponse("summary", stopReason = "stop")))
        }
        val original = history(40_000)
        val events = mutableListOf<AgentEvent>()
        assertTrue(compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) }).compacted)
        assertTrue(CompactionText.tokens(material(requests[1])) < CompactionText.tokens(material(requests[0])))
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(original.first())), requests.drop(1).map(::material))
        assertTrue(events.progress().filter { it.streamState == CompactionStreamState.RETRYING }.all { it.completedCharacters == 0L })
    }

    @Test fun repeatedTruncationShrinksWithoutUsingPartialSummaries() = runTest {
        prepare(200_000)
        val original = history(40_000)
        val requests = mutableListOf<List<AgentMessage>>()
        var calls = 0
        every { provider.completeStream(any(), capture(requests), any(), any()) } returns flow {
            emit(AIStreamChunk.Final(if (++calls <= 3) AIResponse("REJECTED_PARTIAL", stopReason = "length") else AIResponse("summary", stopReason = "stop")))
        }
        assertTrue(compactor.compactIfNeeded(original, provider, force = true).compacted)
        assertTrue((0..2).all { CompactionText.tokens(material(requests[it + 1])) < CompactionText.tokens(material(requests[it])) })
        assertTrue(requests.none { (it.single() as AgentMessage.UserMessage).content.contains("REJECTED_PARTIAL") })
        CompactionSummaryFixture.assertComplete(CompactionText.units(listOf(original.first())), requests.drop(3).map(::material))
    }

    @Test fun customPromptKeepsFullPreviousSummaryWithinEveryRequestBudget() = runTest {
        prepare()
        every { prompts.resolvePrompt(any()) } returns "Custom JSON handoff format." + "规".repeat(1_000)
        val previous = "keep unique ID=42 and unresolved conflict\n" + "旧".repeat(4_000)
        val original = listOf(
            AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true),
            AgentMessage.AssistantMessage(content = previous, isContextSummary = true),
        ) + history()
        val requests = mutableListOf<List<AgentMessage>>()
        val systems = mutableListOf<String>()
        every { provider.completeStream(capture(systems), capture(requests), any(), any()) } returns complete()
        assertTrue(compactor.compactIfNeeded(original, provider, force = true).compacted)
        assertTrue((requests.first().single() as AgentMessage.UserMessage).content.contains("<previous-summary>\n$previous\n</previous-summary>"))
        requests.forEachIndexed { index, request -> assertTrue(CompactionText.estimateRequest(systems[index], emptyList(), request) <= 14_400) }
    }
}
