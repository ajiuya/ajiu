package com.aicode.feature.agent.domain.container

import com.aicode.feature.agent.domain.subagent.BuiltinAgentPublication
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

internal class LocalBuiltinDefinitionStore(
    aicodeDir: File,
    private val afterAgentChunk: (Path, Long) -> Unit = { _, _ -> }
) : BuiltinDefinitionStore {
    private val root = aicodeDir.absoluteFile.let { directory ->
        directory.parentFile!!.canonicalFile.resolve(directory.name).toPath()
    }
    private val owned = HashMap<String, Any>()

    companion object {
        private val installLock = ReentrantLock()
        private val directoryMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        private val fileMode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

        fun install(
            aicodeDir: File,
            agents: Map<String, ByteArray> = emptyMap(),
            skills: Map<String, Map<String, ByteArray>> = emptyMap(),
            onFailure: (String, Exception) -> Unit = { _, _ -> }
        ): BuiltinInstallReport {
            installLock.lockInterruptibly()
            return try {
                BuiltinDefinitionInstaller(LocalBuiltinDefinitionStore(aicodeDir), onFailure = onFailure).install(agents, skills)
            } finally { installLock.unlock() }
        }
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun requireDirectory(path: Path) {
        check(attributes(path)?.isDirectory == true) { "Unsafe builtin directory: $path" }
    }

    private fun ensureRoot() {
        var ancestor = root.root
        requireDirectory(ancestor)
        for (component in root.parent) {
            ancestor = ancestor.resolve(component)
            requireDirectory(ancestor)
        }
        try { Files.createDirectory(root, directoryMode) } catch (_: FileAlreadyExistsException) { }
        requireDirectory(root)
    }

    private fun checked(path: String): Path {
        BuiltinDefinitionPaths.managed(path)
        ensureRoot()
        var parent = root
        path.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            requireDirectory(parent)
        }
        return root.resolve(path)
    }

    override fun node(path: String): BuiltinNode {
        val attrs = attributes(checked(path)) ?: return BuiltinNode.MISSING
        return when {
            attrs.isDirectory -> BuiltinNode.DIRECTORY
            attrs.isRegularFile -> BuiltinNode.FILE
            else -> BuiltinNode.OTHER
        }
    }

    override fun children(path: String): List<String> {
        val dir = checked(path)
        requireDirectory(dir)
        val identity = attributes(dir)?.fileKey()
        val names = Files.newDirectoryStream(dir).use { stream ->
            stream.asSequence().take(BuiltinDefinitionPaths.MAX_SCAN_ENTRIES + 1).map { it.fileName.toString() }.toList()
        }
        check(identity != null && attributes(checked(path))?.fileKey() == identity) { "Builtin directory changed" }
        check(names.size <= BuiltinDefinitionPaths.MAX_SCAN_ENTRIES) { "Too many builtin candidates" }
        return names
    }

    override fun readDefinition(path: String, maxBytes: Int): ByteArray? {
        require(maxBytes in 1..BuiltinDefinitionPaths.MAX_DEFINITION_BYTES)
        val dest = checked(path)
        val before = attributes(dest) ?: return null
        if (!before.isRegularFile || before.size() > maxBytes || before.fileKey() == null) return null
        val bytes = FileChannel.open(dest, setOf(READ, NOFOLLOW_LINKS)).use { input ->
            if (input.size() != before.size()) return null
            val buffer = ByteBuffer.allocate(before.size().toInt())
            while (buffer.hasRemaining()) {
                checkBuiltinInstallThread()
                if (input.read(buffer) < 0) return null
            }
            if (input.read(ByteBuffer.allocate(1)) >= 0) return null
            buffer.array()
        }
        val after = attributes(checked(path)) ?: return null
        return bytes.takeIf {
            after.isRegularFile && after.fileKey() == before.fileKey() && after.size() == before.size() &&
                after.lastModifiedTime() == before.lastModifiedTime()
        }
    }

    override fun ensureDirectory(path: String) {
        BuiltinDefinitionPaths.managed(path)
        ensureRoot()
        var current = root
        for (component in path.split('/')) {
            requireDirectory(current)
            current = current.resolve(component)
            try { Files.createDirectory(current, directoryMode) } catch (_: FileAlreadyExistsException) { }
            requireDirectory(current)
        }
    }

    override fun createDirectory(path: String) {
        val dest = checked(path)
        Files.createDirectory(dest, directoryMode)
        attributes(dest)?.fileKey()?.let { owned[path] = it }
    }

    override fun writeNew(path: String, bytes: ByteArray) {
        val dest = checked(path)
        FileChannel.open(dest, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), fileMode).use { channel ->
            attributes(dest)?.fileKey()?.let { owned[path] = it }
            var offset = 0
            while (offset < bytes.size) {
                checkBuiltinInstallThread()
                val buffer = ByteBuffer.wrap(bytes, offset, minOf(32 * 1024, bytes.size - offset))
                while (buffer.hasRemaining()) channel.write(buffer)
                offset = buffer.position()
            }
            channel.force(true)
        }
    }

    override fun publish(staged: String, target: String, directory: Boolean): Boolean {
        val source = checked(staged)
        val dest = checked(target)
        check(node(staged) == if (directory) BuiltinNode.DIRECTORY else BuiltinNode.FILE)
        if (!directory) return publishAgent(source, dest, target)
        // ATOMIC_MOVE can replace empty directories. Reserve the name first and verify the inode;
        // concurrent user content makes rename fail rather than deleting or merging that content.
        try { Files.createDirectory(dest, directoryMode) } catch (_: FileAlreadyExistsException) { return false }
        val identity = attributes(dest)?.fileKey()
        try {
            check(identity != null && attributes(dest)?.fileKey() == identity && emptyDirectory(dest)) {
                "Builtin publication reservation changed"
            }
            checked(target)
            Files.move(source, dest, ATOMIC_MOVE, REPLACE_EXISTING)
            owned.remove(staged)
            return true
        } finally {
            if (identity != null && attributes(dest)?.fileKey() == identity && emptyDirectory(dest)) {
                Files.deleteIfExists(dest)
            }
        }
    }

    private fun publishAgent(source: Path, dest: Path, target: String): Boolean = BuiltinAgentPublication.write {
        checked(target)
        val output = try {
            FileChannel.open(dest, setOf(CREATE_NEW, WRITE, APPEND, NOFOLLOW_LINKS), fileMode)
        } catch (_: FileAlreadyExistsException) { return@write false }
        var identity: Any? = null
        var copied = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        var completeHash: ByteArray? = null
        var completed = false
        try {
            output.use { out ->
                val created = attributes(dest)
                check(created?.isRegularFile == true && created.size() == 0L && created.fileKey() != null) {
                    "Builtin agent claim changed"
                }
                identity = created.fileKey()
                FileChannel.open(source, setOf(READ, NOFOLLOW_LINKS)).use { input ->
                    val buffer = ByteBuffer.allocate(32 * 1024)
                    while (true) {
                        checkBuiltinInstallThread()
                        check(attributes(checked(target))?.let { it.fileKey() == identity && it.size() == copied } == true) {
                            "Builtin agent owner or length changed"
                        }
                        buffer.clear()
                        if (input.read(buffer) < 0) break
                        buffer.flip()
                        while (buffer.hasRemaining()) {
                            checkBuiltinInstallThread()
                            val start = buffer.position()
                            val written = out.write(buffer)
                            digest.update(buffer.array(), start, written)
                            copied += written
                        }
                        afterAgentChunk(dest, copied)
                    }
                }
                out.force(true)
                completeHash = digest.digest()
                check(matchesAgent(dest, identity, copied, completeHash!!)) { "Builtin agent changed during publication" }
            }
            completed = true
            true
        } finally {
            if (!completed) {
                // Do not unlink a user's replacement, edit or append, even after our own copy fails.
                val prefixHash = completeHash ?: digest.digest()
                val interrupted = Thread.interrupted()
                try {
                    runCatching {
                        if (matchesAgent(checked(target), identity, copied, prefixHash) &&
                            attributes(dest)?.fileKey() == identity) Files.deleteIfExists(dest)
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun matchesAgent(path: Path, identity: Any?, size: Long, expectedHash: ByteArray): Boolean {
        if (identity == null) return false
        fun same(): Boolean = attributes(path)?.let {
            it.isRegularFile && it.fileKey() == identity && it.size() == size
        } == true
        if (!same()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, setOf(READ, NOFOLLOW_LINKS)).use { input ->
            if (input.size() != size) return false
            val buffer = ByteBuffer.allocate(32 * 1024)
            var read = 0L
            while (true) {
                buffer.clear()
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                if (read > size) return false
                digest.update(buffer.array(), 0, count)
            }
            if (read != size) return false
        }
        return same() && digest.digest().contentEquals(expectedHash)
    }

    private fun emptyDirectory(path: Path): Boolean =
        attributes(path)?.isDirectory == true && Files.newDirectoryStream(path).use { !it.iterator().hasNext() }

    override fun isReceipt(path: String): Boolean = attributes(checked(path))?.let { it.isRegularFile && it.size() == 0L } == true

    override fun remove(path: String) {
        val identity = owned[path] ?: return
        val dest = checked(path)
        if (attributes(dest)?.fileKey() == identity) Files.deleteIfExists(dest)
        owned.remove(path)
    }
}
