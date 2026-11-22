package com.aicode.feature.agent.data.local.dao

import com.aicode.feature.agent.domain.tool.toTransportString

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.data.local.entity.RecoveryDraftEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRequestEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.data.local.entity.RecoveryToolEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.recovery.PendingRecoveryTarget
import com.aicode.feature.agent.domain.recovery.RecoveryResumeSnapshot
import com.aicode.feature.agent.domain.recovery.RecoveryScope
import com.aicode.feature.agent.domain.recovery.RecoveryStage
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.recovery.RecoveryToolCall
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Dao
interface AgentRecoveryDao {
    @Query("SELECT id, executionGeneration, autoDispatchBlockedReason FROM chat_sessions WHERE id = :sessionId")
    suspend fun sessionGate(sessionId: String): RecoverySessionGate?

    @Query("SELECT * FROM agent_recovery_requests WHERE id = :requestId")
    suspend fun requestRow(requestId: String): RecoveryRequestEntity?

    @Query("SELECT * FROM agent_recovery_runs WHERE id = :runId")
    suspend fun runRow(runId: String): RecoveryRunEntity?

    @Query("SELECT * FROM agent_recovery_runs WHERE requestId = :requestId ORDER BY createdAt, id")
    suspend fun runsForRequest(requestId: String): List<RecoveryRunEntity>

    @Query("SELECT * FROM agent_recovery_tools WHERE runId IN (SELECT id FROM agent_recovery_runs WHERE requestId = :requestId) ORDER BY runId, step, toolCallId")
    suspend fun toolsForRequest(requestId: String): List<RecoveryToolEntity>

    @Query("SELECT * FROM agent_recovery_tools WHERE runId = :runId ORDER BY step, toolCallId")
    suspend fun toolsForRun(runId: String): List<RecoveryToolEntity>

    @Query("SELECT * FROM agent_recovery_tools WHERE runId = :runId AND toolCallId = :toolCallId")
    suspend fun toolRow(runId: String, toolCallId: String): RecoveryToolEntity?

    @Query("SELECT * FROM agent_recovery_drafts WHERE runId = :runId")
    suspend fun draftRow(runId: String): RecoveryDraftEntity?

    @Query("SELECT * FROM agent_messages WHERE id = :messageId")
    suspend fun messageRow(messageId: String): AgentMessageEntity?

    @Query("""
        UPDATE agent_notification_outbox SET deliveredAt = :now
        WHERE sessionId = :sessionId AND seq IN (:seqs) AND deliveredAt IS NULL AND retiredAt IS NULL
          AND generation = (SELECT executionGeneration FROM chat_sessions WHERE id = :sessionId)
    """)
    suspend fun markRecoveryNotificationsDelivered(sessionId: String, seqs: List<Long>, now: Long): Int

    @Query("""
        UPDATE agent_notification_outbox SET retiredAt = :now
        WHERE sessionId = :sessionId AND deliveredAt IS NULL AND retiredAt IS NULL
    """)
    suspend fun retireRecoveryNotifications(sessionId: String, now: Long): Int

    @Transaction
    @Query("""
        SELECT q.* FROM agent_recovery_requests q JOIN chat_sessions s ON s.id = q.sessionId
        WHERE q.sessionId = :sessionId AND q.generation = s.executionGeneration
          AND q.status NOT IN ('COMPLETED', 'ABANDONED')
        ORDER BY q.queueOrder, q.id
    """)
    fun observePendingRows(sessionId: String): Flow<List<RecoveryPendingRows>>

