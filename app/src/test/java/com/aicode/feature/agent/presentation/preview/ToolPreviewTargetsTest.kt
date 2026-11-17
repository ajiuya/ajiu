package com.aicode.feature.agent.presentation.preview

import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPreviewTargetsTest {
    private fun message(id: String, content: String = "done") =
        AgentUIMessage(id, MessageRole.TOOL, content, toolName = "Bash", toolArgs = "{\"command\":\"pwd\"}")

    @Test
    fun allSessionToolsKeepChronologicalOrderWithoutWindowCap() {
        val messages = (0 until 1_501).map { message("call-$it") }
        val targets = buildToolPreviewTargets(messages, emptyList(), emptyList())
        assertEquals(1_501, targets.size)
        assertEquals(messages.map { it.id }, targets.map { it.id })
    }

    @Test
    fun runningOverlayAndDraftHandoffNeverDuplicateACall() {
        val targets = buildToolPreviewTargets(
            listOf(message("a"), message("b", SessionUseCase.PENDING_TOOL_MARKER)),
            listOf(RunningToolOutput("b", "live", "Root", "{\"command\":\"id\"}"), RunningToolOutput("c", "new", "terminal")),
            listOf(PreparingToolPreviewSnapshot("b", "Root", "{}"), PreparingToolPreviewSnapshot("d", "writeFile", "{")),
        )
        assertEquals(listOf("a", "b", "c", "d"), targets.map { it.id })
        assertEquals("live", targets[1].content)
        assertEquals("Root", targets[1].name)
        assertEquals(ToolPreviewStage.RUNNING, targets[1].stage)
        assertEquals(ToolPreviewStage.PREPARING, targets.last().stage)
    }

    @Test
    fun completedMessagesWinOverStaleRunningOutputsAndPreparingDrafts() {
        val message = message("finished").copy(toolActionTitle = "Final action")
        val target = buildToolPreviewTargets(
            listOf(message),
            listOf(RunningToolOutput("finished", "stale live", "Root", "{\"command\":\"id\"}")),
            listOf(PreparingToolPreviewSnapshot("finished", "Root", "{}")),
        ).single()
        assertEquals(ToolPreviewStage.COMPLETED, target.stage)
        assertEquals(message.content, target.content)
        assertEquals(message.toolName, target.name)
        assertEquals(message.toolArgs, target.arguments)
        assertEquals(message.toolActionTitle, target.actionTitle)
    }

    @Test
    fun failedMessagesWinOverStaleRunningOutputs() {
        val message = message("failed", "final failure").copy(isError = true, toolActionTitle = "Failed action")
        val target = buildToolPreviewTargets(
            listOf(message),
            listOf(RunningToolOutput("failed", "stale live", "Root", "{\"command\":\"id\"}")),
            emptyList(),
        ).single()
        assertEquals(ToolPreviewStage.FAILED, target.stage)
        assertEquals(message.content, target.content)
        assertEquals(message.toolName, target.name)
        assertEquals(message.toolArgs, target.arguments)
        assertEquals(message.toolActionTitle, target.actionTitle)
    }

    @Test
    fun pendingPlaceholdersAreNotReportedAsCompletedOrShownAsOutput() {
        val target = buildToolPreviewTargets(listOf(message("a", SessionUseCase.PENDING_TOOL_MARKER)), emptyList(), emptyList()).single()
        assertEquals(ToolPreviewStage.RUNNING, target.stage)
        assertEquals("", target.content)
    }

    @Test
    fun manualSelectionSurvivesNewActivityAndInPlaceStatusChanges() {
        val state = ToolPreviewHostState("session")
        val initial = buildToolPreviewTargets(listOf(message("a"), message("b")), emptyList(), emptyList())
        state.select("a")
        val running = initial + ToolPreviewTarget("c", "Bash", "{}", "tail", ToolPreviewStage.RUNNING)
        assertEquals(0, previewSelectedIndex(running, state.selectedMessageId, state.followingLatest))
        val completed = running.map { it.copy(stage = ToolPreviewStage.COMPLETED) }
        assertEquals(0, previewSelectedIndex(completed, state.selectedMessageId, state.followingLatest))
        state.followLatest()
        assertEquals(2, previewSelectedIndex(completed, state.selectedMessageId, state.followingLatest))
    }

    @Test
    fun automaticSelectionReactsToActivityEvenWhenTheCountStaysTheSame() {
        val targets = listOf(
            ToolPreviewTarget("a", "Bash", "{}", "", ToolPreviewStage.RUNNING),
            ToolPreviewTarget("b", "Bash", "{}", "", ToolPreviewStage.COMPLETED),
            ToolPreviewTarget("c", "editFile", "{}", "", ToolPreviewStage.PREPARING),
        )
        assertEquals(2, previewSelectedIndex(targets, null, true))
        assertEquals(0, previewSelectedIndex(targets.dropLast(1) + targets.last().copy(stage = ToolPreviewStage.COMPLETED), null, true))
        assertEquals(2, previewSelectedIndex(targets.map { it.copy(stage = ToolPreviewStage.COMPLETED) }, null, true))
    }

    @Test
    fun longPressFirstLastSelectionsStayManualAndSessionsAreIndependent() {
        val a = ToolPreviewHostState("a")
        a.openDetails("first")
        assertTrue(a.detailOpen)
        assertTrue(a.followingLatest)
        assertEquals("first", a.detailMessageId)
        a.selectDetail("middle")
        assertEquals("middle", a.detailMessageId)
        assertEquals(null, a.selectedMessageId)
        a.select("last")
        a.closeDetails()
        assertEquals("last", a.selectedMessageId)
        assertFalse(a.followingLatest)
        val b = ToolPreviewHostState("b")
        assertEquals(null, b.selectedMessageId)
        assertTrue(b.followingLatest)
        assertFalse(b.detailOpen)
    }

    @Test
    fun detailSelectionDoesNotPauseAutoFollowOrChangeTheManuallySelectedBar() {
        val state = ToolPreviewHostState("session")
        val targets = listOf(
            ToolPreviewTarget("first", "Bash", "{}", "", ToolPreviewStage.COMPLETED),
            ToolPreviewTarget("second", "Bash", "{}", "", ToolPreviewStage.RUNNING),
        )
        state.openDetails("first")
        state.selectDetail("second")
        assertEquals(1, previewSelectedIndex(targets, state.selectedMessageId, state.followingLatest))
        assertTrue(state.followingLatest)
        state.select("first")
        state.selectDetail("second")
        assertEquals(0, previewSelectedIndex(targets, state.selectedMessageId, state.followingLatest))
        state.closeDetails()
        assertEquals(null, state.detailMessageId)
        assertEquals("first", state.selectedMessageId)
    }

    @Test
    fun hiddenBackgroundAndMismatchedSessionsDoNotStartPreviewWork() {
        assertTrue(toolPreviewWorkVisible("a", "a", visible = true, lifecycleStarted = true))
        assertFalse(toolPreviewWorkVisible("a", "a", visible = false, lifecycleStarted = true))
        assertFalse(toolPreviewWorkVisible("a", "a", visible = true, lifecycleStarted = false))
        assertFalse(toolPreviewWorkVisible("a", "b", visible = true, lifecycleStarted = true))
        assertFalse(toolPreviewWorkVisible(null, null, visible = true, lifecycleStarted = true))
    }

    @Test
    fun mapInputApiProducesOneCompleteSnapshot() {
        val snapshot = PreparingToolPreviewSnapshot.fromInput("id", "writeFile", mapOf("path" to JsonPrimitive("a.kt")))
        assertEquals("id", snapshot.id)
        assertEquals("a.kt", fileToolDraftOf(snapshot.name, snapshot.accumulated)?.path)
        assertEquals(-1, previewSelectedIndex(emptyList(), null, true))
    }
}
