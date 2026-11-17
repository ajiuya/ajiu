package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentChangedFileSource
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.FakeAgentNotificationDao
import com.aicode.feature.agent.domain.session.SessionUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class SubAgentJournalFixture {
    val sessionRows = linkedMapOf(
        "parent" to ChatSessionEntity("parent", "Parent", 1, 1, workspacePath = "/work", model = "parent-model"),
        "child" to ChatSessionEntity("child", "Child", 2, 2, workspacePath = "/work", parentId = "parent"),
        "other-parent" to ChatSessionEntity("other-parent", "Other", 1, 1, workspacePath = "/work"),
        "other-child" to ChatSessionEntity("other-child", "Other child", 2, 2, workspacePath = "/work", parentId = "other-parent")
    )
    val messageRows = linkedMapOf<String, AgentMessageEntity>()
    val interactions = FakeSubAgentInteractionDao(messageRows)
    val notificationDao = FakeAgentNotificationDao()
    val notifications = AgentNotificationCenter(notificationDao)
    val sessions = mockk<ChatSessionDao>()
    val messages = mockk<AgentMessageDao>()
    val sessionUseCase = mockk<SessionUseCase>()
    val journal = SubAgentInteractionJournal(interactions, messages, sessions, notifications)
    val bus = SubAgentEventBus(journal)

    init {
        sessionRows.forEach { (id, session) -> notificationDao.generations[id] = session.executionGeneration }
        coEvery { sessions.getById(any()) } coAnswers { sessionRows[firstArg<String>()] }
        coEvery { sessions.getSubSessionsByParentOnce(any()) } coAnswers {
            val parent = firstArg<String>()
            sessionRows.values.filter { it.parentId == parent }
        }
        coEvery { messages.getMessageById(any()) } coAnswers { messageRows[firstArg<String>()] }
        coEvery { messages.getMessagesBySessionOnce(any()) } coAnswers {
            val session = firstArg<String>()
            messageRows.values.filter { it.sessionId == session }.sortedBy { it.timestamp }
        }
        coEvery { sessionUseCase.getSessionById(any()) } coAnswers { sessionRows[firstArg<String>()] }
        coEvery { sessionUseCase.upsertSession(any()) } coAnswers {
            val row = firstArg<ChatSessionEntity>()
            sessionRows[row.id] = row
        }
        coEvery { sessionUseCase.deleteSession(any()) } coAnswers {
            val id = firstArg<String>()
            sessionRows.remove(id)
            messageRows.entries.removeAll { it.value.sessionId == id }
            interactions.deleteByParent(id)
            listOf(id)
        }
    }
}

