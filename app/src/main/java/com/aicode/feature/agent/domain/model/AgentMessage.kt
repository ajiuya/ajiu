package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.tool.ToolCall

@Serializable
sealed class AgentMessage {
    @Serializable
    data class UserMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val images: List<AgentImage> = emptyList(),
        val isCompactionMarker: Boolean = false,
        val isTransient: Boolean = false,
        val modelInputOwnerId: String? = null,
        val projectMemoryKey: String? = null
    ) : AgentMessage()

    @Serializable
    data class AssistantMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        /** 本轮模型的思考过程（对应 OpenAI/DeepSeek 的 reasoning_content）。回传上下文时需要原样发回，否则 DeepSeek 思考模式会报 400 错误。 */
        val reasoning: String = "",
        /** Anthropic extended thinking 的加密签名。与 [reasoning] 一起原样回传（工具循环必须），否则 400。其他 provider 为空串。 */
        val signature: String = "",
        /** Anthropic thinking / redacted_thinking 内容块的原样快照（JSON 数组文本），回传时需保持原样与原序。其他 provider 为空串。 */
        val thinkingBlocksJson: String = "",
        /**
         * 本轮模型直接生成的图片（Gemini 图像模型）。内存态下 base64Data 可为空、path 指向容器文件，
         * 回放时按 path 重建 base64 喂模型；落库只存附件路径不存 base64（见 [MessagePersistenceUseCase]）。
         */
        val images: List<AgentImage> = emptyList(),
        val inputTokens: Int = 0,
        val isContextSummary: Boolean = false
    ) : AgentMessage()

    @Serializable
    data class ToolResultMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolName: String,
        val result: String,
        val images: List<AgentImage> = emptyList(),
        /** 仅喂模型的精简结果文本；null 时回退用 [result]。UI 与持久化仍用 result。 */
        val modelResult: String? = null,
        val messageId: String = "tool_$id"
    ) : AgentMessage()
}

@Serializable
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

const val CONTEXT_COMPACTION_MARKER = "Context checkpoint: The assistant summary below preserves prior work. Continue the latest user request from the current point. Do not answer with a progress summary alone; take the next concrete step unless the task is complete or blocked by required user input, approval, or a safety constraint."
const val CONTEXT_SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"

val AgentMessage.id: String
    get() = when (this) {
        is AgentMessage.UserMessage -> id
        is AgentMessage.AssistantMessage -> id
        is AgentMessage.ToolResultMessage -> messageId
    }

data class AgentContext(
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val language: String?,
    val history: List<AgentMessage> = emptyList(),
    val inputImages: List<AgentImage> = emptyList(),
    /** 当前会话 id：用于把本轮所有 AI 请求/响应落到该会话的日志文件（[com.aicode.core.util.AILogger]）。 */
    val sessionId: String? = null,
    val inputMessageId: String = java.util.UUID.randomUUID().toString(),
    val lastInputTokens: Int = 0,
    /** 当前会话的持续目标文本；null 表示未设定目标。 */
    val goal: String? = null,
    /** 当前会话的 goal 生命周期状态；ACTIVE 表示自主循环进行中。 */
    val goalStatus: GoalStatus? = null,
    /** 思考强度（"low"/"medium"/"high"），随每次 LLM 请求传给支持的 provider。 */
    val reasoningEffort: String? = null,
    /**
     * 本会话绑定的自定义子代理定义；非空表示这是一个按定义运行的子代理会话，
     * 系统提示词与工具集都按其配置组装（见 [com.aicode.feature.agent.domain.prompt.SystemPromptProvider]）。
     */
    val agentDefinition: AgentDefinition? = null,
    val rootApprovalId: String? = null,
    val shizukuApprovalId: String? = null,
    val projectMemoryIdentity: com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity? = null,
    val projectMemoryContext: String? = null,
    val projectMemoryProjectId: String? = null,
    val originalUserRequest: String? = null,
    val projectMemoryTurnOverride: com.aicode.feature.agent.domain.memory.ProjectMemoryTurnOverride? = null,
    val projectMemoryCapture: com.aicode.feature.agent.domain.memory.ProjectMemoryCapture? = null,
    val persistInputSnapshot: Boolean = false,
    val transientInput: Boolean = false,
    val recoveryRunId: String? = null,
    val requireFreshFileRead: Boolean = false,
    /** 默认通用子代理也属于子会话，不能因没有命名定义而收到父代理的角色目录。 */
    val isSubagent: Boolean = false
) {
}
