package com.aicode.feature.agent.domain.tool.container

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.terminal.data.repository.TerminalTaskHistoryRepository
import com.aicode.feature.terminal.domain.TabInfo
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskScope
import com.aicode.feature.terminal.domain.TerminalTaskScopeResolver
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTaskHistoryToolTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TerminalTaskScope(
        profileId = "local-profile",
        rootfsSourceIdentity = "a".repeat(64),
        rootfsPath = "/data/rootfs",
        rootfsIdentity = "rootfs-device:inode:installed",
        workspaceRoot = "/workspace",
        workspaceIdentity = "workspace-device:inode"
    )
    private lateinit var provider: TerminalSessionProvider
    private lateinit var history: TerminalTaskHistoryRepository
    private lateinit var resolver: TerminalTaskScopeResolver
    private lateinit var tool: TerminalSessionTool

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        provider = mockk()
        history = mockk()
        resolver = mockk()
        coEvery { resolver.capture() } returns scope
        tool = TerminalSessionTool(provider, history, resolver)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun finishedHistoryReturnsTheRecordedZeroAndNonzeroExitCodes() = runTest(dispatcher) {
        for (exitCode in listOf(0, 23)) {
            val record = record(status = TerminalTaskStatus.FINISHED, exitCode = exitCode)
            stubOwnedRecord(record)

            for (entry in EntryPoint.entries) {
                assertSnapshot(invoke(entry, historyArgs()), record)
            }
        }

        coVerify(exactly = 4) { history.read(HISTORY_ID, scope, SESSION_ID) }
        coVerify(exactly = 0) { history.list(any(), any()) }
        verify { provider wasNot Called }
    }

    @Test
    fun unknownHistoryKeepsTheRawSnapshotWithoutInventingAnExitCodeOrLiveTab() = runTest(dispatcher) {
        val record = record()
        stubOwnedRecord(record)

        for (entry in EntryPoint.entries) {
            val data = assertSnapshot(invoke(entry, historyArgs()), record)
            assertEquals(JsonPrimitive("UNKNOWN"), data["status"])
            assertFalse(data.containsKey("exit_code"))
            assertFalse(data.containsKey("running"))
            assertFalse(data.containsKey("tab_id"))
            assertTrue(data.getValue("snapshot_note").jsonPrimitive.content.contains("未记录退出码不代表成功"))
        }

        coVerify(exactly = 2) { history.read(HISTORY_ID, scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun historyFilterSelectsMatchingLinesAndKeepsUnknownStatus() = runTest(dispatcher) {
        val record = record()
        stubOwnedRecord(record)

        for (entry in EntryPoint.entries) {
            assertSnapshot(
                invoke(entry, historyArgs("filter" to "(?i)^error:")),
                record,
                output = "error: first\nERROR: last"
            )
        }

        verify { provider wasNot Called }
    }

    @Test
    fun unmatchedHistoryFilterReportsTheOriginalLineCountRatherThanAnEmptyOutput() = runTest(dispatcher) {
        val record = record()
        stubOwnedRecord(record)

        for (entry in EntryPoint.entries) {
            assertSnapshot(
                invoke(entry, historyArgs("filter" to "NEVER_MATCH")),
                record,
                output = "没有匹配 NEVER_MATCH 的行（原输出共 5 行）。"
            )
        }

        verify { provider wasNot Called }
    }

    @Test
    fun missingOrBlankSourceSessionRejectsHistoryBeforeResolvingScopeOrReadingStorage() = runTest(dispatcher) {
        for (sessionId in listOf(null, "", " \t")) {
            for (entry in EntryPoint.entries) {
                assertError(
                    invoke(entry, historyArgs(), context(sessionId)),
                    "TERMINAL_HISTORY_UNAVAILABLE"
                )
            }
        }

        verifyNoHistoryAccess()
        verify { resolver wasNot Called }
        verify { provider wasNot Called }
    }

    @Test
    fun missingLocalScopeRejectsHistoryWithoutReadingStorage() = runTest(dispatcher) {
        coEvery { resolver.capture() } returns null

        for (entry in EntryPoint.entries) {
            assertError(invoke(entry, historyArgs()), "TERMINAL_HISTORY_UNAVAILABLE")
        }

        coVerify(exactly = 2) { resolver.capture() }
        verifyNoHistoryAccess()
        verify { provider wasNot Called }
    }

    @Test
    fun historyFromAnotherSessionIsNotReturnedWhenTheScopedReadIsNull() = runTest(dispatcher) {
        val privateRecord = record(output = "PRIVATE_OTHER_SESSION_OUTPUT")
        stubOwnedRecord(privateRecord)

        for (entry in EntryPoint.entries) {
            val result = invoke(entry, historyArgs(), context("another-session"))
            val error = assertError(result, "TERMINAL_HISTORY_NOT_FOUND")
            assertFalse(error.message.contains(privateRecord.output))
        }

        coVerify(exactly = 2) { history.read(HISTORY_ID, scope, "another-session") }
        coVerify(exactly = 0) { history.read(HISTORY_ID, scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun historyFromAnotherWorkspaceRootfsOrProfileIsNotReturnedWhenTheScopedReadIsNull() = runTest(dispatcher) {
        val privateRecord = record(output = "PRIVATE_OTHER_SCOPE_OUTPUT")
        stubOwnedRecord(privateRecord)
        val foreignScopes = listOf(
            scope.copy(workspaceRoot = "/another-workspace"),
            scope.copy(workspaceIdentity = "replacement-workspace"),
            scope.copy(rootfsPath = "/another-rootfs"),
            scope.copy(rootfsIdentity = "replacement-rootfs"),
            scope.copy(rootfsSourceIdentity = "b".repeat(64)),
            scope.copy(profileId = "another-profile")
        )

        for (foreignScope in foreignScopes) {
            coEvery { resolver.capture() } returns foreignScope
            for (entry in EntryPoint.entries) {
                val error = assertError(invoke(entry, historyArgs()), "TERMINAL_HISTORY_NOT_FOUND")
                assertFalse(error.message.contains(privateRecord.output))
            }
            coVerify(exactly = 2) { history.read(HISTORY_ID, foreignScope, SESSION_ID) }
        }

        coVerify(exactly = 0) { history.read(HISTORY_ID, scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun scopeChangeDuringHistoryReadDiscardsTheAlreadyReadSnapshot() = runTest(dispatcher) {
        val record = record(output = "PRIVATE_PREVIOUS_SCOPE_OUTPUT")
        stubOwnedRecord(record)
        val changed = scope.copy(workspaceIdentity = "replacement-workspace")

        for (entry in EntryPoint.entries) {
            coEvery { resolver.capture() } returnsMany listOf(scope, changed)
            val error = assertError(invoke(entry, historyArgs()), "TERMINAL_HISTORY_UNAVAILABLE")
            assertFalse(error.message.contains(record.output))
        }

        coVerify(exactly = 2) { history.read(HISTORY_ID, scope, SESSION_ID) }
        coVerify(exactly = 4) { resolver.capture() }
        verify { provider wasNot Called }
    }

    @Test
    fun structurallyEqualRecapturedScopesStillAllowTheSnapshot() = runTest(dispatcher) {
        val record = record()
        stubOwnedRecord(record)

        for (entry in EntryPoint.entries) {
            coEvery { resolver.capture() } returnsMany listOf(scope.copy(), scope.copy())
            assertSnapshot(invoke(entry, historyArgs()), record)
        }

        coVerify(exactly = 4) { resolver.capture() }
        verify { provider wasNot Called }
    }

    @Test
    fun readWithoutTargetsListsLiveTabsAndClearlySeparatesUnknownReadOnlyHistory() = runTest(dispatcher) {
        val unknown = record(title = "Unverified task")
        val finished = record(
            historyId = FINISHED_HISTORY_ID,
            title = "Failed task",
            status = TerminalTaskStatus.FINISHED,
            exitCode = 7
        )
        val running = record(historyId = RUNNING_HISTORY_ID, title = "Running snapshot", status = TerminalTaskStatus.RUNNING)
        every { provider.listTabs() } returns listOf(
            TabInfo("term-live", "Live shell", isBackground = false, running = true, command = null),
            TabInfo("term-done", "Completed live tab", isBackground = true, running = false, command = "echo done", historyId = FINISHED_HISTORY_ID)
        )
        coEvery { history.list(scope, SESSION_ID) } returns listOf(unknown, finished, running)

        for (entry in EntryPoint.entries) {
            val text = successText(invoke(entry, args()))
            assertTrue(text.contains("term-live  [交互/运行中]  Live shell"))
            assertTrue(text.contains("term-done  [后台/已结束]  Completed live tab  (echo done)  history_id=$FINISHED_HISTORY_ID"))
            assertTrue(text.contains("任务历史（只读，不能作为活终端操作）"))
            val unknownLine = text.lineSequence().single { it.startsWith("history_id=$HISTORY_ID ") }
            assertTrue(unknownLine.contains("[UNKNOWN]  Unverified task"))
            assertTrue(unknownLine.contains("未记录退出码；需核验，不能认定完成"))
            assertFalse(unknownLine.contains("exit_code="))
            assertFalse(unknownLine.contains("已结束"))
            assertTrue(text.contains("history_id=$FINISHED_HISTORY_ID  [FINISHED]  Failed task  exit_code=7"))
            assertTrue(text.contains("history_id=$RUNNING_HISTORY_ID  [RUNNING]  Running snapshot"))
            assertFalse(text.contains(unknown.output))
        }

        coVerify(exactly = 2) { history.list(scope, SESSION_ID) }
        coVerify(exactly = 0) { history.read(any(), any(), any()) }
        verify(exactly = 2) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun listingWithoutSessionStillListsLiveTabsButDoesNotAccessHistory() = runTest(dispatcher) {
        every { provider.listTabs() } returns emptyList()

        for (sessionId in listOf(null, "", " \t")) {
            for (entry in EntryPoint.entries) {
                assertEquals("当前没有终端标签。", successText(invoke(entry, args(), context(sessionId))))
            }
        }

        verifyNoHistoryAccess()
        verify { resolver wasNot Called }
        verify(exactly = 6) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun listingOutsideLocalScopeStillListsLiveTabsWithoutReadingHistory() = runTest(dispatcher) {
        every { provider.listTabs() } returns emptyList()
        coEvery { resolver.capture() } returns null

        for (entry in EntryPoint.entries) {
            assertEquals("当前没有终端标签。", successText(invoke(entry, args())))
        }

        verifyNoHistoryAccess()
        verify(exactly = 2) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun scopeChangeDuringListingRejectsBothThePreviousScopesLiveTabsAndHistory() = runTest(dispatcher) {
        val liveTitle = "PRIVATE_PREVIOUS_LIVE_TITLE"
        every { provider.listTabs() } returns listOf(
            TabInfo("term-previous", liveTitle, isBackground = true, running = false, command = null, historyId = HISTORY_ID)
        )
        val record = record(title = "PRIVATE_PREVIOUS_SCOPE_TITLE")
        coEvery { history.list(scope, SESSION_ID) } returns listOf(record)

        for (entry in EntryPoint.entries) {
            coEvery { resolver.capture() } returnsMany listOf(scope, scope.copy(rootfsIdentity = "new-rootfs"))
            val error = assertError(invoke(entry, args()), "TERMINAL_HISTORY_UNAVAILABLE")
            assertFalse(error.message.contains(liveTitle))
            assertFalse(error.message.contains("term-previous"))
            assertFalse(error.message.contains(record.title))
            assertFalse(error.message.contains(HISTORY_ID))
        }

        coVerify(exactly = 2) { history.list(scope, SESSION_ID) }
        coVerify(exactly = 4) { resolver.capture() }
        verify(exactly = 2) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun historyIdCannotStartSendSendKeysOrCloseThroughEitherPublicEntryPoint() = runTest(dispatcher) {
        val mutations = listOf(
            args("start", "command" to "echo must-not-run") + ("notify" to JsonPrimitive(true)),
            args("send", "tab_id" to "term-live", "input" to "must-not-send"),
            args("key", "tab_id" to "term-live", "key" to "ctrl+c"),
            args("close", "tab_id" to "term-live") +
                ("tab_ids" to JsonArray(listOf(JsonPrimitive("term-other"))))
        )

        for (historyId in listOf(HISTORY_ID, " ")) {
            for (mutation in mutations) {
                for (entry in EntryPoint.entries) {
                    assertError(
                        invoke(entry, mutation + ("history_id" to JsonPrimitive(historyId))),
                        "TERMINAL_HISTORY_READ_ONLY"
                    )
                }
            }
        }

        verify { provider wasNot Called }
        verify { resolver wasNot Called }
        verifyNoHistoryAccess()
    }

    @Test
    fun aHistoryUuidPassedAsTabIdDoesNotFallBackToTheHistoryRepository() = runTest(dispatcher) {
        every { provider.getTabOutput(HISTORY_ID) } returns null
        stubOwnedRecord(record(output = "PRIVATE_HISTORY_OUTPUT"))

        for (entry in EntryPoint.entries) {
            val error = assertError(invoke(entry, args("read", "tab_id" to HISTORY_ID)), "UNKNOWN")
            assertTrue(error.message.contains("未找到终端标签"))
            assertFalse(error.message.contains("PRIVATE_HISTORY_OUTPUT"))
        }

        verify(exactly = 2) { provider.getTabOutput(HISTORY_ID) }
        confirmVerified(provider)
        verify { resolver wasNot Called }
        verifyNoHistoryAccess()
    }

    @Test
    fun aLiveTabReadRemainsALiveReadWithoutHistoryAccess() = runTest(dispatcher) {
        every { provider.getTabOutput("term-live") } returns RAW_OUTPUT

        for (entry in EntryPoint.entries) {
            assertEquals(RAW_OUTPUT, successText(invoke(entry, args("read", "tab_id" to "term-live"))))
        }

        verify(exactly = 2) { provider.getTabOutput("term-live") }
        confirmVerified(provider)
        verify { resolver wasNot Called }
        verifyNoHistoryAccess()
    }

    @Test
    fun simultaneousTabAndHistoryTargetsAreRejectedBeforeReadingEither() = runTest(dispatcher) {
        for (entry in EntryPoint.entries) {
            assertError(
                invoke(entry, historyArgs("tab_id" to "term-live")),
                "AMBIGUOUS_TERMINAL_TARGET"
            )
        }

        verify { provider wasNot Called }
        verify { resolver wasNot Called }
        verifyNoHistoryAccess()
    }

    @Test
    fun invalidFilterIsRejectedBeforeAccessingHistory() = runTest(dispatcher) {
        for (entry in EntryPoint.entries) {
            assertError(invoke(entry, historyArgs("filter" to "[")), "INVALID_REGEX")
        }

        verify { provider wasNot Called }
        verify { resolver wasNot Called }
        verifyNoHistoryAccess()
    }

    @Test
    fun blankOrNullHistoryIdIsNotTreatedAsARequestToListTabs() = runTest(dispatcher) {
        for (historyId in listOf(JsonPrimitive(""), JsonPrimitive(" \t\n"), JsonNull)) {
            for (entry in EntryPoint.entries) {
                assertError(invoke(entry, args() + ("history_id" to historyId)), "INVALID_HISTORY_ID")
            }
        }

        verify { provider wasNot Called }
        verify { resolver wasNot Called }
        verifyNoHistoryAccess()
    }

    @Test
    fun repositoryHistoryIdValidationFailureIsReportedAsInvalidHistory() = runTest(dispatcher) {
        coEvery { history.read("not-a-history-uuid", scope, SESSION_ID) } throws IllegalArgumentException("invalid uuid")

        for (entry in EntryPoint.entries) {
            assertError(
                invoke(entry, args("read", "history_id" to "not-a-history-uuid")),
                "INVALID_HISTORY_ID"
            )
        }

        coVerify(exactly = 2) { history.read("not-a-history-uuid", scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun historyStorageFailureIsAnErrorRatherThanAnEmptySuccessfulSnapshot() = runTest(dispatcher) {
        coEvery { history.read(HISTORY_ID, scope, SESSION_ID) } throws IOException("PRIVATE_STORAGE_PATH")

        for (entry in EntryPoint.entries) {
            val error = assertError(invoke(entry, historyArgs()), "TERMINAL_HISTORY_READ_FAILED")
            assertTrue(error.message.contains("不能据此判断执行结果"))
            assertFalse(error.message.contains("PRIVATE_STORAGE_PATH"))
        }

        coVerify(exactly = 2) { history.read(HISTORY_ID, scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun historyListingStorageFailureExplicitlyReportsUnknownResultsRatherThanAnEmptyList() = runTest(dispatcher) {
        every { provider.listTabs() } returns emptyList()
        coEvery { history.list(scope, SESSION_ID) } throws IOException("PRIVATE_STORAGE_PATH")

        for (entry in EntryPoint.entries) {
            val text = successText(invoke(entry, args()))
            assertTrue(text.startsWith("当前没有终端标签。"))
            assertTrue(text.contains("任务历史读取失败"))
            assertTrue(text.contains("历史是否存在及执行结果未知"))
            assertFalse(text.contains("PRIVATE_STORAGE_PATH"))
        }

        coVerify(exactly = 2) { history.list(scope, SESSION_ID) }
        verify(exactly = 2) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun streamingHistoryReadPreservesTheOriginalContextSessionId() = runTest(dispatcher) {
        val record = record(sourceSessionId = "stream-origin-session")
        stubOwnedRecord(record)

        assertSnapshot(
            invoke(EntryPoint.STREAM, historyArgs(), context("stream-origin-session")),
            record
        )

        coVerify(exactly = 1) { history.read(HISTORY_ID, scope, "stream-origin-session") }
        coVerify(exactly = 0) { history.read(HISTORY_ID, scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun streamingHistoryListingPreservesTheOriginalContextSessionId() = runTest(dispatcher) {
        every { provider.listTabs() } returns emptyList()
        coEvery { history.list(scope, "stream-origin-session") } returns listOf(record(sourceSessionId = "stream-origin-session"))

        val text = successText(invoke(EntryPoint.STREAM, args(), context("stream-origin-session")))

        assertTrue(text.contains("history_id=$HISTORY_ID  [UNKNOWN]"))
        coVerify(exactly = 1) { history.list(scope, "stream-origin-session") }
        coVerify(exactly = 0) { history.list(scope, SESSION_ID) }
        verify(exactly = 1) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun historyReadCancellationPreservesItsOriginalCauseThroughBothEntryPoints() = runTest(dispatcher) {
        val cancellation = CancellationException("history read cancelled")
        coEvery { history.read(HISTORY_ID, scope, SESSION_ID) } throws cancellation

        for (entry in EntryPoint.entries) {
            assertCancellation(cancellation) { invoke(entry, historyArgs()) }
        }

        coVerify(exactly = 2) { history.read(HISTORY_ID, scope, SESSION_ID) }
        verify { provider wasNot Called }
    }

    @Test
    fun historyListingCancellationPreservesItsOriginalCauseThroughBothEntryPoints() = runTest(dispatcher) {
        val cancellation = CancellationException("history list cancelled")
        every { provider.listTabs() } returns emptyList()
        coEvery { history.list(scope, SESSION_ID) } throws cancellation

        for (entry in EntryPoint.entries) {
            assertCancellation(cancellation) { invoke(entry, args()) }
        }

        coVerify(exactly = 2) { history.list(scope, SESSION_ID) }
        verify(exactly = 2) { provider.listTabs() }
        confirmVerified(provider)
    }

    @Test
    fun scopeCaptureCancellationPropagatesInsteadOfBecomingAnUnavailableHistoryError() = runTest(dispatcher) {
        val cancellation = CancellationException("scope capture cancelled")
        coEvery { resolver.capture() } throws cancellation

        for (entry in EntryPoint.entries) {
            assertCancellation(cancellation) { invoke(entry, historyArgs()) }
        }

        verifyNoHistoryAccess()
        verify { provider wasNot Called }
    }

    @Test
    fun postReadScopeCaptureCancellationDiscardsTheSnapshotAndPreservesItsOriginalCause() = runTest(dispatcher) {
        val cancellation = CancellationException("scope recheck cancelled")
        coEvery { history.read(HISTORY_ID, scope, SESSION_ID) } coAnswers {
            coEvery { resolver.capture() } throws cancellation
            record()
        }

        for (entry in EntryPoint.entries) {
            coEvery { resolver.capture() } returns scope
            assertCancellation(cancellation) { invoke(entry, historyArgs()) }
        }

        coVerify(exactly = 2) { history.read(HISTORY_ID, scope, SESSION_ID) }
        coVerify(exactly = 4) { resolver.capture() }
        verify { provider wasNot Called }
    }

    private suspend fun invoke(
        entry: EntryPoint,
        args: Map<String, JsonElement>,
        context: AgentContext = context()
    ): ToolResult = when (entry) {
        EntryPoint.CONTEXT -> tool.executeWithContext(args, context)
        EntryPoint.STREAM -> {
            val events = tool.executeStream(args, context).toList()
            assertEquals("Read-only requests must emit exactly one final event", 1, events.size)
            assertTrue(events.single() is ToolStreamEvent.Completed)
            (events.single() as ToolStreamEvent.Completed).result
        }
    }

    private fun stubOwnedRecord(record: TerminalTaskRecord) {
        coEvery { history.read(record.historyId, any(), any()) } coAnswers {
            val requestedScope = secondArg<TerminalTaskScope>()
            val requestedSession = thirdArg<String>()
            record.takeIf { it.scope == requestedScope && it.sourceSessionId == requestedSession }
        }
    }

    private fun verifyNoHistoryAccess() {
        coVerify(exactly = 0) { history.read(any(), any(), any()) }
        coVerify(exactly = 0) { history.list(any(), any()) }
    }

    private fun assertSnapshot(
        result: ToolResult,
        record: TerminalTaskRecord,
        output: String = record.output
    ): JsonObject {
        assertTrue("Expected a successful snapshot, got $result", result is ToolResult.Success)
        val data = (result as ToolResult.Success).data
        assertTrue("Expected structured history data, got $data", data is JsonObject)
        val snapshot = data as JsonObject
        assertEquals(JsonPrimitive(record.historyId), snapshot["history_id"])
        assertEquals(JsonPrimitive(true), snapshot["read_only"])
        assertEquals(JsonPrimitive(record.status.name), snapshot["status"])
        assertEquals(JsonPrimitive(record.title), snapshot["title"])
        assertEquals(JsonPrimitive(record.startedAt), snapshot["started_at"])
        assertEquals(JsonPrimitive(record.updatedAt), snapshot["updated_at"])
        assertEquals(JsonPrimitive(output), snapshot["output"])
        if (record.exitCode == null) {
            assertFalse(snapshot.containsKey("exit_code"))
        } else {
            assertEquals(JsonPrimitive(record.exitCode), snapshot["exit_code"])
        }
        assertFalse(snapshot.containsKey("tab_id"))
        assertFalse(snapshot.containsKey("running"))
        return snapshot
    }

    private fun assertError(result: ToolResult, code: String): ToolResult.Error {
        assertTrue("Expected $code, got $result", result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals(code, error.code)
        assertTrue(error.message.isNotBlank())
        return error
    }

    private fun successText(result: ToolResult): String {
        assertTrue("Expected a successful text result, got $result", result is ToolResult.Success)
        val data = (result as ToolResult.Success).data
        assertTrue("Expected a text result, got $data", data is JsonPrimitive)
        return data.jsonPrimitive.content
    }

    private suspend fun assertCancellation(expected: CancellationException, block: suspend () -> Unit) {
        try {
            block()
            fail("CancellationException must propagate with its original cause")
        } catch (actual: CancellationException) {
            assertEquals(expected.javaClass, actual.javaClass)
            assertEquals(expected.message, actual.message)
            // 协程栈恢复可能复制异常；原始取消实例仍须保留在原因链中。
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === expected })
        }
    }

    private fun args(action: String = "read", vararg fields: Pair<String, String>): Map<String, JsonElement> =
        mapOf("action" to JsonPrimitive(action)) + fields.associate { (name, value) -> name to JsonPrimitive(value) }

    private fun historyArgs(vararg fields: Pair<String, String>): Map<String, JsonElement> =
        args("read", "history_id" to HISTORY_ID, *fields)

    private fun context(sessionId: String? = SESSION_ID): AgentContext = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "/workspace",
        language = null,
        sessionId = sessionId,
        inputMessageId = "history-tool-test-input"
    )

    private fun record(
        historyId: String = HISTORY_ID,
        title: String = "Saved task",
        sourceSessionId: String = SESSION_ID,
        status: TerminalTaskStatus = TerminalTaskStatus.UNKNOWN,
        output: String = RAW_OUTPUT,
        exitCode: Int? = null
    ): TerminalTaskRecord = TerminalTaskRecord(
        historyId = historyId,
        tabId = "term-$historyId",
        title = title,
        sourceSessionId = sourceSessionId,
        scope = scope,
        startedAt = 100L,
        updatedAt = 200L,
        finishedAt = 200L.takeIf { status == TerminalTaskStatus.FINISHED },
        status = status,
        output = output,
        outputRevision = 4L,
        exitCode = exitCode
    )

    private enum class EntryPoint { CONTEXT, STREAM }

    private companion object {
        const val SESSION_ID = "session1"
        const val HISTORY_ID = "11111111-1111-4111-8111-111111111111"
        const val FINISHED_HISTORY_ID = "22222222-2222-4222-8222-222222222222"
        const val RUNNING_HISTORY_ID = "33333333-3333-4333-8333-333333333333"
        const val RAW_OUTPUT = "INFO begin\r\nerror: first\r\nnoise\rERROR: last\ntrailing"
    }
}
