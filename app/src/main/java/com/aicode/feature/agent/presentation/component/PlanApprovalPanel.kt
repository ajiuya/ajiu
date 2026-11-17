package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.agent.domain.tool.plan.PendingPlanApproval

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanApprovalPanel(
    request: PendingPlanApproval,
    onApprove: () -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier = Modifier,
    sessionTitle: String = "",
    enabled: Boolean = true
) {
    var reviewing by remember(request.id) { mutableStateOf(false) }
    var feedback by remember(request.id) { mutableStateOf("") }
    val markdownCache = remember(request.id) { MarkdownRenderCache() }
    val summaryScrollState = key(request.id) { rememberScrollState() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.semanticColors.cardSurface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, MaterialTheme.semanticColors.subtleBorder)
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(summaryScrollState)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.plan_approval_heading),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (request.title.isNotBlank()) {
                Text(
                    text = request.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (sessionTitle.isNotBlank()) {
                Text(
                    text = stringResource(R.string.plan_approval_session, sessionTitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.plan_approval_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { reviewing = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.sm)
            ) {
                Text(
                    text = stringResource(R.string.plan_approval_review),
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    if (reviewing && enabled) {
        key(request.id) {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            AdaptiveModalBottomSheet(
                onDismissRequest = { reviewing = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = { WindowInsets(0.dp) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = screenHeight * 0.85f)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.lg)
                        .padding(bottom = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        Text(
                            text = stringResource(R.string.plan_approval_heading),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (sessionTitle.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.plan_approval_session, sessionTitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (request.title.isNotBlank()) {
                            Text(
                                text = request.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        MarkdownContent(
                            text = request.plan,
                            color = MaterialTheme.colorScheme.onSurface,
                            cache = markdownCache,
                            lazyScroll = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = screenHeight * 0.62f)
                        )
                        AppTextField(
                            value = feedback,
                            onValueChange = { feedback = it },
                            label = stringResource(R.string.plan_approval_feedback_label),
                            placeholder = stringResource(R.string.plan_approval_feedback_placeholder),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            maxLines = 4,
                            colors = dialogTextFieldColors()
                        )
                        Text(
                            text = stringResource(R.string.plan_approval_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Button(
                            onClick = {
                                reviewing = false
                                onReject(feedback.trim())
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Radius.sm),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.plan_approval_reject),
                                textAlign = TextAlign.Center
                            )
                        }
                        Button(
                            onClick = {
                                reviewing = false
                                onApprove()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Radius.sm),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.semanticColors.success,
                                contentColor = MaterialTheme.semanticColors.onSuccess
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.plan_approval_approve),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
