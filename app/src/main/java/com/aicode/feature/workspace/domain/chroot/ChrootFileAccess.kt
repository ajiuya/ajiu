package com.aicode.feature.workspace.domain.chroot

import com.aicode.core.util.boundedLines
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.FilePathOverlapException
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.identityCheckedRead
import com.aicode.feature.workspace.domain.readBytesBounded
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.FileAlreadyExistsException
import kotlin.io.NoSuchFileException

class ChrootFileAccess internal constructor(
    private val captureIdentity: () -> String,
    private val sessionFactory: () -> ChrootFileSession,
    private val mapPath: (String) -> String
) : FileAccessProvider {
    internal constructor(
        captureIdentity: () -> String,
        sessionFactory: () -> ChrootFileSession
    ) : this(captureIdentity, sessionFactory, { it })

    private fun guestPath(path: String): String = Companion.guestPath(mapPath(path))

    @OptIn(InternalCoroutinesApi::class)
    internal class Operation(
        val session: ChrootFileSession,
        val execution: WorkspaceExecutionContext?,
        val job: Job?,
        private val identity: String,
        private val checkSource: () -> Unit
    ) : AutoCloseable {
        private var cancellation: DisposableHandle? = null
        private val closeLock = Any()
        private val closed = AtomicBoolean()
        private val closeFailure = AtomicReference<Throwable?>()

        private fun closeSession() = synchronized(closeLock) {
            if (closed.compareAndSet(false, true)) {
                try { session.close() } catch (failure: Throwable) { closeFailure.set(failure) }
            }
        }

        init {
            try {
                checkCurrent()
                cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                    if (cause is CancellationException) closeSession()
                }
                checkCurrent()
            } catch (failure: Throwable) {
                runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        fun checkCurrent() {
            checkSource()
            session.checkCurrent()
            if (session.identity != identity) throw CancellationException("Chroot file source changed")
            checkSource()
        }

        fun <T> request(path: String, block: (ChrootFileWorkerClient) -> T): T {
            checkCurrent()
            try {
                return block(session.client).also { checkCurrent() }
            } catch (failure: Throwable) {
                try {
                    checkCurrent()
                } catch (changed: Throwable) {
                    if (changed !== failure) changed.addSuppressed(failure)
                    throw changed
                }
                if (failure is ChrootFileOperationException) {
                    val contextual = failure.withGuestPath(path)
                    when (failure.errno) {
                        ENOENT -> throw NoSuchFileException(File(path)).apply { initCause(contextual) }
                        EEXIST -> throw FileAlreadyExistsException(File(path)).apply { initCause(contextual) }
                    }
                    throw contextual
                }
                throw failure
            }
        }

        override fun close() {
            var failure: Throwable? = null
            try { cancellation?.dispose() } catch (problem: Throwable) { failure = problem }
            closeSession()
            closeFailure.getAndSet(null)?.let { cleanup ->
                val primary = failure
                if (primary == null) failure = cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
            failure?.let { throw it }
        }
    }

    private fun capture(expectedIdentity: String? = null, ownerJob: Job? = null): Operation {
        val execution = WorkspaceExecutionContext.current()
        val job = ownerJob ?: WorkspaceExecutionContext.blockingContext()[Job]
        job?.ensureActive()
        execution?.checkCurrent()
        val identity = captureIdentity()
        fun checkSource() {
            job?.ensureActive()
            execution?.checkCurrent()
            if ((expectedIdentity != null && identity != expectedIdentity) ||
                (execution != null && identity != execution.fileCacheIdentity) ||
                captureIdentity() != identity) {
                throw CancellationException("Chroot file source changed")
            }
        }
        checkSource()
        val session = try {
            sessionFactory()
        } catch (failure: Throwable) {
            try {
                checkSource()
            } catch (changed: Throwable) {
                if (changed !== failure) changed.addSuppressed(failure)
                throw changed
            }
            throw failure
        }
        return Operation(session, execution, job, identity, ::checkSource)
    }

    private fun <T> access(block: (Operation) -> T): T = capture().use { operation ->
        block(operation).also { operation.checkCurrent() }
    }

    internal fun transferBackend(path: String, check: () -> Unit): com.aicode.feature.workspace.domain.TransferBackend {
        val execution = WorkspaceExecutionContext.current() ?: throw IOException("Captured chroot transfer execution required")
        val identity = cacheIdentity()
        val guest = guestPath(path)
        return ChrootTransferBackend(guest, {
            execution.useCaptured { capture(identity, WorkspaceExecutionContext.blockingContext()[Job]) }
        }) {
            execution.checkCurrent()
            check()
            if (captureIdentity() != identity) throw CancellationException("Chroot transfer source changed")
        }
    }

    internal data class UploadDirectoryIdentity(
        val guest: String,
        val resolved: String,
        val entry: String,
        val device: Long,
        val inode: Long,
        val entryKind: Int,
        val entryDevice: Long,
        val entryInode: Long,
        val sourceIdentity: String,
    )

    internal fun captureUploadDirectory(path: String, check: () -> Unit): UploadDirectoryValidation {
        val execution = WorkspaceExecutionContext.current() ?: throw IOException("Captured chroot upload execution required")
        check()
        val guest = guestPath(path)
        val identity = cacheIdentity()
        val operation = capture(identity, WorkspaceExecutionContext.blockingContext()[Job])
        return try {
            UploadDirectoryValidation(operation, execution, guest, identity, check)
        } catch (failure: Throwable) {
            runCatching { operation.close() }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }

    internal inner class UploadDirectoryValidation internal constructor(
        private val operation: Operation,
        private val execution: WorkspaceExecutionContext,
        private val path: String,
        private val sourceIdentity: String,
        private val check: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        val identity: UploadDirectoryIdentity = readIdentity()

        private fun checkOwner() {
            if (closed.get()) throw CancellationException("Chroot upload directory validation is closed")
            if (WorkspaceExecutionContext.current() !== execution ||
                WorkspaceExecutionContext.blockingContext()[Job] !== operation.job) {
                throw CancellationException("Chroot upload operation owner changed")
            }
            check()
            operation.checkCurrent()
        }

        private fun readIdentity(): UploadDirectoryIdentity {
            checkOwner()
            val resolved = resolvedIdentity(operation, path, true)
            val entry = resolvedIdentity(operation, path, false)
            val directory = operation.request(path) { it.stat(path, true) }
            val entryStat = operation.request(path) { it.stat(path, false) }
            if (directory.kind != DIRECTORY || (entryStat.kind != DIRECTORY && entryStat.kind != SYMLINK)) {
                throw IOException("Upload target directory does not exist")
            }
            if (!operation.session.isWritable(resolved) || !operation.session.isWritable(entry)) {
                throw IOException("Upload target directory is read-only")
            }
            checkOwner()
            return UploadDirectoryIdentity(path, resolved, entry, directory.device, directory.inode,
                entryStat.kind, entryStat.device, entryStat.inode, sourceIdentity)
        }

        fun checkCurrent() {
            if (readIdentity() != identity) throw IOException("Upload target directory changed")
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try { operation.close() } catch (failure: Throwable) {
                throw IOException("Chroot upload directory worker cleanup is UNKNOWN", failure)
            }
        }
    }

    internal fun captureReadRoot(path: String): BoundedReadRoot {
        val guest = guestPath(path)
        val identity = cacheIdentity()
        return capture(identity).use { operation ->
            val resolved = resolvedIdentity(operation, guest, true)
            if (resolved == "/") throw IOException("Bounded directory read root required")
            val stat = operation.request(guest) { it.stat(guest, true) }
            if (stat.kind != DIRECTORY) throw IOException("Readable directory root required")
            BoundedReadRoot(guest, resolved, stat, identity).also { it.checkRoot(operation) }
        }
    }

    internal inner class BoundedReadRoot internal constructor(
        private val path: String,
        private val resolved: String,
        private val stat: ChrootFileStat,
        private val identity: String,
    ) {
        internal fun checkRoot(operation: Operation) {
            operation.checkCurrent()
            val current = operation.request(path) { it.stat(path, true) }
            if (resolvedIdentity(operation, path, true) != resolved || current.kind != DIRECTORY ||
                current.device != stat.device || current.inode != stat.inode) {
                throw CancellationException("Chroot read root changed")
            }
        }

        private fun target(operation: Operation, path: String): Pair<String, ChrootFileStat> {
            checkRoot(operation)
            val target = resolvedIdentity(operation, path, true)
            if (!target.startsWith("$resolved/")) throw IOException("File outside read root")
            val metadata = operation.request(path) { it.stat(path, true) }
            if (metadata.kind != REGULAR) throw IOException("Existing regular file required")
            if (metadata.size < 0) throw IOException("Invalid chroot file size")
            if (resolvedIdentity(operation, path, true) != target) throw CancellationException("Chroot read target changed")
            checkRoot(operation)
            return target to metadata
        }

        fun checkCurrent() = capture(identity).use { checkRoot(it) }

        fun checkPath(path: String) {
            val guest = guestPath(path)
            capture(identity).use { operation ->
                checkRoot(operation)
                val target = resolvedIdentity(operation, guest, true)
                if (target != resolved && !target.startsWith("$resolved/")) throw IOException("Path outside read root")
                val metadata = operation.request(guest) { it.stat(guest, true) }
                if (metadata.kind != DIRECTORY && metadata.kind != REGULAR) throw IOException("Readable file or directory required")
                if (resolvedIdentity(operation, guest, true) != target) throw CancellationException("Chroot read target changed")
                checkRoot(operation)
            }
        }

        fun size(path: String): Long {
            val guest = guestPath(path)
            return capture(identity).use { operation -> target(operation, guest).second.size }
        }

        fun openRead(path: String, maxBytes: Long): InputStream {
            require(maxBytes in 0..MAX_STREAM_BYTES)
            val guest = guestPath(path)
            val operation = capture(identity)
            var input: InputStream? = null
            try {
                val expected = target(operation, guest)
                if (expected.second.size > maxBytes) throw FileReadTooLargeException(maxBytes)
                fun checkTarget() {
                    if (target(operation, guest) != expected) throw CancellationException("Chroot read target changed")
                }
                val opened = checkedRead(operation, guest)
                input = opened
                checkTarget()
                val closed = AtomicBoolean()
                val bounded = object : InputStream() {
                    private var count = 0L
                    private val single = ByteArray(1)
                    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
                        if (len == 0) return 0
                        val remaining = maxBytes - count
                        val requested = minOf(len.toLong(), IO_CHUNK.toLong(), remaining + 1).toInt()
                        val length = opened.read(b, off, requested)
                        if (length > remaining) throw FileReadTooLargeException(maxBytes)
                        if (length == 0) throw IOException("Chroot file read made no progress")
                        if (length > 0) count += length
                        return length
                    }
                    override fun close() {
                        if (!closed.compareAndSet(false, true)) return
                        try { opened.close() } finally { operation.close() }
                    }
                }
                val checked = identityCheckedRead(bounded, ::checkTarget)
                return operation.execution?.protectRead(checked) ?: checked
            } catch (failure: Throwable) {
                runCatching { input?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { operation.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    override fun cacheIdentity(): String = captureIdentity()

    override fun openRead(path: String): InputStream = openOwnedRead(guestPath(path), null)

    override fun openRead(path: String, expectedCacheIdentity: String): InputStream =
        openOwnedRead(guestPath(path), expectedCacheIdentity)

    private fun openOwnedRead(path: String, expectedIdentity: String?, ownerJob: Job? = null): InputStream {
        val operation = capture(expectedIdentity, ownerJob)
        return try {
            val input = checkedRead(operation, path)
            val closed = AtomicBoolean()
            val owned = object : InputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    try { input.close() } finally { operation.close() }
                }
            }
            val checked = identityCheckedRead(owned, operation::checkCurrent)
            operation.execution?.protectRead(checked) ?: checked
        } catch (failure: Throwable) {
            runCatching { operation.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun checkedRead(operation: Operation, path: String): InputStream {
        var opened: InputStream? = null
        val input = try {
            operation.request(path) { client -> client.openRead(path).also { opened = it } }
        } catch (failure: Throwable) {
            runCatching { opened?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        val translated = object : InputStream() {
            override fun read(): Int = operation.request(path) { input.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = operation.request(path) { input.read(b, off, len) }
            override fun close() = input.close()
        }
        val checked = identityCheckedRead(translated, operation::checkCurrent)
        return operation.execution?.protectRead(checked) ?: checked
    }

    override fun readFile(path: String): String = String(readBytes(path), Charsets.UTF_8)

    override fun readBytes(path: String): ByteArray = readBytes(path, MAX_READ_BYTES)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        val guest = guestPath(path)
        return access { operation ->
            val stat = operation.request(guest) { it.stat(guest) }
            if (stat.kind != REGULAR) throw IOException("Regular chroot file required")
            if (stat.size > maxBytes) throw FileReadTooLargeException(maxBytes)
            checkedRead(operation, guest).use { it.readBytesBounded(maxBytes) }
        }
    }

    override fun readLines(path: String): Sequence<String> {
        val guest = guestPath(path)
        val identity = cacheIdentity()
        val execution = WorkspaceExecutionContext.current()
        val job = WorkspaceExecutionContext.blockingContext()[Job]
        return boundedLines {
            job?.ensureActive()
            execution?.checkCurrent()
            val input = if (execution == null) openOwnedRead(guest, identity, job) else {
                execution.useCaptured { openOwnedRead(guest, identity, job) }
            }
            InputStreamReader(input, Charsets.UTF_8)
        }
    }

    private fun statOrNull(operation: Operation, path: String, followFinal: Boolean = true): ChrootFileStat? =
        operation.request(path) { client ->
            try { client.stat(path, followFinal) } catch (failure: ChrootFileOperationException) {
                if (failure.errno != ENOENT) throw failure
                null
            }
        }

    override fun exists(path: String): Boolean {
        val guest = guestPath(path)
        return access { statOrNull(it, guest) != null }
    }

    override fun isDirectory(path: String): Boolean {
        val guest = guestPath(path)
        return access { statOrNull(it, guest)?.kind == DIRECTORY }
    }

    override fun isFile(path: String): Boolean {
        val guest = guestPath(path)
        return access { statOrNull(it, guest)?.kind == REGULAR }
    }

    override fun fileSize(path: String): Long {
        val guest = guestPath(path)
        return access { statOrNull(it, guest)?.size ?: 0L }
    }

    override fun lastModified(path: String): Long {
        val guest = guestPath(path)
        return access { statOrNull(it, guest)?.modifiedMs ?: 0L }
    }

    override fun fingerprint(path: String): String? {
        val guest = guestPath(path)
        return access { operation ->
            val stat = statOrNull(operation, guest) ?: return@access null
            if (stat.kind != REGULAR) return@access null
            if (stat.size > MAX_READ_BYTES) throw FileReadTooLargeException(MAX_READ_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(IO_CHUNK)
            var total = 0L
            checkedRead(operation, guest).use { input ->
                while (true) {
                    val length = minOf(buffer.size.toLong(), MAX_READ_BYTES - total + 1).toInt()
                    val count = input.read(buffer, 0, length)
                    if (count < 0) break
                    if (count == 0) throw IOException("Chroot file read made no progress")
                    total += count
                    if (total > MAX_READ_BYTES) throw FileReadTooLargeException(MAX_READ_BYTES)
                    digest.update(buffer, 0, count)
                }
            }
            "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private fun resolvedIdentity(operation: Operation, path: String, followFinal: Boolean): String {
        val resolved = operation.request(path) { it.resolve(path, followFinal) }
        if (!resolved.startsWith('/') || resolved.split('/').any { it == "." || it == ".." }) {
            throw IOException("Invalid chroot file identity path")
        }
        guestPath(resolved)
        return resolved.trimEnd('/').ifEmpty { "/" }
    }

    override fun resolvePath(path: String, followFinalLink: Boolean): String {
        val guest = guestPath(path)
        return access { resolvedIdentity(it, guest, followFinalLink) }
    }

    private fun permissions(stat: ChrootFileStat, identityPath: String, session: ChrootFileSession): String = buildString {
        append(if (stat.mode and 0x124 != 0) 'r' else '-')
        append(if (stat.kind != SYMLINK && stat.mode and 0x92 != 0 && session.isWritable(identityPath)) 'w' else '-')
        append(if (stat.mode and 0x49 != 0) 'x' else '-')
    }

    override fun permissions(path: String): String {
        val guest = guestPath(path)
        return access { operation ->
            val stat = statOrNull(operation, guest) ?: return@access "---"
            permissions(stat, resolvedIdentity(operation, guest, true), operation.session)
        }
    }

    override fun listFiles(path: String): List<FileEntry> {
        val guest = guestPath(path)
        return access { operation ->
            val identity = resolvedIdentity(operation, guest, true)
            operation.request(guest) { it.list(guest) }.map { (name, stat) ->
                operation.checkCurrent()
                FileEntry(
                    name = name,
                    isDirectory = stat.kind == DIRECTORY,
                    size = stat.size,
                    lastModified = stat.modifiedMs,
                    localFile = null,
                    permissions = permissions(stat, child(identity, name), operation.session)
                )
            }
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        require(maxDepth >= 0)
        val guest = guestPath(path)
        return access { operation ->
            if (statOrNull(operation, guest)?.kind != DIRECTORY || maxDepth == 0) return@access emptyList()
            val result = mutableListOf<String>()
            val pending = ArrayDeque<Triple<String, String, Int>>()
            pending.add(Triple(guest, "", 0))
            var entries = 0
            while (pending.isNotEmpty()) {
                val (directory, prefix, depth) = pending.removeLast()
                for ((name, stat) in operation.request(directory) { it.list(directory) }) {
                    if (++entries > MAX_TREE_ENTRIES) throw IOException("Chroot directory exceeds the entry limit")
                    val relative = prefix + name
                    val entry = child(directory, name)
                    if (stat.kind == REGULAR) result += relative
                    if (stat.kind == DIRECTORY && depth + 1 < maxDepth) {
                        guestPath(entry)
                        pending.add(Triple(entry, "$relative/", depth + 1))
                    }
                }
            }
            result
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        writeStream(path, ByteArrayInputStream(bytes), overwrite)
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val guest = guestPath(path)
        return access { operation -> writeStream(operation, guest, input, overwrite) }
    }

    private fun writeStream(operation: Operation, path: String, input: InputStream, overwrite: Boolean): Long {
        ensureParent(operation, path)
        val checked = identityCheckedRead(object : InputStream() {
            override fun read(): Int = input.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, minOf(len, IO_CHUNK))
            override fun close() = Unit
        }, operation::checkCurrent)
        return checked.use { stream -> operation.request(path) { it.write(path, stream, overwrite) } }
    }

    private fun ensureParent(operation: Operation, path: String) {
        val parent = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }
        ensureDirectory(operation, parent)
    }

    private fun ensureDirectory(operation: Operation, path: String) {
        val existing = statOrNull(operation, path)
        if (existing != null) {
            if (existing.kind != DIRECTORY) throw ChrootFileOperationException(ENOTDIR, path, "mkdirs")
            return
        }
        operation.request(path) { it.mkdirs(path) }
    }

    override fun mkdirs(path: String) {
        val guest = guestPath(path)
        access { ensureDirectory(it, guest) }
    }

    override fun copyToLocal(path: String): File {
        val guest = guestPath(path)
        var created: File? = null
        return try {
            capture().use { operation ->
                val stat = operation.request(guest) { it.stat(guest) }
                if (stat.kind != REGULAR) throw IOException("Regular chroot file required")
                if (stat.size > MAX_STREAM_BYTES) throw FileReadTooLargeException(MAX_STREAM_BYTES)
                val directory = operation.session.cacheDirectory
                if ((!directory.isDirectory && !directory.mkdirs()) || !directory.isDirectory) {
                    throw IOException("Chroot file cache unavailable")
                }
                operation.checkCurrent()
                val temporary = File.createTempFile("aicode_chroot_", ".copy", directory)
                created = temporary
                checkedRead(operation, guest).use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(IO_CHUNK)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("Chroot file read made no progress")
                            total += count
                            if (total > MAX_STREAM_BYTES) throw FileReadTooLargeException(MAX_STREAM_BYTES)
                            operation.checkCurrent()
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                operation.checkCurrent()
                temporary
            }
        } catch (failure: Throwable) {
            val temporary = created
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                failure.addSuppressed(IOException("Chroot cache copy cleanup failed"))
            }
            throw failure
        }
    }

    override fun delete(path: String) {
        val guest = guestPath(path)
        access { operation -> operation.request(guest) { it.delete(guest, false) } }
    }

    override fun deleteRecursively(path: String) {
        val guest = guestPath(path)
        access { operation ->
            operation.request(guest) { client ->
                try { client.delete(guest, true) } catch (failure: ChrootFileOperationException) {
                    if (failure.errno != ENOENT) throw failure
                }
            }
        }
    }

    private data class Paths(val source: String, val target: String, val sourceIdentity: String, val targetIdentity: String)

    private fun checkedPaths(operation: Operation, source: String, target: String): Paths {
        val sourceEntry = resolvedIdentity(operation, source, false)
        val targetEntry = resolvedIdentity(operation, target, false)
        val sourceIdentity = resolvedIdentity(operation, source, true)
        val targetIdentity = resolvedIdentity(operation, target, true)
        if (listOf(sourceEntry, sourceIdentity).any { from ->
                listOf(targetEntry, targetIdentity).any { to -> contains(from, to) || contains(to, from) }
            }) {
            throw FilePathOverlapException("Chroot source and destination overlap")
        }
        return Paths(sourceEntry, targetEntry, sourceIdentity, targetIdentity)
    }

    private fun checkPaths(operation: Operation, source: String, target: String, paths: Paths) {
        if (checkedPaths(operation, source, target) != paths) throw IOException("Chroot transfer path identity changed")
    }

    override fun rename(path: String, newPath: String) {
        val source = guestPath(path)
        val target = guestPath(newPath)
        access { operation ->
            val paths = checkedPaths(operation, source, target)
            operation.request(source) { it.stat(source, false) }
            if (statOrNull(operation, target, false) != null) throw FileAlreadyExistsException(File(target))
            ensureParent(operation, target)
            checkPaths(operation, source, target, paths)
            operation.request(source) { it.rename(source, target, false) }
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val source = guestPath(path)
        val target = guestPath(newPath)
        access { operation ->
            val paths = checkedPaths(operation, source, target)
            val sourceStat = operation.request(source) { it.stat(source, false) }
            val tree = copyTree(operation, source, sourceStat)
            checkCopyTarget(operation, target, sourceStat.kind, overwrite)
            ensureParent(operation, target)
            checkPaths(operation, source, target, paths)
            for ((relative, expected) in tree) {
                val from = if (relative.isEmpty()) source else child(source, relative)
                val to = if (relative.isEmpty()) target else child(target, relative)
                checkPaths(operation, source, target, paths)
                val current = operation.request(from) { it.stat(from, false) }
                if (current != expected) throw IOException("Chroot copy source changed")
                when (current.kind) {
                    DIRECTORY -> {
                        if (statOrNull(operation, to, false) != null) throw FileAlreadyExistsException(File(to))
                        operation.request(to) { it.mkdirs(to) }
                    }
                    REGULAR -> checkedRead(operation, from).use { input ->
                        val count = writeStream(operation, to, input, overwrite)
                        if (count != current.size) throw IOException("Chroot copy size changed")
                        if (operation.request(from) { it.stat(from, false) } != current) throw IOException("Chroot copy source changed")
                    }
                    else -> throw ChrootFileOperationException(EOPNOTSUPP, from, "copy", to)
                }
            }
        }
    }

    private fun copyTree(operation: Operation, source: String, root: ChrootFileStat): List<Pair<String, ChrootFileStat>> {
        val entries = mutableListOf("" to root)
        var index = 0
        while (index < entries.size) {
            val (relative, stat) = entries[index++]
            if (stat.kind == SYMLINK) throw ChrootFileOperationException(EOPNOTSUPP,
                if (relative.isEmpty()) source else child(source, relative), "copy")
            if (stat.kind != DIRECTORY) continue
            val directory = if (relative.isEmpty()) source else child(source, relative)
            for ((name, childStat) in operation.request(directory) { it.list(directory) }) {
                val childRelative = if (relative.isEmpty()) name else "$relative/$name"
                if (childRelative.count { it == '/' } >= MAX_COPY_DEPTH) throw IOException("Chroot copy exceeds the depth limit")
                if (entries.size >= MAX_TREE_ENTRIES) throw IOException("Chroot copy exceeds the entry limit")
                entries += childRelative to childStat
            }
        }
        return entries
    }

    private fun checkCopyTarget(operation: Operation, target: String, sourceKind: Int, overwrite: Boolean) {
        val existing = statOrNull(operation, target, false) ?: return
        if (!overwrite) throw FileAlreadyExistsException(File(target))
        if (sourceKind != REGULAR || existing.kind != REGULAR) throw ChrootFileOperationException(EOPNOTSUPP, target, "copy")
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val source = guestPath(path)
        val target = guestPath(newPath)
        access { operation ->
            val paths = checkedPaths(operation, source, target)
            operation.request(source) { it.stat(source, false) }
            val targetStat = statOrNull(operation, target, false)
            if (targetStat != null && !overwrite) throw FileAlreadyExistsException(File(target))
            ensureParent(operation, target)
            checkPaths(operation, source, target, paths)
            operation.request(source) { it.rename(source, target, overwrite) }
        }
    }

    override fun parentPath(path: String): String? {
        val guest = guestPath(path).trimEnd('/')
        if (guest.isEmpty()) return null
        return displayPath(guest.substringBeforeLast('/', "").ifEmpty { "/" })
    }

    override fun toDisplayPath(path: String): String = displayPath(guestPath(path))

    private companion object {
        const val ENOENT = 2
        const val EEXIST = 17
        const val ENOTDIR = 20
        const val EOPNOTSUPP = 95
        const val DIRECTORY = 1
        const val REGULAR = 2
        const val SYMLINK = 3
        const val IO_CHUNK = 64 * 1024
        const val MAX_READ_BYTES = 64L * 1024 * 1024
        const val MAX_STREAM_BYTES = 512L * 1024 * 1024
        const val MAX_TREE_ENTRIES = 10000
        const val MAX_COPY_DEPTH = 128
        val FORBIDDEN_ROOTS = setOf("proc", "dev", "sys", "tmp", "run")

        fun guestPath(path: String): String {
            if ('\u0000' in path) throw IOException("Invalid chroot guest path")
            val guest = when {
                path == "~" -> "/root"
                path.startsWith("~/") -> "/root/" + path.removePrefix("~/")
                path.startsWith('/') -> path
                else -> "/root/workspace/$path"
            }
            val components = mutableListOf<String>()
            for (part in guest.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (components.isNotEmpty()) components.removeAt(components.lastIndex)
                    else -> components += part
                }
                if (components.firstOrNull() in FORBIDDEN_ROOTS) throw ChrootFileOperationException(13, guest, "validate-path")
            }
            return guest
        }

        fun displayPath(path: String): String = when {
            path == "/root/workspace" || path == "/root/workspace/" -> "~/workspace"
            path.startsWith("/root/workspace/") -> "~/workspace/" + path.removePrefix("/root/workspace/")
            path == "/root" || path == "/root/" -> "~"
            path.startsWith("/root/") -> "~/" + path.removePrefix("/root/")
            else -> path
        }

        fun child(parent: String, name: String): String = parent.trimEnd('/') + "/" + name

        fun contains(parent: String, child: String): Boolean =
            parent == "/" || child == parent || child.startsWith("$parent/")
    }
}
