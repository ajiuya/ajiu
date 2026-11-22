package com.aicode.feature.backup.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.backup.data.BackupTargetRepository
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreTarget
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.backup.domain.BackupDecryptionException
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.backup.domain.PluginBackupSectionMeta
import com.aicode.feature.backup.domain.RestoreStats
import com.aicode.feature.backup.domain.WorkspaceBackupMeta
import com.aicode.feature.backup.domain.remote.BackupTarget
import com.aicode.feature.backup.domain.remote.RemoteBackup
import com.aicode.core.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object Working : BackupState()
    data object ExportDone : BackupState()
    data class ImportSuccess(val stats: RestoreStats) : BackupState()
    data class ContentSelection(
        val workspaces: List<WorkspaceBackupMeta>,
        val pluginSections: List<PluginBackupSectionMeta> = emptyList(),
        val contentSections: List<BackupContentSectionMeta> = emptyList(),
        val remoteTarget: BackupContentRestoreTarget? = null
    ) : BackupState()
    data class Error(val message: String) : BackupState()
}

/** 网络备份的独立状态；导入/还原结果仍走 [BackupState]。 */
sealed class NetworkBackupState {
    data object Idle : NetworkBackupState()
    data object Working : NetworkBackupState()
    data class UploadDone(val targetName: String) : NetworkBackupState()
    data class RemoteList(
        val targetId: String,
        val targetName: String,
        val items: List<RemoteBackup>
    ) : NetworkBackupState()
    data class Error(val message: String) : NetworkBackupState()
}