internal class FakeSubAgentInteractionDao(
    private val messages: Map<String, AgentMessageEntity> = emptyMap()
) : SubAgentInteractionDao {
    val recoveryRuns = mutableListOf<RecoveryRunEntity>()
    private val rows = MutableStateFlow<List<SubAgentInteractionEntity>>(emptyList())
    private var nextId = 0L
    var insertFailure: RuntimeException? = null
    var beforeInsert: suspend (SubAgentInteractionEntity) -> Unit = {}

    private fun ordered(entries: List<SubAgentInteractionEntity>) =
        entries.sortedWith(compareBy({ it.timestamp }, { it.id }))

    override suspend fun insertIgnore(interaction: SubAgentInteractionEntity): Long {
        beforeInsert(interaction)
        insertFailure?.let { throw it }
        return synchronized(this) {
            if (rows.value.any { it.id == interaction.id }) -1L
            else {
                rows.value = rows.value + interaction
                ++nextId
            }
        }
    }

    override suspend fun insertAllIgnore(interactions: List<SubAgentInteractionEntity>): List<Long> =
        interactions.map { insertIgnore(it) }

    override suspend fun getById(id: String): SubAgentInteractionEntity? = rows.value.find { it.id == id }

    private fun metadata(entries: List<SubAgentInteractionEntity>, parentId: String) =
        ordered(entries.filter { it.parentSessionId == parentId }).map {
            SubAgentInteractionMetadata(
                id = it.id,
                parentSessionId = it.parentSessionId,
                subSessionId = it.subSessionId,
                kind = it.kind,
                timestamp = it.timestamp,
                origin = it.origin,
                sourceMessageId = it.sourceMessageId,
                title = it.title,
                model = it.model,
                preview = it.content.take(240),
                instructionId = it.instructionId,
                agentName = it.agentName
            )
        }

    override fun observeByParent(parentId: String): Flow<List<SubAgentInteractionMetadata>> =
        rows.map { metadata(it, parentId) }

    override suspend fun getMetadataByParentOnce(parentId: String): List<SubAgentInteractionMetadata> =
        metadata(rows.value, parentId)

    override fun getByChildPaged(parentId: String, childId: String, limit: Int): Flow<List<SubAgentInteractionEntity>> =
        rows.map { entries ->
            ordered(entries.filter { it.parentSessionId == parentId && it.subSessionId == childId }).takeLast(limit)
        }

    override fun observeCountByChild(parentId: String, childId: String): Flow<Int> =
        rows.map { entries -> entries.count { it.parentSessionId == parentId && it.subSessionId == childId } }

    override suspend fun getParentIdsByChildOnce(childId: String): List<String> =
        rows.value.filter { it.subSessionId == childId }.map { it.parentSessionId }.distinct()

    override suspend fun getAllOnce(): List<SubAgentInteractionEntity> = ordered(rows.value)

    override suspend fun getPageAfter(lastTimestamp: Long, lastId: String, limit: Int): List<SubAgentInteractionEntity> =
        ordered(rows.value.filter {
            it.timestamp > lastTimestamp || (it.timestamp == lastTimestamp && it.id > lastId)
        }).take(limit)

    override suspend fun getPageByParentAfter(
        parentId: String,
        lastTimestamp: Long,
        lastId: String,
        limit: Int
    ): List<SubAgentInteractionEntity> = getPageAfter(lastTimestamp, lastId, Int.MAX_VALUE)
        .filter { it.parentSessionId == parentId }.take(limit)

    override suspend fun getUnfinishedExecutions(): List<SubAgentInteractionEntity> = ordered(rows.value.filter { start ->
        start.kind in setOf("SPAWNED", "STARTED") && rows.value.none { later ->
            later.parentSessionId == start.parentSessionId && later.subSessionId == start.subSessionId &&
                (later.generation == start.generation || later.generation == null || start.generation == null) &&
                later.kind in setOf("SPAWNED", "STARTED") &&
                (later.timestamp > start.timestamp || later.timestamp == start.timestamp && later.id > start.id)
        } && rows.value.none { terminal ->
            terminal.parentSessionId == start.parentSessionId && terminal.subSessionId == start.subSessionId &&
                terminal.kind in setOf("COMPLETED", "FAILED", "STOPPED", "DELETED", "INTERRUPTED") &&
                terminal.timestamp >= start.timestamp && (terminal.kind == "DELETED" ||
                    (terminal.generation == start.generation || terminal.generation == null || start.generation == null) &&
                    (terminal.runId == start.runId || terminal.runId == null || start.runId == null))
        }
    })

    override suspend fun latestExecution(parentId: String, childId: String, runId: String?) =
        ordered(rows.value.filter { it.parentSessionId == parentId && it.subSessionId == childId &&
            it.kind in setOf("SPAWNED", "STARTED") && (runId == null || it.runId == runId) }).lastOrNull()

    override suspend fun recoveryRunsForChild(childId: String) =
        recoveryRuns.filter { it.sessionId == childId }.sortedWith(compareByDescending<RecoveryRunEntity> { it.createdAt }.thenByDescending { it.id })

    override suspend fun changedFileSources(childId: String, messageIds: List<String>) =
        messages.values.filter { it.sessionId == childId && it.id in messageIds && it.role == "TOOL" &&
            it.toolName in setOf("editFile", "writeFile", "generateImage") && !it.isContextExcluded &&
            !it.isContextSummary && !it.isCompactionMarker }
            .sortedWith(compareBy({ it.timestamp }, { it.id }))
            .map { SubAgentChangedFileSource(it.id, it.toolName, it.toolArgs) }

    override suspend fun deleteByParent(parentId: String) {
        synchronized(this) { rows.value = rows.value.filterNot { it.parentSessionId == parentId } }
    }

    override suspend fun deleteAll() {
        synchronized(this) { rows.value = emptyList() }
    }
}
