package com.aicode.feature.agent.data.local.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRequestEntity
import com.aicode.feature.agent.domain.recovery.PendingRecoveryTarget
import com.aicode.feature.agent.domain.recovery.RecoveryItem
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRequestPayload
import com.aicode.feature.agent.domain.recovery.RecoveryScope
import com.aicode.feature.agent.domain.recovery.RecoveryStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AgentRecoveryPendingRoomTest {
    private lateinit var database: AgentDatabase
    private lateinit var repository: RecoveryRepository
    private val queryExecutor = Executors.newSingleThreadExecutor()
    private val transactionExecutor = Executors.newSingleThreadExecutor()
    private val dao get() = database.agentRecoveryDao()
    private val sessions get() = database.chatSessionDao()
    private val scope = RecoveryScope("parent", "/synthetic/workspace", 3)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AgentDatabase::class.java
        ).allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        sessions.upsert(session(scope.sessionId))
        repository = RecoveryRepository(dao)
        repository.initialize()
    }

    @After
    fun tearDown() {
        try {
            if (::database.isInitialized) database.close()
        } finally {
            queryExecutor.shutdownNow()
            transactionExecutor.shutdownNow()
        }
    }

    @Test
    fun continuousCollectorRemovesClaimedItemAndRestoresItAfterFailure() = runBlocking {
        assertClaimLifecycle(RecoveryStatus.FAILED)
    }

    @Test
    fun continuousCollectorRemovesClaimedItemAndRestoresItAfterInterruption() = runBlocking {
        assertClaimLifecycle(RecoveryStatus.INTERRUPTED)
    }

    @Test
    fun claimingQueueHeadPreservesLaterPendingRowsAndRejectsOutOfOrderClaims() = runBlocking {
        val first = request("z-first", queueOrder = 1)
        val second = request("a-second", queueOrder = 2)
        val third = request("m-third", queueOrder = 3)
        for (row in listOf(third, second, first)) dao.insertRequestRow(row)

        withPendingCollector { emissions ->
            val initial = emissions.awaitPending(first.id, second.id, third.id)
            val secondTarget = initial[1].pendingTarget()
            val thirdTarget = initial[2].pendingTarget()
            assertNull(repository.claimPending(scope, secondTarget, "local", false))
            assertNull(repository.claimPending(scope, thirdTarget, "local", false))
            assertTrue(dao.runsForRequest(second.id).isEmpty())
            assertTrue(dao.runsForRequest(third.id).isEmpty())

            val firstRun = checkNotNull(repository.claimPending(scope, initial.first().pendingTarget(), "local", false))
            val later = emissions.awaitPending(second.id, third.id)
            assertEquals(listOf(RecoveryStatus.AWAITING_CONFIRMATION, RecoveryStatus.AWAITING_CONFIRMATION),
                later.map { it.request.status })
            assertEquals(second, dao.requestRow(second.id))
            assertEquals(third, dao.requestRow(third.id))
            assertEquals("RUNNING", dao.requestRow(first.id)?.status)
            assertEquals(second.id, dao.firstPendingRequestId(scope.sessionId, scope.generation))
            assertNull(repository.claimPending(scope, secondTarget, "local", false))
            assertTrue(dao.runsForRequest(second.id).isEmpty())

            assertTrue(repository.finish(firstRun.id, RecoveryStatus.INTERRUPTED))
            val interrupted = emissions.awaitPending(first.id, second.id, third.id)
            assertEquals(RecoveryStatus.INTERRUPTED, interrupted.first().request.status)
            assertEquals(first.id, dao.firstPendingRequestId(scope.sessionId, scope.generation))
            assertNull(repository.claimPending(scope, secondTarget, "local", false))
            assertEquals(second, dao.requestRow(second.id))
            assertEquals(third, dao.requestRow(third.id))

            val resumed = checkNotNull(repository.claimPending(scope, interrupted.first().pendingTarget(), "local", false))
            emissions.awaitPending(second.id, third.id)
            assertTrue(repository.finish(resumed.id, RecoveryStatus.COMPLETED))

            val secondRun = checkNotNull(repository.claimPending(scope, secondTarget, "local", false))
            emissions.awaitPending(third.id)
            assertEquals("COMPLETED", dao.requestRow(first.id)?.status)
            assertEquals("RUNNING", dao.requestRow(second.id)?.status)
            assertEquals(third, dao.requestRow(third.id))
            assertNull(repository.claimPending(scope, thirdTarget, "local", false))
            assertTrue(dao.runsForRequest(third.id).isEmpty())

            assertTrue(repository.finish(secondRun.id, RecoveryStatus.COMPLETED))
            val thirdRun = checkNotNull(repository.claimPending(scope, thirdTarget, "local", false))
            emissions.awaitPending()
            assertEquals(RecoveryStatus.RUNNING, thirdRun.status)
            assertEquals(mapOf(first.id to "COMPLETED", second.id to "COMPLETED", third.id to "RUNNING"),
                dao.requestsForSession(scope.sessionId).associate { it.id to it.status })
            assertEquals(2, dao.runsForRequest(first.id).size)
            assertEquals(1, dao.runsForRequest(second.id).size)
            assertEquals(1, dao.runsForRequest(third.id).size)
        }
    }

    @Test
    fun continuousScopeCollectorExcludesGrandchildrenForeignWorkspacesAndStaleGenerations() = runBlocking {
        val child = session("child", parentId = scope.sessionId, generation = 7)
        val grandchild = session("grandchild", parentId = child.id, generation = 11)
        val foreign = session("foreign-child", parentId = scope.sessionId, workspacePath = "/synthetic/other", generation = 5)
        val unrelated = session("unrelated")
        sessions.upsertAll(listOf(child, grandchild, foreign, unrelated))
        val parentRequest = request("parent-request", queueOrder = 10)
        val childRequest = request("child-request", sessionId = child.id, generation = child.executionGeneration)
        val excluded = listOf(
            request("grandchild-request", sessionId = grandchild.id, generation = grandchild.executionGeneration),
            request("foreign-request", sessionId = foreign.id, generation = foreign.executionGeneration),
            request("unrelated-request", sessionId = unrelated.id),
            request("stale-child-request", sessionId = child.id, generation = child.executionGeneration - 1),
            request("stale-parent-request", generation = scope.generation - 1)
        )
        val stored = excluded + childRequest + parentRequest
        for (row in stored) dao.insertRequestRow(row)

        withPendingCollector { emissions ->
            val initial = emissions.awaitPending(parentRequest.id, childRequest.id)
            val parentTarget = initial[0].pendingTarget()
            val childTarget = initial[1].pendingTarget()
            assertTrue(repository.isInScope(scope, parentTarget))
            assertTrue(repository.isInScope(scope, childTarget))
            for (row in excluded) {
                assertFalse(repository.isInScope(scope, row.pendingTarget()))
                assertNull(repository.claimPending(scope, row.pendingTarget(), "local", false))
                assertEquals(row, dao.requestRow(row.id))
                assertTrue(dao.runsForRequest(row.id).isEmpty())
            }

            sessions.upsert(child.copy(workspacePath = foreign.workspacePath))
            emissions.awaitPending(parentRequest.id)
            assertFalse(repository.isInScope(scope, childTarget))
            assertNull(repository.claimPending(scope, childTarget, "local", false))
            assertEquals(childRequest, dao.requestRow(childRequest.id))

            sessions.upsert(child)
            emissions.awaitPending(parentRequest.id, childRequest.id)
            sessions.upsert(child.copy(executionGeneration = child.executionGeneration + 1))
            emissions.awaitPending(parentRequest.id)
            assertFalse(repository.isInScope(scope, childTarget))
            assertNull(repository.claimPending(scope, childTarget, "local", false))
            assertEquals(childRequest, dao.requestRow(childRequest.id))

            sessions.upsert(session(scope.sessionId, generation = scope.generation + 1))
            emissions.awaitPending()
            assertFalse(repository.isInScope(scope, parentTarget))
            assertNull(repository.claimPending(scope, parentTarget, "local", false))
            for (row in stored) assertEquals(row, dao.requestRow(row.id))
            assertTrue(dao.runsForRequest(parentRequest.id).isEmpty())
            assertTrue(dao.runsForRequest(childRequest.id).isEmpty())
        }
    }

    private suspend fun assertClaimLifecycle(finishStatus: RecoveryStatus) {
        val original = request("request")
        dao.insertRequestRow(original)

        withPendingCollector { emissions ->
            val pending = emissions.awaitPending(original.id).single()
            assertEquals(RecoveryStatus.AWAITING_CONFIRMATION, pending.request.status)
            assertNull(pending.run)
            val target = pending.pendingTarget()

            val run = checkNotNull(repository.claimPending(scope, target, "local", false))
            emissions.awaitPending()
            assertEquals(original.id, run.requestId)
            assertEquals(RecoveryStatus.RUNNING, run.status)
            assertEquals("RUNNING", dao.requestRow(original.id)?.status)
            assertEquals(run.id, dao.requestRow(original.id)?.activeRunId)
            assertTrue(repository.isCurrent(run.id))
            assertNull(repository.claimPending(scope, target, "local", false))
            assertEquals(1, dao.runsForRequest(original.id).size)

            assertTrue(repository.finish(run.id, finishStatus))
            val restored = emissions.awaitPending(original.id).single()
            assertEquals(finishStatus, restored.request.status)
            assertEquals(finishStatus, restored.run?.status)
            assertEquals(run.id, restored.request.activeRunId)
            assertEquals(run.id, restored.run?.id)
            assertEquals(original.inputMessageId, restored.request.inputMessageId)
            assertEquals(original.queueOrder, restored.request.queueOrder)
            assertEquals(pending.payload, restored.payload)
            assertFalse(repository.isCurrent(run.id))

            val retry = checkNotNull(repository.claimPending(scope, restored.pendingTarget(), "local", false))
            emissions.awaitPending()
            assertNotEquals(run.id, retry.id)
            assertEquals(run.id, retry.resumeSnapshot?.sourceRunId)
            assertEquals(retry.id, dao.requestRow(original.id)?.activeRunId)
            assertTrue(repository.isCurrent(retry.id))
            assertEquals(mapOf(run.id to finishStatus.name, retry.id to "RUNNING"),
                dao.runsForRequest(original.id).associate { it.id to it.status })
        }
    }

    private suspend fun withPendingCollector(
        block: suspend (ReceiveChannel<List<RecoveryItem>>) -> Unit
    ) = coroutineScope {
        val emissions = Channel<List<RecoveryItem>>(Channel.UNLIMITED)
        val collector = launch {
            repository.observePending(scope).distinctUntilChanged().collect { emissions.send(it) }
        }
        try {
            block(emissions)
        } finally {
            withContext(NonCancellable) {
                collector.cancelAndJoin()
                emissions.cancel()
            }
        }
    }

    private suspend fun ReceiveChannel<List<RecoveryItem>>.awaitPending(vararg requestIds: String): List<RecoveryItem> {
        val items = withTimeout(10_000) { receive() }
        assertEquals("Pending request order from the existing collector", requestIds.toList(), items.map { it.request.id })
        return items
    }

    private fun request(
        id: String,
        sessionId: String = scope.sessionId,
        generation: Long = scope.generation,
        queueOrder: Long = 1
    ) = RecoveryRequestEntity(
        id = id,
        sessionId = sessionId,
        generation = generation,
        environment = "local",
        inputMessageId = "input-$id",
        queueOrder = queueOrder,
        payloadJson = Json.encodeToString(RecoveryRequestPayload("Synthetic request $id", scope.workspacePath)),
        status = "AWAITING_CONFIRMATION",
        activeRunId = null,
        continuationCount = 0,
        continuationOutputTokens = 0,
        continuationChars = 0,
        createdAt = 1,
        updatedAt = 1
    )

    private fun session(
        id: String,
        parentId: String? = null,
        workspacePath: String = scope.workspacePath,
        generation: Long = scope.generation
    ) = ChatSessionEntity(
        id = id,
        title = "Synthetic session $id",
        createdAt = 1,
        updatedAt = 1,
        workspacePath = workspacePath,
        parentId = parentId,
        executionGeneration = generation
    )

    private fun RecoveryRequestEntity.pendingTarget() = PendingRecoveryTarget(
        id, sessionId, generation, activeRunId, emptySet()
    )
}
