package com.aicode.feature.agent.domain.session

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.RecoverySessionGate
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryNotificationCoordinator
import com.aicode.feature.agent.domain.subagent.SubAgentInteractionJournal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionUseCaseRecoveryTest {
    private val sessionDao = mockk<ChatSessionDao>(relaxed = true)
    private val messageDao = mockk<AgentMessageDao>(relaxed = true)
    private val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
    private val recoveryRepository = mockk<RecoveryRepository>(relaxed = true)
    private val subAgentJournal = mockk<SubAgentInteractionJournal>(relaxed = true)
    private val notificationRecovery = mockk<RecoveryNotificationCoordinator>(relaxed = true)
    private val useCase = SessionUseCase(sessionDao, messageDao, interactionDao, recoveryRepository, subAgentJournal, notificationRecovery)

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.e(any(), any(), any()) } returns Unit
        coEvery { sessionDao.getRecoveryGate(any()) } returns null
        coEvery { sessionDao.hasPendingRecovery(any()) } returns false
        coEvery { messageDao.hasMessages(any()) } returns false
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun concurrentColdStartCallersAwaitOneInitializationAndBothLegacyMarkers() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { recoveryRepository.initialize() } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val first = async { useCase.initColdStartCleanup() }
        entered.await()
        val second = async { useCase.initColdStartCleanup() }
        runCurrent()

        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        coVerify(exactly = 0) { messageDao.markPendingToolsInterrupted(any(), any(), any()) }
        release.complete(Unit)
        first.await()
        second.await()
        useCase.initColdStartCleanup()

        coVerify(exactly = 1) { recoveryRepository.initialize() }
        coVerify(exactly = 1) { subAgentJournal.recoverInterrupted() }
        coVerify(exactly = 1) { notificationRecovery.prepareConfirmationRequests() }
        coVerify(exactly = 1) {
            messageDao.markPendingToolsInterrupted("TOOL", "${SessionUseCase.PENDING_TOOL_MARKER}%", SessionUseCase.INTERRUPTED_TOOL_TEXT)
        }
        coVerify(exactly = 1) {
            messageDao.markPendingToolsInterrupted("TOOL", "${SessionUseCase.LEGACY_PENDING_TOOL_MARKER}%", SessionUseCase.INTERRUPTED_TOOL_TEXT)
        }
        coVerifyOrder {
            recoveryRepository.initialize()
            subAgentJournal.recoverInterrupted()
            notificationRecovery.prepareConfirmationRequests()
            messageDao.markPendingToolsInterrupted("TOOL", "${SessionUseCase.PENDING_TOOL_MARKER}%", SessionUseCase.INTERRUPTED_TOOL_TEXT)
            messageDao.markPendingToolsInterrupted("TOOL", "${SessionUseCase.LEGACY_PENDING_TOOL_MARKER}%", SessionUseCase.INTERRUPTED_TOOL_TEXT)
        }
    }

    @Test
    fun failedInitializationRemainsFailedForAllCallersWithoutLegacyCleanup() = runTest {
        val failure = IllegalStateException("Recovery initialization failed")
        coEvery { recoveryRepository.initialize() } throws failure

        assertSame(failure, runCatching { useCase.initColdStartCleanup() }.exceptionOrNull())
        val repeatedFailure = runCatching { useCase.initColdStartCleanup() }.exceptionOrNull()
        assertEquals(failure.javaClass, repeatedFailure?.javaClass)
        assertEquals(failure.message, repeatedFailure?.message)
        assertTrue(generateSequence(repeatedFailure) { it.cause }.any { it === failure })

        coVerify(exactly = 1) { recoveryRepository.initialize() }
        coVerify(exactly = 0) { messageDao.markPendingToolsInterrupted(any(), any(), any()) }
    }

    @Test
    fun failedLegacyCleanupCannotBeBypassedByLaterCallers() = runTest {
        val failure = IllegalStateException("Message cleanup failed")
        coEvery { messageDao.markPendingToolsInterrupted(any(), any(), any()) } throws failure

        assertSame(failure, runCatching { useCase.initColdStartCleanup() }.exceptionOrNull())
        val repeatedFailure = runCatching { useCase.initColdStartCleanup() }.exceptionOrNull()
        assertEquals(failure.javaClass, repeatedFailure?.javaClass)
        assertEquals(failure.message, repeatedFailure?.message)
        assertTrue(generateSequence(repeatedFailure) { it.cause }.any { it === failure })

        coVerify(exactly = 1) { recoveryRepository.initialize() }
        coVerify(exactly = 1) { messageDao.markPendingToolsInterrupted(any(), any(), any()) }
    }

    @Test
    fun deletingNestedSessionsFencesEachBeforeDiscoveryAndClearsRecoveryBeforeHistory() = runTest {
        coEvery { sessionDao.getSubSessionsByParentOnce("root") } returns listOf(session("child", "root"))
        coEvery { sessionDao.getSubSessionsByParentOnce("child") } returns listOf(session("grandchild", "child"))

        assertEquals(listOf("root", "child", "grandchild"), useCase.deleteSession("root"))

        coVerifyOrder {
            recoveryRepository.deleteSession("root", "session_deleted")
            sessionDao.getSubSessionsByParentOnce("root")
            recoveryRepository.deleteSession("child", "session_deleted")
            sessionDao.getSubSessionsByParentOnce("child")
            recoveryRepository.deleteSession("grandchild", "session_deleted")
            sessionDao.getSubSessionsByParentOnce("grandchild")
            interactionDao.deleteByParent("grandchild")
            messageDao.deleteBySession("grandchild")
            sessionDao.delete("grandchild")
            interactionDao.deleteByParent("child")
            messageDao.deleteBySession("child")
            sessionDao.delete("child")
            interactionDao.deleteByParent("root")
            messageDao.deleteBySession("root")
            sessionDao.delete("root")
        }
        coVerify(exactly = 0) { interactionDao.deleteAll() }
    }

    @Test
    fun deletingChildPreservesParentOwnedAuditAndRecovery() = runTest {
        useCase.deleteSession("child")

        coVerify(exactly = 1) { recoveryRepository.deleteSession("child", "session_deleted") }
        coVerify(exactly = 0) { recoveryRepository.deleteSession("root", any()) }
        coVerify(exactly = 0) { interactionDao.deleteByParent("root") }
        coVerify(exactly = 0) { interactionDao.deleteAll() }
    }

    @Test
    fun deletingWorkspaceFencesAllSessionsBeforeRemovingAnyHistory() = runTest {
        coEvery { sessionDao.getAllSessionsByWorkspaceOnce("/workspace") } returns listOf(session("root"), session("child", "root"))

        assertEquals(2, useCase.deleteSessionsByWorkspace("/workspace"))

        coVerifyOrder {
            recoveryRepository.deleteSession("root", "session_deleted")
            recoveryRepository.deleteSession("child", "session_deleted")
            interactionDao.deleteByParent("root")
            messageDao.deleteBySession("root")
            interactionDao.deleteByParent("child")
            messageDao.deleteBySession("child")
            sessionDao.deleteByWorkspace("/workspace")
        }
    }

    @Test
    fun recoveryDeletionFailureDoesNotDeleteSessionHistory() = runTest {
        val failure = IllegalStateException("Recovery storage failed")
        coEvery { recoveryRepository.deleteSession("root", any()) } throws failure

        assertSame(failure, runCatching { useCase.deleteSession("root") }.exceptionOrNull())

        coVerify(exactly = 0) { messageDao.deleteBySession(any()) }
        coVerify(exactly = 0) { interactionDao.deleteByParent(any()) }
        coVerify(exactly = 0) { sessionDao.delete(any()) }
    }

    @Test
    fun emptySessionChecksRetainRecoveryRequestsAndPersistentPlanHold() = runTest {
        coEvery { sessionDao.hasPendingRecovery("pending") } returns true
        coEvery { sessionDao.getRecoveryGate("held") } returns RecoverySessionGate("held", 7, "plan_rejected")
        coEvery { messageDao.hasMessages("history") } returns true

        assertFalse(useCase.isSessionEmpty("pending"))
        assertFalse(useCase.isSessionEmpty("held"))
        assertFalse(useCase.isSessionEmpty("history"))
        assertTrue(useCase.isSessionEmpty("empty"))
    }

    @Test
    fun recyclingEmptySessionsPreservesPendingHeldHistoryAndKeepId() = runTest {
        coEvery { sessionDao.getRootSessionsByWorkspaceOnce("/workspace") } returns
            listOf("pending", "held", "history", "keep", "empty").map { session(it) }
        coEvery { sessionDao.hasPendingRecovery("pending") } returns true
        coEvery { sessionDao.getRecoveryGate("held") } returns RecoverySessionGate("held", 4, "plan_rejected")
        coEvery { messageDao.hasMessages("history") } returns true

        assertEquals(1, useCase.recycleEmptySessions("/workspace", keepId = "keep"))

        coVerify(exactly = 1) { recoveryRepository.deleteSession("empty", "session_deleted") }
        coVerify(exactly = 1) { sessionDao.delete("empty") }
        for (id in listOf("pending", "held", "history", "keep")) {
            coVerify(exactly = 0) { recoveryRepository.deleteSession(id, any()) }
            coVerify(exactly = 0) { sessionDao.delete(id) }
        }
    }

    @Test
    fun entityDomainRoundtripAndUseCaseUpsertKeepExecutionGenerationAndHold() = runTest {
        val original = session("existing").copy(executionGeneration = 98, autoDispatchBlockedReason = "plan_rejected")
        val updated = ChatSessionEntity.fromDomain(original.toDomain().copy(title = "Renamed"))

        useCase.upsertSession(updated)

        assertEquals(98L, updated.executionGeneration)
        assertEquals("plan_rejected", updated.autoDispatchBlockedReason)
        coVerify(exactly = 1) { sessionDao.upsert(updated) }
    }

    @Test
    fun newRootAndChildSessionsStartWithIndependentGenerationAndNoInheritedHold() {
        val parent = session("root").copy(executionGeneration = 98, autoDispatchBlockedReason = "plan_rejected")
        val root = useCase.newSessionEntity("/workspace")
        val child = useCase.newSubSessionEntity("Child", parent.id, parent, "test-agent")

        assertEquals(0L, root.executionGeneration)
        assertEquals(0L, child.executionGeneration)
        assertEquals(null, root.autoDispatchBlockedReason)
        assertEquals(null, child.autoDispatchBlockedReason)
    }

    private fun session(id: String, parentId: String? = null) =
        ChatSessionEntity(id, id, 1, 1, workspacePath = "/workspace", parentId = parentId)
}
