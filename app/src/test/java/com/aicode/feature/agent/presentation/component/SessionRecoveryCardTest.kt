package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.ReasoningEffort
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

private const val BUBBLE = "session_recovery_bubble"
private const val CONTINUE = "session_recovery_continue"
private const val ABANDON = "session_recovery_abandon"
private const val COMPOSER_ROW = "chat_composer_row"
private const val FOOTER = "recovery_test_footer"

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    qualifiers = "zh-rCN-w400dp-h1000dp",
    application = Application::class,
    manifest = Config.NONE
)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SessionRecoveryCardTest {
    @Test(timeout = 30_000)
    fun emptyQueueHasNoBubbleAndMultiplePendingItemsShareOneBubble() {
        val pendingCount = mutableStateOf(0)
        var calls = 0
        render({
            SessionRecoveryBubble(pendingCount.value, true, true, false, { calls++ }, { calls++ })
        }) {
            assertFalse(nodes().any { it.tag() in listOf(BUBBLE, CONTINUE, ABANDON) })
            pendingCount.value = 1
            awaitUi("One pending request") { nodes().any { it.tag() == BUBBLE } }
            assertNull(node(CONTINUE).config.getOrNull(SemanticsProperties.StateDescription))
            pendingCount.value = 3
            val count = activity.getString(R.string.session_recovery_bubble_count, 3)
            awaitUi("Compact pending count") { count in texts() }
            assertEquals(1, nodes().count { it.tag() == BUBBLE })
            assertEquals(
                activity.getString(R.string.session_recovery_pending_count, 3),
                node(CONTINUE).config.getOrNull(SemanticsProperties.StateDescription)
            )
            assertFalse(hasDialog())
            assertEquals(0, calls)
        }
    }

    @Test(timeout = 30_000)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun bubbleFitsTheExistingComposerRowAndEachHalfDispatchesImmediately() {
        val pendingCount = mutableStateOf(0)
        val callbacks = mutableListOf<String>()
        render({
            InputBar(value = "", onValueChange = {}, recoveryBubble = {
                SessionRecoveryBubble(pendingCount.value, true, true, false,
                    { callbacks += CONTINUE }, { callbacks += ABANDON })
            })
        }) {
            val footerHeight = node(FOOTER).boundsInRoot.height
            val row = node(COMPOSER_ROW).boundsInRoot
            assertEquals(36f, row.height, 0.01f)
            assertTrue(nodes().none { it.tag() == BUBBLE })

            pendingCount.value = 2
            awaitUi("Bubble in the existing composer row") { nodes().any { it.tag() == BUBBLE } }
            assertEquals(footerHeight, node(FOOTER).boundsInRoot.height, 0.01f)
            assertEquals(row, node(COMPOSER_ROW).boundsInRoot)
            val bubble = node(BUBBLE).boundsInRoot
            assertEquals(104f, bubble.width, 0.01f)
            assertEquals(30f, bubble.height, 0.01f)
            assertEquals(row.right, bubble.right, 0.01f)
            assertTrue(bubble.top >= row.top && bubble.bottom <= row.bottom)
            val placeholder = activity.getString(R.string.chat_input_placeholder)
            assertTrue(nodes().single { placeholder in it.texts() }.layoutBounds().right <= bubble.left)
            assertTrue(editor().layoutBounds().right <= bubble.left)

            val continueButton = node(CONTINUE)
            val abandonButton = node(ABANDON)
            for (button in listOf(continueButton, abandonButton)) {
                assertEquals(Role.Button, button.config.getOrNull(SemanticsProperties.Role))
                assertTrue(button.config.contains(SemanticsActions.OnClick))
            }
            val continueBounds = continueButton.boundsInRoot
            val abandonBounds = abandonButton.boundsInRoot
            assertTrue(continueBounds.right < abandonBounds.left)
            assertEquals(30f, continueBounds.height, 0.01f)
            assertEquals(30f, abandonBounds.height, 0.01f)
            tap(Offset(continueBounds.right - 2f, continueBounds.center.y))
            assertEquals(listOf(CONTINUE), callbacks)
            tap(Offset(abandonBounds.left + 2f, abandonBounds.center.y))
            assertEquals(listOf(CONTINUE, ABANDON), callbacks)
            assertFalse(hasDialog())
        }
    }

    @Test(timeout = 30_000)
    fun confirmationChangesTheVisibleLabelAndAccessibleNameWhileKeepingTheContinueAction() {
        val confirming = mutableStateOf(false)
        var continued = 0
        render({ SessionRecoveryBubble(1, true, true, confirming.value, { continued++ }, {}) }) {
            assertEquals(listOf("继续"), node(CONTINUE).description())
            confirming.value = true
            awaitUi("Continue in confirmation state") { "确认继续" in texts() }
            assertEquals(listOf("确认继续"), node(CONTINUE).description())
            assertEquals(listOf("放弃"), node(ABANDON).description())
            assertTrue(requireNotNull(node(CONTINUE).config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            assertEquals(1, continued)
            assertFalse(hasDialog())
        }
    }

    @Test(timeout = 30_000)
    fun disabledActionsNeverDispatchAndEachActionCanBeEnabledIndependently() {
        val canContinue = mutableStateOf(false)
        val callbacks = mutableListOf<String>()
        render({
            SessionRecoveryBubble(1, canContinue.value, !canContinue.value, false,
                { callbacks += CONTINUE }, { callbacks += ABANDON })
        }) {
            assertTrue(node(CONTINUE).isDisabled())
            assertFalse(node(ABANDON).isDisabled())
            node(CONTINUE).config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            tap(node(CONTINUE).boundsInRoot.center)
            assertTrue(callbacks.isEmpty())
            tap(node(ABANDON).boundsInRoot.center)
            assertEquals(listOf(ABANDON), callbacks)

            canContinue.value = true
            awaitUi("Independent availability") { node(ABANDON).isDisabled() && !node(CONTINUE).isDisabled() }
            node(ABANDON).config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
            tap(node(ABANDON).boundsInRoot.center)
            assertEquals(listOf(ABANDON), callbacks)
            tap(node(CONTINUE).boundsInRoot.center)
            assertEquals(listOf(ABANDON, CONTINUE), callbacks)
        }
    }

    @Test(timeout = 30_000)
    fun narrowLargeFontComposerStillEditsAndExpandsWithoutCrossingTheRecoveryTargets() {
        val value = mutableStateOf(List(20) { "Draft line" }.joinToString("\n"))
        val fullScreen = mutableListOf<Boolean>()
        var abandons = 0
        render(
            widthPx = 240,
            fontScale = 2.5f,
            content = {
                InputBar(value.value, { value.value = it }, recoveryBubble = {
                    SessionRecoveryBubble(3, true, true, true, {}, { abandons++ })
                }, onFullScreenInputChanged = { fullScreen += it })
            }
        ) {
            val expandLabel = activity.getString(R.string.chat_input_expand)
            awaitUi("Expand and recovery actions") {
                nodes(merged = true).any { expandLabel in it.description() && it.config.contains(SemanticsActions.OnClick) }
            }
            val expand = nodes(merged = true).single { expandLabel in it.description() && it.config.contains(SemanticsActions.OnClick) }
            val bubble = node(BUBBLE).boundsInRoot
            val row = node(COMPOSER_ROW).boundsInRoot
            assertEquals(row.right, bubble.right, 0.01f)
            assertTrue(bubble.width in 1f..104f && bubble.height > 0f)
            assertEquals(listOf(activity.getString(R.string.session_recovery_confirm_continue)), node(CONTINUE).description())
            assertTrue(editor().layoutBounds().width >= 24f)
            assertTrue(editor().layoutBounds().right <= expand.boundsInRoot.left)
            assertTrue(expand.boundsInRoot.right <= bubble.left)

            val updatedDraft = value.value + "\nEdited"
            val input = editor()
            assertTrue(requireNotNull(input.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            awaitUi("Editor focused") { editor().config.getOrNull(SemanticsProperties.Focused) == true }
            assertTrue(requireNotNull(editor().config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(updatedDraft)))
            assertEquals(updatedDraft, value.value)
            tap(node(ABANDON).boundsInRoot.center)
            assertEquals(1, abandons)
            assertFalse(hasDialog())
            tap(expand.boundsInRoot.center)
            awaitUi("Full-screen editor") { hasDialog() && fullScreen.lastOrNull() == true }
            assertEquals(1, abandons)
        }
    }

    @Composable
    private fun InputBar(
        value: String,
        onValueChange: (String) -> Unit,
        recoveryBubble: @Composable () -> Unit,
        onFullScreenInputChanged: (Boolean) -> Unit = {}
    ) {
        ChatInputBar(
            value = value,
            onValueChange = onValueChange,
            onSend = {},
            onStop = {},
            isBusy = false,
            workspaceViewModel = null,
            hasRunningSessions = { false },
            activeProvider = null,
            providers = emptyList(),
            modelMetadata = emptyMap(),
            onSelectModel = { _, _ -> },
            reasoningEffort = ReasoningEffort.MEDIUM,
            onReasoningEffortChange = {},
            pendingAttachments = emptyList(),
            onRemoveAttachment = {},
            canUploadFiles = true,
            canUploadImages = true,
            onUploadFile = {},
            onUploadImage = {},
            onTakePhoto = {},
            modifier = Modifier.testTag(FOOTER),
            recoveryBubble = recoveryBubble
        ) { isFullScreen -> onFullScreenInputChanged(isFullScreen) }
    }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)
    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    private fun SemanticsNode.description(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun SemanticsNode.isDisabled(): Boolean = config.contains(SemanticsProperties.Disabled)
    private fun SemanticsNode.layoutBounds(): Rect {
        val layout = layoutInfo
        return Rect(layout.coordinates.localToRoot(Offset.Zero), Size(layout.width.toFloat(), layout.height.toFloat()))
    }

    private fun findComposeRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                findComposeRoot(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private inner class RecoveryUi(
        val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit
    ) {
        fun nodes(merged: Boolean = false): List<SemanticsNode> =
            findComposeRoot(view)?.semanticsOwner?.getAllSemanticsNodes(mergingEnabled = merged).orEmpty()
        fun node(tag: String): SemanticsNode = nodes().single { it.tag() == tag }
        fun texts(): List<String> = nodes().flatMap { it.texts() }
        fun editor(): SemanticsNode = nodes().single { it.config.contains(SemanticsActions.SetText) }
        fun hasDialog(): Boolean = ShadowDialog.getLatestDialog()?.isShowing == true

        fun tap(position: Offset) {
            val root = findComposeRoot(view) as View
            val downTime = SystemClock.uptimeMillis()
            val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = position.x
                y = position.y
                pressure = 1f
                size = 1f
            }
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                    arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    root.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                advanceFrame()
            }
        }

        fun awaitUi(message: String, condition: () -> Boolean) {
            repeat(180) {
                advanceFrame()
                if (condition()) return
            }
            fail("$message did not complete within 180 controlled frames; tags=${nodes().mapNotNull { it.tag() }}")
        }
    }

    private fun render(
        content: @Composable () -> Unit,
        widthPx: Int = 400,
        fontScale: Float = 1f,
        inspect: RecoveryUi.() -> Unit
    ) = runTest(timeout = 15.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var frame = 0L
        val ui = RecoveryUi(activity, view) {
            repeat(4) { looper.runOneTask() }
            Snapshot.sendApplyNotifications()
            runCurrent()
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
            Snapshot.sendApplyNotifications()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            findComposeRoot(view)?.measureAndLayoutForTest()
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            ui.awaitUi("Initial composition") { ui.nodes().isNotEmpty() }
            repeat(6) {
                // Give the text measurement and editor-width update an initial frame each.
                ui.awaitUi("Initial layout") { findComposeRoot(view) != null }
            }
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
