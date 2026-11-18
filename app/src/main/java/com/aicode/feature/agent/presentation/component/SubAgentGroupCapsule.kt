package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubAgentGroupCapsule(
    capsules: List<SubAgentTimelineCapsule>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (capsules.isEmpty()) return
    val host = LocalSubAgentCardHost.current
    val members = capsules.distinctBy { it.subSessionId }.map { capsule ->
        resolveSubAgentSummary(
            subSessionId = capsule.subSessionId,
            title = capsule.title,
            summary = host?.summaries?.get(capsule.subSessionId),
            legacy = host?.states?.get(capsule.subSessionId),
            fallbackStatus = capsule.fallbackStatus,
            fallbackAgentName = capsule.fallbackAgentName,
        )
    }
    val names = members.map { subAgentRoleLabel(it) }.joinToString(" · ")
    val memberStates = members.map {
        stringResource(R.string.subagent_group_member_status, subAgentRoleLabel(it), subAgentStatusLabel(it.status))
    }.joinToString(" · ")
    val actionLabel = stringResource(if (expanded) R.string.subagent_group_collapse else R.string.subagent_group_expand)
    val stateLabel = stringResource(if (expanded) R.string.subagent_group_expanded else R.string.subagent_group_collapsed)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
                .semantics { stateDescription = "$stateLabel · $memberStates" }
                .heightIn(min = if (expanded) Spacing.xxl + Spacing.sm else Spacing.xxl * 2)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!expanded) {
                Box(
                    modifier = Modifier.size(Spacing.xxl).clip(RoundedCornerShape(Radius.sm))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FeatherIcons.Cpu, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Spacing.lg))
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.subagent_group_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.subagent_group_total_count, members.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.subtleText,
                    )
                }
                if (!expanded) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        SubAgentInteractionStatus.entries.forEach { status ->
                            val count = members.count { it.status == status }
                            if (count > 0) {
                                Text(
                                    text = stringResource(R.string.subagent_group_status_count, subAgentStatusLabel(status), count),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = subAgentStatusColor(status),
                                )
                            }
                        }
                    }
                    Text(
                        text = names,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ExpandableChevronIcon(
                expanded = expanded,
                style = ChevronRotationStyle.RIGHT_DOWN,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = Spacing.lg,
            )
        }
    }
}
