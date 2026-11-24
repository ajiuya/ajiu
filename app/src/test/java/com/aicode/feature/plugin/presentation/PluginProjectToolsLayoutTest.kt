package com.aicode.feature.plugin.presentation

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h900dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PluginProjectToolsLayoutTest {
    @Test(timeout = 30_000)
    fun emptyAndSmallListsStayAt438RatherThanFillingTheirHeightBound(): Unit = render {
        show(emptyList())
        assertPanel(438f, 320f)
        assertTrue(hasText(label(R.string.plugin_project_tools_empty)))
        assertTrue(tag("plugin-project-tools-list").size.height in 1..250)
        assertEquals(0f, tag("plugin-project-tools-list").config[SemanticsProperties.VerticalScrollAxisRange].maxValue(), 0f)
        tools = listOf(tool(0))
        pinned = setOf(tool(0).key)
        settle()
        assertPanel(438f, 320f)
        assertTrue(hasText("Tool 0"))
        assertFalse(hasText(label(R.string.plugin_project_tools_empty)))
        assertTrue(tag("plugin-project-tools-list").size.height in 1..250)
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontKeeps16dpMarginsAndScrollableToolsInsideThe80PercentCap(): Unit = render(320, 640, 1.5f) {
        show(emptyList())
        assertPanel(438f, 288f)
        tools = (0 until 40).map(::tool)
        pinned = tools.map { it.key }.toSet()
        settle()
        assertPanel((640f - 48f) * 0.8f, 288f)
        assertEquals(1.5f, tag("plugin-project-tools-close").layoutInfo.density.fontScale, 0f)
        assertScrollingKeepsHeaderVisible()
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w1200dp-h1000dp")
    fun tabletStillUses320dpAndContentCanGrowPast438ButNotPast80Percent(): Unit = render(1200, 1000) {
        show(listOf(tool(0)))
        assertPanel(438f, 320f)
        tools = (0 until 40).map(::tool)
        pinned = tools.map { it.key }.toSet()
        settle()
        assertPanel((1000f - 48f) * 0.8f, 320f)
        assertTrue(tag("plugin-project-tools-dialog").size.height > 438)
        assertScrollingKeepsHeaderVisible()
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w800dp-h320dp")
    fun landscapeShrinksEvenAnEmptyPanelBelow438AndLongListsStillScroll(): Unit = render(800, 320, 1.5f) {
        show(emptyList())
        assertPanel((320f - 48f) * 0.8f, 320f)
        tools = (0 until 40).map(::tool)
        pinned = tools.map { it.key }.toSet()
        settle()
        assertPanel((320f - 48f) * 0.8f, 320f)
        assertScrollingKeepsHeaderVisible()
    }

    @Test(timeout = 30_000)
    fun filterPinAndOpenDispatchTheRealKeysAndObserveUpdatedPinnedState(): Unit = render {
        show(listOf(tool(0), tool(1)), setOf(tool(0).key))
        assertTrue(hasText("Tool 0"))
        assertFalse(hasText("Tool 1"))
        clickText("Tool 0")
        assertEquals(listOf(tool(0).key), opened)
        assertTrue(pinChanges.isEmpty())
        clickTag("plugin-project-tools-filter")
        assertTrue(hasText("Tool 1"))
        val switches = nodes().filter { it.config.contains(SemanticsProperties.ToggleableState) }.sortedBy { it.positionInRoot.y }
        assertEquals(listOf(ToggleableState.On, ToggleableState.Off), switches.map { it.config[SemanticsProperties.ToggleableState] })
        click(switches[1])
        assertEquals(listOf(tool(1).key to true), pinChanges)
        clickTag("plugin-project-tools-filter")
        assertTrue(hasText("Tool 1"))
        clickText("Tool 1")
        assertEquals(listOf(tool(0).key, tool(1).key), opened)
        assertEquals(0, closed)
    }

    @Test(timeout = 30_000)
    fun closeButtonBackdropTapAndSystemBackDismissButCardBlankAreaDoesNot(): Unit = render {
        show(emptyList())
        val panel = tag("plugin-project-tools-dialog").bounds()
        tap(panel.center.x, panel.bottom - 24f)
        assertEquals(0, closed)
        assertTrue(dialog().isShowing)
        clickTag("plugin-project-tools-close")
        assertEquals(1, closed)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
        show(emptyList())
        tap(8f, 8f)
        assertEquals(2, closed)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
        show(emptyList())
        dialog().onBackPressed()
        settle()
        assertEquals(3, closed)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }

    private class Ui(val activity: ComponentActivity, val view: ComposeView, val width: Int, val height: Int, val pump: (Int) -> Unit) {
        var visible by mutableStateOf(false)
        var tools by mutableStateOf(emptyList<PluginWorkspaceTool>())
        var pinned by mutableStateOf(emptySet<String>())
        var closed = 0
        val opened = mutableListOf<String>()
        val pinChanges = mutableListOf<Pair<String, Boolean>>()

        @Composable
        fun Content() {
            if (visible) PluginProjectToolsDialog(onClose = ::close) {
                PluginProjectToolsContent(tools, pinned, "en", { key, selected ->
                    pinChanges += key to selected
                    pinned = if (selected) pinned + key else pinned - key
                }, { opened += it }, ::close)
            }
        }
        private fun close() { closed++; visible = false }
        fun show(next: List<PluginWorkspaceTool>, selected: Set<String> = next.map { it.key }.toSet()) {
            tools = next
            pinned = selected
            visible = true
            settle()
        }
        fun dialog(): android.app.Dialog = requireNotNull(ShadowDialog.getLatestDialog()).also { assertTrue(it.isShowing) }
        fun nodes(): Collection<SemanticsNode> {
            val window = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: view
            return requireNotNull(findRoot(window)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        }
        fun tag(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun label(resource: Int) = activity.getString(resource)
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun clickTag(value: String) = click(tag(value))
        fun clickText(value: String) = click(nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true })
        fun click(node: SemanticsNode) {
            var target: SemanticsNode? = node
            while (target != null && target.config.getOrNull(SemanticsActions.OnClick)?.action == null) target = target.parent
            assertTrue(requireNotNull(requireNotNull(target).config[SemanticsActions.OnClick].action).invoke())
            settle()
        }
        fun tap(x: Float, y: Float) {
            val root = requireNotNull(findRoot(requireNotNull(dialog().window).decorView)) as View
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(now, now + index * 16L, action, x, y, 0)
                try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                pump(1)
            }
            settle()
        }
        fun assertPanel(expectedHeight: Float, expectedWidth: Float) {
            val panel = tag("plugin-project-tools-dialog")
            val bounds = panel.bounds()
            assertEquals(expectedWidth, bounds.width, 0.6f)
            assertEquals(expectedHeight, bounds.height, 0.6f)
            assertEquals(width / 2f, bounds.center.x, 0.6f)
            assertEquals(height / 2f, bounds.center.y, 0.6f)
            assertTrue(bounds.left >= 16f && width - bounds.right >= 16f)
            assertTrue(bounds.top >= 24f && height - bounds.bottom >= 24f)
            assertTrue(bounds.height <= (height - 48f) * 0.8f + 0.6f)
            assertRoundedShape(panel, 16f)
            assertInside(tag("plugin-project-tools-close"), panel)
            assertInside(tag("plugin-project-tools-filter"), panel)
            assertInside(tag("plugin-project-tools-list"), panel)
        }
        fun assertScrollingKeepsHeaderVisible() {
            val closeBefore = tag("plugin-project-tools-close").bounds()
            val before = tag("plugin-project-tools-list").config[SemanticsProperties.VerticalScrollAxisRange].value()
            val list = tag("plugin-project-tools-list")
            assertTrue(list.size.height > 0)
            assertTrue(list.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > before)
            assertTrue(requireNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 300f))
            settle()
            assertTrue(tag("plugin-project-tools-list").config[SemanticsProperties.VerticalScrollAxisRange].value() > before)
            assertEquals(closeBefore, tag("plugin-project-tools-close").bounds())
            assertInside(tag("plugin-project-tools-list"), tag("plugin-project-tools-dialog"))
        }
        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(100) { frame ->
                pump(1)
                val geometry = nodes().map { "${it.id}:${it.positionInRoot}:${it.size}" }
                stable = if (geometry == previous) stable + 1 else 0
                if (stable >= 4 && frame >= 16) return
                previous = geometry
            }
            error("Project tools layout did not settle")
        }
    }

    private fun render(width: Int = 400, height: Int = 900, fontScale: Float = 1f, inspect: Ui.() -> Unit): Unit = runTest(timeout = 20.seconds) {
        val previousFontScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        val previousInspector = isDebugInspectorInfoEnabled
        isDebugInspectorInfoEnabled = true
        var frame = 0L
        fun layout(window: View) {
            window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            findRoot(window)?.measureAndLayoutForTest()
        }
        val ui = Ui(activity, view, width, height) { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::layout)
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme { ui.Content() }
                }
            }
            activity.setContentView(view)
            ui.pump(12)
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(previousFontScale)
            isDebugInspectorInfoEnabled = previousInspector
        }
    }

    companion object {
        private fun tool(index: Int) = PluginWorkspaceTool("layout.sample", "tool-$index", LocalizedText.single("Tool $index"),
            LocalizedText.single("Tool description $index"), "settings", "general", "tool.html", File("/workspace/plugin"), "1.0.0")
        private fun SemanticsNode.bounds() = Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)
        private fun assertInside(node: SemanticsNode, parent: SemanticsNode) {
            val inner = node.bounds()
            val outer = parent.bounds()
            assertTrue("$inner outside $outer", inner.left >= outer.left - 0.6f && inner.top >= outer.top - 0.6f &&
                inner.right <= outer.right + 0.6f && inner.bottom <= outer.bottom + 0.6f)
        }
        private fun assertRoundedShape(node: SemanticsNode, radius: Float) {
            val shapes = node.layoutInfo.getModifierInfo().flatMap { info ->
                (info.modifier as? InspectableValue)?.inspectableElements?.mapNotNull { it.value as? Shape }?.toList().orEmpty()
            }
            assertTrue("Missing production $radius dp rounded shape: $shapes", shapes.any {
                val outline = it.createOutline(Size(node.size.width.toFloat(), node.size.height.toFloat()), LayoutDirection.Ltr, Density(1f))
                outline is Outline.Rounded && listOf(outline.roundRect.topLeftCornerRadius, outline.roundRect.topRightCornerRadius,
                    outline.roundRect.bottomLeftCornerRadius, outline.roundRect.bottomRightCornerRadius)
                    .all { corner -> abs(corner.x - radius) <= 0.5f && abs(corner.y - radius) <= 0.5f }
            })
        }
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
