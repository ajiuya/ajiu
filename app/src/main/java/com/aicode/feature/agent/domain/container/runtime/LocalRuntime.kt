package com.aicode.feature.agent.domain.container.runtime

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.workspace.domain.FileAccessProvider
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class RuntimeOwnerKind { AGENT, TERMINAL, GIT, MCP, PLUGIN, FILE }

data class RuntimeOwner(val kind: RuntimeOwnerKind, val scopeId: String) {
    init {
        require(scopeId.isNotBlank() && scopeId.toByteArray(Charsets.UTF_8).size <= 384 && scopeId.none { it.isISOControl() })
    }

    internal val identity: String get() = "${if (kind == RuntimeOwnerKind.AGENT) "chat" else kind.name.lowercase()}:$scopeId"
    override fun toString(): String = "RuntimeOwner(kind=$kind)"
}

enum class LocalRuntimeCleanup { VERIFIED, UNKNOWN }

class LocalRuntimeAuthorizationRequired : IOException("CHROOT requires explicit authorization for this execution owner and source")

sealed interface LocalRuntimeTaskResult {
    data class Exited(val code: Int?, val signal: Int?) : LocalRuntimeTaskResult {
        init {
            require((code != null && code in 0..255 && signal == null) ||
                (signal != null && signal in 1..64 && code == null))
        }
        val terminalStatus: Int get() = code ?: -checkNotNull(signal)
    }
    data object Unknown : LocalRuntimeTaskResult
}

abstract class LocalRuntimeProcess : Process(), AutoCloseable {
    abstract val instanceId: String
    abstract val launchId: String
    abstract val taskState: StateFlow<LocalRuntimeTaskResult?>
    abstract val cleanupState: StateFlow<LocalRuntimeCleanup?>
    abstract suspend fun awaitTaskExit(): LocalRuntimeTaskResult
    abstract suspend fun awaitCleanup(): LocalRuntimeCleanup
    abstract fun resize(columns: Int, rows: Int): Boolean
    abstract fun signal(signal: Int): Boolean
    abstract fun stop()
    final override fun close() = stop()
}

class LocalRuntimeCapture internal constructor(
    val owner: RuntimeOwner,
    val profileId: String,
    val configurationRevision: Long,
    val instance: ChrootInstance,
    val workspacePath: String,
    val homePath: String,
    val digest: String,
    internal val binding: Any,
    internal val profile: ContainerProfile?,
    private val sourceCheck: () -> Unit
) {
    internal constructor(
        owner: RuntimeOwner,
        profileId: String,
        configurationRevision: Long,
        instance: ChrootInstance,
        workspacePath: String,
        homePath: String,
        digest: String,
        binding: Any,
        sourceCheck: () -> Unit
    ) : this(owner, profileId, configurationRevision, instance, workspacePath, homePath, digest, binding, null, sourceCheck)

    fun checkCurrent() = sourceCheck()
    override fun toString(): String = "LocalRuntimeCapture(profile=$profileId, instance=${instance.id})"
}

data class LocalRuntimeMount(val source: String, val target: String, val readOnly: Boolean)

class LocalRuntimeAuthorization internal constructor(
    val owner: RuntimeOwner,
    val profileId: String,
    val configurationRevision: Long,
    val instance: ChrootInstance,
    val rootState: RootAccessState,
    val workspacePath: String,
    val homePath: String,
    val digest: String,
    val mounts: List<LocalRuntimeMount>,
    val sourceFingerprint: String,
    internal val binding: Any
) {
    override fun toString(): String = "LocalRuntimeAuthorization(profile=$profileId, instance=${instance.id})"
}

data class LocalRuntimeState(
    val instance: ChrootInstance?,
    val rootEnabled: Boolean,
    val journalHealthy: Boolean,
    val recoveryRequired: Boolean,
    val activeLaunches: Int,
    val authorizedOwners: Int,
    val busy: Boolean
) {
    val installed: Boolean get() = instance != null && !recoveryRequired && journalHealthy
}

interface LocalRuntimeCommands {
    suspend fun capture(
        profile: ContainerProfile,
        configurationRevision: Long,
        workspacePath: String,
        owner: RuntimeOwner,
        sourceFingerprint: String,
        checkSource: () -> Unit
    ): LocalRuntimeCapture

    suspend fun exec(
        capture: LocalRuntimeCapture,
        command: String,
        cwd: String? = null,
        timeoutMs: Long = CommandEngine.DEFAULT_TIMEOUT_MS,
        unbounded: Boolean = false
    ): CommandResult

    fun execStream(
        capture: LocalRuntimeCapture,
        command: String,
        cwd: String? = null,
        timeoutMs: Long = CommandEngine.DEFAULT_TIMEOUT_MS
    ): Flow<CommandEvent>

    suspend fun startStdio(
        capture: LocalRuntimeCapture,
        program: String,
        args: List<String>,
        cwd: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 0
    ): LocalRuntimeProcess

    suspend fun startPty(
        capture: LocalRuntimeCapture,
        argv: List<String>,
        cwd: String? = null,
        extraEnv: Map<String, String> = emptyMap(),
        columns: Int = 80,
        rows: Int = 24
    ): LocalRuntimeProcess

    fun fileAccess(capture: LocalRuntimeCapture): FileAccessProvider
    suspend fun startFileWorker(capture: LocalRuntimeCapture): LocalRuntimeProcess
    fun revokeOwner(owner: RuntimeOwner)
}

interface LocalRuntimeUserActions {
    val changes: StateFlow<Long>
    val pendingAuthorizations: StateFlow<List<LocalRuntimeAuthorization>>
    fun state(profile: ContainerProfile): LocalRuntimeState

    suspend fun prepareAuthorization(request: LocalRuntimeAuthorization): LocalRuntimeAuthorization

    suspend fun authorizeFromUser(authorization: LocalRuntimeAuthorization): LocalRuntimeCapture
    suspend fun createInstanceFromUser(profile: ContainerProfile, expectedRoot: RootAccessState): ChrootInstance
    suspend fun stopInstance(instance: ChrootInstance): LocalRuntimeCleanup
    suspend fun removeInstanceFromUser(profile: ContainerProfile, expectedRoot: RootAccessState)
}
