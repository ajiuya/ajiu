package com.aicode.feature.plugin.presentation

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.domain.PluginIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.Code
import compose.icons.feathericons.Layers
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Package
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Tool
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PluginVisualIcon(
    icon: PluginIcon? = null,
    modifier: Modifier = Modifier
) {
    val targetPixels = with(LocalDensity.current) { 56.dp.roundToPx().coerceAtLeast(1) }
    var image by remember(icon?.file, targetPixels) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(icon?.file, targetPixels) {
        val file = icon?.file ?: return@LaunchedEffect
        image = withContext(Dispatchers.IO) {
            runCatching {
                require(file.extension.lowercase(Locale.ROOT) in setOf("png", "webp"))
                val bytes = PluginFiles.readRegular(requireNotNull(file.parentFile), file.name, 256 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType in setOf("image/png", "image/webp"))
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                var sample = 1
                // Small compressed icons can still declare enormous pixel dimensions.
                while (bounds.outWidth / sample > targetPixels || bounds.outHeight / sample > targetPixels) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(
        modifier = modifier.size(48.dp),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            val fallback = when (icon?.name?.lowercase(Locale.ROOT)) {
                "package" -> FeatherIcons.Package
                "tool", "tools" -> FeatherIcons.Tool
                "monitor", "desktop" -> FeatherIcons.Monitor
                "layers", "layout" -> FeatherIcons.Layers
                "code" -> FeatherIcons.Code
                "settings", "sliders" -> FeatherIcons.Sliders
                else -> FeatherIcons.Box
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Spacing.xl))
            }
        }
    }
}

@Composable
internal fun PluginVisualHeader(
    name: String,
    version: String,
    icon: PluginIcon? = null,
    modifier: Modifier = Modifier,
    iconSize: Dp = 48.dp,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    nameModifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    metadata: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginVisualIcon(icon, iconModifier.size(iconSize))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(name, modifier = nameModifier, style = titleStyle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    version,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                metadata()
            }
        }
    }
}

@Composable
internal fun PluginVisualExpandableRow(
    title: String,
    stateKey: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().clickable(
            onClickLabel = stringResource(if (expanded) R.string.plugin_center_collapse else R.string.plugin_center_expand)
        ) { expanded = !expanded }.padding(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.semanticColors.subtleText, modifier = Modifier.size(Spacing.lg))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ExpandableChevronIcon(expanded = expanded, size = Spacing.lg, tint = MaterialTheme.semanticColors.subtleText)
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            content()
        }
    }
}