/** 导入来源：SAF 选中的文件，或下载到本地的临时文件。 */
private sealed interface ImportSource {
    data class Saf(val uri: Uri) : ImportSource
    data class Local(val file: File) : ImportSource
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val targetRepository: BackupTargetRepository
) : ViewModel() {

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _networkState = MutableStateFlow<NetworkBackupState>(NetworkBackupState.Idle)
    val networkState: StateFlow<NetworkBackupState> = _networkState.asStateFlow()

    val targets: StateFlow<List<BackupTarget>> = targetRepository.targets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var importJob: Job? = null

    @Volatile
    private var preparedImport: File? = null

    /** 当前导入来源；Local 临时文件在导入结束/取消时删除。 */
    @Volatile
    private var pendingSource: ImportSource? = null

    private val prefs = context.getSharedPreferences("backup_options", Context.MODE_PRIVATE)

    private val _exportOptions = MutableStateFlow(loadExportOptions())
    val exportOptions: StateFlow<BackupOptions> = _exportOptions.asStateFlow()
    private val mutablePluginSections = MutableStateFlow<List<PluginBackupSectionMeta>>(emptyList())
    val pluginSections = mutablePluginSections.asStateFlow()
    fun refreshPluginSections() { viewModelScope.launch { mutablePluginSections.value = backupManager.availablePluginSections() } }

    /** 导出数据范围：勾选即持久化，下次进入页面沿用。 */
    fun updateExportOptions(options: BackupOptions) {
        _exportOptions.value = options.copy(pluginIds = options.pluginIds.toSet())
        prefs.edit()
            .putBoolean(KEY_PROVIDERS, options.providers)
            .putBoolean(KEY_REMOTE_CONNECTIONS, options.remoteConnections)
            .putBoolean(KEY_CHAT_HISTORY, options.chatHistory)
            .putBoolean(KEY_MCP_SERVERS, options.mcpServers)
            .putBoolean(KEY_PERMISSION_RULES, options.permissionRules)
            .putBoolean(KEY_APP_SETTINGS, options.appSettings)
            .putBoolean(KEY_WORKSPACE_FILES, options.workspaceFiles)
            .putBoolean(KEY_MEMORIES, options.memories)
            .putBoolean(KEY_SKILLS, options.skills)
            .putBoolean(KEY_PROMPTS, options.prompts)
            .putBoolean(KEY_PLUGINS, options.plugins)
            .putStringSet(KEY_PLUGIN_IDS, options.pluginIds.toSet())
            .apply()
    }

    private fun loadExportOptions(): BackupOptions = BackupOptions(
        providers = prefs.getBoolean(KEY_PROVIDERS, true),
        remoteConnections = prefs.getBoolean(KEY_REMOTE_CONNECTIONS, true),
        chatHistory = prefs.getBoolean(KEY_CHAT_HISTORY, true),
        mcpServers = prefs.getBoolean(KEY_MCP_SERVERS, true),
        permissionRules = prefs.getBoolean(KEY_PERMISSION_RULES, true),
        appSettings = prefs.getBoolean(KEY_APP_SETTINGS, true),
        workspaceFiles = prefs.getBoolean(KEY_WORKSPACE_FILES, false),
        memories = prefs.getBoolean(KEY_MEMORIES, false),
        skills = prefs.getBoolean(KEY_SKILLS, false),
        prompts = prefs.getBoolean(KEY_PROMPTS, false),
        plugins = prefs.getBoolean(KEY_PLUGINS, false),
        pluginIds = prefs.getStringSet(KEY_PLUGIN_IDS, emptySet()).orEmpty().toSet()
    )

    /** 流式导出到 [output]（调用方打开，本方法负责关闭）。 */
    fun export(password: String, options: BackupOptions, output: OutputStream) {
        if (isBusy()) {
            runCatching { output.close() }
            return
        }
        val optionsSnapshot = options.copy(pluginIds = options.pluginIds.toSet())
        _state.value = BackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            try {
                backupManager.export(pw, optionsSnapshot, output)
                _state.value = BackupState.ExportDone
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BackupState.Error(e.message ?: context.getString(R.string.backup_export_failed))
            } finally {
                pw?.fill('\u0000')
                runCatching { output.close() }
            }
        }
    }

    // ── 网络备份目标 ──────────────────────────────────────────

    fun saveTarget(target: BackupTarget) {
        viewModelScope.launch { targetRepository.save(target) }
    }

    fun deleteTarget(id: String) {
        viewModelScope.launch { targetRepository.delete(id) }
    }

    /** 测试任意目标（可未保存）的连通性，结果通过 [onResult] 回调（成功布尔 + 文案）。 */
    fun testTarget(target: BackupTarget, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { targetRepository.storageFor(target).testConnection().getOrThrow() }
            }
            result
                .onSuccess { onResult(true, context.getString(R.string.backup_net_test_success)) }
                .onFailure { onResult(false, it.message ?: context.getString(R.string.backup_net_test_failed)) }
        }
    }

    /** 导出当前备份并上传到 [target]。备份默认含解密后的 API Key / SSH 凭据，禁止明文上传远端。 */
    fun uploadToTarget(target: BackupTarget, password: String) {
        if (isBusy()) return
        if (password.isEmpty()) {
            _networkState.value = NetworkBackupState.Error(
                context.getString(R.string.backup_net_upload_password_required)
            )
            return
        }
        val options = _exportOptions.value
        val optionsSnapshot = options.copy(pluginIds = options.pluginIds.toSet())
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            var temp: File? = null
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    temp = File.createTempFile("netbackup", ".tar.gz", context.cacheDir)
                }
                val file = requireNotNull(temp)
                withContext(Dispatchers.IO) {
                    file.outputStream().buffered().use { backupManager.export(pw, optionsSnapshot, it) }
                }
                val name = "aicode-backup-${fileTimestamp()}.tar.gz"
                val result = withContext(Dispatchers.IO) {
                    runCatching { targetRepository.storageFor(target).upload(name, file).getOrThrow() }
                }
                result
                    .onSuccess { _networkState.value = NetworkBackupState.UploadDone(target.name) }
                    .onFailure {
                        _networkState.value = NetworkBackupState.Error(
                            it.message ?: context.getString(R.string.backup_net_upload_failed)
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "网络备份上传失败")
                _networkState.value = NetworkBackupState.Error(e.message ?: context.getString(R.string.backup_net_upload_failed))
            } finally {
                pw?.fill('\u0000')
                val file = temp
                if (file != null) withContext(Dispatchers.IO + NonCancellable) { file.delete() }
            }
        }
    }

    /** 拉取 [target] 上的备份列表。 */
    fun loadRemoteBackups(target: BackupTarget) {
        if (isBusy()) return
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { targetRepository.storageFor(target).list().getOrThrow() }
            }
            result
                .onSuccess { items ->
                    _networkState.value = NetworkBackupState.RemoteList(target.id, target.name, items)
                }
                .onFailure {
                    _networkState.value = NetworkBackupState.Error(
                        it.message ?: context.getString(R.string.backup_net_list_failed)
                    )
                }
        }
    }

    /** 删除远端某个备份后刷新列表。 */
    fun deleteRemoteBackup(target: BackupTarget, name: String) {
        if (isBusy()) return
        _networkState.value = NetworkBackupState.Working
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { targetRepository.storageFor(target).delete(name).getOrThrow() }
            }
            result
                .onSuccess {
                    _networkState.value = NetworkBackupState.Idle
                    loadRemoteBackups(target)
                }
                .onFailure {
                    _networkState.value = NetworkBackupState.Error(
                        it.message ?: context.getString(R.string.backup_net_delete_failed)
                    )
                }
        }
    }

    /** 从 [target] 下载 [name] 并进入导入流程。 */
    fun restoreFromRemote(target: BackupTarget, name: String, password: String) {
        if (isBusy()) return
        _state.value = BackupState.Working
        _networkState.value = NetworkBackupState.Idle
        importJob = viewModelScope.launch {
            val pw = password.toCharArray().takeIf { it.isNotEmpty() }
            var temp: File? = null
            try {
                withContext(Dispatchers.IO + NonCancellable) {
                    temp = File.createTempFile("netrestore", ".tar.gz", context.cacheDir)
                }
                val file = requireNotNull(temp)
                withContext(Dispatchers.IO) {
                    targetRepository.storageFor(target).download(name, file).getOrThrow()
                }
                startImport(ImportSource.Local(file), pw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "网络备份恢复失败")
                _state.value = BackupState.Error(describeImportError(e))
            } finally {
                pw?.fill('\u0000')
                if (_state.value !is BackupState.ContentSelection || !isActive) {
                    withContext(Dispatchers.IO + NonCancellable) { temp?.delete() }
                }
            }
        }
    }

    fun dismissNetworkState() {
        if (_networkState.value !is NetworkBackupState.Working) {
            _networkState.value = NetworkBackupState.Idle
        }
    }

    // ── 导入 ──────────────────────────────────────────────────

    fun import(uri: Uri, password: String) {
        if (isBusy()) return
        _state.value = BackupState.Working
        importJob = viewModelScope.launch {
            startImport(ImportSource.Saf(uri), password.toCharArray().takeIf { it.isNotEmpty() })
        }
    }

    private suspend fun startImport(source: ImportSource, pw: CharArray?) {
        var awaitingSelection = false
        pendingSource = source
        try {
            val preview = withContext(Dispatchers.IO + NonCancellable) {
                val input = openStream(source)
                    ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
                input.use {
                    // SAF 内容可以变化；明文和加密备份都只从同一个本地快照预览、还原。
                    val file = backupManager.prepareImport(it, pw)
                    preparedImport = file
                    file.inputStream().use { prepared -> backupManager.previewImport(prepared, null) }
                }
            }.getOrThrow()
            currentCoroutineContext().ensureActive()
            if (preview.hasWorkspaceData || preview.pluginSections.isNotEmpty() || preview.contentSections.isNotEmpty()) {
                _state.value = BackupState.ContentSelection(
                    preview.workspaces, preview.pluginSections, preview.contentSections, preview.remoteTarget
                )
                awaitingSelection = true
            } else {
                restorePreparedImport(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = BackupState.Error(describeImportError(e))
        } finally {
            pw?.fill('\u0000')
            if (!awaitingSelection || !currentCoroutineContext().isActive) {
                clearPreparedImport()
                discardLocalSource(source)
                pendingSource = null
            }
        }
    }

    fun confirmImportSelection(
        selected: Set<String>,
        selectedPluginIds: Set<String> = emptySet(),
        contentSelection: BackupContentRestoreSelection = BackupContentRestoreSelection()
    ) {
        val preview = _state.value as? BackupState.ContentSelection ?: return
        if (importJob?.isActive == true) return
        val selectedSections = preview.contentSections.filter { it.id in contentSelection.sectionIds }
        val remoteSelected = selectedSections.any {
            it.location == BackupContentLocation.SSH_GLOBAL || it.location == BackupContentLocation.SSH_PROJECT
        }
        if (remoteSelected && (preview.remoteTarget == null || contentSelection.remoteTargetIdentity != preview.remoteTarget.identity)) return
        val fullPluginIds = selectedSections.filter { it.kind == BackupContentKind.PLUGIN }.mapNotNull { it.pluginId }.toSet()
        val extensions = selectedPluginIds.intersect(preview.pluginSections.map { it.pluginId }.toSet()) - fullPluginIds
        val selectionSnapshot = contentSelection.copy(
            sectionIds = selectedSections.map { it.id }.toSet(),
            remoteTargetIdentity = if (remoteSelected) preview.remoteTarget?.identity else null
        )
        val workspaceSnapshot = selected.intersect(preview.workspaces.map { it.name }.toSet())
        val source = pendingSource
        _state.value = BackupState.Working
        importJob = viewModelScope.launch {
            try {
                restorePreparedImport(workspaceSnapshot, extensions, selectionSnapshot)
            } finally {
                clearPreparedImport()
                if (source != null) discardLocalSource(source)
                pendingSource = null
            }
        }
    }

    fun cancelImportSelection() {
        if (_state.value !is BackupState.ContentSelection) return
        val file = preparedImport
        val source = pendingSource
        preparedImport = null
        pendingSource = null
        _state.value = BackupState.Idle
        importJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(Dispatchers.IO + NonCancellable) {
                file?.delete()
                (source as? ImportSource.Local)?.file?.delete()
            }
        }
    }

    private suspend fun restorePreparedImport(
        selected: Set<String>?,
        selectedPluginIds: Set<String> = emptySet(),
        contentSelection: BackupContentRestoreSelection = BackupContentRestoreSelection()
    ) {
        try {
            val file = preparedImport
                ?: throw IllegalArgumentException(context.getString(R.string.backup_read_failed))
            val result = withContext(Dispatchers.IO) {
                file.inputStream().use {
                    backupManager.import(it, null, selected, selectedPluginIds, contentSelection)
                }
            }
            result.onSuccess {
                _state.value = BackupState.ImportSuccess(it)
            }.onFailure {
                FileLogger.e(TAG, "还原失败")
                _state.value = BackupState.Error(describeImportError(it))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "还原异常")
            _state.value = BackupState.Error(describeImportError(e))
        }
    }

    private suspend fun openStream(source: ImportSource): InputStream? = withContext(Dispatchers.IO) {
        when (source) {
            is ImportSource.Saf -> context.contentResolver.openInputStream(source.uri)
            is ImportSource.Local -> if (source.file.exists()) source.file.inputStream() else null
        }
    }

    /** 删除下载到本地的临时文件（SAF 来源无副作用）。 */
    private suspend fun discardLocalSource(source: ImportSource) {
        val file = (source as? ImportSource.Local)?.file ?: return
        withContext(Dispatchers.IO + NonCancellable) { file.delete() }
    }

    private suspend fun clearPreparedImport() {
        val file = preparedImport
        preparedImport = null
        withContext(Dispatchers.IO + NonCancellable) { file?.delete() }
    }

    override fun onCleared() {
        preparedImport?.delete()
        preparedImport = null
        (pendingSource as? ImportSource.Local)?.file?.delete()
        pendingSource = null
        super.onCleared()
    }

    private fun describeImportError(e: Throwable): String = when (e) {
        is BackupDecryptionException -> e.message ?: context.getString(R.string.backup_wrong_password)
        else -> buildString {
            append(e.message ?: e::class.simpleName ?: context.getString(R.string.backup_import_failed))
            // 诊断期附上 cause 链（最多两层），方便定位真实异常
            var cause = e.cause
            var depth = 0
            while (cause != null && depth < 2) {
                append("\nCaused by: ")
                append(cause::class.simpleName ?: "未知异常")
                append(": ")
                append(cause.message ?: "")
                cause = cause.cause
                depth++
            }
        }
    }

    private fun isBusy(): Boolean =
        _state.value is BackupState.Working || _state.value is BackupState.ContentSelection ||
            _networkState.value is NetworkBackupState.Working || importJob?.isActive == true

    fun reset() {
        if (_state.value is BackupState.ContentSelection) {
            cancelImportSelection()
        } else if (!isBusy()) {
            _state.value = BackupState.Idle
        }
    }

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG = "Backup"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_REMOTE_CONNECTIONS = "remote_connections"
        private const val KEY_CHAT_HISTORY = "chat_history"
        private const val KEY_MCP_SERVERS = "mcp_servers"
        private const val KEY_PERMISSION_RULES = "permission_rules"
        private const val KEY_APP_SETTINGS = "app_settings"
        private const val KEY_WORKSPACE_FILES = "workspace_files"
        private const val KEY_MEMORIES = "memories"
        private const val KEY_SKILLS = "skills"
        private const val KEY_PROMPTS = "prompts"
        private const val KEY_PLUGINS = "plugins"
        private const val KEY_PLUGIN_IDS = "plugin_ids"
    }
}
