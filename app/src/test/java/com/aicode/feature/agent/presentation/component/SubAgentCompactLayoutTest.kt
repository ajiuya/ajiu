package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.SubAgentExecutionPage
import com.aicode.feature.agent.presentation.SubAgentInstructionResult
import com.aicode.feature.agent.presentation.SubAgentInteractionPage
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SubAgentCompactLayoutTest {
    @Test
    fun allEightStatesKeepTheRecordedNameBesideStatusAndTaskOnTheNextLine() {
        val summaries = SubAgentInteractionStatus.entries.map { status ->
            SubAgentInteractionSummary(
                subSessionId = status.name, agentName = "Agent ${status.ordinal}", title = "Task ${status.ordinal}",
                status = status, exchangeCount = status.ordinal + 2, toolCallCount = status.ordinal + 5,
                latestPreview = "Public summary ${status.ordinal}", currentToolTitle = "Inspect source ${status.ordinal}",
                startedAtMs = 1_000, finishedAtMs = 4_000,
            )
        }
        val labels = mutableMapOf<SubAgentInteractionStatus, String>()
        render(width = 400, content = {
            CompositionLocalProvider(LocalSubAgentCardHost provides SubAgentCardHost(summaries = summaries.associateBy { it.subSessionId })) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    summaries.forEach { summary ->
                        labels[summary.status] = subAgentStatusLabel(summary.status)
                        key(summary.subSessionId) { SubAgentInteractionCapsule(summary.subSessionId, fallbackAgentName = summary.agentName) }
                    }
                }
            }
        }) {
            summaries.forEach { summary ->
                val name = textNode(summary.agentName!!)
                val task = textNode(summary.title)
                val status = textNode(labels.getValue(summary.status))
                assertTrue(name.boundsInRoot.left < status.layoutBounds().left)
                assertTrue(name.boundsInRoot.center.y in status.layoutBounds().top..status.layoutBounds().bottom)
                assertEquals(name.boundsInRoot.left, task.layoutBounds().left, 0.01f)
                assertTrue(task.layoutBounds().top >= name.boundsInRoot.bottom)
                assertTrue(task.layoutBounds().top >= status.layoutBounds().bottom)
                assertTrue(hasText(activity.getString(R.string.subagent_interaction_counts, summary.exchangeCount, summary.toolCallCount)))
                val preview = if (summary.status.isActiveExecution()) {
                    activity.getString(R.string.subagent_interaction_current_tool, summary.currentToolTitle)
                } else summary.latestPreview
                assertTrue(hasText(preview))
                assertFalse(textLayout(name).didOverflowHeight)
                assertFalse(textLayout(status).didOverflowHeight)
            }
            assertEquals(0, nodes().count { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun collapsedGroupExpandsToCompactMemberRowsInsideTheSameCard() {
        val summaries = SubAgentInteractionStatus.entries.map { status ->
            SubAgentInteractionSummary(
                status.name, agentName = "Agent ${status.ordinal}", title = "Task ${status.ordinal}", status = status,
                latestPreview = "Public summary ${status.ordinal}", currentToolTitle = "Inspect source ${status.ordinal}",
                startedAtMs = 1_000, finishedAtMs = 4_000, lastActivityAtMs = status.ordinal.toLong(),
            )
        }
        val capsules = summaries.map { SubAgentTimelineCapsule(it.subSessionId, null, it.title, fallbackAgentName = it.agentName) }
        val labels = mutableMapOf<SubAgentInteractionStatus, String>()
        var sourceReads = 0
        val host = SubAgentCardHost(summaries = summaries.associateBy { it.subSessionId }, conversation = {
            sourceReads++
            flowOf(SubAgentInteractionPage(loaded = true))
        })
        render(content = {
            val expansion = rememberSubAgentGroupExpansion("parent", "turn")
            CompositionLocalProvider(LocalSubAgentCardHost provides host) {
                summaries.forEach { labels[it.status] = subAgentStatusLabel(it.status) }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SubAgentGroupCapsule(capsules + capsules.first(), expansion.expanded, expansion::toggle)
                }
            }
        }) {
            val names = summaries.joinToString(" · ") { it.agentName!! }
            assertTrue(hasText("Agent 2 · Inspect source 2"))
            assertTrue(hasText(formatToolDuration(3_000L)))
            assertFalse(hasText(names))
            assertTrue(hasText(activity.getString(R.string.subagent_group_total_count, 8)))
            assertEquals(5, nodes().count { "A" in it.texts() })
            assertTrue(hasText("+3"))
            assertFalse(SubAgentInteractionStatus.entries.any { hasText("● 1 ${labels.getValue(it)}") })
            val group = clickNode(activity.getString(R.string.subagent_group_title))
            assertTrue(group.boundsInRoot.height <= 72f)
            val description = group.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
            summaries.forEach { summary ->
                assertTrue(description.contains(activity.getString(R.string.subagent_group_member_status, summary.agentName, labels.getValue(summary.status))))
            }
            assertEquals(0, sourceReads)
            click(group)
            awaitUi { summaries.all { hasText(it.title) && hasTag("subagent_status:${it.subSessionId}") } }
            assertEquals(8, summaries.count { hasText(it.agentName!!) })
            summaries.forEach { summary ->
                val role = textNode(summary.agentName!!)
                val task = taggedNode("subagent_task:${summary.subSessionId}")
                val status = taggedNode("subagent_status:${summary.subSessionId}")
                assertEquals(listOf(summary.title), task.texts())
                assertEquals(listOf(labels.getValue(summary.status)), status.texts())
                assertTrue(role.layoutBounds().right + 8f <= task.layoutBounds().left + 0.01f)
                assertTrue(task.layoutBounds().right + 8f <= status.layoutBounds().left + 0.01f)
                assertEquals(task.layoutBounds().center.y, status.layoutBounds().center.y, 0.01f)
                assertFalse(hasText("${summary.title} · ${labels.getValue(summary.status)}"))
            }
            assertTrue(hasText(activity.getString(R.string.subagent_group_working_count, 3)))
            assertTrue(hasText(activity.getString(R.string.subagent_group_completed_count, 1)))
            assertTrue(nodes().none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            assertFalse(hasText(activity.getString(R.string.subagent_interaction_counts, 2, 5)))
            assertEquals(0, sourceReads)
            clickText(activity.getString(R.string.subagent_group_title))
            awaitUi {
                hasText("+3") && nodes().count { "A" in it.texts() } == 5 &&
                    summaries.none { hasTag("subagent_task:${it.subSessionId}") || hasTag("subagent_status:${it.subSessionId}") }
            }
            assertFalse(hasText(activity.getString(R.string.subagent_group_working_count, 3)))
            assertFalse(hasText(activity.getString(R.string.subagent_group_completed_count, 1)))
            assertEquals(0, sourceReads)
        }
    }

    @Test
    fun confirmationNavigationIsAvailableWhileFoldedAndDoesNotSubscribeOrStop() {
        var opened = ""
        var stopped = ""
        var reads = 0
        val summary = SubAgentInteractionSummary("reviewer", agentName = "Recorded reviewer", title = "Review changes",
            status = SubAgentInteractionStatus.AWAITING_CONFIRMATION)
        val host = SubAgentCardHost(summaries = mapOf("reviewer" to summary),
            onOpenConfirm = { opened = it }, onStop = { stopped = it }, conversation = {
                reads++
                flowOf(SubAgentInteractionPage(loaded = true))
            })
        render(content = {
            CompositionLocalProvider(LocalSubAgentCardHost provides host) { SubAgentInteractionCapsule("reviewer") }
        }) {
            clickText(activity.getString(R.string.subagent_interaction_handle_confirmation_short))
            assertEquals("reviewer", opened)
            assertEquals("", stopped)
            assertEquals(0, reads)
            assertFalse(hasText(activity.getString(R.string.subagent_interaction_conversation)))
            assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedCardKeepsScopedOpenStopSendTechnicalDetailsAndIndependentExecutionSubscription() {
        val summary = SubAgentInteractionSummary("coder", agentName = "Recorded coder", title = "Implement compact rows",
            model = "actual-model", status = SubAgentInteractionStatus.RUNNING, currentToolTitle = "Read real source")
        var conversationReads = 0
        var executionReads = 0
        var conversationCollectors = 0
        var executionCollectors = 0
        var leases = 0
        var opened = ""
        var stopped = ""
        val sent = mutableListOf<Pair<String, String>>()
        val host = SubAgentCardHost(
            summaries = mapOf("coder" to summary), onOpen = { opened = it }, onStop = { stopped = it },
            onExpanded = { _, active -> if (active) leases++ else leases-- },
            conversation = {
                conversationReads++
                flow {
                    conversationCollectors++
                    try { emit(SubAgentInteractionPage(loaded = true)); awaitCancellation() }
                    finally { conversationCollectors-- }
                }
            },
            execution = {
                executionReads++
                flow {
                    executionCollectors++
                    try { emit(SubAgentExecutionPage(loaded = true)); awaitCancellation() }
                    finally { executionCollectors-- }
                }
            },
            onSend = { child, text -> sent += child to text; SubAgentInstructionResult(sent = true) },
        )
        render(content = {
            CompositionLocalProvider(LocalSubAgentCardHost provides host) { SubAgentInteractionCapsule("coder") }
        }) {
            assertEquals(0, conversationReads)
            assertEquals(0, executionReads)
            clickText("Recorded coder")
            awaitUi { conversationCollectors == 1 }
            assertEquals(1, conversationReads)
            assertEquals(1, leases)
            assertEquals(0, executionReads)
            assertTrue(hasText(activity.getString(R.string.subagent_interaction_current_tool, "Read real source")))
            val open = clickNode(activity.getString(R.string.subagent_interaction_open_session))
            assertEquals(32f, open.boundsInRoot.height, 0.01f)
            click(open)
            clickText(activity.getString(R.string.subagent_interaction_stop))
            assertEquals("coder", opened)
            assertEquals("coder", stopped)
            clickText(activity.getString(R.string.subagent_interaction_show_technical))
            awaitUi { hasText(activity.getString(R.string.subagent_interaction_model, "actual-model")) }
            assertTrue(hasText(activity.getString(R.string.subagent_interaction_session_id, "coder")))
            val draft = "/review continue with this exact user instruction"
            setDraft(draft)
            clickDescription(activity.getString(R.string.subagent_interaction_send))
            awaitUi { sent.size == 1 && editableText().isEmpty() }
            assertEquals(listOf("coder" to draft), sent)
            assertTrue(hasText(activity.getString(R.string.subagent_interaction_sent)))
            clickText(activity.getString(R.string.subagent_interaction_execution))
            awaitUi { executionCollectors == 1 }
            assertEquals(1, executionReads)
            clickText(activity.getString(R.string.subagent_interaction_execution))
            awaitUi { executionCollectors == 0 }
            assertEquals(1, conversationCollectors)
            clickText("Recorded coder")
            awaitUi { conversationCollectors == 0 && leases == 0 }
            assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun executionToolsKeep32DpRows8DpSpacingPagingAndRealRunningOutput() {
        val tools = (1..6).map { number ->
            AgentUIMessage(id = "tool$number", role = MessageRole.TOOL, content = "Actual output $number",
                toolName = "task", toolArgs = "{\"action\":\"read\",\"id\":\"nested$number\"}",
                toolActionTitle = "Read nested task $number", toolDurationMs = 200L)
        }
        val host = SubAgentCardHost(
            summaries = mapOf("coder" to SubAgentInteractionSummary("coder", agentName = "Coder")),
            execution = { flowOf(SubAgentExecutionPage(messages = tools, loaded = true,
                runningTools = listOf(RunningToolOutput("tool6", "Real running output", "task", tools.last().toolArgs!!)))) },
        )
        render(content = {
            CompositionLocalProvider(LocalSubAgentCardHost provides host) { SubAgentExecutionContent("coder", host) }
        }) {
            clickText(activity.getString(R.string.subagent_interaction_execution))
            awaitUi { hasText("Read nested task 6") }
            val rows = (3..6).map { clickNode("Read nested task $it") }
            rows.forEach { assertEquals(32f, it.boundsInRoot.height, 0.01f) }
            rows.zipWithNext().forEach { (first, second) ->
                assertEquals(8f, second.boundsInRoot.top - first.boundsInRoot.bottom, 0.01f)
            }
            assertFalse(hasText("Read nested task 1"))
            clickText("Read nested task 6")
            awaitUi { hasText("Real running output") }
            assertTrue(hasText(tools.last().toolArgs!!))
            assertFalse(hasText("Actual output 6"))
            clickText("Read nested task 6")
            clickDescription(activity.getString(R.string.subagent_interaction_older_page))
            awaitUi { hasText("Read nested task 1") && !hasText("Read nested task 6") }
            assertTrue(hasText("Read nested task 2"))
            clickDescription(activity.getString(R.string.subagent_interaction_newer_page))
            awaitUi { hasText("Read nested task 6") }
        }
    }

    @Test
    fun unownedCardKeepsOperationsDisabledAfterExpandingTheCompactBody() {
        var reads = 0
        var sends = 0
        val host = SubAgentCardHost(
            summaries = mapOf("foreign" to SubAgentInteractionSummary("different-child", agentName = "Must not leak")),
            conversation = { reads++; flowOf(SubAgentInteractionPage(loaded = true)) },
            onSend = { _, _ -> sends++; SubAgentInstructionResult(sent = true) },
        )
        lateinit var unknownName: String
        render(content = {
            unknownName = stringResource(R.string.subagent_interaction_role_unknown)
            CompositionLocalProvider(LocalSubAgentCardHost provides host) { SubAgentInteractionCapsule("foreign") }
        }) {
            clickText(unknownName)
            awaitUi { hasText(activity.getString(R.string.subagent_interaction_unavailable)) }
            assertFalse(hasText("Must not leak"))
            assertEquals(0, reads)
            assertEquals(0, sends)
            assertTrue(clickNode(activity.getString(R.string.subagent_interaction_open_session)).config.contains(SemanticsProperties.Disabled))
            assertTrue(nodes().single { it.config.contains(SemanticsProperties.EditableText) }.config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishExpandedCountsIncludeApprovalAndExcludeFailedOrStoppedMembers() = assertExpandedCounts()

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseExpandedCountsIncludeApprovalAndExcludeFailedOrStoppedMembers() = assertExpandedCounts()

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishEightStatesKeepSeparateReadableColumnsAtNormalAndNarrowLargeFontSizes() =
        assertGroupColumns("Tester", "Fix", "Inspect the very long task title without moving the status column ")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseEightStatesKeepSeparateReadableColumnsAtNormalAndNarrowLargeFontSizes() =
        assertGroupColumns("测试员", "查", "核对真实任务标题并且始终保留完整状态栏的位置")

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishSingleMemberStatusChangesUseTheSlotMeasuredAcrossAllEightStates() =
        assertChangingStatusSlot("Coder", "Fix", "Keep this much longer task from moving any status ")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun chineseSingleMemberStatusChangesUseTheSlotMeasuredAcrossAllEightStates() =
        assertChangingStatusSlot("编码员", "查", "变更任务标题长度不能挤压或移动真实状态")

    @Test
    @Config(qualifiers = "en-rUS")
    fun forcedNarrowEnglishMembersActuallyStackTasksBelowTheirCompleteStatus() =
        assertGroupColumns("Coder", "Fix", "Force the second task line and retain the whole status ", forcedWidth = 140)

    @Test
    @Config(qualifiers = "zh-rCN")
    fun forcedNarrowChineseMembersActuallyStackTasksBelowTheirCompleteStatus() =
        assertGroupColumns("编码员", "查", "强制第二行标题并且保留完整状态", forcedWidth = 140)

    private fun assertExpandedCounts() {
        val cases = listOf(
            Triple(SubAgentInteractionStatus.entries.toList(), 3, 1),
            Triple(listOf(SubAgentInteractionStatus.AWAITING_CONFIRMATION), 1, 0),
            Triple(listOf(SubAgentInteractionStatus.FAILED, SubAgentInteractionStatus.INTERRUPTED,
                SubAgentInteractionStatus.DELETED, SubAgentInteractionStatus.HISTORY), 0, 0),
            Triple(List(3) { SubAgentInteractionStatus.COMPLETED }, 0, 3),
            Triple(listOf(SubAgentInteractionStatus.QUEUED, SubAgentInteractionStatus.RUNNING,
                SubAgentInteractionStatus.AWAITING_CONFIRMATION), 3, 0),
        )
        for ((statuses, working, completed) in cases) {
            var conversationReads = 0
            var executionReads = 0
            val summaries = statuses.mapIndexed { index, status ->
                SubAgentInteractionSummary("member-$index", title = "Task $index", agentName = "Agent $index", status = status)
            }
            val host = SubAgentCardHost(
                summaries = summaries.associateBy { it.subSessionId },
                conversation = { conversationReads++; flowOf(SubAgentInteractionPage(loaded = true)) },
                execution = { executionReads++; flowOf(SubAgentExecutionPage(loaded = true)) },
            )
            render(width = 320, fontScale = 1.5f, content = {
                CompositionLocalProvider(LocalSubAgentCardHost provides host) {
                    val capsules = summaries.map { SubAgentTimelineCapsule(it.subSessionId, null, it.title, fallbackAgentName = it.agentName) }
                    SubAgentGroupCapsule(capsules + capsules.first(), expanded = true, onToggle = {})
                }
            }) {
                val workingNode = textNode(activity.getString(R.string.subagent_group_working_count, working))
                val completedNode = textNode(activity.getString(R.string.subagent_group_completed_count, completed))
                assertTrue(hasText(activity.getString(R.string.subagent_group_total_count, statuses.size)))
                for (node in listOf(workingNode, completedNode)) {
                    assertFullTextFits(node)
                    assertEquals(1, textLayout(node).lineCount)
                    assertTrue(node.layoutBounds().left >= 0f && node.layoutBounds().right <= 320f)
                }
                val a = workingNode.layoutBounds()
                val b = completedNode.layoutBounds()
                assertTrue("Count chips must not overlap: $a, $b",
                    a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
                assertEquals(0, conversationReads)
                assertEquals(0, executionReads)
            }
        }
    }

    private fun assertGroupColumns(rolePrefix: String, shortTask: String, longTask: String, forcedWidth: Int? = null) {
        val cases = forcedWidth?.let { listOf(Triple(it, 1.5f, 0)) } ?: listOf(
            Triple(400, 1f, 0), Triple(320, 1f, 16), Triple(320, 1.5f, 16),
            Triple(320, 2f, 16), Triple(360, 2f, 16),
        )
        for ((width, fontScale, horizontalInset) in cases) {
            val useLongTasks = mutableStateOf(false)
            val labels = mutableMapOf<SubAgentInteractionStatus, String>()
            var expectedFontSize = TextUnit.Unspecified
            render(width = width, fontScale = fontScale, content = {
                expectedFontSize = MaterialTheme.typography.labelSmall.fontSize
                val summaries = SubAgentInteractionStatus.entries.map { status ->
                    labels[status] = subAgentStatusLabel(status)
                    SubAgentInteractionSummary(status.name, title = if (useLongTasks.value) longTask.repeat(64) else shortTask,
                        agentName = "$rolePrefix${status.ordinal}", status = status)
                }
                CompositionLocalProvider(LocalSubAgentCardHost provides SubAgentCardHost(summaries = summaries.associateBy { it.subSessionId })) {
                    Box(Modifier.padding(horizontal = horizontalInset.dp)) {
                        SubAgentGroupCapsule(summaries.map {
                            SubAgentTimelineCapsule(it.subSessionId, null, it.title, fallbackAgentName = it.agentName)
                        }, expanded = true, onToggle = {})
                    }
                }
            }) {
                val initialStatuses = mutableMapOf<SubAgentInteractionStatus, Rect>()
                val initialTasks = mutableMapOf<SubAgentInteractionStatus, Rect>()
                for (long in listOf(false, true)) {
                    useLongTasks.value = long
                    val taskText = if (long) longTask.repeat(64) else shortTask
                    awaitUi { taggedNode("subagent_task:QUEUED").texts() == listOf(taskText) }
                    var statusLeft: Float? = null
                    var statusRight: Float? = null
                    val stackedRows = mutableSetOf<Boolean>()
                    SubAgentInteractionStatus.entries.forEach { state ->
                        val role = textNode("$rolePrefix${state.ordinal}")
                        val task = taggedNode("subagent_task:${state.name}")
                        val status = taggedNode("subagent_status:${state.name}")
                        assertEquals(listOf(taskText), task.texts())
                        assertEquals(listOf(labels.getValue(state)), status.texts())
                        val stacked = task.layoutBounds().top >= maxOf(role.layoutBounds().bottom, status.layoutBounds().bottom)
                        stackedRows += stacked
                        assertTrue("Role and status must not overlap", role.layoutBounds().right + 8f <= status.layoutBounds().left + 0.01f)
                        if (stacked) {
                            assertEquals(role.layoutBounds().left, task.layoutBounds().left, 0.01f)
                            assertEquals(status.layoutBounds().right, task.layoutBounds().right, 0.01f)
                            assertTrue("Second-line task must retain its vertical gap",
                                task.layoutBounds().top >= maxOf(role.layoutBounds().bottom, status.layoutBounds().bottom) + Spacing.xs.value - 0.01f)
                        } else {
                            assertTrue("Role and task must not overlap", role.layoutBounds().right + 8f <= task.layoutBounds().left + 0.01f)
                            assertTrue("Task and status must not overlap", task.layoutBounds().right + 8f <= status.layoutBounds().left + 0.01f)
                            assertEquals(role.layoutBounds().center.y, task.layoutBounds().center.y, 0.01f)
                            assertEquals(task.layoutBounds().center.y, status.layoutBounds().center.y, 0.01f)
                        }
                        assertTrue("Task must retain usable width", task.layoutBounds().width > 0f)
                        assertEquals(width - horizontalInset - Spacing.md.value, status.layoutBounds().right, 0.01f)
                        assertEquals(TextAlign.End, textLayout(status).layoutInput.style.textAlign)
                        listOf(role, status).forEach { node ->
                            val layout = textLayout(node)
                            assertFullTextFits(node)
                            assertFalse(layout.isLineEllipsized(0))
                            assertTrue("Text metrics must remain nonzero", layout.size.height > 0)
                            if (node == role || !stacked) assertEquals(1, layout.lineCount)
                            assertEquals(expectedFontSize, layout.layoutInput.style.fontSize)
                            assertEquals(fontScale, layout.layoutInput.density.fontScale, 0f)
                            assertTrue(node.layoutBounds().height >= layout.size.height - 0.01f)
                        }
                        val taskLayout = textLayout(task)
                        assertEquals(1, taskLayout.lineCount)
                        assertEquals(TextOverflow.Ellipsis, taskLayout.layoutInput.overflow)
                        assertEquals(expectedFontSize, taskLayout.layoutInput.style.fontSize)
                        assertTrue("Task glyph height must fit", task.layoutBounds().height >= taskLayout.size.height - 0.01f)
                        if (long) {
                            assertTrue("Long fixture must exceed the measured task slot",
                                taskLayout.multiParagraph.intrinsics.maxIntrinsicWidth > task.layoutBounds().width)
                            assertEquals("Long tasks must request one line rather than draw outside the slot", 1, taskLayout.layoutInput.maxLines)
                            assertTrue("Long task layout must remain bounded by its allocated slot",
                                taskLayout.size.width <= task.layoutBounds().width + 0.01f)
                        } else assertFullTextFits(task)
                        statusLeft?.let { assertEquals(it, status.layoutBounds().left, 0.01f) }
                        statusRight?.let { assertEquals(it, status.layoutBounds().right, 0.01f) }
                        statusLeft = status.layoutBounds().left
                        statusRight = status.layoutBounds().right
                        if (long) {
                            assertEquals("Task length must not move status", initialStatuses.getValue(state), status.layoutBounds())
                            assertEquals("Task column must keep its geometry", initialTasks.getValue(state), task.layoutBounds())
                        } else {
                            initialStatuses[state] = status.layoutBounds()
                            initialTasks[state] = task.layoutBounds()
                        }
                    }
                    assertEquals("Every member must use the same responsive row arrangement", 1, stackedRows.size)
                    if (width == 400 && fontScale == 1f) assertEquals(setOf(false), stackedRows)
                    if (forcedWidth != null) assertEquals("The forced narrow composable must exercise the stacked branch", setOf(true), stackedRows)
                }
            }
        }
    }

    private fun assertChangingStatusSlot(role: String, shortTask: String, longTask: String) {
        for ((width, fontScale) in listOf(400 to 1f, 320 to 1.5f)) {
            val currentStatus = mutableStateOf(SubAgentInteractionStatus.QUEUED)
            val currentTask = mutableStateOf(shortTask)
            val labels = mutableMapOf<SubAgentInteractionStatus, String>()
            render(width = width, fontScale = fontScale, content = {
                SubAgentInteractionStatus.entries.forEach { labels[it] = subAgentStatusLabel(it) }
                val summary = SubAgentInteractionSummary("changing", title = currentTask.value,
                    agentName = role, status = currentStatus.value)
                CompositionLocalProvider(LocalSubAgentCardHost provides SubAgentCardHost(summaries = mapOf("changing" to summary))) {
                    SubAgentGroupCapsule(listOf(SubAgentTimelineCapsule("changing", null, summary.title, fallbackAgentName = role)), expanded = true, onToggle = {})
                }
            }) {
                val original = taggedNode("subagent_status:changing").layoutBounds()
                for ((index, status) in SubAgentInteractionStatus.entries.withIndex()) {
                    currentStatus.value = status
                    currentTask.value = if (index % 2 == 0) longTask.repeat(64) else shortTask
                    awaitUi {
                        taggedNode("subagent_status:changing").texts() == listOf(labels.getValue(status)) &&
                            taggedNode("subagent_task:changing").texts() == listOf(currentTask.value)
                    }
                    val node = taggedNode("subagent_status:changing")
                    val layout = textLayout(node)
                    assertEquals("Status=$status must not resize or move the fixed slot", original, node.layoutBounds())
                    assertEquals(TextAlign.End, layout.layoutInput.style.textAlign)
                    assertFullTextFits(node)
                    assertFalse(layout.isLineEllipsized(0))
                    assertEquals(1, layout.lineCount)
                }
            }
        }
    }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun SemanticsNode.layoutBounds(): Rect = if (size.height == 0) {
        Rect(positionInRoot, Size(size.width.toFloat(), size.height.toFloat()))
    } else boundsInRoot

    private inner class CompactUi(
        val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit,
    ) {
        fun nodes(merged: Boolean = false): List<SemanticsNode> =
            (view.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = merged).orEmpty()
        fun hasText(text: String): Boolean = nodes().any { text in it.texts() }
        fun hasTag(tag: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun taggedNode(tag: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun textNode(text: String): SemanticsNode = nodes().single { text in it.texts() }
        fun textLayout(node: SemanticsNode): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(result))
            return result.single()
        }
        fun assertFullTextFits(node: SemanticsNode) {
            val layout = textLayout(node)
            val bounds = node.layoutBounds()
            val description = "text=${node.texts()}, intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, size=${layout.size}, bounds=$bounds"
            assertTrue(description, layout.multiParagraph.intrinsics.maxIntrinsicWidth <= layout.size.width + 0.01f)
            assertTrue(description, layout.multiParagraph.height <= layout.size.height + 0.01f)
            assertTrue(description, bounds.width >= layout.size.width - 0.01f)
            assertTrue(description, bounds.height >= layout.size.height - 0.01f)
        }
        fun clickNode(text: String): SemanticsNode = nodes(merged = true).single {
            text in it.texts() && it.config.contains(SemanticsActions.OnClick)
        }
        fun click(node: SemanticsNode) {
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            repeat(4) { advanceFrame() }
        }
        fun clickText(text: String) = click(clickNode(text))
        fun clickDescription(description: String) = click(nodes(merged = true).single {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true &&
                it.config.contains(SemanticsActions.OnClick)
        })
        fun editableText(): String = nodes().single { it.config.contains(SemanticsActions.SetText) }
            .config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        fun setDraft(text: String) {
            val editor = nodes().single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(editor.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(text)))
            awaitUi { editableText() == text }
        }
        fun awaitUi(condition: () -> Boolean) {
            repeat(120) {
                advanceFrame()
                if (condition()) return
            }
            error("Compact UI did not settle: ${nodes().map { it.config }}")
        }
    }

    private fun render(
        width: Int = 1_000,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        inspect: CompactUi.() -> Unit,
    ) = runTest(timeout = 15.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        val looper = shadowOf(android.os.Looper.getMainLooper())
        var frame = 0L
        val ui = CompactUi(activity, view) {
            repeat(4) { looper.runOneTask() }
            Snapshot.sendApplyNotifications()
            runCurrent()
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
            Snapshot.sendApplyNotifications()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(10_000, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            (view.getChildAt(0) as? ViewRootForTest)?.measureAndLayoutForTest()
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { AIEditorTheme(darkTheme = false, content = content) }
            }
            activity.setContentView(view)
            ui.awaitUi { ui.nodes().isNotEmpty() }
            repeat(6) { ui.awaitUi { true } }
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }
}
