package com.aicode.feature.agent.domain.tool.plan

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SubmitPlanToolTest {

    private lateinit var manager: PlanApprovalManager
    private lateinit var tool: SubmitPlanTool

    @Before
    fun setUp() {
        manager = PlanApprovalManager()
        val appContext = mockk<Context> {
            every { getString(R.string.plan_approval_default_title) } returns "Implementation plan"
            every { getString(R.string.plan_approval_missing_plan) } returns "Plan is required"
            every { getString(R.string.plan_approval_missing_session) } returns "Session is required"
            every { getString(R.string.plan_approval_rejected_result, *anyVararg()) } answers {
                "Plan rejected: ${secondArg<Array<Any?>>().single()}"
            }
        }
        tool = SubmitPlanTool(manager, appContext)
    }

    private fun context(sessionId: String? = "session-1") = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/workspace",
        language = null,
        sessionId = sessionId
    )

    @Test
    fun schema_exposesOptionalTitleAndRequiredMarkdownPlan() {
        assertEquals("submitPlan", SubmitPlanTool.NAME)
        assertEquals("PLAN_REJECTED", SubmitPlanTool.REJECTED_CODE)
        assertEquals(SubmitPlanTool.NAME, tool.name)
        assertEquals(setOf(ToolCapability.USER_INTERACTION), tool.capabilities)
        assertEquals(setOf("title", "plan"), tool.parameters.keys)
        assertEquals(ParameterType.STRING, tool.parameters.getValue("title").type)
        assertFalse(tool.parameters.getValue("title").required)
        assertEquals(ParameterType.STRING, tool.parameters.getValue("plan").type)
        assertTrue(tool.parameters.getValue("plan").required)
        assertTrue(tool.parameters.getValue("plan").description.contains("Markdown"))

        val schema = tool.toJsonSchema()
        assertEquals("object", schema["type"])
        assertEquals(listOf("plan"), schema["required"])
        val properties = schema["properties"] as Map<*, *>
        assertEquals("string", (properties["title"] as Map<*, *>)["type"])
        assertEquals("string", (properties["plan"] as Map<*, *>)["type"])
    }

    @Test
    fun executeWithoutContext_returnsMissingSessionWithoutWaiting() = runTest {
        val result = tool.execute(mapOf("plan" to JsonPrimitive("# Plan")))

        assertEquals(ToolResult.Error("Session is required", "MISSING_SESSION"), result)
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun executeWithContext_missingOrBlankSessionReturnsErrorWithoutWaiting() = runTest {
        for (sessionId in listOf(null, "", " \t\n")) {
            val result = tool.executeWithContext(
                mapOf("plan" to JsonPrimitive("# Plan")),
                context(sessionId)
            )

            assertEquals(ToolResult.Error("Session is required", "MISSING_SESSION"), result)
            assertNull(manager.pendingPlan.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
        }
    }

    @Test
    fun executeWithContext_missingPlanReturnsErrorWithoutWaiting() = runTest {
        val result = tool.executeWithContext(emptyMap(), context())

        assertEquals(ToolResult.Error("Plan is required", "MISSING_PLAN"), result)
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun executeWithContext_blankOrNonStringPlanReturnsErrorWithoutWaiting() = runTest {
        val invalidPlans = listOf<JsonElement>(
            JsonPrimitive(""),
            JsonPrimitive(" \t\n"),
            JsonPrimitive(123),
            JsonPrimitive(true),
            JsonNull,
            JsonObject(emptyMap()),
            JsonArray(emptyList())
        )

        for (plan in invalidPlans) {
            val result = tool.executeWithContext(mapOf("plan" to plan), context())

            assertEquals(ToolResult.Error("Plan is required", "MISSING_PLAN"), result)
            assertNull(manager.pendingPlan.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
        }
    }

    @Test
    fun approvalFieldsInArguments_cannotApproveThePlan() = runTest {
        val result = async {
            tool.executeWithContext(
                mapOf(
                    "plan" to JsonPrimitive("# Plan"),
                    "approved" to JsonPrimitive(true),
                    "requestId" to JsonPrimitive("model-request")
                ),
                context()
            )
        }
        runCurrent()

        val pending = manager.pendingPlan.value!!
        assertFalse(result.isCompleted)
        assertNotEquals("model-request", pending.id)
        assertFalse(manager.resolve("model-request", PlanApprovalDecision(true)))
        assertTrue(manager.resolve(pending.id, PlanApprovalDecision(false)))
        assertTrue(result.await() is ToolResult.Error)
    }

    @Test
    fun approvedPlan_returnsStructuredSuccessWithTitleAndMarkdown() = runTest {
        val plan = "# Plan\n\n- Implement\n- Test"
        val result = async {
            tool.executeWithContext(
                mapOf(
                    "title" to JsonPrimitive("  Feature plan  "),
                    "plan" to JsonPrimitive("\n$plan\n")
                ),
                context()
            )
        }
        runCurrent()

        val pending = manager.pendingPlan.value!!
        assertEquals("session-1", pending.sessionId)
        assertEquals("Feature plan", pending.title)
        assertEquals(plan, pending.plan)
        assertEquals(setOf("session-1"), manager.awaitingSessionIds.value)
        assertFalse(result.isCompleted)
        assertTrue(manager.resolve(pending.id, PlanApprovalDecision(true)))

        assertEquals(
            ToolResult.Success(buildJsonObject {
                put("approved", true)
                put("title", "Feature plan")
                put("plan", plan)
            }),
            result.await()
        )
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun missingOrInvalidTitle_usesLocalizedDefaultTitle() = runTest {
        val titles = listOf<JsonElement?>(
            null,
            JsonPrimitive(""),
            JsonPrimitive(" \t\n"),
            JsonPrimitive(123),
            JsonPrimitive(false),
            JsonNull,
            JsonObject(emptyMap()),
            JsonArray(emptyList())
        )

        for (title in titles) {
            val args = buildMap<String, JsonElement> {
                put("plan", JsonPrimitive("# Plan"))
                if (title != null) put("title", title)
            }
            val result = async { tool.executeWithContext(args, context()) }
            runCurrent()

            val pending = manager.pendingPlan.value!!
            assertEquals("Implementation plan", pending.title)
            assertTrue(manager.resolve(pending.id, PlanApprovalDecision(true)))
            assertEquals(
                ToolResult.Success(buildJsonObject {
                    put("approved", true)
                    put("title", "Implementation plan")
                    put("plan", "# Plan")
                }),
                result.await()
            )
            assertNull(manager.pendingPlan.value)
            assertTrue(manager.awaitingSessionIds.value.isEmpty())
        }
    }

    @Test
    fun rejectedPlan_returnsRejectionCodeAndExactFeedback() = runTest {
        val feedback = "Add a rollback step\nKeep the public API unchanged"
        val result = async {
            tool.executeWithContext(mapOf("plan" to JsonPrimitive("# Plan")), context())
        }
        runCurrent()

        val pending = manager.pendingPlan.value!!
        assertTrue(manager.resolve(pending.id, PlanApprovalDecision(false, feedback)))

        val rejection = result.await()
        assertTrue(rejection is ToolResult.Error)
        rejection as ToolResult.Error
        assertEquals(SubmitPlanTool.REJECTED_CODE, rejection.code)
        assertEquals("Plan rejected: $feedback", rejection.message)
        assertTrue(rejection.message.contains(feedback))
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun rejectedPlan_withoutFeedbackStillReturnsRejection() = runTest {
        val result = async {
            tool.executeWithContext(mapOf("plan" to JsonPrimitive("# Plan")), context())
        }
        runCurrent()

        val pending = manager.pendingPlan.value!!
        assertTrue(manager.resolve(pending.id, PlanApprovalDecision(false)))

        assertEquals(
            ToolResult.Error("Plan rejected: ", SubmitPlanTool.REJECTED_CODE),
            result.await()
        )
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun eachInvocation_usesIndependentUuidAndIgnoresOldRequestId() = runTest {
        val sessionId = UUID.randomUUID().toString()
        val args = mapOf("plan" to JsonPrimitive("# Plan"))
        val firstResult = async { tool.executeWithContext(args, context(sessionId)) }
        runCurrent()

        val first = manager.pendingPlan.value!!
        assertEquals(sessionId, first.sessionId)
        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertNotEquals(sessionId, first.id)
        assertTrue(manager.resolve(first.id, PlanApprovalDecision(true)))
        assertTrue(firstResult.await() is ToolResult.Success)

        val secondResult = async { tool.executeWithContext(args, context(sessionId)) }
        runCurrent()

        val second = manager.pendingPlan.value!!
        assertEquals(second.id, UUID.fromString(second.id).toString())
        assertEquals(sessionId, second.sessionId)
        assertNotEquals(sessionId, second.id)
        assertNotEquals(first.id, second.id)
        assertFalse(manager.resolve(sessionId, PlanApprovalDecision(true)))
        assertFalse(manager.resolve(first.id, PlanApprovalDecision(false, "Stale feedback")))
        runCurrent()
        assertFalse(secondResult.isCompleted)
        assertEquals(second, manager.pendingForSession(sessionId))

        assertTrue(manager.resolve(second.id, PlanApprovalDecision(true)))
        assertTrue(secondResult.await() is ToolResult.Success)
        assertNull(manager.pendingPlan.value)
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun cancelledExecution_propagatesCancellationAndCleansPendingPlan() = runTest {
        val result = async {
            tool.executeWithContext(mapOf("plan" to JsonPrimitive("# Plan")), context())
        }
        runCurrent()
        val pending = manager.pendingPlan.value!!
        assertNotNull(manager.pendingForSession("session-1"))

        result.cancelAndJoin()

        assertTrue(result.isCancelled)
        assertNull(manager.pendingPlan.value)
        assertNull(manager.pendingForSession("session-1"))
        assertTrue(manager.awaitingSessionIds.value.isEmpty())
        assertFalse(manager.resolve(pending.id, PlanApprovalDecision(true)))
    }
}
