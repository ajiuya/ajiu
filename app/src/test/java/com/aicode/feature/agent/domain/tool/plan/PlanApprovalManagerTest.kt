package com.aicode.feature.agent.domain.tool.plan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanApprovalManagerTest {

    private val manager = PlanApprovalManager()

    private fun request(id: String, sessionId: String) = PendingPlanApproval(
        id = id,
        sessionId = sessionId,
        title = "Implementation plan",
        plan = "# Plan\n\n- Implement the feature"
    )

    @Test
    fun initialState_hasNoPendingPlanOrAwaitingSessions() = runTest {
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        assertNull(manager.pendingForSession("session-1"))
        assertFalse(manager.resolve("unknown-id", PlanApprovalDecision(true)))
    }

    @Test
    fun simultaneousSessions_resolveOnlyExactRequestId() = runTest {
        val first = request("request-1", "session-1")
        val second = request("request-2", "session-2")
        val firstResult = async { manager.awaitApproval(first) }
        val secondResult = async { manager.awaitApproval(second) }
        runCurrent()

        assertEquals(first, manager.pendingPlan.value)
        assertEquals(setOf("session-1", "session-2"), manager.awaitingSessionIds.value)
        assertEquals(first, manager.pendingForSession("session-1"))
        assertEquals(second, manager.pendingForSession("session-2"))
        assertNull(manager.pendingForSession("unknown-session"))

        assertFalse(manager.resolve(first.sessionId, PlanApprovalDecision(true)))
        assertFalse(manager.resolve("unknown-id", PlanApprovalDecision(true)))
        runCurrent()
        assertFalse(firstResult.isCompleted)
        assertFalse(secondResult.isCompleted)
        assertEquals(first, manager.pendingPlan.value)
        assertEquals(setOf("session-1", "session-2"), manager.awaitingSessionIds.value)

        val rejection = PlanApprovalDecision(false, "Add a rollback step")
        assertTrue(manager.resolve(second.id, rejection))
        assertEquals(rejection, secondResult.await())
        assertFalse(firstResult.isCompleted)
        assertEquals(first, manager.pendingPlan.value)
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)
        assertNull(manager.pendingForSession("session-2"))

        val approval = PlanApprovalDecision(true)
        assertTrue(manager.resolve(first.id, approval))
        assertEquals(approval, firstResult.await())
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun resolvingFirstRequest_immediatelyPublishesNextInInsertionOrder() = runTest {
        val first = request("request-1", "session-1")
        val second = request("request-2", "session-2")
        val third = request("request-3", "session-3")
        val firstResult = async { manager.awaitApproval(first) }
        val secondResult = async { manager.awaitApproval(second) }
        val thirdResult = async { manager.awaitApproval(third) }
        runCurrent()

        assertTrue(manager.resolve(first.id, PlanApprovalDecision(true)))
        assertEquals(second, manager.pendingPlan.value)
        assertEquals(setOf("session-2", "session-3"), manager.awaitingSessionIds.value)
        assertEquals(PlanApprovalDecision(true), firstResult.await())

        assertTrue(manager.resolve(second.id, PlanApprovalDecision(true)))
        assertEquals(third, manager.pendingPlan.value)
        assertEquals(setOf("session-3"), manager.awaitingSessionIds.value)
        assertEquals(PlanApprovalDecision(true), secondResult.await())

        assertTrue(manager.resolve(third.id, PlanApprovalDecision(true)))
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        assertEquals(PlanApprovalDecision(true), thirdResult.await())
    }

    @Test
    fun duplicateResolve_returnsFalseAndKeepsFirstRejection() = runTest {
        val request = request("request-1", "session-1")
        val result = async { manager.awaitApproval(request) }
        runCurrent()

        val rejection = PlanApprovalDecision(false, "Revise the tests")
        assertTrue(manager.resolve(request.id, rejection))
        assertFalse(manager.resolve(request.id, rejection))
        assertFalse(manager.resolve(request.id, PlanApprovalDecision(true)))
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        assertNull(manager.pendingForSession(request.sessionId))

        assertEquals(rejection, result.await())
        assertFalse(manager.resolve(request.id, PlanApprovalDecision(true)))
    }

    @Test
    fun cancellingFirstRequest_cleansItUpAndPromotesNextSession() = runTest {
        val first = request("request-1", "session-1")
        val second = request("request-2", "session-2")
        val firstResult = async { manager.awaitApproval(first) }
        val secondResult = async { manager.awaitApproval(second) }
        runCurrent()

        firstResult.cancelAndJoin()

        assertTrue(firstResult.isCancelled)
        assertEquals(second, manager.pendingPlan.value)
        assertEquals(setOf("session-2"), manager.awaitingSessionIds.value)
        assertNull(manager.pendingForSession(first.sessionId))
        assertFalse(manager.resolve(first.id, PlanApprovalDecision(true)))
        assertFalse(secondResult.isCompleted)

        assertTrue(manager.resolve(second.id, PlanApprovalDecision(true)))
        assertEquals(PlanApprovalDecision(true), secondResult.await())
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun cancellingNonFirstRequest_preservesFirstSession() = runTest {
        val first = request("request-1", "session-1")
        val second = request("request-2", "session-2")
        val firstResult = async { manager.awaitApproval(first) }
        val secondResult = async { manager.awaitApproval(second) }
        runCurrent()

        secondResult.cancelAndJoin()

        assertEquals(first, manager.pendingPlan.value)
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)
        assertEquals(first, manager.pendingForSession(first.sessionId))
        assertNull(manager.pendingForSession(second.sessionId))
        assertFalse(firstResult.isCompleted)
        assertFalse(manager.resolve(second.id, PlanApprovalDecision(true)))

        firstResult.cancelAndJoin()
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun cancelledRequest_oldIdDoesNotResolveSameSessionReplacement() = runTest {
        val oldRequest = request("old-id", "session-1")
        val oldResult = async { manager.awaitApproval(oldRequest) }
        runCurrent()
        oldResult.cancelAndJoin()
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())

        val replacement = request("new-id", "session-1")
        val replacementResult = async { manager.awaitApproval(replacement) }
        runCurrent()

        assertFalse(manager.resolve(oldRequest.id, PlanApprovalDecision(true)))
        runCurrent()
        assertFalse(replacementResult.isCompleted)
        assertEquals(replacement, manager.pendingPlan.value)
        assertEquals(replacement, manager.pendingForSession("session-1"))
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)

        assertTrue(manager.resolve(replacement.id, PlanApprovalDecision(true)))
        assertEquals(PlanApprovalDecision(true), replacementResult.await())
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun resolvedRequest_oldIdDoesNotRejectSameSessionReplacement() = runTest {
        val oldRequest = request("old-id", "session-1")
        val oldResult = async { manager.awaitApproval(oldRequest) }
        runCurrent()
        assertTrue(manager.resolve(oldRequest.id, PlanApprovalDecision(false)))
        assertEquals(PlanApprovalDecision(false), oldResult.await())

        val replacement = request("new-id", "session-1")
        val replacementResult = async { manager.awaitApproval(replacement) }
        runCurrent()

        assertFalse(manager.resolve(oldRequest.id, PlanApprovalDecision(false, "Stale feedback")))
        runCurrent()
        assertFalse(replacementResult.isCompleted)
        assertEquals(replacement, manager.pendingPlan.value)
        assertEquals(replacement, manager.pendingForSession("session-1"))

        assertTrue(manager.resolve(replacement.id, PlanApprovalDecision(true)))
        assertEquals(PlanApprovalDecision(true), replacementResult.await())
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun sameSessionRequests_remainAwaitingUntilLastRequestFinishes() = runTest {
        val first = request("request-1", "session-1")
        val second = request("request-2", "session-1")
        val firstResult = async { manager.awaitApproval(first) }
        val secondResult = async { manager.awaitApproval(second) }
        runCurrent()

        assertEquals(first, manager.pendingForSession("session-1"))
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)
        assertTrue(manager.resolve(first.id, PlanApprovalDecision(true)))
        assertEquals(second, manager.pendingPlan.value)
        assertEquals(second, manager.pendingForSession("session-1"))
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)
        assertEquals(PlanApprovalDecision(true), firstResult.await())

        secondResult.cancelAndJoin()
        assertNull(manager.pendingPlan.value)
        assertNull(manager.pendingForSession("session-1"))
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun duplicatePendingId_isRejectedWithoutReplacingOriginalRequest() = runTest {
        val original = request("request-1", "session-1")
        val originalResult = async { manager.awaitApproval(original) }
        runCurrent()

        val failure = runCatching {
            manager.awaitApproval(request(original.id, "session-2"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(original, manager.pendingPlan.value)
        assertEquals(original, manager.pendingForSession("session-1"))
        assertNull(manager.pendingForSession("session-2"))
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)
        assertFalse(originalResult.isCompleted)

        assertTrue(manager.resolve(original.id, PlanApprovalDecision(true)))
        assertEquals(PlanApprovalDecision(true), originalResult.await())
        assertNull(manager.pendingPlan.value)
    }
}
