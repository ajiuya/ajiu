package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.SpeedTier
import com.aicode.feature.agent.domain.model.SpeedTierPolicy
import com.aicode.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Zap

@Composable
internal fun SpeedTierMenuEntry(chosenSpeedTier: SpeedTier, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(stringResource(R.string.speed_tier_menu_entry, stringResource(chosenSpeedTier.speedTierLabelRes())))
        },
        leadingIcon = { Icon(FeatherIcons.Zap, contentDescription = null) },
        onClick = onClick
    )
}

@Composable
internal fun SpeedTierMenu(
    chosenSpeedTier: SpeedTier,
    onSpeedTierChange: (SpeedTier) -> Unit,
    providerConfig: AIProviderConfig?,
    onBack: () -> Unit
) {
    val effective = effectiveSpeedTier(chosenSpeedTier, providerConfig)
    DropdownMenuItem(
        text = { Text(stringResource(R.string.speed_tier_menu_back)) },
        leadingIcon = { Icon(FeatherIcons.ArrowLeft, contentDescription = null) },
        onClick = onBack
    )
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    stringResource(R.string.speed_tier_chosen, stringResource(chosenSpeedTier.speedTierLabelRes())),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(R.string.speed_tier_effective, stringResource(effective.speedTierLabelRes())),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        enabled = false,
        onClick = {}
    )
    SpeedTier.entries.forEach { tier ->
        val available = effectiveSpeedTier(tier, providerConfig) == tier
        DropdownMenuItem(
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(tier.speedTierLabelRes()))
                    if (!available || tier == SpeedTier.STANDARD) {
                        Text(
                            text = stringResource(
                                when (tier) {
                                    SpeedTier.STANDARD -> R.string.speed_tier_standard_hint
                                    SpeedTier.FAST -> R.string.speed_tier_fast_requirement
                                    SpeedTier.ULTRAFAST -> R.string.speed_tier_ultrafast_requirement
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            leadingIcon = {
                if (chosenSpeedTier == tier) Icon(FeatherIcons.Check, contentDescription = null)
            },
            modifier = Modifier.semantics { selected = chosenSpeedTier == tier },
            enabled = available,
            onClick = { onSpeedTierChange(tier) }
        )
    }
    DropdownMenuItem(
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.speed_tier_global_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.speed_tier_cost_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        enabled = false,
        onClick = {}
    )
}

internal fun effectiveSpeedTier(chosen: SpeedTier, provider: AIProviderConfig?): SpeedTier =
    provider?.let {
        SpeedTierPolicy.effective(
            chosen = chosen,
            providerType = it.type,
            baseUrl = it.baseUrl,
            providerId = it.id,
            useResponses = it.useResponseApi,
            model = it.effectiveModel
        )
    } ?: SpeedTier.STANDARD

internal fun SpeedTier.speedTierLabelRes(): Int = when (this) {
    SpeedTier.STANDARD -> R.string.speed_tier_standard
    SpeedTier.FAST -> R.string.speed_tier_fast
    SpeedTier.ULTRAFAST -> R.string.speed_tier_ultrafast
}
