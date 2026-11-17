package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.aicode.feature.settings.data.repository.ChatBubbleStyle

@Composable
internal fun UserMessageBubble(
    text: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    style: ChatBubbleStyle = LocalUserChatBubbleStyle.current,
    selectable: Boolean = true
) {
    ChatBubbleSurface(
        style = ChatBubbleStyle.userStyleFromId(style.id),
        side = ChatBubbleSide.USER,
        modifier = modifier.widthIn(max = maxWidth),
        selectable = selectable
    ) { color ->
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)
                .scaledForChat(LocalChatTextScale.current)
        )
    }
}
