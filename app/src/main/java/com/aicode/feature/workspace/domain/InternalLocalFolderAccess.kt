package com.aicode.feature.workspace.domain

import com.aicode.core.util.boundedLines
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemLoopException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

internal class LocalFolderContainmentException : IOException("Path escapes the authorized local folder")

class InternalLocalFolderAccess internal constructor(
    private val snapshot: LocalFolderSnapshot,
    private val execution: WorkspaceExecutionContext? = null,
    private val checkScope: () -> Unit = {}
) : FileAccessProvider {
    private fun checkCurrent() {
        checkScope()
        execution?.checkCurrent()
        WorkspaceExecutionContext.current()?.let {
            it.checkCurrent()
            if (execution != null && execution !== it) throw CancellationException("Workspace execution capture changed")
        }
        snapshot.checkCurrent()
    }

    private data class Resolved(val root: CapturedLocalFolderRoot, val file: File)

    private fun resolve(path: String, followFinalLink: Boolean = true, verifyAuthorization: Boolean = true): Resolved {
        checkCurrent()
        val parsed = parseLocalFolderPath(path) ?: throw IOException("Invalid local folder path")
        val root = snapshot.rootFor(path, verifyAuthorization)
        val file = resolveSegments(root, parsed.segments, followFinalLink)
        checkCurrent()
        return Resolved(root, file)
    }

    private fun resolveSegments(root: CapturedLocalFolderRoot, segments: List<String>, followFinalLink: Boolean): File {
        val directory = root.directory.toPath()
        val pending = ArrayDeque(segments)
        var current = directory
        var links = 0
        while (pending.isNotEmpty()) {
            checkCurrent()
            val part = pending.removeFirst()
            when (part) {
                "." -> continue
                ".." -> {
                    if (current == directory) throw LocalFolderContainmentException()
                    current = current.parent ?: throw LocalFolderContainmentException()
                    continue
                }
            }
            val candidate = current.resolve(part)
            if (!candidate.startsWith(directory)) throw LocalFolderContainmentException()
            if ((followFinalLink || pending.isNotEmpty()) && Files.isSymbolicLink(candidate)) {
                if (++links > 40) throw FileSystemLoopException(candidate.toString())
                val target = Files.readSymbolicLink(candidate)
                val parts = if (target.isAbsolute) {
                    if (!target.startsWith(directory)) throw LocalFolderContainmentException()
                    current = directory
                    directory.relativize(target).map { it.toString() }
                } else target.map { it.toString() }
                parts.asReversed().forEach(pending::addFirst)
            } else current = candidate
        }
        if (!current.startsWith(directory)) throw LocalFolderContainmentException()
        return current.toFile()
    }

    private fun logicalPath(root: CapturedLocalFolderRoot, file: File): String {
        val relative = root.directory.toPath().relativize(file.toPath()).joinToString("/")
        return root.grant.rootPath + if (relative.isEmpty()) "" else "/$relative"
    }

    private fun entryExists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    override fun cacheIdentity(): String {
        checkCurrent()
        return snapshot.roots.joinToString("", "local-folders:${snapshot.revision}:") { "${it.id}:${it.epoch};" }
    }

    override fun openRead(path: String): InputStream {
        val captured = resolve(path)
        if (!captured.file.isFile) throw IOException("Not a regular file: $path")
        val raw = object : java.io.FilterInputStream(captured.file.inputStream()) {
            override fun close() {
                try { snapshot.checkAuthorization(captured.root) } finally { super.close() }
            }
        }
        val input = identityCheckedRead(raw) {
            checkCurrent()
            if (resolve(path, verifyAuthorization = false).file != captured.file) {
                throw CancellationException("Local folder path changed")
            }
        }
        return execution?.protectRead(input) ?: WorkspaceExecutionContext.current()?.protectRead(input) ?: input
    }

    override fun readFile(path: String): String = readBytes(path).toString(Charsets.UTF_8)

    override fun readLines(path: String): Sequence<String> {
        resolve(path)
        return boundedLines { openRead(path).bufferedReader() }
    }

    override fun readBytes(path: String): ByteArray = readBytes(path, DEFAULT_MAX_BYTES)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        val file = resolve(path).file
        val size = file.length()
        checkCurrent()
        if (size > maxBytes) throw FileReadTooLargeException(maxBytes)
        return openRead(path).use { it.readBytesBounded(maxBytes) }
    }

    override fun exists(path: String): Boolean = resolve(path).file.exists().also { checkCurrent() }
    override fun isDirectory(path: String): Boolean = resolve(path).file.isDirectory.also { checkCurrent() }
    override fun isFile(path: String): Boolean = resolve(path).file.isFile.also { checkCurrent() }
    override fun fileSize(path: String): Long = resolve(path).file.length().also { checkCurrent() }
    override fun lastModified(path: String): Long = resolve(path).file.lastModified().also { checkCurrent() }

    override fun fingerprint(path: String): String? {
        if (!isFile(path)) return null
        val digest = MessageDigest.getInstance("SHA-256")
        openRead(path).use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                if (count == 0) throw IOException("File read made no progress")
                digest.update(bytes, 0, count)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun permissions(path: String): String = permissions(resolve(path).file).also { checkCurrent() }

    private fun permissions(file: File): String = buildString {
        append(if (file.canRead()) 'r' else '-')
        append(if (file.canWrite()) 'w' else '-')
        append(if (file.canExecute()) 'x' else '-')
    }

    override fun listFiles(path: String): List<FileEntry> {
        val directory = resolve(path)
        if (!directory.file.isDirectory) throw IOException("Not a directory: $path")
        return children(directory.file).map { entry ->
            checkCurrent()
            val logical = logicalPath(directory.root, entry)
            val child = try {
                resolve(logical).file
            } catch (_: LocalFolderContainmentException) {
                null
            } catch (_: FileSystemLoopException) {
                null
            }
            val attributes = Files.readAttributes(entry.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            FileEntry(
                name = entry.name,
                isDirectory = child?.isDirectory ?: false,
                size = child?.length() ?: attributes.size(),
                lastModified = child?.lastModified() ?: attributes.lastModifiedTime().toMillis(),
                permissions = child?.let(::permissions) ?: "---"
            ).also { checkCurrent() }
        }
    }

    private fun children(directory: File): List<File> =
        (directory.listFiles() ?: throw IOException("Cannot enumerate directory: ${directory.name}")).toList()
            .also { checkCurrent() }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        require(maxDepth >= 0)
        val source = resolve(path)
        if (!source.file.isDirectory) return emptyList()
        val result = mutableListOf<String>()
        fun visit(directory: File, prefix: String, depth: Int) {
            if (depth >= maxDepth) return
            for (child in children(directory)) {
                val logical = logicalPath(source.root, child)
                val entry = resolve(logical, false).file
                if (Files.isSymbolicLink(entry.toPath())) continue
                val relative = prefix + entry.name
                if (entry.isDirectory) visit(entry, "$relative/", depth + 1)
                else if (entry.isFile) result.add(relative)
            }
        }
        visit(source.file, "", 0)
        checkCurrent()
        return result
    }

    override fun resolvePath(path: String, followFinalLink: Boolean): String = resolve(path, followFinalLink).file.absolutePath

    internal fun transferFile(path: String, followFinalLink: Boolean = true): File {
        guardMutablePath(path)
        return resolve(path, followFinalLink).file.also { checkCurrent() }
    }

    internal fun readFileForTransfer(path: String, followFinalLink: Boolean = true): File =
        resolve(path, followFinalLink).file.also { checkCurrent() }

    internal fun directoryForWatch(path: String): File {
        val directory = resolve(path).file
        if (!directory.isDirectory) throw IOException("Not a directory: $path")
        checkCurrent()
        return directory
    }

    internal fun guardMutablePath(path: String) {
        val entry = resolve(path, false).file.toPath()
        val identities = mutableListOf(entry)
        try {
            identities.add(resolve(path).file.toPath())
        } catch (_: LocalFolderContainmentException) {
            // The final link may be removed without accessing its target.
        } catch (_: FileSystemLoopException) {
        }
        val roots = snapshot.records.map { it.directory.toPath() } + listOfNotNull(
            execution?.localMapping?.workspaceRoot?.toPath(), execution?.localMapping?.rootfsRoot?.toPath()
        )
        if (identities.any { identity -> roots.any { it.startsWith(identity) } }) {
            throw IOException("Cannot modify a file access root or its ancestor")
        }
        checkCurrent()
    }

    private fun guardTransfer(source: String, target: String) {
        guardMutablePath(source)
        guardMutablePath(target)
        val sources = listOf(resolve(source, false).file.toPath(), resolve(source).file.toPath())
        val targets = mutableListOf(resolve(target, false).file.toPath())
        try { targets.add(resolve(target).file.toPath()) } catch (_: LocalFolderContainmentException) {}
        if (sources.any { from -> targets.any { to -> from.startsWith(to) || to.startsWith(from) } }) {
            throw FilePathOverlapException("Source and destination overlap")
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        ByteArrayInputStream(content.toByteArray(encoding)).use { writeStream(path, it, overwrite) }
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        ByteArrayInputStream(bytes).use { writeStream(path, it, overwrite) }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val file = transferFile(path)
        if (entryExists(file) && !overwrite) throw FileAlreadyExistsException(file)
        Files.createDirectories(file.parentFile.toPath())
        if (transferFile(path) != file) throw CancellationException("Local folder path changed")
        val temporary = File.createTempFile(".aicode-tmp-", ".part", file.parentFile)
        var total = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val cancellation = execution?.onCancellation { runCatching { output.close() }; runCatching { input.close() } }
                try {
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkCurrent()
                        val count = input.read(bytes)
                        checkCurrent()
                        if (count < 0) break
                        if (count == 0) throw IOException("File read made no progress")
                        if (resolve(path, verifyAuthorization = false).file != file) {
                            throw CancellationException("Local folder path changed")
                        }
                        output.write(bytes, 0, count)
                        total += count
                    }
                    output.flush()
                    output.fd.sync()
                } finally { cancellation?.dispose() }
            }
            if (temporary.length() != total) throw IOException("Local file write verification failed")
            if (transferFile(path) != file) throw CancellationException("Local folder path changed")
            if (!overwrite) Files.move(temporary.toPath(), file.toPath()) else replace(temporary, file)
            checkCurrent()
            return total
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun copyToLocal(path: String): File = resolve(path).file

    override fun delete(path: String) {
        val file = transferFile(path, false)
        Files.deleteIfExists(file.toPath())
        checkCurrent()
    }

    override fun deleteRecursively(path: String) {
        val resolved = resolve(path, false)
        guardMutablePath(path)
        if (!entryExists(resolved.file)) return
        deleteTree(resolved)
    }

    private fun deleteTree(source: Resolved) {
        Files.walkFileTree(source.file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                resolve(logicalPath(source.root, dir.toFile()), false)
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val logical = logicalPath(source.root, file.toFile())
                Files.delete(transferFile(logical, false).toPath())
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(transferFile(logicalPath(source.root, dir.toFile()), false).toPath())
                return FileVisitResult.CONTINUE
            }
        })
        checkCurrent()
    }

    override fun rename(path: String, newPath: String) {
        guardTransfer(path, newPath)
        val source = resolve(path, false)
        val target = resolve(newPath, false)
        if (!entryExists(source.file)) throw NoSuchFileException(source.file)
        if (entryExists(target.file)) throw FileAlreadyExistsException(target.file)
        validateTree(source, target)
        Files.createDirectories(target.file.parentFile.toPath())
        checkCurrent()
        Files.move(source.file.toPath(), target.file.toPath())
        checkCurrent()
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        guardTransfer(path, newPath)
        val source = resolve(path)
        val target = resolve(newPath, false)
        if (!entryExists(source.file)) throw NoSuchFileException(source.file)
        validateTree(source, target)
        prepareTarget(target, overwrite)
        copyTree(source, target)
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        guardTransfer(path, newPath)
        val source = resolve(path, false)
        val target = resolve(newPath, false)
        if (!entryExists(source.file)) throw NoSuchFileException(source.file)
        validateTree(source, target)
        prepareTarget(target, overwrite)
        checkCurrent()
        if (source.file.renameTo(target.file)) {
            checkCurrent()
            return
        }
        copyTree(source, target)
        deleteTree(source)
    }

    private fun prepareTarget(target: Resolved, overwrite: Boolean) {
        if (entryExists(target.file)) {
            if (!overwrite) throw FileAlreadyExistsException(target.file)
            deleteTree(target)
        }
        Files.createDirectories(target.file.parentFile.toPath())
        checkCurrent()
    }

    private fun validateTree(source: Resolved, target: Resolved) {
        Files.walkFileTree(source.file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCurrent()
                guardMutablePath(logicalPath(source.root, dir.toFile()))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCurrent()
                if (Files.isSymbolicLink(file)) {
                    resolve(logicalPath(source.root, file.toFile()))
                    val destination = target.file.toPath().resolve(source.file.toPath().relativize(file))
                    val text = Files.readSymbolicLink(file)
                    val candidate = if (text.isAbsolute) text else destination.parent.resolve(text)
                    if (!candidate.startsWith(target.root.directory.toPath())) throw LocalFolderContainmentException()
                    resolveSegments(target.root, target.root.directory.toPath().relativize(candidate).map { it.toString() }, true)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyTree(source: Resolved, target: Resolved) {
        Files.walkFileTree(source.file.toPath(), object : SimpleFileVisitor<Path>() {
            private fun destination(file: Path): File = target.file.toPath().resolve(source.file.toPath().relativize(file)).toFile()
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCurrent()
                val targetPath = logicalPath(target.root, destination(dir))
                Files.createDirectories(transferFile(targetPath, false).toPath())
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCurrent()
                val from = logicalPath(source.root, file.toFile())
                val to = logicalPath(target.root, destination(file))
                if (Files.isSymbolicLink(file)) {
                    validateTree(Resolved(source.root, file.toFile()), Resolved(target.root, destination(file)))
                    Files.copy(transferFile(from, false).toPath(), transferFile(to, false).toPath(), LinkOption.NOFOLLOW_LINKS)
                } else openRead(from).use { writeStream(to, it, false) }
                return FileVisitResult.CONTINUE
            }
        })
        checkCurrent()
    }

    override fun mkdirs(path: String) {
        Files.createDirectories(resolve(path).file.toPath())
        checkCurrent()
    }

    override fun parentPath(path: String): String? {
        resolve(path, false)
        val parsed = parseLocalFolderPath(path) ?: throw IOException("Invalid local folder path")
        val root = localRootPath(parsed.id, parsed.epoch)
        if (parsed.segments.isEmpty()) return null
        val entry = resolve(path, false)
        val parent = entry.file.parentFile ?: return null
        return logicalPath(entry.root, parent).takeIf { it == root || it.startsWith("$root/") }
    }

    override fun toDisplayPath(path: String): String {
        resolve(path, false)
        return path
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
    }
}
