package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class PluginRuntimeManager internal constructor(
    private val pluginManager: PluginManager,
    private val processFactory: PluginRuntimeProcessFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) : AutoCloseable {
    @Inject constructor(
        pluginManager: PluginManager,
        installer: ContainerInstaller,
        engine: LinuxContainerEngine,
        containerSettingsRepository: ContainerSettingsRepository,
        runtimeCommands: LocalRuntimeCommands
    ) : this(pluginManager, LocalPluginProcessFactory(installer, engine, containerSettingsRepository, runtimeCommands))

    private data class Identity(val id: String, val revision: String, val hash: String, val epoch: Long)
    private class Lane(val mutex: Mutex = Mutex(), var users: Int = 0)
    private class Entry(
        val identity: Identity,
        val authority: Any,
        val resident: Boolean,
        val checkCurrent: () -> Unit,
        var lastUsed: Long,
        var resource: PluginOwnedProcess? = null,
        var closing: Boolean = false,
        var busy: Boolean = false
    ) {
        val released = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val invocations = PluginRequestBudget(PLUGIN_MAX_INVOCATIONS)
    private val hostRequests = PluginRequestBudget(PLUGIN_MAX_HOST_REQUESTS)
    private val lanes = mutableMapOf<String, Lane>()
    private val entries = mutableSetOf<Entry>()
    private val hasEntries = MutableStateFlow(false)
    private val current = mutableMapOf<String, Entry>()
    private var closed = false

    init {
        scope.launch {
            pluginManager.snapshot.collect {
                val captured = synchronized(lock) { entries.toList() }
                captured.filter { runCatching { it.checkCurrent() }.isFailure }.forEach { retire(it) }
            }
        }
        scope.launch {
            hasEntries.collectLatest { active ->
                if (!active) return@collectLatest
                while (isActive) {
                    delay(1_000)
                    val owned = synchronized(lock) { entries.filterNot { it.closing } }
                    owned.forEach { entry ->
                        val stale = runCatching { entry.checkCurrent() }.isFailure
                        val idle = synchronized(lock) { !entry.busy && !entry.resident && clock() - entry.lastUsed >= 10 * 60_000 }
                        if (stale || idle) retire(entry)
                    }
                }
            }
        }
    }

    suspend fun invoke(
        capture: PluginCapture,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject,
        context: PluginRuntimeContext,
        hostRequest: suspend (String, JsonObject) -> JsonElement,
        method: String = "tool.invoke",
        timeoutMs: Long = 120_000L
    ): JsonElement = withContext(dispatcher) {
        if (method !in setOf("tool.invoke", "ui.invoke", "hook.invoke") || capture.cleanup && method != "hook.invoke" || handler.isBlank() || handler.length > 256 || timeoutMs !in 1..120_000L) {
            throw PluginProcessException("PLUGIN_ARGUMENT_INVALID", "Plugin invocation arguments are invalid")
        }
        withLane(capture.id) {
            capture.checkCurrent()
            context.checkCurrent()
            if (context.runtimeOwner?.kind != com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.PLUGIN ||
                context.sourceIdentity == null) {
                throw PluginProcessException("PLUGIN_CONTEXT_REQUIRED", "A plugin execution source owner is required")
            }
            if (runtime != capture.content.runtime || runtime.pluginId != capture.id || runtime.installDir != capture.installDir) {
                throw PluginProcessException("PLUGIN_CAPTURE_MISMATCH", "Plugin runtime does not belong to the capture")
            }
            if (!pluginManager.areDependenciesPrepared(capture)) {
                throw PluginProcessException("PLUGIN_DEPENDENCIES_REQUIRED", "Confirm dependency preparation in native plugin settings before invoking")
            }
            val dependencyDir = pluginManager.dependencyDirectory(capture)
            val executionDirectory = if (runtime.dependencies != null) {
                PluginDependencyImage.verify(capture, dependencyDir)
            } else runtime.installDir
            val identity = identity(capture)
            var entry = synchronized(lock) { current[capture.id] }
            val previous = entry
            if (previous != null && (previous.identity != identity || previous.authority !== context.authorityIdentity || previous.resource !is PluginProcessTransport)) {
                retire(previous)
                withTimeoutOrNull(2_000) { previous.resource?.awaitClosed() }
                entry = null
            }
            if (entry == null) {
                val profile = context.runtimeProfile ?: processFactory.captureProfile()
                if (profile.isChroot && (context.workspacePath.isBlank() || context.runtimeOwner?.kind != com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.PLUGIN ||
                        context.sourceIdentity == null || context.localRuntimeCapture == null)) {
                    throw PluginProcessException("PLUGIN_CONTEXT_REQUIRED", "A captured plugin workspace runtime authorization is required")
                }
                capture.checkCurrent()
                context.checkCurrent()
                val data = pluginManager.dataDirectory(capture.id)
                if (!data.isDirectory && !data.mkdirs()) throw PluginProcessException("PLUGIN_DATA_UNAVAILABLE", "Plugin private data is unavailable")
                val environment = buildMap {
                    put("AICODE_PLUGIN_ID", capture.id)
                    put("AICODE_PLUGIN_VERSION", capture.manifest.version.toString())
                    put("AICODE_DATA_DIR", processFactory.containerPath(data))
                    put("AICODE_WORKSPACE", context.workspacePath)
                    runtime.dependencies?.let {
                        if (it.tool == "pip") put("PYTHONPATH", processFactory.containerPath(File(dependencyDir, "python")))
                    }
                }
                val launch = PluginLocalLaunch(profile, processFactory.containerPath(executionDirectory), runtime.command.toList(), environment,
                    context.runtimeOwner, context.localRuntimeCapture, context.checkCurrent)
                val created = reserve(identity, context.authorityIdentity, runtime.resident) {
                    capture.checkActive()
                    context.checkCurrent()
                }
                try {
                    val transport = PluginProcessTransport(
                        processFactory = { processFactory.start(launch) },
                        checkStart = {
                            context.checkCurrent()
                            capture.checkCurrent()
                            if (runtime.dependencies != null) PluginDependencyImage.verify(capture, dependencyDir)
                            context.checkCurrent()
                        },
                        hostBudget = hostRequests,
                        dispatcher = dispatcher,
                        onClosed = { release(created) }
                    )
                    attach(created, transport)
                    entry = created
                } catch (error: Exception) {
                    releaseUnstarted(created)
                    throw error
                }
            }
            val owned = requireNotNull(entry)
            val transport = owned.resource as PluginProcessTransport
            synchronized(lock) { owned.busy = true; owned.lastUsed = clock() }
            val checked = context.guarded {
                capture.checkActive()
                context.checkCurrent()
            }
            var verified = false
            try {
                transport.request(method, buildJsonObject {
                    put("handler", handler)
                    put("args", args)
                }, checked, hostRequest, timeoutMs).also {
                    capture.checkCurrent()
                    if (runtime.dependencies != null) PluginDependencyImage.verify(capture, dependencyDir)
                    context.checkCurrent()
                    verified = true
                }
            } catch (error: Exception) {
                retire(owned)
                throw error
            } finally {
                if (!verified) runCatching {
                    capture.checkCurrent()
                    if (runtime.dependencies != null) PluginDependencyImage.verify(capture, dependencyDir)
                    context.checkCurrent()
                }
                synchronized(lock) { owned.busy = false; owned.lastUsed = clock() }
                if (!owned.resident) retire(owned)
            }
        }
    }

    internal suspend fun captureLocalProfile(): ContainerProfile = withContext(dispatcher) {
        val profile = processFactory.captureProfile()
        if (profile.mode != ExecutionMode.LOCAL_PROOT) {
            throw PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", "Dependency preparation requires a local container")
        }
        profile.copy(
            extraBindings = java.util.Collections.unmodifiableList(ArrayList(profile.extraBindings)),
            extraArgs = java.util.Collections.unmodifiableList(ArrayList(profile.extraArgs)),
            env = java.util.Collections.unmodifiableMap(LinkedHashMap(profile.env))
        )
    }

    internal suspend fun runPreparation(
        capture: PluginCapture,
        profile: ContainerProfile,
        directory: File,
        command: List<String>,
        prepareInputs: () -> Unit
    ): Int = withContext(dispatcher) {
        suspend fun checkTarget() {
            capture.checkCurrent()
            if (captureLocalProfile() != profile) {
                throw PluginProcessException("PLUGIN_DEPENDENCY_TARGET_CHANGED", "Local container changed; confirm dependency preparation again")
            }
            capture.checkCurrent()
        }
        withLane(capture.id) {
            checkTarget()
            synchronized(lock) { current[capture.id] }?.let { previous ->
                retire(previous)
                withTimeoutOrNull(2_000) { previous.resource?.awaitClosed() }
            }
            synchronized(lock) { entries.firstOrNull { it.identity.id == capture.id } }?.let {
                throw PluginProcessException("PLUGIN_BUSY", "Previous plugin resources are still closing")
            }
            prepareInputs()
            checkTarget()
            val launch = PluginLocalLaunch(profile, processFactory.containerPath(directory), command.toList(), emptyMap())
            val owned = reserve(identity(capture), Any(), false, capture::checkActive)
            try {
                val process = PluginPreparationProcess(
                    start = { checkTarget(); processFactory.start(launch) },
                    checkCurrent = capture::checkActive,
                    dispatcher = dispatcher,
                    onClosed = { release(owned) }
                )
                attach(owned, process)
                process.run().also { checkTarget() }
            } finally {
                retire(owned)
                releaseUnstarted(owned)
            }
        }
    }

    private suspend fun <T> withLane(id: String, block: suspend () -> T): T {
        if (!invocations.acquire()) throw PluginProcessException("PLUGIN_BUSY", "Plugin invocation limit reached")
        val lane = synchronized(lock) { lanes.getOrPut(id) { Lane() }.also { it.users++ } }
        try {
            return lane.mutex.withLock {
                synchronized(lock) { if (closed) throw PluginProcessException("PLUGIN_CLOSED", "Plugin runtime is closed") }
                block()
            }
        } finally {
            synchronized(lock) {
                lane.users--
                if (lane.users == 0 && lanes[id] === lane) lanes.remove(id)
            }
            invocations.release()
        }
    }

    private fun identity(capture: PluginCapture) = Identity(capture.id, capture.installRevision, capture.contentHash, capture.activationEpoch)

    private fun reserve(identity: Identity, authority: Any, resident: Boolean, checkCurrent: () -> Unit): Entry = synchronized(lock) {
        if (closed) throw PluginProcessException("PLUGIN_CLOSED", "Plugin runtime is closed")
        checkCurrent()
        if (entries.any { it.identity.id == identity.id }) {
            throw PluginProcessException("PLUGIN_BUSY", "Previous plugin resources are still closing")
        }
        if (entries.size >= PLUGIN_MAX_PROCESSES) throw PluginProcessException("PLUGIN_BUSY", "Local plugin process limit reached")
        Entry(identity, authority, resident, checkCurrent, clock()).also {
            entries.add(it)
            current[identity.id] = it
            hasEntries.value = true
        }
    }

    private fun attach(entry: Entry, resource: PluginOwnedProcess) {
        val shouldClose = synchronized(lock) {
            entry.resource = resource
            entry.closing || closed
        }
        if (shouldClose) resource.close()
    }

    private fun retire(entry: Entry) {
        val resource = synchronized(lock) {
            entry.closing = true
            if (current[entry.identity.id] === entry) current.remove(entry.identity.id)
            entry.resource
        }
        resource?.close()
    }

    private fun release(entry: Entry) = synchronized(lock) {
        entries.remove(entry)
        hasEntries.value = entries.isNotEmpty()
        if (current[entry.identity.id] === entry) current.remove(entry.identity.id)
        entry.released.complete(Unit)
        Unit
    }

    private fun releaseUnstarted(entry: Entry) {
        synchronized(lock) { if (entry.resource == null) release(entry) }
    }

    fun closePlugin(pluginId: String) {
        synchronized(lock) { entries.filter { it.identity.id == pluginId } }.forEach { retire(it) }
    }

    internal suspend fun closePluginAndAwait(pluginId: String, timeoutMs: Long = 5_000L): Boolean {
        require(timeoutMs in 1..120_000L)
        return withTimeoutOrNull(timeoutMs) {
            val lane = synchronized(lock) { lanes.getOrPut(pluginId) { Lane() }.also { it.users++ } }
            try {
                closePlugin(pluginId)
                lane.mutex.withLock {
                    while (true) {
                        val remaining = synchronized(lock) { entries.filter { it.identity.id == pluginId } }
                        if (remaining.isEmpty()) break
                        remaining.forEach { retire(it) }
                        remaining.forEach { it.released.await() }
                    }
                }
                true
            } finally {
                synchronized(lock) {
                    lane.users--
                    if (lane.users == 0 && lanes[pluginId] === lane) lanes.remove(pluginId)
                }
            }
        } ?: false
    }

    internal fun closeCapture(capture: PluginCapture) {
        val expected = identity(capture)
        synchronized(lock) { entries.filter { it.identity == expected } }.forEach { retire(it) }
    }

    override fun close() {
        val remaining = synchronized(lock) { closed = true; entries.toList() }
        remaining.forEach { retire(it) }
        scope.cancel()
    }

    internal suspend fun awaitClosed() {
        synchronized(lock) { entries.toList() }.forEach { it.released.await() }
    }
}

internal class LocalPluginProcessFactory(
    private val installer: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val settings: ContainerSettingsRepository,
    private val runtimeCommands: LocalRuntimeCommands
) : PluginRuntimeProcessFactory {
    override suspend fun captureProfile(): ContainerProfile {
        val selected = settings.activeProfileFlow.first()
        val profile = if (selected.mode == ExecutionMode.LOCAL_PROOT) selected else {
            val id = settings.defaultContainerIdFlow.first()
            settings.customProfilesFlow.first().firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
                ?: ContainerProfile.BUILTIN_ALPINE
        }
        if (profile.mode != ExecutionMode.LOCAL_PROOT || engine.notReadyHintFor(profile) != null) {
            throw PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", "Initialize a local container and install the declared runtime before retrying")
        }
        return profile.copy(extraBindings = profile.extraBindings.toList(), extraArgs = profile.extraArgs.toList(), env = profile.env.toMap())
    }

    override fun containerPath(file: File): String {
        val root = installer.aicodeDir.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        if (!path.startsWith(root)) throw PluginProcessException("PLUGIN_PATH_INVALID", "Plugin path is outside the local configuration directory")
        val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
        return "/root/.aicode" + if (relative.isEmpty()) "" else "/$relative"
    }

    override suspend fun start(launch: PluginLocalLaunch): Process {
        launch.checkSource()
        if (launch.profile.isChroot) {
            val capture = launch.localRuntimeCapture
                ?: throw PluginProcessException("PLUGIN_CONTEXT_REQUIRED", "A captured plugin workspace runtime authorization is required")
            if (capture.owner.kind != com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind.PLUGIN ||
                capture.owner != launch.runtimeOwner || capture.profileId != launch.profile.id) {
                throw PluginProcessException("PLUGIN_CAPTURE_MISMATCH", "Plugin runtime capture belongs to another source")
            }
            return runtimeCommands.startStdio(capture, "/bin/sh", launch.launcherArguments(), extraEnv = launch.environment)
        }
        return engine.startStdioProcess(
            program = "/bin/sh",
            programArgs = launch.launcherArguments(),
            projectPath = null,
            extraEnv = launch.environment,
            profile = launch.profile
        )
    }
}
