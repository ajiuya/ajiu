package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.connection.channel.direct.Session

@Singleton
class RemoteAgentConfigCache @Inject constructor(
    private val connection: RemoteSshConnection,
    private val settings: ContainerSettingsRepository,
    private val modeHolder: ExecutionModeHolder,
    private val workspace: WorkspaceRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transport = SshAgentConfigTransport(connection, ::checkKey)
    private val engine = RemoteAgentConfigCacheEngine(transport, scope)
    internal val definitionRevision: Long get() = engine.revision

    internal fun captureProfile(): RemoteAgentConfigProfile = runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
        val revision = settings.activeProfileSelectionRevision
        val id = settings.activeProfileIdFlow.first()
        if (revision != settings.activeProfileSelectionRevision) throw CancellationException("Remote profile selection changed")
        RemoteAgentConfigProfile(id, revision)
    }

    internal fun sourceIdentity(source: RemoteSftpFileAccess.ExecutionSource): String {
        val key = key(source, "~/.aicode/skills", AgentConfigKind.SKILLS)
        checkKey(key)
        return "${key.identity}:${engine.revision}"
    }

    internal fun directory(source: RemoteSftpFileAccess.ExecutionSource, root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot? =
        runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
            WorkspaceExecutionContext.current()?.checkCurrent()
            val key = key(source, root, kind)
            checkKey(key)
            val snapshot = try { engine.directory(key, forceRefresh) } catch (_: RemoteAgentConfigUncacheableException) { null }
            currentCoroutineContext().ensureActive()
            WorkspaceExecutionContext.current()?.checkCurrent()
            checkKey(key)
            snapshot
        }

    internal fun invalidation(remotePath: String): () -> Unit {
        val config = connection.config ?: return {}
        val home = connection.remoteHome ?: return {}
        val remote = normalize(remotePath)
        val server = RemoteAgentConfigServer(config.host, config.port, config.username, home)
        val selected = workspace.currentPath().takeIf { it.isNotBlank() && it != "/" } ?: config.remoteWorkspacePath.trimEnd('/')
        val project = normalizeWorkspace(selected, home)
        val roots = listOf("${home.trimEnd('/')}/.aicode/skills", "${home.trimEnd('/')}/.aicode/agents",
            "$project/.aicode/skills", "$project/.aicode/agents").map(::normalize)
        return { engine.invalidate(server, remote, roots) }
    }

    private fun key(source: RemoteSftpFileAccess.ExecutionSource, root: String, kind: AgentConfigKind): RemoteAgentConfigKey {
        connection.checkIdentity(source.identity)
        val config = connection.config ?: throw CancellationException("SSH configuration unavailable")
        val profile = source.agentConfigProfile
        val workspaceRoot = normalizeWorkspace(source.workspaceRoot, source.identity.home)
        return RemoteAgentConfigKey(
            RemoteAgentConfigServer(config.host, config.port, config.username, source.identity.home), source.identity,
            workspaceRoot, profile, kind, root.trimEnd('/'), normalize(remotePathFor(root.trimEnd('/') + "/.", workspaceRoot, source.identity.home))
        )
    }

    private fun checkKey(key: RemoteAgentConfigKey) {
        if (modeHolder.currentMode() != ExecutionMode.REMOTE_SSH) throw CancellationException("Remote definition execution mode changed")
        connection.checkIdentity(key.connection)
        val config = connection.config ?: throw CancellationException("SSH configuration unavailable")
        val selected = workspace.currentPath().takeIf { it.isNotBlank() && it != "/" } ?: config.remoteWorkspacePath.trimEnd('/')
        if (key.server != RemoteAgentConfigServer(config.host, config.port, config.username, key.connection.home) ||
            key.workspace != normalizeWorkspace(selected, key.connection.home) || captureProfile() != key.profile
        ) throw CancellationException("Remote definition source changed")
        connection.checkIdentity(key.connection)
    }

    private fun normalizeWorkspace(root: String, home: String): String = normalize(
        when { root == "~" -> home; root.startsWith("~/") -> home.trimEnd('/') + root.removePrefix("~"); else -> root }
    )

    private fun normalize(path: String): String = Paths.get(path).normalize().toString().trimEnd('/').ifEmpty { "/" }
}

