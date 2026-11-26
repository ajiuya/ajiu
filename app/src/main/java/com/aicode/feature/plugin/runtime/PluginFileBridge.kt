package com.aicode.feature.plugin.runtime

import android.system.ErrnoException
import android.system.Os
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.FilePathOverlapException
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.HostTransferBackend
import com.aicode.feature.workspace.domain.InternalLocalFolderAccess
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.LocalFolderContainmentException
import com.aicode.feature.workspace.domain.LocalFolderGrantException
import com.aicode.feature.workspace.domain.LocalFolderUnavailableReason
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.TransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferStat
import com.aicode.feature.workspace.domain.TransferReader
import com.aicode.feature.workspace.domain.TransferVersion
import com.aicode.feature.workspace.domain.TransferStage
import com.aicode.feature.workspace.domain.transferStream
import com.aicode.feature.workspace.domain.TransferOperation
import com.aicode.feature.workspace.domain.TransferResult
import com.aicode.feature.workspace.domain.transferVersion
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isLocalFolderPath
import com.aicode.feature.workspace.domain.localRootPath
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPException

@Singleton
class PluginFileBridge @Inject constructor(private val routing: FileBackendRouting) {
    private val commits = Mutex()

    internal suspend fun watchTarget(context: PluginInvocationContext, path: String): PluginFileWatchTarget =
        withContext(Dispatchers.IO + (context.execution ?: EmptyCoroutineContext)) {
            context.checkIntegrity()
            val request = Request.parse(context, "fs.stat", buildJsonObject { put("path", path) })
            val scope = FileScope(context, request.source.root, routing, context::checkCurrent)
            scope.watchTarget(request.source.relative)
        }

