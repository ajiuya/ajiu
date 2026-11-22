package com.aicode.feature.workspace.domain

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal const val TRANSFER_BUFFER_BYTES = 32 * 1024
internal const val TRANSFER_MAX_ENTRIES = 10_000
internal const val TRANSFER_MAX_DEPTH = 64
private const val TRANSFER_MAX_PATH_CHARS = 2 * 1024 * 1024
internal const val TRANSFER_MAX_LINK_CHARS = 16 * 1024

internal enum class TransferKind { FILE, DIRECTORY, LINK }

internal data class TransferStat(
    val kind: TransferKind,
    val size: Long?,
    val modified: String,
    val entryIdentity: String? = null,
)

internal interface TransferReader {
    fun stat(relative: String): TransferStat?
    fun list(relative: String): List<String>
    fun openRead(relative: String): InputStream
    fun readLink(relative: String): String
    fun isExecutable(relative: String): Boolean = false
}

internal interface TransferBackend : TransferReader {
    fun createStage(kind: TransferKind): TransferStage
    fun deleteEntry(relative: String)
}

internal interface TransferStage : TransferReader, AutoCloseable {
    val committed: Boolean
    fun mkdir(relative: String)
    fun writeFile(relative: String, writer: (OutputStream) -> Unit)
    fun writeLink(relative: String, target: String)
    fun setExecutable(relative: String, executable: Boolean) {
        throw IOException("Transfer staging permissions are not supported")
    }
    fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit)
}

internal data class TransferNode(
    val relative: String,
    val stat: TransferStat,
    val bytes: Long = 0,
    val digest: String? = null,
    val link: String? = null,
)

internal data class TransferVersion(val nodes: List<TransferNode>) {
    val kind: TransferKind get() = nodes.first().stat.kind

    fun sameContent(other: TransferVersion): Boolean = nodes.size == other.nodes.size &&
        nodes.zip(other.nodes).all { (left, right) ->
            left.relative == right.relative && left.stat.kind == right.stat.kind &&
                left.bytes == right.bytes && left.digest == right.digest && left.link == right.link
        }
}

internal fun transferVersion(reader: TransferReader, check: () -> Unit): TransferVersion? {
    check()
    if (reader.stat("") == null) return null
    val nodes = ArrayList<TransferNode>()
    var pathChars = 0L
    fun visit(relative: String, depth: Int) {
        check()
        if (depth > TRANSFER_MAX_DEPTH || nodes.size >= TRANSFER_MAX_ENTRIES) {
            throw IOException("Transfer directory exceeds the entry or depth limit")
        }
        pathChars += relative.length
        if (pathChars > TRANSFER_MAX_PATH_CHARS) throw IOException("Transfer manifest exceeds the path limit")
        val before = reader.stat(relative) ?: throw IOException("Transfer source disappeared: $relative")
        val index = nodes.size
        nodes += TransferNode(relative, before)
        when (before.kind) {
            TransferKind.FILE -> {
                val hash = reader.openRead(relative).use { transferStream(it, null, before.size, check) }
                nodes[index] = TransferNode(relative, before, hash.bytes, hash.digest)
            }
            TransferKind.LINK -> {
                val target = reader.readLink(relative)
                if (target.length > TRANSFER_MAX_LINK_CHARS || '\u0000' in target) throw IOException("Invalid symbolic link")
                nodes[index] = TransferNode(relative, before, link = target)
            }
            TransferKind.DIRECTORY -> {
                val children = checkedTransferNames(reader.list(relative))
                for (name in children) visit(transferChild(relative, name), depth + 1)
                if (checkedTransferNames(reader.list(relative)) != children) throw IOException("Transfer directory changed")
            }
        }
        check()
        if (reader.stat(relative) != before) throw IOException("Transfer source changed during verification: $relative")
    }
    visit("", 0)
    check()
    return TransferVersion(nodes.toList())
}

internal data class TransferDigest(val bytes: Long, val digest: String)

