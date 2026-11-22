package com.aicode.feature.agent.domain.recovery

import com.aicode.feature.agent.data.local.dao.AgentRecoveryDao
import com.aicode.feature.agent.data.local.dao.RecoveryPendingRows
import com.aicode.feature.agent.data.local.dao.currentDraft
import com.aicode.feature.agent.data.local.dao.recoverySnapshot
import com.aicode.feature.agent.data.local.dao.resumeSnapshot
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.RecoveryDraftEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRequestEntity
import com.aicode.feature.agent.data.local.entity.RecoveryRunEntity
import com.aicode.feature.agent.data.local.entity.RecoveryToolEntity
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecoveryRepository @Inject constructor(
    private val recoveryDao: AgentRecoveryDao
) {
    private val ownerFence = UUID.randomUUID().toString()
    private val initializationMutex = Mutex()
    private var initialized = false
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun initialize() {
        initializationMutex.withLock {
            if (initialized) return
            recoveryDao.initializeRecovery(ownerFence, System.currentTimeMillis())
            initialized = true
        }
    }

    suspend fun enqueue(
        sessionId: String,
        payload: RecoveryRequestPayload,
        environment: String,
        requestId: String = UUID.randomUUID().toString(),
        inputMessageId: String = UUID.randomUUID().toString()
    ): RecoveryRequest {
        initialize()
        require(environment.isNotBlank() && requestId.isNotBlank() && inputMessageId.isNotBlank())
        val payloadJson = encodePayload(payload)
        val now = System.currentTimeMillis()
        return checkNotNull(recoveryDao.enqueueRequest(RecoveryRequestEntity(
            id = requestId, sessionId = sessionId, generation = 0, environment = environment,
            inputMessageId = inputMessageId, queueOrder = 0, payloadJson = payloadJson, status = "QUEUED",
            activeRunId = null, continuationCount = 0, continuationOutputTokens = 0, continuationChars = 0,
            createdAt = now, updatedAt = now
        ))) { "Target session deleted" }.toModel()
    }

    suspend fun updateQueued(requestId: String, payload: RecoveryRequestPayload): Boolean =
        recoveryDao.updateQueuedRequest(requestId, encodePayload(payload), System.currentTimeMillis())

    suspend fun reorderQueued(sessionId: String, requestIds: List<String>): Boolean =
        recoveryDao.reorderQueuedRequests(sessionId, requestIds, System.currentTimeMillis())

    suspend fun claim(requestId: String, environment: String, confirmed: Boolean = false): RecoveryRun? {
        initialize()
        return recoveryDao.claimRequest(
            requestId, environment, confirmed, UUID.randomUUID().toString(), ownerFence, System.currentTimeMillis()
        )?.toModel()
    }

    suspend fun awaitConfirmation(requestId: String): Boolean =
        recoveryDao.awaitRecoveryConfirmation(requestId, System.currentTimeMillis())

    suspend fun claimPending(
        scope: RecoveryScope,
        target: PendingRecoveryTarget,
        environment: String,
        unknownToolsVerified: Boolean
    ): RecoveryRun? {
        initialize()
        return recoveryDao.claimPendingRecovery(
            scope, target, environment, unknownToolsVerified, UUID.randomUUID().toString(), ownerFence, System.currentTimeMillis()
        )?.toModel()
    }

    fun observePending(sessionId: String): Flow<List<RecoveryItem>> = pendingItems(recoveryDao.observePendingRows(sessionId))

    fun observePending(scope: RecoveryScope): Flow<List<RecoveryItem>> =
        pendingItems(recoveryDao.observePendingScopeRows(scope.sessionId, scope.generation, scope.workspacePath))

    private fun pendingItems(pendingRows: Flow<List<RecoveryPendingRows>>): Flow<List<RecoveryItem>> =
        pendingRows.onStart { initialize() }.map { rows ->
            rows.map { row ->
                val active = row.runs.singleOrNull { it.run.id == row.request.activeRunId }
                val tools = row.runs.flatMap { it.tools }
                val draft = active?.let { current ->
                    current.run.currentDraft(current.drafts.singleOrNull())?.toModel()
                        ?: current.run.resumeSnapshot()?.takeIf {
                            current.run.committedStep == 0 && it.draftText.isNotEmpty()
                        }?.let {
                            RecoveryDraft(it.sourceRunId, it.sourceStep, it.sourceRevision, it.draftText, current.run.updatedAt)
                        }
                }
                RecoveryItem(
                    request = row.request.toModel(),
                    payload = json.decodeFromString(row.request.payloadJson),
                    run = active?.run?.toModel(),
                    draft = draft,
                    unknownTools = tools.filter { it.status == "UNKNOWN" }.map { it.toModel() },
                    pendingTools = tools.filter {
                        it.assistantMessageId != null && it.status in listOf("DECLARED", "WAITING_APPROVAL", "STARTED")
                    }.map { it.toModel() },
                    holdReason = row.sessions.singleOrNull()?.autoDispatchBlockedReason,
                    stage = active?.let {
                        it.run.recoverySnapshot(it.drafts.singleOrNull(), tools, row.request.inputMessageId in row.inputMessageIds).stage
                    } ?: RecoveryStage.NOT_STARTED
                )
            }
        }

    suspend fun request(requestId: String): RecoveryRequest? = recoveryDao.requestRow(requestId)?.toModel()

    suspend fun notificationRequest(sessionId: String, seq: Long): RecoveryRequest? {
        val generation = recoveryDao.sessionGate(sessionId)?.executionGeneration ?: return null
        return recoveryDao.requestsForSession(sessionId).firstOrNull {
            it.generation == generation && seq in json.decodeFromString<RecoveryRequestPayload>(it.payloadJson).notificationSeqs
        }?.toModel()
    }

    fun observeNotificationInputMessageIds(sessionId: String): Flow<Map<Long, String>> =
        recoveryDao.observeRequestsForSession(sessionId).map { requests ->
            buildMap {
                requests.forEach { request ->
                    val seqs = runCatching {
                        json.decodeFromString<RecoveryRequestPayload>(request.payloadJson).notificationSeqs
                    }.getOrDefault(emptyList())
                    seqs.forEach { seq -> put(seq, request.inputMessageId) }
                }
            }
        }

    suspend fun payload(requestId: String): RecoveryRequestPayload? =
        recoveryDao.requestRow(requestId)?.let { json.decodeFromString(it.payloadJson) }

    suspend fun run(runId: String): RecoveryRun? = recoveryDao.runRow(runId)?.toModel()

    suspend fun runFor(requestId: String): RecoveryRun? =
        recoveryDao.requestRow(requestId)?.activeRunId?.let { run(it) }

    suspend fun isCurrent(runId: String): Boolean = recoveryDao.ownedRunning(runId, ownerFence) != null

    suspend fun canDispatch(sessionId: String): Boolean {
        val session = recoveryDao.sessionGate(sessionId) ?: return false
        return session.autoDispatchBlockedReason == null
    }

    suspend fun beginStep(runId: String): RecoveryStep? =
        recoveryDao.beginRecoveryStep(runId, ownerFence, System.currentTimeMillis())

    suspend fun restartStream(runId: String, step: RecoveryStep): RecoveryStep? =
        recoveryDao.restartRecoveryStream(runId, ownerFence, step, System.currentTimeMillis())

    suspend fun saveDraft(runId: String, step: RecoveryStep, text: String, force: Boolean = false): Boolean =
        recoveryDao.saveRecoveryDraft(
            runId, ownerFence, step, capDraftText(text),
            System.currentTimeMillis(), if (force) 0 else DRAFT_MINIMUM_INTERVAL_MS, MAX_DRAFT_BYTES
        )

    suspend fun declareTools(runId: String, step: RecoveryStep, calls: List<RecoveryToolCall>): Boolean =
        recoveryDao.declareRecoveryTools(runId, ownerFence, step, calls, System.currentTimeMillis())

    suspend fun markToolWaitingApproval(runId: String, toolCallId: String): Boolean =
        recoveryDao.markRecoveryTool(runId, ownerFence, toolCallId, false, System.currentTimeMillis())

    suspend fun markToolStarted(runId: String, toolCallId: String): Boolean =
        recoveryDao.markRecoveryTool(runId, ownerFence, toolCallId, true, System.currentTimeMillis())

    suspend fun commitInput(runId: String, entity: AgentMessageEntity, notificationSeqs: List<Long> = emptyList()): Boolean =
        recoveryDao.commitRecoveryInput(runId, ownerFence, entity, notificationSeqs)

    suspend fun commitSupplementalInput(runId: String, entity: AgentMessageEntity): Boolean =
        recoveryDao.commitRecoverySupplementalInput(runId, ownerFence, entity)

    suspend fun freezeInputSnapshot(runId: String, snapshotJson: String, messageId: String? = null): String? =
        recoveryDao.freezeRecoveryInputSnapshot(runId, ownerFence, snapshotJson, messageId)

    suspend fun commitAssistant(runId: String, step: RecoveryStep, entity: AgentMessageEntity): Boolean =
        recoveryDao.commitRecoveryAssistant(runId, ownerFence, step, entity, System.currentTimeMillis())

    suspend fun commitToolPlaceholder(runId: String, entity: AgentMessageEntity): Boolean =
        recoveryDao.commitRecoveryToolPlaceholder(runId, ownerFence, entity)

    suspend fun updateToolTiming(runId: String, callId: String, startedAtMs: Long?, durationMs: Long?): Boolean =
        recoveryDao.updateRecoveryToolTiming(runId, ownerFence, callId, startedAtMs, durationMs)

    suspend fun updateTurnDuration(runId: String, messageId: String, durationMs: Long): Boolean =
        recoveryDao.updateRecoveryTurnDuration(runId, ownerFence, messageId, durationMs)

    suspend fun commitToolResult(runId: String, entity: AgentMessageEntity, notificationSeqs: List<Long> = emptyList()): Boolean =
        recoveryDao.commitRecoveryToolResult(runId, ownerFence, entity, System.currentTimeMillis(), notificationSeqs)

    suspend fun commitScopedToolResults(
        scope: RecoveryScope,
        target: PendingRecoveryTarget,
        runId: String,
        results: List<AgentMessageEntity>
    ): Boolean = recoveryDao.commitScopedRecoveryToolResults(scope, target, runId, ownerFence, results, System.currentTimeMillis())

    suspend fun replaceTodos(runId: String, items: List<TodoItemEntity>): Boolean =
        recoveryDao.replaceRecoveryTodos(runId, ownerFence, items)

    suspend fun finish(runId: String, status: RecoveryStatus, holdReason: String? = null): Boolean =
        recoveryDao.finishRecoveryRun(runId, ownerFence, status.name, holdReason, System.currentTimeMillis())

    suspend fun abandonPending(requestId: String, sessionId: String): Boolean =
        recoveryDao.abandonPendingRecoveryRequest(requestId, sessionId, System.currentTimeMillis())

    suspend fun abandonPending(scope: RecoveryScope, target: PendingRecoveryTarget): Boolean =
        recoveryDao.abandonScopedRecovery(scope, target, System.currentTimeMillis())

    suspend fun isInScope(scope: RecoveryScope, target: PendingRecoveryTarget): Boolean =
        recoveryDao.isInRecoveryScope(scope.sessionId, scope.generation, scope.workspacePath, target.sessionId, target.generation)

    suspend fun abandon(requestId: String): Boolean =
        recoveryDao.abandonRecoveryRequest(requestId, System.currentTimeMillis())

    suspend fun invalidateSession(sessionId: String, reason: String, generationBump: Boolean = true): Boolean {
        require(reason.isNotBlank())
        return recoveryDao.invalidateRecoverySession(sessionId, reason, generationBump, System.currentTimeMillis())
    }

    suspend fun deleteSession(sessionId: String, reason: String = "session_deleted"): Boolean {
        require(reason.isNotBlank())
        return recoveryDao.deleteRecoverySession(sessionId, reason)
    }

    suspend fun holdSession(sessionId: String, reason: String): Boolean {
        require(reason.isNotBlank())
        return recoveryDao.holdRecoverySession(sessionId, reason, System.currentTimeMillis())
    }

    suspend fun clearExplicitHold(sessionId: String): Boolean = recoveryDao.clearRecoveryHold(sessionId)

    suspend fun incrementContinuationBudget(runId: String, outputTokens: Int, chars: Int): Boolean =
        recoveryDao.incrementRecoveryBudget(runId, ownerFence, outputTokens, chars, System.currentTimeMillis())

    private fun encodePayload(payload: RecoveryRequestPayload): String {
        require(payload.inputAttachments.all {
            !it.localPath.startsWith("data:", ignoreCase = true) &&
                !it.containerPath.startsWith("data:", ignoreCase = true)
        }) { "Recovery attachments must contain file paths" }
        val encoded = json.encodeToString(payload)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) { "Recovery request is too large" }
        return encoded
    }

    companion object {
        const val MAX_DRAFT_BYTES = 32_768
        const val DRAFT_MINIMUM_INTERVAL_MS = 500L
        const val MAX_PAYLOAD_BYTES = 512_000
        internal const val DRAFT_OMITTED_MARKER = "\n[… visible draft omitted …]\n"

        internal fun capDraftText(text: String): String {
            if (text.toByteArray(Charsets.UTF_8).size <= MAX_DRAFT_BYTES) return text
            val budget = MAX_DRAFT_BYTES - DRAFT_OMITTED_MARKER.toByteArray(Charsets.UTF_8).size
            val head = MessagePersistenceUseCase.capBytes(text, budget / 2)
            val tailBudget = budget - head.toByteArray(Charsets.UTF_8).size
            var start = text.length
            var used = 0
            while (start > head.length) {
                val point = Character.codePointBefore(text, start)
                val size = when {
                    point < 0x80 -> 1
                    point < 0x800 -> 2
                    point < 0x10000 -> 3
                    else -> 4
                }
                if (used + size > tailBudget) break
                used += size
                start -= Character.charCount(point)
            }
            return head + DRAFT_OMITTED_MARKER + text.substring(start)
        }
    }
}

private fun RecoveryRequestEntity.toModel() = RecoveryRequest(
    id, sessionId, generation, environment, inputMessageId, queueOrder, RecoveryStatus.valueOf(status), activeRunId,
    continuationCount, continuationOutputTokens, continuationChars, createdAt, updatedAt
)

private fun RecoveryRunEntity.toModel() = RecoveryRun(
    id, requestId, sessionId, generation, ownerFence, RecoveryStatus.valueOf(status), step, revision, committedStep,
    createdAt, updatedAt, endedAt, resumeSnapshot()
)

private fun RecoveryToolEntity.toModel() = RecoveryTool(
    runId, toolCallId, toolName, step, revision, RecoveryToolStatus.valueOf(status), assistantMessageId, resultMessageId, updatedAt
)

private fun RecoveryDraftEntity.toModel() = RecoveryDraft(runId, step, revision, text, updatedAt)