    internal suspend fun call(
        context: PluginInvocationContext,
        method: String,
        params: JsonObject,
        authorize: suspend (tool: String, arguments: JsonObject, operation: PluginApprovalOperation, details: String) -> Unit
    ): JsonElement {
        val installed = currentCoroutineContext()[WorkspaceExecutionContext]
        if (installed != null && installed !== context.execution) changed()
        return withContext(Dispatchers.IO + (context.execution ?: EmptyCoroutineContext)) {
            val owner = currentCoroutineContext()
            fun check() { owner.ensureActive(); context.checkCurrent() }
            fun integrity() { check(); context.checkIntegrity(); check() }
            try {
                integrity()
                val request = Request.parse(context, method, params)
                val scope = FileScope(context, request.source.root, routing, ::check)
                val targetScope = request.dest?.let { if (it.root == request.source.root) scope else FileScope(context, it.root, routing, ::check) }
                val source = request.source.relative
                val dest = request.dest?.relative
                val frozen = try {
                    val endpoints = scope.inspect(source, request.treeOperation) to dest?.let { requireNotNull(targetScope).inspect(it, true) }
                    scope.guard(request, targetScope)
                    Result.success(endpoints)
                } catch (failure: PluginHostException) {
                    if (failure.code != "NOT_A_DIRECTORY") throw failure
                    Result.failure(failure)
                } catch (failure: IOException) {
                    check()
                    Result.failure(failure)
                }
                if (request.mutable || request.source.root != DATA) {
                    val previous = frozen.getOrNull()?.first?.leaf
                    val arguments = buildJsonObject {
                        put("method", method)
                        put("path", (request.dest ?: request.source).logical)
                        put("root", (request.dest ?: request.source).root)
                        put("operation", request.operation.name)
                        if (dest != null) { put("from", request.source.logical); put("to", requireNotNull(request.dest).logical) }
                        request.bytes?.let { put("bytes", it.size) }
                        if (request.mutable) put("overwrite", request.overwrite || request.bytes != null && previous != null)
                    }
                    val details = buildJsonObject {
                        put("method", method)
                        if (dest != null) {
                            put("from", scope.display(source)); put("to", requireNotNull(targetScope).display(dest))
                        } else put("path", scope.display(source))
                        (request.bytes?.size?.toLong() ?: previous?.size)?.let { put("bytes", it) }
                        if (request.bytes != null) previous?.size?.let { put("replacesBytes", it) }
                    }.toString()
                    if (details.length > PluginApprovalCoordinator.MAX_DETAILS) {
                        throw PluginHostException("APPROVAL_TOO_LARGE", "File targets cannot be displayed in full")
                    }
                    authorize(request.tool, arguments, request.operation, details)
                }
                fun execute(): JsonObject {
                    integrity()
                    val (before, destination) = frozen.getOrThrow()
                    fun verify(stagingParent: String? = null) {
                        check()
                        scope.guard(request, targetScope)
                        if (!before.matches(scope.inspect(source, request.treeOperation), stagingParent) ||
                            (dest != null && !requireNotNull(destination).matches(requireNotNull(targetScope).inspect(dest, true), stagingParent))) changed()
                        scope.validate(request, before, destination)
                    }
                    verify()
                    val sourceTree = if (request.treeOperation && before.leaf != null) scope.freezeTree(source) else null
                    val destinationTree = if (request.treeOperation && dest != null && destination?.leaf != null) requireNotNull(targetScope).freezeTree(dest) else null
                    fun commit(stagingParent: String?) { integrity(); verify(stagingParent) }
                    val result = when (method) {
                        "fs.stat" -> buildJsonObject {
                            put("exists", before.leaf != null); put("isDir", before.leaf?.kind == TransferKind.DIRECTORY)
                            put("size", before.leaf?.size ?: 0L)
                        }
                        "fs.list" -> scope.list(source)
                        "fs.read", "fs.readBinary" -> {
                            val bytes = scope.read(source, requireNotNull(before.leaf), MAX_BYTES)
                            buildJsonObject {
                                if (method == "fs.readBinary") put("base64", Base64.getEncoder().encodeToString(bytes))
                                else put("content", bytes.toString(Charsets.UTF_8))
                                put("size", bytes.size)
                            }
                        }
                        "fs.write", "fs.writeBinary", "fs.append" -> {
                            val input = requireNotNull(request.bytes)
                            val bytes = if (method == "fs.append" && before.leaf != null)
                                scope.read(source, before.leaf, MAX_BYTES - input.size) + input else input
                            scope.publish(source, before, bytes, ::commit); ok()
                        }
                        "fs.mkdir" -> { if (before.leaf == null) scope.publish(source, before, null, ::commit); ok() }
                        "fs.copy", "fs.rename" -> {
                            scope.transfer(source, requireNotNull(targetScope), requireNotNull(dest), requireNotNull(destination),
                                requireNotNull(sourceTree), destinationTree, method == "fs.rename", ::commit)
                            ok()
                        }
                        "fs.delete" -> { scope.deleteTree(source, requireNotNull(sourceTree), ::commit); ok() }
                        else -> unsupported()
                    }
                    if (!request.mutable) verify()
                    integrity()
                    return result
                }
                if (request.mutable) commits.withLock { execute() } else execute()
            } catch (failure: IOException) {
                check()
                throw when (failure) {
                    is LocalFolderGrantException -> if (failure.reason == LocalFolderUnavailableReason.READ_WRITE_REQUIRED)
                        PluginHostException("FILE_READ_ONLY", "The device folder does not grant read and write access") else forbiddenError()
                    is java.nio.file.AccessDeniedException -> PluginHostException("FILE_ACCESS_DENIED", "The filesystem denied access")
                    is LocalFolderContainmentException, is java.nio.file.FileSystemLoopException -> forbiddenError()
                    is java.nio.file.FileAlreadyExistsException, is kotlin.io.FileAlreadyExistsException,
                    is FilePathOverlapException -> PluginHostException("PATH_CONFLICT", "The source and destination conflict")
                    is java.nio.file.NoSuchFileException, is kotlin.io.NoSuchFileException -> missingError()
                    is java.nio.file.DirectoryNotEmptyException -> treeError()
                    is java.nio.file.NotDirectoryException -> notDirectory()
                    is java.nio.file.AtomicMoveNotSupportedException -> PluginHostException("ATOMIC_WRITE_UNSUPPORTED", "The backend cannot safely replace this file")
                    is FileReadTooLargeException -> sizeError()
                    is SFTPException -> when (failure.statusCode) {
                        StatusCode.NO_SUCH_FILE, StatusCode.NO_SUCH_PATH -> missingError()
                        StatusCode.FILE_ALREADY_EXISTS -> conflict()
                        StatusCode.DIR_NOT_EMPTY -> treeError()
                        StatusCode.NOT_A_DIRECTORY -> notDirectory()
                        StatusCode.LINK_LOOP -> forbiddenError()
                        StatusCode.PERMISSION_DENIED -> PluginHostException("FILE_ACCESS_DENIED", "The remote filesystem denied access")
                        StatusCode.WRITE_PROTECT -> PluginHostException("FILE_READ_ONLY", "The remote filesystem is read-only")
                        StatusCode.OP_UNSUPPORTED -> PluginHostException("METHOD_NOT_SUPPORTED", "The remote server does not support this operation")
                        else -> fileError()
                    }
                    else -> fileError()
                }
            } catch (_: java.nio.file.ReadOnlyFileSystemException) {
                check(); throw PluginHostException("FILE_READ_ONLY", "The filesystem is read-only")
            } catch (_: SecurityException) { check(); throw forbiddenError() }
        }
    }
}

