package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 删除工作区时按 workspacePath 级联清理会话与消息的行为。 */
class SessionUseCaseWorkspaceDeletionTest {

    private fun session(id: String, workspacePath: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = workspacePath,
        parentId = parentId
    )

    @Test
    fun deleteSessionsByWorkspace_deletesMessagesThenSessions() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/a") } returns listOf(
            session("root", "/ws/a"),
            session("sub", "/ws/a", parentId = "root")
        )

        val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
        val useCase = SessionUseCase(chatDao, messageDao, interactionDao)
        val deleted = useCase.deleteSessionsByWorkspace("/ws/a")

        assertEquals(2, deleted)
        coVerify(exactly = 1) { interactionDao.deleteByParent("root") }
        coVerify(exactly = 1) { interactionDao.deleteByParent("sub") }
        coVerify(exactly = 1) { messageDao.deleteBySession("root") }
        coVerify(exactly = 1) { messageDao.deleteBySession("sub") }
        coVerify(exactly = 1) { chatDao.deleteByWorkspace("/ws/a") }
    }

    @Test
    fun deleteParentCleansOwnedJournalIncludingDeletedChildHistory() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
        coEvery { chatDao.getSubSessionsByParentOnce("root") } returns listOf(session("sub", "/ws", "root"))
        val useCase = SessionUseCase(chatDao, messageDao, interactionDao)

        assertEquals(listOf("root", "sub"), useCase.deleteSession("root"))

        coVerify(exactly = 1) { interactionDao.deleteByParent("root") }
        coVerify(exactly = 1) { interactionDao.deleteByParent("sub") }
        coVerify(exactly = 0) { interactionDao.deleteAll() }
        coVerify(exactly = 1) { chatDao.delete("root") }
        coVerify(exactly = 1) { chatDao.delete("sub") }
    }

    @Test
    fun deleteChildDoesNotClearParentAudit() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
        coEvery { chatDao.getSubSessionsByParentOnce("sub") } returns emptyList()
        val useCase = SessionUseCase(chatDao, messageDao, interactionDao)

        assertEquals(listOf("sub"), useCase.deleteSession("sub"))

        coVerify(exactly = 1) { interactionDao.deleteByParent("sub") }
        coVerify(exactly = 0) { interactionDao.deleteByParent("root") }
        coVerify(exactly = 0) { interactionDao.deleteAll() }
        coVerify(exactly = 1) { messageDao.deleteBySession("sub") }
        coVerify(exactly = 1) { chatDao.delete("sub") }
    }

    @Test
    fun deleteSessionsByWorkspace_noSessions_skipsDeletion() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/empty") } returns emptyList()

        val interactionDao = mockk<SubAgentInteractionDao>(relaxed = true)
        val useCase = SessionUseCase(chatDao, messageDao, interactionDao)
        val deleted = useCase.deleteSessionsByWorkspace("/ws/empty")

        assertEquals(0, deleted)
        coVerify(exactly = 0) { interactionDao.deleteByParent(any()) }
        coVerify(exactly = 0) { messageDao.deleteBySession(any()) }
        coVerify(exactly = 0) { chatDao.deleteByWorkspace(any()) }
    }
}