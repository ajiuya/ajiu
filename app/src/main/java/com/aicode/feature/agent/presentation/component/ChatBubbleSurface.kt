package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.ChatBubblePalette
import com.aicode.core.theme.DarkColorScheme
import com.aicode.core.theme.DarkSemanticColors
import com.aicode.core.theme.LocalAppSemanticColors
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.data.repository.ChatBubbleStyle

internal enum class ChatBubbleSide { USER, ASSISTANT }

internal data class ChatBubbleColors(
    val surface: Color,
    val content: Color,
    val border: Color = Color.Transparent
)

internal fun chatBubbleColors(style: ChatBubbleStyle, scheme: ColorScheme): ChatBubbleColors = when (style) {
    ChatBubbleStyle.CLASSIC -> ChatBubbleColors(scheme.surface, scheme.onSurface, scheme.onSurface)
    ChatBubbleStyle.SHADOW, ChatBubbleStyle.MINIMAL -> ChatBubbleColors(scheme.surface, scheme.onSurface)
    ChatBubbleStyle.DARK -> ChatBubbleColors(ChatBubblePalette.NightSurface, ChatBubblePalette.OnNight)
    ChatBubbleStyle.SQUARE -> ChatBubbleColors(scheme.surface, scheme.onSurface, scheme.onSurface)
    ChatBubbleStyle.GREEN -> ChatBubbleColors(ChatBubblePalette.FreshGreenSurface, ChatBubblePalette.OnFreshGreen)
    ChatBubbleStyle.VALENTINE -> ChatBubbleColors(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline)
    ChatBubbleStyle.ORIGINAL -> ChatBubbleColors(scheme.primaryContainer, scheme.onPrimaryContainer)
    ChatBubbleStyle.DOCUMENT -> ChatBubbleColors(Color.Transparent, scheme.onSurface)
}

internal data class ChatBubbleLayout(
    val topCorner: Dp,
    val bottomCorner: Dp,
    val horizontalPadding: Dp,
    val topPadding: Dp,
    val bottomPadding: Dp
)

internal fun chatBubbleLayout(
    style: ChatBubbleStyle,
    isChunkHeader: Boolean,
    isChunkFooter: Boolean
): ChatBubbleLayout {
    if (style == ChatBubbleStyle.DOCUMENT) return ChatBubbleLayout(0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
    val corner = when (style) {
        ChatBubbleStyle.SQUARE -> Radius.xs
        ChatBubbleStyle.MINIMAL, ChatBubbleStyle.GREEN -> Radius.xl
        else -> ChatStyle.bubbleCorner
    }
    return ChatBubbleLayout(
        topCorner = if (isChunkHeader) corner else 0.dp,
        bottomCorner = if (isChunkFooter) corner else 0.dp,
        horizontalPadding = Spacing.md,
        topPadding = if (isChunkHeader) Spacing.sm else 0.dp,
        bottomPadding = if (isChunkFooter) Spacing.sm else 0.dp
    )
}

@Composable
internal fun ChatBubbleSurface(
    style: ChatBubbleStyle,
    side: ChatBubbleSide,
    modifier: Modifier = Modifier,
    isChunkHeader: Boolean = true,
    isChunkFooter: Boolean = true,
    selectable: Boolean = true,
    content: @Composable (Color) -> Unit
) {
    val colors = chatBubbleColors(style, MaterialTheme.colorScheme)
    val layout = chatBubbleLayout(style, isChunkHeader, isChunkFooter)
    val shape = RoundedCornerShape(
        topStart = layout.topCorner,
        topEnd = layout.topCorner,
        bottomStart = layout.bottomCorner,
        bottomEnd = layout.bottomCorner
    )
    val hasTail = style == ChatBubbleStyle.SQUARE
    val borderWidth = if (style == ChatBubbleStyle.CLASSIC) 1.5.dp else ChatStyle.hairline
    val shadowColor = ChatBubblePalette.Shadow

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (hasTail && side == ChatBubbleSide.ASSISTANT) {
            ChatBubbleTail(side, colors.border, visible = isChunkFooter)
        }
        Box(
            modifier = Modifier
                .weight(1f, fill = side == ChatBubbleSide.ASSISTANT)
                .drawWithCache {
                    val shadowPath = bubbleBorderPath(
                        size, layout.topCorner.toPx(), layout.bottomCorner.toPx(),
                        isChunkHeader, isChunkFooter
                    )
                    onDrawBehind {
                        // 只在整条正文首尾投影，长文中间块延续两侧阴影，避免出现横向接缝。
                        if (style == ChatBubbleStyle.SHADOW) {
                            drawPath(shadowPath, shadowColor.copy(alpha = 0.04f), style = Stroke(Spacing.sm.toPx()))
                            drawPath(shadowPath, shadowColor.copy(alpha = 0.06f), style = Stroke(Spacing.xs.toPx()))
                            drawPath(shadowPath, shadowColor.copy(alpha = 0.08f), style = Stroke(ChatStyle.hairline.toPx() * 2))
                        }
                    }
                }
                .clip(shape)
                .background(colors.surface)
                .drawWithCache {
                    val stroke = borderWidth.toPx()
                    val borderPath = bubbleBorderPath(
                        size, layout.topCorner.toPx(), layout.bottomCorner.toPx(),
                        isChunkHeader, isChunkFooter, inset = stroke / 2
                    )
                    onDrawBehind {
                        if (colors.border != Color.Transparent) {
                            drawPath(borderPath, colors.border, style = Stroke(stroke))
                        }
                    }
                }
                .padding(
                    start = layout.horizontalPadding,
                    end = layout.horizontalPadding,
                    top = layout.topPadding,
                    bottom = layout.bottomPadding
                )
        ) {
            val body: @Composable () -> Unit = {
                val selectionColor = if (style == ChatBubbleStyle.DOCUMENT) {
                    MaterialTheme.colorScheme.primary
                } else {
                    colors.content
                }
                CompositionLocalProvider(
                    LocalContentColor provides colors.content,
                    LocalTextSelectionColors provides TextSelectionColors(
                        handleColor = selectionColor,
                        backgroundColor = selectionColor.copy(alpha = 0.24f)
                    )
                ) {
                    if (selectable) {
                        SelectionContainer { content(colors.content) }
                    } else {
                        content(colors.content)
                    }
                }
            }
            if (style == ChatBubbleStyle.DARK) {
                CompositionLocalProvider(LocalAppSemanticColors provides DarkSemanticColors) {
                    MaterialTheme(colorScheme = DarkColorScheme) { body() }
                }
            } else {
                body()
            }
        }
        if (hasTail && side == ChatBubbleSide.USER) {
            ChatBubbleTail(side, colors.border, visible = isChunkFooter)
        }
    }
}

