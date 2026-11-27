package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aicode.R
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeRemoval
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeState
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeUserActions
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal enum class ChrootInstanceAction { CREATE, STOP, REMOVE, REPAIR_JOURNAL, RECOVER_REMOVE }

/** Shared by the list, edit sheet and both stages of explicit user confirmation. */
internal fun ChrootInstanceAction.isAvailable(
    profile: ContainerProfile,
    state: LocalRuntimeState?,
    root: RootAccessState
): Boolean {
    if (!profile.isChroot || state == null || state.busy || state.instance != profile.chrootInstance) return false
    val rootAvailable = root.enabled && state.rootEnabled
    val healthy = state.journalHealthy && !state.recoveryRequired && !state.removalRebootRequired
    return when (this) {
        ChrootInstanceAction.CREATE -> rootAvailable && healthy && state.instance == null
        ChrootInstanceAction.STOP -> healthy && state.instance != null
        ChrootInstanceAction.REMOVE -> rootAvailable && healthy
        ChrootInstanceAction.REPAIR_JOURNAL -> rootAvailable && state.journalRepairAvailable
        ChrootInstanceAction.RECOVER_REMOVE -> rootAvailable && state.instance != null &&
            (state.journalHealthy || state.journalRepairAvailable)
    }
}

internal fun chrootRemovalAction(state: LocalRuntimeState?): ChrootInstanceAction =
    if (state?.instance != null && (state.recoveryRequired || !state.journalHealthy || state.removalRebootRequired)) {
        ChrootInstanceAction.RECOVER_REMOVE
    } else ChrootInstanceAction.REMOVE

internal sealed interface ChrootConfirmation {
    val rootState: RootAccessState

    data class Instance(
        val action: ChrootInstanceAction,
        val profile: ContainerProfile,
        override val rootState: RootAccessState,
        val snapshot: ContainerProfileSnapshot
    ) : ChrootConfirmation

    data class Authorization(val authorization: LocalRuntimeAuthorization) : ChrootConfirmation {
        override val rootState: RootAccessState get() = authorization.rootState
    }
}

internal data class ChrootSettingsUiState(
    val states: Map<String, LocalRuntimeState> = emptyMap(),
    val rootState: RootAccessState = RootAccessState(),
    val pendingAuthorizations: List<LocalRuntimeAuthorization> = emptyList(),
    val confirmation: ChrootConfirmation? = null,
    val workingProfileId: String? = null,
    val workingAction: ChrootInstanceAction? = null,
    val error: Int? = null,
    val addition: ContainerProfileAdditionUiState? = null,
    val info: Int? = null
)

