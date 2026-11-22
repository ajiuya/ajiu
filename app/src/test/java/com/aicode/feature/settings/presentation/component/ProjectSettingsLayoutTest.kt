package com.aicode.feature.settings.presentation.component

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.memory.ProjectMemoryAssociations
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapacity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryProject
import com.aicode.feature.settings.presentation.ProjectProgressUiState
import java.time.Duration
import java.util.Locale
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
class ProjectSettingsLayoutTest {
    @Test(timeout = 90_000)
    fun foldedManagementKeepsUnknownCapacityAndOpeningItNeverChangesAProject(): Unit = render {
        wholeSection = true
        settle()
        visibleTag("project-settings-toggle")
        val associationsBefore = state.associations
        val selectedBefore = state.selectedProject
        assertTrue(hasText(label(R.string.project_progress_capacity_unknown)))
        assertFalse(hasTag("project-settings-binding"))
        assertFalse(hasTag("project-settings-capacity"))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch })
        assertTrue(operations.isEmpty())
        clickTag("project-settings-toggle")
        assertTrue(hasTag("project-settings-binding"))
        assertTrue(tag("project-settings-capacity").containsText(label(R.string.project_progress_capacity_unknown)))
        assertFalse(nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        assertFalse(hasTag("project-settings-capacity-details"))
        assertFalse(hasText("0 B"))
        assertFalse(hasText(label(R.string.project_settings_count_pair, 0, 2048)))
        assertFalse(hasText(label(R.string.project_settings_usage_pair, "0 B", "16 MiB")))
        clickTag("project-settings-toggle")
        visibleTag("project-settings-toggle")
        assertTrue(hasText(label(R.string.project_progress_capacity_unknown)))
        assertFalse(hasTag("project-settings-binding"))
        assertEquals(associationsBefore, state.associations)
        assertEquals(selectedBefore, state.selectedProject)
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    fun foldedNearCapacityWarningStaysBeforeHistoryAndTheSummaryUsesActualUsage(): Unit = render {
        wholeSection = true
        state = state.copy(capacity = capacity(active = 2000, archived = 12, bytes = 1_000_000))
        settle()
        val warning = label(R.string.project_progress_capacity_warning)
        val recent = label(R.string.project_progress_recent)
        assertTrue(hasText(warning))
        assertTrue(textNode(warning).bounds().bottom <= textNode(recent).bounds().top)
        assertFalse(hasTag("project-settings-capacity"))
        visibleTag("project-settings-toggle")
        assertTrue(tag("project-settings-toggle").containsText(label(R.string.project_settings_capacity_summary,
            2000, 2048, projectProgressFormatBytes(1_000_000, activity.resources.configuration.locales[0]), "16 MiB")))
        assertFalse(hasText(label(R.string.project_progress_capacity_value, 2000, 2048, 12, 1_000_000, 16_777_216)))
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    fun bindingStatusDistinguishesUnboundExplicitNoneAndTheActualBoundProjectInBothScopes(): Unit = render {
        val other = project.copy(id = "workspace-project", name = "Workspace default project")
        listOf("session", null).forEach { session ->
            val cases = listOf(
                Triple(false, null, label(R.string.project_progress_not_bound)),
                Triple(true, null, label(R.string.project_progress_explicit_none)),
                Triple(true, project, label(R.string.project_progress_bound_project, project.name))
            )
            cases.forEach { (present, bound, expected) ->
                state = state.copy(sessionId = session, associations = if (session != null) {
                    ProjectMemoryAssociations(present, bound, true, other)
                } else {
                    ProjectMemoryAssociations(true, other, present, bound)
                })
                settle()
                assertEquals(listOf(expected), tag("project-settings-binding-status").config[SemanticsProperties.Text].map { it.text })
                assertFalse(tag("project-settings-binding-status").containsText(label(R.string.project_progress_bound_project, other.name)))
                assertTrue(operations.isEmpty())
            }
        }
        state = state.copy(associations = null)
        settle()
        assertTrue(tag("project-settings-binding-status").containsText(label(R.string.project_progress_binding_unknown)))
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    fun bindingScopeHintsAndEachExplicitActionKeepTheirExactSessionOrWorkspaceMeaning(): Unit = render {
        listOf("session", null).forEach { session ->
            state = state.copy(sessionId = session)
            settle()
            val scope = if (session == null) R.string.project_settings_binding_scope_workspace
                else R.string.project_settings_binding_scope_session
            val hint = if (session == null) R.string.project_settings_binding_scope_hint_workspace
                else R.string.project_settings_binding_scope_hint_session
            val otherHint = if (session == null) R.string.project_settings_binding_scope_hint_session
                else R.string.project_settings_binding_scope_hint_workspace
            assertTrue(tag("project-settings-binding-scope").containsText(label(scope)))
            assertTrue(tag("project-settings-binding").containsText(label(hint)))
            assertFalse(tag("project-settings-binding").containsText(label(otherHint)))
            val before = operations.toList()
            clickTag("project-settings-bind")
            assertEquals(before + "bind", operations)
            clickTag("project-settings-unbind")
            assertEquals(before + listOf("bind", "unbind"), operations)
            clickLabel(R.string.project_progress_handoff)
            assertEquals(before + listOf("bind", "unbind", "handoff"), operations)
            assertEquals(ProjectMemoryAssociations(false, null, false, null), state.associations)
        }
    }

    @Test(timeout = 90_000)
    fun aSelectedProjectOutsideTheWorkspaceCannotBindButOtherOriginalActionsRemainAvailable(): Unit = render {
        state = state.copy(projects = listOf(project.copy(identity = identity.copy(projectRoot = "/other/project"))))
        settle()
        assertFalse(state.canBindSelected)
        assertTrue(tag("project-settings-binding").containsText(label(R.string.project_progress_outside_workspace)))
        assertTrue(tag("project-settings-bind").config.contains(SemanticsProperties.Disabled))
        tapDisabledTag("project-settings-bind")
        assertTrue(operations.isEmpty())
        tapEnabledTag("project-settings-unbind")
        assertEquals(listOf("unbind"), operations)
        assertFalse(tag("project-settings-auto-progress").config.contains(SemanticsProperties.Disabled))
        clickLabel(R.string.project_progress_handoff)
        assertEquals(listOf("unbind", "handoff"), operations)
    }

    @Test(timeout = 90_000)
    fun automaticProgressUsesTheSharedControlledSwitchAndOnlyEmitsTheRequestedBoolean(): Unit = render {
        settle()
        var toggle = tag("project-settings-auto-progress")
        assertEquals(Role.Switch, toggle.config[SemanticsProperties.Role])
        assertEquals(ToggleableState.On, toggle.config[SemanticsProperties.ToggleableState])
        assertEquals(42, toggle.size.width)
        assertEquals(24, toggle.size.height)
        clickTag("project-settings-auto-progress")
        assertEquals(listOf("auto:false"), operations)
        assertEquals(ToggleableState.On, tag("project-settings-auto-progress").config[SemanticsProperties.ToggleableState])
        state = state.copy(projects = listOf(project.copy(autoProgressEnabled = false)))
        settle()
        toggle = tag("project-settings-auto-progress")
        assertEquals(ToggleableState.Off, toggle.config[SemanticsProperties.ToggleableState])
        clickTag("project-settings-auto-progress")
        assertEquals(listOf("auto:false", "auto:true"), operations)
        assertEquals(ToggleableState.Off, tag("project-settings-auto-progress").config[SemanticsProperties.ToggleableState])
    }

    @Test(timeout = 90_000)
    fun capacitySeparatesCountsArchiveAndBinaryStorageAndPreservesTheExactRawDetails(): Unit = render {
        state = state.copy(capacity = capacity())
        settle()
        val card = tag("project-settings-capacity")
        assertTrue(card.containsText(label(R.string.project_settings_all_environments)))
        assertTrue(card.containsText(label(R.string.project_settings_active_count)))
        assertTrue(card.containsText(label(R.string.project_settings_count_pair, 3, 2048)))
        assertTrue(card.containsText(label(R.string.project_settings_archived_count)))
        assertTrue(tag("project-settings-archived").containsText("7"))
        assertTrue(card.containsText(label(R.string.project_settings_storage_used)))
        assertTrue(card.containsText(label(R.string.project_settings_usage_pair, "60 B", "16 MiB")))
        assertEquals(2, descendants(card).count { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
        assertProgress("project-settings-active-usage", 3f / 2048)
        assertProgress("project-settings-storage-usage", 60f / 16_777_216)
        val raw = label(R.string.project_progress_capacity_value, 3, 2048, 7, 60, 16_777_216)
        assertFalse(hasText(raw))
        clickTag("project-settings-capacity-details")
        assertTrue(tag("project-settings-capacity").containsText(raw))
        assertTrue(operations.isEmpty())
        clickTag("project-settings-capacity-details")
        assertFalse(hasText(raw))
        assertEquals("60 B", projectProgressFormatBytes(60, Locale.US))
        assertEquals("16 MiB", projectProgressFormatBytes(16_777_216, Locale.US))
        assertEquals("1 KiB", projectProgressFormatBytes(1024, Locale.US))
        assertEquals("1.5 MiB", projectProgressFormatBytes(1_572_864, Locale.US))
        assertEquals("1,5 MiB", projectProgressFormatBytes(1_572_864, Locale.GERMANY))
    }

    @Test(timeout = 90_000)
    fun zeroAndNearCapacityProgressUseRealTotalsAndArchivingNeverSubtractsStoredBytes(): Unit = render {
        state = state.copy(capacity = capacity(active = 0, archived = 91, bytes = 0))
        settle()
        assertProgress("project-settings-active-usage", 0f)
        assertProgress("project-settings-storage-usage", 0f)
        assertTrue(tag("project-settings-capacity").containsText(label(R.string.project_settings_usage_pair, "0 B", "16 MiB")))
        val near = capacity(active = 2000, archived = 91, bytes = 16_777_156)
        assertTrue(near.nearCapacity)
        state = state.copy(capacity = near)
        settle()
        assertProgress("project-settings-active-usage", 2000f / 2048)
        assertProgress("project-settings-storage-usage", 16_777_156f / 16_777_216)
        clickTag("project-settings-capacity-details")
        assertTrue(hasText(label(R.string.project_progress_capacity_value, 2000, 2048, 91, 16_777_156, 16_777_216)))
        state = state.copy(capacity = near.copy(activeEntries = 0, remainingEntries = 2048, archivedEntries = 2091))
        settle()
        assertProgress("project-settings-active-usage", 0f)
        assertProgress("project-settings-storage-usage", 16_777_156f / 16_777_216)
        assertTrue(hasText(label(R.string.project_progress_capacity_value, 0, 2048, 2091, 16_777_156, 16_777_216)))
        assertEquals(16_777_156, state.capacity?.usedBytes)
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    fun environmentUsesTheCurrentModeAndTheExplicitReadOnlyDetailsCopyTheCompleteOriginalPath(): Unit = render {
        val path = "/workspace/" + "nested original directory/".repeat(24) + "literal-项目-root"
        state = state.copy(workspaceRoot = path, environmentMode = "local_proot",
            projects = listOf(project.copy(identity = identity.copy(mode = "remote_ssh", projectRoot = "/selected-project-root"))))
        settle()
        val environment = tag("project-settings-environment")
        assertTrue(environment.containsText(label(R.string.project_progress_local_environment)))
        assertFalse(environment.containsText(label(R.string.project_progress_remote_environment)))
        assertFalse(environment.containsText("/selected-project-root"))
        val preview = textLayout(tag("project-settings-path")).layoutInput
        assertEquals(path, preview.text.text)
        assertEquals(1, preview.maxLines)
        assertEquals(TextOverflow.MiddleEllipsis, preview.overflow)
        assertFalse(hasTag("project-settings-copy-path"))
        val clipboardBefore = clipboardText()
        clickTag("project-settings-environment-details")
        assertEquals(clipboardBefore, clipboardText())
        val detail = tag("project-settings-path")
        assertEquals(path, detail.config[SemanticsProperties.Text].single().text)
        assertEquals(path, textLayout(detail).layoutInput.text.text)
        assertEquals(Int.MAX_VALUE, textLayout(detail).layoutInput.maxLines)
        assertFalse(detail.config.contains(SemanticsActions.SetText))
        assertFalse(detail.config.contains(SemanticsActions.OnClick))
        val selectionTarget = requireNotNull(generateSequence(detail.parent) { it.parent }.firstOrNull {
            it.config.contains(SemanticsProperties.Focused) && it.config.getOrNull(SemanticsActions.RequestFocus)?.action != null
        }) { "Complete path must retain the read-only selection container's focus target" }
        assertTrue(requireNotNull(selectionTarget.config[SemanticsActions.RequestFocus].action).invoke())
        settle()
        assertEquals(true, nodes().single { it.id == selectionTarget.id }.config[SemanticsProperties.Focused])
        assertEquals(clipboardBefore, clipboardText())
        clickTag("project-settings-copy-path")
        assertEquals(path, clipboardText())
        assertEquals(1, nodes().count { it.config.getOrNull(SemanticsProperties.TestTag) == "project-settings-path" })
        assertTrue(operations.isEmpty())
        clickTag("project-settings-environment-details")
        assertFalse(hasTag("project-settings-copy-path"))
        assertEquals(path, textLayout(tag("project-settings-path")).layoutInput.text.text)
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    fun busyDisablesEveryMutationWithoutInvokingCallbacksAndReenablesTheSameActionsAfterward(): Unit = render {
        wholeSection = true
        state = state.copy(capacity = capacity())
        settle()
        clickTag("project-settings-toggle")
        clickTag("project-settings-environment-details")
        val clipboardBefore = clipboardText()
        state = state.copy(busy = true)
        settle()
        listOf("project-settings-toggle", "project-settings-bind", "project-settings-unbind",
            "project-settings-auto-progress", "project-settings-capacity-details", "project-settings-copy-path")
            .forEach(::tapDisabledTag)
        tapDisabledLabel(R.string.project_progress_handoff)
        assertTrue(operations.isEmpty())
        assertEquals(clipboardBefore, clipboardText())
        assertEquals(ToggleableState.On, tag("project-settings-auto-progress").config[SemanticsProperties.ToggleableState])
        assertFalse(hasText(label(R.string.project_progress_capacity_value, 3, 2048, 7, 60, 16_777_216)))
        state = state.copy(busy = false)
        settle()
        tapEnabledTag("project-settings-unbind")
        assertEquals(listOf("unbind"), operations)
        clickTag("project-settings-bind")
        clickTag("project-settings-auto-progress")
        clickLabel(R.string.project_progress_handoff)
        assertEquals(listOf("unbind", "bind", "auto:false", "handoff"), operations)
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontDarkManagementKeepsRoundedGroupsAndEveryCapacityLabelAndValueInside(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        val path = "/workspace/" + "very long project path segment/".repeat(50)
        state = state.copy(workspaceRoot = path, capacity = capacity())
        settle()
        val binding = tag("project-settings-binding")
        val environment = tag("project-settings-environment")
        val capacity = tag("project-settings-capacity")
        listOf(binding, environment, capacity).forEach { card ->
            assertRounded(card)
            assertTrue(card.bounds().left >= 0f && card.bounds().right <= 320f)
            descendants(card).filter { it.config.contains(SemanticsProperties.Text) }.forEach { assertContained(it, card) }
        }
        assertTrue(binding.bounds().bottom < environment.bounds().top)
        assertTrue(environment.bounds().bottom < capacity.bounds().top)
        assertContained(tag("project-settings-binding-status"), binding)
        assertContained(tag("project-settings-binding-scope"), binding)
        assertContained(tag("project-settings-path"), environment)
        val pathInput = textLayout(tag("project-settings-path")).layoutInput
        assertEquals(path, pathInput.text.text)
        assertEquals(1, pathInput.maxLines)
        assertEquals(TextOverflow.MiddleEllipsis, pathInput.overflow)
        assertEquals(1.5f, pathInput.density.fontScale, 0f)
        val previewHeight = tag("project-settings-path").size.height
        state = state.copy(workspaceRoot = "/workspace")
        settle()
        assertEquals(previewHeight.toFloat(), tag("project-settings-path").size.height.toFloat(), 1f)
        assertLabelValueDoNotOverlap(label(R.string.project_settings_active_count), label(R.string.project_settings_count_pair, 3, 2048))
        assertLabelValueDoNotOverlap(label(R.string.project_settings_storage_used), label(R.string.project_settings_usage_pair, "60 B", "16 MiB"))
        assertLabelValueDoNotOverlap(label(R.string.project_settings_archived_count), "7")
        listOf("project-settings-bind", "project-settings-unbind", "project-settings-auto-progress",
            "project-settings-environment-details", "project-settings-active-usage", "project-settings-storage-usage",
            "project-settings-capacity-details").forEach { assertVisible(visibleTag(it)) }
        assertTrue(operations.isEmpty())
    }

    @Test(timeout = 90_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun overviewKeepsTheCompleteLongPathInATwoLineContractWithoutUnboundedHeight(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        wholeSection = true
        val path = "/workspace/" + "deep root/".repeat(80)
        state = state.copy(projects = listOf(project.copy(identity = identity.copy(projectRoot = path))))
        settle()
        val pathNode = textNode(path)
        val input = textLayout(pathNode).layoutInput
        assertEquals(path, input.text.text)
        assertEquals(2, input.maxLines)
        assertEquals(TextOverflow.Ellipsis, input.overflow)
        assertEquals(1.5f, input.density.fontScale, 0f)
        assertVisible(pathNode)
        val longHeight = pathNode.size.height
        state = state.copy(projects = listOf(project))
        settle()
        val shortHeight = textNode(identity.projectRoot).size.height
        assertTrue("$longHeight must not grow with the full root length; short=$shortHeight", longHeight <= shortHeight * 2 + 2)
        assertTrue(operations.isEmpty())
    }

    private class Ui(val activity: ComponentActivity, val view: ComposeView, val pump: (Int) -> Unit) {
        var state by mutableStateOf(baseState())
        var expanded by mutableStateOf(true)
        var wholeSection by mutableStateOf(false)
        val operations = mutableListOf<String>()

        @Composable
        fun Content() {
            if (wholeSection) {
                ProjectProgressSection(
                    state,
                    { operations += "select:${it.id}" },
                    { operations += "open:${it.id}" },
                    { operations += "register" },
                    { operations += "bind" },
                    { operations += "unbind" },
                    { operations += "auto:$it" },
                    { operations += "handoff" },
                    { operations += "archive:$it" },
                    { operations += "more" },
                    { operations += "filter:$it" }
                )
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
                    ProjectProgressManagement(
                        state, expanded, { expanded = !expanded },
                        !state.busy && !state.loading && state.associations != null,
                        { operations += "bind" }, { operations += "unbind" },
                        { operations += "auto:$it" }, { operations += "handoff" }
                    )
                }
            }
        }

        fun nodes(): List<SemanticsNode> = requireNotNull(findRoot(view)).semanticsOwner
            .getAllSemanticsNodes(mergingEnabled = false)
        fun label(resource: Int, vararg args: Any): String = activity.getString(resource, *args)
        fun texts(): List<String> = nodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        }
        fun hasText(value: String): Boolean = value in texts()
        fun hasTag(value: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun tag(value: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun textNode(value: String): SemanticsNode = nodes().single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }
        fun clipboardText(): String? = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.text?.toString()
        fun assertProgress(value: String, expected: Float) {
            val info = tag(value).config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(0f..1f, info.range)
            assertEquals(0, info.steps)
            assertEquals(expected, info.current, 0.0000001f)
        }
        fun assertLabelValueDoNotOverlap(label: String, value: String) {
            val titleBounds = textNode(label).bounds()
            val valueBounds = textNode(value).bounds()
            assertTrue("Title $titleBounds and value $valueBounds must not overlap", titleBounds.width > 0f &&
                valueBounds.width > 0f && (titleBounds.right <= valueBounds.left + 0.5f ||
                titleBounds.bottom <= valueBounds.top + 0.5f || valueBounds.bottom <= titleBounds.top + 0.5f))
        }
        fun tapEnabledTag(value: String) {
            val node = visibleTag(value)
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action)
            tap(node)
        }
        fun clickTag(value: String) {
            val node = reveal("tag $value") { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            clickNode(descendants(node).single { it.config.getOrNull(SemanticsActions.OnClick)?.action != null })
        }
        fun clickLabel(resource: Int) {
            val value = label(resource)
            var node: SemanticsNode? = reveal("label $value") {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick)?.action == null) node = node.parent
            clickNode(requireNotNull(node) { "No clickable ancestor for $value" })
        }
        private fun clickNode(node: SemanticsNode) {
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            settle()
        }
        fun tapDisabledTag(value: String) {
            val node = reveal("disabled tag $value") { it.config.getOrNull(SemanticsProperties.TestTag) == value }
            val targets = descendants(node)
            assertTrue("$value must not expose an enabled action", targets.none {
                it.config.getOrNull(SemanticsActions.OnClick)?.action != null && !it.config.contains(SemanticsProperties.Disabled)
            })
            tap(node)
        }
        fun tapDisabledLabel(resource: Int) {
            val value = label(resource)
            var node: SemanticsNode? = reveal("disabled label $value") {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }
            while (node != null && (!node.config.contains(SemanticsProperties.Disabled) ||
                node.config.getOrNull(SemanticsActions.OnClick)?.action == null)) node = node.parent
            val actionId = requireNotNull(node) { "No disabled action ancestor for $value" }.id
            tap(reveal("disabled action for $value") { it.id == actionId })
        }
        private fun tap(node: SemanticsNode) {
            val bounds = node.bounds()
            assertTrue("Touch target node=${node.id}, bounds=$bounds, clipped=${node.boundsInRoot} outside ${view.width}x${view.height}",
                bounds.width > 0f && bounds.height > 0f && bounds.left >= 0f && bounds.top >= 0f &&
                bounds.right <= view.width && bounds.bottom <= view.height)
            val downTime = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
                val coordinates = MotionEvent.PointerCoords().apply {
                    x = bounds.center.x; y = bounds.center.y; pressure = 1f; size = 1f
                }
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer),
                    arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    view.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
                pump(2)
            }
            settle()
        }
        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(120) { frame ->
                pump(1)
                val geometry = nodes().map { node ->
                    "${node.id}:${node.positionInRoot}:${node.size}:" +
                        node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value()
                }
                stable = if (geometry == previous) stable + 1 else 0
                if (stable >= 4 && frame >= 24) return
                previous = geometry
            }
            error("Project settings production Compose layout did not settle: ${texts()}")
        }
        fun visibleTag(value: String): SemanticsNode = reveal("tag $value") {
            it.config.getOrNull(SemanticsProperties.TestTag) == value
        }
        private fun reveal(description: String, matches: (SemanticsNode) -> Boolean): SemanticsNode {
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
                    ?: error("$description absent without a scrollable container")
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action)
                    .invoke(0f, if (attempt == 0) -10_000f else 280f))
                settle()
            }
            error("$description did not become visible")
        }
        fun assertContained(child: SemanticsNode, parent: SemanticsNode) {
            val inner = child.bounds()
            val outer = parent.bounds()
            assertTrue("$inner is not contained in $outer", inner.width > 0f && inner.height > 0f &&
                inner.left >= outer.left - 0.5f && inner.top >= outer.top - 0.5f &&
                inner.right <= outer.right + 0.5f && inner.bottom <= outer.bottom + 0.5f)
        }
        fun assertVisible(node: SemanticsNode) {
            val bounds = node.bounds()
            assertTrue("$bounds outside ${view.width}x${view.height}", bounds.width > 0f && bounds.height > 0f &&
                bounds.left >= 0f && bounds.top >= 0f && bounds.right <= view.width && bounds.bottom <= view.height)
            assertEquals(bounds.width, node.boundsInRoot.width, 0.5f)
            assertEquals(bounds.height, node.boundsInRoot.height, 0.5f)
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
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val previousInspector = isDebugInspectorInfoEnabled
        isDebugInspectorInfoEnabled = true
        var frame = 0L
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            findRoot(view)?.measureAndLayoutForTest()
        }
        val ui = Ui(activity, view) { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout()
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme(darkTheme = darkTheme) { ui.Content() }
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
        private val identity = ProjectMemoryIdentity("local_proot", projectRoot = "/workspace", profileId = "profile")
        private val project = ProjectMemoryProject("project-id", "Project name", identity = identity)
        private fun baseState() = ProjectProgressUiState(
            workspaceName = "Workspace", workspaceRoot = identity.projectRoot, environmentMode = identity.mode,
            loading = false, sessionId = "session", projects = listOf(project), selectedProjectId = project.id,
            associations = ProjectMemoryAssociations(false, null, false, null)
        )
        private fun capacity(active: Int = 3, archived: Int = 7, bytes: Int = 60) =
            ProjectMemoryCapacity(active, 2048, archived, 2048 - active, bytes, 16_777_216)
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
        private fun assertRounded(node: SemanticsNode) {
            val shapes = node.layoutInfo.getModifierInfo().flatMap { info ->
                (info.modifier as? InspectableValue)?.inspectableElements?.mapNotNull { it.value as? Shape }?.toList().orEmpty()
            }
            assertTrue("Expected a production rounded card shape, found $shapes", shapes.any { shape ->
                val outline = shape.createOutline(Size(node.size.width.toFloat(), node.size.height.toFloat()),
                    LayoutDirection.Ltr, Density(1f))
                outline is Outline.Rounded && listOf(outline.roundRect.topLeftCornerRadius, outline.roundRect.topRightCornerRadius,
                    outline.roundRect.bottomLeftCornerRadius, outline.roundRect.bottomRightCornerRadius).all { it.x > 0f && it.y > 0f }
            })
        }
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
