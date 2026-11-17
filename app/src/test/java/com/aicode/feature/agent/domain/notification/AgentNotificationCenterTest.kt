package com.aicode.feature.agent.domain.notification

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知队列的消费语义：peek 不移除、ack 只移除已确认项、drain 原子取走、多会话互不干扰。
 * 这些性质保证同一条后台通知不会既被工具结果搭车送达、又被本轮结束的兜底路径重复送达。
 */
class AgentNotificationCenterTest {

    private fun task(id: String) = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = id,
        title = "build $id",
        outcome = NotificationOutcome.COMPLETED,
        exitCode = 0
    )

    @Test
    fun peek_doesNotRemove() = runTest {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))

        assertEquals(1, center.peek("s1").size)
        assertEquals(1, center.peek("s1").size)
        assertEquals(1, center.pendingCount("s1"))
    }

    @Test
    fun ack_removesOnlyAckedItems() = runTest {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        val peeked = center.peek("s1")
        // peek 之后新到达的事件不应被这次 ack 误删
        center.enqueue("s1", task("term-2"))

        center.ack("s1", peeked.map { it.seq })

        val remaining = center.peek("s1")
        assertEquals(1, remaining.size)
        assertEquals("term-2", remaining.first().sourceId)
    }

    @Test
    fun drain_takesAllAndClears() = runTest {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s1", task("term-2"))

        val drained = center.drain("s1")

        assertEquals(listOf("term-1", "term-2"), drained.map { it.sourceId })
        assertEquals(0, center.pendingCount("s1"))
        assertTrue(center.drain("s1").isEmpty())
    }

    @Test
    fun sessionsAreIsolated() = runTest {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s2", task("term-2"))

        center.clear("s1")

        assertEquals(0, center.pendingCount("s1"))
        assertEquals(1, center.pendingCount("s2"))
    }

    @Test
    fun clearAll_removesEverySession() = runTest {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s2", task("term-2"))

        center.clearAll()

        assertEquals(0, center.pendingCount("s1"))
        assertEquals(0, center.pendingCount("s2"))
    }

    @Test
    fun stableEventRetryPreservesSequenceAndFirstPayloadAfterAck() = runTest {
        val dao = FakeAgentNotificationDao()
        val center = AgentNotificationCenter(dao)
        val item = task("term").copy(eventId = "stable")
        val seq = center.enqueue("s1", item)
        assertEquals(seq, center.enqueue("s1", item.copy(title = "retry cannot replace payload")))
        assertEquals(item.copy(seq = seq), center.peek("s1").single())
        center.ack("s1", listOf(seq))
        assertEquals(seq, center.enqueue("s1", item))
        assertTrue(center.peek("s1").isEmpty())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun reopenedCenterRestoresUnackedRowsBehindExplicitConfirmation() = runTest {
        val dao = FakeAgentNotificationDao()
        val first = AgentNotificationCenter(dao)
        val item = task("term").copy(tailOutput = "public result", eventId = "survives-restart")
        val seq = first.enqueue("s1", item)
        val reopened = AgentNotificationCenter(dao)
        assertTrue(reopened.peek("s1").isEmpty())
        assertEquals(listOf(item.copy(seq = seq)), reopened.pendingForConfirmation("s1"))
        assertEquals(listOf("s1"), reopened.pendingRecipientIds())
        assertEquals(seq, reopened.enqueue("s1", item))
        assertTrue(reopened.peek("s1").isEmpty())
        assertEquals(0, reopened.confirmPending("s2", listOf(seq)))
        assertEquals(1, reopened.confirmPending("s1", listOf(seq)))
        assertEquals(listOf(item.copy(seq = seq)), reopened.peek("s1"))
        reopened.ack("s1", listOf(seq))
        val secondRestart = AgentNotificationCenter(dao)
        assertTrue(secondRestart.pendingRecipientIds().isEmpty())
    }

    @Test
    fun confirmationAndDiscardApplyOnlyToSelectedSequences() = runTest {
        val center = AgentNotificationCenter()
        val old = center.enqueue("s1", task("old"), requiresConfirmation = true)
        val newer = center.enqueue("s1", task("newer"), requiresConfirmation = true)
        val live = center.enqueue("s1", task("live"))
        center.confirmPending("s1", listOf(old))
        assertEquals(listOf(old, live), center.peek("s1").map { it.seq })
        center.discard("s1", listOf(old))
        assertEquals(listOf(live), center.peek("s1").map { it.seq })
        assertEquals(listOf(newer), center.pendingForConfirmation("s1").map { it.seq })
    }

    @Test
    fun generationChangeFencesDelayedProducerAckAndConfirmation() = runTest {
        val dao = FakeAgentNotificationDao()
        val center = AgentNotificationCenter(dao)
        val item = task("term").copy(eventId = "event")
        val old = center.enqueue("s1", item, generation = 0)
        dao.generations["s1"] = 1
        assertEquals(0L, center.enqueue("s1", item.copy(eventId = "late"), generation = 0))
        assertTrue(center.peek("s1").isEmpty())
        val current = center.enqueue("s1", item, generation = 1)
        assertTrue(current > old)
        center.ack("s1", listOf(old))
        center.discard("s1", listOf(old))
        assertEquals(listOf(current), center.peek("s1").map { it.seq })
        assertEquals(null, dao.rows.single { it.seq == old }.deliveredAt)
        assertEquals(null, dao.rows.single { it.seq == old }.retiredAt)
        dao.generations.remove("s1")
        assertEquals(0L, center.enqueue("s1", task("deleted")))
        assertTrue(center.pendingRecipientIds().isEmpty())
    }

    @Test
    fun clearingPersistsRetirementAndNeverReusesSequencesOrRequeuesSameEvent() = runTest {
        val dao = FakeAgentNotificationDao()
        val center = AgentNotificationCenter(dao)
        val first = task("first")
        val old = center.enqueue("s1", first)
        center.clear("s1")
        assertEquals(old, center.enqueue("s1", first))
        val next = center.enqueue("s1", task("second"))
        assertTrue(next > old)
        val reopened = AgentNotificationCenter(dao)
        assertEquals(listOf(next), reopened.pendingForConfirmation("s1").map { it.seq })
        reopened.clearAll()
        assertTrue(AgentNotificationCenter(dao).pendingRecipientIds().isEmpty())
        assertTrue(dao.rows.all { it.retiredAt != null })
    }

    @Test
    fun storageFailurePropagatesWithoutMakingNotificationDeliverable() = runTest {
        val dao = FakeAgentNotificationDao()
        val center = AgentNotificationCenter(dao)
        dao.insertFailure = IllegalStateException("disk full")
        try {
            center.enqueue("s1", task("failed"))
            org.junit.Assert.fail("Storage failures must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("disk full", error.message)
        }
        assertTrue(center.peek("s1").isEmpty())
        assertTrue(dao.rows.isEmpty())
    }
}
