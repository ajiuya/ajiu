package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AgentMessageSessionInsertDaoTest {
    private val queries = mockk<AgentMessageDao>(relaxed = true)
    private val dao = object : AgentMessageDao by queries {
        override suspend fun insertMessageIfSessionExists(message: AgentMessageEntity) =
            super<AgentMessageDao>.insertMessageIfSessionExists(message)
    }

    @Test
    fun missingSessionSkipsEveryRoleWithoutReadingOrWritingMessages() = runTest {
        coEvery { queries.sessionExists("session") } returns false

        for (role in listOf("USER", "ASSISTANT", "TOOL")) {
            dao.insertMessageIfSessionExists(row(role))
        }

        coVerify(exactly = 3) { queries.sessionExists("session") }
        coVerify(exactly = 0) { queries.insert(any()) }
        coVerify(exactly = 0) { queries.insertToolMessagePreservingMetadata(any()) }
        coVerify(exactly = 0) { queries.getMessageById(any()) }
    }

    @Test
    fun existingSessionUsesTheOriginalInsertForOrdinaryMessages() = runTest {
        coEvery { queries.sessionExists("session") } returns true
        coEvery { queries.getMessageById(any()) } returns null
        val user = row("USER")
        val assistant = row("ASSISTANT")

        dao.insertMessageIfSessionExists(user)
        dao.insertMessageIfSessionExists(assistant)

        coVerify(exactly = 1) { queries.insert(user) }
        coVerify(exactly = 1) { queries.insert(assistant) }
        coVerify(exactly = 0) { queries.insertToolMessagePreservingMetadata(any()) }
    }

    @Test
    fun userUpdatesPreserveFrozenInputAndCannotReplaceAnotherSession() = runTest {
        coEvery { queries.sessionExists("session") } returns true
        val saved = row("USER").copy(modelInputSnapshotJson = "frozen snapshot")
        coEvery { queries.getMessageById(saved.id) } returns saved
        dao.insertMessageIfSessionExists(saved.copy(content = "Visible content update", modelInputSnapshotJson = null))
        coVerify(exactly = 1) { queries.insert(saved.copy(content = "Visible content update")) }
        coEvery { queries.getMessageById(saved.id) } returns saved.copy(sessionId = "other")
        dao.insertMessageIfSessionExists(saved)
        coVerify(exactly = 1) { queries.insert(any()) }
    }

    @Test
    fun existingSessionToolUsesTheMetadataPreservingInsert() = runTest {
        coEvery { queries.sessionExists("session") } returns true
        val tool = row("TOOL").copy(toolCallId = "call", toolActionTitle = "Read file", toolDurationMs = 125)
        coEvery { queries.insertToolMessagePreservingMetadata(tool) } returns true

        dao.insertMessageIfSessionExists(tool)

        coVerify(exactly = 1) { queries.insertToolMessagePreservingMetadata(tool) }
        coVerify(exactly = 0) { queries.insert(any()) }
    }

    private fun row(role: String): AgentMessageEntity = AgentMessageEntity(
        id = role, sessionId = "session", role = role, content = "Feedback", timestamp = 1
    )
}
