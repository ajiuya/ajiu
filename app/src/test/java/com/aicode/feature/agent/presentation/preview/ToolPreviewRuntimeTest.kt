package com.aicode.feature.agent.presentation.preview

import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolPreviewRuntimeTest {
    @Test fun historyUsesRealStartThenTimestampAndIdTieBreakWithoutChangingOriginalRecords() {
        val messages = listOf(
            AgentUIMessage("b", MessageRole.TOOL, "done", timestamp = 300, toolName = "Bash", toolStartedAtMs = 100),
            AgentUIMessage("a", MessageRole.TOOL, "done", timestamp = 100, toolName = "Bash"),
            AgentUIMessage("c", MessageRole.TOOL, "done", timestamp = 200, toolName = "Bash"),
        )
        val history = buildToolPreviewHistory("parent", messages)
        assertEquals(listOf("a", "b", "c"), history.targets.map { it.id })
        assertTrue(history.targets.all { it.sourceSessionId == "parent" })
        assertEquals(300, messages.first().timestamp.toInt())
        assertEquals(history.targets, overlayToolPreviewRunning(history, listOf(RunningToolOutput("b", "stale", "Root"))))
    }

    @Test fun runningOverlayDoesNotRebuildHistoryOrImportChildRoutingFromTaskResults() {
        val messages = listOf(
            AgentUIMessage("task", MessageRole.TOOL, """{"status":"success","data":{"id":"child","toolName":"Bash"}}""",
                timestamp = 1, toolName = "task", toolArgs = "{}"),
            AgentUIMessage("run", MessageRole.TOOL, SessionUseCase.PENDING_TOOL_MARKER, timestamp = 2, toolName = "Bash", toolArgs = "{}"),
        )
        val history = buildToolPreviewHistory("parent", messages)
        val overlaid = overlayToolPreviewRunning(history, listOf(RunningToolOutput("run", "live output", "Bash", "{}")))
        assertSame(history.targets.first(), overlaid.first())
        assertEquals("task", overlaid.first().name)
        assertEquals(2, overlaid.size)
        assertEquals("live output", overlaid.last().content)
        val preparing = overlayToolPreviewPreparing(overlaid, listOf(PreparingToolPreviewSnapshot("run", "Bash", "{}"),
            PreparingToolPreviewSnapshot("draft", "writeFile", "{")), "parent")
        assertEquals(listOf("task", "run", "draft"), preparing.map { it.id })
        assertTrue(preparing.all { it.sourceSessionId == "parent" })
        assertEquals(ToolPreviewStage.PREPARING, preparing.last().stage)
    }

    @Test fun retainedHistoryAlwaysReadsSelectedCallsSafetyAndRoutingFromCurrentInput() {
        val original = AgentUIMessage("selected", MessageRole.TOOL, "public output", toolName = "readFile",
            toolArgs = """{"path":"/work/public.txt"}""")
        val history = buildToolPreviewHistory("parent", listOf(original))
        val selected = history.targets.single()
        val sensitive = original.copy(toolArgs = """{"path":"/work/.env"}""", content = "unlabelled private result")
        val current = requireNotNull(currentToolPreviewTarget(selected, listOf(sensitive), emptyList(), emptyList(), "parent"))
        assertTrue(hidesPreviewBody(current))
        assertEquals("", buildToolPreviewDisplay(current).result)
        assertEquals("public output", selected.content)
        assertNull(currentToolPreviewTarget(selected, emptyList(), emptyList(), emptyList(), "parent"))
        assertNull(currentToolPreviewTarget(selected, listOf(original), emptyList(), emptyList(), "child"))
        val changedTool = original.copy(toolName = "Bash", toolArgs = """{"command":"pwd"}""")
        assertEquals("Bash", currentToolPreviewTarget(selected, listOf(changedTool), emptyList(), emptyList(), "parent")!!.name)
    }

    @Test fun currentPersistedCallsEnterNavigationBeforeTheFirstOrNextHistoryBuild() {
        val older = AgentUIMessage("older", MessageRole.TOOL, "older output", timestamp = 1, toolName = "Bash")
        val latest = older.copy(id = "call", content = "latest output", timestamp = 2, toolActionTitle = "Latest action")
        for (initial in listOf(emptyList(), listOf(older))) {
            val history = buildToolPreviewHistory("session", initial)
            val targets = retainToolPreviewNavigation(history.targets, history.targets, initial + latest, emptyList(), emptyList(), "session")
            assertEquals(initial.map { it.id } + latest.id, targets.map { it.id })
            assertEquals(latest.id, targets[previewSelectedIndex(targets, null, true)].id)
            assertEquals("Latest action", targets.last().actionTitle)
            assertTrue(targets.all { it.sourceSessionId == "session" })
        }
    }

    @Test fun retainedPreparingCallsAdoptTheirCurrentStageBeforeHistoryCatchesUp() {
        val older = AgentUIMessage("older", MessageRole.TOOL, "older output", timestamp = 1, toolName = "Bash")
        val pending = older.copy(id = "call", timestamp = 2, content = SessionUseCase.PENDING_TOOL_MARKER,
            toolArgs = """{"command":"pwd"}""")
        val history = buildToolPreviewHistory("session", listOf(older))
        val draft = PreparingToolPreviewSnapshot("call", "Bash", pending.toolArgs.orEmpty())
        val previous = overlayToolPreviewPreparing(history.targets, listOf(draft), "session")
        val pendingTargets = retainToolPreviewNavigation(previous, history.targets, listOf(older, pending), emptyList(), emptyList(), "session")
        assertEquals(ToolPreviewStage.RUNNING, pendingTargets.last().stage)
        val finished = pending.copy(content = "finished output")
        val finishedTargets = retainToolPreviewNavigation(pendingTargets, history.targets, listOf(older, finished), emptyList(), emptyList(), "session")
        assertEquals(listOf("older", "call"), finishedTargets.map { it.id })
        assertEquals(ToolPreviewStage.COMPLETED, finishedTargets.last().stage)
        val failedTargets = retainToolPreviewNavigation(finishedTargets, history.targets, listOf(older, finished.copy(isError = true)), emptyList(), emptyList(), "session")
        assertEquals(ToolPreviewStage.FAILED, failedTargets.last().stage)
    }

    @Test fun retainedNavigationImmediatelyDropsDeletedFilteredAndForeignSessionCalls() {
        val message = AgentUIMessage("call", MessageRole.TOOL, "public output", toolName = "Bash", toolArgs = "{}")
        val history = buildToolPreviewHistory("session", listOf(message))
        for (messages in listOf(emptyList(), listOf(message.copy(role = MessageRole.ASSISTANT)),
            listOf(message.copy(toolName = "")), listOf(message.copy(isCompactionFailure = true)))) {
            assertTrue(retainToolPreviewNavigation(history.targets, history.targets, messages, emptyList(), emptyList(), "session").isEmpty())
        }
        val next = retainToolPreviewNavigation(history.targets, history.targets, listOf(message.copy(content = "next output")),
            emptyList(), emptyList(), "next session")
        assertEquals("next session", next.single().sourceSessionId)
        assertEquals("next output", next.single().content)
        assertNotSame(history.targets.single(), next.single())
    }

    @Test fun absenceFromAllCurrentSourcesCannotBeBridgedWithAnOldDraftOrSensitiveBody() {
        val draft = PreparingToolPreviewSnapshot("call", "writeFile", """{"path":"/work/a.kt","content":"public source"}""")
        val previous = overlayToolPreviewPreparing(emptyList(), listOf(draft), "session")
        assertTrue(retainToolPreviewNavigation(previous, emptyList(), emptyList(), emptyList(), emptyList(), "session").isEmpty())
        assertNull(currentToolPreviewTarget(previous.single(), emptyList(), emptyList(), emptyList(), "session"))
        val sensitive = AgentUIMessage("call", MessageRole.TOOL, "private output", toolName = draft.name,
            toolArgs = """{"path":"/work/.env","content":"private source"}""", toolActionTitle = "Must stay hidden")
        val targets = retainToolPreviewNavigation(previous, emptyList(), listOf(sensitive), emptyList(), emptyList(), "session")
        val current = requireNotNull(currentToolPreviewTarget(targets.single(), listOf(sensitive), emptyList(), emptyList(), "session"))
        assertTrue(hidesPreviewBody(current))
        assertNull(safePreviewActionTitle(current))
        assertFalse(buildToolPreviewDisplay(current).copyText.contains("public source"))
        assertFalse(buildToolPreviewDisplay(current).copyText.contains("private source"))
    }

    @Test fun identityChangesAreImmediateWhileContinuousOutputFlushesAt500MsWithoutDebounceStarvation() = runTest {
        val source = MutableSharedFlow<List<RunningToolOutput>>()
        val seen = mutableListOf<List<RunningToolOutput>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.coalesceToolPreviewContent().collect { seen += it }
        }
        fun tool(text: String, args: String = "{}") = listOf(RunningToolOutput("id", text, "Bash", args))
        source.emit(tool("first")); runCurrent()
        assertEquals("first", seen.last().single().text)
        repeat(4) { index -> advanceTimeBy(100); source.emit(tool("update-$index")); runCurrent() }
        assertEquals(1, seen.size)
        advanceTimeBy(199); runCurrent()
        assertEquals(1, seen.size)
        advanceTimeBy(1); runCurrent()
        assertEquals("update-3", seen.last().single().text)
        source.emit(tool("pending")); runCurrent()
        source.emit(tool("changed command", "{\"command\":\"pwd\"}")); runCurrent()
        assertEquals("changed command", seen.last().single().text)
        source.emit(emptyList()); runCurrent()
        val size = seen.size
        advanceTimeBy(1_000); runCurrent()
        assertEquals(size, seen.size)
        assertTrue(seen.last().isEmpty())
        collector.cancel()
    }

    @Test fun cancellationStopsPendingPublicationAndOutputOnlyChangesDoNotChangeIdentityKeys() = runTest {
        val source = MutableSharedFlow<List<RunningToolOutput>>()
        val seen = mutableListOf<List<RunningToolOutput>>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.coalesceToolPreviewContent().collect { seen += it }
        }
        val first = listOf(RunningToolOutput("id", "first", "Bash", "{}"))
        val next = first.map { it.copy(text = "next") }
        assertEquals(runningToolPreviewKeysOf(first), runningToolPreviewKeysOf(next))
        source.emit(first); source.emit(next); runCurrent()
        collector.cancel()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, seen.size)
    }
}
