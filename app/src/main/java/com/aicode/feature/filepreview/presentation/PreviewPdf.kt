package com.aicode.feature.filepreview.presentation

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import java.io.File
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun PreviewPdf(file: File, modifier: Modifier = Modifier, onError: () -> Unit) {
    val session = remember(file) { PdfPreviewSession(file) }
    val latestOnError by rememberUpdatedState(onError)
    var pageCount by remember(session) { mutableIntStateOf(0) }
    var failed by remember(session) { mutableStateOf(false) }

    DisposableEffect(session) {
        onDispose { session.invalidate() }
    }
    LaunchedEffect(session) {
        try {
            val openedPageCount = session.open()
            if (session.isClosed) return@LaunchedEffect
            pageCount = openedPageCount
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!session.isClosed) {
                failed = true
                latestOnError()
            }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.semanticColors.pageBackground)) {
        val renderWidth = (constraints.maxWidth - with(LocalDensity.current) {
            (Spacing.md * 2).roundToPx()
        }).coerceIn(1, PdfPageCache.MAX_WIDTH_PX)
        when {
            failed -> Text(
                stringResource(R.string.file_preview_error),
                Modifier.align(Alignment.Center).padding(Spacing.lg),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            pageCount == 0 -> PdfLoading(Modifier.align(Alignment.Center))
            else -> {
                val listState = rememberLazyListState()
                val visiblePages by remember(listState) {
                    derivedStateOf {
                        val layout = listState.layoutInfo
                        layout.visibleItemsInfo.asSequence().filter {
                            it.offset < layout.viewportEndOffset &&
                                it.offset.toLong() + it.size > layout.viewportStartOffset
                        }.map { it.index }.toSet()
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    items(count = pageCount, key = { it }) { index ->
                        PdfPreviewPage(session, index, pageCount, renderWidth, index in visiblePages) {
                            latestOnError()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPreviewPage(
    session: PdfPreviewSession,
    index: Int,
    pageCount: Int,
    renderWidth: Int,
    visible: Boolean,
    onError: () -> Unit
) {
    val pageColor = with(MaterialTheme.colorScheme) {
        if (surface.luminance() >= onSurface.luminance()) surface else onSurface
    }.toArgb()
    var bitmap by remember(session, index, renderWidth, pageColor) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(session, index, renderWidth, pageColor) { mutableStateOf(false) }
    val latestOnError by rememberUpdatedState(onError)
    LaunchedEffect(session, index, renderWidth, visible, pageColor) {
        if (!visible) {
            bitmap = null
            return@LaunchedEffect
        }
        failed = false
        try {
            val rendered = session.page(index, renderWidth, pageColor)
            currentCoroutineContext().ensureActive()
            if (!session.isClosed) bitmap = rendered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (!session.isClosed) {
                failed = true
                latestOnError()
            }
        }
    }
    val pageLabel = stringResource(R.string.file_preview_page, index + 1, pageCount)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(pageLabel, color = MaterialTheme.semanticColors.subtleText, style = MaterialTheme.typography.labelMedium)
        val currentBitmap = bitmap
        when {
            currentBitmap != null -> {
                val displayHeight = with(LocalDensity.current) {
                    min(
                        renderWidth.toDouble() * currentBitmap.height / currentBitmap.width,
                        PdfPageCache.MAX_HEIGHT_PX.toDouble()
                    ).toFloat().toDp()
                }
                Image(
                    bitmap = remember(currentBitmap) { currentBitmap.asImageBitmap() },
                    contentDescription = pageLabel,
                    modifier = Modifier.fillMaxWidth().height(displayHeight),
                    contentScale = ContentScale.Fit
                )
            }
            else -> Box(
                Modifier.fillMaxWidth().height(Spacing.xxl * 4).background(MaterialTheme.semanticColors.mutedSurface),
                contentAlignment = Alignment.Center
            ) {
                if (failed) Text(
                    stringResource(R.string.file_preview_error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                ) else PdfLoading()
            }
        }
    }
}

@Composable
private fun PdfLoading(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(R.string.file_preview_loading),
            color = MaterialTheme.semanticColors.subtleText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private class PdfPreviewSession(private val file: File) {
    private val mutex = Mutex()
    private val cache = PdfPageCache()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    val isClosed: Boolean get() = cache.isClosed

    suspend fun open(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            check(!isClosed)
            val openedDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = openedDescriptor
            val openedRenderer = PdfRenderer(openedDescriptor)
            renderer = openedRenderer
            currentCoroutineContext().ensureActive()
            check(!isClosed)
            openedRenderer.pageCount.also { check(it > 0) }
        }
    }

    suspend fun page(index: Int, width: Int, background: Int): Bitmap =
        cache.load(PdfPageCache.Key(index, width, background)) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                check(!isClosed)
                val activeRenderer = checkNotNull(renderer)
                var pending: Bitmap? = null
                try {
                    activeRenderer.openPage(index).use { page ->
                        val size = PdfPageCache.renderSize(page.width, page.height, width)
                        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                        pending = bitmap
                        bitmap.eraseColor(background)
                        val matrix = Matrix().apply {
                            setScale(size.width.toFloat() / page.width, size.height.toFloat() / page.height)
                        }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                } catch (failure: Throwable) {
                    pending?.recycle()
                    throw failure
                }
            }
        }

    fun invalidate() = cache.close()

    suspend fun close() {
        invalidate()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val openedRenderer = renderer
                val openedDescriptor = descriptor
                renderer = null
                descriptor = null
                try {
                    openedRenderer?.close()
                } finally {
                    openedDescriptor?.close()
                }
            }
        }
    }
}

internal class PdfPageCache(
    private val maxBytes: Long = MAX_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    internal data class Key(val index: Int, val width: Int, val background: Int)
    internal data class RenderSize(val width: Int, val height: Int)
    private val entries = LinkedHashMap<Key, Bitmap>(16, 0.75f, true)
    var byteCount: Long = 0
        private set
    @Volatile
    var isClosed: Boolean = false
        private set

    init {
        require(maxBytes in 0..MAX_BYTES)
    }

    @Synchronized
    operator fun get(key: Key): Bitmap? = if (isClosed) null else entries[key]

    suspend fun load(key: Key, render: suspend () -> Bitmap): Bitmap {
        currentCoroutineContext().ensureActive()
        if (isClosed) throw CancellationException("PDF preview closed")
        this[key]?.let { return it }
        var pending: Bitmap? = null
        try {
            val bitmap = withContext(dispatcher) { render().also { pending = it } }
            currentCoroutineContext().ensureActive()
            if (!publish(key, bitmap)) throw CancellationException("PDF preview closed")
            pending = null
            return bitmap
        } finally {
            // Only a bitmap that has never reached Compose or the cache may be recycled.
            pending?.recycle()
        }
    }

    @Synchronized
    fun publish(key: Key, bitmap: Bitmap): Boolean {
        if (isClosed) return false
        val bytes = bitmap.allocationByteCount.toLong()
        entries.remove(key)?.let { byteCount -= it.allocationByteCount }
        if (bytes <= maxBytes) {
            entries[key] = bitmap
            byteCount += bytes
        }
        val iterator = entries.entries.iterator()
        while (byteCount > maxBytes && iterator.hasNext()) {
            byteCount -= iterator.next().value.allocationByteCount
            iterator.remove()
        }
        return true
    }

    @Synchronized
    fun close() {
        isClosed = true
        // Compose and render-thread draw commands may still own these published bitmaps.
        entries.clear()
        byteCount = 0
    }

    companion object {
        const val MAX_BYTES = 24L * 1024 * 1024
        const val MAX_WIDTH_PX = 1600
        const val MAX_HEIGHT_PX = 4096
        const val MAX_PAGE_PIXELS = 2L * 1024 * 1024

        fun renderSize(pageWidth: Int, pageHeight: Int, viewportWidth: Int): RenderSize {
            require(pageWidth > 0 && pageHeight > 0 && viewportWidth > 0)
            val width = min(viewportWidth, MAX_WIDTH_PX).toDouble()
            val area = pageWidth.toDouble() * pageHeight.toDouble()
            val scale = min(
                width / pageWidth,
                min(MAX_HEIGHT_PX.toDouble() / pageHeight, sqrt(MAX_PAGE_PIXELS.toDouble() / area))
            )
            val renderedWidth = floor(pageWidth * scale).toInt().coerceIn(1, width.toInt())
            val renderedHeight = floor(pageHeight * scale).toInt().coerceIn(1, MAX_HEIGHT_PX)
            return RenderSize(renderedWidth, renderedHeight)
        }
    }
}
