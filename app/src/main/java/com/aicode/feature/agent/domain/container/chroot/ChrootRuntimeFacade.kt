package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import com.aicode.R
import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeRemoval
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeState
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeUserActions
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.chroot.ChrootFileSession
import com.aicode.feature.workspace.domain.chroot.ChrootFileWorkerClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ChrootRuntimeFacade internal constructor(
    private val authority: RootAuthorityCoordinator,
    private val manager: ChrootRuntimeManager,
    private val recovery: ChrootRecoveryRepository,
    private val installer: ChrootInstanceInstaller,
    private val settings: ContainerSettingsRepository,
    private val sessionFactory: ChrootRuntimeProcessFactory,
    private val captureSources: (ContainerProfile, String) -> ChrootAppSources,
    private val cacheDirectory: File,
    private val scope: CoroutineScope,
    private val legacyInstanceMessage: () -> String = { "This instance uses the legacy read-only format; create a new CHROOT for writable Root without migrating the old instance." }
) : LocalRuntimeCommands, LocalRuntimeUserActions {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authority: RootAuthorityCoordinator,
        manager: ChrootRuntimeManager,
        recovery: ChrootRecoveryRepository,
        installer: ChrootInstanceInstaller,
        settings: ContainerSettingsRepository,
        sessionFactory: ChrootRuntimeProcessFactory,
        sources: ChrootAppSourceCapture
    ) : this(authority, manager, recovery, installer, settings, sessionFactory, sources::capture,
        File(context.cacheDir, "chroot-files"), CoroutineScope(SupervisorJob() + Dispatchers.IO),
        { context.getString(R.string.container_chroot_legacy_instance) })

    private class Request(
        val facade: Any,
        val sources: ChrootAppSources,
        val owner: RuntimeOwner,
        val revision: Long,
        val fingerprint: String,
        val root: RootAccessState,
        val checkSource: () -> Unit,
        val digest: String
    )

    private class Binding(val request: Request, val leaseId: String, val session: ChrootRuntimeSession, val sourceCheck: () -> Unit) {
        val valid = AtomicBoolean(true)
    }

    // All issuance/flight mutations use authority.withLock, also used by synchronous revocations.
    // Identity epochs do not wrap; per-call tickets avoid retaining every disconnected owner forever.
    private class Issuance(val root: RootAccessState, val owner: RuntimeOwner, val instanceId: String, val epoch: Any) {
        var valid = true
    }

    private class Flight(val issuance: Issuance) {
        val result = CompletableDeferred<Binding>()
    }

    private class Acquisition(val binding: Binding? = null, val flight: Flight? = null, val leader: Boolean = false)

    private val identity = Any()
    private var issuanceEpoch = Any()
    private val issuances = mutableSetOf<Issuance>()
    private val flights = mutableMapOf<String, Flight>()
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val busyInstances = ConcurrentHashMap.newKeySet<String>()
    private val busyProfiles = ConcurrentHashMap.newKeySet<String>()
    private val journalRepairBusy = AtomicBoolean()
    private val _changes = MutableStateFlow(0L)
    override val changes = _changes.asStateFlow()
    private val pending = MutableStateFlow<List<LocalRuntimeAuthorization>>(emptyList())
    override val pendingAuthorizations = pending.asStateFlow()

    init {
        authority.registerRevocation(onRevoke = ::revokeAllBindings)
        sessionFactory.registerSessionRevocation(::revokeAllBindings)
        settings.registerConfigurationRevocation(::revokeAllBindings)
        scope.launch {
            combine(
                settings.profileConfigurationRevisions, manager.stateRevision, authority.accessState,
                recovery.isHealthy, recovery.blockedInstances
            ) { _, _, _, _, _ -> Unit }.collect {
                invalidateStaleBindings()
                changed()
            }
        }
        scope.launch {
            combine(installer.failedInstances, installer.isHealthy, installer.unpublishedInstalls,
                installer.removalRebootRequired, recovery.canRepairInterruptedWrite) { _, _, _, _, _ -> Unit }.collect { changed() }
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                invalidateStaleBindings()
            }
        }
    }

    override suspend fun capture(
        profile: ContainerProfile,
        configurationRevision: Long,
        workspacePath: String,
        owner: RuntimeOwner,
        sourceFingerprint: String,
        checkSource: () -> Unit
    ): LocalRuntimeCapture = withContext(Dispatchers.IO) {
        val instance = profile.chrootInstance ?: throw IOException("CHROOT instance has not been created")
        val issuance = authority.withLock { root ->
            if (!root.enabled) throw ChrootRuntimeUnavailable("CHROOT requires current enabled Root authority")
            ensureAvailable(instance)
            Issuance(root, owner, instance.id, issuanceEpoch).also { issuances.add(it) }
        }
        try {
            authority.accessSnapshot()
            authority.withLock { checkIssuance(issuance) }
            val request = freeze(profile, configurationRevision, workspacePath, owner, sourceFingerprint, issuance.root, checkSource)
            val acquired = authority.withLock {
                checkIssuance(issuance)
                checkSource(request)
                ensureAvailable(instance)
                val cached = bindings[request.digest]
                if (cached != null && bindingCurrent(cached)) {
                    Acquisition(binding = cached)
                } else {
                    if (cached != null) revokeBinding(cached)
                    val flight = flights[request.digest]
                    if (flight != null) Acquisition(flight = flight)
                    else Flight(issuance).let { next ->
                        flights[request.digest] = next
                        Acquisition(flight = next, leader = true)
                    }
                }
            }
            val binding = acquired.binding ?: run {
                val flight = checkNotNull(acquired.flight)
                if (acquired.leader) {
                    try {
                        flight.result.complete(issueBinding(request, issuance))
                    } catch (failure: Throwable) {
                        flight.result.completeExceptionally(failure)
                    } finally {
                        authority.withLock { if (flights[request.digest] === flight) flights.remove(request.digest) }
                    }
                }
                flight.result.await()
            }
            currentCoroutineContext().ensureActive()
            authority.withLock {
                checkIssuance(issuance)
                checkSource(request)
                checkBinding(binding)
                capture(binding, checkSource)
            }
        } finally {
            authority.withLock { issuances.remove(issuance) }
        }
    }

    // Legacy API shape is retained for callers, but old/forged UI tokens never mint policy bindings.
    override suspend fun prepareAuthorization(request: LocalRuntimeAuthorization): LocalRuntimeAuthorization =
        throw IOException("CHROOT owner confirmation is obsolete; capture the current execution source")

    override suspend fun authorizeFromUser(authorization: LocalRuntimeAuthorization): LocalRuntimeCapture =
        throw IOException("CHROOT owner confirmation is obsolete; capture the current execution source")

    private suspend fun issueBinding(request: Request, issuance: Issuance): Binding {
        recheckRequest(request, issuance)
        val session = sessionFactory.currentSession.value?.also(sessionFactory::checkSession)
            ?: sessionFactory.ensureSession(request.root)
        recheckRequest(request, issuance)
        sessionFactory.checkSession(session)
        val instance = checkNotNull(request.sources.profile.chrootInstance)
        val lease = manager.allowBinding(request.owner.identity, request.sources.profile.id, request.revision,
            instance.id, ChrootFileIdentity(instance.device, instance.inode), request.root) {
            checkIssuance(issuance)
            checkSource(request)
            sessionFactory.checkSession(session)
            ensureAvailable(instance)
        } ?: throw IOException("CHROOT runtime binding is unavailable")
        val binding = Binding(request, lease, session, request.checkSource)
        try {
            recheckRequest(request, issuance)
            currentCoroutineContext().ensureActive()
            return authority.withLock {
                checkIssuance(issuance)
                checkSource(request)
                sessionFactory.checkSession(session)
                ensureAvailable(instance)
                check(bindings.putIfAbsent(request.digest, binding) == null) { "Duplicate CHROOT binding issuance" }
                checkBinding(binding)
                changed()
                binding
            }
        } catch (failure: Throwable) {
            revokeBinding(binding)
            throw failure
        }
    }

    private fun recheckRequest(request: Request, issuance: Issuance) {
        authority.withLock { checkIssuance(issuance); checkSource(request); ensureAvailable(checkNotNull(request.sources.profile.chrootInstance)) }
        val fresh = freeze(request.sources.profile, request.revision, request.sources.workspacePath,
            request.owner, request.fingerprint, request.root, request.checkSource)
        if (fresh.digest != request.digest) throw CancellationException("CHROOT execution source changed during binding")
        authority.withLock { checkIssuance(issuance); checkSource(request); ensureAvailable(checkNotNull(request.sources.profile.chrootInstance)) }
    }

    private fun checkIssuance(issuance: Issuance) {
        if (!issuance.valid || issuance.epoch !== issuanceEpoch || issuance !in issuances ||
            issuance.root != authority.accessState.value || !issuance.root.enabled) {
            throw CancellationException("CHROOT binding issuance was revoked")
        }
    }

    private fun invalidateIssuances(predicate: (Issuance) -> Boolean) {
        val tickets = issuances.filter(predicate)
        tickets.forEach { it.valid = false }
        issuances.removeAll(tickets.toSet())
        val revoked = flights.filterValues { predicate(it.issuance) }
        revoked.forEach { (digest, flight) ->
            flights.remove(digest)
            flight.result.completeExceptionally(CancellationException("CHROOT binding issuance was revoked"))
        }
    }

    private fun revokeAllBindings() = authority.withLock {
        issuanceEpoch = Any()
        invalidateIssuances { true }
        bindings.values.toList().forEach(::revokeBinding)
        changed()
    }

    override fun state(profile: ContainerProfile): LocalRuntimeState {
        val instance = profile.chrootInstance
        val active = instance?.let { manager.instanceState(it.id) }
        val healthy = recovery.isHealthy.value && installer.isHealthy.value
        val blocked = active?.blocked == true || instance?.id?.let { it in installer.failedInstances.value } == true ||
            (instance == null && installer.unpublishedInstalls.value.isNotEmpty())
        val owners = if (instance == null) 0 else bindings.values.filter {
            it.request.sources.profile.chrootInstance == instance && bindingCurrent(it)
        }.map { it.request.owner }.distinct().size
        return LocalRuntimeState(instance, authority.accessState.value.enabled, healthy, blocked,
            active?.activeLaunches ?: 0, owners, journalRepairBusy.get() || profile.id in busyProfiles || (instance != null && instance.id in busyInstances),
            journalRepairAvailable = recovery.canRepairInterruptedWrite.value && installer.isHealthy.value,
            removalRebootRequired = instance?.id?.let { it in installer.removalRebootRequired.value } == true)
    }

    override suspend fun createInstanceFromUser(profile: ContainerProfile, expectedRoot: RootAccessState): ChrootInstance {
        if (!profile.isChroot || profile.chrootInstance != null || profile.isBuiltin) throw IOException("A fresh CHROOT profile is required")
        if (!busyProfiles.add(profile.id)) throw IOException("CHROOT profile operation is already in progress")
        changed()
        try {
            if (journalRepairBusy.get() || !recovery.isHealthy.value || !installer.isHealthy.value || installer.unpublishedInstalls.value.isNotEmpty()) {
                throw IOException("CHROOT recovery is required")
            }
            val snapshot = settings.captureProfileSnapshot()
            if (snapshot.profiles.singleOrNull { it.id == profile.id } != profile) {
                throw CancellationException("CHROOT installation profile changed")
            }
            val session = sessionFactory.ensureFromUser(expectedRoot)
            settings.checkProfileSnapshot(snapshot)
            sessionFactory.checkSession(session)
            return installer.installFromUser(profile, expectedRoot, expectedSession = session) { instance ->
                sessionFactory.checkSession(session)
                settings.publishChrootInstance(snapshot, profile, instance) {
                    sessionFactory.checkSession(session)
                    if (authority.accessState.value != expectedRoot) throw CancellationException("Root authority changed before installation publication")
                }
            }
        } finally {
            busyProfiles.remove(profile.id)
            changed()
        }
    }

    override suspend fun stopInstance(instance: ChrootInstance): LocalRuntimeCleanup {
        if (!busyInstances.add(instance.id)) throw IOException("CHROOT instance operation is already in progress")
        changed()
        return try { stopAndVerify(instance) } finally { busyInstances.remove(instance.id); changed() }
    }

    override suspend fun removeInstanceFromUser(profile: ContainerProfile, expectedRoot: RootAccessState) {
        val instance = profile.chrootInstance
        if (instance == null) {
            removeUninstalledProfile(profile, expectedRoot)
            return
        }
        if (!profile.isChroot || !busyInstances.add(instance.id)) throw IOException("CHROOT instance operation is unavailable")
        changed()
        try {
            val snapshot = settings.captureProfileSnapshot()
            if (snapshot.profiles.singleOrNull { it.id == profile.id } != profile) {
                throw CancellationException("CHROOT removal profile changed")
            }
            val session = sessionFactory.ensureFromUser(expectedRoot)
            settings.checkProfileSnapshot(snapshot)
            sessionFactory.checkSession(session)
            if (stopAndVerify(instance) != LocalRuntimeCleanup.VERIFIED) throw IOException("CHROOT cleanup is not verified")
            ensureAvailable(instance, allowBusy = true)
            if (manager.instanceState(instance.id).activeLaunches != 0) throw IOException("CHROOT instance is still active")
            settings.checkProfileSnapshot(snapshot)
            sessionFactory.checkSession(session)
            installer.removeFromUser(instance, expectedRoot, expectedSession = session) {
                sessionFactory.checkSession(session)
                settings.publishChrootInstance(snapshot, profile, null) {
                    sessionFactory.checkSession(session)
                    if (authority.accessState.value != expectedRoot) throw CancellationException("Root authority changed before removal publication")
                }
            }
        } finally {
            busyInstances.remove(instance.id)
            changed()
        }
    }

    private suspend fun removeUninstalledProfile(profile: ContainerProfile, expectedRoot: RootAccessState) {
        require(profile.isChroot && !profile.isBuiltin && profile.chrootInstance == null)
        if (!busyProfiles.add(profile.id)) throw IOException("CHROOT profile operation is already in progress")
        changed()
        try {
            val snapshot = settings.captureProfileSnapshot()
            if (snapshot.profiles.singleOrNull { it.id == profile.id } != profile) {
                throw CancellationException("CHROOT removal profile changed")
            }
            authority.accessSnapshot()
            fun checkCurrent() {
                if (authority.accessState.value != expectedRoot || !expectedRoot.enabled) {
                    throw CancellationException("Root authority changed before configuration removal")
                }
                if (journalRepairBusy.get() || !recovery.isHealthy.value || !installer.isHealthy.value ||
                    installer.unpublishedInstalls.value.isNotEmpty()) throw IOException("CHROOT recovery is required")
                settings.checkProfileSnapshot(snapshot)
            }
            checkCurrent()
            settings.removeUninstalledChrootProfile(snapshot, profile, ::checkCurrent)
        } finally {
            busyProfiles.remove(profile.id)
            changed()
        }
    }

    override suspend fun repairJournalFromUser(profile: ContainerProfile, expectedRoot: RootAccessState) = withContext(Dispatchers.IO) {
        require(profile.isChroot && !profile.isBuiltin)
        if (!journalRepairBusy.compareAndSet(false, true)) throw IOException("Runtime journal repair is already in progress")
        changed()
        try {
            val snapshot = settings.captureProfileSnapshot()
            if (snapshot.profiles.singleOrNull { it.id == profile.id } != profile) {
                throw CancellationException("CHROOT repair profile changed")
            }
            if (!installer.isHealthy.value || !recovery.canRepairInterruptedWrite.value) {
                throw IOException("Runtime journal cannot be repaired from its committed snapshot")
            }
            val session = sessionFactory.ensureFromUser(expectedRoot)
            revokeAllBindings()
            manager.withJournalMaintenance {
                settings.checkProfileSnapshot(snapshot)
                sessionFactory.checkSession(session)
                if (authority.accessState.value != expectedRoot || !expectedRoot.enabled) {
                    throw CancellationException("Root authority changed before journal repair")
                }
                recovery.repairInterruptedWriteFromUser()
                sessionFactory.checkSession(session)
                settings.checkProfileSnapshot(snapshot)
            }
        } finally {
            journalRepairBusy.set(false)
            changed()
        }
    }

    override suspend fun recoverAndRemoveInstanceFromUser(
        profile: ContainerProfile,
        expectedRoot: RootAccessState
    ): LocalRuntimeRemoval = withContext(Dispatchers.IO) {
        val instance = profile.chrootInstance ?: throw IOException("CHROOT instance is missing")
        require(profile.isChroot && !profile.isBuiltin)
        if (recovery.canRepairInterruptedWrite.value) repairJournalFromUser(profile, expectedRoot)
        if (!busyInstances.add(instance.id)) throw IOException("CHROOT instance operation is already in progress")
        changed()
        try {
            if (journalRepairBusy.get() || !recovery.isHealthy.value || !installer.isHealthy.value) {
                throw IOException("CHROOT journal requires repair before recovery removal")
            }
            val snapshot = settings.captureProfileSnapshot()
            if (snapshot.profiles.singleOrNull { it.id == profile.id } != profile) {
                throw CancellationException("CHROOT recovery removal profile changed")
            }
            val session = sessionFactory.ensureFromUser(expectedRoot)
            // UNKNOWN stays recorded. Only the native reboot receipt may authorize this deletion.
            authority.withLock {
                invalidateIssuances { it.instanceId == instance.id }
                bindings.values.toList().filter { it.request.sources.profile.chrootInstance?.id == instance.id }.forEach(::revokeBinding)
            }
            withTimeoutOrNull(ChrootRuntimeTimeouts.CLEANUP_AWAIT_TIMEOUT_MS) { manager.stopAndAwaitInstance(instance.id) }
                ?: throw IOException("CHROOT launches have not settled for recovery removal")
            if (manager.instanceState(instance.id).activeLaunches != 0) throw IOException("CHROOT instance is still active")
            settings.checkProfileSnapshot(snapshot)
            sessionFactory.checkSession(session)
            installer.recoverAndRemoveFromUser(instance, expectedRoot, expectedSession = session, checkCurrent = {
                settings.checkProfileSnapshot(snapshot)
                sessionFactory.checkSession(session)
                if (authority.accessState.value != expectedRoot) throw CancellationException("Root authority changed before recovery removal")
            }) {
                sessionFactory.checkSession(session)
                settings.publishChrootInstance(snapshot, profile, null) {
                    sessionFactory.checkSession(session)
                    if (authority.accessState.value != expectedRoot) throw CancellationException("Root authority changed before removal publication")
                }
            }
        } finally {
            busyInstances.remove(instance.id)
            changed()
        }
    }

    private suspend fun stopAndVerify(instance: ChrootInstance): LocalRuntimeCleanup = withContext(NonCancellable) {
        authority.withLock {
            invalidateIssuances { it.instanceId == instance.id }
            bindings.values.toList().filter { it.request.sources.profile.chrootInstance?.id == instance.id }.forEach(::revokeBinding)
        }
        val results = manager.stopAndAwaitInstance(instance.id)
        changed()
        if (results.all { it == ChrootCleanupResult.VERIFIED } && recovery.isHealthy.value && installer.isHealthy.value &&
            !manager.instanceState(instance.id).blocked && instance.id !in recovery.blockedInstances.value &&
            instance.id !in installer.failedInstances.value && manager.instanceState(instance.id).activeLaunches == 0
        ) LocalRuntimeCleanup.VERIFIED else LocalRuntimeCleanup.UNKNOWN
    }

    override fun revokeOwner(owner: RuntimeOwner) = authority.withLock {
        invalidateIssuances { it.owner == owner }
        bindings.values.toList().filter { it.request.owner == owner }.forEach(::revokeBinding)
        manager.revokeOwner(owner.identity)
        changed()
    }

    override suspend fun startStdio(
        capture: LocalRuntimeCapture,
        program: String,
        args: List<String>,
        cwd: String?,
        extraEnv: Map<String, String>,
        timeoutMs: Long
    ): LocalRuntimeProcess = start(capture, listOf(program) + args, cwd, extraEnv, timeoutMs, ChrootIoMode.STDIO)

    override suspend fun startPty(
        capture: LocalRuntimeCapture,
        argv: List<String>,
        cwd: String?,
        extraEnv: Map<String, String>,
        columns: Int,
        rows: Int
    ): LocalRuntimeProcess {
        require(columns in 1..4096 && rows in 1..4096)
        val process = start(capture, argv, cwd, extraEnv, 0, ChrootIoMode.PTY)
        if (!process.resize(columns, rows) && process.isAlive) {
            process.stop()
            withContext(NonCancellable) { process.awaitCleanup() }
            throw IOException("CHROOT terminal size could not be applied")
        }
        return process
    }

    override suspend fun startFileWorker(capture: LocalRuntimeCapture): LocalRuntimeProcess =
        start(capture, listOf("/run/ajiu-file-worker"), "/root/workspace", emptyMap(), 0, ChrootIoMode.STDIO)

    private suspend fun start(
        capture: LocalRuntimeCapture,
        argv: List<String>,
        cwd: String?,
        extraEnv: Map<String, String>,
        timeoutMs: Long,
        mode: ChrootIoMode
    ): LocalRuntimeProcess = withContext(Dispatchers.IO) {
        capture.checkCurrent()
        val binding = capture.binding as? Binding ?: throw IOException("CHROOT capture is invalid")
        checkBinding(binding)
        val request = binding.request
        val fresh = freeze(request.sources.profile, request.revision, request.sources.workspacePath, request.owner,
            request.fingerprint, request.root, binding.sourceCheck)
        if (fresh.digest != request.digest) {
            revokeBinding(binding)
            throw CancellationException("CHROOT mounted source identity changed")
        }
        val instance = capture.instance
        val launch = ChrootLaunch.freeze(
            instance.id, nonce(), request.owner.identity, ByteArray(32).also(random::nextBytes),
            ChrootFileIdentity(instance.device, instance.inode), request.sources.appIdentity, mode, 15_000,
            if (timeoutMs == 0L) 0 else timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS).toInt(),
            cwd(capture, cwd), argv, environment(request.sources.profile, extraEnv), request.sources.mounts
        )
        val permit = manager.authorize(binding.leaseId, launch, request.revision)
            ?: throw IOException("CHROOT runtime launch authorization is unavailable")
        val handle = manager.launch(binding.leaseId, permit, launch) { capture.checkCurrent() }
            ?: throw IOException("CHROOT runtime launch was denied")
        val process = ChrootPublicProcess(handle, scope)
        bindCancellation(process)
        try {
            if (!handle.awaitReady()) {
                process.stop()
                // A structured stderr failure can arrive just after STOP marks READY false.
                withContext(NonCancellable) { handle.awaitCleanup() }
                if (handle.nativeFailure.value?.nativeStage == "legacy_readonly_instance") throw IOException(legacyInstanceMessage())
                val detail = ChrootRuntimeDiagnostic(handle.instanceId, handle.launchId, handle.failure.value,
                    handle.nativeFailure.value, handle.cleanupState.value).summary()
                throw IOException("CHROOT runtime could not start the requested task ($detail)")
            }
            capture.checkCurrent()
            process
        } catch (failure: Throwable) {
            process.stop()
            withContext(NonCancellable) { handle.awaitCleanup() }
            throw failure
        }
    }

    override suspend fun exec(
        capture: LocalRuntimeCapture,
        command: String,
        cwd: String?,
        timeoutMs: Long,
        unbounded: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        val process = startShell(capture, command, cwd, effectiveTimeout)
        val timedOut = AtomicBoolean()
        val watchdog = scope.launch { delay(effectiveTimeout); if (process.isAlive) { timedOut.set(true); process.stop() } }
        val cancellation = cancellationHook(process)
        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) { readOutput(process.inputStream, output) }
                val stderr = async(Dispatchers.IO) { readOutput(process.errorStream, output) }
                stdout.await()
                stderr.await()
            }
            val task = process.awaitTaskExit()
            val cleanup = process.awaitCleanup()
            capture.checkCurrent()
            val code = if (task is LocalRuntimeTaskResult.Exited && cleanup == LocalRuntimeCleanup.VERIFIED && !timedOut.get()) {
                task.code ?: 128 + checkNotNull(task.signal)
            } else null
            CommandResult(output.build(), code, output.truncated)
        } finally {
            cancellation?.dispose()
            watchdog.cancel()
            process.stop()
            withContext(NonCancellable) { process.awaitCleanup() }
        }
    }

    override fun execStream(
        capture: LocalRuntimeCapture,
        command: String,
        cwd: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = channelFlow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, CommandEngine.MAX_TIMEOUT_MS)
        val process = startShell(capture, command, cwd, effectiveTimeout)
        val timedOut = AtomicBoolean()
        val watchdog = scope.launch { delay(effectiveTimeout); if (process.isAlive) { timedOut.set(true); process.stop() } }
        val cancellation = cancellationHook(process)
        suspend fun forward(input: java.io.InputStream) {
            BoundedLineReader(InputStreamReader(input)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    capture.checkCurrent()
                    send(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
                }
            }
        }
        try {
            val stdout = launch(Dispatchers.IO) { forward(process.inputStream) }
            val stderr = launch(Dispatchers.IO) { forward(process.errorStream) }
            stdout.join()
            stderr.join()
            val task = process.awaitTaskExit()
            val cleanup = process.awaitCleanup()
            capture.checkCurrent()
            send(CommandEvent.Exit(if (task is LocalRuntimeTaskResult.Exited && cleanup == LocalRuntimeCleanup.VERIFIED && !timedOut.get()) {
                task.code ?: 128 + checkNotNull(task.signal)
            } else null))
        } finally {
            cancellation?.dispose()
            watchdog.cancel()
            process.stop()
            withContext(NonCancellable) { process.awaitCleanup() }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun startShell(capture: LocalRuntimeCapture, command: String, cwd: String?, timeoutMs: Long): LocalRuntimeProcess {
        val binding = capture.binding as? Binding ?: throw IOException("CHROOT capture is invalid")
        val shell = binding.request.sources.profile.shellPath?.takeIf(String::isNotBlank) ?: "/bin/sh"
        val process = start(capture, listOf(shell, "-c", command), cwd, emptyMap(), timeoutMs, ChrootIoMode.STDIO)
        try {
            process.outputStream.close()
            return process
        } catch (failure: Throwable) {
            process.stop()
            withContext(NonCancellable) { process.awaitCleanup() }
            throw failure
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun bindCancellation(process: LocalRuntimeProcess) {
        val owner = WorkspaceExecutionContext.blockingContext()[Job] ?: currentCoroutineContext()[Job]
        val cancellation = owner?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it is CancellationException) process.stop()
        }
        if (cancellation != null) scope.launch {
            try { process.awaitCleanup() } finally { cancellation.dispose() }
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun cancellationHook(process: LocalRuntimeProcess) = currentCoroutineContext()[Job]
        ?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) process.stop() }

    private fun readOutput(input: java.io.InputStream, output: BoundedOutput) {
        BoundedLineReader(InputStreamReader(input)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                synchronized(output) {
                    output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                    output.append("\n")
                }
            }
        }
    }

    override fun fileAccess(capture: LocalRuntimeCapture): FileAccessProvider = ChrootFileAccess(
        captureIdentity = { capture.checkCurrent(); capture.digest },
        sessionFactory = {
            capture.checkCurrent()
            val process = runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) { startFileWorker(capture) }
            val errorDrain = scope.async(Dispatchers.IO) {
                var size = 0
                val buffer = ByteArray(4096)
                while (true) {
                    val count = process.errorStream.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > 64 * 1024) { process.stop(); throw IOException("CHROOT file worker reported an error") }
                }
                size
            }
            val closed = AtomicBoolean()
            fun closeWorker() {
                if (!closed.compareAndSet(false, true)) return
                runBlocking(NonCancellable + Dispatchers.IO) {
                    try {
                        runCatching { process.outputStream.close() }
                        val task = withTimeoutOrNull(5_000L) { process.awaitTaskExit() }
                        if (task == null) process.stop()
                        val cleanup = withTimeoutOrNull(ChrootRuntimeTimeouts.CLEANUP_AWAIT_TIMEOUT_MS) { process.awaitCleanup() } ?: LocalRuntimeCleanup.UNKNOWN
                        val errors = withTimeoutOrNull(1_000L) { errorDrain.await() } ?: -1
                        if (cleanup != LocalRuntimeCleanup.VERIFIED || task !is LocalRuntimeTaskResult.Exited ||
                            task.code != 0 || errors != 0) throw IOException("CHROOT file worker cleanup is not verified")
                    } finally { process.stop() }
                }
            }
            val client = ChrootFileWorkerClient(process.inputStream, process.outputStream, capture::checkCurrent, ::closeWorker)
            val binding = capture.binding as? Binding ?: throw IOException("CHROOT capture is invalid")
            ChrootFileSession(capture.digest, client, cacheDirectory, capture::checkCurrent,
                writablePaths = listOf("/"),
                readOnlyPaths = binding.request.sources.mounts.filter { it.readOnly }.map { it.target },
                closeOwner = ::closeWorker)
        },
        mapPath = { path -> mapPath(capture, path) }
    )

    private fun freeze(
        profile: ContainerProfile,
        revision: Long,
        workspacePath: String,
        owner: RuntimeOwner,
        fingerprint: String,
        root: RootAccessState,
        sourceCheck: () -> Unit
    ): Request {
        require(revision >= 0 && fingerprint.isNotBlank() && fingerprint.toByteArray(Charsets.UTF_8).size <= 4096)
        sourceCheck()
        if (settings.profileConfigurationRevision != revision) throw CancellationException("CHROOT profile configuration changed")
        val captured = captureSources(profile, workspacePath)
        val appSources = captured.copy(
            profile = captured.profile.copy(
                env = Collections.unmodifiableMap(captured.profile.env.toMap()),
                extraBindings = Collections.unmodifiableList(captured.profile.extraBindings.toList()),
                extraArgs = Collections.unmodifiableList(captured.profile.extraArgs.toList())
            ),
            mounts = Collections.unmodifiableList(captured.mounts.toList())
        )
        sourceCheck()
        val initial = Request(identity, appSources, owner, revision, fingerprint, root, sourceCheck, "")
        return Request(identity, appSources, owner, revision, fingerprint, root, sourceCheck, digest(initial))
    }

    private fun digest(request: Request): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
            text(GUEST_POLICY)
            text(request.owner.identity)
            text(request.sources.profile.id)
            output.writeLong(request.revision)
            text(request.fingerprint)
            val instance = checkNotNull(request.sources.profile.chrootInstance)
            text(instance.id); output.writeLong(instance.device); output.writeLong(instance.inode); output.writeLong(instance.installGeneration)
            text(request.root.authorityId); output.writeLong(request.root.generation); output.writeBoolean(request.root.enabled)
            text(request.sources.profile.shellPath.orEmpty())
            text(request.sources.workspacePath); text(request.sources.homePath); text(request.sources.dns)
            output.writeInt(request.sources.appIdentity.uid); output.writeInt(request.sources.appIdentity.gid)
            request.sources.appIdentity.groups.forEach(output::writeInt)
            output.writeInt(-1)
            request.sources.profile.env.toSortedMap().forEach { (key, value) -> text(key); text(value) }
            output.writeInt(-1)
            request.sources.mounts.sortedBy { it.target }.forEach {
                text(it.source); text(it.target); output.writeBoolean(it.readOnly); output.writeInt(it.kind.ordinal)
                output.writeLong(it.identity.device); output.writeLong(it.identity.inode)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun checkSource(request: Request) {
        if (request.facade !== identity || settings.profileConfigurationRevision != request.revision) {
            throw CancellationException("CHROOT profile configuration changed")
        }
        request.checkSource()
        if (request.root != authority.accessState.value || !request.root.enabled) {
            throw CancellationException("CHROOT Root authority changed")
        }
    }

    private fun checkBinding(binding: Binding) = authority.withLock {
        if (!binding.valid.get() || bindings[binding.request.digest] !== binding || !manager.isLeaseCurrent(binding.leaseId)) {
            throw CancellationException("CHROOT execution authorization was revoked")
        }
        checkSource(binding.request)
        sessionFactory.checkSession(binding.session)
        binding.sourceCheck()
        ensureAvailable(checkNotNull(binding.request.sources.profile.chrootInstance))
    }

    private fun bindingCurrent(binding: Binding): Boolean = runCatching { checkBinding(binding) }.isSuccess

    private fun capture(binding: Binding, sourceCheck: () -> Unit): LocalRuntimeCapture {
        val request = binding.request
        return LocalRuntimeCapture(request.owner, request.sources.profile.id, request.revision,
            checkNotNull(request.sources.profile.chrootInstance), request.sources.workspacePath, request.sources.homePath,
            request.digest, binding, request.sources.profile) { sourceCheck(); checkBinding(binding) }
    }

    private fun invalidateStaleBindings() = authority.withLock {
        bindings.values.toList().filterNot(::bindingCurrent).forEach(::revokeBinding)
    }

    private fun revokeBinding(binding: Binding) = authority.withLock {
        binding.valid.set(false)
        bindings.remove(binding.request.digest, binding)
        manager.revokeLease(binding.leaseId)
        changed()
    }

    private fun ensureAvailable(instance: ChrootInstance, allowBusy: Boolean = false) {
        if (journalRepairBusy.get()) throw IOException("CHROOT runtime records are being repaired")
        if (!recovery.isHealthy.value) {
            throw IOException("CHROOT runtime journal is incomplete; open Containers & Images to repair runtime records or remove the affected instance")
        }
        if (!installer.isHealthy.value || instance.id in recovery.blockedInstances.value ||
            manager.instanceState(instance.id).blocked || instance.id in installer.failedInstances.value ||
            !allowBusy && instance.id in busyInstances) {
            val detail = manager.failureSummary(instance.id)?.let { " ($it)" }.orEmpty()
            throw IOException("CHROOT instance requires verified cleanup or recovery$detail")
        }
    }

    private fun changed() = _changes.update { if (it == Long.MAX_VALUE) 0 else it + 1L }

    private fun mapPath(capture: LocalRuntimeCapture, path: String): String {
        val binding = capture.binding as? Binding ?: throw IOException("CHROOT capture is invalid")
        val mount = binding.request.sources.mounts.filter { path == it.source || path.startsWith(it.source + "/") }
            .maxByOrNull { it.source.length } ?: return path
        return mount.target + path.removePrefix(mount.source)
    }

    private fun cwd(capture: LocalRuntimeCapture, path: String?): String =
        if (path.isNullOrBlank()) "/root/workspace" else mapPath(capture, path)

    private fun environment(profile: ContainerProfile, extraEnv: Map<String, String>): List<String> =
        (mapOf("HOME" to "/root", "PATH" to "/root/.aicode/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
            "TMPDIR" to "/tmp", "LANG" to "C.UTF-8", "TERM" to "xterm-256color") + profile.env + extraEnv)
            .toSortedMap().map { "${it.key}=${it.value}" }

    private companion object {
        const val GUEST_POLICY = "root-guest-rw-v1"
        val random = SecureRandom()
        fun nonce(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
