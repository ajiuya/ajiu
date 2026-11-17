package com.aicode.feature.agent.domain.tool.plan

data class PendingPlanApproval(
    val id: String,
    val sessionId: String,
    val title: String,
    val plan: String
)

data class PlanApprovalDecision(
    val approved: Boolean,
    val feedback: String = ""
)
