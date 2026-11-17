package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
// Legacy 字宽不是实际字形度量；窄屏大字体只检查容器、样式输入和完整语义。
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class CompactionProgressBubbleTest {
    private val percentages = listOf(0, 37, 90, 95, 100)

    @Test(timeout = 30_000)
    fun chineseLabelsAppendEveryProgressValue() {
        assertProgressLabels("正在压缩上下文")
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "en")
    fun englishLabelsAppendEveryProgressValue() {
        assertProgressLabels("Compressing context")
    }

    @Test(timeout = 30_000)
    fun progressKeepsOriginalBusyIndicatorStylePositionHeightAndThreeDots() {
        lateinit var progressBody: Rect
        lateinit var originalBody: Rect
        lateinit var originalLabel: String
        var expectedStyle = TextStyle.Default
        var expectedColor = Color.Unspecified
        render(content = {
            expectedStyle = MaterialTheme.typography.labelMedium
            expectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            originalLabel = stringResource(R.string.chat_compressing_context)
            Column {
                Box(Modifier.onGloballyPositioned { progressBody = it.boundsInRoot() }) {
                    CompactionProgressBubble(37)
                }
                Box(Modifier.onGloballyPositioned { originalBody = it.boundsInRoot() }) {
                    AgentBusyIndicator(
                        label = originalLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = ChatStyle.toolRowMinHeight),
                        showDots = true
                    )
                }
            }
        }) { root, _ ->
            val layouts = textLayouts(root)
            val progress = layouts.single { it.layoutInput.text.text == "正在压缩上下文 37%" }
            val original = layouts.single { it.layoutInput.text.text == originalLabel }
            listOf(progress, original).forEach { layout ->
                val input = layout.layoutInput
                assertEquals(expectedColor, input.style.color)
                assertEquals(expectedStyle.fontSize, input.style.fontSize)
                assertEquals(expectedStyle.lineHeight, input.style.lineHeight)
                assertEquals(expectedStyle.fontWeight, input.style.fontWeight)
                assertEquals(1, input.maxLines)
                assertEquals(TextOverflow.Ellipsis, input.overflow)
            }
            assertEquals(32f, progressBody.height, 0.01f)
            assertEquals(originalBody.height, progressBody.height, 0.01f)
            assertEquals(1_000f, progressBody.width, 0.01f)
            assertEquals(originalBody.width, progressBody.width, 0.01f)

            val statuses = statusNodes(root)
            assertEquals(2, statuses.size)
            val progressStatus = statuses.single { description(it) == "正在压缩上下文 37%" }
            val originalStatus = statuses.single { description(it) == originalLabel }
            listOf(progressStatus to progressBody, originalStatus to originalBody).forEach { (status, body) ->
                assertEquals(body.left, status.boundsInRoot.left, 0.01f)
                assertEquals(body.center.y, status.boundsInRoot.center.y, 0.01f)
                assertThreeDots(status)
            }
        }
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "en")
    fun narrowContainerAtLargeFontScaleKeepsFullProgressSemantics() {
        val bodies = mutableMapOf<Int, Rect>()
        var expectedStyle = TextStyle.Default
        render(width = 160, fontScale = 3f, content = {
            expectedStyle = MaterialTheme.typography.labelMedium
            Column {
                percentages.forEach { percent ->
                    Box(Modifier.onGloballyPositioned { bodies[percent] = it.boundsInRoot() }) {
                        CompactionProgressBubble(percent)
                    }
                }
            }
        }) { root, _ ->
            assertLabels(root, percentages.map { "Compressing context $it%" })
            assertEquals(percentages.toSet(), bodies.keys)
            bodies.values.forEach { body ->
                assertEquals(160f, body.width, 0.01f)
                assertTrue("The original minimum row height must survive", body.height >= 32f)
            }
            textLayouts(root).forEach { layout ->
                assertEquals(expectedStyle.fontSize, layout.layoutInput.style.fontSize)
                assertEquals(3f, layout.layoutInput.density.fontScale, 0f)
                assertEquals(1, layout.layoutInput.maxLines)
            }
        }
    }

    private fun assertProgressLabels(prefix: String) {
        render(content = {
            Column {
                percentages.forEach { percent -> CompactionProgressBubble(percent) }
            }
        }) { root, activity ->
            assertEquals(prefix, activity.getString(R.string.chat_compressing_context))
            assertLabels(root, percentages.map { "$prefix $it%" })
            statusNodes(root).forEach { assertThreeDots(it) }
        }
    }

    private fun assertLabels(root: ViewRootForTest, expected: List<String>) {
        assertEquals(expected.sorted(), textLayouts(root).map { it.layoutInput.text.text }.sorted())
        assertEquals(expected.sorted(), statusNodes(root).map { description(it) }.sorted())
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).forEach { node ->
            assertFalse(node.config.contains(SemanticsActions.OnClick))
            assertFalse(node.config.contains(SemanticsProperties.ProgressBarRangeInfo))
        }
    }

    private fun statusNodes(root: ViewRootForTest): List<SemanticsNode> =
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter { node ->
            node.config.isMergingSemanticsOfDescendants &&
                node.config.getOrNull(SemanticsProperties.ContentDescription) != null
        }

    private fun description(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.ContentDescription)!!.single()

    private fun assertThreeDots(status: SemanticsNode) {
        val content = layoutChildren(status.layoutInfo)
        assertEquals("The label and TypingDots must remain siblings", 2, content.size)
        val dots = content.last()
        assertEquals(26, dots.width)
        assertEquals(16, dots.height)
        val children = layoutChildren(dots)
        assertEquals(listOf(6, 4, 6, 4, 6), children.map { it.width })
        listOf(0, 2, 4).forEach { index -> assertEquals(6, children[index].height) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun layoutChildren(info: LayoutInfo): List<LayoutInfo> =
        info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>

    private fun textLayouts(root: ViewRootForTest): List<TextLayoutResult> =
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).mapNotNull { node ->
            val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
                ?: return@mapNotNull null
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(getLayout(layouts))
            layouts.single()
        }

    private fun render(
        width: Int = 1_000,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
        inspect: (ViewRootForTest, ComponentActivity) -> Unit
    ) = runTest(timeout = 15.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // 独立帧时钟保留无限动画，但不让它们在 paused looper 中持续自调度。
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            repeat(10) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2_000, View.MeasureSpec.AT_MOST)
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                (view.getChildAt(0) as ViewRootForTest).measureAndLayoutForTest()
                if (clock.hasAwaiters) clock.sendFrame((frame + 1L) * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
            }
            val root = view.getChildAt(0) as ViewRootForTest
            root.measureAndLayoutForTest()
            inspect(root, activity)
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }
}
