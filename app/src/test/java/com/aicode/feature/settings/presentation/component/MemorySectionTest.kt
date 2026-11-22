package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.settings.presentation.MemoryUiState
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MemorySectionTest {
    @Test fun noWorkspaceShowsBothGroupsAndTheProjectUnavailableHint() {
        render({
            MemorySection(MemoryUiState(loading = false), {}, {}, scope = MemoryScope.GLOBAL, query = "",
                onScopeChange = {}, onQueryChange = {})
        }) { root, activity, _ ->
            val texts = texts(root)
            assertTrue(activity.getString(R.string.memory_a_global) in texts)
            assertTrue(activity.getString(R.string.memory_a_current_project) in texts)
            assertTrue(activity.getString(R.string.memory_management_no_workspace) in texts)
            val tabs = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Tab
            }
            assertEquals(2, tabs.size)
            val global = tabs.single { it.config.getOrNull(SemanticsProperties.Selected) == true }
            val project = tabs.single { it.config.getOrNull(SemanticsProperties.Selected) == false }
            assertFalse(global.config.contains(SemanticsProperties.Disabled))
            assertTrue(project.config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun sameNameRowsOpenTheirExactScopeAndOnlyRenderLightweightPreviews() {
        val global = entry(MemoryScope.GLOBAL)
        val project = entry(MemoryScope.PROJECT)
        var opened: MemoryEntry? = null
        val selectedScope = mutableStateOf(MemoryScope.PROJECT)
        render({
            MemorySection(MemoryUiState(entries = listOf(global, project), hasWorkspace = true, projectName = "workspace", loading = false),
                onOpenDetail = { opened = it }, onDelete = {}, scope = selectedScope.value, query = "",
                onScopeChange = { selectedScope.value = it }, onQueryChange = {})
        }) { root, _, pump ->
            listOf(project, global).forEach { expected ->
                if (selectedScope.value != expected.scope) {
                    val tab = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "memory-scope-global"
                    }
                    assertTrue(tab.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
                    pump(12)
                }
                val rows = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter { node ->
                    node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("memory-row-") == true
                }
                assertEquals(1, rows.size)
                assertEquals("memory-row-${expected.id}", rows.single().config[SemanticsProperties.TestTag])
                val visible = texts(root)
                assertTrue(expected.name in visible)
                assertTrue(expected.description in visible)
                assertTrue(expected.preview in visible)
                assertFalse((if (expected.scope == MemoryScope.PROJECT) global else project).preview in visible)
                fun containsName(node: androidx.compose.ui.semantics.SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == expected.name } == true ||
                        node.children.any(::containsName)
                fun openAction(node: androidx.compose.ui.semantics.SemanticsNode): (() -> Boolean)? =
                    node.config.getOrNull(SemanticsActions.OnClick)?.action?.takeIf { containsName(node) }
                        ?: node.children.firstNotNullOfOrNull(::openAction)
                assertTrue(requireNotNull(openAction(rows.single())).invoke())
                assertEquals(expected, opened)
            }
        }
    }

    @Test fun projectScopeIsDisabledWithoutWorkspaceAndGlobalRemainsSelected() {
        render({ NewMemoryForm(hasWorkspace = false, saving = false, error = null, onSave = { _, _, _, _ -> },
            initialScope = MemoryScope.PROJECT) }) { root, activity, _ ->
            val radios = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
                it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
            }
            assertEquals(2, radios.size)
            val global = radios.single { it.config.getOrNull(SemanticsProperties.Selected) == true }
            val project = radios.single { it.config.getOrNull(SemanticsProperties.Selected) == false }
            assertFalse(global.config.contains(SemanticsProperties.Disabled))
            assertTrue(project.config.contains(SemanticsProperties.Disabled))
            assertTrue(activity.getString(R.string.memory_management_no_workspace) in texts(root))
        }
    }

    @Test fun createConflictIsVisibleAndTheFormHasOnlyNameSummaryAndBodyInputs() {
        render({
            NewMemoryForm(hasWorkspace = true, saving = false, error = MemoryManagementError.NAME_CONFLICT,
                onSave = { _, _, _, _ -> })
        }) { root, activity, _ ->
            assertTrue(activity.getString(R.string.memory_management_name_conflict) in texts(root))
            val inputs = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
                it.config.getOrNull(SemanticsActions.SetText)?.action != null
            }
            assertEquals(3, inputs.size)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
    @Config(qualifiers = "w400dp-h1000dp")
    @Test(timeout = 30_000)
    fun savingRejectsHideAndFailureKeepsTheVisibleDraftForRetry() = runTest(timeout = 15.seconds) {
        val saving = mutableStateOf(false)
        val failure = mutableStateOf<MemoryManagementError?>(null)
        val submitted = mutableListOf<List<String>>()
        var dismissRequests = 0
        lateinit var sheetState: SheetState
        lateinit var show: () -> Job
        lateinit var hide: () -> Job
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var sheetRoot: ViewRootForTest? = null
        var sheetStateReady = false
        var compositionWidthDp = 0
        val mainLooper = shadowOf(Looper.getMainLooper())
        var frame = 0L
        fun findComposeRoot(candidate: View): ViewRootForTest? {
            if (candidate is ViewRootForTest) return candidate
            if (candidate is ViewGroup) {
                repeat(candidate.childCount) { index ->
                    findComposeRoot(candidate.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        fun awaitUi(message: String, condition: () -> Boolean) {
            repeat(180) {
                repeat(4) { mainLooper.runOneTask() }
                Snapshot.sendApplyNotifications()
                runCurrent()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                findComposeRoot(view)?.measureAndLayoutForTest()
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { decor ->
                    decor.measure(
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY)
                    )
                    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                    sheetRoot = findComposeRoot(decor)
                    sheetRoot?.measureAndLayoutForTest()
                }
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
                if (condition()) return
            }
            val dialog = ShadowDialog.getLatestDialog()
            val decor = dialog?.window?.decorView
            val state = if (sheetStateReady) {
                "expandedAnchor=${sheetState.hasExpandedState}, current=${sheetState.currentValue}, target=${sheetState.targetValue}"
            } else "sheetState=uninitialized"
            fail("$message did not complete within 180 controlled frames; " +
                "widthDp=$compositionWidthDp, host=${view.measuredWidth}x${view.measuredHeight}, " +
                "hostRoot=${findComposeRoot(view) != null}, dialog=${dialog?.javaClass?.simpleName}, showing=${dialog?.isShowing}, " +
                "decor=${decor?.measuredWidth}x${decor?.measuredHeight}, sheetRoot=${sheetRoot != null}, " +
                "$state, mainIdle=${mainLooper.isIdle}")
        }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // 独立 Recomposer 让弹窗自动动画也等待手动帧，避免 AndroidUiDispatcher 在 paused looper 内自调度饥饿。
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false) {
                        sheetState = rememberNewMemorySheetState(saving.value)
                        val widthDp = LocalConfiguration.current.screenWidthDp
                        val scope = rememberCoroutineScope()
                        SideEffect {
                            sheetStateReady = true
                            compositionWidthDp = widthDp
                            show = { scope.launch { sheetState.show() } }
                            hide = { scope.launch { sheetState.hide() } }
                        }
                        AdaptiveModalBottomSheet(
                            sheetState = sheetState,
                            sheetGesturesEnabled = !saving.value,
                            onDismissRequest = { if (!saving.value) dismissRequests++ }
                        ) {
                            NewMemoryForm(hasWorkspace = true, saving = saving.value, error = failure.value,
                                onSave = { name, summary, body, selectedScope ->
                                    submitted += listOf(name, summary, body, selectedScope.name)
                                    saving.value = true
                                })
                        }
                    }
                }
            }
            activity.setContentView(view)
            awaitUi("Bottom sheet layout") { sheetRoot != null && sheetState.hasExpandedState }
            val root = requireNotNull(sheetRoot)
            assertNotSame("The bottom sheet must use its own dialog window", findComposeRoot(view), root)
            val initialShow = show()
            awaitUi("Initial show") { initialShow.isCompleted }
            assertFalse(initialShow.isCancelled)
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            fun clickCreate() {
                val create = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any {
                        it.text == activity.getString(R.string.memory_management_create)
                    } == true && node.config.getOrNull(SemanticsActions.OnClick)?.action != null
                }
                assertFalse(create.config.contains(SemanticsProperties.Disabled))
                assertTrue(create.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
            }
            val inputs = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
                it.config.getOrNull(SemanticsActions.SetText)?.action != null
            }.sortedBy { it.boundsInRoot.top }
            val draft = listOf("draft-memory", "Draft summary", "# Keep this Markdown\n\nComplete draft body")
            assertEquals(draft.size, inputs.size)
            inputs.zip(draft).forEach { (input, text) ->
                assertTrue(input.config.getOrNull(SemanticsActions.SetText)!!.action!!.invoke(AnnotatedString(text)))
            }
            awaitUi("Draft input") {
                root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).mapNotNull {
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text
                }.containsAll(draft)
            }
            clickCreate()
            awaitUi("Saving form") { activity.getString(R.string.memory_management_creating) in texts(root) }
            assertTrue(saving.value)
            assertEquals(listOf(draft + MemoryScope.GLOBAL.name), submitted)

            val hideAttempt = hide()
            awaitUi("Saving hide attempt") { hideAttempt.isCompleted }
            assertFalse(hideAttempt.isCancelled)
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            assertEquals(SheetValue.Expanded, sheetState.targetValue)
            assertTrue(sheetState.isVisible)
            assertEquals(0, dismissRequests)

            saving.value = false
            failure.value = MemoryManagementError.NAME_CONFLICT
            awaitUi("Failed save form") { activity.getString(R.string.memory_management_name_conflict) in texts(root) }
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            assertTrue(sheetState.isVisible)
            val retained = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
                it.config.getOrNull(SemanticsActions.SetText)?.action != null
            }.sortedBy { it.boundsInRoot.top }
            assertEquals(draft, retained.map { it.config.getOrNull(SemanticsProperties.EditableText)!!.text })
            assertTrue(retained.all { !it.config.contains(SemanticsProperties.Disabled) })
            clickCreate()
            awaitUi("Retry saving form") { activity.getString(R.string.memory_management_creating) in texts(root) }
            assertEquals(listOf(draft + MemoryScope.GLOBAL.name, draft + MemoryScope.GLOBAL.name), submitted)
            saving.value = false
            failure.value = null
            awaitUi("Retry finished") { activity.getString(R.string.memory_management_create) in texts(root) }
            val allowedHide = hide()
            awaitUi("Unlocked hide") { allowedHide.isCompleted }
            assertFalse(allowedHide.isCancelled)
            assertEquals(SheetValue.Hidden, sheetState.currentValue)
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }

    @Test fun detailShowsTheExactFileAndScopeAndCopiesTheCompleteMarkdownBody() {
        val document = MemoryDocument(entry(MemoryScope.PROJECT), "# Complete Markdown\n\n**all** of the body")
        render({ MemoryDetailSection(document, projectName = "workspace") }) { root, activity, _ ->
            assertTrue(document.entry.fileName in texts(root))
            assertTrue(activity.getString(R.string.memory_management_project_named, "workspace") in texts(root))
            val copy = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                    it.text == activity.getString(R.string.memory_management_copy_body)
                } == true && node.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            assertTrue(copy.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(document.content, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun newMemoryFormStartsInTheCurrentProjectScopeWithoutChangingItsDefaultGlobalContract() {
        MemoryScope.entries.forEach { initial ->
            render({ NewMemoryForm(hasWorkspace = true, saving = false, error = null,
                onSave = { _, _, _, _ -> }, initialScope = initial) }) { root, activity, _ ->
                val selected = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                        it.config.getOrNull(SemanticsProperties.Selected) == true
                }
                val option = requireNotNull(selected.parent)
                fun containsScopeText(node: androidx.compose.ui.semantics.SemanticsNode): Boolean =
                    node.config.getOrNull(SemanticsProperties.Text)?.any {
                        it.text == activity.getString(memoryScopeLabelRes(initial))
                    } == true || node.children.any(::containsScopeText)
                assertTrue(containsScopeText(option))
                assertFalse(selected.config.contains(SemanticsProperties.Disabled))
            }
        }
    }

    @Test fun savingDisablesAllDraftInputsAndBothScopeChoices() {
        render({ NewMemoryForm(hasWorkspace = true, saving = true, error = null,
            onSave = { _, _, _, _ -> }, initialScope = MemoryScope.PROJECT) }) { root, activity, _ ->
            val nodes = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            val inputs = nodes.filter { it.config.contains(SemanticsProperties.EditableText) }
            assertEquals(3, inputs.size)
            assertTrue(inputs.all { it.config.contains(SemanticsProperties.Disabled) })
            assertTrue(inputs.all { it.config.getOrNull(SemanticsActions.SetText)?.action == null })
            val radios = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            assertEquals(2, radios.size)
            assertTrue(radios.all { it.config.contains(SemanticsProperties.Disabled) })
            val create = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any {
                    it.text == activity.getString(R.string.memory_management_creating)
                } == true && node.config.contains(SemanticsActions.OnClick)
            }
            assertTrue(create.config.contains(SemanticsProperties.Disabled))
        }
    }

    private fun entry(scope: MemoryScope) = MemoryEntry(
        scope, "same-name.md", "same-name", "${scope.name} summary", "${scope.name} preview", "${scope.name}-v1"
    )

    private fun texts(root: ViewRootForTest): List<String> =
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
        }

    private fun render(
        content: @Composable () -> Unit,
        inspect: (ViewRootForTest, ComponentActivity, (Int) -> Unit) -> Unit
    ): Unit = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var frame = 0L
        val pump: (Int) -> Unit = { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2_000, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            (view.getChildAt(0) as? ViewRootForTest)?.measureAndLayoutForTest()
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            pump(12)
            val root = view.getChildAt(0) as ViewRootForTest
            root.measureAndLayoutForTest()
            inspect(root, activity, pump)
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }
}
