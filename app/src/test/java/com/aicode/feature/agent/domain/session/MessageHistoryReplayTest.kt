package com.aicode.feature.agent.domain.session

import androidx.room.InvalidationTracker
import com.aicode.core.util.ImageDownscaler
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessage
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aicode.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import com.aicode.feature.agent.data.remote.anthropic.AnthropicUsage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.ModelInputSnapshot
import com.aicode.feature.agent.domain.model.id
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.presentation.AgentAttachment
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageHistoryReplayTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val rows = mutableListOf<AgentMessageEntity>()
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val observer = slot<InvalidationTracker.Observer>()
    private val tracker = mockk<InvalidationTracker>(relaxed = true) {
        every { addObserver(capture(observer)) } just Runs
    }
    private val database = mockk<AgentDatabase> {
        every { invalidationTracker } returns tracker
    }
    private val persistence = MessagePersistenceUseCase(dao, database)

    @Before
    fun setUp() {
        coEvery { dao.getMessagesBySessionOnce(any()) } answers {
            rows.filter { it.sessionId == firstArg<String>() }.sortedBy { it.timestamp }
        }
        mockkObject(ImageDownscaler)
        every { ImageDownscaler.downscaleToBase64(any(), any()) } returns ("image/png" to "encoded")
    }

    @After
    fun tearDown() {
        unmockkObject(ImageDownscaler)
    }

    @Test
    fun domainFlagsDefaultToOrdinaryMessagesIncludingLegacySerializedMessages() {
        val user = AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER)
        val assistant = AgentMessage.AssistantMessage(content = CONTEXT_SUMMARY_LEGACY_PREFIX)
        assertFalse(user.isCompactionMarker)
        assertFalse(user.isTransient)
        assertFalse(assistant.isContextSummary)

        val oldUser = Json.decodeFromString<AgentMessage.UserMessage>("""{"id":"user","content":"What did we do so far?"}""")
        val oldAssistant = Json.decodeFromString<AgentMessage.AssistantMessage>("""{"id":"assistant","content":"summary"}""")
        assertFalse(oldUser.isCompactionMarker)
        assertFalse(oldUser.isTransient)
        assertFalse(oldAssistant.isContextSummary)
    }

    @Test
    fun explicitDomainFlagsSurviveSerializationAndCopy() {
        val user = AgentMessage.UserMessage(content = CONTEXT_COMPACTION_MARKER, isCompactionMarker = true, isTransient = true)
        val assistant = AgentMessage.AssistantMessage(content = "summary", isContextSummary = true)
        assertEquals(user, Json.decodeFromString<AgentMessage.UserMessage>(Json.encodeToString(user)))
        assertEquals(assistant, Json.decodeFromString<AgentMessage.AssistantMessage>(Json.encodeToString(assistant)))
        assertTrue(user.copy(content = "changed").isCompactionMarker)
        assertTrue(user.copy(content = "changed").isTransient)
        assertTrue(assistant.copy(content = "changed").isContextSummary)
    }

    @Test
    fun ordinarySameTextQuestionAndAssistantAreNotInternalCompaction() = runTest {
        rows += row("question", "USER", CONTEXT_COMPACTION_MARKER)
        rows += row("answer", "ASSISTANT", CONTEXT_SUMMARY_LEGACY_PREFIX + "\n  An ordinary answer", 2)

        val history = persistence.buildHistory("session", "[pending]")

        assertEquals(listOf("question", "answer"), history.map {
            when (it) {
                is AgentMessage.UserMessage -> it.id
                is AgentMessage.AssistantMessage -> it.id
                else -> error("Unexpected tool result")
            }
        })
        val user = history[0] as AgentMessage.UserMessage
        val assistant = history[1] as AgentMessage.AssistantMessage
        assertEquals(CONTEXT_COMPACTION_MARKER, user.content)
        assertFalse(user.isCompactionMarker)
        assertFalse(user.isTransient)
        assertEquals(rows[1].content, assistant.content)
        assertFalse(assistant.isContextSummary)
    }

    @Test
    fun ordinaryAssistantWhitespaceAndLegacyLookingPrefixesRemainByteForByte() = runTest {
        val contents = listOf(
            " \n\t  indented code\n",
            CONTEXT_SUMMARY_LEGACY_PREFIX + "\n  quoted text",
            " \n" + CONTEXT_SUMMARY_LEGACY_PREFIX + "\n  quoted text",
            CONTEXT_SUMMARY_LEGACY_PREFIX.dropLast(1) + " ordinary text"
        )
        contents.forEachIndexed { index, content -> rows += row("answer-$index", "ASSISTANT", content, index.toLong()) }

        val history = persistence.buildHistory("session", "[pending]").map { it as AgentMessage.AssistantMessage }

        assertEquals(contents, history.map { it.content })
        assertTrue(history.none { it.isContextSummary })
    }

    @Test
    fun persistedFlagsMapToDomainAndOnlyActualSummaryIsNormalized() = runTest {
        rows += row("marker", "USER", "localized marker", 10).copy(isCompactionMarker = true)
        rows += row("summary", "ASSISTANT", CONTEXT_SUMMARY_LEGACY_PREFIX + "\n\t summary\n", 11).copy(
            isContextSummary = true, reasoning = "reasoning", signature = "signature",
            thinkingBlocksJson = "[]", inputTokens = 123
        )
        rows += row("ordinary", "ASSISTANT", " \n  ordinary", 12)

        val history = persistence.buildHistory("session", "[pending]")

        val marker = history[0] as AgentMessage.UserMessage
        assertEquals("marker", marker.id)
        assertEquals(CONTEXT_COMPACTION_MARKER, marker.content)
        assertTrue(marker.isCompactionMarker)
        assertFalse(marker.isTransient)
        val summary = history[1] as AgentMessage.AssistantMessage
        assertEquals("summary\n", summary.content)
        assertTrue(summary.isContextSummary)
        assertEquals("reasoning", summary.reasoning)
        assertEquals("signature", summary.signature)
        assertEquals("[]", summary.thinkingBlocksJson)
        assertEquals(123, summary.inputTokens)
        val ordinary = history[2] as AgentMessage.AssistantMessage
        assertEquals(rows[2].content, ordinary.content)
        assertFalse(ordinary.isContextSummary)
    }

    @Test
    fun oldMarkedSummaryWithoutMarkerReplaysWithTransientMarkerAcrossBuilds() = runTest {
        rows += row("question", "USER", CONTEXT_COMPACTION_MARKER, 1)
        rows += row("old-summary", "ASSISTANT", CONTEXT_SUMMARY_LEGACY_PREFIX + "\n  saved state", 2)
            .copy(isContextSummary = true)
        val originalRows = rows.toList()

        val first = persistence.buildHistory("session", "[pending]")
        assertSame(first, persistence.buildHistory("session", "[pending]"))
        persistence.invalidateHistory("session")
        val rebuilt = persistence.buildHistory("session", "[pending]")
        val cold = MessagePersistenceUseCase(dao, database).buildHistory("session", "[pending]")

        for (history in listOf(first, rebuilt, cold)) {
            assertEquals(3, history.size)
            val marker = history[0] as AgentMessage.UserMessage
            assertTrue(marker.isCompactionMarker)
            assertTrue(marker.isTransient)
            assertEquals(CONTEXT_COMPACTION_MARKER, marker.content)
            assertTrue(rows.none { it.id == marker.id })
            val summary = history[1] as AgentMessage.AssistantMessage
            assertEquals("old-summary", summary.id)
            assertTrue(summary.isContextSummary)
            assertEquals("saved state", summary.content)
            val ordinary = history[2] as AgentMessage.UserMessage
            assertEquals("question", ordinary.id)
            assertFalse(ordinary.isCompactionMarker)
            assertFalse(ordinary.isTransient)
        }
        assertEquals(originalRows, rows)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun stableGenerationAndPendingMarkerReuseTheExistingHistoryEntry() = runTest {
        rows += row("user", "USER", "hello")

        val first = persistence.buildHistory("session", "[pending]")
        assertSame(first, persistence.buildHistory("session", "[pending]"))
        persistence.buildHistory("session", "different marker")

        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun generationChangedDuringDaoReadIsRereadBeforeReturnOrCachePublish() = runTest {
        val old = row("answer", "ASSISTANT", "stale")
        val updated = old.copy(content = "current")
        var reads = 0
        coEvery { dao.getMessagesBySessionOnce("session") } answers {
            if (++reads == 1) {
                observer.captured.onInvalidated(setOf("agent_messages"))
                listOf(old)
            } else listOf(updated)
        }

        val history = persistence.buildHistory("session", "[pending]")

        assertEquals("current", (history.single() as AgentMessage.AssistantMessage).content)
        assertSame(history, persistence.buildHistory("session", "[pending]"))
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun deletionDuringSuspendedReadCannotReturnOrCacheTheOldSnapshot() = runTest {
        rows += row("answer", "ASSISTANT", "before deletion")
        val readStarted = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        var reads = 0
        coEvery { dao.getMessagesBySessionOnce("session") } coAnswers {
            val snapshot = rows.toList()
            if (++reads == 1) {
                readStarted.complete(Unit)
                finishRead.await()
            }
            snapshot
        }
        val builder = async { persistence.buildHistory("session", "[pending]") }
        readStarted.await()
        rows.clear()
        observer.captured.onInvalidated(setOf("agent_messages", "chat_sessions"))
        finishRead.complete(Unit)

        val history = builder.await()

        assertTrue(history.isEmpty())
        assertSame(history, persistence.buildHistory("session", "[pending]"))
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun sessionTableInvalidationAlsoInvalidatesAWarmHistoryEntry() = runTest {
        rows += row("answer", "ASSISTANT", "before deletion")
        val old = persistence.buildHistory("session", "[pending]")
        rows.clear()
        observer.captured.onInvalidated(setOf("chat_sessions"))

        val history = persistence.buildHistory("session", "[pending]")

        assertFalse(old.isEmpty())
        assertTrue(history.isEmpty())
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun failedRebuildDoesNotRestoreAnInvalidatedHistoryEntry() = runTest {
        rows += row("answer", "ASSISTANT", "before edit")
        persistence.buildHistory("session", "[pending]")
        persistence.invalidateHistory("session")
        coEvery { dao.getMessagesBySessionOnce("session") } throws IllegalStateException("read failed")

        val failure = runCatching { persistence.buildHistory("session", "[pending]") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(row("answer", "ASSISTANT", "after edit"))
        val history = persistence.buildHistory("session", "[pending]")
        assertEquals("after edit", (history.single() as AgentMessage.AssistantMessage).content)
        coVerify(exactly = 3) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun cancelledReadCannotPublishItsOldSnapshot() = runTest {
        val readStarted = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        coEvery { dao.getMessagesBySessionOnce("session") } coAnswers {
            readStarted.complete(Unit)
            finishRead.await()
            listOf(row("answer", "ASSISTANT", "cancelled snapshot"))
        }
        val builder = async { persistence.buildHistory("session", "[pending]") }
        readStarted.await()
        builder.cancelAndJoin()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(row("answer", "ASSISTANT", "current"))

        val history = persistence.buildHistory("session", "[pending]")

        assertEquals("current", (history.single() as AgentMessage.AssistantMessage).content)
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun stableImageCacheIsRetainedAcrossHistoryInvalidations() = runTest {
        rows += imageRow("image")
        val first = persistence.buildHistory("session", "[pending]")
        persistence.invalidateHistory("session")

        val rebuilt = persistence.buildHistory("session", "[pending]")

        assertEquals(first, rebuilt)
        verify(exactly = 1) { ImageDownscaler.downscaleToBase64(any(), any()) }
    }

    @Test
    fun generationChangedDuringImageDecodeDoesNotPublishTheObsoleteImage() = runTest {
        rows += imageRow("image")
        var decodes = 0
        every { ImageDownscaler.downscaleToBase64(any(), any()) } answers {
            if (++decodes == 1) {
                observer.captured.onInvalidated(setOf("agent_messages"))
                "image/png" to "obsolete"
            } else "image/png" to "current"
        }

        val history = persistence.buildHistory("session", "[pending]")

        val user = history.single() as AgentMessage.UserMessage
        assertEquals("current", user.images.single().base64Data)
        assertSame(history, persistence.buildHistory("session", "[pending]"))
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
        verify(exactly = 2) { ImageDownscaler.downscaleToBase64(any(), any()) }
    }

    @Test
    fun cancelledImageRebuildPublishesNeitherHistoryNorPreviouslyDecodedImages() = runTest {
        rows += imageRow("first")
        rows += imageRow("second", 2)
        var decodes = 0
        every { ImageDownscaler.downscaleToBase64(any(), any()) } answers {
            if (++decodes == 2) throw CancellationException("decode cancelled")
            "image/png" to "obsolete"
        }

        val failure = runCatching { persistence.buildHistory("session", "[pending]") }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        every { ImageDownscaler.downscaleToBase64(any(), any()) } returns ("image/png" to "current")
        val history = persistence.buildHistory("session", "[pending]")
        assertEquals(listOf("current", "current"), history.map { (it as AgentMessage.UserMessage).images.single().base64Data })
        verify(exactly = 4) { ImageDownscaler.downscaleToBase64(any(), any()) }
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun failedImageRebuildDoesNotPublishEarlierDecodedImages() = runTest {
        rows += imageRow("first")
        rows += imageRow("second", 2)
        var decodes = 0
        every { ImageDownscaler.downscaleToBase64(any(), any()) } answers {
            if (++decodes == 2) throw AssertionError("decoder failed")
            "image/png" to "obsolete"
        }

        val failure = runCatching { persistence.buildHistory("session", "[pending]") }.exceptionOrNull()

        assertTrue(failure is AssertionError)
        every { ImageDownscaler.downscaleToBase64(any(), any()) } returns ("image/png" to "current")
        val history = persistence.buildHistory("session", "[pending]")
        assertEquals(listOf("current", "current"), history.map { (it as AgentMessage.UserMessage).images.single().base64Data })
        verify(exactly = 4) { ImageDownscaler.downscaleToBase64(any(), any()) }
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun lateOldRecoveryResultReturnsToOriginalAssistantBeforeNewUserAndAssistantOnAnthropicWire() = runTest {
        val call = ToolCall("old-call", "Bash", mapOf("command" to JsonPrimitive("printf original"),
            "tool_title" to JsonPrimitive("Check original state")))
        val thinking = """[{"type":"thinking","thinking":"original thought","signature":"signed-block"},{"type":"redacted_thinking","data":"opaque"}]"""
        rows += row("original-user", "USER", "Original request", 1)
        rows += assistantRow("old-assistant", listOf(call), 2).copy(reasoning = "original reasoning",
            signature = "original signature", thinkingBlocksJson = thinking, inputTokens = 137)
        rows += row("new-user", "USER", "New explicit question", 3)
        rows += row("new-assistant", "ASSISTANT", "New explicit answer", 4)
        rows += resultRow("late-result", call, "Actual failure from the old recovery card", 5).copy(isError = true)
        val auditRows = rows.toList()

        val history = persistence.buildHistory("session", "[pending]")
        val wire = anthropicMessages(history)

        assertEquals(listOf("original-user", "old-assistant", "late-result", "new-user", "new-assistant"), history.map { it.id })
        val originalAssistant = history[1] as AgentMessage.AssistantMessage
        assertEquals(listOf(call), originalAssistant.toolCalls)
        assertEquals(thinking, originalAssistant.thinkingBlocksJson)
        assertEquals("original reasoning", originalAssistant.reasoning)
        assertEquals("original signature", originalAssistant.signature)
        assertEquals(137, originalAssistant.inputTokens)
        val projectedResult = history[2] as AgentMessage.ToolResultMessage
        assertEquals(call.id, projectedResult.id)
        assertEquals("late-result", projectedResult.messageId)
        assertEquals(rows.last().content, projectedResult.result)
        assertImmediateToolPairs(history)
        assertEquals(listOf("user", "assistant", "user", "user", "assistant"), wire.map { it.role })
        val assistantBlocks = blocks(wire[1])
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), assistantBlocks.map { it.type })
        assertEquals("signed-block", assistantBlocks[0].signature)
        assertEquals("opaque", assistantBlocks[1].data)
        assertEquals(mapOf("command" to "printf original", "tool_title" to "Check original state"), assistantBlocks.last().input)
        assertEquals(listOf(call.id), blocks(wire[2]).map { it.tool_use_id })
        assertEquals(rows.last().content, blocks(wire[2]).single().content)
        assertEquals("New explicit question", wire[3].content)
        assertEquals(auditRows, rows)
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.insertAll(any()) }
    }

    @Test
    fun parallelCompletedResultsReplayInDeclaredOrderInASingleAnthropicResultMessage() = runTest {
        val first = ToolCall("first", "readFile", mapOf("path" to JsonPrimitive("first.kt")))
        val second = ToolCall("second", "readFile", mapOf("path" to JsonPrimitive("second.kt")))
        rows += row("user", "USER", "Read both", 1)
        rows += assistantRow("assistant", listOf(first, second), 2)
        rows += resultRow("second-result", second, "Second file", 3)
        rows += resultRow("first-result", first, "First file", 4)
        rows += row("answer", "ASSISTANT", "Both read", 5)

        val history = persistence.buildHistory("session", "[pending]")
        val wire = anthropicMessages(history)

        assertEquals(listOf("first-result", "second-result"), history.filterIsInstance<AgentMessage.ToolResultMessage>().map { it.messageId })
        assertEquals(listOf(first, second), (history[1] as AgentMessage.AssistantMessage).toolCalls)
        assertImmediateToolPairs(history)
        assertEquals(listOf("user", "assistant", "user", "assistant"), wire.map { it.role })
        assertEquals(listOf("first", "second"), blocks(wire[2]).map { it.tool_use_id })
        assertEquals(listOf("First file", "Second file"), blocks(wire[2]).map { it.content })
    }

    @Test
    fun compactionAndFrozenUserInputKeepTheirMeaningAroundReorderedToolResults() = runTest {
        val compactedCall = ToolCall("compacted", "Bash", emptyMap())
        val retainedCall = ToolCall("retained", "Bash", emptyMap())
        rows += assistantRow("compacted-assistant", listOf(compactedCall), 1).copy(isCompacted = true)
        rows += row("retained-user", "USER", "Keep this question", 2)
        rows += assistantRow("retained-assistant", listOf(retainedCall), 3)
        rows += row("new-user", "USER", "Display-only text", 4).copy(modelInputSnapshotJson = Json.encodeToString(
            ModelInputSnapshot("Frozen model input", projectMemoryContext = "Project state", projectMemoryProjectId = "project")
        ))
        rows += row("new-assistant", "ASSISTANT", "New answer", 5)
        rows += resultRow("retained-result", retainedCall, "Retained output", 6)
        rows += resultRow("orphan-compacted-result", compactedCall, "Compacted output", 7)
        rows += row("marker", "USER", "Compaction display", 8).copy(isCompactionMarker = true)
        rows += row("summary", "ASSISTANT", CONTEXT_SUMMARY_LEGACY_PREFIX + "\n Summary state", 9).copy(isContextSummary = true)
        val auditRows = rows.toList()

        val history = persistence.buildHistory("session", "[pending]")

        assertEquals(listOf("marker", "summary", "retained-user", "retained-assistant", "retained-result",
            "project-memory-new-user", "new-user", "new-assistant"), history.map { it.id })
        assertTrue((history[0] as AgentMessage.UserMessage).isCompactionMarker)
        assertTrue((history[1] as AgentMessage.AssistantMessage).isContextSummary)
        assertEquals("Summary state", (history[1] as AgentMessage.AssistantMessage).content)
        val memory = history[5] as AgentMessage.UserMessage
        val input = history[6] as AgentMessage.UserMessage
        assertTrue(memory.isTransient)
        assertEquals("new-user", memory.modelInputOwnerId)
        assertEquals(input.projectMemoryKey, memory.projectMemoryKey)
        assertEquals("Frozen model input", input.content)
        assertFalse(input.isTransient)
        assertImmediateToolPairs(history)
        val wire = anthropicMessages(history)
        val useIndex = wire.indexOfFirst { blocks(it).any { block -> block.type == "tool_use" } }
        assertEquals(listOf(retainedCall.id), blocks(wire[useIndex + 1]).map { it.tool_use_id })
        assertEquals(auditRows, rows)
    }

    @Test
    fun ambiguousIdsPlaceholdersMismatchedNamesAndMissingPairsCannotProduceToolResults() = runTest {
        val duplicateDeclaration = ToolCall("duplicate-declaration", "Bash", emptyMap())
        val duplicateResult = ToolCall("duplicate-result", "Bash", emptyMap())
        val mismatch = ToolCall("wrong-name", "Bash", emptyMap())
        val missing = ToolCall("missing", "Bash", emptyMap())
        val pending = ToolCall("pending", "Bash", emptyMap())
        val legacyPending = ToolCall("legacy-pending", "Bash", emptyMap())
        val tooEarly = ToolCall("too-early", "Bash", emptyMap())
        val sameBatchDuplicate = ToolCall("same-batch-duplicate", "Bash", emptyMap())
        val valid = ToolCall("valid", "Bash", emptyMap())
        rows += resultRow("too-early-result", tooEarly, "Older unrelated output", 1)
        rows += assistantRow("first", listOf(duplicateDeclaration, duplicateResult, mismatch, missing, pending,
            legacyPending, tooEarly, sameBatchDuplicate, sameBatchDuplicate, valid), 2)
        rows += assistantRow("second", listOf(duplicateDeclaration), 3)
        rows += resultRow("duplicate-declaration-result", duplicateDeclaration, "Ambiguous owner", 4)
        rows += resultRow("duplicate-result-a", duplicateResult, "Ambiguous result A", 5)
        rows += resultRow("duplicate-result-b", duplicateResult, "Ambiguous result B", 6)
        rows += resultRow("wrong-tool", mismatch, "Other tool output", 7).copy(toolName = "readFile")
        rows += resultRow("pending-result", pending, "[pending] waiting", 8)
        rows += resultRow("legacy-pending-result", legacyPending, SessionUseCase.LEGACY_PENDING_TOOL_MARKER + " waiting", 9)
        rows += resultRow("same-batch-duplicate-result", sameBatchDuplicate, "Ambiguous batch", 10)
        rows += resultRow("valid-placeholder", valid, "[pending] old placeholder", 11)
        rows += resultRow("valid-result", valid, "Actual completed output", 12)

        val history = persistence.buildHistory("session", "[pending]")
        val wire = anthropicMessages(history)

        assertEquals(listOf(valid), (history[0] as AgentMessage.AssistantMessage).toolCalls)
        assertTrue((history.last() as AgentMessage.AssistantMessage).toolCalls.isEmpty())
        val result = history.filterIsInstance<AgentMessage.ToolResultMessage>().single()
        assertEquals(valid.id, result.id)
        assertEquals("valid-result", result.messageId)
        assertEquals("Actual completed output", result.result)
        assertImmediateToolPairs(history)
        assertEquals(listOf(valid.id), wire.flatMap(::blocks).filter { it.type == "tool_use" }.map { it.id })
        assertEquals(listOf(valid.id), wire.flatMap(::blocks).filter { it.type == "tool_result" }.map { it.tool_use_id })
    }

    @Test
    fun compactedOrExcludedDuplicateIdsDoNotBindAResultToANewerDeclaration() = runTest {
        val reused = ToolCall("reused", "Bash", emptyMap())
        val excludedResult = ToolCall("excluded-result", "Bash", emptyMap())
        val valid = ToolCall("unique", "Bash", emptyMap())
        rows += assistantRow("old-compacted", listOf(reused), 1).copy(isCompacted = true)
        rows += assistantRow("current", listOf(reused, excludedResult, valid), 2)
        rows += resultRow("old-excluded-result", excludedResult, "Hidden older result", 3).copy(isContextExcluded = true)
        rows += resultRow("reused-result", reused, "Ambiguous declaration", 4)
        rows += resultRow("new-excluded-result", excludedResult, "Ambiguous duplicate result", 5)
        rows += resultRow("unique-result", valid, "Unambiguous completed result", 6)

        val history = persistence.buildHistory("session", "[pending]")

        assertEquals(listOf(valid), (history.first() as AgentMessage.AssistantMessage).toolCalls)
        assertEquals(listOf("unique-result"), history.filterIsInstance<AgentMessage.ToolResultMessage>().map { it.messageId })
        assertImmediateToolPairs(history)
        val wire = anthropicMessages(history)
        assertEquals(listOf(valid.id), wire.flatMap(::blocks).filter { it.type == "tool_result" }.map { it.tool_use_id })
    }

    private suspend fun anthropicMessages(history: List<AgentMessage>): List<AnthropicMessage> {
        val api = mockk<AnthropicApi>()
        val request = slot<AnthropicMessageRequest>()
        coEvery { api.createMessage(any(), any(), any(), any(), capture(request)) } returns AnthropicMessageResponse(
            id = "reply", type = "message", role = "assistant",
            content = listOf(AnthropicContentBlock(type = "text", text = "ok")), model = "claude-3-5-sonnet-20241022",
            stop_reason = "end_turn", stop_sequence = null, usage = AnthropicUsage(1, 1)
        )
        AnthropicAdapter(api).apply { cacheBreakpointsEnabled = false; maxNetworkRetries = 0 }
            .complete("system", history, emptyList(), null)
        return request.captured.messages
    }

    private fun blocks(message: AnthropicMessage): List<AnthropicContentBlock> =
        (message.content as? List<*>)?.filterIsInstance<AnthropicContentBlock>().orEmpty()

    private fun assertImmediateToolPairs(history: List<AgentMessage>) {
        val claimedResults = mutableSetOf<String>()
        history.forEachIndexed { index, message ->
            if (message is AgentMessage.AssistantMessage) {
                val actual = history.drop(index + 1).take(message.toolCalls.size)
                assertTrue(actual.all { it is AgentMessage.ToolResultMessage })
                assertEquals(message.toolCalls.map { it.id }, actual.map { (it as AgentMessage.ToolResultMessage).id })
                actual.forEach { assertTrue(claimedResults.add((it as AgentMessage.ToolResultMessage).messageId)) }
            }
        }
        assertEquals(history.filterIsInstance<AgentMessage.ToolResultMessage>().map { it.messageId }.toSet(), claimedResults)
    }

    private fun assistantRow(id: String, calls: List<ToolCall>, timestamp: Long) =
        row(id, "ASSISTANT", "Visible response", timestamp).copy(toolCallsJson = Json.encodeToString(calls))

    private fun resultRow(id: String, call: ToolCall, content: String, timestamp: Long) =
        row(id, "TOOL", content, timestamp).copy(toolCallId = call.id, toolName = call.name)

    private fun row(id: String, role: String, content: String, timestamp: Long = 1): AgentMessageEntity =
        AgentMessageEntity(id = id, sessionId = "session", role = role, content = content, timestamp = timestamp)

    private fun imageRow(id: String, timestamp: Long = 1): AgentMessageEntity {
        val file = temporaryFolder.newFile("$id.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val attachment = AgentAttachment(
            fileName = file.name, containerPath = "/workspace/${file.name}", localPath = file.absolutePath,
            mimeType = "image/png", sizeBytes = file.length(), isImage = true
        )
        return row(id, "USER", "image", timestamp).copy(attachmentsJson = Json.encodeToString(listOf(attachment)))
    }
}
