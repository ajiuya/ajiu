package com.aicode.feature.filepreview.data

import android.content.Context
import com.aicode.feature.filepreview.domain.PreviewLimits
import com.aicode.feature.filepreview.domain.PreviewRequest
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspaceRootRead
import com.aicode.feature.workspace.domain.UiFileExecutionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject

internal class PreviewSourceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspace: WorkspaceRepository,
    private val executionFactory: WorkspaceExecutionContextFactory,
    private val rootReader: WorkspaceRootRead,
) {
    private var sourceKey: List<Any>? = null
    private var uiSource: UiFileExecutionSource? = null

    @Synchronized
    private fun sourceFor(path: String, scopeJob: Job): UiFileExecutionSource {
        val selection = workspace.selection.value
        val workspacePath = workspace.currentPath()
        val key = listOf(selection.id, workspacePath, path, scopeJob)
        if (sourceKey != key) {
            uiSource?.close()
            sourceKey = key
            uiSource = UiFileExecutionSource("file-preview", "${selection.id}:$workspacePath:$path", scopeJob) {
                if (workspace.selection.value !== selection || workspace.currentPath() != workspacePath) {
                    throw CancellationException("Preview workspace changed")
                }
            }
        }
        return requireNotNull(uiSource).also { it.checkCurrent() }
    }

    @Synchronized
    fun close() {
        uiSource?.close()
        uiSource = null
        sourceKey = null
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun open(request: PreviewRequest, scopeJob: Job): PreviewSource {
        val sessionJob = Job()
        var opened: PreviewSource? = null
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true, invokeImmediately = true,
        ) { cause ->
            if (cause is CancellationException) {
                sessionJob.cancel()
                opened?.close()
            }
        }
        try {
            val result = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val cacheRoot = context.cacheDir.canonicalFile
                if (!cacheRoot.isDirectory) throw IOException("Preview cache is unavailable")
                if (request.cachedPath.isNotBlank()) {
                    val original = File(request.cachedPath)
                    if (!original.isAbsolute || !Files.isRegularFile(original.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        throw IOException("Cached preview is unavailable")
                    }
                    val snapshot = original.canonicalFile
                    if (snapshot == cacheRoot || !snapshot.toPath().startsWith(cacheRoot.toPath())) {
                        throw IOException("Cached preview must be inside the application cache")
                    }
                    val paths = PreviewPaths.entry(request.path.ifBlank { snapshot.name })
                    val size = snapshot.length()
                    if (size > PreviewLimits.FILE_BYTES) throw IOException("File exceeds preview limit")
                    PreviewSource(paths, size, sessionJob, null, null, snapshot, cacheRoot) {}
                } else {
                    val paths = PreviewPaths.entry(request.path)
                    val ui = sourceFor(paths.entry, scopeJob)
                    val execution = executionFactory.freezeForPaths(listOf(paths.entry), runtimeOwner = ui.runtimeOwner,
                        checkSource = ui::checkCurrent)
                    fun checkWorkspace() {
                        ui.checkCurrent()
                        execution.checkCurrent()
                    }
                    checkWorkspace()
                    val root = rootReader.capture(paths.root, execution, sessionJob)
                    if (request.recordedLocalPath.isNotBlank()) {
                        val recorded = File(request.recordedLocalPath)
                        val current = root.localFile(paths.entry)
                        if (!recorded.isAbsolute || current == null || current.canonicalFile != recorded.canonicalFile) {
                            throw IOException("Recorded local file does not match the current preview source")
                        }
                    }
                    val size = root.size(paths.entry)
                    if (size < 0 || size > PreviewLimits.FILE_BYTES) throw IOException("File exceeds preview limit")
                    checkWorkspace()
                    PreviewSource(paths, size, sessionJob, execution, root, null, cacheRoot, ::checkWorkspace)
                }.also { source ->
                    opened = source
                    currentCoroutineContext().ensureActive()
                    source.checkCurrent()
                }
            }
            currentCoroutineContext().ensureActive()
            result.startMonitoring()
            return result
        } catch (failure: Throwable) {
            sessionJob.cancel()
            opened?.close()
            if (failure is CancellationException || failure is IOException || failure is Error) throw failure
            throw IOException("Cannot open preview source", failure)
        } finally {
            cancellation?.dispose()
        }
    }
}
