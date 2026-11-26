package com.aicode.feature.agent.presentation

import android.content.Context
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.workflow.ToolPreviewImageCache
import com.aicode.feature.agent.presentation.preview.PreviewFileReader
import com.aicode.feature.agent.presentation.preview.PreviewImageBytes
import com.aicode.feature.agent.presentation.preview.PreviewImageFile
import com.aicode.feature.agent.presentation.preview.PreviewImageKey
import com.aicode.feature.agent.presentation.preview.PreviewImagePayload
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderGrantRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.UiFileExecutionSource
import com.aicode.feature.workspace.domain.isLocalFolderPath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onStart

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ToolPreviewFileAccessAdapter @Inject constructor(
    private val access: FileAccessProvider,
    private val local: LocalFileAccess,
    private val mode: ExecutionModeHolder,
    connection: RemoteSshConnection,
    private val workspaces: WorkspaceRepository,
    private val settings: ContainerSettingsRepository,
    private val localFolders: LocalFolderGrantRepository,
    private val executionFactory: WorkspaceExecutionContextFactory,
    @param:ApplicationContext context: Context,
) {
    private val images = File(context.cacheDir.canonicalFile, ToolPreviewImageCache.DIRECTORY)
    private val imageAliases = listOf(File(context.cacheDir, ToolPreviewImageCache.DIRECTORY).absolutePath)

    private val executionChanges = combine(
        mode.mode, connection.connectionState,
        combine(workspaces.current, workspaces.selection) { _, _ -> Unit }, settings.activeProfileIdFlow,
        combine(settings.activeProfileSelectionRevisions, settings.profileConfigurationRevisions) { _, _ -> Unit }
    ) { _, _, _, _, _ -> Unit }

    val readers: Flow<PreviewFileReader?> = flow<PreviewFileReader?> {
        val scopeJob = requireNotNull(currentCoroutineContext()[Job])
        var closeSource: (() -> Unit)? = null
        try {
            combine(executionChanges, localFolders.snapshots) { _, _ -> Unit }.collect {
                closeSource?.invoke()
                val captured = try { captureReader(scopeJob) } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                } catch (_: Exception) { null }
                closeSource = captured?.second
                emit(captured?.first)
            }
        } finally {
            closeSource?.invoke()
        }
    }.onStart { emit(null) }.flowOn(Dispatchers.IO)

    private suspend fun captureReader(scopeJob: Job): Pair<PreviewFileReader, () -> Unit> {
        val selection = workspaces.selection.value
        val workspacePath = workspaces.currentPath()
        val profile = settings.captureProfileSnapshot()
        val executionMode = mode.currentMode()
        val modeRevision = mode.modeSelectionRevision
        val roots = localFolders.snapshot()
        val closed = AtomicBoolean()
        val owners = LinkedHashMap<String, UiFileExecutionSource>(16, 0.75f, true)
        val source = "tool-preview:${UUID.randomUUID()}:local-roots:${roots.revision}"
        fun checkSource() {
            scopeJob.ensureActive()
            if (closed.get() || workspaces.selection.value !== selection || workspaces.currentPath() != workspacePath ||
                mode.currentMode() != executionMode || mode.modeSelectionRevision != modeRevision) {
                throw CancellationException("Tool preview source changed")
            }
            settings.checkProfileSnapshot(profile)
            roots.checkCurrent()
        }
        fun owner(path: String): UiFileExecutionSource = synchronized(owners) {
            checkSource()
            owners.getOrPut(path) {
                if (owners.size >= 64) owners.remove(owners.keys.first())?.close()
                UiFileExecutionSource("tool-preview", "${selection.id}:$workspacePath:$path", scopeJob, ::checkSource)
            }
        }
        val reader = PreviewFileReader(source) { path ->
            checkSource()
            if (isPrivatePreviewImagePath(images, path) || imageAliases.any { isPrivatePreviewImagePath(File(it), path) }) {
                readPrivatePreviewImage(images, path)?.let { payload ->
                    payload.copy(isCurrent = {
                        runCatching { checkSource(); payload.isCurrent() }.getOrDefault(false)
                    })
                }
            } else try { runBlocking(scopeJob + Dispatchers.IO) {
                val ui = owner(path)
                val execution = executionFactory.freezeForPaths(listOf(path), runtimeOwner = ui.runtimeOwner,
                    checkSource = ui::checkCurrent)
                withContext(execution) {
                    val endpoint = FileBackendRouting().capture(execution, listOf(path), ui::checkCurrent).endpoint(path)
                    val captured = captureToolPreviewFileReader(endpoint.provider, local, images, imageAliases,
                        sourceSuffix = ":$source", capturedIsCurrent = {
                            try { ui.checkCurrent(); execution.checkCurrent(); true }
                            catch (_: CancellationException) { false }
                        })
                    fun checked(block: () -> Boolean): Boolean = try {
                        runBlocking(execution.ownedBlockingContext(scopeJob)) {
                            ui.checkCurrent()
                            execution.checkCurrent()
                            block().also { execution.checkCurrent(); ui.checkCurrent() }
                        }
                    } catch (_: CancellationException) { false }
                    when (val payload = captured?.readVersionedFile(path)) {
                        is PreviewImageBytes -> payload.copy(key = payload.key.copy(source = source),
                            isCurrent = { checked(payload.isCurrent) })
                        is PreviewImageFile -> PreviewImageFile(payload.key.copy(source = source), payload.file,
                            isCurrent = { checked(payload.isCurrent) }, isSameTarget = { checked(payload.isSameTarget) })
                        null -> null
                    }
                }
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
        }
        return reader to {
            closed.set(true)
            synchronized(owners) { owners.values.forEach { it.close() }; owners.clear() }
        }
    }
}

internal fun captureToolPreviewFileReader(
    access: FileAccessProvider,
    local: FileAccessProvider?,
    privateImages: File,
    privateAliases: List<String> = emptyList(),
    sourceSuffix: String = "",
    capturedIsCurrent: (String) -> Boolean = { true },
): PreviewFileReader? {
    val source = try {
        access.cacheIdentity()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return null
    }
    val cacheSource = source + sourceSuffix
    return PreviewFileReader(cacheSource) { path ->
        try {
            if (!capturedIsCurrent(path)) null
            else if (isPrivatePreviewImagePath(privateImages, path) || privateAliases.any { alias ->
                    isPrivatePreviewImagePath(File(alias), path)
                }) {
                readPrivatePreviewImage(privateImages, path)
            } else if (access.cacheIdentity() != source) {
                null
            } else if (!isLocalFolderPath(path) && source.startsWith("local:") && local != null && local.cacheIdentity() == source) {
                val file = local.copyToLocal(path).canonicalFile
                fun sameTarget(): Boolean = capturedIsCurrent(path) && access.cacheIdentity() == source && local.cacheIdentity() == source &&
                    local.copyToLocal(path).canonicalFile == file && access.cacheIdentity() == source
                if (!file.isFile || file.length() !in 1..MAX_PREVIEW_FILE_BYTES.toLong() || !sameTarget()) null else {
                    val version = local.fingerprint(path)
                    if (version == null || !sameTarget()) null else PreviewImageFile(
                        PreviewImageKey(cacheSource, path, version), file,
                        isCurrent = { sameTarget() && local.fingerprint(path) == version && sameTarget() },
                        isSameTarget = ::sameTarget,
                    )
                }
            } else {
                access.openRead(path, source).use { stream ->
                    val bytes = readPreviewBytes(stream, MAX_PREVIEW_FILE_BYTES)
                    if (bytes == null || !capturedIsCurrent(path) || access.cacheIdentity() != source) null
                    else PreviewImageBytes(PreviewImageKey(cacheSource, path, previewBytesHash(bytes)), bytes,
                        isCurrent = { capturedIsCurrent(path) && access.cacheIdentity() == source })
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

private const val MAX_PREVIEW_FILE_BYTES = 20 * 1024 * 1024

private fun isPrivatePreviewImagePath(root: File, path: String): Boolean {
    val normalized = File(path).absoluteFile.toPath().normalize().toFile().path
    return path == root.absolutePath || path.startsWith(root.absolutePath + File.separator) ||
        normalized == root.absolutePath || normalized.startsWith(root.absolutePath + File.separator)
}

private fun readPrivatePreviewImage(root: File, path: String): PreviewImageBytes? {
    // A missing/evicted App cache path must never fall through to the selected SSH backend.
    if (!ToolPreviewImageCache.contains(root, path) || Files.isSymbolicLink(root.toPath())) return null
    val file = File(path)
    if (!file.isFile || file.length() !in 1..ToolPreviewImageCache.MAX_IMAGE_BYTES.toLong()) return null
    val bytes = file.inputStream().use { readPreviewBytes(it, ToolPreviewImageCache.MAX_IMAGE_BYTES) } ?: return null
    val version = previewBytesHash(bytes)
    return PreviewImageBytes(PreviewImageKey("app-private-tool-image", path, version), bytes,
        isCurrent = {
            ToolPreviewImageCache.contains(root, path) && file.isFile && file.length() == bytes.size.toLong() &&
                file.inputStream().use { readPreviewBytes(it, ToolPreviewImageCache.MAX_IMAGE_BYTES) }
                    ?.let(::previewBytesHash) == version
        })
}

private fun readPreviewBytes(input: InputStream, limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Preview read cancelled")
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() > limit - count) return null
        output.write(buffer, 0, count)
    }
    return output.toByteArray().takeIf { it.isNotEmpty() }
}

private fun previewBytesHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
