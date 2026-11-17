package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import java.time.Duration
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
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
// Legacy graphics 以字符数模拟字宽；这里只验证真实 Compose 的尺寸与语义，不作字形渲染验收。
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class)
class ToolCapsuleLayoutTest {
    @Test
    fun collapsedToolBodyHas32DpHeightWithTheThemeLabelSize() {
        lateinit var body: Rect
        var expectedLabelSize = TextUnit.Unspecified
        val texts = measure {
            expectedLabelSize = MaterialTheme.typography.labelLarge.fontSize
            Box(Modifier.onGloballyPositioned { body = it.boundsInRoot() }) {
                ToolMessageBody(message(), expandedOverride = false, durationMs = 100)
            }
        }

        assertEquals("body=$body; ${describeTexts(texts)}", 32f, body.height, 0.01f)
        val title = texts.single { it.layout.layoutInput.text.text == "Inspect file" }
        assertEquals(expectedLabelSize, title.layout.layoutInput.style.fontSize)
        assertFalse(title.layout.didOverflowHeight)
    }

    @Test
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun enlargedFontKeepsItsSizeAndFitsVerticallyInTheGrowingBody() {
        lateinit var body: Rect
        var expectedLabelSize = TextUnit.Unspecified
        val texts = measure(fontScale = 3f) {
            expectedLabelSize = MaterialTheme.typography.labelLarge.fontSize
            Box(Modifier.onGloballyPositioned { body = it.boundsInRoot() }) {
                ToolMessageBody(message(), expandedOverride = false, durationMs = 100)
            }
        }

        assertTrue("Large text must grow the 32dp minimum", body.height > 32f)
        val title = texts.single { it.layout.layoutInput.text.text == "Inspect file" }
        assertEquals(expectedLabelSize, title.layout.layoutInput.style.fontSize)
        assertEquals(3f, title.layout.layoutInput.density.fontScale, 0f)
        assertEquals("Only the title and duration are visible while collapsed", 2, texts.size)
        texts.forEach {
            assertFalse("body=$body; ${describeTexts(texts)}", it.layout.didOverflowHeight)
            assertTrue("The full text layout must fit", it.bounds.height >= it.layout.size.height - 0.01f)
            assertTrue("Top padding must survive font scaling", it.bounds.top >= body.top + 4f - 0.01f)
            assertTrue("Bottom padding must survive font scaling", it.bounds.bottom <= body.bottom - 4f + 0.01f)
        }
    }

    @Test
    fun adjacentToolItemsKeepAn8DpFooterAnd40DpItemHeight() {
        lateinit var standaloneBody: Rect
        lateinit var first: Rect
        lateinit var second: Rect
        val texts = measure {
            Column {
                Box(Modifier.onGloballyPositioned { standaloneBody = it.boundsInRoot() }) {
                    ToolMessageBody(message("standalone"), expandedOverride = false, durationMs = 100)
                }
                Box(Modifier.onGloballyPositioned { first = it.boundsInRoot() }) {
                    AgentMessageItem(message("first"), toolExpandedOverride = false, toolDurationMs = 100)
                }
                Box(Modifier.onGloballyPositioned { second = it.boundsInRoot() }) {
                    AgentMessageItem(message("second"), toolExpandedOverride = false, toolDurationMs = 100)
                }
            }
        }

        assertEquals("body=$standaloneBody; ${describeTexts(texts)}", 32f, standaloneBody.height, 0.01f)
        assertEquals(40f, first.height, 0.01f)
        assertEquals(40f, second.height, 0.01f)
        assertEquals(8f, first.height - standaloneBody.height, 0.01f)
        assertEquals(8f, second.height - standaloneBody.height, 0.01f)
        assertEquals(8f, second.top - (first.top + standaloneBody.height), 0.01f)
    }

    @Test
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun collapsedSubAgentGroupRendersTheRecordedNamesIncludingExplore() {
        val summaries = listOf(
            SubAgentInteractionSummary("coder", title = "Implement layout", agentName = "编码员", status = SubAgentInteractionStatus.COMPLETED),
            SubAgentInteractionSummary("reviewer", title = "Review layout", agentName = "审核员", status = SubAgentInteractionStatus.COMPLETED),
            SubAgentInteractionSummary("explorer", title = "Find components", agentName = "Explore", status = SubAgentInteractionStatus.COMPLETED)
        )
        val texts = measure {
            CompositionLocalProvider(LocalSubAgentCardHost provides SubAgentCardHost(
                summaries = summaries.associateBy { it.subSessionId }
            )) {
                SubAgentGroupCapsule(
                    capsules = summaries.map {
                        SubAgentTimelineCapsule(it.subSessionId, anchorMessageId = null, title = "Fallback task", fallbackAgentName = "Fallback agent")
                    },
                    expanded = false,
                    onToggle = {}
                )
            }
        }

        val names = texts.single { it.layout.layoutInput.text.text == "编码员 · 审核员 · Explore" }
        assertFalse(names.layout.didOverflowHeight)
        assertTrue(names.bounds.height > 0f)
        assertTrue(texts.none { "Fallback" in it.layout.layoutInput.text.text })
    }

