package com.aicode.feature.agent.domain.tool.subagent

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.subagent.SubAgentJournalFixture
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.SubagentTierSettingsRepository
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentToolJournalTest {
    private class Boundary {
        val fixture = SubAgentJournalFixture()
        val definitions = mockk<AgentDefinitionRepository>()
        val providers = mockk<AIProviderRepository>()
        val tiers = mockk<SubagentTierSettingsRepository>()
        val appContext = mockk<Context>()
        val task = TaskTool(fixture.sessionUseCase, fixture.sessions, fixture.messages, fixture.bus,
            definitions, providers, tiers, appContext)
        val messageParent = MessageParentTool(fixture.sessionUseCase, fixture.bus)

        init {
            every { definitions.listEnabled() } returns emptyList()
            every { providers.getAllProviders() } returns flowOf(emptyList())
            coEvery { tiers.snapshot() } returns emptyMap()
            every { appContext.getString(any()) } returns "delivered"
            every { fixture.sessionUseCase.newSubSessionEntity(any(), any(), any(), any(), any(), any(), any()) } answers {
                val parent = arg<ChatSessionEntity>(2)
                parent.copy(
                    id = UUID.randomUUID().toString(),
                    title = firstArg(),
                    parentId = arg(1),
                    subagentType = arg(3),
                    providerId = arg<String?>(4) ?: parent.providerId,
                    model = arg<String?>(5) ?: parent.model,
                    reasoningEffort = arg<String?>(6) ?: parent.reasoningEffort
                )
            }
        }
    }

    @Before
    fun silenceLogging() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
    }

    @After
    fun restoreLogging() {
        unmockkObject(FileLogger)
    }

    private fun context(id: String) = AgentContext(null, null, "/work", null, sessionId = id)
    private fun action(action: String) = mapOf("action" to JsonPrimitive(action), "id" to JsonPrimitive("child"))

    @Test
    fun sendToolsRecordBothDirectionsAndRepeatedBodiesRemainSeparate() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.bus.events.collect {
                assertNotNull(fixture.interactions.getById(it.eventId))
                received += it
            }
        }
        val parentBody = "actual full parent message\n".repeat(2000)
        val childBody = "Need a decision\n".repeat(200)
        repeat(2) {
            assertTrue(boundary.task.executeWithContext(action("send") + ("message" to JsonPrimitive(parentBody)),
                context("parent")) is ToolResult.Success)
        }
        assertTrue(boundary.messageParent.executeWithContext(mapOf("message" to JsonPrimitive(childBody)),
            context("child")) is ToolResult.Success)
        val rows = fixture.interactions.getAllOnce()
        assertEquals(3, rows.size)
        assertEquals(3, rows.map { it.id }.toSet().size)
        assertEquals(3, received.size)
        assertTrue(rows.filter { it.kind == "MESSAGE_FROM_PARENT" }.all {
            it.content == parentBody.trim() && it.origin == "PARENT"
        })
        val reply = rows.single { it.kind == "MESSAGE_FROM_SUB" }
        assertEquals(childBody.trim(), reply.content)
        assertEquals("SUB", reply.origin)
    }

    @Test
    fun createRecordsFullPromptAfterSessionUpsertAndBeforeSpawnDelivery() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        val prompt = "complete delegated instruction\n".repeat(2000)
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.bus.events.collect {
                assertTrue(fixture.sessionRows.containsKey(it.subSessionId))
                assertEquals(prompt.trim(), fixture.interactions.getById(it.eventId)!!.content)
                received += it
            }
        }
        val result = boundary.task.executeWithContext(mapOf("prompt" to JsonPrimitive(prompt)), context("parent"))
        assertTrue(result is ToolResult.Success)
        val stored = fixture.interactions.getAllOnce().single()
        assertEquals("SPAWNED", stored.kind)
        assertEquals("PARENT", stored.origin)
        assertEquals(prompt.trim(), stored.content)
        assertEquals(stored.subSessionId, received.single().subSessionId)
        assertEquals(setOf(stored.subSessionId), fixture.bus.activeSubSessionIds.value)
        assertEquals(1, fixture.bus.activeCount)
    }

    @Test
    fun createJournalFailureReleasesUnusedReservationAndNeverDispatches() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        fixture.interactions.insertFailure = IllegalStateException("disk full")
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        try {
            boundary.task.executeWithContext(mapOf("prompt" to JsonPrimitive("task")), context("parent"))
            fail("Failed recording must not report success")
        } catch (error: IllegalStateException) {
            assertEquals("disk full", error.message)
        }
        assertTrue(received.isEmpty())
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
        assertEquals(0, fixture.bus.activeCount)
    }

    @Test
    fun stopRecordsParentOriginAndFullReasonBeforeReleasingRuntimeSlot() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        fixture.bus.markStarted("child")
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.bus.events.collect {
                val stored = fixture.interactions.getById(it.eventId)!!
                assertEquals("STOPPED", stored.kind)
                assertEquals("PARENT", stored.origin)
                assertEquals("Stopped by parent via task(action=\"stop\")", stored.content)
                assertEquals(0, fixture.bus.activeCount)
                received += it
            }
        }
        assertTrue(boundary.task.executeWithContext(action("stop"), context("parent")) is ToolResult.Success)
        assertEquals(1, received.size)
    }

    @Test
    fun idleChildDeletionIsRecordedAndDeliveredBeforeSessionRemoval() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        coEvery { fixture.sessionUseCase.deleteSession("child") } coAnswers {
            val stored = fixture.interactions.getAllOnce().single()
            assertEquals("DELETED", stored.kind)
            assertEquals("PARENT", stored.origin)
            assertEquals("Child", stored.title)
            assertEquals("Deleted by parent via task(action=\"del\")", stored.content)
            assertEquals(listOf(SubAgentEventType.DELETED), received.map { it.type })
            fixture.sessionRows.remove("child")
            fixture.interactions.deleteByParent("child")
            listOf("child")
        }
        assertEquals(0, fixture.bus.activeCount)
        assertTrue(boundary.task.executeWithContext(action("del"), context("parent")) is ToolResult.Success)
        assertFalse(fixture.sessionRows.containsKey("child"))
        assertEquals("DELETED", fixture.interactions.getAllOnce().single().kind)
        coVerify(exactly = 1) { fixture.sessionUseCase.deleteSession("child") }
    }

    @Test
    fun runningChildDeletionUsesDeletedRatherThanStoppedAndRetainsParentAudit() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        fixture.bus.markStarted("child")
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        assertTrue(boundary.task.executeWithContext(action("del"), context("parent")) is ToolResult.Success)
        assertEquals(listOf(SubAgentEventType.DELETED), received.map { it.type })
        assertEquals(0, fixture.bus.activeCount)
        assertEquals("parent", fixture.interactions.getAllOnce().single().parentSessionId)
        assertFalse(fixture.sessionRows.containsKey("child"))
    }

    @Test
    fun deleteJournalFailurePreservesChildAndDoesNotRequestRuntimeDeletion() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        fixture.interactions.insertFailure = IllegalStateException("disk full")
        fixture.bus.markStarted("child")
        try {
            boundary.task.executeWithContext(action("del"), context("parent"))
            fail("Failed audit must block deletion")
        } catch (_: IllegalStateException) {
        }
        assertTrue(fixture.sessionRows.containsKey("child"))
        assertEquals(setOf("child"), fixture.bus.activeSubSessionIds.value)
        coVerify(exactly = 0) { fixture.sessionUseCase.deleteSession(any()) }
    }

    @Test
    fun messageParentJournalFailureCannotReportSent() = runTest {
        val boundary = Boundary()
        val fixture = boundary.fixture
        fixture.interactions.insertFailure = IllegalStateException("disk full")
        val received = mutableListOf<SubAgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.bus.events.collect { received += it } }
        try {
            boundary.messageParent.executeWithContext(mapOf("message" to JsonPrimitive("Need input")), context("child"))
            fail("Failed recording must not report sent")
        } catch (_: IllegalStateException) {
        }
        assertTrue(received.isEmpty())
        assertTrue(fixture.interactions.getAllOnce().isEmpty())
    }

    @Test
    fun toolsRejectUnownedChildBeforeWritingOrDeleting() = runTest {
        val boundary = Boundary()
        for (actionName in listOf("send", "stop", "del")) {
            val result = boundary.task.executeWithContext(action(actionName) + ("message" to JsonPrimitive("go")), context("other-parent"))
            assertTrue(result is ToolResult.Error)
        }
        assertTrue(boundary.fixture.interactions.getAllOnce().isEmpty())
        coVerify(exactly = 0) { boundary.fixture.sessionUseCase.deleteSession(any()) }
    }
}
