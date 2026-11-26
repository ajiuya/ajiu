package com.aicode.feature.agent.domain.root

import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootAuthorityCoordinator internal constructor(
    private val repository: RootAccessSettingsRepository,
    scope: CoroutineScope
) {
    @Inject
    constructor(repository: RootAccessSettingsRepository) : this(
        repository,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private companion object {
        const val REVOCATION_TIMEOUT_MS = 5_000L
    }

    private class Revocation(
        val onRevoke: () -> Unit,
        val awaitStopped: suspend () -> Unit
    )

    private val gate = Any()
    private val settingsMutex = Mutex()
    private val revocations = mutableSetOf<Revocation>()
    private val _accessState = MutableStateFlow(RootAccessState())
    val accessState: StateFlow<RootAccessState> = _accessState.asStateFlow()
    private var observedState: RootAccessState? = null
    private var ready = false
    private var blocked = false
    private var revoking = false
    private var revocationFailed = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                accessSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            repository.state.collect {
                synchronized(gate) { observeLocked(repository.state.value) }
            }
        }
    }

    suspend fun accessSnapshot(): RootAccessState {
        try {
            repository.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(gate) {
                blocked = true
                observeLocked(repository.state.value)
                revokeLocked(revocations.toList())
                publishLocked()
            }
            throw e
        }
        return synchronized(gate) {
            ready = true
            observeLocked(repository.state.value)
            publishLocked()
            _accessState.value
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settingsMutex.withLock {
            val pending = synchronized(gate) {
                blocked = true
                revocationFailed = false
                observeLocked(repository.state.value)
                revocations.toList().also { revokeLocked(it) }
            }
            withContext(NonCancellable) {
                val stopped = withTimeoutOrNull(REVOCATION_TIMEOUT_MS) {
                    supervisorScope {
                        pending.forEach { registration ->
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    registration.awaitStopped()
                                } catch (_: Throwable) {
                                    synchronized(gate) { revocationFailed = true }
                                    currentCoroutineContext().ensureActive()
                                }
                            }
                        }
                    }
                    true
                }
                if (stopped != true) synchronized(gate) { revocationFailed = true }
                try {
                    repository.setEnabled(enabled)
                    repository.snapshot()
                    synchronized(gate) {
                        ready = true
                        observeLocked(repository.state.value)
                        blocked = revocationFailed
                        publishLocked()
                    }
                } catch (e: Exception) {
                    synchronized(gate) {
                        observeLocked(repository.state.value)
                        publishLocked()
                    }
                    throw e
                }
            }
        }
    }

    fun <T> withCurrent(
        expectedAuthorityId: String,
        expectedGeneration: Long,
        block: () -> T
    ): T? = withLock { current ->
        if (!current.enabled || current.authorityId.isBlank() ||
            current.authorityId != expectedAuthorityId || current.generation != expectedGeneration
        ) {
            null
        } else {
            block()
        }
    }

    fun registerRevocation(
        onRevoke: () -> Unit,
        awaitStopped: suspend () -> Unit = {}
    ): AutoCloseable {
        val registration = Revocation(onRevoke, awaitStopped)
        withLock { current ->
            revocations.add(registration)
            if (!current.enabled) revokeLocked(listOf(registration))
        }
        return AutoCloseable {
            synchronized(gate) { revocations.remove(registration) }
        }
    }

    internal fun <T> withLock(block: (RootAccessState) -> T): T = synchronized(gate) {
        observeLocked(repository.state.value)
        block(currentLocked())
    }

    private fun observeLocked(next: RootAccessState) {
        if (observedState != next) {
            observedState = next
            revokeLocked(revocations.toList())
        }
        publishLocked()
    }

    private fun revokeLocked(pending: List<Revocation>) {
        val wasRevoking = revoking
        revoking = true
        publishLocked()
        try {
            pending.forEach {
                runCatching { it.onRevoke() }.onFailure {
                    revocationFailed = true
                    blocked = true
                }
            }
        } finally {
            revoking = wasRevoking
            publishLocked()
        }
    }

    private fun currentLocked(): RootAccessState {
        val current = observedState ?: RootAccessState()
        return if (!ready || blocked || revoking) current.copy(enabled = false) else current
    }

    private fun publishLocked() {
        _accessState.value = currentLocked()
    }
}
