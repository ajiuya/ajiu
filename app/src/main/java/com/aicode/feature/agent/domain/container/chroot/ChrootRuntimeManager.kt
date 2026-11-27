package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ChrootRuntimeManager internal constructor(
    private val authority: RootAuthorityCoordinator,
    private val factory: ChrootProcessFactory,
    private val recovery: ChrootRecoveryRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val controlFactory: (ChrootLaunch) -> ChrootControlTransport,
    private val clock: () -> Long,
    private val diagnostics: ChrootRuntimeDiagnostics? = null,
    private val cleanupTimeoutMs: Long = ChrootRuntimeTimeouts.CLEANUP_TIMEOUT_MS
) {
    @Inject
    constructor(
        authority: RootAuthorityCoordinator,
        factory: ChrootRuntimeProcessFactory,
        recovery: ChrootRecoveryRepository,
        diagnostics: ChrootRuntimeDiagnostics
    ) : this(
        authority, factory, recovery, CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO,
        { ChrootControlChannel(it.token(), it.appIdentity.uid) }, { System.nanoTime() / 1_000_000L }, diagnostics
    )

    private data class Configuration(val revision: Long, val instanceId: String, val rootfs: ChrootFileIdentity)

    private data class Lease(
        val id: String,
        val owner: String,
        val profile: String,
        val configuration: Configuration,
        val root: RootAccessState,
        val epoch: Long
    )

    private data class Permit(val lease: Lease, val digest: String, val revision: Long)

    private class Execution(val lease: Lease, val spec: ChrootLaunch, val checkSource: () -> Unit) {
        lateinit var handle: ChrootLaunchHandle
        val prepared = CompletableDeferred<Unit>()
        val inputLock = ReentrantLock()
        var eofRequested = false
        var eofSent = false
        @Volatile var process: Process? = null
        @Volatile var control: ChrootControlTransport? = null
        @Volatile var stopOnly = false
        @Volatile var go = false
        @Volatile var finished = false
        @Volatile var startupStage = ChrootRuntimeFailureStage.HELPER_CAPTURE
        var intentReserved = false
        var journaled = false
        var birthAttempted = false
        var ready = false
        var controlAuthenticated = false
        var stopSent = false
        @Volatile var exitReceived = false
        var cleanupEvidence: ChrootCleanupResult? = null
        var stdoutEnded = false
        var helperErrorsEnded = false
        var wrapperExited = false
        var wrapperStatusVerified = false
        var stopScheduled = false
        var stopTrigger: ChrootStopTrigger? = null
        @Volatile var completionEvidence: ChrootCompletionEvidence? = null
    }

    private val leases = mutableMapOf<String, Lease>()
    private val permits = mutableMapOf<String, Permit>()
    private val configurations = mutableMapOf<String, Configuration>()
    private val active = mutableMapOf<String, Execution>()
    private val revocationPending = mutableSetOf<Execution>()
    private val uncertainInstances = mutableSetOf<String>()
    private val localBlocked = MutableStateFlow<Set<String>>(emptySet())
    private var activationEpoch = 0L
    private var exhausted = false
    private var journalMaintenance = false
    val stateRevision = MutableStateFlow(0L)

    private fun changedLocked() {
        stateRevision.value = if (stateRevision.value == Long.MAX_VALUE) 0L else stateRevision.value + 1L
    }

    data class InstanceState(val activeLaunches: Int, val authorizedOwners: Int, val blocked: Boolean)

    fun instanceState(instanceId: String): InstanceState = authority.withLock { current ->
        InstanceState(
            active.values.count { it.spec.instanceId == instanceId },
            leases.values.filter { it.configuration.instanceId == instanceId && validLeaseLocked(it, current) }
                .map { it.owner }.distinct().size,
            !availableLocked()
        )
    }

    fun hasUnverifiedCleanup(instanceId: String): Boolean = authority.withLock {
        instanceId in uncertainInstances || instanceId in recovery.blockedInstances.value
    }

    fun failureSummary(instanceId: String): String? = diagnostics?.let { store ->
        (store.latestForLaunches(instanceId, recovery.unknownLaunchIds(instanceId)) ?: store.latestUnknown(instanceId))?.let {
            it.summary() + if (store.isHealthy) "" else ";diagnosticPersistence=UNAVAILABLE"
        }
    }

    suspend fun stopAndAwaitInstance(instanceId: String): List<ChrootCleanupResult> {
        val handles = authority.withLock {
            stopInstance(instanceId)
            active.values.filter { it.spec.instanceId == instanceId }.map { it.handle }
        }
        return handles.map { it.awaitCleanup() }
    }

    /** Stops known launches and prevents new leases/permits while a user repairs the journal. */
    suspend fun <T> withJournalMaintenance(block: suspend () -> T): T {
        val pending = authority.withLock {
            check(!journalMaintenance) { "Runtime journal maintenance is already active" }
            journalMaintenance = true
            leases.clear()
            permits.clear()
            active.values.toList().also { executions ->
                executions.forEach { markStopOnlyLocked(it) }
                scope.launch(dispatcher) { executions.forEach { stopExecution(it) } }
                changedLocked()
            }
        }
        try {
            withTimeout(ChrootRuntimeTimeouts.CLEANUP_AWAIT_TIMEOUT_MS) { pending.forEach { it.handle.awaitCleanup() } }
            authority.withLock { check(active.isEmpty()) { "Runtime launches have not settled" } }
            return block()
        } finally {
            authority.withLock { journalMaintenance = false; changedLocked() }
        }
    }

    val accessState get() = authority.accessState
    val blockedInstances = combine(recovery.blockedInstances, localBlocked) { journal, local -> journal + local }
        .stateIn(scope, SharingStarted.Eagerly, recovery.blockedInstances.value)

    init {
        authority.registerRevocation(
            onRevoke = {
                leases.clear()
                permits.clear()
                changedLocked()
                if (activationEpoch == Long.MAX_VALUE) exhausted = true else activationEpoch++
                val pending = active.values.toList()
                pending.forEach { markStopOnlyLocked(it) }
                revocationPending.addAll(pending)
                scope.launch(dispatcher) { pending.forEach { stopExecution(it) } }
            },
            awaitStopped = {
                val pending = authority.withLock { revocationPending.toList() }
                pending.forEach { it.handle.awaitCleanup() }
                authority.withLock { revocationPending.removeAll(pending.toSet()) }
            }
        )
    }

    // Compatibility for explicit lifecycle/device callers; a lease is an internal capability, not a UI approval.
    suspend fun activateFromUser(
        ownerIdentity: String,
        profileId: String,
        configurationRevision: Long,
        instanceId: String,
        rootfsIdentity: ChrootFileIdentity,
        expectedRootState: RootAccessState
    ): String? = allowBinding(ownerIdentity, profileId, configurationRevision, instanceId, rootfsIdentity, expectedRootState)

    suspend fun allowBinding(
        ownerIdentity: String,
        profileId: String,
        configurationRevision: Long,
        instanceId: String,
        rootfsIdentity: ChrootFileIdentity,
        expectedRootState: RootAccessState,
        checkCurrent: () -> Unit = {}
    ): String? {
        if (ownerIdentity.isBlank() || ownerIdentity.length > 512 || profileId.isBlank() ||
            configurationRevision < 0 || !NONCE.matches(instanceId) || !expectedRootState.enabled
        ) return null
        authority.accessSnapshot()
        return authority.withCurrent(expectedRootState.authorityId, expectedRootState.generation) {
            checkCurrent()
            if (!availableLocked() || exhausted) return@withCurrent null
            val next = Configuration(configurationRevision, instanceId, rootfsIdentity)
            val previous = configurations[profileId]
            if (previous != null && (configurationRevision < previous.revision ||
                    (configurationRevision == previous.revision && previous != next))) return@withCurrent null
            if (previous != null && previous != next) revokeMatchingLocked { it.profile == profileId }
            configurations[profileId] = next
            UUID.randomUUID().toString().also { id ->
                leases[id] = Lease(id, ownerIdentity, profileId, next, expectedRootState, activationEpoch)
                changedLocked()
            }
        }
    }

    suspend fun authorize(leaseId: String, launch: ChrootLaunch, profileRevision: Long): String? {
        authority.accessSnapshot()
        return authority.withLock { current ->
            val lease = leases[leaseId] ?: return@withLock null
            if (!validLeaseLocked(lease, current) || !matches(lease, launch, profileRevision) ||
                !availableLocked() || launch.launchId in active ||
                launch.launchId in recovery.recordedLaunchIds.value) return@withLock null
            UUID.randomUUID().toString().also { permits[it] = Permit(lease, launch.digest, profileRevision) }
        }
    }

    suspend fun launch(
        leaseId: String,
        permitId: String,
        spec: ChrootLaunch,
        checkSource: () -> Unit
    ): ChrootLaunchHandle? {
        authority.accessSnapshot()
        val execution = authority.withLock { current ->
            val permit = permits.remove(permitId) ?: return@withLock null
            val lease = leases[leaseId] ?: return@withLock null
            if (permit.lease != lease || permit.digest != spec.digest || !validLeaseLocked(lease, current) ||
                !matches(lease, spec, permit.revision) || !availableLocked() ||
                spec.launchId in active || spec.launchId in recovery.recordedLaunchIds.value ||
                !sourceValid(checkSource)) return@withLock null
            Execution(lease, spec, checkSource).also { execution ->
                execution.handle = ChrootLaunchHandle(
                    spec.instanceId, spec.launchId,
                    { sendInput(execution, ChrootFrameType.STDIN, it) },
                    { requestInputEof(execution) },
                    { columns, rows -> sendControl(execution, ChrootFrameType.RESIZE, ChrootWireProtocol.resize(columns, rows)) },
                    { signal -> sendControl(execution, ChrootFrameType.SIGNAL, byteArrayOf(signal.toByte())) },
                    { requestStop(execution) }
                )
                active[spec.launchId] = execution
                changedLocked()
            }
        } ?: return null
        scope.launch(dispatcher) { startExecution(execution) }
        return execution.handle
    }

    fun isLeaseCurrent(leaseId: String): Boolean = authority.withLock { current ->
        leases[leaseId]?.let { validLeaseLocked(it, current) && availableLocked() } == true
    }

    fun revokeLease(leaseId: String) = authority.withLock { revokeMatchingLocked { it.id == leaseId } }

    fun revokeOwner(ownerIdentity: String) = authority.withLock { revokeMatchingLocked { it.owner == ownerIdentity } }

    fun revokeProfile(profileId: String) = authority.withLock { revokeMatchingLocked { it.profile == profileId } }

    fun stopInstance(instanceId: String) = authority.withLock {
        revokeMatchingLocked { it.configuration.instanceId == instanceId }
        active.values.filter { it.spec.instanceId == instanceId }.forEach {
            markStopOnlyLocked(it)
            scope.launch(dispatcher) { stopExecution(it) }
        }
        Unit
    }

    private fun availableLocked(): Boolean = !journalMaintenance && recovery.isHealthy.value

    private fun validLeaseLocked(lease: Lease, current: RootAccessState): Boolean = !exhausted && current.enabled &&
        current.authorityId == lease.root.authorityId && current.generation == lease.root.generation &&
        lease.epoch == activationEpoch && leases[lease.id] == lease && configurations[lease.profile] == lease.configuration

    private fun matches(lease: Lease, spec: ChrootLaunch, revision: Long): Boolean =
        lease.owner == spec.ownerIdentity && lease.configuration.revision == revision &&
            lease.configuration.instanceId == spec.instanceId && lease.configuration.rootfs == spec.rootfsIdentity

    private fun revokeMatchingLocked(predicate: (Lease) -> Boolean) {
        val revoked = leases.values.filter(predicate).mapTo(hashSetOf()) { it.id }
        revoked.forEach(leases::remove)
        permits.entries.removeAll { it.value.lease.id in revoked }
        active.values.filter { it.lease.id in revoked }.forEach {
            markStopOnlyLocked(it)
            scope.launch(dispatcher) { stopExecution(it) }
        }
        if (revoked.isNotEmpty()) changedLocked()
    }

    private suspend fun startExecution(execution: Execution) {
        try {
            if (execution.stopOnly || execution.finished) {
                finishUnstarted(execution)
                return
            }
            execution.startupStage = ChrootRuntimeFailureStage.HELPER_CAPTURE
            val helper = factory.captureHelper()
            val reserved = authority.withLock {
                if (execution.stopOnly || execution.finished) false else {
                    execution.intentReserved = true
                    true
                }
            }
            if (!reserved) {
                finishUnstarted(execution)
                return
            }
            execution.startupStage = ChrootRuntimeFailureStage.INTENT_JOURNAL
            recovery.recordIntent(
                execution.spec.instanceId, execution.spec.launchId, execution.spec.rootfsIdentity,
                execution.spec.ownerIdentity, execution.lease.root.generation
            )
            authority.withLock { execution.journaled = true }
            execution.startupStage = ChrootRuntimeFailureStage.CONTROL_LISTENER
            val control = controlFactory(execution.spec)
            execution.control = control
            execution.startupStage = ChrootRuntimeFailureStage.PROCESS_START
            val process = authority.withCurrent(execution.lease.root.authorityId, execution.lease.root.generation) {
                if (!canRunLocked(execution)) null else {
                    execution.birthAttempted = true
                    factory.startChecked(helper) {
                        authority.withLock { canRunLocked(execution) }
                    }.also { execution.process = it }
                }
            }
            if (process == null) {
                control.close()
                finishUnstarted(execution)
                return
            }
            execution.startupStage = ChrootRuntimeFailureStage.STARTING_JOURNAL
            recovery.recordPhase(execution.spec.launchId, ChrootRecoveryPhase.STARTING)
            scope.launch(dispatcher) { readFrames(execution, process) }
            scope.launch(dispatcher) { readHelperErrors(execution, process) }
            scope.launch(dispatcher) {
                while (process.isAlive && !execution.finished) delay(20L)
                if (!process.isAlive) {
                    val wrapperStatus = runCatching { process.exitValue() }.getOrNull()
                    if (wrapperStatus != 0) {
                        recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_EXIT_STATUS)
                    }
                    authority.withLock {
                        execution.wrapperExited = true
                        execution.wrapperStatusVerified = wrapperStatus == 0
                    }
                    if (wrapperStatus != 0) requestStop(execution)
                    scheduleStopDeadline(execution, ChrootStopTrigger.HELPER_EXIT)
                    tryFinish(execution)
                }
            }
            scope.launch(dispatcher) {
                delay(START_TIMEOUT_MS)
                val startupIncomplete = authority.withLock {
                    !execution.go && !execution.finished &&
                        !(execution.stopOnly && execution.controlAuthenticated && execution.ready && execution.stopSent)
                }
                if (startupIncomplete) {
                    recordFailure(execution, ChrootRuntimeFailureKind.TIMEOUT, execution.startupStage)
                    failChannel(execution)
                }
            }
            execution.startupStage = ChrootRuntimeFailureStage.LAUNCH_WRITE
            ChrootWireProtocol.write(process.outputStream, ChrootFrameType.LAUNCH, execution.spec.payload())
            withTimeout(START_TIMEOUT_MS) {
                execution.startupStage = ChrootRuntimeFailureStage.CONTROL_HANDSHAKE
                control.awaitHelper()
                authority.withLock { execution.controlAuthenticated = true }
                execution.startupStage = ChrootRuntimeFailureStage.PREPARED_READY
                execution.prepared.await()
            }
            // An already-dispatched helper still needs its authenticated control channel and
            // native READY to stop cleanly. Cancellation never grants GO or resets the stop budget.
            if (execution.finished) return
            if (execution.stopOnly) {
                stopExecution(execution)
                return
            }
            execution.startupStage = ChrootRuntimeFailureStage.GO
            var goAttempted = false
            val sent = authority.withCurrent(execution.lease.root.authorityId, execution.lease.root.generation) {
                if (execution.stopOnly || execution.finished) false else {
                    goAttempted = true
                    if (!canRunLocked(execution) || !execution.ready) false
                    else control.trySend(ChrootFrameType.GO).also { sent ->
                        if (sent) {
                            execution.go = true
                            // EXIT is consumed under the same authority gate. Publish successful
                            // startup before a fast task can complete and resolve readiness false.
                            execution.handle.markReady()
                        }
                    }
                }
            } == true
            if (!sent) {
                if (goAttempted && !execution.finished) {
                    recordFailure(execution, ChrootRuntimeFailureKind.STARTUP, ChrootRuntimeFailureStage.GO)
                }
                requestStop(execution)
                return
            }
            flushInputEof(execution)
            execution.startupStage = ChrootRuntimeFailureStage.RUNNING_JOURNAL
            recovery.recordPhase(execution.spec.launchId, ChrootRecoveryPhase.RUNNING)
            scope.launch(dispatcher) {
                while (!execution.finished && !execution.stopOnly && !execution.exitReceived) {
                    delay((execution.spec.leaseMs / 3L).coerceAtLeast(1_000L))
                    if (execution.finished || execution.stopOnly || execution.exitReceived) break
                    if (!sendControl(execution, ChrootFrameType.HEARTBEAT)) break
                }
            }
        } catch (_: TimeoutCancellationException) {
            recordFailure(execution, ChrootRuntimeFailureKind.TIMEOUT, execution.startupStage)
            if (execution.process == null) finishUnstarted(execution) else failChannel(execution)
        } catch (_: CancellationException) {
            recordFailure(execution, ChrootRuntimeFailureKind.CANCELLED, execution.startupStage)
            if (execution.process == null) finishUnstarted(execution) else failChannel(execution)
        } catch (_: Exception) {
            val kind = if (execution.startupStage in setOf(ChrootRuntimeFailureStage.LAUNCH_WRITE,
                    ChrootRuntimeFailureStage.CONTROL_HANDSHAKE, ChrootRuntimeFailureStage.GO)) {
                ChrootRuntimeFailureKind.TRANSPORT
            } else {
                ChrootRuntimeFailureKind.STARTUP
            }
            recordFailure(execution, kind, execution.startupStage)
            if (execution.process == null) finishUnstarted(execution) else failChannel(execution)
        }
    }

    private fun canRunLocked(execution: Execution): Boolean {
        val valid = !execution.stopOnly && !execution.finished && active[execution.spec.launchId] === execution &&
            validLeaseLocked(execution.lease, authority.accessState.value) && recovery.isHealthy.value &&
            sourceValid(execution.checkSource)
        if (!valid && !execution.stopOnly && !execution.finished) {
            markStopOnlyLocked(execution)
            scope.launch(dispatcher) { stopExecution(execution) }
        }
        return valid
    }

    private fun sendControl(execution: Execution, type: ChrootFrameType, body: ByteArray = byteArrayOf()): Boolean {
        val sent = authority.withCurrent(execution.lease.root.authorityId, execution.lease.root.generation) {
            if (!canRunLocked(execution) || !execution.go || execution.exitReceived) false
            else execution.control?.trySend(type, body) == true
        } == true
        if (!sent && !execution.finished) {
            recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.CONTROL_WRITE)
            requestStop(execution)
        }
        return sent
    }

    private fun requestInputEof(execution: Execution) {
        if (!authority.withLock { canRunLocked(execution) }) return
        execution.inputLock.lock()
        try {
            if (execution.stopOnly || execution.finished || execution.exitReceived) return
            execution.eofRequested = true
            flushInputEofLocked(execution)
        } finally {
            execution.inputLock.unlock()
        }
    }

    private fun flushInputEof(execution: Execution) {
        execution.inputLock.lock()
        try {
            flushInputEofLocked(execution)
        } finally {
            execution.inputLock.unlock()
        }
    }

    private fun flushInputEofLocked(execution: Execution) {
        if (!execution.eofRequested || execution.eofSent || !execution.go ||
            execution.stopOnly || execution.finished || execution.exitReceived) return
        try {
            val process = execution.process ?: throw IOException("Runtime input is unavailable")
            ChrootWireProtocol.write(process.outputStream, ChrootFrameType.STDIN_EOF)
            execution.eofSent = true
        } catch (_: Exception) {
            recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.INPUT_WRITE)
            requestStop(execution)
            throw IOException("Runtime input channel is unavailable")
        }
    }

    private fun sendInput(execution: Execution, type: ChrootFrameType, body: ByteArray) {
        if (!authority.withLock { canRunLocked(execution) && execution.go }) throw IOException("Runtime input is unavailable")
        execution.inputLock.lock()
        try {
            if (execution.stopOnly || execution.finished || !execution.go || execution.eofRequested) {
                throw IOException("Runtime input is unavailable")
            }
            val process = execution.process ?: throw IOException("Runtime input is unavailable")
            ChrootWireProtocol.write(process.outputStream, type, body)
        } catch (_: Exception) {
            recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.INPUT_WRITE)
            requestStop(execution)
            throw IOException("Runtime input channel is unavailable")
        } finally {
            execution.inputLock.unlock()
        }
    }

    private suspend fun readFrames(execution: Execution, process: Process) {
        var stage = ChrootRuntimeFailureStage.FRAME_READ
        try {
            while (!execution.finished) {
                stage = ChrootRuntimeFailureStage.FRAME_READ
                val frame = ChrootWireProtocol.read(process.inputStream)
                if (frame == null) {
                    val complete = authority.withLock {
                        execution.stdoutEnded = true
                        execution.cleanupEvidence != null && execution.ready
                    }
                    if (complete) tryFinish(execution) else {
                        recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDOUT_EOF)
                        failChannel(execution)
                    }
                    return
                }
                stage = ChrootRuntimeFailureStage.FRAME_SEQUENCE
                if (frame.type == ChrootFrameType.ERROR) {
                    val failure = ChrootRuntimeFailure.nativeError(frame.body)
                    recordNativeFailure(execution, failure)
                    recordFailure(execution, failure)
                }
                var observedFailure: ChrootRuntimeFailure? = null
                val accepted = authority.withLock {
                    if (execution.finished || execution.cleanupEvidence != null) return@withLock false
                    when (frame.type) {
                        ChrootFrameType.READY -> {
                            if (execution.ready) false else if (!MessageDigest.isEqual(frame.body, execution.spec.token())) {
                                observedFailure = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.PROTOCOL, ChrootRuntimeFailureStage.READY_TOKEN)
                                false
                            } else {
                                execution.ready = true
                                execution.prepared.complete(Unit)
                                true
                            }
                        }
                        ChrootFrameType.STDOUT, ChrootFrameType.STDERR -> execution.go && !execution.exitReceived &&
                            execution.handle.appendOutput(frame.type == ChrootFrameType.STDERR, frame.body).also {
                                if (!it) observedFailure = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.PROTOCOL, ChrootRuntimeFailureStage.OUTPUT_BUFFER)
                            }
                        ChrootFrameType.EXIT -> {
                            val cancelledBeforeGo = execution.stopOnly && execution.controlAuthenticated &&
                                execution.ready && execution.stopSent && !execution.go
                            if (execution.exitReceived || (!execution.go && !cancelledBeforeGo)) false else {
                                val result = ChrootWireProtocol.exit(frame.body)
                                execution.exitReceived = true
                                // The bootstrap can exit after STOP without ever executing the
                                // requested command. Its exit is not a successful task execution.
                                execution.handle.completeTask(if (execution.go) ChrootTaskResult.Exited(result)
                                    else ChrootTaskResult.Unknown)
                                true
                            }
                        }
                        ChrootFrameType.CLEANUP -> {
                            if (!execution.ready || (!execution.exitReceived && !execution.stopOnly)) false else {
                                execution.cleanupEvidence = ChrootWireProtocol.cleanup(frame.body)
                                if (execution.cleanupEvidence == ChrootCleanupResult.UNKNOWN) {
                                    observedFailure = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.CLEANUP, ChrootRuntimeFailureStage.CLEANUP_EVIDENCE)
                                }
                                markStopOnlyLocked(execution)
                                true
                            }
                        }
                        else -> false
                    }
                }
                observedFailure?.let { recordFailure(execution, it) }
                if (!accepted) {
                    recordFailure(execution, ChrootRuntimeFailureKind.PROTOCOL, ChrootRuntimeFailureStage.FRAME_SEQUENCE)
                    failChannel(execution)
                    return
                }
                if (frame.type == ChrootFrameType.CLEANUP || frame.type == ChrootFrameType.EXIT) {
                    scheduleStopDeadline(execution,
                        if (frame.type == ChrootFrameType.EXIT) ChrootStopTrigger.TASK_EXIT else ChrootStopTrigger.CLEANUP)
                    tryFinish(execution)
                }
            }
        } catch (failure: Exception) {
            val kind = if (failure is ProtocolException) ChrootRuntimeFailureKind.PROTOCOL else ChrootRuntimeFailureKind.TRANSPORT
            recordFailure(execution, kind, stage)
            failChannel(execution)
        }
    }

    private fun readHelperErrors(execution: Execution, process: Process) {
        try {
            val input = process.errorStream
            val first = input.read()
            if (first >= 0) {
                recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
                requestStop(execution)
                try {
                    val available = input.available()
                    if (available in 0..254) {
                        val bytes = ByteArray(available + 1)
                        bytes[0] = first.toByte()
                        if (available == 0 || input.read(bytes, 1, available) == available) {
                            ChrootRuntimeFailure.nativeStderr(bytes)?.let { recordNativeFailure(execution, it) }
                        }
                    }
                } finally {
                    completeFinal(execution, ChrootCleanupResult.UNKNOWN)
                }
            } else {
                authority.withLock { execution.helperErrorsEnded = true }
                tryFinish(execution)
            }
        } catch (_: Exception) {
            if (!execution.finished) {
                recordFailure(execution, ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR_READ)
                failChannel(execution)
            }
        }
    }

    private fun requestStop(execution: Execution) {
        authority.withLock { markStopOnlyLocked(execution) }
        scope.launch(dispatcher) { stopExecution(execution) }
    }

    private fun markStopOnlyLocked(execution: Execution) {
        execution.stopOnly = true
        (execution.process as? ChrootBrokerProcess)?.cancelPendingLaunch()
        execution.handle.markStopOnly()
    }

    private fun stopExecution(execution: Execution) {
        var claimedBeforeIntent = false
        val undispatched = authority.withLock {
            if (execution.finished) return@withLock null
            if (!execution.intentReserved) {
                claimedBeforeIntent = claimFinalLocked(execution, ChrootCleanupResult.VERIFIED)
                true
            } else if ((execution.process as? ChrootBrokerProcess)?.wasCancelledBeforeDispatch == true) true
            else {
                // STOP before native READY can abort enrollment and cannot provide the existing
                // cleanup proof. Keep both listener and stdin alive while the cancelled startup
                // reaches READY, without allowing GO. The original deadline still bounds this wait.
                if (execution.controlAuthenticated && execution.ready && !execution.stopSent) {
                    val control = execution.control
                    val sent = runCatching { control?.trySend(ChrootFrameType.STOP) == true }.getOrDefault(false)
                    execution.stopSent = sent
                    if (!sent) runCatching { control?.close() }
                }
                false
            }
        } ?: return
        if (claimedBeforeIntent) publishFinal(execution, ChrootCleanupResult.VERIFIED)
        else if (undispatched) completeFinal(execution, ChrootCleanupResult.VERIFIED)
        else scheduleStopDeadline(execution)
    }

    private fun scheduleStopDeadline(execution: Execution, trigger: ChrootStopTrigger = ChrootStopTrigger.STOP) {
        val schedule = authority.withLock {
            if (execution.finished || execution.stopScheduled) false else {
                execution.stopScheduled = true
                execution.stopTrigger = trigger
                true
            }
        }
        if (schedule) scope.launch(dispatcher) {
            val deadline = clock() + cleanupTimeoutMs
            delay((deadline - clock()).coerceIn(1L, cleanupTimeoutMs))
            val decision = authority.withLock {
                if (execution.finished) null else {
                    // EOF/status collectors may have published their proof just before this
                    // deadline without yet reaching tryFinish. Decide and claim under one gate.
                    val complete = completionResultLocked(execution)
                    val result = complete ?: ChrootCleanupResult.UNKNOWN
                    if (complete == null) execution.completionEvidence = completionEvidenceLocked(execution)
                    if (claimFinalLocked(execution, result)) result to (complete == null) else null
                }
            } ?: return@launch
            if (decision.second) {
                recordFailure(execution, ChrootRuntimeFailureKind.TIMEOUT, ChrootRuntimeFailureStage.STOP_DEADLINE)
            }
            publishFinal(execution, decision.first)
        }
    }

    private fun completionEvidenceLocked(execution: Execution): ChrootCompletionEvidence {
        var flags = 0
        if (execution.ready) flags = flags or ChrootCompletionEvidence.READY
        if (execution.go) flags = flags or ChrootCompletionEvidence.GO
        if (execution.exitReceived) flags = flags or ChrootCompletionEvidence.TASK_EXIT
        if (execution.cleanupEvidence != null) flags = flags or ChrootCompletionEvidence.CLEANUP_SEEN
        if (execution.cleanupEvidence == ChrootCleanupResult.VERIFIED) flags = flags or ChrootCompletionEvidence.CLEANUP_VERIFIED
        if (execution.stdoutEnded) flags = flags or ChrootCompletionEvidence.STDOUT_ENDED
        if (execution.helperErrorsEnded) flags = flags or ChrootCompletionEvidence.STDERR_ENDED
        if (execution.wrapperExited) flags = flags or ChrootCompletionEvidence.HELPER_EXITED
        if (execution.wrapperStatusVerified) flags = flags or ChrootCompletionEvidence.HELPER_STATUS_VERIFIED
        return ChrootCompletionEvidence(checkNotNull(execution.stopTrigger), flags)
    }

    private fun recordFailure(execution: Execution, kind: ChrootRuntimeFailureKind, stage: ChrootRuntimeFailureStage) =
        recordFailure(execution, ChrootRuntimeFailure.local(kind, stage))

    private fun recordFailure(execution: Execution, failure: ChrootRuntimeFailure) {
        if (execution.handle.recordFailure(failure)) reportDiagnostic(execution)
    }

    private fun recordNativeFailure(execution: Execution, failure: ChrootRuntimeFailure) {
        if (execution.handle.recordNativeFailure(failure)) reportDiagnostic(execution)
    }

    private fun reportDiagnostic(execution: Execution, cleanup: ChrootCleanupResult? = null) {
        val store = diagnostics ?: return
        val failure = execution.handle.failure.value
        val nativeFailure = execution.handle.nativeFailure.value
        val cleanupEvidence = cleanup ?: execution.handle.cleanupState.value
        val completionEvidence = execution.completionEvidence
        // Never put diagnostic disk I/O inside the authority gate or delay STOP/UNKNOWN publication.
        scope.launch(dispatcher) {
            store.record(execution.spec.instanceId, execution.spec.launchId, failure, nativeFailure, cleanupEvidence, completionEvidence)
        }
    }

    private fun failChannel(execution: Execution) {
        requestStop(execution)
        // Only the broker's successful pending->cancelled CAS proves no START was committed.
        // A dead/failed process, missing descriptor reply or absent PID is not such proof.
        val undispatched = (execution.process as? ChrootBrokerProcess)?.wasCancelledBeforeDispatch == true
        completeFinal(execution, if (undispatched) ChrootCleanupResult.VERIFIED else ChrootCleanupResult.UNKNOWN)
    }

    private fun completionResultLocked(execution: Execution): ChrootCleanupResult? =
        if (execution.finished || !execution.stdoutEnded || !execution.helperErrorsEnded || !execution.wrapperExited) null
        else if (!execution.wrapperStatusVerified) ChrootCleanupResult.UNKNOWN
        else execution.cleanupEvidence ?: ChrootCleanupResult.UNKNOWN

    private fun tryFinish(execution: Execution) {
        val result = authority.withLock {
            completionResultLocked(execution)?.takeIf { claimFinalLocked(execution, it) }
        } ?: return
        publishFinal(execution, result)
    }

    private fun finishUnstarted(execution: Execution) {
        if (execution.process != null || execution.birthAttempted) {
            failChannel(execution)
            return
        }
        completeFinal(execution, ChrootCleanupResult.VERIFIED)
    }

    private fun claimFinalLocked(execution: Execution, proposed: ChrootCleanupResult): Boolean {
        if (execution.finished) return false
        execution.finished = true
        markStopOnlyLocked(execution)
        if (proposed == ChrootCleanupResult.UNKNOWN) {
            uncertainInstances.add(execution.spec.instanceId)
            localBlocked.value = uncertainInstances.toSet()
        }
        return true
    }

    private fun completeFinal(execution: Execution, proposed: ChrootCleanupResult) {
        if (authority.withLock { claimFinalLocked(execution, proposed) }) publishFinal(execution, proposed)
    }

    private fun publishFinal(execution: Execution, proposed: ChrootCleanupResult) {
        scope.launch(dispatcher) {
            var result = proposed
            try {
                if (execution.journaled) recovery.recordCleanup(execution.spec.launchId, result)
            } catch (_: Exception) {
                recordFailure(execution, ChrootRuntimeFailureKind.CLEANUP, ChrootRuntimeFailureStage.CLEANUP_JOURNAL)
                result = ChrootCleanupResult.UNKNOWN
            }
            authority.withLock {
                if (result == ChrootCleanupResult.UNKNOWN) {
                    uncertainInstances.add(execution.spec.instanceId)
                    localBlocked.value = uncertainInstances.toSet()
                }
                if (active[execution.spec.launchId] === execution) active.remove(execution.spec.launchId)
                changedLocked()
            }
            runCatching { execution.control?.close() }
            execution.handle.completeTask(ChrootTaskResult.Unknown)
            execution.handle.completeCleanup(result)
            reportDiagnostic(execution, result)
            val process = execution.process
            if (result == ChrootCleanupResult.VERIFIED && process != null && !process.isAlive) {
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
            } else if (result == ChrootCleanupResult.UNKNOWN && process != null) {
                scope.launch(dispatcher) { runCatching { process.outputStream.close() } }
                scope.launch(dispatcher) { runCatching { process.inputStream.close() } }
                scope.launch(dispatcher) { runCatching { process.errorStream.close() } }
            }
        }
    }

    private fun sourceValid(check: () -> Unit): Boolean = try {
        check()
        true
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
        val NONCE = Regex("[0-9a-f]{24}")
    }
}
