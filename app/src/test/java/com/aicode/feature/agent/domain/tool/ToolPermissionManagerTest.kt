package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import io.mockk.coVerify
import io.mockk.mockk
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
class ToolPermissionManagerTest {

    private val grants = mockk<SessionPermissionGrantRepository>(relaxed = true)
    private val manager = ToolPermissionManager(grants)

    private fun request(id: String, sessionId: String, grant: String? = null) = PendingToolPermission(
        id = id,
        toolName = "Bash",
        title = "确认执行工具",
        summary = "",
        details = "",
        argsPreview = "",
        sessionId = sessionId,
        sessionGrant = grant
    )

    @Test
    fun simultaneousSessions_resolveByRequestIdAndKeepInsertionOrder() = runTest {
        val first = request("id1", "s1", "root:sudo id")
        val second = request("id2", "s2", "tool:Shizuku")
        val d1 = async { manager.awaitApproval(first) }
        val d2 = async { manager.awaitApproval(second) }
        runCurrent()

        assertEquals(setOf("s1", "s2"), manager.awaitingSessionIds.value)
        assertEquals(first, manager.pendingRequest.value)
        assertEquals(second, manager.pendingForSession("s2"))

        manager.resolve("s1", PermissionChoice.REJECT)
        runCurrent()
        assertFalse(d1.isCompleted)
        assertFalse(d2.isCompleted)

        manager.resolve(second.id, PermissionChoice.ALWAYS_SESSION)
        assertEquals(PermissionChoice.ALWAYS_SESSION, d2.await())
        assertFalse(d1.isCompleted)
        assertEquals(setOf("s1"), manager.awaitingSessionIds.value)
        assertEquals(first, manager.pendingRequest.value)
        assertNull(manager.pendingForSession("s2"))
        coVerify(exactly = 1) { grants.allow("s2", "tool:Shizuku") }
        coVerify(exactly = 0) { grants.allow("s1", any()) }

        manager.resolve(first.id, PermissionChoice.REJECT)
        assertEquals(PermissionChoice.REJECT, d1.await())
        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        coVerify(exactly = 1) { grants.allow(any(), any()) }
    }

