package com.aicode.feature.agent.presentation

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

class SubAgentInstructionGateway @Inject constructor(
    private val sessions: SessionUseCase,
    private val events: SubAgentEventBus
) {
    suspend fun send(
        parentId: String?,
        childId: String,
        text: String,
        enqueueUserRequest: (childId: String, body: String, workspacePath: String, instructionId: String) -> Unit
    ): SubAgentInstructionResult {
        coroutineContext.ensureActive()
        val body = text.trim()
        if (body.isEmpty()) return SubAgentInstructionResult(false, SubAgentInstructionFailure.EMPTY)
        if (parentId == null) return SubAgentInstructionResult(false, SubAgentInstructionFailure.SESSION_MISSING)
        return try {
            val child = sessions.getSessionById(childId)
                ?: return SubAgentInstructionResult(false, SubAgentInstructionFailure.SESSION_MISSING)
            val parent = sessions.getSessionById(parentId)
                ?: return SubAgentInstructionResult(false, SubAgentInstructionFailure.SESSION_MISSING)
            if (child.parentId != parentId || child.workspacePath != parent.workspacePath) {
                return SubAgentInstructionResult(false, SubAgentInstructionFailure.NOT_OWNED)
            }
            coroutineContext.ensureActive()
            // 接受后必须完成持久化与入队，不能随输入组件离开 composition 而丢失半次接收。
            withContext(NonCancellable) {
                val accepted = SubAgentEvent(
                    subSessionId = childId,
                    parentSessionId = parentId,
                    type = SubAgentEventType.MESSAGE_FROM_PARENT,
                    detail = body,
                    origin = "USER"
                )
                events.recordForDisplay(accepted)
                enqueueUserRequest(childId, body, child.workspacePath, accepted.eventId)
            }
            SubAgentInstructionResult(true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            FileLogger.e("SubAgentInstruction", "Could not accept user instruction: child=$childId", error)
            SubAgentInstructionResult(false, SubAgentInstructionFailure.SEND_FAILED)
        }
    }
}
