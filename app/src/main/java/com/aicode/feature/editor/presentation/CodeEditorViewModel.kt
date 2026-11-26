package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.data.EditorSettingsRepository
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.agent.domain.tool.FileVersionRegistry
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.UiFileExecutionSource
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 编辑器页状态。 */
sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

/** 保存结果，一次性事件，经 [CodeEditorViewModel.saveEvents] 下发。 */
sealed interface SaveResult {
    data object Success : SaveResult

    /**
     * 磁盘上的内容在编辑器打开之后被改过（多半是 AI 会话写的），本次保存**未写入**。
     *
     * 携带磁盘当前内容供 UI 展示差异：不直接覆盖，也不直接丢弃用户的编辑，由用户选。
     *
     * @param diskContent 磁盘当前内容（可能是 AI 刚写进去的）
     */
    data class Conflict(val diskContent: String) : SaveResult

    data class Error(val detail: String?) : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val executionFactory: WorkspaceExecutionContextFactory,
    private val editorSettings: EditorSettingsRepository,
    private val versions: FileVersionRegistry,
    private val workspace: WorkspaceRepository,
    private val routing: FileBackendRouting,
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** 是否正在写盘（远程模式下经 SFTP，耗时较长，供 UI 转圈）。 */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    @Volatile private var loadedPath: String? = null
    private var fileSource: UiFileExecutionSource? = null
    private var selectionId: String? = null
    private var loading: Job? = null
    private var writing: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var loaded: LoadedFile? = null

    private class LoadedFile(
        val path: String,
        val generation: Long,
        val source: UiFileExecutionSource,
        val execution: WorkspaceExecutionContext,
        val access: FileAccessProvider,
        var fingerprint: String?,
    )

    private fun checkLoaded(file: LoadedFile) {
        file.source.checkCurrent()
        if (loaded !== file || generation != file.generation) throw CancellationException("Editor file changed")
        file.execution.checkCurrent()
    }

