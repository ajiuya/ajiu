package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.memory.MemoryDocument
import com.aicode.feature.agent.domain.memory.MemoryEntry
import com.aicode.feature.agent.domain.memory.MemoryManagementError
import com.aicode.feature.agent.domain.memory.MemoryManagementResult
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.memory.MemoryStorageTarget
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MemoryUiState(
    val entries: List<MemoryEntry> = emptyList(),
    val hasWorkspace: Boolean = false,
    val projectName: String? = null,
    val loading: Boolean = true,
    val targetChanged: Boolean = false,
    val selectedEntry: MemoryEntry? = null,
    val document: MemoryDocument? = null,
    val detailLoading: Boolean = false,
    val creating: Boolean = false,
    val saving: Boolean = false,
    val pendingDelete: MemoryEntry? = null,
    val deleting: Boolean = false,
    val error: MemoryManagementError? = null
)

@HiltViewModel
class MemoryViewModel internal constructor(
    private val repository: MemoryRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val remoteSshConnection: RemoteSshConnection,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {
    @Inject constructor(
        repository: MemoryRepository,
        workspaceRepository: WorkspaceRepository,
        executionModeHolder: ExecutionModeHolder,
        remoteSshConnection: RemoteSshConnection
    ) : this(repository, workspaceRepository, executionModeHolder, remoteSshConnection, Dispatchers.IO)

    private data class Identity(
        val path: String?,
        val mode: ExecutionMode,
        val host: String?,
        val port: Int?,
        val username: String?,
        val serverRoot: String?
    )

    private class Page(val identity: Identity, val storage: MemoryStorageTarget)

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()
    private var page: Page? = null
    private var observer: Job? = null
    private var refreshGeneration = 0
    private var detailGeneration = 0

    fun open() {
        val identity = identity()
        val captured = Page(identity, repository.captureStorageTarget(identity.path))
        page = captured
        _state.value = MemoryUiState(
            hasWorkspace = captured.storage.projectRoot != null,
            projectName = workspaceRepository.current.value?.name
        )
        observer?.cancel()
        observer = viewModelScope.launch {
            combine(workspaceRepository.current, executionModeHolder.mode, remoteSshConnection.connectionState) { _, _, _ -> Unit }
                .collect { isCurrent(captured) }
        }
        refresh(captured)
    }

    fun close() {
        page = null
        observer?.cancel()
        observer = null
    }

    fun refresh() {
        val captured = page ?: return
        if (isCurrent(captured)) refresh(captured)
    }

    private fun refresh(captured: Page) {
        val generation = ++refreshGeneration
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            if (!isCurrent(captured)) return@launch
            val result = withContext(ioDispatcher) { repository.listEntries(captured.storage) }
            if (!isCurrent(captured) || generation != refreshGeneration) return@launch
            _state.update {
                when (result) {
                    is MemoryManagementResult.Success -> it.copy(entries = result.value, loading = false)
                    is MemoryManagementResult.Failure -> it.copy(loading = false, error = result.error)
                }
            }
        }
    }

    fun openDetail(entry: MemoryEntry) {
        val captured = page ?: return
        if (!isCurrent(captured)) return
        val generation = ++detailGeneration
        _state.update { it.copy(selectedEntry = entry, document = null, detailLoading = true, error = null) }
        viewModelScope.launch {
            if (!isCurrent(captured)) return@launch
            val result = withContext(ioDispatcher) { repository.readEntry(captured.storage, entry) }
            if (!isCurrent(captured) || generation != detailGeneration) return@launch
            _state.update {
                when (result) {
                    is MemoryManagementResult.Success -> it.copy(document = result.value, selectedEntry = result.value.entry, detailLoading = false)
                    is MemoryManagementResult.Failure -> it.copy(detailLoading = false, error = result.error)
                }
            }
        }
    }

    fun closeDetail() {
        detailGeneration++
        _state.update { it.copy(selectedEntry = null, document = null, detailLoading = false, error = null) }
    }

    fun startCreate() {
        val captured = page ?: return
        if (isCurrent(captured)) _state.update { it.copy(creating = true, error = null) }
    }

    fun dismissCreate() {
        if (!_state.value.saving) _state.update { it.copy(creating = false, error = null) }
    }

    fun create(name: String, description: String, content: String, scope: MemoryScope) {
        val captured = page ?: return
        if (!isCurrent(captured) || _state.value.saving) return
        if (scope == MemoryScope.PROJECT && captured.storage.projectRoot == null) {
            _state.update { it.copy(error = MemoryManagementError.NO_WORKSPACE) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            if (!isCurrent(captured)) return@launch
            val result = withContext(ioDispatcher) { repository.createOnly(captured.storage, scope, name, description, content) }
            if (!isCurrent(captured)) return@launch
            when (result) {
                is MemoryManagementResult.Success -> {
                    _state.update { it.copy(saving = false, creating = false) }
                    refresh(captured)
                }
                is MemoryManagementResult.Failure -> _state.update { it.copy(saving = false, error = result.error) }
            }
        }
    }

    fun requestDelete(entry: MemoryEntry) {
        val captured = page ?: return
        if (isCurrent(captured) && !_state.value.deleting) _state.update { it.copy(pendingDelete = entry, error = null) }
    }

    fun dismissDelete() {
        if (!_state.value.deleting) _state.update { it.copy(pendingDelete = null, error = null) }
    }

    fun confirmDelete() {
        val captured = page ?: return
        val entry = _state.value.pendingDelete ?: return
        if (!isCurrent(captured) || _state.value.deleting) return
        _state.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            if (!isCurrent(captured)) return@launch
            val result = withContext(ioDispatcher) { repository.deleteEntry(captured.storage, entry) }
            if (!isCurrent(captured)) return@launch
            when (result) {
                is MemoryManagementResult.Success -> {
                    _state.update { it.copy(deleting = false, pendingDelete = null) }
                    if (_state.value.selectedEntry?.id == entry.id) closeDetail()
                    refresh(captured)
                }
                is MemoryManagementResult.Failure -> _state.update {
                    it.copy(deleting = false, pendingDelete = null, error = result.error)
                }
            }
        }
    }

    private fun identity(): Identity {
        val mode = executionModeHolder.currentMode()
        val config = if (mode == ExecutionMode.REMOTE_SSH) remoteSshConnection.config else null
        return Identity(
            workspaceRepository.current.value?.path?.takeIf { it.isNotBlank() }, mode,
            config?.host, config?.port, config?.username, config?.remoteWorkspacePath
        )
    }

    private fun isCurrent(captured: Page): Boolean {
        if (page !== captured) return false
        if (captured.identity == identity()) return true
        page = null
        _state.value = MemoryUiState(loading = false, targetChanged = true)
        return false
    }
}