    @Test
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    fun childCardAndSidebarShowTheSameRealNameAboveTheTaskWhileRootTitleStaysUnchanged() {
        val summary = SubAgentInteractionSummary("coder", title = "Implement compact tools", agentName = "编码员",
            status = SubAgentInteractionStatus.COMPLETED)
        val texts = measure {
            Column {
                CompositionLocalProvider(LocalSubAgentCardHost provides SubAgentCardHost(summaries = mapOf("coder" to summary))) {
                    SubAgentInteractionCapsule("coder", fallbackAgentName = "Fallback agent")
                }
                ChatSessionRow(
                    session = ChatSession("coder", summary.title, 0, 0, parentId = "parent", subagentType = summary.agentName),
                    selected = false, onClick = {}, onLongClick = {}
                )
                ChatSessionRow(
                    session = ChatSession("parent", "Parent title", 0, 0, subagentType = "not a child"),
                    selected = false, onClick = {}, onLongClick = {}
                )
            }
        }
        val names = texts.filter { it.layout.layoutInput.text.text == "编码员" }
        val tasks = texts.filter { it.layout.layoutInput.text.text == summary.title }
        assertEquals(2, names.size)
        assertEquals(2, tasks.size)
        names.zip(tasks).forEach { (name, task) -> assertTrue(name.bounds.top < task.bounds.top) }
        assertEquals(1, texts.count { it.layout.layoutInput.text.text == "Parent title" })
        assertTrue(texts.none { it.layout.layoutInput.text.text in setOf("Fallback agent", "not a child") })
    }

    @Test
    fun completionStatisticsAndContextRingWaitTogetherEvenWhenActionsAreHidden() {
        val reply = AgentUIMessage(
            id = "reply", role = MessageRole.ASSISTANT, content = "",
            contextInputTokens = 20, contextInputBudget = 1_000,
            attachments = listOf(AgentAttachment("report.txt", "/work/report.txt", "", "text/plain", 10, false)),
        )
        for (settled in listOf(false, true)) {
            val texts = measure {
                AgentMessageItem(
                    message = reply,
                    showActions = false,
                    playbackSettled = settled,
                    taskDurationMs = 42_000,
                    turnUsage = TurnUsage(1_000, 200, 940),
                )
            }.map { it.layout.layoutInput.text.text }
            assertTrue("report.txt" in texts)
            for (statistic in listOf("↑1.0k ↓200", "94%", "42s", "≈2%")) {
                assertEquals("statistic=$statistic; texts=$texts", settled, statistic in texts)
            }
        }
    }

    private fun message(id: String = "tool") = AgentUIMessage(
        id = id,
        role = MessageRole.TOOL,
        content = "File inspected",
        timestamp = 0,
        toolName = "readFile",
        toolArgs = "{\"path\":\"/workspace/file.txt\"}",
        toolActionTitle = "Inspect file"
    )

    private data class MeasuredText(val bounds: Rect, val layout: TextLayoutResult)

    private fun describeTexts(texts: List<MeasuredText>): String = texts.joinToString { measured ->
        val layout = measured.layout
        val input = layout.layoutInput
        "text=${input.text}, size=${layout.size}, fontSize=${input.style.fontSize}, " +
            "lineHeight=${input.style.lineHeight}, density=${input.density.density}, " +
            "fontScale=${input.density.fontScale}, bounds=${measured.bounds}, lines=${layout.lineCount}, " +
            "overflowWidth=${layout.didOverflowWidth}, overflowHeight=${layout.didOverflowHeight}"
    }

    private fun measure(
        fontScale: Float = 1f,
        content: @Composable () -> Unit
    ): List<MeasuredText> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            // Metadata is prepared off-main; it can change the chevron, not the collapsed height.
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2_000, View.MeasureSpec.AT_MOST)
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            val root = view.getChildAt(0) as ViewRootForTest
            root.measureAndLayoutForTest()
            return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).mapNotNull { node ->
                val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                    ?: return@mapNotNull null
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(getLayout(layouts))
                MeasuredText(node.boundsInRoot, layouts.single())
            }
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
