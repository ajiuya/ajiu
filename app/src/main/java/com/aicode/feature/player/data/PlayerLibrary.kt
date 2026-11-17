package com.aicode.feature.player.data

import com.aicode.feature.player.domain.PlayerMediaTypes
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal fun containerPath(raw: String): String {
    if (raw.isBlank() || raw.any { it.code < 32 } || '\\' in raw) throw PlayerHttpException(400, "Invalid container path")
    val prefix = when { raw == "~" || raw.startsWith("~/") -> "~"; raw.startsWith('/') -> ""; else -> throw PlayerHttpException(400, "Absolute path required") }
    val parts = mutableListOf<String>()
    raw.removePrefix(prefix).split('/').forEach {
        when (it) { "", "." -> Unit; ".." -> if (parts.isEmpty()) throw PlayerHttpException(403, "Path escapes root") else parts.removeAt(parts.lastIndex); else -> parts += it }
    }
    return prefix + "/" + parts.joinToString("/")
}
internal fun withinRoot(path: String, root: String) = path == root || path.startsWith(if (root == "/" || root == "~/") root else "$root/")

internal class PlayerLibrary(
    private val execution: PlayerFileExecution,
    root: String,
    private val prefix: String,
    private val resources: PlayerResources,
    private val createCopy: () -> File,
    private val budget: PlayerCopyBudget = PlayerCopyBudget(),
    private val maxDepth: Int = 5,
    private val maxItems: Int = 1000,
    private val maxEntries: Int = 5000
) : AutoCloseable {
    private val access get() = execution.access
    private val root = execution.run { containerPath(access.toDisplayPath(root)) }
    private class Copy(val file: File, val entry: FileEntry, val reservation: AutoCloseable?) {
        var leases = 0
        var retired = false
    }
    private class Flight {
        val lock = ReentrantLock()
        val consumers = mutableSetOf<Consumer>()
        var stream: InputStream? = null
        var cancelled = false
    }
    private class Consumer(val progress: () -> Unit, val release: (Consumer) -> Unit) : AutoCloseable {
        val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release(this) }
    }
    internal class ValidatedFile(val size: Long, val descriptor: RandomAccessFile?, private val release: () -> Unit = {}) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) try { descriptor?.close() } finally { release() } }
    }
    private val copies = linkedMapOf<String, Copy>()
    private val flights = mutableMapOf<String, Flight>()
    private var closed = false
    fun checkCurrent() { resources.checkOpen(); execution.checkCurrent() }
    private fun checked(raw: String): String {
        checkCurrent()
        val path = containerPath(access.toDisplayPath(raw))
        if (!withinRoot(path, root)) throw PlayerHttpException(403, "Path outside session root")
        checkRemote(path)
        return path
    }
    fun checkedPath(raw: String): String = execution.run { checked(raw) }
    private fun checkRemote(path: String) {
        (access as? RemoteSftpFileAccess)?.checkPlaybackPath(path, root, execution.identity)
    }
    private fun displayedFile(file: File): String = when (val backend = access) {
        is LocalFileAccess -> backend.toDisplayPath(file.canonicalFile)
        else -> backend.toDisplayPath(file.canonicalPath)
    }
    private fun safeEntry(entry: FileEntry): Boolean {
        if (entry.name.isEmpty() || entry.name in setOf(".", "..") || '/' in entry.name || '\\' in entry.name || entry.name.any { it.code < 32 }) return false
        val local = entry.localFile ?: return true
        val resolved = when (val backend = access) {
            is LocalFileAccess -> backend.copyToLocal(backend.toDisplayPath(local))
            else -> local
        }
        return withinRoot(containerPath(displayedFile(resolved)), root)
    }
    fun json(requestedRoot: String = root): JsonObject = execution.run {
        val items = mutableListOf<JsonObject>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(checked(requestedRoot) to 0)
        var seen = 0
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty() && seen < maxEntries && items.size < maxItems) {
            checkCurrent()
            val (directory, depth) = pending.removeFirst()
            if (!visited.add(directory)) continue
            try { checkRemote(directory) } catch (_: IOException) { continue }
            if (!access.isDirectory(directory)) continue
            for (entry in access.listFiles(directory)) {
                checkCurrent()
                if (++seen > maxEntries || items.size >= maxItems) break
                if (!safeEntry(entry)) continue
                val path = directory.trimEnd('/') + "/" + entry.name
                try { checkRemote(path) } catch (_: IOException) { continue }
                if (entry.isDirectory) {
                    if (depth + 1 < maxDepth && entry.name !in setOf(".git", "node_modules", "__pycache__", "tmp")) pending.add(path to depth + 1)
                } else if (PlayerMediaTypes.supports(entry.name)) items += buildJsonObject {
                    put("name", entry.name); put("url", "${prefix}local?path=${encodeQuery(path)}")
                    put("kind", PlayerMediaTypes.kind(entry.name)); put("size", entry.size)
                }
            }
        }
        buildJsonObject { put("items", JsonArray(items)) }
    }
    private fun fileEntry(path: String): FileEntry {
        val relative = path.removePrefix(root).trimStart('/')
        if (relative.isEmpty()) throw PlayerHttpException(404, "File required")
        var directory = root
        var result: FileEntry? = null
        val parts = relative.split('/')
        parts.forEachIndexed { index, name ->
            checkCurrent()
            checkRemote(directory)
            checkRemote(directory.trimEnd('/') + "/" + name)
            val entry = access.listFiles(directory).firstOrNull { it.name == name } ?: throw PlayerHttpException(404, "File not found")
            if (!safeEntry(entry)) throw PlayerHttpException(403, "Path outside session root")
            if (index < parts.lastIndex && !entry.isDirectory) throw PlayerHttpException(404, "Directory required")
            result = entry; directory = directory.trimEnd('/') + "/" + name
        }
        return result!!.takeUnless { it.isDirectory } ?: throw PlayerHttpException(404, "File required")
    }
    fun openValidated(path: String, metadataOnly: Boolean = false, connection: PlayerResources? = null,
        progress: () -> Unit = {}, validate: (Long) -> Unit = {}): ValidatedFile = execution.run {
        val checked = checked(path)
        if (metadataOnly) return@run open(checked, true, null, validate)
        lateinit var flight: Flight
        val consumer = Consumer(progress) { user ->
            val stream = synchronized(copies) {
                flight.consumers.remove(user)
                if (flight.consumers.isEmpty()) {
                    flight.cancelled = true
                    if (flights[checked] === flight) flights.remove(checked)
                    flight.stream.also { flight.stream = null }
                } else null
            }
            runCatching { stream?.close() }
        }
        synchronized(copies) {
            if (closed) throw IOException("Player library closed")
            flight = flights.getOrPut(checked) { Flight() }; flight.consumers.add(consumer)
        }
        try {
            connection?.add(consumer)
            while (!flight.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                if (consumer.closed.get()) throw IOException("Player request closed")
                checkCurrent()
            }
            try {
                if (consumer.closed.get()) throw IOException("Player request closed")
                val opened = open(checked, false, flight, validate)
                if (consumer.closed.get()) { opened.close(); throw IOException("Player request closed") }
                opened
            } finally { flight.lock.unlock() }
        } finally { connection?.remove(consumer); consumer.close() }
    }
    private fun checkFlight(flight: Flight) {
        checkCurrent()
        synchronized(copies) { if (closed || flight.cancelled) throw IOException("Player copy cancelled") }
    }
    private fun retire(copy: Copy) {
        copy.retired = true
        if (copy.leases == 0 && copy.reservation != null) { copy.file.delete(); copy.reservation.close() }
    }
    private fun lease(copy: Copy): ValidatedFile {
        val descriptor = RandomAccessFile(copy.file, "r")
        if (descriptor.length() != copy.entry.size) { descriptor.close(); throw PlayerHttpException(503, "File changed") }
        copy.leases++
        return ValidatedFile(copy.entry.size, descriptor) { synchronized(copies) { copy.leases--; if (copy.retired) retire(copy) } }
    }
    private fun open(path: String, metadataOnly: Boolean, flight: Flight?, validate: (Long) -> Unit): ValidatedFile {
        checkCurrent()
        val entry = fileEntry(path)
        entry.localFile?.let { validateFile(access.copyToLocal(path), entry) }
        validate(entry.size)
        checkCurrent()
        if (metadataOnly) return ValidatedFile(entry.size, null)
        synchronized(copies) {
            if (closed) throw IOException("Player library closed")
            copies[path]?.let { previous ->
                if (previous.entry == entry && previous.file.isFile && previous.file.length() == entry.size) return lease(previous)
                copies.remove(path); retire(previous)
            }
        }
        var reservation: PlayerCopyBudget.Reservation? = null
        val file: File
        if (entry.localFile == null) {
            synchronized(copies) {
                copies.entries.filter { it.value.leases == 0 }.map { it.key }.forEach { key -> retire(copies.remove(key)!!) }
            }
            file = createCopy()
            try {
                reservation = budget.reserve(entry.size, file.parentFile!!)
                copyRemote(path, requireNotNull(flight), file, entry.size, reservation)
            } catch (failure: Throwable) { file.delete(); reservation?.close(); throw failure }
        } else file = access.copyToLocal(path)
        try {
            checkCurrent()
            if (entry.localFile == null) {
                val after = fileEntry(path)
                if (after.size != entry.size || after.lastModified != entry.lastModified) throw PlayerHttpException(503, "Remote file changed")
            }
            validateFile(file, entry)
            synchronized(copies) {
                if (closed) throw IOException("Player library closed")
                checkCurrent()
                if (copies.size >= 8) { val oldest = copies.entries.first(); copies.remove(oldest.key); retire(oldest.value) }
                val copy = Copy(file, entry, reservation)
                val result = lease(copy)
                copies[path] = copy
                return result
            }
        } catch (failure: Throwable) { if (reservation != null) { file.delete(); reservation.close() }; throw failure }
    }
    private fun copyRemote(path: String, flight: Flight, file: File, expected: Long, reservation: PlayerCopyBudget.Reservation) {
        checkFlight(flight)
        val input = (access as? RemoteSftpFileAccess)?.openPlaybackRead(path, root, execution.identity)
            ?: access.openRead(path, execution.identity)
        synchronized(copies) {
            if (closed || flight.cancelled) { input.close(); throw IOException("Player copy cancelled") }
            flight.stream = input
        }
        try {
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L; var nextDiskCheck = 0L
                while (true) {
                    checkFlight(flight)
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (count > expected - written) throw IOException("Remote file grew during copy")
                    if (written >= nextDiskCheck) { budget.checkDisk(file.parentFile!!); nextDiskCheck = written + 8 * 1024 * 1024 }
                    output.write(buffer, 0, count); written += count; reservation.written(count)
                    synchronized(copies) { flight.consumers.toList() }.forEach { user ->
                        if (!user.closed.get()) try { user.progress() } catch (_: Exception) { user.close() }
                    }
                }
                if (written != expected) throw IOException("Incomplete remote file")
            } }
            checkFlight(flight)
        } finally { synchronized(copies) { if (flight.stream === input) flight.stream = null } }
    }
    private fun validateFile(file: File, entry: FileEntry) {
        if (!file.isFile) throw PlayerHttpException(404, "File not found")
        if (entry.localFile != null && !withinRoot(containerPath(displayedFile(file)), root)) throw PlayerHttpException(403, "Path outside session root")
        if (file.length() != entry.size) throw PlayerHttpException(503, "File changed")
    }
    override fun close() {
        val streams = synchronized(copies) {
            if (closed) return
            closed = true
            copies.values.forEach(::retire); copies.clear()
            flights.values.mapNotNull { it.cancelled = true; it.stream }.also { flights.clear() }
        }
        streams.forEach { runCatching { it.close() } }
    }
}
