package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModelStore
import com.aicode.R
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeMount
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeState
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeUserActions
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val runtime = mockk<LocalRuntimeUserActions>()
    private val containers = mockk<ContainerSettingsRepository>()
    private val roots = mockk<RootAccessSettingsRepository>()
    private val changes = MutableStateFlow(0L)
    private val pending = MutableStateFlow<List<LocalRuntimeAuthorization>>(emptyList())
    private val root = MutableStateFlow(RootAccessState(true, 7, "test-authority"))
    private val instance = ChrootInstance("0123456789abcdef01234567", 5, 19, 1234)
    private val profile = ContainerProfile(
        id = "chroot-profile", name = "CHROOT", rootfsSource = RootfsSource.Asset("alpine-rootfs.bin"),
        shellPath = "/bin/sh", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = instance
    )
    private val profiles = MutableStateFlow(listOf(profile))
    private val repositoryIdentity = Any()
    private var snapshot = snapshot(profile)
    private var runtimeState = LocalRuntimeState(instance, true, true, false, 0, 0, false)
    private val store = ViewModelStore()
    private lateinit var viewModel: ChrootSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { runtime.changes } returns changes
        every { runtime.pendingAuthorizations } returns pending
        every { runtime.state(any()) } answers { runtimeState }
        every { containers.customProfilesFlow } returns profiles
        coEvery { containers.captureProfileSnapshot() } answers { snapshot }
        every { containers.checkProfileSnapshot(any()) } just Runs
        every { roots.state } returns root
        coEvery { roots.snapshot() } answers { root.value }
        viewModel = ChrootSettingsViewModel(runtime, containers, roots)
        store.put("chroot", viewModel)
        settle()
    }

    @After
    fun tearDown() {
        store.clear()
        settle()
        Dispatchers.resetMain()
    }

    @Test
    fun observingSettingsDoesNotCreateAuthorizeOrStopAnything() {
        assertEquals(runtimeState, viewModel.uiState.value.states[profile.id])
        root.value = root.value.copy(generation = 8)
        changes.value++
        settle()
        assertEquals(root.value, viewModel.uiState.value.rootState)
        noLifecycleCalls()
    }

    @Test
    fun legacyPendingTokensAreNeverExposedPreparedOrActivatedBySettings() {
        val request = authorization()
        val fabricated = authorization(scope = "not-a-request")
        pending.value = listOf(request)
        changes.value++
        settle()
        assertTrue(viewModel.uiState.value.pendingAuthorizations.isEmpty())
        viewModel.requestAuthorization(fabricated)
        viewModel.requestAuthorization(request)
        settle()
        assertNull(viewModel.uiState.value.confirmation)
        viewModel.confirm()
        settle()
        coVerify(exactly = 0) { runtime.prepareAuthorization(any()) }
        noLifecycleCalls()
    }

    @Test
    fun stopAndRemovalStillRequireTheReviewedRootGeneration() {
        for (action in listOf(ChrootInstanceAction.STOP, ChrootInstanceAction.REMOVE)) {
            viewModel.requestInstanceAction(profile.id, action)
            settle()
            assertNotNull(viewModel.uiState.value.confirmation)
            root.value = root.value.copy(generation = root.value.generation + 1)
            viewModel.confirm()
            settle()
            noLifecycleCalls()
            assertNotNull(viewModel.uiState.value.error)
        }
        coEvery { runtime.removeInstanceFromUser(profile, root.value) } returns Unit
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.REMOVE)
        settle()
        viewModel.confirm()
        settle()
        coVerify(exactly = 1) { runtime.removeInstanceFromUser(profile, root.value) }
        coVerify(exactly = 0) { runtime.authorizeFromUser(any()) }
        coVerify(exactly = 0) { containers.upsertCustomProfile(any()) }
    }

    @Test
    fun cancellingStopOrRemovalNeverDispatchesTheInstanceOperation() {
        for (action in listOf(ChrootInstanceAction.STOP, ChrootInstanceAction.REMOVE)) {
            viewModel.requestInstanceAction(profile.id, action)
            settle()
            assertNotNull(viewModel.uiState.value.confirmation)
            noLifecycleCalls()
            viewModel.cancelConfirmation()
            viewModel.confirm()
            settle()
            assertNull(viewModel.uiState.value.confirmation)
            noLifecycleCalls()
        }
    }

    @Test
    fun unknownJournalOrRecoveryStateRejectsEveryDestructiveActionBeforeRuntimeCalls() {
        for (state in listOf(runtimeState.copy(journalHealthy = false), runtimeState.copy(recoveryRequired = true), runtimeState.copy(busy = true))) {
            runtimeState = state
            changes.value++
            settle()
            ChrootInstanceAction.entries.forEach { action ->
                viewModel.requestInstanceAction(profile.id, action)
                settle()
                assertNull(viewModel.uiState.value.confirmation)
                assertEquals(R.string.container_chroot_operation_failed, viewModel.uiState.value.error)
            }
        }
        noLifecycleCalls()
    }

    @Test
    fun createUsesTheConfirmedSnapshotAndRootButNeverDoublePublishesTheProfile() {
        val uninstalled = profile.copy(chrootInstance = null)
        snapshot = snapshot(uninstalled)
        profiles.value = listOf(uninstalled)
        runtimeState = runtimeState.copy(instance = null)
        val finish = CompletableDeferred<Unit>()
        coEvery { runtime.createInstanceFromUser(uninstalled, root.value) } coAnswers { finish.await(); instance }
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.CREATE)
        settle()
        assertNotNull(viewModel.uiState.value.confirmation)
        coVerify(exactly = 0) { runtime.createInstanceFromUser(any(), any()) }
        viewModel.confirm()
        viewModel.confirm()
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.REMOVE)
        settle()
        coVerify(exactly = 1) { runtime.createInstanceFromUser(uninstalled, root.value) }
        assertEquals(profile.id, viewModel.uiState.value.workingProfileId)
        finish.complete(Unit)
        settle()
        assertNull(viewModel.uiState.value.workingProfileId)
        coVerify(exactly = 0) { containers.upsertCustomProfile(any()) }
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
    }

    @Test
    fun changedProfileSnapshotBlocksAnAlreadyReviewedRemoval() {
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.REMOVE)
        settle()
        every { containers.checkProfileSnapshot(any()) } throws CancellationException("changed")
        viewModel.confirm()
        settle()
        coVerify(exactly = 0) { runtime.removeInstanceFromUser(any(), any()) }
        assertEquals(R.string.container_chroot_summary_stale, viewModel.uiState.value.error)
    }

    @Test
    fun removalIsOwnedByRuntimeAndStopUnknownNeverReportsSuccessfulCleanup() {
        coEvery { runtime.removeInstanceFromUser(profile, root.value) } throws IOException("credential=not-for-ui")
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.REMOVE)
        settle()
        viewModel.confirm()
        settle()
        coVerify(exactly = 1) { runtime.removeInstanceFromUser(profile, root.value) }
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
        assertEquals(R.string.container_chroot_operation_failed, viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.error.toString().contains("not-for-ui"))

        coEvery { runtime.stopInstance(instance) } returns LocalRuntimeCleanup.UNKNOWN
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.STOP)
        settle()
        viewModel.confirm()
        settle()
        coVerify(exactly = 1) { runtime.stopInstance(instance) }
        assertEquals(R.string.container_chroot_operation_failed, viewModel.uiState.value.error)
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
    }

    @Test
    fun manualAddAwaitsSaveAndDefaultThenCreatesTheFreshProfileExactlyOnceWithoutDoublePublish() {
        acceptAdditions()
        val added = freshAddition()
        val saved = CompletableDeferred<Unit>()
        val created = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coEvery { containers.upsertCustomProfile(any()) } coAnswers {
            events += "save-start"
            saved.await()
            publishAdded(firstArg())
            events += "saved"
        }
        coEvery { containers.setDefaultContainerId(added.id) } coAnswers {
            assertTrue(viewModel.uiState.value.addition!!.saved)
            events += "default"
            snapshot = ContainerProfileSnapshot(snapshot.selectedProfileId, added.id, snapshot.profiles,
                snapshot.selectionRevision, snapshot.configurationRevision + 1, repositoryIdentity)
        }
        coEvery { runtime.createInstanceFromUser(any(), any()) } coAnswers {
            val exact = firstArg<ContainerProfile>()
            assertEquals(added, exact)
            assertEquals(root.value, secondArg<RootAccessState>())
            assertEquals(added.id, snapshot.defaultContainerId)
            assertTrue(snapshot.profiles.contains(exact))
            events += "create"
            created.await()
            instance
        }
        val request = ContainerProfileAddition(added, setAsDefault = true)
        viewModel.addProfileFromUser(request, root.value)
        settle()
        assertEquals(listOf("save-start"), events)
        viewModel.addProfileFromUser(request, root.value)
        viewModel.addProfileFromUser(ContainerProfileAddition(freshAddition()), root.value)
        viewModel.requestInstanceAction(profile.id, ChrootInstanceAction.REMOVE)
        assertEquals(added.id, viewModel.uiState.value.addition!!.profileId)
        assertFalse(viewModel.uiState.value.addition!!.saved)
        saved.complete(Unit)
        settle()
        assertEquals(listOf("save-start", "saved", "default", "create"), events)
        assertNull(viewModel.uiState.value.confirmation) // Add consent does not open a second CREATE dialog.
        created.complete(Unit)
        settle()
        assertTrue(viewModel.uiState.value.addition!!.completed)
        coVerify(exactly = 1) { containers.upsertCustomProfile(any()) }
        coVerify(exactly = 1) { runtime.createInstanceFromUser(added, root.value) }
        coVerify(exactly = 0) { containers.publishChrootInstance(any(), any(), any()) }
        coVerify(exactly = 0) { containers.setActiveProfile(any()) }
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
        // A restored/replayed request cannot overwrite or recreate the already added UUID.
        viewModel.addProfileFromUser(request, root.value)
        settle()
        coVerify(exactly = 1) { containers.upsertCustomProfile(any()) }
        coVerify(exactly = 1) { runtime.createInstanceFromUser(any(), any()) }
    }

    @Test
    fun downloadedAddUsesItsExactUriAndImportedRecordIsNotEvidenceOfCreation() {
        acceptAdditions()
        val added = freshAddition()
        val record = DownloadedImageRecord("ubuntu", (added.rootfsSource as RootfsSource.LocalFile).uri, "24.04.3")
        val downloads = MutableStateFlow(listOf(record))
        every { containers.downloadedImagesFlow } returns downloads
        coEvery { containers.upsertDownloadedImage(any()) } coAnswers {
            downloads.value = listOf(firstArg())
        }
        coEvery { runtime.createInstanceFromUser(added, root.value) } throws IOException("actual Root unavailable")
        viewModel.addProfileFromUser(ContainerProfileAddition(added, downloadedImage = record), root.value)
        settle()
        assertTrue(downloads.value.single().installed)
        assertTrue(viewModel.uiState.value.addition!!.saved)
        assertFalse(viewModel.uiState.value.addition!!.completed)
        assertNotNull(viewModel.uiState.value.addition!!.error)
        assertEquals(added, snapshot.profiles.single { it.id == added.id })
        coVerify(exactly = 1) { runtime.createInstanceFromUser(added, root.value) }
        coVerify(exactly = 0) { containers.publishChrootInstance(any(), any(), any()) }
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
    }

    @Test
    fun downloadedChrootSuccessAlsoCreatesOnlyOnceAndNeverChangesItsSource() {
        acceptAdditions()
        val added = freshAddition()
        val record = DownloadedImageRecord("debian", (added.rootfsSource as RootfsSource.LocalFile).uri, "12.15")
        every { containers.downloadedImagesFlow } returns MutableStateFlow(listOf(record))
        coEvery { runtime.createInstanceFromUser(added, root.value) } returns instance
        val request = ContainerProfileAddition(added, downloadedImage = record)
        viewModel.addProfileFromUser(request, root.value)
        viewModel.addProfileFromUser(request, root.value)
        settle()
        assertTrue(viewModel.uiState.value.addition!!.completed)
        coVerify(exactly = 1) { containers.upsertCustomProfile(added) }
        coVerify(exactly = 1) { containers.upsertDownloadedImage(record.copy(installed = true)) }
        coVerify(exactly = 1) { runtime.createInstanceFromUser(added, root.value) }
        coVerify(exactly = 0) { containers.publishChrootInstance(any(), any(), any()) }
    }

    @Test
    fun ordinaryProotAddSavesOnlyWithoutReadingRootOrCreating() {
        acceptAdditions()
        val added = freshAddition().copy(localRuntime = LocalContainerRuntime.PROOT)
        viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
        settle()
        assertTrue(viewModel.uiState.value.addition!!.completed)
        coVerify(exactly = 1) { containers.upsertCustomProfile(added) }
        coVerify(exactly = 0) { roots.snapshot() }
        verify(exactly = 0) { runtime.state(added) }
        coVerify(exactly = 0) { containers.setDefaultContainerId(any()) }
        noLifecycleCalls()
    }

    @Test
    fun disabledRootStillSavesUninstalledConfigurationWithoutCreatingOrEnablingRoot() {
        acceptAdditions()
        root.value = root.value.copy(enabled = false)
        val added = freshAddition()
        viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
        settle()
        assertTrue(viewModel.uiState.value.addition!!.saved)
        assertFalse(viewModel.uiState.value.addition!!.completed)
        assertEquals(R.string.container_chroot_root_disabled, viewModel.uiState.value.addition!!.error)
        assertNull(snapshot.profiles.single { it.id == added.id }.chrootInstance)
        coVerify(exactly = 1) { containers.upsertCustomProfile(added) }
        coVerify(exactly = 0) { roots.setEnabled(any()) }
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
        noLifecycleCalls()
    }

    @Test
    fun rootGenerationChangeDuringSaveAndAChangedClickConsentBothBlockCreation() {
        acceptAdditions()
        val added = freshAddition()
        coEvery { containers.upsertCustomProfile(any()) } coAnswers {
            publishAdded(firstArg())
            root.value = root.value.copy(generation = root.value.generation + 1)
        }
        viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
        settle()
        assertTrue(viewModel.uiState.value.addition!!.saved)
        assertEquals(R.string.container_chroot_summary_stale, viewModel.uiState.value.addition!!.error)
        val oldConsent = root.value
        root.value = root.value.copy(authorityId = "replacement-authority")
        val other = freshAddition()
        viewModel.addProfileFromUser(ContainerProfileAddition(other), oldConsent)
        settle()
        assertFalse(viewModel.uiState.value.addition!!.saved)
        coVerify(exactly = 1) { containers.upsertCustomProfile(any()) }
        noLifecycleCalls()
    }

    @Test
    fun unhealthyBusyRecoveryOrChangedConfigurationRetainsSavedProfileWithoutCreating() {
        acceptAdditions()
        val healthy = runtimeState
        for (unavailable in listOf(healthy.copy(rootEnabled = false), healthy.copy(journalHealthy = false),
            healthy.copy(recoveryRequired = true), healthy.copy(busy = true), healthy.copy(instance = instance))) {
            runtimeState = unavailable
            val added = freshAddition()
            viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
            settle()
            assertTrue(viewModel.uiState.value.addition!!.saved)
            assertNotNull(viewModel.uiState.value.addition!!.error)
            assertTrue(snapshot.profiles.any { it.id == added.id })
        }
        runtimeState = healthy
        val changed = freshAddition()
        coEvery { containers.upsertCustomProfile(any()) } coAnswers {
            publishAdded(firstArg<ContainerProfile>().copy(rootfsSource = RootfsSource.LocalFile("file:///private/changed.tar.gz")))
        }
        viewModel.addProfileFromUser(ContainerProfileAddition(changed), root.value)
        settle()
        assertEquals(R.string.container_chroot_summary_stale, viewModel.uiState.value.addition!!.error)
        noLifecycleCalls()
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
    }

    @Test
    fun cancellationDuringNonCancellableSaveNeverDispatchesLateCreationOrDeletesTheSavedProfile() {
        acceptAdditions()
        val added = freshAddition()
        val finishSave = CompletableDeferred<Unit>()
        coEvery { containers.upsertCustomProfile(any()) } coAnswers {
            withContext(NonCancellable) {
                finishSave.await()
                publishAdded(firstArg())
            }
        }
        viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
        settle()
        viewModel.cancelAddition()
        finishSave.complete(Unit)
        settle()
        assertFalse(viewModel.uiState.value.addition!!.inProgress)
        assertTrue(viewModel.uiState.value.addition!!.cancelled)
        assertTrue(snapshot.profiles.any { it.id == added.id })
        noLifecycleCalls()
        coVerify(exactly = 0) { containers.deleteCustomProfile(any()) }
    }

    @Test
    fun upsertFailureDoesNotClaimTheProfileWasSavedAndMutableRequestFieldsAreFrozen() {
        acceptAdditions()
        val env = mutableMapOf("ORIGINAL" to "value")
        val added = freshAddition().copy(env = env)
        val finishSave = CompletableDeferred<Unit>()
        coEvery { containers.upsertCustomProfile(any()) } coAnswers {
            finishSave.await()
            assertEquals(mapOf("ORIGINAL" to "value"), firstArg<ContainerProfile>().env)
            throw IOException("save failed")
        }
        viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
        env["ORIGINAL"] = "changed after click"
        finishSave.complete(Unit)
        settle()
        assertFalse(viewModel.uiState.value.addition!!.saved)
        assertEquals(R.string.container_profile_add_failed, viewModel.uiState.value.addition!!.error)
        noLifecycleCalls()
    }

    @Test
    fun staleDownloadIsRejectedBeforeSavingAndObservingOldUninstalledProfilesNeverCreates() {
        acceptAdditions()
        val added = freshAddition()
        val record = DownloadedImageRecord("ubuntu", (added.rootfsSource as RootfsSource.LocalFile).uri, "24.04.3")
        every { containers.downloadedImagesFlow } returns MutableStateFlow(listOf(record.copy(fileUri = "file:///private/replaced.tar.gz")))
        viewModel.addProfileFromUser(ContainerProfileAddition(added, downloadedImage = record), root.value)
        settle()
        assertFalse(viewModel.uiState.value.addition!!.saved)
        coVerify(exactly = 0) { containers.upsertCustomProfile(any()) }
        publishAdded(added)
        changes.value++
        root.value = root.value.copy(generation = root.value.generation + 1)
        settle()
        noLifecycleCalls()
    }

    @Test
    fun completeConfigurationSnapshotIsCheckedAfterSaveAndBeforeCreate() {
        acceptAdditions()
        val added = freshAddition()
        every { containers.checkProfileSnapshot(any()) } answers {
            val checked = firstArg<ContainerProfileSnapshot>()
            if (checked.profiles.any { it.id == added.id }) throw CancellationException("other configuration changed")
        }
        viewModel.addProfileFromUser(ContainerProfileAddition(added), root.value)
        settle()
        assertTrue(viewModel.uiState.value.addition!!.saved)
        assertEquals(R.string.container_chroot_summary_stale, viewModel.uiState.value.addition!!.error)
        noLifecycleCalls()
    }

    @Test
    fun typedAddRequestCannotOverwriteExistingIdentityOrReuseBuiltinOrInstalledProfile() {
        acceptAdditions()
        for (forged in listOf(profile.copy(chrootInstance = null), freshAddition().copy(chrootInstance = instance),
            freshAddition().copy(isBuiltin = true), freshAddition().copy(extraArgs = listOf("--old-proot-flag")))) {
            viewModel.clearAdditionResult()
            viewModel.addProfileFromUser(ContainerProfileAddition(forged), root.value)
            settle()
            assertFalse(viewModel.uiState.value.addition!!.saved)
            assertNotNull(viewModel.uiState.value.addition!!.error)
        }
        coVerify(exactly = 0) { containers.upsertCustomProfile(any()) }
        noLifecycleCalls()
    }

    private fun acceptAdditions() {
        runtimeState = runtimeState.copy(instance = null)
        coEvery { containers.upsertCustomProfile(any()) } coAnswers { publishAdded(firstArg()) }
        coEvery { containers.setDefaultContainerId(any()) } just Runs
        coEvery { containers.upsertDownloadedImage(any()) } just Runs
    }

    private fun freshAddition() = newContainerProfile(profile.copy(
        chrootInstance = null, rootfsSource = RootfsSource.LocalFile("file:///private/rootfs_images/selected.tar.xz")
    ))

    private fun publishAdded(added: ContainerProfile) {
        val next = snapshot.profiles.filterNot { it.id == added.id } + added
        snapshot = ContainerProfileSnapshot(snapshot.selectedProfileId, snapshot.defaultContainerId, next,
            snapshot.selectionRevision, snapshot.configurationRevision + 1, repositoryIdentity)
        profiles.value = next
    }

    private fun noLifecycleCalls() {
        coVerify(exactly = 0) { runtime.authorizeFromUser(any()) }
        coVerify(exactly = 0) { runtime.createInstanceFromUser(any(), any()) }
        coVerify(exactly = 0) { runtime.stopInstance(any()) }
        coVerify(exactly = 0) { runtime.removeInstanceFromUser(any(), any()) }
    }

    private fun authorization(scope: String = "existing-session") = LocalRuntimeAuthorization(
        owner = RuntimeOwner(RuntimeOwnerKind.AGENT, scope), profileId = profile.id, configurationRevision = 4,
        instance = instance, rootState = root.value, workspacePath = "/actual/workspace", homePath = "/private/home",
        digest = "immutable-plan", mounts = listOf(LocalRuntimeMount("/actual/workspace", "/workspace", false)),
        sourceFingerprint = "actual-session-source", binding = Any()
    )

    private fun snapshot(profile: ContainerProfile) = ContainerProfileSnapshot(
        profile.id, ContainerProfile.BUILTIN_ID, listOf(profile), 1, 4, repositoryIdentity
    )

    private fun settle() = dispatcher.scheduler.runCurrent()
}
