package com.aicode.feature.agent.domain.memory

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** 目录在主线程捕获，后续 IO 不再查询工作区、执行模式或 SSH 连接。 */
data class MemoryStorageTarget(val globalRoot: File, val projectRoot: File?)

data class MemoryEntry(
    val scope: MemoryScope,
    val fileName: String,
    val name: String,
    val description: String,
    val preview: String,
    val version: String,
    val modifiedAt: Long? = null
) {
    val id: String get() = "${scope.name}:$fileName"
}

data class MemoryDocument(val entry: MemoryEntry, val content: String)

enum class MemoryManagementError {
    INVALID_NAME, NAME_CONFLICT, NO_WORKSPACE, NOT_FOUND, CHANGED, UNSAFE_PATH, IO_FAILED
}

sealed interface MemoryManagementResult<out T> {
    data class Success<T>(val value: T) : MemoryManagementResult<T>
    data class Failure(val error: MemoryManagementError) : MemoryManagementResult<Nothing>
}

internal class MemoryStorageException(val error: MemoryManagementError) : IOException()

internal object MemoryStoragePaths {
    // Android 的私有 filesDir 可能经 /data/user/0 系统别名访问；只解析该可信前缀，保留其下目录供链接校验。
    fun withAppDirectory(root: File, appDirectory: File): File {
        val prefix = appDirectory.absolutePath.trimEnd(File.separatorChar) + File.separator
        val path = root.absolutePath
        return if (path.startsWith(prefix)) File(appDirectory.canonicalFile, path.removePrefix(prefix)) else root
    }

    fun checkRoot(root: File): File {
        val original = root.absoluteFile
        if (original.path.any { Character.isISOControl(it) }) {
            throw MemoryStorageException(MemoryManagementError.UNSAFE_PATH)
        }
        var path = original.toPath()
        while (true) {
            if (Files.isSymbolicLink(path)) throw MemoryStorageException(MemoryManagementError.UNSAFE_PATH)
            path = path.parent ?: break
        }
        val absolute = original.toPath().normalize()
        val directory = absolute.toFile()
        if (directory.canonicalFile != directory ||
            (Files.exists(absolute, NOFOLLOW_LINKS) && !Files.isDirectory(absolute, NOFOLLOW_LINKS))
        ) {
            throw MemoryStorageException(MemoryManagementError.UNSAFE_PATH)
        }
        return directory
    }

