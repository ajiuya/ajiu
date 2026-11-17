package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.semanticColors
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.State as MarkdownParseState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import dev.snipme.highlights.model.SyntaxTheme
import com.aicode.core.ui.KeyedDeferredCache
import com.aicode.feature.agent.presentation.preview.UiWorkEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 代码块与行内代码共用字体。系统等宽（FontFamily.Monospace）缺下标字形且不回落，
// 内置 JetBrains Mono NL 经资源加载可走系统 fallback，能显示 ₀-₉ 等下标/上标字符。
private val CodeFontFamily = FontFamily(Font(R.font.jetbrains_mono_nl))

internal fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.1fB".format(tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fk".format(tokens / 1_000.0)
    else -> tokens.toString()
}

internal data class MarkdownPreparation(val content: PreparedMarkdown? = null, val failed: Boolean = false)

@Composable
internal fun rememberMarkdownPreparation(
    text: String,
    cache: MarkdownRenderCache,
    textSizePx: Float,
    colorArgb: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    prepare: suspend (String, PreparedMarkdown?) -> MarkdownPreparation = { raw, previous ->
        val processed = MarkdownPreprocessor.resolve(raw)
        when (val parsed = cache.resolve(processed)) {
            is MarkdownParseState.Success -> {
                val resources = LatexCache.prepare(parsed.content, textSizePx, colorArgb, previous)
                MarkdownPreparation(PreparedMarkdown(raw, parsed, resources.layouts, resources.bitmaps, textSizePx, colorArgb))
            }
            else -> MarkdownPreparation(failed = parsed is MarkdownParseState.Error)
        }
    },
): MarkdownPreparation {
    val latestText by rememberUpdatedState(text)
    val latestPrepare by rememberUpdatedState(prepare)
    var result by remember(cache, textSizePx, colorArgb) {
        mutableStateOf(MarkdownPreparation(cache.displayedPrefix(text, textSizePx, colorArgb)))
    }
    UiWorkEffect(cache, textSizePx, colorArgb, dispatcher) {
        // 允许在途前缀完成；逐字取消会让慢排版在持续流中无法发布。
        snapshotFlow { latestText }.collect { raw ->
            val previous = result.content?.takeIf { raw.startsWith(it.raw) }
            val prepareInput = latestPrepare
            val prepared = withContext(dispatcher) { prepareInput(raw, previous) }
            currentCoroutineContext().ensureActive()
            if (latestText.startsWith(raw)) {
                prepared.content?.let(cache::rememberDisplayed)
                result = prepared.copy(content = prepared.content ?: previous)
            }
        }
    }
    return result.copy(content = result.content?.takeIf { text.startsWith(it.raw) })
}

