package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AgentMessageSnapshotDaoTest {
    private val rows = linkedMapOf<String, AgentMessageEntity>()
    private var generation: Long? = 3
    private val queries = mockk<AgentMessageDao>()
    private val dao = object : AgentMessageDao by queries {
        override suspend fun freezeModelInputSnapshot(sessionId: String, messageId: String, snapshotJson: String, generation: Long?) =
            super<AgentMessageDao>.freezeModelInputSnapshot(sessionId, messageId, snapshotJson, generation)
    }

    @Before
    fun setUp() {
        rows["user"] = AgentMessageEntity("user", "session", "USER", "Original", 1)
        coEvery { queries.sessionGenerationMatches(any(), any()) } answers {
            firstArg<String>() == "session" && generation != null && (secondArg<Long?>() == null || secondArg<Long?>() == generation)
        }
        coEvery { queries.getMessageById(any()) } answers { rows[firstArg<String>()] }
        coEvery { queries.writeModelInputSnapshotIfAbsent(any(), any(), any(), any()) } answers {
            val id = secondArg<String>()
            val row = rows[id]
            if (row != null && row.sessionId == firstArg<String>() && row.role == "USER" && row.modelInputSnapshotJson == null) {
                rows[id] = row.copy(modelInputSnapshotJson = thirdArg())
                1
            } else 0
        }
    }

    @Test
    fun firstSnapshotWinsAndReturnsOriginalJsonWithoutReplacingInput() = runTest {
        val original = rows.getValue("user")
        assertEquals("{\"version\":1}", dao.freezeModelInputSnapshot("session", "user", "{\"version\":1}", 3))
        assertEquals("{\"version\":1}", dao.freezeModelInputSnapshot("session", "user", "{\"version\":2}", 3))
        assertEquals(original.copy(modelInputSnapshotJson = "{\"version\":1}"), rows.getValue("user"))
        coVerify(exactly = 1) { queries.writeModelInputSnapshotIfAbsent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queries.insert(any()) }
    }

    @Test
    fun deletedOrChangedGenerationCannotFreezeOrReadBackFrozenSnapshot() = runTest {
        rows["user"] = rows.getValue("user").copy(modelInputSnapshotJson = "frozen")
        assertNull(dao.freezeModelInputSnapshot("session", "user", "late", 2))
        generation = null
        assertNull(dao.freezeModelInputSnapshot("session", "user", "late", null))
        coVerify(exactly = 0) { queries.writeModelInputSnapshotIfAbsent(any(), any(), any(), any()) }
    }

    @Test
    fun wrongSessionRoleAndMissingInputCannotFreezeOrInsert() = runTest {
        rows["assistant"] = rows.getValue("user").copy(id = "assistant", role = "ASSISTANT")
        rows["other"] = rows.getValue("user").copy(id = "other", sessionId = "other")
        assertNull(dao.freezeModelInputSnapshot("session", "missing", "snapshot", 3))
        assertNull(dao.freezeModelInputSnapshot("session", "assistant", "snapshot", 3))
        assertNull(dao.freezeModelInputSnapshot("session", "other", "snapshot", 3))
        coVerify(exactly = 0) { queries.writeModelInputSnapshotIfAbsent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { queries.insert(any()) }
    }
}
