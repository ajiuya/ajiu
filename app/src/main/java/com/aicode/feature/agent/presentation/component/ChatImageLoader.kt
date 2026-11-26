package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.aicode.core.ui.ImageDecodeSpec
import com.aicode.core.ui.KeyedDeferredCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.aicode.core.ui.ImageSource
import com.aicode.core.ui.ImageViewerRequest
import com.aicode.core.ui.decodeSampledBitmap
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChatImageLoader"

/**
 * 聊天区图片加载器：把 [ImageSource] 落成位图交给全屏查看器。全项目只有这一份解码策略。
 *
 * [ImageSource.ContainerPath] 经 [FileAccessProvider.copyToLocal] 落到宿主文件 —— 本地 PRoot 模式
 * 只是路径映射、不产生拷贝；远程 SSH 模式使用与 Markdown 共享的来源隔离文件租约。
 */
internal fun chatImageLoader(
    fileAccess: FileAccessProvider,
    maxEdge: Int,
    maxPixels: Long,
    captureFile: (suspend (String) -> WorkspaceExecutionContext)? = null
): suspend (ImageSource) -> ImageBitmap? = { source ->
    withContext(Dispatchers.IO) {
        try {
            when (source) {
                is ImageSource.ContainerPath -> {
                    val execution = captureFile?.invoke(source.path)
                    if (execution == null) ChatImageFiles.load(fileAccess, source.path, ImageDecodeSpec(maxEdge, maxPixels))
                    else withContext(execution) {
                        execution.checkCurrent()
                        ChatImageFiles.load(fileAccess, source.path, ImageDecodeSpec(maxEdge, maxPixels)).also { execution.checkCurrent() }
                    }
                }
                else -> ChatLocalImageCache.load(source, ImageDecodeSpec(maxEdge, maxPixels))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "图片加载失败", e)
            null
        }
    }
}

private object ChatLocalImageCache {
    private data class Key(val source: ImageSource, val spec: ImageDecodeSpec, val size: Long = 0L, val modified: Long = 0L)
    private val images = KeyedDeferredCache<Key, ImageBitmap?>(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), 32, retain = { it != null },
        maxWeight = 48L * 1024 * 1024,
        weigh = { key, image -> 256L + 2L * when (val source = key.source) {
            is ImageSource.Base64 -> source.data.length
            is ImageSource.LocalFile -> source.path.length
            is ImageSource.ContainerPath -> source.path.length
        } + (image?.asAndroidBitmap()?.allocationByteCount?.toLong() ?: 0L) },
    )

    suspend fun load(source: ImageSource, spec: ImageDecodeSpec): ImageBitmap? {
        val file = (source as? ImageSource.LocalFile)?.let { File(it.path) }
        val key = Key(source, spec, file?.length() ?: 0L, file?.lastModified() ?: 0L)
        return images.get(key) {
            when (source) {
                is ImageSource.LocalFile -> file?.takeIf { it.isFile && it.length() > 0L }
                    ?.let { decodeSampledBitmap(it.absolutePath, spec.maxEdge, spec.maxPixels) }
                is ImageSource.Base64 -> decodeSampledBitmap(Base64.getDecoder().decode(source.data), spec.maxEdge, spec.maxPixels)
                is ImageSource.ContainerPath -> error("Container images require a file lease")
            }
        }
    }
}

/**
 * 待发送图片附件的查看请求。
 *
 * `localPath` 优先、base64 兜底：`decodeByteArray` 要完整 byte[] 常驻，加上本来就压在内存里的
 * base64 字符串（UTF-16 实占两倍），一张 5MB 的图瞬时要多吃十几 MB；从磁盘解码没这份开销。
 */
internal fun PendingUploadAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        image?.base64Data?.takeIf { it.isNotBlank() }?.let { ImageSource.Base64(it) }
    ),
    title = fileName
)

/**
 * 已发送附件的查看请求。
 *
 * 带上 `containerPath` 兜底：远程模式下 `sendFile` 记的 `localPath` 来自 `createTempFile` +
 * `deleteOnExit`，进程重启或缓存被清掉后文件就没了，这时得能退回容器路径重新拉一次。
 */
internal fun AgentAttachment.toViewerRequest(): ImageViewerRequest = ImageViewerRequest(
    sources = listOfNotNull(
        localPath.takeIf { it.isNotBlank() }?.let { ImageSource.LocalFile(it) },
        containerPath.takeIf { it.isNotBlank() }?.let { ImageSource.ContainerPath(it) }
    ),
    title = fileName
)

/**
 * Markdown 内嵌图片的查看请求：link 就是 AI 视角的容器路径。
 *
 * 库把 alt 直接交给 Image 当 contentDescription，不经过 transformer，所以这里拿不到它，
 * 退而取路径末段的文件名当标题。
 */
internal fun markdownViewerRequest(link: String): ImageViewerRequest = ImageViewerRequest(
    sources = listOf(ImageSource.ContainerPath(link)),
    title = link.trimEnd('/').substringAfterLast('/')
)