    /** 重复调用同一路径不会重复读盘，供 Compose 重组时安全调用。 */
    fun load(path: String) {
        val selection = workspace.selection.value
        if (loadedPath == path && selectionId == selection.id &&
            (loading?.isActive == true || loaded?.let { runCatching { checkLoaded(it) }.isSuccess } == true)) return
        if (loadedPath != path || selectionId != selection.id || fileSource == null) {
            fileSource?.close()
            loadedPath = path
            selectionId = selection.id
            fileSource = UiFileExecutionSource("editor", "${selection.id}:$path", requireNotNull(viewModelScope.coroutineContext[Job])) {
                if (workspace.selection.value !== selection || loadedPath != path) throw CancellationException("Editor source changed")
            }
        }
        val source = requireNotNull(fileSource)
        loading?.cancel()
        writing?.cancel()
        val epoch = ++generation
        loaded = null
        _saving.value = false
        _uiState.value = EditorUiState.Loading
        loading = viewModelScope.launch(Dispatchers.IO) {
            try {
                val execution = executionFactory.freezeForPaths(listOf(path), runtimeOwner = source.runtimeOwner,
                    checkSource = source::checkCurrent)
                withContext(execution) {
                    val route = routing.capture(execution, listOf(path), source::checkCurrent)
                    val access = route.endpoint(path).provider
                    val state = if (!access.exists(path) || !access.isFile(path)) EditorUiState.Error(null) else {
                        val size = access.fileSize(path)
                        if (size > MAX_EDITABLE_BYTES) EditorUiState.TooLarge(size) else {
                            val bytes = access.readBytes(path, MAX_EDITABLE_BYTES)
                            if (looksBinary(bytes)) EditorUiState.Binary else {
                                TextMateSetup.ensureInitialized(context)
                                val scope = TextMateSetup.scopeNameFor(path)
                                if (scope != null) runCatching { TextMateLanguage.create(scope, false).destroy() }
                                val fingerprint = access.fingerprint(path)
                                currentCoroutineContext().ensureActive()
                                route.checkCurrent()
                                if (generation != epoch) throw CancellationException("Editor file changed")
                                loaded = LoadedFile(path, epoch, source, execution, access, fingerprint)
                                fingerprint?.let { versions.recordExternal(access.toDisplayPath(path), it) }
                                EditorUiState.Success(bytes.toString(Charsets.UTF_8), scope)
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    source.checkCurrent()
                    if (generation == epoch) _uiState.value = state
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (generation == epoch) _uiState.value = EditorUiState.Error(cancelled.message)
            } catch (failure: Exception) {
                FileLogger.w(TAG, "打开文件失败: $path", failure)
                if (generation == epoch) _uiState.value = EditorUiState.Error(failure.message)
            }
        }
    }

    /**
     * 把编辑器当前内容写回文件。写入在 IO 线程进行，结果通过 [saveEvents] 通知，期间 [saving] 置位。
     *
     * 保存前先比对磁盘指纹：若文件在编辑器打开之后被 AI 会话或别的进程改过，**不写入**，
     * 改为下发 [SaveResult.Conflict]，由用户决定覆盖还是放弃自己的编辑。否则用户的保存会
     * 静默抹掉 AI 刚写的改动（反向亦然）。
     */
    fun save(content: String) = saveCaptured(content, force = false)

    private fun saveCaptured(content: String, force: Boolean) {
        val file = loaded ?: return
        if (_saving.value) return
        _saving.value = true
        writing = viewModelScope.launch(Dispatchers.IO + file.execution) {
            try {
                checkLoaded(file)
                val current = if (force) null else file.access.fingerprint(file.path)
                if (!force && file.fingerprint != null && current != null && current != file.fingerprint) {
                    val disk = file.access.readBytes(file.path, MAX_EDITABLE_BYTES).toString(Charsets.UTF_8)
                    checkLoaded(file)
                    _saveEvents.send(SaveResult.Conflict(disk))
                } else {
                    checkLoaded(file)
                    _saveEvents.send(writeThrough(file, content))
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                _saveEvents.send(SaveResult.Error(cancelled.message))
            } catch (failure: Exception) {
                FileLogger.w(TAG, "保存文件失败: ${file.path}", failure)
                _saveEvents.send(SaveResult.Error(failure.message))
            } finally {
                if (generation == file.generation) _saving.value = false
            }
        }
    }

    /**
     * 无视冲突直接覆盖磁盘（用户在冲突提示里选了「覆盖」）。
     *
     * 这是用户显式确认的覆盖，因此不再比对指纹——但仍走已捕获文件后端的原子写。
     */
    fun saveForce(content: String) = saveCaptured(content, force = true)

    /**
     * 放弃编辑器里的修改、重新从磁盘加载（用户在冲突提示里选了「放弃我的修改」）。
     *
     * 复用 [load] 会因已有成功捕获而直接返回，所以这里先清掉已加载快照再走加载，
     * 保证拿到的是磁盘最新内容。
     */
    fun reloadFromDisk() {
        val path = loadedPath ?: return
        loading?.cancel()
        loaded = null
        load(path)
    }

    /** 真正写盘并维护版本基线；成功/失败统一转成 [SaveResult]。 */
    private fun writeThrough(file: LoadedFile, content: String): SaveResult {
        checkLoaded(file)
        file.access.writeFile(file.path, content)
        checkLoaded(file)
        file.fingerprint = file.access.fingerprint(file.path)
        file.fingerprint?.let { versions.recordExternal(file.access.toDisplayPath(file.path), it) }
        checkLoaded(file)
        return SaveResult.Success
    }

    internal fun release() {
        fileSource?.close()
        fileSource = null
        loaded = null
        loadedPath = null
        selectionId = null
        generation++
        loading?.cancel()
        loading = null
        writing?.cancel()
        writing = null
        _saving.value = false
        _uiState.value = EditorUiState.Loading
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "CodeEditorViewModel"

        /** 全量读入内存，超过该体积拒绝打开以避免 OOM 与长时间卡顿。 */
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

        /** 二进制嗅探的采样字节数：与 git 一致，仅看开头一段。 */
        const val BINARY_SNIFF_BYTES = 8000

        /** 二进制判定：开头采样段内出现 NUL 字节即视为二进制（文本文件不含 NUL，误判率极低）。 */
        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until limit) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
