package com.aicode.feature.agent.domain.recovery

import com.aicode.feature.agent.presentation.AgentAttachment
import kotlinx.serialization.Serializable

@Serializable
enum class RecoveryStatus {
    QUEUED, RUNNING, AWAITING_CONFIRMATION, INTERRUPTED, FAILED, COMPLETED, ABANDONED
}

@Serializable
enum class RecoveryToolStatus {
    DECLARED, WAITING_APPROVAL, STARTED, FINISHED, UNKNOWN
}

@Serializable
enum class RecoveryStage {
    NOT_STARTED, TEXT, TOOLS_NOT_EXECUTED, TOOLS_UNKNOWN, TOOLS_COMPLETED
}

@Serializable
data class RecoveryResumeSnapshot(
    val stage: RecoveryStage,
    val draftText: String,
    val sourceRunId: String,
    val sourceStep: Int = 0,
    val sourceRevision: Long = 0,
    val inputMessageExists: Boolean = false
)

@Serializable
enum class RecoveryOrigin {
    USER, CARD, GOAL, NOTIFICATION, COMMAND
}

@Serializable
data class RecoveryRequestPayload(
    val request: String,
    val projectRoot: String,
    val modelRequest: String = request,
    val currentFile: String? = null,
    val selectedCode: String? = null,
    val inputAttachments: List<AgentAttachment> = emptyList(),
    val origin: RecoveryOrigin = RecoveryOrigin.USER,
    val isAutoTrigger: Boolean = false,
    val persistUserMessage: Boolean = true,
    val subAgentInstructionId: String? = null,
    val notificationSeqs: List<Long> = emptyList()
)

@Serializable
data class RecoveryRequest(
    val id: String,
    val sessionId: String,
    val generation: Long,
    val environment: String,
    val inputMessageId: String,
    val queueOrder: Long,
    val status: RecoveryStatus,
    val activeRunId: String?,
    val continuationCount: Int,
    val continuationOutputTokens: Long,
    val continuationChars: Long,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class RecoveryRun(
    val id: String,
    val requestId: String,
    val sessionId: String,
    val generation: Long,
    val ownerFence: String,
    val status: RecoveryStatus,
    val step: Int,
    val revision: Long,
    val committedStep: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long?,
    val resumeSnapshot: RecoveryResumeSnapshot? = null
)

@Serializable
data class RecoveryStep(val step: Int, val revision: Long)

@Serializable
data class RecoveryToolCall(val toolCallId: String, val toolName: String)

data class RecoveryScope(val sessionId: String, val workspacePath: String, val generation: Long)

data class PendingRecoveryTarget(
    val requestId: String,
    val sessionId: String,
    val generation: Long,
    val activeRunId: String?,
    val unknownTools: Set<RecoveryToolCall>
)

@Serializable
data class RecoveryTool(
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val step: Int,
    val revision: Long,
    val status: RecoveryToolStatus,
    val assistantMessageId: String?,
    val resultMessageId: String?,
    val updatedAt: Long
)

@Serializable
data class RecoveryDraft(
    val runId: String,
    val step: Int,
    val revision: Long,
    val text: String,
    val updatedAt: Long
)

@Serializable
data class RecoveryItem(
    val request: RecoveryRequest,
    val payload: RecoveryRequestPayload,
    val run: RecoveryRun?,
    val draft: RecoveryDraft?,
    val unknownTools: List<RecoveryTool>,
    val pendingTools: List<RecoveryTool>,
    val holdReason: String?,
    val stage: RecoveryStage = RecoveryStage.NOT_STARTED
) {
    fun pendingTarget(): PendingRecoveryTarget = PendingRecoveryTarget(
        request.id, request.sessionId, request.generation, request.activeRunId,
        (unknownTools + pendingTools.filter { it.status == RecoveryToolStatus.STARTED })
            .mapTo(mutableSetOf()) { RecoveryToolCall(it.toolCallId, it.toolName) }
    )
}
