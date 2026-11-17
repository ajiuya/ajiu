package com.aicode.feature.agent.presentation.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

/** The host captures an immutable source and supplies the version/target validation callbacks. */
class PreviewFileReader(
    val source: String,
    private val read: (String) -> PreviewImagePayload?,
) : (String) -> ByteArray? {
    fun readVersionedFile(path: String): PreviewImagePayload? = if (isLocalPreviewImagePath(path)) read(path) else null
    override fun invoke(path: String): ByteArray? = (readVersionedFile(path) as? PreviewImageBytes)?.bytes
}

internal const val TOOL_PREVIEW_MAX_EDGE_PX = 480
internal const val TOOL_DETAIL_MAX_EDGE_PX = 2048

private class ToolPreviewImageInput(var target: ToolPreviewTarget)

internal class ToolPreviewBitmapCache(private val maxEntries: Int = 3, private val maxBytes: Long = 24L * 1024 * 1024) {
    private val images = LinkedHashMap<Any, Bitmap>(4, 0.75f, true)
    operator fun get(key: Any): Bitmap? = images[key]
    fun put(key: Any, bitmap: Bitmap) {
        if (bitmap.allocationByteCount.toLong() > maxBytes) return
        images[key] = bitmap
        while (images.size > maxEntries || images.values.sumOf { it.allocationByteCount.toLong() } > maxBytes) {
            images.remove(images.keys.first())
        }
    }
    fun remove(key: Any) { images.remove(key) }
    fun clear() = images.clear()
}

internal data class PreviewFrameKey(val sessionId: String?, val callId: String, val tabId: String, val maxEdge: Int)
internal fun previewFrameKey(target: ToolPreviewTarget, maxEdge: Int): PreviewFrameKey? =
    explicitBrowserTabId(target)?.let { PreviewFrameKey(target.sourceSessionId, target.id, it, maxEdge) }

internal fun toolPreviewImageKey(target: ToolPreviewTarget, source: Any): PreviewImageKey? =
    target.imagePath?.takeIf { !target.active && !hidesPreviewBody(target) && isLocalPreviewImagePath(it) }?.let {
        PreviewImageKey(source, it, "${target.sourceSessionId.orEmpty().length}:${target.sourceSessionId.orEmpty()}:${target.id.length}:${target.id}:${target.content}")
    }

@Composable
internal fun rememberToolPreviewBitmap(
    target: ToolPreviewTarget,
    browserManager: BrowserManager?,
    cache: ToolPreviewBitmapCache,
    enabled: Boolean,
    resolveLocalImage: ((String) -> File?)? = null,
    readFileBytes: ((String) -> ByteArray?)? = null,
    maxEdge: Int = TOOL_PREVIEW_MAX_EDGE_PX,
    safety: ToolPreviewSafety = rememberToolPreviewSafety(target),
    samplingPaused: Boolean = false,
): Bitmap? {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val workEnabled = enabled && LocalUiWorkVisible.current && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val samplingEnabled = workEnabled && !samplingPaused
    if (safety.bodyHidden) return null
    val path = remember(target.imagePath, target.name, target.arguments, target.content, target.stage) {
        target.imagePath ?: browserScreenshotPath(target)
    }
    val source = (readFileBytes as? PreviewFileReader)?.source ?: readFileBytes ?: resolveLocalImage
    val input = remember(target.sourceSessionId, target.id, target.name, safety.browserTabId, path, source, maxEdge,
        target.stage == ToolPreviewStage.PREPARING) { ToolPreviewImageInput(target) }
    if (!samplingPaused) input.target = target
    val imageTarget = input.target
    val owner = remember(imageTarget.sourceSessionId, imageTarget.id, imageTarget.name, imageTarget.arguments, imageTarget.stage, path, source, maxEdge) {
        PreviewImageKey(source ?: "unavailable", path.orEmpty(), "${imageTarget.sourceSessionId}:${imageTarget.id}:${imageTarget.name}:${imageTarget.arguments}:${imageTarget.stage}:$maxEdge")
    }
    val frameKey = safety.browserTabId?.let { PreviewFrameKey(target.sourceSessionId, target.id, it, maxEdge) }
    val thumbnailKey = frameKey?.copy(maxEdge = TOOL_PREVIEW_MAX_EDGE_PX)
    val resultVersion = if (imageTarget.active) null else imageTarget.content
    return key(owner, resultVersion) {
        val cachedFrame = remember(cache) {
            if (imageTarget.stage == ToolPreviewStage.PREPARING) null
            else frameKey?.let(cache::get) ?: if (!imageTarget.active) thumbnailKey?.let(cache::get) else null
        }
        val bitmap by produceState(cachedFrame, owner, resultVersion, samplingEnabled, browserManager) {
            if (!samplingEnabled || imageTarget.stage == ToolPreviewStage.PREPARING) return@produceState
            if (!imageTarget.active) {
                (frameKey?.let(cache::get) ?: thumbnailKey?.let(cache::get))?.let { value = it }
                if (path == null || !isLocalPreviewImagePath(path)) return@produceState
                try {
                    val payloadReader: () -> PreviewImagePayload? = {
                        when {
                            readFileBytes is PreviewFileReader -> readFileBytes.readVersionedFile(path)
                            readFileBytes != null -> readFileBytes(path)?.let {
                                PreviewImageBytes(PreviewImageKey(readFileBytes, path, imageBytesVersion(it)), it)
                            }
                            resolveLocalImage != null -> resolveLocalImage(path)?.canonicalFile?.let { file ->
                                val modified = file.lastModified()
                                val size = file.length()
                                PreviewImageFile(PreviewImageKey(resolveLocalImage, path, "$modified:$size"), file,
                                    isCurrent = { file.isFile && file.lastModified() == modified && file.length() == size },
                                    isSameTarget = { resolveLocalImage(path)?.canonicalFile == file })
                            }
                            else -> null
                        }
                    }
                    val loaded = loadVersionedPreviewFileImage(
                        read = payloadReader,
                        maxEdge = maxEdge,
                        decodeBytes = { decodePreviewImage(it, maxEdge) },
                        decodeBounds = { input ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeStream(input, null, bounds)
                            bounds.outWidth to bounds.outHeight
                        },
                        decodeFile = { input, sample ->
                            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
                        },
                        discard = Bitmap::recycle,
                    )
                    currentCoroutineContext().ensureActive()
                    if (loaded != null) value = loaded
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A failed read must not replace this call's last frame. */ }
                return@produceState
            }
            val tabId = safety.browserTabId?.takeIf { wantsBrowserFrames(imageTarget.arguments) } ?: return@produceState
            val manager = browserManager ?: return@produceState
            frameKey?.let(cache::get)?.let { value = it }
            while (true) {
                var pending: Bitmap? = null
                try {
                    pending = manager.captureThumbnail(tabId, maxEdge)
                    currentCoroutineContext().ensureActive()
                    val next = pending
                    if (next != null) {
                        frameKey?.let { cache.put(it, next) }
                        value = next
                        pending = null
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Preserve the previous frame during a transient navigation. */ }
                finally { pending?.recycle() }
                delay(1_000L)
            }
        }
        if (workEnabled) bitmap else null
    }
}

private fun imageBytesVersion(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

private fun decodePreviewImage(bytes: ByteArray, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
        inSampleSize = previewImageSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
    })
}