internal class SshAgentConfigTransport(
    private val connection: RemoteSshConnection,
    private val checkSource: (RemoteAgentConfigKey) -> Unit,
    private val timeoutMillis: Long = 30_000
) : RemoteAgentConfigTransport {
    override fun checkIdentity(key: RemoteAgentConfigKey) {
        connection.checkIdentity(key.connection)
        checkSource(key)
    }

    override suspend fun manifest(key: RemoteAgentConfigKey): Map<String, RemoteAgentConfigFingerprint> = RemoteAgentConfigProtocol.manifest(
        execute(key, RemoteAgentConfigProtocol.manifestCommand(key.remoteRoot, key.kind), byteArrayOf(), RemoteAgentConfigProtocol.MAX_MANIFEST_BYTES), key.kind
    )

    override suspend fun bodies(key: RemoteAgentConfigKey, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String> = RemoteAgentConfigProtocol.bodies(
        execute(key, RemoteAgentConfigProtocol.bodiesCommand(key.remoteRoot), RemoteAgentConfigProtocol.request(expected.keys), RemoteAgentConfigProtocol.MAX_WIRE_BYTES), expected
    )

    @OptIn(InternalCoroutinesApi::class)
    internal suspend fun execute(key: RemoteAgentConfigKey, command: String, stdin: ByteArray, limit: Int): ByteArray = withTimeout(timeoutMillis) {
        checkIdentity(key)
        val job = currentCoroutineContext()[Job]!!
        var acquired: Session? = null
        val session = try {
            runInterruptible(Dispatchers.IO) {
                checkIdentity(key)
                key.connection.owner.startSession().also { acquired = it }
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            job.ensureActive()
            throw failure
        }
        val closed = AtomicBoolean()
        fun close() { if (closed.compareAndSet(false, true)) session.close() }
        val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) runCatching { close() }
        }
        try {
            checkIdentity(key)
            val channel = runInterruptible(Dispatchers.IO) {
                checkIdentity(key)
                try { session.exec(command) } catch (failure: IOException) {
                    job.ensureActive()
                    throw failure
                }
            }
            currentCoroutineContext().ensureActive()
            val bytes = coroutineScope {
                val owner = currentCoroutineContext()[Job]!!
                val ioCancellation = owner.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause != null) runCatching { close() }
                }
                fun check() { owner.ensureActive(); checkIdentity(key) }
                try {
                    val output = async(Dispatchers.IO) {
                        try { channel.inputStream.use { RemoteAgentConfigProtocol.readLimited(it, limit, ::check) } } catch (failure: IOException) {
                            job.ensureActive()
                            throw failure
                        }
                    }
                    val error = async(Dispatchers.IO) {
                        try { channel.errorStream.use { RemoteAgentConfigProtocol.readLimited(it, 64 * 1024, ::check) }; Unit } catch (failure: IOException) {
                            job.ensureActive()
                            throw failure
                        }
                    }
                    val input = async(Dispatchers.IO) {
                        try {
                            channel.outputStream.use { stream ->
                                var offset = 0
                                while (offset < stdin.size) {
                                    check()
                                    val count = minOf(8192, stdin.size - offset)
                                    stream.write(stdin, offset, count)
                                    offset += count
                                }
                                check()
                            }
                        } catch (failure: IOException) {
                            job.ensureActive()
                            throw failure
                        }
                    }
                    input.await()
                    output.await().also { error.await() }
                } finally { ioCancellation.dispose() }
            }
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                channel.join(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            currentCoroutineContext().ensureActive()
            checkIdentity(key)
            if (channel.exitStatus == 31 && channel.exitSignal == null) throw RemoteAgentConfigUncacheableException()
            if (channel.exitStatus != 0 || channel.exitSignal != null) throw IOException("Remote configuration exec failed (exit=${channel.exitStatus ?: "missing"})")
            bytes
        } catch (failure: Throwable) {
            job.ensureActive()
            throw failure
        } finally {
            cancellation.dispose()
            runCatching { close() }
        }
    }
}
