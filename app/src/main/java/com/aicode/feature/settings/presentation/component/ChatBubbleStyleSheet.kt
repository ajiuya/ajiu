package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.feature.agent.presentation.component.AssistantMessageBubble
import com.aicode.feature.agent.presentation.component.ChatStyle
import com.aicode.feature.agent.presentation.component.LocalChatTextScale
import com.aicode.feature.agent.presentation.component.UserMessageBubble
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatBubbleStyleSheet(
    isUser: Boolean,
    userStyle: ChatBubbleStyle,
    assistantStyle: ChatBubbleStyle,
    chatTextScale: Float,
    onSelect: (ChatBubbleStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val selected = if (isUser) userStyle else assistantStyle
    val styles = if (isUser) ChatBubbleStyle.userStyles else ChatBubbleStyle.assistantStyles
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.85f
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(
                    if (isUser) R.string.chat_bubble_user_sheet_title else R.string.chat_bubble_assistant_sheet_title
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    if (isUser) R.string.chat_bubble_user_sheet_hint else R.string.chat_bubble_assistant_sheet_hint
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText
            )
            CompositionLocalProvider(LocalChatTextScale provides chatTextScale) {
                BubbleConversationPreview(userStyle, assistantStyle)
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 320.dp * chatTextScale) 2 else 1
                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        styles.chunked(columns).forEach { rowStyles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                            ) {
                                rowStyles.forEach { style ->
                                    ChatBubbleStyleCell(
                                        style = style,
                                        isUser = isUser,
                                        selected = style == selected,
                                        onClick = { onSelect(style) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowStyles.size < columns) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (selected == ChatBubbleStyle.DOCUMENT) {
                Text(
                    text = stringResource(R.string.chat_bubble_document_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }
        }
    }
}

@Composable
private fun BubbleConversationPreview(userStyle: ChatBubbleStyle, assistantStyle: ChatBubbleStyle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .background(MaterialTheme.semanticColors.pageBackground)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            text = stringResource(R.string.chat_bubble_preview_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            UserMessageBubble(
                text = stringResource(R.string.chat_bubble_preview_user),
                maxWidth = maxWidth,
                style = userStyle,
                selectable = false
            )
        }
        AssistantMessageBubble(
            text = stringResource(R.string.chat_bubble_preview_assistant),
            style = assistantStyle,
            selectable = false
        )
    }
}

@Composable
private fun ChatBubbleStyleCell(
    style: ChatBubbleStyle,
    isUser: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Radius.mdLarge)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.semanticColors.mutedSurface
            )
            .border(
                width = ChatStyle.hairline,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.semanticColors.subtleBorder,
                shape = shape
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.xxl * 2)
                .clearAndSetSemantics {},
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            if (isUser) {
                UserMessageBubble(
                    text = stringResource(R.string.chat_bubble_preview_sample),
                    maxWidth = maxWidth,
                    style = style,
                    selectable = false
                )
            } else {
                AssistantMessageBubble(
                    text = stringResource(R.string.chat_bubble_preview_sample),
                    style = style,
                    selectable = false
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(style.labelRes),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Spacing.lg)
                )
            }
        }
    }
}
