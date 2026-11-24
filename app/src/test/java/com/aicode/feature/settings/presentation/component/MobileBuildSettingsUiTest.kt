package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.settings.domain.model.MobileBuildSettings
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w720dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MobileBuildSettingsUiTest {
    @Test(timeout = 90_000)
    fun permissionsPagePlacesAnIndependentDefaultOffGroupAfterTheRootRow(): Unit = render(permissions = true) {
        val rootTitle = hostText(R.string.settings_root_access)
        val mobileTitle = hostText(R.string.mobile_build_title)
        assertTrue(mobileTitle.positionInRoot.y > rootTitle.positionInRoot.y)
        assertEquals(2, hostNodes().count { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch })
        assertToggle(hostToggle(R.string.mobile_build_enable), false)
        assertTrue(label(R.string.mobile_build_scope_hint) in hostTexts())
        assertTrue(submitted.isEmpty())
        assertTrue(enabledChanges.isEmpty())
    }

    @Test(timeout = 90_000)
    fun allOptionsHaveTheExpectedDefaultsAndEditingWhileOffOnlyCommitsOnSave(): Unit = render {
        showPanel()
        assertDefaultOptions()
        assertEquals(12, panelNodes().count { it.config.contains(SemanticsProperties.Selected) })
        selectDraft()
        assertEquals(MobileBuildSettings(), settings)
        assertTrue(submitted.isEmpty())
        assertTrue(enabledChanges.isEmpty())
        clickPanelText(R.string.common_save)
        assertTrue(saving)
        assertEquals(listOf(draft()), submitted)
        assertEquals(MobileBuildSettings(), settings)
        assertPanelOpen()
        finishSave(success = true)
        awaitPanelClosed()
        assertEquals(draft(), settings)
        assertFalse(settings.enabled)
        showPanel()
        assertDraftOptions()
    }

    @Test(timeout = 90_000)
    fun cancelDiscardsTheDraftAndReopeningUsesThePersistedOptions(): Unit = render {
        showPanel()
        selectDraft()
        clickPanelText(R.string.common_cancel)
        awaitPanelClosed()
        assertEquals(MobileBuildSettings(), settings)
        assertTrue(submitted.isEmpty())
        showPanel()
        assertDefaultOptions()
    }

    @Test(timeout = 90_000)
    fun dismissWithoutSavingDoesNotWriteAnything(): Unit = render {
        showPanel()
        selectOption(R.string.mobile_build_test_heap, R.string.mobile_build_heap_512_mb)
        dismissPanel()
        awaitPanelClosed()
        assertEquals(MobileBuildSettings(), settings)
        assertTrue(submitted.isEmpty())
        showPanel()
        assertDefaultOptions()
    }

    @Test(timeout = 90_000)
    fun disablingAndReenablingKeepsEverySavedChoice(): Unit = render(initial = draft().copy(enabled = true)) {
        click(hostToggle(R.string.mobile_build_enable))
        assertEquals(listOf(false), enabledChanges)
        assertEquals(draft(), settings)
        showPanel()
        assertDraftOptions()
        clickPanelText(R.string.common_cancel)
        awaitPanelClosed()
        click(hostToggle(R.string.mobile_build_enable))
        assertEquals(listOf(false, true), enabledChanges)
        assertEquals(draft().copy(enabled = true), settings)
        showPanel()
        assertDraftOptions()
        assertTrue(submitted.isEmpty())
    }

    @Test(timeout = 90_000)
    fun savingLocksControlsAndDismissWhileFailureKeepsTheDraftOpenForRetry(): Unit = render {
        showPanel()
        selectDraft()
        clickPanelText(R.string.common_save)
        await("Saving controls") { label(R.string.mobile_build_saving) in panelTexts() }
        val controls = panelNodes().filter { node ->
            node.config.contains(SemanticsProperties.Selected) ||
                node.config.getOrNull(SemanticsProperties.Role) == Role.Switch
        }
        assertEquals(14, controls.size)
        assertTrue(controls.all { it.config.contains(SemanticsProperties.Disabled) })
        assertTrue(actionForText(panelNodes(), R.string.mobile_build_saving).config.contains(SemanticsProperties.Disabled))
        assertTrue(actionForText(panelNodes(), R.string.common_cancel).config.contains(SemanticsProperties.Disabled))
        assertTrue(hostToggle(R.string.mobile_build_enable).config.contains(SemanticsProperties.Disabled))
        dismissPanel()
        assertPanelOpen()
        assertEquals(1, submitted.size)
        finishSave(success = false)
        await("Localized save error") { label(R.string.mobile_build_save_failed) in panelTexts() }
        assertPanelOpen()
        assertDraftOptions()
        assertEquals(MobileBuildSettings(), settings)
        assertFalse(actionForText(panelNodes(), R.string.common_save).config.contains(SemanticsProperties.Disabled))
        clickPanelText(R.string.common_save)
        assertEquals(listOf(draft(), draft()), submitted)
        finishSave(success = true)
        awaitPanelClosed()
        assertEquals(draft(), settings)
    }

    @Config(qualifiers = "w360dp-h640dp")
    @Test(timeout = 90_000)
    fun compactBottomSheetScrollsToTheSaveAndCancelActions(): Unit = render(width = 360, height = 640) {
        showPanel()
        await("Scrollable compact form") {
            panelNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke()?.let { it > 0f } == true
            }
        }
        val scroller = panelNodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
        assertTrue(requireNotNull(scroller.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, 100_000f))
        pump(40)
        val panelRoot = requireNotNull(panelRoot())
        listOf(R.string.common_save, R.string.common_cancel).forEach { resource ->
            val action = actionForText(panelNodes(), resource)
            assertTrue("Action must remain within the compact dialog", action.boundsInRoot.top >= 0f)
            assertTrue("Action must be reachable after scrolling", action.boundsInRoot.bottom <= panelRoot.view.height.toFloat())
        }
        clickPanelText(R.string.common_save)
        assertEquals(listOf(MobileBuildSettings()), submitted)
        finishSave(success = true)
        awaitPanelClosed()
    }

    private class Ui(
        val activity: ComponentActivity,
        private val view: ComposeView,
        initial: MobileBuildSettings,
        private val permissions: Boolean,
        val pump: (Int) -> Unit
    ) {
        var settings by mutableStateOf(initial)
        var saving by mutableStateOf(false)
        private var error by mutableStateOf<Int?>(null)
        private var savedVersion by mutableStateOf(0L)
        val submitted = mutableListOf<MobileBuildSettings>()
        val enabledChanges = mutableListOf<Boolean>()

        @Composable
        fun Content() {
            val setEnabled: (Boolean) -> Unit = {
                enabledChanges += it
                settings = settings.copy(enabled = it)
            }
            val save: (MobileBuildSettings) -> Unit = {
                submitted += it
                saving = true
                error = null
            }
            if (permissions) {
                AppPermissionsSection(
                    shizukuState = ShizukuState.READY,
                    onRequestShizukuPermission = {},
                    onOpenShizuku = {},
                    onRefreshShizuku = {},
                    mobileBuildSettings = settings,
                    mobileBuildSaving = saving,
                    mobileBuildError = error,
                    mobileBuildOptionsSavedVersion = savedVersion,
                    onSetMobileBuildEnabled = setEnabled,
                    onSaveMobileBuildOptions = save,
                    onClearMobileBuildError = { error = null }
                )
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    MobileBuildSettingsGroup(
                        settings = settings,
                        saving = saving,
                        error = error,
                        optionsSavedVersion = savedVersion,
                        onSetEnabled = setEnabled,
                        onSaveOptions = save,
                        onClearError = { error = null }
                    )
                }
            }
        }

        fun label(resource: Int, vararg args: Any): String = activity.getString(resource, *args)

        fun hostNodes(): List<SemanticsNode> = nodes(findRoot(view))

        fun panelRoot(): ViewRootForTest? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            ?.window?.decorView?.let(::findRoot)

        fun panelNodes(): List<SemanticsNode> = nodes(panelRoot())

        fun hostTexts(): List<String> = texts(hostNodes())

        fun panelTexts(): List<String> = texts(panelNodes())

        fun hostText(resource: Int): SemanticsNode = textNode(hostNodes(), resource)

        fun hostToggle(resource: Int): SemanticsNode = toggle(hostNodes(), resource)

        private fun panelToggle(resource: Int): SemanticsNode = toggle(panelNodes(), resource)

        private fun toggle(nodes: List<SemanticsNode>, resource: Int): SemanticsNode = nodes.single {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label(resource)) == true
        }

        private fun textNode(nodes: List<SemanticsNode>, resource: Int): SemanticsNode = nodes.single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label(resource) } == true
        }

        fun actionForText(nodes: List<SemanticsNode>, resource: Int): SemanticsNode =
            generateSequence(textNode(nodes, resource)) { it.parent }
                .first { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }

        fun click(node: SemanticsNode) {
            assertFalse("Do not invoke a disabled control", node.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            pump(32)
        }

        fun clickPanelText(resource: Int) = click(actionForText(panelNodes(), resource))

        fun showPanel() {
            click(actionForText(hostNodes(), R.string.mobile_build_options))
            await("Options panel") { label(R.string.mobile_build_options_title) in panelTexts() }
            assertNotSame(findRoot(view), panelRoot())
        }

        fun dismissPanel() {
            val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as ComponentDialog
            dialog.onBackPressedDispatcher.onBackPressed()
            pump(32)
        }

        fun assertPanelOpen() {
            assertTrue(requireNotNull(ShadowDialog.getLatestDialog()).isShowing)
            assertTrue(label(R.string.mobile_build_options_title) in panelTexts())
        }

        fun awaitPanelClosed() = await("Options panel dismissal") { panelRoot() == null }

        fun finishSave(success: Boolean) {
            assertTrue(saving)
            if (success) {
                settings = submitted.last().copy(enabled = settings.enabled)
                savedVersion++
                error = null
            } else {
                error = R.string.mobile_build_save_failed
            }
            saving = false
            pump(32)
        }

        private fun option(title: Int, value: String): SemanticsNode {
            val description = label(R.string.mobile_build_option_description, label(title), value)
            return panelNodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
            }
        }

        fun selectOption(title: Int, value: Int) = click(option(title, label(value)))

        private fun assertOption(title: Int, value: Int) {
            assertEquals(true, option(title, label(value)).config.getOrNull(SemanticsProperties.Selected))
        }

        private fun assertWorker(worker: Int) {
            assertEquals(true, option(R.string.mobile_build_workers, label(R.string.mobile_build_worker_option, worker))
                .config.getOrNull(SemanticsProperties.Selected))
        }

        fun assertDefaultOptions() {
            listOf(R.string.mobile_build_heap_2_gb, R.string.mobile_build_heap_2_5_gb, R.string.mobile_build_heap_3_gb)
                .forEach { option(R.string.mobile_build_gradle_heap, label(it)) }
            listOf(R.string.mobile_build_heap_512_mb, R.string.mobile_build_heap_768_mb, R.string.mobile_build_heap_1_gb,
                R.string.mobile_build_heap_1_5_gb, R.string.mobile_build_heap_2_gb)
                .forEach { option(R.string.mobile_build_test_heap, label(it)) }
            (1..4).forEach { option(R.string.mobile_build_workers, label(R.string.mobile_build_worker_option, it)) }
            assertOption(R.string.mobile_build_gradle_heap, R.string.mobile_build_heap_3_gb)
            assertOption(R.string.mobile_build_test_heap, R.string.mobile_build_heap_1_gb)
            assertWorker(1)
            assertToggle(panelToggle(R.string.mobile_build_daemon), false)
            assertToggle(panelToggle(R.string.mobile_build_parallel), false)
        }

        fun selectDraft() {
            selectOption(R.string.mobile_build_gradle_heap, R.string.mobile_build_heap_2_5_gb)
            selectOption(R.string.mobile_build_test_heap, R.string.mobile_build_heap_1_5_gb)
            click(option(R.string.mobile_build_workers, label(R.string.mobile_build_worker_option, 3)))
            click(panelToggle(R.string.mobile_build_daemon))
            click(panelToggle(R.string.mobile_build_parallel))
            assertDraftOptions()
        }

        fun assertDraftOptions() {
            assertOption(R.string.mobile_build_gradle_heap, R.string.mobile_build_heap_2_5_gb)
            assertOption(R.string.mobile_build_test_heap, R.string.mobile_build_heap_1_5_gb)
            assertWorker(3)
            assertToggle(panelToggle(R.string.mobile_build_daemon), true)
            assertToggle(panelToggle(R.string.mobile_build_parallel), true)
        }

        fun await(message: String, condition: () -> Boolean) {
            repeat(180) {
                pump(1)
                if (condition()) return
            }
            fail("$message did not complete within 180 controlled frames; host=${hostTexts()}, panel=${panelTexts()}")
        }
    }

    private fun render(
        initial: MobileBuildSettings = MobileBuildSettings(),
        permissions: Boolean = false,
        width: Int = 720,
        height: Int = 1000,
        inspect: Ui.() -> Unit
    ): Unit = runTest(timeout = 60.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var frame = 0L
        fun layout(target: View) {
            target.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            target.layout(0, 0, target.measuredWidth, target.measuredHeight)
            findRoot(target)?.measureAndLayoutForTest()
        }
        val ui = Ui(activity, view, initial, permissions) { count -> repeat(count) {
            repeat(4) { shadowOf(Looper.getMainLooper()).runOneTask() }
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::layout)
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) { ui.Content() }
                }
            }
            activity.setContentView(view)
            ui.await("Initial settings layout") { ui.hostNodes().isNotEmpty() }
            ui.pump(32)
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
        private fun draft() = MobileBuildSettings(
            gradleHeapMb = 2560,
            daemonEnabled = true,
            parallelEnabled = true,
            maxWorkers = 3,
            testHeapMb = 1536
        )

        private fun nodes(root: ViewRootForTest?): List<SemanticsNode> = root?.semanticsOwner
            ?.getAllSemanticsNodes(mergingEnabled = false).orEmpty()

        private fun texts(nodes: List<SemanticsNode>): List<String> = nodes.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }

        private fun assertToggle(node: SemanticsNode, checked: Boolean) {
            assertEquals(if (checked) ToggleableState.On else ToggleableState.Off,
                node.config.getOrNull(SemanticsProperties.ToggleableState))
        }

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