    @Transaction
    @Query("""
        SELECT q.* FROM agent_recovery_requests q
        JOIN chat_sessions s ON s.id = q.sessionId AND s.executionGeneration = q.generation
        JOIN chat_sessions root ON root.id = :scopeId AND root.executionGeneration = :scopeGeneration
        WHERE (s.id = root.id OR s.parentId = root.id)
          AND s.workspacePath = :workspacePath AND root.workspacePath = :workspacePath
          AND q.status IN ('AWAITING_CONFIRMATION', 'INTERRUPTED', 'FAILED')
        ORDER BY CASE WHEN q.sessionId = :scopeId THEN 0 ELSE 1 END, q.sessionId, q.queueOrder, q.id
    """)
    fun observePendingScopeRows(scopeId: String, scopeGeneration: Long, workspacePath: String): Flow<List<RecoveryPendingRows>>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM chat_sessions s
            JOIN chat_sessions root ON root.id = :scopeId AND root.executionGeneration = :scopeGeneration
            WHERE s.id = :sessionId AND s.executionGeneration = :generation
              AND (s.id = root.id OR s.parentId = root.id)
              AND s.workspacePath = :workspacePath AND root.workspacePath = :workspacePath)
    """)
    suspend fun isInRecoveryScope(scopeId: String, scopeGeneration: Long, workspacePath: String, sessionId: String, generation: Long): Boolean

    @Query("""
        SELECT r.* FROM agent_recovery_runs r
        JOIN agent_recovery_requests q ON q.id = r.requestId AND q.activeRunId = r.id
        JOIN chat_sessions s ON s.id = r.sessionId
        WHERE r.id = :runId AND r.ownerFence = :ownerFence
          AND r.status = 'RUNNING' AND q.status = 'RUNNING'
          AND r.sessionId = q.sessionId AND r.generation = q.generation
          AND r.generation = s.executionGeneration AND s.autoDispatchBlockedReason IS NULL
    """)
    suspend fun ownedRunning(runId: String, ownerFence: String): RecoveryRunEntity?

    @Query("SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM agent_recovery_requests WHERE sessionId = :sessionId AND generation = :generation")
    suspend fun nextQueueOrder(sessionId: String, generation: Long): Long

    @Query("SELECT EXISTS(SELECT 1 FROM agent_recovery_runs WHERE sessionId = :sessionId AND generation = :generation AND status = 'RUNNING')")
    suspend fun hasRunning(sessionId: String, generation: Long): Boolean

    @Query("SELECT * FROM agent_recovery_requests WHERE status NOT IN ('COMPLETED', 'ABANDONED')")
    suspend fun outstandingRequests(): List<RecoveryRequestEntity>

    @Query("SELECT * FROM agent_recovery_requests WHERE sessionId = :sessionId")
    suspend fun requestsForSession(sessionId: String): List<RecoveryRequestEntity>

    @Query("""
        SELECT id FROM agent_recovery_requests
        WHERE sessionId = :sessionId AND generation = :generation
          AND status IN ('AWAITING_CONFIRMATION', 'INTERRUPTED', 'FAILED')
        ORDER BY queueOrder, id LIMIT 1
    """)
    suspend fun firstPendingRequestId(sessionId: String, generation: Long): String?

    @Query("""
        SELECT q.* FROM agent_recovery_requests q
        JOIN chat_sessions s ON s.id = q.sessionId AND s.executionGeneration = q.generation
        WHERE q.sessionId = :sessionId
        ORDER BY q.queueOrder, q.id
    """)
    fun observeRequestsForSession(sessionId: String): Flow<List<RecoveryRequestEntity>>

    @Query("""
        UPDATE agent_messages SET toolStartedAtMs = COALESCE(toolStartedAtMs, :startedAtMs),
            toolDurationMs = COALESCE(toolDurationMs, :durationMs)
        WHERE sessionId = :sessionId AND id = 'tool_' || :callId AND toolCallId = :callId AND role = 'TOOL'
    """)
    suspend fun updateRecoveryToolTimingRow(sessionId: String, callId: String, startedAtMs: Long?, durationMs: Long?): Int

    @Query("UPDATE agent_messages SET turnDurationMs = COALESCE(turnDurationMs, :durationMs) WHERE sessionId = :sessionId AND id = :messageId AND role = 'ASSISTANT'")
    suspend fun updateRecoveryTurnDurationRow(sessionId: String, messageId: String, durationMs: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM todo_items WHERE id = :itemId AND sessionId != :sessionId)")
    suspend fun todoOwnedByAnotherSession(itemId: String, sessionId: String): Boolean

    @Query("DELETE FROM todo_items WHERE sessionId = :sessionId")
    suspend fun deleteRecoveryTodoRows(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun storeRecoveryTodoRows(items: List<TodoItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRequestRow(request: RecoveryRequestEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRunRow(run: RecoveryRunEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertToolRows(tools: List<RecoveryToolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun storeDraftRow(draft: RecoveryDraftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun storeMessageRow(message: AgentMessageEntity)

    @Update
    suspend fun updateRequestRow(request: RecoveryRequestEntity)

    @Update
    suspend fun updateRunRow(run: RecoveryRunEntity)

    @Update
    suspend fun updateToolRow(tool: RecoveryToolEntity)

    @Query("DELETE FROM agent_recovery_drafts WHERE runId = :runId")
    suspend fun deleteDraftRow(runId: String)

    @Query("DELETE FROM agent_recovery_tools WHERE runId IN (SELECT id FROM agent_recovery_runs WHERE sessionId = :sessionId)")
    suspend fun deleteSessionToolRows(sessionId: String)

    @Query("DELETE FROM agent_recovery_drafts WHERE runId IN (SELECT id FROM agent_recovery_runs WHERE sessionId = :sessionId)")
    suspend fun deleteSessionDraftRows(sessionId: String)

    @Query("DELETE FROM agent_recovery_runs WHERE sessionId = :sessionId")
    suspend fun deleteSessionRunRows(sessionId: String)

    @Query("DELETE FROM agent_recovery_requests WHERE sessionId = :sessionId")
    suspend fun deleteSessionRequestRows(sessionId: String)

    @Query("DELETE FROM agent_recovery_tools WHERE runId = :runId AND step = :step AND assistantMessageId IS NULL AND status = 'DECLARED'")
    suspend fun deleteUncommittedToolRows(runId: String, step: Int)

    @Query("UPDATE chat_sessions SET autoDispatchBlockedReason = :reason, executionGeneration = executionGeneration + :generationBump WHERE id = :sessionId")
    suspend fun updateSessionGate(sessionId: String, reason: String?, generationBump: Int): Int

    @Transaction
    suspend fun replaceRecoveryTodos(runId: String, ownerFence: String, items: List<TodoItemEntity>): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (items.any { it.sessionId != run.sessionId } || items.map { it.id }.distinct().size != items.size) return false
        for (item in items) {
            if (todoOwnedByAnotherSession(item.id, run.sessionId)) return false
        }
        deleteRecoveryTodoRows(run.sessionId)
        if (items.isNotEmpty()) storeRecoveryTodoRows(items)
        return true
    }

    @Transaction
    suspend fun enqueueRequest(request: RecoveryRequestEntity): RecoveryRequestEntity? {
        val session = sessionGate(request.sessionId) ?: return null
        val existing = requestRow(request.id)
        if (existing != null) {
            require(existing.sessionId == request.sessionId && existing.generation == session.executionGeneration &&
                existing.environment == request.environment && existing.payloadJson == request.payloadJson) {
                "Recovery request ID is already in use"
            }
            return existing
        }
        val input = messageRow(request.inputMessageId)
        require(input == null || input.sessionId == request.sessionId && input.role == "USER") {
            "Recovery input message ID is already in use"
        }
        val stored = request.copy(
            generation = session.executionGeneration,
            queueOrder = nextQueueOrder(request.sessionId, session.executionGeneration),
            status = if (session.autoDispatchBlockedReason == null) "QUEUED" else "AWAITING_CONFIRMATION"
        )
        insertRequestRow(stored)
        return stored
    }

    @Transaction
    suspend fun updateQueuedRequest(requestId: String, payloadJson: String, now: Long): Boolean {
        val request = requestRow(requestId) ?: return false
        val gate = sessionGate(request.sessionId) ?: return false
        if (request.generation != gate.executionGeneration || gate.autoDispatchBlockedReason != null || request.status != "QUEUED") return false
        updateRequestRow(request.copy(payloadJson = payloadJson, updatedAt = now))
        return true
    }

    @Transaction
    suspend fun reorderQueuedRequests(sessionId: String, requestIds: List<String>, now: Long): Boolean {
        val gate = sessionGate(sessionId) ?: return false
        if (gate.autoDispatchBlockedReason != null || requestIds.distinct().size != requestIds.size) return false
        val queued = requestsForSession(sessionId).filter { it.generation == gate.executionGeneration && it.status == "QUEUED" }
        if (queued.map { it.id }.toSet() != requestIds.toSet()) return false
        val slots = queued.map { it.queueOrder }.sorted()
        val rows = queued.associateBy { it.id }
        for ((index, id) in requestIds.withIndex()) {
            updateRequestRow(rows.getValue(id).copy(queueOrder = -index.toLong() - 1, updatedAt = now))
        }
        for ((index, id) in requestIds.withIndex()) {
            updateRequestRow(rows.getValue(id).copy(queueOrder = slots[index], updatedAt = now))
        }
        return true
    }

    @Transaction
    suspend fun claimRequest(
        requestId: String,
        environment: String,
        confirmed: Boolean,
        runId: String,
        ownerFence: String,
        now: Long
    ): RecoveryRunEntity? {
        val request = requestRow(requestId) ?: return null
        val session = sessionGate(request.sessionId) ?: return null
        if (session.executionGeneration != request.generation || session.autoDispatchBlockedReason != null ||
            request.environment != environment || hasRunning(request.sessionId, request.generation)) return null
        if (request.status != "QUEUED" &&
            (!confirmed || request.status !in listOf("AWAITING_CONFIRMATION", "INTERRUPTED", "FAILED"))) return null
        if (!confirmed && requestsForSession(request.sessionId).any {
            it.generation == request.generation && it.queueOrder < request.queueOrder &&
                it.status in listOf("QUEUED", "RUNNING")
        }) return null
        val previous = request.activeRunId?.let { runRow(it) }?.takeIf {
            it.requestId == request.id && it.sessionId == request.sessionId && it.generation == request.generation
        }
        val snapshot = previous?.let {
            it.recoverySnapshot(draftRow(it.id), toolsForRequest(request.id), messageRow(request.inputMessageId) != null)
        }
        val run = RecoveryRunEntity(
            id = runId, requestId = request.id, sessionId = request.sessionId, generation = request.generation,
            ownerFence = ownerFence, status = "RUNNING", step = 0, revision = 0, committedStep = 0,
            createdAt = now, updatedAt = now, endedAt = null,
            resumeSnapshotJson = snapshot?.let { Json.encodeToString(it) }
        )
        insertRunRow(run)
        updateRequestRow(request.copy(status = "RUNNING", activeRunId = run.id, updatedAt = now))
        return run
    }

    @Transaction
    suspend fun claimPendingRecovery(
        scope: RecoveryScope,
        target: PendingRecoveryTarget,
        environment: String,
        unknownToolsVerified: Boolean,
        runId: String,
        ownerFence: String,
        now: Long
    ): RecoveryRunEntity? {
        val request = pendingRequestInScope(scope, target) ?: return null
        val session = sessionGate(target.sessionId) ?: return null
        if (request.environment != environment || session.autoDispatchBlockedReason == "PLAN_REJECTED" ||
            hasRunning(target.sessionId, target.generation)) return null
        val unknown = toolsForRequest(request.id)
            .filter { it.status == "UNKNOWN" || it.status == "STARTED" && it.assistantMessageId != null }
            .mapTo(mutableSetOf()) { RecoveryToolCall(it.toolCallId, it.toolName) }
        if (unknown != target.unknownTools || unknown.isNotEmpty() && !unknownToolsVerified) return null
        if (session.autoDispatchBlockedReason != null) updateSessionGate(target.sessionId, null, 0)
        return claimRequest(request.id, environment, true, runId, ownerFence, now)
    }

    private suspend fun pendingRequestInScope(scope: RecoveryScope, target: PendingRecoveryTarget): RecoveryRequestEntity? {
        val request = requestRow(target.requestId) ?: return null
        if (request.sessionId != target.sessionId || request.generation != target.generation ||
            request.activeRunId != target.activeRunId || request.status !in listOf("AWAITING_CONFIRMATION", "INTERRUPTED", "FAILED")) return null
        if (!isInRecoveryScope(scope.sessionId, scope.generation, scope.workspacePath, target.sessionId, target.generation)) return null
        if (firstPendingRequestId(target.sessionId, target.generation) != request.id) return null
        return request
    }

    @Transaction
    suspend fun abandonScopedRecovery(scope: RecoveryScope, target: PendingRecoveryTarget, now: Long): Boolean {
        val request = pendingRequestInScope(scope, target) ?: return false
        return abandonPendingRecoveryRequest(request.id, target.sessionId, now)
    }

    @Transaction
    suspend fun awaitRecoveryConfirmation(requestId: String, now: Long): Boolean {
        val request = requestRow(requestId) ?: return false
        val session = sessionGate(request.sessionId) ?: return false
        if (request.generation != session.executionGeneration || request.status != "QUEUED") return false
        updateRequestRow(request.copy(status = "AWAITING_CONFIRMATION", updatedAt = now))
        return true
    }

    @Transaction
    suspend fun initializeRecovery(ownerFence: String, now: Long) {
        for (request in outstandingRequests()) {
            val session = sessionGate(request.sessionId)
            if (session == null || session.executionGeneration != request.generation) {
                retireRequest(request, "ABANDONED", now)
                continue
            }
            val active = request.activeRunId?.let { runRow(it) }
            if (active?.status == "RUNNING" && active.ownerFence == ownerFence) continue
            for (run in runsForRequest(request.id)) {
                if (run.status == "RUNNING") {
                    updateRunRow(run.copy(status = "INTERRUPTED", updatedAt = now, endedAt = now))
                }
                for (tool in toolsForRun(run.id)) {
                    if (tool.status == "STARTED") updateToolRow(tool.copy(status = "UNKNOWN", updatedAt = now))
                }
            }
            updateRequestRow(request.copy(
                status = if (request.status == "RUNNING") "INTERRUPTED" else if (request.status == "QUEUED")
                    "AWAITING_CONFIRMATION" else request.status,
                updatedAt = now
            ))
        }
    }

    @Transaction
    suspend fun beginRecoveryStep(runId: String, ownerFence: String, now: Long): RecoveryStep? {
        val run = ownedRunning(runId, ownerFence) ?: return null
        if (run.step != run.committedStep || toolsForRequest(run.requestId).any {
            it.assistantMessageId != null && it.status != "FINISHED"
        }) return null
        val marker = RecoveryStep(run.step + 1, 0)
        updateRunRow(run.copy(step = marker.step, revision = marker.revision, updatedAt = now))
        deleteDraftRow(run.id)
        return marker
    }

    @Transaction
    suspend fun restartRecoveryStream(runId: String, ownerFence: String, marker: RecoveryStep, now: Long): RecoveryStep? {
        val run = ownedRunning(runId, ownerFence) ?: return null
        if (!run.matches(marker) || run.committedStep >= marker.step) return null
        val next = marker.copy(revision = marker.revision + 1)
        deleteUncommittedToolRows(run.id, marker.step)
        deleteDraftRow(run.id)
        updateRunRow(run.copy(revision = next.revision, updatedAt = now))
        return next
    }

    @Transaction
    suspend fun saveRecoveryDraft(
        runId: String,
        ownerFence: String,
        marker: RecoveryStep,
        text: String,
        now: Long,
        minimumIntervalMs: Long,
        maximumBytes: Int
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (!run.matches(marker) || run.committedStep >= marker.step || text.toByteArray(Charsets.UTF_8).size > maximumBytes) return false
        val previous = draftRow(runId)
        if (previous != null && previous.step == marker.step && previous.revision == marker.revision &&
            (previous.text == text || now - previous.updatedAt < minimumIntervalMs)) return false
        storeDraftRow(RecoveryDraftEntity(runId, marker.step, marker.revision, text, now))
        return true
    }

    @Transaction
    suspend fun declareRecoveryTools(
        runId: String,
        ownerFence: String,
        marker: RecoveryStep,
        calls: List<RecoveryToolCall>,
        now: Long
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (!run.matches(marker) || run.committedStep >= marker.step ||
            calls.map { it.toolCallId }.distinct().size != calls.size ||
            calls.any { it.toolCallId.isBlank() || it.toolName.isBlank() }) return false
        val existing = toolsForRun(runId).filter { it.step == marker.step }
        if (existing.isNotEmpty()) return existing.map { RecoveryToolCall(it.toolCallId, it.toolName) }.toSet() ==
            calls.toSet() && existing.all { it.revision == marker.revision && it.status == "DECLARED" }
        val previousCalls = toolsForRequest(run.requestId).map { it.toolCallId }.toSet()
        for (call in calls) {
            if (call.toolCallId in previousCalls || messageRow("tool_${call.toolCallId}") != null) return false
        }
        insertToolRows(calls.map {
            RecoveryToolEntity(runId, it.toolCallId, it.toolName, marker.step, marker.revision,
                "DECLARED", null, null, now)
        })
        return true
    }

    @Transaction
    suspend fun markRecoveryTool(
        runId: String,
        ownerFence: String,
        toolCallId: String,
        started: Boolean,
        now: Long
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        val tool = toolRow(runId, toolCallId) ?: return false
        if (tool.step != run.step || tool.revision != run.revision || run.committedStep != tool.step ||
            tool.assistantMessageId == null || tool.status !in listOf("DECLARED", "WAITING_APPROVAL")) return false
        val assistant = messageRow(tool.assistantMessageId) ?: return false
        if (assistant.sessionId != run.sessionId || assistant.role != "ASSISTANT") return false
        if (!started && tool.status == "WAITING_APPROVAL") return true
        updateToolRow(tool.copy(status = if (started) "STARTED" else "WAITING_APPROVAL", updatedAt = now))
        return true
    }

    @Transaction
    suspend fun commitRecoveryInput(
        runId: String,
        ownerFence: String,
        message: AgentMessageEntity,
        notificationSeqs: List<Long> = emptyList()
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        val request = requestRow(run.requestId) ?: return false
        val isResume = message.id == "resume_${run.id}" && runsForRequest(request.id).any { it.id != run.id }
        if (message.sessionId != run.sessionId || message.role != "USER" ||
            message.id != request.inputMessageId && !isResume) return false
        val existing = messageRow(message.id)
        if (existing != null) {
            if (existing.sessionId != message.sessionId || existing.role != "USER") return false
        } else {
            storeMessageRow(if (isResume) message.copy(
                continuationOfMessageId = request.inputMessageId,
                continuationDraft = run.resumeSnapshot()?.takeIf { run.committedStep == 0 }?.draftText?.takeIf { it.isNotEmpty() }
            ) else message)
        }
        if (notificationSeqs.isNotEmpty()) markRecoveryNotificationsDelivered(run.sessionId, notificationSeqs, System.currentTimeMillis())
        return true
    }

    @Transaction
    suspend fun commitRecoverySupplementalInput(runId: String, ownerFence: String, message: AgentMessageEntity): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (message.sessionId != run.sessionId || message.role != "USER" || message.isCompactionMarker ||
            message.isContextSummary || message.modelInputSnapshotJson != null || messageRow(message.id) != null) return false
        storeMessageRow(message)
        return true
    }

    @Transaction
    suspend fun freezeRecoveryInputSnapshot(runId: String, ownerFence: String, snapshotJson: String, messageId: String? = null): String? {
        val run = ownedRunning(runId, ownerFence) ?: return null
        val request = requestRow(run.requestId) ?: return null
        val inputId = messageId ?: request.inputMessageId
        if (inputId != request.inputMessageId && (inputId != "resume_${run.id}" ||
                runsForRequest(request.id).none { it.id != run.id })) return null
        val message = messageRow(inputId) ?: return null
        if (message.sessionId != run.sessionId || message.role != "USER") return null
        message.modelInputSnapshotJson?.let { return it }
        storeMessageRow(message.copy(modelInputSnapshotJson = snapshotJson))
        return snapshotJson
    }

    @Transaction
    suspend fun commitRecoveryAssistant(
        runId: String,
        ownerFence: String,
        marker: RecoveryStep,
        message: AgentMessageEntity,
        now: Long
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (!run.matches(marker) || marker.step <= 0 || message.sessionId != run.sessionId || message.role != "ASSISTANT") return false
        if (run.committedStep == marker.step) return messageRow(message.id) == message
        if (run.committedStep > marker.step || messageRow(message.id) != null) return false
        val calls = if (message.toolCallsJson == null) emptyList() else
            runCatching { Json.decodeFromString<List<ToolCall>>(message.toolCallsJson) }.getOrNull() ?: return false
        if (calls.map { it.id }.distinct().size != calls.size || calls.any { it.id.isBlank() || it.name.isBlank() }) return false
        if (!declareRecoveryTools(runId, ownerFence, marker, calls.map { RecoveryToolCall(it.id, it.name) }, now)) return false
        val tools = toolsForRun(runId).filter { it.step == marker.step }
        storeMessageRow(message)
        for (tool in tools) updateToolRow(tool.copy(assistantMessageId = message.id, updatedAt = now))
        updateRunRow(run.copy(
            committedStep = marker.step, updatedAt = now,
            resumeSnapshotJson = run.resumeSnapshot()?.copy(draftText = "")?.let { Json.encodeToString(it) }
        ))
        deleteDraftRow(runId)
        return true
    }

    @Transaction
    suspend fun commitRecoveryToolPlaceholder(runId: String, ownerFence: String, message: AgentMessageEntity): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        val callId = message.toolCallId ?: return false
        if (message.sessionId != run.sessionId || message.role != "TOOL" || message.id != "tool_$callId") return false
        val tool = toolRow(runId, callId) ?: return false
        if (tool.assistantMessageId == null || tool.toolName != message.toolName ||
            tool.status !in listOf("DECLARED", "WAITING_APPROVAL", "STARTED")) return false
        val existing = messageRow(message.id)
        if (existing != null && (existing.sessionId != message.sessionId || existing.role != message.role ||
                existing.toolCallId != message.toolCallId)) return false
        storeMessageRow(message.copy(
            timestamp = existing?.timestamp ?: message.timestamp,
            toolActionTitle = existing?.toolActionTitle ?: message.toolActionTitle,
            toolStartedAtMs = existing?.toolStartedAtMs ?: message.toolStartedAtMs,
            toolDurationMs = existing?.toolDurationMs ?: message.toolDurationMs
        ))
        return true
    }

    @Transaction
    suspend fun updateRecoveryToolTiming(
        runId: String, ownerFence: String, callId: String, startedAtMs: Long?, durationMs: Long?
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        val tool = toolRow(runId, callId) ?: return false
        if (tool.assistantMessageId == null) return false
        return updateRecoveryToolTimingRow(run.sessionId, callId, startedAtMs, durationMs) == 1
    }

    @Transaction
    suspend fun updateRecoveryTurnDuration(runId: String, ownerFence: String, messageId: String, durationMs: Long): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        return updateRecoveryTurnDurationRow(run.sessionId, messageId, durationMs) == 1
    }

    @Transaction
    suspend fun commitRecoveryToolResult(
        runId: String,
        ownerFence: String,
        message: AgentMessageEntity,
        now: Long,
        notificationSeqs: List<Long> = emptyList()
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        val callId = message.toolCallId ?: return false
        if (message.sessionId != run.sessionId || message.role != "TOOL" || message.id != "tool_$callId") return false
        val tool = toolsForRequest(run.requestId).singleOrNull { it.toolCallId == callId } ?: return false
        if (tool.assistantMessageId == null || message.toolName != tool.toolName) return false
        val assistant = messageRow(tool.assistantMessageId) ?: return false
        if (assistant.sessionId != run.sessionId || assistant.role != "ASSISTANT") return false
        if (tool.status != "STARTED" && !message.isError && tool.status != "FINISHED") return false
        if (tool.runId != runId && !message.isError) return false
        val existing = messageRow(message.id)
        if (existing != null && (existing.sessionId != message.sessionId || existing.role != message.role ||
                existing.toolCallId != message.toolCallId)) return false
        if (tool.status == "FINISHED") {
            if (existing == null || existing.content != message.content || existing.isError != message.isError) return false
            if (notificationSeqs.isNotEmpty()) markRecoveryNotificationsDelivered(run.sessionId, notificationSeqs, now)
            return true
        }
        storeMessageRow(message.copy(
            timestamp = existing?.timestamp ?: message.timestamp,
            toolActionTitle = existing?.toolActionTitle ?: message.toolActionTitle,
            toolStartedAtMs = existing?.toolStartedAtMs ?: message.toolStartedAtMs,
            toolDurationMs = existing?.toolDurationMs ?: message.toolDurationMs
        ))
        updateToolRow(tool.copy(status = "FINISHED", resultMessageId = message.id, updatedAt = now))
        deleteDraftRow(tool.runId)
        if (notificationSeqs.isNotEmpty()) markRecoveryNotificationsDelivered(run.sessionId, notificationSeqs, now)
        return true
    }

    @Transaction
    suspend fun commitScopedRecoveryToolResults(
        scope: RecoveryScope,
        target: PendingRecoveryTarget,
        runId: String,
        ownerFence: String,
        results: List<AgentMessageEntity>,
        now: Long
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (run.requestId != target.requestId || run.sessionId != target.sessionId || run.generation != target.generation ||
            !isInRecoveryScope(scope.sessionId, scope.generation, scope.workspacePath, target.sessionId, target.generation)) return false
        for (result in results) {
            if (!commitRecoveryToolResult(runId, ownerFence, result, now, emptyList())) {
                throw CancellationException("Recovery tool result is no longer current")
            }
        }
        return true
    }

    @Transaction
    suspend fun finishRecoveryRun(runId: String, ownerFence: String, status: String, holdReason: String?, now: Long): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (status !in listOf("AWAITING_CONFIRMATION", "INTERRUPTED", "FAILED", "COMPLETED", "ABANDONED")) return false
        val request = requestRow(run.requestId) ?: return false
        if (status == "COMPLETED" && (run.step != run.committedStep || toolsForRequest(request.id).any {
            it.assistantMessageId != null && it.status != "FINISHED"
        })) return false
        updateRunRow(run.copy(status = status, updatedAt = now, endedAt = now))
        for (tool in toolsForRun(run.id)) {
            if (tool.status == "STARTED") updateToolRow(tool.copy(status = "UNKNOWN", updatedAt = now))
            if (tool.status != "FINISHED") {
                val row = messageRow("tool_${tool.toolCallId}")
                if (row != null && row.sessionId == run.sessionId && row.role == "TOOL" && row.toolCallId == tool.toolCallId) {
                    val unknown = tool.status in listOf("STARTED", "UNKNOWN")
                    storeMessageRow(row.copy(isError = true, content = com.aicode.feature.agent.domain.tool.ToolResult.Error(
                        if (unknown) "Execution outcome unknown after interruption. Verify actual state before continuing."
                        else "The tool call was not executed before interruption.",
                        if (unknown) "RESULT_UNKNOWN" else "NOT_EXECUTED"
                    ).toTransportString()))
                }
            }
        }
        updateRequestRow(request.copy(status = status, updatedAt = now))
        if (status == "COMPLETED" || status == "ABANDONED") {
            for (oldRun in runsForRequest(request.id)) deleteDraftRow(oldRun.id)
        }
        if (holdReason != null) holdRecoverySession(run.sessionId, holdReason, now)
        return true
    }

    @Transaction
    suspend fun abandonPendingRecoveryRequest(requestId: String, sessionId: String, now: Long): Boolean {
        val request = requestRow(requestId) ?: return false
        if (request.sessionId != sessionId || request.status !in listOf("AWAITING_CONFIRMATION", "INTERRUPTED", "FAILED")) return false
        return abandonRecoveryRequest(requestId, now)
    }

    @Transaction
    suspend fun abandonRecoveryRequest(requestId: String, now: Long): Boolean {
        val request = requestRow(requestId) ?: return false
        val session = sessionGate(request.sessionId) ?: return false
        if (request.generation != session.executionGeneration || request.status in listOf("COMPLETED", "ABANDONED")) return false
        retireRequest(request, "ABANDONED", now)
        return true
    }

    @Transaction
    suspend fun invalidateRecoverySession(sessionId: String, reason: String, generationBump: Boolean, now: Long): Boolean {
        val exists = sessionGate(sessionId) != null
        for (request in requestsForSession(sessionId)) {
            if (request.status !in listOf("COMPLETED", "ABANDONED")) retireRequest(request, "ABANDONED", now)
        }
        retireRecoveryNotifications(sessionId, now)
        if (exists) updateSessionGate(sessionId, reason, if (generationBump) 1 else 0)
        return exists
    }

    @Transaction
    suspend fun deleteRecoverySession(sessionId: String, reason: String): Boolean {
        val exists = sessionGate(sessionId) != null
        if (exists) updateSessionGate(sessionId, reason, 1)
        retireRecoveryNotifications(sessionId, System.currentTimeMillis())
        deleteSessionToolRows(sessionId)
        deleteSessionDraftRows(sessionId)
        deleteSessionRunRows(sessionId)
        deleteSessionRequestRows(sessionId)
        return exists
    }

    @Transaction
    suspend fun holdRecoverySession(sessionId: String, reason: String, now: Long): Boolean {
        val session = sessionGate(sessionId) ?: return false
        updateSessionGate(sessionId, reason, 0)
        for (request in requestsForSession(sessionId)) {
            if (request.generation != session.executionGeneration || request.status in listOf("COMPLETED", "ABANDONED")) continue
            for (run in runsForRequest(request.id)) {
                if (run.status == "RUNNING") {
                    updateRunRow(run.copy(status = "INTERRUPTED", updatedAt = now, endedAt = now))
                    for (tool in toolsForRun(run.id)) {
                        if (tool.status == "STARTED") updateToolRow(tool.copy(status = "UNKNOWN", updatedAt = now))
                    }
                }
            }
            updateRequestRow(request.copy(status = if (request.status == "RUNNING") "INTERRUPTED" else
                if (request.status == "QUEUED") "AWAITING_CONFIRMATION" else request.status, updatedAt = now))
        }
        return true
    }

    @Transaction
    suspend fun clearRecoveryHold(sessionId: String): Boolean {
        if (sessionGate(sessionId) == null) return false
        return updateSessionGate(sessionId, null, 0) == 1
    }

    @Transaction
    suspend fun incrementRecoveryBudget(
        runId: String,
        ownerFence: String,
        outputTokens: Int,
        chars: Int,
        now: Long
    ): Boolean {
        val run = ownedRunning(runId, ownerFence) ?: return false
        if (outputTokens < 0 || chars < 0) return false
        val request = requestRow(run.requestId) ?: return false
        if (request.continuationCount >= 3 || request.continuationOutputTokens + outputTokens > 64_000) return false
        updateRequestRow(request.copy(
            continuationCount = request.continuationCount + 1,
            continuationOutputTokens = request.continuationOutputTokens + outputTokens,
            continuationChars = request.continuationChars + chars,
            updatedAt = now
        ))
        return true
    }

    @Transaction
    suspend fun retireRequest(request: RecoveryRequestEntity, status: String, now: Long) {
        updateRequestRow(request.copy(status = status, updatedAt = now))
        for (run in runsForRequest(request.id)) {
            if (run.status == "RUNNING") updateRunRow(run.copy(status = status, updatedAt = now, endedAt = now))
            for (tool in toolsForRun(run.id)) {
                if (tool.status == "STARTED") updateToolRow(tool.copy(status = "UNKNOWN", updatedAt = now))
            }
            deleteDraftRow(run.id)
        }
    }
}

private fun RecoveryRunEntity.matches(marker: RecoveryStep): Boolean = step == marker.step && revision == marker.revision

private val recoverySnapshotJson = Json { ignoreUnknownKeys = true }

internal fun RecoveryRunEntity.resumeSnapshot(): RecoveryResumeSnapshot? = resumeSnapshotJson?.let {
    runCatching { recoverySnapshotJson.decodeFromString<RecoveryResumeSnapshot>(it) }.getOrNull()
}

internal fun RecoveryRunEntity.currentDraft(draft: RecoveryDraftEntity?): RecoveryDraftEntity? = draft?.takeIf {
    it.runId == id && it.step == step && it.revision == revision && it.step > committedStep
}

internal fun RecoveryRunEntity.recoverySnapshot(
    draft: RecoveryDraftEntity?,
    tools: List<RecoveryToolEntity>,
    inputMessageExists: Boolean
): RecoveryResumeSnapshot {
    val inherited = resumeSnapshot()?.takeIf { committedStep == 0 }
    val draftText = currentDraft(draft)?.text ?: inherited?.draftText.orEmpty()
    val committedTools = tools.filter { it.assistantMessageId != null }
    val stage = when {
        committedTools.any { it.status in listOf("STARTED", "UNKNOWN") } -> RecoveryStage.TOOLS_UNKNOWN
        committedTools.any { it.status in listOf("DECLARED", "WAITING_APPROVAL") } -> RecoveryStage.TOOLS_NOT_EXECUTED
        step > committedStep || draftText.isNotEmpty() -> RecoveryStage.TEXT
        committedTools.any { it.runId == id && it.step == committedStep && it.status == "FINISHED" } -> RecoveryStage.TOOLS_COMPLETED
        committedStep > 0 -> RecoveryStage.TEXT
        committedTools.isNotEmpty() && inherited?.stage in listOf(
            RecoveryStage.TOOLS_NOT_EXECUTED, RecoveryStage.TOOLS_UNKNOWN, RecoveryStage.TOOLS_COMPLETED
        ) -> RecoveryStage.TOOLS_COMPLETED
        inherited != null && inherited.stage != RecoveryStage.NOT_STARTED -> inherited.stage
        inputMessageExists -> RecoveryStage.TEXT
        else -> RecoveryStage.NOT_STARTED
    }
    return RecoveryResumeSnapshot(stage, draftText, id, step, revision, inputMessageExists)
}

data class RecoverySessionGate(
    val id: String,
    val executionGeneration: Long,
    val autoDispatchBlockedReason: String?
)

data class RecoveryRunRows(
    @Embedded val run: RecoveryRunEntity,
    @Relation(parentColumn = "id", entityColumn = "runId") val tools: List<RecoveryToolEntity>,
    @Relation(parentColumn = "id", entityColumn = "runId") val drafts: List<RecoveryDraftEntity>
)

data class RecoveryPendingRows(
    @Embedded val request: RecoveryRequestEntity,
    @Relation(parentColumn = "id", entityColumn = "requestId", entity = RecoveryRunEntity::class)
    val runs: List<RecoveryRunRows>,
    @Relation(parentColumn = "sessionId", entityColumn = "id", entity = ChatSessionEntity::class,
        projection = ["id", "executionGeneration", "autoDispatchBlockedReason"])
    val sessions: List<RecoverySessionGate>,
    @Relation(parentColumn = "inputMessageId", entityColumn = "id", entity = AgentMessageEntity::class,
        projection = ["id"])
    val inputMessageIds: List<String> = emptyList()
)
