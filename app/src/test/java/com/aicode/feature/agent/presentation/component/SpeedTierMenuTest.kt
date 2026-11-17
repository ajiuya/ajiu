package com.aicode.feature.agent.presentation.component

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.SpeedTier
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
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
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class SpeedTierMenuTest {
    private fun provider(model: String = "gpt-6-astra") = AIProviderConfig(
        id = "openai",
        name = "fixture",
        type = ProviderType.OPENAI,
        apiKey = "",
        baseUrl = "https://api.openai.com/",
        defaultModel = model,
        useResponseApi = true
    )

    @Test(timeout = 30_000)
    fun rootEntryShowsChosenPreferenceAndOpensSubmenuThroughCallback() {
        var opened = false
        render(content = { SpeedTierMenuEntry(SpeedTier.ULTRAFAST) { opened = true } }) { root, activity ->
            val label = activity.getString(R.string.speed_tier_menu_entry, activity.getString(R.string.speed_tier_ultrafast))
            val entry = item(root, label)
            assertFalse(opened)
            click(entry)
            assertTrue(opened)
        }
    }

    @Test(timeout = 30_000)
    fun downgradedRequestKeepsChosenUltraSelectedAndDisablesUnsupportedUltra() {
        var changes = 0
        render(content = {
            Column { SpeedTierMenu(SpeedTier.ULTRAFAST, { changes++ }, provider("gpt-5.6"), {}) }
        }) { root, activity ->
            assertSummaries(root, activity, SpeedTier.ULTRAFAST, SpeedTier.FAST)
            val ultra = item(root, activity.getString(R.string.speed_tier_ultrafast))
            assertTrue(ultra.config.contains(SemanticsProperties.Disabled))
            assertEquals(true, ultra.config.getOrNull(SemanticsProperties.Selected))
            assertFalse(item(root, activity.getString(R.string.speed_tier_fast)).config.contains(SemanticsProperties.Disabled))
            assertEquals(0, changes)
        }
    }

    @Test(timeout = 30_000)
    fun unsupportedProtocolDisablesBothFastChoicesWithoutWritingPreference() {
        var changes = 0
        val config = provider().copy(type = ProviderType.ANTHROPIC, id = "xai")
        render(content = {
            Column { SpeedTierMenu(SpeedTier.ULTRAFAST, { changes++ }, config, {}) }
        }) { root, activity ->
            assertSummaries(root, activity, SpeedTier.ULTRAFAST, SpeedTier.STANDARD)
            listOf(R.string.speed_tier_fast, R.string.speed_tier_ultrafast).forEach { label ->
                assertTrue(item(root, activity.getString(label)).config.contains(SemanticsProperties.Disabled))
            }
            assertFalse(item(root, activity.getString(R.string.speed_tier_standard)).config.contains(SemanticsProperties.Disabled))
            assertEquals(0, changes)
        }
    }

    @Test(timeout = 30_000)
    fun supportedChoiceInvokesChangeOnlyAfterSelection() {
        val changes = mutableListOf<SpeedTier>()
        render(content = {
            Column { SpeedTierMenu(SpeedTier.STANDARD, { changes.add(it) }, provider(), {}) }
        }) { root, activity ->
            val ultra = item(root, activity.getString(R.string.speed_tier_ultrafast))
            assertFalse(ultra.config.contains(SemanticsProperties.Disabled))
            assertTrue(changes.isEmpty())
            click(ultra)
            assertEquals(listOf(SpeedTier.ULTRAFAST), changes)
        }
    }

    @Test(timeout = 30_000)
    fun xaiShowsFastRequestWhileRetainingGlobalUltrafastPreference() {
        val config = provider("grok-4").copy(id = "xai", useResponseApi = false)
        render(content = {
            Column { SpeedTierMenu(SpeedTier.ULTRAFAST, {}, config, {}) }
        }) { root, activity ->
            assertSummaries(root, activity, SpeedTier.ULTRAFAST, SpeedTier.FAST)
            assertTrue(item(root, activity.getString(R.string.speed_tier_ultrafast)).config.contains(SemanticsProperties.Disabled))
            assertFalse(item(root, activity.getString(R.string.speed_tier_fast)).config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test(timeout = 30_000)
    fun backDoesNotChangePreferenceAndMissingProviderDisablesFastChoices() {
        var wentBack = false
        var changes = 0
        render(content = {
            Column { SpeedTierMenu(SpeedTier.FAST, { changes++ }, null, { wentBack = true }) }
        }) { root, activity ->
            assertSummaries(root, activity, SpeedTier.FAST, SpeedTier.STANDARD)
            assertTrue(item(root, activity.getString(R.string.speed_tier_fast)).config.contains(SemanticsProperties.Disabled))
            assertTrue(item(root, activity.getString(R.string.speed_tier_ultrafast)).config.contains(SemanticsProperties.Disabled))
            click(item(root, activity.getString(R.string.speed_tier_menu_back)))
            assertTrue(wentBack)
            assertEquals(0, changes)
        }
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "en")
    fun englishMenuSeparatesPreferenceFromRequestAndUsesEffectiveModelFallback() {
        val config = provider().copy(selectedModel = "")
        assertEquals(SpeedTier.ULTRAFAST, effectiveSpeedTier(SpeedTier.ULTRAFAST, config))
        render(content = {
            Column { SpeedTierMenu(SpeedTier.ULTRAFAST, {}, config, {}) }
        }) { root, activity ->
            assertEquals("Ultrafast", activity.getString(R.string.speed_tier_ultrafast))
            assertSummaries(root, activity, SpeedTier.ULTRAFAST, SpeedTier.ULTRAFAST)
            assertFalse(item(root, "Ultrafast").config.contains(SemanticsProperties.Disabled))
        }
    }

    private fun assertSummaries(
        root: ViewRootForTest,
        activity: ComponentActivity,
        chosen: SpeedTier,
        effective: SpeedTier
    ) {
        val texts = nodes(root).flatMap(::texts)
        assertTrue(texts.contains(activity.getString(R.string.speed_tier_chosen, activity.getString(chosen.speedTierLabelRes()))))
        assertTrue(texts.contains(activity.getString(R.string.speed_tier_effective, activity.getString(effective.speedTierLabelRes()))))
    }

    private fun nodes(root: ViewRootForTest): List<SemanticsNode> =
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun item(root: ViewRootForTest, label: String): SemanticsNode =
        nodes(root).single { label in texts(it) && it.config.contains(SemanticsActions.OnClick) }

    private fun click(node: SemanticsNode) {
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
    }

    private fun render(
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
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            repeat(10) { frame ->
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
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
