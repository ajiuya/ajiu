package com.aicode.feature.agent.domain.recovery

import android.content.Context
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class RecoveryNotificationCoordinator @Inject constructor(
    private val notifications: AgentNotificationCenter,
    private val recovery: RecoveryRepository,
    private val sessions: ChatSessionDao,
    private val identities: ProjectMemoryIdentityResolver,
    private val projectJournal: ProjectMemoryJournal,
    @param:ApplicationContext private val context: Context
) {
    suspend fun prepareConfirmationRequests(sessionId: String? = null) {
        notifications.initialize()
        val recipients = sessionId?.let(::listOf) ?: notifications.pendingRecipientIds()
        for (recipient in recipients) {
            val pending = notifications.pendingForConfirmation(recipient)
            if (pending.isEmpty()) continue
            val session = sessions.getById(recipient) ?: continue
            val identity = projectJournal.sessionIdentity(recipient) ?: try {
                identities.capture(session.workspacePath, headless = true)?.identity
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (error: Exception) {
                FileLogger.w("RecoveryNotifications", "Notification recovery environment is unavailable", error)
                null
            } ?: continue
            for (item in pending) {
                val id = "notification-recovery-${item.seq}"
                if (recovery.request(id) != null || recovery.notificationRequest(recipient, item.seq) != null) continue
                recovery.enqueue(recipient, RecoveryRequestPayload(
                    request = context.getString(R.string.session_recovery_notification_request),
                    projectRoot = session.workspacePath,
                    modelRequest = AgentNotificationFormatter.buildMessage(listOf(item)),
                    origin = RecoveryOrigin.NOTIFICATION,
                    notificationSeqs = listOf(item.seq)
                ), Json.encodeToString(identity), requestId = id, inputMessageId = "input-$id")
                recovery.awaitConfirmation(id)
            }
        }
    }
}