internal fun transferStream(input: InputStream, output: OutputStream?, expectedSize: Long?, check: () -> Unit): TransferDigest {
    if (expectedSize != null && expectedSize < 0) throw IOException("Invalid transfer size")
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
    var bytes = 0L
    while (true) {
        check()
        val count = input.read(buffer, 0, buffer.size)
        check()
        if (count < 0) break
        if (count == 0) throw IOException("Transfer read made no progress")
        if (count > buffer.size || bytes > Long.MAX_VALUE - count) throw IOException("Invalid transfer read length")
        bytes += count
        if (expectedSize != null && bytes > expectedSize) throw IOException("Transfer source exceeded its declared size")
        output?.write(buffer, 0, count)
        digest.update(buffer, 0, count)
    }
    if (expectedSize != null && bytes != expectedSize) throw IOException("Transfer source ended before its declared size")
    check()
    return TransferDigest(bytes, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
}

internal fun checkedTransferNames(names: List<String>): List<String> {
    if (names.size > TRANSFER_MAX_ENTRIES) throw IOException("Transfer directory exceeds the entry limit")
    if (names.any { !validTransferName(it) } || names.toSet().size != names.size) {
        throw IOException("Invalid or duplicate transfer entry name")
    }
    return names.sorted()
}

internal fun validTransferName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
    '/' !in name && '\\' !in name && '\u0000' !in name && name.none { it.isISOControl() }

internal fun transferChild(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

internal fun transferRelative(relative: String): String {
    if (relative.isNotEmpty() && relative.split('/').any { !validTransferName(it) }) {
        throw IOException("Invalid transfer relative path")
    }
    return relative
}

internal class HostTransferBackend(
    private val fileAt: (relative: String, followFinal: Boolean) -> File,
    private val check: () -> Unit,
) : TransferBackend {
    private val root = fileAt("", false).toPath().toAbsolutePath().normalize()
    private fun checkedFile(relative: String, followFinal: Boolean): File {
        check()
        transferRelative(relative)
        if (fileAt("", false).toPath().toAbsolutePath().normalize() != root) throw IOException("Transfer host root changed")
        val expected = if (relative.isEmpty()) root else root.resolve(relative)
        var parent = expected.parent
        while (relative.isNotEmpty() && parent != null && parent.startsWith(root)) {
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Unsafe transfer source parent")
            if (parent == root) break
            parent = parent.parent
        }
        val resolved = fileAt(relative, followFinal).toPath().toAbsolutePath().normalize()
        if (!followFinal && resolved != expected) throw IOException("Transfer source parent changed")
        return resolved.toFile()
    }
    private val reader = HostTransferReader(::checkedFile, check)
    override fun stat(relative: String): TransferStat? = reader.stat(relative)
    override fun list(relative: String): List<String> = reader.list(relative)
    override fun openRead(relative: String): InputStream = reader.openRead(relative)
    override fun readLink(relative: String): String = reader.readLink(relative)
    override fun isExecutable(relative: String): Boolean = reader.isExecutable(relative)

    override fun createStage(kind: TransferKind): TransferStage {
        check()
        val target = checkedFile("", false).toPath()
        val parent = target.parent ?: throw IOException("Transfer target requires a parent directory")
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Transfer target parent is not a directory")
        val stage = parent.resolve(".aicode-transfer-${UUID.randomUUID()}")
        // A directory owns the namespace even for a single-file transfer; no pre-existing entry is ever reused.
        Files.createDirectory(stage)
        return try {
            HostTransferStage(stage, target, kind, check) {
                if (checkedFile("", false).toPath() != target) throw IOException("Transfer target path changed")
            }
        } catch (failure: Throwable) {
            runCatching { Files.delete(stage) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun deleteEntry(relative: String) {
        check()
        Files.delete(checkedFile(transferRelative(relative), false).toPath())
    }
}

internal class HostTransferReader(
    private val fileAt: (String, Boolean) -> File,
    private val check: () -> Unit,
) : TransferReader {
    private fun path(relative: String): Path {
        check()
        return fileAt(transferRelative(relative), false).toPath()
    }

    override fun stat(relative: String): TransferStat? {
        val path = path(relative)
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: java.nio.file.NoSuchFileException) {
            return null.also { check() }
        }
        check()
        val kind = when {
            attrs.isSymbolicLink -> TransferKind.LINK
            attrs.isDirectory -> TransferKind.DIRECTORY
            attrs.isRegularFile -> TransferKind.FILE
            else -> throw IOException("Unsupported transfer entry type: $relative")
        }
        return TransferStat(kind, if (kind == TransferKind.FILE) attrs.size() else null,
            attrs.lastModifiedTime().toString(), attrs.fileKey()?.toString())
    }

    override fun list(relative: String): List<String> {
        val path = path(relative)
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw IOException("Transfer directory required")
        val names = ArrayList<String>()
        Files.newDirectoryStream(path).use { stream ->
            for (entry in stream) {
                check()
                if (names.size >= TRANSFER_MAX_ENTRIES) throw IOException("Transfer directory exceeds the entry limit")
                names += entry.fileName.toString()
            }
        }
        check()
        return checkedTransferNames(names)
    }

    override fun openRead(relative: String): InputStream {
        val path = path(relative)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Regular transfer file required")
        val checked = identityCheckedRead(Files.newInputStream(path, NOFOLLOW_LINKS), check)
        return WorkspaceExecutionContext.current()?.protectRead(checked) ?: checked
    }

    override fun readLink(relative: String): String = Files.readSymbolicLink(path(relative)).toString().also { check() }

    override fun isExecutable(relative: String): Boolean {
        val file = path(relative)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("Regular transfer file required")
        return Files.isExecutable(file).also { check() }
    }
}

private class HostTransferStage(
    private val owned: Path,
    private val target: Path,
    kind: TransferKind,
    private val check: () -> Unit,
    private val checkTarget: () -> Unit,
) : TransferStage {
    private val ownerKey = Files.readAttributes(owned, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    private val payload = owned.resolve("payload")
    private var preservePrevious = false
    override var committed: Boolean = false
        private set
    private val reader = HostTransferReader({ relative, _ -> stagePath(relative).toFile() }, ::checkOwned)

    init {
        if (kind == TransferKind.DIRECTORY) Files.createDirectory(payload)
    }

    private fun checkOwned() {
        check()
        if (!ownsStage()) throw IOException("Transfer staging ownership changed")
        checkTarget()
    }

    private fun ownsStage(): Boolean = try {
        val attrs = Files.readAttributes(owned, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        attrs.isDirectory && (ownerKey == null || attrs.fileKey() == ownerKey)
    } catch (_: java.nio.file.NoSuchFileException) { false }

    private fun stagePath(relative: String): Path {
        transferRelative(relative)
        val path = if (relative.isEmpty()) payload else payload.resolve(relative)
        var parent = path.parent
        while (parent != null && parent != owned) {
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) throw IOException("Unsafe transfer staging parent")
            parent = parent.parent
        }
        if (parent != owned) throw IOException("Transfer path outside staging")
        return path
    }

    override fun stat(relative: String): TransferStat? = reader.stat(relative)
    override fun list(relative: String): List<String> = reader.list(relative)
    override fun openRead(relative: String): InputStream = reader.openRead(relative)
    override fun readLink(relative: String): String = reader.readLink(relative)
    override fun isExecutable(relative: String): Boolean = reader.isExecutable(relative)

    override fun mkdir(relative: String) {
        checkOwned()
        Files.createDirectory(stagePath(relative))
    }

    override fun writeFile(relative: String, writer: (OutputStream) -> Unit) {
        checkOwned()
        FileChannel.open(stagePath(relative), CREATE_NEW, WRITE).use { channel ->
            writer(Channels.newOutputStream(channel))
            checkOwned()
            channel.force(true)
        }
    }

    override fun writeLink(relative: String, target: String) {
        checkOwned()
        if (target.length > TRANSFER_MAX_LINK_CHARS || '\u0000' in target) throw IOException("Invalid symbolic link")
        Files.createSymbolicLink(stagePath(relative), File(target).toPath())
    }

    override fun setExecutable(relative: String, executable: Boolean) {
        checkOwned()
        val path = stagePath(relative)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Regular staging file required")
        if (Files.isExecutable(path) != executable && !path.toFile().setExecutable(executable, true)) {
            throw IOException("Cannot set staging executable permission")
        }
        checkOwned()
    }

    override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) {
        checkOwned()
        checkBeforeCommit()
        checkOwned()
        if (overwrite && Files.isDirectory(target, NOFOLLOW_LINKS)) {
            val previous = owned.resolve("previous")
            Files.move(target, previous)
            try {
                checkOwned()
                Files.move(payload, target)
            } catch (failure: Throwable) {
                try {
                    if (!ownsStage() || Files.exists(target, NOFOLLOW_LINKS)) throw IOException("Replaced directory cannot be restored safely")
                    Files.move(previous, target)
                } catch (restore: Throwable) {
                    preservePrevious = true
                    failure.addSuppressed(restore)
                }
                throw failure
            }
        } else if (overwrite) {
            Files.move(payload, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } else {
            // ATOMIC_MOVE alone may overwrite on Unix. The no-replace move contract also works on shared storage.
            Files.move(payload, target)
        }
        committed = true
    }

    override fun close() {
        // Cleanup uses the originally owned inode, never the mutable target or a re-resolved backend path.
        if (!ownsStage() || preservePrevious) return
        Files.walkFileTree(owned, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (!ownsStage()) throw IOException("Transfer staging ownership changed")
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (!ownsStage()) throw IOException("Transfer staging ownership changed")
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                if (!ownsStage()) throw IOException("Transfer staging ownership changed")
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
