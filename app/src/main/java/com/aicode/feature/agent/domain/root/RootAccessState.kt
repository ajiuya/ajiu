package com.aicode.feature.agent.domain.root

data class RootAccessState(
    val enabled: Boolean = false,
    val generation: Long = 0,
    val authorityId: String = ""
)

data class RootCommandResult(val output: String, val exitCode: Int)
