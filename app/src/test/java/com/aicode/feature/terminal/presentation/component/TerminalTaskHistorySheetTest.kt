package com.aicode.feature.terminal.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.terminal.domain.MAX_TERMINAL_OUTPUT_CHARS
import com.aicode.feature.terminal.domain.TerminalTaskRecord
import com.aicode.feature.terminal.domain.TerminalTaskScope
import com.aicode.feature.terminal.domain.TerminalTaskStatus
import com.aicode.feature.terminal.presentation.TerminalHistoryUiState
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
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
@Config(sdk = [28], qualifiers = "w320dp-h700dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class TerminalTaskHistorySheetTest {
    @Test(timeout = 90_000)
    fun emptyLoadingAndErrorStatesNeverMasqueradeAsSuccessfulEmptyHistory(): Unit = render {
        assertTrue(hasText(R.string.terminal_history_empty))
        state = state.copy(loading = true)
        pump()
        assertTrue(hasText(R.string.terminal_history_loading))
        assertFalse(hasText(R.string.terminal_history_empty))
        state = state.copy(loading = false, storageError = true)
        pump()
        assertTrue(hasText(R.string.terminal_history_storage_error))
        assertFalse(hasText(R.string.terminal_history_empty))
        state = state.copy(storageError = false, unavailable = true)
        pump()
        assertTrue(hasText(R.string.terminal_history_unavailable))
        assertFalse(hasText(R.string.terminal_history_empty))
        assertReadOnly()
    }

    @Test(timeout = 90_000)
    fun listAndDetailsShowRealExitCodesAndUnknownWithoutRunOrDeleteActions(): Unit = render {
        val finished = record.copy(status = TerminalTaskStatus.FINISHED, exitCode = 17, finishedAt = 3000)
        val unknown = record.copy(historyId = "unknown", status = TerminalTaskStatus.UNKNOWN, title = "Unknown task")
        state = state.copy(records = listOf(finished, unknown))
        pump()
        reveal { it.containsText(label(R.string.terminal_history_finished, 17)) }
        reveal { it.containsText(label(R.string.terminal_history_unknown)) }
        clickTag("terminal-history-record-${finished.historyId}")
        assertEquals(listOf("select:${finished.historyId}"), operations)
        assertEquals(finished, state.selectedRecord)
        reveal { it.hasText(label(R.string.terminal_history_id, finished.historyId)) }
        reveal { it.hasText(label(R.string.terminal_history_snapshot_note)) }
        val output = reveal { it.config.getOrNull(SemanticsProperties.TestTag) == "terminal-history-output-0" }
        assertEquals(record.output, output.config[SemanticsProperties.Text].single().text)
        assertReadOnly()
        clickTag("terminal-history-back")
        assertNull(state.selectedRecord)
        assertEquals(listOf("select:${finished.historyId}", "back"), operations)
        clickTag("terminal-history-close")
        assertEquals("close", operations.last())
    }

    @Test(timeout = 90_000)
    fun narrowLargeFontKeepsHeaderActionsVisibleAndLongPlainOutputScrollable(): Unit = render(fontScale = 1.5f) {
        val long = record.copy(
            title = "A saved task with a long title ".repeat(8),
            output = "Literal output line <button>not executable</button>\n".repeat(7000)
        )
        state = state.copy(records = listOf(long), selectedHistoryId = long.historyId)
        pump()
        assertVisible(tag("terminal-history-close"))
        assertVisible(tag("terminal-history-back"))
        assertTrue(tag("terminal-history-back").bounds().right <= tag("terminal-history-close").bounds().left)
        val first = reveal { it.config.getOrNull(SemanticsProperties.TestTag) == "terminal-history-output-0" }
        assertEquals(2048, first.config[SemanticsProperties.Text].single().text.length)
        val scroll = tag("terminal-history-detail").config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scroll.maxValue() > 0f)
        assertTrue(requireNotNull(tag("terminal-history-detail").config[SemanticsActions.ScrollBy].action).invoke(0f, 5000f))
        pump()
        assertTrue(tag("terminal-history-detail").config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertVisible(tag("terminal-history-close"))
        assertReadOnly()
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    fun localHistoryActionNeedsNoReadyOrTabStateAndIsHiddenForRemote(): Unit = render(actionOnly = true) {
        assertVisible(tag("terminal-history-open"))
        clickTag("terminal-history-open")
        assertEquals(listOf("open"), operations)
        remote = true
        pump()
        assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "terminal-history-open" })
    }

    @Test(timeout = 90_000)
    fun selectedEmptyOutputAndStorageWarningRemainReadOnly(): Unit = render {
        val empty = record.copy(output = "", historyError = "private-storage-error")
        state = state.copy(records = listOf(empty), selectedHistoryId = empty.historyId)
        pump()
        reveal { it.hasText(label(R.string.terminal_history_storage_error)) }
        reveal { it.hasText(label(R.string.terminal_history_output_empty)) }
        assertFalse(nodes().any { it.hasText("private-storage-error") })
        assertReadOnly()
    }

    @Test
    fun outputChunkingIsBoundedAndLosslessWithinTheSnapshotLimit() {
        val input = "literal\n".repeat(1000)
        val chunks = terminalHistoryOutputChunks(input)
        assertEquals(input, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 2048 })
        val oversized = "x".repeat(MAX_TERMINAL_OUTPUT_CHARS + 1000)
        assertEquals(MAX_TERMINAL_OUTPUT_CHARS + 64, terminalHistoryOutputChunks(oversized).sumOf { it.length })
        assertTrue(terminalHistoryOutputChunks("").isEmpty())
    }

    @Test
    fun outputChunkAndLimitBoundariesNeverSplitUtf16SurrogatePairs() {
        val pair = "\uD83D\uDE00"
        val input = "x".repeat(2047) + pair + "tail"
        val chunks = terminalHistoryOutputChunks(input)
        assertEquals(input, chunks.joinToString(""))
        assertEquals(2047, chunks.first().length)
        assertTrue(chunks.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        val limit = MAX_TERMINAL_OUTPUT_CHARS + 64
        val clipped = terminalHistoryOutputChunks("x".repeat(limit - 1) + pair + "tail")
        assertEquals(limit - 1, clipped.sumOf { it.length })
        assertTrue(clipped.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    private class Ui(
        val activity: ComponentActivity,
        val view: ComposeView,
        val pumpFrames: (Int) -> Unit
    ) {
        var state by mutableStateOf(TerminalHistoryUiState(visible = true))
        var remote by mutableStateOf(false)
        val operations = mutableListOf<String>()
        fun pump() = pumpFrames(16)
        fun nodes(): List<SemanticsNode> = requireNotNull(findRoot(view)).semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false)
        fun label(resource: Int, vararg args: Any): String = activity.getString(resource, *args)
        fun hasText(resource: Int): Boolean = nodes().any { it.hasText(label(resource)) }
        fun tag(value: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun assertReadOnly() {
            assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
            assertFalse(nodes().any { it.config.contains(SemanticsProperties.EditableText) })
            val tags = nodes().filter { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }
                .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            assertTrue(tags.all { it == "terminal-history-close" || it == "terminal-history-back" ||
                it == "terminal-history-open" || it.startsWith("terminal-history-record-") })
        }
        fun clickTag(value: String) {
            val node = reveal { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            pump()
        }
        fun assertVisible(node: SemanticsNode) {
            val bounds = node.bounds()
            assertTrue("$bounds outside ${view.width}x${view.height}", bounds.width > 0f && bounds.height > 0f &&
                bounds.left >= 0f && bounds.top >= 0f && bounds.right <= view.width && bounds.bottom <= view.height)
        }
        fun reveal(matches: (SemanticsNode) -> Boolean): SemanticsNode {
            repeat(80) {
                nodes().singleOrNull(matches)?.let { node ->
                    var parent = node.parent
                    while (parent != null) {
                        val viewport = parent.boundsInRoot
                        val action = parent.config.getOrNull(SemanticsActions.ScrollBy)?.action
                        if (action != null && node.bounds().top >= viewport.bottom) {
                            assertTrue(action.invoke(0f, node.bounds().top - viewport.top))
                            pump()
                            return@repeat
                        }
                        parent = parent.parent
                    }
                    return node
                }
                val scroll = nodes().firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    ?: error("No matching node and no scroll container")
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 350f))
                pump()
            }
            error("History node did not become visible")
        }
    }

    private fun render(fontScale: Float = 1f, actionOnly: Boolean = false, inspect: Ui.() -> Unit): Unit =
        runTest(timeout = 60.seconds) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            val activity = controller.get()
            val view = ComposeView(activity)
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
                recomposer.runRecomposeAndApplyChanges()
            }
            var frame = 0L
            val ui = Ui(activity, view) { count -> repeat(count) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                testScheduler.advanceTimeBy(16)
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                findRoot(view)?.measureAndLayoutForTest()
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
            } }
            try {
                view.setParentCompositionContext(recomposer)
                view.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        AIEditorTheme {
                            if (actionOnly) {
                                TerminalHistoryAction(ui.remote) { ui.operations += "open" }
                            } else {
                                TerminalTaskHistoryContent(
                                    state = ui.state,
                                    onSelect = { ui.operations += "select:$it"; ui.state = ui.state.copy(selectedHistoryId = it) },
                                    onBack = { ui.operations += "back"; ui.state = ui.state.copy(selectedHistoryId = null) },
                                    onDismiss = { ui.operations += "close" },
                                    modifier = Modifier
                                )
                            }
                        }
                    }
                }
                activity.setContentView(view)
                ui.pump()
                ui.inspect()
            } finally {
                view.disposeComposition()
                recomposer.cancel()
                runner.cancel()
                runCurrent()
                controller.pause().stop().destroy()
            }
        }

    companion object {
        private val scope = TerminalTaskScope("profile", "a".repeat(64), "/rootfs", "rootfs", "/workspace", "workspace")
        private val record = TerminalTaskRecord(
            "ebdceca4-6af4-4490-912d-d99aa478d21d", "term-record", "Saved task", "session", scope,
            1000, 2000, output = "Literal <b>output</b>\nNo terminal input here"
        )
        private fun SemanticsNode.hasText(value: String): Boolean =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        private fun SemanticsNode.containsText(value: String): Boolean =
            config.getOrNull(SemanticsProperties.Text)?.any { value in it.text } == true
        private fun SemanticsNode.bounds(): Rect = Rect(positionInRoot.x, positionInRoot.y,
            positionInRoot.x + size.width, positionInRoot.y + size.height)
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