@Composable
internal fun MarkdownContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    compact: Boolean = false,
    /** 首版排版准备期间的占位；更新期间继续显示上一版完整 Markdown。 */
    loading: (@Composable () -> Unit)? = null,
    /** 长文本虚拟化渲染：正文改用 LazyColumn 逐块渲染（调用方需配合 heightIn 等有限高度约束
     *  才能触发懒加载），避免超大 md 一次性全量组合/测量造成的卡顿。 */
    lazyScroll: Boolean = false,
    onRendered: ((String) -> Unit)? = null,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val semantic = MaterialTheme.semanticColors

    // markdownColor / markdownTypography / markdownPadding / markdownDimens / markdownComponents
    // 都是库提供的 @Composable 工厂函数（内部自带 remember 记忆化），不能也无需再套一层
    // remember（套了会报 “@Composable invocations can only happen from ...”）。直接调用即可。
    val mdColors = markdownColor(
        text = color,
        codeBackground = semantic.mutedSurface,
        inlineCodeBackground = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.22f else 0.12f),
        dividerColor = semantic.subtleBorder,
        tableBackground = semantic.mutedSurface,
    )

    val typography = MaterialTheme.typography
    // compact：正文、列表用 bodySmall，标题降一档，用于思考气泡等次要文本区域
    val scale = LocalChatTextScale.current
    val body = (if (compact) typography.bodySmall else typography.bodyMedium).scaledForChat(scale)
    val bodyLineHeight = (if (compact) 18.sp else 20.sp) * scale
    val codeSize = (if (compact) 12.sp else 13.sp) * scale
    val mdTypography = markdownTypography(
        h1 = (if (compact) typography.titleMedium else typography.headlineSmall).scaledForChat(scale).copy(fontWeight = FontWeight.Bold, color = color),
        h2 = (if (compact) typography.titleSmall else typography.titleLarge).scaledForChat(scale).copy(fontWeight = FontWeight.Bold, color = color),
        h3 = (if (compact) typography.bodyLarge else typography.titleMedium).scaledForChat(scale).copy(fontWeight = FontWeight.SemiBold, color = color),
        h4 = (if (compact) typography.bodyMedium else typography.titleSmall).scaledForChat(scale).copy(fontWeight = FontWeight.SemiBold, color = color),
        h5 = (if (compact) typography.bodySmall else typography.bodyLarge).scaledForChat(scale).copy(fontWeight = FontWeight.Medium, color = color),
        h6 = (if (compact) typography.bodySmall else typography.bodyMedium).scaledForChat(scale).copy(fontWeight = FontWeight.Medium, color = color),
        paragraph = body.copy(color = color, lineHeight = bodyLineHeight),
        code = TextStyle(fontFamily = CodeFontFamily, fontSize = codeSize, color = MaterialTheme.colorScheme.onSurface),
        inlineCode = TextStyle(fontFamily = CodeFontFamily, fontSize = body.fontSize, color = MaterialTheme.colorScheme.onSurface),
        ordered = body.copy(color = color, lineHeight = bodyLineHeight),
        bullet = body.copy(color = color, lineHeight = bodyLineHeight),
        table = typography.bodySmall.scaledForChat(scale).copy(color = color),
    )

    val mdPadding = markdownPadding(
        block = 4.dp,
        list = 2.dp,
        listItemBottom = 1.dp,
        listIndent = 12.dp,
        codeBlock = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    )

    val mdDimens = markdownDimens(
        codeBackgroundCornerSize = 6.dp,
        tableCellPadding = 6.dp,
        tableCornerSize = 6.dp,
    )

    val highlightTheme = remember(isDark) { SyntaxThemes.atom(darkMode = isDark) }
    val renderCache = cache ?: remember { MarkdownRenderCache() }
    val baseTransformer = LocalMarkdownImageTransformer.current
    val density = LocalDensity.current
    val textSizePx = with(density) { body.fontSize.toPx() }
    val colorArgb = color.toArgb()
    val preparation = rememberMarkdownPreparation(text, renderCache, textSizePx, colorArgb)
    val prepared = preparation.content
    val parseFailed = preparation.failed
    val mathTransformer = remember(baseTransformer, prepared) {
        MathImageTransformer(baseTransformer, prepared?.layouts.orEmpty(), prepared?.bitmaps.orEmpty())
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color
    ) {
        val renderState = prepared?.state
        // 打字进度追平时，异步排版仍可能展示上一版前缀。
        val renderedSource = prepared?.raw ?: text.takeIf { loading == null && parseFailed }
        SideEffect { renderedSource?.let { onRendered?.invoke(it) } }

        if (renderState != null) {
            // 长文本用 LazyColumn 逐块渲染时，animations（文本尺寸动画等）不适用，属预期。
            // 下面三个对象（successRenderer / mdComponents / mdAnimations）均 remember：不缓存的话
            // 每次重组都新建 lambda 实例，令下游 Markdown 无法 skip。
            val successRenderer: @Composable (
                state: MarkdownParseState.Success,
                components: MarkdownComponents,
                modifier: Modifier
            ) -> Unit = remember(lazyScroll) {
                if (lazyScroll) {
                    { state, components, m ->
                        LazyColumn(modifier = m) {
                            items(
                                items = state.node.children,
                                key = { node -> node.startOffset },
                                contentType = { node -> node.type }
                            ) { node ->
                                // 列表位置按偏移保留，节点内部状态与当前 AST 绑定。
                                key(node) {
                                    MarkdownElement(node, components, state.content)
                                }
                            }
                        }
                    }
                } else {
                    { state, components, m ->
                        MarkdownSuccess(state = state, components = components, modifier = m)
                    }
                }
            }

            // markdownComponents 是 @Composable（内部自带记忆化），不能套 remember，直接调用。
            val mdComponents = markdownComponents(
                // 外层 SelectionContainer（MessageBubbles）统一负责选区；超长助手消息已由
                // AIChatPanel 拆成多条有界 item，不存在超长单 item 的选区树/交互失效问题。
                codeFence = {
                    MarkdownCodeFence(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightTheme, showHeader = true)
                    }
                },
                codeBlock = {
                    MarkdownCodeBlock(
                        content = it.content,
                        node = it.node,
                    ) { code, language, style ->
                        SafeMarkdownHighlightedCode(code, language, style, highlightTheme, showHeader = true)
                    }
                },
                // 库默认 maxLines=1 + Ellipsis，单元格长文会被截断；这里放开为完整多行显示。
                table = {
                    MarkdownTable(
                        content = it.content,
                        node = it.node,
                        style = it.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, header, tableWidth, style ->
                            MarkdownTableRow(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                },
            )

            Markdown(
                state = renderState,
                modifier = modifier,
                colors = mdColors,
                typography = mdTypography,
                padding = mdPadding,
                dimens = mdDimens,
                // 关闭段落文本的 animateContentSize：快速流式更新下它会持续追赶目标高度，反而弹性抖动。
                animations = markdownAnimations(animateTextSize = { this }),
                imageTransformer = mathTransformer,
                components = mdComponents,
                success = successRenderer,
            )
        } else if (loading != null) {
            loading()
        } else if (parseFailed) {
            PlainMarkdownText(
                text = text,
                color = color,
                modifier = modifier
            )
        }
    }
}

