package com.aicode.feature.agent.domain.tool.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TabInfo
import com.aicode.feature.terminal.domain.TerminalCloseResult
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionOwnerToolTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "origin-session")
    private val provider = OwnedProvider()
    private lateinit var tool: TerminalSessionTool

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.v(any(), any()) } just Runs
        val resolver = mockk<com.aicode.feature.terminal.domain.TerminalTaskScopeResolver>()
        coEvery { resolver.capture() } returns null
        tool = TerminalSessionTool(provider, mockk(), resolver)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(FileLogger)
    }

    @Test
    fun absentBlankAndInvalidCallerOwnersCannotUseAnyLiveTerminalPath() = runTest(dispatcher) {
        for (args in operations()) {
            assertError(tool.execute(args), "TERMINAL_OWNER_REQUIRED")
            for (session in listOf(null, "", " \t", "bad\nsession")) {
                assertError(tool.executeWithContext(args, context(session)), "TERMINAL_OWNER_REQUIRED")
                assertError(stream(args, session), "TERMINAL_OWNER_REQUIRED")
            }
        }
        assertTrue(provider.calls.isEmpty())
        for (invalid in listOf("", " ", "bad\nsession")) {
            assertTrue(runCatching { RuntimeOwner(RuntimeOwnerKind.AGENT, invalid) }.isFailure)
        }
    }

    @Test
    fun siblingParentOrTerminalNamedSessionCannotBorrowAnotherOwnersTabOrItsOutput() = runTest(dispatcher) {
        provider.tabs["term-1"] = TabInfo("term-1", "PRIVATE", true, true, "private-command", runtimeOwner = owner)
        provider.output["term-1"] = "PRIVATE-OUTPUT"
        val nativeOwner = RuntimeOwner(RuntimeOwnerKind.TERMINAL, "term-2")
        provider.tabs["term-2"] = TabInfo("term-2", "PRIVATE-UI", false, true, null, runtimeOwner = nativeOwner)
        provider.output["term-2"] = "PRIVATE-UI-OUTPUT"
        for (session in listOf("sibling-session", "parent-session", "terminal:term-1", "terminal:term-2")) {
            for (tab in listOf("term-1", "term-2")) {
                for (args in operations().filter { it["action"] != JsonPrimitive("start") }) {
                    val targeted = args + ("tab_id" to JsonPrimitive(tab))
                    assertTrue(tool.executeWithContext(targeted, context(session)) is ToolResult.Error)
                    assertTrue(stream(targeted, session) is ToolResult.Error)
                }
            }
            val listed = tool.executeWithContext(args("read"), context(session)) as ToolResult.Success
            assertFalse(listed.data.toString().contains("PRIVATE"))
        }
        assertTrue(provider.calls.isEmpty())
        assertEquals(setOf("term-1", "term-2"), provider.tabs.keys)
    }

    @Test
    fun startAndStreamingStartFreezeTheOriginalContextOwnerAndOldProotOutputShape() = runTest(dispatcher) {
        for (streaming in listOf(false, true)) {
            val result = if (streaming) stream(args("start", "command" to "printf actual"), owner.scopeId)
                else tool.executeWithContext(args("start", "command" to "printf actual"), context(owner.scopeId))
            assertTrue(result is ToolResult.Success)
            val data = (result as ToolResult.Success).data as JsonObject
            assertEquals(setOf("tab_id", "running", "output"), data.keys)
            assertEquals(JsonPrimitive("actual-output"), data["output"])
            val id = (data.getValue("tab_id") as JsonPrimitive).content
            assertEquals(owner, provider.tabs.getValue(id).runtimeOwner)
            assertEquals(owner.scopeId, provider.startedSessions.last())
        }
        assertEquals(2, provider.startedSessions.size)
    }

    @Test
    fun authorizationRequiredIsExplicitThroughBothExecutionPathsWithoutFallback() = runTest(dispatcher) {
        provider.requireAuthorization = true
        val args = args("start", "command" to "must-not-fallback")
        assertError(tool.executeWithContext(args, context(owner.scopeId)), "LOCAL_RUNTIME_AUTHORIZATION_REQUIRED")
        assertError(stream(args, owner.scopeId), "LOCAL_RUNTIME_AUTHORIZATION_REQUIRED")
        assertTrue(provider.tabs.isEmpty())
        assertEquals(listOf("start", "start"), provider.calls)
    }

    @Test
    fun unknownTaskExitAndPendingCleanupAreReturnedSeparatelyWithoutAnInventedExitCode() = runTest(dispatcher) {
        provider.tabs["term-1"] = TabInfo("term-1", "Task", true, false, "task", runtimeOwner = owner,
            taskStatus = "UNKNOWN", cleanupStatus = "PENDING", exitKnown = false)
        provider.output["term-1"] = "[command exited: 0]"
        val result = tool.executeWithContext(args("read", "tab_id" to "term-1"), context(owner.scopeId)) as ToolResult.Success
        val data = result.data as JsonObject
        assertEquals(JsonPrimitive("UNKNOWN"), data["task_status"])
        assertEquals(JsonPrimitive("PENDING"), data["cleanup_status"])
        assertEquals(JsonPrimitive(false), data["exit_known"])
        assertFalse(data.containsKey("exit_code"))
        provider.closeCleanup = LocalRuntimeCleanup.UNKNOWN
        val closed = tool.executeWithContext(args("close", "tab_id" to "term-1"), context(owner.scopeId)) as ToolResult.Success
        val closeData = closed.data as JsonObject
        assertEquals(JsonObject(mapOf("term-1" to JsonPrimitive("UNKNOWN"))), closeData["cleanup_status"])
        assertTrue(provider.tabs.isEmpty())
    }

    @Test
    fun ownedPlainProotAndSshReadAndControlKeepTheirExistingBehavior() = runTest(dispatcher) {
        provider.tabs["term-1"] = TabInfo("term-1", "Legacy", true, true, "shell", runtimeOwner = owner)
        provider.output["term-1"] = "legacy-output"
        val read = tool.executeWithContext(args("read", "tab_id" to "term-1"), context(owner.scopeId)) as ToolResult.Success
        assertEquals(JsonPrimitive("legacy-output"), read.data)
        assertTrue(tool.executeWithContext(args("key", "tab_id" to "term-1", "key" to "ctrl+c"), context(owner.scopeId)) is ToolResult.Success)
        assertTrue(tool.executeWithContext(args("close", "tab_id" to "term-1"), context(owner.scopeId)) is ToolResult.Success)
        assertEquals(listOf("read:term-1", "bytes:term-1", "close:term-1"), provider.calls)
    }

    private suspend fun stream(args: Map<String, JsonElement>, session: String?): ToolResult =
        (tool.executeStream(args, context(session)).toList().last() as ToolStreamEvent.Completed).result

    private fun assertError(result: ToolResult, code: String) {
        assertTrue("Expected $code, got $result", result is ToolResult.Error)
        assertEquals(code, (result as ToolResult.Error).code)
    }

    private fun operations(): List<Map<String, JsonElement>> = listOf(
        args("start", "command" to "must-not-start"),
        args("send", "tab_id" to "term-1", "input" to "must-not-send"),
        args("key", "tab_id" to "term-1", "key" to "ctrl+c"),
        args("read", "tab_id" to "term-1"),
        args("close", "tab_id" to "term-1")
    )

    private fun args(action: String, vararg fields: Pair<String, String>): Map<String, JsonElement> =
        mapOf("action" to JsonPrimitive(action)) + fields.associate { it.first to JsonPrimitive(it.second) }

    private fun context(session: String?) = AgentContext(null, null, "/workspace", null, sessionId = session)

    private class OwnedProvider : TerminalSessionProvider {
        val tabs = linkedMapOf<String, TabInfo>()
        val output = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()
        val startedSessions = mutableListOf<String?>()
        var requireAuthorization = false
        var closeCleanup: LocalRuntimeCleanup? = null
        override val tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>()
        override suspend fun startBackgroundCommand(command: String, title: String?, notify: Boolean, sourceSessionId: String?): String {
            calls += "start"
            if (requireAuthorization) throw LocalRuntimeAuthorizationRequired()
            startedSessions += sourceSessionId
            val id = "term-${tabs.size + 1}"
            tabs[id] = TabInfo(id, title ?: id, true, true, command,
                runtimeOwner = RuntimeOwner(RuntimeOwnerKind.AGENT, requireNotNull(sourceSessionId)))
            output[id] = "actual-output"
            return id
        }
        override fun listTabs(): List<TabInfo> = tabs.values.toList()
        override fun getTabOutput(id: String): String? { calls += "read:$id"; return output[id] }
        override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean { calls += "input:$id"; return id in tabs }
        override fun writeToTab(id: String, text: String): Boolean { calls += "text:$id"; return id in tabs }
        override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean { calls += "bytes:$id"; return id in tabs }
        override fun closeTab(id: String): Boolean { calls += "close:$id"; return tabs.remove(id) != null }
        override suspend fun closeOwnedTab(id: String, owner: RuntimeOwner): TerminalCloseResult? =
            if (listOwnedTabs(owner).any { it.id == id } && closeTab(id)) TerminalCloseResult(closeCleanup) else null
    }
}
