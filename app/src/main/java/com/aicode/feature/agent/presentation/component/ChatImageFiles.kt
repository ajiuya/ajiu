package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.aicode.core.ui.ImageDecodeSpec
import com.aicode.core.ui.KeyedDeferredCache
import com.aicode.core.ui.LeasedFileCache
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.feature.workspace.domain.FileAccessProvider
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
    private data class Key(val source: String, val path: String, val size: Long, val modified: Long)
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
        val identity = access.cacheIdentity()
        val key = Key(identity, path, access.fileSize(path), access.lastModified(path))
        checkSource(access, identity)
        val result = images.get(DecodeKey(key, spec)) {
            files.acquire(key) {
                checkSource(access, identity)
                if (identity.startsWith("local:")) {
                    LeasedFileCache.Resource(access.copyToLocal(path), owned = false).also { checkSource(access, identity) }
                } else {
                    download(access, path, identity)
                }
            }.use { lease ->
                checkSource(access, identity)
                val file = lease.file
                val image = if (file.isFile && file.length() > 0L) {
                    decodeSampledBitmap(file.absolutePath, spec.maxEdge, spec.maxPixels)
                } else null
                checkSource(access, identity)
                image
            }
        }
        currentCoroutineContext().ensureActive()
        checkSource(access, identity)
        result
    }

    private suspend fun download(access: FileAccessProvider, path: String, identity: String): LeasedFileCache.Resource {
        val temp = File.createTempFile("image_", ".cache", directory)
        try {
            val context = currentCoroutineContext()
            access.openRead(path, identity).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            context.ensureActive()
            checkSource(access, identity)
            return LeasedFileCache.Resource(temp, owned = true)
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun checkSource(access: FileAccessProvider, identity: String) {
        if (access.cacheIdentity() != identity) throw CancellationException("Image source changed")
    }
}
