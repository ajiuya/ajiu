package com.aicode.feature.plugin.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import com.aicode.feature.plugin.data.index.NoMatchingReleaseException
import com.aicode.feature.plugin.data.index.PluginIndexClient
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginPackageExpectation
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.SemVer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class PluginStoreItem(val plugin: PluginIndexPlugin, val source: PluginIndexSource, val installedVersion: String?, val updateAvailable: Boolean, val compatible: Boolean, val stale: Boolean = false) {
    val key: String get() = "${source.id}:${plugin.id}"
}
data class PluginStorePreview(val preview: PluginPreview, val downloadUrl: String)
internal data class PluginStoreUiState(
    val loading: Boolean = true,
    val sources: List<PluginIndexSource> = emptyList(),
    val items: List<PluginStoreItem> = emptyList(),
    val downloading: String? = null,
    val preview: PluginStorePreview? = null,
    val problem: PluginProblem? = null,
    val itemProblems: Map<String, PluginProblem> = emptyMap(),
    val previewKey: String? = null,
    val sourceProblem: PluginProblem? = null,
    val sourceSaved: Boolean = false,
    val installedId: String? = null,
    val notice: Int? = null,
    val mirror: String = ""
)

@HiltViewModel
class PluginStoreViewModel @Inject constructor(
    private val client: PluginIndexClient,
    private val sources: PluginIndexSourceRepository,
    private val settings: PluginStoreSettingsRepository,
    private val manager: PluginManager
) : ViewModel() {
    private val mutableState = MutableStateFlow(PluginStoreUiState())
    internal val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private var loadRevision = 0L

    init {
        viewModelScope.launch {
            sources.sources.collect { list ->
                val changed = mutableState.value.sources.map { it.indexUrl } != list.map { it.indexUrl }
                mutableState.update { it.copy(sources = list) }
                if (changed) load(false)
            }
        }
        viewModelScope.launch { settings.downloadMirror.collect { mirror -> mutableState.update { it.copy(mirror = mirror) } } }
        viewModelScope.launch {
            manager.plugins.map { installed -> installed.associate { it.record.id to it.record.version } }.distinctUntilChanged().collect { versions ->
                mutableState.update { state -> state.copy(items = state.items.map { item ->
                    val version = versions[item.plugin.id]
                    item.copy(installedVersion = version, updateAvailable = isNewer(item.plugin.version, version))
                }) }
            }
        }
    }

    fun refresh() = load(true)
    private fun load(force: Boolean) {
        loadJob?.cancel()
        val revision = ++loadRevision
        val list = state.value.sources
        val previous = state.value.items.groupBy { it.source.id }
        mutableState.update { it.copy(loading = true, problem = null) }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            manager.ensureLoaded()
            val items = mutableListOf<PluginStoreItem>()
            var failed = false
            list.forEach { source ->
                try {
                    val result = client.fetchIndex(source, force)
                    val installed = manager.plugins.value.associate { it.record.id to it.record.version }
                    items += result.index.plugins.map { plugin ->
                        val version = installed[plugin.id]
                        PluginStoreItem(plugin, source, version, isNewer(plugin.version, version), client.isCompatible(plugin), result.stale)
                    }
                    if (!result.stale) sources.markFetched(source.id, result.fetchedAt)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true; items += previous[source.id].orEmpty().map { it.copy(stale = true) } }
            }
            if (revision == loadRevision) mutableState.update { it.copy(loading = false, items = items, problem = if (failed) PluginProblem("INDEX_FAILED", R.string.plugin_store_error) else null) }
        }
    }

    fun download(item: PluginStoreItem) {
        if (!item.compatible || state.value.downloading != null || state.value.preview != null) return
        mutableState.update { it.copy(downloading = item.key, itemProblems = it.itemProblems - item.key, notice = null) }
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            var staged: String? = null
            try {
                val asset = client.resolveRelease(item.plugin)
                client.download(asset).use { downloaded ->
                    val origin = PluginInstallOrigin(PluginInstallOriginKind.INDEX, item.source.id, item.source.name,
                        item.source.indexUrl, item.plugin.repository, officialUpstream = false)
                    val expected = PluginPackageExpectation(item.plugin.id, asset.version, item.plugin.author, item.plugin.repository, downloaded.archiveSha256)
                    when (val result = downloaded.file.inputStream().use { manager.preview(it, origin, expected) }) {
                        is PluginPreviewResult.Success -> {
                            staged = result.preview.token
                            mutableState.update { it.copy(preview = PluginStorePreview(result.preview, downloaded.downloadUrl), previewKey = item.key) }
                            staged = null
                        }
                        is PluginPreviewResult.Failure -> result.errors.firstOrNull()?.let { error ->
                            mutableState.update { it.copy(itemProblems = it.itemProblems + (item.key to error.toProblem())) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                FileLogger.w("PluginStore", "Package request failed for ${item.plugin.id}@${item.plugin.version.orEmpty()}: ${failure.javaClass.simpleName}")
                mutableState.update { it.copy(itemProblems = it.itemProblems + (item.key to downloadProblem(failure))) }
            }
            finally {
                staged?.let { manager.discardPreview(it) }
                mutableState.update { it.copy(downloading = null) }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }
    fun dismissPreview() {
        val token = state.value.preview?.preview?.token ?: return
        mutableState.update { it.copy(preview = null, previewKey = null) }
        viewModelScope.launch { manager.discardPreview(token) }
    }
    fun install(token: String) {
        if (state.value.preview?.preview?.token != token || state.value.downloading != null) return
        val key = state.value.previewKey
        mutableState.update { it.copy(preview = null, downloading = key ?: token) }
        viewModelScope.launch {
            try {
                when (val result = withContext(NonCancellable) { try { manager.install(token) } finally { manager.discardPreview(token) } }) {
                    is PluginInstallResult.Success -> mutableState.update {
                        it.copy(installedId = result.plugin.record.id, notice = R.string.plugin_install_disabled)
                    }
                    is PluginInstallResult.Failure -> result.errors.firstOrNull()?.let { error ->
                        mutableState.update { state ->
                            if (key == null) state.copy(problem = error.toProblem())
                            else state.copy(itemProblems = state.itemProblems + (key to error.toProblem()))
                        }
                    }
                }
            } finally { mutableState.update { it.copy(downloading = null, previewKey = null) } }
        }
    }
    internal fun consumeInstalled() { mutableState.update { it.copy(installedId = null) } }
    fun saveSources(value: String, mirror: String) {
        viewModelScope.launch {
            val result = sources.setCustomSources(value.lines())
            val mirrorResult = settings.setDownloadMirror(mirror)
            mutableState.update { it.copy(sourceProblem = if (result.isFailure || mirrorResult.isFailure) PluginProblem("SOURCE_INVALID", R.string.plugin_source_invalid) else null,
                sourceSaved = result.isSuccess && mirrorResult.isSuccess) }
        }
    }
    override fun onCleared() {
        state.value.preview?.preview?.token?.let { token -> kotlinx.coroutines.CoroutineScope(NonCancellable + Dispatchers.IO).launch { manager.discardPreview(token) } }
        super.onCleared()
    }
    companion object {
        internal fun downloadProblem(failure: Exception): PluginProblem = when (failure) {
            is NoMatchingReleaseException -> PluginProblem("RELEASE_NOT_FOUND", R.string.plugin_store_release_not_found)
            else -> PluginProblem("DOWNLOAD_FAILED", R.string.plugin_store_download_error, failure.javaClass.simpleName)
        }
        internal fun isNewer(candidate: String?, installed: String?): Boolean =
            candidate?.let(SemVer::parse)?.let { left -> installed?.let(SemVer::parse)?.let { left > it } } ?: false
    }
}
