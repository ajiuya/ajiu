package com.aicode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.core.util.toUserMessage
import com.aicode.core.util.formatCostUsd
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import com.aicode.feature.settings.data.repository.LiveNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ApprovalNotificationSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.context.CompactionPhase
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.asDirtySignal
import com.aicode.feature.agent.domain.subagent.SubAgentDigester
import com.aicode.feature.agent.domain.subagent.SubAgentDigest
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.takeTailLines
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.recovery.*
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemorySessionCoordinator
import com.aicode.feature.agent.domain.memory.ProjectMemoryRuntimeBindings
import com.aicode.feature.agent.domain.memory.ProjectMemoryPathResolver
import com.aicode.feature.agent.domain.memory.ProjectProgressCollector
import com.aicode.feature.agent.domain.memory.ProjectProgressTerminalTracker
import com.aicode.feature.agent.presentation.component.SessionRecoveryItemUi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalDecision
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalManager
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.mention.FileMentionRepository
import com.aicode.feature.agent.domain.mention.FileMentionState
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.notification.AgentLiveNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager
import com.aicode.feature.agent.presentation.notification.AgentApprovalNotificationManager.ApprovalKind
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.toolRunningLabelRes
import com.aicode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import com.aicode.feature.agent.presentation.preview.PreparingToolPreviewSnapshot
import com.aicode.feature.agent.domain.tool.ToolPresentation
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val planApprovalManager: PlanApprovalManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val terminalSessionManager: TerminalSessionManager,
    private val terminalSessionProvider: TerminalSessionProvider,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val fileMentionRepository: FileMentionRepository,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerOsDetector: ContainerOsDetector,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val rootAccessSettingsRepository: RootAccessSettingsRepository,
    private val liveNotificationSettings: LiveNotificationSettingsRepository,
    private val agentLiveNotificationManager: AgentLiveNotificationManager,
    private val approvalNotificationSettings: ApprovalNotificationSettingsRepository,
    private val agentApprovalNotificationManager: AgentApprovalNotificationManager,
    private val subAgentEventBus: SubAgentEventBus,
    private val subAgentInteractionReader: SubAgentInteractionReader,
    private val subAgentInstructionGateway: SubAgentInstructionGateway,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    val browserManager: com.aicode.feature.agent.domain.tool.browser.BrowserManager,
    val previewPathMapper: WorkspacePathMapper,
    private val toolPreviewFileAccessAdapter: ToolPreviewFileAccessAdapter,
    private val fileChangeHub: FileChangeHub,
    private val recoveryRepository: RecoveryRepository,
    private val projectMemoryJournal: ProjectMemoryJournal,
    private val projectMemoryIdentityResolver: ProjectMemoryIdentityResolver,
    private val projectMemorySessionCoordinator: ProjectMemorySessionCoordinator,
    private val projectMemoryRuntimeBindings: ProjectMemoryRuntimeBindings,
    private val projectMemoryPathResolver: ProjectMemoryPathResolver,
    private val projectProgressTerminalTracker: ProjectProgressTerminalTracker,
    @param:ApplicationContext private val context: Context,
    private val fileVersions: com.aicode.feature.agent.domain.tool.FileVersionRegistry = com.aicode.feature.agent.domain.tool.FileVersionRegistry(),
    private val notificationRecovery: RecoveryNotificationCoordinator? = null
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()
    private val requestAcceptance = Mutex()
    private val notificationDispatch = Mutex()
    private var pendingNotificationStops = 0
    private val stopAllSettled = MutableStateFlow(true)
    private var acceptanceEpoch = 0L
    private val activeRecoveryRuns = mutableMapOf<String, String>()
    private val recoveryCodec = Json { ignoreUnknownKeys = true }
    private val recoveryBusyIds = MutableStateFlow<Set<String>>(emptySet())
    val recovering: StateFlow<Boolean> = recoveryBusyIds.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private fun _recoveryItemsFlow(): Flow<Pair<String?, List<SessionRecoveryItemUi>>> =
        currentSessionId.flatMapLatest { sid ->
            if (sid == null) flowOf(null to emptyList()) else combine(
                recoveryRepository.observePending(sid),
                projectMemoryIdentityResolver.environmentChanges.onStart { emit(Unit) }
            ) { items, _ ->
                notificationRecovery?.prepareConfirmationRequests(sid)
                val environment = runCatching {
                    projectMemoryIdentityResolver.capture(_currentWorkspace.value)?.identity?.let { recoveryCodec.encodeToString(it) }
                }.getOrNull()
                sid to items.filter { it.request.status in setOf(RecoveryStatus.AWAITING_CONFIRMATION, RecoveryStatus.INTERRUPTED, RecoveryStatus.FAILED) }.map { item ->
                    SessionRecoveryItemUi(item.request.id, item.payload.request, item.draft?.text.orEmpty(),
                        (item.unknownTools + item.pendingTools).map { it.toolName }.distinct(), item.holdReason ?: "process_interrupted",
                        environment == item.request.environment && item.holdReason != "PLAN_REJECTED",
                        stage = item.stage)
                }
            }.flowOn(Dispatchers.IO)
        }
    private val planRejectedSessions = mutableSetOf<String>()

    /**
     * goal 自主循环的「连续空回合」计数：自动续跑回合若既无工具调用也无可见正文，
     * 连续达到阈值即置 BLOCKED，避免无限空转烧 token（借鉴 Codex 的空响应熔断）。
     */
    private val goalEmptyTurnCounts = mutableMapOf<String, Int>()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
    val recoveryItems: StateFlow<Pair<String?, List<SessionRecoveryItemUi>>> = _recoveryItemsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    /**
     * 各会话「最后一轮是否正常完成」：只有正常收尾（收到完成标记）才在轮末回复下挂「复制 / 更多」
     * 按钮；主动暂停 / 出错 / 未开始都不挂，避免按钮挂在半截输出上。
     */
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    /**
     * 子代理卡片状态（按子会话 id 索引），仅存内存。
     *
     * 卡片主体从工具结果渲染（agent、任务、档位、模型都在结果里），这里只补「本进程才知道」的实时状态：
     * 运行中 / 耗时 / 完成摘要 / 改动文件。进程重启后历史卡片自动回退为静态展示。
     */
    private val _subAgentCards = MutableStateFlow<Map<String, SubAgentCardState>>(emptyMap())
    val subAgentCards: StateFlow<Map<String, SubAgentCardState>> = _subAgentCards.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    /**
     * 失败文案：服务端给了类型码（如拒答/上下文超限）时用本地化说明，
     * 并附上服务端的具体理由（如果有）；无类型码时直接展示原错误文本。
     */
    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini 的 finishReason 全大写，这几种与 Anthropic 的 refusal 同义（内容策略拦截）。
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            "continuation_budget_exceeded" -> context.getString(R.string.recovery_continuation_limit)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30
    private val loadedMessageLimits = mutableMapOf<String, Int>()

    /** 聊天记录搜索：命中条数上限、输入防抖、片段上下文宽度、定位时预留的分页余量。 */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val snippetContext = 40
    private val messageLimitMargin = 5

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        val id = _currentSessionId.value ?: return
        val editor = draftPrefs.edit()
        if (text.isEmpty()) {
            _inputDrafts.value = _inputDrafts.value - id
            editor.remove(id)
        } else {
            _inputDrafts.value = _inputDrafts.value + (id to text)
            editor.putString(id, text)
        }
        editor.apply()
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
    }

    /**
     * 单条工具详情的手动展开态：key = 工具消息 id。
     *
     * 放在 ViewModel 而不是组合里：窄窗下打开设置 / 终端 / Git / 编辑器都是全屏路由，聊天页整棵
     * 组合被 dispose，`remember` 的 map 与按 message.id 的 remember 会一起丢——展开过的工具
     * 一离开视线（滚出屏幕被回收、切页返回）就缩回默认态。这里按 App 进程的内存保留，
     * 会话间互不影响（key 取消息 id，全局唯一），不落盘。
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次手动展开/收起（取值由调用方按当前可见态取反后传入）。 */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * 已结束任务的过程展开态：key = `turn:<轮首消息 id>`。
     * 与 [toolExpansionOverrides] 同款，按 App 进程内存保留、不落盘；运行轮始终显示列表，
     * 结束时归回收起态，此后尊重用户查看历史的展开选择。
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    val turnMessageKeys = mutableMapOf<String, MutableMap<String, String>>()

    /** 记录一次整轮折叠的手动展开/收起。 */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * 思考过程展开态的手动选择：key = 助手消息 id。与 [toolExpansionOverrides] 同款（进程内存、不落盘），
     * 使思考卡片划出视口回收、切页返回后仍保持展开/收起。
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次思考卡片的展开/收起。 */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    fun streamingReasoningKey(sessionId: String): String = "reasoning:stream:$sessionId"

    /** 思考过程耗时（毫秒）：key = 助手消息 id。记录流式思考的实际耗时，供落库后的卡片展示一位小数秒数。 */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val state = messagesState.value
        if (state.sessionId != sid || !state.loaded || !state.hasMore) return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        if (loadedMessageLimits[sid] != currentLimit) return
        // 按当前上限的一定比例递增：一轮长过程折叠后可见项很少，定步长 30 条要连续补载很多次才能翻过它。
        // 单次步长封顶 90：每次补载都要重查、重转换并重建列表，步长随上限无界增长会让单次卡顿越来越长。
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + (currentLimit / 2).coerceIn(30, 90)))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        // 切到新工作区：恢复该工作区上次持久化的展开状态（无记录则只展开根）。
        _expandedPaths.value = loadExpansion(path)
        // 搜索限定当前工作区，切区后旧结果无意义，一并清空。
        _chatSearchQuery.value = ""
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有根会话的子代理，按父会话 id 分组，供侧边栏会话行就地展开。
     * 用全量查询而非逐行惰加载：同一张表一次读完，避开每展开一行开一个 Flow 的订阅风暴。
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── 聊天记录全局搜索（限当前工作区）──
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLike(keyword),
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * 打开一条搜索命中：切到对应会话，把该会话分页上限抬到足够包含目标消息，并登记待定位消息。
     * 实际滚动由聊天页在消息就绪后完成。搜索词与结果保留，重开侧边栏仍停在结果列表。
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** 转义 LIKE 通配符，配合 SQL 里的 ESCAPE '!'（转义字符本身需最先处理）。 */
    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** 以命中词为中心截取片段：折叠换行空白，两端按需加省略号；未命中时退回开头一段。 */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(snippetContext * 2)
        val start = (index - snippetContext).coerceAtLeast(0)
        val end = (index + keyword.length + snippetContext).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }

    /** 侧边栏「文件」Tab 已展开的目录集合（容器路径）。含工作区根：根也可折叠，默认展开；按工作区持久化。 */
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))

    /** 文件树展开状态按工作区持久化（重启保留）；key 为工作区路径，值为已展开的容器路径集合。 */
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)

    /** 读取某工作区持久化的展开集；无记录时默认只展开工作区根。 */
    private fun loadExpansion(workspace: String): Set<String> =
        expansionPrefs.getStringSet(workspace, null)?.toSet()
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    /** 持久化当前工作区的展开集（传新集合副本，SharedPreferences 禁止复用已存实例）。 */
    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** 正在展开、等待列目录返回的目录路径（远程 SSH 下 listFiles 可能耗时数秒）；UI 在该行显示等待动画。 */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** 正在执行写操作（新建/重命名/删除/复制/移动）的条目路径集合；UI 在对应行与工具栏显示等待动画。 */
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()

    /** 手动刷新信号：远程模式无 inotify，只能靠它；本地模式作为兜底。 */
    private val _browseRefresh = MutableStateFlow(0)

    /**
     * 当前容器根节点的显示名与图标来源：名称取当前 profile，系统类型取已检测缓存，[FileTreeRoot.Container.remote] 看其执行模式。
     */
    private val containerRootNode: Flow<FileTreeRoot.Container> = combine(
        containerSettingsRepository.activeProfileFlow,
        containerOsDetector.osMap
    ) { profile, osMap ->
        FileTreeRoot.Container(
            label = profile.name,
            osId = osMap[profile.id],
            remote = profile.mode == ExecutionMode.REMOTE_SSH
        )
    }

    /**
     * 扁平化的可见文件树。listFiles 在本地是阻塞 IO、远程是网络调用，必须跑 IO 调度器。
     * 监听工作区根与所有已展开目录：任一发生变动（不限 AI，终端与其它 App 同样算）或手动刷新都会重建树。
     * 只监听展开中的目录、不递归整棵树，避免大仓库开出大量 inotify 句柄。
     */
    val browseState: StateFlow<FileBrowseState> = combine(_expandedPaths, containerRootNode) { expanded, root -> expanded to root }
        .flatMapLatest { (expanded, root) ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map { fileChangeHub.watchWorkspace(it).asDirtySignal() }.merge().debounce(BROWSE_DEBOUNCE_MS),
                // drop(1) 丢掉 StateFlow 重建时的当前值，否则刚展开就会多读一次
                _browseRefresh.drop(1).map { }
            )
            flow {
                // 首次产出前由 stateIn 初值 Loading 占位；后续展开/折叠/刷新不再回到 Loading，
                // StateFlow 保留上一份 Success 直到新树就绪，避免闪加载动画与滚动位置丢失。
                emit(buildBrowseTree(expanded, root))
                triggers.collect { emit(buildBrowseTree(expanded, root)) }
            }.flowOn(Dispatchers.IO)
        }
        // 新树已就绪：清掉展开等待态（无论成功/出错都清，避免转圈卡死）。
        .onEach { _expandingPath.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    /** 读取工作区根与当前容器根，按 [expanded] 递归展开，产出扁平的可见节点列表；工作区根读取失败则整体报错。 */
    private fun buildBrowseTree(expanded: Set<String>, containerRoot: FileTreeRoot.Container): FileBrowseState {
        val workspaceRoot = WorkspacePathMapper.CONTAINER_ROOT
        val workspaceEntries = runCatching { fileAccess.listFiles(workspaceRoot) }.getOrElse { e ->
            FileLogger.w(TAG, "列目录失败: $workspaceRoot", e)
            return FileBrowseState.Error(e.message)
        }
        val nodes = mutableListOf<FileTreeNode>()
        nodes += rootNode(FileTreeRoot.Workspace, workspaceRoot, expanded)
        if (workspaceRoot in expanded) {
            appendBrowseChildren(
                workspaceRoot, workspaceEntries, depth = 1, expanded = expanded,
                ignorePatterns = loadRootGitignore(workspaceRoot), relParts = emptyList(), out = nodes
            )
        }
        // 当前容器根：读不到（容器未安装、远程未连接）时只把该行标红，不影响工作区那棵。
        val containerExpanded = CONTAINER_FS_ROOT in expanded
        val containerEntries = if (containerExpanded) {
            runCatching { fileAccess.listFiles(CONTAINER_FS_ROOT) }.getOrNull()
        } else {
            null
        }
        nodes += rootNode(containerRoot, CONTAINER_FS_ROOT, expanded, hasError = containerExpanded && containerEntries == null)
        if (containerEntries != null) {
            appendBrowseChildren(
                CONTAINER_FS_ROOT, containerEntries, depth = 1, expanded = expanded,
                ignorePatterns = emptyList(), relParts = emptyList(), out = nodes
            )
        }
        return FileBrowseState.Success(nodes)
    }

    /** 文件树的一个根节点行；[FileTreeNode.entry] 的 name 同时用作显示名（见 [FileTreeRoot.label]）。 */
    private fun rootNode(root: FileTreeRoot, path: String, expanded: Set<String>, hasError: Boolean = false) = FileTreeNode(
        entry = FileEntry(name = root.label, isDirectory = true, size = 0, lastModified = 0),
        path = path,
        depth = 0,
        root = root,
        isExpanded = path in expanded,
        hasError = hasError
    )

    /** 读工作区根 .gitignore（本地/远程均可）；去空行/注释/否定行，读不到则空。仅根 .gitignore，不处理嵌套。 */
    private fun loadRootGitignore(root: String): List<String> = runCatching {
        val path = "$root/.gitignore"
        if (!fileAccess.exists(path)) return emptyList()
        fileAccess.readFile(path).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
            .toList()
    }.getOrDefault(emptyList())

    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        // 按名字去重：文件系统的 readdir 在 FUSE 存储（外部工作区/模拟存储）上可能重复返回同一条目，
        // AOSP 的 ReaddirHelper 亦做同样处理。同名条目会产生重复的节点 path，撞坏 LazyColumn 的 key。
        for (entry in entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)) {
            val path = childPathPrefix(parent) + entry.name
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isExpanded = false, ignored = ignored)
                continue
            }
            val children = runCatching { fileAccess.listFiles(path) }.getOrNull()
            out += FileTreeNode(entry, path, depth, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    fun refreshBrowse() {
        _browseRefresh.value++
    }

    /** 展开/折叠目录；折叠时连同其所有后代一并移出展开集，避免残留监听与再展开时意外深开。改变后按工作区持久化。
     *  展开需等列目录返回，期间标记 [expandingPath] 让 UI 显示等待动画，并忽略对同一目录的重复点击（否则会把它折回去）。 */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith(childPathPrefix(path)) }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
    }

    /**
     * 文件浏览的写操作共用包装：跑 IO 调度器，成功后主动重读目录（远程模式无 inotify）。
     * [busyPaths] 为本次操作涉及的条目路径，操作期间加入 [fileOpPaths] 让 UI 显示等待动画。
     * [block] 返回 false 表示名称非法或同名已存在，抛异常表示 IO 失败，两者均回报失败。
     */
    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                runCatching(block)
                    .onFailure { FileLogger.w(TAG, "文件操作失败", it) }
                    .getOrDefault(false)
            }
            if (success) refreshBrowse()
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    /** [parent] 目录下的子路径；名称非法时返回 null。 */
    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) childPathPrefix(parent) + name.trim() else null

    /** 容器路径下的子路径前缀：容器根 `/` 下为 `/`，其余为 `<path>/`。拼接子路径与折叠时连带删后代都用它，避免出现 `//name`。 */
    private fun childPathPrefix(path: String): String = if (path.endsWith("/")) path else "$path/"

    /** 在 [parent] 目录新建空文件。 */
    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    /** 在 [parent] 目录新建文件夹。 */
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    /** 重命名条目（仅同目录内改名，不跨目录移动）。 */
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) {
                false
            } else {
                fileAccess.rename(path, "$parent/${newName.trim()}")
                true
            }
        }

    /** 删除条目；目录连同内容递归删除。 */
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    // region 文件复制 / 剪切 / 粘贴

    /** 文件浏览剪切板：复制或剪切后暂存源条目，供粘贴到其它目录。 */
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()

    /** 复制条目进剪切板（剪切板只能存一项，直接覆盖旧的）。 */
    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    /** 剪切条目进剪切板（粘贴成功后删除源，且只允许粘贴一次）。 */
    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    /** 清空剪切板。 */
    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    /** 粘贴冲突（目标已存在同名项）待用户确认覆盖。持有源路径与目标目录，确认后调 [pasteBrowseEntryOverwrite]。 */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    /** 把剪切板内容粘贴到 [targetDir]。目标已存在同名项时，发 [pasteConflict] 让 UI 弹窗询问是否覆盖，
     *  不执行粘贴；否则直接粘贴。粘贴成功即清空剪切板（无论复制/剪切，一次粘贴后失效），失败保留供重试。 */
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    /** 用户确认覆盖后强制粘贴（目标已存在同名项也会覆盖）。 */
    fun pasteBrowseEntryOverwrite() {
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    /** 清空粘贴冲突待确认状态（用户点了「取消」时）。 */
    fun clearPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths = busyPaths, onResult = { success ->
            // 无论复制还是剪切，粘贴成功即清空剪切板，避免重复粘贴；失败保留，允许重试。
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            if (clip.isCut) {
                fileAccess.move(clip.sourcePath, target, overwrite)
                true
            } else {
                fileAccess.copy(clip.sourcePath, target, overwrite)
                true
            }
        }
    }

    // endregion

    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前会话的持续目标（goal）快照；null 表示未设定目标。 */
    val currentSessionGoal: StateFlow<SessionGoalUi?> = currentSessionState
        .map { s ->
            s?.goal?.takeIf { it.isNotBlank() }?.let {
                SessionGoalUi(it, s.goalStatus ?: GoalStatus.ACTIVE, s.goalTurnCount)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = list.asSequence()
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false,
                    limit = limit
                )
            }.flowOn(Dispatchers.Default)
                .onEach { loadedMessageLimits[id] = limit }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 模型正在流式产出、还没开始执行的工具名（如 `editFile`）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。上游在工具名一出现就上报（[AgentEvent.ToolCallPreparing]），
     * UI 据此把状态换成具体场景（「正在编辑文件」）。工具真正开始执行后由 [runningTool] 接管。
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private data class CompactionOwner(val job: Job, val attemptId: String, val phase: CompactionPhase = CompactionPhase.PREPARING)
    private val compactionOwners = HashMap<String, CompactionOwner>()
    private val compressionCommandOwners = HashMap<String, Job>()
    private val _compactionPercents = MutableStateFlow<Map<String, Int>>(emptyMap())
    val compactionPercent: StateFlow<Pair<String?, Int>> = combine(_currentSessionId, _compactionPercents) { id, progress ->
        id to (progress[id] ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null to 0)
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()
    private val _generationSpeeds = MutableStateFlow<Map<String, AgentEvent.GenerationSpeed>>(emptyMap())
    val speedTier = generalSettingsRepository.speedTierFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.aicode.feature.agent.domain.model.SpeedTier.STANDARD)

    fun setSpeedTier(tier: com.aicode.feature.agent.domain.model.SpeedTier) {
        viewModelScope.launch { generalSettingsRepository.setSpeedTier(tier) }
    }

    val generationSpeed: StateFlow<AgentEvent.GenerationSpeed?> = _currentSessionId
        .flatMapLatest { id -> _generationSpeeds.map { it[id] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setGenerationSpeed(sessionId: String, speed: AgentEvent.GenerationSpeed?) {
        // 空闲采样的零值表示暂未收到增量，显示保留最后一次有效读数。
        if (speed != null && speed.tokensPerSecond <= 0.0) return
        _generationSpeeds.value = if (speed == null) _generationSpeeds.value - sessionId
            else _generationSpeeds.value + (sessionId to speed)
    }

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        code.contains("timeout") -> R.string.compaction_error_timeout
        code == "compaction_http_401" || code == "compaction_http_403" -> R.string.compaction_error_authentication
        code == "compaction_http_429" -> R.string.compaction_error_rate_limit
        code.startsWith("compaction_http_") -> R.string.compaction_error_service
        code == "compaction_io" -> R.string.compaction_error_network
        code == "compaction_truncated" -> R.string.compaction_error_truncated
        code in setOf("compaction_attempt_limit", "compaction_block_limit", "compaction_budget_unreachable") -> R.string.compaction_error_budget
        code == "compaction_busy" -> R.string.compaction_error_busy
        else -> R.string.compaction_error_incomplete
    })

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) {
            _reasoningTimings.value = _reasoningTimings.value - sessionId
            reasoningExpansionOverrides.remove(streamingReasoningKey(sessionId))
        }
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    /** 按 sessionId 维护的多 Key 切换提示；重新出内容或本轮结束时置 null。 */
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在等待用户授权的会话 id 集合，供侧边栏会话行点亮橙色指示灯。 */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion
    val pendingPlanApproval = planApprovalManager.pendingPlan
    val pendingPlanApprovalSessionTitle: StateFlow<String> = combine(
        pendingPlanApproval,
        sessions,
        subSessionsByParent
    ) { request, roots, subs ->
        val sid = request?.sessionId.orEmpty()
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val approvalSessionIds: StateFlow<Set<String>> = combine(
        awaitingPermissionSessionIds,
        askUserQuestionManager.pendingQuestion,
        planApprovalManager.awaitingSessionIds
    ) { awaiting, question, plans ->
        buildSet {
            addAll(awaiting)
            addAll(plans)
            question?.sessionId?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val externallyRecordedSubAgentStops = mutableSetOf<String>()
    private val stoppingSessions = mutableSetOf<String>()
    private val deletedSessionIds = mutableSetOf<String>()
    private val subAgentWaitingSlots = MutableStateFlow<Set<String>>(emptySet())
    private val subAgentInteractionStates = combine(agentStates, subAgentWaitingSlots) { states, waiting ->
        states.filterKeys { it !in waiting }
    }
    private val subAgentQueuedIds = combine(_queuedRequests, subAgentWaitingSlots) { queued, waiting ->
        queued.filterValues { it.isNotEmpty() }.keys + waiting
    }
    private val subAgentInteractionLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val subAgentExecutionLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val subAgentToolTitles = _runningTools.map { tools ->
        tools.mapValues { (_, running) -> running.values.lastOrNull()?.toolName.orEmpty() }
            .filterValues { it.isNotBlank() }
    }.distinctUntilChanged()
    private val subAgentInteractionRuntime = combine(
        subAgentInteractionStates, approvalSessionIds, subAgentQueuedIds, subAgentToolTitles
    ) { states, approvals, queued, tools ->
        SubAgentInteractionRuntime(
            activeIds = states.filterValues { it is AgentUIState.Loading || it is AgentUIState.Streaming }.keys,
            awaitingIds = approvals,
            queuedIds = queued,
            terminalStatuses = states.mapNotNull { (id, state) ->
                when (state) {
                    is AgentUIState.Error -> SubAgentInteractionStatus.FAILED
                    is AgentUIState.Result -> when (state.status) {
                        WorkflowStatus.SUCCESS, WorkflowStatus.PARTIAL_SUCCESS -> SubAgentInteractionStatus.COMPLETED
                        WorkflowStatus.FAILED -> SubAgentInteractionStatus.FAILED
                        WorkflowStatus.CANCELLED -> SubAgentInteractionStatus.INTERRUPTED
                    }
                    else -> null
                }?.let { id to it }
            }.toMap(),
            toolTitles = tools
        )
    }.distinctUntilChanged()

    val subAgentInteractionSummaries: StateFlow<Pair<String?, Map<String, SubAgentInteractionSummary>>> = _currentSessionId
        .flatMapLatest { parentId ->
            if (parentId == null) flowOf(null to emptyMap())
            else combine(subAgentInteractionReader.summaryFacts(parentId), subAgentInteractionRuntime) { facts, runtime ->
                parentId to projectSubAgentInteractionSummaries(facts, runtime)
            }.flowOn(Dispatchers.Default).onStart { emit(parentId to emptyMap()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyMap())

    fun subAgentConversation(parentId: String, childId: String): Flow<SubAgentInteractionPage> =
        combine(_currentSessionId, subAgentInteractionLimits) { selected, limits ->
            selected to (limits[childId] ?: SubAgentInteractionReader.PAGE_SIZE)
        }.distinctUntilChanged().flatMapLatest { (selected, limit) ->
            if (selected != parentId) flowOf(SubAgentInteractionPage(loaded = true))
            else subAgentInteractionReader.conversation(parentId, childId, limit)
        }

    fun subAgentExecution(parentId: String, childId: String): Flow<SubAgentExecutionPage> =
        combine(_currentSessionId, subAgentExecutionLimits) { selected, limits ->
            selected to (limits[childId] ?: SubAgentInteractionReader.PAGE_SIZE)
        }.distinctUntilChanged().flatMapLatest { (selected, limit) ->
            if (selected != parentId) flowOf(SubAgentExecutionPage(loaded = true))
            else subAgentInteractionReader.ownsChild(parentId, childId).flatMapLatest { owned ->
                if (!owned) flowOf(SubAgentExecutionPage(loaded = true))
                else combine(
                    subAgentInteractionReader.execution(parentId, childId, limit),
                    _streamingTexts.map { it[childId].orEmpty() }.distinctUntilChanged(),
                    _runningTools.map { it[childId]?.values?.toList().orEmpty() }.distinctUntilChanged()
                ) { page, text, tools -> page.copy(streamingText = text, runningTools = tools) }
                    .onStart { emit(SubAgentExecutionPage()) }
            }
        }

    fun loadMoreSubAgentInteractions(childId: String) {
        val limit = subAgentInteractionLimits.value[childId] ?: SubAgentInteractionReader.PAGE_SIZE
        subAgentInteractionLimits.value += childId to (limit.toLong() + SubAgentInteractionReader.PAGE_SIZE).coerceAtMost(Int.MAX_VALUE - 1L).toInt()
    }

    fun loadMoreSubAgentExecution(childId: String) {
        val limit = subAgentExecutionLimits.value[childId] ?: SubAgentInteractionReader.PAGE_SIZE
        subAgentExecutionLimits.value += childId to (limit.toLong() + SubAgentInteractionReader.PAGE_SIZE).coerceAtMost(Int.MAX_VALUE - 1L).toInt()
    }

    suspend fun sendSubAgentInstruction(parentId: String, childId: String, text: String): SubAgentInstructionResult {
        if (_currentSessionId.value != parentId) return SubAgentInstructionResult(false, SubAgentInstructionFailure.NOT_OWNED)
        return subAgentInstructionGateway.send(parentId, childId, text) { id, body, workspace, instructionId ->
            enqueueAgentRequest(request = body, projectRoot = workspace, targetSessionId = id, subAgentInstructionId = instructionId)
        }
    }

    fun openSubAgentSession(parentId: String, childId: String) {
        viewModelScope.launch {
            val child = sessionUseCase.getSessionById(childId) ?: return@launch
            if (_currentSessionId.value == parentId && child.parentId == parentId) _currentSessionId.value = childId
        }
    }

    fun stopOwnedSubAgent(parentId: String, childId: String) {
        viewModelScope.launch {
            val child = sessionUseCase.getSessionById(childId) ?: return@launch
            if (_currentSessionId.value == parentId && child.parentId == parentId) stopAgentSession(childId)
        }
    }

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId = mutableMapOf<String, String>()
    private val _preparingToolArguments = MutableStateFlow<Map<String, Map<String, PreparingToolPreviewSnapshot>>>(emptyMap())
    val preparingToolArguments: StateFlow<Pair<String?, List<PreparingToolPreviewSnapshot>>> = _currentSessionId
        .flatMapLatest { sid -> _preparingToolArguments.map { sid to it[sid]?.values?.toList().orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    val runningToolPreviewSnapshot: StateFlow<Pair<String?, List<RunningToolOutput>>> = _currentSessionId
        .flatMapLatest { sid -> _runningTools.map { sid to it[sid]?.values?.toList().orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    val toolPreviewFileReader: StateFlow<Pair<String?, com.aicode.feature.agent.presentation.preview.PreviewFileReader?>> =
        combine(_currentSessionId, toolPreviewFileAccessAdapter.readers) { sid, reader -> sid to reader }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    val toolPreviewMessages: StateFlow<Pair<String?, List<AgentUIMessage>>> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(null to emptyList())
        else agentMessageDao.getToolMessagesBySession(sid).map { rows -> sid to rows.map { it.toUIMessage() } }
            .flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    private val _toolExecutionStarts = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val toolExecutionStarts: StateFlow<Map<String, Long>> = _currentSessionId
        .flatMapLatest { sid -> _toolExecutionStarts.map { it[sid].orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private val turnStartedElapsed = mutableMapOf<String, Long>()

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        if (sessions.value.any { sessionJobs[it.id]?.isActive == true }) return true
        // 子代理会话不在 sessions（只含根会话）里：漏掉它们会在父代理这一轮结束时
        // 提前释放唤醒锁与前台服务，仍在跑的子代理会被系统挂起。
        return subSessionsByParent.value.values.any { subs ->
            subs.any { sessionJobs[it.id]?.isActive == true }
        }
    }

    private fun protectJob(job: Job) {
        val owner = "agent:${UUID.randomUUID()}"
        TaskKeepaliveController.acquire(context, owner, cpu = true)
        job.invokeOnCompletion { TaskKeepaliveController.release(context, owner) }
    }

    /** App 退到后台时 Agent 完成，弹一条可点击的系统通知（标题=任务完成，正文=用户消息）。 */
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

    /**
     * 系统通知正文：普通用户消息直接展示；后台回调触发的轮次不裸露
     * 「[系统通知 - 非用户输入]…」内部构造文本，改为展示任务标题。
     */
    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100

        /** 子代理卡片状态保留条数：只服务当前展示，超出按开始时间淘汰最早的一批。 */
        const val MAX_SUBAGENT_CARDS = 50
        /** 从后台任务通知文本中提取 <title> 内容，供系统通知正文展示。 */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        /** 文件浏览排序：目录在前，同类按名称不区分大小写。 */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 写文件会连珠触发多个 inotify 事件，合并后再重读目录。 */
        const val BROWSE_DEBOUNCE_MS = 300L

        /** 会话目标文本长度上限，避免 system 提示词过长。 */
        const val MAX_GOAL_CHARS = 2000

        /** goal 自主循环连续空回合阈值：达到即置 BLOCKED（借鉴 Codex 的空响应熔断）。 */
        const val MAX_GOAL_EMPTY_TURNS = 3

        /** 当前容器在容器内的根路径，对应宿主上的 rootfs 目录（随当前 profile 变化）。 */
        const val CONTAINER_FS_ROOT = "/"
    }

    init {
        viewModelScope.launch {
            rootAccessSettingsRepository.state.collect { state ->
                if (!state.enabled) toolPermissionManager.rejectForTool("Root")
            }
        }
        // 冷启动收尾：上次进程被杀时残留的「执行中」工具回填为「已中断」。在后台执行，不阻塞首屏与会话展示。
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // 启动与工作区切换时重新扫描技能（项目级技能随工作区变化），刷新 `/` 命令菜单。
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // 立即确定并设置当前会话，确保首帧 UI 秒开、侧边栏立即出现新会话，彻底消除转圈卡顿。
                // 「打开最近会话」有历史就直接进最近那个（按 updatedAt，不受置顶影响）；
                // 「新开会话」复用还没发过消息的空会话（避免每次启动都堆一个空会话），其余情况新建。
                val targetId = when {
                    recent == null -> createAndUpsertSession(path)
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // 异步回收多余的空会话（保留当前 targetId），在后台执行，不卡主线程与首帧渲染。
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionProvider.tabFinishedEvents.collect { event ->
                withContext(Dispatchers.IO) {
                    projectProgressTerminalTracker.finished(event.sourceSessionId, event.tabId, event.exitCode,
                        event.sourceMode, event.channelGeneration)
                }
                handleBackgroundCommandFinished(event)
            }
        }

        // 授权审批通知开关关闭时立即撤销。
        viewModelScope.launch {
            approvalNotificationSettings.enabledFlow.collectLatest { enabled ->
                if (!enabled) agentApprovalNotificationManager.dismiss()
            }
        }

        // 授权审批通知：阻塞等待用户确认（授权/提问）时聚合会话，仅 App 在后台时由管理器展示。
        // 包含子代理会话——子代理也会提交授权，漏掉它们会让等待埋没。
        viewModelScope.launch {
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

        viewModelScope.launch {
            combine(
                _agentStates,
                _runningTools,
                _preparingTools,
                _compactingSessions,
                approvalSessionIds
            ) { states, tools, preparing, compacting, approvals ->
                states.filterValues { it is AgentUIState.Loading || it is AgentUIState.Streaming }
                    .keys
                    .associateWith { sid ->
                        val toolName = tools[sid]?.values?.lastOrNull()?.toolName?.takeIf { it.isNotBlank() }
                            ?: preparing[sid]
                        when {
                            sid in approvals -> context.getString(R.string.notification_approval_needed)
                            toolName != null -> context.getString(toolRunningLabelRes(toolName))
                            compacting[sid] == true -> context.getString(R.string.chat_compressing_context)
                            else -> context.getString(R.string.chat_status_thinking)
                        }
                    }
            }.distinctUntilChanged()
                .collectLatest { statusMap ->
                    val running = statusMap.mapNotNull { (sid, label) ->
                        val session = sessionUseCase.getSessionById(sid) ?: return@mapNotNull null
                        // 子代理会话并入父会话展示，避免父子重复。
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

        // 订阅子代理生命周期事件（类比 terminal 的 notify=true 异步回调）：
        // - SPAWNED：task 工具已创建子会话并替用户发消息，这里在子会话上自动启动 AI 工作流；
        // - STOPPED：task(action="stop") 请求停止子代理，取消对应会话的 AI 任务；
        // - COMPLETED/FAILED：由子会话工作流结束时发出（见 handleSubAgentFinished），注入父会话通知；
        // - DELETED：task(action="del") 已删库，只清运行时状态，不做任何停止收尾。
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                if (!isCurrentSubAgentEvent(event)) {
                    if (event.type == SubAgentEventType.SPAWNED && sessionJobs[event.subSessionId]?.isActive != true) {
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
                            if (sessionJobs[event.subSessionId]?.isActive == true) externallyRecordedSubAgentStops.add(event.subSessionId)
                            stopAgentSession(event.subSessionId)
                        }
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.DELETED -> {
                        // 不能走 stopAgentSession：它的收尾会往已删除会话落「已停止」占位消息，
                        // 取消中 job 的 finally 也会对着不存在的会话补送通知、烧一轮 LLM。
                        markSubAgentFinished(event.subSessionId, SubAgentCardStatus.STOPPED)
                        clearSessionRuntimeState(event.subSessionId)
                    }
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                    SubAgentEventType.STARTED, SubAgentEventType.INSTRUCTION_CANCELLED -> Unit
                }
            }
        }
    }

    /** 子代理开始运行：卡片立刻进入「运行中」，不必等工具结果回来。 */
    private fun markSubAgentRunning(subSessionId: String) {
        viewModelScope.launch {
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

    /**
     * 子代理结束：把状态、摘要与改动文件写进卡片。
     *
     * @param digest 已算好的结果摘要（与完成通知同一份），调用方拿不到时传 null 只更新状态。
     */
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
        // 卡片状态只服务当前进程的展示，超过 50 条就丢掉最早的，避免长会话无限增长。
        if (_subAgentCards.value.size > MAX_SUBAGENT_CARDS) {
            val keep = _subAgentCards.value.entries.sortedByDescending { it.value.startedAt }
                .take(MAX_SUBAGENT_CARDS).associate { it.key to it.value }
            _subAgentCards.value = keep
        }
    }

    /**
     * 子代理已创建（task 工具已创建子会话）：在子会话上启动 AI 工作流。
     * 消息由 executeAgentRequestStream 统一落库（与用户手动发消息一致），
     * 标题保留 task 传入的 description（skipTitleUpdate）。
     */
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
            // SPAWNED 已把它计入活跃集合，而工作流永远不会启动，交回并发名额
            subAgentEventBus.release(event.subSessionId)
            return
        }
        // 子会话可能已被用户删除，跳过
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        // 子代理运行中不允许重复启动（同一会话已有活跃 job）
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true,
            expectedSubAgentEvent = event
        )
    }

    /**
     * 子代理完成/失败通知：父会话忙碌时先入队（由本轮内下一批工具结果搭车送达，见
     * [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]），空闲时立即注入一条系统通知消息触发新一轮。
     * 父代理据此得知子代理结束；正常完成时结果摘要已随通知送达，需完整报告再 `task(action="read")`。
     * 与 terminal 后台通知同机制。
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        viewModelScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            // 摘要只在正常完成时提炼：失败/终止带的是错误原因（detail），再抽正文只会误导。
            // 读整段消息 + 解析 toolArgs 放 IO 线程：长会话的子代理消息量大，不该占主线程。
            val digest = if (event.type == SubAgentEventType.COMPLETED) {
                withContext(Dispatchers.IO) {
                    runCatching { SubAgentDigester.digestRun(
                        agentMessageDao.getMessagesBySessionOnce(event.subSessionId), event.sourceMessageId, event.runMessageIds
                    ) }
                        .onFailure { FileLogger.w(TAG, "子代理结果摘要提取失败: ${event.subSessionId}", it) }
                        .getOrNull()
                }
            } else {
                null
            }
            // 卡片与通知共用同一份摘要：卡片在聊天流里展示，通知喂给父代理。
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

    /**
     * 主会话发给运行中/已完成子代理的消息：收件人忙碌时入队搭车，空闲时触发新一轮。
     * 与 [notifyParentSubAgentFinished] 同分发逻辑，方向相反。
     */
    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true,
            eventId = event.eventId,
            generation = event.generation
        )
    }

    /** 子代理发给主会话的消息：同上，收件人为主会话。 */
    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false,
            eventId = event.eventId,
            generation = event.parentGeneration
        )
    }

    /**
     * 投递一条代理间消息：收件人忙碌时入 [AgentNotificationCenter]（本轮内工具结果搭车，或整轮结束后兜底），
     * 空闲时以一条通知消息触发其新一轮。消息正文随通知一并送达，收件方无需再另行读取。
     */
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

    /**
     * 向父会话投递一条子代理结束通知：父会话忙碌时入队搭车，空闲时立即注入触发新一轮。
     * 失败与被终止都要带上原因，否则父代理只知道「没成」，还得再 read 一次才可能拿到线索。
     * 正常完成时附带结果摘要与改动文件，省掉父代理那一次 read。
     */
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

    /**
     * 后台命令（notify=true）结束后的回调。
     *
     * - 会话忙碌：入队 [agentNotificationCenter]，由本轮内下一批工具结果搭车送达（AI 当轮即可感知，
     *   省掉「等本轮结束再起一轮」的 LLM 往返）；整轮再没有工具调用时由 [flushPendingNotifications] 兜底。
     * - 会话空闲：立即以一条系统通知消息触发 Agent 新一轮。
     *
     * 兜底路径用 user 消息而非 assistant(tool_call) + tool_result 消息对：后者会与原 terminal 工具调用的
     * tool 结果在落库顺序上错位（后台回调异步触发，可能抢先于原 terminal 结果落库），导致 messages
     * 违反 OpenAI「assistant(tool_calls) → tool 结果紧跟」的配对约束，上游返回 400。user 消息无需与
     * 任何 tool_call 配对，天然不破坏顺序。通知文本带围栏说明，防止 AI 误判为用户的新指令或批准；
     * AI 据此用 terminal(read) 取回完整输出。
     *
     * 不自行 persist 通知、用 isAutoTrigger=false 走通知补送流程：由
     * executeAgentRequestStream 统一 persist 这条 user 消息，workflow 的 InitRequest 追加的同一条
     * UserMessage 即是它，避免重复落库或出现空占位消息。
     */
    private suspend fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        val item = event.toPendingNotification()
        deliverSystemEvent(sessionId, item)
    }

    /**
     * 统一投递一条系统事件给指定会话：忙碌则入 [agentNotificationCenter]，由本轮内工具结果搭车送达；
     * 空闲则以一条系统通知消息触发新一轮。后台任务完成、子代理结束与代理间消息共用此分发，
     * 避免各处重复判断忙碌/空闲。
     */
    private suspend fun deliverSystemEvent(sessionId: String, item: PendingNotification, generation: Long? = null) {
        if (agentNotificationCenter.enqueue(sessionId, item, generation) <= 0) return
        flushPendingNotifications(sessionId)
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    /**
     * 本轮结束后的兜底送达：把仍留在队列里的通知合并成一条消息发送。
     * 已被工具结果搭车送达的通知此时已 ack 移除，取到空则什么都不做。
     */
    private fun flushPendingNotifications(sessionId: String) {
        viewModelScope.launch {
            notificationDispatch.withLock {
                if (pendingNotificationStops > 0 || sessionId in deletedSessionIds || sessionId in planRejectedSessions || sessionId in stoppingSessions) return@withLock
                if (!recoveryRepository.canDispatch(sessionId)) return@withLock
                if (sessionJobs[sessionId]?.isCompleted == false || sessionId in _runningCommandSessions.value) return@withLock
                val items = agentNotificationCenter.peek(sessionId).filter {
                    recoveryRepository.notificationRequest(sessionId, it.seq) == null
                }
                if (items.isEmpty()) return@withLock
                val root = sessionUseCase.getSessionById(sessionId)?.workspacePath ?: return@withLock
                executeAgentRequestStream(
                    request = AgentNotificationFormatter.buildMessage(items),
                    projectRoot = root,
                    targetSessionId = sessionId,
                    notificationSeqs = items.map { it.seq }
                )
            }
        }
    }

    fun continueRecovery(requestId: String) {
        val sid = _currentSessionId.value ?: return
        if (sid in deletedSessionIds || sid in stoppingSessions || sessionJobs[sid]?.isCompleted == false || requestId in recoveryBusyIds.value) return
        recoveryBusyIds.value += requestId
        viewModelScope.launch {
            var claimed: RecoveryRun? = null
            var handedOff = false
            try {
                stopAllSettled.first { it }
                val item = recoveryRepository.observePending(sid).first().firstOrNull { it.request.id == requestId } ?: return@launch
                if (item.holdReason == "PLAN_REJECTED") return@launch
                val capture = projectMemoryIdentityResolver.capture(item.payload.projectRoot) ?: return@launch
                if (recoveryCodec.encodeToString(capture.identity) != item.request.environment || _currentWorkspace.value != item.payload.projectRoot) return@launch
                capture.assertCurrent()
                recoveryRepository.clearExplicitHold(sid)
                val run = recoveryRepository.claim(requestId, item.request.environment, confirmed = true) ?: return@launch
                claimed = run
                for (tool in (item.unknownTools + item.pendingTools).distinctBy { it.toolCallId }) {
                    val unknown = tool.status in setOf(RecoveryToolStatus.UNKNOWN, RecoveryToolStatus.STARTED)
                    val text = if (unknown) "Execution outcome unknown after interruption. Do not replay this call; verify current state first."
                        else "This tool call was not executed before interruption. No previous approval is restored."
                    messagePersistenceUseCase.persist(sid, MessageRole.TOOL,
                        com.aicode.feature.agent.domain.tool.ToolResult.Error(text, if (unknown) "RESULT_UNKNOWN" else "NOT_EXECUTED").toTransportString(),
                        id = "tool_${tool.toolCallId}", toolCallId = tool.toolCallId, toolName = tool.toolName, isError = true,
                        recoveryRunId = run.id)
                }
                val resume = run.resumeSnapshot?.inputMessageExists == true
                val images = messagePersistenceUseCase.loadAttachmentImages(item.payload.inputAttachments)
                capture.assertCurrent()
                executeAgentRequestStream(
                    request = if (resume) context.getString(R.string.recovery_continue_request) else item.payload.request,
                    modelRequest = item.payload.modelRequest, currentFile = item.payload.currentFile, selectedCode = item.payload.selectedCode,
                    projectRoot = item.payload.projectRoot, inputImages = images, inputAttachments = item.payload.inputAttachments,
                    targetSessionId = sid, isAutoTrigger = false, persistUserMessage = true,
                    subAgentInstructionId = item.payload.subAgentInstructionId, acceptedRecoveryRun = run,
                    acceptedCapture = capture, resuming = resume, notificationSeqs = item.payload.notificationSeqs)
                handedOff = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                setAgentState(sid, AgentUIState.Error(context.getString(R.string.recovery_continue_failed)))
            } finally {
                try {
                    if (!handedOff) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        claimed?.let { runCatching { recoveryRepository.finish(it.id, RecoveryStatus.INTERRUPTED) } }
                    }
                } finally {
                    recoveryBusyIds.value -= requestId
                }
            }
        }
    }

    fun abandonRecovery(requestId: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val payload = recoveryRepository.payload(requestId)
            if (recoveryRepository.abandonPending(requestId, sid)) {
                payload?.notificationSeqs?.takeIf { it.isNotEmpty() }?.let { agentNotificationCenter.discard(sid, it) }
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
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        persistUserMessage: Boolean = true,
        targetSessionId: String? = null,
        subAgentInstructionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        if (sid in deletedSessionIds) return
        if (!isAutoTrigger && sid != null) planRejectedSessions.remove(sid)
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isCompleted == false || sid in _runningCommandSessions.value || sid in stoppingSessions)
        if (isCurrentRunning) {
            val epoch = acceptanceEpoch
            viewModelScope.launch {
                var acceptedId: String? = null
                var queued = false
                try {
                stopAllSettled.first { it }
                requestAcceptance.withLock {
                    val entity = sessionUseCase.getSessionById(requireNotNull(sid)) ?: return@withLock
                    if (sid in deletedSessionIds) return@withLock
                    val capture = projectMemoryIdentityResolver.capture(entity.workspacePath) ?: return@withLock
                    capture.assertCurrent()
                    if (!isAutoTrigger) recoveryRepository.clearExplicitHold(sid)
                    val accepted = recoveryRepository.enqueue(sid,
                        recoveryPayload(request, modelRequest, currentFile, selectedCode, entity.workspacePath,
                            inputAttachments, isAutoTrigger, persistUserMessage, subAgentInstructionId),
                        recoveryCodec.encodeToString(capture.identity))
                    acceptedId = accepted.id
                    if (epoch != acceptanceEpoch || sid in deletedSessionIds || _currentWorkspace.value != entity.workspacePath) {
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
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage,
                subAgentInstructionId = subAgentInstructionId,
                recoveryRequestId = accepted.id
            )
                    val currentList = _queuedRequests.value[sid] ?: emptyList()
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
                    if (!queued) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
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
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage,
                subAgentInstructionId = subAgentInstructionId
            )
        }
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val removed = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
        recordCancelledSubAgentInstructions(sid, listOf(removed))
        removed.recoveryRequestId?.let { requestId -> viewModelScope.launch { recoveryRepository.abandon(requestId) } }
    }

    private fun recordCancelledSubAgentInstructions(sessionId: String, requests: List<QueuedRequest>) {
        val instructionIds = requests.mapNotNull { it.subAgentInstructionId }
        if (instructionIds.isEmpty()) return
        viewModelScope.launch {
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

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        viewModelScope.launch {
            val ids = reordered.mapNotNull { it.recoveryRequestId }
            if (ids.size == reordered.size && !recoveryRepository.reorderQueued(sid, ids)) return@launch
            _queuedRequests.value += sid to reordered
        }
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val old = queue.firstOrNull { it.id == id && it.subAgentInstructionId == null } ?: return
        val updated = old.copy(request = text, modelRequest = syncQueuedModelRequest(old, text))
        viewModelScope.launch {
            val requestId = old.recoveryRequestId
            if (requestId != null && !recoveryRepository.updateQueued(requestId,
                    recoveryPayload(updated.request, updated.modelRequest, updated.currentFile, updated.selectedCode,
                        updated.projectRoot, updated.inputAttachments, updated.isAutoTrigger, updated.persistUserMessage, updated.subAgentInstructionId))) return@launch
            _queuedRequests.value += sid to (_queuedRequests.value[sid].orEmpty().map { if (it.id == id) updated else it })
        }
    }

    /**
     * 用编辑后的文本重建 modelRequest：无附件注入时与 request 同步；有附件注入时
     * （拼成 `request + "\n\n" + 附件清单`）只替换文本前缀、保留附件部分。
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    /**
     * 把当前会话队列中某条消息立即插入正在运行的轮次：从队列移除，转成用户插话通知交给
     * [deliverSystemEvent]——忙碌时搭车注入本批工具结果，AI 当前轮即可感知；空闲时作为
     * 新一轮用户消息发送。
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        if (req.subAgentInstructionId != null) return
        planRejectedSessions.remove(sid)
        req.recoveryRequestId?.let { requestId -> viewModelScope.launch { recoveryRepository.abandon(requestId) } }
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
        viewModelScope.launch { deliverSystemEvent(
            sid,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sid,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        ) }
    }

    private fun processNextInQueue(sessionId: String) {
        if (sessionId in planRejectedSessions || sessionId in stoppingSessions) return
        // 已有活跃 job（可能是本次收尾前由通知合并/flush 等入口启动的）时不消费，
        // 避免队列被多个收尾入口重复消费、同一会话并发跑两个 job。
        if (sessionJobs[sessionId]?.isCompleted == false) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
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

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再按类型派发——
     * 内置命令走本地动作，技能则把其正文作为本轮指令触发 agent 回合。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runResolvedCommand(
        resolved: ResolvedCommand,
        input: String,
        sessionId: String,
        currentFile: String?,
        selectedCode: String?
    ) {
        val compress = resolved is ResolvedCommand.Action && resolved.name == "compress"
        val workspace = _currentWorkspace.value
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = requireNotNull(coroutineContext[Job])
            protectJob(owner)
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input, requireSession = compress)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                if (compress && (compressionCommandOwners[sessionId] !== owner || !owner.isActive ||
                        _currentWorkspace.value != workspace || sessionId in deletedSessionIds || sessionId in stoppingSessions)) return@launch
                when (resolved) {
                    is ResolvedCommand.Action -> {
                        val invocation = if (compress) object : SlashCommandContext by this@AIAgentViewModel {
                            override fun compactCurrentSession() {
                                startContextCompaction(sessionId, true, currentFile, selectedCode)
                            }
                        } else this@AIAgentViewModel
                        resolved.handler.execute(invocation, resolved.args)
                    }
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                if (!compress || compressionCommandOwners[sessionId] === owner) {
                    if (compress) compressionCommandOwners.remove(sessionId)
                    _runningCommandSessions.value -= sessionId
                    flushPendingNotifications(sessionId)
                    processNextInQueue(sessionId)
                }
            }
        }
        if (compress) compressionCommandOwners[sessionId] = job
        job.start()
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        persistUserMessage: Boolean = true,
        /** 子代理等场景：已预设会话标题，跳过首条消息的标题推导/生成，保留预设标题。 */
        skipTitleUpdate: Boolean = false,
        notificationSeqs: List<Long> = emptyList(),
        subAgentInstructionId: String? = null,
        recoveryRequestId: String? = null,
        acceptedRecoveryRun: RecoveryRun? = null,
        acceptedCapture: ProjectMemoryCapture? = null,
        resuming: Boolean = false,
        expectedSubAgentEvent: SubAgentEvent? = null
    ): Job = viewModelScope.launch {
        stopAllSettled.first { it }
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过请求")
            return@launch
        }
        if (sessionId in deletedSessionIds) return@launch
        // 命令分流：命中斜杠命令（内置命令或技能）时，不走 agent workflow，直接执行命令操作
        // （命令文本已作为用户消息落库，进入对话上下文）。不注册 sessionJobs，
        // 因此 isRunning 保持 false，/compress 等命令内部的自检可以正常工作。
        if (subAgentInstructionId == null && request.startsWith("/")) {
            slashCommandRegistry.resolve(request)?.let { command ->
                runResolvedCommand(command, request, sessionId, currentFile, selectedCode)
                return@launch
            }
        }
        // 兼容历史会话：若会话尚未持久化绑定 providerId/model，发消息时将其固化，避免后续默认模型变动影响已有会话
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession == null || sessionId in deletedSessionIds) return@launch
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

        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        agentLiveNotificationManager.markSessionStarted(sessionId)
        setAgentState(sessionId, AgentUIState.Streaming)
        coroutineContext[Job]?.let(::protectJob)
        if (!isAutoTrigger && persistUserMessage && notificationSeqs.isEmpty() && !request.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            turnStartedElapsed[sessionId] = SystemClock.elapsedRealtime()
        } else {
            turnStartedElapsed.putIfAbsent(sessionId, SystemClock.elapsedRealtime())
        }

        // 声明在 try 外：catch/finally 需要据此向父会话发失败通知并释放子代理并发名额，
        // 而 try 内的异常可能发生在更早的 buildHistory 阶段。
        val isSubSession = currentSession?.parentId != null
        var latestAssistantBodyId: String? = null
        val runMessageIds = linkedSetOf<String>()
        var terminalInteractionRecorded = false
        var turnSucceeded = false
        var sawCompleted = false
        var planRejected = false
        // 本轮是否处于 goal 自主循环（用于「打断即暂停」）与本轮是否有活动（用于空回合熔断）。
        var goalActiveAtTurn = false
        var turnHadActivity = false
        // 首条消息的 LLM 标题请求延后到主请求收到首个内容（或本轮结束）再发：
        // 并发槽很少的本地模型会让主请求在服务端排在标题请求之后。
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
            sessionUseCase.initColdStartCleanup()
            val root = currentSession.workspacePath
            val inheritedProject = currentSession.parentId?.let(projectMemoryRuntimeBindings::get)
            executionCapture = executionCapture ?: projectMemoryIdentityResolver.capture(root,
                projectMemoryJournal.sessionIdentity(sessionId) ?: inheritedProject?.identity, headless = isAutoTrigger || isSubSession)
            val baseCapture = executionCapture ?: throw IllegalStateException(context.getString(R.string.recovery_request_not_saved))
            val frozenCapture = if (expectedSubAgentEvent == null) baseCapture else ProjectMemoryCapture(
                baseCapture.identity, baseCapture.channelGeneration
            ) {
                baseCapture.assertCurrent()
                if (!isCurrentSubAgentEvent(expectedSubAgentEvent)) throw CancellationException("Subagent parent execution changed")
            }
            frozenCapture.assertCurrent()
            val environment = recoveryCodec.encodeToString(frozenCapture.identity)
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
            recoveryRun = recoveryRun ?: recoveryRepository.claim(accepted.id, environment)
            val ownedRun = recoveryRun ?: run {
                recoveryRepository.awaitConfirmation(accepted.id)
                if (sessionJobs[sessionId] == coroutineContext[Job]) setAgentState(sessionId, AgentUIState.Idle)
                return@launch
            }
            if (!recoveryRepository.isCurrent(ownedRun.id)) return@launch
            activeRecoveryRuns[sessionId] = ownedRun.id
            if (resuming) fileVersions.beginRecovery(sessionId)
            var failed = false
            if (isSubSession) {
                val parentId = requireNotNull(currentSession?.parentId)
                if (!subAgentEventBus.tryMarkStarted(sessionId)) {
                    subAgentWaitingSlots.value += sessionId
                    try {
                        while (!subAgentEventBus.tryMarkStarted(sessionId)) delay(100)
                    } finally {
                        subAgentWaitingSlots.value -= sessionId
                    }
                }
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
            // 必须在插入本次用户消息之前读取历史：workflow 会自己 add(userRequest)，避免重复。
            val historyStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(
                TAG,
                "history.start",
                details = "operation=$historyStartedNs requestChars=${request.length} attachments=${inputAttachments.size}"
            )
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            FileLogger.memoryCheckpoint(
                TAG,
                "history.ready",
                elapsedMs = (System.nanoTime() - historyStartedNs) / 1_000_000,
                details = "operation=$historyStartedNs messages=${history.size}"
            )
            val isFirst = history.isEmpty()
            val preparedModelRequest = if (resuming) {
                recoveryContinuationInput(requireNotNull(ownedRun.resumeSnapshot),
                    requireNotNull(recoveryRepository.payload(accepted.id)), accepted.inputMessageId,
                    history, todoItemDao.getBySessionOnce(sessionId).map { it.toDomain() })
            } else modelRequest

            val userMsgId = if (resuming) "resume_${ownedRun.id}" else accepted.inputMessageId
            val persistedRequest = if (notificationSeqs.isNotEmpty() && !resuming) modelRequest else request
            if (!persistUserMessage) {
                // 目标续跑等内部轮次：不落库、不建检查点，仅把指令作为本轮用户消息发给模型。
            } else if (!isAutoTrigger) {
                if (notificationSeqs.isEmpty()) {
                    messagePersistenceUseCase.persist(sessionId, MessageRole.USER, persistedRequest, id = userMsgId, attachments = inputAttachments,
                            recoveryRunId = ownedRun.id,
                            continuationOfMessageId = accepted.inputMessageId.takeIf { resuming })
                } else {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        messagePersistenceUseCase.persist(sessionId, MessageRole.USER, persistedRequest, id = userMsgId, attachments = inputAttachments,
                            recoveryRunId = ownedRun.id,
                            continuationOfMessageId = accepted.inputMessageId.takeIf { resuming },
                            notificationSeqs = notificationSeqs)
                    }
                }
                checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    // 后台异步用 LLM 生成更贴切的标题替换临时标题；失败/取不到时保留临时标题
                    pendingTitleRequest = {
                        viewModelScope.launch {
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
            // 子会话的 subagentType 存的是自定义 agent 名；能查到定义时提示词与工具集都按它组装。
            // 定义来自目录扫描（远程模式经 SFTP），故切到 IO，避免阻塞主线程。
            val agentDefinition = withContext(Dispatchers.IO) {
                sessionEntity?.takeIf { it.parentId != null }
                    ?.subagentType
                    ?.let { agentDefinitionRepository.findIncludingDisabled(it) }
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
                projectRoot = projectRoot,
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
                    _reasoningTimings.value = _reasoningTimings.value + (sessionId to (start to end))
                }
            }
            val requestJob = requireNotNull(coroutineContext[Job])
            agentWorkflow.executeEvents(
                userRequest = preparedModelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
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
                        _reasoningTimings.value = _reasoningTimings.value +
                            (sessionId to (currentReasoningStart!! to currentReasoningEnd))
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        startPendingTitle()
                        finishReasoning()
                        // 工具名先于参数到达：让 UI 把「正在思考」换成具体场景（「正在编辑文件」）。
                        // 参数流完、工具真正开始执行后由 ToolCallStarted 清掉，改由工具行表达。
                        setPreparingTool(sessionId, event.toolName)
                    }
                    AgentEvent.GenerationStarted -> {
                        _preparingToolArguments.value -= sessionId
                    }
                    is AgentEvent.GenerationSpeed -> setGenerationSpeed(sessionId, event)
                    is AgentEvent.Retrying -> {
                        _preparingToolArguments.value -= sessionId
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // 重试会从头重新流式输出：清掉已展示的正文/思维链气泡，
                        // 否则重连后思维链重新生成而旧正文残留（workflow 已同步清空累积器）。
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
                    is AgentEvent.CompactionProgress, is AgentEvent.CompactionFinished -> {
                        acceptCompactionEvent(sessionId, requestJob, event)
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        if (!acceptCompactionEvent(sessionId, requestJob, event)) return@collect
                        // 落库为无配对的 TOOL 消息：UI 渲染失败卡片，buildHistory 回放自动丢弃，不进模型上下文。
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            compactionFailureMessage(event.reason),
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true,
                            requireSession = true
                        )
                    }
                    is AgentEvent.AssistantText -> {
                        startPendingTitle()
                        finishReasoning()
                        val reasoningDuration = currentReasoningStart?.let { start ->
                            currentReasoningEnd?.minus(start)
                        }
                        currentReasoningStart = null
                        currentReasoningEnd = null

                        // 流式收尾：在落库并触发 UI messages 更新之前，先同步清空流式状态，
                        // 避免落库消息先行发射导致 UI 出现「落库消息与流式气泡同屏并存」的时差。
                        reasoningExpansionOverrides.remove(streamingReasoningKey(sessionId))?.let { expanded ->
                            reasoningExpansionOverrides[event.messageId] = expanded
                        }
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        val msgId = event.messageId
                        if (reasoningDuration != null && reasoningDuration > 0) {
                            reasoningDurations[msgId] = reasoningDuration
                        }
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
                            // 同步写库：工具循环下一轮 CallLlm 前会重读 lastInputTokens 判断压缩，
                            // 异步写库可能读到压缩前的旧大值导致重复触发压缩。
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        } else {
                            // 上游没给 token 时也透出一次 0 事件，驱动面板用兜底路径刷新，
                            // 否则最近 input/output 一直停在上一轮真实值，上游切换无感。
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, 0, 0, 0))
                        }
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
                        _toolExecutionStarts.value += sessionId to
                            (_toolExecutionStarts.value[sessionId].orEmpty() + ("tool_${event.id}" to event.startedElapsedMs))
                    }
                    is AgentEvent.TurnFinished -> {
                        val duration = turnStartedElapsed[sessionId]?.let {
                            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
                        } ?: event.durationMs
                        event.messageId?.let { recoveryRepository.updateTurnDuration(ownedRun.id, it, duration) }
                    }
                    is AgentEvent.ToolCallStarted -> {
                        turnHadActivity = true
                        val msgId = "tool_${event.id}"
                        runMessageIds += msgId
                        setStreamingText(sessionId, null)
                        setPreparingTool(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false,
                            toolActionTitle = event.actionTitle,
                            recoveryRunId = ownedRun.id,
                            recoveryPlaceholder = true
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                        _preparingToolArguments.value += sessionId to (_preparingToolArguments.value[sessionId].orEmpty() - event.id)
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
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
                        // 子代理会话失败时通知父会话
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error,
                                        eventId = "terminal-${ownedRun.id}",
                                        runId = ownedRun.id,
                                        generation = ownedRun.generation
                                    ), finish = { recoveryRepository.finish(ownedRun.id, RecoveryStatus.FAILED) }
                                )
                                terminalInteractionRecorded = true
                            }
                        }
                    }
                    AgentEvent.PlanRejected -> {
                        planRejected = true
                        progressOutcome = "interrupted"
                        recoveryOutcome = RecoveryStatus.ABANDONED
                        planRejectedSessions.add(sessionId)
                        val parentId = currentSession?.parentId
                        if (parentId != null) {
                            subAgentEventBus.emit(SubAgentEvent(
                                subSessionId = sessionId, parentSessionId = parentId,
                                type = SubAgentEventType.STOPPED,
                                detail = context.getString(R.string.plan_approval_stopped),
                                eventId = "terminal-${ownedRun.id}", runId = ownedRun.id, generation = ownedRun.generation
                            ), finish = { recoveryRepository.finish(ownedRun.id, RecoveryStatus.ABANDONED, "PLAN_REJECTED") })
                            terminalInteractionRecorded = true
                        } else {
                            recoveryRepository.finish(ownedRun.id, RecoveryStatus.ABANDONED, "PLAN_REJECTED")
                        }
                        setAgentState(sessionId, AgentUIState.Idle)
                        _completedSessions.value = _completedSessions.value - sessionId
                        val session = sessionUseCase.getSessionById(sessionId)
                        if (session?.goalStatus == GoalStatus.ACTIVE.name) {
                            sessionUseCase.updateGoalStatus(sessionId, GoalStatus.PAUSED)
                        }
                    }
                    AgentEvent.Completed -> {
                        if (sessionJobs[sessionId] !== requestJob) return@collect
                        sawCompleted = true
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        if (planRejected || failed) return@collect
                        // 仅当 App 不在前台时发 agent 完成通知（避免打扰正在看对话的用户）。
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                    }
                }
            }
            if (sessionJobs[sessionId] !== requestJob || !requestJob.isActive || sessionId in deletedSessionIds) return@launch
            turnSucceeded = sawCompleted && !failed && !planRejected
            if (turnSucceeded) {
                val parentId = currentSession?.parentId
                if (parentId != null) {
                    subAgentEventBus.emit(SubAgentEvent(
                        subSessionId = sessionId, parentSessionId = parentId,
                        type = SubAgentEventType.COMPLETED,
                        eventId = "terminal-${ownedRun.id}",
                        sourceMessageId = latestAssistantBodyId,
                        runMessageIds = runMessageIds.toSet(),
                        runId = ownedRun.id, generation = ownedRun.generation
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
            // 仅当本 job 仍持有忙状态时才置完成态：并发场景下队列/通知可能已启动新的 job
            // 并把状态改为 Streaming，不能被先结束的 job 误覆盖成 Result（按钮会提前变回发送）。
            val finishedState = _agentStates.value[sessionId]
            if (!failed && !planRejected && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                agentLiveNotificationManager.markCompletionOutcome(sessionId, failed = false)
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            val recordedStop = externallyRecordedSubAgentStops.remove(sessionId)
            if (isSubSession && !terminalInteractionRecorded && !recordedStop) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching {
                        currentSession?.parentId?.let { parentId ->
                            subAgentEventBus.recordForDisplay(SubAgentEvent(
                                sessionId, parentId, SubAgentEventType.STOPPED,
                                context.getString(R.string.agent_stopped_by_user),
                                eventId = recoveryRun?.let { "terminal-${it.id}" } ?: UUID.randomUUID().toString(),
                                runId = recoveryRun?.id, generation = recoveryRun?.generation
                            ), finish = { recoveryRun?.let { recoveryRepository.finish(it.id, RecoveryStatus.INTERRUPTED) } ?: true })
                        }
                    }.onFailure { FileLogger.w(TAG, "Could not record interrupted subagent", it) }
                }
            }
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (sessionJobs[sessionId] == coroutineContext[Job] &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            // goal 自主循环期间用户打断：暂停目标，避免下条消息后自动续跑（对齐 Codex「打断即暂停」）。
            if (goalActiveAtTurn) {
                viewModelScope.launch {
                    runCatching { sessionUseCase.updateGoalStatus(sessionId, GoalStatus.PAUSED) }
                }
            }
            throw e
        } catch (e: Exception) {
             progressOutcome = "failed"
             recoveryOutcome = RecoveryStatus.FAILED
             if (goalActiveAtTurn && sessionJobs[sessionId] == coroutineContext[Job]) {
                 sessionUseCase.updateGoalStatus(sessionId, GoalStatus.BLOCKED)
             }
             FileLogger.e(TAG, "executeAgentRequestStream 失败", e)
             agentLiveNotificationManager.markCompletionOutcome(sessionId, failed = true)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage(context)))
             _completedSessions.value = _completedSessions.value - sessionId
             // 子会话在 workflow 之外失败（如历史构建异常）：通知父会话并释放并发名额。
             // 不补这一手，父代理会一直等一条永不到来的完成通知，名额也泄漏到进程重启。
             if (isSubSession) {
                 sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                     subAgentEventBus.emit(
                         SubAgentEvent(
                             subSessionId = sessionId,
                             parentSessionId = parentId,
                             type = SubAgentEventType.FAILED,
                             detail = e.toUserMessage(context),
                             eventId = recoveryRun?.let { "terminal-${it.id}" } ?: UUID.randomUUID().toString(),
                             runId = recoveryRun?.id, generation = recoveryRun?.generation
                         ), finish = { recoveryRun?.let { recoveryRepository.finish(it.id, RecoveryStatus.FAILED) } ?: true }
                     )
                 }
             }
        } finally {
            val finishingJob = coroutineContext[Job]
            withContext(kotlinx.coroutines.NonCancellable) {
            startPendingTitle()
            withContext(Dispatchers.IO) {
                if (recoveryRun == null) acceptedRequestId?.let { runCatching { recoveryRepository.awaitConfirmation(it) } }
                recoveryRun?.let { runCatching { recoveryRepository.finish(it.id, recoveryOutcome) } }
                progressCollector?.let { collector ->
                    runCatching { collector.save(progressOutcome) }
                    projectProgressTerminalTracker.release(sessionId, collector)
                }
            }
            val isOwnJob = sessionJobs[sessionId] == finishingJob
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            // 子会话并发名额的兜底释放：正常路径已由 COMPLETED/FAILED/STOPPED 事件移出活跃集合，
            // 这里覆盖取消（如切换工作区的 stopAllAgents）、外层异常等不发事件的收尾路径。
            // 已释放时 release 返回 false 无副作用；不在此补发通知——需要通知的路径都各自处理过。
            if (isOwnJob) {
                if (isSubSession) subAgentEventBus.release(sessionId)
                sessionJobs.remove(sessionId)
                activeRecoveryRuns.remove(sessionId)
                _runningTools.value = _runningTools.value - sessionId
                _preparingToolArguments.value -= sessionId
                _toolExecutionStarts.value -= sessionId
                setStreamingText(sessionId, null)
                setStreamingReasoning(sessionId, null)
                setPreparingTool(sessionId, null)
                setCompacting(sessionId, false)
                setRetryState(sessionId, null)
                setKeySwitchState(sessionId, null)
                // 取消/失败路径不会走到 AssistantDelta 的 finishReasoning。
                val timing = _reasoningTimings.value[sessionId]
                if (timing != null && timing.second == null) {
                    _reasoningTimings.value = _reasoningTimings.value +
                        (sessionId to (timing.first to System.currentTimeMillis()))
                }
                if (sessionId !in stoppingSessions) {
                    // 拒绝收尾期间用户可能已提交新请求；先接续显式请求，再补后台通知。
                    if (planRejected) processNextInQueue(sessionId)
                    if (notificationSeqs.isEmpty() || agentNotificationCenter.peek(sessionId).none { it.seq in notificationSeqs }) {
                        flushPendingNotifications(sessionId)
                    }
                    val currentState = _agentStates.value[sessionId]
                    if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                        setAgentState(sessionId, AgentUIState.Idle)
                    }
                    if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                        if (turnSucceeded) runCatching { maybeQueueGoalContinuation(sessionId, isGoalTurn, turnHadActivity) }
                        processNextInQueue(sessionId)
                    }
                }
            }
            }
        }
    }.also { job ->
        // 同步注册 job：launch 内的 sessionJobs 赋值是异步的，finally 中 flushPendingNotifications
        // 与 processNextInQueue 会在赋值前都看到 isActive=false 而双消费启动两个 job，
        // 先结束的 job 把状态置 Idle/Result 覆盖仍在跑的 job 的 Streaming。
        if (targetSessionId != null && (subAgentInstructionId != null || slashCommandRegistry.resolve(request) == null)) {
            sessionJobs[targetSessionId] = job
            job.invokeOnCompletion {
                if (sessionJobs[targetSessionId] === job) sessionJobs.remove(targetSessionId)
            }
        }
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

    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        acceptanceEpoch++
        val jobs = (sessionJobs.values + compressionCommandOwners.values).distinct().filter { !it.isCompleted }
        compressionCommandOwners.clear()
        val stoppedSessions = (sessionJobs.keys + _agentStates.value.keys + planApprovalManager.awaitingSessionIds.value).toSet()
        planRejectedSessions.addAll(stoppedSessions)
        pendingNotificationStops++
        stopAllSettled.value = false
        viewModelScope.launch {
            try {
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
                viewModelScope.launch { recoveryRepository.abandon(requestId) }
            }
        }
        _queuedRequests.value = emptyMap()
        goalEmptyTurnCounts.clear()
        // 先释放全部子代理并发名额再取消：job 取消走 CancellationException 路径，
        // 不会发 COMPLETED/FAILED 事件，不释放的话名额一直占到进程重启，
        // 之后任何会话都无法再派子代理。不通知父会话——切换工作区是用户主动停一切，
        // 父会话自身也在被停之列，通知只会触发已停会话的无谓新一轮。
        val activeSubIds = subAgentEventBus.activeSubSessionIds.value.toList()
        activeSubIds.forEach { subAgentEventBus.release(it) }
        // 同理，被取消的子代理也不会有终态事件：不就地落卡片状态的话，
        // 父会话聊天流里的派发卡片会永远显示「运行中」，直到进程重启。
        activeSubIds.forEach { id ->
            if (_subAgentCards.value.containsKey(id)) {
                markSubAgentFinished(id, SubAgentCardStatus.STOPPED)
            }
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

    /**
     * 主动打断当前会话正在运行的 agent：取消协程（会一并取消挂起的网络请求与容器命令进程），
     * 并把「执行中」的工具占位行收尾为「已停止」，避免悬挂的 spinner 与孤儿记录。
     */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * 停止指定会话的 AI 任务（子代理停止/用户手动停止共用）。
     * 取消 job 并把未完成的流式内容落库为「已停止」；队列下一条照常执行。
     */
    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: compressionCommandOwners[sessionId] ?: return
        if (!job.isActive) return
        stoppingSessions.add(sessionId)
        if (planApprovalManager.pendingForSession(sessionId) != null) {
            planRejectedSessions.add(sessionId)
        }
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
        // cancel() 在 Dispatchers.Main.immediate 上可能立即恢复挂起协程
        // （如 awaitApproval 的 CompletableDeferred.await），旧 job 的 finally →
        // flushPendingNotifications 在 cancel() 调用栈内同步执行并可能启动新 job。
        // 不预先清除待送通知——它们应由 finally 正常 flush 给新 job 处理。
        job.cancel()
        viewModelScope.launch {
            var sessionExists = false
            try {
            job.join()
            sessionExists = sessionUseCase.getSessionById(sessionId) != null
            if (!sessionExists) return@launch
            if (recoveryAtStop == null && runningTools.isNotEmpty()) {
                // 并行执行被中止：所有未完成的工具都落库为「已停止」
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
                val reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() }
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    reasoning = reasoning
                )
            }
            // 授权弹窗挂起中的工具调用：awaitApproval 挂起期间 _runningTools 为空
            // （ToolCallStarted 在授权通过后才发出），但 AssistantText 已落库了带
            // tool_call 声明的 assistant 消息。不补结果会导致该 tool_call 成为
            // 孤立记录，被 buildHistory 的 validIds 交集过滤掉，AI 不知道自己曾调用过。
            if (recoveryAtStop == null && pendingPermission != null) {
                val msgId = "tool_${pendingPermission.id}"
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = stoppedText,
                    id = msgId,
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

    // region 会话管理

    /**
     * 清掉一个会话的全部运行时状态（并发名额、通知队列、待发请求、job、流式/工具占位）。
     * UI 删除与 task(action="del") 共用的收尾：不清的话，取消中 job 的 finally 会对着
     * 已删除的会话补送通知或执行队列下一条——白烧一轮 LLM、往不存在的会话落孤儿消息。
     */
    private fun clearSessionRuntimeState(sessionId: String) {
        acceptanceEpoch++
        deletedSessionIds.add(sessionId)
        messagePersistenceUseCase.releaseSessionCache(sessionId)
        activeRecoveryRuns.remove(sessionId)
        projectMemoryRuntimeBindings.release(sessionId)
        viewModelScope.launch(Dispatchers.IO) { projectMemoryJournal.clearSession(sessionId) }
        projectProgressTerminalTracker.clearSession(sessionId)
        compressionCommandOwners.remove(sessionId)?.cancel()
        setCompacting(sessionId, false)
        _preparingToolArguments.value -= sessionId
        _toolExecutionStarts.value -= sessionId
        subAgentWaitingSlots.value -= sessionId
        turnStartedElapsed.remove(sessionId)
        subAgentEventBus.release(sessionId)
        if (sessionJobs[sessionId]?.isActive == true) externallyRecordedSubAgentStops.add(sessionId)
        // 先清队列再取消 job：cancel 可能在调用栈内同步跑 finally → flushPendingNotifications，
        // 队列已空则直接返回，不会为已删除会话再起一轮。
        viewModelScope.launch { agentNotificationCenter.clear(sessionId) }
        _queuedRequests.value = _queuedRequests.value - sessionId
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        _agentStates.value = _agentStates.value - sessionId
        _streamingTexts.value = _streamingTexts.value - sessionId
        setStreamingReasoning(sessionId, null)
        _runningTools.value = _runningTools.value - sessionId
        _retryStates.value = _retryStates.value - sessionId
        setGenerationSpeed(sessionId, null)
        _inputDrafts.value = _inputDrafts.value - sessionId
        draftPrefs.edit().remove(sessionId).apply()
    }

    /** 新建会话；工作区里已有空会话时直接切过去复用（当前会话为空则优先），避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return@launch
        val curId = _currentSessionId.value
        val reusableId = if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            curId
        } else {
            sessionUseCase.getEmptySessionOfWorkspace(ws)?.id
        }
        if (reusableId != null) {
            setAgentState(reusableId, AgentUIState.Idle)
            _currentSessionId.value = reusableId
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(ws)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // 同步记忆到模型级默认档位，供后续新建会话沿用
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            _contextUsages.value = _contextUsages.value - sid
            setGenerationSpeed(sid, null)
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 输入框 `@` 文件引用菜单状态。 */
    private val _fileMentionState = MutableStateFlow<FileMentionState>(FileMentionState.Idle)
    val fileMentionState: StateFlow<FileMentionState> = _fileMentionState.asStateFlow()

    private var fileMentionJob: Job? = null

    /**
     * 打开 `@` 菜单：当前无清单时拉取一次；一次 `@` 会话内（[dismissFileMention] 前）不重复拉取。
     */
    fun requestFileMention() {
        val current = _fileMentionState.value
        if (current is FileMentionState.Ready || current is FileMentionState.Loading) return
        fileMentionJob?.cancel()
        _fileMentionState.value = FileMentionState.Loading
        fileMentionJob = viewModelScope.launch {
            val result = fileMentionRepository.loadFiles(_currentWorkspace.value)
            _fileMentionState.value = when (result) {
                is FileMentionRepository.LoadResult.Success -> FileMentionState.Ready(result.files)
                FileMentionRepository.LoadResult.Unavailable -> FileMentionState.Unavailable
                is FileMentionRepository.LoadResult.Failed -> FileMentionState.Error(result.message)
            }
        }
    }

    /** 关闭 `@` 菜单：丢弃清单，下次打开重新拉取，保证拿到最新文件列表。 */
    fun dismissFileMention() {
        fileMentionJob?.cancel()
        fileMentionJob = null
        _fileMentionState.value = FileMentionState.Idle
    }

    /** 在已就绪的清单里匹配当前查询；未就绪返回空。 */
    fun matchFileMentions(query: String): List<String> {
        val files = (_fileMentionState.value as? FileMentionState.Ready)?.files ?: return emptyList()
        return fileMentionRepository.match(files, query)
    }

    /** 暴露给 UI：输入框 `/` 菜单展示的命令列表（内置命令 + 已启用技能）。 */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** 重新扫描技能并刷新命令菜单；进入 `/` 菜单时调用，反映技能的增删改。 */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init —— 触发 agent 回合，分析代码库并生成/改进项目规则文件。 */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = prompt,
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** 技能触发 —— 把技能正文作为本轮指令执行。 */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = SlashCommandRegistry.buildSkillPrompt(skill, args),
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
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
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
        }
    }

    /** /goal —— 管理当前会话的持续目标。 */
    override fun setSessionGoal(rawArgs: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = sessionUseCase.getSessionById(sid) ?: return@launch
            val arg = rawArgs.trim()
            when {
                arg.isEmpty() -> persistGoalNotice(sid, goalSummary(session))
                arg.equals("clear", ignoreCase = true) || arg.equals("off", ignoreCase = true) -> clearGoal(sid)
                arg.equals("pause", ignoreCase = true) -> pauseGoal(sid)
                arg.equals("resume", ignoreCase = true) -> resumeGoal(sid)
                else -> setGoalText(sid, session, arg)
            }
        }
    }

    /** UI：暂停/恢复当前会话的 goal 自主循环。 */
    fun setGoalPaused(paused: Boolean) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch { if (paused) pauseGoal(sid) else resumeGoal(sid) }
    }

    /** UI：清除当前会话的 goal。 */
    fun clearGoal() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch { clearGoal(sid) }
    }

    private fun goalSummary(session: ChatSessionEntity): String {
        val goal = session.goal?.takeIf { it.isNotBlank() }
            ?: return "当前会话未设定目标。用 `/goal <目标>` 设定后，AI 会自主循环推进直到达成、受阻或预算耗尽。"
        val status = session.goalStatus
            ?.let { runCatching { GoalStatus.valueOf(it) }.getOrNull() }
            ?: GoalStatus.ACTIVE
        val statusLabel = when (status) {
            GoalStatus.ACTIVE -> "进行中"
            GoalStatus.PAUSED -> "已暂停"
            GoalStatus.COMPLETED -> "已完成"
            GoalStatus.BLOCKED -> "受阻"
            GoalStatus.EXHAUSTED -> "预算耗尽"
        }
        return "当前目标（$statusLabel，已续跑 ${session.goalTurnCount} 轮）：\n$goal"
    }

    private suspend fun setGoalText(sid: String, session: ChatSessionEntity, text: String) {
        val goalText = text.trim().take(MAX_GOAL_CHARS)
        if (goalText.isEmpty()) return
        goalEmptyTurnCounts.remove(sid)
        sessionUseCase.setGoal(sid, goalText, session.totalInputTokens, session.totalOutputTokens)
        persistGoalNotice(
            sid,
            "已设定目标：$goalText\nAI 将自主循环推进，直到达成、受阻或预算耗尽。" +
                "可随时 `/goal pause` / `/goal resume` / `/goal clear`。"
        )
        enqueueGoalTurn(sid, session.workspacePath, buildGoalPrompt(goalText, 1))
    }

    private suspend fun clearGoal(sid: String) {
        goalEmptyTurnCounts.remove(sid)
        sessionUseCase.setGoal(sid, null, 0, 0)
        persistGoalNotice(sid, "已清除当前目标。")
    }

    private suspend fun pauseGoal(sid: String) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        if (session.goal.isNullOrBlank()) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        sessionUseCase.updateGoalStatus(sid, GoalStatus.PAUSED)
        persistGoalNotice(sid, "目标已暂停。用 `/goal resume` 继续。")
    }

    private suspend fun resumeGoal(sid: String) {
        val session = sessionUseCase.getSessionById(sid) ?: return
        val goal = session.goal?.takeIf { it.isNotBlank() }
        if (goal == null) {
            persistGoalNotice(sid, "当前会话未设定目标。")
            return
        }
        planRejectedSessions.remove(sid)
        recoveryRepository.clearExplicitHold(sid)
        sessionUseCase.updateGoalStatus(sid, GoalStatus.ACTIVE)
        persistGoalNotice(sid, "目标已恢复，继续自主循环。")
        enqueueGoalTurn(sid, session.workspacePath, buildGoalPrompt(goal, session.goalTurnCount + 1))
    }

    /**
     * goal 自主循环续跑：本轮正常完成后调用。目标仍激活、预算未耗尽且无待跑用户消息时，
     * 追加下一轮续跑；预算触顶则置 EXHAUSTED 并提示。
     */
    private suspend fun maybeQueueGoalContinuation(sessionId: String, isGoalTurn: Boolean, hadActivity: Boolean) {
        val session = sessionUseCase.getSessionById(sessionId) ?: return
        val goal = session.goal?.takeIf { it.isNotBlank() } ?: return
        if (session.goalStatus != GoalStatus.ACTIVE.name) return
        // 用户消息优先：队列里已有待跑项时不追加续跑，等用户消息跑完再续。
        if (!_queuedRequests.value[sessionId].isNullOrEmpty()) return
        // 空回合熔断：自动续跑回合连续无进展（既无工具调用也无可见正文）达到阈值即置 BLOCKED。
        if (isGoalTurn) {
            if (hadActivity) {
                goalEmptyTurnCounts.remove(sessionId)
            } else {
                val n = (goalEmptyTurnCounts[sessionId] ?: 0) + 1
                if (n >= MAX_GOAL_EMPTY_TURNS) {
                    goalEmptyTurnCounts.remove(sessionId)
                    sessionUseCase.updateGoalStatus(sessionId, GoalStatus.BLOCKED)
                    persistGoalNotice(
                        sessionId,
                        "目标自主循环连续 $MAX_GOAL_EMPTY_TURNS 轮无进展，已置为「受阻」并停止。可 `/goal resume` 继续或 `/goal clear` 清除。"
                    )
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
            persistGoalNotice(
                sessionId,
                "目标自主循环已达预算上限（轮次上限 $maxTurns 轮 / token 预算 $maxTokens），已停止。" +
                    "可 `/goal resume` 继续或 `/goal clear` 清除。"
            )
            return
        }
        sessionUseCase.incrementGoalTurn(sessionId)
        enqueueGoalTurn(sessionId, session.workspacePath, buildGoalPrompt(goal, session.goalTurnCount + 1))
    }

    /** 把一轮 goal 续跑/启动请求交给会话：空闲则立即执行，忙则入队（用户消息优先）。不落库。 */
    private fun enqueueGoalTurn(sessionId: String, workspacePath: String, prompt: String) {
        enqueueAgentRequest(
            request = prompt,
            modelRequest = prompt,
            projectRoot = workspacePath,
            isAutoTrigger = true,
            persistUserMessage = false,
            targetSessionId = sessionId
        )
    }

    private fun buildGoalPrompt(goal: String, turn: Int): String =
        "【目标自主循环 · 第 $turn 轮】继续推进目标：$goal\n" +
            "按系统提示中的完成审计与受阻审计规则决定是否调用 goalStatus；未达成则直接继续下一步。"

    private suspend fun persistGoalNotice(sid: String, text: String) {
        sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
        messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, text, isContextExcluded = true)
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 汇总自 [start] 起的调用次数、token 与预估费用；费用按「渠道 + 模型」分组逐组估算，才取得到自定义单价。 */
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

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        startContextCompaction(sid, allowCommand = true)
    }

    fun compactSession(sid: String, currentFile: String? = null, selectedCode: String? = null) =
        startContextCompaction(sid, false, currentFile, selectedCode)

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
        sid: String,
        allowCommand: Boolean,
        currentFile: String? = null,
        selectedCode: String? = null
    ) {
        if (sid in deletedSessionIds || sid in stoppingSessions || sid in approvalSessionIds.value ||
            (!allowCommand && sid in _runningCommandSessions.value) || _compactingSessions.value[sid] == true ||
            sessionJobs[sid]?.isCompleted == false) return
        val workspace = _currentWorkspace.value
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val owner = requireNotNull(coroutineContext[Job])
            protectJob(owner)
            setCompacting(sid, true)
            var failed = false
            suspend fun writable(): Boolean = sessionJobs[sid] === owner && owner.isActive &&
                sid !in deletedSessionIds && sid !in stoppingSessions && sessionUseCase.getSessionById(sid) != null
            try {
                val entity = sessionUseCase.getSessionById(sid) ?: return@launch
                if (entity.workspacePath != workspace || sid in deletedSessionIds) return@launch
                val domain = entity.toDomain()
                val definition = withContext(Dispatchers.IO) {
                    entity.subagentType?.takeIf { entity.parentId != null }?.let { agentDefinitionRepository.findIncludingDisabled(it) }
                }
                if (!writable() || _currentWorkspace.value != workspace) return@launch
                val snapshot = AgentContext(
                    currentFile = currentFile,
                    selectedCode = selectedCode,
                    language = currentFile?.let { detectLanguage(it) },
                    projectRoot = entity.workspacePath,
                    sessionId = sid,
                    goal = domain.goal,
                    goalStatus = domain.goalStatus,
                    reasoningEffort = domain.reasoningEffort?.apiValue,
                    agentDefinition = definition
                )
                val tools = effectiveSessionTools(definition, entity.parentId != null, domain.goalStatus == GoalStatus.ACTIVE)
                val changed = agentWorkflow.compactSession(sid, snapshot, tools) { event ->
                    if (!acceptCompactionEvent(sid, owner, event)) return@compactSession
                    if (event is AgentEvent.CompactionFailed) {
                        failed = true
                        if (writable()) messagePersistenceUseCase.persist(
                            sessionId = sid,
                            role = MessageRole.TOOL,
                            content = compactionFailureMessage(event.reason),
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true,
                            requireSession = true
                        )
                    }
                }
                if (!failed && !changed && writable()) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction),
                        requireSession = true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "手动压缩未完成: session=$sid")
                if (writable()) messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, compactionFailureMessage("compaction_failed")),
                    requireSession = true
                )
            } finally {
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
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                subAgentEventBus.release(id)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            clearSessionRuntimeState(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        deletedSessionIds.add(id)
        // 删掉运行中的子代理时先取它的父会话与标题（删库后就取不到了），稍后告知父代理。
        // 删的是父会话时无需通知（父会话本身也没了），但级联删掉的子会话仍要交回并发槽位。
        val deletedSession = sessionUseCase.getSessionById(id)
        deletedSession?.parentId?.let { parentId ->
            subAgentEventBus.recordForDisplay(SubAgentEvent(
                id, parentId, SubAgentEventType.DELETED,
                context.getString(R.string.subagent_interaction_deleted_notice), origin = "USER"
            ))
        }
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            clearSessionRuntimeState(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        stoppedSubAgent?.let { sub ->
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。"
            )
        }
    }

    // Checkpoint Rewind 选中的目标消息 id
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    /**
     * 最近一次「回滚代码」因文件已被其他会话/用户改过而跳过的文件清单（逻辑路径）。
     *
     * 用一次性状态而不是事件流：UI 消费后调用 [clearRewindConflicts] 置空，避免重组时重复提示。
     */
    private val _rewindConflicts = MutableStateFlow<List<String>>(emptyList())
    val rewindConflicts: StateFlow<List<String>> = _rewindConflicts.asStateFlow()

    /** UI 已展示过冲突提示，清空以免重复弹。 */
    fun clearRewindConflicts() {
        _rewindConflicts.value = emptyList()
    }

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. 停止当前正在运行的 Agent 任务及后续排队
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        acceptanceEpoch++
        recoveryRepository.invalidateSession(sessionId, "rewind")
        projectMemoryRuntimeBindings.release(sessionId)
        projectProgressTerminalTracker.clearSession(sessionId)
        compressionCommandOwners.remove(sessionId)?.cancelAndJoin()
        _runningCommandSessions.value -= sessionId
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)

        // 2. 重置会话运行、流式与检查点状态
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        _contextUsages.value = _contextUsages.value - sessionId
        setGenerationSpeed(sessionId, null)
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        if (targetMsgEntity.isCompactionMarker || targetMsgEntity.isContextSummary) return@launch
        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                    _rewindConflicts.value = result.skipped
                }
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                    _rewindConflicts.value = result.skipped
                }
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** 置顶/取消置顶会话。置顶后排在列表最前（置顶分组），不改 updatedAt。 */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        )
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新消息失败", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg != null && msg.role == MessageRole.USER.name) {
                agentMessageDao.deleteMessagesAfterTimestamp(msg.sessionId, msg.timestamp)
            }
            agentMessageDao.deleteMessageById(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除消息失败", e)
        }
    }

    /**
     * 从指定消息处分叉新会话：复制该消息（含）之前的全部消息，标题「Fork of 原标题」，
     * 复制 provider/model/mode/思考档位/目标等配置，token 计数按复制消息重算，不复制
     * checkpoint/todo/LLM 调用记录（分叉是干净副本），完成后切换到新会话。
     *
     * 截断点按 timestamp 取前缀，与 rewind 同口径；被上下文压缩吞掉的旧消息 UI 不展示、
     * 选不中，分叉点必在压缩摘要之后，摘要行按时间戳自然随副本带走，回放上下文完整。
     */
    fun forkFromMessage(messageId: String) = viewModelScope.launch {
        val sourceId = _currentSessionId.value ?: return@launch
        try {
            val target = agentMessageDao.getMessageById(messageId) ?: return@launch
            if (target.sessionId != sourceId) return@launch
            if (target.isCompactionMarker || target.isContextSummary) return@launch
            val source = chatSessionDao.getById(sourceId) ?: return@launch

            val copied = trimIncompleteTurnTail(
                agentMessageDao.getMessagesBySessionOnce(sourceId).filter { it.timestamp <= target.timestamp }
            )
            if (copied.isEmpty()) return@launch

            val now = System.currentTimeMillis()
            val fork = source.copy(
                id = UUID.randomUUID().toString(),
                title = context.getString(R.string.chat_fork_title, source.title),
                createdAt = now,
                updatedAt = now,
                goalTurnCount = 0,
                goalStartInputTokens = 0,
                goalStartOutputTokens = 0,
                totalInputTokens = copied.sumOf { it.inputTokens },
                totalOutputTokens = copied.sumOf { it.outputTokens },
                lastInputTokens = 0,
                isPinned = false,
                parentId = null,
                subagentType = null
            )
            chatSessionDao.upsert(fork)
            agentMessageDao.insertAll(
                copied.map { it.copy(id = UUID.randomUUID().toString(), sessionId = fork.id) }
            )
            selectSession(fork.id)
        } catch (e: Exception) {
            FileLogger.e(TAG, "分叉会话失败", e)
        }
    }

    /**
     * 截齐尾部不完整的轮次：末尾是 TOOL 行（配对的 assistant 工具调用被截走）或带
     * toolCalls 的 ASSISTANT 行（工具结果在截断点之后）时从末尾逐行丢弃，保证副本
     * 以完整轮次收尾——否则回放时出现悬挂 tool_use，Anthropic 等端点会直接 400。
     */
    private fun trimIncompleteTurnTail(messages: List<AgentMessageEntity>): List<AgentMessageEntity> {
        var result = messages
        while (result.isNotEmpty()) {
            val last = result.last()
            val incomplete = last.role == MessageRole.TOOL.name ||
                (last.role == MessageRole.ASSISTANT.name && !last.toolCallsJson.isNullOrBlank())
            if (!incomplete) break
            result = result.dropLast(1)
        }
        return result
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }
}