/**
 * 代码块高亮渲染：等价于库的 `MarkdownHighlightedCode`，但在把高亮区间写进 AnnotatedString
 * 之前先校验范围。highlights 引擎对部分内容会给出 start > end 的区间，库原实现直接调用
 * addStyle，AnnotatedString.Range 构造随即抛 "Reversed range is not supported"；该计算跑在
 * 库自己的后台协程里（produceState + Dispatchers.Default），异常直达线程默认处理器，
 * 调用方 try/catch 拦不住，表现为聊天页闪退。越界（end > code.length）同样会导致崩溃，
 * 一并丢弃。
 */
/**
 * 代码高亮结果的进程级缓存。高亮本身跑在 Dispatchers.Default（不卡主线程），但无跨视口
 * 缓存时 item 每次重进视口都会重跑高亮，完成后又触发一次主线程重组（从纯文本切到
 * 高亮文本）。fling 快速滚过大量代码块时这会挤满 Default 线程并制造密集重组。命中缓存
 * 时直接同步拿到结果作为 produceState 初值，既不重算也不闪纯文本。key 含 isDark（主题影响颜色）。
 */
internal object CodeHighlightCache {
    private data class Key(val code: String, val language: String?, val dark: Boolean)

    private val cache = KeyedDeferredCache<Key, AnnotatedString>(
        CoroutineScope(SupervisorJob() + Dispatchers.Default), 128,
        maxWeight = MarkdownCacheBudget.HIGHLIGHT,
        weigh = { key, value -> MarkdownCacheBudget.textPair(key.code, value.text) +
            2L * (key.language?.length ?: 0) + 192L * value.spanStyles.size },
    )

    fun get(code: String, language: String?, dark: Boolean): AnnotatedString? = cache.peek(Key(code, language, dark))
    suspend fun resolve(code: String, language: String?, dark: Boolean, theme: SyntaxTheme): AnnotatedString =
        cache.get(Key(code, language, dark)) { buildHighlightedText(code, language, theme) }
    fun stats() = cache.stats()
}

@Composable
private fun SafeMarkdownHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
    highlightTheme: SyntaxTheme,
    showHeader: Boolean,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cached = CodeHighlightCache.get(code, language, isDark)
    var highlighted by remember(code, language, isDark) { mutableStateOf(cached ?: AnnotatedString(code)) }
    UiWorkEffect(code, language, isDark) {
        highlighted = CodeHighlightCache.resolve(code, language, isDark, highlightTheme)
    }

    MarkdownCodeBackground(
        color = LocalMarkdownColors.current.codeBackground,
        shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        showHeader = showHeader,
        language = language,
        code = code,
    ) {
        MarkdownBasicText(
            text = highlighted,
            style = style,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(LocalMarkdownPadding.current.codeBlock),
        )
    }
}

private fun buildHighlightedText(
    code: String,
    language: String?,
    highlightTheme: SyntaxTheme,
): AnnotatedString {
    // 高亮引擎本身也可能抛错（语言解析等），失败时退回纯文本，不让它影响消息渲染。
    val highlights = runCatching {
        val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) } ?: SyntaxLanguage.DEFAULT
        Highlights.Builder().theme(highlightTheme).code(code)
            .language(syntaxLanguage)
            .build()
            .getHighlights()
    }.getOrDefault(emptyList())

    return buildAnnotatedString {
        append(code)
        for (highlight in highlights) {
            val start = highlight.location.start
            val end = highlight.location.end
            if (start < 0 || start >= end || end > code.length) continue
            val spanStyle = when (highlight) {
                is ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(spanStyle, start, end)
        }
    }
}

@Composable
private fun PlainMarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = color, lineHeight = 20.sp)
            .scaledForChat(LocalChatTextScale.current)
    )
}
