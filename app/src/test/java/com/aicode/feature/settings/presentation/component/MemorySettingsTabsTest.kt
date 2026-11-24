package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.os.Looper
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.memory.ProjectMemoryAssociations
import com.aicode.feature.settings.presentation.MemoryUiState
import com.aicode.feature.settings.presentation.MemoryViewModel
import com.aicode.feature.settings.presentation.ProjectProgressUiState
import com.aicode.feature.settings.presentation.ProjectProgressViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MemorySettingsTabsTest {
    @Test(timeout = 90_000)
    fun progressViewModelOpensOnlyOnFirstTabSelectionAndBothViewModelsCloseOnExit(): Unit = render {
        val memoryState = MutableStateFlow(MemoryUiState(
            entries = emptyList(), hasWorkspace = true, projectName = "workspace", loading = false
        ))
        val projectState = MutableStateFlow(ProjectProgressUiState(
            workspaceName = "workspace", workspaceRoot = "/workspace", loading = false, projectsLoaded = true,
            associations = ProjectMemoryAssociations(false, null, true, null)
        ))
        val memoryViewModel = mockk<MemoryViewModel>()
        val projectViewModel = mockk<ProjectProgressViewModel>()
        every { memoryViewModel.state } returns memoryState
        every { memoryViewModel.open() } returns Unit
        every { memoryViewModel.close() } returns Unit
        every { memoryViewModel.refresh() } returns Unit
        every { memoryViewModel.closeDetail() } returns Unit
        every { memoryViewModel.startCreate() } returns Unit
        every { memoryViewModel.consumeCreatedScope() } returns Unit
        every { projectViewModel.state } returns projectState
        every { projectViewModel.open(any()) } returns Unit
        every { projectViewModel.close() } returns Unit
        every { projectViewModel.checkEnvironment() } returns Unit
        every { projectViewModel.refresh() } returns Unit

        var routeVisible by mutableStateOf(true)
        this.screen = {
            if (routeVisible) {
                MemorySettingsRoute(memoryViewModel, projectViewModel, "session-42", true) {
                    routeVisible = false
                }
            }
        }
        settle()
        assertTrue(hasTag("memory-tabs"))
        assertTrue(hasText(label(R.string.memory_long_term_title)))
        assertTrue(hasText(label(R.string.memory_long_term_hint)))
        assertFalse(hasTag("memory-project-progress"))
        assertEquals(true, tag("memory-tab-long-term").config[SemanticsProperties.Selected])
        verify(exactly = 1) { memoryViewModel.open() }
        verify(exactly = 0) { projectViewModel.open(any()) }

        clickTag("memory-tab-project-progress")
        assertEquals(true, tag("memory-tab-project-progress").config[SemanticsProperties.Selected])
        assertTrue(hasText(label(R.string.project_progress_title)))
        assertTrue(hasText(label(R.string.project_progress_current_workspace)))
        assertTrue(hasText(label(R.string.project_progress_environment_value,
            label(R.string.project_progress_environment_unknown), label(R.string.project_progress_not_registered))))
        assertTrue(hasText(label(R.string.project_progress_register_short)))
        verify(exactly = 1) { projectViewModel.open("session-42") }
        clickTag("memory-tab-long-term")
        clickTag("memory-tab-project-progress")
        verify(exactly = 1) { projectViewModel.open("session-42") }
        verify(exactly = 0) { projectViewModel.close() }
        verify(exactly = 0) { memoryViewModel.close() }

        projectState.value = projectState.value.copy(busy = true)
        settle()
        listOf("memory-tab-long-term", "memory-tab-project-progress").forEach {
            assertTrue(tag(it).config.contains(SemanticsProperties.Disabled))
        }
        projectState.value = projectState.value.copy(busy = false)
        settle()
        clickTag("memory-tab-long-term")
        clickBack()
        verify(exactly = 1) { memoryViewModel.close() }
        verify(exactly = 1) { projectViewModel.close() }
    }

    private inner class Ui(val activity: ComponentActivity, val view: ComposeView, val pump: (Int) -> Unit) {
        var screen by mutableStateOf<@Composable () -> Unit>({})

        fun nodes(): List<SemanticsNode> = findRoot(view).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        fun label(resource: Int, vararg args: Any): String = activity.getString(resource, *args)
        fun hasTag(value: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun hasText(value: String): Boolean = nodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
        }
        fun tag(value: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }

        fun clickTag(value: String) {
            settle()
            val target = tag(value)
            assertFalse(target.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            settle()
        }

        fun clickBack() {
            settle()
            val icon = nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label(R.string.common_back)) == true
            }
            var target: SemanticsNode? = icon
            while (target != null && target.config.getOrNull(SemanticsActions.OnClick)?.action == null) target = target.parent
            val action = requireNotNull(target).config[SemanticsActions.OnClick].action
            assertTrue(requireNotNull(action).invoke())
            settle()
        }

        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(120) { frame ->
                pump(1)
                val geometry = nodes().map { "${it.id}:${it.positionInRoot}:${it.size}" }
                stable = if (geometry == previous) stable + 1 else 0
                if (stable >= 4 && frame >= 24) return
                previous = geometry
            }
            error("Memory settings tabs did not settle")
        }
    }

    private fun render(inspect: Ui.() -> Unit): Unit = runTest(timeout = 60.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var frame = 0L
        lateinit var ui: Ui
        ui = Ui(activity, view) { count ->
            repeat(count) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                testScheduler.advanceTimeBy(16)
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.measure(ViewMeasure.exactly(400), ViewMeasure.exactly(1000))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                findRoot(view).measureAndLayoutForTest()
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
            }
        }
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
        }
    }

    private data object ViewMeasure {
        fun exactly(value: Int): Int = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    }

    private fun findRoot(view: View): ViewRootForTest {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findRootOrNull(view.getChildAt(index))?.let { return it }
        }
        error("Compose root was not attached")
    }

    private fun findRootOrNull(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) repeat(view.childCount) { index -> findRootOrNull(view.getChildAt(index))?.let { return it } }
        return null
    }
}
