package com.aicode.feature.agent.presentation.preview

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A path alone cannot own an image across workspace, connection or result changes. */
data class PreviewImageKey(val source: Any, val path: String, val version: String)

sealed interface PreviewImagePayload {
    val key: PreviewImageKey
}

data class PreviewImageBytes(
    override val key: PreviewImageKey,
    val bytes: ByteArray,
    val isCurrent: () -> Boolean = { true },
) : PreviewImagePayload

class PreviewImageFile(
    override val key: PreviewImageKey,
    val file: File,
    val isCurrent: () -> Boolean,
    val isSameTarget: () -> Boolean,
) : PreviewImagePayload

/** Closing never recycles a published image: Compose may still be displaying it. */
internal class PreviewImageLease<T>(val key: PreviewImageKey) : AutoCloseable {
    private var closed = false
    @Synchronized fun publish(value: T): T? = if (closed) null else value
    @Synchronized override fun close() { closed = true }
}

internal const val PREVIEW_MAX_IMAGE_BYTES = 20 * 1024 * 1024

internal suspend fun <T : Any> loadVersionedPreviewFileImage(
    read: () -> PreviewImagePayload?,
    maxEdge: Int?,
    decodeBytes: (ByteArray) -> T?,
    decodeBounds: (InputStream) -> Pair<Int, Int>?,
    decodeFile: (InputStream, Int) -> T?,
    discard: (T) -> Unit,
    io: CoroutineDispatcher = Dispatchers.IO,
    cpu: CoroutineDispatcher = Dispatchers.Default,
): T? {
    val snapshot = runInterruptible(io) { read() } ?: return null
    val lease = PreviewImageLease<T>(snapshot.key)
    var pending: T? = null
    try {
        val valid = when (snapshot) {
            is PreviewImageBytes -> {
                if (!withContext(io) { snapshot.isCurrent() }) return null
                withContext(cpu) {
                    currentCoroutineContext().ensureActive()
                    if (snapshot.bytes.size in 1..PREVIEW_MAX_IMAGE_BYTES) pending = decodeBytes(snapshot.bytes)
                }
                withContext(io) { snapshot.isCurrent() }
            }
            is PreviewImageFile -> withContext(io) {
                currentCoroutineContext().ensureActive()
                if (!snapshot.file.isFile || snapshot.file.length() !in 1..PREVIEW_MAX_IMAGE_BYTES.toLong() ||
                    !snapshot.isCurrent() || !snapshot.isSameTarget()) return@withContext false
                currentCoroutineContext().ensureActive()
                val sample = if (maxEdge == null) 1 else {
                    val bounds = snapshot.file.inputStream().use(decodeBounds) ?: return@withContext false
                    currentCoroutineContext().ensureActive()
                    if (bounds.first <= 0 || bounds.second <= 0 || !snapshot.isCurrent() || !snapshot.isSameTarget()) {
                        return@withContext false
                    }
                    previewImageSampleSize(bounds.first, bounds.second, maxEdge)
                }
                currentCoroutineContext().ensureActive()
                snapshot.file.inputStream().use { pending = decodeFile(it, sample) }
                currentCoroutineContext().ensureActive()
                snapshot.isCurrent() && snapshot.isSameTarget()
            }
        }
        currentCoroutineContext().ensureActive()
        if (!valid) return null
        val image = pending ?: return null
        return lease.publish(image)?.also { pending = null }
    } finally {
        try { pending?.let(discard) } finally { lease.close() }
    }
}

internal suspend fun <T : Any> loadPreviewImage(
    lease: PreviewImageLease<T>,
    read: () -> ByteArray?,
    decode: (ByteArray) -> T?,
    discard: (T) -> Unit = {},
    io: CoroutineDispatcher = Dispatchers.IO,
    cpu: CoroutineDispatcher = Dispatchers.Default,
): T? {
    val bytes = runInterruptible(io) { read() } ?: return null
    if (bytes.size !in 1..PREVIEW_MAX_IMAGE_BYTES) return null
    var pending: T? = null
    try {
        withContext(cpu) { pending = decode(bytes) }
        currentCoroutineContext().ensureActive()
        return pending?.let(lease::publish)?.also { pending = null }
    } finally { pending?.let(discard) }
}

internal fun previewImageSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (maxOf(width, height).toLong() / sample > maxEdge.coerceAtLeast(1) && sample < (1 shl 30)) sample *= 2
    return sample
}
