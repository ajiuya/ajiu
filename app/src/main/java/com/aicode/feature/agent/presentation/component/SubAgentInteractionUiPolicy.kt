package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import java.util.Locale

internal data class SubAgentCapsuleExpansion(
    val expanded: Boolean = false,
    val observedStatus: SubAgentInteractionStatus? = null,
) {
    fun toggle(): SubAgentCapsuleExpansion = copy(expanded = !expanded)
    fun observe(status: SubAgentInteractionStatus): SubAgentCapsuleExpansion = copy(
        expanded = expanded && !shouldAutoCollapseSubAgent(observedStatus, status),
        observedStatus = status,
    )
}

internal fun SubAgentInteractionStatus.isActiveExecution(): Boolean =
    this == SubAgentInteractionStatus.RUNNING || this == SubAgentInteractionStatus.AWAITING_CONFIRMATION

internal fun SubAgentInteractionStatus.isTerminal(): Boolean = when (this) {
    SubAgentInteractionStatus.COMPLETED, SubAgentInteractionStatus.FAILED,
    SubAgentInteractionStatus.INTERRUPTED, SubAgentInteractionStatus.DELETED -> true
    else -> false
}

internal fun shouldAutoCollapseSubAgent(
    previous: SubAgentInteractionStatus?,
    next: SubAgentInteractionStatus,
): Boolean = (previous?.isActiveExecution() == true || previous == SubAgentInteractionStatus.QUEUED) && next.isTerminal()

internal fun resolveSubAgentSummary(
    subSessionId: String,
    title: String,
    summary: SubAgentInteractionSummary?,
    legacy: SubAgentCardState?,
    fallbackStatus: String?,
    fallbackAgentName: String? = null,
): SubAgentInteractionSummary {
    fun resolveName(currentName: String?, sessionExists: Boolean = true): String? {
        val current = subAgentDisplayIdentity(currentName, subSessionId)
        val fallback = subAgentDisplayIdentity(fallbackAgentName, subSessionId)
        return when {
            sessionExists && current.kind == SubAgentIdentityKind.NAMED -> current.name
            fallback.kind == SubAgentIdentityKind.NAMED -> fallback.name
            sessionExists && current.kind == SubAgentIdentityKind.GENERIC -> currentName?.trim()
            fallback.kind == SubAgentIdentityKind.GENERIC -> fallbackAgentName?.trim()
            else -> null
        }
    }
    if (summary?.subSessionId == subSessionId) return summary.copy(
        title = summary.title.ifBlank { title },
        agentName = resolveName(summary.agentName,
            summary.sessionExists && summary.status != SubAgentInteractionStatus.DELETED),
    )
    if (legacy?.subSessionId == subSessionId) {
        val start = legacy.startedAt.takeIf { it > 0L }
        val total = legacy.elapsedMs?.takeIf { it >= 0L }
        return SubAgentInteractionSummary(
            subSessionId = subSessionId,
            title = legacy.title.ifBlank { title },
            agentName = resolveName(legacy.agentName),
            status = when (legacy.status) {
                SubAgentCardStatus.RUNNING -> SubAgentInteractionStatus.RUNNING
                SubAgentCardStatus.COMPLETED -> SubAgentInteractionStatus.COMPLETED
                SubAgentCardStatus.FAILED -> SubAgentInteractionStatus.FAILED
                SubAgentCardStatus.STOPPED -> SubAgentInteractionStatus.INTERRUPTED
            },
            startedAtMs = start,
            finishedAtMs = if (start != null && total != null && start <= Long.MAX_VALUE - total) start + total else null,
            latestPreview = legacy.excerpt.orEmpty(),
        )
    }
    val status = when (fallbackStatus?.trim()?.uppercase(Locale.ROOT)) {
        "QUEUED" -> SubAgentInteractionStatus.QUEUED
        "RUNNING" -> SubAgentInteractionStatus.RUNNING
        "AWAITING_CONFIRMATION", "WAITING_CONFIRMATION" -> SubAgentInteractionStatus.AWAITING_CONFIRMATION
        "COMPLETED" -> SubAgentInteractionStatus.COMPLETED
        "FAILED" -> SubAgentInteractionStatus.FAILED
        "INTERRUPTED", "STOPPED" -> SubAgentInteractionStatus.INTERRUPTED
        "DELETED" -> SubAgentInteractionStatus.DELETED
        else -> SubAgentInteractionStatus.HISTORY
    }
    return SubAgentInteractionSummary(subSessionId = subSessionId, title = title, agentName = resolveName(null),
        status = status, sessionExists = status != SubAgentInteractionStatus.DELETED)
}

internal enum class SubAgentIdentityKind { NAMED, GENERIC, UNKNOWN }
internal data class SubAgentDisplayIdentity(val kind: SubAgentIdentityKind, val name: String = "")

internal fun subAgentDisplayIdentity(summary: SubAgentInteractionSummary): SubAgentDisplayIdentity =
    subAgentDisplayIdentity(summary.agentName, summary.subSessionId)

internal fun subAgentDisplayIdentity(agentName: String?, subSessionId: String): SubAgentDisplayIdentity {
    val name = agentName?.trim().orEmpty()
    return when {
        name.isBlank() || isSubAgentTechnicalPlaceholder(name, subSessionId) ->
            SubAgentDisplayIdentity(SubAgentIdentityKind.UNKNOWN)
        name.equals("generic", true) || name.equals("subagent", true) ->
            SubAgentDisplayIdentity(SubAgentIdentityKind.GENERIC)
        else -> SubAgentDisplayIdentity(SubAgentIdentityKind.NAMED, name)
    }
}

