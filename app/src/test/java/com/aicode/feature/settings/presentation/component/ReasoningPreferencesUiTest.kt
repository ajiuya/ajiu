package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.settings.data.repository.StartupSessionMode
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ReasoningPreferencesUiTest {
    @Test(timeout = 30_000)
    fun defaultOffSwitchSubmitsOnlyReasoningChangesAndFollowsParentReadback() = render {
        assertState(ToggleableState.Off)
        assertTrue(reasoningChanges.isEmpty())

        clickReasoningSwitch()
        pump(4)
        assertEquals(listOf(true), reasoningChanges)
        assertState(ToggleableState.Off)
        autoExpandReasoning = true
        pump(16)
        assertState(ToggleableState.On)
        assertEquals(listOf(true), reasoningChanges)

        clickReasoningSwitch()
        pump(4)
        assertEquals(listOf(true, false), reasoningChanges)
        assertState(ToggleableState.On)
        autoExpandReasoning = false
        pump(16)
        assertState(ToggleableState.Off)
        assertEquals(listOf(true, false), reasoningChanges)
    }

    @Test(timeout = 30_000)
    fun externallyLoadedAndRestoredPreferencesUpdateSwitchWithoutSubmittingChanges() = render {
        assertState(ToggleableState.Off)
        assertTrue(reasoningChanges.isEmpty())
        for (value in listOf(true, false)) {
            autoExpandReasoning = value
            pump(16)
            assertState(if (value) ToggleableState.On else ToggleableState.Off)
            assertTrue("Preference readback must not submit another change", reasoningChanges.isEmpty())
        }
    }

    private class PreferencesUi(
        private val activity: ComponentActivity,
        private val view: ComposeView,
        private val advanceFrame: () -> Unit
    ) {
        var autoExpandReasoning by mutableStateOf(false)
        val reasoningChanges = mutableListOf<Boolean>()
        val otherChanges = mutableListOf<String>()
        val typewriterSpeed = 173
        private val root: ViewRootForTest get() = view.getChildAt(0) as ViewRootForTest

        fun pump(count: Int = 1) = repeat(count) { advanceFrame() }

        private fun hasText(text: String): Boolean = root.semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false).any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
            }

        private fun reasoningSwitch(merged: Boolean): SemanticsNode {
            val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
            val title = activity.getString(R.string.settings_auto_expand_reasoning)
            val label = nodes.single {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == title } == true
            }
            val switches = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            return generateSequence(label) { it.parent }.firstNotNullOf { ancestor ->
                switches.singleOrNull { toggle ->
                    generateSequence(toggle) { it.parent }.any { it.id == ancestor.id }
                }
            }
        }

        fun clickReasoningSwitch() {
            val action = reasoningSwitch(merged = true).config[SemanticsActions.OnClick].action
            assertTrue(requireNotNull(action).invoke())
        }

        fun assertState(expected: ToggleableState) {
            assertTrue(hasText(activity.getString(R.string.settings_auto_expand_reasoning)))
            assertTrue(hasText(activity.getString(R.string.settings_auto_expand_reasoning_desc)))
            for (merged in listOf(false, true)) {
                val toggle = reasoningSwitch(merged)
                assertEquals(Role.Switch, toggle.config[SemanticsProperties.Role])
                assertEquals(expected, toggle.config[SemanticsProperties.ToggleableState])
                assertFalse(toggle.config.contains(SemanticsProperties.Disabled))
                assertNotNull(toggle.config.getOrNull(SemanticsActions.OnClick)?.action)
                val bounds = toggle.boundsInRoot
                assertTrue("The setting switch must be laid out in the viewport",
                    bounds.width > 0f && bounds.height > 0f && bounds.top >= 0f && bounds.bottom <= view.height)
            }
            assertTrue(hasText(activity.getString(R.string.settings_typewriter_speed_value, typewriterSpeed)))
            assertEquals("Other settings must remain untouched", emptyList<String>(), otherChanges)
        }
    }

    private fun render(inspect: PreferencesUi.() -> Unit) = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val looper = shadowOf(Looper.getMainLooper())
        var frameTime = 0L
        val ui = PreferencesUi(activity, view) {
            looper.idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            frameTime += 16_000_000L
            if (clock.hasAwaiters) clock.sendFrame(frameTime)
            runCurrent()
            Snapshot.sendApplyNotifications()
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            (view.getChildAt(0) as? ViewRootForTest)?.measureAndLayoutForTest()
        }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) {
                        GeneralSettingsSection(
                            autoRemoveStaleModels = true,
                            onToggleAutoRemoveStaleModels = { ui.otherChanges += "autoRemoveStaleModels:$it" },
                            startupSessionMode = StartupSessionMode.NEW_SESSION,
                            onSelectStartupSessionMode = { ui.otherChanges += "startupSessionMode:$it" },
                            firstByteTimeoutSec = 300,
                            onSetFirstByteTimeoutSec = { ui.otherChanges += "firstByteTimeoutSec:$it" },
                            streamIdleTimeoutSec = 300,
                            onSetStreamIdleTimeoutSec = { ui.otherChanges += "streamIdleTimeoutSec:$it" },
                            maxNetworkRetries = 6,
                            onSetMaxNetworkRetries = { ui.otherChanges += "maxNetworkRetries:$it" },
                            enterToSend = false,
                            onToggleEnterToSend = { ui.otherChanges += "enterToSend:$it" },
                            autoExpandReasoning = ui.autoExpandReasoning,
                            onToggleAutoExpandReasoning = { ui.reasoningChanges += it },
                            compactionThresholdPercent = 85,
                            onSetCompactionThresholdPercent = { ui.otherChanges += "compactionThresholdPercent:$it" },
                            sendFileMaxSizeMb = 100,
                            onSetSendFileMaxSizeMb = { ui.otherChanges += "sendFileMaxSizeMb:$it" },
                            goalMaxTurns = 50,
                            onSetGoalMaxTurns = { ui.otherChanges += "goalMaxTurns:$it" },
                            goalMaxTokens = 500_000,
                            onSetGoalMaxTokens = { ui.otherChanges += "goalMaxTokens:$it" },
                            deleteExternalWorkspaceSessions = false,
                            onToggleDeleteExternalWorkspaceSessions = { ui.otherChanges += "deleteExternalWorkspaceSessions:$it" },
                            typewriterCharsPerSecond = ui.typewriterSpeed,
                            onSetTypewriterCharsPerSecond = {
                                ui.otherChanges += "typewriterCharsPerSecond:$it"
                                Job().apply { complete() }
                            }
                        )
                    }
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
        }
    }
}
