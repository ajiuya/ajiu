package com.aicode.feature.agent.domain.notification

import com.aicode.feature.agent.data.local.dao.AgentNotificationDao
import com.aicode.feature.agent.data.local.entity.AgentNotificationEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class AgentNotificationKind { BACKGROUND_TASK, SUBAGENT, AGENT_MESSAGE, USER_MESSAGE }

@Serializable
enum class AgentMessageAction { MESSAGE, ADD_INSTRUCTION, CORRECTION }

@Serializable
enum class NotificationOutcome { COMPLETED, FAILED, STOPPED, UNKNOWN }

data class AgentNotificationTimelineEntry(
    val seq: Long,
    val createdAt: Long,
    val notification: PendingNotification,
)

@Serializable
data class PendingNotification(
    val kind: AgentNotificationKind,
    val sourceId: String,
    val title: String,
    val outcome: NotificationOutcome,
    val command: String? = null,
    val exitCode: Int? = null,
    val tailOutput: String? = null,
    val detail: String? = null,
    val message: String? = null,
    val fromParent: Boolean = false,
    val action: AgentMessageAction = AgentMessageAction.MESSAGE,
    val targetTitle: String? = null,
    val resultExcerpt: String? = null,
    val changedFiles: List<String> = emptyList(),
    val seq: Long = 0,
    val eventId: String = UUID.randomUUID().toString()
)

@Singleton
class AgentNotificationCenter private constructor(
    private val store: AgentNotificationStore
) {
    @Inject
    constructor(dao: AgentNotificationDao) : this(RoomAgentNotificationStore(dao))

    // 独立测试存储；生产注入构造器必须取得持久 DAO。
    constructor() : this(InMemoryAgentNotificationStore())

    private val initializationMutex = Mutex()
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun initialize() {
        initializationMutex.withLock {
            if (initialized) return
            store.requirePendingConfirmation()
            initialized = true
        }
    }

    /** 返回持久序号；目标已删除或 generation 失效时返回 0。 */
    suspend fun enqueue(
        sessionId: String,
        item: PendingNotification,
        generation: Long? = null,
        requiresConfirmation: Boolean = false
    ): Long {
        initialize()
        require(sessionId.isNotBlank() && item.eventId.isNotBlank())
        val currentGeneration = generation ?: store.sessionGeneration(sessionId) ?: return 0
        return store.enqueue(AgentNotificationEntity(
            sessionId = sessionId,
            generation = currentGeneration,
            eventKey = eventKey(sessionId, currentGeneration, item.eventId),
            payloadJson = json.encodeToString(item.copy(seq = 0)),
            createdAt = System.currentTimeMillis(),
            requiresConfirmation = requiresConfirmation
        ))
    }

    suspend fun peek(sessionId: String): List<PendingNotification> {
        initialize()
        return store.pending(sessionId, requiresConfirmation = false).map(::decode)
    }

    suspend fun pendingForConfirmation(sessionId: String): List<PendingNotification> {
        initialize()
        return store.pending(sessionId, requiresConfirmation = true).map(::decode)
    }

    fun observeTimeline(sessionId: String): Flow<List<AgentNotificationTimelineEntry>> = flow {
        initialize()
        emitAll(store.observeTimeline(sessionId).map { rows ->
            rows.map { row -> AgentNotificationTimelineEntry(row.seq, row.createdAt, decode(row)) }
        })
    }

    suspend fun pendingRecipientIds(): List<String> {
        initialize()
        return store.pendingRecipientIds()
    }

    suspend fun confirmPending(sessionId: String, seqs: Collection<Long>): Int {
        initialize()
        if (seqs.isEmpty()) return 0
        return store.confirmPending(sessionId, seqs.distinct())
    }

    /** recovery 消息落库路径应在同一 Room 事务中直接调用 DAO.markDelivered。 */
    suspend fun ack(sessionId: String, seqs: Collection<Long>) {
        initialize()
        if (seqs.isNotEmpty()) store.markDelivered(sessionId, seqs.distinct(), System.currentTimeMillis())
    }

    suspend fun drain(sessionId: String): List<PendingNotification> {
        initialize()
        return store.drain(sessionId, System.currentTimeMillis()).map(::decode)
    }

    /** 包括等待用户确认的当前代际记录。 */
    suspend fun pendingCount(sessionId: String): Int {
        initialize()
        return store.pendingCount(sessionId)
    }

    suspend fun discard(sessionId: String, seqs: Collection<Long>) {
        initialize()
        if (seqs.isNotEmpty()) store.retireSelected(sessionId, seqs.distinct(), System.currentTimeMillis())
    }

    suspend fun clear(sessionId: String) {
        initialize()
        store.retireSession(sessionId, System.currentTimeMillis())
    }

    suspend fun clearAll() {
        initialize()
        store.retireAll(System.currentTimeMillis())
    }

    private fun decode(row: AgentNotificationEntity): PendingNotification =
        json.decodeFromString<PendingNotification>(row.payloadJson).copy(seq = row.seq)

    private fun eventKey(sessionId: String, generation: Long, eventId: String): String =
        "${sessionId.length}:$sessionId:$generation:$eventId"
}

