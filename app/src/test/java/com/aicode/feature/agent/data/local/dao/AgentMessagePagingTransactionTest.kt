package com.aicode.feature.agent.data.local.dao

import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 验证 Room 生成 DAO 的事务标志契约，不覆盖真实 SQLite 或 CursorWindow 并发。 */
class AgentMessagePagingTransactionTest {
    @Before
    fun setUp() {
        mockkStatic("androidx.room.coroutines.FlowUtil")
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.coroutines.FlowUtil")
    }

    @Test
    fun pagedMessagesRequestTransactionalFlowFromGeneratedDao() {
        val database = mockk<RoomDatabase>()
        val inTransaction = slot<Boolean>()
        val tableNames = slot<Array<String>>()
        val expected = flowOf(emptyList<AgentMessageEntity>())
        every {
            createFlow<List<AgentMessageEntity>>(database, capture(inTransaction), capture(tableNames), any())
        } returns expected

        val actual = AgentMessageDao_Impl(database).getMessagesBySessionPaged("session", 50)

        assertSame(expected, actual)
        assertTrue("Paged message reads must request a Room transaction", inTransaction.captured)
        assertArrayEquals(arrayOf("agent_messages"), tableNames.captured)
        verify(exactly = 1) {
            createFlow<List<AgentMessageEntity>>(database, true, any(), any())
        }
    }
}