    @Test
    fun resolvingFirstRequest_displaysNextPendingRequest() = runTest {
        val first = request("id1", "s1")
        val second = request("id2", "s2")
        val d1 = async { manager.awaitApproval(first) }
        val d2 = async { manager.awaitApproval(second) }
        runCurrent()

        manager.resolve(first.id, PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, d1.await())
        assertEquals(second, manager.pendingRequest.value)
        assertEquals(setOf("s2"), manager.awaitingSessionIds.value)

        manager.resolve(second.id, PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, d2.await())
        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun cancelledRequest_oldIdDoesNotApproveSameSessionReplacement() = runTest {
        val oldRequest = request("old-id", "s1", "root:sudo id")
        val old = async { manager.awaitApproval(oldRequest) }
        runCurrent()
        assertEquals(oldRequest, manager.pendingRequest.value)

        old.cancelAndJoin()
        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())

        val replacementRequest = request("new-id", "s1", "root:sudo id")
        val replacement = async { manager.awaitApproval(replacementRequest) }
        runCurrent()

        manager.resolve(oldRequest.id, PermissionChoice.ALWAYS_SESSION)
        runCurrent()

        assertFalse(replacement.isCompleted)
        assertEquals(replacementRequest, manager.pendingRequest.value)
        assertEquals(replacementRequest, manager.pendingForSession("s1"))
        assertEquals(setOf("s1"), manager.awaitingSessionIds.value)
        coVerify(exactly = 0) { grants.allow(any(), any()) }

        manager.resolve(replacementRequest.id, PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, replacement.await())
        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun rejectionAndSingleApproval_doNotSaveSessionGrant() = runTest {
        for (choice in listOf(PermissionChoice.REJECT, PermissionChoice.ONCE)) {
            val request = request(choice.name, "s1", "tool:Shizuku")
            val pending = async { manager.awaitApproval(request) }
            runCurrent()

            manager.resolve(request.id, choice)

            assertEquals(choice, pending.await())
            assertNull(manager.pendingRequest.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
        }
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun alwaysSessionChoice_savesOnlyRequestedGrantAndSession() = runTest {
        val sessionGrants = listOf("tool:Shizuku", "root:sudo id", "elevate:rm -rf \$(pwd)/build")
        for ((index, grant) in sessionGrants.withIndex()) {
            val sessionId = "session-$index"
            val request = request("id-$index", sessionId, grant)
            val pending = async { manager.awaitApproval(request) }
            runCurrent()

            manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)

            assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
            coVerify(exactly = 1) { grants.allow(sessionId, grant) }
            assertNull(manager.pendingRequest.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
        }
        coVerify(exactly = sessionGrants.size) { grants.allow(any(), any()) }
    }

    @Test
    fun alwaysSessionChoice_withoutGrantDoesNotSave() = runTest {
        val request = request("no-grant", "s1")
        val pending = async { manager.awaitApproval(request) }
        runCurrent()

        manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)

        assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
        coVerify(exactly = 0) { grants.allow(any(), any()) }
        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun alwaysSessionChoice_withBlankSessionDoesNotSaveOrMarkSessionAwaiting() = runTest {
        for (sessionId in listOf("", "  ")) {
            val request = request("blank-${sessionId.length}", sessionId, "tool:Shizuku")
            val pending = async { manager.awaitApproval(request) }
            runCurrent()

            assertEquals(request, manager.pendingRequest.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
            manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)

            assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
            assertNull(manager.pendingRequest.value)
        }
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_alwaysChoiceWaitsForWorkflowValidationBeforeSavingWideGrant() = runTest {
        val grant = "host-write-session-v1:root-source-epoch"
        val request = request("host-write-id", "session", grant).copy(toolName = "Root")
        val pending = async { manager.awaitApproval(request) }
        runCurrent()
        assertEquals(grant, manager.pendingRequest.value!!.sessionGrant)

        manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)

        assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
        assertNull(manager.pendingRequest.value)
        coVerify(exactly = 0) { grants.allow(any(), any()) }

        manager.confirmHostWriteSessionGrant("session", grant)
        coVerify(exactly = 1) { grants.allow("session", grant) }
        coVerify(exactly = 1) { grants.allow(any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_onceAndRejectNeverSaveWideGrant() = runTest {
        for (choice in listOf(PermissionChoice.ONCE, PermissionChoice.REJECT)) {
            val request = request("host-${choice.name}", "session", "host-write-session-v1:scope")
            val pending = async { manager.awaitApproval(request) }
            runCurrent()
            manager.resolve(request.id, choice)
            assertEquals(choice, pending.await())
            assertNull(manager.pendingRequest.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
        }
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_completedAlwaysThenRevocationCannotTriggerLateManagerSave() = runTest {
        val request = request("queued-choice", "session", "host-write-session-v1:old-epoch").copy(toolName = "Root")
        val pending = async { manager.awaitApproval(request) }
        runCurrent()
        manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
        // The completed choice cannot be replaced, but it must not itself persist an old scope.
        manager.rejectForTool("Root")
        assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
        manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun hostWriteSessionRegression_blankScopeOrSessionCannotBeSavedByEitherManagerPath() = runTest {
        for ((index, grant) in listOf("", "  ", "host-write-session-v1:", "host-write-session-v1:  ").withIndex()) {
            val request = request("blank-scope-$index", "session", grant)
            val pending = async { manager.awaitApproval(request) }
            runCurrent()
            manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
            assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
            manager.confirmHostWriteSessionGrant("session", grant)
        }
        for ((index, session) in listOf("", "  ").withIndex()) {
            val request = request("blank-session-$index", session, "host-write-session-v1:scope")
            val pending = async { manager.awaitApproval(request) }
            runCurrent()
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
            manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
            assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
            manager.confirmHostWriteSessionGrant(session, request.sessionGrant!!)
        }
        manager.confirmHostWriteSessionGrant("session", "root:sudo id")
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun disablingRootRejectsOnlyPendingHostRootRequests() = runTest {
        val rootRequest = request("root-id", "s1").copy(toolName = "Root")
        val otherRequest = request("bash-id", "s2")
        val root = async { manager.awaitApproval(rootRequest) }
        val other = async { manager.awaitApproval(otherRequest) }
        runCurrent()

        manager.rejectForTool("Root")
        assertEquals(PermissionChoice.REJECT, root.await())
        assertFalse(other.isCompleted)
        manager.resolve("root-id", PermissionChoice.ONCE)
        assertEquals(otherRequest, manager.pendingRequest.value)
        manager.resolve("bash-id", PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, other.await())
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }

    @Test
    fun resolveUnknownOrAlreadyResolvedId_doesNotChangePendingRequestOrSaveGrant() = runTest {
        val request = request("id1", "s1", "root:sudo id")
        val pending = async { manager.awaitApproval(request) }
        runCurrent()

        manager.resolve("nonexistent", PermissionChoice.ALWAYS_SESSION)
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(request, manager.pendingRequest.value)
        coVerify(exactly = 0) { grants.allow(any(), any()) }

        manager.resolve(request.id, PermissionChoice.REJECT)
        manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)
        assertEquals(PermissionChoice.REJECT, pending.await())
        manager.resolve(request.id, PermissionChoice.ALWAYS_SESSION)

        assertNull(manager.pendingRequest.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        coVerify(exactly = 0) { grants.allow(any(), any()) }
    }
}
