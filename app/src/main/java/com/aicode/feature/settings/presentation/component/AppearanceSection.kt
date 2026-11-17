package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.data.repository.ChatBubbleStyle
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.Image
import compose.icons.feathericons.MessageSquare
import kotlin.math.roundToInt

@Composable
internal fun AppearanceSection(
    themeMode: AppThemeMode,
    themePresetId: String?,
    dynamicColorEnabled: Boolean,
    onThemeModeSelected: (AppThemeMode) -> Unit,
    onThemePresetSelected: (String) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    chatBackgroundImagePath: String?,
    chatBackgroundAlpha: Float,
    chatTextScale: Float,
    onChatTextScaleChanged: (Float) -> Unit,
    userChatBubbleStyle: ChatBubbleStyle,
    assistantChatBubbleStyle: ChatBubbleStyle,
    onUserChatBubbleStyleChanged: (ChatBubbleStyle) -> Unit,
    onAssistantChatBubbleStyleChanged: (ChatBubbleStyle) -> Unit,
    onOpenGlobalBackground: () -> Unit,
    onOpenChatBackground: () -> Unit
) {
    var editingUserBubbles by remember { mutableStateOf<Boolean?>(null) }
    editingUserBubbles?.let { isUser ->
        ChatBubbleStyleSheet(
            isUser = isUser,
            userStyle = userChatBubbleStyle,
            assistantStyle = assistantChatBubbleStyle,
            chatTextScale = chatTextScale,
            onSelect = if (isUser) onUserChatBubbleStyleChanged else onAssistantChatBubbleStyleChanged,
            onDismiss = { editingUserBubbles = null }
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_theme_title))
        SettingsGroup {
            ThemeSelectionContent(
                selected = themeMode,
                selectedPresetId = themePresetId,
                dynamicColorEnabled = dynamicColorEnabled,
                onSelected = onThemeModeSelected,
                onPresetSelected = onThemePresetSelected,
                onDynamicColorChanged = onDynamicColorChanged
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_background_image))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_global_background),
                onClick = onOpenGlobalBackground,
                trailing = {
                    Text(
                        text = if (backgroundImagePath != null) {
                            stringResource(
                                R.string.settings_percent_value,
                                BackgroundSettingsRepository.alphaToSlider(backgroundAlpha).roundToInt()
                            )
                        } else {
                            stringResource(R.string.settings_background_image_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.settings_chat_background),
                subtitle = stringResource(R.string.settings_chat_background_hint),
                onClick = onOpenChatBackground,
                trailing = {
                    Text(
                        text = if (chatBackgroundImagePath != null) {
                            stringResource(R.string.settings_percent_value, (chatBackgroundAlpha * 100f).roundToInt())
                        } else {
                            stringResource(R.string.settings_background_image_none)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.chat_bubble_settings_title))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.chat_bubble_user_title),
                subtitle = stringResource(userChatBubbleStyle.labelRes),
                onClick = { editingUserBubbles = true }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(R.string.chat_bubble_assistant_title),
                subtitle = stringResource(assistantChatBubbleStyle.labelRes),
                onClick = { editingUserBubbles = false }
            )
        }
        Text(
            text = stringResource(R.string.chat_bubble_settings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(horizontal = Spacing.md)
        )

        SettingsGroupHeader(text = stringResource(R.string.settings_chat_text_size))
        SettingsGroup {
            ChatTextSizeSetting(
                textScale = chatTextScale,
                onTextScaleChanged = onChatTextScaleChanged
            )
        }
        Text(
            text = stringResource(R.string.settings_chat_appearance_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.padding(horizontal = Spacing.md)
        )
    }
}

@Composable
private fun ChatTextSizeSetting(
    textScale: Float,
    onTextScaleChanged: (Float) -> Unit
) {
    var sliderPercent by remember(textScale) { mutableFloatStateOf(textScale * 100f) }
    val previewStyle = MaterialTheme.typography.bodyLarge
    val previewScale = sliderPercent / 100f

    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
        Text(
            text = stringResource(R.string.settings_chat_text_size_value, sliderPercent.roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.End)
        )
        Slider(
            value = sliderPercent,
            onValueChange = { sliderPercent = it },
            onValueChangeFinished = { onTextScaleChanged(sliderPercent / 100f) },
            valueRange = ThemeSettingsRepository.MIN_CHAT_TEXT_SCALE * 100f..
                ThemeSettingsRepository.MAX_CHAT_TEXT_SCALE * 100f,
            steps = 59
        )
        Spacer(Modifier.height(Spacing.sm))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.semanticColors.mutedSurface
        ) {
            Text(
                text = stringResource(R.string.settings_chat_text_preview),
                style = previewStyle.copy(
                    fontSize = previewStyle.fontSize * previewScale,
                    lineHeight = previewStyle.lineHeight * previewScale
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(Spacing.md)
            )
        }
    }
}
