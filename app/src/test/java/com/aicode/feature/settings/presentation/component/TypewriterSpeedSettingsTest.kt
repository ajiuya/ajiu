package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.settings.data.repository.StartupSessionMode
import java.time.Duration
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class TypewriterSpeedSettingsTest {
    @Test(timeout = 30_000)
    fun sheetPreviewsDragBeforeSavingAndKeepsTheLatestChoiceDuringSlowReadback() = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var savedSpeed by mutableIntStateOf(30)
        val submitted = mutableListOf<Int>()
        val saves = mutableListOf<CompletableJob>()
        var frame = 0L
        fun layout(window: View): ViewRootForTest? {
            window.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            return findRoot(window)?.also { it.measureAndLayoutForTest() }
        }
        fun pump() {
            looper.idleFor(Duration.ofMillis(16))
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { layout(it) }
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        }
        fun waitFor(message: String, condition: () -> Boolean) {
            repeat(120) {
                pump()
                if (condition()) return
            }
            assertTrue(message, condition())
        }
        fun label(speed: Int): String = activity.getString(R.string.settings_typewriter_speed_value, speed)
        val title = activity.getString(R.string.settings_typewriter_speed)
        fun click(root: ViewRootForTest, text: String) {
            val click = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single {
                it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true
            }.config[SemanticsActions.OnClick]
            assertTrue(requireNotNull(click.action).invoke())
        }
        fun openSheet(host: ViewRootForTest, speed: Int): ViewRootForTest {
            click(host, title)
            var sheet: ViewRootForTest? = null
            waitFor("Speed sheet must open with ${label(speed)}") {
                val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                sheet = dialog?.window?.decorView?.let(::findRoot)
                sheet?.hasText(label(speed)) == true && sheet?.slider()?.boundsInRoot?.let { it.top >= 0 && it.bottom <= 1000 } == true
            }
            repeat(30) { pump() }
            return requireNotNull(sheet).also { assertNotSame(host, it) }
        }
        fun closeSheet(sheet: ViewRootForTest) {
            val action = sheet.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                .firstNotNullOf { it.config.getOrNull(SemanticsActions.Dismiss)?.action }
            assertTrue(action.invoke())
            waitFor("Speed sheet must close") { ShadowDialog.getLatestDialog()?.isShowing != true }
        }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) {
                        GeneralSettingsSection(
                            autoRemoveStaleModels = true, onToggleAutoRemoveStaleModels = {},
                            startupSessionMode = StartupSessionMode.NEW_SESSION, onSelectStartupSessionMode = {},
                            firstByteTimeoutSec = 300, onSetFirstByteTimeoutSec = {},
                            streamIdleTimeoutSec = 300, onSetStreamIdleTimeoutSec = {},
                            maxNetworkRetries = 6, onSetMaxNetworkRetries = {},
                            enterToSend = false, onToggleEnterToSend = {},
                            autoExpandReasoning = false, onToggleAutoExpandReasoning = {},
                            compactionThresholdPercent = 85, onSetCompactionThresholdPercent = {},
                            sendFileMaxSizeMb = 100, onSetSendFileMaxSizeMb = {},
                            goalMaxTurns = 50, onSetGoalMaxTurns = {},
                            goalMaxTokens = 500_000, onSetGoalMaxTokens = {},
                            deleteExternalWorkspaceSessions = false, onToggleDeleteExternalWorkspaceSessions = {},
                            typewriterCharsPerSecond = savedSpeed, onSetTypewriterCharsPerSecond = {
                                submitted += it
                                Job().also { save -> saves += save }
                            }
                        )
                    }
                }
            }
            activity.setContentView(view)
            waitFor("Preferences must show the typewriter setting") { findRoot(view)?.hasText(title) == true }
            val host = requireNotNull(findRoot(view))
            var sheet = openSheet(host, 30)
            assertEquals(30f..350f, sheet.slider().config[SemanticsProperties.ProgressBarRangeInfo].range)
            val bounds = sheet.slider().boundsInRoot
            val window = sheet as View
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int, x: Float) {
                val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coordinates = MotionEvent.PointerCoords().apply { this.x = x; y = bounds.center.y; pressure = 1f; size = 1f }
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                    arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    val delivered = window.dispatchTouchEvent(event)
                    if (action == MotionEvent.ACTION_DOWN) assertTrue(delivered)
                } finally { event.recycle() }
                repeat(4) { pump() }
            }
            touch(MotionEvent.ACTION_DOWN, bounds.left + 16f)
            for (fraction in listOf(0.2f, 0.4f, 0.5f)) touch(MotionEvent.ACTION_MOVE, bounds.left + bounds.width * fraction)
            val preview = sheet.slider().config[SemanticsProperties.ProgressBarRangeInfo].current.roundToInt()
            assertTrue("A real horizontal drag must update the displayed slider: $preview", preview in 100..280)
            assertTrue(sheet.hasText(label(preview)))
            assertTrue(submitted.isEmpty())
            touch(MotionEvent.ACTION_UP, bounds.center.x)
            assertEquals(listOf(preview), submitted)
            closeSheet(sheet)
            assertTrue(host.hasText(label(preview)))
            sheet = openSheet(host, preview)
            val setProgress = requireNotNull(sheet.slider().config[SemanticsActions.SetProgress].action)
            assertTrue(setProgress(243.6f))
            waitFor("Accessibility progress must be visible") { sheet.hasText(label(244)) }
            assertEquals(listOf(preview, 244), submitted)

            click(sheet, activity.getString(R.string.settings_typewriter_speed_reset, 30))
            assertEquals(listOf(preview, 244, 30), submitted)
            waitFor("Reset must preview the default immediately") { sheet.hasText(label(30)) }
            savedSpeed = preview
            saves[0].complete()
            repeat(6) { pump() }
            assertTrue(sheet.hasText(label(30)))
            assertTrue(host.hasText(label(30)))
            savedSpeed = 30
            saves[1].complete()
            saves[2].complete()
            waitFor("Reset readback must retain the default") { host.hasText(label(30)) }
            closeSheet(sheet)
            sheet = openSheet(host, 30)
            assertTrue(sheet.hasText(label(30)))
            assertEquals(listOf(preview, 244, 30), submitted)

            val changeAfterReset = requireNotNull(sheet.slider().config[SemanticsActions.SetProgress].action)
            assertTrue(changeAfterReset(173f))
            waitFor("Second change must be visible") { sheet.hasText(label(173)) }
            click(sheet, activity.getString(R.string.settings_typewriter_speed_reset, 30))
            saves[3].complete()
            saves[4].complete()
            waitFor("Reset may finish without a new readback emission") { host.hasText(label(30)) }
            repeat(6) { pump() }
            savedSpeed = 350
            waitFor("A later settings restore must not be masked by the completed reset") {
                host.hasText(label(350)) && sheet.hasText(label(350))
            }
            assertEquals(listOf(preview, 244, 30, 173, 30), submitted)
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }

    private fun ViewRootForTest.hasText(text: String): Boolean =
        semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }

    private fun ViewRootForTest.slider() = semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        .single { it.config.getOrNull(SemanticsActions.SetProgress) != null }

    private fun findRoot(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findRoot(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
