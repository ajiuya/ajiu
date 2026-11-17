package com.aicode.feature.agent.domain.subagent

import androidx.room.withTransaction
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class SubAgentInteractionJournal private constructor(
    private val interactionDao: SubAgentInteractionDao,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val notifications: AgentNotificationCenter,
    private val database: AgentDatabase?
) {
    @Inject
    constructor(database: AgentDatabase, notifications: AgentNotificationCenter) : this(
        database.subAgentInteractionDao(), database.agentMessageDao(), database.chatSessionDao(), notifications, database
    )

    // 独立 DAO 夹具入口；生产构造器始终使用同一个 Room 数据库事务。
    internal constructor(
        interactionDao: SubAgentInteractionDao,
        agentMessageDao: AgentMessageDao,
        chatSessionDao: ChatSessionDao,
        notifications: AgentNotificationCenter = AgentNotificationCenter()
    ) : this(interactionDao, agentMessageDao, chatSessionDao, notifications, null)

    suspend fun record(
        event: SubAgentEvent,
        finish: (suspend () -> Boolean)? = null
    ): SubAgentInteractionEntity {
        notifications.initialize()
        return transaction {
            val existing = interactionDao.getById(event.eventId)
            if (existing != null) {
                val entry = existingFor(event, existing)
                enqueueNotification(entry, runMessageIds = event.runMessageIds)
                return@transaction entry
            }
            if (finish != null && !finish()) throw CancellationException("Subagent run no longer owned")
            val entry = recordEntry(event)
            enqueueNotification(entry, runMessageIds = event.runMessageIds)
            entry
        }
    }

    suspend fun isCurrent(entry: SubAgentInteractionEntity): Boolean {
        val parent = chatSessionDao.getById(entry.parentSessionId) ?: return false
        if (entry.parentGeneration != null && parent.executionGeneration != entry.parentGeneration) return false
        val child = chatSessionDao.getById(entry.subSessionId)
        if (entry.kind == SubAgentEventType.STOPPED.name && entry.runId != null &&
            interactionDao.latestExecution(entry.parentSessionId, entry.subSessionId, null)?.runId != entry.runId) return false
        return if (child == null) entry.kind == SubAgentEventType.DELETED.name
        else child.parentId == parent.id && (entry.generation == null || child.executionGeneration == entry.generation)
    }

    private suspend fun recordEntry(event: SubAgentEvent): SubAgentInteractionEntity {
        require(event.eventId.isNotBlank()) { "Subagent interaction requires an event id" }
        require(event.parentSessionId != event.subSessionId) { "A subagent cannot be its own parent" }
        val parent = requireNotNull(chatSessionDao.getById(event.parentSessionId)) {
            "Subagent interaction parent session does not exist"
        }
        val child = requireNotNull(chatSessionDao.getById(event.subSessionId)) {
            "Subagent interaction child session does not exist"
        }
        require(child.parentId == parent.id) { "Subagent interaction does not belong to this parent" }
        val isInstructionResolution = event.type == SubAgentEventType.STARTED ||
            event.type == SubAgentEventType.INSTRUCTION_CANCELLED
        require(event.instructionId == null || isInstructionResolution) {
            "Only STARTED or INSTRUCTION_CANCELLED can reference an accepted user instruction"
        }
        if (event.type == SubAgentEventType.INSTRUCTION_CANCELLED) {
            require(!event.instructionId.isNullOrBlank()) {
                "INSTRUCTION_CANCELLED requires an accepted user instruction id"
            }
        }
        if (isInstructionResolution) {
            require(event.sourceMessageId == null) {
                "Instruction resolution identity is not an assistant source message id"
            }
            event.instructionId?.let { instructionId ->
                val instruction = requireNotNull(interactionDao.getById(instructionId)) {
                    "Instruction has not been accepted"
                }
                require(instruction.parentSessionId == parent.id && instruction.subSessionId == child.id &&
                    instruction.kind == SubAgentEventType.MESSAGE_FROM_PARENT.name && instruction.origin == "USER") {
                    "Instruction resolution must reference a USER message accepted for this same parent and child"
                }
            }
        }

        val sourceMessage = event.sourceMessageId?.let { sourceMessageId ->
            val source = requireNotNull(agentMessageDao.getMessageById(sourceMessageId)) {
                "Subagent interaction source message does not exist"
            }
            require(source.sessionId == child.id) { "Source message does not belong to this child" }
            if (event.type == SubAgentEventType.COMPLETED) {
                require(source.isAssistantBody()) { "Completion source must be an assistant body" }
            }
            source
        }
        val start = if (event.type in setOf(SubAgentEventType.COMPLETED, SubAgentEventType.FAILED,
                SubAgentEventType.STOPPED, SubAgentEventType.INTERRUPTED)) {
            interactionDao.latestExecution(parent.id, child.id, event.runId)
        } else null
        val entry = SubAgentInteractionEntity(
            id = event.eventId,
            parentSessionId = parent.id,
            subSessionId = child.id,
            kind = event.type.name,
            content = if (event.type == SubAgentEventType.COMPLETED && sourceMessage != null) {
                sourceMessage.content
            } else {
                event.detail
            },
            timestamp = event.timestamp,
            origin = event.origin ?: event.type.defaultOrigin(),
            sourceMessageId = sourceMessage?.id,
            title = child.title,
            model = child.model ?: parent.model,
            instructionId = event.instructionId,
            generation = event.generation ?: start?.generation ?: child.executionGeneration,
            parentGeneration = event.parentGeneration ?: start?.parentGeneration ?: parent.executionGeneration,
            runId = event.runId ?: start?.runId,
            agentName = start?.agentName ?: child.subagentType?.takeIf { it.isNotBlank() && it != "subagent" }
        )
        if (interactionDao.insertIgnore(entry) != -1L) return entry
        return existingFor(event, checkNotNull(interactionDao.getById(event.eventId)) {
            "Ignored subagent interaction is missing"
        })
    }

    /** 冷启动恢复审计，不广播 SPAWNED/消息，也不恢复并发槽位。 */
    suspend fun recoverInterrupted(detail: String = ""): List<SubAgentInteractionEntity> {
        notifications.initialize()
        return transaction {
            val recovered = mutableListOf<SubAgentInteractionEntity>()
            for (start in interactionDao.getUnfinishedExecutions()) {
                val parent = chatSessionDao.getById(start.parentSessionId) ?: continue
                val child = chatSessionDao.getById(start.subSessionId) ?: continue
                if (child.parentId != parent.id) continue
                if (start.generation != null && start.generation != child.executionGeneration) continue
                val runs = interactionDao.recoveryRunsForChild(child.id)
                val run = if (start.runId != null) runs.find { it.id == start.runId } else runs.firstOrNull {
                    it.generation == child.executionGeneration &&
                        (start.kind == SubAgentEventType.SPAWNED.name && it.createdAt >= start.timestamp ||
                            it.createdAt <= start.timestamp && (it.endedAt == null || it.endedAt >= start.timestamp))
                }
                // 没有执行代际的旧导入日志只读；恢复账本才能证明它属于待恢复执行。
                if (start.generation == null && run == null) continue
                if (run != null && (run.generation != child.executionGeneration ||
                        run.status == "RUNNING" || run.status == "COMPLETED")) continue
                val interrupted = start.copy(
                    id = "interrupted:${start.id}",
                    kind = SubAgentEventType.INTERRUPTED.name,
                    content = detail,
                    timestamp = maxOf(System.currentTimeMillis(), start.timestamp + 1),
                    origin = "SYSTEM",
                    sourceMessageId = null,
                    instructionId = null,
                    generation = start.generation ?: child.executionGeneration,
                    parentGeneration = start.parentGeneration ?: parent.executionGeneration,
                    runId = run?.id ?: start.runId
                )
                if (interactionDao.insertIgnore(interrupted) != -1L) recovered += interrupted
                enqueueNotification(interrupted, requiresConfirmation = true)
            }
            recovered
        }
    }

    private suspend fun enqueueNotification(
        entry: SubAgentInteractionEntity,
        requiresConfirmation: Boolean = false,
        runMessageIds: Set<String> = emptySet()
    ) {
        val parentGeneration = entry.parentGeneration ?: return
        val childGeneration = entry.generation ?: return
        val parent = chatSessionDao.getById(entry.parentSessionId) ?: return
        val child = chatSessionDao.getById(entry.subSessionId)
        if (parent.executionGeneration != parentGeneration) return
        if (child != null && child.executionGeneration != childGeneration) return
        val kind = SubAgentEventType.valueOf(entry.kind)
        val item: PendingNotification
        val recipient: String
        val generation: Long
        when (kind) {
            SubAgentEventType.COMPLETED, SubAgentEventType.FAILED, SubAgentEventType.STOPPED,
            SubAgentEventType.INTERRUPTED -> {
                recipient = entry.parentSessionId
                generation = parentGeneration
                item = PendingNotification(
                    kind = AgentNotificationKind.SUBAGENT,
                    sourceId = entry.subSessionId,
                    title = entry.agentName ?: entry.title.orEmpty(),
                    outcome = when (kind) {
                        SubAgentEventType.COMPLETED -> NotificationOutcome.COMPLETED
                        SubAgentEventType.FAILED -> NotificationOutcome.FAILED
                        else -> NotificationOutcome.STOPPED
                    },
                    detail = entry.content.takeIf { kind != SubAgentEventType.COMPLETED && it.isNotBlank() },
                    resultExcerpt = entry.content.takeIf { kind == SubAgentEventType.COMPLETED && it.isNotBlank() }
                        ?.take(SubAgentDigester.EXCERPT_MAX_CHARS),
                    changedFiles = if (kind == SubAgentEventType.COMPLETED) changedFiles(entry, runMessageIds) else emptyList(),
                    eventId = entry.id
                )
            }
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                if (entry.origin == "USER" || entry.content.isBlank()) return
                val fromParent = kind == SubAgentEventType.MESSAGE_FROM_PARENT
                recipient = if (fromParent) entry.subSessionId else entry.parentSessionId
                generation = if (fromParent) childGeneration else parentGeneration
                item = PendingNotification(
                    kind = AgentNotificationKind.AGENT_MESSAGE,
                    sourceId = if (fromParent) entry.parentSessionId else entry.subSessionId,
                    title = if (fromParent) parent.title else entry.agentName ?: entry.title.orEmpty(),
                    outcome = NotificationOutcome.COMPLETED,
                    message = entry.content,
                    fromParent = fromParent,
                    eventId = entry.id
                )
            }
            else -> return
        }
        notifications.enqueue(recipient, item, generation,
            requiresConfirmation || kind == SubAgentEventType.INTERRUPTED)
    }

    private suspend fun changedFiles(entry: SubAgentInteractionEntity, messageIds: Set<String>): List<String> {
        val sources = messageIds.toList().chunked(400).flatMap {
            interactionDao.changedFileSources(entry.subSessionId, it)
        }
        return SubAgentDigester.digest(sources.map {
            AgentMessageEntity(it.id, entry.subSessionId, "TOOL", "", 0, toolName = it.toolName, toolArgs = it.toolArgs)
        }).changedFiles
    }

    private suspend fun <T> transaction(block: suspend () -> T): T =
        if (database != null) database.withTransaction { block() } else block()

    private fun existingFor(event: SubAgentEvent, entry: SubAgentInteractionEntity): SubAgentInteractionEntity {
        require(entry.parentSessionId == event.parentSessionId &&
            entry.subSessionId == event.subSessionId && entry.kind == event.type.name &&
            entry.instructionId == event.instructionId &&
            (event.generation == null || entry.generation == event.generation) &&
            (event.parentGeneration == null || entry.parentGeneration == event.parentGeneration) &&
            (event.runId == null || entry.runId == event.runId)) {
            "Subagent interaction event id was reused for a different relationship, execution, kind or instruction"
        }
        return entry
    }

    private fun AgentMessageEntity.isAssistantBody(): Boolean =
        role == "ASSISTANT" && content.isNotBlank() && !isContextSummary && !isCompactionMarker && !isContextExcluded
}

internal fun SubAgentEventType.defaultOrigin(): String = when (this) {
    SubAgentEventType.SPAWNED, SubAgentEventType.MESSAGE_FROM_PARENT -> "PARENT"
    SubAgentEventType.MESSAGE_FROM_SUB, SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> "SUB"
    SubAgentEventType.STOPPED, SubAgentEventType.DELETED, SubAgentEventType.STARTED,
    SubAgentEventType.INSTRUCTION_CANCELLED, SubAgentEventType.INTERRUPTED -> "SYSTEM"
}
