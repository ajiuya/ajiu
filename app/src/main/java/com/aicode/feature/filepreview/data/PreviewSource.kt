package com.aicode.feature.filepreview.data

import com.aicode.feature.filepreview.domain.PreviewLimits
import com.aicode.feature.workspace.domain.RootRead
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

internal class PreviewSource internal constructor(
    private val paths: PreviewPaths,
    val size: Long,
    private val sessionJob: CompletableJob,
    private val execution: WorkspaceExecutionContext?,
    private val rootRead: RootRead?,
    private val snapshot: File?,
    private val cacheRoot: File,
    private val environmentCheck: () -> Unit,
) : AutoCloseable {
    val entryPath: String = paths.entry
    val displayPath: String = entryPath
    val relatedFilesAvailable: Boolean = snapshot == null
    private val _valid = MutableStateFlow(true)
    val valid: StateFlow<Boolean> = _valid.asStateFlow()
    private val closed = AtomicBoolean()
    private val resourcesLock = Any()
    private val resources = mutableSetOf<AutoCloseable>()
    private val copies = mutableMapOf<String, File>()
    private val ownedFiles = mutableSetOf<File>()
    private val copyMutex = Mutex()
    private var sessionDirectory: File? = null
    private var copiedBytes = 0L
    private val executionContext = execution ?: EmptyCoroutineContext
    private val sessionContext = sessionJob + executionContext + Dispatchers.IO

    internal fun startMonitoring() {
        CoroutineScope(sessionContext).launch {
            while (isActive) {
                delay(250)
                try {
                    checkCurrent()
                } catch (_: CancellationException) {
                    close()
                } catch (_: IOException) {
                    close()
                }
            }
        }
    }

    fun checkCurrent() {
        if (closed.get()) throw CancellationException("Preview session closed")
        sessionJob.ensureActive()
        try {
            environmentCheck()
            execution?.checkCurrent()
            rootRead?.checkCurrent()
            snapshot?.let {
                if (it.canonicalFile != it || !Files.isRegularFile(it.toPath()) || !it.toPath().startsWith(cacheRoot.toPath())) {
                    throw IOException("Cached preview is no longer available")
                }
            }
        } catch (failure: CancellationException) {
            close()
            throw failure
        } catch (failure: IOException) {
            close()
            throw failure
        }
    }

    fun resolve(relative: String, basePath: String = entryPath): String {
        checkCurrent()
        val resolved = paths.resolve(relative, basePath)
        if (!relatedFilesAvailable && resolved.substringBefore('#') != entryPath) {
            throw IOException("Cached preview only authorizes its entry file")
        }
        return resolved
    }

    fun openRead(path: String = entryPath): InputStream = openRead(path, sessionJob, rootRead)

    private fun openRead(path: String, owner: Job, reader: RootRead?): InputStream =
        runBlocking(owner + executionContext + Dispatchers.IO) {
            val authorized = resolve(path).substringBefore('#')
            checkCurrent()
            val input = if (snapshot != null) {
                if (snapshot.length() > PreviewLimits.FILE_BYTES) throw IOException("File exceeds preview limit")
                snapshot.inputStream()
            } else {
                requireNotNull(reader).openRead(authorized, PreviewLimits.FILE_BYTES)
            }
            val stream = TrackedInput(input, owner)
            track(stream)
            try {
                checkCurrent()
                stream
            } catch (failure: Throwable) {
                runCatching { stream.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun localFile(path: String = entryPath): File = withContext(Dispatchers.IO + executionContext) {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val operationJob = Job(sessionJob)
        val operationLock = Any()
        val operationResources = mutableSetOf<AutoCloseable>()
        val operationCancellation = operationJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) {
                val closing = synchronized(operationLock) { operationResources.toList().also { operationResources.clear() } }
                closing.forEach { runCatching { it.close() } }
            }
        }
        val callerCancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
            onCancelling = true, invokeImmediately = true,
        ) { cause ->
            if (cause is CancellationException) CoroutineScope(Dispatchers.IO).launch { operationJob.cancel(cause) }
        }
        fun trackOperation(resource: AutoCloseable) {
            track(resource)
            synchronized(operationLock) {
                if (!operationJob.isActive) {
                    runCatching { resource.close() }
                    synchronized(resourcesLock) { resources -= resource }
                    operationJob.ensureActive()
                }
                operationResources += resource
            }
        }
        val operationRoot = rootRead?.forOperation(operationJob)
        try {
            copyMutex.withLock {
                val authorized = resolve(path).substringBefore('#')
                currentCoroutineContext().ensureActive()
                operationJob.ensureActive()
                val direct = snapshot ?: operationRoot?.localFile(authorized)
                if (direct != null) {
                    checkCurrent()
                    if (direct.length() > PreviewLimits.FILE_BYTES) throw IOException("File exceeds preview limit")
                    return@withLock direct
                }
                copies[authorized]?.let { checkCurrent(); return@withLock it }
                val expected = requireNotNull(operationRoot).size(authorized)
                val remaining = PreviewLimits.FILE_BYTES - copiedBytes
                if (expected < 0 || expected > remaining) throw IOException("Preview session cache limit exceeded")
                val directory = synchronized(resourcesLock) {
                    checkCurrent()
                    sessionDirectory ?: run {
                        if (cacheRoot.canonicalFile != cacheRoot || !cacheRoot.isDirectory) throw IOException("Preview cache unavailable")
                        Files.createTempDirectory(cacheRoot.toPath(), "filepreview_").toFile().also { sessionDirectory = it }
                    }
                }
                if (directory.usableSpace < expected + COPY_BUFFER_BYTES) throw IOException("Insufficient preview cache space")
                val file = synchronized(resourcesLock) {
                    checkCurrent()
                    File.createTempFile("entry_", ".copy", directory).also { ownedFiles += it }
                }
                var succeeded = false
                try {
                    openRead(authorized, operationJob, operationRoot).use { input ->
                        trackOperation(input)
                        val output = FileOutputStream(file)
                        trackOperation(output)
                        try {
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var count = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                operationJob.ensureActive()
                                checkCurrent()
                                val length = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining - count + 1).toInt())
                                if (length < 0) break
                                if (length == 0) throw IOException("Preview copy made no progress")
                                if (length > remaining - count) throw IOException("Preview session cache limit exceeded")
                                if (directory.usableSpace < length) throw IOException("Insufficient preview cache space")
                                output.write(buffer, 0, length)
                                count += length
                            }
                            output.flush()
                            checkCurrent()
                            currentCoroutineContext().ensureActive()
                            synchronized(resourcesLock) {
                                checkCurrent()
                                operationJob.ensureActive()
                                callerContext.ensureActive()
                                copiedBytes += count
                                copies[authorized] = file
                            }
                            succeeded = true
                        } finally {
                            try { output.close() } finally { synchronized(resourcesLock) { resources -= output } }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    operationJob.ensureActive()
                    file
                } catch (failure: Throwable) {
                    if (succeeded) {
                        synchronized(resourcesLock) {
                            if (copies[authorized] == file) {
                                copies.remove(authorized)
                                copiedBytes -= file.length()
                            }
                        }
                        succeeded = false
                    }
                    if (failure is IOException || failure is CancellationException) {
                        callerContext.ensureActive()
                        operationJob.ensureActive()
                    }
                    throw failure
                } finally {
                    if (!succeeded) {
                        synchronized(resourcesLock) { ownedFiles -= file }
                        file.delete()
                        synchronized(resourcesLock) {
                            if (ownedFiles.isEmpty() && directory.listFiles().isNullOrEmpty() && directory.delete()) {
                                sessionDirectory = null
                            }
                        }
                    }
                }
            }
        } catch (failure: IOException) {
            callerContext.ensureActive()
            operationJob.ensureActive()
            throw failure
        } finally {
            callerCancellation?.dispose()
            operationCancellation.dispose()
            operationJob.complete()
        }
    }

    private fun track(resource: AutoCloseable) {
        synchronized(resourcesLock) {
            if (closed.get()) {
                runCatching { resource.close() }
                throw CancellationException("Preview session closed")
            }
            resources += resource
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        _valid.value = false
        val closing: List<AutoCloseable>
        val deleting: List<File>
        val directory: File?
        synchronized(resourcesLock) {
            closing = resources.toList()
            resources.clear()
            deleting = ownedFiles.toList()
            ownedFiles.clear()
            copies.clear()
            directory = sessionDirectory
            sessionDirectory = null
        }
        CoroutineScope(Dispatchers.IO).launch {
            sessionJob.cancel()
            closing.forEach { runCatching { it.close() } }
            deleting.forEach { it.delete() }
            directory?.delete()
        }
    }

    private inner class TrackedInput(private val input: InputStream, private val owner: Job) : InputStream() {
        private val finished = AtomicBoolean()
        private val single = ByteArray(1)
        private var count = 0L
        private var exhausted = false

        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            if (exhausted) return -1
            if (finished.get()) throw IOException("Stream closed")
            return runBlocking(owner + executionContext + Dispatchers.IO) {
                try {
                    checkCurrent()
                    owner.ensureActive()
                    val length = input.read(b, off, minOf(len.toLong(), PreviewLimits.FILE_BYTES - count + 1).toInt())
                    if (finished.get()) throw IOException("Stream closed")
                    checkCurrent()
                    if (length > PreviewLimits.FILE_BYTES - count) throw IOException("File exceeded preview limit during read")
                    if (length < 0) { exhausted = true; close() } else count += length
                    length
                } catch (failure: Throwable) {
                    runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
        }

        override fun close() {
            if (finished.compareAndSet(false, true)) {
                try { input.close() } finally { synchronized(resourcesLock) { resources -= this } }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 32 * 1024
    }
}

internal class PreviewPaths private constructor(val entry: String, val root: String) {
    fun resolve(relative: String, basePath: String): String {
        val base = basePath.substringBefore('#')
        if (!within(base) || decode(base) != base || base.split('/').any { it == "." || it == ".." }) {
            throw IOException("Base path outside preview root")
        }
        val fragment = relative.indexOf('#').takeIf { it >= 0 }?.let { relative.substring(it) }.orEmpty()
        val rawPath = relative.substringBefore('#')
        if (rawPath.isEmpty()) return base + fragment
        val path = decode(rawPath)
        if (path.startsWith("//") || path == "~" || path == "~/" || SCHEME.containsMatchIn(path)) {
            throw IOException("External preview resource is not allowed")
        }
        val absolute = path.startsWith('/') || path.startsWith("~/")
        val prefix = root.trimEnd('/') + "/"
        val suffix = if (absolute) {
            if (!path.startsWith(prefix)) throw IOException("Resource outside preview root")
            path.removePrefix(prefix)
        } else path
        val parts = if (absolute) mutableListOf() else {
            base.substringBeforeLast('/').removePrefix(root).trimStart('/').split('/').filter { it.isNotEmpty() }.toMutableList()
        }
        suffix.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw IOException("Resource outside preview root") else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        if (parts.isEmpty()) throw IOException("Regular file path required")
        return prefix + parts.joinToString("/") + fragment
    }

    private fun within(path: String): Boolean = path.startsWith(root.trimEnd('/') + "/")

    companion object {
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun entry(path: String): PreviewPaths {
            val decoded = decode(path.trim())
            if (decoded.isBlank() || decoded.contains('#') || decoded.startsWith("//") || SCHEME.containsMatchIn(decoded)) {
                throw IOException("Invalid preview entry path")
            }
            val absolute = when {
                decoded.startsWith("~/") || decoded.startsWith('/') -> decoded
                decoded.startsWith('~') -> throw IOException("Invalid home path")
                else -> "~/workspace/$decoded"
            }
            val prefix = if (absolute.startsWith("~/")) "~" else ""
            val parts = mutableListOf<String>()
            absolute.removePrefix(prefix).split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> if (parts.isEmpty()) throw IOException("Entry path escapes its root") else parts.removeAt(parts.lastIndex)
                    else -> parts += part
                }
            }
            if (parts.isEmpty()) throw IOException("Regular file path required")
            val entry = "$prefix/${parts.joinToString("/")}"
            return PreviewPaths(entry, entry.substringBeforeLast('/').ifEmpty { "/" })
        }

        private fun decode(path: String): String {
            if (path.any { it == '\\' || it.code < 32 || it.code == 127 } || path.contains('?')) {
                throw IOException("Invalid preview path")
            }
            val decoded = try {
                URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
            } catch (failure: IllegalArgumentException) {
                throw IOException("Invalid preview path encoding", failure)
            }
            if (decoded.any { it == '%' || it == '\\' || it.code < 32 || it.code == 127 || it == '?' || it == '#' }) {
                throw IOException("Ambiguous preview path encoding")
            }
            return decoded
        }
    }
}
