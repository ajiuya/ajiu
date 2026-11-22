package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.plan.SubmitPlanTool
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentAction
import com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentSessionState
import com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentSideEffect
import com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow.ToolBatchResult
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanApprovalWorkflowTest {
    private val workflow = StatefulAgentWorkflow(
        toolRegistry = mockk(),
        aiProviderRepository = mockk(),
        providerFactory = com.aicode.feature.agent.domain.provider.ProviderInstanceFactory(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk()
        ),
        promptProvider = mockk(),
        permissionManager = mockk(),
        policyEngine = mockk(),
        contextCompactor = mockk(),
        toolOutputStore = mockk(),
        modelMetadataService = mockk(),
        compactionModelSettingsRepository = mockk(),
        titleModelSettingsRepository = mockk(),
        defaultModelSettingsRepository = mockk(),
        generalSettingsRepository = mockk(),
        sessionUseCase = mockk(),
        messagePersistenceUseCase = mockk(),
        checkpointManager = mockk(),
        llmCallRecordDao = mockk(),
        agentNotificationCenter = mockk(),
        eventInjector = mockk(),
        fileAccess = mockk(),
        appContext = mockk(),
        rootCommandManager = mockk(),
        shizukuManager = mockk(),
        recoveryRepository = mockk(relaxed = true),
        workspaceExecutionContextFactory = mockk(),
        pluginHooks = mockk(relaxed = true)
    )
    private val plan = ToolCall("plan", SubmitPlanTool.NAME, mapOf("plan" to JsonPrimitive("Implement and test")))
    private val write = ToolCall("write", "writeFile", emptyMap())
    private val command = ToolCall("command", "Bash", emptyMap())
    private val task = ToolCall("task", "task", emptyMap())

    private fun response(calls: List<ToolCall>) = AgentAction.LlmResponse(
        AIResponse("Proposal", calls), "assistant", "Wait for the approved plan and retry"
    )

    @Test
    fun planBatch_defersAllOtherToolsRegardlessOfOrder() {
        for (calls in listOf(listOf(write, plan, command, task), listOf(plan, write, command, task))) {
            val (state, effects) = workflow.reduce(AgentSessionState(), response(calls))
            assertEquals(calls, state.batchToolCalls)
            assertEquals(listOf(plan), state.pendingPermissionCalls)
            assertTrue(state.approvedToolCalls.isEmpty())
            assertEquals(setOf(write.id, command.id, task.id), state.rejectedToolResults.keys)
            assertEquals(listOf(AgentSideEffect.RequestPermission(plan)), effects.filterIsInstance<AgentSideEffect.RequestPermission>())
            val deferred = effects.filterIsInstance<AgentSideEffect.CancelToolBatch>().single()
            assertEquals(setOf(write, command, task), deferred.toolCalls.toSet())
            assertEquals("PLAN_BATCH_DEFERRED", deferred.errorCode)
            assertFalse(effects.any { it is AgentSideEffect.ExecuteToolBatch })
        }
    }

    @Test
    fun multiplePlanCalls_onlyFirstCanBePresented() {
        val second = plan.copy(id = "second")
        val (state, _) = workflow.reduce(AgentSessionState(), response(listOf(plan, second, command)))
        assertEquals(listOf(plan), state.pendingPermissionCalls)
        assertEquals(setOf(second.id, command.id), state.rejectedToolResults.keys)
    }

    @Test
    fun approvedToolPermission_executesOnlyPlanAndNotDeferredTools() {
        val (pending, _) = workflow.reduce(AgentSessionState(), response(listOf(write, plan, command)))
        val (state, effects) = workflow.reduce(pending, AgentAction.PermissionEvaluated(plan, true, ""))
        assertEquals(listOf(plan), state.approvedToolCalls)
        assertEquals(listOf(AgentSideEffect.ExecuteToolBatch(listOf(plan))), effects)
        assertEquals(setOf(write.id, command.id), state.rejectedToolResults.keys)
    }

    @Test
    fun approvedPlan_returnsPairedResultsAndRequestsFreshModelTurn() {
        val (pending, _) = workflow.reduce(AgentSessionState(), response(listOf(write, plan, command)))
        val result = ToolBatchResult(plan.id, plan.name, ToolResult.Success(JsonPrimitive("Approved")).toTransportString(), false)
        val (state, effects) = workflow.reduce(pending, AgentAction.ToolBatchFinished(listOf(result)))
        val messages = state.messages.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(listOf(write.id, plan.id, command.id), messages.map { it.id })
        assertEquals(listOf(AgentSideEffect.CallLlm), effects)
        assertFalse(state.isFinished)
        assertTrue(state.batchToolCalls.isEmpty())
    }

    @Test
    fun rejectedPlan_finishesWithoutAnotherModelOrToolCall() {
        val (pending, _) = workflow.reduce(AgentSessionState(), response(listOf(write, plan, task)))
        val result = ToolBatchResult(plan.id, plan.name, ToolResult.Error("Revise scope", SubmitPlanTool.REJECTED_CODE).toTransportString(), true)
        val (state, effects) = workflow.reduce(pending, AgentAction.ToolBatchFinished(listOf(result)))
        assertTrue(state.isFinished)
        assertEquals(listOf(AgentSideEffect.PlanRejected), effects)
        assertEquals(listOf(write.id, plan.id, task.id), state.messages.filterIsInstance<AgentMessage.ToolResultMessage>().map { it.id })
        assertTrue(state.pendingPermissionCalls.isEmpty())
        assertTrue(state.approvedToolCalls.isEmpty())
    }

    @Test
    fun deniedPlan_cannotResumeThroughOtherCalls() {
        val (pending, _) = workflow.reduce(AgentSessionState(), response(listOf(plan, command)))
        val (denied, deniedEffects) = workflow.reduce(
            pending, AgentAction.PermissionEvaluated(plan, false, "", "Denied by project", "SYSTEM_DENIED")
        )
        assertEquals(listOf(AgentSideEffect.ExecuteToolBatch(emptyList())), deniedEffects)
        val (state, effects) = workflow.reduce(denied, AgentAction.ToolBatchFinished(emptyList()))
        assertTrue(state.isFinished)
        assertEquals(listOf(AgentSideEffect.PlanRejected), effects)
        assertEquals(listOf(plan.id, command.id), state.messages.filterIsInstance<AgentMessage.ToolResultMessage>().map { it.id })
    }

    @Test
    fun malformedPlanResult_stopsRatherThanAllowingImplementation() {
        val (pending, _) = workflow.reduce(AgentSessionState(), response(listOf(plan)))
        val result = ToolBatchResult(plan.id, plan.name, ToolResult.Error("Missing plan", "INVALID_PLAN").toTransportString(), true)
        val (state, effects) = workflow.reduce(pending, AgentAction.ToolBatchFinished(listOf(result)))
        assertTrue(state.isFinished)
        assertEquals(listOf(AgentSideEffect.PlanRejected), effects)
    }

    @Test
    fun ordinaryTools_keepTheirOriginalBatchBehavior() {
        val (state, effects) = workflow.reduce(AgentSessionState(), response(listOf(write, command)))
        assertEquals(listOf(write, command), state.pendingPermissionCalls)
        assertTrue(state.rejectedToolResults.isEmpty())
        assertEquals(listOf(AgentSideEffect.RequestPermission(write)), effects)
    }
}
