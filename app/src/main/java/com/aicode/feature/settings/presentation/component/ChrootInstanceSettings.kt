package com.aicode.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeState
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.settings.presentation.ChrootConfirmation
import com.aicode.feature.settings.presentation.ChrootInstanceAction
import com.aicode.feature.settings.presentation.ChrootSettingsUiState
import com.aicode.feature.settings.presentation.isAvailable

@StringRes
internal fun chrootStatusResource(state: LocalRuntimeState?, action: ChrootInstanceAction?): Int = when {
    action == ChrootInstanceAction.CREATE -> R.string.container_chroot_status_installing
    action == ChrootInstanceAction.REPAIR_JOURNAL -> R.string.container_chroot_status_repairing
    action == ChrootInstanceAction.STOP || action == ChrootInstanceAction.REMOVE ||
        action == ChrootInstanceAction.RECOVER_REMOVE -> R.string.container_chroot_status_cleanup
    state?.removalRebootRequired == true -> R.string.container_chroot_status_removal_reboot_required
    state == null || state.recoveryRequired || !state.journalHealthy -> R.string.container_chroot_status_unknown
    state.busy -> R.string.container_chroot_status_busy
    state.instance == null -> R.string.container_chroot_status_not_installed
    state.activeLaunches > 0 -> R.string.container_chroot_status_active
    // Legacy resource ID now means installed/ready; an owner count is not a permission gate.
    else -> R.string.container_chroot_status_unauthorized
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun ChrootProfileSettings(
    profile: ContainerProfile,
    uiState: ChrootSettingsUiState,
    onInstanceAction: (String, ChrootInstanceAction) -> Unit,
    onAuthorization: (LocalRuntimeAuthorization) -> Unit
) {
    val state = uiState.states[profile.id]
    val action = uiState.workingAction.takeIf { uiState.workingProfileId == profile.id }
    fun available(action: ChrootInstanceAction) = uiState.workingProfileId == null &&
        action.isAvailable(profile, state, uiState.rootState)
    val rootAvailable = uiState.rootState.enabled && state?.rootEnabled == true
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.container_runtime_chroot),
            subtitle = stringResource(chrootStatusResource(state, action)),
            trailing = {
                if (uiState.workingProfileId == profile.id) CircularProgressIndicator()
            }
        )
        if (!rootAvailable) {
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.container_chroot_root_disabled))
        }
        if (state?.removalRebootRequired == true) {
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.container_chroot_removal_reboot_required))
        }
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.container_chroot_create),
            enabled = available(ChrootInstanceAction.CREATE),
            onClick = { onInstanceAction(profile.id, ChrootInstanceAction.CREATE) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.container_chroot_stop),
            enabled = available(ChrootInstanceAction.STOP),
            onClick = { onInstanceAction(profile.id, ChrootInstanceAction.STOP) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(if (profile.chrootInstance == null) R.string.container_chroot_remove_configuration else R.string.container_chroot_remove),
            enabled = available(ChrootInstanceAction.REMOVE),
            onClick = { onInstanceAction(profile.id, ChrootInstanceAction.REMOVE) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.container_chroot_repair_journal),
            enabled = available(ChrootInstanceAction.REPAIR_JOURNAL),
            onClick = { onInstanceAction(profile.id, ChrootInstanceAction.REPAIR_JOURNAL) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.container_chroot_recover_remove),
            enabled = available(ChrootInstanceAction.RECOVER_REMOVE),
            onClick = { onInstanceAction(profile.id, ChrootInstanceAction.RECOVER_REMOVE) }
        )
    }
}

