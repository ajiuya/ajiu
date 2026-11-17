package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.SubAgentHistoryStats
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionDao
import com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.SubAgentInteractionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject

internal data class SubAgentInteractionRuntime(
    val activeIds: Set<String> = emptySet(),
    val awaitingIds: Set<String> = emptySet(),
    val queuedIds: Set<String> = emptySet(),
    val terminalStatuses: Map<String, SubAgentInteractionStatus> = emptyMap(),
    val toolTitles: Map<String, String> = emptyMap(),
)

internal data class SubAgentInteractionFacts(
    val metadata: List<SubAgentInteractionMetadata>,
    val children: List<ChatSessionEntity>,
    val historyStats: List<SubAgentHistoryStats>,
)

class SubAgentInteractionReader @Inject constructor(
    private val interactions: SubAgentInteractionDao,
    private val sessions: ChatSessionDao,
    private val messages: AgentMessageDao,
) {
    internal fun summaryFacts(parentId: String): Flow<SubAgentInteractionFacts> = flow {
        emitAll(combine(
            interactions.observeByParent(parentId),
            sessions.getSubSessionsByParent(parentId),
            messages.observeSubAgentHistoryStats(parentId),
        ) { metadata, children, history -> SubAgentInteractionFacts(metadata, children, history) })
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun conversation(parentId: String, childId: String, limit: Int): Flow<SubAgentInteractionPage> = flow {
        val safeLimit = limit.coerceIn(PAGE_SIZE, Int.MAX_VALUE - 1)
        val legacyLimit = (safeLimit.toLong() * 3L + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        emitAll(combine(
            interactions.getByChildPaged(parentId, childId, safeLimit + 1),
            messages.observeSubAgentLegacyMessages(parentId, childId, legacyLimit),
        ) { journal, legacy ->
            val entries = (recoverLegacySubAgentInteractions(legacy) + journal.asSequence()
                .filterNot { it.kind == "STARTED" || isPrivateInteractionKind(it.kind) }
                .map(::interactionEntry).toList())
                .sortedWith(compareBy(SubAgentInteractionEntry::timestamp, SubAgentInteractionEntry::id))
            SubAgentInteractionPage(
                entries = entries.takeLast(safeLimit),
                loaded = true,
                hasMore = entries.size > safeLimit || journal.size > safeLimit || legacy.size >= legacyLimit,
                recoveredFromHistory = legacy.isNotEmpty(),
            )
        })
    }.flowOn(Dispatchers.Default).retainSubAgentPageOnError(SubAgentInteractionPage()) { previous, error ->
        previous.copy(loaded = true, error = error.message ?: error.javaClass.simpleName)
    }

    fun ownsChild(parentId: String, childId: String): Flow<Boolean> = flow {
        emitAll(sessions.getByIdFlow(childId).map { it?.parentId == parentId && parentId != childId })
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun execution(parentId: String, childId: String, limit: Int): Flow<SubAgentExecutionPage> =
        ownsChild(parentId, childId).flatMapLatest { owned ->
            if (!owned) flowOf(SubAgentExecutionPage(loaded = true)) else flow {
                val safeLimit = limit.coerceIn(PAGE_SIZE, Int.MAX_VALUE - 1)
                emitAll(messages.observeSubAgentExecutionMessages(parentId, childId, safeLimit + 1).map { rows ->
                    SubAgentExecutionPage(
                        messages = rows.takeLast(safeLimit).asSequence()
                            .filterNot { it.isCompactionMarker || it.isContextSummary }
                            .filter { it.role in setOf(MessageRole.USER.name, MessageRole.ASSISTANT.name, MessageRole.TOOL.name) }
                            .filter { it.role == MessageRole.TOOL.name || it.content.hasVisibleContent() || !it.attachmentsJson.isNullOrBlank() }
                            .map { it.toUIMessage().copy(reasoning = null) }
                            .toList(),
                        loaded = true,
                        hasMore = rows.size > safeLimit,
                    )
                })
            }
        }.flowOn(Dispatchers.Default).retainSubAgentPageOnError(SubAgentExecutionPage()) { previous, error ->
            previous.copy(loaded = true, error = error.message ?: error.javaClass.simpleName)
        }

    companion object {
        const val PAGE_SIZE = 10
    }
}

internal fun projectSubAgentInteractionSummaries(
    facts: SubAgentInteractionFacts,
    runtime: SubAgentInteractionRuntime,
): Map<String, SubAgentInteractionSummary> {
    val children = facts.children.associateBy { it.id }
    val stats = facts.historyStats.associateBy { it.sessionId }
    val byChild = facts.metadata.groupBy { it.subSessionId }
    return (children.keys + byChild.keys).associateWith { childId ->
        val child = children[childId]
        val allEvents = byChild[childId].orEmpty().sortedWith(compareBy({ it.timestamp }, { it.id }))
        val cancelledInstructions = allEvents.filter { it.kind == "INSTRUCTION_CANCELLED" }.mapNotNullTo(HashSet()) { it.instructionId }
        val events = allEvents.filterNot { it.kind == "INSTRUCTION_CANCELLED" || it.id in cancelledInstructions }
        val last = events.lastOrNull()
        val history = stats[childId]
        val startedInstructions = events.filter { it.kind == "STARTED" }.mapNotNullTo(HashSet()) { it.instructionId }
        val unstartedUserInstructions = events.any {
            it.kind == "MESSAGE_FROM_PARENT" && it.origin == "USER" && it.id !in startedInstructions
        }
        val status = when {
            child == null -> SubAgentInteractionStatus.DELETED
            childId in runtime.awaitingIds -> SubAgentInteractionStatus.AWAITING_CONFIRMATION
            childId in runtime.activeIds -> SubAgentInteractionStatus.RUNNING
            childId in runtime.queuedIds -> SubAgentInteractionStatus.QUEUED
            last?.kind == "DELETED" -> SubAgentInteractionStatus.DELETED
            unstartedUserInstructions -> SubAgentInteractionStatus.INTERRUPTED
            runtime.terminalStatuses[childId] != null -> runtime.terminalStatuses.getValue(childId)
            last?.kind == "COMPLETED" -> SubAgentInteractionStatus.COMPLETED
            last?.kind == "FAILED" -> SubAgentInteractionStatus.FAILED
            last?.kind in setOf("STOPPED", "INTERRUPTED") -> SubAgentInteractionStatus.INTERRUPTED
            last != null -> SubAgentInteractionStatus.INTERRUPTED
            else -> SubAgentInteractionStatus.HISTORY
        }
        val terminalIndex = events.indexOfLast { it.kind in TERMINAL_INTERACTION_KINDS }
        val openRun = status in LIVE_INTERACTION_STATUSES || (last != null && last.kind !in TERMINAL_INTERACTION_KINDS)
        val runEnd = if (openRun) events.size else terminalIndex + 1
        val previousTerminal = events.take(if (openRun) events.size else terminalIndex.coerceAtLeast(0))
            .indexOfLast { it.kind in TERMINAL_INTERACTION_KINDS }
        val runEvents = events.subList((previousTerminal + 1).coerceAtMost(runEnd), runEnd.coerceAtLeast(0))
        val start = runEvents.firstOrNull { it.kind == "STARTED" }
            ?: runEvents.firstOrNull { it.kind == "SPAWNED" || it.kind == "MESSAGE_FROM_PARENT" }
        val staleTerminal = runtime.terminalStatuses[childId] == status &&
            last?.kind in TERMINAL_INTERACTION_KINDS && !terminalKindMatches(status, last?.kind)
        val conclusion = if (staleTerminal || unstartedUserInstructions) "" else runEvents.lastOrNull {
            it.kind in setOf("MESSAGE_FROM_SUB", "COMPLETED", "FAILED") && it.preview.isNotBlank()
        }?.preview ?: if (events.isEmpty()) history?.lastAssistantPreview.orEmpty() else ""
        SubAgentInteractionSummary(
            subSessionId = childId,
            title = child?.title ?: events.lastOrNull { !it.title.isNullOrBlank() }?.title.orEmpty(),
            agentName = child?.subagentType?.takeIf { it.isNotBlank() }
                ?: events.lastOrNull { !it.agentName.isNullOrBlank() }?.agentName,
            model = (child?.model ?: events.lastOrNull { !it.model.isNullOrBlank() }?.model)
                ?.takeUnless { it == "inherit" || it.isBlank() },
            status = status,
            exchangeCount = allEvents.count { it.kind in EXCHANGE_INTERACTION_KINDS } +
                if (allEvents.isEmpty()) (history?.userMessages ?: 0) + (history?.assistantMessages ?: 0) else 0,
            toolCallCount = history?.toolCallCount ?: 0,
            startedAtMs = start?.timestamp?.takeUnless { staleTerminal },
            finishedAtMs = if (openRun || unstartedUserInstructions ||
                !terminalKindMatches(status, events.getOrNull(terminalIndex)?.kind)) null
                else events.getOrNull(terminalIndex)?.timestamp,
            lastActivityAtMs = listOfNotNull(allEvents.lastOrNull()?.timestamp, history?.lastActivityAtMs).maxOrNull(),
            latestPreview = conclusion,
            currentToolTitle = runtime.toolTitles[childId]?.takeIf { status in LIVE_INTERACTION_STATUSES },
            sessionExists = child != null,
        )
    }
}

internal val TERMINAL_INTERACTION_KINDS = setOf("COMPLETED", "FAILED", "STOPPED", "INTERRUPTED", "DELETED")
internal val EXCHANGE_INTERACTION_KINDS = setOf("SPAWNED", "MESSAGE_FROM_PARENT", "MESSAGE_FROM_SUB", "COMPLETED", "FAILED")
internal val LIVE_INTERACTION_STATUSES = setOf(
    SubAgentInteractionStatus.QUEUED, SubAgentInteractionStatus.RUNNING,
    SubAgentInteractionStatus.AWAITING_CONFIRMATION,
)

private fun terminalKindMatches(status: SubAgentInteractionStatus, kind: String?): Boolean =
    if (status == SubAgentInteractionStatus.INTERRUPTED) kind in setOf("STOPPED", "INTERRUPTED")
    else kind != null && kind == terminalKindForStatus(status)

private fun terminalKindForStatus(status: SubAgentInteractionStatus): String? = when (status) {
    SubAgentInteractionStatus.COMPLETED -> "COMPLETED"
    SubAgentInteractionStatus.FAILED -> "FAILED"
    SubAgentInteractionStatus.INTERRUPTED -> "STOPPED"
    SubAgentInteractionStatus.DELETED -> "DELETED"
    else -> null
}

private fun interactionEntry(row: SubAgentInteractionEntity) = SubAgentInteractionEntry(
    id = row.id,
    speaker = when {
        row.origin == "USER" -> SubAgentInteractionSpeaker.USER
        row.kind == "SPAWNED" || row.kind == "MESSAGE_FROM_PARENT" -> SubAgentInteractionSpeaker.PARENT
        row.kind == "MESSAGE_FROM_SUB" || row.kind == "COMPLETED" -> SubAgentInteractionSpeaker.SUBAGENT
        else -> SubAgentInteractionSpeaker.SYSTEM
    },
    content = row.content,
    timestamp = row.timestamp,
    kind = row.kind,
    sourceMessageId = row.sourceMessageId,
)

private val legacyInteractionJson = Json { ignoreUnknownKeys = true }
private val parentInteractionPattern = Regex("<agent-message>[\\s\\S]*?<message>([\\s\\S]*?)</message>[\\s\\S]*?</agent-message>")

internal fun recoverLegacySubAgentInteractions(rows: List<AgentMessageEntity>): List<SubAgentInteractionEntry> = buildList {
    for (row in rows) {
        if (row.isCompactionMarker || row.isContextSummary) continue
        if (row.role == MessageRole.USER.name && row.content.hasVisibleContent()) {
            val fromParent = parentInteractionPattern.findAll(row.content)
                .map { unescapeInteractionXml(it.groupValues[1]) }.toList()
            if (fromParent.isNotEmpty()) {
                fromParent.forEachIndexed { ordinal, body ->
                    add(SubAgentInteractionEntry("legacy:${row.id}:parent:$ordinal", SubAgentInteractionSpeaker.PARENT, body, row.timestamp, "HISTORY", row.id))
                }
            } else if (!row.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
                add(SubAgentInteractionEntry("legacy:${row.id}", SubAgentInteractionSpeaker.USER, row.content, row.timestamp, "HISTORY", row.id))
            }
        }
        if (row.role == MessageRole.ASSISTANT.name) {
            if (row.content.hasVisibleContent()) {
                add(SubAgentInteractionEntry("legacy:${row.id}", SubAgentInteractionSpeaker.SUBAGENT, row.content, row.timestamp, "HISTORY", row.id))
            }
            val calls = row.toolCallsJson?.let {
                runCatching { legacyInteractionJson.parseToJsonElement(it) as? JsonArray }.getOrNull()
            }
            calls?.forEachIndexed { ordinal, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                if ((call["name"] as? JsonPrimitive)?.contentOrNull != "messageParent") return@forEachIndexed
                val args = call["arguments"] as? JsonObject ?: return@forEachIndexed
                val message = (args["message"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                add(SubAgentInteractionEntry("legacy:${row.id}:sub:$ordinal", SubAgentInteractionSpeaker.SUBAGENT, message, row.timestamp, "HISTORY", row.id))
            }
        }
        if (row.role == MessageRole.TOOL.name) {
            val transport = runCatching { legacyInteractionJson.parseToJsonElement(row.content) as? JsonObject }.getOrNull()
            (transport?.get("notifications") as? JsonArray)?.forEachIndexed { ordinal, element ->
                val item = element as? JsonObject ?: return@forEachIndexed
                if ((item["kind"] as? JsonPrimitive)?.contentOrNull != "agent_message") return@forEachIndexed
                val body = (item["message"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                add(SubAgentInteractionEntry("legacy:${row.id}:parent:$ordinal", SubAgentInteractionSpeaker.PARENT, body, row.timestamp, "HISTORY", row.id))
            }
        }
    }
}

private fun <T> Flow<T>.retainSubAgentPageOnError(initial: T, onFailure: (T, Exception) -> T): Flow<T> = flow {
    var previous = initial
    try {
        collect { page -> previous = page; emit(page) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        emit(onFailure(previous, error))
    }
}

private fun isPrivateInteractionKind(kind: String): Boolean =
    kind.trim().lowercase(java.util.Locale.ROOT) in setOf("reasoning", "thinking", "analysis", "chain_of_thought")

private fun unescapeInteractionXml(value: String): String = value.replace("&lt;", "<")
    .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
