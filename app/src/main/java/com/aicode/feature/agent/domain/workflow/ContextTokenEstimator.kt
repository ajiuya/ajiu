package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.settings.domain.model.ModelContextPolicy

object ContextTokenEstimator {
    private val cache = ContextEstimateCache()

    internal val cacheStats: ContextEstimateCache.Stats get() = cache.stats

    fun estimate(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        charsPerToken: Int = ModelContextPolicy.CHARS_PER_TOKEN
    ): Int = breakdown(systemPrompt, messages, tools, charsPerToken).estimatedTotal

    fun breakdown(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        charsPerToken: Int = ModelContextPolicy.CHARS_PER_TOKEN
    ): ContextUsageBreakdown {
        val toolDefinitionTokens = tools.sumOf { tool ->
            cache.schemaTextTokens(tool, charsPerToken).toLong() + 16
        }
        var chatTokens = 0L
        var toolResultTokens = 0L
        var imageTokens = 0L
        messages.forEach { message ->
            val images = when (message) {
                is AgentMessage.UserMessage -> message.images
                is AgentMessage.AssistantMessage -> message.images
                is AgentMessage.ToolResultMessage -> message.images
            }
            val messageImageTokens = images.size * 4_096L
            val textTokens = (estimate(message, charsPerToken).toLong() - messageImageTokens).coerceAtLeast(0L)
            when (message) {
                is AgentMessage.UserMessage, is AgentMessage.AssistantMessage -> chatTokens += textTokens
                is AgentMessage.ToolResultMessage -> toolResultTokens += textTokens
            }
            imageTokens += messageImageTokens
        }
        return ContextUsageBreakdown(
            systemTokens = ModelContextPolicy.estimateTextTokens(systemPrompt, charsPerToken),
            toolDefinitionTokens = toolDefinitionTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            chatTokens = chatTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            toolResultTokens = toolResultTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            imageTokens = imageTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    fun estimate(message: AgentMessage, charsPerToken: Int = ModelContextPolicy.CHARS_PER_TOKEN): Int {
        val textTokens = cache.messageTextTokens(message, charsPerToken)
        val images = when (message) {
            is AgentMessage.UserMessage -> message.images.size
            is AgentMessage.AssistantMessage -> message.images.size
            is AgentMessage.ToolResultMessage -> message.images.size
        }
        return (textTokens.toLong() + images * 4_096L + 12)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 有上游实测用量时以它为锚点，只把锚点之后新增内容的估算增量叠上去；
     * 不能再和整体估算取大——固定开销（系统提示词与工具定义）的估算偏高时，
     * 取大会让真实 10k 的上下文被判成超预算。
     */
    fun calibrated(estimated: Int, baselineEstimate: Int, baselineUsage: Int): Int =
        if (baselineUsage > 0) {
            (baselineUsage.toLong() + (estimated.toLong() - baselineEstimate).coerceAtLeast(0))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else estimated
}
