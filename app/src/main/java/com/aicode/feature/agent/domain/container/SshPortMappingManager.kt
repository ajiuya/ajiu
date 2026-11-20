package com.aicode.feature.agent.domain.container

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.terminal.domain.TaskKeepaliveController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class PortMappingStatus { CONNECTING, ACTIVE, FAILED }

enum class PortMappingError {
    INVALID_PORT, LOCAL_MODE, NOT_CONNECTED, PORT_IN_USE, TARGET_UNREACHABLE,
    CONNECTION_FAILED, LIMIT_REACHED, CONNECTION_CHANGED, LISTEN_FAILED
}

data class PortMapping(
    val id: String,
    val remotePort: Int,
    val localPort: Int?,
    val status: PortMappingStatus,
    val error: PortMappingError? = null,
    val backgroundProtected: Boolean = false,
    val ownerPluginId: String? = null
)

data class PluginPortMappingOwner(val pluginId: String, val leaseId: String)

data class PortMappingState(
    val remoteMode: Boolean = false,
    val available: Boolean = false,
    val mappings: List<PortMapping> = emptyList()
)

internal data class PortMappingProtection(val protected: Boolean, val suspended: Boolean)

internal interface PortMappingKeepalive {
    val states: Flow<PortMappingProtection>
    fun snapshot(): PortMappingProtection
    fun acquire(owner: String)
    fun release(owner: String)
}

private class AndroidPortMappingKeepalive(private val context: Context) : PortMappingKeepalive {
    override val states = TaskKeepaliveController.states.map { PortMappingProtection(it.protected, it.suspended) }
    override fun snapshot(): PortMappingProtection {
        TaskKeepaliveController.isSuspended(context)
        return TaskKeepaliveController.snapshot().let { PortMappingProtection(it.protected, it.suspended) }
    }
    override fun acquire(owner: String) {
        TaskKeepaliveController.acquire(context, owner, cpu = true, network = true)
    }
    override fun release(owner: String) = TaskKeepaliveController.release(context, owner)
}

