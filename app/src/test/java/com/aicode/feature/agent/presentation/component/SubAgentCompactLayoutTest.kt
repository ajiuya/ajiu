package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.unit.Density
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
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SubAgentCompactLayoutTest {
    @Test
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
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
                assertTrue(name.boundsInRoot.left < status.boundsInRoot.left)
                assertTrue(name.boundsInRoot.center.y in status.boundsInRoot.top..status.boundsInRoot.bottom)
                assertEquals(name.boundsInRoot.left, task.boundsInRoot.left, 0.01f)
                assertTrue(task.boundsInRoot.top >= name.boundsInRoot.bottom)
                assertTrue(task.boundsInRoot.top >= status.boundsInRoot.bottom)
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
    fun collapsedGroupReportsEveryRealStateAndAllNamesThenExpandsEveryChild() {
        val summaries = SubAgentInteractionStatus.entries.map { status ->
            SubAgentInteractionSummary(status.name, agentName = "Agent ${status.ordinal}", title = "Task ${status.ordinal}", status = status)
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
                    if (expansion.expanded) summaries.forEach { summary ->
                        key(summary.subSessionId) { SubAgentInteractionCapsule(summary.subSessionId, fallbackAgentName = summary.agentName) }
                    }
                }
            }
        }) {
            val names = summaries.joinToString(" · ") { it.agentName!! }
            assertTrue(hasText(names))
            assertTrue(hasText(activity.getString(R.string.subagent_group_total_count, 8)))
            summaries.forEach { summary ->
                assertTrue(hasText(activity.getString(R.string.subagent_group_status_count, labels.getValue(summary.status), 1)))
            }
            val group = clickNode(activity.getString(R.string.subagent_group_title))
            val description = group.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
            summaries.forEach { summary ->
                assertTrue(description.contains(activity.getString(R.string.subagent_group_member_status, summary.agentName, labels.getValue(summary.status))))
            }
            assertEquals(0, sourceReads)
            click(group)
            awaitUi { summaries.all { hasText(it.title) } }
            assertEquals(8, summaries.count { hasText(it.agentName!!) })
            assertEquals(0, sourceReads)
            clickText(activity.getString(R.string.subagent_group_title))
            awaitUi { hasText(names) && summaries.none { hasText(it.title) } }
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

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private inner class CompactUi(
        val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit,
    ) {
        fun nodes(merged: Boolean = false): List<SemanticsNode> =
            (view.getChildAt(0) as? ViewRootForTest)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = merged).orEmpty()
        fun hasText(text: String): Boolean = nodes().any { text in it.texts() }
        fun textNode(text: String): SemanticsNode = nodes().single { text in it.texts() }
        fun textLayout(node: SemanticsNode): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(result))
            return result.single()
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
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) { AIEditorTheme(darkTheme = false, content = content) }
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
