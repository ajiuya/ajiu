package com.aicode.feature.plugin.presentation

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.model.SpeedTier
import com.aicode.feature.agent.presentation.component.ChatHeader
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginSnapshot
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.domain.PluginUiSlots
import com.aicode.feature.plugin.domain.SemVer
import com.aicode.feature.plugin.runtime.PluginActions
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h900dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PluginMenuSpacingTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test(timeout = 30_000)
    fun emptyProblemsEmitNoLayoutEvenWithAModifierAndRealErrorsRemainVisible(): Unit = render {
        var problems by mutableStateOf(emptyList<PluginProblem>())
        screen = {
            Column {
                Text("Before", Modifier.testTag("before"))
                PluginProblems(problems, Modifier.testTag("problems").padding(horizontal = 16.dp))
                Text("After", Modifier.testTag("after"))
            }
        }
        settle()
        assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "problems" })
        assertEquals(tag("before").bounds().bottom, tag("after").bounds().top, 0f)
        problems = listOf(PluginProblem("ACTION_FAILED", R.string.plugin_error_runtime, "Real error detail"))
        settle()
        assertTrue(tag("problems").size.height > 16)
        assertTrue(hasText(label(R.string.plugin_error_runtime)))
        assertTrue(hasText(label(R.string.plugin_error_detail, "ACTION_FAILED", "Real error detail")))
        assertTrue(tag("after").bounds().top > tag("before").bounds().bottom)
    }

    @Test(timeout = 30_000)
    fun actualHeaderHasSix48dpRowsWithoutTheEmptySlotGapAndReusesIts12dpPopupForSpeed(): Unit = render {
        val fixture = SlotFixture()
        fixture.snapshot.value = PluginSnapshot()
        val selected = mutableListOf<SpeedTier>()
        screen = {
            CompositionLocalProvider(LocalPluginSlotHost provides fixture.host) {
                ChatHeader("Session", null, 0, 0, {}, {}, {}, {}, onSpeedTierChange = { selected += it })
            }
        }
        settle()
        clickDescription(label(R.string.chat_more_actions))
        val popup = popupViews().single()
        assertMainMenuRows()
        assertPopupRadius(popup, 12f)
        clickText(speedEntry())
        assertSame(popup, popupViews().single())
        assertTrue(hasText(label(R.string.speed_tier_menu_back)))
        assertFalse(hasText(label(R.string.plugin_project_tools)))
        assertPopupRadius(popup, 12f)
        clickText(label(R.string.speed_tier_menu_back))
        assertSame(popup, popupViews().single())
        assertMainMenuRows()
        clickText(speedEntry())
        clickText(label(R.string.speed_tier_standard))
        assertEquals(listOf(SpeedTier.STANDARD), selected)
        assertTrue(popupViews().isEmpty())
    }

    @Test(timeout = 30_000)
    fun slotRuntimeErrorStaysVisibleAfterTheEntryListBecomesEmpty(): Unit = render {
        val fixture = SlotFixture()
        coEvery { fixture.gateway.captureContext(fixture.capture, null, null, null) } throws IllegalStateException("Context unavailable")
        screen = { CompositionLocalProvider(LocalPluginSlotHost provides fixture.host) { PluginSlotContent(PluginUiSlots.CHAT_HEADER_MENU) } }
        settle()
        clickText("Slot action")
        fixture.snapshot.value = PluginSnapshot()
        settle()
        assertFalse(hasText("Slot action"))
        assertTrue(hasText(label(R.string.plugin_error_context)))
        assertTrue(text(label(R.string.plugin_error_context)).size.height > 0)
    }

    @Test(timeout = 30_000)
    fun selectedActionAndItsResultSurviveAnEmptyContributionList(): Unit = render {
        val fixture = SlotFixture()
        val invocation = PluginInvocationContext(fixture.capture, null, "/workspace", null, temporaryFolder.newFolder("plugin-data"),
            emptyFlow(), sourceIdentity = "fixed-source") {}
        coEvery { fixture.gateway.captureContext(fixture.capture, null, null, null) } returns invocation
        coEvery { fixture.actions.invoke(fixture.capture, "action.run", any(), invocation) } returns JsonPrimitive("Finished")
        try {
            screen = { CompositionLocalProvider(LocalPluginSlotHost provides fixture.host) { PluginSlotContent(PluginUiSlots.CHAT_HEADER_MENU) } }
            settle()
            clickText("Slot action")
            fixture.snapshot.value = PluginSnapshot()
            settle()
            assertTrue(hasText(label(R.string.plugin_action_arguments)))
            clickText(label(R.string.plugin_run_action))
            assertTrue(hasText(label(R.string.plugin_action_result)))
            assertTrue(hasText(JsonPrimitive("Finished").toString()))
            coVerify(exactly = 1) { fixture.actions.invoke(fixture.capture, "action.run", any(), invocation) }
        } finally { invocation.close() }
    }

    private class SlotFixture {
        private val id = "spacing.sample"
        private val hash = "a".repeat(64)
        private val entry = PluginUiEntry(id, "action", LocalizedText.single("Slot action"), PluginUiSlots.CHAT_HEADER_MENU, "settings", handler = "action.run")
        private val record = PluginRecord(id, "1.0.0", true, 1, archiveSha256 = hash, manifestSha256 = hash,
            installRevision = "spacing-revision", contentHash = hash, fileHashes = emptyMap(), trustHash = hash)
        private val manifest = PluginManifest(1, id, LocalizedText.single("Spacing sample"), SemVer(1, 0, 0), "Sample publisher",
            "https://example.invalid/repository", LocalizedText.single("Menu spacing fixture"), "MIT", "1.0.0")
        private val content = PluginContent(uiEntries = listOf(entry))
        val snapshot = MutableStateFlow(PluginSnapshot(plugins = listOf(InstalledPlugin(record, PluginStatus.ENABLED, manifest, content))))
        val capture = PluginCapture(record, manifest, content, File("/workspace/plugin"), {}, {})
        val manager = mockk<PluginManager>()
        val gateway = mockk<PluginHostGateway>()
        val actions = mockk<PluginActions>()
        val host: PluginSlotHost
        init {
            every { manager.snapshot } returns snapshot
            every { manager.capture(id) } returns capture
            coEvery { manager.ensureLoaded() } returns Unit
            host = PluginSlotHost(manager, gateway, actions)
        }
    }

    private class Ui(val activity: ComponentActivity, val view: ComposeView, val pump: (Int) -> Unit) {
        var screen by mutableStateOf<@Composable () -> Unit>({})
        fun nodes(): Collection<SemanticsNode> {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            val views = if (dialog != null) listOf(dialog) else listOf(view) + popupViews()
            return views.mapNotNull(::findRoot).distinct().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
        }
        fun label(resource: Int, vararg args: Any) = activity.getString(resource, *args)
        fun speedEntry() = label(R.string.speed_tier_menu_entry, label(R.string.speed_tier_standard))
        fun tag(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun hasText(value: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun text(value: String) = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun clickDescription(value: String) = click(nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true })
        fun clickText(value: String) = click(text(value))
        fun clickable(node: SemanticsNode): SemanticsNode {
            var target: SemanticsNode? = node
            while (target != null && target.config.getOrNull(SemanticsActions.OnClick)?.action == null) target = target.parent
            return requireNotNull(target)
        }
        fun click(node: SemanticsNode) {
            val target = clickable(node)
            assertFalse(target.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
            settle()
        }
        fun assertMainMenuRows() {
            val labels = listOf(label(R.string.chat_new_session), label(R.string.chat_open_git), label(R.string.chat_open_terminal),
                label(R.string.plugin_project_tools), speedEntry(), label(R.string.speed_tier_open_mcp))
            val rows = labels.map { clickable(text(it)) }
            assertEquals(6, rows.distinctBy { it.id }.size)
            rows.forEach { assertEquals(48, it.size.height) }
            rows.zipWithNext().forEach { (before, after) -> assertEquals(before.bounds().bottom, after.bounds().top, 0.5f) }
        }
        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(100) { frame ->
                pump(1)
                val geometry = nodes().map { "${it.id}:${it.positionInRoot}:${it.size}" }
                stable = if (geometry == previous) stable + 1 else 0
                if (stable >= 4 && frame >= 24) return
                previous = geometry
            }
            error("Plugin menu layout did not settle")
        }
    }

    private fun render(inspect: Ui.() -> Unit): Unit = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        val previousInspector = isDebugInspectorInfoEnabled
        isDebugInspectorInfoEnabled = true
        var frame = 0L
        fun layout(window: View, exact: Boolean) {
            val mode = if (exact) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            window.measure(View.MeasureSpec.makeMeasureSpec(400, mode), View.MeasureSpec.makeMeasureSpec(900, mode))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            findRoot(window)?.measureAndLayoutForTest()
        }
        val ui = Ui(activity, view) { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view, true)
            popupViews().forEach { layout(it, false) }
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { layout(it, true) }
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme { ui.screen() }
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
            isDebugInspectorInfoEnabled = previousInspector
        }
    }

    companion object {
        private fun SemanticsNode.bounds() = Rect(positionInRoot.x, positionInRoot.y, positionInRoot.x + size.width, positionInRoot.y + size.height)
        @Suppress("UNCHECKED_CAST")
        private fun popupViews(): List<View> {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val manager = type.getMethod("getInstance").invoke(null)
            val views = type.getDeclaredField("mViews").apply { isAccessible = true }.get(manager) as List<View>
            return views.filter { it.javaClass.name.contains("PopupLayout") }
        }
        private fun assertPopupRadius(popup: View, radius: Float) {
            val root = requireNotNull(findRoot(popup)).semanticsOwner.rootSemanticsNode
            fun layouts(info: LayoutInfo): List<LayoutInfo> {
                @Suppress("UNCHECKED_CAST")
                val children = info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>
                return listOf(info) + children.flatMap(::layouts)
            }
            val shapes = layouts(root.layoutInfo).flatMap { info -> info.getModifierInfo().flatMap { modifier ->
                (modifier.modifier as? InspectableValue)?.inspectableElements?.mapNotNull { it.value as? Shape }?.toList().orEmpty()
            } }
            assertTrue("Popup has no production rounded $radius dp surface; shapes=$shapes", shapes.any {
                val outline = it.createOutline(Size(400f, 600f), LayoutDirection.Ltr, Density(1f))
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