private const val DATA = "data://"
private const val WORKSPACE = WorkspacePathMapper.CONTAINER_ROOT
private const val MAX_BYTES = 1024 * 1024
private const val MAX_TREE_BYTES = 128L * 1024 * 1024
private const val MAX_LIST = 512
private const val MAX_PATH = 4096
private const val MAX_DEPTH = 64
private const val CHUNK = 32 * 1024

private data class Name(val root: String, val relative: String) {
    val logical: String get() = if (relative.isEmpty()) root else if (root == DATA) DATA + relative else "${root.trimEnd('/')}/$relative"
}

private data class Request(val method: String, val source: Name, val dest: Name?, val bytes: ByteArray?, val overwrite: Boolean = false) {
    val treeOperation: Boolean get() = method in setOf("fs.copy", "fs.rename", "fs.delete")
    val operation: PluginApprovalOperation get() = when (method) {
        "fs.read", "fs.readBinary", "fs.list", "fs.stat" -> PluginApprovalOperation.READ_FILES
        "fs.delete" -> PluginApprovalOperation.DELETE_FILES
        else -> PluginApprovalOperation.WRITE_FILES
    }
    val mutable: Boolean get() = operation != PluginApprovalOperation.READ_FILES
    val tool: String get() = when (method) {
        "fs.read", "fs.readBinary" -> "readFile"
        "fs.list", "fs.stat" -> "list"
        else -> "writeFile"
    }

