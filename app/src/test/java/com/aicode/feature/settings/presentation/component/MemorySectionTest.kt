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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.settings.presentation.MemoryUiState
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
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

    @Test fun listRowsPlaceRecentBeforeOlderBeforeUnknownByActualVerticalPosition() {
        val recent = timedEntry(MemoryScope.GLOBAL, "z-recent.md", "z-recent", 3_000L)
        val older = timedEntry(MemoryScope.GLOBAL, "m-older.md", "m-older", 1_000L)
        val unknown = timedEntry(MemoryScope.GLOBAL, "a-unknown.md", "a-unknown", null)
        render({
            MemorySection(MemoryUiState(entries = listOf(unknown, older, recent), hasWorkspace = true, loading = false),
                {}, {}, scope = MemoryScope.GLOBAL, query = "", onScopeChange = {}, onQueryChange = {})
        }) { root, _, _ ->
            assertRowOrder(root, listOf(recent, older, unknown))
            listOf(recent, older, unknown).forEach { item ->
                val preview = taggedNode(root, "memory-preview-${item.id}")
                val updated = taggedNode(root, "memory-updated-${item.id}")
                assertNotEquals(preview.id, updated.id)
                assertTrue("The update time must have its own line below the preview",
                    preview.boundsInRoot.bottom <= updated.boundsInRoot.top)
            }
        }
    }

    @Config(qualifiers = "tr-rTR")
    @Test fun listEqualTimeRowsUseRootCaseFoldedNameThenFileNameRegardlessOfInputOrder() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val firstByFile = timedEntry(MemoryScope.PROJECT, "B-file.md", "iota", 2_000L)
            val nextByFile = timedEntry(MemoryScope.PROJECT, "a-file.md", "IOta", 2_000L)
            val lastByName = timedEntry(MemoryScope.PROJECT, "0-first.md", "Jupiter", 2_000L)
            val input = mutableStateOf(listOf(lastByName, nextByFile, firstByFile))
            render({
                MemorySection(MemoryUiState(entries = input.value, hasWorkspace = true, loading = false),
                    {}, {}, scope = MemoryScope.PROJECT, query = "", onScopeChange = {}, onQueryChange = {})
            }) { root, _, pump ->
                assertEquals("tr", Locale.getDefault().language)
                assertRowOrder(root, listOf(firstByFile, nextByFile, lastByName))
                input.value = listOf(nextByFile, firstByFile, lastByName)
                pump(12)
                assertRowOrder(root, listOf(firstByFile, nextByFile, lastByName))
                input.value = listOf(nextByFile, lastByName, firstByFile).map { it.copy(modifiedAt = null) }
                pump(12)
                assertRowOrder(root, listOf(firstByFile, nextByFile, lastByName))
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test fun listScopeAndSearchChangesKeepNewestFirstAndNeverMixSameNamedScopes() {
        val globalRecent = timedEntry(MemoryScope.GLOBAL, "z-recent.md", "z-recent", 3_000L)
        val globalOlder = timedEntry(MemoryScope.GLOBAL, "m-older.md", "m-older", 2_000L,
            description = "Needle summary")
        val globalUnknown = timedEntry(MemoryScope.GLOBAL, "a-unknown.md", "a-unknown", null,
            preview = "Needle preview")
        val projectRecent = globalUnknown.copy(scope = MemoryScope.PROJECT, modifiedAt = 4_000L)
        val projectOlder = globalRecent.copy(scope = MemoryScope.PROJECT, modifiedAt = 1_000L)
        val projectUnknown = globalOlder.copy(scope = MemoryScope.PROJECT, modifiedAt = null)
        val selectedScope = mutableStateOf(MemoryScope.GLOBAL)
        val query = mutableStateOf("")
        render({
            MemorySection(MemoryUiState(entries = listOf(globalUnknown, projectUnknown, globalOlder,
                projectOlder, globalRecent, projectRecent), hasWorkspace = true, loading = false),
                {}, {}, scope = selectedScope.value, query = query.value,
                onScopeChange = { selectedScope.value = it }, onQueryChange = { query.value = it })
        }) { root, _, pump ->
            fun search(value: String) {
                val input = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
                    it.config.getOrNull(SemanticsActions.SetText)?.action != null
                }
                assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
                pump(12)
                assertEquals(value, query.value)
            }
            fun select(scope: MemoryScope) {
                val tag = if (scope == MemoryScope.GLOBAL) "memory-scope-global" else "memory-scope-project"
                assertTrue(requireNotNull(taggedNode(root, tag).config[SemanticsActions.OnClick].action).invoke())
                pump(12)
                assertEquals(scope, selectedScope.value)
            }
            assertRowOrder(root, listOf(globalRecent, globalOlder, globalUnknown))
            search("  nEeDlE  ")
            assertRowOrder(root, listOf(globalOlder, globalUnknown))
            select(MemoryScope.PROJECT)
            assertEquals("  nEeDlE  ", query.value)
            assertRowOrder(root, listOf(projectRecent, projectUnknown))
            search("")
            assertRowOrder(root, listOf(projectRecent, projectOlder, projectUnknown))
            select(MemoryScope.GLOBAL)
            assertRowOrder(root, listOf(globalRecent, globalOlder, globalUnknown))
            search("  M-OLDER ")
            assertRowOrder(root, listOf(globalOlder))
        }
    }

    @Config(qualifiers = "en-rUS")
    @Test fun listUpdatedAtUsesUtcMinuteFormatAndEnglishUnknownLabel() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val known = timedEntry(MemoryScope.GLOBAL, "known.md", "known",
                Instant.parse("2024-01-02T23:04:59Z").toEpochMilli())
            val unknown = timedEntry(MemoryScope.GLOBAL, "unknown.md", "unknown", null,
                description = "", preview = "")
            render({
                MemorySection(MemoryUiState(entries = listOf(unknown, known), hasWorkspace = true, loading = false),
                    {}, {}, scope = MemoryScope.GLOBAL, query = "", onScopeChange = {}, onQueryChange = {})
            }) { root, activity, _ ->
                assertEquals("Updated 2024-01-02 23:04",
                    activity.getString(R.string.memory_management_updated_at, "2024-01-02 23:04"))
                assertEquals(listOf("Updated 2024-01-02 23:04"),
                    taggedNode(root, "memory-updated-${known.id}").config[SemanticsProperties.Text].map { it.text })
                assertEquals("Time unknown", activity.getString(R.string.memory_management_time_unknown))
                assertEquals(listOf("Updated Time unknown"),
                    taggedNode(root, "memory-updated-${unknown.id}").config[SemanticsProperties.Text].map { it.text })
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Config(qualifiers = "zh-rCN")
    @Test fun listUpdatedAtUsesDeviceTimeZoneAndChineseUnknownLabel() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
            val known = timedEntry(MemoryScope.GLOBAL, "known.md", "known",
                Instant.parse("2024-01-02T23:04:59Z").toEpochMilli())
            val unknown = timedEntry(MemoryScope.GLOBAL, "unknown.md", "unknown", null)
            render({
                MemorySection(MemoryUiState(entries = listOf(unknown, known), hasWorkspace = true, loading = false),
                    {}, {}, scope = MemoryScope.GLOBAL, query = "", onScopeChange = {}, onQueryChange = {})
            }) { root, activity, _ ->
                assertEquals("更新于 2024-01-03 07:04",
                    activity.getString(R.string.memory_management_updated_at, "2024-01-03 07:04"))
                assertEquals(listOf("更新于 2024-01-03 07:04"),
                    taggedNode(root, "memory-updated-${known.id}").config[SemanticsProperties.Text].map { it.text })
                assertEquals("时间未知", activity.getString(R.string.memory_management_time_unknown))
                assertEquals(listOf("更新于 时间未知"),
                    taggedNode(root, "memory-updated-${unknown.id}").config[SemanticsProperties.Text].map { it.text })
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Config(qualifiers = "en-rUS-w320dp-h640dp")
    @TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
    @Test fun listNarrowRowKeepsUpdatedAtInItsOwnTextBelowPreviewWhenNameRequestsEllipsis() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val item = timedEntry(MemoryScope.GLOBAL, "long.md", "A very long memory name ".repeat(20).trimEnd(),
                Instant.parse("2024-01-02T23:04:59Z").toEpochMilli(),
                description = "Summary", preview = "Preview")
            render({
                MemorySection(MemoryUiState(entries = listOf(item), hasWorkspace = true, loading = false),
                    {}, {}, scope = MemoryScope.GLOBAL, query = "", onScopeChange = {}, onQueryChange = {})
            }, width = 320, height = 640) { root, _, _ ->
                assertRowOrder(root, listOf(item))
                val name = taggedNode(root, "memory-name-${item.id}")
                val preview = taggedNode(root, "memory-preview-${item.id}")
                val updated = taggedNode(root, "memory-updated-${item.id}")
                assertNotEquals(name.id, updated.id)
                assertNotEquals(preview.id, updated.id)
                assertEquals(listOf(item.name), name.config[SemanticsProperties.Text].map { it.text })
                assertEquals(listOf("Updated 2024-01-02 23:04"), updated.config[SemanticsProperties.Text].map { it.text })
                assertTrue(name.boundsInRoot.bottom <= preview.boundsInRoot.top)
                assertTrue(preview.boundsInRoot.bottom <= updated.boundsInRoot.top)
                assertEquals(preview.boundsInRoot.left, updated.boundsInRoot.left, 0.5f)
                val row = taggedNode(root, "memory-row-${item.id}").boundsInRoot
                assertTrue(updated.boundsInRoot.width > 0f && updated.boundsInRoot.height > 0f)
                assertTrue(updated.boundsInRoot.left >= row.left && updated.boundsInRoot.right <= row.right)
                assertTrue(updated.boundsInRoot.top >= row.top && updated.boundsInRoot.bottom <= row.bottom)
                val nameLayout = textLayout(name)
                assertEquals(item.name, nameLayout.layoutInput.text.text)
                assertEquals(1, nameLayout.layoutInput.maxLines)
                assertEquals(TextOverflow.Ellipsis, nameLayout.layoutInput.overflow)
                assertTrue(nameLayout.layoutInput.softWrap)
                assertTrue(nameLayout.multiParagraph.intrinsics.maxIntrinsicWidth > name.boundsInRoot.width)
                assertTrue(nameLayout.size.width <= nameLayout.layoutInput.constraints.maxWidth)
                assertTrue(nameLayout.size.width <= name.boundsInRoot.width + 0.5f)
                assertEquals("API 28 legacy line-break shadow returns one full paragraph", 1, nameLayout.lineCount)
                assertEquals("The shadow cannot prove real glyph ellipsis", item.name.length,
                    nameLayout.getLineEnd(0, visibleEnd = true))
                assertFalse("The shadow cannot prove a requested overflowing ellipsis glyph", nameLayout.isLineEllipsized(0))
                val updatedLayout = textLayout(updated)
                assertEquals("Updated 2024-01-02 23:04", updatedLayout.layoutInput.text.text)
                assertTrue(updatedLayout.layoutInput.softWrap)
                assertEquals(Int.MAX_VALUE, updatedLayout.layoutInput.maxLines)
                assertEquals(TextOverflow.Clip, updatedLayout.layoutInput.overflow)
                assertFalse(updatedLayout.didOverflowHeight)
                assertFalse(updatedLayout.multiParagraph.didExceedMaxLines)
                assertTrue(updatedLayout.lineCount > 0)
                // The API 28 shadow reports the constraint width for a wrap-content paragraph; check visible line bounds.
                if (updatedLayout.lineCount == 1) {
                    assertTrue(updatedLayout.multiParagraph.intrinsics.maxIntrinsicWidth <= updated.boundsInRoot.width + 0.5f)
                }
                assertTrue(updatedLayout.size.width <= updated.boundsInRoot.width + 0.5f)
                assertTrue(updatedLayout.multiParagraph.height <= updatedLayout.size.height + 0.5f)
                assertTrue(updatedLayout.size.height <= updated.boundsInRoot.height + 0.5f)
                assertEquals(0, updatedLayout.getLineStart(0))
                assertEquals("Updated 2024-01-02 23:04".length,
                    updatedLayout.getLineEnd(updatedLayout.lineCount - 1, visibleEnd = true))
                for (line in 0 until updatedLayout.lineCount) {
                    assertFalse(updatedLayout.isLineEllipsized(line))
                    assertTrue(updatedLayout.getLineLeft(line) >= -0.5f)
                    assertTrue(updatedLayout.getLineRight(line) <= updated.boundsInRoot.width + 0.5f)
                    assertTrue(updatedLayout.getLineTop(line) >= -0.5f)
                    assertTrue(updatedLayout.getLineBottom(line) <= updated.boundsInRoot.height + 0.5f)
                    assertTrue(updatedLayout.getLineBottom(line) > updatedLayout.getLineTop(line))
                    if (line > 0) assertTrue(updatedLayout.getLineTop(line) >= updatedLayout.getLineBottom(line - 1))
                }
            }
        } finally {
            TimeZone.setDefault(previousZone)
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

    private fun timedEntry(
        scope: MemoryScope,
        fileName: String,
        name: String,
        modifiedAt: Long?,
        description: String = "Summary",
        preview: String = "Preview"
    ) = MemoryEntry(scope, fileName, name, description, preview, "$scope-$fileName-v1", modifiedAt)

    private fun taggedNode(root: ViewRootForTest, tag: String): SemanticsNode =
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
            it.config.getOrNull(SemanticsProperties.TestTag) == tag
        }

    private fun assertRowOrder(root: ViewRootForTest, expected: List<MemoryEntry>) {
        val rows = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("memory-row-") == true
        }
        assertEquals(expected.size, rows.size)
        assertEquals(expected.map { "memory-row-${it.id}" }.toSet(),
            rows.map { it.config[SemanticsProperties.TestTag] }.toSet())
        val viewport = taggedNode(root, "memory-list").boundsInRoot
        val expectedRows = expected.map { taggedNode(root, "memory-row-${it.id}") }
        expectedRows.forEach { row ->
            val bounds = row.boundsInRoot
            assertTrue("${row.config[SemanticsProperties.TestTag]} must really be visible: $bounds in $viewport",
                bounds.width > 0f && bounds.height > 0f && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
        }
        expectedRows.zipWithNext().forEach { (first, second) ->
            assertTrue("${first.config[SemanticsProperties.TestTag]} at ${first.boundsInRoot.top} must be above " +
                "${second.config[SemanticsProperties.TestTag]} at ${second.boundsInRoot.top}",
                first.boundsInRoot.top < second.boundsInRoot.top)
        }
    }

    private fun textLayout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(results))
        return results.single()
    }

    private fun texts(root: ViewRootForTest): List<String> =
        root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
        }

    private fun render(
        content: @Composable () -> Unit,
        width: Int = 1_000,
        height: Int = 2_000,
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
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
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
