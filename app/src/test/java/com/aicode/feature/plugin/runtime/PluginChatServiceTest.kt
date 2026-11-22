package com.aicode.feature.plugin.runtime

import android.app.Application
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.plan.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.presentation.runtime.AgentRunManager
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PluginChatServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: PluginConversationFixture
    private lateinit var context: PluginInvocationContext
    private lateinit var service: PluginChatService
    private val rows = mutableMapOf<String, ChatSessionEntity>()
    private val sessionDao = mockk<ChatSessionDao>()
    private val messageDao = mockk<AgentMessageDao>()
    private val runs = mockk<AgentRunManager>()
    private val gateway = mockk<PluginHostGateway>()
    private val ui = PluginChatUiRequests()
    private var approved = true
    private val selected = ChatSessionEntity("chat-A", "Current", 1, 1, workspacePath = "/work")
    private val permissions = ToolPermissionManager(mockk())
    private val plans = PlanApprovalManager()
    private val questions = AskUserQuestionManager()

    @Before fun setup() = runBlocking<Unit> {
        fixture = PluginConversationFixture(temporary.root)
        fixture.install()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "work", mockk(), null, null) {}
        context = fixture.context(execution = execution)
        rows[selected.id] = selected
        rows["other"] = ChatSessionEntity("other", "Foreign", 1, 1, workspacePath = "/other")
        coEvery { sessionDao.getById(any()) } answers { rows[firstArg()] }
        coEvery { gateway.authorize(any(), any(), any(), PluginApprovalOperation.EXECUTE_TOOL, any(), any()) } coAnswers {
            firstArg<PluginInvocationContext>().checkCurrent()
            if (!approved) throw PluginHostException("USER_REJECTED", "Denied")
        }
        service = PluginChatService(RuntimeEnvironment.getApplication(), SessionUseCase(sessionDao, messageDao, mockk()),
            sessionDao, messageDao, mockk<DefaultModelSettingsRepository>(), mockk<ModelReasoningEffortRepository>(), runs, ui,
            permissions, questions, plans, Provider { gateway })
    }

    private fun params(id: String = "chat-A") = buildJsonObject { put("sessionId", id) }
    private suspend fun error(block: suspend () -> Any?): String = try { block(); throw AssertionError("Expected denial") }
        catch (failure: PluginHostException) { failure.code }

    @Test fun unknownAndForeignChatIdsAreNotVisibleInCapturedWorkspace() = runBlocking<Unit> {
        assertEquals("SESSION_NOT_FOUND", error { service.getSession(context, params("unknown")) })
        assertEquals("SESSION_NOT_FOUND", error { service.getSession(context, params("other")) })
        coVerify(exactly = 0) { gateway.authorize(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun deletionRequiresNativeApprovalBeforeExistingRuntimeIsTouched() = runBlocking<Unit> {
        approved = false
        assertEquals("USER_REJECTED", error { service.deleteSession(context, params()) })
        coVerify(exactly = 0) { runs.deleteSessions(any(), any()) }
        coVerify(exactly = 0) { sessionDao.delete(any()) }
    }

    @Test fun sessionGenerationFenceIsRecheckedAfterNativeApproval() = runBlocking<Unit> {
        coEvery { gateway.authorize(any(), any(), any(), any(), any(), any()) } coAnswers {
            rows[selected.id] = selected.copy(executionGeneration = selected.executionGeneration + 1)
        }
        assertEquals("CONTEXT_CHANGED", error { service.openSession(context, params()) })
        coVerify(exactly = 0) { runs.deleteSessions(any(), any()) }
    }

    @Test fun chatUiRequestsCarryCallerIdentityAndRequireAnAttachedNativeConsumer() = runBlocking<Unit> {
        assertEquals("CHAT_UI_UNAVAILABLE", error { service.openSession(context, params()) })
        val attachment = ui.attach()
        try {
            val operation = async(start = CoroutineStart.UNDISPATCHED) { service.openSession(context, params()).jsonObject }
            val request = withTimeout(5000) { ui.requests.first() }
            assertFalse(operation.isCompleted)
            assertEquals("/work", request.workspacePath)
            assertEquals("chat-A", request.callerSessionId)
            assertEquals(fixture.capture.id, request.pluginId)
            assertEquals(PluginChatUiAction.Open("chat-A"), request.action)
            request.checkCurrent()
            request.complete(true)
            val result = withTimeout(5000) { operation.await() }
            assertEquals("false", result.getValue("pendingNavigation").jsonPrimitive.content)
            try { request.checkCurrent(); fail("Completed request must not be replayed") } catch (_: kotlinx.coroutines.CancellationException) { }
        } finally { attachment.close() }
    }

    @Test fun revokedCallerSourceCancelsPendingDraftWithoutChangingTarget() = runBlocking<Unit> {
        val attachment = ui.attach()
        try {
            val operation = async(start = CoroutineStart.UNDISPATCHED) { runCatching {
                service.setDraft(context, buildJsonObject { put("sessionId", "chat-A"); put("text", "Draft"); put("append", true) })
            } }
            val request = withTimeout(5000) { ui.requests.first() }
            fixture.current = false
            fixture.changes.emit(Unit)
            request.complete(false)
            assertTrue(withTimeout(5000) { operation.await() }.isFailure)
            try { request.checkCurrent(); fail("Stale request must be rejected") } catch (_: kotlinx.coroutines.CancellationException) { }
        } finally { attachment.close() }
    }

    @Test fun pendingApprovalListingIsReadOnlyAndHasNoResolveMethods() = runBlocking<Unit> {
        val result = service.listApprovals(context, params()).jsonObject
        assertEquals("true", result.getValue("pendingNavigation").jsonPrimitive.content)
        val api = PluginConversationApi(mockk(), service, mockk(), mockk(), mockk())
        assertFalse(api.methods.contains("chat.resolveToolApproval"))
        assertFalse(api.methods.contains("chat.resolveQuestion"))
        assertFalse(api.methods.contains("chat.resolvePlanApproval"))
        assertEquals(33, api.methods.size)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun fullLengthChatTextUsesExactAuthorizationArgsAndBoundedApprovalDetails() = runBlocking<Unit> {
        val text = "正文".repeat(16_000)
        val changed = text.dropLast(1) + "异"
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val approvalDetails = mutableListOf<Pair<String, JsonObject>>()
        coEvery { gateway.authorize(any(), any(), any(), PluginApprovalOperation.EXECUTE_TOOL, any(), any()) } coAnswers {
            val caller = firstArg<PluginInvocationContext>()
            val method = secondArg<String>()
            val arguments = arg<JsonObject>(2)
            val details = arg<String>(4)
            caller.checkCurrent()
            assertEquals(method, details.substringBefore('\n'))
            assertEquals("chat-A", arguments.getValue("sessionId").jsonPrimitive.content)
            val body = arguments.getValue("text").jsonPrimitive.content
            assertEquals(32_000, body.length)
            assertTrue(body == text || body == changed)
            if (method == "chat.sendMessage") assertEquals(text, body)
            assertTrue(caller.approvalDetails(details).length <= PluginApprovalCoordinator.MAX_DETAILS)
            val display = kotlinx.serialization.json.Json.parseToJsonElement(details.substringAfter('\n')).jsonObject
            assertEquals("chat-A", display.getValue("sessionId").jsonPrimitive.content)
            assertEquals(32_000, display.getValue("text").jsonObject.getValue("length").jsonPrimitive.content.toInt())
            if (arguments.getValue("text").jsonPrimitive.content == text) {
                assertEquals(expectedHash, display.getValue("text").jsonObject.getValue("sha256").jsonPrimitive.content)
            }
            approvalDetails += method to display
        }
        every { runs.enqueueAgentRequest(text, projectRoot = "/work", targetSessionId = "chat-A") } returns Unit
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val attachment = ui.attach()
        try {
            val sendArgs = buildJsonObject { put("sessionId", "chat-A"); put("text", text) }
            service.sendMessage(context, sendArgs)
            verify(exactly = 1) { runs.enqueueAgentRequest(text, projectRoot = "/work", targetSessionId = "chat-A") }
            val drafts = listOf(text, changed)
            for (draft in drafts) {
                val operation = async(start = CoroutineStart.UNDISPATCHED) {
                    service.setDraft(context, buildJsonObject { put("sessionId", "chat-A"); put("text", draft); put("append", true) })
                }
                val request = withTimeout(5000) { ui.requests.first() }
                assertEquals(PluginChatUiAction.SetDraft("chat-A", draft, true), request.action)
                request.checkCurrent()
                request.complete(true)
                assertEquals("true", withTimeout(5000) { operation.await() }.jsonObject.getValue("accepted").jsonPrimitive.content)
            }
            assertEquals(listOf("chat.sendMessage", "chat.setDraft", "chat.setDraft"), approvalDetails.map { it.first })
            assertNotEquals(approvalDetails[1].second.getValue("text").jsonObject.getValue("sha256"),
                approvalDetails[2].second.getValue("text").jsonObject.getValue("sha256"))
        } finally { attachment.close(); Dispatchers.resetMain() }
    }

    @Test fun unknownPermissionFlagsAreNotPassedToChatRuntime() = runBlocking<Unit> {
        val args = buildJsonObject { put("sessionId", "chat-A"); put("text", "Run"); put("elevate", true) }
        assertEquals("PARAM_INVALID", error { service.sendMessage(context, args) })
    }
}
