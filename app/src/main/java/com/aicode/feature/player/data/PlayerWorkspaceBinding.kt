package com.aicode.feature.player.data

import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.AgentConfigSnapshot
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.InternalLocalFolderAccess
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.identityCheckedRead
import com.aicode.feature.workspace.domain.isLocalFolderPath
import com.aicode.feature.workspace.domain.parseLocalFolderPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal interface PlayerFileExecution {
    val access: FileAccessProvider
    val identity: String
    fun checkCurrent()
    fun <T> run(block: () -> T): T
    fun checkPlaybackPath(path: String, root: String) {
        (access as? RemoteSftpFileAccess)?.checkPlaybackPath(path, root, identity)
    }
    fun openPlaybackRead(path: String, root: String): InputStream =
        (access as? RemoteSftpFileAccess)?.openPlaybackRead(path, root, identity) ?: access.openRead(path, identity)
    fun displayedFile(file: File): String = when (val backend = access) {
        is LocalFileAccess -> backend.toDisplayPath(file.canonicalFile)
        else -> backend.toDisplayPath(file.canonicalPath)
    }
    fun resolveLocalEntry(file: File): File = when (val backend = access) {
        is LocalFileAccess -> backend.copyToLocal(backend.toDisplayPath(file))
        else -> file
    }
}

internal class PlayerWorkspaceBinding(
    private val execution: WorkspaceExecutionContext,
    private val capture: ProjectMemoryCapture,
    private val owner: Job,
    private val checkWorkspace: () -> Unit
) : PlayerFileExecution {
    private val routedAccess = PlayerCapturedFileAccess(execution, owner, ::checkCurrent)
    override val access: FileAccessProvider get() = routedAccess
    override val identity: String = "${execution.fileCacheIdentity}:local-folders:${execution.localFolderSnapshot.revision}"
    val namespace: String = playerStateNamespace(capture.identity)

    override fun checkCurrent() {
        owner.ensureActive()
        checkWorkspace()
        execution.checkCurrent()
        routedAccess.checkLocalFolders()
    }

    override fun checkPlaybackPath(path: String, root: String) = routedAccess.checkPlaybackPath(path, root)
    override fun openPlaybackRead(path: String, root: String): InputStream = routedAccess.openPlaybackRead(path, root)
    override fun displayedFile(file: File): String = routedAccess.displayedFile(file)
    override fun resolveLocalEntry(file: File): File = routedAccess.resolveLocalEntry(file)

    // Executor threads do not inherit coroutine thread-local workspace mappings.
    override fun <T> run(block: () -> T): T = runBlocking(execution.ownedBlockingContext(owner)) {
        checkCurrent()
        capture.assertCurrent()
        block().also { checkCurrent(); capture.assertCurrent() }
    }

    companion object {
        suspend fun capture(repository: WorkspaceRepository, resolver: ProjectMemoryIdentityResolver,
            factory: WorkspaceExecutionContextFactory, owner: Job, root: String,
            runtimeOwner: RuntimeOwner?, checkSource: (() -> Unit)? = null): PlayerWorkspaceBinding? {
            val workspace = repository.current.value?.takeIf { it.available } ?: return null
            val selection = repository.selection.value
            fun checkWorkspace() {
                checkSource?.invoke()
                if (repository.selection.value !== selection || repository.current.value != workspace) {
                    throw CancellationException("Player workspace changed")
                }
            }
            checkWorkspace()
            val capture = resolver.capture(workspace.path) ?: return null
            val execution = factory.freezeForPaths(listOf(root), capture, runtimeOwner, ::checkWorkspace)
            return PlayerWorkspaceBinding(execution, capture, owner, ::checkWorkspace)
                .also { it.checkCurrent(); capture.assertCurrent() }
        }
    }
}

