package com.aicode.feature.agent.domain.workflow

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.domain.recovery.RecoveryDraftWindow
import com.aicode.feature.agent.domain.recovery.RecoveryStep
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.plan.SubmitPlanTool
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.forInvocation
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.agent.domain.tool.ToolPresentation
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.provider.clampedFrom
import com.aicode.feature.agent.domain.provider.reasoningPlanFor
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.provider.wireSummary
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.ExecutionSourceChangedException
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import com.aicode.feature.plugin.runtime.PluginHookDispatcher
import com.aicode.feature.plugin.runtime.PluginToolDelegation
import com.aicode.feature.plugin.domain.PluginHookEvents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val providerFactory: ProviderInstanceFactory,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider,
    @param:ApplicationContext private val appContext: Context,
    private val rootCommandManager: RootCommandManager,
    private val shizukuManager: com.aicode.feature.agent.domain.shizuku.ShizukuManager,
    private val recoveryRepository: com.aicode.feature.agent.domain.recovery.RecoveryRepository,
    private val workspaceExecutionContextFactory: com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory,
    private val pluginHooks: PluginHookDispatcher
) : AgentWorkflow {

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 4_000
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** 模型直出图片落盘目录（与 GenerateImageTool 保持一致）。 */
        const val GENERATED_IMAGE_DIR = "~/.aicode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 错误类型码（如服务端 stop_reason），供 UI 换成本地化文案；null 表示直接展示 [error]。 */
        val errorCode: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(
            val response: AIResponse,
            val messageId: String,
            val planBatchDeferredReason: String,
            val continuationAllowed: Boolean = true
        ) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED",
        val rootApprovalId: String? = null,
        val shizukuApprovalId: String? = null,
        val permissionProof: PermissionProof? = null
    )

    /** The final plugin arguments were reviewed against this immutable, call-local decision. */
    private data class PermissionProof(
        val evaluation: ToolPermissionPolicyEngine.EvalResult,
        val askApprovedForCall: Boolean
    ) {
        fun check(verdict: ToolPermissionPolicyEngine.EvalResult) {
            if (evaluation.permissionSource != verdict.permissionSource ||
                evaluation.hostSystemTargets != verdict.hostSystemTargets ||
                evaluation.hostSystemUncertainties != verdict.hostSystemUncertainties ||
                evaluation.hostWriteRootGeneration != verdict.hostWriteRootGeneration ||
                evaluation.hostWriteRootAuthorityId != verdict.hostWriteRootAuthorityId ||
                evaluation.rootGeneration != verdict.rootGeneration ||
                evaluation.rootAuthorityId != verdict.rootAuthorityId ||
                evaluation.shizukuIdentity != verdict.shizukuIdentity ||
                (verdict.verdict == ToolPermissionPolicyEngine.Verdict.ASK && !askApprovedForCall)
            ) throw ExecutionSourceChangedException()
        }
    }

    private sealed interface ToolBatchExecution {
        data class Finished(val results: List<ToolRunResult>) : ToolBatchExecution
        data class Aborted(val error: String, val reasonCode: String) : ToolBatchExecution
    }

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList(),
        val timing: ToolCallTimer.Finished? = null
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(
            val toolCalls: List<ToolCall>,
            val reason: String = "用户拒绝了本轮工具调用，该调用未执行。",
            val errorCode: String = USER_REJECTED_CODE
        ) : AgentSideEffect
        object PlanRejected : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider =
        createStandaloneProvider(getEffectiveProviderConfig(sessionId), sessionId)

    private suspend fun getEffectiveProviderConfig(sessionId: String?): AIProviderConfig {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return config
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // 绑定的模型可能已被移出该 provider 的模型列表，此时绑定失效、继续往下回退默认模型
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override fun discardCompactionCheckpoint(sessionId: String) = contextCompactor.discardCheckpoint(sessionId)

    override suspend fun compactSession(
        sessionId: String,
        context: AgentContext,
        tools: List<AgentTool>,
        onEvent: suspend (AgentEvent) -> Unit
    ): Boolean {
        require(context.sessionId == sessionId)
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        val systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(context.copy(history = history)) }
        // 摘要模型独立选择；窗口、固定开销与有效工具仍按同一主会话快照校验。
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId,
            force = true, windowProvider = provider, systemPrompt = systemPrompt, tools = tools, onEvent = onEvent)
        if (result.compacted) pluginHooks.observe(PluginHookEvents.AGENT_COMPACTION, context, buildJsonObject { put("compacted", true) })
        return result.compacted
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider =
        providerFactory.create(config, sessionId)

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    internal fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    id = action.messageId,
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images,
                    inputTokens = action.response.inputTokens
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // 拒答/上下文超限：正文可能为空，静默结束会让用户看到空白气泡以为卡死。
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isTruncated && !action.continuationAllowed) {
                        newState = newState.copy(isFinished = true, error = "", errorCode = "continuation_budget_exceeded")
                    } else if (action.response.isTruncated) {
                        val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                        newState = newState.copy(messages = newState.messages + continuation)
                        effects.add(AgentSideEffect.PersistUser(continuation))
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    val toolCalls = action.response.toolCalls.toList()
                    val planCall = toolCalls.firstOrNull { it.name == SubmitPlanTool.NAME }
                    val pendingCalls = if (planCall == null) toolCalls else listOf(planCall)
                    val deferredCalls = if (planCall == null) emptyList() else toolCalls.filter { it !== planCall }
                    val deferredResults = deferredCalls.associate { call ->
                        call.id to ToolBatchResult(
                            call.id,
                            call.name,
                            ToolResult.Error(action.planBatchDeferredReason, "PLAN_BATCH_DEFERRED").toTransportString(),
                            true
                        )
                    }
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = pendingCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = deferredResults
                    )
                    if (deferredCalls.isNotEmpty()) {
                        effects.add(AgentSideEffect.CancelToolBatch(deferredCalls, action.planBatchDeferredReason, "PLAN_BATCH_DEFERRED"))
                    }
                    effects.add(AgentSideEffect.RequestPermission(pendingCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        val notExecuted = (state.approvedToolCalls + state.pendingPermissionCalls)
                            .filterNot { it.id == action.toolCall.id || it.id in state.rejectedToolResults }
                            .distinctBy { it.id }
                        if (notExecuted.isNotEmpty()) effects.add(AgentSideEffect.CancelToolBatch(notExecuted))
                        return newState to effects
                    }
                    // 策略/系统拒绝：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                val planCall = state.batchToolCalls.firstOrNull { it.name == SubmitPlanTool.NAME }
                val planResult = planCall?.let { state.rejectedToolResults[it.id] ?: resultsById[it.id] }
                val planRejected = planCall != null && planResult?.isError != false
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap(),
                    isFinished = planRejected
                )
                effects.add(if (planRejected) AgentSideEffect.PlanRejected else AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        // Construct inside the cold flow: even two collectors of the same Flow own separate state.
        TurnRunner(userRequest, context, tools) { send(it) }.run()
    }

    /** One request's provider snapshot, recovery cursor and statistics; never shared between requests. */
    private class LlmRequest(
        val providerInUse: AIProvider,
        val requestMetadata: ModelMetadata,
        val inputBudget: Int,
        var recoveryStep: RecoveryStep?,
        val compactionProvider: AIProvider,
        val compactedMessages: List<AgentMessage>,
        val requestBreakdown: ContextUsageBreakdown,
        val requestEstimate: Int,
        val predictedInput: Int,
        emit: suspend (AgentEvent) -> Unit
    ) {
        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        var callError: String? = null
        var callCompleted = false
        val stream = StreamAccumulator(callStartElapsed, emit = emit)
        val draftWindow = RecoveryDraftWindow()
        lateinit var hookRequestId: String
    }

    /**
     * Mutable turn state belongs only to this collection, not to StatefulAgentWorkflow or the Flow.
     * Keep phases as non-inline suspend members so their state machines and finally bodies stay small.
     */
    private inner class TurnRunner(
        private val userRequest: String,
        private val currentContext: AgentContext,
        private val currentTools: List<AgentTool>,
        private val send: suspend (AgentEvent) -> Unit
    ) {
        private val turnStartedElapsed = SystemClock.elapsedRealtime()
        private val toolTimer = ToolCallTimer(System::currentTimeMillis, SystemClock::elapsedRealtime)
        private var lastPersistedAssistantId: String? = null
        private val runtimeOwner = currentContext.sessionId?.let { RuntimeOwner(RuntimeOwnerKind.AGENT, it) }
        private var state = AgentSessionState()
        private var hookRunStatus = "cancelled"
        private var hookToolCalls = 0
        private val actionQueue = ArrayDeque<AgentAction>()
        private lateinit var systemPrompt: String
        private lateinit var providerConfig: AIProviderConfig
        private lateinit var aiProvider: AIProvider
        private lateinit var metadata: ModelMetadata
        // 同一模型压缩失败后不反复重试；切换模型后需按新窗口重新评估。
        private var compactionAttemptFailed = false
        private var baselineEstimate = 0
        private var baselineUsage = 0
        private var overflowRecoveryAttempted = false
        private var overflowRecoveryStep: RecoveryStep? = null
        private var continuationCount = 0
        private var continuationOutputTokens = 0L
        private val rootApprovals = mutableMapOf<String, String>()
        private val shizukuApprovals = mutableMapOf<String, String>()
        private val permissionProofs = mutableMapOf<String, PermissionProof>()
        private val delegations = mutableMapOf<String, PluginToolDelegation>()

        suspend fun run() {
            pluginHooks.observe(PluginHookEvents.AGENT_RUN_STARTED, currentContext, buildJsonObject {
                put("status", "started"); put("toolCalls", 0)
            })
            try {
                initialize()
                try {
                    drive()
                } finally {
                    releasePendingApprovals()
                }
                finish()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                hookRunStatus = "error"
                throw failure
            } finally {
                recordRunFinished()
            }
        }

        private suspend fun initialize() {
            actionQueue.addLast(
                AgentAction.InitRequest(
                    currentContext.history + if (currentContext.persistInputSnapshot) {
                        messagePersistenceUseCase.prepareModelInput(currentContext, userRequest)
                    } else com.aicode.feature.agent.domain.model.ModelInputSnapshot.prepare(currentContext, userRequest)
                        .messages(currentContext.inputMessageId, currentContext.inputImages, persisted = !currentContext.transientInput, snapshotStored = false)
                )
            )
            initializePrompt()
            initializeProvider()
            initializeBaseline()
        }

        private suspend fun initializePrompt() {
            // 提示词构建读取技能/子代理/记忆等文件（远程模式经 SFTP），必须离开收集线程。
            val promptStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(TAG, "prompt.start", details = "operation=$promptStartedNs")
            systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
            FileLogger.memoryCheckpoint(
                TAG,
                "prompt.ready",
                elapsedMs = (System.nanoTime() - promptStartedNs) / 1_000_000,
                details = "operation=$promptStartedNs promptChars=${systemPrompt.length}"
            )
        }

        private suspend fun initializeProvider() {
            val providerStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(TAG, "provider.start", details = "operation=$providerStartedNs")
            providerConfig = getEffectiveProviderConfig(currentContext.sessionId)
            aiProvider = createStandaloneProvider(providerConfig, currentContext.sessionId)
            FileLogger.memoryCheckpoint(
                TAG,
                "provider.ready",
                elapsedMs = (System.nanoTime() - providerStartedNs) / 1_000_000,
                details = "operation=$providerStartedNs"
            )
            metadata = modelMetadataService.resolve(providerConfig.id, providerConfig.type, providerConfig.effectiveModel)
            aiProvider.reasoningCapability = metadata.toReasoningCapability()
            (aiProvider as? AnthropicAdapter)?.let {
                it.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
            }
        }

        private suspend fun initializeBaseline() {
            val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
            baselineEstimate = withContext(Dispatchers.Default) {
                ContextTokenEstimator.estimate(systemPrompt,
                    currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
            }
            baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
                (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
            } else 0
        }

        private suspend fun drive() {
            workflowLoop@ while (!state.isFinished && actionQueue.isNotEmpty()) {
                val action = actionQueue.removeFirst()
                val (newState, effects) = reduce(state, action)
                state = newState
                for (effect in effects) {
                    if (!applyEffect(effect)) continue@workflowLoop
                }
            }
        }

        /** False skips the remaining effects after an approval source change has finished the turn. */
        private suspend fun applyEffect(effect: AgentSideEffect): Boolean {
            when (effect) {
                is AgentSideEffect.PersistUser -> persistUser(effect.message)
                AgentSideEffect.CallLlm -> callLlm()
                is AgentSideEffect.RequestPermission -> return requestPermission(effect)
                is AgentSideEffect.CancelToolBatch -> cancelBatch(effect)
                AgentSideEffect.PlanRejected -> send(AgentEvent.PlanRejected)
                is AgentSideEffect.ExecuteToolBatch -> executeBatch(effect)
            }
            return true
        }

        private suspend fun persistUser(message: AgentMessage.UserMessage) {
            currentContext.sessionId?.let { sessionId ->
                messagePersistenceUseCase.persist(sessionId, com.aicode.feature.agent.presentation.MessageRole.USER,
                    message.content, id = message.id,
                    recoveryRunId = currentContext.recoveryRunId, recoverySupplemental = currentContext.recoveryRunId != null)
                messagePersistenceUseCase.invalidateHistory(sessionId)
            }
        }

        private suspend fun callLlm() {
            val request = prepareRequest() ?: return
            send(AgentEvent.GenerationStarted)
            request.hookRequestId = UUID.randomUUID().toString()
            pluginHooks.observe(PluginHookEvents.AGENT_REQUEST_STARTED, currentContext, buildJsonObject {
                put("requestId", request.hookRequestId); put("messageCount", request.compactedMessages.size)
                put("toolCount", currentTools.size); put("estimatedInputTokens", request.predictedInput)
            })
            try {
                streamRequest(request)
                completeResponse(request)
            } catch (e: CancellationException) {
                saveCancelledDraft(request)
                throw e
            } catch (e: Exception) {
                handleStreamFailure(request, e)
            } finally {
                // A real call boundary keeps this body out of every suspension/exit in the stream phases.
                recordLlmFinally(request)
            }
        }

        /** Null retains the old continue-to-next-effect behavior when the input budget is exhausted. */
        private suspend fun prepareRequest(): LlmRequest? {
            refreshProvider()
            val providerInUse = aiProvider
            val requestMetadata = metadata
            val inputBudget = ModelContextPolicy.effectiveInputBudget(requestMetadata)
            val recoveryStep = beginRecoveryStep()
            refreshPrompt()
            val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
            val compactedMessages = compactMessages(providerInUse, compactionProvider)
            val requestBreakdown = withContext(Dispatchers.Default) {
                ContextTokenEstimator.breakdown(systemPrompt, compactedMessages, currentTools)
            }
            val requestEstimate = requestBreakdown.estimatedTotal
            val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
            send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
            if (predictedInput >= inputBudget) {
                actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                return null
            }
            return LlmRequest(providerInUse, requestMetadata, inputBudget, recoveryStep, compactionProvider,
                compactedMessages, requestBreakdown, requestEstimate, predictedInput, send)
        }

        private suspend fun refreshProvider() {
            currentContext.projectMemoryCapture?.assertCurrent()
            val selectedConfig = getEffectiveProviderConfig(currentContext.sessionId)
            if (selectedConfig.id != providerConfig.id || selectedConfig.effectiveModel != providerConfig.effectiveModel) {
                currentContext.sessionId?.let(contextCompactor::discardCheckpoint)
                val nextProvider = createStandaloneProvider(selectedConfig, currentContext.sessionId)
                val nextMetadata = modelMetadataService.resolve(selectedConfig.id, selectedConfig.type, selectedConfig.effectiveModel)
                nextProvider.reasoningCapability = nextMetadata.toReasoningCapability()
                if (nextProvider is AnthropicAdapter) {
                    nextProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(nextMetadata)
                }
                providerConfig = selectedConfig
                aiProvider = nextProvider
                metadata = nextMetadata
                baselineUsage = 0
                baselineEstimate = 0
                compactionAttemptFailed = false
                // 原生思考签名只属于生成它的模型；只改本次运行的回放副本，不改已保存历史。
                state = state.copy(messages = state.messages.map { message ->
                    if (message is AgentMessage.AssistantMessage) {
                        message.copy(signature = "", thinkingBlocksJson = "")
                    } else message
                })
            }
        }

        private suspend fun beginRecoveryStep(): RecoveryStep? = currentContext.recoveryRunId?.let { runId ->
            val previous = overflowRecoveryStep
            overflowRecoveryStep = null
            (if (previous == null) recoveryRepository.beginStep(runId) else recoveryRepository.restartStream(runId, previous))
                ?: throw CancellationException("Recovery request is no longer current")
        }

        private suspend fun refreshPrompt() {
            if (state.iterations > 0) {
                val refreshedPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
                if (refreshedPrompt != systemPrompt) {
                    systemPrompt = refreshedPrompt
                    baselineUsage = 0
                    baselineEstimate = 0
                }
            }
        }

        private suspend fun compactMessages(providerInUse: AIProvider, compactionProvider: AIProvider): List<AgentMessage> {
            var compactedMessages = state.messages
            if (!compactionAttemptFailed) {
                val estimate = withContext(Dispatchers.Default) {
                    ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                }
                val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, currentContext.sessionId,
                    windowProvider = providerInUse, systemPrompt = systemPrompt, tools = currentTools,
                    currentInputTokens = predictedInput) { event ->
                    if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                    send(event)
                }
                compactedMessages = compaction.messages
                if (compaction.compacted) {
                    pluginHooks.observe(PluginHookEvents.AGENT_COMPACTION, currentContext, buildJsonObject {
                        put("compacted", true); put("estimatedInputTokens", predictedInput)
                    })
                    state = state.copy(messages = compaction.messages)
                    baselineUsage = 0
                    baselineEstimate = 0
                }
            }
            return compactedMessages
        }

        private suspend fun streamRequest(request: LlmRequest) {
            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
            val messagesToSend = sanitizeImagesForModel(request.compactedMessages, request.requestMetadata.supportsVision)
            coroutineScope {
                val speedUpdates = launch {
                    while (isActive) {
                        delay(GenerationSpeedTracker.UPDATE_INTERVAL_MS)
                        request.stream.emitSpeed()
                    }
                }
                try {
                    request.providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort)
                        .collect { chunk -> onStreamChunk(request, chunk) }
                } finally {
                    speedUpdates.cancelAndJoin()
                }
            }
        }

        private suspend fun onStreamChunk(request: LlmRequest, chunk: AIStreamChunk) {
            currentContext.projectMemoryCapture?.assertCurrent()
            if (chunk is AIStreamChunk.Retrying || chunk is AIStreamChunk.KeySwitched) {
                currentContext.recoveryRunId?.let { runId ->
                    request.recoveryStep = recoveryRepository.restartStream(runId, requireNotNull(request.recoveryStep))
                        ?: throw CancellationException("Recovery stream is no longer current")
                }
            }
            request.stream.onChunk(chunk)
            if (chunk is AIStreamChunk.TextDelta) currentContext.recoveryRunId?.let { runId ->
                val step = requireNotNull(request.recoveryStep)
                val draftStartedAt = SystemClock.elapsedRealtime()
                if (request.draftWindow.isDue(step, draftStartedAt) &&
                    recoveryRepository.saveDraft(runId, step, request.stream.text.toString())) {
                    request.draftWindow.saved(step, draftStartedAt)
                }
                if (!recoveryRepository.isCurrent(runId)) throw CancellationException("Recovery draft is no longer current")
            }
        }

        private suspend fun completeResponse(request: LlmRequest) {
            val stream = request.stream
            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
            stream.flush()
            val aiResponse = stream.finalResponse ?: AIResponse(content = stream.text.toString())
            stream.finishSpeed(aiResponse.outputTokens)
            // Final 中的模型直出图片先在冻结的执行上下文落盘，再把 path/附件交 UI 与 reducer。
            val (persistedImages, attachments) =
                if (aiResponse.images.isNotEmpty()) withContext(workspaceExecutionContextFactory.freeze(currentContext.projectMemoryCapture, runtimeOwner = runtimeOwner)) {
                    persistModelImages(aiResponse.images)
                } else emptyList<AgentImage>() to emptyList()
            request.callCompleted = true
            if (aiResponse.stopReason == "model_context_window_exceeded" && !overflowRecoveryAttempted) {
                overflowRecoveryAttempted = true
                if (recoverOverflow(request)) return
            }
            val continuationAllowed = allowContinuation(aiResponse)
            baselineEstimate = request.requestEstimate
            baselineUsage = aiResponse.inputTokens
            val responseContextUsage = AgentEvent.ContextUsage(
                if (baselineUsage > 0) baselineUsage else request.predictedInput, request.inputBudget, baselineUsage <= 0
            )
            send(responseContextUsage)
            val responseMessageId = UUID.randomUUID().toString()
            val responseWithReasoning = if (stream.reasoning.isNotEmpty()) {
                aiResponse.copy(reasoning = stream.reasoning.toString())
            } else aiResponse
            persistResponse(request, aiResponse, attachments, responseMessageId, responseContextUsage)
            actionQueue.addLast(
                AgentAction.LlmResponse(
                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                    else responseWithReasoning,
                    messageId = responseMessageId,
                    planBatchDeferredReason = appContext.getString(R.string.plan_approval_batch_deferred),
                    continuationAllowed = continuationAllowed
                )
            )
        }

        private suspend fun persistResponse(
            request: LlmRequest,
            aiResponse: AIResponse,
            attachments: List<AgentAttachment>,
            responseMessageId: String,
            responseContextUsage: AgentEvent.ContextUsage
        ) {
            if (currentContext.recoveryRunId != null || aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                send(
                    AgentEvent.AssistantText(
                        aiResponse.content,
                        aiResponse.toolCalls,
                        request.stream.reasoning.toString(),
                        aiResponse.signature ?: "",
                        aiResponse.inputTokens,
                        aiResponse.outputTokens,
                        aiResponse.cachedInputTokens,
                        aiResponse.thinkingBlocksJson ?: "",
                        attachments = attachments,
                        messageId = responseMessageId,
                        persisted = persisted,
                        contextUsage = responseContextUsage,
                        contextBreakdown = request.requestBreakdown,
                        recoveryStep = request.recoveryStep
                    )
                )
                persisted.await()
                lastPersistedAssistantId = responseMessageId
            }
        }

        /** Both overflow paths return through callLlm's finally before the queued retry can run. */
        private suspend fun recoverOverflow(request: LlmRequest): Boolean {
            val recovery = contextCompactor.compactIfNeeded(state.messages, request.compactionProvider,
                currentContext.sessionId, force = true, windowProvider = request.providerInUse,
                systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = request.predictedInput) { send(it) }
            if (!recovery.compacted) return false
            pluginHooks.observe(PluginHookEvents.AGENT_COMPACTION, currentContext, buildJsonObject {
                put("compacted", true); put("estimatedInputTokens", request.predictedInput)
            })
            state = state.copy(messages = recovery.messages)
            baselineUsage = 0
            baselineEstimate = 0
            overflowRecoveryStep = request.recoveryStep
            actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
            return true
        }

        private suspend fun allowContinuation(aiResponse: AIResponse): Boolean {
            if (!aiResponse.isTruncated || aiResponse.toolCalls.isNotEmpty()) return true
            return currentContext.recoveryRunId?.let { runId ->
                recoveryRepository.incrementContinuationBudget(runId,
                    aiResponse.outputTokens.takeIf { it > 0 } ?: ModelContextPolicy.estimateTextTokens(aiResponse.content),
                    aiResponse.content.length)
            } ?: run {
                continuationCount++
                continuationOutputTokens += aiResponse.outputTokens.takeIf { it > 0 }
                    ?: ModelContextPolicy.estimateTextTokens(aiResponse.content)
                continuationCount <= 3 && continuationOutputTokens <= 64_000
            }
        }

        private suspend fun saveCancelledDraft(request: LlmRequest) {
            if (currentContext.recoveryRunId != null && request.recoveryStep != null && request.stream.text.isNotEmpty()) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching {
                        recoveryRepository.saveDraft(currentContext.recoveryRunId, requireNotNull(request.recoveryStep), request.stream.text.toString(), force = true)
                    }
                }
            }
        }

        private suspend fun handleStreamFailure(request: LlmRequest, e: Exception) {
            val stream = request.stream
            if (!overflowRecoveryAttempted && stream.text.isEmpty() && stream.reasoning.isEmpty() && isContextOverflow(e)) {
                overflowRecoveryAttempted = true
                if (recoverOverflow(request)) {
                    request.callError = e.message
                    return
                }
            }
            val partial = stream.text.toString()
            val reasoning = stream.reasoning.toString()
            // 流式被中断时也要落库已收到的思考，避免流式气泡清空后思考消失。
            // 有正文或有思考其一即落库；两者皆空则不写空消息。
            if (currentContext.recoveryRunId != null && partial.isNotEmpty()) {
                recoveryRepository.saveDraft(currentContext.recoveryRunId, requireNotNull(request.recoveryStep), partial, force = true)
            }
            if (currentContext.recoveryRunId == null && (partial.isNotEmpty() || reasoning.isNotBlank())) {
                val partialEvent = AgentEvent.AssistantText(
                    partial,
                    emptyList(),
                    reasoning,
                    contextUsage = AgentEvent.ContextUsage(request.predictedInput, request.inputBudget, true),
                    contextBreakdown = request.requestBreakdown,
                    recoveryStep = request.recoveryStep
                )
                send(partialEvent)
                lastPersistedAssistantId = partialEvent.messageId
            }
            // 多 Key 的自动切换与重发已在 adapter 内完成；这里直接上报原始错误。
            val errorText = "LLM 调用失败: ${e.message}"
            actionQueue.addLast(AgentAction.LlmError(errorText))
            request.callError = e.message ?: e.javaClass.simpleName
        }

        private suspend fun recordLlmFinally(request: LlmRequest) {
            val durationMillis = (SystemClock.elapsedRealtime() - request.callStartElapsed).toInt()
            val stream = request.stream
            val usage = stream.finalResponse
            pluginHooks.observe(PluginHookEvents.AGENT_REQUEST_FINISHED, currentContext, buildJsonObject {
                put("requestId", request.hookRequestId)
                put("status", if (request.callCompleted) "success" else if (request.callError != null) "error" else "cancelled")
                put("durationMs", durationMillis); put("inputTokens", usage?.inputTokens ?: 0); put("outputTokens", usage?.outputTokens ?: 0)
                put("toolCalls", usage?.toolCalls?.size ?: 0); usage?.stopReason?.let { put("stopReason", it) }
            })
            runCatching {
                // 与适配器同源算一次「实际会发什么」，保留生效/降级的统计信息。
                val providerInUse = request.providerInUse
                val reasoningPlan = reasoningPlanFor(providerInUse, currentContext.reasoningEffort)
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = currentContext.sessionId,
                        providerId = providerInUse.providerId.ifBlank { null },
                        model = providerInUse.model,
                        reasoningEffort = currentContext.reasoningEffort,
                        reasoningWire = reasoningPlan.wireSummary(),
                        reasoningClampedFrom = reasoningPlan.clampedFrom(currentContext.reasoningEffort),
                        kind = "chat",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = stream.ttfbElapsed?.toInt(),
                        durationMillis = durationMillis,
                        status = when {
                            request.callCompleted -> "success"
                            request.callError != null -> "error"
                            else -> "cancelled"
                        },
                        errorMessage = request.callError,
                        stopReason = usage?.stopReason,
                        retryCount = stream.retryAttempts,
                        createdAt = request.callStartWall
                    )
                )
            }
        }

        private suspend fun requestPermission(effect: AgentSideEffect.RequestPermission): Boolean {
            currentContext.recoveryRunId?.let { runId ->
                if (!recoveryRepository.markToolWaitingApproval(runId, effect.toolCall.id)) {
                    throw CancellationException("Tool approval request is no longer current")
                }
            }
            val tool = toolRegistry.forInvocation(effect.toolCall.name, currentTools)
            val executionArgs = tool?.let { ToolPresentation.executionArguments(it, effect.toolCall.arguments) }
                ?: effect.toolCall.arguments
            val mutation = pluginHooks.beforeTool(currentContext, effect.toolCall, JsonObject(executionArgs))
            val finalArguments = JsonObject(mutation.arguments.toMap())
            val effectiveCall = effect.toolCall.copy(arguments = finalArguments)
            val argsPreview = finalArguments.toString()
            val actionTitle = ToolPresentation.titleOf(effect.toolCall)
            hookToolCalls++
            val checkResult = try {
                if (mutation.denied) PermissionCheckResult(false, mutation.reason ?: "Plugin rejected this tool call", "PLUGIN_DENIED")
                else requestPermissionIfNeeded(tool, effect.toolCall.id, effectiveCall.arguments, argsPreview, currentContext)
            } catch (_: ExecutionSourceChangedException) {
                PermissionCheckResult(false, appContext.getString(R.string.tool_execution_source_changed), "EXECUTION_SOURCE_CHANGED")
            }
            if (!checkResult.approved && checkResult.errorCode == "EXECUTION_SOURCE_CHANGED") {
                // No tool has started while the batch is being reviewed. Stop the turn;
                // the outer finally revokes every previously queued native permit.
                actionQueue.clear()
                state = state.copy(isFinished = true, error = checkResult.denyReason, errorCode = checkResult.errorCode)
                return false
            }

            if (!checkResult.approved) {
                val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                send(AgentEvent.ToolCallFinished(
                    effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview,
                    actionTitle = actionTitle, persisted = persisted
                ))
                persisted.await()
                pluginHooks.observe(PluginHookEvents.AGENT_TOOL_FINISHED, currentContext, buildJsonObject {
                    put("callId", effectiveCall.id); put("toolName", effectiveCall.name); put("status", "error"); put("durationMs", 0)
                })
            } else {
                mutation.delegation?.let { delegations[effectiveCall.id] = it }
                checkResult.permissionProof?.let { permissionProofs[effectiveCall.id] = it }
                checkResult.rootApprovalId?.let { rootApprovals[effect.toolCall.id] = it }
                checkResult.shizukuApprovalId?.let { shizukuApprovals[effect.toolCall.id] = it }
                send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview, actionTitle))
            }
            actionQueue.addLast(AgentAction.PermissionEvaluated(effectiveCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
            return true
        }

        private suspend fun cancelBatch(effect: AgentSideEffect.CancelToolBatch) {
            // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
            // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
            effect.toolCalls.forEach { toolCall ->
                delegations.remove(toolCall.id)
                permissionProofs.remove(toolCall.id)
                rootApprovals.remove(toolCall.id)?.let { rootCommandManager.revokePermit(it) }
                shizukuApprovals.remove(toolCall.id)?.let { shizukuManager.revokePermit(it) }
                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                send(
                    AgentEvent.ToolCallFinished(
                        id = toolCall.id,
                        toolName = toolCall.name,
                        result = ToolResult.Error(
                            effect.reason,
                            effect.errorCode
                        ).toTransportString(),
                        isError = true,
                        argsPreview = JsonObject(toolRegistry.forInvocation(toolCall.name, currentTools)?.let {
                            ToolPresentation.executionArguments(it, toolCall.arguments)
                        } ?: toolCall.arguments).toString(),
                        actionTitle = ToolPresentation.titleOf(state.batchToolCalls.firstOrNull { it.id == toolCall.id } ?: toolCall),
                        persisted = persisted
                    )
                )
                persisted.await()
            }
        }

        private suspend fun executeBatch(effect: AgentSideEffect.ExecuteToolBatch) {
            // 计划审批在进入此批次前已与其他调用隔离，避免并行工具越过审批。
            val toolCalls = effect.toolCalls
            val executionContexts = toolCalls.associate { call ->
                call.id to currentContext.copy(
                    rootApprovalId = rootApprovals[call.id],
                    shizukuApprovalId = shizukuApprovals[call.id]
                )
            }
            val batch = executeToolBatch(
                toolCalls, state.batchToolCalls, currentContext, currentTools, runtimeOwner,
                executionContexts, permissionProofs.toMap(), delegations.toMap(), toolTimer
            ) { send(it) }
            when (batch) {
                is ToolBatchExecution.Aborted -> {
                    // The child scope has already cancelled and joined every sibling, including cleanup.
                    // Do not turn a stale approval into an ordinary tool result and another model call.
                    rootApprovals.values.forEach(rootCommandManager::revokePermit)
                    shizukuApprovals.values.forEach(shizukuManager::revokePermit)
                    rootApprovals.clear()
                    shizukuApprovals.clear()
                    permissionProofs.clear()
                    delegations.clear()
                    actionQueue.clear()
                    state = state.copy(isFinished = true, error = batch.error, errorCode = batch.reasonCode)
                }
                is ToolBatchExecution.Finished -> {
                    val batchResults = toolCalls.mapIndexed { index, toolCall ->
                        val runResult = batch.results[index]
                        ToolBatchResult(
                            toolCall.id, toolCall.name, runResult.raw, runResult.isError,
                            runResult.attachments, runResult.images
                        )
                    }
                    toolCalls.forEach { permissionProofs.remove(it.id) }
                    actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                }
            }
        }

        private fun releasePendingApprovals() {
            delegations.clear()
            permissionProofs.clear()
            rootApprovals.values.forEach(rootCommandManager::revokePermit)
            shizukuApprovals.values.forEach(shizukuManager::revokePermit)
        }

        private suspend fun finish() {
            hookRunStatus = if (state.error != null) "error" else "success"
            send(AgentEvent.TurnFinished(
                lastPersistedAssistantId,
                (SystemClock.elapsedRealtime() - turnStartedElapsed).coerceAtLeast(0L)
            ))
            state.error?.let { send(AgentEvent.Failed(it, state.errorCode)) }
            send(AgentEvent.Completed)
        }

        private suspend fun recordRunFinished() {
            pluginHooks.observe(PluginHookEvents.AGENT_RUN_FINISHED, currentContext, buildJsonObject {
                put("status", hookRunStatus); put("toolCalls", hookToolCalls); state.errorCode?.let { put("errorCode", it) }
            })
        }
    }

    /**
     * Keep the structured parallel scope outside the turn's reducer loop. Only explicit
     * authorization/source failures become terminal results; cancellation and ordinary I/O still escape.
     */
    private suspend fun executeToolBatch(
        toolCalls: List<ToolCall>,
        originalCalls: List<ToolCall>,
        context: AgentContext,
        tools: List<AgentTool>,
        runtimeOwner: RuntimeOwner?,
        executionContexts: Map<String, AgentContext>,
        permissionProofs: Map<String, PermissionProof>,
        delegations: Map<String, PluginToolDelegation>,
        toolTimer: ToolCallTimer,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolBatchExecution = try {
        val results = coroutineScope {
            val pathLocks = ConcurrentHashMap<String, Mutex>()
            val notificationSelection = Mutex()
            val reservedNotifications = mutableSetOf<Long>()
            toolCalls.map { toolCall ->
                async {
                    val run: suspend () -> ToolRunResult = {
                        val execution = workspaceExecutionContextFactory.freeze(context.projectMemoryCapture, runtimeOwner = runtimeOwner)
                        withContext(execution) {
                            context.projectMemoryCapture?.assertCurrent()
                            val tool = toolRegistry.forInvocation(toolCall.name, tools)
                            val verdict = tool?.let {
                                freezePermissionEvaluation(policyEngine.evaluate(it.name, toolCall.arguments, context.sessionId, execution))
                            }
                            // A lost native channel invalidates the queued permit, unlike an explicit policy DENY.
                            if (verdict?.channelUnavailable == true) throw ExecutionSourceChangedException()
                            if (verdict != null && verdict.verdict != ToolPermissionPolicyEngine.Verdict.DENY) {
                                val proof = permissionProofs[toolCall.id] ?: throw ExecutionSourceChangedException()
                                proof.check(verdict)
                            }
                            execution.checkCurrent()
                            context.recoveryRunId?.let { runId ->
                                if (!recoveryRepository.markToolStarted(runId, toolCall.id)) {
                                    throw CancellationException("Tool execution is no longer current")
                                }
                            }
                            val started = toolTimer.start()
                            onEvent(AgentEvent.ToolExecutionStarted(toolCall.id, started.startedAtMs, started.startedElapsedMs))
                            val executionContext = executionContexts.getValue(toolCall.id)
                            pluginHooks.observe(PluginHookEvents.AGENT_TOOL_STARTED, executionContext, buildJsonObject {
                                put("callId", toolCall.id); put("toolName", toolCall.name)
                            })
                            val delegation = delegations[toolCall.id]
                            if (verdict?.verdict != ToolPermissionPolicyEngine.Verdict.DENY && toolCall.name in setOf("editFile", "writeFile")) {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    context.sessionId?.let { checkpointManager.beforeFileModified(it, path) }
                                }
                            }
                            val result = when {
                                verdict?.verdict == ToolPermissionPolicyEngine.Verdict.DENY -> ToolRunResult(
                                    ToolResult.Error(verdict.denyReason ?: "Tool is denied by the current policy", "SYSTEM_DENIED").toTransportString(), true
                                )
                                delegation != null && tool != null -> {
                                    val delegated = pluginHooks.executeDelegated(delegation, toolCall, context.recoveryRunId ?: context.inputMessageId) { chunk ->
                                        onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, chunk.takeLast(LIVE_TAIL_CHARS)))
                                    }
                                    prepareToolResult(delegated, toolCall, executionContext)
                                }
                                tool is StreamingAgentTool -> runToolStream(tool, toolCall, executionContext, onEvent)
                                else -> runToolSync(tool, toolCall, executionContext)
                            }
                            execution.checkCurrent()
                            result.copy(timing = toolTimer.finish(started))
                        }
                    }
                    // Release the path lock before persisting; another same-path call may now execute.
                    val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull
                    val result = if (path.isNullOrBlank()) run()
                        else pathLocks.computeIfAbsent(path) { Mutex() }.withLock { run() }
                    val notifications = notificationSelection.withLock {
                        (context.sessionId?.let { agentNotificationCenter.peek(it) } ?: emptyList())
                            .filter { reservedNotifications.add(it.seq) }
                    }
                    val committedResult = if (notifications.isEmpty()) result else
                        result.copy(raw = eventInjector.inject(result.raw, notifications))
                    val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                    onEvent(AgentEvent.ToolCallFinished(
                        toolCall.id, toolCall.name, committedResult.raw, result.isError,
                        argsPreview = JsonObject(toolRegistry.forInvocation(toolCall.name, tools)?.let {
                            ToolPresentation.executionArguments(it, toolCall.arguments)
                        } ?: toolCall.arguments).toString(),
                        attachments = result.attachments, persisted = persisted,
                        actionTitle = ToolPresentation.titleOf(originalCalls.firstOrNull { it.id == toolCall.id } ?: toolCall),
                        startedAtMs = result.timing?.startedAtMs,
                        durationMs = result.timing?.durationMs,
                        notificationSeqs = notifications.map { it.seq }
                    ))
                    persisted.await()
                    pluginHooks.observe(PluginHookEvents.AGENT_TOOL_FINISHED, context, buildJsonObject {
                        put("callId", toolCall.id); put("toolName", toolCall.name); put("status", if (result.isError) "error" else "success")
                        put("durationMs", result.timing?.durationMs ?: 0)
                    })
                    committedResult
                }
            }.awaitAll()
        }
        ToolBatchExecution.Finished(results)
    } catch (_: LocalRuntimeAuthorizationRequired) {
        ToolBatchExecution.Aborted(appContext.getString(R.string.local_runtime_authorization_required), "LOCAL_RUNTIME_AUTHORIZATION_REQUIRED")
    } catch (_: ExecutionSourceChangedException) {
        ToolBatchExecution.Aborted(appContext.getString(R.string.tool_execution_source_changed), "EXECUTION_SOURCE_CHANGED")
    }

    /**
     * 流式响应累计器：把 [AIProvider.completeStream] 的增量按时间窗口节流后推送，并保留最终响应与调用统计。
     *
     * 独立管理增量、节流与速度状态，让 [executeEvents] 的各阶段只负责请求编排，
     * 避免把逐增量处理也合并进驱动 turn 的协程状态机。
     *
     * 节流：正文与思考各自按 60ms 窗口合并，窗口内只追加缓冲，发布时才物化累计文本，
     * 避免高频增量反复复制全文，并将每类下游事件频率压到约 16/s；流结束时补发尾部。
     * 下游打字机按自身渲染节奏消费快照，与此发布窗口独立。
     */
    internal class StreamAccumulator(
        private val callStartElapsed: Long,
        private val materialize: (StringBuilder) -> String = { it.toString() },
        private val emit: suspend (AgentEvent) -> Unit
    ) {
        private companion object {
            const val DELTA_THROTTLE_MS = 60L
        }

        /** 累积正文（节流推送与最终落库都用它）。 */
        val text = StringBuilder()

        /** 累积思考内容。 */
        val reasoning = StringBuilder()

        var finalResponse: AIResponse? = null
        var ttfbElapsed: Long? = null
        var retryAttempts = 0

        private var textDirty = false
        private var reasoningDirty = false
        private var lastTextSentAt = 0L
        private var lastReasoningSentAt = 0L
        private var requestDurationMillis: Long? = null
        private val speed = GenerationSpeedTracker()
        private val argumentPreviews = mutableMapOf<String, AIStreamChunk.ToolCallArgumentsDelta>()

        suspend fun emitSpeed() {
            speed.sample(SystemClock.elapsedRealtime())?.let { emit(it) }
        }

        suspend fun finishSpeed(outputTokens: Int) {
            speed.finish(outputTokens, requestDurationMillis)?.let { emit(it) }
        }

        suspend fun onChunk(chunk: AIStreamChunk) {
            when (chunk) {
                is AIStreamChunk.TextDelta -> {
                    markTtfb()
                    text.append(chunk.text)
                    speed.append(chunk.text, SystemClock.elapsedRealtime())
                    textDirty = true
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTextSentAt >= DELTA_THROTTLE_MS) {
                        flushText()
                        lastTextSentAt = now
                    }
                }
                is AIStreamChunk.ReasoningDelta -> {
                    // 思考内容也算首字（推理模型先吐思考再吐正文）
                    markTtfb()
                    reasoning.append(chunk.text)
                    speed.append(chunk.text, SystemClock.elapsedRealtime())
                    reasoningDirty = true
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReasoningSentAt >= DELTA_THROTTLE_MS) {
                        flushReasoning()
                        lastReasoningSentAt = now
                    }
                }
                is AIStreamChunk.Retrying -> {
                    retryAttempts = maxOf(retryAttempts, chunk.attempt)
                    reset()
                    emit(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                }
                is AIStreamChunk.KeySwitched -> {
                    reset()
                    emit(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                }
                is AIStreamChunk.Final -> {
                    // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                    markTtfb()
                    finalResponse = chunk.response
                    requestDurationMillis = chunk.requestDurationMillis
                }
                is AIStreamChunk.ToolCallArgumentsDelta -> {
                    speed.appendToolArguments(chunk.id, chunk.totalAsciiChars, chunk.totalOtherChars, SystemClock.elapsedRealtime())
                    val previous = argumentPreviews.put(chunk.id, chunk)
                    if (previous?.name != chunk.name || previous.accumulated != chunk.accumulated) {
                        emit(AgentEvent.ToolCallArgumentsPreview(chunk.id, chunk.name, chunk.accumulated))
                    }
                }
                is AIStreamChunk.ToolCallDeclared -> {
                    // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                    // 免得长参数流式期间一直停在「正在思考」。
                    markTtfb()
                    speed.begin(SystemClock.elapsedRealtime())
                    emit(AgentEvent.ToolCallPreparing(chunk.name))
                }
            }
        }

        /** 补发窗口内压着的最后一段文本（流结束时调用）。 */
        suspend fun flush() {
            flushText()
            flushReasoning()
        }

        private fun markTtfb() {
            if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
        }

        /** 重试/换 Key 后重放：丢弃已推送内容，避免新旧文本拼接。 */
        private fun reset() {
            speed.reset()
            requestDurationMillis = null
            argumentPreviews.clear()
            text.setLength(0)
            reasoning.setLength(0)
            textDirty = false
            reasoningDirty = false
            lastTextSentAt = 0L
            lastReasoningSentAt = 0L
        }

        private suspend fun flushText() {
            if (!textDirty) return
            textDirty = false
            emit(AgentEvent.AssistantDelta(materialize(text)))
        }

        private suspend fun flushReasoning() {
            if (!reasoningDirty) return
            reasoningDirty = false
            emit(AgentEvent.ReasoningDelta(materialize(reasoning)))
        }
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        return try {
            // 工具实现在此被调用；工作流本身在主线程收集，故统一切到 IO——避免远程模式下工具里的
            // 同步文件/网络 IO 阻塞主线程。依赖主线程的工具（浏览器）在自身实现内已切回 Main。
            withContext(Dispatchers.IO) {
                prepareToolResult(tool.executeWithContext(toolCall.arguments, context), toolCall, context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExecutionSourceChangedException) {
            throw e
        } catch (e: LocalRuntimeAuthorizationRequired) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    private suspend fun prepareToolResult(result: ToolResult, call: ToolCall, context: AgentContext): ToolRunResult {
        val fileAttachments = if (call.name == "sendFile" || call.name == "generateImage") extractAttachments(result) else emptyList()
        val images = if (result is ToolResult.Success) result.images else emptyList()
        val previewAttachments = if (fileAttachments.isEmpty() && images.isNotEmpty()) runCatching {
            ToolPreviewImageCache(java.io.File(appContext.cacheDir.canonicalFile, ToolPreviewImageCache.DIRECTORY))
                .store(context.sessionId, call.id, call.name, images)
        }.getOrDefault(emptyList()) else emptyList()
        val transformed = pluginHooks.afterTool(context, call, result)
        val transport = if (call.name == "sendFile" || call.name == "generateImage") stripAttachments(transformed) else transformed
        val processed = toolOutputStore.process(call.name, call.id, transport)
        return ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, (fileAttachments + previewAttachments).distinct(), images)
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList(), thinkingBlocksJson = "")
            }
        }
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "title",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成提交信息失败", e)
    }.getOrNull()

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        return try {
            // 同 runToolSync：流式工具也可能在主线程收集上下文里被驱动，统一切 IO。
            withContext(Dispatchers.IO) {
                val live = StringBuilder()
                var lastEmitMs = 0L
                var finalResult: ToolResult? = null
                tool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
                prepareToolResult(result, toolCall, context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExecutionSourceChangedException) {
            throw e
        } catch (e: LocalRuntimeAuthorizationRequired) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 把模型直出的图片（base64）落盘到 `~/.aicode/generated-images/`，返回带容器路径的 images
     * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }


    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return success.copy(data = JsonObject(strippedData))
    }

    private fun freezePermissionEvaluation(eval: ToolPermissionPolicyEngine.EvalResult) = eval.copy(
        hostSystemTargets = java.util.Collections.unmodifiableSet(LinkedHashSet(eval.hostSystemTargets)),
        hostSystemUncertainties = java.util.Collections.unmodifiableSet(LinkedHashSet(eval.hostSystemUncertainties))
    )

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        context: AgentContext
    ): PermissionCheckResult {
        val sessionId = context.sessionId
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        val eval = freezePermissionEvaluation(policyEngine.evaluate(tool.name, arguments, sessionId, null))
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            return PermissionCheckResult(false, reason, "SYSTEM_DENIED")
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> issueExecutionPermit(eval, arguments, sessionId)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                pluginHooks.observe(PluginHookEvents.AGENT_PERMISSION_REVIEW, context, buildJsonObject {
                    put("callId", callId); put("toolName", tool.name); put("requiresNativeApproval", true)
                })
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val sessionGrant = eval.sessionGrant?.takeIf { grant ->
                    grant.isNotBlank() && !sessionId.isNullOrBlank() &&
                        (!grant.startsWith(ToolPermissionManager.HOST_WRITE_GRANT_PREFIX) ||
                            grant.removePrefix(ToolPermissionManager.HOST_WRITE_GRANT_PREFIX).isNotBlank())
                }
                val request = base.copy(
                    // 提供商的 tool call ID 可能跨会话重复，审批 ID 必须独立。
                    id = UUID.randomUUID().toString(),
                    title = eval.askTitle ?: base.title,
                    approvalNote = eval.approvalNote,
                    sessionGrant = sessionGrant,
                    sessionId = sessionId.orEmpty()
                )
                val rootGeneration = eval.rootGeneration ?: eval.hostWriteRootGeneration
                val rootAuthorityId = if (eval.rootGeneration != null) eval.rootAuthorityId else eval.hostWriteRootAuthorityId
                val choice = if (rootGeneration == null && eval.shizukuIdentity == null) {
                    permissionManager.awaitApproval(request)
                } else coroutineScope {
                    val decision = async(start = CoroutineStart.UNDISPATCHED) { permissionManager.awaitApproval(request) }
                    val revocation = launch(start = CoroutineStart.UNDISPATCHED) {
                        coroutineScope {
                            if (rootGeneration != null) launch(start = CoroutineStart.UNDISPATCHED) {
                                rootCommandManager.accessState.collect { state ->
                                    if (!state.enabled || state.generation != rootGeneration || state.authorityId != rootAuthorityId) {
                                        permissionManager.resolve(request.id, PermissionChoice.REJECT)
                                    }
                                }
                            }
                            if (eval.shizukuIdentity != null) launch(start = CoroutineStart.UNDISPATCHED) {
                                shizukuManager.identity.collect { identity ->
                                    if (identity != eval.shizukuIdentity) {
                                        permissionManager.resolve(request.id, PermissionChoice.REJECT)
                                    }
                                }
                            }
                        }
                    }
                    try {
                        decision.await()
                    } finally {
                        revocation.cancelAndJoin()
                    }
                }
                when (choice) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                    PermissionChoice.ONCE, PermissionChoice.ALWAYS_SESSION -> {
                        if (choice == PermissionChoice.ALWAYS_SESSION && sessionGrant == null) {
                            PermissionCheckResult(false, appContext.getString(R.string.tool_permission_invalid_choice), "INVALID_APPROVAL_CHOICE")
                        } else {
                            if (choice == PermissionChoice.ALWAYS_SESSION && sessionGrant != null &&
                                sessionGrant.startsWith(ToolPermissionManager.HOST_WRITE_GRANT_PREFIX)
                            ) {
                                // Recheck after the suspended choice, before saving. A valid broad
                                // scope must be visible to later calls in this same approval batch.
                                // The final WEC-bound proof and exact native permit are still checked
                                // separately for every execution, including session-ALLOW calls.
                                confirmHostWriteSessionGrant(tool.name, arguments, sessionId, eval, sessionGrant)?.let { return it }
                            }
                            issueExecutionPermit(eval, arguments, sessionId, askApprovedForCall = true)
                        }
                    }
                }
            }
        }
    }

    private suspend fun confirmHostWriteSessionGrant(
        toolName: String,
        arguments: Map<String, JsonElement>,
        sessionId: String?,
        reviewed: ToolPermissionPolicyEngine.EvalResult,
        grant: String
    ): PermissionCheckResult? {
        val current = freezePermissionEvaluation(policyEngine.evaluate(toolName, arguments, sessionId, null))
        if (current.channelUnavailable) throw ExecutionSourceChangedException()
        if (current.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            return PermissionCheckResult(false, current.denyReason ?: "该工具被项目安全规则策略禁止执行", "SYSTEM_DENIED")
        }
        PermissionProof(reviewed, askApprovedForCall = true).check(current)
        if (current.verdict == ToolPermissionPolicyEngine.Verdict.ASK && current.sessionGrant != grant) {
            throw ExecutionSourceChangedException()
        }
        permissionManager.confirmHostWriteSessionGrant(sessionId, grant)
        return null
    }

    private suspend fun issueExecutionPermit(
        eval: ToolPermissionPolicyEngine.EvalResult,
        arguments: Map<String, JsonElement>,
        sessionId: String?,
        askApprovedForCall: Boolean = false
    ): PermissionCheckResult {
        val proof = PermissionProof(freezePermissionEvaluation(eval), askApprovedForCall)
        if (eval.rootGeneration != null) {
            if (sessionId.isNullOrBlank() || eval.rootAuthorityId.isNullOrBlank()) {
                return PermissionCheckResult(false, appContext.getString(R.string.root_approval_required), "ROOT_APPROVAL_REQUIRED")
            }
            val command = (arguments["command"] as? JsonPrimitive)?.content.orEmpty()
            val permit = rootCommandManager.authorize(sessionId, command, eval.rootGeneration, eval.rootAuthorityId)
            return if (permit == null) PermissionCheckResult(false, appContext.getString(R.string.root_access_disabled), "ROOT_ACCESS_CHANGED")
                else PermissionCheckResult(true, rootApprovalId = permit, permissionProof = proof)
        }
        if (eval.shizukuIdentity != null) {
            if (sessionId.isNullOrBlank()) {
                return PermissionCheckResult(false, appContext.getString(R.string.shizuku_approval_required), "SHIZUKU_APPROVAL_REQUIRED")
            }
            val command = (arguments["command"] as? JsonPrimitive)?.content.orEmpty()
            val permit = shizukuManager.authorize(sessionId, command, eval.shizukuIdentity)
            return if (permit == null) PermissionCheckResult(false, appContext.getString(R.string.shizuku_identity_changed), "SHIZUKU_IDENTITY_CHANGED")
                else PermissionCheckResult(true, shizukuApprovalId = permit, permissionProof = proof)
        }
        return PermissionCheckResult(true, permissionProof = proof)
    }
}