    companion object {
        fun parse(context: PluginInvocationContext, method: String, params: JsonObject): Request {
            val pair = method == "fs.rename" || method == "fs.copy"
            val keys = when (method) {
                "fs.read", "fs.readBinary", "fs.stat", "fs.list", "fs.mkdir", "fs.delete" -> setOf("path")
                "fs.write", "fs.append" -> setOf("path", "content")
                "fs.writeBinary" -> setOf("path", "base64")
                "fs.copy", "fs.rename" -> setOf("from", "to", "overwrite")
                else -> unsupported()
            }
            if (!keys.containsAll(params.keys)) invalid()
            fun string(key: String): String = (params[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
            val from = name(context, string(if (pair) "from" else "path"))
            val to = if (pair) name(context, string("to")) else null
            val overwrite = params["overwrite"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: invalid() } ?: false
            val bytes = when (method) {
                "fs.write", "fs.append" -> string("content").let {
                    if (it.length > MAX_BYTES) throw sizeError()
                    if ('\u0000' in it) invalid()
                    it.toByteArray(Charsets.UTF_8)
                }
                "fs.writeBinary" -> string("base64").let {
                    if (it.length > ((MAX_BYTES + 2) / 3) * 4) throw sizeError()
                    try { Base64.getDecoder().decode(it) } catch (_: IllegalArgumentException) { invalid("Invalid base64 content") }
                }
                else -> null
            }
            if (bytes != null && bytes.size > MAX_BYTES) throw sizeError()
            return Request(method, from, to, bytes, overwrite)
        }

        private fun name(context: PluginInvocationContext, raw: String): Name {
            if (raw.length > MAX_PATH || raw != raw.trim() || unsafeText(raw) || '\\' in raw) invalid()
            val execution = context.execution
            val mapping = execution?.localMapping
            val home = mapping?.home ?: execution?.localRuntimeCapture?.let { "/root" } ?: execution?.remoteSource?.identity?.home
            val expanded = when { raw == "~" -> home ?: noWorkspace(); raw.startsWith("~/") && !raw.startsWith(WORKSPACE) -> (home ?: noWorkspace()).trimEnd('/') + raw.removePrefix("~"); else -> raw }
            val remote = execution?.remoteSource?.workspaceRoot?.trimEnd('/')
            val workspaceAliases = listOfNotNull(remote, mapping?.home?.trimEnd('/')?.plus("/workspace"),
                execution?.localRuntimeCapture?.let { "/root/workspace" })
            val roots = listOfNotNull(mapping?.let { WorkspacePathMapper.AICODE_ROOT }) + mapping?.profile?.extraBindings.orEmpty().mapNotNull {
                it.substringAfter(':', it).trim().takeIf { path -> path.startsWith('/') && path != "/" }?.trimEnd('/')
            }
            val (root, rest) = when {
                raw.startsWith(DATA) -> DATA to raw.removePrefix(DATA)
                isLocalFolderPath(raw) -> {
                    val base = localRootPath(raw) ?: throw forbiddenError()
                    base to raw.removePrefix(base).removePrefix("/")
                }
                raw == WORKSPACE || raw.startsWith("$WORKSPACE/") -> WORKSPACE to raw.removePrefix(WORKSPACE).removePrefix("/")
                workspaceAliases.any { expanded == it || expanded.startsWith("$it/") } -> {
                    val alias = workspaceAliases.first { expanded == it || expanded.startsWith("$it/") }
                    WORKSPACE to expanded.removePrefix(alias).removePrefix("/")
                }
                expanded.startsWith('/') -> {
                    val base = roots.sortedByDescending { it.length }.firstOrNull { expanded == it || expanded.startsWith("$it/") } ?: "/"
                    base to expanded.removePrefix(base).removePrefix("/")
                }
                raw.startsWith('~') || "://" in raw -> throw forbiddenError()
                else -> WORKSPACE to raw
            }
            if (rest.startsWith('/') || "//" in rest) throw forbiddenError()
            val relative = rest.removeSuffix("/")
            if (relative.isNotEmpty()) {
                val parts = relative.split('/')
                if (parts.size > MAX_DEPTH || parts.any { !safePart(it) }) throw forbiddenError()
                if (root == DATA && parts.first().equals("dependencies", ignoreCase = true)) throw forbiddenError()
            }
            return Name(root, relative)
        }
    }
}

private data class EntryState(val parents: Map<String, TransferStat>, val leaf: TransferStat?, val firstMissing: String?) {
    fun matches(other: EntryState, stagingParent: String?): Boolean = leaf == other.leaf && firstMissing == other.firstMissing &&
        parents.keys == other.parents.keys && parents.all { (path, a) ->
            val b = other.parents.getValue(path)
            // SFTP v3 exposes no inode. Only our own staging directory's parent may change mtime at commit.
            a.kind == b.kind && a.entryIdentity == b.entryIdentity && (path == stagingParent || a.entryIdentity != null || a.modified == b.modified)
        }
}

private class FileScope(
    private val context: PluginInvocationContext,
    private val root: String,
    routing: FileBackendRouting,
    private val check: () -> Unit
) {
    private val execution = context.execution
    private val route = if (root == DATA) null else routing.capture(execution ?: noWorkspace(), listOf(root), check)
    private val endpoint = route?.endpoint(root)
    private val physicalNamespace = endpoint?.physicalNamespace ?: "host"
    private val routeIdentity = endpoint?.identity
    private val physical = if (root == DATA) context.dataDirectory.absolutePath else requireNotNull(endpoint).resolvePath(false).trimEnd('/').ifEmpty { "/" }
    private val host = endpoint?.physicalNamespace == "host"
    private val storage = context.dataDirectory.absoluteFile.parentFile?.takeIf { it.name == "data" }?.parentFile
    private val registry = storage?.parentFile?.parentFile?.resolve("plugin-registry.json")
    private val guestManagement = if (execution?.localRuntimeCapture != null && root != DATA && !host) "/root/.aicode/plugins" else null

    init {
        if (route != null && (context.workspacePath.isBlank() || unsafeText(context.workspacePath))) noWorkspace()
        if (!physical.startsWith('/') || unsafeText(physical) || Paths.get(physical).normalize().toString() != physical) throw forbiddenError()
        checkPath("")
        if (route != null && route.endpoint(root).resolvePath(true) != physical) throw forbiddenError()
    }
    private val tree = backend("")

    fun display(relative: String): String = if (root == WORKSPACE) child(physical, relative) else logical(relative)
    private fun logical(relative: String): String = Name(root, relative).logical
    private fun management(path: String): Boolean = guestManagement?.let { within(path, it) } == true || host && (within(path, context.dataDirectory.absolutePath) ||
        storage?.let { within(path, it.absolutePath) } == true || registry?.let {
            path == it.absolutePath || path == "${it.absolutePath}.install" ||
                (File(path).parentFile == it.parentFile && File(path).name.startsWith(".${it.name}."))
        } == true)

    private fun checkPath(relative: String) {
        check()
        val expected = child(physical, relative)
        if (management(expected)) throw forbiddenError()
        if (route == null) {
            try { PluginFiles.checkDirectoryChain(context.dataDirectory.toPath()) }
            catch (_: IOException) { throw PluginHostException("DATA_UNAVAILABLE", "The plugin data directory is unavailable") }
        } else {
            val path = logical(relative)
            val endpoint = route.endpoint(path)
            if (endpoint.identity != routeIdentity || endpoint.physicalNamespace != physicalNamespace) changed()
            val provider = endpoint.provider
            if (provider.resolvePath(path, false) != expected) throw forbiddenError()
        }
    }

    private fun hostFile(relative: String, follow: Boolean): File {
        val path = logical(relative)
        return when (val provider = route?.endpoint(path)?.transferBackend) {
            null -> if (relative.isEmpty()) context.dataDirectory else File(context.dataDirectory, relative)
            is InternalLocalFolderAccess -> provider.readFileForTransfer(path, follow)
            is LocalFileAccess -> provider.transferFile(path, follow)
            else -> unsupported()
        }
    }

    private fun backend(relative: String): TransferBackend {
        val path = logical(relative)
        // Use only this endpoint's safe transfer API: its general copy, delete or write method may traverse a tree or replace a shared temporary file.
        return when (val provider = route?.endpoint(path)?.transferBackend) {
            is RemoteSftpFileAccess -> provider.transferBackend(path, check)
            is ChrootFileAccess -> provider.transferBackend(path, check)
            null, is InternalLocalFolderAccess, is LocalFileAccess -> HostTransferBackend({ sub, follow -> hostFile(child(relative, sub), follow) }, check)
            else -> unsupported()
        }
    }

    private fun version(relative: String): TransferStat? {
        val stat = tree.stat(relative) ?: return null
        if (stat.entryIdentity != null || (!host && root != DATA) || stat.kind == TransferKind.LINK) return stat
        val identity = try { Os.lstat(hostFile(relative, false).absolutePath) } catch (failure: ErrnoException) {
            throw IOException("File identity unavailable", failure)
        }
        return stat.copy(entryIdentity = "${identity.st_dev}:${identity.st_ino}")
    }

    fun inspect(relative: String, allowFinalLink: Boolean = false): EntryState {
        checkPath("")
        val base = version("")?.takeIf { it.kind == TransferKind.DIRECTORY } ?: noWorkspace()
        if (relative.isEmpty()) return EntryState(emptyMap(), base, null)
        val parents = linkedMapOf("" to base)
        var prefix = ""
        for (part in relative.split('/')) {
            prefix = child(prefix, part)
            val info = version(prefix)
            if (info?.kind == TransferKind.LINK && (!allowFinalLink || prefix != relative)) throw forbiddenError()
            if (info == null || prefix == relative) {
                checkPath(relative)
                return EntryState(parents, info, if (info == null) prefix else null)
            }
            if (info.kind != TransferKind.DIRECTORY) throw notDirectory()
            parents[prefix] = info
        }
        error("Invalid file target")
    }

    fun guard(request: Request, targetScope: FileScope? = null) {
        if (!request.mutable) return
        val source = request.source.relative
        val dest = request.dest?.relative
        for (relative in listOf(source) + if (targetScope === this) listOfNotNull(dest) else emptyList()) {
            val path = child(physical, relative)
            if (relative.isEmpty() || management(path) || guestManagement?.let { within(it, path) } == true ||
                (host && listOfNotNull(context.dataDirectory, storage, registry).any { within(it.absolutePath, path) })) throw forbiddenError()
            route?.let { capture ->
                try { capture.guardMutablePath(logical(relative)) } catch (_: IOException) { check(); throw forbiddenError() }
            }
        }
        if (dest != null) {
            val target = requireNotNull(targetScope)
            if (target !== this) target.guard(Request("fs.write", requireNotNull(request.dest), null, null))
            if (physicalNamespace == target.physicalNamespace) {
                if (within(child(physical, source), child(target.physical, dest)) || within(child(target.physical, dest), child(physical, source))) throw conflict()
            }
            if (target === this) route?.guardTransfer(logical(source), logical(dest))
        }
    }

    fun validate(request: Request, before: EntryState, dest: EntryState?) {
        val leaf = before.leaf
        when (request.method) {
            "fs.stat" -> Unit
            "fs.list" -> { if (leaf == null) throw missingError(); if (leaf.kind != TransferKind.DIRECTORY) throw notDirectory() }
            "fs.mkdir" -> if (leaf != null && leaf.kind != TransferKind.DIRECTORY) throw conflict()
            "fs.read", "fs.readBinary" -> { if (leaf == null) throw missingError(); requireBytes(leaf, MAX_BYTES) }
            "fs.write", "fs.writeBinary", "fs.append" -> if (leaf != null) {
                if (leaf.kind != TransferKind.FILE) throw conflict()
                if (request.method == "fs.append") requireBytes(leaf, MAX_BYTES - requireNotNull(request.bytes).size)
            }
            "fs.delete", "fs.copy", "fs.rename" -> {
                if (leaf == null) throw missingError()
                if (dest?.leaf != null && !request.overwrite) throw conflict()
                val existing = dest?.leaf
                if (existing != null && (existing.kind == TransferKind.DIRECTORY) != (leaf.kind == TransferKind.DIRECTORY)) throw conflict()
            }
        }
    }

    fun watchTarget(relative: String): PluginFileWatchTarget {
        val initial = inspect(relative)
        if (initial.leaf != null && initial.leaf.kind != TransferKind.DIRECTORY) throw notDirectory()
        val hostDirectory = if (host || root == DATA) hostFile(relative, false) else null
        val expected = display(relative)
        return PluginFileWatchTarget(expected, hostDirectory, execution) {
            context.checkCurrent()
            val now = inspect(relative)
            if (initial.parents.keys != now.parents.keys || initial.parents.any { (path, stat) ->
                    val current = now.parents.getValue(path)
                    stat.kind != current.kind || stat.entryIdentity != current.entryIdentity
                } || initial.leaf?.entryIdentity != now.leaf?.entryIdentity && initial.leaf != null) changed()
        }
    }

    private fun names(relative: String): List<String> = tree.list(relative)

    fun list(relative: String): JsonObject {
        val entries = ArrayList<JsonObject>()
        for (name in names(relative)) {
            check()
            if (!safePart(name) || (root == DATA && relative.isEmpty() && name.equals("dependencies", ignoreCase = true))) continue
            val path = child(relative, name)
            if (management(child(physical, path))) continue
            val stat = tree.stat(path) ?: changed()
            if (stat.kind == TransferKind.LINK) continue
            entries += buildJsonObject { put("name", name); put("isDir", stat.kind == TransferKind.DIRECTORY); put("size", stat.size ?: 0L) }
            if (entries.size > MAX_LIST) break
        }
        return buildJsonObject { put("entries", JsonArray(entries.take(MAX_LIST))); put("truncated", entries.size > MAX_LIST) }
    }

    fun read(relative: String, expected: TransferStat, limit: Int): ByteArray {
        val size = requireBytes(expected, limit)
        checkPath(relative)
        val path = logical(relative)
        val remote = route?.endpoint(path)?.transferBackend as? RemoteSftpFileAccess
        val input: InputStream = if (remote == null) tree.openRead(relative) else {
            val readRoot = remote.captureReadRoot(root)
            if (readRoot.canonicalPath != physical) changed()
            remote.openRootRead(path, readRoot, limit.toLong())
        }
        return input.use {
            val out = ByteArrayOutputStream(size.toInt())
            val buffer = ByteArray(CHUNK)
            while (true) {
                check()
                val available = minOf(buffer.size, limit - out.size() + 1)
                val n = it.read(buffer, 0, available)
                check()
                if (n < 0) break
                if (n !in 1..available) throw IOException("Invalid read length")
                if (out.size() + n > limit) throw sizeError()
                out.write(buffer, 0, n)
            }
            if (out.size().toLong() != size || version(relative) != expected) changed()
            out.toByteArray()
        }
    }

    fun publish(relative: String, before: EntryState, bytes: ByteArray?, verify: (String?) -> Unit) {
        // Stage at the first absent component so a rejected or cancelled write cannot leave new parent directories.
        val base = before.firstMissing ?: relative
        val tail = relative.removePrefix(base).removePrefix("/")
        val kind = if (tail.isNotEmpty() || bytes == null) TransferKind.DIRECTORY else TransferKind.FILE
        verify(null)
        backend(base).createStage(kind).use { stage ->
            var prefix = ""
            if (tail.isNotEmpty()) for (part in tail.split('/')) {
                prefix = child(prefix, part)
                if (prefix != tail || bytes == null) { check(); stage.mkdir(prefix) }
            }
            if (bytes != null) stage.writeFile(tail) { output ->
                var offset = 0
                while (offset < bytes.size) {
                    check(); val count = minOf(CHUNK, bytes.size - offset)
                    output.write(bytes, offset, count); offset += count
                }
                check()
            }
            stage.commit(before.leaf != null) { verify(base.substringBeforeLast('/', "")) }
        }
        route?.endpoint(logical(relative))?.provider?.invalidateAgentConfig(logical(relative))
    }

    fun freezeTree(relative: String): TransferVersion {
        val reader = backend(relative)
        var bytes = 0L
        val bounded = object : TransferReader by reader {
            override fun stat(relative: String): TransferStat? = reader.stat(relative).also { stat ->
                if (stat?.kind == TransferKind.FILE && (stat.size == null || stat.size !in 0..MAX_TREE_BYTES)) throw sizeError()
            }
            override fun openRead(relative: String): InputStream = object : java.io.FilterInputStream(reader.openRead(relative)) {
                override fun read(): Int = read(one, 0, 1).let { if (it < 0) -1 else one[0].toInt() and 255 }
                private val one = ByteArray(1)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    check()
                    val count = super.read(buffer, offset, minOf(length, (MAX_TREE_BYTES - bytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
                    check()
                    if (count > 0) { bytes += count; if (bytes > MAX_TREE_BYTES) throw sizeError() }
                    return count
                }
            }
        }
        return transferVersion(bounded, check) ?: throw missingError()
    }

    fun transfer(source: String, destination: FileScope, target: String, before: EntryState, version: TransferVersion,
        expectedTarget: TransferVersion?, cut: Boolean, verify: (String?) -> Unit) {
        val base = before.firstMissing ?: target
        val tail = target.removePrefix(base).removePrefix("/")
        val raw = destination.backend(target)
        val targetBackend = object : TransferBackend by raw {
            override fun createStage(kind: TransferKind): TransferStage {
                val stage = destination.backend(base).createStage(if (tail.isEmpty()) kind else TransferKind.DIRECTORY)
                try {
                    if (tail.isNotEmpty()) {
                        var parent = ""
                        for (part in tail.split('/').dropLast(1)) { parent = child(parent, part); stage.mkdir(parent) }
                        if (kind == TransferKind.DIRECTORY) stage.mkdir(tail)
                    }
                    return object : TransferStage {
                        override val committed: Boolean get() = stage.committed
                        private fun path(relative: String) = child(tail, relative)
                        override fun stat(relative: String) = stage.stat(path(relative))
                        override fun list(relative: String) = stage.list(path(relative))
                        override fun openRead(relative: String) = stage.openRead(path(relative))
                        override fun readLink(relative: String) = stage.readLink(path(relative))
                        override fun mkdir(relative: String) = stage.mkdir(path(relative))
                        override fun writeFile(relative: String, writer: (java.io.OutputStream) -> Unit) = stage.writeFile(path(relative), writer)
                        override fun writeLink(relative: String, target: String) = stage.writeLink(path(relative), target)
                        override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) = stage.commit(overwrite) { verify(base.substringBeforeLast('/', "")); checkBeforeCommit() }
                        override fun close() = stage.close()
                    }
                } catch (failure: Throwable) {
                    runCatching { stage.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
        }
        val sourceBackend = backend(source)
        val result = TransferOperation(sourceBackend, targetBackend, logical(source), destination.logical(target), cut, check,
            { verify(base.substringBeforeLast('/', "")) }, checkAfterCommitPaths = {
                check(); checkPath(source); destination.checkPath(target)
            }).run(version, expectedTarget)
        if (result is TransferResult.CopiedButSourceNotRemoved) throw PluginHostException("COPIED_SOURCE_RETAINED", result.message)
        route?.endpoint(logical(source))?.provider?.invalidateAgentConfig(logical(source))
        destination.route?.endpoint(destination.logical(target))?.provider?.invalidateAgentConfig(destination.logical(target))
    }

    fun deleteTree(relative: String, version: TransferVersion, verify: (String?) -> Unit) {
        verify(null)
        val backend = backend(relative)
        if (freezeTree(relative) != version) changed()
        for (node in version.nodes.asReversed()) {
            check()
            checkPath(relative)
            val now = backend.stat(node.relative) ?: changed()
            if (node.stat.kind == TransferKind.DIRECTORY) {
                if (now.kind != TransferKind.DIRECTORY || now.entryIdentity != node.stat.entryIdentity || backend.list(node.relative).isNotEmpty()) changed()
            } else {
                if (now != node.stat) changed()
                if (node.stat.kind == TransferKind.LINK) {
                    if (backend.readLink(node.relative) != node.link) changed()
                } else if (backend.openRead(node.relative).use { transferStream(it, null, node.bytes, check) }.digest != node.digest) changed()
            }
            check()
            checkPath(relative)
            backend.deleteEntry(node.relative)
        }
        route?.endpoint(logical(relative))?.provider?.invalidateAgentConfig(logical(relative))
    }
}

private fun requireBytes(stat: TransferStat, limit: Int): Long {
    if (stat.kind != TransferKind.FILE) throw PluginHostException("NOT_A_FILE", "A regular file is required")
    return stat.size?.takeIf { it in 0L..limit.toLong() } ?: throw sizeError()
}
private fun child(root: String, part: String): String = if (part.isEmpty()) root else if (root.isEmpty()) part else "${root.trimEnd('/')}/$part"
private fun within(path: String, root: String): Boolean = path == root || path.startsWith("$root/")
private fun unsafeText(s: String): Boolean = s.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it == '\u2028' || it == '\u2029' }
private fun safePart(s: String): Boolean = s.isNotEmpty() && s == s.trim() && s != "." && s != ".." && ':' !in s && '/' !in s && '\\' !in s &&
    !unsafeText(s) && !s.startsWith(".aicode-transfer-", ignoreCase = true)
private fun ok(): JsonObject = buildJsonObject { put("ok", true) }
private fun changed(): Nothing = throw CancellationException("Plugin file authority or target changed")
private fun noWorkspace(): Nothing = throw PluginHostException("CONTEXT_REQUIRED", "A ready captured workspace is required")
private fun unsupported(): Nothing = throw PluginHostException("METHOD_NOT_SUPPORTED", "This file operation is not supported by the backend")
private fun invalid(reason: String = "Invalid plugin file parameters"): Nothing = throw PluginHostException("PARAM_INVALID", reason)
private fun notDirectory() = PluginHostException("NOT_A_DIRECTORY", "A directory is required")
private fun missingError() = PluginHostException("NOT_FOUND", "The requested entry does not exist")
private fun sizeError() = PluginHostException("TOO_LARGE", "Plugin file content exceeds the byte limit")
private fun treeError() = PluginHostException("DIRECTORY_CHANGED", "The directory changed during the file operation")
private fun forbiddenError() = PluginHostException("PATH_FORBIDDEN", "The file path is outside the permitted scope or uses a reserved entry or symbolic link")
private fun conflict() = PluginHostException("PATH_CONFLICT", "The file operation conflicts with an existing entry or overlapping path")
private fun fileError() = PluginHostException("FILE_IO", "The file backend could not complete this operation")
