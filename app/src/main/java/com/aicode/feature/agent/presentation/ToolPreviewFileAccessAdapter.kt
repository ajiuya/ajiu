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
import com.aicode.feature.workspace.domain.isLocalFolderPath
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.onStart

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class ToolPreviewFileAccessAdapter @Inject constructor(
    private val access: FileAccessProvider,
    private val local: LocalFileAccess,
    mode: ExecutionModeHolder,
    connection: RemoteSshConnection,
    workspaces: WorkspaceRepository,
    settings: ContainerSettingsRepository,
    localFolders: LocalFolderGrantRepository,
    executionFactory: WorkspaceExecutionContextFactory,
    @param:ApplicationContext context: Context,
) {
    private val images = File(context.cacheDir.canonicalFile, ToolPreviewImageCache.DIRECTORY)
    private val imageAliases = listOf(File(context.cacheDir, ToolPreviewImageCache.DIRECTORY).absolutePath)

    private val executionChanges = combine(
        mode.mode, connection.connectionState, workspaces.current,
        settings.activeProfileIdFlow, settings.activeProfileSelectionRevisions
    ) { _, _, _, _, _ -> Unit }

    val readers: Flow<PreviewFileReader?> = combine(executionChanges, localFolders.snapshots) { _, _ -> Unit }
        .mapLatest {
            try {
                val execution = executionFactory.freeze()
                val roots = execution.localFolderSnapshot
                val reader = execution.useCaptured {
                    captureToolPreviewFileReader(access, local, images, imageAliases,
                        sourceSuffix = ":local-roots:${roots.revision}",
                        capturedIsCurrent = { path ->
                            try {
                                execution.checkCurrent()
                                if (isLocalFolderPath(path)) roots.rootFor(path)
                                true
                            } catch (_: CancellationException) {
                                false
                            }
                        })
                }
                reader?.let { captured ->
                    PreviewFileReader(captured.source) { path -> execution.useCaptured { captured.readVersionedFile(path) } }
                }
            } catch (cancelled: CancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                null
            } catch (_: Exception) {
                null
            }
        }.onStart { emit(null) }.flowOn(Dispatchers.IO)
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

private fun readPrivatePreviewImage(root: File, path: String): PreviewImagePayload? {
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