@Composable
internal fun ChrootSettingsDialogs(
    uiState: ChrootSettingsUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClearError: () -> Unit
) {
    (uiState.confirmation as? ChrootConfirmation.Instance)?.let { confirmation ->
        val profileId = confirmation.profile.id
        val state = uiState.states[profileId]
        val current = confirmation.rootState == uiState.rootState && uiState.workingProfileId == null &&
            confirmation.action.isAvailable(confirmation.profile, state, uiState.rootState)
        AlertDialog(
            onDismissRequest = onCancel,
            title = {
                Text(stringResource(when (confirmation.action) {
                    ChrootInstanceAction.CREATE -> R.string.container_chroot_create
                    ChrootInstanceAction.STOP -> R.string.container_chroot_stop
                    ChrootInstanceAction.REMOVE -> if (confirmation.profile.chrootInstance == null)
                        R.string.container_chroot_remove_configuration else R.string.container_chroot_remove
                    ChrootInstanceAction.REPAIR_JOURNAL -> R.string.container_chroot_repair_journal
                    ChrootInstanceAction.RECOVER_REMOVE -> R.string.container_chroot_recover_remove
                }))
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    SummaryField(R.string.container_chroot_summary_profile, "${confirmation.profile.name}\n${confirmation.profile.id}")
                    SummaryField(R.string.container_chroot_summary_configuration, confirmation.snapshot.configurationRevision.toString())
                    SummaryField(R.string.container_chroot_summary_image, when (val source = confirmation.profile.rootfsSource) {
                        is RootfsSource.Asset -> source.path
                        is RootfsSource.LocalFile -> source.uri
                        is RootfsSource.RemoteSsh -> source.connectionId
                    })
                    confirmation.profile.chrootInstance?.let { InstanceSummary(it) }
                    RootSummary(confirmation.rootState)
                    Text(stringResource(when (confirmation.action) {
                        ChrootInstanceAction.CREATE -> R.string.container_chroot_create_notice
                        ChrootInstanceAction.STOP -> R.string.container_chroot_stop_notice
                        ChrootInstanceAction.REMOVE -> if (confirmation.profile.chrootInstance == null)
                            R.string.container_chroot_remove_configuration_notice else R.string.container_chroot_remove_notice
                        ChrootInstanceAction.REPAIR_JOURNAL -> R.string.container_chroot_repair_journal_notice
                        ChrootInstanceAction.RECOVER_REMOVE -> R.string.container_chroot_recover_remove_notice
                    }))
                    if (!current) Text(stringResource(R.string.container_chroot_summary_stale), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm, enabled = current) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    uiState.info?.let { info ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(stringResource(R.string.container_chroot_info_title)) },
            text = { Text(stringResource(info)) },
            confirmButton = { TextButton(onClick = onClearError) { Text(stringResource(R.string.common_confirm)) } }
        )
    }
    uiState.error?.let { error ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(stringResource(R.string.container_chroot_error_title)) },
            text = { Text(stringResource(R.string.container_chroot_error_message, stringResource(error))) },
            confirmButton = { TextButton(onClick = onClearError) { Text(stringResource(R.string.common_confirm)) } }
        )
    }
}

@Composable
internal fun ChrootAuthorizationSummary(authorization: LocalRuntimeAuthorization) {
    SummaryField(R.string.container_chroot_summary_owner, "${ownerKindLabel(authorization.owner.kind)}\n${authorization.owner.scopeId}")
    SummaryField(R.string.container_chroot_summary_profile, authorization.profileId)
    SummaryField(R.string.container_chroot_summary_configuration, authorization.configurationRevision.toString())
    SummaryField(R.string.container_chroot_summary_source, authorization.sourceFingerprint)
    SummaryField(R.string.container_chroot_summary_digest, authorization.digest)
    InstanceSummary(authorization.instance)
    RootSummary(authorization.rootState)
    SummaryField(R.string.container_chroot_summary_workspace, authorization.workspacePath)
    SummaryField(R.string.container_chroot_summary_home, authorization.homePath)
    SummaryField(R.string.container_chroot_summary_mounts, authorization.mounts.map {
        stringResource(
            if (it.readOnly) R.string.container_chroot_mount_read_only else R.string.container_chroot_mount_read_write,
            it.source, it.target
        )
    }.joinToString("\n"))
    Text(stringResource(R.string.container_chroot_authorization_scope_notice))
}

@Composable
private fun InstanceSummary(instance: ChrootInstance) {
    SummaryField(
        R.string.container_chroot_summary_instance,
        stringResource(R.string.container_chroot_instance_identity, instance.id, instance.device, instance.inode, instance.installGeneration)
    )
}

@Composable
private fun RootSummary(root: RootAccessState) {
    SummaryField(
        R.string.container_chroot_summary_root,
        stringResource(R.string.container_chroot_root_identity, root.authorityId, root.generation,
            stringResource(if (root.enabled) R.string.common_enabled else R.string.common_disabled))
    )
}

@Composable
private fun SummaryField(@StringRes title: Int, value: String) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Text(stringResource(title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ownerKindLabel(kind: RuntimeOwnerKind): String = stringResource(when (kind) {
    RuntimeOwnerKind.AGENT -> R.string.container_chroot_owner_agent
    RuntimeOwnerKind.TERMINAL -> R.string.container_chroot_owner_terminal
    RuntimeOwnerKind.GIT -> R.string.container_chroot_owner_git
    RuntimeOwnerKind.MCP -> R.string.container_chroot_owner_mcp
    RuntimeOwnerKind.PLUGIN -> R.string.container_chroot_owner_plugin
    RuntimeOwnerKind.FILE -> R.string.container_chroot_owner_file
})
