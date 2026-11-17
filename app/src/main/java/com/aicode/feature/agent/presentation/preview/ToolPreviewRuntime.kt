package com.aicode.feature.agent.presentation.preview

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.component.isToolRunning
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ToolPreviewHistory(
    val sessionId: String?,
    val targets: List<ToolPreviewTarget>,
    val indices: Map<String, Int>,
    val finishedIds: Set<String>,
    val inputMessages: List<AgentUIMessage>? = null,
)

internal fun buildToolPreviewHistory(sessionId: String?, messages: List<AgentUIMessage>): ToolPreviewHistory {
    val byId = messages.associateBy { it.id }
    val targets = buildToolPreviewTargets(messages, emptyList(), emptyList(), sessionId).sortedWith(
        compareBy<ToolPreviewTarget> {
            val message = byId.getValue(it.id)
            message.toolStartedAtMs?.takeIf { start -> start > 0L } ?: message.timestamp
        }.thenBy { it.id },
    )
    return ToolPreviewHistory(sessionId, targets, targets.mapIndexed { index, target -> target.id to index }.toMap(),
        targets.filterNot(ToolPreviewTarget::active).mapTo(HashSet()) { it.id }, messages)
}

internal fun retainToolPreviewNavigation(
    previous: List<ToolPreviewTarget>,
    current: List<ToolPreviewTarget>,
    messages: List<AgentUIMessage>,
    running: List<RunningToolOutput>,
    preparing: List<PreparingToolPreviewSnapshot>,
    sessionId: String?,
): List<ToolPreviewTarget> {
    val byId = messages.filter { it.role == MessageRole.TOOL && !it.isCompactionFailure && !it.toolName.isNullOrBlank() }.associateBy { it.id }
    val liveIds = running.filter { it.toolName.isNotBlank() }.mapTo(HashSet()) { it.messageId }
    val draftIds = preparing.filter { it.name.isNotBlank() }.mapTo(HashSet()) { it.id }
    val ids = byId.keys + liveIds + draftIds
    val remaining = current.filter { it.sourceSessionId == sessionId && it.id in ids }.associateByTo(LinkedHashMap()) { it.id }
    val retained = previous.filter { it.sourceSessionId == sessionId && it.id in ids }.map { remaining.remove(it.id) ?: it } + remaining.values
    val known = retained.mapTo(HashSet()) { it.id }
    // Only navigation metadata is needed here; the selected call is resolved from current input.
    val added = byId.values.filter { known.add(it.id) }.map { message ->
        ToolPreviewTarget(message.id, message.toolName.orEmpty(), message.toolArgs.orEmpty(), message.content,
            if (message.isError) ToolPreviewStage.FAILED else ToolPreviewStage.COMPLETED,
            actionTitle = message.toolActionTitle, sourceSessionId = sessionId)
    }
    return (retained + added).map { target ->
        val message = byId[target.id]
        val stage = when {
            message != null && !message.isToolRunning(null) -> if (message.isError) ToolPreviewStage.FAILED else ToolPreviewStage.COMPLETED
            target.id in liveIds || message != null -> ToolPreviewStage.RUNNING
            else -> ToolPreviewStage.PREPARING
        }
        if (target.stage == stage) target else target.copy(stage = stage)
    }
}

internal fun currentToolPreviewTarget(
    previous: ToolPreviewTarget,
    messages: List<AgentUIMessage>,
    running: List<RunningToolOutput>,
    preparing: List<PreparingToolPreviewSnapshot>,
    sessionId: String?,
): ToolPreviewTarget? {
    if (previous.sourceSessionId != sessionId) return null
    val message = messages.firstOrNull { it.id == previous.id }
    val live = running.firstOrNull { it.messageId == previous.id }
    val draft = preparing.firstOrNull { it.id == previous.id }
    return buildToolPreviewTargets(listOfNotNull(message), listOfNotNull(live), listOfNotNull(draft), sessionId).firstOrNull()
}

internal fun overlayToolPreviewRunning(history: ToolPreviewHistory, running: List<RunningToolOutput>): List<ToolPreviewTarget> {
    if (running.isEmpty()) return history.targets
    val targets = history.targets.toMutableList()
    val seen = HashSet<String>()
    for (tool in running) {
        if (tool.toolName.isBlank() || tool.messageId in history.finishedIds || !seen.add(tool.messageId)) continue
        val index = history.indices[tool.messageId]
        val previous = index?.let(targets::get)
        val target = ToolPreviewTarget(tool.messageId, tool.toolName,
            tool.toolArgs.ifBlank { previous?.arguments.orEmpty() }, tool.text, ToolPreviewStage.RUNNING,
            actionTitle = previous?.actionTitle, sourceSessionId = history.sessionId)
        if (index == null) targets += target else targets[index] = target
    }
    return targets
}

internal fun overlayToolPreviewPreparing(targets: List<ToolPreviewTarget>, preparing: List<PreparingToolPreviewSnapshot>, sessionId: String?): List<ToolPreviewTarget> {
    if (preparing.isEmpty()) return targets
    val ids = targets.mapTo(HashSet()) { it.id }
    return targets + preparing.filter { it.name.isNotBlank() && ids.add(it.id) }.map {
        ToolPreviewTarget(it.id, it.name, it.accumulated, "", ToolPreviewStage.PREPARING, sourceSessionId = sessionId)
    }
}

internal data class RunningToolPreviewKey(val messageId: String, val toolName: String, val toolArgs: String)
internal fun runningToolPreviewKeysOf(tools: List<RunningToolOutput>): List<RunningToolPreviewKey> =
    tools.map { RunningToolPreviewKey(it.messageId, it.toolName, it.toolArgs) }

/** Identity changes are immediate; continuous output is bounded-latency, never debounce-starved. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<RunningToolOutput>>.coalesceToolPreviewContent(intervalMs: Long = 500L): Flow<List<RunningToolOutput>> = channelFlow {
    require(intervalMs > 0)
    val lock = Mutex()
    var keys: List<RunningToolPreviewKey>? = null
    var pending: List<RunningToolOutput>? = null
    var flush: Job? = null
    collect { next -> lock.withLock {
        val nextKeys = runningToolPreviewKeysOf(next)
        if (nextKeys != keys) {
            flush?.cancel(); flush = null; pending = null; keys = nextKeys
            send(next)
        } else {
            pending = next
            if (flush == null) flush = launch {
                delay(intervalMs)
                lock.withLock { pending?.let { send(it) }; pending = null; flush = null }
            }
        }
    } }
}
