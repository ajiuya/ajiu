package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.settings.data.repository.ExecutionMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PluginProcessException(val code: String, override val message: String) : Exception(message)

class PluginRuntimeContext private constructor(
    val workspacePath: String,
    val sessionId: String?,
    val invocationId: String,
    internal val authorityIdentity: Any,
    internal val allowIdleCallbacks: Boolean,
    internal val sourceIdentity: Any?,
    internal val runtimeOwner: RuntimeOwner?,
    internal val localRuntimeCapture: LocalRuntimeCapture?,
    internal val runtimeProfile: ContainerProfile?,
    val checkCurrent: () -> Unit
) {
    constructor(workspacePath: String, sessionId: String?, invocationId: String, checkCurrent: () -> Unit) :
        this(workspacePath, sessionId, invocationId, Any(), false, null, null, null, null, checkCurrent)

    internal fun withExecutionSource(source: Any, owner: RuntimeOwner, capture: LocalRuntimeCapture?, profile: ContainerProfile?): PluginRuntimeContext {
        require(capture == null || capture.owner == owner)
        return PluginRuntimeContext(workspacePath, sessionId, invocationId, authorityIdentity, allowIdleCallbacks,
            source, owner, capture, profile, checkCurrent)
    }

    internal fun withBackgroundAuthority(identity: Any): PluginRuntimeContext =
        PluginRuntimeContext(workspacePath, sessionId, invocationId, identity, allowIdleCallbacks,
            sourceIdentity, runtimeOwner, localRuntimeCapture, runtimeProfile, checkCurrent)

    internal fun withIdleCallbacks(): PluginRuntimeContext =
        PluginRuntimeContext(workspacePath, sessionId, invocationId, authorityIdentity, true,
            sourceIdentity, runtimeOwner, localRuntimeCapture, runtimeProfile, checkCurrent)

    internal fun guarded(checkCurrent: () -> Unit): PluginRuntimeContext =
        PluginRuntimeContext(workspacePath, sessionId, invocationId, authorityIdentity, allowIdleCallbacks,
            sourceIdentity, runtimeOwner, localRuntimeCapture, runtimeProfile, checkCurrent)
}

internal const val PLUGIN_MAX_PROCESSES = 4
internal const val PLUGIN_MAX_INVOCATIONS = 32
internal const val PLUGIN_MAX_HOST_REQUESTS = 32
internal const val PLUGIN_MAX_LINE_BYTES = 1024 * 1024
internal const val PLUGIN_MAX_OUTPUT_BYTES = 8L * 1024 * 1024
internal const val PLUGIN_MAX_STDERR_BYTES = 1024L * 1024

internal class PluginRequestBudget(private val maximum: Int) {
    private val count = AtomicInteger()

    fun acquire(): Boolean {
        while (true) {
            val current = count.get()
            if (current >= maximum) return false
            if (count.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        check(count.decrementAndGet() >= 0)
    }
}

internal data class PluginLocalLaunch(
    val profile: ContainerProfile,
    val workingDirectory: String,
    val command: List<String>,
    val environment: Map<String, String>,
    val runtimeOwner: RuntimeOwner? = null,
    val localRuntimeCapture: LocalRuntimeCapture? = null,
    val checkSource: () -> Unit = {}
) {
    init {
        require(profile.mode == ExecutionMode.LOCAL_PROOT)
        require(command.isNotEmpty() && command.first().isNotBlank())
    }

    fun launcherArguments(): List<String> = listOf(
        "-c",
        "cd -- \"\$1\" && shift && { command -v \"\$1\" >/dev/null 2>&1 || exit 127; } && exec \"\$@\"",
        "plugin-launcher",
        workingDirectory
    ) + command
}

internal interface PluginOwnedProcess {
    fun close()
    suspend fun awaitClosed()
}

internal interface PluginRuntimeProcessFactory {
    suspend fun captureProfile(): ContainerProfile
    fun containerPath(file: File): String
    suspend fun start(launch: PluginLocalLaunch): Process
}
