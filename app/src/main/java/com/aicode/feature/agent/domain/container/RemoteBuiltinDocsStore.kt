package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean

internal class RemoteBuiltinDocsStore(
    private val client: SFTPClient,
    home: String,
    private val checkCurrent: () -> Unit
) : BuiltinDocsSync.Backend {
    private val home = home.also { require(it.startsWith('/')) { "Remote home must be absolute" } }
        .trimEnd('/').ifEmpty { "/" }
    private val aicode = this.home.trimEnd('/') + "/.aicode"
    private val root = "$aicode/docs"

    init {
        if (this.home != "/") BuiltinDocsSync.validateRelativePath(this.home.removePrefix("/"))
    }

    private fun attributes(path: String): FileAttributes? {
        checkCurrent()
        val attrs = try {
            client.lstat(path)
        } catch (error: SFTPException) {
            if (error.statusCode != Response.StatusCode.NO_SUCH_FILE &&
                error.statusCode != Response.StatusCode.NO_SUCH_PATH) throw error
            null
        }
        checkCurrent()
        return attrs
    }

    private fun requireDirectory(path: String) {
        check(attributes(path)?.type == FileMode.Type.DIRECTORY) { "Unsafe remote docs directory" }
    }

    private fun checkHome() {
        requireDirectory("/")
        var parent = ""
        home.split('/').filter { it.isNotEmpty() }.forEach { component ->
            parent += "/$component"
            requireDirectory(parent)
        }
    }

    private fun validatePath(path: String) {
        if (path.isEmpty() || path == BuiltinDocsSync.STATE_FILE ||
            path == BuiltinDocsSync.STAGING_DIR || path == BuiltinDocsSync.BACKUP_DIR) return
        BuiltinDocsSync.validateRelativePath(path)
        when (path.substringBefore('/')) {
            BuiltinDocsSync.STAGING_DIR -> require(
                Regex("[0-9a-f]{64}\\.part").matches(path.substringAfter('/'))
            ) { "Invalid built-in docs staging path" }
            BuiltinDocsSync.BACKUP_DIR -> {
                val parts = path.split('/', limit = 3)
                require(parts.size >= 2 && BuiltinDocsSync.HASH.matches(parts[1])) { "Invalid built-in docs backup path" }
                if (parts.size == 3) BuiltinDocsSync.validateRelativePath(parts[2])
            }
            else -> BuiltinDocsSync.validateRelativePath(path)
        }
    }

    private fun checkedPath(path: String): String? {
        validatePath(path)
        checkHome()
        val parents = listOf(aicode) + if (path.isEmpty()) emptyList() else
            path.split('/').dropLast(1).runningFold(root) { parent, part -> "$parent/$part" }
        for (parent in parents) {
            val attrs = attributes(parent) ?: return null
            check(attrs.type == FileMode.Type.DIRECTORY) { "Unsafe remote docs parent" }
        }
        return if (path.isEmpty()) root else "$root/$path"
    }

    override fun type(path: String): BuiltinDocsSync.EntryType? = checkedPath(path)?.let(::attributes)?.let {
        when (it.type) {
            FileMode.Type.REGULAR -> BuiltinDocsSync.EntryType.FILE
            FileMode.Type.DIRECTORY -> BuiltinDocsSync.EntryType.DIRECTORY
            FileMode.Type.SYMLINK -> BuiltinDocsSync.EntryType.SYMLINK
            else -> BuiltinDocsSync.EntryType.OTHER
        }
    }

    private fun mkdir(path: String) {
        if (attributes(path) == null) {
            checkCurrent()
            client.sftpEngine.makeDir(path, FileAttributes.Builder().withPermissions(448).build())
            checkCurrent()
        }
        requireDirectory(path)
    }

    override fun createDirectory(path: String) {
        validatePath(path)
        checkHome()
        if (path.isEmpty()) {
            mkdir(aicode)
            mkdir(root)
        } else {
            mkdir(checkNotNull(checkedPath(path)))
        }
    }

    override fun readState(): ByteArray {
        val output = ByteArrayOutputStream()
        readChunks(BuiltinDocsSync.STATE_FILE, BuiltinDocsSync.MAX_STATE_BYTES) { bytes, count ->
            output.write(bytes, 0, count)
        }
        return output.toByteArray()
    }

    override fun fileHash(path: String): String = hash(path, limit(path)).second

    private fun limit(path: String): Int = if (path == BuiltinDocsSync.STATE_FILE) {
        BuiltinDocsSync.MAX_STATE_BYTES
    } else BuiltinDocsSync.MAX_DOCUMENT_BYTES

    private fun hash(path: String, maxBytes: Int): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val length = readChunks(path, maxBytes) { bytes, count -> digest.update(bytes, 0, count) }
        return length to BuiltinDocsSync.hexDigest(digest.digest())
    }

    private fun regular(path: String, maxBytes: Int): FileAttributes {
        val attrs = checkNotNull(attributes(path)) { "Missing built-in docs file" }
        check(attrs.type == FileMode.Type.REGULAR && attrs.size in 0..maxBytes.toLong()) {
            "Unsafe or oversized built-in docs file"
        }
        return attrs
    }

    private fun unchanged(before: FileAttributes, after: FileAttributes) {
        check(after.type == FileMode.Type.REGULAR && before.size == after.size && before.mtime == after.mtime) {
            "Built-in docs file changed during I/O"
        }
    }

    private fun readChunks(path: String, maxBytes: Int, consume: (ByteArray, Int) -> Unit): Long {
        val remote = checkNotNull(checkedPath(path))
        val before = regular(remote, maxBytes)
        checkCurrent()
        client.open(remote, EnumSet.of(OpenMode.READ)).use { file ->
            unchanged(before, regular(checkNotNull(checkedPath(path)), maxBytes))
            unchanged(before, file.fetchAttributes())
            val bytes = ByteArray(32 * 1024)
            var offset = 0L
            while (offset < before.size) {
                checkCurrent()
                val requested = minOf(bytes.size.toLong(), before.size - offset).toInt()
                val count = file.read(offset, bytes, 0, requested)
                checkCurrent()
                check(count in 1..requested) { "Incomplete built-in docs file" }
                consume(bytes, count)
                offset += count
            }
            checkCurrent()
            check(file.read(offset, bytes, 0, 1) == -1) { "Built-in docs file grew during I/O" }
            checkCurrent()
            unchanged(before, file.fetchAttributes())
        }
        unchanged(before, regular(checkNotNull(checkedPath(path)), maxBytes))
        checkCurrent()
        return before.size
    }

    private fun expect(path: String, expectedHash: String?) {
        val remote = checkNotNull(checkedPath(path))
        val attrs = attributes(remote)
        if (expectedHash == null) {
            check(attrs == null) { "Built-in docs destination appeared during I/O" }
        } else {
            check(attrs?.type == FileMode.Type.REGULAR && fileHash(path) == expectedHash) {
                "Built-in docs destination changed during I/O"
            }
        }
    }

    override fun replaceAtomically(path: String, bytes: ByteArray, expectedHash: String?) {
        require(bytes.size <= limit(path)) { "Built-in docs payload is too large" }
        val hash = BuiltinDocsSync.sha256(bytes)
        replace(path, bytes.size.toLong(), hash, expectedHash, overwrite = expectedHash != null) { file ->
            var offset = 0
            while (offset < bytes.size) {
                checkCurrent()
                val count = minOf(32 * 1024, bytes.size - offset)
                file.write(offset.toLong(), bytes, offset, count)
                checkCurrent()
                offset += count
            }
        }
    }

    override fun backupFile(path: String, backupPath: String, expectedHash: String) {
        val (length, sourceHash) = hash(path, BuiltinDocsSync.MAX_DOCUMENT_BYTES)
        check(sourceHash == expectedHash) { "Built-in docs backup source changed" }
        replace(backupPath, length, expectedHash, null, overwrite = false) { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            readChunks(path, BuiltinDocsSync.MAX_DOCUMENT_BYTES) { bytes, count ->
                digest.update(bytes, 0, count)
                checkCurrent()
                file.write(offset, bytes, 0, count)
                checkCurrent()
                offset += count
            }
            check(offset == length && BuiltinDocsSync.hexDigest(digest.digest()) == expectedHash) {
                "Built-in docs source changed during backup"
            }
        }
        check(fileHash(backupPath) == expectedHash) { "Built-in docs backup verification failed" }
    }

    private fun cleanupTemporary(path: String) {
        val remote = checkedPath(BuiltinDocsSync.temporaryPath(path)) ?: return
        val attrs = attributes(remote) ?: return
        check(attrs.type == FileMode.Type.REGULAR && attrs.size in 0..limit(path).toLong()) {
            "Unsafe built-in docs staging file"
        }
        checkCurrent()
        client.rm(remote)
        checkCurrent()
    }

    private fun replace(
        path: String,
        length: Long,
        expectedContentHash: String,
        expectedPreviousHash: String?,
        overwrite: Boolean,
        write: (RemoteFile) -> Unit
    ) {
        expect(path, expectedPreviousHash)
        createDirectory(BuiltinDocsSync.STAGING_DIR)
        cleanupTemporary(path)
        val temporaryPath = BuiltinDocsSync.temporaryPath(path)
        val temporary = checkNotNull(checkedPath(temporaryPath))
        checkCurrent()
        client.open(temporary, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL),
            FileAttributes.Builder().withPermissions(384).build()).use { file ->
            write(file)
        }
        val (actualLength, actualHash) = hash(temporaryPath, limit(path))
        check(actualLength == length && actualHash == expectedContentHash) { "Built-in docs staged payload mismatch" }
        expect(path, expectedPreviousHash)
        val remote = checkNotNull(checkedPath(path))
        checkCurrent()
        if (overwrite) {
            client.rename(temporary, remote, EnumSet.of(RenameFlags.ATOMIC, RenameFlags.OVERWRITE))
        } else {
            // Standard SFTP RENAME refuses a newly appeared destination, including a competing backup.
            client.rename(temporary, remote)
        }
        checkCurrent()
    }

    override fun deleteFile(path: String, expectedHash: String) {
        BuiltinDocsSync.validateDocumentPath(path)
        expect(path, expectedHash)
        val remote = checkNotNull(checkedPath(path))
        checkCurrent()
        client.rm(remote)
        checkCurrent()
    }
}

@OptIn(InternalCoroutinesApi::class)
internal suspend fun <T> withBuiltinDocsChannel(channel: SFTPClient, block: () -> T): T {
    val closed = AtomicBoolean()
    fun close() {
        if (closed.compareAndSet(false, true)) channel.close()
    }
    val hook = currentCoroutineContext()[Job]!!.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
        if (cause != null) {
            try {
                close()
            } catch (failure: Exception) {
                cause.addSuppressed(failure)
            }
        }
    }
    var failure: Throwable? = null
    try {
        val result = withContext(Dispatchers.IO) { runInterruptible { block() } }
        currentCoroutineContext().ensureActive()
        return result
    } catch (error: Throwable) {
        failure = error
        currentCoroutineContext().ensureActive()
        throw error
    } finally {
        hook.dispose()
        try {
            close()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (failure == null) throw error else failure.addSuppressed(error)
        }
    }
}
