package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.presentation.MessageRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolActionTitlePersistenceTest {
    private val rows = linkedMapOf<String, AgentMessageEntity>()
    private val sessions = mutableSetOf("session", "other")
    private val queries = mockk<AgentMessageDao>(relaxed = true)
    private val dao = object : AgentMessageDao by queries {
        override suspend fun insertToolMessagePreservingMetadata(message: AgentMessageEntity): Boolean =
            super<AgentMessageDao>.insertToolMessagePreservingMetadata(message)

        override suspend fun insertMessageIfSessionExists(message: AgentMessageEntity) =
            super<AgentMessageDao>.insertMessageIfSessionExists(message)
    }
    private val database = mockk<AgentDatabase> {
        every { invalidationTracker } returns mockk(relaxed = true)
    }
    private val persistence = MessagePersistenceUseCase(dao, database)

    @Before
    fun setUp() {
        coEvery { queries.sessionExists(any()) } answers { firstArg<String>() in sessions }
        coEvery { queries.getMessageById(any()) } answers { rows[firstArg<String>()] }
        coEvery { queries.insert(any()) } answers {
            val message = firstArg<AgentMessageEntity>()
            rows[message.id] = message
        }
        coEvery { queries.getMessagesBySessionOnce(any()) } answers {
            rows.values.filter { it.sessionId == firstArg<String>() }.sortedBy { it.timestamp }
        }
    }

    @Test
    fun titleAndMeasuredTimingAreStoredOnFirstInsertAndMappedToUi() = runTest {
        persistTool("call", title = "Read configuration", startedAtMs = 10_000, durationMs = 125)

        val saved = rows.getValue("tool_call")
        assertEquals("Read configuration", saved.toolActionTitle)
        assertEquals(10_000L, saved.toolStartedAtMs)
        assertEquals(125L, saved.toolDurationMs)
        assertNull(saved.turnDurationMs)
        assertNotEquals(saved.timestamp, saved.toolStartedAtMs)
        val ui = saved.toUIMessage()
        assertEquals(saved.toolActionTitle, ui.toolActionTitle)
        assertEquals(saved.toolStartedAtMs, ui.toolStartedAtMs)
        assertEquals(saved.toolDurationMs, ui.toolDurationMs)
        assertNull(ui.turnDurationMs)
    }

    @Test
    fun finalResultReplacementKeepsStoredTitleTimingAndOrderingTimestamp() = runTest {
        persistTool("call", content = "[pending]", title = "Read configuration", startedAtMs = 10_000, durationMs = 125)
        val before = rows.getValue("tool_call")

        persistTool("call", content = "Failed", isError = true)

        assertEquals(before.copy(content = "Failed", isError = true), rows.getValue("tool_call"))
    }

    @Test
    fun replacementFillsMissingMetadataButCannotOverwriteFrozenValues() = runTest {
        persistTool("call", content = "[pending]")
        val original = rows.getValue("tool_call")
        persistTool("call", title = "Read configuration", startedAtMs = 10_000)
        persistTool("call", title = "Ignored title", startedAtMs = 99_000, durationMs = 0)
        persistTool("call", title = "Ignored again", durationMs = 999)

        val saved = rows.getValue("tool_call")
        assertEquals(original.timestamp, saved.timestamp)
        assertEquals("Read configuration", saved.toolActionTitle)
        assertEquals(10_000L, saved.toolStartedAtMs)
        assertEquals(0L, saved.toolDurationMs)
    }

    @Test
    fun reverseCompletionKeepsTheOriginalToolOrder() = runTest {
        persistTool("first", content = "[pending]")
        persistTool("second", content = "[pending]")
        val originalTimes = rows.mapValues { it.value.timestamp }

        persistTool("second", content = "Finished second")
        persistTool("first", content = "Finished first")

        assertEquals(originalTimes, rows.mapValues { it.value.timestamp })
        assertEquals(listOf("tool_first", "tool_second"), rows.values.sortedBy { it.timestamp }.map { it.id })
    }

    @Test
    fun persistedLegacyTitleSurvivesReplacementWithoutRegistration() = runTest {
        rows["tool_call"] = AgentMessageEntity(
            id = "tool_call", sessionId = "session", role = "TOOL", content = "[pending]", timestamp = 123,
            toolCallId = "call", toolActionTitle = "Saved in schema 67"
        )

        persistTool("call")

        val saved = rows.getValue("tool_call")
        assertEquals(123L, saved.timestamp)
        assertEquals("Saved in schema 67", saved.toolActionTitle)
        assertNull(saved.toolStartedAtMs)
        assertNull(saved.toolDurationMs)
        assertNull(saved.toUIMessage().toolStartedAtMs)
        assertNull(saved.toUIMessage().toolDurationMs)
    }

    @Test
    fun turnAndToolTimingAreMutuallyExclusiveByMessageRole() = runTest {
        for (role in MessageRole.entries) {
            persistence.persist(
                sessionId = "session", role = role, content = "Message", id = role.name,
                toolActionTitle = "Tool title", toolStartedAtMs = 10_000, toolDurationMs = 125, turnDurationMs = 4_000
            )
            val saved = rows.getValue(role.name)
            val ui = saved.toUIMessage()
            if (role == MessageRole.TOOL) {
                assertEquals("Tool title", saved.toolActionTitle)
                assertEquals(10_000L, saved.toolStartedAtMs)
                assertEquals(125L, saved.toolDurationMs)
            } else {
                assertNull(saved.toolActionTitle)
                assertNull(saved.toolStartedAtMs)
                assertNull(saved.toolDurationMs)
            }
            assertEquals(if (role == MessageRole.ASSISTANT) 4_000L else null, saved.turnDurationMs)
            assertEquals(saved.turnDurationMs, ui.turnDurationMs)
            assertEquals(saved.toolStartedAtMs, ui.toolStartedAtMs)
            assertEquals(saved.toolDurationMs, ui.toolDurationMs)
        }
    }

    @Test
    fun timingUpdateDelegatesOriginalIdsAndNullableEventsWithoutInserting() = runTest {
        persistence.updateToolTiming("session", "call", 10_000, null)
        persistence.updateToolTiming("session", "call", null, 125)
        persistence.updateToolTiming("other", "call", null, null)
        persistence.updateTurnDuration("session", "assistant", 4_000)

        coVerify(exactly = 1) { queries.updateToolTiming("session", "call", 10_000, null) }
        coVerify(exactly = 1) { queries.updateToolTiming("session", "call", null, 125) }
        coVerify(exactly = 1) { queries.updateToolTiming("other", "call", null, null) }
        coVerify(exactly = 1) { queries.updateTurnDuration("session", "assistant", 4_000) }
        coVerify(exactly = 0) { queries.insert(any()) }
        assertTrue(rows.isEmpty())
    }

    @Test
    fun replacementCannotStealAnotherSessionCallOrRole() = runTest {
        persistTool("call", sessionId = "other", title = "Other session", startedAtMs = 10_000, durationMs = 125)
        val original = rows.getValue("tool_call")
        persistTool("call", sessionId = "session", title = "Wrong session")
        assertEquals(original, rows.getValue("tool_call"))
        rows[original.id] = original.copy(sessionId = "session", toolCallId = "different")
        persistTool("call", title = "Wrong call")
        assertEquals(original.copy(sessionId = "session", toolCallId = "different"), rows.getValue(original.id))
        rows[original.id] = original.copy(sessionId = "session", role = "ASSISTANT")
        persistTool("call", title = "Wrong role")
        assertEquals(original.copy(sessionId = "session", role = "ASSISTANT"), rows.getValue(original.id))
    }

    @Test
    fun missingSessionCannotReceiveToolInsertsOrReplacements() = runTest {
        persistTool("existing", title = "Keep")
        val original = rows.getValue("tool_existing")
        sessions.remove("session")

        persistTool("existing", content = "Late result")
        persistTool("new", content = "Late result")

        assertEquals(original, rows.getValue("tool_existing"))
        assertFalse(rows.containsKey("tool_new"))
        coVerify(exactly = 1) { queries.insert(any()) }
    }

    @Test
    fun requiredSessionSkipsAllMessageRolesAfterDeletionWithoutInserting() = runTest {
        sessions.remove("session")

        for (role in MessageRole.entries) {
            persistence.persist(
                sessionId = "session", role = role, content = "Late feedback", id = role.name,
                toolCallId = "call", requireSession = true
            )
        }

        assertTrue(rows.isEmpty())
        coVerify(exactly = 0) { queries.insert(any()) }
        coVerify(exactly = 0) { queries.getMessageById(any()) }
    }

    @Test
    fun requiredSessionStoresExcludedAssistantFeedbackWhileTheSessionExists() = runTest {
        persistence.persist(
            sessionId = "session", role = MessageRole.ASSISTANT, content = "Compaction feedback", id = "feedback",
            isContextExcluded = true, requireSession = true
        )

        val saved = rows.getValue("feedback")
        assertEquals("session", saved.sessionId)
        assertEquals("ASSISTANT", saved.role)
        assertEquals("Compaction feedback", saved.content)
        assertTrue(saved.isContextExcluded)
        coVerify(exactly = 1) { queries.insert(any()) }
    }

    @Test
    fun requiredSessionToolReplacementStillPreservesEstablishedMetadata() = runTest {
        persistTool("call", content = "[pending]", title = "Keep title", startedAtMs = 10_000, durationMs = 125)
        val original = rows.getValue("tool_call")

        persistence.persist(
            sessionId = "session", role = MessageRole.TOOL, content = "Finished", id = "tool_call",
            toolCallId = "call", toolName = "readFile", toolArgs = "{\"path\":\"/workspace/config.json\"}",
            toolActionTitle = "Ignored title", toolStartedAtMs = 99_000, toolDurationMs = 999,
            requireSession = true
        )

        assertEquals(original.copy(content = "Finished"), rows.getValue("tool_call"))
    }

    @Test
    fun ordinaryUserAndAssistantWritesKeepTheirOriginalDefaultPath() = runTest {
        sessions.remove("session")

        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
            persistence.persist(sessionId = "session", role = role, content = "Message", id = role.name)
        }

        assertEquals(setOf("USER", "ASSISTANT"), rows.keys)
        coVerify(exactly = 2) { queries.insert(any()) }
        coVerify(exactly = 0) { queries.sessionExists(any()) }
    }

    @Test
    fun presentationMetadataStaysOutOfModelHistoryAndDoesNotRewriteToolArgumentsOrResults() = runTest {
        val call = ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("/workspace/config.json")))
        persistence.persist("session", MessageRole.ASSISTANT, "", id = "assistant", toolCalls = listOf(call), turnDurationMs = 4_000)
        persistTool("call", content = "Raw tool output", title = "UI-only title", startedAtMs = 10_000, durationMs = 125)

        val history = persistence.buildHistory("session", "[pending]")

        assertEquals(2, history.size)
        assertEquals(listOf(call), (history[0] as AgentMessage.AssistantMessage).toolCalls)
        assertEquals("Raw tool output", (history[1] as AgentMessage.ToolResultMessage).result)
        assertFalse(history.toString().contains("UI-only title"))
        assertFalse(history.toString().contains("toolDurationMs"))
        assertFalse(history.toString().contains("turnDurationMs"))
        assertEquals("{\"path\":\"/workspace/config.json\"}", rows.getValue("tool_call").toolArgs)
    }

    private suspend fun persistTool(
        callId: String,
        sessionId: String = "session",
        content: String = "Finished",
        isError: Boolean = false,
        title: String? = null,
        startedAtMs: Long? = null,
        durationMs: Long? = null
    ) {
        persistence.persist(
            sessionId = sessionId,
            role = MessageRole.TOOL,
            content = content,
            id = "tool_$callId",
            toolCallId = callId,
            toolName = "readFile",
            toolArgs = "{\"path\":\"/workspace/config.json\"}",
            isError = isError,
            toolActionTitle = title,
            toolStartedAtMs = startedAtMs,
            toolDurationMs = durationMs
        )
    }
}
