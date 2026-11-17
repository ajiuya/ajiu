package com.aicode.feature.agent.presentation

import androidx.compose.runtime.Immutable

enum class SubAgentInteractionStatus {
    QUEUED, RUNNING, AWAITING_CONFIRMATION, COMPLETED, FAILED, INTERRUPTED, DELETED, HISTORY
}

@Immutable
data class SubAgentInteractionSummary(
    val subSessionId: String,
    val title: String = "",
    val agentName: String? = null,
    val model: String? = null,
    val status: SubAgentInteractionStatus = SubAgentInteractionStatus.HISTORY,
    val exchangeCount: Int = 0,
    val toolCallCount: Int = 0,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val lastActivityAtMs: Long? = null,
    val latestPreview: String = "",
    val currentToolTitle: String? = null,
    val sessionExists: Boolean = true,
)

enum class SubAgentInteractionSpeaker { PARENT, SUBAGENT, USER, SYSTEM }

@Immutable
data class SubAgentInteractionEntry(
    val id: String,
    val speaker: SubAgentInteractionSpeaker,
    val content: String,
    val timestamp: Long,
    val kind: String,
    val sourceMessageId: String? = null,
)

@Immutable
data class SubAgentInteractionPage(
    val entries: List<SubAgentInteractionEntry> = emptyList(),
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val recoveredFromHistory: Boolean = false,
    val error: String? = null,
)

@Immutable
data class SubAgentExecutionPage(
    val messages: List<AgentUIMessage> = emptyList(),
    val streamingText: String = "",
    val runningTools: List<RunningToolOutput> = emptyList(),
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

enum class SubAgentInstructionFailure { EMPTY, SESSION_MISSING, NOT_OWNED, SEND_FAILED }

@Immutable
data class SubAgentInstructionResult(
    val sent: Boolean,
    val failure: SubAgentInstructionFailure? = null,
)
