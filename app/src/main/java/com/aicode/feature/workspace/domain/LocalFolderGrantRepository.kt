package com.aicode.feature.workspace.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val LOCAL_FOLDER_PREFIX = "/mnt/.aicode-local-fs"
private val localFolderIdentityPattern = Regex("[A-Za-z0-9_-]{1,128}")

fun isLocalFolderPath(path: String): Boolean {
    val value = path.trim()
    return value == LOCAL_FOLDER_PREFIX || value.startsWith("$LOCAL_FOLDER_PREFIX/")
}

fun localRootPath(id: String, epoch: String): String {
    require(localFolderIdentityPattern.matches(id) && localFolderIdentityPattern.matches(epoch))
    return "$LOCAL_FOLDER_PREFIX/$id/$epoch"
}

fun localRootPath(path: String): String? = parseLocalFolderPath(path)?.let { localRootPath(it.id, it.epoch) }

internal data class LocalFolderPath(val id: String, val epoch: String, val segments: List<String>)

internal fun parseLocalFolderPath(path: String): LocalFolderPath? {
    if (!isLocalFolderPath(path) || path != path.trim() || '\u0000' in path || '\\' in path) return null
    val components = path.removePrefix("$LOCAL_FOLDER_PREFIX/").split('/')
    if (components.size < 2 || !localFolderIdentityPattern.matches(components[0]) ||
        !localFolderIdentityPattern.matches(components[1])) return null
    val children = components.drop(2)
    if (children.dropLastWhile { it.isEmpty() }.any { it.isEmpty() }) return null
    return LocalFolderPath(components[0], components[1], children.filter { it.isNotEmpty() })
}

@Serializable
enum class LocalFolderUnavailableReason {
    UNSUPPORTED_PROVIDER,
    PERSISTENT_PERMISSION_REQUIRED,
    PATH_UNAVAILABLE,
    READ_WRITE_REQUIRED,
    PROBE_FAILED
}

class LocalFolderGrantException(val reason: LocalFolderUnavailableReason) : IOException(reason.name)

data class LocalFolderGrant(
    val id: String,
    val epoch: String,
    val label: String,
    val rootPath: String,
    val available: Boolean,
    val reason: LocalFolderUnavailableReason? = null
)

internal data class CapturedLocalFolderRoot(val grant: LocalFolderGrant, val directory: File)

class LocalFolderSnapshot internal constructor(
    val revision: Long,
    roots: List<LocalFolderGrant>,
    records: List<CapturedLocalFolderRoot>,
    private val checkAuthorization: (CapturedLocalFolderRoot) -> Unit = {},
    private val checkFileSource: (CapturedLocalFolderRoot) -> Unit = {},
    private val check: () -> Unit
) {
    val roots: List<LocalFolderGrant> = Collections.unmodifiableList(roots.toList())
    internal val records: List<CapturedLocalFolderRoot> = Collections.unmodifiableList(records.toList())

    fun checkCurrent() = check()

    internal fun rootFor(path: String, verifyAuthorization: Boolean = true): CapturedLocalFolderRoot {
        checkCurrent()
        val parsed = parseLocalFolderPath(path) ?: throw IOException("Invalid local folder path")
        val root = records.firstOrNull { it.grant.id == parsed.id && it.grant.epoch == parsed.epoch }
            ?: throw CancellationException("Local folder authorization changed")
        if (!root.grant.available) throw LocalFolderGrantException(
            root.grant.reason ?: LocalFolderUnavailableReason.PATH_UNAVAILABLE
        )
        if (verifyAuthorization) checkAuthorization(root) else checkFileSource(root)
        checkCurrent()
        return root
    }

    internal fun checkFileSource(root: CapturedLocalFolderRoot) {
        checkCurrent()
        checkFileSource.invoke(root)
        checkCurrent()
    }

    internal fun checkAuthorization(root: CapturedLocalFolderRoot) {
        checkCurrent()
        checkAuthorization.invoke(root)
        checkCurrent()
    }

    companion object {
        val EMPTY = LocalFolderSnapshot(0, emptyList(), emptyList()) {}
    }
}

@Serializable
internal data class StoredLocalFolderGrant(
    val id: String,
    val epoch: String,
    val uri: String,
    val label: String,
    val documentPath: String,
    val canonicalRoot: String,
    val sourceIdentity: String,
    val fileIdentity: String,
    val available: Boolean = false,
    val reason: LocalFolderUnavailableReason? = null
)

