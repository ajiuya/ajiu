package com.aicode.feature.agent.presentation.notification

import android.content.Context
import io.mockk.mockk
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.SessionPermissionGrantRepository
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentApprovalActionReceiverTest {
    private val grants = SessionPermissionGrantRepository(mockk<Context>())
    private val permissions = ToolPermissionManager(grants)
    private fun request(id: String, session: String = "session-A", grant: String? = "command-session-v1:source-A") =
        PendingToolPermission(id, "Bash", "Review", "command", "details", "args", sessionGrant = grant, sessionId = session)
    private fun send(id: String, choice: String, kind: String = "PERMISSION", action: String = AgentApprovalActionReceiver.ACTION) {
        resolveApprovalAction(action, kind, choice, id, permissions)
    }

    @Test fun sessionNotificationChoiceUsesOnlyItsPendingRequestAndSession() = runTest {
        val first = request("first")
        val other = request("other", "session-B")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { permissions.awaitApproval(first) }
        val unrelated = async(start = CoroutineStart.UNDISPATCHED) { permissions.awaitApproval(other) }
        send(first.id, PermissionChoice.ALWAYS_SESSION.name)
        assertEquals(PermissionChoice.ALWAYS_SESSION, pending.await())
        assertTrue(grants.isAllowed(first.sessionId, requireNotNull(first.sessionGrant)))
        assertFalse(grants.isAllowed(other.sessionId, requireNotNull(other.sessionGrant)))
        assertFalse(unrelated.isCompleted)
        send(other.id, PermissionChoice.REJECT.name)
        assertEquals(PermissionChoice.REJECT, unrelated.await())
    }

    @Test fun onceRejectAndHostWriteChoicesDoNotManufactureStoredAuthority() = runTest {
        for (choice in PermissionChoice.entries) {
            val request = request(choice.name, grant = ToolPermissionManager.HOST_WRITE_GRANT_PREFIX + "reviewed-source")
            val pending = async(start = CoroutineStart.UNDISPATCHED) { permissions.awaitApproval(request) }
            send(request.id, choice.name)
            assertEquals(choice, pending.await())
            assertFalse(grants.isAllowed(request.sessionId, requireNotNull(request.sessionGrant)))
        }
    }

    @Test fun staleNotificationCannotAuthorizeReplacementRequest() = runTest {
        val original = request("old")
        val pending = async(start = CoroutineStart.UNDISPATCHED) { permissions.awaitApproval(original) }
        pending.cancelAndJoin()
        val replacement = request("replacement")
        val current = async(start = CoroutineStart.UNDISPATCHED) { permissions.awaitApproval(replacement) }
        send(original.id, PermissionChoice.ALWAYS_SESSION.name)
        assertFalse(current.isCompleted)
        assertFalse(grants.isAllowed(replacement.sessionId, requireNotNull(replacement.sessionGrant)))
        send(replacement.id, PermissionChoice.ONCE.name)
        assertEquals(PermissionChoice.ONCE, current.await())
    }

    @Test fun invalidKindsChoicesAndUnavailableScopeNeverApprove() = runTest {
        for (request in listOf(request("missing", grant = null), request("blank", session = " "),
            request("incomplete", grant = ToolPermissionManager.HOST_WRITE_GRANT_PREFIX))) {
            assertFalse(request.canAllowSession)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { permissions.awaitApproval(request) }
            send(request.id, PermissionChoice.ALWAYS_SESSION.name)
            send(request.id, PermissionChoice.ONCE.name, kind = "QUESTION")
            send(request.id, PermissionChoice.ONCE.name, kind = "PLAN")
            send(request.id, "ALLOW_ALL")
            send(request.id, PermissionChoice.ONCE.name, action = "unrelated")
            assertFalse(pending.isCompleted)
            send(request.id, PermissionChoice.REJECT.name)
            assertEquals(PermissionChoice.REJECT, pending.await())
        }
    }
}
