package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import java.time.Duration
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
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ShizukuPermissionsUiTest {
    @Test(timeout = 90_000)
    fun deniedPermissionShowsTheRequestHintAndOnlyRequestsPermission(): Unit = render(ShizukuState.PERMISSION_DENIED) {
        assertPresentation(R.string.settings_shizuku_status_denied, R.string.settings_shizuku_hint_denied)
        assertAbsent(R.string.shizuku_shell_required, R.string.settings_shizuku_status_shell_required,
            R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertCalls()
        clickShizuku()
        assertCalls(requests = 1)
        assertPresentation(R.string.settings_shizuku_status_denied, R.string.settings_shizuku_hint_denied)
    }

    @Test(timeout = 90_000)
    fun unavailableIdentityShowsItsOwnHintAndOnlyRefreshesAfterTheResumeBaseline(): Unit = render(ShizukuState.IDENTITY_UNAVAILABLE) {
        assertPresentation(R.string.settings_shizuku_status_identity_unavailable, R.string.settings_shizuku_hint_identity_unavailable)
        assertAbsent(R.string.shizuku_shell_required, R.string.settings_shizuku_status_shell_required,
            R.string.settings_shizuku_hint_denied, R.string.settings_shizuku_status_root_ready,
            R.string.settings_shizuku_hint_root_ready)
        assertCalls()
        clickShizuku()
        assertCalls(refreshes = 1)
        assertPresentation(R.string.settings_shizuku_status_identity_unavailable, R.string.settings_shizuku_hint_identity_unavailable)
    }

    @Test(timeout = 90_000)
    fun confirmedNonShellIdentityKeepsTheOriginalHintAndOnlyOpensShizukuWithRootOff(): Unit = render(ShizukuState.SHELL_REQUIRED) {
        assertPresentation(R.string.settings_shizuku_status_shell_required, R.string.shizuku_shell_required)
        assertAbsent(R.string.settings_shizuku_hint_denied, R.string.settings_shizuku_hint_identity_unavailable,
            R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertCalls()
        clickShizuku()
        assertCalls(opens = 1)
        assertPresentation(R.string.settings_shizuku_status_shell_required, R.string.shizuku_shell_required)
    }

    @Test(timeout = 90_000)
    fun readyShowsNoPermissionRequestOrClickableShizukuAction(): Unit = render(ShizukuState.READY) {
        assertPresentation(R.string.settings_shizuku_status_ready, R.string.settings_shizuku_hint_ready)
        assertAbsent(R.string.settings_shizuku_status_denied, R.string.settings_shizuku_hint_denied,
            R.string.shizuku_shell_required, R.string.settings_shizuku_hint_identity_unavailable,
            R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertShizukuNotClickable()
        settle()
        assertCalls()
        assertShizukuNotClickable()
    }

    @Test(timeout = 90_000)
    fun repeatedDeniedReadyAndUnknownTransitionsReplaceTheActionWithoutAutomaticGrants(): Unit = render(ShizukuState.PERMISSION_DENIED) {
        var expectedRequests = 0
        var expectedRefreshes = 0
        repeat(2) {
            state = ShizukuState.PERMISSION_DENIED
            settle()
            assertPresentation(R.string.settings_shizuku_status_denied, R.string.settings_shizuku_hint_denied)
            assertAbsent(R.string.settings_shizuku_hint_ready, R.string.settings_shizuku_hint_identity_unavailable,
                R.string.shizuku_shell_required, R.string.settings_shizuku_status_root_ready,
                R.string.settings_shizuku_hint_root_ready)
            assertCalls(requests = expectedRequests, refreshes = expectedRefreshes)
            clickShizuku()
            expectedRequests++
            assertCalls(requests = expectedRequests, refreshes = expectedRefreshes)

            state = ShizukuState.READY
            settle()
            assertPresentation(R.string.settings_shizuku_status_ready, R.string.settings_shizuku_hint_ready)
            assertAbsent(R.string.settings_shizuku_hint_denied, R.string.settings_shizuku_hint_identity_unavailable,
                R.string.shizuku_shell_required, R.string.settings_shizuku_status_root_ready,
                R.string.settings_shizuku_hint_root_ready)
            assertShizukuNotClickable()
            assertCalls(requests = expectedRequests, refreshes = expectedRefreshes)

            state = ShizukuState.READY_ROOT
            settle()
            assertPresentation(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
            assertAbsent(R.string.settings_shizuku_hint_denied, R.string.settings_shizuku_hint_identity_unavailable,
                R.string.shizuku_shell_required, R.string.settings_shizuku_status_ready,
                R.string.settings_shizuku_hint_ready)
            assertShizukuNotClickable(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
            assertCalls(requests = expectedRequests, refreshes = expectedRefreshes)

            state = ShizukuState.IDENTITY_UNAVAILABLE
            settle()
            assertPresentation(R.string.settings_shizuku_status_identity_unavailable, R.string.settings_shizuku_hint_identity_unavailable)
            assertAbsent(R.string.settings_shizuku_hint_denied, R.string.settings_shizuku_hint_ready,
                R.string.shizuku_shell_required, R.string.settings_shizuku_status_root_ready,
                R.string.settings_shizuku_hint_root_ready)
            assertCalls(requests = expectedRequests, refreshes = expectedRefreshes)
            clickShizuku()
            expectedRefreshes++
            assertCalls(requests = expectedRequests, refreshes = expectedRefreshes)
        }
    }

    @Test(timeout = 90_000)
    fun rootReadyShowsItsOwnResourcesWithoutEnablingRootOrExposingAShizukuAction(): Unit = render(ShizukuState.READY_ROOT) {
        assertPresentation(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertAbsent(R.string.settings_shizuku_status_denied, R.string.settings_shizuku_hint_denied,
            R.string.shizuku_shell_required, R.string.settings_shizuku_hint_identity_unavailable,
            R.string.settings_shizuku_status_ready, R.string.settings_shizuku_hint_ready)
        assertShizukuNotClickable(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        settle()
        assertCalls()
        assertShizukuNotClickable(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
    }

    @Test(timeout = 90_000)
    fun rootSwitchFollowsOnlyIndependentRootEnabledAcrossShizukuStateChanges(): Unit = render(ShizukuState.READY_ROOT) {
        assertPresentation(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertCalls()

        rootEnabled = true
        settle()
        assertPresentation(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertShizukuNotClickable(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertCalls()

        state = ShizukuState.READY
        settle()
        assertPresentation(R.string.settings_shizuku_status_ready, R.string.settings_shizuku_hint_ready)
        assertShizukuNotClickable()
        assertCalls()

        state = ShizukuState.PERMISSION_DENIED
        settle()
        assertPresentation(R.string.settings_shizuku_status_denied, R.string.settings_shizuku_hint_denied)
        assertCalls()

        state = ShizukuState.IDENTITY_UNAVAILABLE
        settle()
        assertPresentation(R.string.settings_shizuku_status_identity_unavailable, R.string.settings_shizuku_hint_identity_unavailable)
        assertCalls()

        state = ShizukuState.SHELL_REQUIRED
        settle()
        assertPresentation(R.string.settings_shizuku_status_shell_required, R.string.shizuku_shell_required)
        assertCalls()

        state = ShizukuState.READY_ROOT
        settle()
        assertPresentation(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertShizukuNotClickable(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertCalls()

        rootEnabled = false
        settle()
        assertPresentation(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertShizukuNotClickable(R.string.settings_shizuku_status_root_ready, R.string.settings_shizuku_hint_root_ready)
        assertCalls()
    }

    private class Ui(
        val activity: ComponentActivity,
        private val view: ComposeView,
        initialState: ShizukuState,
        val pump: (Int) -> Unit
    ) {
        var state by mutableStateOf(initialState)
        var rootEnabled by mutableStateOf(false)
        private var requests = 0
        private var opens = 0
        private var refreshes = 0
        private var resumeRefreshBaseline = 0
        private val rootChanges = mutableListOf<Boolean>()

        @Composable
        fun Content() {
            assertSame(activity, LocalLifecycleOwner.current)
            assertSame(activity.activityResultRegistry, LocalActivityResultRegistryOwner.current?.activityResultRegistry)
            AppPermissionsSection(
                shizukuState = state,
                onRequestShizukuPermission = { requests++ },
                onOpenShizuku = { opens++ },
                onRefreshShizuku = { refreshes++ },
                rootEnabled = rootEnabled,
                onSetRootEnabled = { rootChanges += it }
            )
        }

        fun recordInitialResumeRefresh() {
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            assertEquals("The initial LifecycleResumeEffect must refresh exactly once", 1, refreshes)
            resumeRefreshBaseline = refreshes
            assertCalls()
        }

        private fun nodes(): List<SemanticsNode> = requireNotNull(findRoot(view)).semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false)

        private fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }

        private fun textNode(resource: Int): SemanticsNode {
            val text = activity.getString(resource)
            return nodes().single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
        }

        fun assertPresentation(status: Int, hint: Int) {
            val visible = texts()
            assertTrue(activity.getString(R.string.settings_shizuku) in visible)
            assertTrue(activity.getString(status) in visible)
            assertTrue(activity.getString(hint) in visible)
            assertRootMatchesEnabled()
        }

        fun assertAbsent(vararg resources: Int) {
            val visible = texts()
            resources.forEach { resource ->
                val text = activity.getString(resource)
                assertFalse("Unexpected Shizuku text: $text", text in visible)
            }
        }

        fun assertCalls(requests: Int = 0, opens: Int = 0, refreshes: Int = 0) {
            assertEquals("Permission requests", requests, this.requests)
            assertEquals("Open Shizuku requests", opens, this.opens)
            assertEquals("Refreshes after the initial resume", refreshes, this.refreshes - resumeRefreshBaseline)
            assertTrue("Shizuku actions must not enable or request Root", rootChanges.isEmpty())
            assertRootMatchesEnabled()
            assertNull("No external activity may be launched", shadowOf(activity.application).nextStartedActivity)
        }

        private fun assertRootMatchesEnabled() {
            val rootRow = generateSequence(textNode(R.string.settings_root_access)) { it.parent }
                .first { node -> descendants(node).any { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch } }
            val rowNodes = descendants(rootRow)
            assertFalse("Locate only the Root row, not the surrounding permissions page", rowNodes.any { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                    it.text == activity.getString(R.string.mobile_build_enable) ||
                        it.text == activity.getString(R.string.settings_shizuku)
                } == true
            })
            val toggle = rowNodes.single { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }
            val expected = if (rootEnabled) ToggleableState.On else ToggleableState.Off
            assertEquals("The Root switch must follow only rootEnabled", expected, toggle.config[SemanticsProperties.ToggleableState])
        }

        private fun shizukuAction(): SemanticsNode? = generateSequence(textNode(R.string.settings_shizuku)) { it.parent }
            .firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null }

        fun clickShizuku() {
            val node = requireNotNull(shizukuAction()) { "The Shizuku row must expose its own action" }
            val rowTexts = descendants(node).flatMap { child ->
                child.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            }
            assertTrue(activity.getString(R.string.settings_shizuku) in rowTexts)
            assertFalse("Do not click a surrounding settings container",
                activity.getString(R.string.settings_permission_storage) in rowTexts)
            assertFalse("Do not click the Root row", activity.getString(R.string.settings_root_access) in rowTexts)
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            settle()
        }

        fun assertShizukuNotClickable(
            status: Int = R.string.settings_shizuku_status_ready,
            hint: Int = R.string.settings_shizuku_hint_ready
        ) {
            assertNull("READY and READY_ROOT must remove the Shizuku row action, not retain an earlier callback", shizukuAction())
            assertFalse(textNode(status).config.contains(SemanticsActions.OnClick))
            assertFalse(textNode(hint).config.contains(SemanticsActions.OnClick))
        }

        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(120) { frame ->
                pump(1)
                val semantics = nodes().map { node ->
                    "${node.id}:${node.positionInRoot}:${node.size}:" +
                        "${node.config.getOrNull(SemanticsProperties.Text)}:" +
                        "${node.config.getOrNull(SemanticsActions.OnClick)?.action != null}:" +
                        node.config.getOrNull(SemanticsProperties.ToggleableState)
                }
                stable = if (semantics == previous) stable + 1 else 0
                if (stable >= 4 && frame >= 24) return
                previous = semantics
            }
            error("Shizuku permission Compose UI did not settle within 120 controlled frames: ${texts()}")
        }
    }

    private fun render(initialState: ShizukuState, inspect: Ui.() -> Unit): Unit = runTest(timeout = 60.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var frame = 0L
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            findRoot(view)?.measureAndLayoutForTest()
        }
        val ui = Ui(activity, view, initialState) { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout()
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) { ui.Content() }
                }
            }
            activity.setContentView(view)
            ui.settle()
            ui.recordInitialResumeRefresh()
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
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