internal interface LocalFolderGrantStore {
    suspend fun read(): List<StoredLocalFolderGrant>
    suspend fun write(roots: List<StoredLocalFolderGrant>)
}

internal interface LocalFolderGrantPlatform {
    fun resolve(uri: Uri): File?
    fun resolvePersisted(uri: String): File?
    fun persistReadWrite(uri: Uri)
    fun hasReadWrite(uri: String): Boolean
    fun sourceIdentity(root: File, uri: String): String
    fun fileIdentity(root: File): String
    fun checkDirectory(root: File, probe: Boolean): LocalFolderUnavailableReason?
}

@Singleton
class LocalFolderGrantRepository internal constructor(
    private val store: LocalFolderGrantStore,
    private val platform: LocalFolderGrantPlatform,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        AndroidLocalFolderGrantStore(context), AndroidLocalFolderGrantPlatform(context)
    )

    private val mutex = Mutex()
    private val stateLock = Any()
    private var initialized = false
    private var stored: List<StoredLocalFolderGrant> = emptyList()
    private val _snapshots = MutableStateFlow(makeSnapshot(0, emptyList()))
    val snapshots: StateFlow<LocalFolderSnapshot> = _snapshots.asStateFlow()
    val roots: StateFlow<List<LocalFolderGrant>> = SnapshotValue(snapshots) { it.roots }
    val revision: StateFlow<Long> = SnapshotValue(snapshots) { it.revision }

    fun snapshot(): LocalFolderSnapshot = snapshots.value

    suspend fun awaitReady() = withContext(Dispatchers.IO) {
        mutex.withLock { if (!initialized) loadAndRefresh() }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { loadAndRefresh() }
    }

    suspend fun add(uri: Uri): LocalFolderGrant = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents") {
            throw LocalFolderGrantException(LocalFolderUnavailableReason.UNSUPPORTED_PROVIDER)
        }
        val document = try { platform.resolve(uri) } catch (_: IllegalArgumentException) { null }
            ?: throw LocalFolderGrantException(LocalFolderUnavailableReason.UNSUPPORTED_PROVIDER)
        val canonical = try { document.canonicalFile } catch (_: IOException) {
            throw LocalFolderGrantException(LocalFolderUnavailableReason.PATH_UNAVAILABLE)
        }
        mutex.withLock {
            if (!initialized) loadAndRefresh()
            val permissionFailure = try {
                platform.persistReadWrite(uri)
                if (platform.hasReadWrite(uri.toString())) null else LocalFolderUnavailableReason.PERSISTENT_PERMISSION_REQUIRED
            } catch (_: SecurityException) {
                LocalFolderUnavailableReason.PERSISTENT_PERMISSION_REQUIRED
            }
            val previous = synchronized(stateLock) { stored.firstOrNull { it.uri == uri.toString() } }
            val record = StoredLocalFolderGrant(
                id = previous?.id ?: UUID.randomUUID().toString(),
                epoch = UUID.randomUUID().toString(),
                uri = uri.toString(),
                label = document.name.ifEmpty { canonical.name.ifEmpty { "/" } },
                documentPath = document.absolutePath,
                canonicalRoot = canonical.absolutePath,
                sourceIdentity = if (permissionFailure == null) try {
                    platform.sourceIdentity(canonical, uri.toString())
                } catch (_: IOException) { "" } catch (_: SecurityException) { "" } else "",
                fileIdentity = try { platform.fileIdentity(canonical) } catch (_: IOException) { "" } catch (_: SecurityException) { "" }
            )
            val reason = permissionFailure ?: status(record, probe = true)
            val next = record.copy(available = reason == null, reason = reason)
            val records = synchronized(stateLock) { stored.filterNot { it.id == next.id } + next }
            persistAndPublish(records)
            snapshot().roots.first { it.id == next.id }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) loadAndRefresh()
            val next = synchronized(stateLock) { stored.filterNot { it.id == id } }
            persistAndPublish(next)
        }
    }

    private suspend fun loadAndRefresh() {
        val previous = synchronized(stateLock) { if (initialized) stored else null } ?: store.read()
        val next = previous.map { record ->
            val reason = status(record, probe = true)
            record.copy(
                epoch = if (!record.available && reason == null) UUID.randomUUID().toString() else record.epoch,
                available = reason == null,
                reason = reason
            )
        }
        persistAndPublish(next)
    }

    private suspend fun persistAndPublish(records: List<StoredLocalFolderGrant>) {
        var pending = records
        while (true) {
            store.write(pending)
            val complete = synchronized(stateLock) {
                val current = stored.associateBy { it.id }
                val next = pending.map { record ->
                    val latest = current[record.id]
                    if (latest != null && latest.epoch == record.epoch && !latest.available && record.available) {
                        record.copy(available = false, reason = latest.reason)
                    } else record
                }
                if (next == pending) { publish(pending); true } else { pending = next; false }
            }
            if (complete) return
        }
    }

    private fun status(record: StoredLocalFolderGrant, probe: Boolean): LocalFolderUnavailableReason? = try {
        when {
            !localFolderIdentityPattern.matches(record.id) || !localFolderIdentityPattern.matches(record.epoch) ->
                LocalFolderUnavailableReason.PATH_UNAVAILABLE
            platform.resolvePersisted(record.uri)?.absolutePath != record.documentPath -> LocalFolderUnavailableReason.UNSUPPORTED_PROVIDER
            !platform.hasReadWrite(record.uri) -> LocalFolderUnavailableReason.PERSISTENT_PERMISSION_REQUIRED
            File(record.documentPath).canonicalPath != record.canonicalRoot -> LocalFolderUnavailableReason.PATH_UNAVAILABLE
            record.sourceIdentity.isEmpty() || record.fileIdentity.isEmpty() ||
                platform.sourceIdentity(File(record.canonicalRoot), record.uri) != record.sourceIdentity ->
                LocalFolderUnavailableReason.PATH_UNAVAILABLE
            else -> platform.checkDirectory(File(record.canonicalRoot), probe)
        }
    } catch (_: SecurityException) {
        LocalFolderUnavailableReason.READ_WRITE_REQUIRED
    } catch (_: IOException) {
        LocalFolderUnavailableReason.PATH_UNAVAILABLE
    }

    private fun publish(records: List<StoredLocalFolderGrant>) = synchronized(stateLock) {
        if (initialized && stored == records) return@synchronized
        stored = records.toList()
        initialized = true
        _snapshots.value = makeSnapshot(_snapshots.value.revision + 1, stored)
    }

    private fun makeSnapshot(revision: Long, records: List<StoredLocalFolderGrant>): LocalFolderSnapshot =
        LocalFolderSnapshot(revision, records.map { it.toGrant() }, records.map {
            CapturedLocalFolderRoot(it.toGrant(), File(it.canonicalRoot))
        }, checkAuthorization = { root ->
            val record = records.first { it.id == root.grant.id && it.epoch == root.grant.epoch }
            val reason = status(record, probe = false)
            if (reason != null) {
                invalidate(record, reason)
                throw CancellationException("Local folder authorization is unavailable")
            }
        }, checkFileSource = { root ->
            val record = records.first { it.id == root.grant.id && it.epoch == root.grant.epoch }
            val matches = try {
                File(record.documentPath).canonicalPath == record.canonicalRoot &&
                    record.fileIdentity.isNotEmpty() && platform.fileIdentity(root.directory) == record.fileIdentity
            } catch (_: IOException) { false } catch (_: SecurityException) { false }
            if (!matches) {
                invalidate(record, LocalFolderUnavailableReason.PATH_UNAVAILABLE)
                throw CancellationException("Local folder source changed")
            }
        }) {
            synchronized(stateLock) {
                if (!initialized || _snapshots.value.revision != revision) {
                    throw CancellationException("Local folder registry changed")
                }
            }
        }

    private fun invalidate(record: StoredLocalFolderGrant, reason: LocalFolderUnavailableReason) {
        synchronized(stateLock) {
            if (stored.none { it == record }) return
            publish(stored.map { if (it == record) it.copy(available = false, reason = reason) else it })
        }
        scope.launch {
            try {
                mutex.withLock { store.write(synchronized(stateLock) { stored.toList() }) }
            } catch (failure: IOException) {
                FileLogger.w("LocalFolderGrantRepository", "Failed to persist local folder authorization state", failure)
            }
        }
    }

    private fun StoredLocalFolderGrant.toGrant() = LocalFolderGrant(
        id, epoch, label, localRootPath(id, epoch), available, reason
    )
}

