package com.aicode.feature.agent.domain.recovery

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.RecoveryDaoFixture
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecoveryNotificationCoordinatorTest {
    private val ledger = RecoveryDaoFixture()
    private val recovery = RecoveryRepository(ledger.dao)
    private val notifications = mockk<AgentNotificationCenter>()
    private val sessions = mockk<ChatSessionDao>()
    private val identities = mockk<ProjectMemoryIdentityResolver>()
    private val journal = mockk<ProjectMemoryJournal>()
    private val context = mockk<Context>()
    private val identity = ProjectMemoryIdentity("REMOTE_SSH", "server", 22, "user", "/workspace")
    private val pending = PendingNotification(AgentNotificationKind.SUBAGENT, "child", "Task",
        NotificationOutcome.STOPPED, detail = "interrupted", seq = 7, eventId = "event")
    private val coordinator = RecoveryNotificationCoordinator(notifications, recovery, sessions, identities, journal, context)

    @Before
    fun setUp() {
        coEvery { notifications.initialize() } returns Unit
        coEvery { notifications.pendingRecipientIds() } returns listOf("session")
        coEvery { notifications.pendingForConfirmation("session") } returns listOf(pending)
        coEvery { sessions.getById("session") } returns ChatSessionEntity("session", "Chat", 1, 1, workspacePath = "/workspace")
        every { journal.sessionIdentity("session") } returns identity
        every { context.getString(R.string.session_recovery_notification_request) } returns "Handle pending notifications"
    }

    @Test
    fun coldStartCreatesConfirmationCardUsingSavedEnvironmentWithoutStartingRunOrConnecting() = runTest {
        coordinator.prepareConfirmationRequests()
        val request = recovery.request("notification-recovery-7")!!
        assertEquals(RecoveryStatus.AWAITING_CONFIRMATION, request.status)
        assertEquals(identity, Json.decodeFromString<ProjectMemoryIdentity>(request.environment))
        assertNull(request.activeRunId)
        assertTrue(ledger.runs.isEmpty())
        assertEquals(listOf(7L), recovery.payload(request.id)!!.notificationSeqs)
        coVerify(exactly = 0) { identities.capture(any(), any(), any()) }
        coVerify(exactly = 0) { notifications.confirmPending(any(), any()) }
        coVerify(exactly = 0) { notifications.ack(any(), any()) }
    }

    @Test
    fun repeatedPreparationKeepsStableCardAndDoesNotReviveAbandonedRequest() = runTest {
        coordinator.prepareConfirmationRequests()
        assertTrue(recovery.abandonPending("notification-recovery-7", "session"))
        coordinator.prepareConfirmationRequests()
        assertEquals(1, ledger.requests.size)
        assertEquals(RecoveryStatus.ABANDONED, recovery.request("notification-recovery-7")!!.status)
        assertTrue(ledger.runs.isEmpty())
    }

    @Test
    fun interruptedRuntimeNotificationRequestIsReusedAcrossColdStart() = runTest {
        val oldRequest = recovery.enqueue("session", RecoveryRequestPayload("Notification batch", "/workspace",
            origin = RecoveryOrigin.NOTIFICATION, notificationSeqs = listOf(7, 8)), "saved environment")
        val oldRun = recovery.claim(oldRequest.id, "saved environment")!!
        assertTrue(recovery.finish(oldRun.id, RecoveryStatus.INTERRUPTED))
        coordinator.prepareConfirmationRequests()
        assertEquals(1, ledger.requests.size)
        assertEquals(oldRequest.id, recovery.notificationRequest("session", 7)!!.id)
        assertNull(recovery.request("notification-recovery-7"))
        assertEquals(1, ledger.runs.size)
    }

    @Test
    fun deletedRecipientDoesNotCreateARecoveryRequest() = runTest {
        coEvery { sessions.getById("session") } returns null
        coordinator.prepareConfirmationRequests()
        assertTrue(ledger.requests.isEmpty())
        assertTrue(ledger.runs.isEmpty())
    }
}
