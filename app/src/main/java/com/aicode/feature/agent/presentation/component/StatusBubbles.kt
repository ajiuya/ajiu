package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.presentation.preview.UiWorkEffect
import com.aicode.feature.agent.domain.provider.RetryErrorInfo
import com.aicode.feature.agent.domain.provider.RetryErrorKind
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Key
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** 涟漪高光一个来回的周期（ms）。 */
private const val RIPPLE_PERIOD_MS = 1600

/** 高光带宽度占内容宽度的比例：太宽像整段变色，太窄看不出来。 */
private const val RIPPLE_BAND_RATIO = 0.5f

/** 高光带边缘的不透明度（渐变两端）。 */
private const val RIPPLE_EDGE_ALPHA = 0.12f

/**
 * 高光带峰值不透明度。
 *
 * 高光是白色（主流做法）：深色主题下把字「打亮」，浅色主题下把字朝背景方向提亮成一道光泽。
 * 峰值不能太高——浅色主题下白色 0.85 会直接把字擦掉，0.5 左右是「亮一下但仍认得出字」。
 */
private const val RIPPLE_PEAK_ALPHA = 0.5f

/**
 * 涟漪高光：一条中间亮、两端透明的高光带周期性从左划到右。
 *
 * 用 [BlendMode.SrcAtop] 叠在已绘制内容上——它只作用在不透明像素上，所以不会在空白处
 * 画出一条色带方块。高光默认白色（Claude Code / 主流 shimmer 的做法）；浅色主题下白色会在
 * 文字像素上把字朝背景提亮，形成一道掠过的光泽，而不是盖上一层灰。
 *
 * 性能：进度在 [drawWithContent] 的绘制阶段读取（`by` 委托在 lambda 内取值），只重绘、不重组。
 */
@Composable
internal fun Modifier.rippleHighlight(
    highlight: Color = Color.White,
    bandRatio: Float = RIPPLE_BAND_RATIO,
    periodMs: Int = RIPPLE_PERIOD_MS
): Modifier {
    if (!chatUiWorkActive()) return this
    val transition = rememberInfiniteTransition(label = "ripple-highlight")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-progress"
    )
    return drawWithContent {
        drawContent()
        val band = size.width * bandRatio
        // 起点在左侧完全移出、终点在右侧完全移出，两端各留一个带的余量做「停顿感」
        val center = -band + progress * (size.width + 2 * band)
        drawRect(
            brush = Brush.linearGradient(
                // 五档渐变：两端全透明、中间一个软峰，扫过时是「亮一下」而不是一条硬边色带
                colors = listOf(
                    Color.Transparent,
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    highlight.copy(alpha = RIPPLE_PEAK_ALPHA),
                    highlight.copy(alpha = RIPPLE_EDGE_ALPHA),
                    Color.Transparent
                ),
                start = Offset(center - band / 2f, 0f),
                end = Offset(center + band / 2f, 0f)
            ),
            blendMode = BlendMode.SrcAtop
        )
    }
}

/**
 * 一条会走涟漪高光的文字（[Modifier.rippleHighlight] 的文字版）。
 *
 * 涟漪画在**文字自身宽度**上：调用方不要给它 `weight`，否则光带会在整段可分配宽度里爬、
 * 扫过这几个字只是一瞬间（要占位就让外层容器去占）。
 *
 * [highlight] 传 null 表示只画静态文字：同一处文案要在「空闲」与「进行中」之间切换时，
 * 用同一个组件、只换这一个参数即可（避免两个分支各写一套 Text 参数）。
 */
@Composable
internal fun RippleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    highlight: Color? = Color.White
) {
    val rippleModifier = if (highlight != null) modifier.rippleHighlight(highlight) else modifier
    Text(
        text = text,
        color = color,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = rippleModifier
    )
}

/**
 * 统一的「正在忙什么」状态行：涟漪高光文案（+ 可选的三点跳动）。
 *
 * 高光只盖在文案上，三个跳动的点是独立的动画，不参与高光（两层动画叠在一起会糊）。
 * 外层 [modifier]（撑满宽度、定高）交给容器，内层内容按自身宽度靠左排。
 *
 * 读屏语义统一由本行给出（文案即语义），所以内部的 [TypingDots] 关掉自己的播报。
 */
