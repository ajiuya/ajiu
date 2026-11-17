package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.memory.ProjectMemoryAssociations
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapacity
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectMemoryProject
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProjectProgressError { ENVIRONMENT_UNAVAILABLE, INVALID_INPUT, READ_FAILED, SAVE_FAILED, NOT_FOUND, REGISTERED_BINDING_FAILED }
enum class ProjectProgressForm { REGISTER, HANDOFF }
enum class ProjectProgressEntryAction { REVOKE, ARCHIVE }
enum class ProjectProgressFilter(val kind: String?) { ALL(null), AUTO_PROGRESS("turn"), HANDOFF("handoff") }

data class ProjectProgressConfirmation(val entry: ProjectMemoryEntry, val action: ProjectProgressEntryAction)

data class ProjectProgressUiState(
    val workspaceName: String? = null,
    val workspaceRoot: String? = null,
    val environmentMode: String? = null,
    val sessionId: String? = null,
    val loading: Boolean = true,
    val targetChanged: Boolean = false,
    val projects: List<ProjectMemoryProject> = emptyList(),
    val associations: ProjectMemoryAssociations? = null,
    val capacity: ProjectMemoryCapacity? = null,
    val selectedProjectId: String? = null,
    val entries: List<ProjectMemoryEntry> = emptyList(),
    val latestHandoff: ProjectMemoryEntry? = null,
    val handoffLoading: Boolean = false,
    val filter: ProjectProgressFilter = ProjectProgressFilter.ALL,
    val showingArchive: Boolean = false,
    val historyOffset: Int = 0,
    val historyLoading: Boolean = false,
    val hasMore: Boolean = false,
    val selectedEntryId: String? = null,
    val detail: ProjectMemoryEntry? = null,
    val detailLoading: Boolean = false,
    val form: ProjectProgressForm? = null,
    val formProjectId: String? = null,
    val confirmation: ProjectProgressConfirmation? = null,
    val busy: Boolean = false,
    val error: ProjectProgressError? = null
) {
    val selectedProject: ProjectMemoryProject? get() = projects.firstOrNull { it.id == selectedProjectId }
    val canBindSelected: Boolean get() = selectedProject?.identity?.projectRoot?.let { projectRoot ->
        workspaceRoot?.let { ProjectMemoryIdentityResolver.containsRoot(it, projectRoot) }
    } == true
}

