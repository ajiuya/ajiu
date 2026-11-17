package com.aicode.feature.agent.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.subagent.SubAgentInteractionJournal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AgentNotificationDaoTest {
    private lateinit var database: AgentDatabase
    private val dao get() = database.agentNotificationDao()
    private val sessions get() = database.chatSessionDao()
    private val messages get() = database.agentMessageDao()

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AgentDatabase::class.java
        ).allowMainThreadQueries().build()
        sessions.upsert(ChatSessionEntity("parent", "Parent", 1, 1))
        sessions.upsert(ChatSessionEntity("child", "Task", 1, 1, parentId = "parent", subagentType = "编码员"))
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun persistedSequenceDedupAndRetirementSurviveCenterRecreation() = runBlocking {
        val center = AgentNotificationCenter(dao)
        val item = notification("stable")
        val original = center.enqueue("parent", item)
        assertEquals(original, center.enqueue("parent", item.copy(title = "retry")))
        center.clear("parent")
        val reopened = AgentNotificationCenter(dao)
        assertEquals(original, reopened.enqueue("parent", item))
        assertTrue(reopened.pendingRecipientIds().isEmpty())
        val next = reopened.enqueue("parent", notification("new"))
        assertTrue(next > original)
        assertEquals(listOf(next), reopened.peek("parent").map { it.seq })
    }

    @Test
    fun markDeliveredParticipatesInInputTransactionAndDoesNotAckNewOrForeignRows() = runBlocking {
        val center = AgentNotificationCenter(dao)
        val old = center.enqueue("parent", notification("old"))
        val newer = center.enqueue("parent", notification("newer"))
        val foreign = center.enqueue("child", notification("foreign"))
        try {
            database.withTransaction {
                messages.insert(AgentMessageEntity("input", "parent", "USER", "notification", 5))
                dao.markDelivered("parent", listOf(old, foreign), 6)
                error("rollback input")
            }
            fail("Transaction must fail")
        } catch (error: IllegalStateException) {
            assertEquals("rollback input", error.message)
        }
        assertNull(messages.getMessageById("input"))
        assertEquals(listOf(old, newer), center.peek("parent").map { it.seq })
        database.withTransaction {
            messages.insert(AgentMessageEntity("input", "parent", "USER", "notification", 5))
            assertEquals(1, dao.markDelivered("parent", listOf(old, foreign), 6))
        }
        assertEquals(listOf(newer), center.peek("parent").map { it.seq })
        assertEquals(listOf(foreign), center.peek("child").map { it.seq })
    }

    @Test
    fun coldRowsRequireSelectedConfirmationAndGenerationFenceRemainsInSql() = runBlocking {
        val center = AgentNotificationCenter(dao)
        val old = center.enqueue("parent", notification("old"))
        val reopened = AgentNotificationCenter(dao)
        assertTrue(reopened.peek("parent").isEmpty())
        assertEquals(listOf(old), reopened.pendingForConfirmation("parent").map { it.seq })
        sessions.upsert(sessions.getById("parent")!!.copy(executionGeneration = 1))
        assertEquals(0, dao.confirmPending("parent", listOf(old)))
        assertEquals(0, dao.markDelivered("parent", listOf(old), 10))
        assertEquals(0L, reopened.enqueue("parent", notification("late"), generation = 0))
        val live = reopened.enqueue("parent", notification("live"), generation = 1)
        assertTrue(live > old)
        reopened.discard("parent", listOf(old))
        assertEquals(listOf(live), reopened.peek("parent").map { it.seq })
    }

    @Test
    fun outboxFailureRollsBackJournalAndFinishCallbackTogether() = runBlocking {
        val center = AgentNotificationCenter(dao)
        center.initialize()
        val journal = SubAgentInteractionJournal(database, center)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_notification BEFORE INSERT ON agent_notification_outbox
            BEGIN SELECT RAISE(ABORT, 'forced outbox failure'); END
        """.trimIndent())
        try {
            journal.record(SubAgentEvent("child", "parent", SubAgentEventType.COMPLETED,
                detail = "public result", eventId = "terminal")) {
                sessions.upsert(sessions.getById("child")!!.copy(title = "finished"))
                true
            }
            fail("Outbox failure must abort the entire transaction")
        } catch (error: android.database.sqlite.SQLiteException) {
            assertTrue(error.message.orEmpty().contains("forced outbox failure"))
        }
        assertEquals("Task", sessions.getById("child")!!.title)
        assertTrue(database.subAgentInteractionDao().getAllOnce().isEmpty())
        assertEquals(0, dao.pendingCount("parent"))
    }

    @Test
    fun committedTerminalRetainsPublicBodyNameAndNotificationInSameDatabase() = runBlocking {
        val center = AgentNotificationCenter(dao)
        val journal = SubAgentInteractionJournal(database, center)
        messages.insert(AgentMessageEntity("answer", "child", "ASSISTANT", "public body", 20,
            reasoning = "private reasoning", signature = "private signature", toolCallsJson = "private protocol"))
        val entry = journal.record(SubAgentEvent("child", "parent", SubAgentEventType.COMPLETED,
            eventId = "terminal", sourceMessageId = "answer")) {
            sessions.upsert(sessions.getById("child")!!.copy(updatedAt = 30))
            true
        }
        assertEquals("public body", entry.content)
        assertEquals("编码员", entry.agentName)
        val stored = dao.pending("parent", false).single()
        assertFalse(stored.payloadJson.contains("private"))
        val reopened = AgentNotificationCenter(dao)
        assertEquals("public body", reopened.pendingForConfirmation("parent").single().resultExcerpt)
        assertEquals(entry, database.subAgentInteractionDao().getById("terminal"))
        assertEquals(30L, sessions.getById("child")!!.updatedAt)
    }

    private fun notification(id: String) = PendingNotification(
        AgentNotificationKind.SUBAGENT, "child", "编码员", NotificationOutcome.COMPLETED,
        resultExcerpt = "public result", eventId = id
    )
}
