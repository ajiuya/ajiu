package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionModelSwitchScopeTest {
    @Test
    fun switchesCurrentSessionAndTwoDirectChildrenInOneBatchOnly() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()
        val expectedIds = listOf("parent", "child-a", "child-b")

        val updatedIds = fixture.useCase.updateProviderModelWithDirectChildren("parent", "new-provider", "new-model")

        assertEquals(expectedIds, updatedIds)
        assertRows(before, fixture.rows, expectedIds, "new-provider", "new-model")
        coVerifySequence {
            fixture.queries.getById("parent")
            fixture.queries.getSubSessionsByParentOnce("parent")
            fixture.queries.updateProviderModelBatch(expectedIds, "new-provider", "new-model")
        }
        coVerify(exactly = 0) { fixture.queries.updateProviderModel(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.queries.upsert(any()) }
        coVerify(exactly = 0) { fixture.queries.upsertAll(any()) }
    }

    @Test
    fun switchingChildDoesNotChangeParentSiblingOrDeeperDescendants() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()
        val expectedIds = listOf("child-a", "grandchild")

        val updatedIds = fixture.useCase.updateProviderModelWithDirectChildren("child-a", "child-provider", "child-model")

        assertEquals(expectedIds, updatedIds)
        assertRows(before, fixture.rows, expectedIds, "child-provider", "child-model")
        coVerifySequence {
            fixture.queries.getById("child-a")
            fixture.queries.getSubSessionsByParentOnce("child-a")
            fixture.queries.updateProviderModelBatch(expectedIds, "child-provider", "child-model")
        }
    }

    @Test
    fun missingSessionReturnsEmptyWithoutReadingChildrenOrWritingRows() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()

        val updatedIds = fixture.useCase.updateProviderModelWithDirectChildren("missing", "new-provider", "new-model")

        assertEquals(emptyList<String>(), updatedIds)
        assertEquals(before, fixture.rows)
        coVerify(exactly = 1) { fixture.queries.getById("missing") }
        coVerify(exactly = 0) { fixture.queries.getSubSessionsByParentOnce(any()) }
        coVerify(exactly = 0) { fixture.queries.updateProviderModelBatch(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.queries.updateProviderModel(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.queries.upsert(any()) }
        coVerify(exactly = 0) { fixture.queries.upsertAll(any()) }
    }

    @Test
    fun sessionWithoutChildrenStillUpdatesItselfOnce() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()
        val expectedIds = listOf("child-b")

        val updatedIds = fixture.useCase.updateProviderModelWithDirectChildren("child-b", "new-provider", "new-model")

        assertEquals(expectedIds, updatedIds)
        assertRows(before, fixture.rows, expectedIds, "new-provider", "new-model")
        coVerifySequence {
            fixture.queries.getById("child-b")
            fixture.queries.getSubSessionsByParentOnce("child-b")
            fixture.queries.updateProviderModelBatch(expectedIds, "new-provider", "new-model")
        }
    }

    @Test
    fun nullableSelectionClearsOnlyProviderModelAndLastInputTokensForTargets() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()
        val expectedIds = listOf("parent", "child-a", "child-b")

        val updatedIds = fixture.useCase.updateProviderModelWithDirectChildren("parent", null, null)

        assertEquals(expectedIds, updatedIds)
        assertRows(before, fixture.rows, expectedIds, null, null)
        coVerify(exactly = 1) { fixture.queries.updateProviderModelBatch(expectedIds, null, null) }
    }

    @Test
    fun defaultDaoMethodFiltersWrongParentsAndWorkspacesAndDeduplicatesIds() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()
        val expectedIds = listOf("parent", "child-a", "child-b")
        coEvery { fixture.queries.getSubSessionsByParentOnce("parent") } returns
            (before.values.toList() + before.getValue("child-a"))

        val updatedIds = fixture.useCase.updateProviderModelWithDirectChildren("parent", "new-provider", "new-model")

        assertEquals(expectedIds, updatedIds)
        assertRows(before, fixture.rows, expectedIds, "new-provider", "new-model")
        coVerifySequence {
            fixture.queries.getById("parent")
            fixture.queries.getSubSessionsByParentOnce("parent")
            fixture.queries.updateProviderModelBatch(expectedIds, "new-provider", "new-model")
        }
    }

    @Test
    fun originalSingleSessionApiDoesNotReadOrUpdateChildren() = runTest {
        val fixture = fixture()
        val before = fixture.rows.toMap()

        fixture.useCase.updateProviderModel("parent", "single-provider", "single-model")

        assertRows(before, fixture.rows, listOf("parent"), "single-provider", "single-model")
        coVerifySequence {
            fixture.queries.updateProviderModel("parent", "single-provider", "single-model")
        }
        coVerify(exactly = 0) { fixture.queries.getById(any()) }
        coVerify(exactly = 0) { fixture.queries.getSubSessionsByParentOnce(any()) }
        coVerify(exactly = 0) { fixture.queries.updateProviderModelBatch(any(), any(), any()) }
    }

    private fun fixture() = Fixture(listOf(
        session("ancestor"),
        session("parent", parentId = "ancestor"),
        session("parent-sibling", parentId = "ancestor"),
        session("child-a", parentId = "parent"),
        session("child-b", parentId = "parent"),
        session("grandchild", parentId = "child-a"),
        session("great-grandchild", parentId = "grandchild"),
        session("other-root"),
        session("other-child", parentId = "other-root"),
        session("foreign-child", workspacePath = "/ws/b", parentId = "parent"),
        session("foreign-grandchild", workspacePath = "/ws/b", parentId = "child-a"),
        session("foreign-root", workspacePath = "/ws/b")
    ))

    private fun session(id: String, workspacePath: String = "/ws/a", parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "title-$id",
        createdAt = 100L,
        updatedAt = 200L,
        workspacePath = workspacePath,
        goal = "goal-$id",
        goalStatus = "ACTIVE",
        goalTurnCount = 3,
        goalStartInputTokens = 11,
        goalStartOutputTokens = 13,
        reasoningEffort = "HIGH",
        providerId = "provider-$id",
        model = "model-$id",
        totalInputTokens = 123,
        totalOutputTokens = 456,
        lastInputTokens = 67,
        isPinned = true,
        parentId = parentId,
        subagentType = parentId?.let { "coder" },
        executionGeneration = 9L,
        autoDispatchBlockedReason = "pending-approval"
    )

    private fun assertRows(
        before: Map<String, ChatSessionEntity>,
        after: Map<String, ChatSessionEntity>,
        expectedIds: List<String>,
        providerId: String?,
        model: String?
    ) {
        val expected = before.mapValues { (id, row) ->
            if (id in expectedIds) row.copy(providerId = providerId, model = model, lastInputTokens = 0) else row
        }
        assertEquals(expected, after)
    }

    private class Fixture(sessions: List<ChatSessionEntity>) {
        val rows = sessions.associateBy { it.id }.toMutableMap()
        val queries = mockk<ChatSessionDao>()
        val dao = object : ChatSessionDao by queries {
            override suspend fun updateProviderModelWithDirectChildren(id: String, providerId: String?, model: String?) =
                super<ChatSessionDao>.updateProviderModelWithDirectChildren(id, providerId, model)
        }
        val useCase = SessionUseCase(dao, mockk<AgentMessageDao>(), mockk<SubAgentInteractionDao>())

        init {
            coEvery { queries.getById(any()) } answers { rows[firstArg<String>()] }
            coEvery { queries.getSubSessionsByParentOnce(any()) } answers {
                val parentId = firstArg<String>()
                rows.values.filter { it.parentId == parentId }.sortedByDescending { it.updatedAt }
            }
            coEvery { queries.updateProviderModelBatch(any(), any(), any()) } answers {
                updateRows(firstArg(), secondArg(), thirdArg())
            }
            coEvery { queries.updateProviderModel(any(), any(), any()) } answers {
                updateRows(listOf(firstArg<String>()), secondArg(), thirdArg())
            }
        }

        private fun updateRows(ids: List<String>, providerId: String?, model: String?) {
            ids.forEach { id ->
                rows[id]?.let { row ->
                    rows[id] = row.copy(providerId = providerId, model = model, lastInputTokens = 0)
                }
            }
        }
    }
}