private interface AgentNotificationStore {
    suspend fun sessionGeneration(sessionId: String): Long?
    suspend fun enqueue(notification: AgentNotificationEntity): Long
    suspend fun pending(sessionId: String, requiresConfirmation: Boolean): List<AgentNotificationEntity>
    fun observeTimeline(sessionId: String): Flow<List<AgentNotificationEntity>>
    suspend fun pendingCount(sessionId: String): Int
    suspend fun pendingRecipientIds(): List<String>
    suspend fun requirePendingConfirmation()
    suspend fun confirmPending(sessionId: String, seqs: List<Long>): Int
    suspend fun markDelivered(sessionId: String, seqs: List<Long>, deliveredAt: Long)
    suspend fun drain(sessionId: String, deliveredAt: Long): List<AgentNotificationEntity>
    suspend fun retireSession(sessionId: String, retiredAt: Long)
    suspend fun retireSelected(sessionId: String, seqs: List<Long>, retiredAt: Long)
    suspend fun retireAll(retiredAt: Long)
}

private class RoomAgentNotificationStore(private val dao: AgentNotificationDao) : AgentNotificationStore {
    override suspend fun sessionGeneration(sessionId: String) = dao.sessionGeneration(sessionId)
    override suspend fun enqueue(notification: AgentNotificationEntity) = dao.enqueue(notification)
    override suspend fun pending(sessionId: String, requiresConfirmation: Boolean) = dao.pending(sessionId, requiresConfirmation)
    override fun observeTimeline(sessionId: String) = dao.observeTimeline(sessionId)
    override suspend fun pendingCount(sessionId: String) = dao.pendingCount(sessionId)
    override suspend fun pendingRecipientIds() = dao.pendingRecipientIds()
    override suspend fun requirePendingConfirmation() { dao.requirePendingConfirmation() }
    override suspend fun confirmPending(sessionId: String, seqs: List<Long>) = dao.confirmPending(sessionId, seqs)
    override suspend fun markDelivered(sessionId: String, seqs: List<Long>, deliveredAt: Long) {
        dao.markDelivered(sessionId, seqs, deliveredAt)
    }
    override suspend fun drain(sessionId: String, deliveredAt: Long) = dao.drain(sessionId, deliveredAt)
    override suspend fun retireSession(sessionId: String, retiredAt: Long) { dao.retireSession(sessionId, retiredAt) }
    override suspend fun retireSelected(sessionId: String, seqs: List<Long>, retiredAt: Long) {
        dao.retireSelected(sessionId, seqs, retiredAt)
    }
    override suspend fun retireAll(retiredAt: Long) { dao.retireAll(retiredAt) }
}

private class InMemoryAgentNotificationStore : AgentNotificationStore {
    private val mutex = Mutex()
    private val rows = mutableListOf<AgentNotificationEntity>()
    private var nextSeq = 0L

    override suspend fun sessionGeneration(sessionId: String): Long = 0

    override suspend fun enqueue(notification: AgentNotificationEntity): Long = mutex.withLock {
        if (notification.generation != 0L) return@withLock 0
        rows.find { it.eventKey == notification.eventKey }?.let { return@withLock it.seq }
        val stored = notification.copy(seq = ++nextSeq)
        rows += stored
        stored.seq
    }

    private fun AgentNotificationEntity.isPending() = deliveredAt == null && retiredAt == null

    override fun observeTimeline(sessionId: String): Flow<List<AgentNotificationEntity>> = flow {
        emit(mutex.withLock { rows.filter { it.sessionId == sessionId && it.retiredAt == null }.toList() })
    }

    override suspend fun pending(sessionId: String, requiresConfirmation: Boolean) = mutex.withLock {
        rows.filter { it.sessionId == sessionId && it.isPending() && it.requiresConfirmation == requiresConfirmation }
    }

    override suspend fun pendingCount(sessionId: String) = mutex.withLock {
        rows.count { it.sessionId == sessionId && it.isPending() }
    }

    override suspend fun pendingRecipientIds() = mutex.withLock {
        rows.filter { it.isPending() }.map { it.sessionId }.distinct().sorted()
    }

    override suspend fun requirePendingConfirmation() {
        mutex.withLock {
            rows.replaceAll { if (it.isPending()) it.copy(requiresConfirmation = true) else it }
        }
    }

    override suspend fun confirmPending(sessionId: String, seqs: List<Long>): Int = mutex.withLock {
        var confirmed = 0
        rows.replaceAll {
            if (it.sessionId == sessionId && it.seq in seqs && it.isPending() && it.requiresConfirmation) {
                confirmed++
                it.copy(requiresConfirmation = false)
            } else it
        }
        confirmed
    }

    override suspend fun markDelivered(sessionId: String, seqs: List<Long>, deliveredAt: Long) {
        mutex.withLock {
            rows.replaceAll {
                if (it.sessionId == sessionId && it.seq in seqs && it.isPending())
                    it.copy(deliveredAt = deliveredAt) else it
            }
        }
    }

    override suspend fun drain(sessionId: String, deliveredAt: Long): List<AgentNotificationEntity> = mutex.withLock {
        val pending = rows.filter { it.sessionId == sessionId && it.isPending() && !it.requiresConfirmation }
        val seqs = pending.map { it.seq }.toSet()
        rows.replaceAll { if (it.seq in seqs) it.copy(deliveredAt = deliveredAt) else it }
        pending
    }

    override suspend fun retireSession(sessionId: String, retiredAt: Long) {
        mutex.withLock {
            rows.replaceAll { if (it.sessionId == sessionId && it.isPending()) it.copy(retiredAt = retiredAt) else it }
        }
    }

    override suspend fun retireSelected(sessionId: String, seqs: List<Long>, retiredAt: Long) {
        mutex.withLock {
            rows.replaceAll {
                if (it.sessionId == sessionId && it.seq in seqs && it.isPending()) it.copy(retiredAt = retiredAt) else it
            }
        }
    }

    override suspend fun retireAll(retiredAt: Long) {
        mutex.withLock {
            rows.replaceAll { if (it.isPending()) it.copy(retiredAt = retiredAt) else it }
        }
    }
}