@Composable
internal fun AgentBusyIndicator(
    label: String,
    modifier: Modifier = Modifier,
    showDots: Boolean = false,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 6.dp
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            RippleText(text = label)
            if (showDots) TypingDots(color = dotColor, dotSize = dotSize, announce = false)
        }
    }
}

/**
 * 等待模型响应时的状态行：涟漪高光的文案 + 三个跳动的点，不套描边卡片。
 *
 * [label] 由调用方决定说「正在思考」还是更具体的场景（模型已在吐某次工具调用的参数时，
 * 说「正在编辑文件」），见 [toolRunningLabelRes]。
 */
@Composable
internal fun ThinkingBubble(label: String) {
    AgentBusyIndicator(
        label = label,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** 上下文压缩期间的临时状态行，不落库。 */
@Composable
internal fun CompactionProgressBubble(percent: Int) {
    AgentBusyIndicator(
        label = stringResource(R.string.chat_compressing_context_progress, percent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight),
        showDots = true
    )
}

/** 网络重试期间的临时状态行，不落库。首行展示触发重试的具体错误（如 429/500/网络断开），次行展示重试进度。 */
@Composable
internal fun RetryingBubble(attempt: Int, maxRetries: Int, error: RetryErrorInfo?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (error != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Icon(
                    FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = retryErrorLabel(error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.chat_retrying, attempt, maxRetries),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            TypingDots(color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 多 Key 自动切换期间的临时状态行，不落库。展示当前 Key 不可用、已改用第 N/M 个 Key 重发。
 *
 * 与 [RetryingBubble]、[CompactionProgressBubble] 同为尾巴里的瞬时状态行，沿用扁平文档流：
 * 不套描边卡片，只有图标 + 文案 + 三点跳动。
 */
@Composable
internal fun KeySwitchedBubble(newIndex: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            FeatherIcons.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = stringResource(R.string.chat_key_switched, newIndex, total),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        TypingDots(color = MaterialTheme.colorScheme.primary)
    }
}

/** 错误摘要文本：类别文案 + 状态码（如「速率限制 (429)」）；无状态码时仅类别文案。 */
@Composable
private fun retryErrorLabel(error: RetryErrorInfo): String {
    val base = stringResource(
        when (error.kind) {
            RetryErrorKind.RATE_LIMIT -> R.string.retry_error_rate_limit
            RetryErrorKind.SERVER_OVERLOADED -> R.string.retry_error_server_overloaded
            RetryErrorKind.SERVER_ERROR -> R.string.retry_error_server
            RetryErrorKind.TIMEOUT -> R.string.retry_error_timeout
            RetryErrorKind.CONNECTION_REFUSED -> R.string.retry_error_connection_refused
            RetryErrorKind.DNS_FAILED -> R.string.retry_error_dns_failed
            RetryErrorKind.CONNECTION_RESET -> R.string.retry_error_connection_reset
            RetryErrorKind.SSL_ERROR -> R.string.retry_error_ssl
            RetryErrorKind.NETWORK -> R.string.retry_error_network
            RetryErrorKind.UNKNOWN -> R.string.retry_error_unknown
        }
    )
    val code = error.statusCode
    return if (code != null) stringResource(R.string.retry_error_with_code, base, code) else base
}

/** 流式渲染节流：文本变化后延迟该时长再更新渲染文本，降低 md 解析频率。 */
private const val STREAMING_RENDER_DEBOUNCE_MS = 120L

/**
 * 对持续增长的流式文本做节流渲染：首帧立即渲染当前文本，之后每次文本变化最多
 * [STREAMING_RENDER_DEBOUNCE_MS] 更新一次。上游每个 delta 都携带完整累积文本，
 * 若不节流，每个 token 都会触发一次完整 md 解析（长文本下解析慢、渲染滞后）。
 * 返回的文本只用于渲染，折叠判定等实时逻辑仍直接用原始 [text]。
 */
@Composable
private fun rememberThrottledStreamingText(text: String): String {
    var renderText by remember { mutableStateOf(text) }
    UiWorkEffect(text) {
        if (renderText == text) return@UiWorkEffect
        delay(STREAMING_RENDER_DEBOUNCE_MS)
        if (renderText != text) renderText = text
    }
    return renderText
}

private const val TYPEWRITER_RENDER_INTERVAL_MS = 24L

internal fun typewriterCodePointCount(text: String): Int {
    val end = text.length - if (text.lastOrNull()?.isHighSurrogate() == true) 1 else 0
    return text.codePointCount(0, end)
}

internal fun truncateToCodePoints(text: String, codePoints: Int): String {
    if (codePoints <= 0) return ""
    val count = minOf(codePoints, typewriterCodePointCount(text))
    return text.substring(0, text.offsetByCodePoints(0, count))
}

internal class TypewriterCursor(initialText: String) {
    private var source = initialText
    private var availableEnd = source.length - if (source.lastOrNull()?.isHighSurrogate() == true) 1 else 0
    var availableCodePoints = source.codePointCount(0, availableEnd)
        private set
    private var renderedCodePoints = availableCodePoints
    private var renderedEnd = availableEnd
    private var snapshot = if (availableEnd == source.length) source else source.substring(0, availableEnd)

    fun update(text: String): Boolean {
        if (text === source) return false
        val continues = text.startsWith(source)
        val end = text.length - if (text.lastOrNull()?.isHighSurrogate() == true) 1 else 0
        if (continues) {
            availableCodePoints += text.codePointCount(availableEnd, end)
        } else {
            availableCodePoints = text.codePointCount(0, end)
            renderedCodePoints = 0
            renderedEnd = 0
            snapshot = ""
        }
        source = text
        availableEnd = end
        return !continues
    }

    fun render(codePoints: Int): String {
        val target = codePoints.coerceIn(0, availableCodePoints)
        if (target == renderedCodePoints) return snapshot
        val start = if (target < renderedCodePoints) 0 else renderedEnd
        val count = if (target < renderedCodePoints) target else target - renderedCodePoints
        renderedEnd = source.offsetByCodePoints(start, count)
        renderedCodePoints = target
        snapshot = if (renderedEnd == source.length) source else source.substring(0, renderedEnd)
        return snapshot
    }
}

/** 延续判据的前缀采样上限（字符）：只存指纹不存全文，避免大段流式文本进 saveable。 */
private const val CONTINUITY_HEAD_CHARS = 64

/** 已见文本的前缀指纹，与其长度一起构成「同一轮延续」的判据。 */
internal fun streamHeadFingerprint(text: String): Int =
    text.take(CONTINUITY_HEAD_CHARS).hashCode()

/**
 * [text] 是否是「长度 [seenChars]、前缀指纹 [seenHead]」那段已见文本的延续。
 *
 * 流式文本逐 delta 前缀增长，同一轮内当前文本必然以已见文本为前缀。切页或 item 回收后
 * 重挂载时用它校验恢复出的打字进度 / 计时起点是否仍属于同一轮：期间若已换轮，新文本更短
 * 或开头不同，判为不延续，进度与计时从头开始。
 */
internal fun isStreamContinuation(text: String, seenChars: Int, seenHead: Int): Boolean {
    if (seenChars <= 0 || text.length < seenChars) return false
    return text.take(minOf(seenChars, CONTINUITY_HEAD_CHARS)).hashCode() == seenHead
}

internal fun typewriterRate(lag: Float, charsPerSecond: Int = 30): Float =
    if (lag <= 0f) 0f else charsPerSecond.coerceIn(10, 120).toFloat()

internal fun typewriterDrainRate(lag: Float, charsPerSecond: Int = 30): Float =
    typewriterRate(lag, charsPerSecond)

internal fun advanceTypewriter(
    shownCodePoints: Double,
    availableCodePoints: Int,
    elapsedNanos: Long,
    charsPerSecond: Int = 30,
): Double {
    val rate = typewriterRate((availableCodePoints - shownCodePoints).toFloat(), charsPerSecond)
    return (shownCodePoints + rate.toDouble() * elapsedNanos.coerceAtLeast(0L) / 1_000_000_000.0)
        .coerceAtMost(availableCodePoints.toDouble())
}

/** 打字机当前渲染结果：[text] 为应渲染文本（上游全文的前缀），[settled] 表示是否已追平全文。 */
internal data class TypewriterText(val text: String, val settled: Boolean)

/**
 * 按码点限速的 UI 播放；上游结束不改变速率。调用方在 LazyColumn 外持有，落库后继续以
 * [TypewriterText.settled] 判定交棒。saveable 只存长度与指纹，不保存整段正文。
 */
@Composable
internal fun rememberTypewriterStreamingText(
    text: String,
    active: Boolean,
    sessionKey: String? = null,
    charsPerSecond: Int = 30,
    initialText: String = text,
    revealImmediately: Boolean = false,
    workActive: Boolean = chatUiWorkActive(),
): TypewriterText {
    var shownChars by rememberSaveable(sessionKey) { mutableStateOf(initialText.length) }
    var shownHead by rememberSaveable(sessionKey) { mutableStateOf(streamHeadFingerprint(initialText)) }
    val restored = remember(sessionKey) {
        val prefix = if (isStreamContinuation(text, shownChars, shownHead)) {
            text.substring(0, shownChars)
        } else {
            initialText.takeIf { text.startsWith(it) }.orEmpty()
        }
        truncateToCodePoints(prefix, typewriterCodePointCount(prefix))
    }
    var renderText by remember(sessionKey) { mutableStateOf(restored) }
    val latestInput by rememberUpdatedState(TypewriterInput(text, active, charsPerSecond, revealImmediately, workActive))

    LaunchedEffect(sessionKey) {
        val cursor = TypewriterCursor(restored)
        var shownCodePoints = cursor.availableCodePoints.toDouble()
        var lastFrameNanos = 0L
        var lastRenderNanos = 0L
        fun commitRender(snapshot: String) {
            if (snapshot === renderText) return
            renderText = snapshot
            shownChars = snapshot.length
            shownHead = streamHeadFingerprint(snapshot)
        }
        while (true) {
            val input = latestInput
            if (!input.workActive) {
                val resumed = androidx.compose.runtime.snapshotFlow { latestInput }.first { it.workActive }
                cursor.update(resumed.text)
                shownCodePoints = cursor.availableCodePoints.toDouble()
                commitRender(cursor.render(cursor.availableCodePoints))
                lastFrameNanos = 0L
                lastRenderNanos = 0L
                continue
            }
            if (cursor.update(input.text)) {
                shownCodePoints = 0.0
                lastFrameNanos = 0L
                lastRenderNanos = 0L
                commitRender("")
            }
            val available = cursor.availableCodePoints
            if (input.revealImmediately) shownCodePoints = available.toDouble()
            if (shownCodePoints >= available) {
                commitRender(cursor.render(available))
                // 追平期间不积攒时间额度，后来的突发文本不能花掉空闲时间瞬间显示。
                androidx.compose.runtime.snapshotFlow { latestInput }.first { it != input }
                lastFrameNanos = 0L
                continue
            }
            withFrameNanos { frameNanos ->
                if (!latestInput.workActive) {
                    lastFrameNanos = 0L
                    lastRenderNanos = 0L
                    return@withFrameNanos
                }
                if (lastFrameNanos != 0L) {
                    shownCodePoints = advanceTypewriter(
                        shownCodePoints, available, frameNanos - lastFrameNanos, latestInput.charsPerSecond
                    )
                }
                lastFrameNanos = frameNanos
                if (frameNanos - lastRenderNanos >= TYPEWRITER_RENDER_INTERVAL_MS * 1_000_000L ||
                    shownCodePoints >= available
                ) {
                    val snapshot = cursor.render(shownCodePoints.toInt())
                    if (snapshot != renderText) {
                        lastRenderNanos = frameNanos
                        commitRender(snapshot)
                    }
                }
            }
        }
    }
    return TypewriterText(text = renderText, settled = renderText == text)
}

private data class TypewriterInput(
    val text: String,
    val active: Boolean,
    val charsPerSecond: Int,
    val revealImmediately: Boolean,
    val workActive: Boolean,
)

/**
 * 模型流式吐字时的实时气泡：左对齐、与助手气泡同款。
 * 尾部带三个跳动的点表示仍在生成。本轮结束后由落库的助手气泡接管。
 *
 * 流式阶段以打字机效果渲染（打字进度由调用方经
 * [rememberTypewriterStreamingText] 驱动，见 [AIChatPanel]），落库后继续按所选速率播放尾文。
 */
@Composable
internal fun StreamingBubble(
    text: String,
    cache: MarkdownRenderCache? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AssistantMessageBubble(text = text, cache = cache)
        Spacer(Modifier.height(Spacing.xs))
        TypingDots(color = MaterialTheme.colorScheme.primary, dotSize = 5.dp)
    }
}

/**
 * 折叠行里显示的那一行思考内容：思考进行中取**最后一行**（跟着模型正在写的内容快速滚动），
 * 思考结束后取**第一行**（内容已定，固定成一句预览）。
 *
 * 调用方按单行 + 省略号渲染，所以这里把行首的 Markdown 记号（标题、列表、引用）与行尾的闭合
 * 记号（加粗、行内代码）一并清掉，免得折叠行里露出 `##` / `**` 这种源码记号；整行仍过长时
 * 交给省略号截断。没有任何可见内容时返回空串，调用方回落到「思考过程」文案。
 */
internal fun reasoningPreviewLine(raw: String, live: Boolean): String {
    val line = if (live) {
        // 从末尾回退跳过空白与换行，切出最后一行。不整串 trimEnd()：流式思考下这个函数每帧都跑，
        // 长思考文本的整串复制纯属浪费。
        var end = raw.length
        while (end > 0 && raw[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && raw[start - 1] != '\n') start--
        raw.substring(start, end)
    } else {
        raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }
    // 空格与 Markdown 记号在两端交替出现（`- **要点**`、`## 标题`），放同一个集合里一次剥干净
    return line.trim(*REASONING_PREVIEW_MARKERS)
}

/** 折叠行预览两端要剥掉的字符：空白 + Markdown 记号（标题 `#`、列表 `-` `+` `*`、引用 `>`、加粗与行内代码的 `*` `` ` ``）。 */
private val REASONING_PREVIEW_MARKERS = charArrayOf(' ', '\t', '#', '*', '-', '>', '`', '+')

/** 思考展开窗口的最大高度：超出后在窗口内滚动，不再无限撑高、拖走整页滚动。 */
private val ReasoningWindowMaxHeight = 300.dp

/**
 * 思考过程折叠行：左对齐、浅色弱化，与正式回复区分。**默认收起**，点这一行随时展开/收起。
 *
 * 收起态只占一行：行首思考图标 + 一行内容预览（见 [reasoningPreviewLine]，超宽省略号截断）；
 * 展开态换成完整正文（Markdown 渲染）。两态之间没有「显示尾巴几行」的中间态。
 *
 * [live] 表示思考仍在进行中：折叠行的预览取最后一行、跟着内容滚动，不展开也能看到模型在想什么；
 * 思考结束后预览固定为第一行。
 */
@Composable
internal fun ReasoningBubble(
    text: String,
    cache: MarkdownRenderCache? = null,
    showTimer: Boolean = false,
    /** 已完成思考的总耗时（毫秒）。非空且大于 0 时，折叠行文案展示精确耗时。 */
    durationMs: Long? = null,
    timerStartMs: Long? = null,
    /** 文本已由外部打字机驱动（流式尾巴场景），跳过内部防抖直接渲染。 */
    preRendered: Boolean = false,
    /** 思考所属会话：切会话时重新计时，否则会拿上一个会话的起点算出离谱的时长。 */
    sessionKey: String? = null,
    /** 思考仍在进行中：折叠行的预览取最后一行（跟着滚动），见上方 KDoc。 */
    live: Boolean = false,
    /** 展开态的外部覆盖（宿主持久化，见 [AIAgentViewModel.reasoningExpansionOverrides]）；null = 用内部状态。 */
    expandedOverride: Boolean? = null,
    /** 手动展开/收起时回传新状态，由上层持久化。 */
    onExpandedChange: ((Boolean) -> Unit)? = null
) {
    var localExpanded by remember { mutableStateOf(false) }
    val expanded = expandedOverride ?: localExpanded
    val toggleExpanded: (Boolean) -> Unit = { next ->
        if (onExpandedChange != null) onExpandedChange(next) else localExpanded = next
    }
    // 存绝对起始时间戳而非累加
    // 毫秒数，切页返回或气泡滚出视口重挂载后显示的仍是真实时长；起始戳连同已见文本的长度与
    // 指纹一起进 saveable，恢复时文本若不是同一轮的延续（期间已换轮）则重新计时。
    var timerStartMillis by rememberSaveable { mutableStateOf(0L) }
    var timerSeenChars by rememberSaveable { mutableStateOf(0) }
    var timerSeenHead by rememberSaveable { mutableStateOf(0) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    val latestText by rememberUpdatedState(text)
    UiWorkEffect(showTimer, live, sessionKey, timerStartMs) {
        if (!showTimer || !live) return@UiWorkEffect
        if (timerStartMs != null) {
            timerStartMillis = timerStartMs
        } else if (!isStreamContinuation(latestText, timerSeenChars, timerSeenHead)) {
            timerStartMillis = System.currentTimeMillis()
        }
        while (true) {
            elapsedMillis = (System.currentTimeMillis() - timerStartMillis).coerceAtLeast(0L)
            timerSeenChars = latestText.length
            timerSeenHead = streamHeadFingerprint(latestText)
            delay(100)
        }
    }
    // 展开渲染用节流文本（流式思考时降低 md 解析频率）；preRendered 时外部已按打字机节奏给出渲染文本。
    val renderText = if (preRendered) text else rememberThrottledStreamingText(text)
    val effectiveDurationMs = durationMs?.takeIf { it > 0 } ?: elapsedMillis.takeIf { it > 0 }
    val formattedDuration = remember(effectiveDurationMs, live) {
        effectiveDurationMs?.let { ms ->
            val sec = (ms / 1000.0).coerceAtLeast(0.1)
            String.format(java.util.Locale.US, "%.1f", sec)
        }
    }
    // 折叠行文案：进行中显示实时耗时，完成后显示总耗时（拿不到耗时数据时只显示「思考完成」）。
    val label = when {
        live -> {
            val secText = formattedDuration ?: "0.1"
            stringResource(R.string.chat_thinking_running_time, secText)
        }
        formattedDuration != null -> stringResource(R.string.chat_thinking_done_time, formattedDuration)
        else -> stringResource(R.string.chat_thinking_done)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .clickable { toggleExpanded(!expanded) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThinkingGlyph(tint = Brand.IconGray, iconSize = 16.dp)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.xs))
            ExpandableChevronIcon(
                expanded = expanded,
                contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                tint = Brand.IconGray,
                size = 18.dp
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(Spacing.sm))
                // 展开态用懒渲染（LazyMarkdownSuccess）：超长思考只组合可见块，不再一次性
                // 组合全部节点把 Compose ChangeList 撑爆（OOM 根因）。高度上限交给内层 LazyColumn 自行滚动。
                MarkdownContent(
                    text = renderText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    cache = cache,
                    compact = true,
                    lazyScroll = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ReasoningWindowMaxHeight)
                        .nestedScroll(rememberBoundNestedScrollConnection())
                        .pointerInput(text) {
                            detectTapGestures(
                                onDoubleTap = { toggleExpanded(false) }
                            )
                        }
                )
            }
        }
    }
}

/**
 * 三个循环跳动的点：通用「正在输入/生成」指示器，取代转圈 spinner。
 * 三点以固定相位差依次上下弹跳，形成波浪式律动。
 *
 * 性能优化：用 graphicsLayer { translationY } 替代 offset(y)，动画值变化在 draw 阶段
 * 处理而不触发 compose/recompose，消除无限动画导致父布局每帧重组的开销。
 * 容器高度固定，防止布局波动传递到 LazyColumn。
 */
@Composable
internal fun TypingDots(
    color: Color,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    /** 是否单独给读屏播报「正在生成」：嵌在 [AgentBusyIndicator] 里时由外层状态行统一播报。 */
    announce: Boolean = true
) {
    val animate = chatUiWorkActive()
    val transition = if (animate) rememberInfiniteTransition(label = "typing-dots") else null
    val label = stringResource(R.string.chat_status_generating)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 给读屏一个语义：三个跳动的点对 TalkBack 本来完全不可见。
        modifier = Modifier
            .height(dotSize + 10.dp)
            .then(
                if (announce) Modifier.semantics { contentDescription = label } else Modifier
            )
    ) {
        repeat(3) { index ->
            val offsetY = transition?.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 900
                        0f at 0
                        -5f at 180
                        0f at 360
                        0f at 900
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dot-$index"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offsetY?.value ?: 0f }
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(color)
            )
            if (index < 2) Spacer(Modifier.width(4.dp))
        }
    }
}
