package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileBackendRouting @Inject constructor() {
    fun capture(
        execution: WorkspaceExecutionContext,
        paths: List<String>,
        checkScope: () -> Unit = {}
    ): RouteCapture {
        execution.checkCurrent()
        execution.localFolderSnapshot.checkCurrent()
        checkScope()
        return RouteCapture(execution, paths.toList(), checkScope).also { capture ->
            paths.forEach { capture.endpoint(it) }
            capture.checkCurrent()
        }
    }
}

class RouteCapture internal constructor(
    private val execution: WorkspaceExecutionContext,
    private val capturedPaths: List<String>,
    private val checkScope: () -> Unit
) {
    private val snapshot = execution.localFolderSnapshot
    private val host = InternalLocalFolderAccess(snapshot, execution, ::checkCurrent)
    private val default = CapturedFileAccess(execution.fileAccess, execution, ::checkCurrent)

    fun checkCurrent() {
        checkScope()
        execution.checkCurrent()
        snapshot.checkCurrent()
    }

    fun endpoint(path: String): RoutedFileEndpoint {
        checkCurrent()
        if (capturedPaths.none { path == it || path.startsWith(it.trimEnd('/') + "/") }) {
            throw IOException("Path was not included in the file route capture")
        }
        val local = isLocalFolderPath(path)
        val root = if (local) snapshot.rootFor(path) else null
        val backend = if (local) host else default
        val namespace = when {
            local || execution.mode == ExecutionMode.LOCAL_PROOT -> "host"
            else -> "remote:" + (execution.remoteSource?.identity?.generation?.toString() ?: execution.fileCacheIdentity)
        }
        val identity = root?.let { "local-folder:${it.grant.id}:${it.grant.epoch}:${snapshot.revision}" }
            ?: execution.fileCacheIdentity
        return RoutedFileEndpoint(backend, if (local) host else execution.fileAccess, identity, namespace, path, execution, ::checkCurrent)
    }

    fun guardMutablePath(path: String) {
        val endpoint = endpoint(path)
        if (endpoint.isLocalFolder) {
            execution.useCaptured { host.guardMutablePath(path) }
        }
        val identities = identities(endpoint)
        val roots = protectedRoots(endpoint.physicalNamespace)
        if (identities.any { candidate -> roots.any { root -> isSameOrAncestor(candidate, root) } }) {
            throw IOException("Cannot modify a file access root or its ancestor")
        }
        checkCurrent()
    }

    fun guardTransfer(source: String, target: String) {
        guardMutablePath(target)
        val from = endpoint(source)
        val to = endpoint(target)
        if (from.physicalNamespace == to.physicalNamespace) {
            val sources = identities(from)
            val targets = identities(to)
            if (sources.any { left -> targets.any { right -> isSameOrAncestor(left, right) || isSameOrAncestor(right, left) } }) {
                throw FilePathOverlapException("Source and destination overlap")
            }
        }
        checkCurrent()
    }

    private fun identities(endpoint: RoutedFileEndpoint): List<String> {
        val entry = endpoint.resolvePath(false)
        val real = try {
            endpoint.resolvePath(true)
        } catch (_: LocalFolderContainmentException) {
            entry
        } catch (_: java.nio.file.FileSystemLoopException) {
            entry
        }
        return listOf(entry, real)
    }

    private fun protectedRoots(namespace: String): List<String> {
        if (namespace == "host") {
            val local = execution.localMapping
            return snapshot.records.map { it.directory.absolutePath } + listOfNotNull(
                local?.workspaceRoot?.absolutePath, local?.rootfsRoot?.absolutePath, local?.aicodeRoot?.absolutePath
            )
        }
        return execution.useCaptured {
            listOfNotNull(execution.remoteSource?.workspaceRoot, "/").flatMap { root ->
                listOf(execution.fileAccess.resolvePath(root, false), execution.fileAccess.resolvePath(root, true))
            }
        }
    }

    private fun isSameOrAncestor(ancestor: String, descendant: String): Boolean {
        val left = ancestor.trimEnd('/').ifEmpty { "/" }
        val right = descendant.trimEnd('/').ifEmpty { "/" }
        return left == right || left == "/" && right.startsWith('/') || right.startsWith("$left/")
    }
}

