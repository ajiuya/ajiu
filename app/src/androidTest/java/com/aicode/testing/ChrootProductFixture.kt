package com.aicode.testing

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.FileObserver
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.aicode.testing.ChrootProductTrace.Kind
import com.aicode.testing.ChrootProductTrace.Phase
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.chroot.ChrootAppSourceCapture
import com.aicode.feature.agent.domain.container.chroot.ChrootAppSources
import com.aicode.feature.agent.domain.container.chroot.ChrootArchiveWriter
import com.aicode.feature.agent.domain.container.chroot.ChrootCleanupResult
import com.aicode.feature.agent.domain.container.chroot.ChrootControlChannel
import com.aicode.feature.agent.domain.container.chroot.ChrootHelperIdentity
import com.aicode.feature.agent.domain.container.chroot.ChrootInstanceInstaller
import com.aicode.feature.agent.domain.container.chroot.ChrootProcessFactory
import com.aicode.feature.agent.domain.container.chroot.ChrootRecoveryPhase
import com.aicode.feature.agent.domain.container.chroot.ChrootRecoveryRepository
import com.aicode.feature.agent.domain.container.chroot.ChrootRootBroker
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFacade
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeManager
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeProcessFactory
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeSession
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeUnavailable
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class ChrootProductFixture(
    val context: Context,
    val foreground: ChrootVerificationForeground,
    val trace: ChrootProductTrace
) {
    val runId = nonce()
    val journalDirectory = File(context.noBackupFilesDir, "chroot/product-verification/$runId")
    val evidence = ChrootProductEvidence(runId, journalDirectory, trace)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rootFile = File(context.noBackupFilesDir, "root_access.preferences_pb")
    private val rootDataStore = PreferenceDataStoreFactory.create(scope = rootScope) { rootFile }
    private val readOnlyRootDataStore = object : DataStore<Preferences> {
        override val data = rootDataStore.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("read_only_root_settings")
    }
    val rootSettings = RootAccessSettingsRepository(readOnlyRootDataStore, rootScope) {
        throw IOException("existing_root_identity_required")
    }
    val authority = RootAuthorityCoordinator(rootSettings, scope)
    private val userBootstrapToken = AtomicBoolean()
    private val userBootstrapAttempts = AtomicInteger()
    val broker = ChrootRootBroker(authority, ::consumeBootstrapPermit)
    val factory = ChrootRuntimeProcessFactory(context, broker)
    val recovery: ChrootRecoveryRepository
    val manager: ChrootRuntimeManager
    val installer: ChrootInstanceInstaller
    val settings: ContainerSettingsRepository
    val facade: ChrootRuntimeFacade
    val commands: LocalRuntimeCommands get() = facade
    val isolatedContext: Context
    val project: File
    val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "product-$runId")
    val sourceFingerprint = "product-source-$runId"
    val profileId = "product-$runId"
    private val sourceCapture = ChrootAppSourceCapture(context)
    private val sourceCurrent = AtomicBoolean(true)
    private val sourceChangeRecorded = AtomicBoolean()
    private val rootChangeRecorded = AtomicBoolean()
    private val unusable = AtomicBoolean()
    private val preparationStarted = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val wrappers = CopyOnWriteArrayList<ObservedWrapper>()
    private val publicProcesses = CopyOnWriteArrayList<LocalRuntimeProcess>()
    private val stopLock = Any()
    @Volatile private var controlledStop: Deferred<List<ChrootCleanupResult>>? = null
    @Volatile private var expectedRoot: RootAccessState? = null
    @Volatile private var prepared = false
    @Volatile private var baseline: ChrootAppSources? = null
    @Volatile var instance: ChrootInstance? = null
        private set
    @Volatile var profile: ContainerProfile? = null
        private set
    @Volatile var capture: LocalRuntimeCapture? = null
        private set
    @Volatile private var firstSession: ChrootRuntimeSession? = null
    @Volatile private var rootFileSnapshot: RootFileSnapshot? = null
    @Volatile private var lastIdleCloseSession: ChrootRuntimeSession? = null
    private val verifiedBrokerShutdowns = AtomicInteger()
    private var rootObserver: FileObserver? = null
    private val sourceObservers = mutableListOf<FileObserver>()

    private data class RootFileSnapshot(
        val device: Long, val inode: Long, val size: Long, val modified: Long, val changed: Long, val digest: String
    )

    private enum class WrapperKind { RUNTIME, INSTALL, REMOVE }
    private class ObservedWrapper(val kind: WrapperKind, val process: Process) {
        @Volatile var exit: Int? = null
    }

    init {
        try {
            productRequire(context.packageName == "com.ajiu.debug", "debug_target_required")
            productRequire(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a", "arm64_device_required")
            productRequire(context.filesDir.canonicalPath == "/data/user/0/com.ajiu.debug/files", "real_private_files_required")
            createFreshDirectory(journalDirectory, context.noBackupFilesDir.canonicalFile)
            evidence.attach()
            val privateFiles = File(context.filesDir, "chroot-product-verification/$runId")
            createFreshDirectory(privateFiles, context.filesDir.canonicalFile)
            val privateCache = File(context.cacheDir, "chroot-product-$runId")
            createFreshDirectory(privateCache, context.cacheDir.canonicalFile)
            isolatedContext = object : ContextWrapper(context) {
                override fun getFilesDir(): File = privateFiles
                override fun getCacheDir(): File = privateCache
                override fun getNoBackupFilesDir(): File = journalDirectory
                override fun getApplicationContext(): Context = this
            }
            project = File(context.filesDir, "projects/ajiu-chroot-product-$runId")
            createFreshDirectory(project, context.filesDir.canonicalFile)
            val profiles = PreferenceDataStoreFactory.create(scope = profileScope) {
                File(isolatedContext.filesDir, "container.preferences_pb")
            }
            settings = ContainerSettingsRepository(profiles)
            recovery = ChrootRecoveryRepository(File(journalDirectory, "runtime.bin"), ::syncDirectory)
            val observingFactory = object : ChrootProcessFactory {
                override fun captureHelper(): ChrootHelperIdentity = factory.captureHelper()
                override fun start(helper: ChrootHelperIdentity): Process {
                    checkCurrent()
                    return factory.start(helper).also { wrappers += ObservedWrapper(WrapperKind.RUNTIME, it) }
                }
            }
            manager = ChrootRuntimeManager(authority, observingFactory, recovery, scope, Dispatchers.IO,
                { launch -> ChrootControlChannel(launch.token(), AndroidProcess.myUid()) },
                { System.nanoTime() / 1_000_000L })
            val containers = ContainerInstaller(isolatedContext, ContainerOsDetector(isolatedContext))
            productRequire(containers.ASSET_ROOTFS == "container/arm/alpine-rootfs.bin", "arm_asset_required")
            installer = ChrootInstanceInstaller(
                authority, ChrootArchiveWriter(),
                { _, _ -> throw ChrootRuntimeUnavailable("product_installer_bound_session_required") },
                { source ->
                    productRequire(source == RootfsSource.Asset("alpine-rootfs.bin"), "fixed_asset_required")
                    context.assets.open(containers.ASSET_ROOTFS)
                },
                context.filesDir.canonicalFile, File(journalDirectory, "installer.bin"), scope, Dispatchers.IO,
                System::currentTimeMillis, AndroidProcess::myUid,
                startBound = { id, identity, session ->
                    checkCurrent()
                    factory.checkSession(session)
                    factory.startInstaller(id, identity, session).also {
                        wrappers += ObservedWrapper(if (identity == null) WrapperKind.INSTALL else WrapperKind.REMOVE, it)
                    }
                }
            )
            facade = ChrootRuntimeFacade(authority, manager, recovery, installer, settings, factory,
                ::captureSources, File(isolatedContext.cacheDir, "files"), scope)
            foreground.attachStopCallback {
                sourceCurrent.set(false)
                unusable.set(true)
                evidence.check("foregroundLost", true)
                requestControlledStop()
            }
        } catch (failure: Throwable) {
            evidence.failure("fixture_construction", failure)
            sourceCurrent.set(false)
            unusable.set(true)
            rootObserver?.stopWatching()
            sourceObservers.forEach { it.stopWatching() }
            foreground.detachStopCallback()
            scope.cancel()
            profileScope.cancel()
            rootScope.cancel()
            runCatching { evidence.persist() }
            throw safeFailure("fixture_construction", failure)
        }
    }

    suspend fun prepareFromUser() {
        trace.phase(Phase.PREPARE)
        trace.event(Kind.BEGIN)
        try {
            productRequire(preparationStarted.compareAndSet(false, true), "prepare_once")
            productRequire(factory.currentSession.value == null && factory.bootstrapCount == 0, "initial_no_session")
            rootFileSnapshot = readRootFileSnapshot()
            rootObserver = object : FileObserver(context.noBackupFilesDir.path,
                FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
                    FileObserver.MOVED_FROM or FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == rootFile.name || path == null && event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                        if (rootChangeRecorded.compareAndSet(false, true)) trace.event(Kind.ROOT_CHANGED)
                        failClosed()
                    }
                }
            }.also { it.startWatching() }
            verifyRootFileEvidenceOutsideGate()
            val root = authority.accessSnapshot()
            productRequire(root.enabled && root.authorityId.isNotBlank() && root.generation >= 0, "user_enabled_root_required")
            expectedRoot = root
            val helper = factory.captureHelper()
            val rejected = runCatching { factory.start(helper) }.exceptionOrNull()
            productRequire(rejected is ChrootRuntimeUnavailable && factory.currentSession.value == null &&
                factory.bootstrapCount == 0, "no_session_start_rejected")
            evidence.check("noSessionStartRejected", true)
            evidence.check("installedApkArtifactsVerified", true)
            evidence.count("bootstrapBeforeUserEnsure", factory.bootstrapCount.toLong())
            evidence.persist()
            verifyRootFileEvidenceOutsideGate()
            val session = withExplicitBootstrap { factory.ensureFromUser(root) }
            verifyRootFileEvidenceOutsideGate()
            firstSession = session
            factory.checkSession(session)
            productRequire(authority.accessSnapshot() == root && rootSettings.snapshot() == root &&
                factory.bootstrapCount == 1, "prepare_root_session_current")
            prepared = true
            evidence.check("preparedFromUser", true)
            evidence.check("rootPeerHandshakeVerified", true)
            evidence.count("bootstrapCount", factory.bootstrapCount.toLong())
            trace.event(Kind.END)
            evidence.persist()
        } catch (failure: Throwable) {
            evidence.failure("prepare_from_user", failure)
            failClosed()
            evidence.count("bootstrapCount", factory.bootstrapCount.toLong())
            evidence.persist()
            throw safeFailure("prepare_from_user", failure)
        }
    }

    suspend fun runCase(case: ChrootProductCase, body: suspend ChrootProductFixture.() -> Unit) {
        trace.phase(when (case) {
            ChrootProductCase.A_CREATE -> Phase.A_CREATE
            ChrootProductCase.B_AUTHORIZATION -> Phase.B_AUTHORIZATION
            ChrootProductCase.C_STDIO -> Phase.C_STDIO
            ChrootProductCase.D_PTY -> Phase.D_PTY
            ChrootProductCase.E_FILES -> Phase.E_FILES
            ChrootProductCase.F_DISCONNECT_REMOVE -> Phase.F_DISCONNECT_REMOVE
        })
        evidence.caseStarted(case)
        if (!prepared || unusable.get() || finished.get()) {
            val failure = ChrootProductAssertion("product_case_blocked", "BLOCKED")
            evidence.caseFailed(case, failure, blocked = true)
            evidence.persist()
            throw failure
        }
        try {
            verifyRootFileEvidenceOutsideGate()
            verifySourcesOutsideGate()
            requireUsable()
            withTimeout(120_000L) { body() }
            awaitAllKnownWrappers()
            verifyClosedJournal()
            verifyRootFileEvidenceOutsideGate()
            verifySourcesOutsideGate()
            requireUsable()
            evidence.casePassed(case)
        } catch (failure: Throwable) {
            evidence.caseFailed(case, failure)
            failClosed()
            withContext(NonCancellable) {
                try {
                    requestControlledStop()?.let {
                        trace.event(Kind.STOP_AWAIT)
                        withTimeout(15_000L) { it.await() }
                        trace.event(Kind.STOP_DONE)
                    }
                }
                catch (cleanup: Throwable) { evidence.failure("case_controlled_stop", cleanup) }
            }
            throw safeFailure("product_case", failure)
        } finally {
            observe()
            evidence.persist()
        }
    }

    fun requireUsable() {
        productRequire(prepared && !unusable.get() && !finished.get(), "product_fixture_usable")
        foreground.requireUsable()
        checkCurrent()
        productRequire(recovery.isHealthy.value && recovery.blockedInstances.value.isEmpty() &&
            installer.isHealthy.value && installer.failedInstances.value.isEmpty() &&
            installer.unpublishedInstalls.value.isEmpty(), "product_recovery_healthy")
    }

    fun checkCurrent() {
        foreground.checkCurrent()
        if (!sourceCurrent.get() || unusable.get()) throw CancellationException("product_source_closed")
        val root = expectedRoot
        if (root != null && (rootSettings.state.value != root || authority.accessState.value != root || !root.enabled)) {
            if (rootChangeRecorded.compareAndSet(false, true)) trace.event(Kind.ROOT_CHANGED)
            failClosed()
            throw CancellationException("product_root_changed")
        }
    }

    suspend fun createFromUser() {
        requireUsable()
        productRequire(evidencePreparedWithoutTask(), "prepare_did_not_install_or_launch")
        val fresh = ContainerProfile(profileId, "Chroot product verification", RootfsSource.Asset("alpine-rootfs.bin"),
            "/bin/sh", isBuiltin = false, createdAt = System.currentTimeMillis(), localRuntime = LocalContainerRuntime.CHROOT)
        settings.upsertCustomProfile(fresh)
        settings.setActiveProfile(fresh.id)
        val before = settings.captureProfileSnapshot()
        productRequire(before.profiles.singleOrNull() == fresh && fresh.chrootInstance == null, "isolated_fresh_profile")
        val state = facade.state(fresh)
        productRequire(state.instance == null && !state.installed && !state.recoveryRequired && !state.busy &&
            state.rootEnabled && state.journalHealthy && state.authorizedOwners == 0 && state.activeLaunches == 0,
            "fresh_profile_state")
        evidence.check("freshProfileState", true)
        val installed = facade.createInstanceFromUser(fresh, currentRoot())
        instance = installed
        val after = settings.captureProfileSnapshot()
        val published = after.profiles.singleOrNull { it.id == profileId }
        productRequire(published == fresh.copy(chrootInstance = installed) &&
            after.configurationRevision == before.configurationRevision + 1 && installer.unpublishedInstalls.value.isEmpty(),
            "create_cas_publication")
        profile = checkNotNull(published)
        checkCurrent()
        val captured = captureSources(checkNotNull(profile), project.canonicalPath)
        baseline = captured
        observeSources(captured)
        verifySourcesOutsideGate()
        evidence.identity(captured.appIdentity.uid, captured.appIdentity.gid, captured.appIdentity.groups)
        evidence.check("sourceMountsExactlyThree", captured.mounts.size == 3)
        evidence.check("createPublishedByCas", true)
        evidence.check("nativeInstallerVerified", true)
        productRequire(facade.state(checkNotNull(profile)).installed && factory.bootstrapCount == 1, "created_profile_installed")
    }

    private fun evidencePreparedWithoutTask(): Boolean = wrappers.isEmpty() && recovery.snapshot().isEmpty() && instance == null

    suspend fun pending(owner: RuntimeOwner = this.owner, fingerprint: String = sourceFingerprint,
                        sourceCheck: () -> Unit = ::checkCurrent): LocalRuntimeAuthorization {
        requireUsable()
        val failure = runCatching {
            facade.capture(installedProfile(), settings.profileConfigurationRevision, project.canonicalPath,
                owner, fingerprint, sourceCheck)
        }.exceptionOrNull()
        productRequire(failure is LocalRuntimeAuthorizationRequired, "capture_requires_real_pending")
        return facade.pendingAuthorizations.value.single { it.owner == owner && it.sourceFingerprint == fingerprint }
    }

    suspend fun authorizeFromUser(request: LocalRuntimeAuthorization): LocalRuntimeCapture {
        requireUsable()
        val prepared = facade.prepareAuthorization(request)
        productRequire(factory.bootstrapCount <= 2, "bootstrap_bound")
        val authorized = facade.authorizeFromUser(prepared)
        authorized.checkCurrent()
        capture = authorized
        return authorized
    }

    fun installedProfile(): ContainerProfile = checkNotNull(profile) { "product_profile_missing" }
    fun authorizedCapture(): LocalRuntimeCapture = checkNotNull(capture) { "product_capture_missing" }

    suspend fun currentRoot(): RootAccessState {
        verifyRootFileEvidenceOutsideGate()
        verifySourcesOutsideGate()
        checkCurrent()
        val root = authority.accessSnapshot()
        productRequire(root == expectedRoot && rootSettings.snapshot() == root, "root_authority_unchanged")
        return root
    }

    suspend fun startStdio(argv: List<String>, capture: LocalRuntimeCapture = authorizedCapture(), timeoutMs: Long = 30_000L): LocalRuntimeProcess {
        verifyRootFileEvidenceOutsideGate()
        verifySourcesOutsideGate()
        requireUsable()
        productRequire(argv.isNotEmpty(), "stdio_program_required")
        return commands.startStdio(capture, argv.first(), argv.drop(1), timeoutMs = timeoutMs).also {
            publicProcesses += it
            evidence.task(it.launchId, it.taskState.value, it.cleanupState.value)
        }
    }

    suspend fun startPty(argv: List<String>): LocalRuntimeProcess {
        verifyRootFileEvidenceOutsideGate()
        verifySourcesOutsideGate()
        requireUsable()
        return commands.startPty(authorizedCapture(), argv, columns = 80, rows = 24).also {
            publicProcesses += it
            evidence.task(it.launchId, it.taskState.value, it.cleanupState.value)
        }
    }

    data class Output(val task: LocalRuntimeTaskResult, val stdout: ByteArray, val stderr: ByteArray)

    suspend fun collect(process: LocalRuntimeProcess): Output = withTimeout(30_000L) {
        coroutineScope {
            val output = async(Dispatchers.IO) { readBounded(process.inputStream) }
            val error = async(Dispatchers.IO) { readBounded(process.errorStream) }
            val task = process.awaitTaskExit()
            val cleanup = process.awaitCleanup()
            evidence.task(process.launchId, task, cleanup)
            productRequire(cleanup == LocalRuntimeCleanup.VERIFIED, "public_process_cleanup_verified")
            Output(task, output.await(), error.await())
        }
    }

    suspend fun <T> useProcess(process: LocalRuntimeProcess, body: suspend () -> T): T =
        withCleanup(cleanup = listOf({ settle(process) }), body = body)

    suspend fun settle(process: LocalRuntimeProcess) {
        try {
            if (process.cleanupState.value == null) process.stop()
            val cleanup = withTimeout(15_000L) { process.awaitCleanup() }
            val task = withTimeout(5_000L) { process.awaitTaskExit() }
            evidence.task(process.launchId, task, cleanup)
            productRequire(cleanup == LocalRuntimeCleanup.VERIFIED, "settled_process_cleanup_verified")
        } finally {
            evidence.task(process.launchId, process.taskState.value, process.cleanupState.value)
        }
    }

    suspend fun write(process: LocalRuntimeProcess, bytes: ByteArray) = withContext(Dispatchers.IO) {
        checkCurrent()
        runInterruptible { process.outputStream.write(bytes); process.outputStream.flush() }
    }

    fun requireExit(task: LocalRuntimeTaskResult, expected: Int, stage: String) {
        productRequire(task is LocalRuntimeTaskResult.Exited && task.code == expected && task.signal == null, stage)
    }

    suspend fun verifyClosedJournal() {
        val installed = instance
        if (installed != null) productRequire(manager.instanceState(installed.id).activeLaunches == 0 &&
            !manager.instanceState(installed.id).blocked, "manager_no_active_or_unknown")
        val entries = recovery.snapshot()
        evidence.journal(entries)
        productRequire(recovery.isHealthy.value && recovery.blockedInstances.value.isEmpty() && entries.all {
            it.phase == ChrootRecoveryPhase.CLOSED && it.cleanup == ChrootCleanupResult.VERIFIED
        }, "journal_all_closed_verified")
        productRequire(installer.isHealthy.value && installer.failedInstances.value.isEmpty() &&
            installer.unpublishedInstalls.value.isEmpty(), "installer_all_transactions_settled")
    }

    suspend fun awaitAllKnownWrappers() {
        for (wrapper in wrappers) {
            if (wrapper.exit == null) {
                val ended = runInterruptible(Dispatchers.IO) { wrapper.process.waitFor(5L, TimeUnit.SECONDS) }
                productRequire(ended && !wrapper.process.isAlive, "real_wrapper_exit_observed")
                wrapper.exit = wrapper.process.exitValue()
            }
            productRequire(wrapper.exit == 0, "real_wrapper_exit_zero")
        }
        observe()
    }

    suspend fun disconnectAndReconnectFromUser() {
        requireUsable()
        verifyClosedJournal()
        awaitAllKnownWrappers()
        val original = checkNotNull(firstSession) { "product_initial_session_missing" }
        productRequire(factory.currentSession.value === original && factory.bootstrapCount == 1, "initial_session_current")
        val oldCapture = authorizedCapture()
        awaitBrokerIdleAfterProof()
        closeIdleSessionOnce(original)
        productRequire(factory.currentSession.value == null && factory.bootstrapCount == 1, "idle_session_closed")
        evidence.check("idleDisconnectVerified", true)
        productRequire(runCatching { oldCapture.checkCurrent() }.isFailure, "disconnected_capture_rejected")
        val before = factory.bootstrapCount
        val request = pending()
        productRequire(manager.instanceState(checkNotNull(instance).id).authorizedOwners == 0, "disconnect_revoked_lease")
        val preparedRequest = facade.prepareAuthorization(request)
        val rejectedExec = runCatching { commands.exec(oldCapture, "exit 99", timeoutMs = 1_000L) }.isFailure
        val rejectedStart = runCatching { factory.start(factory.captureHelper()) }.exceptionOrNull() is ChrootRuntimeUnavailable
        productRequire(rejectedExec && rejectedStart && factory.currentSession.value == null &&
            factory.bootstrapCount == before, "background_operations_do_not_rebootstrap")
        evidence.check("noBackgroundSu", true)
        verifyRootFileEvidenceOutsideGate()
        val reconnected = withExplicitBootstrap { facade.authorizeFromUser(preparedRequest) }
        verifyRootFileEvidenceOutsideGate()
        val session = checkNotNull(factory.currentSession.value) { "product_reconnected_session_missing" }
        productRequire(session !== original && session.id != original.id && session.rootState == original.rootState &&
            factory.bootstrapCount == 2, "explicit_user_reconnect_new_session")
        reconnected.checkCurrent()
        productRequire(runCatching { oldCapture.checkCurrent() }.isFailure, "old_capture_stays_revoked")
        productRequire(runCatching { commands.exec(oldCapture, "exit 99", timeoutMs = 1_000L) }.isFailure &&
            factory.bootstrapCount == 2, "old_capture_no_launch_after_reconnect")
        capture = reconnected
        evidence.check("oldCaptureRejected", true)
        evidence.check("userReconnected", true)
    }

    suspend fun revokeStopAndRemoveFromUser() {
        requireUsable()
        val installed = checkNotNull(instance) { "product_instance_missing" }
        val oldCapture = authorizedCapture()
        commands.revokeOwner(owner)
        productRequire(runCatching { oldCapture.checkCurrent() }.isFailure &&
            manager.instanceState(installed.id).authorizedOwners == 0, "owner_revocation_exact")
        productRequire(facade.stopInstance(installed) == LocalRuntimeCleanup.VERIFIED, "instance_stop_verified")
        awaitAllKnownWrappers()
        verifyClosedJournal()
        evidence.check("revokeOwnerVerified", true)
        val before = settings.captureProfileSnapshot()
        val installedProfile = installedProfile()
        productRequire(before.profiles.singleOrNull() == installedProfile, "remove_profile_cas_source")
        facade.removeInstanceFromUser(installedProfile, currentRoot())
        val after = settings.captureProfileSnapshot()
        productRequire(after.profiles.none { it.id == profileId } &&
            after.configurationRevision == before.configurationRevision + 1, "remove_profile_cas_gone")
        awaitAllKnownWrappers()
        verifyClosedJournal()
        productRequire(wrappers.count { it.kind == WrapperKind.REMOVE && it.exit == 0 } == 1, "manifest_remove_real_wrapper")
        evidence.check("rootfsRemoved", true)
        evidence.check("profileRemoved", true)
        val session = checkNotNull(factory.currentSession.value) { "product_remove_session_missing" }
        awaitBrokerIdleAfterProof()
        closeIdleSessionOnce(session)
        productRequire(factory.currentSession.value == null && factory.bootstrapCount == 2, "final_broker_idle_closed")
        evidence.check("brokerClosed", true)
    }

    suspend fun afterTests() {
        if (unusable.get()) requestControlledStop()?.let {
            trace.event(Kind.STOP_AWAIT)
            withTimeout(15_000L) { it.await() }
            trace.event(Kind.STOP_DONE)
        }
        observe()
        evidence.persist()
    }

    suspend fun finishFromDriver() {
        if (!finished.compareAndSet(false, true)) throw ChrootProductAssertion("driver_finish_once")
        trace.phase(Phase.SHUTDOWN)
        trace.event(Kind.BEGIN)
        var primary: Throwable? = null
        withContext(NonCancellable) {
            try {
                if (!preparationStarted.get()) {
                    productRequire(factory.bootstrapCount == 0 && factory.currentSession.value == null && wrappers.isEmpty(), "unprepared_no_root_activity")
                } else {
                    requestControlledStop()?.let { result ->
                        trace.event(Kind.STOP_AWAIT)
                        productRequire(withTimeout(15_000L) { result.await() }.all { it == ChrootCleanupResult.VERIFIED }, "driver_stop_verified")
                        trace.event(Kind.STOP_DONE)
                    }
                    for (process in publicProcesses) settle(process)
                    verifyClosedJournal()
                    awaitAllKnownWrappers()
                    verifyRootFileEvidenceOutsideGate()
                    val root = expectedRoot
                    productRequire(root != null && rootSettings.snapshot() == root && authority.accessSnapshot() == root,
                        "final_root_authority_unchanged")
                    evidence.check("rootAuthorityUnchanged", true)
                }
            } catch (failure: Throwable) {
                unusable.set(true)
                evidence.failure("driver_finish", failure)
                primary = safeFailure("driver_finish", failure)
            } finally {
                try {
                    if (hasSettledTransportProof()) {
                        val session = factory.currentSession.value
                        if (session != null) {
                            awaitBrokerIdleAfterProof()
                            closeIdleSessionOnce(session)
                        }
                    }
                    evidence.check("brokerClosed", factory.currentSession.value == null &&
                        factory.bootstrapCount == verifiedBrokerShutdowns.get())
                    evidence.check("finished", true)
                } catch (failure: Throwable) {
                    unusable.set(true)
                    evidence.failure("driver_idle_close", failure)
                    if (primary == null) primary = safeFailure("driver_idle_close", failure)
                } finally {
                    sourceCurrent.set(false)
                    rootObserver?.stopWatching()
                    rootObserver = null
                    sourceObservers.forEach { it.stopWatching() }
                    sourceObservers.clear()
                    foreground.detachStopCallback()
                    observe()
                    trace.event(Kind.END)
                    try { evidence.persist() } finally {
                        if (hasSettledTransportProof() && factory.currentSession.value == null &&
                            factory.bootstrapCount == verifiedBrokerShutdowns.get()) {
                            scope.cancel()
                            profileScope.cancel()
                            rootScope.cancel()
                        }
                    }
                }
            }
        }
        primary?.let { throw it }
    }

    fun report(): String { observe(); evidence.persist(); return evidence.json() }
    fun isSuccessful(): Boolean = finished.get() && evidence.isSuccessful()

    private fun captureSources(profile: ContainerProfile, workspace: String): ChrootAppSources {
        checkCurrent()
        val current = fixedSources(profile, workspace)
        baseline?.let { productRequire(sameSources(current, it), "captured_source_identity_stable") }
        return current
    }

    private fun fixedSources(profile: ContainerProfile, workspace: String): ChrootAppSources {
        productRequire(workspace == project.canonicalPath && workspace.startsWith(context.filesDir.canonicalPath + "/projects/"),
            "private_workspace_native_allowed")
        val actual = sourceCapture.capture(profile, workspace)
        val mounts = actual.mounts.filter { it.target in setOf("/root", "/root/workspace", "/etc/resolv.conf") }
        val installed = checkNotNull(profile.chrootInstance) { "product_source_instance_missing" }
        productRequire(mounts.size == 3 && mounts.map { it.target }.toSet().size == 3 &&
            mounts.single { it.target == "/root" }.source == File(context.filesDir, "chroot-home/${installed.id}").path &&
            mounts.single { it.target == "/root/workspace" }.source == project.path &&
            mounts.single { it.target == "/etc/resolv.conf" }.source == File(context.filesDir, "chroot-dns/${installed.id}/resolv.conf").path,
            "source_exact_native_mounts")
        return actual.copy(mounts = Collections.unmodifiableList(mounts))
    }

    private fun observeSources(sources: ChrootAppSources) {
        for (mount in sources.mounts) {
            val file = File(mount.source)
            val directory = if (mount.target == "/etc/resolv.conf") checkNotNull(file.parentFile) else file
            val guardedNames = when (mount.target) {
                "/root" -> setOf(".instance")
                "/etc/resolv.conf" -> setOf(".instance", file.name)
                else -> emptySet()
            }
            val mask = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
                FileObserver.MOVED_FROM or FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF
            sourceObservers += object : FileObserver(directory.path, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path in guardedNames || path == null && event and
                        (FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) {
                        if (sourceChangeRecorded.compareAndSet(false, true)) trace.event(Kind.SOURCE_CHANGED)
                        failClosed()
                    }
                }
            }.also { it.startWatching() }
        }
    }

    private fun verifySourcesOutsideGate() {
        val previous = baseline ?: return
        val stable = runCatching { sameSources(fixedSources(previous.profile, previous.workspacePath), previous) }.getOrDefault(false)
        if (!stable) {
            if (sourceChangeRecorded.compareAndSet(false, true)) trace.event(Kind.SOURCE_CHANGED)
            failClosed()
            throw ChrootProductAssertion("real_mounted_source_changed")
        }
    }

    private fun sameSources(first: ChrootAppSources, second: ChrootAppSources): Boolean =
        first.profile == second.profile && first.workspacePath == second.workspacePath && first.homePath == second.homePath &&
            first.dns == second.dns && first.mounts == second.mounts && first.appIdentity.uid == second.appIdentity.uid &&
            first.appIdentity.gid == second.appIdentity.gid && first.appIdentity.groups == second.appIdentity.groups

    private suspend fun awaitBrokerIdleAfterProof() {
        verifyClosedJournal()
        productRequire(wrappers.all { it.exit == 0 }, "broker_idle_requires_wrapper_proof")
        trace.event(Kind.IDLE_WAIT)
        withTimeout(5_000L) { factory.registeredProcessCount.first { it == 0 } }
        trace.event(Kind.IDLE_READY)
    }

    private suspend fun closeIdleSessionOnce(session: ChrootRuntimeSession) {
        productRequire(lastIdleCloseSession !== session, "idle_shutdown_not_retried")
        lastIdleCloseSession = session
        trace.event(Kind.IDLE_CLOSE)
        factory.closeIdleFromUser(session)
        verifiedBrokerShutdowns.incrementAndGet()
        trace.event(Kind.IDLE_CLOSED)
    }

    private fun hasSettledTransportProof(): Boolean = recovery.isHealthy.value && recovery.blockedInstances.value.isEmpty() &&
        recovery.snapshot().all { it.phase == ChrootRecoveryPhase.CLOSED && it.cleanup == ChrootCleanupResult.VERIFIED } &&
        installer.isHealthy.value && installer.failedInstances.value.isEmpty() && installer.unpublishedInstalls.value.isEmpty() &&
        wrappers.all { it.exit == 0 && !it.process.isAlive } &&
        instance?.let { manager.instanceState(it.id).activeLaunches == 0 && !manager.instanceState(it.id).blocked } != false

    private fun consumeBootstrapPermit() {
        productRequire(sourceCurrent.get() && !unusable.get() && userBootstrapToken.compareAndSet(true, false),
            "explicit_user_bootstrap_required")
        val previous = userBootstrapAttempts.get()
        productRequire(previous < 2 && userBootstrapAttempts.compareAndSet(previous, previous + 1), "maximum_two_user_bootstraps")
    }

    private suspend fun <T> withExplicitBootstrap(block: suspend () -> T): T {
        productRequire(sourceCurrent.get() && !unusable.get() && userBootstrapAttempts.get() < 2 &&
            userBootstrapToken.compareAndSet(false, true), "explicit_bootstrap_window_once")
        return try { block() } finally { userBootstrapToken.set(false) }
    }

    private fun verifyRootFileEvidenceOutsideGate() {
        val expected = rootFileSnapshot ?: throw ChrootProductAssertion("existing_root_file_required")
        val same = runCatching { readRootFileSnapshot() == expected }.getOrDefault(false)
        if (!same) {
            if (rootChangeRecorded.compareAndSet(false, true)) trace.event(Kind.ROOT_CHANGED)
            failClosed()
            throw ChrootProductAssertion("real_root_file_changed")
        }
    }

    private fun readRootFileSnapshot(): RootFileSnapshot {
        var parent = rootFile.parentFile
        while (parent != null) {
            productRequire(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode), "root_settings_ordinary_ancestors")
            parent = parent.parentFile
        }
        val before = Os.lstat(rootFile.path)
        productRequire(OsConstants.S_ISREG(before.st_mode) && before.st_uid == AndroidProcess.myUid() && before.st_nlink == 1L &&
            before.st_mode and 511 == 384 && before.st_size in 1..16 * 1024L, "root_settings_private_existing_file")
        val fd = Os.open(rootFile.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
        try {
            val opened = Os.fstat(fd)
            productRequire(opened.st_dev == before.st_dev && opened.st_ino == before.st_ino && opened.st_mode == before.st_mode &&
                opened.st_uid == before.st_uid && opened.st_nlink == 1L && opened.st_size == before.st_size, "root_settings_open_identity")
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(4096)
            var total = 0L
            while (true) {
                val count = Os.read(fd, bytes, 0, bytes.size)
                if (count == 0) break
                productRequire(count > 0 && total + count <= 16 * 1024L, "root_settings_bounded_read")
                digest.update(bytes, 0, count)
                total += count
            }
            val after = Os.lstat(rootFile.path)
            productRequire(after.st_dev == before.st_dev && after.st_ino == before.st_ino && after.st_mode == before.st_mode &&
                after.st_uid == before.st_uid && after.st_nlink == 1L && after.st_size == before.st_size && total == before.st_size &&
                after.st_mtime == before.st_mtime && after.st_ctime == before.st_ctime, "root_settings_stable_read")
            return RootFileSnapshot(before.st_dev, before.st_ino, before.st_size, before.st_mtime, before.st_ctime,
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        } finally { Os.close(fd) }
    }

    private fun failClosed() {
        unusable.set(true)
        sourceCurrent.set(false)
        requestControlledStop()
    }

    private fun requestControlledStop(): Deferred<List<ChrootCleanupResult>>? {
        val installed = instance ?: return null
        return synchronized(stopLock) {
            controlledStop ?: run {
                trace.event(Kind.STOP_REQUEST)
                scope.async(Dispatchers.IO) { manager.stopAndAwaitInstance(installed.id) }.also { controlledStop = it }
            }
        }
    }

    private fun observe() {
        evidence.journal(recovery.snapshot())
        evidence.count("bootstrapCount", factory.bootstrapCount.toLong())
        evidence.count("explicitUserBootstrapCount", userBootstrapAttempts.get().toLong())
        evidence.count("verifiedBrokerShutdownCount", verifiedBrokerShutdowns.get().toLong())
        evidence.check("bootstrapBudgetMatched", factory.bootstrapCount == userBootstrapAttempts.get() && userBootstrapAttempts.get() <= 2)
        evidence.count("wrapperCount", wrappers.size.toLong())
        evidence.count("runtimeWrapperCount", wrappers.count { it.kind == WrapperKind.RUNTIME }.toLong())
        evidence.count("installerWrapperCount", wrappers.count { it.kind != WrapperKind.RUNTIME }.toLong())
        evidence.count("realWrapperExitZeroCount", wrappers.count { it.exit == 0 }.toLong())
        evidence.count("unobservedWrapperExitCount", wrappers.count { it.exit == null }.toLong())
        evidence.check("allRealWrappersExitZero", wrappers.all { it.exit == 0 })
        publicProcesses.forEach { evidence.task(it.launchId, it.taskState.value, it.cleanupState.value) }
    }

    companion object {
        const val MAX_OUTPUT = 64 * 1024
        private val random = SecureRandom()
        private fun nonce(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }

        fun safeFailure(stage: String, failure: Throwable): ChrootProductAssertion = when (failure) {
            is ChrootProductAssertion -> failure
            is kotlinx.coroutines.TimeoutCancellationException -> ChrootProductAssertion(stage, "TIMEOUT")
            is CancellationException -> ChrootProductAssertion(stage, "CANCELLED")
            is SecurityException -> ChrootProductAssertion(stage, "SECURITY")
            is java.io.IOException -> ChrootProductAssertion(stage, "IO")
            else -> ChrootProductAssertion(stage, "UNEXPECTED")
        }

        fun createFreshDirectory(directory: File, root: File) {
            productRequire(!Files.exists(directory.toPath(), NOFOLLOW_LINKS), "fresh_owned_directory")
            createOwnedDirectories(directory, root)
        }

        private fun createOwnedDirectories(directory: File, root: File) {
            productRequire(directory.isAbsolute && directory.toPath().normalize() == directory.toPath() &&
                (directory == root || directory.path.startsWith(root.path + "/")), "owned_directory_containment")
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
                createOwnedDirectories(checkNotNull(directory.parentFile), root)
                Files.createDirectory(directory.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            }
            val stat = Os.lstat(directory.path)
            productRequire(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == AndroidProcess.myUid(), "ordinary_owned_directory")
        }

        private fun syncDirectory(directory: File) {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                productRequire(OsConstants.S_ISDIR(Os.fstat(fd).st_mode), "journal_directory_required")
                Os.fsync(fd)
            } finally { Os.close(fd) }
        }

        suspend fun readBounded(input: InputStream): ByteArray = runInterruptible(Dispatchers.IO) {
            input.use {
                val output = ByteArrayOutputStream()
                val bytes = ByteArray(4096)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    productRequire(count > 0 && count <= MAX_OUTPUT - output.size(), "product_output_bound")
                    output.write(bytes, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}
