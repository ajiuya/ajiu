package com.aicode.feature.agent.presentation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.asDirtySignal
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.mention.FileMentionRepository
import com.aicode.feature.agent.domain.mention.FileMentionState
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.GoalStatus
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationTimelineEntry
import com.aicode.feature.agent.domain.recovery.PendingRecoveryTarget
import com.aicode.feature.agent.domain.recovery.RecoveryRepository
import com.aicode.feature.agent.domain.recovery.RecoveryRun
import com.aicode.feature.agent.domain.recovery.RecoveryScope
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionSelectionStore
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.presentation.component.ReasoningScrollStateStore
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.filebrowse.FileBrowseController
import com.aicode.feature.agent.presentation.filebrowse.FileBrowseScope
import com.aicode.feature.agent.presentation.preview.PreparingToolPreviewSnapshot
import com.aicode.feature.agent.presentation.runtime.AgentRunManager
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.util.UUID
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeUserActions
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import javax.inject.Inject

data class ChatNotificationTimelineEvent(
    val entry: AgentNotificationTimelineEntry,
    val sourceMessageId: String?,
)

internal data class RecoveryBubbleItem(val target: PendingRecoveryTarget, val environmentMatches: Boolean)

internal data class RecoveryBubbleUi(
    val scope: RecoveryScope? = null,
    val items: List<RecoveryBubbleItem> = emptyList(),
    val runningSessions: Set<String> = emptySet(),
    val busy: Boolean = false
) {
    val batch = items.distinctBy { it.target.sessionId }
    val continueTargets = batch.filter { it.environmentMatches && it.target.sessionId !in runningSessions }.map { it.target }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val fileMentionRepository: FileMentionRepository,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerOsDetector: ContainerOsDetector,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionSelectionStore: SessionSelectionStore,
    private val subAgentInteractionReader: SubAgentInteractionReader,
    private val subAgentInstructionGateway: SubAgentInstructionGateway,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    val browserManager: com.aicode.feature.agent.domain.tool.browser.BrowserManager,
    val previewPathMapper: WorkspacePathMapper,
    private val toolPreviewFileAccessAdapter: ToolPreviewFileAccessAdapter,
    private val fileChangeHub: FileChangeHub,
    private val executionModeHolder: ExecutionModeHolder,
    private val workspaceExecutionContextFactory: WorkspaceExecutionContextFactory,
    private val localFolderGrants: com.aicode.feature.workspace.domain.LocalFolderGrantRepository,
    private val fileBackendRouting: com.aicode.feature.workspace.domain.FileBackendRouting,
    private val fileTransfers: com.aicode.feature.workspace.domain.FileTransferService,
    private val recoveryRepository: RecoveryRepository,
    private val projectMemoryIdentityResolver: ProjectMemoryIdentityResolver,
    @param:ApplicationContext private val context: Context,
    private val agentRunManager: AgentRunManager,
    private val localRuntimeUserActions: LocalRuntimeUserActions? = null
) : ViewModel(), SlashCommandContext {

    private val recoveryCodec = Json { ignoreUnknownKeys = true }
    val recovering: StateFlow<Boolean> = agentRunManager.recovering

    private val _currentSessionId = MutableStateFlow<String?>(null)
    @Volatile private var sessionSelectionRevision = 0L
    private val sessionSelections = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val sessionCreationMutex = Mutex()
    private val selectionLoadedWorkspace = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private fun updateSelectedSession(id: String?) {
        sessionSelectionRevision++
        _currentSessionId.value = id
        val workspace = _currentWorkspace.value
        if (id != null && workspace.isNotBlank()) sessionSelections.trySend(workspace to id)
    }
    val chatNotificationTimeline: StateFlow<Pair<String?, List<ChatNotificationTimelineEvent>>> = currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId.isNullOrBlank()) flowOf(null to emptyList()) else combine(
                agentNotificationCenter.observeTimeline(sessionId),
                recoveryRepository.observeNotificationInputMessageIds(sessionId),
            ) { events, inputMessageIds ->
                sessionId to events.map { event ->
                    ChatNotificationTimelineEvent(event, inputMessageIds[event.seq])
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val agentStates: StateFlow<Map<String, AgentUIState>> = agentRunManager.agentStates

    /**
     * 各会话「最后一轮是否正常完成」：只有正常收尾（收到完成标记）才在轮末回复下挂「复制 / 更多」
     * 按钮；主动暂停 / 出错 / 未开始都不挂，避免按钮挂在半截输出上。
     */
    val completedSessions: StateFlow<Set<String>> = agentRunManager.completedSessions

    /**
     * 子代理卡片状态（按子会话 id 索引），仅存内存。
     *
     * 卡片主体从工具结果渲染（agent、任务、档位、模型都在结果里），这里只补「本进程才知道」的实时状态：
     * 运行中 / 耗时 / 完成摘要 / 改动文件。进程重启后历史卡片自动回退为静态展示。
     */
    val subAgentCards: StateFlow<Map<String, SubAgentCardState>> = agentRunManager.subAgentCards

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else agentRunManager.agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

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

    suspend fun applyPluginChatUiRequest(request: com.aicode.feature.plugin.runtime.PluginChatUiRequest): Boolean {
        request.checkCurrent()
        if (_currentWorkspace.value != request.workspacePath) return false
        val revision = sessionSelectionRevision
        val selected = _currentSessionId.value
        val target = when (val action = request.action) {
            is com.aicode.feature.plugin.runtime.PluginChatUiAction.Open -> action.sessionId
            is com.aicode.feature.plugin.runtime.PluginChatUiAction.SetDraft -> action.sessionId ?: selected ?: return false
        }
        val session = chatSessionDao.getById(target) ?: return false
        request.checkCurrent()
        if (_currentWorkspace.value != request.workspacePath || session.workspacePath != request.workspacePath ||
            sessionSelectionRevision != revision || _currentSessionId.value != selected) return false
        when (val action = request.action) {
            is com.aicode.feature.plugin.runtime.PluginChatUiAction.Open -> selectSession(target)
            is com.aicode.feature.plugin.runtime.PluginChatUiAction.SetDraft -> {
                if (target != selected) selectSession(target)
                val existing = _inputDrafts.value[target].orEmpty()
                updateInputDraft(if (action.append && existing.isNotBlank()) existing.trimEnd() + "\n\n" + action.text else action.text)
            }
        }
        request.checkCurrent()
        return true
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
    internal val reasoningScrollStates = ReasoningScrollStateStore()

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
    private val scopedRecovery = combine(_currentWorkspace, currentSessionId, ::Pair).flatMapLatest { (workspace, sid) ->
        flow {
            emit(RecoveryBubbleUi())
            if (sid == null || workspace.isBlank() || !agentRunManager.awaitReady()) return@flow
            emitAll(chatSessionDao.getByIdFlow(sid).map { session ->
                session?.takeIf { it.workspacePath == workspace }?.let { RecoveryScope(it.id, workspace, it.executionGeneration) }
            }.distinctUntilChanged().flatMapLatest { scope ->
                if (scope == null) {
                    flowOf(RecoveryBubbleUi())
                } else {
                    combine(recoveryRepository.observePending(scope), projectMemoryIdentityResolver.environmentChanges.onStart { emit(Unit) }) { items, _ ->
                        val environment = if (items.isEmpty()) null else runCatching {
                            projectMemoryIdentityResolver.capture(workspace)?.identity?.let { recoveryCodec.encodeToString(it) }
                        }.getOrNull()
                        RecoveryBubbleUi(scope, items.map { item ->
                            RecoveryBubbleItem(
                                item.pendingTarget(), environment == item.request.environment &&
                                    item.payload.projectRoot == workspace && item.holdReason != "PLAN_REJECTED"
                            )
                        })
                    }.flowOn(Dispatchers.IO)
                }
            })
        }
    }
    private val recoveryPreparation = combine(
        agentRunManager.recoveryBusySessions, agentRunManager.consumedRecoveryTargets
    ) { preparing, _ -> preparing }
    internal val recoveryBubble: StateFlow<RecoveryBubbleUi> = combine(
        scopedRecovery, recoveryPreparation, agentRunManager.agentStates,
        agentRunManager.compactingSessions, agentRunManager.runningCommandSessions
    ) { snapshot, preparing, states, compacting, commands ->
        val runningSessions = snapshot.items.map { it.target.sessionId }.filterTo(mutableSetOf()) { sid ->
            sid in compacting || sid in commands || states[sid] == AgentUIState.Loading || states[sid] == AgentUIState.Streaming
        }
        // Read the committed invalidations here: another combine input may arrive before their flow emission.
        val consumed = agentRunManager.consumedRecoveryTargets.value
        val items = snapshot.items.filterNot { item ->
            val target = item.target
            target.sessionId in runningSessions || consumed[target.requestId]?.let {
                it.sessionId == target.sessionId && it.generation == target.generation && it.activeRunId == target.activeRunId
            } == true
        }
        snapshot.copy(
            items = items,
            busy = items.distinctBy { it.target.sessionId }.any { it.target.sessionId in preparing },
            runningSessions = runningSessions
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecoveryBubbleUi())

    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        selectionLoadedWorkspace.value = null
        updateSelectedSession(null)
        browseWorkspaceRevision.incrementAndGet()
        _currentWorkspace.value = path
        browseController.setScope(null)
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
    private val browseScopeRetry = MutableStateFlow(0L)
    private val browseOwnerIdentity = UUID.randomUUID().toString()
    private val browseOwnerActive = java.util.concurrent.atomic.AtomicBoolean(true)
    private val browseWorkspaceRevision = java.util.concurrent.atomic.AtomicLong()
    private fun fileRuntimeOwner() = RuntimeOwner(RuntimeOwnerKind.FILE, browseOwnerIdentity)

    private suspend fun freezeFileExecution(
        paths: List<String>? = null,
        checkUiSource: () -> Unit = {}
    ): WorkspaceExecutionContext {
        val workspace = _currentWorkspace.value
        val revision = browseWorkspaceRevision.get()
        val check = {
            checkUiSource()
            if (!browseOwnerActive.get() || _currentWorkspace.value != workspace || browseWorkspaceRevision.get() != revision) {
                throw CancellationException("File browser execution source changed")
            }
        }
        return if (paths == null) workspaceExecutionContextFactory.freeze(runtimeOwner = fileRuntimeOwner(), checkSource = check)
        else workspaceExecutionContextFactory.freezeForPaths(paths, runtimeOwner = fileRuntimeOwner(), checkSource = check)
    }

    internal suspend fun captureUserFile(path: String): WorkspaceExecutionContext = freezeFileExecution(listOf(path))

    // This is a local UI snapshot, not a runtime grant. Capture the actual runtime after the
    // system picker returns and the app is foreground again, without asking the user to authorize.
    internal fun captureAttachmentSelection(): () -> Unit {
        val session = _currentSessionId.value
        val sessionRevision = sessionSelectionRevision
        val workspace = _currentWorkspace.value
        val workspaceRevision = browseWorkspaceRevision.get()
        val modeRevision = executionModeHolder.modeSelectionRevision
        val profileRevision = containerSettingsRepository.activeProfileSelectionRevision
        return {
            if (!browseOwnerActive.get() || _currentSessionId.value != session ||
                sessionSelectionRevision != sessionRevision || _currentWorkspace.value != workspace ||
                browseWorkspaceRevision.get() != workspaceRevision ||
                executionModeHolder.modeSelectionRevision != modeRevision ||
                containerSettingsRepository.activeProfileSelectionRevision != profileRevision) {
                throw CancellationException("Attachment destination changed")
            }
        }
    }

    internal suspend fun captureAttachmentFile(path: String, checkSelection: () -> Unit): WorkspaceExecutionContext =
        freezeFileExecution(listOf(path), checkSelection)

    internal data class AttachmentPickerSelection(val checkCurrent: () -> Unit, val cameraUri: android.net.Uri?)
    private var pendingAttachmentSelection: AttachmentPickerSelection? = null

    internal fun beginAttachmentSelection(cameraUri: android.net.Uri? = null) {
        pendingAttachmentSelection = AttachmentPickerSelection(captureAttachmentSelection(), cameraUri)
    }

    internal fun consumeAttachmentSelection(): AttachmentPickerSelection? =
        pendingAttachmentSelection.also { pendingAttachmentSelection = null }

    override fun onCleared() {
        pendingAttachmentSelection = null
        browseOwnerActive.set(false)
        super.onCleared()
    }
    private val _browseNotices = MutableSharedFlow<BrowseOperationNotice>(extraBufferCapacity = 16)
    val browseNotices = _browseNotices.asSharedFlow()
    private val browseController = FileBrowseController(
        ownerScope = viewModelScope,
        fileAccess = fileAccess,
        executionFactory = workspaceExecutionContextFactory,
        routing = fileBackendRouting,
        transfers = fileTransfers,
        currentSelectionRevision = { containerSettingsRepository.activeProfileSelectionRevision },
        watch = { path -> fileChangeHub.watchWorkspace(path).asDirtySignal() },
        watchLocalFolder = { path ->
            flow {
                val execution = freezeFileExecution(listOf(path))
                val host = withContext(execution) {
                    val route = fileBackendRouting.capture(execution, listOf(path))
                    val backend = route.endpoint(path).provider as com.aicode.feature.workspace.domain.InternalLocalFolderAccess
                    backend.directoryForWatch(path).also { route.checkCurrent() }
                }
                emitAll(fileChangeHub.watchHostDir(host, containerRootPath = path).asDirtySignal())
            }.flowOn(Dispatchers.IO)
        },
        loadExpansion = ::loadExpansion,
        saveExpansion = ::saveExpansion,
        onFailure = { FileLogger.w(TAG, "文件浏览操作失败", it) },
        onNotice = { _browseNotices.tryEmit(it) }
    )
    val expandedPaths: StateFlow<Set<String>> = browseController.expanded
    val expandingPath: StateFlow<String?> = browseController.expandingPath
    val fileOpPaths: StateFlow<Set<String>> = browseController.fileOpPaths
    val browseTransferActive: StateFlow<Boolean> = browseController.transferActive
    val localFolderRevision = localFolderGrants.revision
    val fileProfileSelectionRevisions = containerSettingsRepository.activeProfileSelectionRevisions
    val browseState: StateFlow<FileBrowseState> = browseController.state
    val browseClipboard: StateFlow<BrowseClipboard?> = browseController.clipboard
    val pasteConflict: StateFlow<Pair<String, String>?> = browseController.pasteConflict

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

    fun refreshBrowse() {
        if (browseState.value is FileBrowseState.Error) browseScopeRetry.value++
        else browseController.refresh()
    }
    fun toggleExpand(path: String) = browseController.toggle(path)

    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) =
        browseController.create(parent, name, directory = false, onResult)
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) =
        browseController.create(parent, name, directory = true, onResult)
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        browseController.rename(path, newName, onResult)
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) = browseController.delete(path, onResult)
    fun copyBrowseEntry(path: String, name: String) = browseController.copy(path, name, cut = false)
    fun cutBrowseEntry(path: String, name: String) = browseController.copy(path, name, cut = true)
    fun clearBrowseClipboard() = browseController.clearClipboard()
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = browseController.paste(targetDir, onResult)
    fun pasteBrowseEntryOverwrite() = browseController.confirmOverwrite()
    fun clearPasteConflict() = browseController.clearConflict()
    fun prepareBrowseUpload(targetDir: String): BrowseUploadTarget? =
        browseController.prepareUpload(targetDir, containerSettingsRepository.activeProfileSelectionRevision).also {
            if (it == null) {
                browseScopeRetry.value++
                _browseNotices.tryEmit(BrowseOperationNotice.INVALID_SELECTION)
            }
        }
    fun uploadBrowseFiles(target: BrowseUploadTarget, uris: List<Uri>) {
        if (target.selectionRevision != containerSettingsRepository.activeProfileSelectionRevision) {
            _browseNotices.tryEmit(BrowseOperationNotice.INVALID_SELECTION)
            return
        }
        browseController.upload(target, uris, directory = false)
    }
    fun uploadBrowseDirectory(target: BrowseUploadTarget, uri: Uri) {
        if (target.selectionRevision != containerSettingsRepository.activeProfileSelectionRevision) {
            _browseNotices.tryEmit(BrowseOperationNotice.INVALID_SELECTION)
            return
        }
        browseController.upload(target, listOf(uri), directory = true)
    }
    fun cancelBrowseTransfer() = browseController.cancelTransfer()
    fun addLocalBrowseFolder(uri: Uri, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val added = try {
            withContext(Dispatchers.IO) { localFolderGrants.add(uri).available }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FileLogger.w(TAG, "本机目录授权失败", failure)
            false
        }
        onResult(added)
    }
    fun removeLocalBrowseFolder(id: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            localFolderGrants.remove(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FileLogger.w(TAG, "解除本机目录关联失败", failure)
            _browseNotices.tryEmit(BrowseOperationNotice.FAILED)
        }
    }
    fun refreshLocalBrowseFolders() = viewModelScope.launch(Dispatchers.IO) {
        try {
            localFolderGrants.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            FileLogger.w(TAG, "复核本机目录授权失败", failure)
            _browseNotices.tryEmit(BrowseOperationNotice.FAILED)
        }
    }

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


    val runningToolActivity: StateFlow<RunningToolActivity> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(RunningToolActivity())
            else agentRunManager.runningTools
                .map { tools -> runningToolActivityOf(tools[id]?.values.orEmpty()) }
                .distinctUntilChanged()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RunningToolActivity())

    fun runningToolOutput(messageId: String): Flow<RunningToolOutput?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentRunManager.runningTools
                .map { tools -> runningToolOutputOf(tools[id]?.values.orEmpty(), messageId) }
                .distinctUntilChanged()
        }

    /**
     * 模型正在流式产出、还没开始执行的工具名（如 `editFile`）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。上游在工具名一出现就上报（[AgentEvent.ToolCallPreparing]），
     * UI 据此把状态换成具体场景（「正在编辑文件」）。工具真正开始执行后由 [runningToolActivity] 接管。
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentRunManager.preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val compactionPercent: StateFlow<Pair<String?, Int>> = combine(_currentSessionId, agentRunManager.compactionPercents) { id, progress ->
        id to (progress[id] ?: 0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null to 0)
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = agentRunManager.contextUsages
    val speedTier = generalSettingsRepository.speedTierFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.aicode.feature.agent.domain.model.SpeedTier.STANDARD)

    fun setSpeedTier(tier: com.aicode.feature.agent.domain.model.SpeedTier) {
        viewModelScope.launch { generalSettingsRepository.setSpeedTier(tier) }
    }

    val generationSpeed: StateFlow<AgentEvent.GenerationSpeed?> = _currentSessionId
        .flatMapLatest { id -> agentRunManager.generationSpeeds.map { it[id] } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = agentRunManager.llmCallEvents
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else agentRunManager.compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentRunManager.streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else agentRunManager.reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentRunManager.streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentRunManager.retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else agentRunManager.keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pendingToolPermission = agentRunManager.pendingToolPermission

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        pendingToolPermission,
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
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = agentRunManager.awaitingPermissionSessionIds

    val pendingUserQuestion = agentRunManager.pendingUserQuestion
    val pendingPlanApproval = agentRunManager.pendingPlanApproval
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

    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else agentRunManager.queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val approvalSessionIds: StateFlow<Set<String>> = agentRunManager.approvalSessionIds

    private val subAgentInteractionStates = combine(agentStates, agentRunManager.subAgentWaitingSlots) { states, waiting ->
        states.filterKeys { it !in waiting }
    }
    private val subAgentQueuedIds = combine(agentRunManager.queuedRequests, agentRunManager.subAgentWaitingSlots) { queued, waiting ->
        queued.filterValues { it.isNotEmpty() }.keys + waiting
    }
    private val subAgentInteractionLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val subAgentExecutionLimits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val subAgentToolTitles = agentRunManager.runningTools.map { tools ->
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

    // 页面回来时保留同一会话的快照。伪空摘要会被正文投影当作子代理已移除，导致列表暂时卸载。
    val subAgentInteractionSummaries: StateFlow<Pair<String?, Map<String, SubAgentInteractionSummary>>> = _currentSessionId
        .flatMapLatest { parentId ->
            if (parentId == null) flowOf(null to emptyMap())
            else combine(subAgentInteractionReader.summaryFacts(parentId), subAgentInteractionRuntime) { facts, runtime ->
                parentId to projectSubAgentInteractionSummaries(facts, runtime)
            }.flowOn(Dispatchers.Default)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyMap())

    val subAgentInteractionEvents: StateFlow<Pair<String?, List<com.aicode.feature.agent.data.local.dao.SubAgentInteractionMetadata>>> =
        _currentSessionId.flatMapLatest { parentId ->
            if (parentId == null) flowOf(null to emptyList())
            else subAgentInteractionReader.summaryFacts(parentId)
                .map { facts -> parentId to facts.metadata.filter { it.kind == "MESSAGE_FROM_PARENT" } }
                .flowOn(Dispatchers.Default)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

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
                    agentRunManager.streamingTexts.map { it[childId].orEmpty() }.distinctUntilChanged(),
                    agentRunManager.runningTools.map { it[childId]?.values?.toList().orEmpty() }.distinctUntilChanged()
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
            agentRunManager.enqueueAgentRequest(request = body, projectRoot = workspace, targetSessionId = id, subAgentInstructionId = instructionId)
        }
    }

    fun openSubAgentSession(parentId: String, childId: String) {
        viewModelScope.launch {
            val child = sessionUseCase.getSessionById(childId) ?: return@launch
            if (_currentSessionId.value == parentId && child.parentId == parentId) updateSelectedSession(childId)
        }
    }

    fun stopOwnedSubAgent(parentId: String, childId: String) {
        viewModelScope.launch {
            val child = sessionUseCase.getSessionById(childId) ?: return@launch
            if (_currentSessionId.value == parentId && child.parentId == parentId) stopAgentSession(childId)
        }
    }

    val preparingToolArguments: StateFlow<Pair<String?, List<PreparingToolPreviewSnapshot>>> = _currentSessionId
        .flatMapLatest { sid -> agentRunManager.preparingToolArguments.map { sid to it[sid]?.values?.toList().orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    val runningToolPreviewSnapshot: StateFlow<Pair<String?, List<RunningToolOutput>>> = _currentSessionId
        .flatMapLatest { sid -> agentRunManager.runningTools.map { sid to it[sid]?.values?.toList().orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    val toolPreviewFileReader: StateFlow<Pair<String?, com.aicode.feature.agent.presentation.preview.PreviewFileReader?>> =
        combine(_currentSessionId, toolPreviewFileAccessAdapter.readers) { sid, reader -> sid to reader }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    val toolPreviewMessages: StateFlow<Pair<String?, List<AgentUIMessage>>> = _currentSessionId.flatMapLatest { sid ->
        if (sid == null) flowOf(null to emptyList())
        else agentMessageDao.getToolMessagesBySession(sid).map { rows -> sid to rows.map { it.toUIMessage() } }
            .flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to emptyList())

    val toolExecutionStarts: StateFlow<Map<String, Long>> = _currentSessionId
        .flatMapLatest { sid -> agentRunManager.toolExecutionStarts.map { it[sid].orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun toolExecutionStart(messageId: String): Flow<Long?> = toolExecutionStarts
        .map { it[messageId] }
        .distinctUntilChanged()

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return agentRunManager.isSessionRunning(sid)
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean = agentRunManager.hasRunningSessions(
        buildSet {
            addAll(sessions.value.map { it.id })
            subSessionsByParent.value.values.forEach { subs -> addAll(subs.map { it.id }) }
        }
    )

    private data class BrowseSource(val workspace: String, val profile: String, val mode: ExecutionMode, val selectionRevision: Long,
        val configurationRevision: Long)

    private companion object {
        const val TAG = "AIAgentViewModel"
    }

    init {
        agentRunManager.start()
        viewModelScope.launch { containerRootNode.collect(browseController::setRoot) }
        viewModelScope.launch {
            val selection = combine(
                _currentWorkspace,
                containerSettingsRepository.activeProfileIdFlow.distinctUntilChanged(),
                executionModeHolder.mode,
                containerSettingsRepository.activeProfileSelectionRevisions,
                containerSettingsRepository.profileConfigurationRevisions
            ) { workspace, profile, mode, selectedRevision, configurationRevision ->
                BrowseSource(workspace, profile, mode, selectedRevision, configurationRevision)
            }
            combine(selection, localFolderGrants.snapshots) { source, _ -> source }
                .flatMapLatest { source ->
                    browseController.setScope(null)
                    val actions = localRuntimeUserActions
                    val profile = if (actions != null && source.mode == ExecutionMode.LOCAL_PROOT) {
                        try { containerSettingsRepository.captureProfileSnapshot().activeProfile }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    } else null
                    val runtimeChanges = if (actions != null && profile?.isChroot == true) {
                        actions.changes.map {
                            actions.state(profile).let { state ->
                                listOf(state.instance, state.rootEnabled, state.journalHealthy, state.recoveryRequired, state.authorizedOwners)
                            }
                        }.distinctUntilChanged().drop(1).map { Unit }
                    } else kotlinx.coroutines.flow.emptyFlow()
                    merge(
                        projectMemoryIdentityResolver.environmentChanges,
                        browseScopeRetry.drop(1).map { Unit },
                        runtimeChanges
                    ).onStart { emit(Unit) }.map { source }
                }
                .collectLatest { (workspace, profile, mode, selectionRevision, configurationRevision) ->
                    if (workspace.isBlank()) return@collectLatest
                    try {
                        var grantedFolderScope = false
                        val execution = withContext(Dispatchers.IO) {
                            try { freezeFileExecution() }
                            catch (required: com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired) {
                                val paths = localFolderGrants.snapshot().roots.filter { it.available }.map { it.rootPath }
                                if (paths.isEmpty()) throw required
                                grantedFolderScope = true
                                freezeFileExecution(paths)
                            }
                        }
                        if (execution.mode != mode || (execution.localMapping != null && execution.localMapping.profile.id != profile) ||
                            selectionRevision != containerSettingsRepository.activeProfileSelectionRevision ||
                            configurationRevision != containerSettingsRepository.profileConfigurationRevision ||
                            (execution.localRuntimeCapture != null && execution.localRuntimeCapture.profileId != profile)) {
                            throw CancellationException("File browse environment changed")
                        }
                        execution.checkCurrent()
                        val mapping = execution.localMapping?.let { it.copy(profile = it.profile.copy(name = "")) }
                        browseController.updateScope(FileBrowseScope(workspace, profile, mode, execution.fileCacheIdentity, mapping,
                            execution.localFolderSnapshot.roots, execution.localFolderSnapshot.revision, selectionRevision,
                            runtimeOwner = execution.runtimeOwner, grantedFolderScope = grantedFolderScope))
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        browseController.setUnavailable(cancelled.message)
                    } catch (failure: Exception) {
                        FileLogger.w(TAG, "文件浏览环境暂不可用", failure)
                        browseController.setUnavailable(failure.message)
                    }
                }
        }

        viewModelScope.launch {
            combine(
                _currentWorkspace,
                executionModeHolder.mode,
                executionModeHolder.modeSelectionRevisions,
                containerSettingsRepository.activeProfileSelectionRevisions,
                containerSettingsRepository.profileConfigurationRevisions
            ) { _, _, _, _, _ -> Unit }.collectLatest { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val selectedBeforeLoad = _currentSessionId.value
                val revisionBeforeLoad = sessionSelectionRevision
                fun selectionUnchanged() = _currentWorkspace.value == path &&
                    sessionSelectionRevision == revisionBeforeLoad && _currentSessionId.value == selectedBeforeLoad
                val savedId = sessionSelectionStore.load(path)?.let { id ->
                    sessionUseCase.getSessionById(id)?.takeIf { it.workspacePath == path }?.id
                }
                val alreadySelected = selectedBeforeLoad?.let { id ->
                    sessionUseCase.getSessionById(id)?.takeIf { it.workspacePath == path }?.id
                }
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                val mode = generalSettingsRepository.startupSessionMode()
                if (!selectionUnchanged()) {
                    if (_currentWorkspace.value == path) selectionLoadedWorkspace.value = path
                    return@collectLatest
                }
                val targetId = when {
                    alreadySelected != null -> alreadySelected
                    savedId != null && agentRunManager.isSessionRunning(savedId) -> savedId
                    mode == StartupSessionMode.RECENT_SESSION && savedId != null -> savedId
                    recent == null -> createAndUpsertSession(path)
                    mode == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> sessionUseCase.getEmptySessionOfWorkspace(path)?.id
                        ?: if (selectionUnchanged()) createAndUpsertSession(path) else null
                }
                if (selectionUnchanged()) updateSelectedSession(targetId)
                if (_currentWorkspace.value != path) return@collectLatest
                selectionLoadedWorkspace.value = path

                val keepId = _currentSessionId.value
                if (recent != null && keepId != null && sessionSelectionRevision == revisionBeforeLoad + 1) {
                    val selectedRevision = sessionSelectionRevision
                    launch(Dispatchers.IO) {
                        if (_currentWorkspace.value == path && _currentSessionId.value == keepId &&
                            sessionSelectionRevision == selectedRevision) sessionUseCase.recycleEmptySessions(path, keepId = keepId)
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(_currentWorkspace, _currentSessionId, agentRunManager.resolvedSessions, selectionLoadedWorkspace) { workspace, selected, resolved, loaded ->
                workspace to resolved[workspace].takeIf { selected == null && loaded == workspace }
            }.distinctUntilChanged().collect { (workspace, resolved) ->
                if (resolved != null && _currentWorkspace.value == workspace && _currentSessionId.value == null) {
                    val revision = sessionSelectionRevision
                    val session = sessionUseCase.getSessionById(resolved)
                    if (session?.workspacePath == workspace && _currentWorkspace.value == workspace &&
                        sessionSelectionRevision == revision && _currentSessionId.value == null) updateSelectedSession(resolved)
                }
            }
        }
        viewModelScope.launch {
            sessionSelections.receiveAsFlow().collect { (workspace, id) ->
                val session = chatSessionDao.getById(id)
                if (session?.workspacePath == workspace) sessionSelectionStore.save(id, workspace)
            }
        }
        viewModelScope.launch {
            var cleaned = emptySet<String>()
            agentRunManager.deletedSessions.collect { deleted ->
                (deleted - cleaned).forEach(::clearSessionUiState)
                cleaned = deleted
            }
        }
        viewModelScope.launch {
            agentRunManager.reasoningDurations.collect { durations ->
                reasoningDurations.keys.retainAll(durations.keys)
                reasoningDurations.putAll(durations)
            }
        }
        viewModelScope.launch {
            var lastHandledCommits = emptyMap<String, String>()
            var previousTimingSessions = emptySet<String>()
            combine(agentRunManager.reasoningCommits, agentRunManager.reasoningTimings) { _, _ -> Unit }
                .collect {
                    val commits = agentRunManager.reasoningCommits.value
                    val timings = agentRunManager.reasoningTimings.value
                    commits.forEach { (streamKey, messageId) ->
                        if (lastHandledCommits[streamKey] != messageId) {
                            val sid = streamKey.removePrefix("reasoning:stream:")
                            reasoningExpansionOverrides.remove(streamKey)?.let { expanded ->
                                reasoningExpansionOverrides[messageId] = expanded
                            }
                            reasoningScrollStates.moveIfPresent(sid, streamKey, messageId)
                        }
                    }
                    (previousTimingSessions - timings.keys).forEach { sid ->
                        reasoningExpansionOverrides.remove(streamingReasoningKey(sid))
                    }
                    lastHandledCommits = commits
                    previousTimingSessions = timings.keys
                }
        }
    }

    internal fun continueRecovery(snapshot: RecoveryBubbleUi, unknownToolsVerified: Boolean = false) {
        val scope = snapshot.scope ?: return
        if (snapshot.busy || scope.sessionId != _currentSessionId.value || scope.workspacePath != _currentWorkspace.value) return
        agentRunManager.continueRecovery(scope, snapshot.continueTargets, unknownToolsVerified)
    }

    internal fun abandonRecovery(snapshot: RecoveryBubbleUi) {
        val scope = snapshot.scope ?: return
        if (snapshot.busy || scope.sessionId != _currentSessionId.value || scope.workspacePath != _currentWorkspace.value) return
        val targets = snapshot.batch.map { it.target }.filterNot { target ->
            agentRunManager.isSessionRunning(target.sessionId) ||
                target.sessionId in agentRunManager.runningCommandSessions.value ||
                target.sessionId in agentRunManager.compactingSessions.value ||
                target.sessionId in agentRunManager.recoveryBusySessions.value
        }
        agentRunManager.abandonRecovery(scope, targets)
    }

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
        agentRunManager.enqueueAgentRequest(
            request = request,
            modelRequest = modelRequest,
            currentFile = currentFile,
            selectedCode = selectedCode,
            projectRoot = projectRoot.ifBlank { _currentWorkspace.value },
            inputImages = inputImages,
            inputAttachments = inputAttachments,
            isAutoTrigger = isAutoTrigger,
            persistUserMessage = persistUserMessage,
            targetSessionId = targetSessionId ?: _currentSessionId.value,
            subAgentInstructionId = subAgentInstructionId
        )
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        agentRunManager.removeQueuedRequest(sid, id)
    }

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        agentRunManager.moveQueuedRequest(sid, fromIndex, toIndex)
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        val sid = _currentSessionId.value ?: return
        agentRunManager.updateQueuedRequest(sid, id, newText)
    }

    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        agentRunManager.interjectQueuedRequest(sid, id)
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
    ): Job = agentRunManager.executeAgentRequestStream(
        request = request,
        modelRequest = modelRequest,
        currentFile = currentFile,
        selectedCode = selectedCode,
        projectRoot = projectRoot.ifBlank { _currentWorkspace.value },
        inputImages = inputImages,
        inputAttachments = inputAttachments,
        targetSessionId = targetSessionId ?: _currentSessionId.value,
        isAutoTrigger = isAutoTrigger,
        persistUserMessage = persistUserMessage,
        skipTitleUpdate = skipTitleUpdate,
        notificationSeqs = notificationSeqs,
        subAgentInstructionId = subAgentInstructionId,
        recoveryRequestId = recoveryRequestId,
        acceptedRecoveryRun = acceptedRecoveryRun,
        acceptedCapture = acceptedCapture,
        resuming = resuming,
        expectedSubAgentEvent = expectedSubAgentEvent
    )

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        agentRunManager.resolveToolPermission(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        agentRunManager.resolveUserQuestion(id, answer)
    }

    fun resolvePlanApproval(id: String, approved: Boolean, feedback: String = "") {
        agentRunManager.resolvePlanApproval(id, approved, feedback)
    }

    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        agentRunManager.stopAllAndCloseTerminal()
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        agentRunManager.stopAllAgents()
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
        agentRunManager.stopAgentSession(sessionId)
    }

    // region 会话管理

    private fun clearSessionUiState(sessionId: String) {
        reasoningScrollStates.removeSession(sessionId)
        reasoningExpansionOverrides.remove(streamingReasoningKey(sessionId))
        turnMessageKeys.remove(sessionId)?.values?.toSet()?.forEach(turnExpansionOverrides::remove)
        _inputDrafts.value = _inputDrafts.value - sessionId
        draftPrefs.edit().remove(sessionId).apply()
        _messageLimit.value = _messageLimit.value - sessionId
        loadedMessageLimits.remove(sessionId)
        subAgentInteractionLimits.value -= sessionId
        subAgentExecutionLimits.value -= sessionId
        if (_pendingScrollMessage.value?.first == sessionId) _pendingScrollMessage.value = null
    }

    /** 新建会话；工作区里已有空会话时直接切过去复用（当前会话为空则优先），避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return@launch
        val curId = _currentSessionId.value
        val revision = sessionSelectionRevision
        val reusableId = if (curId != null && sessionUseCase.getSessionById(curId)?.workspacePath == ws && sessionUseCase.isSessionEmpty(curId)) {
            curId
        } else {
            sessionUseCase.getEmptySessionOfWorkspace(ws)?.id
        }
        if (_currentWorkspace.value != ws || sessionSelectionRevision != revision) return@launch
        if (reusableId != null) {
            agentRunManager.resetIdle(reusableId)
            updateSelectedSession(reusableId)
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(ws)
        if (_currentWorkspace.value == ws && sessionSelectionRevision == revision) updateSelectedSession(sid)
    }

    fun setCurrentSessionId(id: String) {
        updateSelectedSession(id)
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
        val running = agentRunManager.isSessionRunning(sid)
        viewModelScope.launch {
            val updated = sessionUseCase.updateProviderModelWithDirectChildren(sid, providerId, model)
            if (updated.isEmpty()) return@launch
            updated.forEach { agentRunManager.resetModelRuntime(it) }
            val session = sessionUseCase.getSessionById(sid) ?: return@launch
            // 空闲根会话仍保留新会话默认值；运行中的选择只作用于该会话范围。
            if (!running && session.parentId == null && sessionUseCase.isSessionEmpty(sid)) {
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
        agentRunManager.initProject(_currentSessionId.value, _currentWorkspace.value, prompt)
    }

    /** 技能触发 —— 把技能正文作为本轮指令执行。 */
    override fun runSkill(skill: Skill, args: String) {
        agentRunManager.runSkill(_currentSessionId.value, _currentWorkspace.value, skill, args)
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        agentRunManager.showUsage(_currentSessionId.value, _currentWorkspace.value)
    }

    /** /goal —— 管理当前会话的持续目标。 */
    override fun setSessionGoal(rawArgs: String) {
        val sid = _currentSessionId.value ?: return
        agentRunManager.setSessionGoal(sid, rawArgs)
    }

    /** UI：暂停/恢复当前会话的 goal 自主循环。 */
    fun setGoalPaused(paused: Boolean) {
        val sid = _currentSessionId.value ?: return
        agentRunManager.setGoalPaused(sid, paused)
    }

    /** UI：清除当前会话的 goal。 */
    fun clearGoal() {
        val sid = _currentSessionId.value ?: return
        agentRunManager.clearGoal(sid)
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        agentRunManager.compactSession(sid, workspacePath = _currentWorkspace.value)
    }

    fun compactSession(sid: String, currentFile: String? = null, selectedCode: String? = null) {
        agentRunManager.compactSession(sid, workspacePath = _currentWorkspace.value,
            currentFile = currentFile, selectedCode = selectedCode)
    }

    fun selectSession(id: String) {
        updateSelectedSession(id)
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        val deleted = agentRunManager.deleteSessions(ids)
        deleted.forEach(::clearSessionUiState)
        selectAfterDeletion(deleted)
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        val deleted = agentRunManager.deleteSession(id)
        deleted.forEach(::clearSessionUiState)
        selectAfterDeletion(deleted)
    }

    private suspend fun selectAfterDeletion(deletedIds: Set<String>) {
        val selected = _currentSessionId.value ?: return
        if (selected !in deletedIds) return
        val ws = _currentWorkspace.value
        val revision = sessionSelectionRevision
        val fallback = if (ws.isBlank()) null
            else sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        if (_currentSessionId.value == selected && _currentWorkspace.value == ws && sessionSelectionRevision == revision) {
            updateSelectedSession(fallback)
            if (fallback == null) sessionSelectionStore.clear(ws)
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
    ): Job {
        val sessionId = _currentSessionId.value
        dismissRewindMenu()
        return viewModelScope.launch {
            if (sessionId == null) return@launch
            val result = agentRunManager.rewind(sessionId, messageId, option) ?: return@launch
            if (_currentSessionId.value != sessionId) return@launch
            _rewindConflicts.value = result.conflicts
            result.fillPrompt?.let { onFillPrompt(it, result.attachments) }
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
        val revision = sessionSelectionRevision
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        if (_currentWorkspace.value == ws && sessionSelectionRevision == revision) updateSelectedSession(id)
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String = sessionCreationMutex.withLock {
        sessionUseCase.getEmptySessionOfWorkspace(workspacePath)?.let { return@withLock it.id }
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        s.id
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
}
