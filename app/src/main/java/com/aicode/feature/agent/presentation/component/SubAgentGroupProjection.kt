package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary

internal const val SUB_AGENT_GROUP_CONTENT_TYPE = "subagent-group"

internal data class SubAgentTurnGroup(
    val key: String,
    val turnKey: String,
    val capsules: List<SubAgentTimelineCapsule>,
    val carrierMessageIds: Set<String>,
)

internal data class SubAgentGroupProjection(
    val bodyItems: List<ChatRenderItem>,
    val footerItems: List<ChatRenderItem>,
    val capsules: List<SubAgentTimelineCapsule>,
    val representedChildIds: Set<String>,
    val carrierMessageIds: Set<String>,
    val groups: List<SubAgentTurnGroup> = emptyList(),
    val groupKeyByCarrierMessageId: Map<String, String> = emptyMap(),
) {
    val items: List<ChatRenderItem> = if (footerItems.isEmpty()) bodyItems else bodyItems + footerItems
    // 主列表在 body 与 footer 之间始终保留一个 __active__ 槽位。
    val expectedLazyItemCount: Int get() = bodyItems.size + 1 + footerItems.size
}

internal fun projectSubAgentGroup(
    items: List<ChatRenderItem>,
    sessionId: String?,
    activeTurnKey: String? = null,
    statusRanks: Map<String, Int> = subAgentGroupStatusRanks(items),
): SubAgentGroupProjection {
    val childrenByTurn = items.filter { it.subAgentCapsule != null && it.chatTurnKey != null }
        .groupBy { requireNotNull(it.chatTurnKey) }
    if (childrenByTurn.isEmpty()) {
        val body = if (items.none { it.subAgentCapsule != null }) items else items.filter { it.subAgentCapsule == null }
        return SubAgentGroupProjection(body, emptyList(), emptyList(), emptySet(), emptySet())
    }
    val groups = childrenByTurn.map { (turnKey, children) ->
        val latestByChild = children.withIndex().associate { it.value.subAgentCapsule!!.subSessionId to it.index }
        val capsules = children.filterIndexed { index, item -> latestByChild[item.subAgentCapsule!!.subSessionId] == index }
            .sortedBy { statusRanks[it.key] ?: 1 }
            .map { it.subAgentCapsule!! }
        SubAgentTurnGroup(
            key = "subagent-group:${sessionId.orEmpty().length}:${sessionId.orEmpty()}:${turnKey.length}:$turnKey",
            turnKey = turnKey,
            capsules = capsules,
            carrierMessageIds = children.flatMapTo(linkedSetOf()) { it.subAgentCarrierMessageIds + it.message.id },
        )
    }
    val groupsByTurn = groups.associateBy { it.turnKey }
    val lastIndexByTurn = items.withIndex().filter { it.value.chatTurnKey != null }
        .associate { requireNotNull(it.value.chatTurnKey) to it.index }
    fun renderGroup(group: SubAgentTurnGroup): ChatRenderItem = ChatRenderItem(
        message = childrenByTurn.getValue(group.turnKey).last().message,
        key = group.key,
        contentType = SUB_AGENT_GROUP_CONTENT_TYPE,
        chatTurnKey = group.turnKey,
    )
    val bodyItems = buildList {
        items.forEachIndexed { index, item ->
            if (item.subAgentCapsule == null) add(item)
            val turnKey = item.chatTurnKey
            if (turnKey != activeTurnKey && lastIndexByTurn[turnKey] == index) {
                groupsByTurn[turnKey]?.let { add(renderGroup(it)) }
            }
        }
    }
    val footerItems = groupsByTurn[activeTurnKey]?.let { listOf(renderGroup(it)) }.orEmpty()
    val capsules = groups.flatMap { it.capsules }
    val groupByCarrier = buildMap {
        groups.forEach { group -> group.carrierMessageIds.forEach { putIfAbsent(it, group.key) } }
    }
    return SubAgentGroupProjection(
        bodyItems = bodyItems,
        footerItems = footerItems,
        capsules = capsules,
        representedChildIds = capsules.mapTo(linkedSetOf()) { it.subSessionId },
        carrierMessageIds = groupByCarrier.keys,
        groups = groups,
        groupKeyByCarrierMessageId = groupByCarrier,
    )
}