@Singleton
class SshPortMappingManager internal constructor(
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val settings: ContainerSettingsRepository,
    private val activeConfigFlow: Flow<RemoteConnectionConfig?>,
    private val keepalive: PortMappingKeepalive,
    private val transport: PortMappingTransport,
    private val scope: CoroutineScope
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        connection: RemoteSshConnection,
        modeHolder: ExecutionModeHolder,
        settings: ContainerSettingsRepository,
        resolver: ActiveRemoteConnectionResolver
    ) : this(
        connection, modeHolder, settings, resolver.activeConfigFlow,
        AndroidPortMappingKeepalive(context), SshPortMappingTransport(connection),
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private class Source(
        val revision: Long,
        val modeRevision: Long,
        val selectionRevision: Long,
        val identity: RemoteConnectionIdentity,
        val config: RemoteConnectionConfig
    )

    private class Lease(private val keepalive: PortMappingKeepalive) : AutoCloseable {
        private val owner = "port-mapping:${UUID.randomUUID()}"
        private val lock = Any()
        private var closed = false
        private var acquiring = false
        private var acquired = false

        fun acquire() {
            synchronized(lock) {
                if (closed) throw CancellationException("Port mapping closed")
                acquiring = true
            }
            try {
                keepalive.acquire(owner)
            } finally {
                val release = synchronized(lock) {
                    acquiring = false
                    if (closed) true else { acquired = true; false }
                }
                if (release) keepalive.release(owner)
            }
        }

        override fun close() {
            val release = synchronized(lock) {
                if (closed) return
                closed = true
                acquired && !acquiring
            }
            if (release) keepalive.release(owner)
        }
    }

    private class Token(
        val id: String,
        val remotePort: Int,
        var localPort: Int?,
        val source: Source?,
        val lease: Lease,
        var status: PortMappingStatus = PortMappingStatus.CONNECTING,
        var error: PortMappingError? = null,
        var job: Job? = null,
        var resource: PortMappingResource? = null,
        var leased: Boolean = false,
        var jobFinished: Boolean = true,
        var cleanups: Int = 0,
        val pluginOwner: PluginPortMappingOwner? = null,
        val pluginCheck: () -> Unit = {}
    ) {
        val released = CompletableDeferred<Unit>()
    }

    private class Cleanup(val token: Token, val reserved: Boolean, val job: Job?, val resource: PortMappingResource?, val lease: Lease) {
        fun close() {
            job?.cancel()
            runCatching { resource?.close() }
            runCatching { lease.close() }
        }
    }

    private val lock = Any()
    private val cleanupScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    private val tokens = linkedMapOf<String, Token>()
    private val reservations = mutableSetOf<Token>()
    private var sourceRevision = 0L
    private var configReady = false
    private var activeConfig: RemoteConnectionConfig? = null
    private var protection = PortMappingProtection(protected = false, suspended = false)
    private val _state = MutableStateFlow(PortMappingState())
    val state: StateFlow<PortMappingState> = _state.asStateFlow()

    @OptIn(InternalCoroutinesApi::class)
    private fun observeSources(activeConfigFlow: Flow<RemoteConnectionConfig?>) {
        scope.coroutineContext[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { closeAll() }
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                activeConfigFlow.collect { config ->
                    val cleanup = synchronized(lock) {
                        if (configReady && activeConfig == config) return@collect
                        configReady = true
                        activeConfig = config
                        invalidateSourceLocked()
                    }
                    cleanup.forEach(::scheduleCleanup)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val cleanup = synchronized(lock) {
                    configReady = false
                    activeConfig = null
                    invalidateSourceLocked()
                }
                cleanup.forEach(::scheduleCleanup)
                FileLogger.w("SshPortMapping", "Active SSH configuration unavailable")
            }
        }
        observe(modeHolder.mode) { invalidateSourceLocked() }
        observe(modeHolder.modeSelectionRevisions) { invalidateSourceLocked() }
        observe(settings.activeProfileSelectionRevisions) { invalidateSourceLocked() }
        observe(connection.identityState) { invalidateSourceLocked() }
        observe(keepalive.states) { next ->
            val removed = if (!protection.suspended && next.suspended) tokens.values.filter { it.status != PortMappingStatus.FAILED } else emptyList()
            protection = next
            val cleanup = removed.map { detachLocked(it) }
            removed.forEach { tokens.remove(it.id) }
            publishLocked()
            cleanup
        }
    }

    init { observeSources(activeConfigFlow) }

    private fun <T> observe(flow: Flow<T>, update: (T) -> List<Cleanup>) {
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            flow.collect { value ->
                val cleanup = synchronized(lock) { update(value) }
                cleanup.forEach(::scheduleCleanup)
            }
        }
    }

    private fun scheduleCleanup(cleanup: Cleanup) {
        cleanupScope.launch {
            try { cleanup.close() } finally {
                synchronized(lock) {
                    if (cleanup.reserved) cleanup.token.cleanups--
                    releaseReservationLocked(cleanup.token)
                }
            }
        }
    }

    private fun releaseReservationLocked(token: Token) {
        if (token.jobFinished && token.cleanups == 0) {
            reservations.remove(token)
            if (tokens[token.id] !== token || token.status == PortMappingStatus.FAILED) token.released.complete(Unit)
        }
    }

    fun add(remotePort: Int, localPort: Int? = null): String = addInternal(remotePort, localPort)

    fun addOwned(remotePort: Int, localPort: Int?, owner: PluginPortMappingOwner,
        identity: RemoteConnectionIdentity, check: () -> Unit): String {
        check()
        connection.checkIdentity(identity)
        return addInternal(remotePort, localPort, owner) {
            check(); connection.checkIdentity(identity)
        }
    }

    fun ownedMapping(owner: PluginPortMappingOwner, id: String): PortMapping? = synchronized(lock) {
        val token = tokens[id]?.takeIf { it.pluginOwner == owner } ?: return@synchronized null
        _state.value.mappings.firstOrNull { it.id == token.id }
    }

    suspend fun closeOwned(owner: PluginPortMappingOwner, id: String): Boolean {
        val token = synchronized(lock) { (tokens.values + reservations).firstOrNull { it.id == id && it.pluginOwner == owner } } ?: return false
        val cleanup = synchronized(lock) {
            if (tokens[id] === token) tokens.remove(id)
            detachLocked(token).also { publishLocked() }
        }
        scheduleCleanup(cleanup)
        token.released.await()
        return true
    }

    private fun addInternal(remotePort: Int, localPort: Int?, pluginOwner: PluginPortMappingOwner? = null,
        pluginCheck: () -> Unit = {}): String {
        pluginCheck()
        val id = UUID.randomUUID().toString()
        val initialProtection = keepalive.snapshot()
        val token = synchronized(lock) {
            protection = initialProtection
            val source = captureSourceLocked()
            val error = when {
                remotePort !in 1..65535 || (localPort != null && localPort !in 1..65535) -> PortMappingError.INVALID_PORT
                modeHolder.currentMode() != ExecutionMode.REMOTE_SSH -> PortMappingError.LOCAL_MODE
                source == null -> PortMappingError.NOT_CONNECTED
                reservations.size >= MAX_MAPPINGS -> PortMappingError.LIMIT_REACHED
                else -> null
            }
            Token(id, remotePort, localPort, source, Lease(keepalive), pluginOwner = pluginOwner, pluginCheck = pluginCheck).also { token ->
                tokens[id] = token
                if (error != null) {
                    token.status = PortMappingStatus.FAILED
                    token.error = error
                    token.released.complete(Unit)
                } else {
                    reservations += token
                    token.jobFinished = false
                    token.job = scope.launch(start = CoroutineStart.LAZY) { establish(token) }.also { job ->
                        job.invokeOnCompletion { failure ->
                            if (failure != null) fail(token, PortMappingError.CONNECTION_FAILED)
                            synchronized(lock) {
                                token.jobFinished = true
                                releaseReservationLocked(token)
                            }
                        }
                    }
                }
                publishLocked()
            }
        }
        token.job?.start()
        return id
    }

    fun close(id: String) {
        val cleanup = synchronized(lock) {
            val token = tokens.remove(id) ?: return
            detachLocked(token).also { publishLocked() }
        }
        scheduleCleanup(cleanup)
    }

    fun closeAll() {
        val cleanup = synchronized(lock) {
            val owned = (tokens.values + reservations).distinct().map(::detachLocked)
            tokens.clear()
            publishLocked()
            owned
        }
        cleanup.forEach(::scheduleCleanup)
    }

    fun address(id: String): String? {
        var stale: Token? = null
        val address = synchronized(lock) {
            val token = tokens[id]?.takeIf { it.status == PortMappingStatus.ACTIVE } ?: return@synchronized null
            if (!isCurrentLocked(token)) {
                stale = token
                null
            } else token.localPort?.let { "http://$PORT_MAPPING_LOOPBACK:$it" }
        }
        stale?.let { fail(it, PortMappingError.CONNECTION_CHANGED) }
        return address
    }

    private suspend fun establish(token: Token) {
        var owned: PortMappingResource? = null
        try {
            checkCurrent(token)
            val source = token.source ?: throw CancellationException("Port mapping source changed")
            if (activeConfigFlow.first() != source.config) throw CancellationException("SSH configuration changed")
            checkCurrent(token)
            token.lease.acquire()
            synchronized(lock) {
                checkCurrentLocked(token)
                token.leased = true
                publishLocked()
            }
            val context = currentCoroutineContext()
            val check = { context.ensureActive(); checkCurrent(token) }
            val resource = transport.open(source.identity, token.remotePort, token.localPort, check) {
                fail(token, PortMappingError.CONNECTION_FAILED)
            }
            owned = resource
            check()
            synchronized(lock) {
                checkCurrentLocked(token)
                token.resource = resource
                token.localPort = resource.localPort
                publishLocked()
            }
            check()
            resource.probe(check)
            check()
            resource.listen(check) {
                synchronized(lock) {
                    checkCurrentLocked(token)
                    token.status = PortMappingStatus.ACTIVE
                    publishLocked()
                }
            }
            fail(token, PortMappingError.LISTEN_FAILED)
        } catch (fatal: Error) {
            throw fatal
        } catch (failure: Exception) {
            val error = when (failure) {
                is PortMappingFailure -> failure.error
                is CancellationException -> PortMappingError.CONNECTION_CHANGED
                else -> PortMappingError.CONNECTION_FAILED
            }
            fail(token, error)
        } finally {
            runCatching { owned?.close() }
            runCatching { token.lease.close() }
        }
    }

    private fun checkCurrent(token: Token) = synchronized(lock) { checkCurrentLocked(token) }

    private fun checkCurrentLocked(token: Token) {
        if (!isCurrentLocked(token)) throw CancellationException("Port mapping source changed")
    }

    private fun isCurrentLocked(token: Token): Boolean {
        val source = token.source ?: return false
        return tokens[token.id] === token && token.status != PortMappingStatus.FAILED && sourceCurrentLocked(source) &&
            runCatching { token.pluginCheck() }.isSuccess
    }

    private fun captureSourceLocked(): Source? {
        if (modeHolder.currentMode() != ExecutionMode.REMOTE_SSH || !configReady) return null
        val config = activeConfig ?: return null
        if (connection.config != config) return null
        val identity = runCatching { connection.captureIdentity() }.getOrNull() ?: return null
        return Source(sourceRevision, modeHolder.modeSelectionRevision, settings.activeProfileSelectionRevision, identity, config)
            .takeIf(::sourceCurrentLocked)
    }

    private fun sourceCurrentLocked(source: Source): Boolean =
        source.revision == sourceRevision && modeHolder.currentMode() == ExecutionMode.REMOTE_SSH &&
            modeHolder.modeSelectionRevision == source.modeRevision &&
            settings.activeProfileSelectionRevision == source.selectionRevision &&
            activeConfig == source.config && connection.config == source.config &&
            runCatching { connection.checkIdentity(source.identity) }.isSuccess

    private fun fail(token: Token, error: PortMappingError) {
        val cleanup = synchronized(lock) {
            if (tokens[token.id] !== token || token.status == PortMappingStatus.FAILED) return
            token.status = PortMappingStatus.FAILED
            token.error = error
            detachLocked(token).also { publishLocked() }
        }
        scheduleCleanup(cleanup)
    }

    private fun invalidateSourceLocked(): List<Cleanup> {
        sourceRevision++
        val cleanup = tokens.values.filter { it.status != PortMappingStatus.FAILED }.map { token ->
            token.status = PortMappingStatus.FAILED
            token.error = PortMappingError.CONNECTION_CHANGED
            detachLocked(token)
        }
        publishLocked()
        return cleanup
    }

    private fun detachLocked(token: Token): Cleanup {
        val reserved = token in reservations
        if (reserved) token.cleanups++
        return Cleanup(token, reserved, token.job, token.resource, token.lease).also {
            token.job = null
            token.resource = null
            token.leased = false
        }
    }

    private fun publishLocked() {
        val failures = tokens.values.filter { it.status == PortMappingStatus.FAILED }
        failures.take((failures.size - MAX_FAILED_ROWS).coerceAtLeast(0)).forEach { tokens.remove(it.id) }
        _state.value = PortMappingState(
            remoteMode = modeHolder.currentMode() == ExecutionMode.REMOTE_SSH,
            available = captureSourceLocked() != null,
            mappings = tokens.values.map { token ->
                PortMapping(token.id, token.remotePort, token.localPort, token.status, token.error,
                    token.leased && token.status != PortMappingStatus.FAILED && protection.protected && !protection.suspended,
                    token.pluginOwner?.pluginId)
            }
        )
    }

    private companion object {
        const val MAX_MAPPINGS = 4
        const val MAX_FAILED_ROWS = 16
    }
}
