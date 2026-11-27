package com.aicode.feature.settings.presentation

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeMount
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeState
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.presentation.component.ChrootAuthorizationSummary
import com.aicode.feature.settings.presentation.component.ChrootProfileSettings
import com.aicode.feature.settings.presentation.component.ChrootSettingsDialogs
import com.aicode.feature.settings.presentation.component.ContainerRuntimePicker
import com.aicode.feature.settings.presentation.component.ContainerAdditionFeedback
import com.aicode.feature.settings.presentation.component.ContainerChrootAddNotice
import com.aicode.feature.settings.presentation.component.ContainerSection
import com.aicode.feature.settings.presentation.component.ContainerImageDownloadSection
import com.aicode.core.ui.ListSelectionState
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1000dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ChrootSettingsUiTest {
    private val instance = ChrootInstance("0123456789abcdef01234567", 5, 19, 1234)
    private val profile = ContainerProfile(
        id = "chroot-profile", name = "CHROOT", rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
        shellPath = "/bin/sh", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT
    )
    private val root = RootAccessState(true, 7, "test-authority")

    @Test(timeout = 30_000)
    fun runtimeSelectionWorksOnlyBeforeCreationAndDoesNotActivateAnything() {
        var runtime by mutableStateOf(LocalContainerRuntime.PROOT)
        var locked by mutableStateOf(false)
        val selections = mutableListOf<LocalContainerRuntime>()
        render(content = {
            ContainerRuntimePicker(runtime, locked) { runtime = it; selections += it }
        }) {
            assertTrue(selections.isEmpty())
            clickText(text(R.string.container_runtime_chroot))
            pump()
            assertEquals(listOf(LocalContainerRuntime.CHROOT), selections)
            locked = true
            pump()
            assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
            assertEquals(LocalContainerRuntime.CHROOT, runtime)
        }
    }

    @Test(timeout = 30_000)
    fun uninstalledUnknownOrUnhealthyProfilesWithoutRepairCapabilityHaveNoLifecycleActions() {
        val available = LocalRuntimeState(null, true, true, false, 0, 0, false)
        var state by mutableStateOf(available)
        val actions = mutableListOf<ChrootInstanceAction>()
        val authorizations = mutableListOf<LocalRuntimeAuthorization>()
        render(content = {
            Column {
                ChrootProfileSettings(
                    profile, ChrootSettingsUiState(states = mapOf(profile.id to state), rootState = root),
                    { _, action -> actions += action }, { authorizations += it }
                )
            }
        }) {
            assertTrue(actions.isEmpty())
            clickText(text(R.string.container_chroot_create))
            pump()
            assertEquals(listOf(ChrootInstanceAction.CREATE), actions)
            for (unknown in listOf(available.copy(recoveryRequired = true), available.copy(journalHealthy = false), available.copy(busy = true))) {
                state = unknown
                pump()
                assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
            }
            assertTrue(authorizations.isEmpty())
            assertEquals(listOf(ChrootInstanceAction.CREATE), actions)
        }
    }

    @Test(timeout = 30_000)
    fun unknownInstancesExposeRepairAndRecoveryOnlyWhenTheirCapabilitiesAreAvailable() {
        val installed = profile.copy(chrootInstance = instance)
        val repairable = LocalRuntimeState(instance, true, false, true, 0, 0, false, journalRepairAvailable = true)
        var state by mutableStateOf(repairable)
        var currentRoot by mutableStateOf(root)
        var workingProfileId by mutableStateOf<String?>(null)
        val actions = mutableListOf<ChrootInstanceAction>()
        render(content = {
            ChrootProfileSettings(installed, ChrootSettingsUiState(states = mapOf(profile.id to state),
                rootState = currentRoot, workingProfileId = workingProfileId), { _, action -> actions += action }, {})
        }) {
            assertTrue(allText().contains(text(R.string.container_chroot_status_unknown)))
            assertTrue(textEnabled(R.string.container_chroot_repair_journal))
            assertTrue(textEnabled(R.string.container_chroot_recover_remove))
            for (blocked in listOf(R.string.container_chroot_create, R.string.container_chroot_stop, R.string.container_chroot_remove)) {
                assertTrue(!textEnabled(blocked))
            }
            assertTrue(actions.isEmpty())
            clickText(text(R.string.container_chroot_repair_journal))
            clickText(text(R.string.container_chroot_recover_remove))
            pump()
            assertEquals(listOf(ChrootInstanceAction.REPAIR_JOURNAL, ChrootInstanceAction.RECOVER_REMOVE), actions)
            state = repairable.copy(journalHealthy = true, journalRepairAvailable = false)
            pump()
            assertTrue(!textEnabled(R.string.container_chroot_repair_journal))
            assertTrue(textEnabled(R.string.container_chroot_recover_remove))
            for (blocked in listOf(repairable.copy(rootEnabled = false), repairable.copy(busy = true),
                repairable.copy(journalRepairAvailable = false))) {
                state = blocked
                pump()
                assertTrue(!textEnabled(R.string.container_chroot_repair_journal))
                assertTrue(!textEnabled(R.string.container_chroot_recover_remove))
            }
            state = repairable
            currentRoot = root.copy(enabled = false)
            pump()
            assertTrue(!textEnabled(R.string.container_chroot_repair_journal))
            assertTrue(!textEnabled(R.string.container_chroot_recover_remove))
            currentRoot = root
            workingProfileId = "another-profile"
            pump()
            assertTrue(!textEnabled(R.string.container_chroot_repair_journal))
            assertTrue(!textEnabled(R.string.container_chroot_recover_remove))
            workingProfileId = null
            state = repairable.copy(instance = null)
            pump()
            assertTrue(!textEnabled(R.string.container_chroot_recover_remove))
        }
    }

    @Test(timeout = 30_000)
    fun containerListDeleteRoutesUnknownAndPendingRemovalToRecoveryAndHealthyToNormalRemoval() {
        val installed = profile.copy(chrootInstance = instance)
        val repairable = LocalRuntimeState(instance, true, false, true, 0, 0, false, journalRepairAvailable = true)
        var state by mutableStateOf(repairable)
        var currentRoot by mutableStateOf(root)
        var workingProfileId by mutableStateOf<String?>(null)
        val actions = mutableListOf<ChrootInstanceAction>()
        var ordinaryDeletes = 0
        render(content = {
            ContainerSection(profiles = listOf(installed), activeProfileId = profile.id, onSelect = {},
                onSaveCustom = { _, _, _ -> }, onEditCustom = { _, _ -> }, onDeleteProfile = { ordinaryDeletes++ },
                chrootUiState = ChrootSettingsUiState(states = mapOf(profile.id to state), rootState = currentRoot,
                    workingProfileId = workingProfileId), onChrootInstanceAction = { _, action -> actions += action })
        }) {
            assertTrue(allText().contains(text(R.string.container_chroot_status_unknown)))
            assertTrue(textEnabled(R.string.common_delete))
            clickText(text(R.string.common_delete))
            pump()
            assertEquals(listOf(ChrootInstanceAction.RECOVER_REMOVE), actions)
            state = repairable.copy(journalHealthy = true, recoveryRequired = false, journalRepairAvailable = false)
            pump()
            clickText(text(R.string.common_delete))
            pump()
            assertEquals(listOf(ChrootInstanceAction.RECOVER_REMOVE, ChrootInstanceAction.REMOVE), actions)
            state = state.copy(removalPending = true)
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_status_removal_pending)))
            assertTrue(textEnabled(R.string.common_delete))
            clickText(text(R.string.common_delete))
            pump()
            assertEquals(listOf(ChrootInstanceAction.RECOVER_REMOVE, ChrootInstanceAction.REMOVE, ChrootInstanceAction.RECOVER_REMOVE), actions)
            for (blocked in listOf(repairable.copy(journalRepairAvailable = false), repairable.copy(busy = true), repairable.copy(rootEnabled = false))) {
                state = blocked
                pump()
                assertTrue(!textEnabled(R.string.common_delete))
            }
            state = repairable
            currentRoot = root.copy(enabled = false)
            pump()
            assertTrue(!textEnabled(R.string.common_delete))
            currentRoot = root
            workingProfileId = "another-profile"
            pump()
            assertTrue(!textEnabled(R.string.common_delete))
            assertEquals(0, ordinaryDeletes)
        }
    }

    @Test(timeout = 30_000)
    fun unknownProfileEditSheetContainsBothExplicitRecoveryActions() {
        val installed = profile.copy(chrootInstance = instance)
        val state = LocalRuntimeState(instance, true, false, true, 0, 0, false, journalRepairAvailable = true)
        val actions = mutableListOf<ChrootInstanceAction>()
        render(content = {
            ContainerSection(profiles = listOf(installed), activeProfileId = profile.id, onSelect = {},
                onSaveCustom = { _, _, _ -> }, onEditCustom = { _, _ -> }, onDeleteProfile = {},
                chrootUiState = ChrootSettingsUiState(states = mapOf(profile.id to state), rootState = root),
                onChrootInstanceAction = { _, action -> actions += action })
        }) {
            clickDescription(R.string.common_edit)
            pump()
            assertTrue(allText().contains(text(R.string.container_edit_image)))
            assertTrue(textEnabled(R.string.container_chroot_repair_journal))
            assertTrue(textEnabled(R.string.container_chroot_recover_remove))
            assertTrue(!textEnabled(R.string.common_save))
            clickText(text(R.string.container_chroot_repair_journal))
            clickText(text(R.string.container_chroot_recover_remove))
            pump()
            assertEquals(listOf(ChrootInstanceAction.REPAIR_JOURNAL, ChrootInstanceAction.RECOVER_REMOVE), actions)
        }
    }

    @Test(timeout = 30_000)
    fun activeLaunchesAreNotReportedAsStoppedOrAsProofTheTaskIsStillRunning() {
        var state by mutableStateOf(LocalRuntimeState(instance, true, true, false, 1, 1, false))
        render(content = {
            Column {
                ChrootProfileSettings(
                    profile.copy(chrootInstance = instance),
                    ChrootSettingsUiState(states = mapOf(profile.id to state), rootState = root),
                    { _, _ -> }, {}
                )
            }
        }) {
            fun shown(value: String) = nodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }
            assertTrue(shown(text(R.string.container_chroot_status_active)))
            assertTrue(!shown(text(R.string.container_chroot_status_stopped)))
            state = state.copy(busy = true)
            pump()
            assertTrue(shown(text(R.string.container_chroot_status_busy)))
            assertTrue(!shown(text(R.string.container_chroot_status_stopped)))
        }
    }

    @Test(timeout = 30_000)
    fun authorizationSummaryContainsExactOwnerSourceConfigurationRootIdentityAndEveryMount() {
        val request = LocalRuntimeAuthorization(
            RuntimeOwner(RuntimeOwnerKind.MCP, "existing-service"), profile.id, 41, instance, root,
            "/actual/workspace", "/private/home", "plan-digest", listOf(
                LocalRuntimeMount("/actual/workspace", "/workspace", false),
                LocalRuntimeMount("/private/dns", "/etc/resolv.conf", true)
            ), "caller-fingerprint", Any()
        )
        render(content = { Column { ChrootAuthorizationSummary(request) } }) {
            val allText = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
            for (expected in listOf("existing-service", "caller-fingerprint", profile.id, "41", instance.id, "19", "1234", root.authorityId, "7", "/actual/workspace", "/private/home", "/private/dns", "/etc/resolv.conf", "plan-digest")) {
                assertTrue("Missing confirmation field: $expected", allText.contains(expected))
            }
            assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
        }
    }

    @Test(timeout = 30_000)
    fun installedProfilesAreReadyWithoutOwnersAndNeverDisplayLegacyAuthorizationRequests() {
        val request = LocalRuntimeAuthorization(RuntimeOwner(RuntimeOwnerKind.AGENT, "obsolete-owner"), profile.id,
            41, instance, root, "/actual/workspace", "/private/home", "old-plan", emptyList(), "old-source", Any())
        val actions = mutableListOf<ChrootInstanceAction>()
        val authorizations = mutableListOf<LocalRuntimeAuthorization>()
        val state = LocalRuntimeState(instance, true, true, false, 0, 0, false)
        render(content = {
            Column {
                ChrootProfileSettings(profile.copy(chrootInstance = instance),
                    ChrootSettingsUiState(states = mapOf(profile.id to state), rootState = root, pendingAuthorizations = listOf(request)),
                    { _, action -> actions += action }, { authorizations += it })
            }
        }) {
            val texts = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString("\n") { it.text }
            assertTrue(texts.contains(text(R.string.container_chroot_status_unauthorized)))
            assertTrue(!texts.contains(text(R.string.container_chroot_authorization_requests)))
            assertTrue(!texts.contains("obsolete-owner"))
            assertTrue(actions.isEmpty())
            clickText(text(R.string.container_chroot_stop))
            pump()
            clickText(text(R.string.container_chroot_remove))
            pump()
            assertEquals(listOf(ChrootInstanceAction.STOP, ChrootInstanceAction.REMOVE), actions)
            assertTrue(authorizations.isEmpty())
        }
    }

    @Test(timeout = 30_000)
    fun recoverySummaryKeepsIdentityFieldsAndRechecksActionCapabilityAndRoot() {
        val installed = profile.copy(chrootInstance = instance)
        val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(installed), 1, 41, Any())
        val repairable = LocalRuntimeState(instance, true, false, true, 0, 0, false, journalRepairAvailable = true)
        val confirmation = ChrootConfirmation.Instance(ChrootInstanceAction.REPAIR_JOURNAL, installed, root, snapshot)
        var uiState by mutableStateOf(ChrootSettingsUiState(states = mapOf(profile.id to repairable),
            rootState = root, confirmation = confirmation))
        var confirmed = 0
        render(content = { ChrootSettingsDialogs(uiState, { confirmed++ }, {}, {}) }) {
            for (expected in listOf(profile.id, instance.id, "41", "1234", root.authorityId, "alpine-rootfs.bin")) {
                assertTrue("Missing confirmation field: $expected", allText().contains(expected))
            }
            assertTrue(allText().contains(text(R.string.container_chroot_repair_journal_notice)))
            assertTrue(textEnabled(R.string.common_confirm))
            assertEquals(0, confirmed)
            clickText(text(R.string.common_confirm))
            assertEquals(1, confirmed)
            uiState = uiState.copy(states = mapOf(profile.id to repairable.copy(journalRepairAvailable = false)))
            pump()
            assertTrue(!textEnabled(R.string.common_confirm))
            assertTrue(allText().contains(text(R.string.container_chroot_summary_stale)))
            uiState = uiState.copy(confirmation = confirmation.copy(action = ChrootInstanceAction.RECOVER_REMOVE),
                states = mapOf(profile.id to repairable))
            pump()
            assertTrue(textEnabled(R.string.common_confirm))
            assertTrue(allText().contains(text(R.string.container_chroot_recover_remove_notice)))
            assertTrue(!allText().contains(text(R.string.container_chroot_summary_stale)))
            val healthyRecords = repairable.copy(journalHealthy = true, journalRepairAvailable = false)
            uiState = uiState.copy(states = mapOf(profile.id to healthyRecords))
            pump()
            assertTrue(textEnabled(R.string.common_confirm))
            for (blocked in listOf(repairable.copy(journalRepairAvailable = false), repairable.copy(rootEnabled = false),
                repairable.copy(busy = true), repairable.copy(instance = null),
                repairable.copy(instance = instance.copy(installGeneration = instance.installGeneration + 1)))) {
                uiState = uiState.copy(states = mapOf(profile.id to blocked))
                pump()
                assertTrue(!textEnabled(R.string.common_confirm))
            }
            for (changedRoot in listOf(root.copy(enabled = false), root.copy(generation = root.generation + 1),
                root.copy(authorityId = "replacement-authority"))) {
                uiState = uiState.copy(states = mapOf(profile.id to repairable), rootState = changedRoot)
                pump()
                assertTrue(!textEnabled(R.string.common_confirm))
            }
            uiState = uiState.copy(rootState = root, workingProfileId = "another-profile")
            pump()
            assertTrue(!textEnabled(R.string.common_confirm))
            assertEquals(1, confirmed)
        }
    }

    @Test(timeout = 30_000)
    fun uninstalledRemovalSummaryDescribesConfigurationOnlyAndRepairDoesNotRequireAnInstance() {
        val healthy = LocalRuntimeState(null, true, true, false, 0, 0, false)
        val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 1, 41, Any())
        val confirmation = ChrootConfirmation.Instance(ChrootInstanceAction.REMOVE, profile, root, snapshot)
        var uiState by mutableStateOf(ChrootSettingsUiState(states = mapOf(profile.id to healthy),
            rootState = root, confirmation = confirmation))
        var confirmed = 0
        render(content = { ChrootSettingsDialogs(uiState, { confirmed++ }, {}, {}) }) {
            assertTrue(allText().contains(text(R.string.container_chroot_remove_configuration)))
            assertTrue(allText().contains(text(R.string.container_chroot_remove_configuration_notice)))
            assertTrue(!allText().contains(text(R.string.container_chroot_remove_notice)))
            assertTrue(!allText().contains(text(R.string.container_chroot_summary_instance)))
            assertTrue(textEnabled(R.string.common_confirm))
            uiState = uiState.copy(states = mapOf(profile.id to healthy.copy(recoveryRequired = true)))
            pump()
            assertTrue(!textEnabled(R.string.common_confirm))
            uiState = uiState.copy(confirmation = confirmation.copy(action = ChrootInstanceAction.REPAIR_JOURNAL),
                states = mapOf(profile.id to healthy.copy(journalHealthy = false, recoveryRequired = true, journalRepairAvailable = true)))
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_repair_journal_notice)))
            assertTrue(textEnabled(R.string.common_confirm))
            uiState = uiState.copy(confirmation = confirmation.copy(action = ChrootInstanceAction.RECOVER_REMOVE))
            pump()
            assertTrue(!textEnabled(R.string.common_confirm))
            assertEquals(0, confirmed)
        }
    }

    @Test(timeout = 30_000)
    fun pendingRemovalIsInformationAndKeepsAnExplicitRetryEntry() {
        val installed = profile.copy(chrootInstance = instance)
        val pending = LocalRuntimeState(instance, true, true, true, 0, 0, false, removalPending = true)
        var uiState by mutableStateOf(ChrootSettingsUiState(states = mapOf(profile.id to pending), rootState = root,
            info = R.string.container_chroot_removal_pending))
        var dismissals = 0
        val actions = mutableListOf<ChrootInstanceAction>()
        render(content = {
            Column {
                ChrootProfileSettings(installed, uiState, { _, action -> actions += action }, {})
                ChrootSettingsDialogs(uiState, {}, {}, { dismissals++; uiState = uiState.copy(info = null) })
            }
        }) {
            assertTrue(allText().contains(text(R.string.container_chroot_info_title)))
            assertTrue(allText().contains(text(R.string.container_chroot_removal_pending)))
            assertTrue(allText().contains(text(R.string.container_chroot_status_removal_pending)))
            assertTrue(!allText().contains(text(R.string.container_chroot_error_title)))
            assertTrue(actions.isEmpty())
            clickText(text(R.string.common_confirm))
            pump()
            assertEquals(1, dismissals)
            assertTrue(!allText().contains(text(R.string.container_chroot_info_title)))
            assertTrue(allText().contains(text(R.string.container_chroot_removal_pending)))
            assertTrue(!textEnabled(R.string.container_chroot_remove))
            assertTrue(textEnabled(R.string.container_chroot_recover_remove))
            clickText(text(R.string.container_chroot_recover_remove))
            pump()
            assertEquals(listOf(ChrootInstanceAction.RECOVER_REMOVE), actions)
        }
    }

    @Test(timeout = 30_000)
    fun activeRemovalOccupancyShowsRetryGuidanceWithoutClaimingRemoval() {
        val uiState = ChrootSettingsUiState(error = R.string.container_chroot_removal_busy)
        render(content = { ChrootSettingsDialogs(uiState, {}, {}, {}) }) {
            assertTrue(allText().contains(text(R.string.container_chroot_error_title)))
            assertTrue(allText().contains(text(R.string.container_chroot_removal_busy)))
            assertTrue(!allText().contains(text(R.string.container_chroot_info_title)))
        }
    }

    @Test(timeout = 30_000)
    fun recoveryWorkShowsProgressInsteadOfAnIdleUnknownOrPendingRemovalStatus() {
        val installed = profile.copy(chrootInstance = instance)
        val state = LocalRuntimeState(instance, true, false, true, 0, 0, true,
            journalRepairAvailable = true, removalPending = true)
        var action by mutableStateOf(ChrootInstanceAction.REPAIR_JOURNAL)
        render(content = {
            ChrootProfileSettings(installed, ChrootSettingsUiState(states = mapOf(profile.id to state), rootState = root,
                workingProfileId = profile.id, workingAction = action), { _, _ -> }, {})
        }) {
            assertTrue(allText().contains(text(R.string.container_chroot_status_repairing)))
            assertTrue(!allText().contains(text(R.string.container_chroot_status_unknown)))
            assertTrue(!allText().contains(text(R.string.container_chroot_status_removal_pending)))
            assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
            action = ChrootInstanceAction.RECOVER_REMOVE
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_status_cleanup)))
            assertTrue(!allText().contains(text(R.string.container_chroot_status_unknown)))
        }
    }

    @Test(timeout = 30_000)
    fun obsoleteAuthorizationConfirmationIsNotRenderedOrDispatched() {
        val request = LocalRuntimeAuthorization(RuntimeOwner(RuntimeOwnerKind.AGENT, "obsolete-owner"), profile.id,
            41, instance, root, "/actual/workspace", "/private/home", "old-plan", emptyList(), "old-source", Any())
        var confirmed = 0
        render(content = {
            ChrootSettingsDialogs(ChrootSettingsUiState(rootState = root,
                confirmation = ChrootConfirmation.Authorization(request)), { confirmed++ }, {}, {})
        }) {
            assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
            assertEquals(0, confirmed)
        }
    }

    @Test(timeout = 30_000)
    fun addConsentExplainsDiskWriteAndRootPrerequisiteButRecompositionDoesNotCreate() {
        var currentRoot by mutableStateOf(root.copy(enabled = false))
        render(content = { Column { ContainerChrootAddNotice(currentRoot) } }) {
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_notice)))
            assertTrue(allText().contains(text(R.string.container_chroot_root_disabled)))
            assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
            currentRoot = root
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_notice)))
            assertTrue(!allText().contains(text(R.string.container_chroot_root_disabled)))
            assertTrue(nodes().none { it.config.contains(SemanticsActions.OnClick) })
        }
    }

    @Test(timeout = 30_000)
    fun sharedAddFeedbackClaimsSavedOnlyAfterUpsertAndCreatedOnlyAfterRuntimeCompletion() {
        var state by mutableStateOf(ContainerProfileAdditionUiState(profile.id, profile.name, true,
            inProgress = false, error = R.string.container_profile_add_failed))
        var cancellations = 0
        var dismissals = 0
        render(content = {
            ContainerAdditionFeedback(state, { cancellations++ }, { dismissals++ })
        }) {
            assertTrue(allText().contains(text(R.string.container_profile_add_failed)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_failed)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_success)))
            state = state.copy(saved = true, error = R.string.container_chroot_root_disabled)
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_failed)))
            assertTrue(allText().contains(text(R.string.container_chroot_root_disabled)))
            state = state.copy(inProgress = true, creatingInstance = true, error = null)
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_status_installing)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_success)))
            clickText(text(R.string.common_cancel))
            pump()
            assertEquals(1, cancellations)
            state = state.copy(inProgress = false, creatingInstance = false, completed = true)
            pump()
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_success)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_failed)))
            clickText(text(R.string.common_close))
            pump()
            assertEquals(1, dismissals)
        }
    }

    @Test(timeout = 30_000)
    fun ordinaryProotAndCancelledFeedbackNeverClaimChrootCreationSucceeded() {
        var state by mutableStateOf(ContainerProfileAdditionUiState(profile.id, profile.name, false,
            saved = true, inProgress = false, completed = true))
        render(content = { ContainerAdditionFeedback(state, {}, {}) }) {
            assertTrue(allText().contains(text(R.string.container_profile_added)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_success)))
            state = state.copy(createsInstance = true, completed = false, cancelled = true)
            pump()
            assertTrue(allText().contains(text(R.string.container_profile_add_cancelled)))
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_failed)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_success)))
        }
    }

    @Test(timeout = 30_000)
    fun containerListAndRestoredUninstalledProfileRecompositionNeverSubmitAddOrEdit() {
        var savedProfile by mutableStateOf(profile)
        var adds = 0
        var edits = 0
        val available = LocalRuntimeState(null, true, true, false, 0, 0, false)
        render(content = {
            ContainerSection(
                profiles = listOf(savedProfile), activeProfileId = "different-profile",
                onSelect = {}, onSaveCustom = { _, _, _ -> adds++ },
                onEditCustom = { _, _ -> edits++ }, onDeleteProfile = {},
                chrootUiState = ChrootSettingsUiState(states = mapOf(profile.id to available), rootState = root)
            )
        }) {
            assertEquals(0, adds)
            assertEquals(0, edits)
            savedProfile = savedProfile.copy(name = "Restored CHROOT")
            pump()
            assertEquals(0, adds)
            assertEquals(0, edits)
            assertTrue(allText().contains(text(R.string.container_chroot_status_not_installed)))
        }
    }

    @Test(timeout = 30_000)
    fun downloadPageShowsSavedButNotCreatedFailureWithoutReplayingImportOnRecomposition() {
        var imports = 0
        var state by mutableStateOf(ChrootSettingsUiState(rootState = root,
            addition = ContainerProfileAdditionUiState(profile.id, profile.name, true,
                saved = true, inProgress = false, error = R.string.container_chroot_operation_failed)))
        val selection = ListSelectionState()
        render(content = {
            ContainerImageDownloadSection(
                catalog = emptyList(), imageVersions = emptyMap(), state = ContainerImageDownloadUiState.Idle,
                downloadedImages = emptyMap(), sourceUnavailableIds = emptySet(), selectedSourceName = "test-source",
                selection = selection, onDownload = {}, onCancel = {}, onImport = { _, _, _ -> imports++ },
                onDelete = {}, onBatchDelete = {}, chrootUiState = state
            )
        }) {
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_failed)))
            assertTrue(allText().contains(text(R.string.container_chroot_operation_failed)))
            assertTrue(!allText().contains(text(R.string.container_chroot_add_create_success)))
            state = state.copy(rootState = root.copy(generation = root.generation + 1))
            pump()
            assertEquals(0, imports)
            assertTrue(allText().contains(text(R.string.container_chroot_add_create_failed)))
        }
    }

    private class Ui(val view: ComposeView, val pump: () -> Unit) {
        fun nodes(): List<SemanticsNode> = listOfNotNull(findRoot(view), dialogView()?.let(::findRoot))
            .distinct().flatMap { it.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true) }
        fun text(resource: Int): String = view.context.getString(resource)
        fun allText(): String = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString("\n") { it.text }
        fun textEnabled(resource: Int): Boolean = nodes().any {
            it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { label -> label.text == text(resource) } == true
        }
        fun clickText(value: String) {
            val target = nodes().single {
                it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) &&
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
            }
            assertTrue(requireNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        }
        fun clickDescription(resource: Int) {
            val target = nodes().single {
                it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text(resource)) == true
            }
            assertTrue(requireNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        }
    }

    companion object {
        private fun dialogView(): View? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index ->
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }
    }

    private fun render(content: @Composable () -> Unit, inspect: Ui.() -> Unit) = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val view = ComposeView(controller.get())
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        val looper = shadowOf(Looper.getMainLooper())
        var time = 0L
        val ui = Ui(view) {
            repeat(16) {
                looper.idleFor(Duration.ofMillis(16))
                testScheduler.advanceTimeBy(16)
                Snapshot.sendApplyNotifications()
                runCurrent()
                time += 16_000_000L
                if (clock.hasAwaiters) clock.sendFrame(time)
                runCurrent()
                Snapshot.sendApplyNotifications()
                view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                findRoot(view)?.measureAndLayoutForTest()
                dialogView()?.let { dialog ->
                    dialog.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                    dialog.layout(0, 0, dialog.measuredWidth, dialog.measuredHeight)
                    findRoot(dialog)?.measureAndLayoutForTest()
                }
            }
        }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) { AIEditorTheme(darkTheme = false, content = content) }
            }
            controller.get().setContentView(view)
            ui.pump()
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }
}
