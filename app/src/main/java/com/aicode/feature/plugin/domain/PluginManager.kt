package com.aicode.feature.plugin.domain

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.plugin.data.ExtractedPackage
import com.aicode.feature.plugin.data.PackageReadResult
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginInstallTransaction
import com.aicode.feature.plugin.data.PluginPackageReader
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.PluginRegistryStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PluginManager internal constructor(
    private val root: File,
    registryFile: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject constructor(@ApplicationContext context: Context, installer: ContainerInstaller) :
        this(androidPluginRoot(context.filesDir, installer.aicodeDir), File(context.filesDir.canonicalFile, "plugin-registry.json"))

    private val registry = PluginRegistryStore(registryFile)
    private val mutableEvents = MutableSharedFlow<PluginLifecycleEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PluginLifecycleEvent> = mutableEvents.asSharedFlow()
    @Volatile private var lifecycleSink: (suspend (PluginLifecycleEvent) -> Unit)? = null
    private val revocationListeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val cleanupLeases = HashMap<String, Pair<PluginRecord, Long>>()

    fun registerLifecycleSink(sink: suspend (PluginLifecycleEvent) -> Unit) {
        synchronized(stateLock) { check(lifecycleSink == null); lifecycleSink = sink }
    }
    internal fun registerRevocationListener(listener: (String) -> Unit) { revocationListeners += listener }
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val mutableSnapshot = MutableStateFlow(PluginSnapshot())
    val snapshot: StateFlow<PluginSnapshot> = mutableSnapshot.asStateFlow()
    val plugins: StateFlow<List<InstalledPlugin>> = Projection(snapshot) { it.plugins }
    val revision: StateFlow<Long> = Projection(snapshot) { it.revision }
    private var loaded = false
    private var storageBlocked = false
    private var epoch = 0L
    private val revoked = HashSet<String>()
    private val previews = LinkedHashMap<String, Staged>()
    private val trusts = HashMap<String, PluginTrustPreview>()
    private val backupRestores = HashMap<String, BackupRestoreFence>()
    private data class Staged(val pkg: ExtractedPackage, val preview: PluginPreview, val previousRevision: String?, val backupOnly: Boolean)

    internal class BackupSourceChangedException : IllegalStateException("PLUGIN_SOURCE_CHANGED")

    internal class InstallSnapshot internal constructor(
        val record: PluginRecord,
        val manifest: PluginManifest,
        val installDirectory: File,
        val dataDirectory: File,
        val checkCurrent: () -> Unit
    )

    internal inner class BackupRestoreFence internal constructor(val id: String, internal var revision: String?) {
        internal fun checkCurrent() = synchronized(stateLock) {
            if (backupRestores[id] !== this || currentRecord(id)?.installRevision != revision || currentRecord(id)?.enabled == true) {
                throw BackupSourceChangedException()
            }
        }
        internal fun snapshot(): InstallSnapshot {
            checkCurrent()
            return installSnapshot(requireNotNull(this@PluginManager.snapshot.value.plugins.firstOrNull { it.record.id == id }), this)
        }
        internal val dataDirectory: File get() { checkCurrent(); return File(root, "data/$id") }
    }

    internal suspend fun stableInstallSnapshots(): List<InstallSnapshot> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            loadLocked()
            check(!storageBlocked) { "plugin registry unavailable" }
            snapshot.value.plugins.map { installSnapshot(it) }
        }
    }

    private fun installSnapshot(plugin: InstalledPlugin, fence: BackupRestoreFence? = null): InstallSnapshot {
        check(plugin.manifest != null && plugin.errors.isEmpty()) { "plugin package unavailable" }
        val record = plugin.record
        fun checkCurrent() {
            fun checkIdentity() = synchronized(stateLock) {
                if (storageBlocked || backupRestores[record.id] !== fence ||
                    currentRecord(record.id)?.let { sameIdentity(record, it) } != true) throw BackupSourceChangedException()
            }
            checkIdentity()
            if (!verifyRecord(record) || record.fileHashes["plugin.json"] != record.manifestSha256 ||
                PluginFiles.contentHash(record.fileHashes) != record.contentHash) throw BackupSourceChangedException()
            checkIdentity()
        }
        checkCurrent()
        return InstallSnapshot(record, plugin.manifest, installDirectory(record.id, record.installRevision), File(root, "data/${record.id}"), ::checkCurrent)
    }

    internal fun createBackupDataStage(): File {
        val parent = File(root, "data")
        PluginFiles.checkDirectoryChain(parent.absoluteFile.toPath().normalize())
        return File(parent, ".backup-import-${UUID.randomUUID()}").also { Files.createDirectory(it.toPath()) }
    }

    internal suspend fun <T> withBackupRestoreFence(id: String, previousRevision: String?, block: suspend (BackupRestoreFence) -> T): T =
        withContext(Dispatchers.IO) {
            require(PluginManifestParser.isValidPluginId(id))
            val fence = operationMutex.withLock {
                loadLocked()
                check(!storageBlocked) { "plugin registry unavailable" }
                val created = synchronized(stateLock) {
                    if (id in backupRestores || currentRecord(id)?.installRevision != previousRevision) throw BackupSourceChangedException()
                    BackupRestoreFence(id, previousRevision).also { backupRestores[id] = it }
                }
                try {
                    revoke(id, restoring = created)
                    registry.save(snapshot.value.plugins.map { it.record })
                    created
                } catch (error: Exception) {
                    synchronized(stateLock) { if (backupRestores[id] === created) backupRestores.remove(id) }
                    throw error
                }
            }
            try { block(fence) }
            finally {
                withContext(NonCancellable) {
                    operationMutex.withLock {
                        synchronized(stateLock) { if (backupRestores[id] === fence) backupRestores.remove(id) }
                    }
                }
            }
        }

    private fun restoring(id: String): Boolean = synchronized(stateLock) { id in backupRestores }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) { operationMutex.withLock { loadLocked() } }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            loadLocked()
            if (!storageBlocked) {
                val existing = snapshot.value.plugins
                val validated = existing.map { loadRecord(it.record) }
                synchronized(stateLock) {
                    val current = snapshot.value.plugins.associateBy { it.record.id }
                    publishLocked(validated.map { plugin ->
                        val live = current[plugin.record.id]
                        if (live != null && live.record.activationEpoch != plugin.record.activationEpoch) live else plugin
                    })
                }
            }
        }
    }
    suspend fun preview(input: InputStream, source: PluginInstallOrigin = PluginInstallOrigin(), expected: PluginPackageExpectation? = null): PluginPreviewResult =
        previewPackage(input, source, expected, PluginPackageReader.Limits(), false)

    internal suspend fun previewForBackup(input: InputStream): PluginPreviewResult =
        previewPackage(input, PluginInstallOrigin(), null, PluginPackageReader.Limits(maxArchiveBytes = BACKUP_MAX_ARCHIVE_BYTES), true)

    private suspend fun previewPackage(input: InputStream, source: PluginInstallOrigin, expected: PluginPackageExpectation?, limits: PluginPackageReader.Limits, backupOnly: Boolean): PluginPreviewResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            loadLocked()
            if (storageBlocked) return@withLock PluginPreviewResult.Failure(snapshot.value.errors)
            try { PluginRegistryStore.validateOrigin(source) }
            catch (_: IllegalArgumentException) { return@withLock PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.FIELD_INVALID, "source"))) }
            prunePreviews()
            if (previews.size >= 8) return@withLock PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.STORAGE_FAILED, "preview limit")))
            val dir = File(root, "staging/${UUID.randomUUID()}")
            var owned = false
            try {
                currentCoroutineContext().ensureActive()
                val pkg = when (val result = PluginPackageReader(limits).read(input, dir)) {
                    is PackageReadResult.Failed -> return@withLock PluginPreviewResult.Failure(listOf(result.error))
                    is PackageReadResult.Ok -> result.pkg
                }
                owned = true
                currentCoroutineContext().ensureActive()
                val manifest = when (val result = PluginManifestParser.parse(pkg.manifestText)) {
                    is ManifestParseResult.Failure -> { PluginFiles.deleteOwned(dir); return@withLock PluginPreviewResult.Failure(result.errors) }
                    is ManifestParseResult.Success -> result.manifest
                }
                if (expected != null && (manifest.id != expected.id || manifest.version.toString() != expected.version ||
                    manifest.publisher != expected.publisher || manifest.repository != expected.repository ||
                    (expected.archiveSha256 != null && pkg.archiveSha256 != expected.archiveSha256))) {
                    PluginFiles.deleteOwned(dir)
                    return@withLock PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.CONTENT_CHANGED, "source expectation")))
                }
                if (source.repository != null && source.repository != manifest.repository) {
                    PluginFiles.deleteOwned(dir)
                    return@withLock PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.CONTENT_CHANGED, "source repository")))
                }
                val content = when (val result = PluginContentLoader.load(dir, manifest)) {
                    is ContentLoadResult.Failed -> { PluginFiles.deleteOwned(dir); return@withLock PluginPreviewResult.Failure(result.errors) }
                    is ContentLoadResult.Ok -> result.content
                }
                if (!PluginFiles.verify(dir, pkg.fileHashes)) {
                    PluginFiles.deleteOwned(dir)
                    return@withLock PluginPreviewResult.Failure(listOf(PluginError(PluginErrorCode.CONTENT_CHANGED)))
                }
                val previous = currentRecord(manifest.id)
                val preview = PluginPreview(UUID.randomUUID().toString(), manifest, content,
                    previous?.version,
                    pkg.archiveSha256, pkg.manifestSha256, pkg.contentHash, risks(manifest), clock() + PREVIEW_TTL,
                    source, previous?.installRevision)
                previews[preview.token] = Staged(pkg, preview, previous?.installRevision, backupOnly)
                PluginPreviewResult.Success(preview)
            } catch (e: Exception) {
                if (owned) runCatching { PluginFiles.deleteOwned(dir) }
                if (e is CancellationException) throw e
                PluginPreviewResult.Failure(listOf(storageError(e)))
            }
        }
    }
    suspend fun discardPreview(token: String): Unit = withContext(NonCancellable + Dispatchers.IO) {
        operationMutex.withLock {
            val staged = previews.remove(token) ?: return@withLock
            PluginFiles.deleteOwned(staged.pkg.dir)
        }
    }
    suspend fun install(token: String): PluginInstallResult = installPackage(token, null)

    internal suspend fun installForBackup(token: String, fence: BackupRestoreFence): PluginInstallResult = installPackage(token, fence)

    private suspend fun installPackage(token: String, fence: BackupRestoreFence?): PluginInstallResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            loadLocked()
            if (storageBlocked) return@withLock PluginInstallResult.Failure(snapshot.value.errors)
            val staged = previews.remove(token)
                ?: return@withLock PluginInstallResult.Failure(listOf(PluginError(PluginErrorCode.PREVIEW_EXPIRED)))
            val pkg = staged.pkg
            val id = staged.preview.manifest.id
            var destination: File? = null
            var moved = false
            var transactionStarted = false
            var committed = false
            try {
                if (synchronized(stateLock) { backupRestores[id] !== fence } || staged.backupOnly && fence == null || fence != null && fence.id != id) {
                    return@withLock PluginInstallResult.Failure(listOf(PluginError(PluginErrorCode.STALE_CAPTURE)))
                }
                fence?.checkCurrent()
                if (clock() >= staged.preview.expiresAt) return@withLock PluginInstallResult.Failure(listOf(PluginError(PluginErrorCode.PREVIEW_EXPIRED)))
                if (currentRecord(id)?.installRevision != staged.previousRevision) {
                    return@withLock PluginInstallResult.Failure(listOf(PluginError(PluginErrorCode.STALE_CAPTURE)))
                }
                if (!PluginFiles.verify(pkg.dir, pkg.fileHashes)) return@withLock PluginInstallResult.Failure(listOf(PluginError(PluginErrorCode.CONTENT_CHANGED)))
                currentCoroutineContext().ensureActive()
                revoke(id, restoring = fence)
                val previous = snapshot.value.plugins.firstOrNull { it.record.id == id }?.record
                val revision = UUID.randomUUID().toString()
                val target = installDirectory(id, revision)
                destination = target
                if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) error("install target exists")
                PluginFiles.ensureDirectory(target.parentFile)
                registry.saveInstallTransaction(PluginInstallTransaction(id, revision, previous?.installRevision))
                transactionStarted = true
                Files.move(pkg.dir.toPath(), target.toPath(), ATOMIC_MOVE)
                moved = true
                val record = PluginRecord(id, staged.preview.manifest.version.toString(), false, clock(),
                    archiveSha256 = pkg.archiveSha256, manifestSha256 = pkg.manifestSha256,
                    installRevision = revision, contentHash = pkg.contentHash, fileHashes = pkg.fileHashes,
                    activationEpoch = synchronized(stateLock) { nextEpochLocked() },
                    origin = staged.preview.source.kind, sourceId = staged.preview.source.sourceId,
                    sourceName = staged.preview.source.sourceName, indexUrl = staged.preview.source.indexUrl,
                    repository = staged.preview.source.repository, officialUpstream = staged.preview.source.officialUpstream)
                val installed = loadRecord(record)
                if (installed.errors.isNotEmpty()) return@withLock PluginInstallResult.Failure(installed.errors)
                registry.save(snapshot.value.plugins.filterNot { it.record.id == id }.map { it.record } + record)
                committed = true
                synchronized(stateLock) {
                    fence?.revision = revision
                    publishLocked(snapshot.value.plugins.filterNot { it.record.id == id } + installed)
                }
                // A committed registry selects an immutable revision directory; the prior package stays intact until then.
                runCatching {
                    previous?.let { PluginFiles.deleteOwned(installDirectory(id, it.installRevision)) }
                    registry.clearInstallTransaction()
                }
                PluginInstallResult.Success(installed)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                PluginInstallResult.Failure(listOf(storageError(e)))
            } finally {
                if (!committed) {
                    if (moved) destination?.let { runCatching { PluginFiles.deleteOwned(it) } }
                    if (transactionStarted) runCatching { registry.clearInstallTransaction() }
                }
                runCatching { PluginFiles.deleteOwned(pkg.dir) }
            }
        }
    }
    suspend fun prepareTrust(id: String): PluginTrustPreview = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            loadLocked()
            val plugin = snapshot.value.plugins.firstOrNull { it.record.id == id }
            fun failed(error: PluginError) = PluginTrustPreview("", id, plugin?.record?.installRevision.orEmpty(),
                plugin?.record?.contentHash.orEmpty(), plugin?.record?.activationEpoch ?: 0, clock(), plugin?.manifest, null, listOf(error))
            if (storageBlocked) return@withLock failed(PluginError(PluginErrorCode.REGISTRY_CORRUPT))
            if (restoring(id)) return@withLock failed(PluginError(PluginErrorCode.STALE_CAPTURE))
            if (plugin?.manifest == null) return@withLock failed(PluginError(PluginErrorCode.NOT_INSTALLED))
            if (!verifyRecord(plugin.record)) { revoke(id, plugin.record); return@withLock failed(PluginError(PluginErrorCode.CONTENT_CHANGED)) }
            synchronized(stateLock) {
                val live = snapshot.value.plugins.firstOrNull { it.record.id == id }
                if (live?.record?.installRevision != plugin.record.installRevision || live.record.activationEpoch != plugin.record.activationEpoch) {
                    return@synchronized failed(PluginError(PluginErrorCode.STALE_CAPTURE))
                }
                trusts.entries.removeAll { it.value.id == id || clock() >= it.value.expiresAt }
                val preview = PluginTrustPreview(UUID.randomUUID().toString(), id, plugin.record.installRevision,
                    plugin.record.contentHash, plugin.record.activationEpoch, clock() + TRUST_TTL, plugin.manifest, risks(plugin.manifest))
                trusts[preview.token] = preview
                preview
            }
        }
    }
    suspend fun enable(id: String, trustToken: String): PluginOperationResult = withContext(Dispatchers.IO) {
        var firstEnable = false
        var activated: PluginCapture? = null
        val result = operationMutex.withLock {
            loadLocked()
            if (storageBlocked) return@withLock operationFailure(PluginErrorCode.REGISTRY_CORRUPT)
            if (restoring(id)) return@withLock operationFailure(PluginErrorCode.STALE_CAPTURE)
            val challenge = synchronized(stateLock) { trusts.remove(trustToken) }
                ?: return@withLock operationFailure(PluginErrorCode.TRUST_REQUIRED)
            if (clock() >= challenge.expiresAt) return@withLock operationFailure(PluginErrorCode.TRUST_EXPIRED)
            val plugin = snapshot.value.plugins.firstOrNull { it.record.id == id }
                ?: return@withLock operationFailure(PluginErrorCode.NOT_INSTALLED)
            if (challenge.id != id || challenge.installRevision != plugin.record.installRevision ||
                challenge.contentHash != plugin.record.contentHash || challenge.activationEpoch != plugin.record.activationEpoch) {
                return@withLock operationFailure(PluginErrorCode.STALE_CAPTURE)
            }
            if (plugin.manifest == null || plugin.errors.isNotEmpty() || !verifyRecord(plugin.record)) {
                revoke(id)
                return@withLock operationFailure(PluginErrorCode.CONTENT_CHANGED)
            }
            val updated = synchronized(stateLock) {
                if (currentRecord(id)?.let { sameIdentity(plugin.record, it) } != true) return@synchronized null
                plugin.record.copy(enabled = true, trustHash = plugin.record.contentHash,
                    activationEpoch = nextEpochLocked(), installHookDelivered = true)
            } ?: return@withLock operationFailure(PluginErrorCode.STALE_CAPTURE)
            try {
                registry.save(snapshot.value.plugins.map { if (it.record.id == id) updated else it.record })
                val accepted = synchronized(stateLock) {
                    if (currentRecord(id)?.let { sameIdentity(plugin.record, it) } != true) false else {
                        revoked.remove(id)
                        publishLocked(snapshot.value.plugins.map { if (it.record.id == id) plugin.copy(record = updated, status = PluginStatus.ENABLED) else it })
                        activated = capture(id)
                        true
                    }
                }
                if (!accepted) { registry.save(snapshot.value.plugins.map { it.record }); operationFailure(PluginErrorCode.STALE_CAPTURE) }
                else {
                    firstEnable = !plugin.record.installHookDelivered
                    PluginOperationResult.Success
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; PluginOperationResult.Failure(listOf(storageError(e))) }
        }
        if (result == PluginOperationResult.Success) {
            activated?.let { active ->
                if (firstEnable) dispatchLifecycle(PluginLifecycleEvent(PluginLifecycleKind.INSTALLED, active))
                dispatchLifecycle(PluginLifecycleEvent(PluginLifecycleKind.ENABLED, active))
            }
        }
        result
    }
    suspend fun disable(id: String): PluginOperationResult {
        if (!PluginManifestParser.isValidPluginId(id)) return operationFailure(PluginErrorCode.FIELD_INVALID)
        if (restoring(id)) return operationFailure(PluginErrorCode.STALE_CAPTURE)
        val previous = synchronized(stateLock) { snapshot.value.plugins.firstOrNull { it.record.id == id && it.isActive } }
        revoke(id)
        return withContext(NonCancellable + Dispatchers.IO) {
            var cleanup: PluginCapture? = null
            val result = operationMutex.withLock {
                loadLocked()
                if (restoring(id)) return@withLock operationFailure(PluginErrorCode.STALE_CAPTURE)
                revoke(id)
                if (storageBlocked) return@withLock operationFailure(PluginErrorCode.REGISTRY_CORRUPT)
                if (currentRecord(id) == null) return@withLock operationFailure(PluginErrorCode.NOT_INSTALLED)
                try {
                    registry.save(snapshot.value.plugins.map { it.record })
                    cleanup = previous?.let(::createCleanupCapture)
                    PluginOperationResult.Success
                } catch (e: Exception) { PluginOperationResult.Failure(listOf(storageError(e))) }
            }
            cleanup?.let { dispatchCleanup(PluginLifecycleKind.DISABLED, it) }
            result
        }
    }
    suspend fun uninstall(id: String, deleteData: Boolean = false): PluginOperationResult {
        if (!PluginManifestParser.isValidPluginId(id)) return operationFailure(PluginErrorCode.FIELD_INVALID)
        if (restoring(id)) return operationFailure(PluginErrorCode.STALE_CAPTURE)
        val previous = synchronized(stateLock) { snapshot.value.plugins.firstOrNull { it.record.id == id && it.isActive } }
        revoke(id)
        return withContext(NonCancellable + Dispatchers.IO) {
            var selected: PluginRecord? = null
            var cleanup: PluginCapture? = null
            val prepared = operationMutex.withLock {
                loadLocked()
                if (restoring(id)) return@withLock operationFailure(PluginErrorCode.STALE_CAPTURE)
                revoke(id)
                if (storageBlocked) return@withLock operationFailure(PluginErrorCode.REGISTRY_CORRUPT)
                selected = currentRecord(id) ?: return@withLock operationFailure(PluginErrorCode.NOT_INSTALLED)
                try {
                    registry.save(snapshot.value.plugins.map { it.record })
                    cleanup = previous?.let(::createCleanupCapture)
                    PluginOperationResult.Success
                } catch (e: Exception) { PluginOperationResult.Failure(listOf(storageError(e))) }
            }
            if (prepared != PluginOperationResult.Success) return@withContext prepared
            cleanup?.let { dispatchCleanup(PluginLifecycleKind.UNINSTALLED, it) }
            operationMutex.withLock {
                val record = requireNotNull(selected)
                if (currentRecord(id)?.let { sameIdentity(record, it) } != true) return@withLock operationFailure(PluginErrorCode.STALE_CAPTURE)
                try {
                    registry.save(snapshot.value.plugins.filterNot { it.record.id == id }.map { it.record })
                    synchronized(stateLock) { publishLocked(snapshot.value.plugins.filterNot { it.record.id == id }) }
                    PluginFiles.deleteOwned(installDirectory(id, record.installRevision))
                    if (deleteData) PluginFiles.deleteOwned(File(root, "data/$id"))
                    PluginOperationResult.Success
                } catch (e: Exception) { PluginOperationResult.Failure(listOf(storageError(e))) }
            }
        }
    }
    fun capture(id: String): PluginCapture? = synchronized(stateLock) {
        if (id in backupRestores) return@synchronized null
        snapshot.value.plugins.firstOrNull { it.record.id == id && it.isActive }?.let {
            PluginCapture(it.record, requireNotNull(it.manifest), it.content,
                installDirectory(id, it.record.installRevision), ::validateCapture, ::validateActiveCapture)
        }
    }
    suspend fun checkCurrent(capture: PluginCapture): Boolean = withContext(Dispatchers.IO) {
        try { capture.checkCurrent(); true } catch (_: PluginCaptureException) { false }
    }
    fun dataDirectory(id: String): File {
        require(PluginManifestParser.isValidPluginId(id)) { "invalid plugin id" }
        return File(root, "data/$id").also { PluginFiles.ensureDirectory(it) }
    }
    fun dependencyDirectory(capture: PluginCapture): File {
        capture.checkCurrent()
        return File(dataDirectory(capture.id), "dependencies/${capture.installRevision}").also { PluginFiles.ensureDirectory(it) }
    }
    fun areDependenciesPrepared(capture: PluginCapture): Boolean {
        capture.checkCurrent()
        val dependency = capture.content.runtime?.dependencies ?: return true
        return synchronized(stateLock) { currentRecord(capture.id)?.let { it.dependenciesReady && it.dependenciesHash == dependency.sha256 } == true }
    }
    suspend fun markDependenciesPrepared(capture: PluginCapture, hash: String = capture.content.runtime?.dependencies?.sha256.orEmpty()): PluginOperationResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            try {
                capture.checkCurrent()
                val dependency = capture.content.runtime?.dependencies ?: return@withLock operationFailure(PluginErrorCode.FIELD_INVALID)
                if (hash != dependency.sha256) return@withLock operationFailure(PluginErrorCode.CONTENT_CHANGED)
                val record = requireNotNull(currentRecord(capture.id)).copy(dependenciesReady = true, dependenciesHash = hash)
                registry.save(snapshot.value.plugins.map { if (it.record.id == capture.id) record else it.record })
                capture.checkCurrent()
                val accepted = synchronized(stateLock) {
                    if (currentRecord(capture.id)?.let { sameIdentity(capture.record, it) && it.enabled } != true) false else {
                        publishLocked(snapshot.value.plugins.map { if (it.record.id == capture.id) it.copy(record = record) else it })
                        true
                    }
                }
                if (!accepted) { registry.save(snapshot.value.plugins.map { it.record }); operationFailure(PluginErrorCode.STALE_CAPTURE) }
                else PluginOperationResult.Success
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is PluginCaptureException) runCatching { registry.save(snapshot.value.plugins.map { it.record }) }
                PluginOperationResult.Failure(listOf(if (e is PluginCaptureException) e.error else storageError(e)))
            }
        }
    }
    fun tools(): List<PluginContribution<PluginTool>> = contributions { it.tools }
    fun skills(): List<PluginContribution<PluginSkill>> = contributions { it.skills }
    fun agents(): List<PluginContribution<PluginAgent>> = contributions { it.agents }
    fun pages(): List<PluginContribution<PluginPage>> = contributions { it.pages }
    fun actions(): List<PluginContribution<PluginUiEntry>> = contributions { it.actions }
    fun uiEntries(slot: String? = null): List<PluginContribution<PluginUiEntry>> = contributions { content ->
        content.uiEntries.filter { slot == null || it.slot == slot }
    }
    fun workspaceTools(): List<PluginContribution<PluginWorkspaceTool>> = contributions { it.workspaceTools }
    fun hooks(event: String? = null): List<PluginContribution<PluginHook>> = contributions { content ->
        content.hooks.filter { event == null || it.event == event }
    }
    fun mcpServers(): List<PluginContribution<PluginMcpServer>> = contributions { it.mcp }
    fun providers(): List<PluginContribution<PluginProviderDefinition>> = contributions { it.providers }
    fun sessionSources(): List<PluginContribution<PluginSessionSourceDefinition>> = contributions { it.sessionSources }
    fun shizuku(): List<PluginContribution<PluginShizukuDefinition>> = contributions { listOfNotNull(it.shizuku) }
    fun overlays(): List<PluginContribution<PluginOverlayDefinition>> = contributions { listOfNotNull(it.overlay) }
    fun backup(): List<PluginContribution<PluginBackupDefinition>> = contributions { listOfNotNull(it.backup) }
    fun modelAccess(): List<PluginContribution<PluginModelAccessDefinition>> = contributions { listOfNotNull(it.modelAccess) }
    private fun <T> contributions(select: (PluginContent) -> List<T>): List<PluginContribution<T>> = synchronized(stateLock) {
        snapshot.value.plugins.filter { it.isActive && it.record.id !in backupRestores }.flatMap { plugin ->
            val source = requireNotNull(capture(plugin.record.id))
            select(source.content).map { PluginContribution(source, it) }
        }
    }
    private fun validateActiveCapture(capture: PluginCapture) {
        val active = synchronized(stateLock) {
            capture.id !in backupRestores && snapshot.value.plugins.any { it.isActive && sameIdentity(capture.record, it.record) }
        }
        if (!active) throw PluginCaptureException(PluginError(PluginErrorCode.STALE_CAPTURE))
    }
    private fun validateCapture(capture: PluginCapture) {
        validateActiveCapture(capture)
        if (!verifyRecord(capture.record)) {
            revoke(capture.id, capture.record)
            throw PluginCaptureException(PluginError(PluginErrorCode.CONTENT_CHANGED))
        }
        validateActiveCapture(capture)
    }
    private fun revoke(id: String, expected: PluginRecord? = null, restoring: BackupRestoreFence? = null) {
        val changed = synchronized(stateLock) {
            if (backupRestores[id] !== restoring) return@synchronized false
            if (expected != null && currentRecord(id)?.let { sameIdentity(expected, it) } != true) return@synchronized false
            revoked.add(id)
            trusts.entries.removeAll { it.value.id == id }
            cleanupLeases.entries.removeAll { it.value.first.id == id }
            val next = nextEpochLocked()
            publishLocked(snapshot.value.plugins.map {
                if (it.record.id == id) it.copy(record = it.record.copy(enabled = false, trustHash = null, activationEpoch = next),
                    status = if (it.errors.isEmpty()) PluginStatus.DISABLED else PluginStatus.INVALID) else it
            })
            true
        }
        if (changed) revocationListeners.forEach { it(id) }
    }
    private fun createCleanupCapture(previous: InstalledPlugin): PluginCapture? {
        if (!previous.isActive || !verifyRecord(previous.record)) return null
        return synchronized(stateLock) {
            val current = currentRecord(previous.record.id) ?: return@synchronized null
            if (previous.record.id in backupRestores) return@synchronized null
            if (current.enabled || current.installRevision != previous.record.installRevision || current.contentHash != previous.record.contentHash) return@synchronized null
            val token = UUID.randomUUID().toString()
            cleanupLeases[token] = current to (clock() + CLEANUP_TTL)
            fun active(capture: PluginCapture) {
                val valid = synchronized(stateLock) {
                    val lease = cleanupLeases[token]
                    lease != null && clock() < lease.second && sameIdentity(lease.first, capture.record) &&
                        currentRecord(capture.id)?.let { !it.enabled && sameIdentity(capture.record, it) } == true
                }
                if (!valid) throw PluginCaptureException(PluginError(PluginErrorCode.STALE_CAPTURE))
            }
            PluginCapture(current, requireNotNull(previous.manifest), previous.content,
                installDirectory(current.id, current.installRevision), { capture ->
                    active(capture)
                    if (!verifyRecord(capture.record)) throw PluginCaptureException(PluginError(PluginErrorCode.CONTENT_CHANGED))
                    active(capture)
                }, ::active, cleanup = true)
        }
    }
    private suspend fun dispatchCleanup(kind: PluginLifecycleKind, capture: PluginCapture) {
        try { dispatchLifecycle(PluginLifecycleEvent(kind, capture)) }
        finally { synchronized(stateLock) { cleanupLeases.entries.removeAll { sameIdentity(it.value.first, capture.record) } } }
    }
    private suspend fun dispatchLifecycle(event: PluginLifecycleEvent) {
        if (!runCatching { event.capture.checkCurrent() }.isSuccess) return
        mutableEvents.tryEmit(event)
        val sink = lifecycleSink ?: return
        try { withTimeoutOrNull(CLEANUP_TTL) { sink(event) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    private fun currentRecord(id: String): PluginRecord? = snapshot.value.plugins.firstOrNull { it.record.id == id }?.record
    private fun sameIdentity(a: PluginRecord, b: PluginRecord): Boolean = a.id == b.id && a.installRevision == b.installRevision &&
        a.contentHash == b.contentHash && a.activationEpoch == b.activationEpoch
    private fun nextEpochLocked(): Long { check(epoch < Long.MAX_VALUE); return ++epoch }
    private fun publishLocked(list: List<InstalledPlugin>, errors: List<PluginError> = snapshot.value.errors) {
        mutableSnapshot.value = PluginSnapshot(snapshot.value.revision + 1,
            java.util.Collections.unmodifiableList(list.sortedBy { it.record.id }),
            java.util.Collections.unmodifiableList(errors.toList()))
    }
    private fun loadLocked() {
        if (loaded) return
        loaded = true
        try {
            PluginFiles.ensureDirectory(root)
            PluginFiles.ensureDirectory(File(root, "installed"))
            PluginFiles.ensureDirectory(File(root, "staging"))
            PluginFiles.ensureDirectory(File(root, "data"))
            val records = registry.load()
            if (registry.loadFailed) throw IllegalStateException("registry corrupt")
            recoverTransaction(records)
            synchronized(stateLock) { epoch = maxOf(epoch, records.maxOfOrNull { it.activationEpoch } ?: 0) }
            val plugins = records.map { r ->
                val record = synchronized(stateLock) {
                    if (r.id in revoked) r.copy(enabled = false, trustHash = null, activationEpoch = nextEpochLocked()) else r
                }
                loadRecord(record)
            }
            synchronized(stateLock) {
                publishLocked(plugins.map { plugin ->
                    if (plugin.record.id in revoked) plugin.copy(record = plugin.record.copy(enabled = false, trustHash = null),
                        status = if (plugin.errors.isEmpty()) PluginStatus.DISABLED else PluginStatus.INVALID) else plugin
                })
            }
        } catch (e: Exception) {
            storageBlocked = true
            synchronized(stateLock) { publishLocked(emptyList(), listOf(PluginError(PluginErrorCode.REGISTRY_CORRUPT, e.javaClass.simpleName))) }
        }
    }
    private fun recoverTransaction(records: List<PluginRecord>) {
        val journal = registry.loadInstallTransaction() ?: return
        val current = records.firstOrNull { it.id == journal.id }?.installRevision
        if (current != journal.revision && current != journal.previousRevision) error("journal identity mismatch")
        val committed = current == journal.revision
        val remove = if (committed) journal.previousRevision else journal.revision
        remove?.let { PluginFiles.deleteOwned(installDirectory(journal.id, it)) }
        registry.clearInstallTransaction()
    }
    private fun installDirectory(id: String, revision: String): File {
        require(PluginManifestParser.isValidPluginId(id) && PluginRegistryStore.isRevision(revision))
        return File(root, "installed/$id/$revision")
    }
    private fun verifyRecord(record: PluginRecord): Boolean = try {
        PluginFiles.verify(installDirectory(record.id, record.installRevision), record.fileHashes)
    } catch (_: Exception) { false }
    private fun loadRecord(record: PluginRecord): InstalledPlugin {
        fun failed(code: PluginErrorCode) = InstalledPlugin(record.copy(enabled = false, trustHash = null), PluginStatus.INVALID,
            errors = listOf(PluginError(code)))
        if (!verifyRecord(record)) return failed(PluginErrorCode.CONTENT_CHANGED)
        return try {
            val dir = installDirectory(record.id, record.installRevision)
            val manifest = when (val result = PluginManifestParser.parse(PluginFiles.readRegularText(dir, "plugin.json", PluginManifestParser.MAX_MANIFEST_BYTES))) {
                is ManifestParseResult.Failure -> return InstalledPlugin(record.copy(enabled = false, trustHash = null), PluginStatus.INVALID, errors = result.errors)
                is ManifestParseResult.Success -> result.manifest
            }
            if (manifest.id != record.id || manifest.version.toString() != record.version) return failed(PluginErrorCode.CONTENT_CHANGED)
            when (val result = PluginContentLoader.load(dir, manifest)) {
                is ContentLoadResult.Failed -> InstalledPlugin(record.copy(enabled = false, trustHash = null), PluginStatus.INVALID, manifest, errors = result.errors)
                is ContentLoadResult.Ok -> if (!verifyRecord(record)) failed(PluginErrorCode.CONTENT_CHANGED)
                    else InstalledPlugin(record, if (record.enabled && record.trustHash == record.contentHash) PluginStatus.ENABLED else PluginStatus.DISABLED, manifest, result.content)
            }
        } catch (_: Exception) { failed(PluginErrorCode.CONTENT_INVALID) }
    }
    private fun prunePreviews() {
        val expired = previews.filterValues { clock() >= it.preview.expiresAt }.keys.toList()
        expired.forEach { token -> previews.remove(token)?.let { PluginFiles.deleteOwned(it.pkg.dir) } }
    }
    private fun storageError(e: Exception) = PluginError(PluginErrorCode.STORAGE_FAILED, e.javaClass.simpleName)
    private fun operationFailure(code: PluginErrorCode) = PluginOperationResult.Failure(listOf(PluginError(code)))
    private fun risks(manifest: PluginManifest) = PluginRiskSummary(manifest.runtime != null,
        manifest.runtime?.command != null, manifest.runtime?.dependencies != null,
        manifest.configPage != null || manifest.workspaceTools.isNotEmpty() || manifest.uiEntries.any { it.page != null },
        manifest.tools.isNotEmpty(), manifest.skills.isNotEmpty(), manifest.agents.isNotEmpty(),
        manifest.hooks.isNotEmpty(), manifest.mcp.isNotEmpty(), manifest.providers.isNotEmpty(),
        manifest.sessionSources.isNotEmpty(), java.util.Collections.unmodifiableSet(PluginCapability.entries.filter {
            manifest.capabilityPurpose(it) != null
        }.toSet()))
    @OptIn(InternalCoroutinesApi::class)
    private class Projection<T>(val source: StateFlow<PluginSnapshot>, val select: (PluginSnapshot) -> T) : StateFlow<T> {
        override val value: T get() = select(source.value)
        override val replayCache: List<T> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<T>): Nothing {
            var initialized = false
            var previous: Any? = null
            return source.collect(object : FlowCollector<PluginSnapshot> {
                override suspend fun emit(value: PluginSnapshot) {
                    val selected = select(value)
                    if (!initialized || previous != selected) {
                        initialized = true
                        previous = selected
                        collector.emit(selected)
                    }
                }
            })
        }
    }
    companion object {
        private fun androidPluginRoot(filesDir: File, aicodeDir: File): File {
            val parent = filesDir.absoluteFile.toPath().normalize()
            val child = aicodeDir.absoluteFile.toPath().normalize()
            require(child.startsWith(parent) && child != parent)
            // Only Android's trusted filesDir may resolve system aliases; plugin-controlled descendants remain no-follow.
            return File(File(filesDir.canonicalFile, parent.relativize(child).toString()), "plugins")
        }
        // Repacked packages may approach the extracted 32 MiB limit; 2 MiB bounds ZIP headers, names and deflate overhead.
        internal const val BACKUP_MAX_ARCHIVE_BYTES = 34L * 1024 * 1024
        const val CLEANUP_TTL = 5 * 1000L
        const val PREVIEW_TTL = 10 * 60 * 1000L
        const val TRUST_TTL = 2 * 60 * 1000L
    }
}
