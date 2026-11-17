package com.aicode.feature.agent.domain.workflow

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
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
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.fixedTemperature
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.AllKeysFailedException
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.provider.isKeySwitchFailure
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.agent.domain.provider.clampedFrom
import com.aicode.feature.agent.domain.provider.reasoningPlanFor
import com.aicode.feature.agent.domain.provider.toReasoningCapability
import com.aicode.feature.agent.domain.provider.wireSummary
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
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

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
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
    private val keyRotator: ProviderKeyRotator,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider,
    @param:ApplicationContext private val appContext: Context,
    private val rootCommandManager: RootCommandManager,
    private val recoveryRepository: com.aicode.feature.agent.domain.recovery.RecoveryRepository,
    private val workspaceExecutionContextFactory: com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
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
        val rootApprovalId: String? = null
    )

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

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
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
        return result.compacted
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
                it.speedTierSource = generalSettingsRepository::speedTier
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（会话内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        provider.reasoningCapability = metadata.toReasoningCapability()
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

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
        val turnStartedElapsed = SystemClock.elapsedRealtime()
        val toolTimer = ToolCallTimer(System::currentTimeMillis, SystemClock::elapsedRealtime)
        var lastPersistedAssistantId: String? = null
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + if (currentContext.persistInputSnapshot) {
                    messagePersistenceUseCase.prepareModelInput(currentContext, userRequest)
                } else com.aicode.feature.agent.domain.model.ModelInputSnapshot.prepare(currentContext, userRequest)
                    .messages(currentContext.inputMessageId, currentContext.inputImages, persisted = !currentContext.transientInput, snapshotStored = false)
            )
        )

        // 提示词构建会读取技能/子代理/记忆等文件（远程模式经 SFTP），必须离开收集本工作流的线程（Main），
        // 否则远程模式下每次会话首次发消息都会在主线程做多次文件/网络往返。
        val promptStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "prompt.start", details = "operation=$promptStartedNs")
        var systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
        FileLogger.memoryCheckpoint(
            TAG,
            "prompt.ready",
            elapsedMs = (System.nanoTime() - promptStartedNs) / 1_000_000,
            details = "operation=$promptStartedNs promptChars=${systemPrompt.length}"
        )
        val providerStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "provider.start", details = "operation=$providerStartedNs")
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        FileLogger.memoryCheckpoint(
            TAG,
            "provider.ready",
            elapsedMs = (System.nanoTime() - providerStartedNs) / 1_000_000,
            details = "operation=$providerStartedNs"
        )
        // 压缩失败后本轮（本次用户请求内）不再重复尝试压缩，避免每次 LLM 调用都白试一次。
        var compactionAttemptFailed = false
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        // 思考能力随模型走：每次请求刷新，切模型后档位集合立即变化（resolver 用它判断能否关闭与降级目标）。
        aiProvider.reasoningCapability = metadata.toReasoningCapability()
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        var baselineEstimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            ContextTokenEstimator.estimate(systemPrompt,
                currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
        }
        var baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
            (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
        } else 0
        var overflowRecoveryAttempted = false
        var overflowRecoveryStep: com.aicode.feature.agent.domain.recovery.RecoveryStep? = null
        var continuationCount = 0
        var continuationOutputTokens = 0L

        val rootApprovals = mutableMapOf<String, String>()
        try {
        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.aicode.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id,
                                recoveryRunId = currentContext.recoveryRunId, recoverySupplemental = currentContext.recoveryRunId != null)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        currentContext.projectMemoryCapture?.assertCurrent()
                        var recoveryStep = currentContext.recoveryRunId?.let { runId ->
                            val previous = overflowRecoveryStep
                            overflowRecoveryStep = null
                            (if (previous == null) recoveryRepository.beginStep(runId) else recoveryRepository.restartStream(runId, previous))
                                ?: throw CancellationException("Recovery request is no longer current")
                        }
                        if (state.iterations > 0) {
                            val refreshedPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
                            if (refreshedPrompt != systemPrompt) {
                                systemPrompt = refreshedPrompt
                                baselineUsage = 0
                                baselineEstimate = 0
                            }
                        }
                        val providerInUse = aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val estimate = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                            }
                            val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId,
                                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                                currentInputTokens = predictedInput) { event ->
                                if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                                send(event)
                            }
                            compactedMessages = compaction.messages
                            if (compaction.compacted) {
                                state = state.copy(messages = compaction.messages)
                                baselineUsage = 0
                                baselineEstimate = 0
                            }
                        }
                        val requestBreakdown = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            ContextTokenEstimator.breakdown(systemPrompt, compactedMessages, currentTools)
                        }
                        val requestEstimate = requestBreakdown.estimatedTotal
                        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
                        send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
                        if (predictedInput >= inputBudget) {
                            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                            continue
                        }

                        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var callError: String? = null
                        var callCompleted = false
                        val stream = StreamAccumulator(callStartElapsed) { send(it) }
                        val draftWindow = com.aicode.feature.agent.domain.recovery.RecoveryDraftWindow()
                        send(AgentEvent.GenerationStarted)

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = sanitizeImagesForModel(compactedMessages, supportsVision)
                            coroutineScope {
                                val speedUpdates = launch {
                                    while (isActive) {
                                        delay(GenerationSpeedTracker.UPDATE_INTERVAL_MS)
                                        stream.emitSpeed()
                                    }
                                }
                                try {
                                    providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort)
                                        .collect { chunk ->
                                            currentContext.projectMemoryCapture?.assertCurrent()
                                            if (chunk is AIStreamChunk.Retrying || chunk is AIStreamChunk.KeySwitched) {
                                                currentContext.recoveryRunId?.let { runId ->
                                                    recoveryStep = recoveryRepository.restartStream(runId, requireNotNull(recoveryStep))
                                                        ?: throw CancellationException("Recovery stream is no longer current")
                                                }
                                            }
                                            stream.onChunk(chunk)
                                            if (chunk is AIStreamChunk.TextDelta) currentContext.recoveryRunId?.let { runId ->
                                                val step = requireNotNull(recoveryStep)
                                                val draftStartedAt = SystemClock.elapsedRealtime()
                                                if (draftWindow.isDue(step, draftStartedAt) &&
                                                    recoveryRepository.saveDraft(runId, step, stream.text.toString())) {
                                                    draftWindow.saved(step, draftStartedAt)
                                                }
                                                if (!recoveryRepository.isCurrent(runId)) throw CancellationException("Recovery draft is no longer current")
                                            }
                                        }
                                } finally {
                                    speedUpdates.cancelAndJoin()
                                }
                            }
                            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
                            stream.flush()
                            val aiResponse = stream.finalResponse ?: AIResponse(content = stream.text.toString())
                            stream.finishSpeed(aiResponse.outputTokens)
                            // 模型直出图片（Gemini 图像模型）不随流式增量到达，整块在 Final 里：
                            // 先把 base64 落盘到 ~/.aicode/generated-images/ 并构造 UI 附件（只存路径不存 base64），
                            // 再随 LlmResponse 把带 path 的 images 交 reduce 挂上 AssistantMessage 供下一回放。
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) withContext(workspaceExecutionContextFactory.freeze(currentContext.projectMemoryCapture)) {
                                    persistModelImages(aiResponse.images)
                                } else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
                            if (aiResponse.stopReason == "model_context_window_exceeded" && !overflowRecoveryAttempted) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    overflowRecoveryStep = recoveryStep
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    continue
                                }
                            }
                            val continuationAllowed = if (aiResponse.isTruncated && aiResponse.toolCalls.isEmpty()) {
                                currentContext.recoveryRunId?.let { runId ->
                                    recoveryRepository.incrementContinuationBudget(runId,
                                        aiResponse.outputTokens.takeIf { it > 0 } ?: ModelContextPolicy.estimateTextTokens(aiResponse.content),
                                        aiResponse.content.length)
                                } ?: run {
                                    continuationCount++
                                    continuationOutputTokens += aiResponse.outputTokens.takeIf { it > 0 }
                                        ?: ModelContextPolicy.estimateTextTokens(aiResponse.content)
                                    continuationCount <= 3 && continuationOutputTokens <= 64_000
                                }
                            } else true
                            baselineEstimate = requestEstimate
                            baselineUsage = aiResponse.inputTokens
                            val responseContextUsage = AgentEvent.ContextUsage(
                                if (baselineUsage > 0) baselineUsage else predictedInput, inputBudget, baselineUsage <= 0
                            )
                            send(responseContextUsage)
                            val responseMessageId = UUID.randomUUID().toString()
                            val responseWithReasoning = if (stream.reasoning.isNotEmpty()) {
                                aiResponse.copy(reasoning = stream.reasoning.toString())
                            } else aiResponse

                            if (currentContext.recoveryRunId != null || aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        stream.reasoning.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments,
                                        messageId = responseMessageId,
                                        persisted = persisted,
                                        contextUsage = responseContextUsage,
                                        contextBreakdown = requestBreakdown,
                                        recoveryStep = recoveryStep
                                    )
                                )
                                persisted.await()
                                lastPersistedAssistantId = responseMessageId
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning,
                                    messageId = responseMessageId,
                                    planBatchDeferredReason = appContext.getString(R.string.plan_approval_batch_deferred),
                                    continuationAllowed = continuationAllowed
                                )
                            )
                        } catch (e: CancellationException) {
                            if (currentContext.recoveryRunId != null && recoveryStep != null && stream.text.isNotEmpty()) {
                                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                                    runCatching { recoveryRepository.saveDraft(currentContext.recoveryRunId, requireNotNull(recoveryStep), stream.text.toString(), force = true) }
                                }
                            }
                            throw e
                        } catch (e: Exception) {
                            if (!overflowRecoveryAttempted && stream.text.isEmpty() && stream.reasoning.isEmpty() && isContextOverflow(e)) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    overflowRecoveryStep = recoveryStep
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    callError = e.message
                                    continue
                                }
                            }
                            val partial = stream.text.toString()
                            val reasoning = stream.reasoning.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (currentContext.recoveryRunId != null && partial.isNotEmpty()) {
                                recoveryRepository.saveDraft(currentContext.recoveryRunId, requireNotNull(recoveryStep), partial, force = true)
                            }
                            if (currentContext.recoveryRunId == null && (partial.isNotEmpty() || reasoning.isNotBlank())) {
                                val partialEvent = AgentEvent.AssistantText(
                                    partial,
                                    emptyList(),
                                    reasoning,
                                    contextUsage = AgentEvent.ContextUsage(predictedInput, inputBudget, true),
                                    contextBreakdown = requestBreakdown,
                                    recoveryStep = recoveryStep
                                )
                                send(partialEvent)
                                lastPersistedAssistantId = partialEvent.messageId
                            }
                            // 多 Key 的自动切换与重发已在 adapter 内完成（见 AIProvider.keySwitcher）；
                            // 走到这里说明不是 Key 问题、或候选 Key 已全部失败，直接上报原始错误。
                            val errorText = "LLM 调用失败: ${e.message}"
                            actionQueue.addLast(AgentAction.LlmError(errorText))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = stream.finalResponse
                            runCatching {
                                // 与适配器同源算一次「实际会发什么」：落库后能在统计页分辨
                                // 「选了但没生效」「被降级」这些静默问题。
                                val reasoningPlan = reasoningPlanFor(providerInUse, currentContext.reasoningEffort)
                                llmCallRecordDao.insert(
                                    LlmCallRecordEntity(
                                        sessionId = currentContext.sessionId,
                                        providerId = providerInUse.providerId.ifBlank { null },
                                        model = providerInUse.model,
                                        reasoningEffort = currentContext.reasoningEffort,
                                        reasoningWire = reasoningPlan.wireSummary(),
                                        reasoningClampedFrom = reasoningPlan.clampedFrom(currentContext.reasoningEffort),
                                        kind = callKind,
                                        inputTokens = usage?.inputTokens ?: 0,
                                        outputTokens = usage?.outputTokens ?: 0,
                                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                        ttfbMillis = stream.ttfbElapsed?.toInt(),
                                        durationMillis = durationMillis,
                                        status = when {
                                            callCompleted -> "success"
                                            callError != null -> "error"
                                            else -> "cancelled"
                                        },
                                        errorMessage = callError,
                                        stopReason = usage?.stopReason,
                                        retryCount = stream.retryAttempts,
                                        createdAt = callStartWall
                                    )
                                )
                            }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        currentContext.recoveryRunId?.let { runId ->
                            if (!recoveryRepository.markToolWaitingApproval(runId, effect.toolCall.id)) {
                                throw CancellationException("Tool approval request is no longer current")
                            }
                        }
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val executionArgs = tool?.let { ToolPresentation.executionArguments(it, effect.toolCall.arguments) }
                            ?: effect.toolCall.arguments
                        val argsPreview = JsonObject(executionArgs).toString()
                        val actionTitle = ToolPresentation.titleOf(effect.toolCall)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, executionArgs, argsPreview, currentContext.sessionId)

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                            send(AgentEvent.ToolCallFinished(
                                effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview,
                                actionTitle = actionTitle, persisted = persisted
                            ))
                            persisted.await()
                        } else {
                            checkResult.rootApprovalId?.let { rootApprovals[effect.toolCall.id] = it }
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview, actionTitle))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            rootApprovals.remove(toolCall.id)?.let { rootCommandManager.revokePermit(it) }
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
                                    argsPreview = JsonObject(toolRegistry.getTool(toolCall.name)?.let {
                                        ToolPresentation.executionArguments(it, toolCall.arguments)
                                    } ?: toolCall.arguments).toString(),
                                    actionTitle = ToolPresentation.titleOf(toolCall),
                                    persisted = persisted
                                )
                            )
                            persisted.await()
                        }
                    }
                    AgentSideEffect.PlanRejected -> send(AgentEvent.PlanRejected)
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 计划审批在进入此批次前已与其他调用隔离，避免并行工具越过审批。
                        val toolCalls = effect.toolCalls
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        withContext(workspaceExecutionContextFactory.freeze(currentContext.projectMemoryCapture)) {
                                            checkpointManager.beforeFileModified(sid, path)
                                        }
                                    }
                                }
                            }
                        }

                        val executionContexts = toolCalls.associate { call ->
                            call.id to currentContext.copy(rootApprovalId = rootApprovals[call.id])
                        }
                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                val pathLocks = ConcurrentHashMap<String, Mutex>()
                                val notificationSelection = Mutex()
                                val reservedNotifications = mutableSetOf<Long>()
                                toolCalls.map { toolCall ->
                                    async {
                                        val run: suspend () -> ToolRunResult = {
                                            val execution = workspaceExecutionContextFactory.freeze(currentContext.projectMemoryCapture)
                                            withContext(execution) {
                                            currentContext.projectMemoryCapture?.assertCurrent()
                                            currentContext.recoveryRunId?.let { runId ->
                                                if (!recoveryRepository.markToolStarted(runId, toolCall.id)) {
                                                    throw CancellationException("Tool execution is no longer current")
                                                }
                                            }
                                            val started = toolTimer.start()
                                            send(AgentEvent.ToolExecutionStarted(toolCall.id, started.startedAtMs, started.startedElapsedMs))
                                            val tool = toolRegistry.getTool(toolCall.name)
                                            val executableCall = toolCall.copy(arguments = tool?.let {
                                                ToolPresentation.executionArguments(it, toolCall.arguments)
                                            } ?: toolCall.arguments)
                                            val result = if (tool is StreamingAgentTool) {
                                                runToolStream(tool, executableCall, executionContexts.getValue(toolCall.id)) { send(it) }
                                            } else {
                                                runToolSync(tool, executableCall, executionContexts.getValue(toolCall.id))
                                            }
                                            execution.checkCurrent()
                                            result.copy(timing = toolTimer.finish(started))
                                            }
                                        }
                                        // 同一批里针对同一路径的调用必须串行：并行会让「读-改-写」互相覆盖，
                                        // 并让写入方的回读校验看到别人的内容而误报失败。不同路径仍并行。
                                        val path = (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull
                                        val result = if (path.isNullOrBlank()) run()
                                        else pathLocks.computeIfAbsent(path) { Mutex() }.withLock { run() }
                                        val sessionId = currentContext.sessionId
                                        val notifications = notificationSelection.withLock {
                                            (if (sessionId != null) agentNotificationCenter.peek(sessionId) else emptyList())
                                                .filter { reservedNotifications.add(it.seq) }
                                        }
                                        val committedResult = if (notifications.isEmpty()) result else
                                            result.copy(raw = eventInjector.inject(result.raw, notifications))
                                        val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                        send(AgentEvent.ToolCallFinished(
                                            toolCall.id, toolCall.name, committedResult.raw, result.isError,
                                            argsPreview = JsonObject(toolRegistry.getTool(toolCall.name)?.let {
                                                ToolPresentation.executionArguments(it, toolCall.arguments)
                                            } ?: toolCall.arguments).toString(),
                                            attachments = result.attachments, persisted = persisted,
                                            actionTitle = ToolPresentation.titleOf(toolCall),
                                            startedAtMs = result.timing?.startedAtMs,
                                            durationMs = result.timing?.durationMs,
                                            notificationSeqs = notifications.map { it.seq }
                                        ))
                                        persisted.await()
                                        committedResult
                                    }
                                }.awaitAll()
                            }
                        }

                        val batchResults = toolCalls.mapIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            ToolBatchResult(
                                toolCall.id,
                                toolCall.name,
                                runResult.raw,
                                runResult.isError,
                                runResult.attachments,
                                runResult.images
                            )
                        }.toMutableList()

                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        } finally {
            rootApprovals.values.forEach(rootCommandManager::revokePermit)
        }
        send(AgentEvent.TurnFinished(
            lastPersistedAssistantId,
            (SystemClock.elapsedRealtime() - turnStartedElapsed).coerceAtLeast(0L)
        ))
        state.error?.let { send(AgentEvent.Failed(it, state.errorCode)) }
        send(AgentEvent.Completed)
    }

    /**
     * 流式响应累计器：把 [AIProvider.completeStream] 的增量按时间窗口节流后推送，并保留最终响应与调用统计。
     *
     * 独立成类是为了不让 [executeEvents] 的协程状态机为每个增量都插入挂起点——那个 `channelFlow` 主体
     * 是本工程最大的协程方法，规模过大会被 ART 长期交给解释器执行。
     *
     * 节流：上游每个 chunk 都携带完整累积文本，逐条推送会让 ViewModel 端每秒重建几十次状态、
     * UI 端反复重启打字机协程。按时间窗口合并，窗口内只保留最新累积文本，到窗口边界才发送，
     * 把下游事件频率压到 ~16/s；打字机渲染本身有 100ms 节流，少发中间态无感知。
     */
    private class StreamAccumulator(
        private val callStartElapsed: Long,
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

        private var pendingText: String? = null
        private var pendingReasoning: String? = null
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
                    pendingText = text.toString()
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
                    pendingReasoning = reasoning.toString()
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
            pendingText = null
            pendingReasoning = null
            lastTextSentAt = 0L
            lastReasoningSentAt = 0L
        }

        private suspend fun flushText() {
            val t = pendingText ?: return
            pendingText = null
            emit(AgentEvent.AssistantDelta(t))
        }

        private suspend fun flushReasoning() {
            val t = pendingReasoning ?: return
            pendingReasoning = null
            emit(AgentEvent.ReasoningDelta(t))
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
                val result = tool.executeWithContext(toolCall.arguments, context)
                val fileAttachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val previewAttachments = if (fileAttachments.isEmpty() && images.isNotEmpty()) runCatching {
                    ToolPreviewImageCache(java.io.File(appContext.cacheDir.canonicalFile, ToolPreviewImageCache.DIRECTORY))
                        .store(context.sessionId, toolCall.id, name, images)
                }.getOrDefault(emptyList()) else emptyList()
                val attachments = (fileAttachments + previewAttachments).distinct()
                val transportResult = if (fileAttachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
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
                is AgentMessage.AssistantMessage -> msg
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
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val attachments = if (images.isNotEmpty()) runCatching {
                    ToolPreviewImageCache(java.io.File(appContext.cacheDir.canonicalFile, ToolPreviewImageCache.DIRECTORY))
                        .store(context.sessionId, toolCall.id, toolCall.name, images)
                }.getOrDefault(emptyList()) else emptyList()
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments.distinct(), images)
            }
        } catch (e: CancellationException) {
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
        return ToolResult.Success(JsonObject(strippedData))
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        sessionId: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        val eval = policyEngine.evaluate(tool.name, arguments, sessionId)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            return PermissionCheckResult(false, reason, "SYSTEM_DENIED")
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    // 提供商的 tool call ID 可能跨会话重复，审批 ID 必须独立。
                    id = UUID.randomUUID().toString(),
                    title = eval.askTitle ?: base.title,
                    approvalNote = eval.approvalNote,
                    sessionGrant = eval.sessionGrant,
                    sessionId = sessionId.orEmpty()
                )
                val choice = if (eval.rootGeneration == null) {
                    permissionManager.awaitApproval(request)
                } else coroutineScope {
                    val decision = async(start = CoroutineStart.UNDISPATCHED) { permissionManager.awaitApproval(request) }
                    val revocation = launch(start = CoroutineStart.UNDISPATCHED) {
                        rootCommandManager.accessState.collect { state ->
                            if (!state.enabled || state.generation != eval.rootGeneration) {
                                permissionManager.resolve(request.id, PermissionChoice.REJECT)
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
                        if (eval.rootGeneration == null) {
                            PermissionCheckResult(true)
                        } else if (choice != PermissionChoice.ONCE || sessionId.isNullOrBlank()) {
                            PermissionCheckResult(false, appContext.getString(R.string.root_approval_required), "ROOT_APPROVAL_REQUIRED")
                        } else {
                            val command = (arguments["command"] as? JsonPrimitive)?.content.orEmpty()
                            val permit = rootCommandManager.authorize(sessionId, command, eval.rootGeneration)
                            if (permit == null) PermissionCheckResult(false, appContext.getString(R.string.root_access_disabled), "ROOT_ACCESS_CHANGED")
                            else PermissionCheckResult(true, rootApprovalId = permit)
                        }
                    }
                }
            }
        }
    }
}
