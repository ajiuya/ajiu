package com.aicode.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginDevPackageBuilder
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginNativeCapabilityChallenge
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginInstallOriginKind
import java.io.File
import java.nio.file.Files
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOperationResult
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.PluginTrustPreview
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.runtime.PluginActions
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import com.aicode.feature.plugin.runtime.PluginDependencyPreparer
import com.aicode.feature.plugin.runtime.PluginDependencyRequest
import com.aicode.feature.plugin.runtime.PluginDependencyResult
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginProcessException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class PluginDependencyConfirmation(val capture: PluginCapture, val request: PluginDependencyRequest)
internal data class PluginActionConfirmation(val capture: PluginCapture, val entry: PluginUiEntry, val context: PluginInvocationContext)
internal enum class PluginManagementPanel { IMPORT, MANAGE, SOURCES, PERMISSIONS, DEVELOPMENT }

internal data class NativePluginUiState(
    val busy: Boolean = false,
    val selectedId: String? = null,
    val managementPanel: PluginManagementPanel? = null,
    val installedId: String? = null,
    val preview: PluginPreview? = null,
    val trust: PluginTrustPreview? = null,
    val dependency: PluginDependencyConfirmation? = null,
    val action: PluginActionConfirmation? = null,
    val problems: List<PluginProblem> = emptyList(),
    val notice: Int? = null,
    val actionResult: String? = null,
    val capability: PluginNativeCapabilityChallenge? = null
)

