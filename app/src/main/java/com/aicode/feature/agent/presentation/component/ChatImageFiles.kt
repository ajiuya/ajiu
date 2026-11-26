package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.aicode.core.ui.ImageDecodeSpec
import com.aicode.core.ui.KeyedDeferredCache
import com.aicode.core.ui.LeasedFileCache
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.DelegatingFileAccess
import com.aicode.feature.workspace.domain.FilePathSource
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object ChatImageFiles {
    private data class Key(val source: String, val path: String, val size: Long, val modified: Long,
        val execution: String?, val owner: RuntimeOwner?)
    private data class DecodeKey(val file: Key, val spec: ImageDecodeSpec)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = LeasedFileCache<Key>(32, 256L * 1024 * 1024, 60_000L, scope = scope)
    private val images = KeyedDeferredCache<DecodeKey, ImageBitmap?>(
        scope, 80, retain = { it != null }, maxWeight = 48L * 1024 * 1024,
        weigh = { key, image -> 256L + 2L * (key.file.source.length.toLong() + key.file.path.length) +
            (image?.asAndroidBitmap()?.allocationByteCount?.toLong() ?: 0L) },
    )
    private val directory by lazy {
        Files.createTempDirectory("aicode_chat_images_").toFile().also {
            scope.launch {
                while (isActive) {
                    delay(30_000L)
                    files.trim()
                }
            }
        }
    }

    suspend fun load(access: FileAccessProvider, path: String, spec: ImageDecodeSpec): ImageBitmap? = withContext(Dispatchers.IO) {
        val sourceContext = WorkspaceExecutionContext.blockingContext()
        val captured = (access as? DelegatingFileAccess)?.capturePathSource(path)
        val expected = captured?.expectedBaseIdentity ?: access.cacheIdentity()
        val identity = captured?.identity ?: expected
        val execution = WorkspaceExecutionContext.current()
        val key = Key(identity, path, access.fileSize(path), access.lastModified(path),
            execution?.fileCacheIdentity, execution?.runtimeOwner)
        checkSource(access, expected, captured)
        val result = images.get(DecodeKey(key, spec)) {
            withContext(sourceContext + Dispatchers.IO) {
                files.acquire(key) {
                    withContext(sourceContext + Dispatchers.IO) {
                        checkSource(access, expected, captured)
                        if (identity.startsWith("local:")) {
                            LeasedFileCache.Resource(access.copyToLocal(path), owned = false).also { checkSource(access, expected, captured) }
                        } else {
                            download(access, path, expected, captured)
                        }
                    }
                }.use { lease ->
                    checkSource(access, expected, captured)
                    val file = lease.file
                    val image = if (file.isFile && file.length() > 0L) {
                        decodeSampledBitmap(file.absolutePath, spec.maxEdge, spec.maxPixels)
                    } else null
                    checkSource(access, expected, captured)
                    image
                }
            }
        }
        currentCoroutineContext().ensureActive()
        checkSource(access, expected, captured)
        result
    }

    private suspend fun download(access: FileAccessProvider, path: String, expected: String, captured: FilePathSource?): LeasedFileCache.Resource {
        val temp = File.createTempFile("image_", ".cache", directory)
        try {
            val context = currentCoroutineContext()
            access.openRead(path, expected).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        checkSource(access, expected, captured, authorize = false)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw java.io.IOException("Image read made no progress")
                        output.write(buffer, 0, count)
                    }
                }
            }
            context.ensureActive()
            checkSource(access, expected, captured)
            return LeasedFileCache.Resource(temp, owned = true)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun checkSource(access: FileAccessProvider, expected: String, captured: FilePathSource?, authorize: Boolean = true) {
        if (authorize) captured?.checkAuthorization() else captured?.checkCurrent()
        if (captured == null && access.cacheIdentity() != expected) throw CancellationException("Image source changed")
    }
}
