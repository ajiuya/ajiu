package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.settings.presentation.MemoryUiState
import com.aicode.feature.agent.domain.memory.ProjectMemoryAssociations
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryProject
import com.aicode.feature.settings.presentation.MemoryViewModel
import com.aicode.feature.settings.presentation.ProjectProgressConfirmation
import com.aicode.feature.settings.presentation.ProjectProgressEntryAction
import com.aicode.feature.settings.presentation.ProjectProgressForm
import com.aicode.feature.settings.presentation.ProjectProgressFilter
import com.aicode.feature.settings.presentation.ProjectProgressUiState
import com.aicode.feature.settings.presentation.ProjectProgressViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MemorySchemeALayoutTest {
    @Test(timeout = 90_000)
    fun searchTrimsAndIgnoresCaseAcrossNameDescriptionAndPreviewWithoutOpeningAnyDocument(): Unit = render {
        val name = entry(MemoryScope.PROJECT, "NameNeedle")
        val description = entry(MemoryScope.PROJECT, "description", description = "SummaryNeedle")
        val preview = entry(MemoryScope.PROJECT, "preview", preview = "PreviewNeedle")
        val bodyOnly = entry(MemoryScope.PROJECT, "body-only")
        val global = entry(MemoryScope.GLOBAL, "NameNeedle")
        val route = RouteFixture(listOf(name, description, preview, bodyOnly, global))
        showRoute(route)
        settle()
        listOf("  nameneedle  " to name, " SUMMARYNEEDLE " to description, " previewNEEDLE\t" to preview)
            .forEach { (query, expected) ->
                editSearch(query)
                assertEquals(setOf(expected.id), rowIds())
                assertCount(1)
            }
        editSearch("Complete Markdown")
        assertTrue(rowIds().isEmpty())
        assertCount(0)
        assertTrue(hasText(label(R.string.memory_a_no_results)))
        verify(exactly = 0) { route.viewModel.openDetail(any()) }
        verify(exactly = 0) { route.viewModel.create(any(), any(), any(), any()) }
    }

    @Test(timeout = 90_000)
    fun selectedScopeNeverMixesSameNamedRowsAndTheCountUsesTheFilteredRange(): Unit = render {
        val global = entry(MemoryScope.GLOBAL, "same")
        val project = entry(MemoryScope.PROJECT, "same")
        val other = entry(MemoryScope.PROJECT, "other")
        showList(listOf(global, project, other))
        settle()
        assertEquals(setOf(project.id, other.id), rowIds())
        assertCount(2)
        clickTag("memory-scope-global")
        assertEquals(setOf(global.id), rowIds())
        assertCount(1)
        clickTag("memory-row-${global.id}")
        assertEquals(listOf(global), opened)
        clickTag("memory-scope-project")
        editSearch("same")
        assertEquals(setOf(project.id), rowIds())
        assertCount(1)
        clickTag("memory-row-${project.id}")
        assertEquals(listOf(global, project), opened)
        assertTrue(deletionRequests.isEmpty())
    }

    @Test(timeout = 90_000)
    fun anEmptyScopeAndANonmatchingSearchHaveDistinctFeedbackAndZeroCounts(): Unit = render {
        showList(listOf(entry(MemoryScope.GLOBAL, "global-only")))
        settle()
        assertCount(0)
        assertTrue(rowIds().isEmpty())
        assertTrue(hasText(label(R.string.memory_management_empty)))
        editSearch("missing")
        assertCount(0)
        assertTrue(hasText(label(R.string.memory_a_no_results)))
        assertFalse(hasText(label(R.string.memory_management_empty)))
        clickTag("memory-scope-global")
        editSearch("")
        assertCount(1)
        assertEquals(setOf("GLOBAL:global-only.md"), rowIds())
    }

    @Test(timeout = 90_000)
    fun loadingAndStorageFailureAreVisibleRatherThanPresentedAsSuccessfulEmptyResults(): Unit = render {
        val route = RouteFixture(emptyList())
        route.state.value = route.state.value.copy(loading = true)
        showRoute(route)
        settle()
        assertTrue(nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        assertFalse(hasText(label(R.string.memory_management_empty)))
        assertFalse(hasText(label(R.string.memory_a_range_end)))
        route.state.value = route.state.value.copy(loading = false, error = MemoryManagementError.IO_FAILED)
        settle()
        assertTrue(hasText(label(R.string.memory_management_io_failed)))
        assertFalse(nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        assertTrue(rowIds().isEmpty())
        verify(exactly = 0) { route.viewModel.openDetail(any()) }
    }

    @Test(timeout = 90_000)
    fun routeDefaultsToProjectWithAWorkspaceAndToUsableGlobalWithoutOne() {
        listOf(true, false).forEach { hasWorkspace ->
            render {
                val global = entry(MemoryScope.GLOBAL, "global")
                val project = entry(MemoryScope.PROJECT, "project")
                showRoute(RouteFixture(listOf(global, project), hasWorkspace))
                settle()
                val expected = if (hasWorkspace) "memory-scope-project" else "memory-scope-global"
                assertEquals(true, tag(expected).config[SemanticsProperties.Selected])
                assertEquals(setOf(if (hasWorkspace) project.id else global.id), rowIds())
                assertCount(1)
                if (!hasWorkspace) {
                    assertTrue(tag("memory-scope-project").config.contains(SemanticsProperties.Disabled))
                    assertFalse(tag("memory-scope-global").config.contains(SemanticsProperties.Disabled))
                    assertTrue(hasText(label(R.string.memory_management_no_workspace)))
                    clickTag("memory-row-${global.id}")
                    assertTrue(hasTag("memory-detail-body"))
                }
            }
        }
    }

    @Test(timeout = 90_000)
    fun asynchronousFirstWorkspaceResultChoosesProjectWithoutOverwritingAnExplicitChoice(): Unit = render {
        val global = entry(MemoryScope.GLOBAL, "global")
        val project = entry(MemoryScope.PROJECT, "project")
        val route = RouteFixture(emptyList(), hasWorkspace = false)
        route.state.value = route.state.value.copy(loading = true)
        showRoute(route)
        settle()
        route.state.value = MemoryUiState(entries = listOf(global, project), hasWorkspace = true,
            projectName = PROJECT, loading = false)
        settle()
        assertEquals(true, tag("memory-scope-project").config[SemanticsProperties.Selected])
        clickTag("memory-scope-global")
        route.state.value = route.state.value.copy(projectName = "Renamed project")
        settle()
        assertEquals(true, tag("memory-scope-global").config[SemanticsProperties.Selected])
        assertEquals(setOf(global.id), rowIds())
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w400dp-h640dp")
    fun listDetailBackRestoresTheSaveableSearchScopeAndActualLazyScrollPosition(): Unit = render(height = 640) {
        val entries = MemoryScope.entries.flatMap { scope -> (0 until 36).map { entry(scope, "Shared-$it") } }
        val route = RouteFixture(entries)
        showRoute(route)
        settle()
        clickTag("memory-scope-global")
        editSearch("Shared")
        scrollList(600f)
        val before = listScroll()
        assertTrue(before > 0f)
        val row = nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("memory-row-GLOBAL:") == true &&
                node.boundsInRoot.height > 0f && node.boundsInRoot.top >= 0f
        }
        val expectedId = row.config[SemanticsProperties.TestTag].removePrefix("memory-row-")
        clickNode(row)
        assertTrue(hasTag("memory-detail-body"))
        assertEquals(expectedId, route.state.value.document?.entry?.id)
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertNull(route.state.value.selectedEntry)
        assertEquals(before, listScroll(), 1f)
        scrollList(-10_000f)
        assertEquals(true, tag("memory-scope-global").config[SemanticsProperties.Selected])
        assertEquals("Shared", searchValue())
        verify(exactly = 1) { route.viewModel.closeDetail() }
    }

    @Test(timeout = 90_000)
    fun hubTabsRenderBothPagesAndRestoreMemoryStateWithoutReopeningEitherViewModel(): Unit = render {
        val entries = listOf(entry(MemoryScope.PROJECT, "project"), entry(MemoryScope.GLOBAL, "global"))
        val route = RouteFixture(entries)
        showRoute(route)
        settle()
        assertTrue(hasTag("memory-tabs"))
        assertEquals(Role.Tab, tag("memory-tab-long-term").config[SemanticsProperties.Role])
        assertEquals(Role.Tab, tag("memory-tab-project-progress").config[SemanticsProperties.Role])
        assertEquals(true, tag("memory-tab-long-term").config[SemanticsProperties.Selected])
        assertFalse(hasTag("memory-project-progress"))
        assertTrue(hasText(label(R.string.memory_long_term_title)))
        assertTrue(hasText(label(R.string.memory_long_term_hint)))
        val memoryTabs = tag("memory-tabs")
        assertTrue(memoryTabs.size.height <= 56f)
        assertTrue(memoryTabs.bounds().bottom <= tag("memory-search").bounds().top)
        assertVisible(tag("memory-list"))
        clickTag("memory-scope-global")
        editSearch("global")
        clickTag("memory-tab-project-progress")
        assertEquals(true, tag("memory-tab-project-progress").config[SemanticsProperties.Selected])
        assertTrue(hasText(label(R.string.project_progress_title)))
        assertTrue(hasText(label(R.string.project_progress_current_workspace)))
        val progressTabs = tag("memory-tabs")
        assertTrue(progressTabs.size.height <= 56f)
        val openCurrent = tag("project-progress-open-current")
        assertTrue(progressTabs.bounds().bottom <= openCurrent.bounds().top)
        assertVisible(openCurrent)
        assertFalse(hasTag("project-progress-register"))
        assertFalse(hasTag("memory-list"))
        clickTag("project-progress-open-current")
        assertEquals(route.project.id, route.projectState.value.selectedProjectId)
        verify(exactly = 1) { route.viewModel.open() }
        verify(exactly = 1) { route.projectViewModel.open(null) }
        verify(exactly = 0) { route.viewModel.close() }
        clickTag("memory-tab-long-term")
        assertEquals(true, tag("memory-tab-long-term").config[SemanticsProperties.Selected])
        assertEquals(true, tag("memory-scope-global").config[SemanticsProperties.Selected])
        assertEquals("global", searchValue())
        assertEquals(setOf(entries.last().id), rowIds())
        clickTag("memory-tab-project-progress")
        verify(exactly = 1) { route.viewModel.open() }
        verify(exactly = 1) { route.projectViewModel.open(null) }
        verify(exactly = 0) { route.projectViewModel.close() }
        clickTag("memory-tab-long-term")
        clickDescription(R.string.common_back)
        verify(exactly = 1) { route.viewModel.close() }
        verify(exactly = 1) { route.projectViewModel.close() }
        assertEquals(1, route.exitCount)
    }

    @Test(timeout = 90_000)
    fun projectStateAndCallbacksSurviveTabSwitchesWhileRecordDetailsHideTabs(): Unit = render {
        val route = RouteFixture(emptyList())
        showRoute(route)
        settle()
        clickTag("memory-tab-project-progress")
        clickTag("project-progress-open-current")
        assertEquals(route.project.id, route.projectState.value.selectedProjectId)
        assertEquals(true, tag("memory-tab-project-progress").config[SemanticsProperties.Selected])
        clickTabLabel(R.string.project_progress_handoff_kind)
        assertEquals(ProjectProgressFilter.HANDOFF, route.projectState.value.filter)
        assertEquals(listOf(route.recentEntry), route.projectState.value.entries)
        verify(exactly = 1) { route.projectViewModel.selectProject(route.project) }
        verify(exactly = 1) { route.projectViewModel.setFilter(ProjectProgressFilter.HANDOFF) }
        clickLabel(R.string.project_progress_view_archive)
        assertTrue(route.projectState.value.showingArchive)
        assertEquals(listOf(route.archiveEntry), route.projectState.value.entries)
        verify(exactly = 1) { route.projectViewModel.showArchive(true) }
        assertTrue(hasTag("memory-tab-project-progress"))
        clickTag("memory-tab-long-term")
        assertEquals(true, tag("memory-tab-long-term").config[SemanticsProperties.Selected])
        clickTag("memory-tab-project-progress")
        assertEquals(route.project.id, route.projectState.value.selectedProjectId)
        assertEquals(ProjectProgressFilter.HANDOFF, route.projectState.value.filter)
        assertTrue(route.projectState.value.showingArchive)
        assertTrue(hasText(route.archiveEntry.summary))
        clickText(route.archiveEntry.summary)
        assertEquals(route.archiveEntry.id, route.projectState.value.selectedEntryId)
        assertTrue(hasText(route.archiveEntry.body))
        val detailBody = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == route.archiveEntry.body } == true }
        assertVisible(detailBody)
        assertFalse(hasTag("memory-tabs"))
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertNull(route.projectState.value.selectedEntryId)
        assertEquals(true, tag("memory-tab-project-progress").config[SemanticsProperties.Selected])
        assertTrue(hasText(route.archiveEntry.summary))
        clickLabel(R.string.project_progress_all_projects)
        assertNull(route.projectState.value.selectedProjectId)
        assertTrue(hasText(label(R.string.project_progress_current_workspace)))
        verify(exactly = 1) { route.projectViewModel.openEntry(route.archiveEntry) }
        verify(exactly = 1) { route.projectViewModel.closeEntry() }
        verify(exactly = 1) { route.projectViewModel.closeProject() }
    }

    @Test(timeout = 90_000)
    fun memoryDetailsHideTabsAndBackReturnsToTheSameSelectedTab(): Unit = render {
        val entry = entry(MemoryScope.GLOBAL, "detail")
        val route = RouteFixture(listOf(entry))
        showRoute(route)
        settle()
        clickTag("memory-scope-global")
        clickTag("memory-row-${entry.id}")
        assertTrue(hasTag("memory-detail-body"))
        assertFalse(hasTag("memory-tabs"))
        activity.onBackPressedDispatcher.onBackPressed()
        settle()
        assertNull(route.state.value.selectedEntry)
        assertEquals(true, tag("memory-tab-long-term").config[SemanticsProperties.Selected])
        assertEquals(true, tag("memory-scope-global").config[SemanticsProperties.Selected])
    }

    @Test(timeout = 90_000)
    fun busyFormsAndDeleteConfirmationsDisableHubTabs(): Unit = render {
        val entry = entry(MemoryScope.GLOBAL, "busy")
        val route = RouteFixture(listOf(entry))
        showRoute(route)
        settle()
        fun assertTabsDisabled(expected: Boolean) {
            listOf("memory-tab-long-term", "memory-tab-project-progress").forEach { tab ->
                assertEquals(expected, tag(tab).config.contains(SemanticsProperties.Disabled))
            }
        }
        route.state.value = route.state.value.copy(saving = true)
        settle()
        assertTabsDisabled(true)
        route.state.value = route.state.value.copy(saving = false, pendingDelete = entry)
        settle()
        assertTabsDisabled(true)
        route.state.value = route.state.value.copy(pendingDelete = null)
        settle()
        assertTabsDisabled(false)
        clickTag("memory-tab-project-progress")
        route.projectState.value = route.projectState.value.copy(form = ProjectProgressForm.HANDOFF)
        settle()
        assertTabsDisabled(true)
        route.projectState.value = route.projectState.value.copy(form = null, busy = true)
        settle()
        assertTabsDisabled(true)
        route.projectState.value = route.projectState.value.copy(busy = false,
            confirmation = ProjectProgressConfirmation(route.recentEntry, ProjectProgressEntryAction.ARCHIVE))
        settle()
        assertTabsDisabled(true)
        route.projectState.value = route.projectState.value.copy(confirmation = null)
        settle()
        assertTabsDisabled(false)
        verify(exactly = 1) { route.projectViewModel.open(null) }
        verify(exactly = 0) { route.viewModel.close() }
    }

    @Test(timeout = 90_000)
    fun targetChangedStatesKeepProtectedPagesReadOnly(): Unit = render {
        val route = RouteFixture(listOf(entry(MemoryScope.GLOBAL, "protected")))
        showRoute(route)
        settle()
        route.state.value = route.state.value.copy(targetChanged = true, loading = false)
        settle()
        assertTrue(hasText(label(R.string.memory_management_target_changed)))
        assertFalse(hasTag("memory-list"))
        val add = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label(R.string.memory_management_add)) == true }
        var addButton: SemanticsNode? = add
        while (addButton != null && !addButton.config.contains(SemanticsProperties.Disabled)) addButton = addButton.parent
        assertTrue(requireNotNull(addButton).config.contains(SemanticsProperties.Disabled))
        clickTag("memory-tab-project-progress")
        route.projectState.value = route.projectState.value.copy(targetChanged = true, loading = false)
        settle()
        assertTrue(hasText(label(R.string.project_progress_target_changed)))
        assertTrue(hasText(label(R.string.project_progress_reload)))
        assertFalse(hasText(label(R.string.project_progress_register)))
        assertFalse(hasText(label(R.string.project_progress_write_handoff)))
        assertEquals(true, tag("memory-tab-project-progress").config[SemanticsProperties.Selected])
        verify(exactly = 0) { route.projectViewModel.startRegister() }
        verify(exactly = 0) { route.projectViewModel.startHandoff() }
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w400dp-h640dp")
    fun switchingTabsRestoresTheActualMemoryLazyScrollWithoutReopeningMemory(): Unit = render(height = 640) {
        val entries = MemoryScope.entries.flatMap { scope -> (0 until 36).map { entry(scope, "Shared-$it") } }
        val route = RouteFixture(entries)
        showRoute(route)
        settle()
        clickTag("memory-scope-global")
        editSearch("Shared")
        scrollList(10_000f)
        val before = listScroll()
        assertTrue(before > 0f)
        val visibleRows = nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("memory-row-GLOBAL:") == true &&
                node.boundsInRoot.height > 0f
        }.associate { it.config[SemanticsProperties.TestTag] to it.positionInRoot.y }
        assertTrue(visibleRows.isNotEmpty())
        clickTag("memory-tab-project-progress")
        assertTrue(hasText(label(R.string.project_progress_title)))
        verify(exactly = 1) { route.viewModel.open() }
        verify(exactly = 1) { route.projectViewModel.open(null) }
        clickTag("memory-tab-long-term")
        assertEquals(before, listScroll(), 1f)
        visibleRows.forEach { (value, top) -> assertEquals(top, tag(value).positionInRoot.y, 1f) }
        scrollList(-10_000f)
        assertEquals(true, tag("memory-scope-global").config[SemanticsProperties.Selected])
        assertEquals("Shared", searchValue())
        assertCount(36)
        verify(exactly = 1) { route.viewModel.open() }
        verify(exactly = 0) { route.viewModel.close() }
    }

    @Test(timeout = 90_000)
    fun onlyASuccessfulCreateEventChangesScopeAndClearsTheOldFilter() {
        MemoryScope.entries.forEach { createdScope ->
            render {
                val existing = entry(if (createdScope == MemoryScope.GLOBAL) MemoryScope.PROJECT else MemoryScope.GLOBAL, "existing")
                val created = entry(createdScope, "created")
                val route = RouteFixture(listOf(existing))
                showRoute(route)
                settle()
                clickTag(if (existing.scope == MemoryScope.GLOBAL) "memory-scope-global" else "memory-scope-project")
                editSearch("filter-that-hides-created")
                route.state.value = route.state.value.copy(error = MemoryManagementError.NAME_CONFLICT)
                settle()
                assertEquals("filter-that-hides-created", searchValue())
                assertEquals(true, tag(if (existing.scope == MemoryScope.GLOBAL) "memory-scope-global" else "memory-scope-project")
                    .config[SemanticsProperties.Selected])
                verify(exactly = 0) { route.viewModel.consumeCreatedScope() }
                route.state.value = route.state.value.copy(entries = listOf(existing, created), error = null, createdScope = createdScope)
                settle()
                assertEquals("", searchValue())
                assertEquals(true, tag(if (createdScope == MemoryScope.GLOBAL) "memory-scope-global" else "memory-scope-project")
                    .config[SemanticsProperties.Selected])
                assertEquals(setOf(created.id), rowIds())
                assertCount(1)
                assertNull(route.state.value.createdScope)
                verify(exactly = 1) { route.viewModel.consumeCreatedScope() }
                verify(exactly = 0) { route.viewModel.create(any(), any(), any(), any()) }
            }
        }
    }

    @Test(timeout = 90_000)
    fun newSheetReceivesTheCurrentScopeAndSubmitsThatScopeOnly() {
        MemoryScope.entries.forEach { scope ->
            render {
                val route = RouteFixture(emptyList())
                showRoute(route)
                settle()
                clickTag(if (scope == MemoryScope.GLOBAL) "memory-scope-global" else "memory-scope-project")
                clickDescription(R.string.memory_management_add)
                val radios = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
                assertEquals(2, radios.size)
                val selected = radios.single { it.config.getOrNull(SemanticsProperties.Selected) == true }
                assertTrue(requireNotNull(selected.parent).containsText(label(memoryScopeLabelRes(scope))))
                val draft = listOf("new-draft", "New description", "# Complete Markdown\n\n**Body**")
                val sheet = requireNotNull(findRoot(requireNotNull(ShadowDialog.getLatestDialog()).window!!.decorView))
                val inputs = sheet.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                    .filter { it.config.getOrNull(SemanticsActions.SetText)?.action != null }.sortedBy { it.boundsInRoot.top }
                assertEquals(3, inputs.size)
                inputs.zip(draft).forEach { (input, value) ->
                    assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
                }
                settle()
                clickLabel(R.string.memory_management_create)
                verify(exactly = 1) { route.viewModel.create(draft[0], draft[1], draft[2], scope) }
                verify(exactly = 1) { route.viewModel.create(any(), any(), any(), any()) }
            }
        }
    }

    @Test(timeout = 90_000)
    fun sameNamedGlobalAndProjectDetailsCopyTheirCompleteMarkdownRatherThanThePreview(): Unit = render {
        MemoryScope.entries.forEach { scope ->
            val entry = entry(scope, "same-file")
            val document = MemoryDocument(entry, "# ${scope.name}\n\n**Complete Markdown**\n\n```kotlin\nval keep = true\n```\n")
            screen = { MemoryDetailSection(document, projectName = PROJECT) }
            settle()
            assertTrue(tag("memory-detail-info").containsText(entry.fileName))
            val expected = if (scope == MemoryScope.GLOBAL) label(R.string.memory_management_global)
                else label(R.string.memory_management_project_named, PROJECT)
            assertTrue(tag("memory-detail-scope").containsText(expected))
            clickTag("memory-copy-body")
            assertEquals(document.content, clipboardText())
            assertNotEquals(entry.preview, clipboardText())
        }
    }

    @Test(timeout = 90_000)
    fun moreStartsClosedOffersOnlyDeleteAndRequestsTheExactEntryWithoutDeletingAnythingElse(): Unit = render {
        val document = MemoryDocument(entry(MemoryScope.PROJECT, "same-file"), "# Complete Markdown")
        screen = { MemoryDetailSection(document, projectName = PROJECT, onDelete = { deletionRequests += it }) }
        settle()
        assertFalse(hasTag("memory-detail-delete"))
        assertFalse(hasText(label(R.string.memory_a_delete)))
        assertTrue(deletionRequests.isEmpty())
        clickTag("memory-detail-more")
        assertTrue(hasTag("memory-detail-delete"))
        val delete = tag("memory-detail-delete")
        val menuRoot = roots().single { root ->
            root.semanticsOwner.getAllSemanticsNodes(false).any { it.id == delete.id }
        }
        assertEquals(1, menuRoot.semanticsOwner.getAllSemanticsNodes(false).count {
            it.config.getOrNull(SemanticsActions.OnClick)?.action != null
        })
        clickTag("memory-detail-delete")
        assertEquals(listOf(document.entry), deletionRequests)
        assertFalse(hasTag("memory-detail-delete"))
        assertTrue(hasTag("memory-detail-body"))
    }

    @Test(timeout = 90_000)
    fun busyDisablesBothFooterActionsAndClosesAnAlreadyOpenMoreMenu(): Unit = render {
        val busy = mutableStateOf(false)
        val document = MemoryDocument(entry(MemoryScope.PROJECT, "busy"), "# Complete Markdown")
        screen = { MemoryDetailSection(document, busy = busy.value, onDelete = { deletionRequests += it }) }
        settle()
        clickTag("memory-detail-more")
        assertTrue(hasTag("memory-detail-delete"))
        busy.value = true
        settle()
        assertTrue(tag("memory-copy-body").config.contains(SemanticsProperties.Disabled))
        assertTrue(tag("memory-detail-more").config.contains(SemanticsProperties.Disabled))
        assertFalse(hasTag("memory-detail-delete"))
        val clipboardBefore = clipboardText()
        listOf("memory-copy-body", "memory-detail-more").forEach { value ->
            requireNotNull(tag(value).config[SemanticsActions.OnClick].action).invoke()
        }
        settle()
        assertEquals(clipboardBefore, clipboardText())
        assertFalse(hasTag("memory-detail-delete"))
        assertTrue(deletionRequests.isEmpty())
        busy.value = false
        settle()
        assertFalse(tag("memory-copy-body").config.contains(SemanticsProperties.Disabled))
        assertFalse(tag("memory-detail-more").config.contains(SemanticsProperties.Disabled))
        assertFalse(hasTag("memory-detail-delete"))
        clickTag("memory-copy-body")
        assertEquals(document.content, clipboardText())
    }

    @Test(timeout = 90_000)
    fun changingTheDetailIdentityDoesNotInheritAnOpenMenuOrThePreviousCopyBody(): Unit = render {
        val first = MemoryDocument(entry(MemoryScope.GLOBAL, "same-file"), "# Global Markdown")
        val second = MemoryDocument(entry(MemoryScope.PROJECT, "same-file"), "# Project Markdown")
        val document = mutableStateOf(first)
        screen = { MemoryDetailSection(document.value, projectName = PROJECT, onDelete = { deletionRequests += it }) }
        settle()
        clickTag("memory-detail-more")
        assertTrue(hasTag("memory-detail-delete"))
        document.value = second
        settle()
        assertFalse(hasTag("memory-detail-delete"))
        clickTag("memory-copy-body")
        assertEquals(second.content, clipboardText())
        clickTag("memory-detail-more")
        clickTag("memory-detail-delete")
        assertEquals(listOf(second.entry), deletionRequests)
    }

    @Test(timeout = 90_000)
    fun detailDeleteUsesTheOriginalConfirmationAndCancelKeepsTheDocument(): Unit = render {
        val entry = entry(MemoryScope.PROJECT, "confirm")
        val route = RouteFixture(listOf(entry))
        showRoute(route)
        settle()
        clickTag("memory-row-${entry.id}")
        clickTag("memory-detail-more")
        clickTag("memory-detail-delete")
        assertEquals(entry, route.state.value.pendingDelete)
        assertTrue(hasText(label(R.string.memory_management_delete_title)))
        assertTrue(hasText(label(R.string.memory_management_delete_message, entry.name, label(memoryScopeLabelRes(entry.scope)))))
        verify(exactly = 1) { route.viewModel.requestDelete(entry) }
        verify(exactly = 0) { route.viewModel.confirmDelete() }
        clickLabel(R.string.common_cancel)
        assertNull(route.state.value.pendingDelete)
        assertEquals(entry, route.state.value.document?.entry)
        assertTrue(hasTag("memory-detail-body"))
        verify(exactly = 1) { route.viewModel.dismissDelete() }
        verify(exactly = 0) { route.viewModel.confirmDelete() }
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontDarkListKeepsRoundedEqualTabsAndTheReferenceHierarchy(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        val first = entry(MemoryScope.PROJECT, "Long memory name ".repeat(30),
            description = "Long description ".repeat(30), preview = "Long preview ".repeat(30))
        val second = entry(MemoryScope.PROJECT, "second")
        showList(listOf(first, second))
        settle()
        assertSegments()
        val search = tag("memory-search")
        assertTrue(search.containsText(label(R.string.memory_a_search)))
        val summary = tag("memory-range-summary")
        val count = tag("memory-count")
        val row = tag("memory-row-${first.id}")
        assertTrue(tag("memory-scope-segments").bounds().bottom <= search.bounds().top)
        assertTrue(search.bounds().bottom <= summary.bounds().top)
        assertTrue(summary.bounds().bottom <= row.bounds().top)
        assertCount(2)
        val scope = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
            text.text == label(R.string.memory_management_project_named, PROJECT)
        } == true }
        assertSameBand(scope, count)
        assertTrue(scope.bounds().right <= count.bounds().left)
        listOf("memory-scope-global", "memory-scope-project", "memory-search", "memory-range-summary", "memory-count")
            .forEach { assertVisible(tag(it)) }
        val icon = tag("memory-icon-${first.id}")
        val name = tag("memory-name-${first.id}")
        val description = tag("memory-description-${first.id}")
        val preview = tag("memory-preview-${first.id}")
        assertTrue(icon.bounds().right < name.bounds().left)
        assertEquals(name.bounds().left, description.bounds().left, 0.5f)
        assertEquals(name.bounds().left, preview.bounds().left, 0.5f)
        val cardLayouts = layoutChildren(requireNotNull(row.layoutInfo.parentInfo))
        val divider = cardLayouts.single { it.height in 1..2 && it.width > 0 }
        val dividerBounds = divider.coordinates.boundsInRoot()
        assertEquals(name.bounds().left, dividerBounds.left, 0.5f)
        assertTrue(dividerBounds.right < row.bounds().right)
        assertTrue(name.bounds().bottom <= description.bounds().top)
        assertTrue(description.bounds().bottom <= preview.bounds().top)
        assertTextInputContract(name, first.name, 1)
        assertTextInputContract(description, first.description, 1)
        assertTextInputContract(preview, first.preview, 2)
        assertTrue(requireNotNull(textLayout(name).layoutInput.style.fontWeight).weight >= 600)
        assertTrue(deletionRequests.isEmpty())
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontDarkDetailKeepsMetadataInThreeRowsAndFooterFixedWhileTheBodyScrolls(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        val document = MemoryDocument(entry(MemoryScope.PROJECT, "Long file name ".repeat(20)),
            (0 until 50).joinToString("\n\n") { "## Section $it\n\n**Complete Markdown** paragraph $it" })
        screen = { MemoryDetailSection(document, projectName = "Long project ".repeat(20)) }
        settle()
        fun recordMarkdownLayout(phase: String, frames: Int) {
            val current = nodes()
            val body = tag("memory-detail-body")
            val bodyTexts = descendants(body).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            println("MEMORY_DETAIL_MARKDOWN_PHASE phase=$phase frames=$frames lifecycle=${activity.lifecycle.currentState} " +
                "bodyTextNodes=${bodyTexts.size} bodyCharacters=${bodyTexts.sumOf { it.length }} " +
                "progressNodes=${current.count { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }}")
            println("MEMORY_DETAIL_MARKDOWN_SOURCE ${document.content.replace("\n", "\\n")}")
            println("MEMORY_DETAIL_MARKDOWN_BODY ${bodyTexts.joinToString("\\n")}")
            current.forEach { node ->
                val range = node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                println("MEMORY_DETAIL_NODE id=${node.id} tag=${node.config.getOrNull(SemanticsProperties.TestTag)} " +
                    "bounds=${node.bounds()} vertical=${range?.value()}/${range?.maxValue()} " +
                    "text=${node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\\n") { it.text }}")
            }
        }
        recordMarkdownLayout("before-await", 0)
        val deadline = System.nanoTime() + 60_000_000_000L
        var ready = false
        var frames = 0
        while (frames < 3_600 && System.nanoTime() < deadline) {
            val bodyTexts = descendants(tag("memory-detail-body"))
                .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            val completeMarkdown = (0 until 50).all { index ->
                bodyTexts.any { it.trim() == "Section $index" } &&
                    bodyTexts.any { it.contains("Complete Markdown") && it.trimEnd().endsWith("paragraph $index") }
            }
            val scrollable = nodes().any {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true
            }
            if (completeMarkdown && scrollable) {
                ready = true
                break
            }
            pump(1)
            frames++
        }
        recordMarkdownLayout(if (ready) "body-ready" else "body-timeout", frames)
        assertTrue("Complete Markdown and positive scroll range were not ready within 60s/3600 controlled frames", ready)
        settle()
        val info = tag("memory-detail-info")
        val scope = tag("memory-detail-scope")
        val file = tag("memory-detail-file")
        val updated = tag("memory-detail-updated")
        val scopeLabel = textNode(R.string.memory_management_scope)
        val fileLabel = textNode(R.string.memory_management_file)
        val updatedLabel = textNode(R.string.memory_management_updated)
        assertSameBand(scopeLabel, scope)
        assertSameBand(fileLabel, file)
        assertSameBand(updatedLabel, updated)
        assertTrue(scopeLabel.bounds().right <= scope.bounds().left)
        assertTrue(fileLabel.bounds().right <= file.bounds().left)
        assertTrue(updatedLabel.bounds().right <= updated.bounds().left)
        assertTrue(updated.containsText(label(R.string.memory_management_time_unknown)))
        assertTrue(scope.bounds().bottom <= file.bounds().top)
        assertTrue(file.bounds().bottom <= updated.bounds().top)
        assertTrue(info.bounds().bottom <= tag("memory-detail-description").bounds().top)
        assertTrue(tag("memory-detail-description").bounds().bottom <= tag("memory-detail-body").bounds().top)
        val copy = tag("memory-copy-body")
        val more = tag("memory-detail-more")
        val actions = tag("memory-detail-actions")
        listOf(copy, more, actions).forEach(::assertVisible)
        assertSameBand(copy, more)
        assertTrue(copy.bounds().right < more.bounds().left)
        assertTrue(copy.size.width > more.size.width)
        assertTrue(more.size.width < actions.size.width * 0.5f)
        val footerBefore = actions.bounds()
        val copyBefore = copy.bounds()
        val moreBefore = more.bounds()
        val scroll = nodes().single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true }
        val initialScroll = scroll.config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 500f))
        settle()
        val afterScroll = nodes().single { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        assertTrue(afterScroll.config[SemanticsProperties.VerticalScrollAxisRange].value() > initialScroll)
        assertEquals(footerBefore, tag("memory-detail-actions").bounds())
        assertEquals(copyBefore, tag("memory-copy-body").bounds())
        assertEquals(moreBefore, tag("memory-detail-more").bounds())
        listOf("memory-copy-body", "memory-detail-more", "memory-detail-actions").forEach { assertVisible(tag(it)) }
        clickTag("memory-copy-body")
        assertEquals(document.content, clipboardText())
    }

    @Test(timeout = 90_000)
    fun hubTabsStayDistinctAt320dpLargeFontAndWithinBoundsOnWideScreens() {
        render(width = 320, height = 640, fontScale = 1.5f) {
            val route = RouteFixture(emptyList())
            showRoute(route)
            settle()
            val longTerm = tag("memory-tab-long-term")
            val progress = tag("memory-tab-project-progress")
            assertEquals(Role.Tab, longTerm.config[SemanticsProperties.Role])
            assertEquals(Role.Tab, progress.config[SemanticsProperties.Role])
            assertTrue(longTerm.bounds().right <= progress.bounds().left)
            listOf(longTerm, progress, tag("memory-tabs")).forEach(::assertVisible)
            assertTrue(tag("memory-tabs").size.height <= 56f)
            val title = descendants(longTerm).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label(R.string.memory_long_term_title) } == true
            }
            val progressTitle = descendants(progress).single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label(R.string.project_progress_title) } == true
            }
            listOf(title to longTerm, progressTitle to progress).forEach { (text, tab) ->
                assertTrue(text.bounds().left >= tab.bounds().left)
                assertTrue(text.bounds().right <= tab.bounds().right)
                assertTrue(text.bounds().top >= tab.bounds().top)
                assertTrue(text.bounds().bottom <= tab.bounds().bottom)
            }
        }
        render(width = 840, height = 1000) {
            val route = RouteFixture(emptyList())
            showRoute(route)
            settle()
            val tabs = tag("memory-tabs")
            val longTerm = tag("memory-tab-long-term")
            val progress = tag("memory-tab-project-progress")
            assertTrue(tabs.size.width > 320f)
            assertTrue(tabs.bounds().left >= 0f && tabs.bounds().right <= view.width)
            assertTrue(longTerm.bounds().right <= progress.bounds().left)
            assertVisible(tabs)
            assertVisible(longTerm)
            assertVisible(progress)
        }
    }

    private class RouteFixture(entries: List<MemoryEntry>, hasWorkspace: Boolean = true) {
        val state = MutableStateFlow(MemoryUiState(entries = entries, hasWorkspace = hasWorkspace,
            projectName = if (hasWorkspace) PROJECT else null, loading = false))
        val viewModel = mockk<MemoryViewModel>()
        val project = ProjectMemoryProject("project-id", "Project", identity = ProjectMemoryIdentity("local_proot", projectRoot = "/workspace"))
        val recentEntry = ProjectMemoryEntry("recent-id", project.id, createdAt = 1_700_000_000_000L, kind = "handoff",
            summary = "Recent handoff", body = "Recent body", status = "recorded")
        val archiveEntry = recentEntry.copy(id = "archive-id", summary = "Archived handoff", archived = true)
        val projectState = MutableStateFlow(ProjectProgressUiState(
            workspaceName = PROJECT, workspaceRoot = "/workspace", loading = false, projectsLoaded = true, projects = listOf(project),
            associations = ProjectMemoryAssociations(false, null, true, null)
        ))
        val projectViewModel = mockk<ProjectProgressViewModel>()
        var exitCount = 0
        init {
            every { viewModel.state } returns state
            every { viewModel.open() } returns Unit
            every { viewModel.close() } returns Unit
            every { viewModel.refresh() } returns Unit
            every { viewModel.closeDetail() } answers {
                state.value = state.value.copy(selectedEntry = null, document = null, detailLoading = false, error = null)
            }
            every { viewModel.openDetail(any()) } answers {
                val selected = firstArg<MemoryEntry>()
                state.value = state.value.copy(selectedEntry = selected,
                    document = MemoryDocument(selected, "# ${selected.scope.name}\n\n**Complete Markdown**"), detailLoading = false)
            }
            every { viewModel.startCreate() } answers { state.value = state.value.copy(creating = true, error = null) }
            every { viewModel.dismissCreate() } answers { state.value = state.value.copy(creating = false) }
            every { viewModel.create(any(), any(), any(), any()) } answers { state.value = state.value.copy(saving = true) }
            every { viewModel.consumeCreatedScope() } answers { state.value = state.value.copy(createdScope = null) }
            every { viewModel.requestDelete(any()) } answers { state.value = state.value.copy(pendingDelete = firstArg()) }
            every { viewModel.dismissDelete() } answers { state.value = state.value.copy(pendingDelete = null) }
            every { viewModel.confirmDelete() } returns Unit
            every { projectViewModel.state } returns projectState
            every { projectViewModel.open(any()) } returns Unit
            every { projectViewModel.close() } returns Unit
            every { projectViewModel.checkEnvironment() } returns Unit
            every { projectViewModel.refresh() } returns Unit
            every { projectViewModel.selectProject(any()) } answers {
                projectState.value = projectState.value.copy(selectedProjectId = firstArg<ProjectMemoryProject>().id,
                    entries = listOf(recentEntry))
            }
            every { projectViewModel.setFilter(any()) } answers {
                val filter = firstArg<ProjectProgressFilter>()
                projectState.value = projectState.value.copy(filter = filter,
                    entries = if (filter == ProjectProgressFilter.HANDOFF) listOf(recentEntry) else emptyList())
            }
            every { projectViewModel.showArchive(any()) } answers {
                val archived = firstArg<Boolean>()
                projectState.value = projectState.value.copy(showingArchive = archived,
                    entries = if (archived) listOf(archiveEntry) else listOf(recentEntry))
            }
            every { projectViewModel.openEntry(any()) } answers {
                val selected = firstArg<ProjectMemoryEntry>()
                projectState.value = projectState.value.copy(selectedEntryId = selected.id, detail = selected, detailLoading = false)
            }
            every { projectViewModel.closeEntry() } answers {
                projectState.value = projectState.value.copy(selectedEntryId = null, detail = null, detailLoading = false)
            }
            every { projectViewModel.closeProject() } answers {
                projectState.value = projectState.value.copy(selectedProjectId = null, selectedEntryId = null, detail = null)
            }
            every { projectViewModel.startRegister() } answers {
                projectState.value = projectState.value.copy(form = ProjectProgressForm.REGISTER)
            }
            every { projectViewModel.startHandoff() } answers {
                projectState.value = projectState.value.copy(form = ProjectProgressForm.HANDOFF)
            }
            every { projectViewModel.dismissForm() } answers { projectState.value = projectState.value.copy(form = null) }
            every { projectViewModel.confirmEntryAction() } returns Unit
            every { projectViewModel.dismissConfirmation() } answers { projectState.value = projectState.value.copy(confirmation = null) }
        }
    }

    private class Ui(val activity: ComponentActivity, val view: ComposeView, val pump: (Int) -> Unit) {
        var screen by mutableStateOf<@Composable () -> Unit>({})
        var scope by mutableStateOf(MemoryScope.PROJECT)
        var query by mutableStateOf("")
        val opened = mutableListOf<MemoryEntry>()
        val deletionRequests = mutableListOf<MemoryEntry>()

        fun showList(entries: List<MemoryEntry>) {
            screen = {
                MemorySection(MemoryUiState(entries = entries, hasWorkspace = true, projectName = PROJECT, loading = false),
                    { opened += it }, { deletionRequests += it }, scope, query, { scope = it }, { query = it })
            }
        }

        fun showRoute(route: RouteFixture) {
            screen = {
                var routeVisible by remember { mutableStateOf(true) }
                if (routeVisible) MemorySettingsRoute(route.viewModel, route.projectViewModel, null, true) {
                    route.exitCount++
                    routeVisible = false
                }
            }
        }

        fun windows(): List<View> = buildList {
            add(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::add)
            (shadowOf(activity.windowManager) as ShadowWindowManagerImpl).views
                .filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }.forEach(::add)
        }.distinct()

        fun roots(): List<ViewRootForTest> = windows().mapNotNull(::findRoot).distinct()
        fun nodes(): List<SemanticsNode> = roots().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false) }
        fun label(resource: Int, vararg args: Any): String = activity.getString(resource, *args)
        fun hasTag(value: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun hasText(value: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun tag(value: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun textNode(resource: Int): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label(resource) } == true }
        fun rowIds(): Set<String> = nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .filter { it.startsWith("memory-row-") }.map { it.removePrefix("memory-row-") }.toSet()
        fun assertCount(expected: Int) { assertTrue(tag("memory-count").containsText(label(R.string.memory_a_count, expected))) }
        fun searchValue(): String = editableSearch().config[SemanticsProperties.EditableText].text
        private fun editableSearch(): SemanticsNode {
            val search = tag("memory-search")
            return descendants(search).single { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
        }
        fun editSearch(value: String) {
            visibleTag("memory-search")
            assertTrue(requireNotNull(editableSearch().config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            settle()
        }
        fun clickTag(value: String) { clickNode(visibleTag(value)) }
        fun clickNode(node: SemanticsNode) {
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            val rowId = node.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { it.startsWith("memory-row-") }
                ?.removePrefix("memory-row-")
            val name = rowId?.let { id -> descendants(node).single {
                it.config.getOrNull(SemanticsProperties.TestTag) == "memory-name-$id"
            }.config[SemanticsProperties.Text].single().text }
            val target = descendants(node).first {
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null && (name == null || it.containsText(name))
            }
            assertFalse(target.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
            settle()
        }
        fun clickDescription(resource: Int) {
            val icon = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label(resource)) == true }
            var current: SemanticsNode? = icon
            while (current != null) {
                if (current.config.getOrNull(SemanticsActions.OnClick)?.action != null) {
                    clickNode(current)
                    return
                }
                current = current.parent
            }
            error("No clickable parent for ${label(resource)}")
        }
        fun clickTabLabel(resource: Int) {
            val text = label(resource)
            clickNode(visibleNode("tab $text") {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && it.containsText(text)
            })
        }
        fun clickText(text: String) {
            val labelNode = visibleNode("label $text") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
            var actionNode: SemanticsNode? = labelNode
            while (actionNode != null && actionNode.config.getOrNull(SemanticsActions.OnClick)?.action == null) {
                actionNode = actionNode.parent
            }
            val actionId = requireNotNull(actionNode) { "No clickable ancestor for $text" }.id
            clickNode(visibleNode("action for $text") { it.id == actionId })
        }
        fun clickLabel(resource: Int) {
            val text = label(resource)
            val labelNode = visibleNode("label $text") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
            }
            var actionNode: SemanticsNode? = labelNode
            while (actionNode != null && actionNode.config.getOrNull(SemanticsActions.OnClick)?.action == null) {
                actionNode = actionNode.parent
            }
            val actionId = requireNotNull(actionNode) { "No clickable ancestor for $text" }.id
            clickNode(visibleNode("action for $text") { it.id == actionId })
        }
        fun clipboardText(): String? = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()
        fun listScroll(): Float = tag("memory-list").config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun scrollList(dy: Float) {
            assertTrue(requireNotNull(tag("memory-list").config[SemanticsActions.ScrollBy].action).invoke(0f, dy))
            settle()
        }
        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(120) { frame ->
                pump(1)
                val geometry = nodes().map { "${it.id}:${it.positionInRoot}:${it.size}:" +
                    it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() }
                stable = if (geometry == previous) stable + 1 else 0
                if (stable >= 4 && frame >= 24) return
                previous = geometry
            }
            error("Memory production Compose layout did not settle")
        }
        fun visibleTag(value: String): SemanticsNode = visibleNode("Memory target $value") {
            it.config.getOrNull(SemanticsProperties.TestTag) == value
        }
        private fun visibleNode(description: String, matches: (SemanticsNode) -> Boolean): SemanticsNode {
            settle()
            repeat(30) { attempt ->
                val node = nodes().singleOrNull(matches)
                if (node != null) {
                    var ancestor = node.parent
                    while (ancestor != null) {
                        val current = ancestor
                        val scroll = current.config.getOrNull(SemanticsActions.ScrollBy)?.action
                        if (scroll != null && current.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                            val viewport = current.boundsInRoot
                            val bounds = node.bounds()
                            val dy = when {
                                bounds.top < viewport.top -> bounds.top - viewport.top
                                bounds.top >= viewport.bottom -> bounds.top - viewport.top
                                bounds.height <= viewport.height && bounds.bottom > viewport.bottom -> bounds.bottom - viewport.bottom
                                else -> 0f
                            }
                            if (dy != 0f) {
                                assertTrue(scroll.invoke(0f, dy))
                                settle()
                                return@repeat
                            }
                        }
                        ancestor = current.parent
                    }
                    return node
                }
                val scroll = nodes().firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    ?: error("$description is absent without a scrollable container")
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, if (attempt == 0) -10_000f else 280f))
                settle()
            }
            error("$description did not become visible")
        }
        fun assertSegments() {
            val segments = tag("memory-scope-segments")
            val global = tag("memory-scope-global")
            val project = tag("memory-scope-project")
            assertTrue(segments.config.contains(SemanticsProperties.SelectableGroup))
            listOf(global, project).forEach {
                assertEquals(Role.Tab, it.config[SemanticsProperties.Role])
                assertTrue(it.bounds().left >= segments.bounds().left && it.bounds().right <= segments.bounds().right)
                assertRounded(it)
            }
            assertRounded(segments)
            val children = layoutChildren(segments.layoutInfo)
            assertEquals("Only the two segment layouts, without an M3 indicator or divider sibling", 2, children.size)
            assertEquals(setOf(global.layoutInfo.semanticsId, project.layoutInfo.semanticsId), children.map { it.semanticsId }.toSet())
            assertEquals(global.size.width.toFloat(), project.size.width.toFloat(), 1f)
            assertEquals(global.positionInRoot.y, project.positionInRoot.y, 0.5f)
            assertTrue(global.bounds().right <= project.bounds().left)
            assertEquals(false, global.config[SemanticsProperties.Selected])
            assertEquals(true, project.config[SemanticsProperties.Selected])
        }
        fun assertVisible(node: SemanticsNode) {
            val bounds = node.bounds()
            assertTrue("$bounds outside ${view.width}x${view.height}", bounds.width > 0 && bounds.height > 0 &&
                bounds.left >= 0f && bounds.top >= 0f && bounds.right <= view.width && bounds.bottom <= view.height)
            assertEquals(bounds.width, node.boundsInRoot.width, 0.5f)
            assertEquals(bounds.height, node.boundsInRoot.height, 0.5f)
        }
        fun assertTextInputContract(node: SemanticsNode, text: String, maxLines: Int) {
            val input = textLayout(node).layoutInput
            assertEquals(text, input.text.text)
            assertEquals(maxLines, input.maxLines)
            assertEquals(TextOverflow.Ellipsis, input.overflow)
            assertEquals(1.5f, input.density.fontScale, 0f)
        }
    }

    private fun render(
        width: Int = 400,
        height: Int = 1000,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        inspect: Ui.() -> Unit
    ): Unit = runTest(timeout = 60.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        val previousInspector = isDebugInspectorInfoEnabled
        isDebugInspectorInfoEnabled = true
        var frame = 0L
        fun layout(window: View, fill: Boolean) {
            val mode = if (fill) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
            window.measure(View.MeasureSpec.makeMeasureSpec(width, mode), View.MeasureSpec.makeMeasureSpec(height, mode))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            findRoot(window)?.measureAndLayoutForTest()
        }
        lateinit var ui: Ui
        ui = Ui(activity, view) { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view, true)
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            dialog?.let { layout(it, true) }
            ui.windows().filter { it !== view && it !== dialog && !containsView(it, view) }.forEach { layout(it, false) }
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme(darkTheme = darkTheme) { ui.screen() }
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
        private const val PROJECT = "workspace"
        private fun entry(scope: MemoryScope, name: String, description: String = "Summary", preview: String = "Preview") =
            MemoryEntry(scope, "$name.md", name, description, preview, "${scope.name}-$name-v1")
        private fun SemanticsNode.bounds(): Rect = Rect(positionInRoot.x, positionInRoot.y,
            positionInRoot.x + size.width, positionInRoot.y + size.height)
        private fun SemanticsNode.containsText(value: String): Boolean =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true || children.any { it.containsText(value) }
        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        private fun textLayout(node: SemanticsNode): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(result))
            return result.single()
        }
        private fun assertSameBand(first: SemanticsNode, second: SemanticsNode) {
            val a = first.bounds()
            val b = second.bounds()
            assertTrue("$a and $b are not in the same row", min(a.bottom, b.bottom) > max(a.top, b.top))
        }
        private fun assertRounded(node: SemanticsNode) {
            val shapes = node.layoutInfo.getModifierInfo().flatMap { info ->
                (info.modifier as? InspectableValue)?.inspectableElements?.mapNotNull { it.value as? Shape }?.toList().orEmpty()
            }
            assertTrue("Expected a production rounded shape, found $shapes", shapes.any { shape ->
                val outline = shape.createOutline(Size(node.size.width.toFloat(), node.size.height.toFloat()), LayoutDirection.Ltr, Density(1f))
                outline is Outline.Rounded && listOf(outline.roundRect.topLeftCornerRadius, outline.roundRect.topRightCornerRadius,
                    outline.roundRect.bottomLeftCornerRadius, outline.roundRect.bottomRightCornerRadius).all { it.x > 0f && it.y > 0f }
            })
        }
        @Suppress("UNCHECKED_CAST")
        private fun layoutChildren(info: LayoutInfo): List<LayoutInfo> =
            info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
        private fun containsView(root: View, child: View): Boolean {
            if (root === child) return true
            return root is ViewGroup && (0 until root.childCount).any { containsView(root.getChildAt(it), child) }
        }
    }
}