@Composable
private fun ChatBubbleTail(side: ChatBubbleSide, color: Color, visible: Boolean) {
    Canvas(modifier = Modifier.size(width = Spacing.xs * 1.5f, height = Spacing.sm)) {
        if (visible) {
            val base = if (side == ChatBubbleSide.USER) 0f else size.width
            val tip = size.width - base
            val path = Path().apply {
                moveTo(base, size.height * 0.15f)
                lineTo(tip, size.height / 2)
                lineTo(base, size.height * 0.85f)
                close()
            }
            drawPath(path, color)
        }
    }
}

private fun bubbleBorderPath(
    size: Size,
    topCorner: Float,
    bottomCorner: Float,
    header: Boolean,
    footer: Boolean,
    inset: Float = 0f
): Path {
    val left = inset
    val right = (size.width - inset).coerceAtLeast(left)
    val top = if (header) inset else 0f
    val bottom = if (footer) (size.height - inset).coerceAtLeast(top) else size.height
    val maxCorner = minOf((right - left) / 2, (bottom - top) / 2).coerceAtLeast(0f)
    val topRadius = (topCorner - inset).coerceIn(0f, maxCorner)
    val bottomRadius = (bottomCorner - inset).coerceIn(0f, maxCorner)
    return Path().apply {
        if (header) {
            moveTo(left + topRadius, top)
            lineTo(right - topRadius, top)
            if (topRadius > 0f) {
                arcTo(Rect(right - topRadius * 2, top, right, top + topRadius * 2), -90f, 90f, false)
            }
        } else {
            moveTo(right, top)
        }
        lineTo(right, bottom - bottomRadius)
        if (footer) {
            if (bottomRadius > 0f) {
                arcTo(Rect(right - bottomRadius * 2, bottom - bottomRadius * 2, right, bottom), 0f, 90f, false)
            }
            lineTo(left + bottomRadius, bottom)
            if (bottomRadius > 0f) {
                arcTo(Rect(left, bottom - bottomRadius * 2, left + bottomRadius * 2, bottom), 90f, 90f, false)
            }
        } else {
            moveTo(left, bottom)
        }
        lineTo(left, top + topRadius)
        if (header && topRadius > 0f) {
            arcTo(Rect(left, top, left + topRadius * 2, top + topRadius * 2), 180f, 90f, false)
        }
    }
}

@Composable
internal fun AssistantMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null,
    style: ChatBubbleStyle = LocalAssistantChatBubbleStyle.current,
    isChunkHeader: Boolean = true,
    isChunkFooter: Boolean = true,
    selectable: Boolean = true,
    onRendered: ((String) -> Unit)? = null,
) {
    ChatBubbleSurface(
        style = style,
        side = ChatBubbleSide.ASSISTANT,
        modifier = modifier.fillMaxWidth(),
        isChunkHeader = isChunkHeader,
        isChunkFooter = isChunkFooter,
        selectable = selectable
    ) { color ->
        MarkdownContent(text = text, color = color, modifier = Modifier.fillMaxWidth(), cache = cache, onRendered = onRendered)
    }
}
