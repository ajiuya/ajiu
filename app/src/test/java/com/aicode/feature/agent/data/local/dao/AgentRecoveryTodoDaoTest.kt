package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRecoveryTodoDaoTest {
    private val fixture = RecoveryDaoFixture()
    private val dao = fixture.dao

    @Test
    fun oldRunCannotReplaceOrClearRestoredTodosAfterGenerationBump() = runTest {
        fixture.enqueue()
        val oldRun = fixture.claim()
        val oldItems = listOf(todo("old-item"))
        fixture.todos[oldItems.single().id] = oldItems.single()
        fixture.sessions["session"] = fixture.sessions.getValue("session").copy(executionGeneration = 1)
        fixture.todos.clear()
        val restored = todo("restored-item").copy(subject = "Restored task", createdAt = 100, updatedAt = 200)
        fixture.todos[restored.id] = restored
        fixture.writes.clear()

        assertFalse(dao.replaceRecoveryTodos(oldRun.id, "owner", oldItems))
        assertFalse(dao.replaceRecoveryTodos(oldRun.id, "owner", emptyList()))

        assertEquals(mapOf(restored.id to restored), fixture.todos)
        assertTrue(fixture.writes.isEmpty())
    }

    @Test
    fun newRunReplacesOnlyOwnedTodosAndPreservesProvidedIdsAndMetadata() = runTest {
        fixture.enqueue("old")
        fixture.claim(requestId = "old", runId = "old-run")
        fixture.sessions["session"] = fixture.sessions.getValue("session").copy(executionGeneration = 1)
        fixture.enqueue("new")
        val newRun = fixture.claim(requestId = "new", runId = "new-run")
        val kept = todo("stable-id").copy(createdAt = 10)
        val removed = todo("removed-id")
        val foreign = todo("foreign-id").copy(sessionId = "other")
        for (item in listOf(kept, removed, foreign)) fixture.todos[item.id] = item
        fixture.writes.clear()

        assertFalse(dao.replaceRecoveryTodos(newRun.id, "wrong-owner", listOf(kept)))
        assertFalse(dao.replaceRecoveryTodos(newRun.id, "owner", listOf(foreign)))
        assertFalse(dao.replaceRecoveryTodos(newRun.id, "owner", listOf(foreign.copy(sessionId = "session"))))
        assertFalse(dao.replaceRecoveryTodos(newRun.id, "owner", listOf(kept, kept)))
        assertTrue(fixture.writes.isEmpty())
        assertEquals(mapOf(kept.id to kept, removed.id to removed, foreign.id to foreign), fixture.todos)

        val replacement = listOf(
            kept.copy(subject = "Updated task", description = "Keep ID", status = "IN_PROGRESS", priority = 2, order = 1, updatedAt = 300),
            todo("new-stable-id").copy(order = 0, createdAt = 250, updatedAt = 300)
        )
        assertTrue(dao.replaceRecoveryTodos(newRun.id, "owner", replacement))
        assertEquals(replacement.associateBy { it.id } + (foreign.id to foreign), fixture.todos)
        assertEquals(listOf("todoDelete:session", "todoUpsert:stable-id", "todoUpsert:new-stable-id"), fixture.writes)

        assertTrue(dao.replaceRecoveryTodos(newRun.id, "owner", emptyList()))
        assertEquals(mapOf(foreign.id to foreign), fixture.todos)
    }

    private fun todo(id: String) = TodoItemEntity(
        id = id,
        sessionId = "session",
        subject = "Task",
        createdAt = 1,
        updatedAt = 2
    )
}
