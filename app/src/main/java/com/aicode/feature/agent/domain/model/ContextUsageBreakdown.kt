package com.aicode.feature.agent.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ContextUsageBreakdown(
    val systemTokens: Int = 0,
    val toolDefinitionTokens: Int = 0,
    val chatTokens: Int = 0,
    val toolResultTokens: Int = 0,
    val imageTokens: Int = 0
) {
    val estimatedTotal: Int
        get() = (systemTokens.toLong() + toolDefinitionTokens + chatTokens + toolResultTokens + imageTokens)
            .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