private class PlayerCapturedFileAccess(
    private val execution: WorkspaceExecutionContext,
    private val owner: Job,
    private val check: () -> Unit,
) : FileAccessProvider {
    private val snapshot = execution.localFolderSnapshot
    private val hostUsed = AtomicBoolean()
    private val host = InternalLocalFolderAccess(snapshot, execution, check)
    private val guestRoots = mutableMapOf<String, ChrootFileAccess.BoundedReadRoot>()

    private fun guestRoot(backend: ChrootFileAccess, root: String): ChrootFileAccess.BoundedReadRoot =
        synchronized(guestRoots) {
            if (!guestRoots.containsKey(root) && guestRoots.size >= 64) throw IOException("Player read root limit exceeded")
            guestRoots.getOrPut(root) { backend.captureReadRoot(root) }
        }

    fun checkLocalFolders() {
        if (hostUsed.get()) snapshot.checkCurrent()
    }

    private fun <T> use(path: String? = null, verifyAuthorization: Boolean = true, block: (FileAccessProvider) -> T): T =
        runBlocking(execution.ownedBlockingContext(owner)) {
            check()
            val backend = if (path != null && isLocalFolderPath(path)) {
                snapshot.rootFor(path, verifyAuthorization)
                hostUsed.set(true)
                host
            } else execution.fileAccess
            val result = block(backend)
            try {
                check()
                result
            } catch (failure: Throwable) {
                if (result is InputStream) runCatching { result.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

    private fun <T> pair(path: String, newPath: String, block: (FileAccessProvider) -> T): T = use(path) { backend ->
        if (isLocalFolderPath(path) != isLocalFolderPath(newPath)) {
            throw IOException("Cross-backend operations require a captured file transfer")
        }
        use(newPath) { block(backend) }
    }

    private fun checkedRead(path: String, open: (FileAccessProvider) -> InputStream): InputStream = use(path) { backend ->
        execution.protectRead(identityCheckedRead(open(backend)) { use(path, verifyAuthorization = false) {} })
    }

    private fun hostPlaybackPath(path: String, root: String): Pair<File, File> {
        if (!isLocalFolderPath(root)) throw IOException("Media path outside playback root")
        val directory = host.directoryForWatch(root).canonicalFile
        val target = host.readFileForTransfer(path).canonicalFile
        if (!target.toPath().startsWith(directory.toPath())) throw IOException("Media path outside playback root")
        return directory to target
    }

    fun checkPlaybackPath(path: String, root: String) = use(path) { backend ->
        if (backend === host) hostPlaybackPath(path, root)
        else {
            if (isLocalFolderPath(root)) throw IOException("Media path outside playback root")
            if (backend is ChrootFileAccess) guestRoot(backend, root).checkPath(path)
            else (backend as? RemoteSftpFileAccess)?.checkPlaybackPath(path, root, execution.fileCacheIdentity)
        }
        Unit
    }

    fun openPlaybackRead(path: String, root: String): InputStream = checkedRead(path) { backend ->
        if (backend === host) {
            val captured = hostPlaybackPath(path, root)
            if (!Files.isRegularFile(captured.second.toPath())) throw IOException("Existing regular media file required")
            val record = snapshot.rootFor(root, verifyAuthorization = false)
            val parsed = requireNotNull(parseLocalFolderPath(root))
            val rootEntry = File(record.directory, parsed.segments.joinToString("/"))
            identityCheckedRead(host.openRead(path)) {
                use(path, verifyAuthorization = false) {
                    snapshot.checkFileSource(record)
                    if (rootEntry.canonicalFile != captured.first || !captured.first.isDirectory) {
                        throw CancellationException("Media read root changed")
                    }
                }
            }
        } else {
            if (isLocalFolderPath(root)) throw IOException("Media path outside playback root")
            if (backend is ChrootFileAccess) guestRoot(backend, root).openRead(path, 512L * 1024 * 1024)
            else (backend as? RemoteSftpFileAccess)?.openPlaybackRead(path, root, execution.fileCacheIdentity)
                ?: backend.openRead(path, execution.fileCacheIdentity)
        }
    }

    fun displayedFile(file: File): String = use { backend ->
        when (backend) {
            is LocalFileAccess -> backend.toDisplayPath(file.canonicalFile)
            else -> backend.toDisplayPath(file.canonicalPath)
        }
    }

    fun resolveLocalEntry(file: File): File = use { backend ->
        when (backend) {
            is LocalFileAccess -> backend.copyToLocal(backend.toDisplayPath(file))
            else -> file
        }
    }

    override fun cacheIdentity(): String = use { execution.fileCacheIdentity }
    override fun agentConfigSourceIdentity(): String = use { it.agentConfigSourceIdentity() }
    override fun agentConfigSnapshot(root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot? =
        use(root) { it.agentConfigSnapshot(root, kind, forceRefresh) }
    override fun invalidateAgentConfig(path: String) = use(path) { it.invalidateAgentConfig(path) }
    override fun openRead(path: String): InputStream = openRead(path, execution.fileCacheIdentity)
    override fun openRead(path: String, expectedCacheIdentity: String): InputStream = checkedRead(path) { backend ->
        if (expectedCacheIdentity != execution.fileCacheIdentity) throw CancellationException("Player file source changed")
        backend.openRead(path, if (backend === host) host.cacheIdentity() else expectedCacheIdentity)
    }
    override fun readFile(path: String): String = use(path) { it.readFile(path) }
    override fun readLines(path: String): Sequence<String> {
        val source = use(path) { it.readLines(path) }
        return sequence {
            val iterator = use(path) { source.iterator() }
            while (use(path, verifyAuthorization = false) { iterator.hasNext() }) {
                yield(use(path, verifyAuthorization = false) { iterator.next() })
            }
        }
    }
    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        use(path) { it.writeFile(path, content, overwrite, encoding) }
    override fun exists(path: String): Boolean = use(path) { it.exists(path) }
    override fun isDirectory(path: String): Boolean = use(path) { it.isDirectory(path) }
    override fun isFile(path: String): Boolean = use(path) { it.isFile(path) }
    override fun fileSize(path: String): Long = use(path) { it.fileSize(path) }
    override fun lastModified(path: String): Long = use(path) { it.lastModified(path) }
    override fun fingerprint(path: String): String? = use(path) { it.fingerprint(path) }
    override fun permissions(path: String): String = use(path) { it.permissions(path) }
    override fun listFiles(path: String): List<FileEntry> = use(path) { it.listFiles(path) }
    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = use(path) { it.listFilesRecursive(path, maxDepth) }
    override fun readBytes(path: String): ByteArray = use(path) { it.readBytes(path) }
    override fun readBytes(path: String, maxBytes: Long): ByteArray = use(path) { it.readBytes(path, maxBytes) }
    override fun resolvePath(path: String, followFinalLink: Boolean): String = use(path) { it.resolvePath(path, followFinalLink) }
    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) = use(path) { it.writeBytes(path, bytes, overwrite) }
    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        use(path) { it.writeStream(path, identityCheckedRead(input) { use(path) {} }, overwrite) }
    override fun copyToLocal(path: String): File = use(path) { it.copyToLocal(path) }
    override fun delete(path: String) = use(path) { it.delete(path) }
    override fun deleteRecursively(path: String) = use(path) { it.deleteRecursively(path) }
    override fun rename(path: String, newPath: String) = pair(path, newPath) { it.rename(path, newPath) }
    override fun copy(path: String, newPath: String, overwrite: Boolean) = pair(path, newPath) { it.copy(path, newPath, overwrite) }
    override fun move(path: String, newPath: String, overwrite: Boolean) = pair(path, newPath) { it.move(path, newPath, overwrite) }
    override fun mkdirs(path: String) = use(path) { it.mkdirs(path) }
    override fun parentPath(path: String): String? = use(path) { it.parentPath(path) }
    override fun toDisplayPath(path: String): String = use(path) { it.toDisplayPath(path) }
}

internal fun playerStateNamespace(identity: ProjectMemoryIdentity): String {
    val normalized = ProjectMemoryIdentityResolver.normalize(identity)
    val fields = listOf(normalized.mode, normalized.profileId, normalized.host,
        normalized.port.toString(), normalized.username, normalized.projectRoot)
    return stateNamespaceHash(fields.joinToString("") { "${it.length}:$it" })
}

internal fun stateNamespaceHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