class RoutedFileEndpoint internal constructor(
    private val backend: FileAccessProvider,
    private val transferAccess: FileAccessProvider,
    val identity: String,
    val physicalNamespace: String,
    val path: String,
    private val execution: WorkspaceExecutionContext,
    private val check: () -> Unit
) {
    internal val isLocalFolder: Boolean get() = backend is InternalLocalFolderAccess

    internal val provider: FileAccessProvider
        get() { check(); return backend }

    internal val transferBackend: FileAccessProvider
        get() {
            check()
            if (WorkspaceExecutionContext.current() !== execution) {
                throw CancellationException("Captured workspace context is not installed")
            }
            return transferAccess
        }

    fun resolvePath(followFinal: Boolean = true): String = execution.useCaptured {
        check()
        backend.resolvePath(path, followFinal).also { check() }
    }
}

class FilePathSource internal constructor(
    val identity: String,
    val expectedBaseIdentity: String,
    private val verifyAuthorization: () -> Unit,
    private val check: () -> Unit
) {
    fun checkCurrent() = check()
    fun checkAuthorization() = verifyAuthorization()
}

private class CapturedFileAccess(
    private val backend: FileAccessProvider,
    private val execution: WorkspaceExecutionContext,
    private val check: () -> Unit
) : FileAccessProvider {
    private fun <T> use(block: () -> T): T = execution.useCaptured {
        check()
        block().also { check() }
    }

    override fun cacheIdentity(): String = use { backend.cacheIdentity() }
    override fun agentConfigSourceIdentity(): String = use { backend.agentConfigSourceIdentity() }
    override fun agentConfigSnapshot(root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot? =
        use { backend.agentConfigSnapshot(root, kind, forceRefresh) }
    override fun invalidateAgentConfig(path: String) = use { backend.invalidateAgentConfig(path) }
    override fun openRead(path: String): java.io.InputStream = openRead(path, cacheIdentity())
    override fun openRead(path: String, expectedCacheIdentity: String): java.io.InputStream {
        var opened: java.io.InputStream? = null
        return try {
            val input = use { backend.openRead(path, expectedCacheIdentity).also { opened = it } }
            identityCheckedRead(input) { use {} }
        } catch (failure: Throwable) {
            runCatching { opened?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
    override fun readFile(path: String): String = use { backend.readFile(path) }
    override fun readLines(path: String): Sequence<String> {
        val source = use { backend.readLines(path) }
        return sequence {
            val iterator = use { source.iterator() }
            while (use { iterator.hasNext() }) yield(use { iterator.next() })
        }
    }
    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: java.nio.charset.Charset) =
        use { backend.writeFile(path, content, overwrite, encoding) }
    override fun exists(path: String): Boolean = use { backend.exists(path) }
    override fun isDirectory(path: String): Boolean = use { backend.isDirectory(path) }
    override fun isFile(path: String): Boolean = use { backend.isFile(path) }
    override fun fileSize(path: String): Long = use { backend.fileSize(path) }
    override fun lastModified(path: String): Long = use { backend.lastModified(path) }
    override fun fingerprint(path: String): String? = use { backend.fingerprint(path) }
    override fun permissions(path: String): String = use { backend.permissions(path) }
    override fun listFiles(path: String): List<FileEntry> = use { backend.listFiles(path) }
    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = use { backend.listFilesRecursive(path, maxDepth) }
    override fun readBytes(path: String): ByteArray = use { backend.readBytes(path) }
    override fun readBytes(path: String, maxBytes: Long): ByteArray = use { backend.readBytes(path, maxBytes) }
    override fun resolvePath(path: String, followFinalLink: Boolean): String = use { backend.resolvePath(path, followFinalLink) }
    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) = use { backend.writeBytes(path, bytes, overwrite) }
    override fun writeStream(path: String, input: java.io.InputStream, overwrite: Boolean): Long =
        use { backend.writeStream(path, identityCheckedRead(input) { use {} }, overwrite) }
    override fun copyToLocal(path: String): java.io.File = use { backend.copyToLocal(path) }
    override fun delete(path: String) = use { backend.delete(path) }
    override fun deleteRecursively(path: String) = use { backend.deleteRecursively(path) }
    override fun rename(path: String, newPath: String) = use { backend.rename(path, newPath) }
    override fun copy(path: String, newPath: String, overwrite: Boolean) = use { backend.copy(path, newPath, overwrite) }
    override fun move(path: String, newPath: String, overwrite: Boolean) = use { backend.move(path, newPath, overwrite) }
    override fun mkdirs(path: String) = use { backend.mkdirs(path) }
    override fun parentPath(path: String): String? = use { backend.parentPath(path) }
    override fun toDisplayPath(path: String): String = use { backend.toDisplayPath(path) }
}