@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
private class SnapshotValue<T>(
    private val source: StateFlow<LocalFolderSnapshot>,
    private val select: (LocalFolderSnapshot) -> T
) : StateFlow<T> {
    override val value: T get() = select(source.value)
    override val replayCache: List<T> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<T>): Nothing = source.collect(object : FlowCollector<LocalFolderSnapshot> {
        private var first = true
        private var previous: T? = null
        override suspend fun emit(value: LocalFolderSnapshot) {
            val next = select(value)
            if (first || previous != next) {
                first = false
                previous = next
                collector.emit(next)
            }
        }
    })
}

private val Context.localFolderGrantDataStore by preferencesDataStore(
    name = "local_folder_grants", corruptionHandler = preferencesCorruptionHandler
)

private class AndroidLocalFolderGrantStore(private val context: Context) : LocalFolderGrantStore {
    private val key = stringPreferencesKey("roots")
    private val serializer = ListSerializer(StoredLocalFolderGrant.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun read(): List<StoredLocalFolderGrant> = context.localFolderGrantDataStore.data.first()[key]
        ?.let { json.decodeFromString(serializer, it) } ?: emptyList()

    override suspend fun write(roots: List<StoredLocalFolderGrant>) {
        context.localFolderGrantDataStore.edit { it[key] = json.encodeToString(serializer, roots) }
    }
}

private class AndroidLocalFolderGrantPlatform(private val context: Context) : LocalFolderGrantPlatform {
    override fun resolve(uri: Uri): File? {
        if (uri.scheme != "content" || uri.authority != "com.android.externalstorage.documents" ||
            !DocumentsContract.isTreeUri(uri)) return null
        return UriPathResolver.toFilePath(context, uri)?.let(::File)
    }