@HiltViewModel
class NativePluginViewModel @Inject constructor(
    private val manager: PluginManager,
    private val dependencies: PluginDependencyPreparer,
    private val gateway: PluginHostGateway,
    private val actions: PluginActions,
    private val capabilities: PluginCapabilityRepository,
    private val operationGrants: com.aicode.feature.plugin.data.PluginOperationGrantRepository,
    private val installer: ContainerInstaller,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val snapshot = manager.snapshot
    val capabilityGrants = capabilities.grants
    private val mutableState = MutableStateFlow(NativePluginUiState())
    internal val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var owner: Any? = null
    private var operationGeneration = 0L

    internal fun attach(owner: Any) {
        if (this.owner === owner) return
        val previewToken = mutableState.value.preview?.token
        operation?.cancel()
        operationGeneration++
        this.owner = owner
        mutableState.update { it.copy(busy = false, preview = null, trust = null, dependency = null, action = null) }
        previewToken?.let(::releasePreview)
        refresh()
    }

    internal fun detach(owner: Any) {
        if (this.owner !== owner) return
        val previewToken = mutableState.value.preview?.token
        this.owner = null
        operation?.cancel()
        operationGeneration++
        operation = null
        mutableState.update { it.copy(busy = false, preview = null, trust = null, dependency = null, action = null, actionResult = null) }
        previewToken?.let(::releasePreview)
    }

    internal fun showManagement(panel: PluginManagementPanel) { mutableState.update { it.copy(managementPanel = panel) } }
    internal fun dismissManagement() { mutableState.update { it.copy(managementPanel = null) } }
    internal fun consumeInstalled() { mutableState.update { it.copy(installedId = null) } }
    internal fun select(id: String) { mutableState.update { it.copy(selectedId = id, problems = emptyList(), notice = null) } }
    internal fun dismissPreview() {
        if (mutableState.value.busy) return
        val token = mutableState.value.preview?.token ?: return
        mutableState.update { it.copy(preview = null) }
        releasePreview(token)
    }
    internal fun dismissTrust() { mutableState.update { it.copy(trust = null) } }
    internal fun dismissDependency() { mutableState.update { it.copy(dependency = null) } }
    internal fun dismissResult() { mutableState.update { it.copy(actionResult = null) } }
    internal fun dismissAction() {
        if (mutableState.value.action != null) operation?.cancel()
        mutableState.update { it.copy(action = null, busy = false) }
    }
    internal fun refresh() = perform { manager.ensureLoaded(); capabilities.ensureLoaded(); manager.refresh() }
    internal fun dismissCapability() { mutableState.update { it.copy(capability = null) } }
    internal fun clearOperationGrants(id: String) = perform { operationGrants.revoke(id) }
    internal fun prepareCapability(id: String, capability: PluginCapability) = perform {
        val challenge = capabilities.prepareGrant(capture(id), capability)
        mutableState.update { it.copy(capability = challenge, problems = if (challenge == null) listOf(PluginProblem("CAPABILITY_NOT_DECLARED", R.string.plugin_capability_not_declared)) else emptyList()) }
    }
    internal fun confirmCapability(token: String) {
        if (mutableState.value.capability?.nativeToken != token) return
        perform { operation(capabilities.confirmGrant(token)); dismissCapability() }
    }
    internal fun revokeCapability(id: String, capability: PluginCapability) = perform { operation(capabilities.revoke(id, capability)) }
    internal fun isCapabilityGranted(id: String, capability: PluginCapability): Boolean = manager.capture(id)?.let { capabilities.checkGranted(it, capability) } == true

    internal fun previewDirectory(path: String) {
        if (mutableState.value.busy) return
        val previewOwner = owner
        perform {
            var staged: String? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    require(path.startsWith('/') && path.length <= 200 && path.none { it.isISOControl() })
                    val root = installer.rootfsDir.canonicalFile
                    val source = File(root, path.removePrefix("/")).absoluteFile.toPath().normalize()
                    require(source.startsWith(root.toPath()) && source != root.toPath())
                    val archive = File.createTempFile("plugin-development-", ".zip", context.cacheDir)
                    try {
                        PluginDevPackageBuilder.zip(source.toFile(), archive)
                        val sourceId = java.security.MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                        val origin = PluginInstallOrigin(PluginInstallOriginKind.DEVELOPMENT, sourceId = sourceId, sourceName = path)
                        archive.inputStream().use { manager.preview(it, origin) }.also { if (it is PluginPreviewResult.Success) staged = it.preview.token }
                    } finally { Files.deleteIfExists(archive.toPath()) }
                }
                if (owner !== previewOwner) return@perform
                when (result) {
                    is PluginPreviewResult.Success -> { mutableState.update { it.copy(preview = result.preview) }; staged = null }
                    is PluginPreviewResult.Failure -> failed(result.errors)
                }
            } finally { staged?.let { manager.discardPreview(it) } }
        }
    }

    internal fun preview(uri: Uri) {
        if (mutableState.value.busy) return
        val previewOwner = owner
        val previousToken = mutableState.value.preview?.token
        mutableState.update { it.copy(preview = null) }
        previousToken?.let(::releasePreview)
        perform {
            var stagedToken: String? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            manager.preview(input).also { if (it is PluginPreviewResult.Success) stagedToken = it.preview.token }
                        } ?: PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.ARCHIVE_UNREADABLE)))
                    }
                }
                if (owner !== previewOwner) return@perform
                when (result) {
                    is PluginPreviewResult.Success -> {
                        mutableState.update { it.copy(preview = result.preview) }
                        stagedToken = null
                    }
                    is PluginPreviewResult.Failure -> failed(result.errors)
                }
            } finally {
                stagedToken?.let { manager.discardPreview(it) }
            }
        }
    }

    internal fun install(token: String) {
        if (mutableState.value.busy || mutableState.value.preview?.token != token) return
        val installOwner = owner
        mutableState.update { it.copy(preview = null) }
        perform(start = CoroutineStart.UNDISPATCHED) {
            val result = withContext(NonCancellable) {
                try { manager.install(token) }
                finally { manager.discardPreview(token) }
            }
            if (owner !== installOwner) return@perform
            when (result) {
                is PluginInstallResult.Success -> mutableState.update {
                    it.copy(trust = null, dependency = null, selectedId = result.plugin.record.id,
                        installedId = result.plugin.record.id, notice = R.string.plugin_install_disabled)
                }
                is PluginInstallResult.Failure -> failed(result.errors)
            }
        }
    }

    internal fun prepareTrust(id: String) = perform {
        val result = manager.prepareTrust(id)
        mutableState.update { it.copy(trust = result) }
    }

    internal fun enable(id: String, token: String) {
        val trust = mutableState.value.trust ?: return
        if (trust.id != id || trust.token != token || trust.errors.isNotEmpty()) return
        perform {
            operation(manager.enable(id, token))
            mutableState.update { it.copy(trust = null) }
        }
    }

    internal fun disable(id: String) = perform {
        mutableState.update { it.copy(trust = null, dependency = null) }
        operation(manager.disable(id))
    }

    internal fun uninstall(id: String, deleteData: Boolean) = perform {
        mutableState.update { it.copy(trust = null, dependency = null) }
        operation(manager.uninstall(id, deleteData))
    }

    internal fun describeDependencies(id: String) = perform {
        val capture = capture(id)
        val request = dependencies.describe(capture)
        mutableState.update { it.copy(dependency = request?.let { PluginDependencyConfirmation(capture, it) }) }
    }

    internal fun prepareDependencies(requestId: String) {
        val confirmation = mutableState.value.dependency ?: return
        if (confirmation.request.requestId != requestId) return
        perform {
            val token = dependencies.confirm(confirmation.request)
            mutableState.update { it.copy(dependency = null) }
            when (val result = dependencies.prepareDependencies(confirmation.capture, token, confirmation.request.inputDigest)) {
                is PluginDependencyResult.Ready -> mutableState.update { it.copy(notice = R.string.plugin_dependencies_ready) }
                is PluginDependencyResult.MissingRuntime -> mutableState.update { it.copy(action = null, problems = listOf(PluginProblem("PLUGIN_RUNTIME_MISSING", R.string.plugin_error_runtime_missing, result.message))) }
                is PluginDependencyResult.Failed -> mutableState.update { it.copy(action = null, problems = listOf(runtimeProblem(result.code ?: "PLUGIN_DEPENDENCY_FAILED", result.message))) }
            }
        }
    }

    internal fun describeAction(id: String, entry: PluginUiEntry) = perform {
        val capture = capture(id)
        val current = capture.content.actions.singleOrNull { it == entry }
            ?: throw PluginCaptureException(PluginError(PluginErrorCode.STALE_CAPTURE))
        val invocation = gateway.captureContext(capture)
        mutableState.update { it.copy(action = PluginActionConfirmation(capture, current, invocation)) }
    }

    internal fun invokeAction(confirmation: PluginActionConfirmation, args: JsonObject) {
        if (mutableState.value.action !== confirmation) return
        perform {
            val result = actions.invoke(confirmation.capture, requireNotNull(confirmation.entry.handler), args, confirmation.context)
            val output = result.toString()
            val display = if (output.length > 64 * 1024) output.take(64 * 1024) + "\n" + context.getString(R.string.plugin_output_truncated) else output
            mutableState.update { it.copy(action = null, actionResult = display) }
        }
    }

    internal suspend fun captureContext(capture: PluginCapture): PluginInvocationContext = gateway.captureContext(capture)
    internal suspend fun call(invocation: PluginInvocationContext, method: String, params: JsonObject): JsonElement = actions.call(invocation, method, params)

    internal fun capture(id: String): PluginCapture = manager.capture(id)
        ?: throw PluginCaptureException(PluginError(PluginErrorCode.TRUST_REQUIRED))

    private fun operation(result: PluginOperationResult) {
        when (result) {
            PluginOperationResult.Success -> Unit
            is PluginOperationResult.Failure -> failed(result.errors)
        }
    }

    private fun failed(errors: List<PluginError>) { mutableState.update { it.copy(action = null, problems = errors.map { error -> error.toProblem() }) } }

    private fun releasePreview(token: String) {
        val releaseOwner = owner
        val generation = operationGeneration
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { manager.discardPreview(token) }
            catch (_: Exception) {
                if (owner === releaseOwner && operationGeneration == generation) failed(listOf(PluginError(PluginErrorCode.STORAGE_FAILED)))
            }
        }
    }

    private fun perform(start: CoroutineStart = CoroutineStart.DEFAULT, block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        val currentOwner = owner
        val generation = ++operationGeneration
        mutableState.update { it.copy(busy = true, problems = emptyList(), notice = null) }
        operation = viewModelScope.launch(start = start) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                if (owner !== currentOwner || operationGeneration != generation) return@launch
                mutableState.update { it.copy(action = null, problems = listOf(PluginProblem("CONTEXT_CHANGED", R.string.plugin_error_context))) }
            } catch (failure: PluginCaptureException) {
                if (owner !== currentOwner || operationGeneration != generation) return@launch
                failed(listOf(failure.error))
            } catch (failure: PluginHostException) {
                if (owner !== currentOwner || operationGeneration != generation) return@launch
                mutableState.update { it.copy(action = null, problems = listOf(runtimeProblem(failure.code, failure.message.orEmpty()))) }
            } catch (failure: PluginProcessException) {
                if (owner !== currentOwner || operationGeneration != generation) return@launch
                mutableState.update { it.copy(action = null, problems = listOf(runtimeProblem(failure.code, failure.message))) }
            } catch (_: Exception) {
                if (owner !== currentOwner || operationGeneration != generation) return@launch
                mutableState.update { it.copy(action = null, problems = listOf(PluginProblem("OPERATION_FAILED", R.string.plugin_error_unexpected))) }
            } finally {
                if (owner === currentOwner && operationGeneration == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}

internal fun runtimeProblem(code: String, detail: String, method: String? = null): PluginProblem = PluginProblem(code, when (code) {
    "CAPABILITY_REQUIRED" -> when (method?.removePrefix("host.")) {
        "overlay.create", "overlay.update", "overlay.close", "overlay.postMessage", "overlay.self",
        "overlay.setDragRegion", "overlay.moveBy", "overlay.resize", "overlay.setVisibilityPolicy" -> R.string.plugin_error_overlay_capability_required
        else -> R.string.plugin_error_capability_required
    }
    "OVERLAY_SYSTEM_PERMISSION_MISSING" -> R.string.plugin_error_overlay_system_permission
    "OVERLAY_NOT_DECLARED", "SURFACE_NOT_FOUND" -> R.string.plugin_error_overlay_not_declared
    "OVERLAY_NOT_FOUND" -> R.string.plugin_error_overlay_not_found
    "OVERLAY_LIMIT" -> R.string.plugin_error_overlay_limit
    "OVERLAY_SERVICE_FAILED" -> R.string.plugin_error_overlay_failed
    "METHOD_NOT_SUPPORTED", "TOOL_NOT_ALLOWED", "TOOL_NOT_FOUND", "HANDLER_NOT_FOUND", "RUNTIME_REQUIRED", "PLUGIN_METHOD_NOT_SUPPORTED" -> R.string.plugin_error_not_supported
    "PLUGIN_RUNTIME_MISSING", "PLUGIN_RUNTIME_UNAVAILABLE", "PLUGIN_DEPENDENCY_TOOL" -> R.string.plugin_error_runtime_missing
    "PLUGIN_DEPENDENCIES_REQUIRED", "DEPENDENCIES_NOT_READY" -> R.string.plugin_error_dependencies
    "PLUGIN_CONFIRMATION_REQUIRED", "PLUGIN_DEPENDENCY_CHANGED" -> R.string.plugin_error_dependency_confirmation
    "CONTEXT_CHANGED", "CONTEXT_REQUIRED", "STALE_CONTEXT", "STALE_CAPTURE", "CONTENT_CHANGED", "PLUGIN_CAPTURE_MISMATCH" -> R.string.plugin_error_context
    "USER_REJECTED", "SYSTEM_DENIED", "PATH_FORBIDDEN", "OVERLAY_DENIED", "SYSTEM_PROTECTED" -> R.string.plugin_error_denied
    else -> R.string.plugin_error_runtime
}, detail.filterNot { it.isISOControl() }.take(2048))
