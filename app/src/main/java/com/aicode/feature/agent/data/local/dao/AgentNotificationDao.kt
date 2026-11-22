package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aicode.feature.agent.data.local.entity.AgentNotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentNotificationDao {
    @Query("SELECT executionGeneration FROM chat_sessions WHERE id = :sessionId")
    suspend fun sessionGeneration(sessionId: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(notification: AgentNotificationEntity): Long

    @Query("SELECT * FROM agent_notification_outbox WHERE eventKey = :eventKey LIMIT 1")
    suspend fun getByEventKey(eventKey: String): AgentNotificationEntity?

    @Query("""
        SELECT n.* FROM agent_notification_outbox n
        JOIN chat_sessions s ON s.id = n.sessionId AND s.executionGeneration = n.generation
        WHERE n.sessionId = :sessionId AND n.deliveredAt IS NULL AND n.retiredAt IS NULL
          AND n.requiresConfirmation = :requiresConfirmation
        ORDER BY n.seq
    """)
    suspend fun pending(sessionId: String, requiresConfirmation: Boolean): List<AgentNotificationEntity>

    @Query("""
        SELECT n.* FROM agent_notification_outbox n
        JOIN chat_sessions s ON s.id = n.sessionId AND s.executionGeneration = n.generation
        WHERE n.sessionId = :sessionId AND n.retiredAt IS NULL
        ORDER BY n.seq
    """)
    fun observeTimeline(sessionId: String): Flow<List<AgentNotificationEntity>>

    @Query("""
        SELECT COUNT(*) FROM agent_notification_outbox n
        JOIN chat_sessions s ON s.id = n.sessionId AND s.executionGeneration = n.generation
        WHERE n.sessionId = :sessionId AND n.deliveredAt IS NULL AND n.retiredAt IS NULL
    """)
    suspend fun pendingCount(sessionId: String): Int

    @Query("""
        UPDATE agent_notification_outbox SET deliveredAt = :deliveredAt
        WHERE sessionId = :sessionId AND seq IN (:seqs)
          AND deliveredAt IS NULL AND retiredAt IS NULL
          AND generation = (SELECT executionGeneration FROM chat_sessions WHERE id = :sessionId)
    """)
    suspend fun markDelivered(sessionId: String, seqs: List<Long>, deliveredAt: Long): Int

    @Query("""
        UPDATE agent_notification_outbox SET retiredAt = :retiredAt
        WHERE sessionId = :sessionId AND deliveredAt IS NULL AND retiredAt IS NULL
    """)
    suspend fun retireSession(sessionId: String, retiredAt: Long): Int

    @Query("""
        UPDATE agent_notification_outbox SET retiredAt = :retiredAt
        WHERE sessionId = :sessionId AND seq IN (:seqs)
          AND deliveredAt IS NULL AND retiredAt IS NULL
          AND generation = (SELECT executionGeneration FROM chat_sessions WHERE id = :sessionId)
    """)
    suspend fun retireSelected(sessionId: String, seqs: List<Long>, retiredAt: Long): Int

    @Query("""
        UPDATE agent_notification_outbox SET retiredAt = :retiredAt
        WHERE deliveredAt IS NULL AND retiredAt IS NULL
    """)
    suspend fun retireAll(retiredAt: Long): Int

    @Query("""
        UPDATE agent_notification_outbox SET requiresConfirmation = 1
        WHERE deliveredAt IS NULL AND retiredAt IS NULL
    """)
    suspend fun requirePendingConfirmation(): Int

    @Query("""
        UPDATE agent_notification_outbox SET requiresConfirmation = 0
        WHERE sessionId = :sessionId AND seq IN (:seqs)
          AND deliveredAt IS NULL AND retiredAt IS NULL AND requiresConfirmation = 1
          AND generation = (SELECT executionGeneration FROM chat_sessions WHERE id = :sessionId)
    """)
    suspend fun confirmPending(sessionId: String, seqs: List<Long>): Int

    @Query("""
        SELECT DISTINCT n.sessionId FROM agent_notification_outbox n
        JOIN chat_sessions s ON s.id = n.sessionId AND s.executionGeneration = n.generation
        WHERE n.deliveredAt IS NULL AND n.retiredAt IS NULL
        ORDER BY n.sessionId
    """)
    suspend fun pendingRecipientIds(): List<String>

    @Transaction
    suspend fun enqueue(notification: AgentNotificationEntity): Long {
        if (sessionGeneration(notification.sessionId) != notification.generation) return 0
        val inserted = insertIgnore(notification)
        if (inserted != -1L) return inserted
        val existing = checkNotNull(getByEventKey(notification.eventKey)) {
            "Ignored notification is missing"
        }
        require(existing.sessionId == notification.sessionId && existing.generation == notification.generation) {
            "Notification event key was reused for a different session or generation"
        }
        return existing.seq
    }

    @Transaction
    suspend fun drain(sessionId: String, deliveredAt: Long): List<AgentNotificationEntity> {
        val rows = pending(sessionId, requiresConfirmation = false)
        if (rows.isNotEmpty()) markDelivered(sessionId, rows.map { it.seq }, deliveredAt)
        return rows
    }
}
