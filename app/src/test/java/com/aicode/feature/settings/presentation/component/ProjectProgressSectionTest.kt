package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.memory.ProjectMemoryAssociations
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapacity
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryProject
import com.aicode.feature.settings.presentation.ProjectProgressEntryAction
import com.aicode.feature.settings.presentation.ProjectProgressError
import com.aicode.feature.settings.presentation.ProjectProgressForm
import com.aicode.feature.settings.presentation.ProjectProgressFilter
import com.aicode.feature.settings.presentation.ProjectProgressUiState
import java.text.DateFormat
import java.time.Duration
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
@OptIn(InternalComposeUiApi::class)
class ProjectProgressSectionTest {
    private val identity = ProjectMemoryIdentity("local_proot", projectRoot = "/workspace", profileId = "profile")
    private val project = ProjectMemoryProject("project-id", "Project name", identity = identity)
    private val associations = ProjectMemoryAssociations(true, null, false, null)

    @Test fun noWorkspaceShowsTheExplicitEmptyState() {
        render({ section(ProjectProgressUiState(loading = false)) }) { root, activity ->
            assertTrue(activity.getString(R.string.project_progress_no_workspace) in texts(root))
            assertFalse(activity.getString(R.string.project_progress_register) in texts(root))
        }
    }

    @Test fun openingAProjectUsesTheExactRowAndCapacityFailureShowsUnknown() {
        var selected: ProjectMemoryProject? = null
        val sibling = project.copy(id = "other-id", name = "Other project", identity = identity.copy(projectRoot = "/other"))
        render({
            section(baseState().copy(projects = listOf(project, sibling)), onSelect = { selected = it })
        }) { root, activity ->
            assertTrue(activity.getString(R.string.project_progress_capacity_unknown) in texts(root))
            clickText(root, sibling.name)
            assertEquals(sibling, selected)
        }
    }

    @Test fun explicitNullBindingIsNotPresentedAsAutomaticOrAsTheWorkspaceDefault() {
        render({ Text(projectProgressBindingText(associations.copy(workspaceBindingPresent = true, workspaceProject = project), true)) }) { root, activity ->
            assertTrue(activity.getString(R.string.project_progress_explicit_none) in texts(root))
            assertFalse(activity.getString(R.string.project_progress_bound_project, project.name) in texts(root))
        }
    }