    fun file(root: File, fileName: String): File {
        if (!fileName.endsWith(".md") || !validFileStem(fileName.removeSuffix(".md"))) {
            throw MemoryStorageException(MemoryManagementError.INVALID_NAME)
        }
        val directory = checkRoot(root)
        val file = File(directory, fileName)
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory ||
            (Files.exists(file.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        ) {
            throw MemoryStorageException(MemoryManagementError.UNSAFE_PATH)
        }
        return file
    }

    private fun validFileStem(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
        name.none { it == '/' || it == '\\' || Character.isISOControl(it) }

    fun validNewName(name: String): Boolean = validFileStem(name) && MemorySource.sanitizeName(name) == name
}

internal class MemoryFileStore(val rootDirectory: File, private val scope: MemoryScope) : MemorySource {
    override fun listMemories(): List<Memory> = synchronized(MemorySource.storageLock) {
        try {
            files().mapNotNull { file ->
                try {
                    read(file.name)?.let { document ->
                        Memory(document.entry.name, document.entry.description, scope, file, document.content)
                    }
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }.sortedBy { it.name.lowercase() }
        } catch (_: IOException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    override fun loadContent(name: String): String? = synchronized(MemorySource.storageLock) {
        listMemories().firstOrNull { it.name.equals(name, ignoreCase = true) }?.content
    }

    override fun saveMemory(name: String, description: String, content: String): Boolean =
        synchronized(MemorySource.storageLock) {
            try {
                val file = MemorySource.resolveMemoryFile(rootDirectory, name)
                prepareDirectory()
                MemoryStoragePaths.file(rootDirectory, file.name)
                val bytes = MemoryParser.format(MemorySource.sanitizeName(name), description, content).toByteArray(Charsets.UTF_8)
                Files.newByteChannel(file.toPath(), setOf(WRITE, java.nio.file.StandardOpenOption.CREATE, NOFOLLOW_LINKS)).use { channel ->
                    channel.truncate(0)
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

    override fun deleteMemory(name: String): Boolean = synchronized(MemorySource.storageLock) {
        try {
            val file = MemorySource.resolveMemoryFile(rootDirectory, name)
            Files.deleteIfExists(file.toPath())
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun entries(): List<MemoryEntry> = files().mapNotNull { file ->
        try {
            read(file.name)?.entry
        } catch (e: MemoryStorageException) {
            if (e.error == MemoryManagementError.UNSAFE_PATH || e.error == MemoryManagementError.INVALID_NAME) null else throw e
        }
    }.sortedBy { it.name.lowercase() }

    fun read(fileName: String): MemoryDocument? {
        val file = MemoryStoragePaths.file(rootDirectory, fileName)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val text = Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            java.nio.channels.Channels.newInputStream(channel).reader(Charsets.UTF_8).use { it.readText() }
        }
        MemoryStoragePaths.file(rootDirectory, fileName)
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (stamp(before) != stamp(after)) throw MemoryStorageException(MemoryManagementError.CHANGED)
        val memory = MemoryParser.parseText(text, file, scope)
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MemoryDocument(
            MemoryEntry(scope, fileName, memory.name, memory.description, memory.content.take(160), "${stamp(after)}:$digest",
                modifiedAt = after.lastModifiedTime().toMillis().takeIf { it > 0 }),
            memory.content
        )
    }

    fun createOnly(name: String, description: String, content: String) {
        if (!MemoryStoragePaths.validNewName(name)) throw MemoryStorageException(MemoryManagementError.INVALID_NAME)
        val file = MemoryStoragePaths.file(rootDirectory, "$name.md")
        if (entries().any { existing ->
                existing.fileName.equals(file.name, ignoreCase = true) || existing.name.equals(name, ignoreCase = true)
            }
        ) {
            throw MemoryStorageException(MemoryManagementError.NAME_CONFLICT)
        }
        prepareDirectory()
        MemoryStoragePaths.file(rootDirectory, file.name)
        val bytes = MemoryParser.format(name, description, content).toByteArray(Charsets.UTF_8)
        try {
            Files.newByteChannel(file.toPath(), setOf(WRITE, CREATE_NEW, NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
        } catch (_: FileAlreadyExistsException) {
            throw MemoryStorageException(MemoryManagementError.NAME_CONFLICT)
        }
    }

    fun deleteEntry(entry: MemoryEntry) {
        val current = read(entry.fileName) ?: throw MemoryStorageException(MemoryManagementError.NOT_FOUND)
        if (entry.version != current.entry.version) throw MemoryStorageException(MemoryManagementError.CHANGED)
        val file = MemoryStoragePaths.file(rootDirectory, entry.fileName)
        Files.delete(file.toPath())
    }

    private fun files(): List<File> {
        val root = MemoryStoragePaths.checkRoot(rootDirectory)
        if (!root.exists()) return emptyList()
        return (root.listFiles() ?: throw IOException("Cannot list memory directory"))
            .filter { it.name.endsWith(".md") && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(it.toPath()) }
    }

    private fun prepareDirectory() {
        val root = MemoryStoragePaths.checkRoot(rootDirectory)
        Files.createDirectories(root.toPath())
        MemoryStoragePaths.checkRoot(rootDirectory)
    }

    private fun stamp(attributes: BasicFileAttributes): String =
        "${attributes.fileKey()}:${attributes.creationTime()}:${attributes.lastModifiedTime()}:${attributes.size()}"
}
