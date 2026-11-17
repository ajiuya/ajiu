package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import com.aicode.feature.settings.presentation.component.decodeBackgroundBitmap
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalChatTextScale = staticCompositionLocalOf { ThemeSettingsRepository.DEFAULT_CHAT_TEXT_SCALE }

internal fun TextStyle.scaledForChat(scale: Float): TextStyle = copy(
    fontSize = if (fontSize.isSp) fontSize * scale else fontSize,
    lineHeight = if (lineHeight.isSp) lineHeight * scale else lineHeight
)

@Composable
internal fun ChatBackgroundImage(imagePath: String?, alpha: Float, modifier: Modifier = Modifier) {
    if (imagePath == null || alpha <= 0f) return
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val width = (configuration.screenWidthDp * density).toInt().coerceAtLeast(1)
    val height = (configuration.screenHeightDp * density).toInt().coerceAtLeast(1)
    val bitmap by produceState<ImageBitmap?>(null, imagePath, width, height) {
        value = withContext(Dispatchers.IO) { decodeBackgroundBitmap(imagePath, width, height) }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.alpha(alpha)
        )
    }
}