    @Test fun interruptedHistoryShowsUnverifiedStatusAndTheRealTimestamp() {
        val entry = entry().copy(status = "interrupted", revoked = true, archived = true)
        render({ Text(projectProgressEntryMetadata(entry)) }) { root, activity ->
            val text = texts(root).joinToString("\n")
            assertTrue(text.contains(activity.getString(R.string.project_progress_status_interrupted)))
            assertTrue(text.contains(activity.getString(R.string.project_progress_unverified)))
            assertTrue(text.contains(activity.getString(R.string.project_progress_revoked)))
            assertTrue(text.contains(activity.getString(R.string.project_progress_archived)))
            assertTrue(text.contains(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.updatedAt))))
        }
    }

    @Test fun selectedProjectUsesTheSharedSwitchAndProducesOnlyTheRequestedChange() {
        var change: Boolean? = null
        render({
            ProjectProgressManagement(baseState().copy(projects = listOf(project), selectedProjectId = project.id),
                true, {}, true, {}, {}, { change = it }, {})
        }) { root, _ ->
            val toggle = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch
            }
            assertTrue(toggle.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
            assertEquals(false, change)
        }
    }

    @Test fun registrationHasOnlyNameWhileManualHandoffHasSummaryAndBody() {
        render({ ProjectProgressEditorForm(ProjectProgressForm.REGISTER, false, null, {}, { _, _ -> }) }) { root, _ ->
            assertEquals(1, inputs(root).size)
        }
        render({ ProjectProgressEditorForm(ProjectProgressForm.REGISTER, false, ProjectProgressError.READ_FAILED,
            {}, { _, _ -> }, registrationReady = false) }) { root, activity ->
            assertTrue(activity.getString(R.string.project_progress_save) in texts(root))
            val saveButton = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                        text.text == activity.getString(R.string.project_progress_save)
                    } == true
            }
            assertTrue(saveButton.config.contains(SemanticsProperties.Disabled))
        }
        render({ ProjectProgressEditorForm(ProjectProgressForm.HANDOFF, false, ProjectProgressError.SAVE_FAILED, {}, { _, _ -> }) }) { root, activity ->
            assertEquals(2, inputs(root).size)
            assertTrue(activity.getString(R.string.project_progress_save_failed) in texts(root))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test(timeout = 30_000)
    fun failedSaveRetainsTheExactPlainTextDraftAndReenablesInputsForRetry() = runTest(timeout = 15.seconds) {
        val saving = mutableStateOf(false)
        val error = mutableStateOf<ProjectProgressError?>(null)
        val submitted = mutableListOf<Pair<String, String>>()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        var frame = 0L
        fun awaitUi(root: ViewRootForTest, message: String, condition: () -> Boolean) {
            repeat(120) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                repeat(4) { shadowOf(Looper.getMainLooper()).runOneTask() }
                root.measureAndLayoutForTest()
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
                root.measureAndLayoutForTest()
                if (condition()) return
            }
            fail("$message did not complete within 120 controlled frames; " +
                "recomposer=${recomposer.currentState.value}, frameAwaiters=${clock.hasAwaiters}, " +
                "inputs=${inputs(root).map { it.config.getOrNull(SemanticsProperties.EditableText)?.text to it.config.contains(SemanticsProperties.Disabled) }}, " +
                "texts=${texts(root)}")
        }
        try {
            render({
                ProjectProgressEditorForm(ProjectProgressForm.HANDOFF, saving.value, error.value, {}, { name, body ->
                    submitted += name to body
                    saving.value = true
                })
            }, parentCompositionContext = recomposer) { root, activity ->
                val draft = listOf("Handoff summary", "Literal **body**\nSecond line")
                val saveLabel = activity.getString(R.string.project_progress_save)
                fun saveEnabled(): Boolean = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).any { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == saveLabel } == true &&
                        node.config.getOrNull(SemanticsActions.OnClick)?.action != null &&
                        !node.config.contains(SemanticsProperties.Disabled)
                }
                assertEquals(draft.size, inputs(root).size)
                inputs(root).zip(draft).forEach { (input, text) ->
                    assertTrue(input.config.getOrNull(SemanticsActions.SetText)!!.action!!.invoke(AnnotatedString(text)))
                }
                awaitUi(root, "Draft input and enabled save") {
                    inputs(root).map { it.config.getOrNull(SemanticsProperties.EditableText)?.text } == draft && saveEnabled()
                }
                assertEquals(draft, inputs(root).map { it.config.getOrNull(SemanticsProperties.EditableText)!!.text })
                clickText(root, saveLabel)
                awaitUi(root, "Saving form") {
                    activity.getString(R.string.project_progress_saving) in texts(root) &&
                        inputs(root).all { it.config.contains(SemanticsProperties.Disabled) }
                }
                assertEquals(listOf(draft[0] to draft[1]), submitted)
                assertTrue(inputs(root).all { it.config.contains(SemanticsProperties.Disabled) })
                saving.value = false
                error.value = ProjectProgressError.SAVE_FAILED
                awaitUi(root, "Failed save with retry enabled") {
                    activity.getString(R.string.project_progress_save_failed) in texts(root) && saveEnabled() &&
                        inputs(root).none { it.config.contains(SemanticsProperties.Disabled) }
                }
                assertTrue(activity.getString(R.string.project_progress_save_failed) in texts(root))
                assertEquals(draft, inputs(root).map { it.config.getOrNull(SemanticsProperties.EditableText)!!.text })
                assertTrue(inputs(root).none { it.config.contains(SemanticsProperties.Disabled) })
                clickText(root, saveLabel)
                assertEquals(listOf(draft[0] to draft[1], draft[0] to draft[1]), submitted)
            }
        } finally {
            recomposer.cancel()
            runner.cancel()
            runCurrent()
        }
    }

    @Test fun detailRendersLiteralMarkdownAndCopiesTheCompleteBodyWithoutMarkdownRenderer() {
        val entry = entry().copy(body = "# Literal Markdown\n\n**all** of the body\n```text\nnot a command\n```")
        val actions = mutableListOf<ProjectProgressEntryAction>()
        render({ ProjectProgressDetail(entry, true, actions::add) }) { root, activity ->
            assertTrue(entry.body in texts(root))
            clickText(root, activity.getString(R.string.project_progress_copy_body))
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(entry.body, clipboard.primaryClip!!.getItemAt(0).text.toString())
            clickText(root, activity.getString(R.string.project_progress_revoke))
            assertEquals(listOf(ProjectProgressEntryAction.REVOKE), actions)
            clickText(root, activity.getString(R.string.project_progress_archive))
            assertEquals(listOf(ProjectProgressEntryAction.REVOKE, ProjectProgressEntryAction.ARCHIVE), actions)
            assertTrue(activity.getString(R.string.project_progress_read_only_history) in texts(root))
        }
    }

    @Test fun alreadyRevokedArchivedEntryDoesNotOfferRepeatMutations() {
        render({ ProjectProgressDetail(entry().copy(revoked = true, archived = true), true, {}) }) { root, activity ->
            assertFalse(activity.getString(R.string.project_progress_revoke) in texts(root))
            assertFalse(activity.getString(R.string.project_progress_archive) in texts(root))
        }
    }

    @Test fun defaultPagePlacesTheIndependentHandoffBeforeActivityAndFoldsManagement() {
        val handoff = entry().copy(summary = "Latest handoff summary")
        val turn = entry().copy(id = "turn-id", kind = "turn", status = "completed", summary = "Project activity · completed · unverified")
        render({
            section(baseState().copy(projects = listOf(project), selectedProjectId = project.id, latestHandoff = handoff, entries = listOf(turn)))
        }) { root, activity ->
            val visible = texts(root)
            assertTrue(handoff.summary in visible)
            assertTrue(activity.getString(R.string.project_progress_activity_title) in visible)
            assertTrue(activity.getString(R.string.project_progress_auto_kind) in visible)
            assertTrue(activity.getString(R.string.project_progress_handoff_kind) in visible)
            assertTrue(textTop(root, handoff.summary) < textTop(root, activity.getString(R.string.project_progress_recent)))
            assertFalse(activity.getString(R.string.project_progress_bind) in visible)
            assertFalse(activity.getString(R.string.project_progress_capacity) in visible)
            assertFalse(root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Switch
            })
            assertTrue(activity.getString(R.string.project_progress_management) in visible)
        }
    }

    @Test fun foldedManagementKeepsCapacityWarningVisibleBeforeTheHistory() {
        val capacity = ProjectMemoryCapacity(2000, 2048, 12, 48, 1_000_000, 16_777_216)
        render({
            section(baseState().copy(projects = listOf(project), selectedProjectId = project.id, capacity = capacity))
        }) { root, activity ->
            val visible = texts(root)
            assertTrue(activity.getString(R.string.project_progress_capacity_warning) in visible)
            assertTrue(textTop(root, activity.getString(R.string.project_progress_capacity_warning)) <
                textTop(root, activity.getString(R.string.project_progress_recent)))
            assertFalse(activity.getString(R.string.project_progress_capacity) in visible)
            assertFalse(activity.getString(R.string.project_progress_bind) in visible)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test(timeout = 30_000)
    fun managementExpandsAndCollapsesWithoutChangingBindingsOrHidingCapacityWarnings() = runTest(timeout = 15.seconds) {
        val capacity = ProjectMemoryCapacity(2000, 2048, 12, 48, 1_000_000, 16_777_216)
        val state = baseState().copy(projects = listOf(project), selectedProjectId = project.id, capacity = capacity)
        val operations = mutableListOf<String>()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        var frame = 0L
        fun awaitUi(root: ViewRootForTest, condition: () -> Boolean) {
            repeat(120) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                repeat(4) { shadowOf(Looper.getMainLooper()).runOneTask() }
                root.measureAndLayoutForTest()
                if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
                runCurrent()
                Snapshot.sendApplyNotifications()
                root.measureAndLayoutForTest()
                if (condition()) return
            }
            fail("Management layout did not settle within 120 controlled frames: ${texts(root)}")
        }
        try {
            render({
                ProjectProgressSection(state, {}, {}, {}, {}, { operations += "bind" }, { operations += "unbind" },
                    { operations += "auto" }, { operations += "handoff" }, {}, {}, {})
            }, parentCompositionContext = recomposer) { root, activity ->
                val header = activity.getString(R.string.project_progress_management)
                val capacityLabel = activity.getString(R.string.project_progress_capacity)
                val warning = activity.getString(R.string.project_progress_capacity_warning)
                assertFalse(capacityLabel in texts(root))
                assertTrue(warning in texts(root))
                clickText(root, header)
                awaitUi(root) { capacityLabel in texts(root) }
                assertTrue(activity.getString(R.string.project_progress_bind) in texts(root))
                clickText(root, activity.getString(R.string.project_settings_capacity_details))
                awaitUi(root) { activity.getString(R.string.project_progress_capacity_value, 2000, 2048, 12, 1_000_000, 16_777_216) in texts(root) }
                assertTrue(activity.getString(R.string.project_progress_capacity_value, 2000, 2048, 12, 1_000_000, 16_777_216) in texts(root))
                assertTrue(warning in texts(root))
                assertTrue(operations.isEmpty())
                clickText(root, header)
                awaitUi(root) { capacityLabel !in texts(root) }
                assertTrue(warning in texts(root))
                assertFalse(activity.getString(R.string.project_progress_bind) in texts(root))
                assertFalse(root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).any {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Switch
                })
                assertTrue(operations.isEmpty())
            }
        } finally {
            recomposer.cancel()
            runner.cancel()
            runCurrent()
        }
    }

    @Test fun filterTabsExposeTheSelectedCategoryAndDispatchTheExactFilter() {
        var selected: ProjectProgressFilter? = null
        render({ ProjectProgressFilterBar(ProjectProgressFilter.AUTO_PROGRESS, true) { selected = it } }) { root, activity ->
            val tabs = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).filter {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Tab
            }
            assertEquals(3, tabs.size)
            assertEquals(listOf(false, true, false), tabs.map { it.config.getOrNull(SemanticsProperties.Selected) })
            clickText(root, activity.getString(R.string.project_progress_handoff_kind))
            assertEquals(ProjectProgressFilter.HANDOFF, selected)
        }
    }

    @Test fun archivedHandoffCardOpensTheCompleteEntryAndKeepsItsArchiveFlag() {
        var opened = false
        val handoff = entry().copy(archived = true)
        render({ ProjectProgressHandoffCard(handoff, true) { opened = true } }) { root, activity ->
            assertTrue(handoff.summary in texts(root))
            assertTrue(texts(root).any { it.contains(activity.getString(R.string.project_progress_archived)) })
            clickText(root, activity.getString(R.string.project_progress_open_handoff))
            assertTrue(opened)
        }
    }

    @Test fun onlyExactAutomaticTemplatesAreLocalizedIncludingRevokedAndRecoveredRecords() {
        val automatic = entry().copy(kind = "turn", status = "completed", summary = "Project activity · completed · unverified")
        val recovered = automatic.copy(status = "interrupted", summary = "Project activity · interrupted · needs_verification")
        val custom = automatic.copy(summary = "Build checked locally", userEdited = true)
        val nearMatch = automatic.copy(summary = automatic.summary + " with detail")
        val manual = automatic.copy(kind = "handoff")
        render({
            Text(projectProgressEntryTitle(automatic))
            Text(projectProgressEntryTitle(automatic.copy(revoked = true, userEdited = true)))
            Text(projectProgressEntryTitle(recovered))
            Text(projectProgressEntryTitle(custom))
            Text(projectProgressEntryTitle(nearMatch))
            Text(projectProgressEntryTitle(manual))
        }) { root, activity ->
            val visible = texts(root)
            assertEquals(3, visible.count { it == activity.getString(R.string.project_progress_activity_title) })
            assertTrue(custom.summary in visible)
            assertTrue(nearMatch.summary in visible)
            assertTrue(manual.summary in visible)
        }
    }

    @Test fun detailUsesTheSameLocalizedAutomaticTitleAndRetainsTheOriginalBody() {
        val automatic = entry().copy(kind = "turn", status = "failed", summary = "Project activity · failed · unverified")
        render({ ProjectProgressDetail(automatic, true, {}) }) { root, activity ->
            assertTrue(activity.getString(R.string.project_progress_activity_title) in texts(root))
            assertFalse(automatic.summary in texts(root))
            assertTrue(automatic.body in texts(root))
        }
    }

    @Composable
    private fun section(
        state: ProjectProgressUiState,
        onSelect: (ProjectMemoryProject) -> Unit = {},
        onAuto: (Boolean) -> Unit = {}
    ) {
        ProjectProgressSection(state, onSelect, {}, {}, {}, {}, {}, onAuto, {}, {}, {}, {})
    }

    private fun baseState() = ProjectProgressUiState(
        workspaceName = "Workspace", workspaceRoot = "/workspace", loading = false, projectsLoaded = true,
        sessionId = "session", associations = associations
    )

    private fun entry() = ProjectMemoryEntry(
        "entry-id", project.id, createdAt = 1700000000000, updatedAt = 1700000010000,
        kind = "handoff", summary = "Entry summary", body = "Complete body", status = "recorded"
    )

    private fun texts(root: ViewRootForTest): List<String> = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).flatMap {
        it.config.getOrNull(SemanticsProperties.Text)?.map { text -> text.text }.orEmpty()
    }

    private fun textTop(root: ViewRootForTest, text: String): Float {
        val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).single {
            it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
        }
        // LEGACY 零高文本的裁剪边界为 Rect.Zero，顺序须取真实布局坐标。
        return node.layoutInfo.coordinates.localToRoot(Offset.Zero).y
    }

    private fun inputs(root: ViewRootForTest) = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).filter {
        it.config.getOrNull(SemanticsActions.SetText)?.action != null
    }.sortedBy { it.boundsInRoot.top }

    private fun clickText(root: ViewRootForTest, text: String) {
        val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.config.getOrNull(SemanticsActions.OnClick)?.action != null
        }
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(node.config.getOrNull(SemanticsActions.OnClick)!!.action!!.invoke())
    }

    private fun render(
        content: @Composable () -> Unit,
        parentCompositionContext: CompositionContext? = null,
        inspect: (ViewRootForTest, ComponentActivity) -> Unit
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        try {
            view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            parentCompositionContext?.let(view::setParentCompositionContext)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    AIEditorTheme(darkTheme = false, content = content)
                }
            }
            activity.setContentView(view)
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }
            val root = view.getChildAt(0) as ViewRootForTest
            root.measureAndLayoutForTest()
            inspect(root, activity)
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