@HiltViewModel
internal class ChrootSettingsViewModel @Inject constructor(
    private val runtime: LocalRuntimeUserActions,
    private val containers: ContainerSettingsRepository,
    private val rootSettings: RootAccessSettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChrootSettingsUiState())
    val uiState = _uiState.asStateFlow()
    private var additionJob: Job? = null

    init {
        viewModelScope.launch {
            combine(containers.customProfilesFlow, runtime.changes, rootSettings.state) {
                    profiles, _, root -> profiles to root
            }.collect { (profiles, root) ->
                _uiState.update { state ->
                    state.copy(
                        states = profiles.filter { it.isChroot }.associate { it.id to runtime.state(it) },
                        rootState = root,
                        pendingAuthorizations = emptyList()
                    )
                }
            }
        }
    }

    /**
     * Add/Import is itself the reviewed creation consent. This is never invoked by a collector,
     * profile restoration or an edit. Existing manual lifecycle confirmations remain separate.
     */
    fun addProfileFromUser(addition: ContainerProfileAddition, rootAtConsent: RootAccessState) {
        if (_uiState.value.workingProfileId != null || _uiState.value.confirmation != null ||
            _uiState.value.addition?.profileId == addition.profile.id) return
        val request = addition.copy(profile = immutableContainerProfile(addition.profile))
        val profile = request.profile
        _uiState.update { it.copy(addition = ContainerProfileAdditionUiState(profile.id, profile.name, profile.isChroot)) }
        additionJob = runAction(
            profile.id,
            null,
            onFailure = { reason ->
                _uiState.update { state -> state.copy(addition = state.addition?.let {
                    it.copy(error = if (it.saved) reason else R.string.container_profile_add_failed)
                }) }
            },
            onCancelled = {
                _uiState.update { state -> state.copy(addition = state.addition?.copy(cancelled = true)) }
            }
        ) {
            // The typed request is not authority: independently reject existing identities/instances.
            val before = containers.captureProfileSnapshot()
            require(before.profiles.none { it.id == profile.id } && profile.chrootInstance == null)
            val expectedRoot = if (profile.isChroot) {
                rootSettings.snapshot().also {
                    if (it != rootAtConsent) throw CancellationException("Root authority changed after Add")
                }
            } else null
            val persisted = persistContainerProfileAddition(containers, request) {
                _uiState.update { state -> state.copy(addition = state.addition?.copy(saved = true)) }
            }
            currentCoroutineContext().ensureActive()
            if (profile.isChroot) {
                val snapshot = containers.captureProfileSnapshot()
                val fresh = snapshot.profiles.singleOrNull { it.id == profile.id }
                    ?: throw CancellationException("Added configuration disappeared")
                if (fresh != persisted) throw CancellationException("Added configuration changed")
                require(fresh.isChroot && !fresh.isBuiltin && fresh.chrootInstance == null)
                val root = checkNotNull(expectedRoot)
                if (rootSettings.snapshot() != root) throw CancellationException("Root authority changed during Add")
                if (!root.enabled) throw AddRootDisabled()
                val state = runtime.state(fresh)
                require(state.rootEnabled && state.journalHealthy && !state.recoveryRequired && !state.busy && state.instance == null) {
                    "CHROOT authority or cleanup state is unavailable"
                }
                containers.checkProfileSnapshot(snapshot)
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(workingAction = ChrootInstanceAction.CREATE,
                    addition = it.addition?.copy(creatingInstance = true)) }
                // Runtime verifies actual system Root and owns publication. Never upsert an instance here.
                runtime.createInstanceFromUser(fresh, root)
            }
            _uiState.update { it.copy(addition = it.addition?.copy(completed = true)) }
        }
    }

    fun rejectChangedAddition() {
        if (_uiState.value.workingProfileId != null) return
        _uiState.update { it.copy(addition = ContainerProfileAdditionUiState(
            "", "", false, inProgress = false, error = R.string.container_chroot_summary_stale
        )) }
    }

    /** Cancels only the explicit add job; a suspended save must not dispatch a late creation. */
    fun cancelAddition() {
        if (_uiState.value.addition?.inProgress == true) additionJob?.cancel()
    }

    fun clearAdditionResult() {
        if (_uiState.value.addition?.inProgress != true) _uiState.update { it.copy(addition = null) }
    }

    fun requestInstanceAction(profileId: String, action: ChrootInstanceAction) {
        runAction(profileId, null) {
            val snapshot = containers.captureProfileSnapshot()
            val profile = snapshot.profiles.firstOrNull { it.id == profileId && it.isChroot }
                ?: error("CHROOT profile is no longer available")
            val root = rootSettings.snapshot()
            val state = runtime.state(profile)
            val reviewedAction = if (action == ChrootInstanceAction.REMOVE) chrootRemovalAction(state) else action
            require(reviewedAction.isAvailable(profile, state, root)) { "CHROOT action is unavailable" }
            containers.checkProfileSnapshot(snapshot)
            _uiState.update { it.copy(confirmation = ChrootConfirmation.Instance(reviewedAction, profile, root, snapshot)) }
        }
    }

    // Kept for existing settings callbacks. Actual execution sources now bind under runtime policy.
    @Suppress("UNUSED_PARAMETER")
    fun requestAuthorization(request: LocalRuntimeAuthorization) = Unit

    fun cancelConfirmation() {
        if (_uiState.value.workingProfileId == null) _uiState.update { it.copy(confirmation = null) }
    }

    // Both result dialogs use the existing settings dismissal callback.
    fun clearError() = _uiState.update { it.copy(error = null, info = null) }

    fun confirm() {
        val confirmation = _uiState.value.confirmation ?: return
        if (_uiState.value.workingProfileId != null) return
        _uiState.update { it.copy(confirmation = null) }
        if (confirmation !is ChrootConfirmation.Instance) return
        runAction(confirmation.profile.id, confirmation.action) {
            require(rootSettings.snapshot() == confirmation.rootState) { "Root authority changed; review a new summary" }
            containers.checkProfileSnapshot(confirmation.snapshot)
            val profile = confirmation.profile
            val state = runtime.state(profile)
            require(confirmation.action.isAvailable(profile, state, confirmation.rootState)) { "CHROOT action is no longer available" }
            when (confirmation.action) {
                ChrootInstanceAction.CREATE -> runtime.createInstanceFromUser(profile, confirmation.rootState)
                ChrootInstanceAction.STOP -> {
                    require(runtime.stopInstance(checkNotNull(state.instance)) == LocalRuntimeCleanup.VERIFIED) {
                        "CHROOT stop did not verify complete cleanup"
                    }
                }
                ChrootInstanceAction.REMOVE -> runtime.removeInstanceFromUser(profile, confirmation.rootState)
                ChrootInstanceAction.REPAIR_JOURNAL -> runtime.repairJournalFromUser(profile, confirmation.rootState)
                ChrootInstanceAction.RECOVER_REMOVE -> {
                    when (runtime.recoverAndRemoveInstanceFromUser(profile, confirmation.rootState)) {
                        LocalRuntimeRemoval.REMOVED -> Unit
                        LocalRuntimeRemoval.REBOOT_REQUIRED -> _uiState.update {
                            it.copy(info = R.string.container_chroot_removal_reboot_required)
                        }
                    }
                }
            }
        }
    }

    private class AddRootDisabled : IllegalStateException()

    private fun runAction(
        profileId: String,
        action: ChrootInstanceAction?,
        onFailure: ((Int) -> Unit)? = null,
        onCancelled: (() -> Unit)? = null,
        block: suspend () -> Unit
    ): Job? {
        if (_uiState.value.workingProfileId != null) return null
        _uiState.update { it.copy(workingProfileId = profileId, workingAction = action, error = null, info = null) }
        return viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            fun fail(reason: Int) {
                if (onFailure != null) onFailure(reason) else _uiState.update { it.copy(error = reason) }
            }
            try {
                currentCoroutineContext().ensureActive()
                block()
            } catch (failure: CancellationException) {
                if (currentCoroutineContext()[Job]?.isActive != true) {
                    onCancelled?.invoke()
                    throw failure
                }
                fail(R.string.container_chroot_summary_stale)
            } catch (failure: Exception) {
                fail(if (failure is AddRootDisabled) R.string.container_chroot_root_disabled else R.string.container_chroot_operation_failed)
            } finally {
                _uiState.update { state -> state.copy(
                    workingProfileId = null,
                    workingAction = null,
                    addition = state.addition?.let {
                        if (it.profileId == profileId) it.copy(inProgress = false, creatingInstance = false) else it
                    }
                ) }
            }
        }
    }
}