    override fun resolvePersisted(uri: String): File? = try {
        resolve(Uri.parse(uri))
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun persistReadWrite(uri: Uri) = context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )

    override fun hasReadWrite(uri: String): Boolean = context.contentResolver.persistedUriPermissions.any {
        it.uri.toString() == uri && it.isReadPermission && it.isWritePermission
    }

    override fun sourceIdentity(root: File, uri: String): String {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri.toString() == uri && it.isReadPermission && it.isWritePermission
        } ?: throw IOException("Local folder permission is unavailable")
        return "${permission.persistedTime}:${fileIdentity(root)}"
    }

    override fun fileIdentity(root: File): String =
        Files.readAttributes(root.toPath(), BasicFileAttributes::class.java).fileKey()?.toString()
            ?: try {
                Os.lstat(root.absolutePath).let { "${it.st_dev}:${it.st_ino}" }
            } catch (failure: ErrnoException) {
                throw IOException("Local folder source identity is unavailable", failure)
            }

    override fun checkDirectory(root: File, probe: Boolean): LocalFolderUnavailableReason? {
        if (!root.isDirectory) return LocalFolderUnavailableReason.PATH_UNAVAILABLE
        if (!root.canRead() || !root.canWrite() || !root.canExecute()) return LocalFolderUnavailableReason.READ_WRITE_REQUIRED
        if (!probe) return null
        var temporary: File? = null
        return try {
            if (root.list() == null) return LocalFolderUnavailableReason.READ_WRITE_REQUIRED
            temporary = File.createTempFile(".aicode-grant-", ".probe", root)
            val marker = UUID.randomUUID().toString().toByteArray()
            FileOutputStream(temporary).use { it.write(marker); it.fd.sync() }
            if (!temporary.inputStream().use { it.readBytesBounded(marker.size.toLong()) }.contentEquals(marker)) {
                LocalFolderUnavailableReason.PROBE_FAILED
            } else if (!temporary.delete()) {
                LocalFolderUnavailableReason.PROBE_FAILED
            } else null
        } catch (_: IOException) {
            LocalFolderUnavailableReason.PROBE_FAILED
        } catch (_: SecurityException) {
            LocalFolderUnavailableReason.READ_WRITE_REQUIRED
        } finally {
            temporary?.delete()
        }
    }
}
