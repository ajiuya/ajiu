package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.ChevronRotationStyle
import com.aicode.core.ui.ExpandableChevronIcon
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import compose.icons.FeatherIcons
import compose.icons.feathericons.Cpu

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
    val memberStates = members.map {
        stringResource(R.string.subagent_group_member_status, subAgentRoleLabel(it), subAgentStatusLabel(it.status))
    }.joinToString(" · ")
    val statusSummary = compactSubAgentStatusSummary(members)
    val activeMember = members
        .filter { it.status.isActiveExecution() || it.status == SubAgentInteractionStatus.QUEUED }
        .maxByOrNull { it.lastActivityAtMs ?: it.startedAtMs ?: Long.MIN_VALUE }
    val focusMember = activeMember ?: members.maxByOrNull {
        it.lastActivityAtMs ?: it.finishedAtMs ?: it.startedAtMs ?: Long.MIN_VALUE
    } ?: members.first()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focusElapsed = rememberSubAgentElapsedMs(focusMember, lifecycle.isAtLeast(Lifecycle.State.STARTED))
    val focusActivity = focusMember.currentToolTitle
        ?.takeIf { focusMember.status.isActiveExecution() && it.isNotBlank() }
        ?: focusMember.latestPreview.takeIf { it.isNotBlank() }
        ?: subAgentVisibleTask(focusMember).takeIf { it.isNotBlank() }
        ?: subAgentStatusLabel(focusMember.status)
    val focusLine = "${subAgentRoleLabel(focusMember)} · $focusActivity"
    val actionLabel = stringResource(if (expanded) R.string.subagent_group_collapse else R.string.subagent_group_expand)
    val stateLabel = stringResource(if (expanded) R.string.subagent_group_expanded else R.string.subagent_group_collapsed)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onToggle)
                    .semantics { stateDescription = "$stateLabel · $memberStates" }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(27.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FeatherIcons.Cpu, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_group_title),
                            style = if (expanded) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.subagent_group_total_count, members.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.semanticColors.subtleText,
                        )
                        if (!expanded) {
                            Text(
                                text = statusSummary,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!expanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Box(
                                modifier = Modifier.size(Spacing.xs).clip(RoundedCornerShape(Radius.pill))
                                    .background(subAgentStatusColor(focusMember.status)),
                            )
                            Text(
                                text = focusLine,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            focusElapsed?.let {
                                Text(
                                    text = formatToolDuration(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.semanticColors.subtleText,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                ExpandableChevronIcon(
                    expanded = expanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = Spacing.lg,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.md + 27.dp + Spacing.sm, end = Spacing.md, bottom = Spacing.sm),
                ) {
                    members.forEach { member ->
                        HorizontalDivider(color = MaterialTheme.semanticColors.subtleBorder)
                        val activity = subAgentVisibleTask(member).takeIf { it.isNotBlank() }
                            ?: member.currentToolTitle?.takeIf { member.status.isActiveExecution() && it.isNotBlank() }
                            ?: member.latestPreview.takeIf { it.isNotBlank() }
                        val status = subAgentStatusLabel(member.status)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = subAgentRoleLabel(member),
                                modifier = Modifier.widthIn(min = 43.dp, max = 80.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(activity, status).joinToString(" · "),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.semanticColors.subtleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class CompactSubAgentStatus(
    val status: SubAgentInteractionStatus,
    val count: Int,
    val label: String,
)

@Composable
private fun compactSubAgentStatusSummary(members: List<SubAgentInteractionSummary>) = buildAnnotatedString {
    val statuses = buildList {
        val workingCount = members.count {
            it.status == SubAgentInteractionStatus.QUEUED || it.status == SubAgentInteractionStatus.RUNNING
        }
        if (workingCount > 0) add(CompactSubAgentStatus(
            SubAgentInteractionStatus.RUNNING,
            workingCount,
            stringResource(R.string.subagent_group_compact_running),
        ))
        val completedCount = members.count { it.status == SubAgentInteractionStatus.COMPLETED }
        if (completedCount > 0) add(CompactSubAgentStatus(
            SubAgentInteractionStatus.COMPLETED,
            completedCount,
            stringResource(R.string.subagent_group_compact_completed),
        ))
        val awaitingCount = members.count { it.status == SubAgentInteractionStatus.AWAITING_CONFIRMATION }
        if (awaitingCount > 0) add(CompactSubAgentStatus(
            SubAgentInteractionStatus.AWAITING_CONFIRMATION,
            awaitingCount,
            stringResource(R.string.subagent_group_compact_waiting),
        ))
        for (status in SubAgentInteractionStatus.entries) {
            if (status == SubAgentInteractionStatus.QUEUED || status == SubAgentInteractionStatus.RUNNING ||
                status == SubAgentInteractionStatus.COMPLETED || status == SubAgentInteractionStatus.AWAITING_CONFIRMATION
            ) continue
            val count = members.count { it.status == status }
            if (count > 0) add(CompactSubAgentStatus(status, count, subAgentStatusLabel(status)))
        }
    }
    statuses.forEachIndexed { index, entry ->
        if (index > 0) append(" · ")
        withStyle(SpanStyle(color = subAgentStatusColor(entry.status))) {
            append("● ${entry.count} ${entry.label}")
        }
    }
}
