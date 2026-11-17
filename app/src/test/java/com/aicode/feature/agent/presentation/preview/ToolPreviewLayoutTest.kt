package com.aicode.feature.agent.presentation.preview

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.presentation.component.TaskOperationAction
import com.aicode.feature.agent.presentation.component.TaskOperationContent
import com.aicode.feature.agent.presentation.component.TaskOperationItem
import com.aicode.feature.agent.presentation.component.TaskOperationPresentation
import com.aicode.feature.agent.presentation.component.TaskOperationStatus
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
import org.robolectric.annotation.TextLayoutMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class)
class ToolPreviewLayoutTest {
    @Test fun geometryHasRc2OverhangInsetBottomAndRowGap() {
        assertEquals(38f, ToolPreviewMetrics.barHeight.value, 0f)
        assertEquals(100f, ToolPreviewMetrics.thumbnailWidth.value, 0f)
        assertEquals(65f, ToolPreviewMetrics.thumbnailHeight.value, 0f)
        assertEquals(27f, ToolPreviewMetrics.overhang.value, 0f)
        assertEquals(10f, ToolPreviewMetrics.thumbnailInset.value, 0f)
        assertEquals(6f, ToolPreviewMetrics.bottomPadding.value, 0f)
        assertEquals(8f, ToolPreviewMetrics.rowGap.value, 0f)
        assertEquals(118f, ToolPreviewMetrics.barStartPadding(true).value, 0f)
        assertEquals(79f, ToolPreviewMetrics.listBottomReserve(true).value, 0f)
        assertEquals(52f, ToolPreviewMetrics.listBottomReserve(false).value, 0f)
        assertEquals(0.9f, ToolPreviewMetrics.detailHeightFraction, 0f)
        assertEquals(2048, TOOL_DETAIL_MAX_EDGE_PX)
    }

