package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapacity
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun ProjectProgressCapacityCard(capacity: ProjectMemoryCapacity?, enabled: Boolean) {
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    SettingsGroupHeader(stringResource(R.string.project_progress_capacity))
    SettingsGroup(Modifier.testTag("project-settings-capacity")) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Text(
                stringResource(R.string.project_settings_all_environments),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText
            )
            if (capacity == null) {
                Text(
                    stringResource(R.string.project_progress_capacity_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val progressColor = if (capacity.nearCapacity) MaterialTheme.semanticColors.warning
                    else MaterialTheme.colorScheme.primary
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.project_settings_active_count),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.project_settings_count_pair, capacity.activeEntries, capacity.maxActiveEntries),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.End
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (capacity.activeEntries.toFloat() / capacity.maxActiveEntries).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag("project-settings-active-usage"),
                        color = progressColor,
                        trackColor = MaterialTheme.semanticColors.mutedSurface
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.project_settings_storage_used),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(
                                R.string.project_settings_usage_pair,
                                projectProgressFormatBytes(capacity.usedBytes, locale),
                                projectProgressFormatBytes(capacity.maxBytes, locale)
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.End
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (capacity.usedBytes.toFloat() / capacity.maxBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().testTag("project-settings-storage-usage"),
                        color = progressColor,
                        trackColor = MaterialTheme.semanticColors.mutedSurface
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.project_settings_archived_count),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        NumberFormat.getIntegerInstance(locale).format(capacity.archivedEntries),
                        modifier = Modifier.testTag("project-settings-archived"),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
        if (capacity != null) {
            SettingsDivider()
            Row(
                Modifier.fillMaxWidth()
                    .testTag("project-settings-capacity-details")
                    .clickable(enabled = enabled, role = Role.Button) {
                        if (enabled) detailsExpanded = !detailsExpanded
                    }
                    .padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.project_settings_capacity_details),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExpandableChevronIcon(expanded = detailsExpanded, tint = MaterialTheme.semanticColors.subtleText)
            }
            if (detailsExpanded) {
                Text(
                    stringResource(
                        R.string.project_progress_capacity_value,
                        capacity.activeEntries,
                        capacity.maxActiveEntries,
                        capacity.archivedEntries,
                        capacity.usedBytes,
                        capacity.maxBytes
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun projectProgressFormatBytes(bytes: Int, locale: Locale): String {
    val unit = when {
        bytes >= 1_073_741_824 -> 3
        bytes >= 1_048_576 -> 2
        bytes >= 1_024 -> 1
        else -> 0
    }
    val divisor = when (unit) {
        3 -> 1_073_741_824.0
        2 -> 1_048_576.0
        1 -> 1_024.0
        else -> 1.0
    }
    val value = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }.format(bytes / divisor)
    val suffix = when (unit) {
        3 -> "GiB"
        2 -> "MiB"
        1 -> "KiB"
        else -> "B"
    }
    return "$value $suffix"
}
