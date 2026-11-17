package com.aicode.feature.agent.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.SubAgentInteractionEntry
import com.aicode.feature.agent.presentation.SubAgentInteractionPage
import com.aicode.feature.agent.presentation.SubAgentInteractionSpeaker
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SubAgentConversationContent(
    subSessionId: String,
    page: SubAgentInteractionPage,
    hasHost: Boolean,
    childLabel: String? = null,
    onLoadMore: () -> Unit,
) {
    val onInteraction = LocalSubAgentCardHost.current?.onContentInteraction ?: {}
    val entries = remember(page.entries) { page.entries.filterNot { isSubAgentHiddenReasoningKind(it.kind) } }
    var fromLatest by rememberSaveable(subSessionId) { mutableIntStateOf(0) }
    val window = subAgentRecordWindow(entries, fromLatest)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.subagent_interaction_conversation), style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (page.hasMore) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(onClick = {
                        onInteraction()
                        fromLatest = window.pageCount
                        onLoadMore()
                    }, enabled = hasHost, modifier = Modifier.heightIn(min = Spacing.xxl),
                        contentPadding = PaddingValues(Spacing.xs)) {
                        Text(stringResource(R.string.subagent_interaction_load_earlier), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (page.recoveredFromHistory) SubAgentInteractionNotice(stringResource(R.string.subagent_interaction_recovered))
        if (hasHost && !page.loaded && page.error == null) {
            Text(stringResource(R.string.subagent_interaction_loading), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        page.error?.let { SubAgentPageError(it) }
        if (page.loaded && entries.isEmpty() && page.error == null) {
            Text(stringResource(R.string.subagent_interaction_empty), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SubAgentRecordNavigation(window,
            onOlder = { fromLatest = window.pageFromLatest + 1 },
            onNewer = { fromLatest = window.pageFromLatest - 1 })
        window.records.forEach { entry -> key(entry.id) { SubAgentConversationEntry(subSessionId, entry, childLabel) } }
    }
}

@Composable
private fun SubAgentConversationEntry(subSessionId: String, entry: SubAgentInteractionEntry, childLabel: String?) {
    val speaker = childLabel?.takeIf { entry.speaker == SubAgentInteractionSpeaker.SUBAGENT } ?: stringResource(when (entry.speaker) {
        SubAgentInteractionSpeaker.PARENT -> R.string.subagent_interaction_source_parent
        SubAgentInteractionSpeaker.SUBAGENT -> R.string.subagent_interaction_source_child
        SubAgentInteractionSpeaker.USER -> R.string.subagent_interaction_source_user
        SubAgentInteractionSpeaker.SYSTEM -> R.string.subagent_interaction_source_system
    })
    val color = when (entry.speaker) {
        SubAgentInteractionSpeaker.PARENT -> MaterialTheme.semanticColors.info
        SubAgentInteractionSpeaker.SUBAGENT -> MaterialTheme.semanticColors.success
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.semanticColors.mutedSurface) {
        Column(Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(speaker, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, color = color)
                SubAgentRecordTimestamp(entry.timestamp)
            }
            SubAgentInteractionMarkdown("$subSessionId:entry:${entry.id}", entry.content)
        }
    }
}

@Composable
internal fun SubAgentInteractionMarkdown(contentKey: String, text: String, modifier: Modifier = Modifier) {
    val onInteraction = LocalSubAgentCardHost.current?.onContentInteraction ?: {}
    val parts = remember(text) { subAgentTextParts(text) }
    var requestedPart by rememberSaveable(contentKey) { mutableIntStateOf(0) }
    val current = requestedPart.coerceIn(parts.indices)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (parts.size > 1) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                IconButton(onClick = { onInteraction(); requestedPart = current - 1 }, enabled = current > 0,
                    modifier = Modifier.size(Spacing.xxl)) {
                    Icon(FeatherIcons.ChevronLeft, stringResource(R.string.subagent_interaction_previous_part), modifier = Modifier.size(Spacing.lg))
                }
                Text(stringResource(R.string.subagent_interaction_part, current + 1, parts.size),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onInteraction(); requestedPart = current + 1 }, enabled = current + 1 < parts.size,
                    modifier = Modifier.size(Spacing.xxl)) {
                    Icon(FeatherIcons.ChevronRight, stringResource(R.string.subagent_interaction_next_part), modifier = Modifier.size(Spacing.lg))
                }
            }
            TextButton(onClick = {
                val result = copySubAgentInteractionText(text) { clipboard.setText(AnnotatedString(it)) }
                val errorMessage = when (result) {
                    SubAgentTextCopyResult.COPIED -> null
                    SubAgentTextCopyResult.TOO_LARGE -> R.string.subagent_interaction_copy_too_large
                    SubAgentTextCopyResult.FAILED -> R.string.subagent_interaction_copy_failed
                }
                errorMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }, modifier = Modifier.heightIn(min = Spacing.xxl), contentPadding = PaddingValues(Spacing.xs)) {
                Text(stringResource(R.string.subagent_interaction_copy_full), style = MaterialTheme.typography.labelSmall)
            }
        }
        SelectionContainer {
            key(current) {
                MarkdownContent(text = parts[current].markdown, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(), compact = true, lazyScroll = false)
            }
        }
    }
}

@Composable
internal fun SubAgentInteractionNotice(text: String) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.semanticColors.infoContainer) {
        Text(text, modifier = Modifier.padding(Spacing.sm), color = MaterialTheme.semanticColors.onInfoContainer,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SubAgentPageError(detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.subagent_interaction_load_failed), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
        if (detail.isNotBlank()) SubAgentInteractionMarkdown("error:$detail", detail)
    }
}

@Composable
internal fun <T> SubAgentRecordNavigation(window: SubAgentRecordWindow<T>, onOlder: () -> Unit, onNewer: () -> Unit) {
    if (window.pageCount <= 1) return
    val onInteraction = LocalSubAgentCardHost.current?.onContentInteraction ?: {}
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        IconButton(onClick = { onInteraction(); onOlder() }, enabled = window.hasOlder, modifier = Modifier.size(Spacing.xxl)) {
            Icon(FeatherIcons.ChevronLeft, stringResource(R.string.subagent_interaction_older_page), modifier = Modifier.size(Spacing.lg))
        }
        Text(stringResource(R.string.subagent_interaction_page, window.pageCount - window.pageFromLatest, window.pageCount),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = { onInteraction(); onNewer() }, enabled = window.hasNewer, modifier = Modifier.size(Spacing.xxl)) {
            Icon(FeatherIcons.ChevronRight, stringResource(R.string.subagent_interaction_newer_page), modifier = Modifier.size(Spacing.lg))
        }
    }
}

@Composable
internal fun SubAgentRecordTimestamp(timestamp: Long) {
    if (timestamp <= 0L) return
    val locale = LocalConfiguration.current.locales[0]
    val formatted = remember(timestamp, locale) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(timestamp))
    }
    Text(formatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.semanticColors.subtleText)
}
