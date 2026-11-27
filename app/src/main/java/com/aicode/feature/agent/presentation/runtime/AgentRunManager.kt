package com.aicode.feature.agent.presentation.runtime

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.formatCostUsd
import com.aicode.core.util.toUserMessage
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectProgressCollector
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.recovery.*
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.SubAgentDigest
import com.aicode.feature.agent.domain.subagent.SubAgentDigester
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalDecision
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.agent.presentation.COMPACTION_FAILURE_TOOL_NAME
import com.aicode.feature.agent.presentation.KeySwitchState
import com.aicode.feature.agent.presentation.LlmCallEvent
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.agent.presentation.RetryState
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.SubAgentCardState
import com.aicode.feature.agent.presentation.SubAgentCardStatus
import com.aicode.feature.agent.presentation.WorkflowStatus
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.formatTokenCount
import com.aicode.feature.agent.presentation.component.toolRunningLabelRes
import com.aicode.feature.agent.presentation.hasVisibleContent
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
import com.aicode.feature.agent.presentation.preview.PreparingToolPreviewSnapshot
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class AgentRunManager internal constructor(
    dependencies: AgentRunDependencies,
    runtimeContext: CoroutineContext,
    private val initializationDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    @Inject
    constructor(dependencies: AgentRunDependencies) : this(dependencies, Dispatchers.Main.immediate, Dispatchers.IO)

    private val agentWorkflow = dependencies.agentWorkflow
    private val toolRegistry = dependencies.toolRegistry
    private val agentMessageDao = dependencies.agentMessageDao
    private val chatSessionDao = dependencies.chatSessionDao
    private val llmCallRecordDao = dependencies.llmCallRecordDao
    private val modelCostCalculator = dependencies.modelCostCalculator
    private val aiProviderRepository = dependencies.aiProviderRepository
    private val defaultModelSettingsRepository = dependencies.defaultModelSettingsRepository
    private val modelReasoningEffortRepository = dependencies.modelReasoningEffortRepository
    private val toolPermissionManager = dependencies.toolPermissionManager
    private val askUserQuestionManager = dependencies.askUserQuestionManager
    private val planApprovalManager = dependencies.planApprovalManager
    private val sessionUseCase = dependencies.sessionUseCase
    private val messagePersistenceUseCase = dependencies.messagePersistenceUseCase
    private val terminalSessionProvider = dependencies.terminalSessionProvider
    private val terminalSessionManager = dependencies.terminalSessionManager
    private val slashCommandRegistry = dependencies.slashCommandRegistry
    private val checkpointManager = dependencies.checkpointManager
    private val checkpointDao = dependencies.checkpointDao
    private val agentSoundSettings = dependencies.agentSoundSettings
    private val generalSettingsRepository = dependencies.generalSettingsRepository
    private val rootAccessSettingsRepository = dependencies.rootAccessSettingsRepository
    private val agentLiveNotificationManager = dependencies.agentLiveNotificationManager
    private val approvalNotificationSettings = dependencies.approvalNotificationSettings
    private val agentApprovalNotificationManager = dependencies.agentApprovalNotificationManager
    private val subAgentEventBus = dependencies.subAgentEventBus
    private val agentNotificationCenter = dependencies.agentNotificationCenter
    private val agentDefinitionRepository = dependencies.agentDefinitionRepository
    private val todoItemDao = dependencies.todoItemDao
    private val recoveryRepository = dependencies.recoveryRepository
    private val projectMemoryJournal = dependencies.projectMemoryJournal
    private val projectMemoryIdentityResolver = dependencies.projectMemoryIdentityResolver
    private val projectMemorySessionCoordinator = dependencies.projectMemorySessionCoordinator
    private val projectMemoryRuntimeBindings = dependencies.projectMemoryRuntimeBindings
    private val projectMemoryPathResolver = dependencies.projectMemoryPathResolver
    private val projectProgressTerminalTracker = dependencies.projectProgressTerminalTracker
    private val context = dependencies.context
    private val fileVersions = dependencies.fileVersions
    private val executionModeHolder = dependencies.executionModeHolder
    private val containerSettingsRepository = dependencies.containerSettingsRepository
    private val remoteSshConnection = dependencies.remoteSshConnection
    private val workspaceExecutionContextFactory = dependencies.workspaceExecutionContextFactory

    private data class RunSource(
        val mode: ExecutionMode,
        val modeRevision: Long,
        val profileRevision: Long,
        val remote: RemoteConnectionIdentity?
    )
    private val queuedSources = mutableMapOf<String, RunSource>()

    private fun captureSource(): Result<RunSource> = runCatching {
        val modeRevision = executionModeHolder.modeSelectionRevision
        val profileRevision = containerSettingsRepository.activeProfileSelectionRevision
        val mode = executionModeHolder.currentMode()
        RunSource(mode, modeRevision, profileRevision,
            if (mode == ExecutionMode.REMOTE_SSH) remoteSshConnection.captureIdentity() else null).also(::checkSource)
    }

    private fun checkSource(source: RunSource) {
        if (executionModeHolder.currentMode() != source.mode || executionModeHolder.modeSelectionRevision != source.modeRevision ||
            containerSettingsRepository.activeProfileSelectionRevision != source.profileRevision) {
            throw CancellationException("Agent execution source changed")
        }
        source.remote?.let(remoteSshConnection::checkIdentity)
    }

    private fun freezeCapture(capture: ProjectMemoryCapture, source: RunSource) =
        ProjectMemoryCapture(capture.identity, capture.channelGeneration) {
            checkSource(source)
            capture.assertCurrent()
            checkSource(source)
        }

    private val ownerJob = SupervisorJob(runtimeContext[Job])
    private val managerScope = CoroutineScope(runtimeContext.minusKey(Job) + ownerJob + CoroutineExceptionHandler { _, failure ->
        FileLogger.e(TAG, "Agent runtime operation failed", failure)
        _startupError.value = failure.toUserMessage(context)
    })
    private val started = AtomicBoolean(false)
    private val bootstrap = CompletableDeferred<Boolean>()
    private val _startupError = MutableStateFlow<String?>(null)
    val startupError: StateFlow<String?> = _startupError.asStateFlow()

    private val sessionJobs = mutableMapOf<String, Job>()
    private val requestAcceptance = Mutex()
    private val notificationDispatch = Mutex()
    private var pendingNotificationStops = 0
    private val stopAllSettled = MutableStateFlow(true)
    private var acceptanceEpoch = 0L
    private val activeRecoveryRuns = mutableMapOf<String, String>()
    private val recoveryCodec = Json { ignoreUnknownKeys = true }
    private val _recoveryBusySessions = MutableStateFlow<Set<String>>(emptySet())
    val recoveryBusySessions: StateFlow<Set<String>> = _recoveryBusySessions.asStateFlow()
    // One consumed run identity per request; bound process-local history and discard deleted sessions.
    private val _consumedRecoveryTargets = MutableStateFlow<Map<String, PendingRecoveryTarget>>(emptyMap())
    internal val consumedRecoveryTargets: StateFlow<Map<String, PendingRecoveryTarget>> = _consumedRecoveryTargets.asStateFlow()
    private fun consumeRecoveryTarget(target: PendingRecoveryTarget) {
        val retained = _consumedRecoveryTargets.value.filterValues {
            it.sessionId != target.sessionId || it.generation == target.generation
        } - target.requestId
        _consumedRecoveryTargets.value = (retained + (target.requestId to target.copy(unknownTools = emptySet())))
            .entries.toList().takeLast(512).associate { it.key to it.value }
    }

    val recovering: StateFlow<Boolean> = _recoveryBusySessions.map { it.isNotEmpty() }
        .stateIn(managerScope, SharingStarted.Eagerly, false)
    private val planRejectedSessions = mutableSetOf<String>()
    private val goalEmptyTurnCounts = mutableMapOf<String, Int>()

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()
    private val _subAgentCards = MutableStateFlow<Map<String, SubAgentCardState>>(emptyMap())
    val subAgentCards: StateFlow<Map<String, SubAgentCardState>> = _subAgentCards.asStateFlow()
    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTools: StateFlow<Map<String, Map<String, RunningToolOutput>>> = _runningTools.asStateFlow()
    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())
    val preparingTools: StateFlow<Map<String, String>> = _preparingTools.asStateFlow()
    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val compactingSessions: StateFlow<Map<String, Boolean>> = _compactingSessions.asStateFlow()
    private data class CompactionOwner(val job: Job, val attemptId: String, val phase: CompactionPhase = CompactionPhase.PREPARING)
    private val compactionOwners = HashMap<String, CompactionOwner>()
    private val compressionCommandOwners = HashMap<String, Job>()
    private val commandOwners = HashMap<String, Job>()
    private val _compactionPercents = MutableStateFlow<Map<String, Int>>(emptyMap())
    val compactionPercents: StateFlow<Map<String, Int>> = _compactionPercents.asStateFlow()
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()
    private val _generationSpeeds = MutableStateFlow<Map<String, AgentEvent.GenerationSpeed>>(emptyMap())
    val generationSpeeds: StateFlow<Map<String, AgentEvent.GenerationSpeed>> = _generationSpeeds.asStateFlow()
    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingTexts: StateFlow<Map<String, String?>> = _streamingTexts.asStateFlow()
    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasonings: StateFlow<Map<String, String?>> = _streamingReasonings.asStateFlow()
    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTimings: StateFlow<Map<String, Pair<Long, Long?>>> = _reasoningTimings.asStateFlow()
    private val _reasoningDurations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val reasoningDurations: StateFlow<Map<String, Long>> = _reasoningDurations.asStateFlow()
    private val _reasoningCommits = MutableStateFlow<Map<String, String>>(emptyMap())
    val reasoningCommits: StateFlow<Map<String, String>> = _reasoningCommits.asStateFlow()
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryStates: StateFlow<Map<String, RetryState?>> = _retryStates.asStateFlow()
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchStates: StateFlow<Map<String, KeySwitchState?>> = _keySwitchStates.asStateFlow()
    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    val queuedRequests: StateFlow<Map<String, List<QueuedRequest>>> = _queuedRequests.asStateFlow()
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val runningCommandSessions: StateFlow<Set<String>> = _runningCommandSessions.asStateFlow()
    private val toolArgsByMsgId = mutableMapOf<String, String>()
    private val _preparingToolArguments = MutableStateFlow<Map<String, Map<String, PreparingToolPreviewSnapshot>>>(emptyMap())
    val preparingToolArguments: StateFlow<Map<String, Map<String, PreparingToolPreviewSnapshot>>> = _preparingToolArguments.asStateFlow()
    private val _toolExecutionStarts = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val toolExecutionStarts: StateFlow<Map<String, Map<String, Long>>> = _toolExecutionStarts.asStateFlow()
    private val turnStartedElapsed = mutableMapOf<String, Long>()
    private val externallyRecordedSubAgentStops = mutableSetOf<String>()
    private val stoppingSessions = mutableSetOf<String>()
    private val deletedSessionIds = mutableSetOf<String>()
    private val _deletedSessions = MutableStateFlow<Set<String>>(emptySet())
    val deletedSessions: StateFlow<Set<String>> = _deletedSessions.asStateFlow()
    private val _subAgentWaitingSlots = MutableStateFlow<Set<String>>(emptySet())
    val subAgentWaitingSlots: StateFlow<Set<String>> = _subAgentWaitingSlots.asStateFlow()
    private val _resolvedSessions = MutableStateFlow<Map<String, String>>(emptyMap())
    val resolvedSessions: StateFlow<Map<String, String>> = _resolvedSessions.asStateFlow()

    val pendingToolPermission = toolPermissionManager.pendingRequest
    val awaitingPermissionSessionIds = toolPermissionManager.awaitingSessionIds
    val pendingUserQuestion = askUserQuestionManager.pendingQuestion
    val pendingPlanApproval = planApprovalManager.pendingPlan
    val awaitingPlanSessionIds = planApprovalManager.awaitingSessionIds
    val approvalSessionIds: StateFlow<Set<String>> = combine(
        awaitingPermissionSessionIds,
        pendingUserQuestion,
        awaitingPlanSessionIds
    ) { awaiting, question, plans ->
        buildSet {
            addAll(awaiting)
            addAll(plans)
            question?.sessionId?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }.stateIn(managerScope, SharingStarted.Eagerly, emptySet())

    fun start() {
        if (!started.compareAndSet(false, true)) return
        managerScope.launch {
            managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                terminalSessionProvider.tabFinishedEvents.collect { event ->
                    if (!awaitReady(event.sourceSessionId)) return@collect
                    withContext(Dispatchers.IO) {
                        projectProgressTerminalTracker.finished(event.sourceSessionId, event.tabId, event.exitCode.takeIf { event.exitKnown },
                            event.sourceMode, event.channelGeneration)
                    }
                    handleBackgroundCommandFinished(event)
                }
            }
            managerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                subAgentEventBus.events.collect { event ->
                    if (!awaitReady(event.subSessionId)) return@collect
                    if (!isCurrentSubAgentEvent(event)) {
                        if (event.type == SubAgentEventType.SPAWNED && sessionJobs[event.subSessionId]?.isCompleted != false) {
                            subAgentEventBus.release(event.subSessionId)
                        }
                        return@collect
                    }
                    when (event.type) {
                        SubAgentEventType.SPAWNED -> {
                            markSubAgentRunning(event.subSessionId)
                            spawnSubAgentWorkflow(event)
                        }
                        SubAgentEventType.STOPPED, SubAgentEventType.INTERRUPTED -> {
                            if (event.runId == null || activeRecoveryRuns[event.subSessionId] == event.runId) {
                                if (sessionJobs[event.subSessionId]?.isCompleted == false) externallyRecordedSubAgentStops.add(event.subSessionId)
                                stopAgentSession(event.subSessionId)
                            }
                            enqueueSubAgentNotification(event)
                        }
                        SubAgentEventType.DELETED -> {
                            markSubAgentFinished(event.subSessionId, SubAgentCardStatus.STOPPED)
                            clearSessionRuntimeState(event.subSessionId)
                        }
                        SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> enqueueSubAgentNotification(event)
                        SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                        SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                        SubAgentEventType.STARTED, SubAgentEventType.INSTRUCTION_CANCELLED -> Unit
                    }
                }
            }
            managerScope.launch {
                rootAccessSettingsRepository.state.collect { state ->
                    if (!state.enabled) toolPermissionManager.rejectForTool("Root")
                }
            }
            managerScope.launch {
                approvalNotificationSettings.enabledFlow.collectLatest { enabled ->
                    if (!enabled) agentApprovalNotificationManager.dismiss()
                }
            }
            managerScope.launch {
                combine(
                    awaitingPermissionSessionIds,
                    askUserQuestionManager.pendingQuestion,
                    toolPermissionManager.pendingRequest,
                    planApprovalManager.awaitingSessionIds
                ) { awaiting, question, permission, plans ->
                    val labels = LinkedHashMap<String, Triple<Int, ApprovalKind, String?>>()
                    awaiting.forEach {
                        labels[it] = Triple(R.string.notification_approval_label_permission, ApprovalKind.PERMISSION,
                            permission?.takeIf { request -> request.sessionId == it }?.id)
                    }
                    question?.sessionId?.takeIf { it.isNotBlank() }?.let {
                        labels.putIfAbsent(it, Triple(R.string.notification_approval_label_question, ApprovalKind.QUESTION, null))
                    }
                    plans.forEach {
                        labels.putIfAbsent(it, Triple(R.string.plan_approval_waiting, ApprovalKind.PLAN, null))
                    }
                    labels
                }.collect { labels ->
                    if (!awaitReady()) return@collect
                    val pending = labels.mapNotNull { (sid, info) ->
                        val session = sessionUseCase.getSessionById(sid) ?: return@mapNotNull null
                        AgentApprovalNotificationManager.PendingApproval(
                            sessionId = sid,
                            sessionTitle = session.title.ifBlank { context.getString(R.string.chat_new_session_btn) },
                            label = context.getString(info.first),
                            kind = info.second,
                            requestId = info.third
                        )
                    }
                    agentApprovalNotificationManager.update(pending)
                }
            }
            managerScope.launch {
                combine(
                    _agentStates,
                    _runningTools,
                    _preparingTools,
                    _compactingSessions,
                    approvalSessionIds
                ) { states, tools, preparing, compacting, approvals ->
                    states.filterValues { it is AgentUIState.Loading || it is AgentUIState.Streaming }
                        .keys.associateWith { sid ->
                            val toolName = tools[sid]?.values?.lastOrNull()?.toolName?.takeIf { it.isNotBlank() }
                                ?: preparing[sid]
                            when {
                                sid in approvals -> context.getString(R.string.notification_approval_needed)
                                toolName != null -> context.getString(toolRunningLabelRes(toolName))
                                compacting[sid] == true -> context.getString(R.string.chat_compressing_context)
                                else -> context.getString(R.string.chat_status_thinking)
                            }
                        }
                }.distinctUntilChanged().collectLatest { statusMap ->
                    if (!awaitReady()) return@collectLatest
                    val running = statusMap.mapNotNull { (sid, label) ->
                        val session = sessionUseCase.getSessionById(sid) ?: return@mapNotNull null
                        if (session.parentId != null) return@mapNotNull null
                        AgentLiveNotificationManager.RunningSession(
                            sessionId = sid,
                            title = session.title.ifBlank { context.getString(R.string.chat_new_session_btn) },
                            statusLabel = label
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    agentLiveNotificationManager.update(running)
                }
            }
            try {
                withContext(initializationDispatcher) { sessionUseCase.initColdStartCleanup() }
                bootstrap.complete(true)
            } catch (cancelled: CancellationException) {
                bootstrap.complete(false)
                throw cancelled
            } catch (failure: Exception) {
                _startupError.value = failure.toUserMessage(context)
                FileLogger.e(TAG, "Agent runtime initialization failed", failure)
                bootstrap.complete(false)
            }
        }
    }

    internal suspend fun awaitReady(sessionId: String? = null): Boolean {
        start()
        val ready = bootstrap.await()
        if (!ready && sessionId != null) {
            setAgentState(sessionId, AgentUIState.Error(_startupError.value ?: context.getString(R.string.recovery_request_not_saved)))
        }
        return ready
    }

    internal suspend fun closeForTest() {
        ownerJob.cancelAndJoin()
    }

    fun isSessionRunning(sessionId: String): Boolean = sessionJobs[sessionId]?.isCompleted == false

    fun hasRunningSessions(ids: Set<String>): Boolean = ids.any { isSessionRunning(it) }

    fun resetIdle(sessionId: String) = managerScope.launch { setAgentState(sessionId, AgentUIState.Idle) }

    fun resetModelRuntime(sessionId: String) = managerScope.launch {
        agentWorkflow.discardCompactionCheckpoint(sessionId)
        _contextUsages.value -= sessionId
        setGenerationSpeed(sessionId, null)
    }

    fun streamingReasoningKey(sessionId: String): String = "reasoning:stream:$sessionId"

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" -> context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            "continuation_budget_exceeded" -> context.getString(R.string.recovery_continuation_limit)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) _runningTools.value - sessionId
            else _runningTools.value + (sessionId to updated)
    }

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        if (compacting) {
            _compactionPercents.value += sessionId to 0
            _compactingSessions.value += sessionId to true
        } else {
            compactionOwners.remove(sessionId)
            _compactionPercents.value -= sessionId
            _compactingSessions.value -= sessionId
        }
    }

    private fun acceptCompactionEvent(sessionId: String, owner: Job, event: AgentEvent): Boolean {
        if (sessionJobs[sessionId] !== owner || !owner.isActive || sessionId in deletedSessionIds || sessionId in stoppingSessions) return false
        if (event is AgentEvent.CompactionStarted) {
            compactionOwners[sessionId] = CompactionOwner(owner, event.attemptId)
            setCompacting(sessionId, true)
            return true
        }
        val attemptId = when (event) {
            is AgentEvent.CompactionProgress -> event.attemptId
            is AgentEvent.CompactionFinished -> event.attemptId
            is AgentEvent.CompactionFailed -> event.attemptId
            else -> return false
        }
        val active = compactionOwners[sessionId] ?: return false
        if (active.job !== owner || active.attemptId != attemptId) return false
        when (event) {
            is AgentEvent.CompactionProgress -> {
                if (active.phase in setOf(CompactionPhase.COMPLETED, CompactionPhase.UNCHANGED, CompactionPhase.FAILED, CompactionPhase.CANCELLED)) return false
                compactionOwners[sessionId] = active.copy(phase = event.progress.phase)
                _compactionPercents.value += sessionId to maxOf(_compactionPercents.value[sessionId] ?: 0, event.progress.percent)
            }
            is AgentEvent.CompactionFinished -> {
                if (active.phase == CompactionPhase.COMPLETED) _contextUsages.value -= sessionId
                setCompacting(sessionId, false)
            }
            is AgentEvent.CompactionFailed -> setCompacting(sessionId, false)
            else -> return false
        }
        return true
    }

    private fun compactionFailureMessage(code: String): String = context.getString(when {
        code == "compaction_total_timeout" -> R.string.compaction_error_total_timeout
        code == "compaction_first_chunk_timeout" -> R.string.compaction_error_first_content_timeout
        code == "compaction_idle_timeout" -> R.string.compaction_error_idle_timeout
        code in setOf("compaction_no_final", "compaction_missing_stop") -> R.string.compaction_error_missing_terminal
        code.contains("timeout") -> R.string.compaction_error_timeout
        code == "compaction_http_401" || code == "compaction_http_403" -> R.string.compaction_error_authentication
        code == "compaction_http_429" -> R.string.compaction_error_rate_limit
        code.startsWith("compaction_http_") -> R.string.compaction_error_service
        code == "compaction_io" -> R.string.compaction_error_network
        code == "compaction_upstream_interrupted" -> R.string.compaction_error_upstream_interrupted
        code == "compaction_truncated" -> R.string.compaction_error_truncated
        code in setOf("compaction_attempt_limit", "compaction_block_limit", "compaction_budget_unreachable") -> R.string.compaction_error_budget
        code == "compaction_busy" -> R.string.compaction_error_busy
        else -> R.string.compaction_error_incomplete
    })

    private fun setGenerationSpeed(sessionId: String, speed: AgentEvent.GenerationSpeed?) {
        if (speed != null && speed.tokensPerSecond <= 0.0) return
        _generationSpeeds.value = if (speed == null) _generationSpeeds.value - sessionId
            else _generationSpeeds.value + (sessionId to speed)
    }

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) _reasoningTimings.value -= sessionId
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    private fun protectJob(job: Job) {
        val owner = "agent:${UUID.randomUUID()}"
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        job.invokeOnCompletion { TaskKeepaliveController.release(context, owner) }
    }

    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(R.string.agent_complete_notification_background_body, titles.first())
                else -> context.getString(R.string.agent_complete_notification_background_multi_body, titles.size)
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private fun markSubAgentRunning(subSessionId: String) {
        managerScope.launch {
            val session = runCatching { sessionUseCase.getSessionById(subSessionId) }.getOrNull()
            _subAgentCards.value = _subAgentCards.value + (
                subSessionId to SubAgentCardState(
                    subSessionId = subSessionId,
                    agentName = session?.subagentType?.takeIf { it.isNotBlank() && it != "subagent" },
                    title = session?.title.orEmpty(),
                    status = SubAgentCardStatus.RUNNING,
                    startedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun markSubAgentFinished(
        subSessionId: String,
        status: SubAgentCardStatus,
        digest: SubAgentDigest? = null
    ) {
        val previous = _subAgentCards.value[subSessionId]
        val startedAt = previous?.startedAt ?: System.currentTimeMillis()
        _subAgentCards.value = _subAgentCards.value + (
            subSessionId to SubAgentCardState(
                subSessionId = subSessionId,
                agentName = previous?.agentName,
                title = previous?.title.orEmpty(),
                status = status,
                excerpt = digest?.excerpt ?: previous?.excerpt,
                changedFiles = digest?.changedFiles?.takeIf { it.isNotEmpty() } ?: previous?.changedFiles.orEmpty(),
                elapsedMs = System.currentTimeMillis() - startedAt,
                startedAt = startedAt
            )
        )
        if (_subAgentCards.value.size > MAX_SUBAGENT_CARDS) {
            _subAgentCards.value = _subAgentCards.value.entries.sortedByDescending { it.value.startedAt }
                .take(MAX_SUBAGENT_CARDS).associate { it.key to it.value }
        }
    }

    private suspend fun isCurrentSubAgentEvent(event: SubAgentEvent): Boolean {
        val parent = sessionUseCase.getSessionById(event.parentSessionId) ?: return false
        if (event.parentGeneration != null && parent.executionGeneration != event.parentGeneration) return false
        val child = sessionUseCase.getSessionById(event.subSessionId)
        return if (child == null) event.type == SubAgentEventType.DELETED
        else child.parentId == parent.id && (event.generation == null || child.executionGeneration == event.generation)
    }

    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "子代理父会话不存在: ${event.parentSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        if (sessionJobs[event.subSessionId]?.isCompleted == false) return
        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true,
            expectedSubAgentEvent = event
        )
    }

    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        managerScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            val digest = if (event.type == SubAgentEventType.COMPLETED) {
                withContext(Dispatchers.IO) {
                    runCatching { SubAgentDigester.digestRun(
                        agentMessageDao.getMessagesBySessionOnce(event.subSessionId), event.sourceMessageId, event.runMessageIds
                    ) }.onFailure { FileLogger.w(TAG, "子代理结果摘要提取失败: ${event.subSessionId}", it) }.getOrNull()
                }
            } else null
            if (!isCurrentSubAgentEvent(event)) return@launch
            markSubAgentFinished(
                subSessionId = event.subSessionId,
                status = when (event.type) {
                    SubAgentEventType.FAILED -> SubAgentCardStatus.FAILED
                    SubAgentEventType.COMPLETED -> SubAgentCardStatus.COMPLETED
                    else -> SubAgentCardStatus.STOPPED
                },
                digest = digest
            )
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = when (event.type) {
                    SubAgentEventType.FAILED -> NotificationOutcome.FAILED
                    SubAgentEventType.COMPLETED -> NotificationOutcome.COMPLETED
                    else -> NotificationOutcome.STOPPED
                },
                detail = event.detail.takeIf { it.isNotBlank() && event.type != SubAgentEventType.COMPLETED },
                resultExcerpt = digest?.excerpt,
                changedFiles = digest?.changedFiles.orEmpty(),
                eventId = event.eventId,
                generation = event.parentGeneration
            )
        }
    }

    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(event.subSessionId, event.parentSessionId, senderTitle, event.detail,
            fromParent = true, eventId = event.eventId, generation = event.generation)
    }

    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
        deliverAgentMessage(event.parentSessionId, event.subSessionId, senderTitle, event.detail,
            fromParent = false, eventId = event.eventId, generation = event.parentGeneration)
    }

    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean,
        eventId: String,
        generation: Long?
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent,
            eventId = eventId
        )
        deliverSystemEvent(recipientSessionId, item, generation)
    }

    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?,
        resultExcerpt: String? = null,
        changedFiles: List<String> = emptyList(),
        eventId: String = UUID.randomUUID().toString(),
        generation: Long? = null
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail,
            resultExcerpt = resultExcerpt,
            changedFiles = changedFiles,
            eventId = eventId
        )
        deliverSystemEvent(parentSessionId, item, generation)
    }

    private suspend fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId jobPending=${sessionJobs[sessionId]?.isCompleted == false} state=${_agentStates.value[sessionId]}")
        deliverSystemEvent(sessionId, event.toPendingNotification())
    }

    private suspend fun deliverSystemEvent(sessionId: String, item: PendingNotification, generation: Long? = null) {
        if (!awaitReady(sessionId)) return
        if (agentNotificationCenter.enqueue(sessionId, item, generation) <= 0) return
        flushPendingNotifications(sessionId)
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = when {
            !exitKnown -> NotificationOutcome.UNKNOWN
            exitCode == 0 -> NotificationOutcome.COMPLETED
            else -> NotificationOutcome.FAILED
        },
        command = command,
        exitCode = exitCode.takeIf { exitKnown },
        tailOutput = if (historyError == null) tailOutput else {
            val status = if (exitKnown) "this command exit status was observed" else "this command exit status is unknown"
            listOf("Task history could not be saved; $status.", tailOutput.orEmpty())
                .joinToString("\n")
        }
    )

    private fun flushPendingNotifications(sessionId: String) {
        managerScope.launch {
            if (!awaitReady(sessionId)) return@launch
            notificationDispatch.withLock {
                if (pendingNotificationStops > 0 || sessionId in deletedSessionIds || sessionId in planRejectedSessions || sessionId in stoppingSessions) return@withLock
                if (!recoveryRepository.canDispatch(sessionId)) return@withLock
                if (!canStartRecovery(sessionId) || sessionId in _recoveryBusySessions.value) return@withLock
                val items = agentNotificationCenter.peek(sessionId).filter {
                    recoveryRepository.notificationRequest(sessionId, it.seq) == null
                }
                if (items.isEmpty()) return@withLock
                val root = sessionUseCase.getSessionById(sessionId)?.workspacePath ?: return@withLock
                if (!canStartRecovery(sessionId) || sessionId in _recoveryBusySessions.value) return@withLock
                executeAgentRequestStream(
                    request = AgentNotificationFormatter.buildMessage(items),
                    projectRoot = root,
                    targetSessionId = sessionId,
                    notificationSeqs = items.map { it.seq }
                )
            }
        }
    }

    private fun canStartRecovery(sessionId: String): Boolean =
        sessionId !in deletedSessionIds && sessionId !in stoppingSessions && !isSessionRunning(sessionId) &&
            sessionId !in _runningCommandSessions.value && sessionId !in _compactingSessions.value

    fun continueRecovery(scope: RecoveryScope, targets: List<PendingRecoveryTarget>, unknownToolsVerified: Boolean = false) {
        if (!unknownToolsVerified && targets.any { it.unknownTools.isNotEmpty() }) return
        val source = captureSource()
        runRecoveryBatch(scope, targets.filter { canStartRecovery(it.sessionId) }) { target, epoch ->
            resumePendingRecovery(scope, target, source, epoch, unknownToolsVerified)
        }
    }

    fun abandonRecovery(scope: RecoveryScope, targets: List<PendingRecoveryTarget>) {
        runRecoveryBatch(scope, targets) { target, _ ->
            val payload = recoveryRepository.payload(target.requestId)
            if (recoveryRepository.abandonPending(scope, target)) {
                consumeRecoveryTarget(target)
                payload?.notificationSeqs?.takeIf { it.isNotEmpty() }?.let { agentNotificationCenter.discard(target.sessionId, it) }
            }
        }
    }

    private fun runRecoveryBatch(
        scope: RecoveryScope,
        targets: List<PendingRecoveryTarget>,
        action: suspend (PendingRecoveryTarget, Long) -> Unit
    ) {
        val batch = targets.distinctBy { it.sessionId }.sortedBy { it.sessionId != scope.sessionId }
        val sessions = batch.mapTo(mutableSetOf()) { it.sessionId }
        if (sessions.isEmpty() || sessions.any { it in _recoveryBusySessions.value }) return
        val epoch = acceptanceEpoch
        _recoveryBusySessions.value += sessions
        managerScope.launch {
            var ready = false
            try {
                if (!awaitReady(scope.sessionId)) return@launch
                ready = true
                stopAllSettled.first { it }
                for (target in batch) {
                    if (epoch != acceptanceEpoch || scope.sessionId in deletedSessionIds) break
                    try {
                        action(target, epoch)
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } catch (failure: Exception) {
                        FileLogger.w(TAG, "Recovery action failed for session ${target.sessionId}", failure)
                        if (canStartRecovery(target.sessionId)) {
                            setAgentState(target.sessionId, AgentUIState.Error(context.getString(R.string.recovery_continue_failed)))
                        }
                    }
                }
            } finally {
                _recoveryBusySessions.value -= sessions
                if (ready && stopAllSettled.value) {
                    for (sessionId in sessions) {
                        processNextInQueue(sessionId)
                        flushPendingNotifications(sessionId)
                    }
                }
            }
        }
    }

    private suspend fun resumePendingRecovery(
        scope: RecoveryScope,
        target: PendingRecoveryTarget,
        source: Result<RunSource>,
        epoch: Long,
        unknownToolsVerified: Boolean
    ) {
        val sessionId = target.sessionId
        var claimed: RecoveryRun? = null
        var releasedHold: String? = null
        var handedOff = false
        try {
            if (!canStartRecovery(sessionId)) return
            val fixedSource = source.getOrThrow()
            checkSource(fixedSource)
            val item = recoveryRepository.observePending(sessionId).first()
                .firstOrNull { it.request.id == target.requestId } ?: return
            val payload = item.payload
            if (scope.workspacePath != payload.projectRoot || item.pendingTarget() != target) return
            val capturedEnvironment = projectMemoryIdentityResolver.capture(payload.projectRoot)?.let { freezeCapture(it, fixedSource) } ?: return
            val environment = recoveryCodec.encodeToString(capturedEnvironment.identity)
            if (environment != item.request.environment) return
            suspend fun assertScopeCurrent() {
                capturedEnvironment.assertCurrent()
                if (!recoveryRepository.isInScope(scope, target)) throw CancellationException("Recovery scope changed")
            }
            val capture = if (sessionId == scope.sessionId) capturedEnvironment else ProjectMemoryCapture(
                capturedEnvironment.identity, capturedEnvironment.channelGeneration, ::assertScopeCurrent
            )
            assertScopeCurrent()
            if (epoch != acceptanceEpoch || !canStartRecovery(sessionId)) return
            val run = recoveryRepository.claimPending(scope, target, environment, unknownToolsVerified) ?: return
            claimed = run
            consumeRecoveryTarget(target)
            releasedHold = item.holdReason
            val images = messagePersistenceUseCase.loadAttachmentImages(payload.inputAttachments)
            assertScopeCurrent()
            if (epoch != acceptanceEpoch || scope.sessionId in deletedSessionIds || !canStartRecovery(sessionId)) return
            val interruptedResults = (item.unknownTools + item.pendingTools).distinctBy { it.toolCallId }.map { tool ->
                val unknown = tool.status in setOf(RecoveryToolStatus.UNKNOWN, RecoveryToolStatus.STARTED)
                val text = if (unknown) "Execution outcome unknown after interruption. Do not replay this call; verify current state first."
                    else "This tool call was not executed before interruption. No previous approval is restored."
                AgentMessageEntity(
                    id = "tool_${tool.toolCallId}", sessionId = sessionId, role = MessageRole.TOOL.name,
                    content = com.aicode.feature.agent.domain.tool.ToolResult.Error(text, if (unknown) "RESULT_UNKNOWN" else "NOT_EXECUTED").toTransportString(),
                    timestamp = messagePersistenceUseCase.nextTimestamp(), toolCallId = tool.toolCallId, toolName = tool.toolName, isError = true
                )
            }
            if (interruptedResults.isNotEmpty()) {
                if (!recoveryRepository.commitScopedToolResults(scope, target, run.id, interruptedResults)) return
                messagePersistenceUseCase.invalidateHistory(sessionId)
            }
            val resume = run.resumeSnapshot?.inputMessageExists == true
            assertScopeCurrent()
            if (epoch != acceptanceEpoch || scope.sessionId in deletedSessionIds || !canStartRecovery(sessionId)) return
            val childEvent = if (sessionId != scope.sessionId) SubAgentEvent(
                subSessionId = sessionId, parentSessionId = scope.sessionId, type = SubAgentEventType.STARTED,
                generation = target.generation, parentGeneration = scope.generation
            ) else null
            val execution = executeAgentRequestStream(
                request = if (resume) context.getString(R.string.recovery_continue_request) else payload.request,
                modelRequest = payload.modelRequest, currentFile = payload.currentFile, selectedCode = payload.selectedCode,
                projectRoot = payload.projectRoot, inputImages = images, inputAttachments = payload.inputAttachments,
                targetSessionId = sessionId, isAutoTrigger = false, persistUserMessage = true, skipTitleUpdate = childEvent != null,
                subAgentInstructionId = payload.subAgentInstructionId, acceptedRecoveryRun = run, acceptedCapture = capture,
                resuming = resume, notificationSeqs = payload.notificationSeqs, expectedSubAgentEvent = childEvent
            )
            handedOff = true
            // The job can exit before entering the workflow's cleanup block. Finish only if this run still owns the row.
            execution.invokeOnCompletion {
                managerScope.launch {
                    if (recoveryRepository.finish(run.id, RecoveryStatus.INTERRUPTED, releasedHold)) {
                        processNextInQueue(sessionId)
                        flushPendingNotifications(sessionId)
                    }
                }
            }
        } finally {
            if (!handedOff) withContext(NonCancellable + Dispatchers.IO) {
                claimed?.let { runCatching { recoveryRepository.finish(it.id, RecoveryStatus.INTERRUPTED, releasedHold) } }
            }
        }
    }

    private fun recoveryPayload(
        request: String, modelRequest: String, currentFile: String?, selectedCode: String?, projectRoot: String,
        inputAttachments: List<AgentAttachment>, isAutoTrigger: Boolean, persistUserMessage: Boolean, subAgentInstructionId: String?
    ) = RecoveryRequestPayload(request, projectRoot, modelRequest, currentFile, selectedCode, inputAttachments,
        when {
            subAgentInstructionId != null -> RecoveryOrigin.CARD
            isAutoTrigger && !persistUserMessage -> RecoveryOrigin.GOAL
            isAutoTrigger -> RecoveryOrigin.NOTIFICATION
            request.startsWith('/') -> RecoveryOrigin.COMMAND
            else -> RecoveryOrigin.USER
        }, isAutoTrigger, persistUserMessage, subAgentInstructionId)

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String,
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        persistUserMessage: Boolean = true,
        targetSessionId: String?,
        subAgentInstructionId: String? = null
    ) {
        val images = inputImages.map { it.copy() }
        val attachments = inputAttachments.map { it.copy() }
        val source = captureSource()
        val sid = targetSessionId
        if (sid in deletedSessionIds) return
        if (!isAutoTrigger && sid != null) planRejectedSessions.remove(sid)
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isCompleted == false || sid in _runningCommandSessions.value || sid in stoppingSessions ||
                sid in _recoveryBusySessions.value)
        if (isCurrentRunning) {
            val epoch = acceptanceEpoch
            managerScope.launch {
                var acceptedId: String? = null
                var queued = false
                try {
                    if (!awaitReady(sid)) return@launch
                    stopAllSettled.first { it }
                    requestAcceptance.withLock {
                        val entity = sessionUseCase.getSessionById(requireNotNull(sid)) ?: return@withLock
                        if (epoch != acceptanceEpoch || sid in deletedSessionIds) return@withLock
                        val fixedSource = source.getOrThrow()
                        checkSource(fixedSource)
                        val capture = projectMemoryIdentityResolver.capture(entity.workspacePath)?.let { freezeCapture(it, fixedSource) } ?: return@withLock
                        capture.assertCurrent()
                        if (!isAutoTrigger) recoveryRepository.clearExplicitHold(sid)
                        val accepted = recoveryRepository.enqueue(sid,
                            recoveryPayload(request, modelRequest, currentFile, selectedCode, entity.workspacePath,
                                attachments, isAutoTrigger, persistUserMessage, subAgentInstructionId),
                            recoveryCodec.encodeToString(capture.identity))
                        acceptedId = accepted.id
                        if (epoch != acceptanceEpoch || sid in deletedSessionIds) {
                            recoveryRepository.abandon(accepted.id)
                            return@withLock
                        }
                        capture.assertCurrent()
                        val req = QueuedRequest(
                            id = accepted.id,
                            request = request,
                            modelRequest = modelRequest,
                            currentFile = currentFile,
                            selectedCode = selectedCode,
                            projectRoot = entity.workspacePath,
                            inputImages = images,
                            inputAttachments = attachments,
                            isAutoTrigger = isAutoTrigger,
                            persistUserMessage = persistUserMessage,
                            subAgentInstructionId = subAgentInstructionId,
                            recoveryRequestId = accepted.id
                        )
                        val currentList = _queuedRequests.value[sid] ?: emptyList()
                        queuedSources[accepted.id] = fixedSource
                        _queuedRequests.value += sid to (currentList + req)
                        queued = true
                        if (sessionJobs[sid]?.isCompleted != false && sid !in stoppingSessions && sid !in _runningCommandSessions.value) {
                            processNextInQueue(sid)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    setAgentState(requireNotNull(sid), AgentUIState.Error(context.getString(R.string.recovery_request_not_saved)))
                } finally {
                    if (!queued) withContext(NonCancellable + Dispatchers.IO) {
                        acceptedId?.let { recoveryRepository.awaitConfirmation(it) }
                    }
                }
            }
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = images,
                inputAttachments = attachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage,
                subAgentInstructionId = subAgentInstructionId
            )
        }
    }

    fun removeQueuedRequest(sessionId: String, id: String) = managerScope.launch {
        if (!awaitReady(sessionId)) return@launch
        val queue = _queuedRequests.value[sessionId] ?: return@launch
        val removed = queue.firstOrNull { it.id == id } ?: return@launch
        queuedSources.remove(removed.recoveryRequestId ?: removed.id)
        _queuedRequests.value += sessionId to queue.filterNot { it.id == id }
        recordCancelledSubAgentInstructions(sessionId, listOf(removed))
        removed.recoveryRequestId?.let { recoveryRepository.abandon(it) }
    }

    private fun recordCancelledSubAgentInstructions(sessionId: String, requests: List<QueuedRequest>) {
        val instructionIds = requests.mapNotNull { it.subAgentInstructionId }
        if (instructionIds.isEmpty()) return
        managerScope.launch {
            val parentId = sessionUseCase.getSessionById(sessionId)?.parentId ?: return@launch
            instructionIds.forEach { instructionId ->
                runCatching {
                    subAgentEventBus.recordForDisplay(SubAgentEvent(
                        sessionId, parentId, SubAgentEventType.INSTRUCTION_CANCELLED,
                        context.getString(R.string.subagent_interaction_instruction_cancelled), instructionId = instructionId
                    ))
                }.onFailure { FileLogger.w(TAG, "Could not record cancelled subagent instruction", it) }
            }
        }
    }

    fun moveQueuedRequest(sessionId: String, fromIndex: Int, toIndex: Int) = managerScope.launch {
        if (!awaitReady(sessionId)) return@launch
        val queue = _queuedRequests.value[sessionId] ?: return@launch
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return@launch
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        val ids = reordered.mapNotNull { it.recoveryRequestId }
        if (ids.size == reordered.size && !recoveryRepository.reorderQueued(sessionId, ids)) return@launch
        _queuedRequests.value += sessionId to reordered
    }

    fun updateQueuedRequest(sessionId: String, id: String, newText: String) = managerScope.launch {
        if (!awaitReady(sessionId)) return@launch
        val text = newText.trim()
        if (text.isEmpty()) return@launch
        val queue = _queuedRequests.value[sessionId] ?: return@launch
        val old = queue.firstOrNull { it.id == id && it.subAgentInstructionId == null } ?: return@launch
        val updated = old.copy(request = text, modelRequest = syncQueuedModelRequest(old, text))
        val requestId = old.recoveryRequestId
        if (requestId != null && !recoveryRepository.updateQueued(requestId,
                recoveryPayload(updated.request, updated.modelRequest, updated.currentFile, updated.selectedCode,
                    updated.projectRoot, updated.inputAttachments, updated.isAutoTrigger, updated.persistUserMessage, updated.subAgentInstructionId))) return@launch
        _queuedRequests.value += sessionId to _queuedRequests.value[sessionId].orEmpty().map { if (it.id == id) updated else it }
    }

    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) newText.trimEnd() + req.modelRequest.removePrefix(prefix) else newText
    }

    fun interjectQueuedRequest(sessionId: String, id: String) = managerScope.launch {
        if (!awaitReady(sessionId)) return@launch
        val queue = _queuedRequests.value[sessionId] ?: return@launch
        val req = queue.firstOrNull { it.id == id } ?: return@launch
        if (req.subAgentInstructionId != null) return@launch
        planRejectedSessions.remove(sessionId)
        queuedSources.remove(req.recoveryRequestId ?: req.id)?.let(::checkSource)
        req.recoveryRequestId?.let { recoveryRepository.abandon(it) }
        _queuedRequests.value += sessionId to queue.filterNot { it.id == id }
        val eventId = req.recoveryRequestId ?: req.id
        deliverSystemEvent(sessionId, PendingNotification(
            kind = AgentNotificationKind.USER_MESSAGE,
            sourceId = eventId,
            title = "",
            outcome = NotificationOutcome.COMPLETED,
            message = req.request,
            eventId = eventId
        ))
    }

    private fun processNextInQueue(sessionId: String) {
        if (sessionId in planRejectedSessions || sessionId in stoppingSessions || sessionId in deletedSessionIds) return
        if (sessionJobs[sessionId]?.isCompleted == false || sessionId in _runningCommandSessions.value ||
            sessionId in _recoveryBusySessions.value) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value += sessionId to queue.drop(1)
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger,
            persistUserMessage = next.persistUserMessage,
            subAgentInstructionId = next.subAgentInstructionId,
            recoveryRequestId = next.recoveryRequestId
        )
    }

    private fun runResolvedCommand(
        resolved: ResolvedCommand,
        input: String,
        sessionId: String,
        workspacePath: String,
        currentFile: String?,
        selectedCode: String?,
        acceptedSource: Result<RunSource>,
        acceptedRecoveryRun: RecoveryRun? = null,
        acceptedCapture: ProjectMemoryCapture? = null,
        recoveryRequestId: String? = null
    ): Job {
        val compress = resolved is ResolvedCommand.Action && resolved.name == "compress"
        val epoch = acceptanceEpoch
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            val owner = requireNotNull(coroutineContext[Job])
            protectJob(owner)
            var outcome = RecoveryStatus.INTERRUPTED
            var recoveryRun = acceptedRecoveryRun
            var delegated: Job? = null
            var commandCompaction: Job? = null
            var compactionOutcome: RecoveryStatus? = null
            fun checkCommandOwner() {
                if (epoch != acceptanceEpoch || commandOwners[sessionId] !== owner || !owner.isActive ||
                    sessionId in deletedSessionIds || sessionId in stoppingSessions) {
                    throw CancellationException("Command owner changed")
                }
            }
            try {
                if (!awaitReady(sessionId)) return@launch
                stopAllSettled.first { it }
                checkCommandOwner()
                val source = acceptedSource.getOrThrow()
                checkSource(source)
                val capture = (acceptedCapture ?: projectMemoryIdentityResolver.capture(workspacePath))
                    ?.let { freezeCapture(it, source) } ?: return@launch
                capture.assertCurrent()
                val acceptedId = acceptedRecoveryRun?.requestId ?: recoveryRequestId
                val accepted = acceptedId?.let { recoveryRepository.request(it) ?: return@launch }
                if (accepted != null) {
                    val session = sessionUseCase.getSessionById(sessionId) ?: return@launch
                    if (accepted.sessionId != sessionId || accepted.generation != session.executionGeneration ||
                        session.workspacePath != workspacePath ||
                        accepted.environment != recoveryCodec.encodeToString(capture.identity)) {
                        throw CancellationException("Recovery command environment changed")
                    }
                    checkCommandOwner()
                    capture.assertCurrent()
                    recoveryRun = recoveryRun ?: recoveryRepository.claim(accepted.id, accepted.environment)
                    val run = recoveryRun ?: return@launch
                    if (run.requestId != accepted.id || run.sessionId != sessionId || run.generation != accepted.generation ||
                        !recoveryRepository.isCurrent(run.id)) return@launch
                }
                checkCommandOwner()
                capture.assertCurrent()
                val inputId = accepted?.inputMessageId ?: UUID.randomUUID().toString()
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input, id = inputId,
                    requireSession = compress, recoveryRunId = recoveryRun?.id)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                if (compress && (compressionCommandOwners[sessionId] !== owner || !owner.isActive ||
                        sessionId in deletedSessionIds || sessionId in stoppingSessions)) return@launch
                checkCommandOwner()
                capture.assertCurrent()
                kotlinx.coroutines.coroutineScope {
                    val invocationScope = this
                    suspend fun delegate(prompt: String) {
                        checkCommandOwner()
                        capture.assertCurrent()
                        check(delegated == null) { "Command already delegated" }
                        val execution = executeAgentRequestStream(prompt, currentFile = currentFile, selectedCode = selectedCode,
                            projectRoot = workspacePath, targetSessionId = sessionId, isAutoTrigger = true,
                            acceptedRecoveryRun = recoveryRun, acceptedCapture = capture)
                        delegated = execution
                        execution.join()
                    }
                    val invocation = object : SlashCommandContext {
                        override fun compactCurrentSession() {
                            val compaction = startContextCompaction(sessionId, true, workspacePath, currentFile, selectedCode, capture,
                                onFinished = { compactionOutcome = it })
                            if (recoveryRun != null) {
                                commandCompaction = compaction
                                invocationScope.launch { compaction?.join() }
                            }
                        }
                        override fun showUsage() {
                            invocationScope.launch { showUsageNow(sessionId, source) }
                        }
                        override fun initProject(prompt: String) {
                            invocationScope.launch { delegate(prompt) }
                        }
                        override fun runSkill(skill: Skill, args: String) {
                            val fixedSkill = skill.copy(requiredTools = skill.requiredTools.toList())
                            invocationScope.launch { delegate(SlashCommandRegistry.buildSkillPrompt(fixedSkill, args)) }
                        }
                        override fun setSessionGoal(rawArgs: String) {
                            invocationScope.launch { setSessionGoalNow(sessionId, rawArgs, source) }
                        }
                    }
                    when (resolved) {
                        is ResolvedCommand.Action -> resolved.handler.execute(invocation, resolved.args)
                        is ResolvedCommand.SkillCommand -> invocation.runSkill(resolved.skill, resolved.args)
                    }
                }
                checkCommandOwner()
                capture.assertCurrent()
                if (delegated == null) {
                    outcome = if (compress && recoveryRun != null) compactionOutcome ?: RecoveryStatus.INTERRUPTED
                        else RecoveryStatus.COMPLETED
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                outcome = RecoveryStatus.FAILED
                if (commandOwners[sessionId] === owner && sessionId !in deletedSessionIds) {
                    setAgentState(sessionId, AgentUIState.Error(failure.toUserMessage(context)))
                }
            } finally {
                withContext(NonCancellable) {
                    // A delegated workflow owns its outcome; only finish a still-owned row left by an early exit.
                    delegated?.takeIf { !it.isCompleted }?.cancelAndJoin()
                    commandCompaction?.takeIf { !it.isCompleted }?.cancelAndJoin()
                    withContext(Dispatchers.IO) {
                        val run = recoveryRun
                        if (run != null) {
                            runCatching {
                                if (recoveryRepository.isCurrent(run.id)) recoveryRepository.finish(run.id, outcome)
                            }
                        } else if (recoveryRequestId != null) {
                            runCatching { recoveryRepository.awaitConfirmation(recoveryRequestId) }
                        }
                    }
                }
                if (commandOwners[sessionId] === owner) {
                    commandOwners.remove(sessionId)
                    if (compress && compressionCommandOwners[sessionId] === owner) compressionCommandOwners.remove(sessionId)
                    _runningCommandSessions.value -= sessionId
                    flushPendingNotifications(sessionId)
                    processNextInQueue(sessionId)
                }
            }
        }
        commandOwners[sessionId] = job
        if (compress) compressionCommandOwners[sessionId] = job
        _runningCommandSessions.value += sessionId
        job.start()
        return job
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String,
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String?,
        isAutoTrigger: Boolean = false,
        persistUserMessage: Boolean = true,
        skipTitleUpdate: Boolean = false,
        notificationSeqs: List<Long> = emptyList(),
        subAgentInstructionId: String? = null,
        recoveryRequestId: String? = null,
        acceptedRecoveryRun: RecoveryRun? = null,
        acceptedCapture: ProjectMemoryCapture? = null,
        resuming: Boolean = false,
        expectedSubAgentEvent: SubAgentEvent? = null
    ): Job {
        val inputImages = inputImages.map { it.copy() }
        val inputAttachments = inputAttachments.map { it.copy() }
        val notificationSeqs = notificationSeqs.toList()
        val expectedSubAgentEvent = expectedSubAgentEvent?.copy(runMessageIds = expectedSubAgentEvent.runMessageIds.toSet())
        val source = recoveryRequestId?.let { queuedSources.remove(it) }?.let { Result.success(it) } ?: captureSource()
        val resolved = if (subAgentInstructionId == null && request.startsWith('/')) slashCommandRegistry.resolve(request) else null
        if (resolved != null && targetSessionId != null) {
            return runResolvedCommand(resolved, request, targetSessionId, projectRoot, currentFile, selectedCode, source,
                acceptedRecoveryRun, acceptedCapture, recoveryRequestId)
        }
        val epoch = acceptanceEpoch
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            if (!awaitReady(targetSessionId)) return@launch
            stopAllSettled.first { it }
            if (epoch != acceptanceEpoch) return@launch
            val sessionId = targetSessionId ?: requestAcceptance.withLock { ensureSession(projectRoot) }
            if (sessionId.isBlank()) {
                FileLogger.w(TAG, "工作区未就绪，跳过请求")
                return@launch
            }
            if (sessionId in deletedSessionIds) return@launch
            if (resolved != null) {
                runResolvedCommand(resolved, request, sessionId, projectRoot, currentFile, selectedCode, source,
                    acceptedRecoveryRun, acceptedCapture, recoveryRequestId).join()
                return@launch
            }
            if (targetSessionId == null && sessionJobs[sessionId]?.isCompleted == false) {
                enqueueAgentRequest(request, modelRequest, currentFile, selectedCode, projectRoot, inputImages,
                    inputAttachments, isAutoTrigger, persistUserMessage, sessionId, subAgentInstructionId)
                return@launch
            }
            val requestJob = requireNotNull(coroutineContext[Job])
            if (targetSessionId != null && sessionJobs[sessionId] !== requestJob) return@launch
            if (targetSessionId == null) sessionJobs[sessionId] = requestJob
            val currentSession = sessionUseCase.getSessionById(sessionId)
            if (currentSession == null || sessionId in deletedSessionIds || epoch != acceptanceEpoch ||
                sessionJobs[sessionId] !== requestJob) return@launch
            if (expectedSubAgentEvent != null && !isCurrentSubAgentEvent(expectedSubAgentEvent)) {
                subAgentEventBus.release(sessionId)
                return@launch
            }
            if (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank()) {
                val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
                val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
                if (defaultProviderId != null && defaultModel != null) {
                    sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
                }
            }
            if (sessionJobs[sessionId] !== requestJob || epoch != acceptanceEpoch) return@launch
            FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
            agentLiveNotificationManager.markSessionStarted(sessionId)
            setAgentState(sessionId, AgentUIState.Streaming)
            protectJob(requestJob)
            if (!isAutoTrigger && persistUserMessage && notificationSeqs.isEmpty() && !request.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
                turnStartedElapsed[sessionId] = SystemClock.elapsedRealtime()
            } else {
                turnStartedElapsed.putIfAbsent(sessionId, SystemClock.elapsedRealtime())
            }
            val isSubSession = currentSession.parentId != null
            var latestAssistantBodyId: String? = null
            val runMessageIds = linkedSetOf<String>()
            var terminalInteractionRecorded = false
            var turnSucceeded = false
            var sawCompleted = false
            var planRejected = false
            var goalActiveAtTurn = false
            var turnHadActivity = false
            var pendingTitleRequest: (() -> Unit)? = null
            fun startPendingTitle() {
                val start = pendingTitleRequest ?: return
                pendingTitleRequest = null
                start()
            }
            val isGoalTurn = !persistUserMessage
            var recoveryRun = acceptedRecoveryRun
            var acceptedRequestId = recoveryRequestId ?: acceptedRecoveryRun?.requestId
            var executionCapture = acceptedCapture
            var progressCollector: ProjectProgressCollector? = null
            var progressOutcome = "interrupted"
            var recoveryOutcome = RecoveryStatus.INTERRUPTED
            try {
                val root = currentSession.workspacePath
                val fixedSource = source.getOrThrow()
                checkSource(fixedSource)
                val inheritedProject = currentSession.parentId?.let(projectMemoryRuntimeBindings::get)
                executionCapture = executionCapture ?: projectMemoryIdentityResolver.capture(root,
                    projectMemoryJournal.sessionIdentity(sessionId) ?: inheritedProject?.identity, headless = isAutoTrigger || isSubSession)
                val baseCapture = executionCapture?.let { freezeCapture(it, fixedSource) }
                    ?: throw IllegalStateException(context.getString(R.string.recovery_request_not_saved))
                val frozenCapture = if (expectedSubAgentEvent == null || acceptedRecoveryRun != null) baseCapture else ProjectMemoryCapture(
                    baseCapture.identity, baseCapture.channelGeneration
                ) {
                    baseCapture.assertCurrent()
                    if (!isCurrentSubAgentEvent(expectedSubAgentEvent)) throw CancellationException("Subagent parent execution changed")
                }
                frozenCapture.assertCurrent()
                val environment = recoveryCodec.encodeToString(frozenCapture.identity)
                if (epoch != acceptanceEpoch || sessionId in deletedSessionIds) throw CancellationException("Request acceptance changed")
                if (!isAutoTrigger && persistUserMessage && notificationSeqs.isEmpty() && !request.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
                    planRejectedSessions.remove(sessionId)
                    recoveryRepository.clearExplicitHold(sessionId)
                }
                val accepted = acceptedRecoveryRun?.let { recoveryRepository.request(it.requestId) }
                    ?: recoveryRequestId?.let { recoveryRepository.request(it) }
                    ?: recoveryRepository.enqueue(sessionId,
                        recoveryPayload(request, modelRequest, currentFile, selectedCode, root, inputAttachments,
                            isAutoTrigger, persistUserMessage, subAgentInstructionId).let { payload ->
                            if (notificationSeqs.isNotEmpty() || request.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) payload.copy(origin = RecoveryOrigin.NOTIFICATION)
                            else payload
                        }.copy(notificationSeqs = notificationSeqs), environment)
                acceptedRequestId = accepted.id
                if (accepted.environment != environment || accepted.sessionId != sessionId) throw CancellationException("Recovery environment changed")
                frozenCapture.assertCurrent()
                if (epoch != acceptanceEpoch || sessionId in deletedSessionIds || sessionJobs[sessionId] !== requestJob) {
                    throw CancellationException("Request acceptance changed")
                }
                recoveryRun = recoveryRun ?: recoveryRepository.claim(accepted.id, environment)
                val ownedRun = recoveryRun ?: run {
                    recoveryRepository.awaitConfirmation(accepted.id)
                    if (sessionJobs[sessionId] === requestJob) setAgentState(sessionId, AgentUIState.Idle)
                    return@launch
                }
                if (!recoveryRepository.isCurrent(ownedRun.id)) return@launch
                activeRecoveryRuns[sessionId] = ownedRun.id
                if (resuming) fileVersions.beginRecovery(sessionId)
                var failed = false
                if (isSubSession) {
                    val parentId = requireNotNull(currentSession.parentId)
                    if (!subAgentEventBus.tryMarkStarted(sessionId)) {
                        _subAgentWaitingSlots.value += sessionId
                        try {
                            while (!subAgentEventBus.tryMarkStarted(sessionId)) {
                                delay(100)
                                if (acceptedRecoveryRun != null) frozenCapture.assertCurrent()
                            }
                        } finally {
                            _subAgentWaitingSlots.value -= sessionId
                        }
                    }
                    frozenCapture.assertCurrent()
                    subAgentEventBus.recordForDisplay(SubAgentEvent(
                        subSessionId = sessionId,
                        parentSessionId = parentId,
                        type = SubAgentEventType.STARTED,
                        instructionId = subAgentInstructionId,
                        runId = ownedRun.id,
                        generation = ownedRun.generation,
                        parentGeneration = expectedSubAgentEvent?.parentGeneration
                    ))
                }
                val historyStartedNs = System.nanoTime()
                FileLogger.memoryCheckpoint(TAG, "history.start",
                    details = "operation=$historyStartedNs requestChars=${request.length} attachments=${inputAttachments.size}")
                val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
                FileLogger.memoryCheckpoint(TAG, "history.ready", elapsedMs = (System.nanoTime() - historyStartedNs) / 1_000_000,
                    details = "operation=$historyStartedNs messages=${history.size}")
                val isFirst = history.isEmpty()
                val preparedModelRequest = if (resuming) {
                    recoveryContinuationInput(requireNotNull(ownedRun.resumeSnapshot),
                        requireNotNull(recoveryRepository.payload(accepted.id)), accepted.inputMessageId,
                        history, todoItemDao.getBySessionOnce(sessionId).map { it.toDomain() })
                } else modelRequest
                val userMsgId = if (resuming) "resume_${ownedRun.id}" else accepted.inputMessageId
                val persistedRequest = if (notificationSeqs.isNotEmpty() && !resuming) modelRequest else request
                if (!persistUserMessage) {
                    Unit
                } else if (!isAutoTrigger) {
                    if (notificationSeqs.isEmpty()) {
                        messagePersistenceUseCase.persist(sessionId, MessageRole.USER, persistedRequest, id = userMsgId, attachments = inputAttachments,
                            recoveryRunId = ownedRun.id, continuationOfMessageId = accepted.inputMessageId.takeIf { resuming })
                    } else {
                        withContext(NonCancellable) {
                            messagePersistenceUseCase.persist(sessionId, MessageRole.USER, persistedRequest, id = userMsgId, attachments = inputAttachments,
                                recoveryRunId = ownedRun.id, continuationOfMessageId = accepted.inputMessageId.takeIf { resuming }, notificationSeqs = notificationSeqs)
                        }
                    }
                    checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                    if (isFirst && !skipTitleUpdate) {
                        sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                        pendingTitleRequest = {
                            managerScope.launch {
                                agentWorkflow.generateTitle(sessionId, request)?.let { sessionUseCase.updateTitle(sessionId, it) }
                            }
                        }
                    }
                } else {
                    messagePersistenceUseCase.persist(sessionId, MessageRole.USER, modelRequest, id = userMsgId,
                        attachments = inputAttachments, recoveryRunId = ownedRun.id)
                }
                messagePersistenceUseCase.invalidateHistory(sessionId)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                val sessionEntity = sessionUseCase.getSessionById(sessionId)
                val sessionDomain = sessionEntity?.toDomain()
                val agentDefinition = withContext(Dispatchers.IO) {
                    sessionEntity?.takeIf { it.parentId != null }?.subagentType?.let { agentDefinitionRepository.findIncludingDisabled(it) }
                }
                val projectSelection = withContext(Dispatchers.IO) {
                    frozenCapture.assertCurrent()
                    projectMemoryJournal.recoverProgress()
                    projectMemoryJournal.rememberSessionIdentity(sessionId, frozenCapture.identity)
                    projectMemorySessionCoordinator.resolve(sessionId, frozenCapture.identity,
                        if (resuming) recoveryRepository.payload(accepted.id)?.request.orEmpty() else request,
                        explicitRequest = !isAutoTrigger && persistUserMessage && !isSubSession,
                        inheritedProjectId = inheritedProject?.projectId)
                }
                frozenCapture.assertCurrent()
                val resumeContext = projectSelection.context
                projectMemoryRuntimeBindings.remember(sessionId, frozenCapture.identity, resumeContext, projectSelection.projectId, frozenCapture)
                val turnOverride = com.aicode.feature.agent.domain.memory.ProjectMemoryTurnOverride(sessionId, userMsgId)
                progressCollector = ProjectProgressCollector(projectMemoryJournal, frozenCapture.identity, "$sessionId:$userMsgId",
                    true, projectSelection.projectId, projectSelection.allowUnboundDiscovery,
                    projectMemoryPathResolver.freeze(frozenCapture.identity), sourceSessionId = sessionId,
                    channelGeneration = frozenCapture.channelGeneration, turnOverride = turnOverride)
                    .also { projectProgressTerminalTracker.register(sessionId, it) }
                val agentContext = AgentContext(
                    currentFile = currentFile,
                    selectedCode = selectedCode,
                    projectRoot = root,
                    language = currentFile?.let { detectLanguage(it) },
                    history = history,
                    inputImages = inputImages,
                    sessionId = sessionId,
                    inputMessageId = userMsgId,
                    lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
                    goal = sessionDomain?.goal,
                    goalStatus = sessionDomain?.goalStatus,
                    reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
                    agentDefinition = agentDefinition,
                    isSubagent = isSubSession,
                    recoveryRunId = ownedRun.id,
                    requireFreshFileRead = resuming,
                    projectMemoryIdentity = frozenCapture.identity,
                    projectMemoryContext = resumeContext,
                    projectMemoryProjectId = projectSelection.projectId,
                    projectMemoryCapture = frozenCapture,
                    projectMemoryTurnOverride = turnOverride,
                    persistInputSnapshot = persistUserMessage,
                    transientInput = !persistUserMessage,
                    originalUserRequest = request
                )
                val isSub = isSubSession
                val goalActive = sessionDomain?.goalStatus == GoalStatus.ACTIVE
                goalActiveAtTurn = goalActive
                val tools = effectiveSessionTools(agentDefinition, isSub, goalActive)
                var currentReasoningStart: Long? = null
                var currentReasoningEnd: Long? = null
                fun finishReasoning() {
                    val start = currentReasoningStart ?: return
                    if (currentReasoningEnd == null) {
                        val end = System.currentTimeMillis()
                        currentReasoningEnd = end
                        _reasoningTimings.value += sessionId to (start to end)
                    }
                }
                agentWorkflow.executeEvents(
                    userRequest = preparedModelRequest,
                    context = agentContext,
                    tools = tools
                ).flowOn(Dispatchers.Default).collect { event ->
                    frozenCapture.assertCurrent()
                    if (!planRejected && !recoveryRepository.isCurrent(ownedRun.id)) throw CancellationException("Recovery run changed")
                    when (event) {
                        is AgentEvent.AssistantDelta -> {
                            startPendingTitle()
                            if (event.accumulated.hasVisibleContent()) {
                                finishReasoning()
                                turnHadActivity = true
                            }
                            setRetryState(sessionId, null)
                            setKeySwitchState(sessionId, null)
                            setStreamingText(sessionId, event.accumulated)
                        }
                        is AgentEvent.ReasoningDelta -> {
                            startPendingTitle()
                            setRetryState(sessionId, null)
                            setKeySwitchState(sessionId, null)
                            if (currentReasoningStart == null) {
                                currentReasoningStart = System.currentTimeMillis()
                                currentReasoningEnd = null
                            }
                            _reasoningTimings.value += sessionId to (currentReasoningStart!! to currentReasoningEnd)
                            setStreamingReasoning(sessionId, event.accumulated)
                        }
                        is AgentEvent.ToolCallPreparing -> {
                            startPendingTitle()
                            finishReasoning()
                            setPreparingTool(sessionId, event.toolName)
                        }
                        AgentEvent.GenerationStarted -> _preparingToolArguments.value -= sessionId
                        is AgentEvent.GenerationSpeed -> setGenerationSpeed(sessionId, event)
                        is AgentEvent.Retrying -> {
                            _preparingToolArguments.value -= sessionId
                            currentReasoningStart = null
                            currentReasoningEnd = null
                            setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                            setStreamingText(sessionId, null)
                            setStreamingReasoning(sessionId, null)
                            setKeySwitchState(sessionId, null)
                        }
                        is AgentEvent.KeySwitched -> {
                            _preparingToolArguments.value -= sessionId
                            currentReasoningStart = null
                            currentReasoningEnd = null
                            setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                            setStreamingText(sessionId, null)
                            setStreamingReasoning(sessionId, null)
                        }
                        is AgentEvent.CompactionStarted -> {
                            if (!acceptCompactionEvent(sessionId, requestJob, event)) return@collect
                            currentReasoningStart = null
                            currentReasoningEnd = null
                            setRetryState(sessionId, null)
                            setKeySwitchState(sessionId, null)
                            setStreamingText(sessionId, null)
                            setStreamingReasoning(sessionId, null)
                        }
                        is AgentEvent.CompactionProgress, is AgentEvent.CompactionFinished -> acceptCompactionEvent(sessionId, requestJob, event)
                        is AgentEvent.ContextUsage -> _contextUsages.value += sessionId to event
                        is AgentEvent.CompactionFailed -> {
                            if (!acceptCompactionEvent(sessionId, requestJob, event)) return@collect
                            messagePersistenceUseCase.persist(sessionId, MessageRole.TOOL, compactionFailureMessage(event.reason),
                                toolName = COMPACTION_FAILURE_TOOL_NAME, isError = true, requireSession = true)
                        }
                        is AgentEvent.AssistantText -> {
                            startPendingTitle()
                            finishReasoning()
                            val reasoningDuration = currentReasoningStart?.let { start -> currentReasoningEnd?.minus(start) }
                            currentReasoningStart = null
                            currentReasoningEnd = null
                            _reasoningCommits.value += streamingReasoningKey(sessionId) to event.messageId
                            setStreamingReasoning(sessionId, null)
                            setStreamingText(sessionId, null)
                            if (event.toolCalls.isEmpty()) setPreparingTool(sessionId, null)
                            val normalized = if (event.content.hasVisibleContent()) event.content else ""
                            val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                            val msgId = event.messageId
                            if (reasoningDuration != null && reasoningDuration > 0) _reasoningDurations.value += msgId to reasoningDuration
                            messagePersistenceUseCase.persist(
                                sessionId,
                                MessageRole.ASSISTANT,
                                normalized,
                                id = msgId,
                                toolCalls = event.toolCalls,
                                reasoning = reasoning,
                                signature = event.signature.ifEmpty { null },
                                thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                                attachments = event.attachments,
                                inputTokens = event.inputTokens,
                                outputTokens = event.outputTokens,
                                cachedInputTokens = event.cachedInputTokens,
                                contextInputTokens = event.contextUsage?.inputTokens,
                                contextInputBudget = event.contextUsage?.inputBudget,
                                contextEstimated = event.contextUsage?.estimated ?: true,
                                contextBreakdown = event.contextBreakdown,
                                recoveryRunId = ownedRun.id,
                                recoveryStep = event.recoveryStep
                            )
                            if (event.inputTokens > 0 || event.outputTokens > 0) {
                                _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                                runCatching {
                                    chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                    if (event.inputTokens > 0) chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            } else _llmCallEvents.tryEmit(LlmCallEvent(sessionId, 0, 0, 0))
                            runMessageIds += msgId
                            if (normalized.hasVisibleContent()) latestAssistantBodyId = msgId
                            event.persisted?.complete(Unit)
                        }
                        is AgentEvent.ToolCallArgumentsPreview -> {
                            val previews = _preparingToolArguments.value[sessionId].orEmpty()
                            _preparingToolArguments.value += sessionId to (previews +
                                (event.id to PreparingToolPreviewSnapshot("tool_${event.id}", event.toolName, event.accumulated)))
                                .entries.toList().takeLast(32).associate { it.toPair() }
                        }
                        is AgentEvent.ToolExecutionStarted -> {
                            frozenCapture.assertCurrent()
                            withContext(Dispatchers.IO) { progressCollector?.recordStarted(event.id, _runningTools.value[sessionId]?.get("tool_${event.id}")?.toolName.orEmpty()) }
                            recoveryRepository.updateToolTiming(ownedRun.id, event.id, event.startedAtMs, null)
                            _toolExecutionStarts.value += sessionId to (_toolExecutionStarts.value[sessionId].orEmpty() + ("tool_${event.id}" to event.startedElapsedMs))
                        }
                        is AgentEvent.TurnFinished -> {
                            val duration = turnStartedElapsed[sessionId]?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) } ?: event.durationMs
                            event.messageId?.let { recoveryRepository.updateTurnDuration(ownedRun.id, it, duration) }
                        }
                        is AgentEvent.ToolCallStarted -> {
                            turnHadActivity = true
                            val msgId = "tool_${event.id}"
                            runMessageIds += msgId
                            setStreamingText(sessionId, null)
                            setPreparingTool(sessionId, null)
                            toolArgsByMsgId[msgId] = event.argsPreview
                            messagePersistenceUseCase.persist(sessionId, MessageRole.TOOL,
                                "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                                id = msgId, toolCallId = event.id, toolName = event.toolName,
                                toolArgs = event.argsPreview, isError = false, toolActionTitle = event.actionTitle,
                                recoveryRunId = ownedRun.id, recoveryPlaceholder = true)
                            setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                            _preparingToolArguments.value += sessionId to (_preparingToolArguments.value[sessionId].orEmpty() - event.id)
                        }
                        is AgentEvent.ToolCallProgress -> {
                            val msgId = "tool_${event.id}"
                            setRunningTool(sessionId, msgId, RunningToolOutput(msgId, event.accumulated, event.toolName, toolArgsByMsgId[msgId] ?: ""))
                        }
                        is AgentEvent.ToolCallFinished -> {
                            val msgId = "tool_${event.id}"
                            runMessageIds += msgId
                            messagePersistenceUseCase.persist(
                                sessionId,
                                MessageRole.TOOL,
                                event.result,
                                id = msgId,
                                toolCallId = event.id,
                                toolName = event.toolName,
                                toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                                isError = event.isError,
                                attachments = event.attachments,
                                toolActionTitle = event.actionTitle,
                                toolStartedAtMs = event.startedAtMs,
                                toolDurationMs = event.durationMs,
                                recoveryRunId = ownedRun.id,
                                notificationSeqs = event.notificationSeqs
                            )
                            withContext(Dispatchers.IO) {
                                frozenCapture.assertCurrent()
                                progressCollector?.let { collector ->
                                    collector.recordFinished(event.id, event.isError)
                                    collector.recordOutcome(event.isError, event.result)
                                    collector.record(event.toolName, ProjectProgressCollector.projectArguments(event.toolName, event.argsPreview), event.isError, event.id)
                                    if (event.toolName == "terminal") projectProgressTerminalTracker.started(sessionId, collector, event.argsPreview, event.result, event.isError)
                                }
                            }
                            toolArgsByMsgId.remove(msgId)
                            _toolExecutionStarts.value += sessionId to (_toolExecutionStarts.value[sessionId].orEmpty() - msgId)
                            _preparingToolArguments.value += sessionId to (_preparingToolArguments.value[sessionId].orEmpty() - event.id)
                            removeRunningTool(sessionId, msgId)
                            event.persisted?.complete(Unit)
                        }
                        is AgentEvent.Failed -> {
                            if (sessionJobs[sessionId] !== requestJob) return@collect
                            agentLiveNotificationManager.markCompletionOutcome(sessionId, failed = true)
                            failed = true
                            progressOutcome = "failed"
                            recoveryOutcome = RecoveryStatus.FAILED
                            if (goalActiveAtTurn) sessionUseCase.updateGoalStatus(sessionId, GoalStatus.BLOCKED)
                            setCompacting(sessionId, false)
                            setAgentState(sessionId, AgentUIState.Error(describeFailure(event)))
                            if (isSub) {
                                sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                    subAgentEventBus.emit(SubAgentEvent(
                                        subSessionId = sessionId, parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED, detail = event.error,
                                        eventId = "terminal-${ownedRun.id}", runId = ownedRun.id, generation = ownedRun.generation,
                                        parentGeneration = expectedSubAgentEvent?.parentGeneration
                                    ), finish = { recoveryRepository.finish(ownedRun.id, RecoveryStatus.FAILED) })
                                    terminalInteractionRecorded = true
                                }
                            }
                        }
                        AgentEvent.PlanRejected -> {
                            planRejected = true
                            progressOutcome = "interrupted"
                            recoveryOutcome = RecoveryStatus.ABANDONED
                            planRejectedSessions.add(sessionId)
                            val parentId = currentSession.parentId
                            if (parentId != null) {
                                subAgentEventBus.emit(SubAgentEvent(
                                    subSessionId = sessionId, parentSessionId = parentId, type = SubAgentEventType.STOPPED,
                                    detail = context.getString(R.string.plan_approval_stopped), eventId = "terminal-${ownedRun.id}",
                                    runId = ownedRun.id, generation = ownedRun.generation,
                                    parentGeneration = expectedSubAgentEvent?.parentGeneration
                                ), finish = { recoveryRepository.finish(ownedRun.id, RecoveryStatus.ABANDONED, "PLAN_REJECTED") })
                                terminalInteractionRecorded = true
                            } else recoveryRepository.finish(ownedRun.id, RecoveryStatus.ABANDONED, "PLAN_REJECTED")
                            setAgentState(sessionId, AgentUIState.Idle)
                            _completedSessions.value -= sessionId
                            val session = sessionUseCase.getSessionById(sessionId)
                            if (session?.goalStatus == GoalStatus.ACTIVE.name) sessionUseCase.updateGoalStatus(sessionId, GoalStatus.PAUSED)
                        }
                        AgentEvent.Completed -> {
                            if (sessionJobs[sessionId] !== requestJob) return@collect
                            sawCompleted = true
                            setRetryState(sessionId, null)
                            setKeySwitchState(sessionId, null)
                            setCompacting(sessionId, false)
                            if (planRejected || failed) return@collect
                            val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            if (!inForeground && agentSoundSettings.isEnabled()) showAgentCompletedNotification(modelRequest)
                        }
                    }
                }
                if (sessionJobs[sessionId] !== requestJob || !requestJob.isActive || sessionId in deletedSessionIds) return@launch
                turnSucceeded = sawCompleted && !failed && !planRejected
                if (turnSucceeded) {
                    if (acceptedRecoveryRun != null) frozenCapture.assertCurrent()
                    val parentId = currentSession.parentId
                    if (parentId != null) {
                        subAgentEventBus.emit(SubAgentEvent(
                            subSessionId = sessionId, parentSessionId = parentId, type = SubAgentEventType.COMPLETED,
                            eventId = "terminal-${ownedRun.id}", sourceMessageId = latestAssistantBodyId,
                            runMessageIds = runMessageIds.toSet(), runId = ownedRun.id, generation = ownedRun.generation,
                            parentGeneration = expectedSubAgentEvent?.parentGeneration
                        ), finish = { recoveryRepository.finish(ownedRun.id, RecoveryStatus.COMPLETED) })
                        terminalInteractionRecorded = true
                    } else if (!recoveryRepository.finish(ownedRun.id, RecoveryStatus.COMPLETED)) {
                        throw CancellationException("Recovery final state was not committed")
                    }
                    progressOutcome = "completed"
                    recoveryOutcome = RecoveryStatus.COMPLETED
                }
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                if (sessionJobs[sessionId] !== requestJob || !requestJob.isActive || sessionId in deletedSessionIds) return@launch
                val finishedState = _agentStates.value[sessionId]
                if (!failed && !planRejected && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                    agentLiveNotificationManager.markCompletionOutcome(sessionId, failed = false)
                    setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                    _completedSessions.value += sessionId
                }
                setStreamingText(sessionId, null)
            } catch (e: CancellationException) {
                val cancelledState = _agentStates.value[sessionId]
                val isOwnJob = sessionJobs[sessionId] === requestJob
                val recordedStop = externallyRecordedSubAgentStops.remove(sessionId)
                if (isSubSession && !terminalInteractionRecorded && !recordedStop) {
                    withContext(NonCancellable) {
                        runCatching {
                            currentSession.parentId?.let { parentId ->
                                subAgentEventBus.recordForDisplay(SubAgentEvent(
                                    sessionId, parentId, SubAgentEventType.STOPPED,
                                    context.getString(R.string.agent_stopped_by_user),
                                    eventId = recoveryRun?.let { "terminal-${it.id}" } ?: UUID.randomUUID().toString(),
                                    runId = recoveryRun?.id, generation = recoveryRun?.generation,
                                    parentGeneration = expectedSubAgentEvent?.parentGeneration
                                ), finish = { recoveryRun?.let { recoveryRepository.finish(it.id, RecoveryStatus.INTERRUPTED) } ?: true })
                            }
                        }.onFailure { FileLogger.w(TAG, "Could not record interrupted subagent", it) }
                    }
                }
                FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
                if (sessionJobs[sessionId] === requestJob && (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)) {
                    setAgentState(sessionId, AgentUIState.Idle)
                    _completedSessions.value -= sessionId
                }
                if (goalActiveAtTurn) managerScope.launch { runCatching { sessionUseCase.updateGoalStatus(sessionId, GoalStatus.PAUSED) } }
                throw e
            } catch (e: Exception) {
                progressOutcome = "failed"
                recoveryOutcome = RecoveryStatus.FAILED
                if (goalActiveAtTurn && sessionJobs[sessionId] === requestJob) sessionUseCase.updateGoalStatus(sessionId, GoalStatus.BLOCKED)
                FileLogger.e(TAG, "executeAgentRequestStream 失败", e)
                if (sessionJobs[sessionId] === requestJob && sessionId !in deletedSessionIds) {
                    agentLiveNotificationManager.markCompletionOutcome(sessionId, failed = true)
                    setAgentState(sessionId, AgentUIState.Error(e.toUserMessage(context)))
                    _completedSessions.value -= sessionId
                }
                if (isSubSession) {
                    sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                        subAgentEventBus.emit(SubAgentEvent(
                            subSessionId = sessionId, parentSessionId = parentId, type = SubAgentEventType.FAILED,
                            detail = e.toUserMessage(context), eventId = recoveryRun?.let { "terminal-${it.id}" } ?: UUID.randomUUID().toString(),
                            runId = recoveryRun?.id, generation = recoveryRun?.generation,
                            parentGeneration = expectedSubAgentEvent?.parentGeneration
                        ), finish = { recoveryRun?.let { recoveryRepository.finish(it.id, RecoveryStatus.FAILED) } ?: true })
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    startPendingTitle()
                    withContext(Dispatchers.IO) {
                        if (recoveryRun == null) acceptedRequestId?.let { runCatching { recoveryRepository.awaitConfirmation(it) } }
                        recoveryRun?.let { runCatching { recoveryRepository.finish(it.id, recoveryOutcome) } }
                        progressCollector?.let { collector ->
                            runCatching { collector.save(progressOutcome) }
                            projectProgressTerminalTracker.release(sessionId, collector)
                        }
                    }
                    val isOwnJob = sessionJobs[sessionId] === requestJob
                    FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
                    if (isOwnJob) {
                        if (isSubSession) subAgentEventBus.release(sessionId)
                        sessionJobs.remove(sessionId)
                        activeRecoveryRuns.remove(sessionId)
                        _runningTools.value -= sessionId
                        _preparingToolArguments.value -= sessionId
                        _toolExecutionStarts.value -= sessionId
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setPreparingTool(sessionId, null)
                        setCompacting(sessionId, false)
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        val timing = _reasoningTimings.value[sessionId]
                        if (timing != null && timing.second == null) _reasoningTimings.value += sessionId to (timing.first to System.currentTimeMillis())
                        if (sessionId !in stoppingSessions && sessionId !in deletedSessionIds) {
                            if (planRejected) processNextInQueue(sessionId)
                            if (notificationSeqs.isEmpty() || agentNotificationCenter.peek(sessionId).none { it.seq in notificationSeqs }) flushPendingNotifications(sessionId)
                            val currentState = _agentStates.value[sessionId]
                            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) setAgentState(sessionId, AgentUIState.Idle)
                            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                                if (turnSucceeded) runCatching { maybeQueueGoalContinuation(sessionId, isGoalTurn, turnHadActivity, source.getOrThrow()) }
                                processNextInQueue(sessionId)
                            }
                        }
                    }
                }
            }
        }
        if (targetSessionId != null) {
            sessionJobs[targetSessionId] = job
            job.invokeOnCompletion {
                if (sessionJobs[targetSessionId] === job) sessionJobs.remove(targetSessionId)
            }
        }
        job.start()
        return job
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    fun resolvePlanApproval(id: String, approved: Boolean, feedback: String = "") {
        planApprovalManager.resolve(id, PlanApprovalDecision(approved, if (approved) "" else feedback))
    }

    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    fun stopAllAgents() {
        acceptanceEpoch++
        val jobs = (sessionJobs.values + compressionCommandOwners.values + commandOwners.values).distinct().filter { !it.isCompleted }
        compressionCommandOwners.clear()
        commandOwners.clear()
        val stoppedSessions = (sessionJobs.keys + _agentStates.value.keys + _runningCommandSessions.value +
            planApprovalManager.awaitingSessionIds.value + _recoveryBusySessions.value).toSet()
        planRejectedSessions.addAll(stoppedSessions)
        pendingNotificationStops++
        stopAllSettled.value = false
        managerScope.launch {
            try {
                if (!awaitReady()) return@launch
                val parents = stoppedSessions.mapNotNull { sessionUseCase.getSessionById(it)?.parentId }
                val recipients = stoppedSessions + parents + agentNotificationCenter.pendingRecipientIds()
                planRejectedSessions.addAll(recipients)
                jobs.forEach { it.join() }
                recipients.forEach { recoveryRepository.holdSession(it, "USER_STOPPED") }
                recipients.forEach { agentNotificationCenter.clear(it) }
            } finally {
                pendingNotificationStops--
                if (pendingNotificationStops == 0) stopAllSettled.value = true
            }
        }
        _queuedRequests.value.forEach { (id, requests) ->
            recordCancelledSubAgentInstructions(id, requests)
            requests.mapNotNull { it.recoveryRequestId }.forEach { requestId ->
                managerScope.launch { recoveryRepository.abandon(requestId) }
            }
        }
        _queuedRequests.value = emptyMap()
        queuedSources.clear()
        goalEmptyTurnCounts.clear()
        val activeSubIds = subAgentEventBus.activeSubSessionIds.value.toList()
        activeSubIds.forEach { subAgentEventBus.release(it) }
        activeSubIds.forEach { id ->
            if (_subAgentCards.value.containsKey(id)) markSubAgentFinished(id, SubAgentCardStatus.STOPPED)
        }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _reasoningTimings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        compactionOwners.clear()
        _compactionPercents.value = emptyMap()
        _compactingSessions.value = emptyMap()
    }

    fun stopAgentSession(sessionId: String) {
        val commandJob = commandOwners[sessionId]
        val job = sessionJobs[sessionId] ?: compressionCommandOwners[sessionId] ?: commandJob ?: return
        if (!job.isActive) return
        stoppingSessions.add(sessionId)
        if (planApprovalManager.pendingForSession(sessionId) != null) planRejectedSessions.add(sessionId)
        subAgentEventBus.release(sessionId)
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val toolStartsAtStop = _toolExecutionStarts.value[sessionId].orEmpty()
        val stoppedElapsed = SystemClock.elapsedRealtime()
        val streamingText = _streamingTexts.value[sessionId]
        val streamingReasoning = _streamingReasonings.value[sessionId]
        val pendingPermission = toolPermissionManager.pendingForSession(sessionId)
        val recoveryAtStop = activeRecoveryRuns[sessionId]
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        FileLogger.d(TAG, "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} state=${_agentStates.value[sessionId]}")
        job.cancel()
        if (commandJob !== job) commandJob?.cancel()
        managerScope.launch {
            var sessionExists = false
            try {
                job.join()
                commandJob?.join()
                sessionExists = sessionUseCase.getSessionById(sessionId) != null
                if (!sessionExists) return@launch
                if (recoveryAtStop == null && runningTools.isNotEmpty()) {
                    runningTools.forEach { running ->
                        val partial = running.text.trimEnd()
                        val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                        messagePersistenceUseCase.persist(
                            sessionId = sessionId,
                            role = MessageRole.TOOL,
                            content = content,
                            id = running.messageId,
                            toolCallId = running.messageId.removePrefix("tool_"),
                            toolName = running.toolName.ifBlank { null },
                            toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                            isError = true,
                            toolDurationMs = toolStartsAtStop[running.messageId]?.let { (stoppedElapsed - it).coerceAtLeast(0L) }
                        )
                        toolArgsByMsgId.remove(running.messageId)
                    }
                } else if (recoveryAtStop == null && (!streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty())) {
                    val partial = (streamingText ?: "").trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(sessionId = sessionId, role = MessageRole.ASSISTANT,
                        content = content, reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() })
                }
                if (recoveryAtStop == null && pendingPermission != null) {
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = stoppedText,
                        id = "tool_${pendingPermission.id}",
                        toolCallId = pendingPermission.id,
                        toolName = pendingPermission.toolName,
                        isError = true
                    )
                }
            } finally {
                stoppingSessions.remove(sessionId)
                if (sessionExists && sessionJobs[sessionId]?.isCompleted != false) {
                    setAgentState(sessionId, AgentUIState.Idle)
                    _runningTools.value -= sessionId
                    setStreamingText(sessionId, null)
                    setStreamingReasoning(sessionId, null)
                    setCompacting(sessionId, false)
                    setRetryState(sessionId, null)
                }
                if (sessionExists) {
                    processNextInQueue(sessionId)
                    flushPendingNotifications(sessionId)
                }
            }
        }
    }

    private fun clearSessionRuntimeState(sessionId: String) {
        acceptanceEpoch++
        deletedSessionIds.add(sessionId)
        messagePersistenceUseCase.releaseSessionCache(sessionId)
        activeRecoveryRuns.remove(sessionId)
        _consumedRecoveryTargets.value = _consumedRecoveryTargets.value.filterValues { it.sessionId != sessionId }
        projectMemoryRuntimeBindings.release(sessionId)
        managerScope.launch(Dispatchers.IO) { projectMemoryJournal.clearSession(sessionId) }
        projectProgressTerminalTracker.clearSession(sessionId)
        compressionCommandOwners.remove(sessionId)?.cancel()
        commandOwners.remove(sessionId)?.cancel()
        _runningCommandSessions.value -= sessionId
        setCompacting(sessionId, false)
        _preparingToolArguments.value -= sessionId
        _toolExecutionStarts.value -= sessionId
        _subAgentWaitingSlots.value -= sessionId
        turnStartedElapsed.remove(sessionId)
        subAgentEventBus.release(sessionId)
        if (sessionJobs[sessionId]?.isCompleted == false) externallyRecordedSubAgentStops.add(sessionId)
        managerScope.launch { agentNotificationCenter.clear(sessionId) }
        _queuedRequests.value[sessionId].orEmpty().forEach { queuedSources.remove(it.recoveryRequestId ?: it.id) }
        _queuedRequests.value -= sessionId
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        _agentStates.value -= sessionId
        _streamingTexts.value -= sessionId
        setStreamingReasoning(sessionId, null)
        _runningTools.value -= sessionId
        _retryStates.value -= sessionId
        setGenerationSpeed(sessionId, null)
        _deletedSessions.value += sessionId
    }

    suspend fun deleteSessions(ids: Set<String>, notifyParent: Boolean = false): Set<String> {
        val fixedIds = ids.toSet()
        return managerScope.async {
            if (fixedIds.isEmpty() || !awaitReady()) return@async emptySet()
            val allDeletedIds = mutableSetOf<String>()
            fixedIds.forEach { id ->
                if (notifyParent) {
                    allDeletedIds += deleteSessionNow(id)
                } else {
                    deletedSessionIds.add(id)
                    val deletedSession = sessionUseCase.getSessionById(id)
                    if (deletedSession?.parentId != null) subAgentEventBus.release(id)
                    checkpointManager.clearSessionCheckpoints(id)
                    allDeletedIds.addAll(sessionUseCase.deleteSession(id))
                }
            }
            if (!notifyParent) allDeletedIds.forEach { clearSessionRuntimeState(it) }
            allDeletedIds.toSet()
        }.await()
    }

    suspend fun deleteSession(id: String): Set<String> = managerScope.async {
        if (!awaitReady(id)) return@async emptySet()
        deleteSessionNow(id)
    }.await()

    private suspend fun deleteSessionNow(id: String): Set<String> {
        deletedSessionIds.add(id)
        val deletedSession = sessionUseCase.getSessionById(id)
        deletedSession?.parentId?.let { parentId ->
            subAgentEventBus.recordForDisplay(SubAgentEvent(
                id, parentId, SubAgentEventType.DELETED,
                context.getString(R.string.subagent_interaction_deleted_notice), origin = "USER"
            ))
        }
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }
        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id).toSet()
        deletedIds.forEach { clearSessionRuntimeState(it) }
        stoppedSubAgent?.let { sub ->
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。"
            )
        }
        return deletedIds
    }

    suspend fun rewind(sessionId: String, messageId: String, option: RewindOption): RewindResult? {
        val source = captureSource()
        return managerScope.async {
            if (!awaitReady(sessionId)) return@async null
            checkSource(source.getOrThrow())
            _queuedRequests.value[sessionId].orEmpty().forEach { queuedSources.remove(it.recoveryRequestId ?: it.id) }
            _queuedRequests.value += sessionId to emptyList()
            agentNotificationCenter.clear(sessionId)
            acceptanceEpoch++
            recoveryRepository.invalidateSession(sessionId, "rewind")
            agentWorkflow.discardCompactionCheckpoint(sessionId)
            projectMemoryRuntimeBindings.release(sessionId)
            projectProgressTerminalTracker.clearSession(sessionId)
            stoppingSessions.add(sessionId)
            try {
                compressionCommandOwners.remove(sessionId)?.cancelAndJoin()
                commandOwners.remove(sessionId)?.cancelAndJoin()
                _runningCommandSessions.value -= sessionId
                val runningJob = sessionJobs[sessionId]
                if (runningJob != null && !runningJob.isCompleted) runningJob.cancelAndJoin()
                sessionJobs.remove(sessionId)
                setAgentState(sessionId, AgentUIState.Idle)
                _runningTools.value -= sessionId
                setStreamingText(sessionId, null)
                setStreamingReasoning(sessionId, null)
                setCompacting(sessionId, false)
                _contextUsages.value -= sessionId
                setGenerationSpeed(sessionId, null)
                setRetryState(sessionId, null)
                checkpointManager.setActiveCheckpointId(sessionId, null)
                val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
                val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@async null
                if (targetMsgEntity.sessionId != sessionId || targetMsgEntity.isCompactionMarker || targetMsgEntity.isContextSummary) return@async null
                val attachments = targetMsgEntity.toUIMessage().attachments.map { it.copy() }
                var conflicts = emptyList<String>()
                var fillPrompt: String? = null
                val fixedSource = source.getOrThrow()
                checkSource(fixedSource)
                val entity = sessionUseCase.getSessionById(sessionId) ?: return@async null
                val capture = projectMemoryIdentityResolver.capture(entity.workspacePath)?.let { freezeCapture(it, fixedSource) } ?: return@async null
                capture.assertCurrent()
                val execution = workspaceExecutionContextFactory.freeze(capture)
                capture.assertCurrent()
                withContext(execution) {
                    when (option) {
                        RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                            if (checkpoint != null) conflicts = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id).skipped.toList()
                            capture.assertCurrent()
                            messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                            fillPrompt = targetMsgEntity.content
                        }
                        RewindOption.RESTORE_CONVERSATION -> {
                            messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                            fillPrompt = targetMsgEntity.content
                        }
                        RewindOption.RESTORE_CODE -> {
                            if (checkpoint != null) conflicts = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id).skipped.toList()
                        }
                    }
                }
                RewindResult(fillPrompt, attachments, conflicts)
            } finally {
                stoppingSessions.remove(sessionId)
            }
        }.await()
    }

    fun initProject(
        sessionId: String?, workspacePath: String, prompt: String,
        currentFile: String? = null, selectedCode: String? = null
    ): Job = executeAgentRequestStream(request = prompt, currentFile = currentFile, selectedCode = selectedCode,
        projectRoot = workspacePath, targetSessionId = sessionId, isAutoTrigger = true)

    fun runSkill(
        sessionId: String?, workspacePath: String, skill: Skill, args: String,
        currentFile: String? = null, selectedCode: String? = null
    ): Job = executeAgentRequestStream(request = SlashCommandRegistry.buildSkillPrompt(skill.copy(requiredTools = skill.requiredTools.toList()), args),
        currentFile = currentFile, selectedCode = selectedCode, projectRoot = workspacePath, targetSessionId = sessionId, isAutoTrigger = true)

    fun showUsage(sessionId: String?, workspacePath: String): Job = launchLocalCommand(sessionId, workspacePath) { sid, source -> showUsageNow(sid, source) }

    fun setSessionGoal(sid: String, rawArgs: String): Job = launchLocalCommand(sid, null) { id, source -> setSessionGoalNow(id, rawArgs, source) }

    fun setGoalPaused(sid: String, paused: Boolean): Job = launchLocalCommand(sid, null) { id, source -> if (paused) pauseGoal(id, source) else resumeGoal(id, source) }

    fun clearGoal(sid: String): Job = launchLocalCommand(sid, null) { id, source -> clearGoalNow(id, source) }

    private fun launchLocalCommand(sessionId: String?, workspacePath: String?, action: suspend (String, RunSource) -> Unit): Job {
        val epoch = acceptanceEpoch
        val source = captureSource()
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            var sid = sessionId
            val owner = requireNotNull(coroutineContext[Job])
            protectJob(owner)
            try {
                if (!awaitReady(sid)) return@launch
                stopAllSettled.first { it }
                if (epoch != acceptanceEpoch) return@launch
                sid = sid ?: requestAcceptance.withLock { ensureSession(workspacePath.orEmpty()) }
                if (sid.isBlank() || sid in deletedSessionIds) return@launch
                if (sessionId == null) {
                    commandOwners[sid] = owner
                    _runningCommandSessions.value += sid
                }
                if (commandOwners[sid] !== owner) return@launch
                val fixedSource = source.getOrThrow()
                checkSource(fixedSource)
                action(sid, fixedSource)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sid?.let { setAgentState(it, AgentUIState.Error(failure.toUserMessage(context))) }
            } finally {
                sid?.let { id ->
                    if (commandOwners[id] === owner) {
                        commandOwners.remove(id)
                        _runningCommandSessions.value -= id
                        flushPendingNotifications(id)
                        processNextInQueue(id)
                    }
                }
            }
        }
        if (sessionId != null) {
            commandOwners[sessionId] = job
            _runningCommandSessions.value += sessionId
        }
        job.start()
        return job
    }

    private suspend fun showUsageNow(sid: String, source: RunSource) {
        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = loadUsageSummary(todayStart)
        val allTime = loadUsageSummary(0L)
        val table = buildString {
            appendLine("| 项目 | 今日 | 累计 |")
            appendLine("|---|---|---|")
            appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
            appendLine("| 输入 tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
            appendLine("| 输出 tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
            appendLine("| 缓存命中 tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
            appendLine("| 预估费用 | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
        }
        checkSource(source)
        sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
        checkSource(source)
        messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
    }

    private suspend fun setSessionGoalNow(sid: String, rawArgs: String, source: RunSource) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        checkSource(source)
        val arg = rawArgs.trim()
        when {
            arg.isEmpty() -> persistGoalNotice(sid, goalSummary(session))
            arg.equals("clear", ignoreCase = true) || arg.equals("off", ignoreCase = true) -> clearGoalNow(sid, source)
            arg.equals("pause", ignoreCase = true) -> pauseGoal(sid, source)
            arg.equals("resume", ignoreCase = true) -> resumeGoal(sid, source)
            else -> setGoalText(sid, session, arg, source)
        }
    }

    private fun goalSummary(session: ChatSessionEntity): String {
        val goal = session.goal?.takeIf { it.isNotBlank() }
            ?: return "当前会话未设定目标。用 `/goal <目标>` 设定后，AI 会自主循环推进直到达成、受阻或预算耗尽。"
        val status = session.goalStatus?.let { runCatching { GoalStatus.valueOf(it) }.getOrNull() } ?: GoalStatus.ACTIVE
        val statusLabel = when (status) {
            GoalStatus.ACTIVE -> "进行中"
            GoalStatus.PAUSED -> "已暂停"
            GoalStatus.COMPLETED -> "已完成"
            GoalStatus.BLOCKED -> "受阻"
            GoalStatus.EXHAUSTED -> "预算耗尽"
        }
        return "当前目标（$statusLabel，已续跑 ${session.goalTurnCount} 轮）：\n$goal"
    }

    private suspend fun setGoalText(sid: String, session: ChatSessionEntity, text: String, source: RunSource) {
        checkSource(source)
        val goalText = text.trim().take(MAX_GOAL_CHARS)
        if (goalText.isEmpty()) return
        goalEmptyTurnCounts.remove(sid)
        sessionUseCase.setGoal(sid, goalText, session.totalInputTokens, session.totalOutputTokens)
        persistGoalNotice(sid, "已设定目标：$goalText\nAI 将自主循环推进，直到达成、受阻或预算耗尽。" + "可随时 `/goal pause` / `/goal resume` / `/goal clear`。")
        checkSource(source)
        enqueueGoalTurn(sid, session.workspacePath, buildGoalPrompt(goalText, 1))
    }

    private suspend fun clearGoalNow(sid: String, source: RunSource) {
        checkSource(source)
        goalEmptyTurnCounts.remove(sid)
        sessionUseCase.setGoal(sid, null, 0, 0)
        persistGoalNotice(sid, "已清除当前目标。")
    }

    private suspend fun pauseGoal(sid: String, source: RunSource) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        checkSource(source)
        if (session.goal.isNullOrBlank()) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        sessionUseCase.updateGoalStatus(sid, GoalStatus.PAUSED)
        persistGoalNotice(sid, "目标已暂停。用 `/goal resume` 继续。")
    }

    private suspend fun resumeGoal(sid: String, source: RunSource) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        checkSource(source)
        val goal = session.goal?.takeIf { it.isNotBlank() }
        if (goal == null) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        planRejectedSessions.remove(sid)
        recoveryRepository.clearExplicitHold(sid)
        sessionUseCase.updateGoalStatus(sid, GoalStatus.ACTIVE)
        persistGoalNotice(sid, "目标已恢复，继续自主循环。")
        checkSource(source)
        enqueueGoalTurn(sid, session.workspacePath, buildGoalPrompt(goal, session.goalTurnCount + 1))
    }

    private suspend fun maybeQueueGoalContinuation(sessionId: String, isGoalTurn: Boolean, hadActivity: Boolean, source: RunSource) {
        val session = sessionUseCase.getSessionById(sessionId) ?: return
        checkSource(source)
        val goal = session.goal?.takeIf { it.isNotBlank() } ?: return
        if (session.goalStatus != GoalStatus.ACTIVE.name) return
        if (!_queuedRequests.value[sessionId].isNullOrEmpty()) return
        if (isGoalTurn) {
            if (hadActivity) goalEmptyTurnCounts.remove(sessionId) else {
                val n = (goalEmptyTurnCounts[sessionId] ?: 0) + 1
                if (n >= MAX_GOAL_EMPTY_TURNS) {
                    goalEmptyTurnCounts.remove(sessionId)
                    sessionUseCase.updateGoalStatus(sessionId, GoalStatus.BLOCKED)
                    persistGoalNotice(sessionId, "目标自主循环连续 $MAX_GOAL_EMPTY_TURNS 轮无进展，已置为「受阻」并停止。可 `/goal resume` 继续或 `/goal clear` 清除。")
                    return
                }
                goalEmptyTurnCounts[sessionId] = n
            }
        }
        val maxTurns = generalSettingsRepository.goalMaxTurns()
        val maxTokens = generalSettingsRepository.goalMaxTokens()
        val usedTokens = (session.totalInputTokens - session.goalStartInputTokens).coerceAtLeast(0) +
            (session.totalOutputTokens - session.goalStartOutputTokens).coerceAtLeast(0)
        if (session.goalTurnCount >= maxTurns || usedTokens >= maxTokens) {
            sessionUseCase.updateGoalStatus(sessionId, GoalStatus.EXHAUSTED)
            persistGoalNotice(sessionId, "目标自主循环已达预算上限（轮次上限 $maxTurns 轮 / token 预算 $maxTokens），已停止。" + "可 `/goal resume` 继续或 `/goal clear` 清除。")
            return
        }
        sessionUseCase.incrementGoalTurn(sessionId)
        checkSource(source)
        enqueueGoalTurn(sessionId, session.workspacePath, buildGoalPrompt(goal, session.goalTurnCount + 1))
    }

    private fun enqueueGoalTurn(sessionId: String, workspacePath: String, prompt: String) {
        enqueueAgentRequest(request = prompt, modelRequest = prompt, projectRoot = workspacePath,
            isAutoTrigger = true, persistUserMessage = false, targetSessionId = sessionId)
    }

    private fun buildGoalPrompt(goal: String, turn: Int): String =
        "【目标自主循环 · 第 $turn 轮】继续推进目标：$goal\n" + "按系统提示中的完成审计与受阻审计规则决定是否调用 goalStatus；未达成则直接继续下一步。"

    private suspend fun persistGoalNotice(sid: String, text: String) {
        sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
        messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, text, isContextExcluded = true)
    }

    private data class UsageSummary(val calls: Int, val inputTokens: Long, val outputTokens: Long, val cachedInputTokens: Long, val costUsd: Double)

    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }

    fun compactSession(sid: String, workspacePath: String, currentFile: String? = null, selectedCode: String? = null): Job? =
        startContextCompaction(sid, false, workspacePath, currentFile, selectedCode)

    private fun effectiveSessionTools(definition: AgentDefinition?, isSub: Boolean, goalActive: Boolean): List<com.aicode.feature.agent.domain.tool.AgentTool> {
        val all = toolRegistry.getAvailableTools()
        return when {
            definition != null -> {
                val allowed = definition.filterToolNames(all.map { it.name }).toSet()
                all.filter { it.name in allowed }
            }
            isSub -> all.filterNot { it.name == AgentDefinition.NESTED_TOOL }
            else -> all.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
        }.let { if (goalActive) it else it.filterNot { tool -> tool.name == "goalStatus" } }
    }

    private fun startContextCompaction(
        sid: String, allowCommand: Boolean, expectedWorkspace: String, currentFile: String? = null, selectedCode: String? = null,
        acceptedCapture: ProjectMemoryCapture? = null,
        onFinished: ((RecoveryStatus) -> Unit)? = null
    ): Job? {
        if (sid in deletedSessionIds || sid in stoppingSessions || sid in approvalSessionIds.value ||
            (!allowCommand && (sid in _runningCommandSessions.value || sid in _recoveryBusySessions.value)) ||
            _compactingSessions.value[sid] == true || sessionJobs[sid]?.isCompleted == false) return null
        val epoch = acceptanceEpoch
        val source = captureSource()
        val job = managerScope.launch(start = CoroutineStart.LAZY) {
            val owner = requireNotNull(coroutineContext[Job])
            protectJob(owner)
            var failed = false
            var outcome = RecoveryStatus.INTERRUPTED
            suspend fun writable(): Boolean = sessionJobs[sid] === owner && owner.isActive &&
                sid !in deletedSessionIds && sid !in stoppingSessions && sessionUseCase.getSessionById(sid) != null
            try {
                if (!awaitReady(sid)) return@launch
                stopAllSettled.first { it }
                if (epoch != acceptanceEpoch || !writable()) return@launch
                setCompacting(sid, true)
                val fixedSource = source.getOrThrow()
                checkSource(fixedSource)
                val entity = sessionUseCase.getSessionById(sid) ?: return@launch
                if (entity.workspacePath != expectedWorkspace || sid in deletedSessionIds) return@launch
                val capture = (acceptedCapture ?: projectMemoryIdentityResolver.capture(entity.workspacePath))?.let { freezeCapture(it, fixedSource) } ?: return@launch
                capture.assertCurrent()
                val domain = entity.toDomain()
                val definition = withContext(Dispatchers.IO) {
                    entity.subagentType?.takeIf { entity.parentId != null }?.let { agentDefinitionRepository.findIncludingDisabled(it) }
                }
                if (!writable()) return@launch
                capture.assertCurrent()
                val snapshot = AgentContext(currentFile = currentFile, selectedCode = selectedCode,
                    language = currentFile?.let { detectLanguage(it) }, projectRoot = entity.workspacePath,
                    sessionId = sid, goal = domain.goal, goalStatus = domain.goalStatus,
                    reasoningEffort = domain.reasoningEffort?.apiValue, agentDefinition = definition,
                    isSubagent = entity.parentId != null,
                    projectMemoryCapture = capture, projectMemoryIdentity = capture.identity)
                val tools = effectiveSessionTools(definition, entity.parentId != null, domain.goalStatus == GoalStatus.ACTIVE)
                val changed = agentWorkflow.compactSession(sid, snapshot, tools) { event ->
                    capture.assertCurrent()
                    if (!acceptCompactionEvent(sid, owner, event)) return@compactSession
                    if (event is AgentEvent.CompactionFailed) {
                        failed = true
                        if (writable()) messagePersistenceUseCase.persist(sessionId = sid, role = MessageRole.TOOL,
                            content = compactionFailureMessage(event.reason), toolName = COMPACTION_FAILURE_TOOL_NAME, isError = true, requireSession = true)
                    }
                }
                capture.assertCurrent()
                if (!failed && !changed && writable()) {
                    messagePersistenceUseCase.persist(sessionId = sid, role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction), requireSession = true)
                }
                outcome = if (failed) RecoveryStatus.FAILED else RecoveryStatus.COMPLETED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome = RecoveryStatus.FAILED
                FileLogger.w(TAG, "手动压缩未完成: session=$sid")
                if (writable()) messagePersistenceUseCase.persist(sessionId = sid, role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, compactionFailureMessage("compaction_failed")), requireSession = true)
            } finally {
                onFinished?.invoke(outcome)
                if (sessionJobs[sid] === owner) {
                    setCompacting(sid, false)
                    sessionJobs.remove(sid)
                    if (sid !in deletedSessionIds && sid !in stoppingSessions) {
                        flushPendingNotifications(sid)
                        processNextInQueue(sid)
                    }
                }
            }
        }
        sessionJobs[sid] = job
        job.start()
        return job
    }

    private suspend fun ensureSession(workspacePath: String): String {
        if (workspacePath.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(workspacePath)?.id ?: createAndUpsertSession(workspacePath)
        _resolvedSessions.value += workspacePath to id
        return id
    }

    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val session = createSession(workspacePath)
        sessionUseCase.upsertSession(session)
        return session.id
    }

    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
            else ReasoningEffort.MEDIUM.name
        return sessionUseCase.newSessionEntity(workspacePath = workspacePath, providerId = providerId, model = model, reasoningEffort = effort)
    }

    private fun detectLanguage(filePath: String): String = when (filePath.substringAfterLast(".").lowercase()) {
        "kt", "kotlin" -> "kotlin"
        "java" -> "java"
        "dart" -> "dart"
        "py" -> "python"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "go" -> "go"
        "rs" -> "rust"
        else -> "text"
    }

    private companion object {
        const val TAG = "AgentRunManager"
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        const val MAX_SUBAGENT_CARDS = 50
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        const val MAX_GOAL_CHARS = 2000
        const val MAX_GOAL_EMPTY_TURNS = 3
    }
}