    @Test fun actualBarKeeps38DpWithPreviewOffAndHidesSingleItemPager() {
        lateinit var bounds: Rect
        val target = ToolPreviewTarget("id", "Bash", "{}", "done", ToolPreviewStage.COMPLETED, actionTitle = "Inspect public files")
        val texts = measure {
            Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                FloatingToolStatusBar(target, buildToolPreviewDisplay(target), null, listOf(target), 0,
                    ToolPreviewHostState("session"), false, ToolPreviewPresentation(actionTitle = safePreviewActionTitle(target)), {})
            }
        }
        assertEquals(38f, bounds.height, 0.01f)
        assertTrue(texts.contains("Inspect public files"))
        assertFalse(texts.contains("1/1"))
    }

    @Test fun actualThumbnailKeeps100By65AndMultipleItemsHaveCompactPager() {
        lateinit var bounds: Rect
        val target = ToolPreviewTarget("id", "Bash", "{}", "done", ToolPreviewStage.COMPLETED)
        val texts = measure {
            Column {
                Box(Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) {
                    ToolPreviewThumbnail(target, buildToolPreviewDisplay(target), null, {}, {})
                }
            }
        }
        assertEquals(100f, bounds.width, 0.01f)
        assertEquals(65f, bounds.height, 0.01f)
        assertTrue(texts.contains("done"))
        val pages = measure { FloatingToolStatusBar(target, buildToolPreviewDisplay(target), null,
            listOf(target, target.copy(id = "second")), 0, ToolPreviewHostState("session"), false, ToolPreviewPresentation(), {}) }
        assertTrue(pages.contains("1/2"))
    }

    @Test fun actualTitleIsCenteredBetweenTheStatusAndActualPager() {
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) for (thumbnail in listOf(false, true)) {
            for ((count, index) in listOf(1 to 0, 2 to 0, 11 to 10, 100 to 99)) {
                val target = ToolPreviewTarget("call-$index", "Bash", "{}", "done", ToolPreviewStage.COMPLETED,
                    actionTitle = "Inspect public files")
                val targets = List(count) { target.copy(id = "call-$it") }
                measure(inspect = { root ->
                    val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                    val title = nodes.single { target.actionTitle in it.texts() }
                    val bounds = title.layoutBounds()
                    val layout = title.textLayout()
                    assertTrue(bounds.width > 0f)
                    assertEquals(TextAlign.Center, layout.layoutInput.style.textAlign)
                    assertEquals(13.sp, layout.layoutInput.style.fontSize)
                    assertEquals(1, layout.layoutInput.maxLines)
                    assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
                    val status = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains((root as View).context.getString(R.string.tool_preview_completed)) == true }
                    val statusBounds = status.layoutBounds()
                    if (direction == LayoutDirection.Ltr) assertTrue(bounds.left >= statusBounds.right + ToolPreviewMetrics.statusGap.value - 0.51f)
                    else assertTrue(bounds.right <= statusBounds.left - ToolPreviewMetrics.statusGap.value + 0.51f)
                    val page = nodes.singleOrNull { "${index + 1}/$count" in it.texts() }?.layoutBounds()
                    val gap = ToolPreviewMetrics.statusGap.value
                    val titleLeft = if (direction == LayoutDirection.Ltr) statusBounds.right + gap
                        else page?.let { it.right + ToolPreviewMetrics.arrowSize.value + gap } ?: ToolPreviewMetrics.barEndPadding.value
                    val titleRight = if (direction == LayoutDirection.Rtl) statusBounds.left - gap
                        else page?.let { it.left - ToolPreviewMetrics.arrowSize.value - gap } ?: (400f - ToolPreviewMetrics.barEndPadding.value)
                    assertEquals("thumbnail=$thumbnail; direction=$direction; page=${index + 1}/$count; bounds=$bounds",
                        (titleLeft + titleRight) / 2, bounds.center.x, 0.51f)
                    assertEquals(titleRight - titleLeft, bounds.width, 1f)
                    if (thumbnail) {
                        if (direction == LayoutDirection.Ltr) assertTrue(bounds.left >= 110f)
                        else assertTrue(bounds.right <= 290f)
                    }
                }) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        FloatingToolStatusBar(target, buildToolPreviewDisplay(target), null, targets, index,
                            ToolPreviewHostState("session"), thumbnail, ToolPreviewPresentation(actionTitle = target.actionTitle), {})
                    }
                }
            }
        }
    }

    @Test fun longTitlesUseAllTheSpaceBetweenActualControlsAtNarrowWidths() {
        for (width in listOf(320, 400)) for (thumbnail in listOf(false, true)) {
            val target = ToolPreviewTarget("call-99", "Bash", "{}", "done", ToolPreviewStage.COMPLETED,
                actionTitle = "Inspect public files ".repeat(40))
            val targets = List(100) { target.copy(id = "call-$it") }
            measure(widthPx = width, inspect = { root ->
                val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                val title = nodes.single { target.actionTitle in it.texts() }
                val bounds = title.layoutBounds()
                val status = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains((root as View).context.getString(R.string.tool_preview_completed)) == true }.layoutBounds()
                val page = nodes.single { "100/100" in it.texts() }.layoutBounds()
                assertTrue(bounds.width > 0f)
                assertTrue(bounds.left >= status.right + ToolPreviewMetrics.statusGap.value - 0.51f)
                assertTrue(bounds.right <= page.left - ToolPreviewMetrics.arrowSize.value - ToolPreviewMetrics.statusGap.value + 0.51f)
                val left = status.right + ToolPreviewMetrics.statusGap.value
                val right = page.left - ToolPreviewMetrics.arrowSize.value - ToolPreviewMetrics.statusGap.value
                assertEquals((left + right) / 2, bounds.center.x, 0.51f)
                assertEquals(right - left, bounds.width, 1f)
                val input = title.textLayout().layoutInput
                assertEquals(TextAlign.Center, input.style.textAlign)
                assertEquals(13.sp, input.style.fontSize)
                assertEquals(1, input.maxLines)
                assertEquals(TextOverflow.Ellipsis, input.overflow)
            }) {
                FloatingToolStatusBar(target, buildToolPreviewDisplay(target), null, targets, 99,
                    ToolPreviewHostState("session"), thumbnail, ToolPreviewPresentation(actionTitle = target.actionTitle), {})
            }
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    @Test fun narrowThumbnailBarsCenterEllipsizedTitlesInTheActualSlotWithScaledFontsAndBothDirections() =
        measureNarrowTitleSlots(requireEllipsisGlyph = true)

    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    @Test fun narrowThumbnailBarsUseTheWholeMeasuredControlSlotWithScaledFontsAndBothDirections() =
        measureNarrowTitleSlots(requireEllipsisGlyph = false)

    private fun measureNarrowTitleSlots(requireEllipsisGlyph: Boolean) {
        for (width in listOf(240, 288, 328, 400)) for (count in listOf(11, 100, 1_000)) for (fontScale in listOf(1f, 1.3f)) {
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                val target = ToolPreviewTarget("call-${count - 1}", "Bash", "{}", "done", ToolPreviewStage.COMPLETED,
                    actionTitle = "列出可用的环境安装脚本与开发工具".repeat(40))
                val targets = List(count) { target.copy(id = "call-$it") }
                measure(widthPx = width, fontScale = fontScale, inspect = { root ->
                    val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                    val title = nodes.single { target.actionTitle in it.texts() }
                    val bounds = title.layoutBounds()
                    val status = nodes.single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains((root as View).context.getString(R.string.tool_preview_completed)) == true
                    }.layoutBounds()
                    val page = nodes.single { "$count/$count" in it.texts() }.layoutBounds()
                    val pagerLeft = page.left - ToolPreviewMetrics.arrowSize.value
                    val pagerRight = page.right + ToolPreviewMetrics.arrowSize.value
                    val gap = ToolPreviewMetrics.statusGap.value
                    val remainingLeft = if (direction == LayoutDirection.Ltr) status.right + gap else pagerRight + gap
                    val remainingRight = if (direction == LayoutDirection.Ltr) pagerLeft - gap else status.left - gap
                    val diagnostic = "width=$width; page=$count; fontScale=$fontScale; direction=$direction; title=$bounds; status=$status; pager=$pagerLeft..$pagerRight"
                    assertTrue(diagnostic, bounds.width > 0f)
                    assertTrue(diagnostic, bounds.left >= remainingLeft - 0.51f)
                    assertTrue(diagnostic, bounds.right <= remainingRight + 0.51f)
                    assertEquals(diagnostic, (remainingLeft + remainingRight) / 2, bounds.center.x, 0.51f)
                    assertEquals(diagnostic, remainingRight - remainingLeft, bounds.width, 1f)
                    val layout = title.textLayout()
                    if (requireEllipsisGlyph) assertTrue(diagnostic, layout.isLineEllipsized(0))
                    val input = title.textLayout().layoutInput
                    assertEquals(TextAlign.Center, input.style.textAlign)
                    assertEquals(13.sp, input.style.fontSize)
                    assertEquals(fontScale, input.density.fontScale, 0f)
                    assertEquals(1, input.maxLines)
                    assertEquals(TextOverflow.Ellipsis, input.overflow)
                }) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        FloatingToolStatusBar(target, buildToolPreviewDisplay(target), null, targets, count - 1,
                            ToolPreviewHostState("session"), true, ToolPreviewPresentation(actionTitle = target.actionTitle), {})
                    }
                }
            }
        }
    }

    @Test fun titleAndPagerRetainDetailClickLongPressAndFirstLastSelectionSemantics() {
        val target = ToolPreviewTarget("call-50", "Bash", "{}", "done", ToolPreviewStage.COMPLETED, actionTitle = "Inspect public files")
        val targets = List(100) { target.copy(id = "call-$it") }
        val state = ToolPreviewHostState("session")
        measure(inspect = { root ->
            val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            val title = nodes.single { target.actionTitle in it.texts() }
            val context = (root as View).context
            assertEquals(Role.Button, title.config.getOrNull(SemanticsProperties.Role))
            assertEquals(context.getString(R.string.tool_preview_open_detail), title.config.getOrNull(SemanticsActions.OnClick)?.label)
            assertEquals(context.getString(R.string.tool_preview_options), title.config.getOrNull(SemanticsActions.OnLongClick)?.label)
            assertNotNull(title.config.getOrNull(SemanticsActions.OnLongClick)?.action)
            assertTrue(requireNotNull(title.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            assertTrue(state.detailOpen)
            assertEquals(target.id, state.detailMessageId)
            for ((description, expectedClick, expectedLongPress) in listOf(
                Triple(R.string.tool_preview_previous, "call-49", "call-0"),
                Triple(R.string.tool_preview_next, "call-51", "call-99"),
            )) {
                val arrow = nodes.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(context.getString(description)) == true }
                assertTrue(requireNotNull(arrow.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
                assertEquals(expectedClick, state.selectedMessageId)
                assertTrue(requireNotNull(arrow.config.getOrNull(SemanticsActions.OnLongClick)?.action).invoke())
                assertEquals(expectedLongPress, state.selectedMessageId)
                assertFalse(state.followingLatest)
            }
        }) {
            FloatingToolStatusBar(target, buildToolPreviewDisplay(target), null, targets, 50,
                state, true, ToolPreviewPresentation(actionTitle = target.actionTitle), {})
        }
    }

    @Test fun taskContentShowsExactRealNamePartialErrorAndAwaitingStateWithoutApprovalControls() {
        val presentation = TaskOperationPresentation(TaskOperationAction.READ, listOf(
            TaskOperationItem(name = "编码员", task = "Implement", status = TaskOperationStatus.AWAITING_CONFIRMATION,
                warning = "captured warning", error = "captured error")), partial = true)
        val texts = measure { TaskOperationContent(presentation, "{}", "", false) }
        assertTrue(texts.any { it.contains("编码员") })
        assertTrue(texts.contains("captured warning"))
        assertTrue(texts.contains("captured error"))
        assertTrue(texts.none { it == "Explore" })
    }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    // Legacy zero-height text clips to Rect.Zero; layout coordinates still contain its real horizontal position.
    private fun SemanticsNode.layoutBounds(): Rect {
        val info = layoutInfo
        return Rect(info.coordinates.localToRoot(Offset.Zero), Size(info.width.toFloat(), info.height.toFloat()))
    }

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
        return layouts.single()
    }

    private fun measure(
        widthPx: Int = 400,
        fontScale: Float = 1f,
        inspect: (ViewRootForTest) -> Unit = {},
        content: @Composable () -> Unit,
    ): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                view.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            fun root(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) root(view.getChildAt(index))?.let { return it }
                return null
            }
            val owner = requireNotNull(root(view))
            owner.measureAndLayoutForTest()
            inspect(owner)
            return owner.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
            }
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
        }
    }
}
