package com.aicode.feature.plugin.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode

internal data class PluginProblem(
    val code: String,
    @param:StringRes val messageRes: Int,
    val detail: String = ""
)

internal fun PluginError.toProblem(): PluginProblem = PluginProblem(code.name, when (code) {
    PluginErrorCode.ARCHIVE_UNREADABLE -> R.string.plugin_error_archive_unreadable
    PluginErrorCode.ARCHIVE_TOO_LARGE,
    PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES,
    PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE -> R.string.plugin_error_archive_limit
    PluginErrorCode.ARCHIVE_UNSAFE_PATH,
    PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY,
    PluginErrorCode.ARCHIVE_SYMLINK -> R.string.plugin_error_archive_unsafe
    PluginErrorCode.MANIFEST_MISSING,
    PluginErrorCode.MANIFEST_TOO_LARGE,
    PluginErrorCode.MANIFEST_NOT_JSON,
    PluginErrorCode.FIELD_MISSING,
    PluginErrorCode.FIELD_INVALID -> R.string.plugin_error_manifest
    PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION,
    PluginErrorCode.UNSUPPORTED_FIELD,
    PluginErrorCode.UNSUPPORTED_CONTRIBUTION -> R.string.plugin_error_unsupported
    PluginErrorCode.API_INCOMPATIBLE -> R.string.plugin_error_api
    PluginErrorCode.DUPLICATE_ID -> R.string.plugin_error_duplicate
    PluginErrorCode.FILE_MISSING,
    PluginErrorCode.CONTENT_INVALID -> R.string.plugin_error_content
    PluginErrorCode.STORAGE_FAILED -> R.string.plugin_error_storage
    PluginErrorCode.REGISTRY_CORRUPT -> R.string.plugin_error_registry
    PluginErrorCode.PREVIEW_EXPIRED -> R.string.plugin_error_preview_expired
    PluginErrorCode.NOT_INSTALLED -> R.string.plugin_error_not_installed
    PluginErrorCode.TRUST_REQUIRED -> R.string.plugin_error_trust_required
    PluginErrorCode.TRUST_EXPIRED -> R.string.plugin_error_trust_expired
    PluginErrorCode.STALE_CAPTURE,
    PluginErrorCode.CONTENT_CHANGED -> R.string.plugin_error_stale
    PluginErrorCode.DEPENDENCIES_NOT_READY -> R.string.plugin_error_dependencies
}, detail)

@Composable
internal fun PluginProblems(problems: List<PluginProblem>, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = Spacing.sm)) {
        problems.forEach { problem ->
            Text(stringResource(problem.messageRes), color = MaterialTheme.colorScheme.error)
            Text(
                stringResource(R.string.plugin_error_detail, problem.code, problem.detail.take(2048)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
