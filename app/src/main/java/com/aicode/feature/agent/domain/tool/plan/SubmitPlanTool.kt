package com.aicode.feature.agent.domain.tool.plan

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

class SubmitPlanTool @Inject constructor(
    private val manager: PlanApprovalManager,
    @ApplicationContext private val appContext: Context
) : AgentTool() {

    companion object {
        const val NAME = "submitPlan"
        const val REJECTED_CODE = "PLAN_REJECTED"
    }

    override val name = NAME
    override val capabilities = setOf(ToolCapability.USER_INTERACTION)
    override val description = "提交 Markdown 实施计划并等待用户批准或拒绝，拒绝时返回用户反馈。"

    override val parameters = mapOf(
        "title" to ToolParameter(
            name = "title",
            type = ParameterType.STRING,
            description = "计划标题，可选；未提供时使用默认标题。",
            required = false
        ),
        "plan" to ToolParameter(
            name = "plan",
            type = ParameterType.STRING,
            description = "需要用户审批的完整实施计划，使用 Markdown 格式。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
        ToolResult.Error(appContext.getString(R.string.plan_approval_missing_session), "MISSING_SESSION")

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sessionId = context.sessionId
        if (sessionId.isNullOrBlank()) {
            return ToolResult.Error(appContext.getString(R.string.plan_approval_missing_session), "MISSING_SESSION")
        }

        val plan = (args["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (plan.isNullOrEmpty()) {
            return ToolResult.Error(appContext.getString(R.string.plan_approval_missing_plan), "MISSING_PLAN")
        }
        val title = (args["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: appContext.getString(R.string.plan_approval_default_title)

        val request = PendingPlanApproval(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            title = title,
            plan = plan
        )
        val decision = manager.awaitApproval(request)
        return if (decision.approved) {
            ToolResult.Success(buildJsonObject {
                put("approved", true)
                put("title", title)
                put("plan", plan)
            })
        } else {
            ToolResult.Error(
                appContext.getString(R.string.plan_approval_rejected_result, decision.feedback),
                REJECTED_CODE
            )
        }
    }
}