internal fun subAgentGroupStatusRanks(
    items: List<ChatRenderItem>,
    summaries: Map<String, SubAgentInteractionSummary> = emptyMap(),
    states: Map<String, SubAgentCardState> = emptyMap(),
): Map<String, Int> = buildMap {
    for (item in items) {
        val capsule = item.subAgentCapsule ?: continue
        val status = resolveSubAgentSummary(
            subSessionId = capsule.subSessionId,
            title = capsule.title,
            summary = summaries[capsule.subSessionId],
            legacy = states[capsule.subSessionId],
            fallbackStatus = capsule.fallbackStatus,
            fallbackAgentName = capsule.fallbackAgentName,
        ).status
        put(item.key, when (status) {
            SubAgentInteractionStatus.COMPLETED -> 0
            SubAgentInteractionStatus.QUEUED, SubAgentInteractionStatus.RUNNING,
            SubAgentInteractionStatus.AWAITING_CONFIRMATION -> 2
            else -> 1
        })
    }
}

internal fun findSubAgentGroupMessageIndex(
    projection: SubAgentGroupProjection,
    messageId: String,
): Int {
    val eligibleItems = projection.items.filterNot { it.contentType == SUB_AGENT_GROUP_CONTENT_TYPE }
    val eligibleIndex = findChatMessageIndex(eligibleItems, messageId)
    val logicalIndex = if (eligibleIndex >= 0) {
        val key = eligibleItems[eligibleIndex].key
        projection.items.indexOfFirst { it.key == key }
    } else if (messageId in projection.carrierMessageIds) {
        val groupKey = projection.groupKeyByCarrierMessageId[messageId]
        projection.items.indexOfFirst { it.key == groupKey }
    } else -1
    return when {
        logicalIndex < 0 -> -1
        logicalIndex >= projection.bodyItems.size -> logicalIndex + 1
        else -> logicalIndex
    }
}

internal data class SubAgentGroupSummary(
    val workingCount: Int = 0,
    val completedCount: Int = 0,
    val awaitingConfirmationCount: Int = 0,
    val failedCount: Int = 0,
    val interruptedCount: Int = 0,
    val deletedCount: Int = 0,
    val historyCount: Int = 0,
) {
    val totalCount: Int
        get() = workingCount + completedCount + failedCount + interruptedCount + deletedCount + historyCount
}

internal fun summarizeSubAgentGroup(
    capsules: List<SubAgentTimelineCapsule>,
    summaries: Map<String, SubAgentInteractionSummary> = emptyMap(),
    states: Map<String, SubAgentCardState> = emptyMap(),
): SubAgentGroupSummary {
    val latestByChild = linkedMapOf<String, SubAgentTimelineCapsule>()
    capsules.forEach { latestByChild[it.subSessionId] = it }
    var workingCount = 0
    var completedCount = 0
    var awaitingConfirmationCount = 0
    var failedCount = 0
    var interruptedCount = 0
    var deletedCount = 0
    var historyCount = 0
    latestByChild.values.forEach { capsule ->
        val summary = resolveSubAgentSummary(
            subSessionId = capsule.subSessionId,
            title = capsule.title,
            summary = summaries[capsule.subSessionId],
            legacy = states[capsule.subSessionId],
            fallbackStatus = capsule.fallbackStatus,
            fallbackAgentName = capsule.fallbackAgentName,
        )
        when (summary.status) {
            SubAgentInteractionStatus.QUEUED, SubAgentInteractionStatus.RUNNING -> workingCount++
            SubAgentInteractionStatus.AWAITING_CONFIRMATION -> {
                workingCount++
                awaitingConfirmationCount++
            }
            SubAgentInteractionStatus.COMPLETED -> completedCount++
            SubAgentInteractionStatus.FAILED -> failedCount++
            SubAgentInteractionStatus.INTERRUPTED -> interruptedCount++
            SubAgentInteractionStatus.DELETED -> deletedCount++
            SubAgentInteractionStatus.HISTORY -> historyCount++
        }
    }
    return SubAgentGroupSummary(
        workingCount = workingCount,
        completedCount = completedCount,
        awaitingConfirmationCount = awaitingConfirmationCount,
        failedCount = failedCount,
        interruptedCount = interruptedCount,
        deletedCount = deletedCount,
        historyCount = historyCount,
    )
}
