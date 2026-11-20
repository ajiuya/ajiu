package com.aicode.feature.plugin.domain

import com.aicode.feature.plugin.data.PluginRecord
import java.io.File

// These values are facts about host validation, not a sandbox or a publisher signature.
enum class PluginStatus { DISABLED, ENABLED, INVALID }
data class InstalledPlugin(
    val record: PluginRecord,
    val status: PluginStatus,
    val manifest: PluginManifest? = null,
    val content: PluginContent = PluginContent(),
    val errors: List<PluginError> = emptyList()
) {
    val isActive: Boolean get() = status == PluginStatus.ENABLED && record.enabled &&
        record.trustHash == record.contentHash && manifest != null && errors.isEmpty()
}
data class PluginSnapshot(
    val revision: Long = 0,
    val plugins: List<InstalledPlugin> = emptyList(),
    val errors: List<PluginError> = emptyList()
)
class PluginCapture internal constructor(
    val record: PluginRecord,
    val manifest: PluginManifest,
    val content: PluginContent,
    val installDir: File,
    private val validate: (PluginCapture) -> Unit,
    private val validateActive: (PluginCapture) -> Unit,
    val cleanup: Boolean = false
) {
    val id: String get() = record.id
    val installRevision: String get() = record.installRevision
    val contentHash: String get() = record.contentHash
    val activationEpoch: Long get() = record.activationEpoch
    // Performs disk hashing; callers must run on IO, including before and after streamed work.
    fun checkCurrent() { validate(this) }
    fun checkActive() { validateActive(this) }
}
class PluginCaptureException(val error: PluginError) : IllegalStateException(error.code.name)
data class PluginContribution<T>(val capture: PluginCapture, val value: T)
data class PluginRiskSummary(
    val nativeProcess: Boolean,
    val customCommand: Boolean,
    val dependencies: Boolean,
    val htmlPages: Boolean,
    val tools: Boolean,
    val skills: Boolean,
    val agents: Boolean,
    val hooks: Boolean = false,
    val mcp: Boolean = false,
    val providers: Boolean = false,
    val sessionSources: Boolean = false,
    val capabilities: Set<PluginCapability> = emptySet()
)
data class PluginPackageExpectation(
    val id: String,
    val version: String,
    val publisher: String,
    val repository: String,
    val archiveSha256: String? = null
)
data class PluginPreview(
    val token: String,
    val manifest: PluginManifest,
    val content: PluginContent,
    val installedVersion: String?,
    val archiveSha256: String,
    val manifestSha256: String,
    val contentHash: String,
    val risks: PluginRiskSummary,
    val expiresAt: Long,
    val source: PluginInstallOrigin = PluginInstallOrigin(),
    val previousRevision: String? = null
)
sealed interface PluginPreviewResult {
    data class Success(val preview: PluginPreview) : PluginPreviewResult
    data class Failure(val errors: List<PluginError>) : PluginPreviewResult
}
sealed interface PluginInstallResult {
    data class Success(val plugin: InstalledPlugin) : PluginInstallResult
    data class Failure(val errors: List<PluginError>) : PluginInstallResult
}
sealed interface PluginOperationResult {
    data object Success : PluginOperationResult
    data class Failure(val errors: List<PluginError>) : PluginOperationResult
}
enum class PluginLifecycleKind(val event: String) {
    INSTALLED(PluginHookEvents.PLUGIN_INSTALLED),
    ENABLED(PluginHookEvents.PLUGIN_ENABLED),
    DISABLED(PluginHookEvents.PLUGIN_DISABLED),
    UNINSTALLED(PluginHookEvents.PLUGIN_UNINSTALLED)
}
data class PluginLifecycleEvent(val kind: PluginLifecycleKind, val capture: PluginCapture) {
    val event: String get() = kind.event
    val pluginId: String get() = capture.id
}

data class PluginTrustPreview(
    val token: String,
    val id: String,
    val installRevision: String,
    val contentHash: String,
    val activationEpoch: Long,
    val expiresAt: Long,
    val manifest: PluginManifest?,
    val risks: PluginRiskSummary?,
    val errors: List<PluginError> = emptyList()
)
