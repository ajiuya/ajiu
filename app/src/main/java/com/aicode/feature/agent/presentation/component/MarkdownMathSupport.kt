package com.aicode.feature.agent.presentation.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicode.core.ui.KeyedDeferredCache
import com.aicode.core.util.FileLogger
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.State as MarkdownParseState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class LatexLayout(
    val drawable: JLatexMathDrawable?,
    val width: Int,
    val height: Int,
    val fallback: String? = null,
    val textSizePx: Float = 0f,
)

internal data class PreparedMarkdown(
    val raw: String,
    val state: MarkdownParseState.Success,
    val layouts: Map<String, LatexLayout>,
    val bitmaps: Map<String, Bitmap>,
    val textSizePx: Float,
    val colorArgb: Int,
)

internal data class PreparedFormulaResources(
    val layouts: Map<String, LatexLayout>,
    val bitmaps: Map<String, Bitmap>,
)

internal object LatexCache {
    private data class LayoutKey(val latex: String, val textSizePx: Float)
    private data class BitmapKey(val latex: String, val textSizePx: Float, val colorArgb: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val layouts = KeyedDeferredCache<LayoutKey, LatexLayout>(
        scope, 512, maxWeight = MarkdownCacheBudget.FORMULA_LAYOUT,
        weigh = { key, _ -> 2048L + key.latex.length * 128L },
    )
    private val bitmaps = KeyedDeferredCache<BitmapKey, Bitmap>(
        scope, 256, maxWeight = MarkdownCacheBudget.FORMULA_BITMAP,
        weigh = { key, bitmap -> 256L + key.latex.length * 2L + bitmap.allocationByteCount },
    )
    private val linkRegex = Regex("""!\[]\((aicode-math-(?:block|inline)://[^)]+)\)""")

    suspend fun prepare(
        markdown: String,
        textSizePx: Float,
        colorArgb: Int,
        previous: PreparedMarkdown? = null,
    ): PreparedFormulaResources = withContext(Dispatchers.Default) {
        val links = linkRegex.findAll(markdown).map { it.groupValues[1] }.distinct().toList()
        coroutineScope {
            links.map { link ->
                async {
                    val latex = MarkdownPreprocessor.decodeMathLink(link)!!.first
                    val pinned = previous?.takeIf { it.textSizePx == textSizePx }
                    val layout = pinned?.layouts?.get(link) ?: layouts.get(LayoutKey(latex, textSizePx)) {
                        measure(latex, textSizePx)
                    }
                    val bitmap = pinned?.takeIf { it.colorArgb == colorArgb }?.bitmaps?.get(link)
                        ?: bitmaps.get(BitmapKey(latex, textSizePx, colorArgb)) { render(layout, colorArgb) }
                    Triple(link, layout, bitmap)
                }
            }.awaitAll().let { resources ->
                PreparedFormulaResources(resources.associate { it.first to it.second }, resources.associate { it.first to it.third })
            }
        }
    }

    private fun measure(latex: String, textSizePx: Float): LatexLayout = try {
        val drawable = JLatexMathDrawable.builder(latex).textSize(textSizePx).padding(2).build()
        LatexLayout(drawable, drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        FileLogger.w("MarkdownMath", "LaTeX 排版失败", error)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizePx }
        val metrics = paint.fontMetrics
        LatexLayout(null, ceil(paint.measureText(latex)).toInt().coerceAtLeast(1) + 4,
            ceil(metrics.descent - metrics.ascent).toInt().coerceAtLeast(1) + 4, latex, textSizePx)
    }

    private fun render(layout: LatexLayout, colorArgb: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val drawable = layout.drawable
        if (drawable != null) {
            // 同一排版的 TeXIcon 可变；只串行化该公式的不同颜色绘制，不限制其它公式。
            synchronized(layout) {
                drawable.icon().setForeground(ru.noties.jlatexmath.awt.Color(colorArgb))
                drawable.setBounds(0, 0, layout.width, layout.height)
                drawable.draw(canvas)
            }
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; textSize = layout.textSizePx }
            canvas.drawText(layout.fallback.orEmpty(), 2f, 2f - paint.fontMetrics.ascent, paint)
        }
        return bitmap
    }

    fun layoutStats() = layouts.stats()
    fun bitmapStats() = bitmaps.stats()
}

internal class MathImageTransformer(
    private val delegate: ImageTransformer,
    private val layouts: Map<String, LatexLayout>,
    private val bitmaps: Map<String, Bitmap>,
) : ImageTransformer {
    override fun placeholderConfig(
        link: String,
        density: androidx.compose.ui.unit.Density,
        containerSize: Size,
        imageWidth: com.mikepenz.markdown.model.ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        if (MarkdownPreprocessor.decodeMathLink(link) == null) {
            return delegate.placeholderConfig(link, density, containerSize, imageWidth, imageSize, imageSizeChanged)
        }
        val layout = requireNotNull(layouts[link])
        return PlaceholderConfig(
            size = with(density) { Size(layout.width.toDp().value, layout.height.toDp().value) },
            verticalAlign = PlaceholderVerticalAlign.Bottom,
        )
    }

    @Composable
    override fun transform(link: String): ImageData? {
        val spec = MarkdownPreprocessor.decodeMathLink(link) ?: return delegate.transform(link)
        val layout = requireNotNull(layouts[link])
        val bitmap = requireNotNull(bitmaps[link])
        val density = LocalDensity.current
        val painter = remember(bitmap, layout) { FixedLatexPainter(bitmap.asImageBitmap(), layout.width, layout.height) }
        val widthDp = with(density) { layout.width.toDp() }
        val heightDp = with(density) { layout.height.toDp() }
        val base = if (spec.second) Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState()) else Modifier
        return ImageData(painter = painter, modifier = base.then(Modifier.size(widthDp, heightDp)), contentScale = ContentScale.Fit)
    }

    private class FixedLatexPainter(
        private val bitmap: androidx.compose.ui.graphics.ImageBitmap,
        widthPx: Int,
        heightPx: Int,
    ) : Painter() {
        override val intrinsicSize = Size(widthPx.toFloat(), heightPx.toFloat())
        override fun DrawScope.onDraw() {
            val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
            val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            drawImage(bitmap, dstSize = IntSize(width, height), dstOffset = IntOffset(
                ((size.width - width) / 2f).roundToInt(), ((size.height - height) / 2f).roundToInt(),
            ))
        }
    }
}
