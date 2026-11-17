package com.aicode.feature.agent.domain.model

/**
 * 会话级持续目标（goal）的生命周期状态；[goal] 为 null 时无意义。
 * ACTIVE 表示自主循环进行中；其余为停止态。
 */
enum class GoalStatus {
    ACTIVE,    // 自主循环进行中
    PAUSED,    // 用户暂停
    COMPLETED, // AI 判定目标已达成
    BLOCKED,   // 受阻，需用户介入
    EXHAUSTED  // 轮次或 token 预算耗尽
}

/**
 * 一次独立的聊天会话。消息通过 sessionId 归属到会话，切换会话即切换聊天历史。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    /** 会话级持续目标文本；null 表示未设定目标。 */
    val goal: String? = null,
    /** goal 生命周期状态；[goal] 为 null 时无意义。 */
    val goalStatus: GoalStatus? = null,
    /** goal 自主循环已执行的续跑轮数（用于轮次上限）。 */
    val goalTurnCount: Int = 0,
    /** 设定 goal 时本会话累计输入 token 快照，用于计算 goal 期间的 token 消耗。 */
    val goalStartInputTokens: Int = 0,
    /** 设定 goal 时本会话累计输出 token 快照。 */
    val goalStartOutputTokens: Int = 0,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    val executionGeneration: Long = 0,
    val autoDispatchBlockedReason: String? = null
)