@HiltViewModel
class ProjectProgressViewModel internal constructor(
    private val journal: ProjectMemoryJournal,
    private val workspaceRepository: WorkspaceRepository,
    private val identityResolver: ProjectMemoryIdentityResolver,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    @Inject constructor(
        journal: ProjectMemoryJournal,
        workspaceRepository: WorkspaceRepository,
        identityResolver: ProjectMemoryIdentityResolver
    ) : this(journal, workspaceRepository, identityResolver, Dispatchers.IO)

    private class Page(val generation: Long, val path: String, val sessionId: String?, val capture: ProjectMemoryCapture)
    private class TargetChanged : CancellationException()

    private val _state = MutableStateFlow(ProjectProgressUiState())
    val state: StateFlow<ProjectProgressUiState> = _state.asStateFlow()
    @Volatile private var page: Page? = null
    @Volatile private var pageGeneration = 0L
    private var workspaceObserver: Job? = null
    private var environmentObserver: Job? = null
    private var projectsGeneration = 0L
    private var selectionGeneration = 0L
    private var historyGeneration = 0L
    private var handoffGeneration = 0L
    private var detailGeneration = 0L

    fun open(sessionId: String?) {
        close()
        val generation = pageGeneration
        val workspace = workspaceRepository.current.value
        val path = workspace?.path?.takeIf { it.isNotBlank() }
        val frozenSession = sessionId?.takeIf { it.isNotBlank() }
        _state.value = ProjectProgressUiState(
            workspaceName = workspace?.name, workspaceRoot = path, sessionId = frozenSession, loading = path != null
        )
        workspaceObserver = viewModelScope.launch {
            workspaceRepository.current.collect {
                if (it?.path?.takeIf(String::isNotBlank) != path) invalidate(generation)
            }
        }
        if (path == null) return
        viewModelScope.launch {
            try {
                val capture = withContext(ioDispatcher) { identityResolver.capture(path) }
                if (!isRequestCurrent(generation, path)) return@launch
                if (capture == null) {
                    _state.update { it.copy(loading = false, error = ProjectProgressError.ENVIRONMENT_UNAVAILABLE) }
                    return@launch
                }
                val captured = Page(generation, path, frozenSession, capture)
                page = captured
                _state.update { it.copy(workspaceRoot = capture.identity.projectRoot, environmentMode = capture.identity.mode) }
                environmentObserver = viewModelScope.launch {
                    try {
                        identityResolver.environmentChanges.collect { checkEnvironment() }
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw cancelled
                        invalidate(captured.generation)
                    } catch (_: Exception) {
                        invalidate(captured.generation)
                    }
                }
                refresh(captured)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(generation)
            } catch (_: Exception) {
                if (isRequestCurrent(generation, path)) {
                    _state.update { it.copy(loading = false, error = ProjectProgressError.ENVIRONMENT_UNAVAILABLE) }
                }
            }
        }
    }

    fun close() {
        pageGeneration++
        page = null
        projectsGeneration++
        historyGeneration++
        handoffGeneration++
        detailGeneration++
        workspaceObserver?.cancel()
        workspaceObserver = null
        environmentObserver?.cancel()
        environmentObserver = null
    }

    fun checkEnvironment() {
        val captured = page ?: return
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) { atTarget(captured) { Unit } }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (_: Exception) {
                invalidate(captured.generation)
            }
        }
    }

    fun refresh() {
        if (_state.value.busy) return
        page?.let { refresh(it) }
    }

    private fun refresh(captured: Page, selectProjectId: String? = _state.value.selectedProjectId) {
        val generation = ++projectsGeneration
        val selectionAtStart = selectionGeneration
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val projects = withContext(ioDispatcher) {
                    atTarget(captured) {
                        journal.listProjects().filter { ProjectMemoryIdentityResolver.sameEndpoint(captured.capture.identity, it.identity) }
                    }
                }
                val associations = withContext(ioDispatcher) {
                    atTarget(captured) { journal.associations(captured.sessionId, captured.capture.identity) }
                }
                val capacity = withContext(ioDispatcher) {
                    try { atTarget(captured) { journal.capacity() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                if (!accepts(captured) || generation != projectsGeneration) return@launch
                val requestedSelection = if (selectionAtStart == selectionGeneration) selectProjectId else _state.value.selectedProjectId
                val selected = requestedSelection?.takeIf { id -> projects.any { it.id == id } }
                _state.update {
                    it.copy(projects = projects, associations = associations, capacity = capacity, loading = false,
                        selectedProjectId = selected,
                        entries = if (selected == null) emptyList() else it.entries,
                        latestHandoff = if (selected == null) null else it.latestHandoff,
                        handoffLoading = selected != null && it.handoffLoading,
                        historyOffset = if (selected == null) 0 else it.historyOffset,
                        historyLoading = selected != null && it.historyLoading,
                        hasMore = selected != null && it.hasMore,
                        selectedEntryId = if (selected == null) null else it.selectedEntryId,
                        detail = if (selected == null) null else it.detail,
                        detailLoading = selected != null && it.detailLoading)
                }
                if (selected != null) {
                    loadLatestHandoff(captured, selected)
                    loadHistory(captured, selected, more = false)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (_: Exception) {
                if (accepts(captured) && generation == projectsGeneration) {
                    _state.update { it.copy(loading = false, error = ProjectProgressError.READ_FAILED) }
                }
            }
        }
    }

    fun selectProject(project: ProjectMemoryProject) {
        val captured = page ?: return
        if (_state.value.busy || !accepts(captured) || _state.value.projects.none { it.id == project.id }) return
        selectionGeneration++
        detailGeneration++
        _state.update {
            it.copy(selectedProjectId = project.id, entries = emptyList(), latestHandoff = null,
                filter = ProjectProgressFilter.ALL, showingArchive = false, historyOffset = 0, hasMore = false, selectedEntryId = null,
                detail = null, detailLoading = false, error = null, confirmation = null)
        }
        loadLatestHandoff(captured, project.id)
        loadHistory(captured, project.id, more = false)
    }

    fun closeProject() {
        if (_state.value.busy) return
        selectionGeneration++
        historyGeneration++
        handoffGeneration++
        detailGeneration++
        _state.update {
            it.copy(selectedProjectId = null, selectedEntryId = null, detail = null, detailLoading = false,
                entries = emptyList(), latestHandoff = null, handoffLoading = false,
                filter = ProjectProgressFilter.ALL, showingArchive = false, historyOffset = 0,
                historyLoading = false, hasMore = false, confirmation = null, error = null)
        }
    }

    fun showArchive(archived: Boolean) {
        val captured = page ?: return
        val id = _state.value.selectedProjectId ?: return
        if (_state.value.busy || _state.value.showingArchive == archived) return
        detailGeneration++
        _state.update {
            it.copy(showingArchive = archived, entries = emptyList(), historyOffset = 0, hasMore = false,
                selectedEntryId = null, detail = null, detailLoading = false, confirmation = null)
        }
        loadHistory(captured, id, more = false)
    }

    fun setFilter(filter: ProjectProgressFilter) {
        val captured = page ?: return
        val state = _state.value
        val id = state.selectedProjectId ?: return
        if (state.busy || state.filter == filter) return
        detailGeneration++
        _state.update {
            it.copy(filter = filter, entries = emptyList(), historyOffset = 0, hasMore = false,
                selectedEntryId = null, detail = null, detailLoading = false, confirmation = null)
        }
        loadHistory(captured, id, more = false)
    }

    private fun loadLatestHandoff(captured: Page, projectId: String) {
        val generation = ++handoffGeneration
        _state.update { it.copy(handoffLoading = true) }
        viewModelScope.launch {
            try {
                val handoff = withContext(ioDispatcher) { atTarget(captured) { journal.resumeRecords(projectId, limit = 0).handoff } }
                if (!accepts(captured) || generation != handoffGeneration || _state.value.selectedProjectId != projectId) return@launch
                _state.update { it.copy(latestHandoff = handoff, handoffLoading = false) }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (_: Exception) {
                if (accepts(captured) && generation == handoffGeneration) {
                    _state.update { it.copy(handoffLoading = false, error = ProjectProgressError.READ_FAILED) }
                }
            }
        }
    }

    fun loadMore() {
        val captured = page ?: return
        val state = _state.value
        val id = state.selectedProjectId ?: return
        if (!state.historyLoading && state.hasMore && !state.busy) loadHistory(captured, id, more = true)
    }

    private fun loadHistory(captured: Page, projectId: String, more: Boolean) {
        val generation = ++historyGeneration
        val state = _state.value
        val archived = state.showingArchive
        val filter = state.filter
        val offset = if (more) state.historyOffset else 0
        val previous = if (more) state.entries else emptyList()
        _state.update { it.copy(historyLoading = true, historyOffset = offset, hasMore = more && state.hasMore, error = null) }
        viewModelScope.launch {
            try {
                val entries = withContext(ioDispatcher) {
                    atTarget(captured) {
                        if (archived) journal.listArchive(projectId, limit = 20, offset = offset, maxChars = 65536, kind = filter.kind)
                        else journal.listRecent(projectId, limit = 20, includeRevoked = true, maxChars = 65536, offset = offset, kind = filter.kind)
                    }
                }
                if (!accepts(captured) || generation != historyGeneration || _state.value.selectedProjectId != projectId ||
                    _state.value.filter != filter || _state.value.showingArchive != archived) return@launch
                _state.update {
                    it.copy(entries = (previous + entries).distinctBy(ProjectMemoryEntry::id), historyOffset = offset + entries.size,
                        historyLoading = false, hasMore = entries.isNotEmpty())
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (_: Exception) {
                if (accepts(captured) && generation == historyGeneration) {
                    _state.update { it.copy(historyLoading = false, error = ProjectProgressError.READ_FAILED) }
                }
            }
        }
    }

    fun openEntry(entry: ProjectMemoryEntry) {
        val captured = page ?: return
        val projectId = _state.value.selectedProjectId ?: return
        if (_state.value.busy || entry.projectId != projectId ||
            (_state.value.entries.none { it.id == entry.id } && _state.value.latestHandoff?.id != entry.id)) return
        val generation = ++detailGeneration
        _state.update { it.copy(selectedEntryId = entry.id, detail = null, detailLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val document = withContext(ioDispatcher) { atTarget(captured) { journal.read(entry.id) } }
                if (!accepts(captured) || generation != detailGeneration || _state.value.selectedProjectId != projectId) return@launch
                _state.update {
                    if (document != null && document.projectId == projectId) it.copy(detail = document, detailLoading = false)
                    else it.copy(detailLoading = false, error = ProjectProgressError.NOT_FOUND)
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (_: Exception) {
                if (accepts(captured) && generation == detailGeneration) {
                    _state.update { it.copy(detailLoading = false, error = ProjectProgressError.READ_FAILED) }
                }
            }
        }
    }

    fun closeEntry() {
        if (_state.value.busy) return
        detailGeneration++
        _state.update { it.copy(selectedEntryId = null, detail = null, detailLoading = false, confirmation = null, error = null) }
    }

    fun startRegister() {
        val captured = page ?: return
        if (accepts(captured) && !_state.value.busy) _state.update { it.copy(form = ProjectProgressForm.REGISTER, error = null) }
    }

    fun startHandoff() {
        val captured = page ?: return
        val projectId = _state.value.selectedProjectId ?: return
        if (accepts(captured) && !_state.value.busy) {
            _state.update { it.copy(form = ProjectProgressForm.HANDOFF, formProjectId = projectId, error = null) }
        }
    }

    fun dismissForm() {
        if (!_state.value.busy) _state.update { it.copy(form = null, formProjectId = null, error = null) }
    }

    fun register(name: String) {
        val captured = page ?: return
        if (_state.value.form != ProjectProgressForm.REGISTER || _state.value.busy) return
        if (name.isBlank() || name.length > 200) {
            _state.update { it.copy(error = ProjectProgressError.INVALID_INPUT) }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            var registered: ProjectMemoryProject? = null
            try {
                val project = withContext(ioDispatcher) {
                    atTarget(captured) { journal.registerProject(captured.capture.identity, name.trim(), updateExisting = false) }
                }
                registered = project
                val projectId = project.id
                withContext(ioDispatcher) { atTarget(captured) { bind(captured, projectId) } }
                if (!accepts(captured)) return@launch
                _state.update { it.copy(busy = false, form = null, formProjectId = null) }
                refresh(captured, projectId)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (failure: Exception) {
                if (accepts(captured)) {
                    _state.update { it.copy(busy = false, error = when {
                        registered != null -> ProjectProgressError.REGISTERED_BINDING_FAILED
                        failure is IllegalArgumentException -> ProjectProgressError.INVALID_INPUT
                        else -> ProjectProgressError.SAVE_FAILED
                    }) }
                }
            }
        }
    }

    fun bindSelected() {
        val captured = page ?: return
        val state = _state.value
        val projectId = state.selectedProjectId ?: return
        if (!state.canBindSelected) return
        mutate(captured, { bind(captured, projectId) }) { refresh(captured) }
    }

    fun unbind() {
        val captured = page ?: return
        mutate(captured, { bind(captured, null) }) { refresh(captured) }
    }

    private fun bind(captured: Page, projectId: String?) {
        if (captured.sessionId != null) journal.bindSessionProject(captured.sessionId, captured.capture.identity, projectId)
        else journal.setWorkspaceProject(captured.capture.identity, projectId)
    }

    fun setAutoProgressEnabled(enabled: Boolean) {
        val captured = page ?: return
        val project = _state.value.selectedProject ?: return
        mutate(captured, { journal.setAutoProgressEnabled(project.id, enabled) }) { updated ->
            _state.update { it.copy(projects = it.projects.map { item -> if (item.id == updated.id) updated else item }) }
        }
    }

    fun saveHandoff(summary: String, body: String) {
        val captured = page ?: return
        val state = _state.value
        val projectId = state.formProjectId ?: return
        if (state.form != ProjectProgressForm.HANDOFF || state.selectedProjectId != projectId) return
        if (summary.isBlank() || summary.length > 512 || body.isBlank() || body.length > 8192) {
            _state.update { it.copy(error = ProjectProgressError.INVALID_INPUT) }
            return
        }
        mutate(captured, {
            journal.append(projectId, summary.trim(), body, kind = "handoff", status = "recorded", sourceSessionId = captured.sessionId)
        }) {
            _state.update { it.copy(form = null, formProjectId = null, showingArchive = false) }
            refresh(captured)
        }
    }

    fun requestEntryAction(action: ProjectProgressEntryAction) {
        val captured = page ?: return
        val state = _state.value
        val entry = state.detail ?: return
        if (!accepts(captured) || state.busy || entry.projectId != state.selectedProjectId ||
            (action == ProjectProgressEntryAction.REVOKE && entry.revoked) ||
            (action == ProjectProgressEntryAction.ARCHIVE && entry.archived)) return
        _state.update { it.copy(confirmation = ProjectProgressConfirmation(entry, action), error = null) }
    }

    fun dismissConfirmation() {
        if (!_state.value.busy) _state.update { it.copy(confirmation = null) }
    }

    fun confirmEntryAction() {
        val captured = page ?: return
        val pending = _state.value.confirmation ?: return
        if (pending.entry.id != _state.value.selectedEntryId || pending.entry.projectId != _state.value.selectedProjectId) return
        mutate(captured, {
            when (pending.action) {
                ProjectProgressEntryAction.REVOKE -> journal.revoke(pending.entry.id, pending.entry.revision)
                ProjectProgressEntryAction.ARCHIVE -> journal.archive(pending.entry.id, pending.entry.revision)
            }
        }) { updated ->
            _state.update { it.copy(confirmation = null, detail = updated) }
            refresh(captured)
        }
    }

    private fun <T> mutate(captured: Page, operation: () -> T, onSuccess: (T) -> Unit) {
        if (_state.value.busy || !accepts(captured)) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { atTarget(captured, operation) }
                if (!accepts(captured)) return@launch
                _state.update { it.copy(busy = false) }
                onSuccess(result)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                invalidate(captured.generation)
            } catch (failure: Exception) {
                if (accepts(captured)) {
                    _state.update { it.copy(busy = false, confirmation = null,
                        error = if (failure is IllegalArgumentException) ProjectProgressError.INVALID_INPUT else ProjectProgressError.SAVE_FAILED) }
                }
            }
        }
    }

    private suspend fun <T> atTarget(captured: Page, operation: () -> T): T {
        if (!accepts(captured)) throw TargetChanged()
        captured.capture.assertCurrent()
        if (!accepts(captured)) throw TargetChanged()
        try {
            return operation()
        } finally {
            captured.capture.assertCurrent()
            if (!accepts(captured)) throw TargetChanged()
        }
    }

    private fun isRequestCurrent(generation: Long, path: String): Boolean =
        pageGeneration == generation && workspaceRepository.current.value?.path == path

    private fun accepts(captured: Page): Boolean = page === captured && isRequestCurrent(captured.generation, captured.path)

    private fun invalidate(generation: Long) {
        if (pageGeneration != generation) return
        close()
        _state.value = ProjectProgressUiState(loading = false, targetChanged = true, sessionId = _state.value.sessionId)
    }
}
