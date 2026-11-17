package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.data.local.dao.AgentNotificationDao
import com.aicode.feature.agent.data.local.entity.AgentNotificationEntity

internal class FakeAgentNotificationDao : AgentNotificationDao {
    val generations = linkedMapOf("s1" to 0L, "s2" to 0L, "parent" to 0L, "child" to 0L)
    val rows = mutableListOf<AgentNotificationEntity>()
    var insertFailure: RuntimeException? = null
    private var nextSeq = 0L

    override suspend fun sessionGeneration(sessionId: String) = generations[sessionId]

    override suspend fun insertIgnore(notification: AgentNotificationEntity): Long {
        insertFailure?.let { throw it }
        if (rows.any { it.eventKey == notification.eventKey }) return -1
        val stored = notification.copy(seq = ++nextSeq)
        rows += stored
        return stored.seq
    }

    override suspend fun getByEventKey(eventKey: String) = rows.find { it.eventKey == eventKey }

    private fun AgentNotificationEntity.currentPending() =
        deliveredAt == null && retiredAt == null && generation == generations[sessionId]

    override suspend fun pending(sessionId: String, requiresConfirmation: Boolean) =
        rows.filter { it.sessionId == sessionId && it.currentPending() && it.requiresConfirmation == requiresConfirmation }
            .sortedBy { it.seq }

    override suspend fun pendingCount(sessionId: String) = rows.count { it.sessionId == sessionId && it.currentPending() }

    override suspend fun pendingRecipientIds() = rows.filter { it.currentPending() }.map { it.sessionId }.distinct().sorted()

    override suspend fun markDelivered(sessionId: String, seqs: List<Long>, deliveredAt: Long): Int {
        var changed = 0
        rows.replaceAll {
            if (it.sessionId == sessionId && it.seq in seqs && it.currentPending()) {
                changed++
                it.copy(deliveredAt = deliveredAt)
            } else it
        }
        return changed
    }

    override suspend fun requirePendingConfirmation(): Int {
        var changed = 0
        rows.replaceAll {
            if (it.deliveredAt == null && it.retiredAt == null) {
                changed++
                it.copy(requiresConfirmation = true)
            } else it
        }
        return changed
    }

    override suspend fun confirmPending(sessionId: String, seqs: List<Long>): Int {
        var changed = 0
        rows.replaceAll {
            if (it.sessionId == sessionId && it.seq in seqs && it.currentPending() && it.requiresConfirmation) {
                changed++
                it.copy(requiresConfirmation = false)
            } else it
        }
        return changed
    }

    override suspend fun retireSession(sessionId: String, retiredAt: Long): Int =
        retireWhere(retiredAt) { it.sessionId == sessionId }

    override suspend fun retireSelected(sessionId: String, seqs: List<Long>, retiredAt: Long): Int =
        retireWhere(retiredAt) { it.sessionId == sessionId && it.seq in seqs && it.generation == generations[sessionId] }

    override suspend fun retireAll(retiredAt: Long): Int = retireWhere(retiredAt) { true }

    private fun retireWhere(retiredAt: Long, matches: (AgentNotificationEntity) -> Boolean): Int {
        var changed = 0
        rows.replaceAll {
            if (matches(it) && it.deliveredAt == null && it.retiredAt == null) {
                changed++
                it.copy(retiredAt = retiredAt)
            } else it
        }
        return changed
    }
}
