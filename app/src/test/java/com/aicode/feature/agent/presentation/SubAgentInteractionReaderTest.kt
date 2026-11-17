package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentHistoryStats
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentInteractionReaderTest {
    private fun child() = ChatSessionEntity("child", "Inspect sources", 1, 1, workspacePath = "/work", parentId = "parent", model = "actual")
    private fun event(id: String, kind: String, time: Long, preview: String = "", origin: String = "PARENT", instructionId: String? = null) =
        SubAgentInteractionMetadata(id, "parent", "child", kind, time, origin, null, "Inspect sources", "actual", preview, instructionId)
    private fun project(events: List<SubAgentInteractionMetadata>, runtime: SubAgentInteractionRuntime = SubAgentInteractionRuntime(), children: List<ChatSessionEntity> = listOf(child())) =
        projectSubAgentInteractionSummaries(SubAgentInteractionFacts(events, children,
            listOf(SubAgentHistoryStats("child", 1, 2, 4, 100, "history result"))), runtime).getValue("child")

    @Test fun ownerlessHistoryDoesNotRestartOrInventRunningAndApprovalsHavePriority() {
        assertEquals(SubAgentInteractionStatus.HISTORY, project(emptyList()).status)
        assertNull(project(emptyList()).startedAtMs)
        assertEquals(SubAgentInteractionStatus.INTERRUPTED, project(listOf(event("spawn", "SPAWNED", 10))).status)
        val old = listOf(event("spawn", "SPAWNED", 10), event("done", "COMPLETED", 20, "old result"))
        val waiting = project(old, SubAgentInteractionRuntime(activeIds = setOf("child"), awaitingIds = setOf("child")))
        assertEquals(SubAgentInteractionStatus.AWAITING_CONFIRMATION, waiting.status)
        assertNull(waiting.finishedAtMs)
        assertEquals("", waiting.latestPreview)
        val failed = project(old, SubAgentInteractionRuntime(terminalStatuses = mapOf("child" to SubAgentInteractionStatus.FAILED)))
        assertEquals(SubAgentInteractionStatus.FAILED, failed.status)
        assertNull(failed.startedAtMs)
        assertNull(failed.finishedAtMs)
        assertEquals("", failed.latestPreview)
    }

    @Test fun eachRunHasItsOwnStartAndAcceptedInstructionNeverBorrowsAnOldSuccess() {
        val events = listOf(event("first", "STARTED", 10),
            event("accepted", "MESSAGE_FROM_PARENT", 15, origin = "USER"), event("done", "COMPLETED", 20, "old"))
        assertEquals(SubAgentInteractionStatus.INTERRUPTED, project(events).status)
        assertNull(project(events).finishedAtMs)
        assertEquals("", project(events).latestPreview)
        val resumed = events + event("next", "STARTED", 30, instructionId = "accepted") + event("last", "COMPLETED", 60, "new")
        val summary = project(resumed)
        assertEquals(SubAgentInteractionStatus.COMPLETED, summary.status)
        assertEquals(30L, summary.startedAtMs)
        assertEquals(60L, summary.finishedAtMs)
        assertEquals("new", summary.latestPreview)
        assertEquals(3, summary.exchangeCount)
        assertEquals(4, summary.toolCallCount)
    }

    @Test fun reorderedUnstartedInstructionDoesNotDisappearBehindAnotherStartedRun() {
        val events = listOf(event("a", "MESSAGE_FROM_PARENT", 10, origin = "USER"),
            event("b", "MESSAGE_FROM_PARENT", 11, origin = "USER"),
            event("start-b", "STARTED", 20, instructionId = "b"), event("done-b", "COMPLETED", 30, "B"))
        val summary = project(events)
        assertEquals(SubAgentInteractionStatus.INTERRUPTED, summary.status)
        assertEquals("", summary.latestPreview)
        val consumedA = events + event("start-a", "STARTED", 40, instructionId = "a") + event("done-a", "COMPLETED", 50, "A")
        assertEquals(SubAgentInteractionStatus.COMPLETED, project(consumedA).status)
        assertEquals("A", project(consumedA).latestPreview)
    }

    @Test fun cancelledQueueInstructionDoesNotPermanentlyOverrideLaterRealCompletion() {
        val events = listOf(event("a", "MESSAGE_FROM_PARENT", 10, origin = "USER"),
            event("b", "MESSAGE_FROM_PARENT", 11, origin = "USER"),
            event("start-b", "STARTED", 20, instructionId = "b"), event("done-b", "COMPLETED", 30, "B"),
            event("cancel-a", "INSTRUCTION_CANCELLED", 40, instructionId = "a"))
        val summary = project(events)
        assertEquals(SubAgentInteractionStatus.COMPLETED, summary.status)
        assertEquals("B", summary.latestPreview)
        assertEquals(20L, summary.startedAtMs)
        assertEquals(30L, summary.finishedAtMs)
        assertEquals(100L, summary.lastActivityAtMs)
    }

    @Test fun persistedInterruptedEventHasItsOwnTerminalTime() {
        val summary = project(listOf(event("start", "STARTED", 10), event("interrupted", "INTERRUPTED", 20)))
        assertEquals(SubAgentInteractionStatus.INTERRUPTED, summary.status)
        assertEquals(10L, summary.startedAtMs)
        assertEquals(20L, summary.finishedAtMs)
        assertEquals("", summary.latestPreview)
    }

    @Test fun deletedChildNameUsesPersistedAuditSnapshot() {
        val summary = project(listOf(event("done", "COMPLETED", 20, "result").copy(agentName = "编码员")),
            children = emptyList())
        assertEquals(SubAgentInteractionStatus.DELETED, summary.status)
        assertEquals("编码员", summary.agentName)
        assertFalse(summary.sessionExists)
    }

    @Test fun deletedChildKeepsOnlyParentAuditAndNoExecutableSession() {
        val deleted = project(listOf(event("done", "COMPLETED", 20, "audit result")),
            SubAgentInteractionRuntime(activeIds = setOf("child")), emptyList())
        assertEquals(SubAgentInteractionStatus.DELETED, deleted.status)
        assertFalse(deleted.sessionExists)
        assertEquals("audit result", deleted.latestPreview)
    }

    @Test fun legacyEntriesKeepIdentityAndFullPublicBodyButNotPrivateReasoning() {
        val xml = "$BACKGROUND_NOTIFICATION_PREFIX\n<agent-message><message>line 1\n&lt;literal&gt; &amp; line 2</message></agent-message>"
        val duplicateBodies = recoverLegacySubAgentInteractions(listOf(
            AgentMessageEntity("one", "child", "USER", xml, 1), AgentMessageEntity("two", "child", "USER", xml, 2)))
        assertEquals(listOf("line 1\n<literal> & line 2", "line 1\n<literal> & line 2"), duplicateBodies.map { it.content })
        assertEquals(2, duplicateBodies.map { it.id }.distinct().size)
        val full = "public report\n".repeat(2000)
        val calls = buildJsonArray { add(buildJsonObject {
            put("name", "messageParent"); put("arguments", buildJsonObject { put("message", full) })
        }) }.toString()
        val entry = recoverLegacySubAgentInteractions(listOf(AgentMessageEntity("assistant", "child", "ASSISTANT", "", 3,
            toolCallsJson = calls, reasoning = "PRIVATE"))).single()
        assertEquals(full, entry.content)
        assertEquals("assistant", entry.sourceMessageId)
    }

    @Test fun pageConstructionIsColdAndLoadsFullJournalWithHonestRecoveryLabel() = runTest {
        val journal = mockk<SubAgentInteractionDao>()
        val messages = mockk<AgentMessageDao>()
        val full = "unabridged\n".repeat(3000)
        every { journal.getByChildPaged("parent", "child", 11) } returns flowOf(listOf(
            SubAgentInteractionEntity("new", "parent", "child", "MESSAGE_FROM_PARENT", full, 20, "USER")))
        every { messages.observeSubAgentLegacyMessages("parent", "child", 31) } returns flowOf(listOf(
            AgentMessageEntity("old", "child", "USER", "old instruction", 10)))
        val pageFlow = SubAgentInteractionReader(journal, mockk(), messages).conversation("parent", "child", 10)
        verify(exactly = 0) { journal.getByChildPaged(any(), any(), any()) }
        verify(exactly = 0) { messages.observeSubAgentLegacyMessages(any(), any(), any()) }
        val page = pageFlow.first()
        assertTrue(page.loaded)
        assertTrue(page.recoveredFromHistory)
        assertEquals(listOf("old instruction", full), page.entries.map { it.content })
        assertFalse(page.hasMore)
    }

    @Test fun cancellationReleasesBothConversationQueries() = runTest {
        val journal = mockk<SubAgentInteractionDao>()
        val messages = mockk<AgentMessageDao>()
        val startedA = CompletableDeferred<Unit>()
        val startedB = CompletableDeferred<Unit>()
        val closedA = CompletableDeferred<Unit>()
        val closedB = CompletableDeferred<Unit>()
        every { journal.getByChildPaged(any(), any(), any()) } returns flow {
            startedA.complete(Unit)
            try { emit(emptyList<SubAgentInteractionEntity>()); awaitCancellation() } finally { closedA.complete(Unit) }
        }
        every { messages.observeSubAgentLegacyMessages(any(), any(), any()) } returns flow {
            startedB.complete(Unit)
            try { emit(emptyList<AgentMessageEntity>()); awaitCancellation() } finally { closedB.complete(Unit) }
        }
        val job = launch { SubAgentInteractionReader(journal, mockk(), messages).conversation("parent", "child", 10).collect {} }
        startedA.await(); startedB.await()
        job.cancelAndJoin()
        assertTrue(closedA.isCompleted && closedB.isCompleted)
    }

    @Test fun ownershipGateDeniesOtherParentsAndRevokesExecutionOnReparenting() = runTest {
        val sessions = mockk<ChatSessionDao>()
        val messages = mockk<AgentMessageDao>()
        val ownership = MutableStateFlow<ChatSessionEntity?>(child().copy(parentId = "other"))
        every { sessions.getByIdFlow("child") } returns ownership
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        every { messages.observeSubAgentExecutionMessages("parent", "child", 11) } returns flow {
            started.complete(Unit)
            try { emit(listOf(AgentMessageEntity("body", "child", "ASSISTANT", "public", 1, reasoning = "private"))); awaitCancellation() }
            finally { closed.complete(Unit) }
        }
        val reader = SubAgentInteractionReader(mockk(), sessions, messages)
        assertFalse(reader.ownsChild("parent", "child").first())
        val denied = reader.execution("parent", "child", 10).first()
        assertTrue(denied.loaded && denied.messages.isEmpty())
        verify(exactly = 0) { messages.observeSubAgentExecutionMessages(any(), any(), any()) }
        ownership.value = child()
        val received = CompletableDeferred<SubAgentExecutionPage>()
        val revoked = CompletableDeferred<Unit>()
        val job = launch {
            reader.execution("parent", "child", 10).collect { page ->
                if (page.messages.isNotEmpty()) received.complete(page)
                else if (received.isCompleted) revoked.complete(Unit)
            }
        }
        val page = received.await()
        assertEquals("public", page.messages.single().content)
        assertNull(page.messages.single().reasoning)
        started.await()
        ownership.value = child().copy(parentId = "other")
        closed.await()
        revoked.await()
        job.cancelAndJoin()
        assertFalse(reader.ownsChild("parent", "child").first())
    }
}