internal fun subAgentVisibleTask(summary: SubAgentInteractionSummary): String = summary.title.takeUnless {
    it.isBlank() || isSubAgentTechnicalPlaceholder(it, summary.subSessionId) || it.trim() == summary.agentName?.trim()
}.orEmpty()

private fun isSubAgentTechnicalPlaceholder(value: String, childId: String): Boolean =
    value.trim() == childId || subAgentUuidPattern.containsMatchIn(value)

private val subAgentUuidPattern = Regex(
    "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])"
)

internal fun resolveSubAgentElapsedMs(summary: SubAgentInteractionSummary, nowMs: Long): Long? {
    val start = summary.startedAtMs?.takeIf { it > 0L } ?: return null
    val finish = summary.finishedAtMs
    if (finish != null) return (finish - start).takeIf { finish >= start }
    if (!summary.status.isActiveExecution()) return null
    return (nowMs - start).takeIf { nowMs >= start }
}

internal fun canRenderSubAgentToolBody(message: AgentUIMessage): Boolean {
    val name = message.toolName?.trim()?.lowercase(Locale.ROOT) ?: return false
    val leafName = name.substringAfterLast('.').substringAfterLast('/').substringAfterLast(':')
    return leafName != "task" && !message.content.contains("\"notifications\"") &&
        !message.content.contains(BACKGROUND_NOTIFICATION_PREFIX) &&
        !message.content.contains("<subagent-notification") && !message.content.contains("<agent-message")
}

internal fun isSubAgentHiddenReasoningKind(kind: String): Boolean =
    kind.trim().lowercase(Locale.ROOT) in setOf("reasoning", "thinking", "analysis", "chain_of_thought")

internal data class SubAgentTextPart(val source: String, val markdown: String)

internal data class SubAgentRecordWindow<T>(
    val records: List<T>,
    val pageFromLatest: Int,
    val pageCount: Int,
) {
    val hasOlder: Boolean get() = pageFromLatest + 1 < pageCount
    val hasNewer: Boolean get() = pageFromLatest > 0
}

internal fun <T> subAgentRecordWindow(records: List<T>, pageFromLatest: Int): SubAgentRecordWindow<T> {
    val pageSize = 4
    val pageCount = ((records.size.toLong() + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)
    val current = pageFromLatest.coerceIn(0, pageCount - 1)
    val end = (records.size - current * pageSize).coerceAtLeast(0)
    return SubAgentRecordWindow(records.subList((end - pageSize).coerceAtLeast(0), end), current, pageCount)
}

private const val SUBAGENT_TEXT_PART_CHARS = 1_200
private const val SUBAGENT_TEXT_PART_LINES = 28

// Only one finite part is composed inside the parent LazyColumn; copied source remains lossless.
internal fun subAgentTextParts(text: String): List<SubAgentTextPart> {
    if (text.isEmpty()) return listOf(SubAgentTextPart("", ""))
    val parts = ArrayList<SubAgentTextPart>()
    val buffer = StringBuilder()
    var linesInPart = 0
    var fence: String? = null
    var fenceAtStart: String? = null
    fun flush() {
        if (buffer.isEmpty()) return
        val source = buffer.toString()
        val markdown = buildString {
            fenceAtStart?.let { append(it).append('\n') }
            append(source)
            fence?.let {
                if (!source.endsWith('\n')) append('\n')
                append(it.takeWhile { ch -> ch == '`' || ch == '~' })
            }
        }
        parts += SubAgentTextPart(source, markdown)
        buffer.setLength(0)
        linesInPart = 0
        fenceAtStart = fence
    }
    var offset = 0
    while (offset < text.length) {
        val newline = text.indexOf('\n', offset)
        val end = if (newline >= 0) newline + 1 else text.length
        val line = text.substring(offset, end)
        if (buffer.isNotEmpty() &&
            (buffer.length + line.length > SUBAGENT_TEXT_PART_CHARS || linesInPart >= SUBAGENT_TEXT_PART_LINES)) flush()
        var lineOffset = 0
        while (lineOffset < line.length) {
            if (buffer.length >= SUBAGENT_TEXT_PART_CHARS) flush()
            var take = minOf(line.length - lineOffset, SUBAGENT_TEXT_PART_CHARS - buffer.length)
            if (lineOffset + take < line.length && take > 0 &&
                Character.isHighSurrogate(line[lineOffset + take - 1]) && Character.isLowSurrogate(line[lineOffset + take])) take--
            if (take == 0) {
                flush()
                continue
            }
            buffer.append(line, lineOffset, lineOffset + take)
            lineOffset += take
            if (lineOffset < line.length) flush()
        }
        linesInPart++
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
        val markerLength = if (marker != null) trimmed.takeWhile { it == marker }.length else 0
        if (markerLength >= 3) {
            val active = fence
            if (active == null) fence = trimmed.trimEnd('\r', '\n')
            else if (active.first() == marker && markerLength >= active.takeWhile { it == marker }.length &&
                trimmed.drop(markerLength).isBlank()) fence = null
        }
        offset = end
    }
    flush()
    return parts
}
