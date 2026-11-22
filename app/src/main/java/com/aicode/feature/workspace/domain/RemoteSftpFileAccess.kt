package com.aicode.feature.workspace.domain

import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.core.util.boundedLines
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.friendlySshError
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper.Companion.CONTAINER_ROOT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response.StatusCode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Paths
import kotlin.io.FileAlreadyExistsException
import java.nio.file.FileSystemLoopException
import kotlin.io.NoSuchFileException
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val TAG = "RemoteSftpFileAccess"

/** 单次 SFTP 读写缓冲大小。 */
private const val IO_CHUNK = 32 * 1024

/** `readFile` / `readBytes` 的远程文件大小上限（字节）：超过即抛 [RemoteOutputTooLargeException]，避免整篇读进内存。 */
private const val MAX_REMOTE_READ_BYTES = 8L * 1024 * 1024

/** 远程文件超过上限时抛出：调用方据此给出「内容过大」提示，而不是误判为文件不存在。 */
class RemoteOutputTooLargeException(message: String) : IOException(message)

/**
 * [FileAccessProvider] 的远程实现：走 SFTP 协议读写远程文件。
 *
 * **独立通道**：SFTP 跑在 [RemoteSshConnection.sftp] 建立的第二条 SSH transport 上，与 Bash/命令的
 * exec 通道隔离——sshj 的 SFTP 有间歇性 Buffer 溢出（hierynomus/sshj#461），共用 transport 时崩溃会
 * 一并拖垮命令通道；分开后只影响文件读写，通道失效时丢弃、下次调用自动重建。
 *
 * 路径映射：AI 给的 `~/workspace/...` 映射到当前选中工作区的远程绝对路径；其它绝对路径（如 `/etc/...`）
 * 直接作为远程绝对路径使用。sshj 的 [SFTPClient] 非线程安全，所有操作经 [sftpMutex] 串行化。
 */
class RemoteSftpFileAccess @Inject constructor(
    private val connection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository,
    private val agentConfigCache: RemoteAgentConfigCache
) : FileAccessProvider {

    private val sftpMutex get() = connection.sftpOperationMutex

    /** 当前选中工作区在远程服务器上的真实路径（如 /data/.../test/111）。 */
    private fun currentWorkspaceRoot(): String {
        val cfg = connection.config ?: throw IllegalStateException("SSH 未连接")
        // currentPath() 远程模式返回选中工作区的远程绝对路径；未选中时回退到 remoteWorkspacePath
        val path = workspaceRepository.currentPath()
        return if (path.isNotBlank() && path != "/") path else cfg.remoteWorkspacePath.trimEnd('/')
    }

    internal data class ExecutionSource(
        val identity: RemoteConnectionIdentity,
        val workspaceRoot: String,
        internal val agentConfigProfile: RemoteAgentConfigProfile
    )

    internal fun captureExecutionSource(): ExecutionSource {
        val identity = connection.captureIdentity()
        return ExecutionSource(identity, currentWorkspaceRoot(), agentConfigCache.captureProfile()).also(::checkExecutionSource)
    }

    internal fun checkExecutionSource(source: ExecutionSource) {
        connection.checkIdentity(source.identity)
        if (currentWorkspaceRoot() != source.workspaceRoot) throw CancellationException("Remote workspace changed")
    }

    private fun executionSource(): ExecutionSource? = WorkspaceExecutionContext.current()?.let {
        it.requireMode(com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        it.remoteSource ?: throw CancellationException("Remote execution source missing")
    }

    /** 把 AI 路径映射到远程服务器上的真实路径。 */
    private fun toRemotePath(path: String): String {
        val source = executionSource()
        return remotePathFor(path, source?.workspaceRoot ?: currentWorkspaceRoot(), source?.identity?.home ?: connection.remoteHome)
    }

    /** 把远程路径还原为 AI 视角的容器路径（回显用）。 */
    private fun toDisplayPathFromRemote(remotePath: String): String =
        displayPathFor(remotePath, executionSource()?.workspaceRoot ?: currentWorkspaceRoot())

    /**
     * 在独立 SFTP 通道上串行执行 [block]。传输层异常时丢弃当前通道（下次调用自动重建）后原样抛出；
     * 业务错误（文件不存在/已存在、SFTP 状态码错误）不重建。不做自动重试——写操作重试可能重复落盘。
     */
    private fun <T> withSftp(block: (SFTPClient) -> T): T = runBlocking(WorkspaceExecutionContext.blockingContext()) {
        withContext(Dispatchers.IO) {
            val execution = WorkspaceExecutionContext.current()
            if (execution != null) {
                val source = executionSource() ?: throw CancellationException("Remote execution source missing")
                val channel = connection.openReadChannel(source.identity)
                val cancellation = execution.onCancellation { channel.close() }
                try {
                    execution.checkCurrent()
                    return@withContext block(channel).also { execution.checkCurrent() }
                } finally {
                    cancellation?.dispose()
                    runCatching { channel.close() }
                }
            }
            sftpMutex.withLock {
                val sftp = try {
                    connection.sftp()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException(friendlySshError(e), e)
                }
                guarded { block(sftp) }
            }
        }
    }

    /**
     * 复用已打开的 SFTP 通道执行一次操作（不重新取 client）。供 [readLines] 惰性迭代使用：每次只锁住
     * 一次读取，`yield` 在锁外，调用方中途放弃迭代时不会把 [sftpMutex] 永久占住。
     */
    private fun <T> onSftp(block: () -> T): T = runBlocking(WorkspaceExecutionContext.blockingContext()) {
        withContext(Dispatchers.IO) { sftpMutex.withLock { guarded(block) } }
    }

    /** 传输层异常时丢弃 SFTP 通道（下次调用自动重建）后原样抛出；业务错误不重建。 */
    private suspend fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e !is SFTPException && e !is NoSuchFileException && e !is FileAlreadyExistsException &&
            e !is FileReadTooLargeException && e !is FileSystemLoopException && e !is FilePathOverlapException
        ) {
            runCatching { connection.invalidateSftp() }
        }
        throw e
    }

    internal fun transferBackend(path: String, check: () -> Unit): TransferBackend {
        val execution = WorkspaceExecutionContext.current() ?: throw IOException("Captured transfer execution required")
        val source = executionSource() ?: throw IOException("Captured remote transfer source required")
        val remote = toRemotePath(path)
        val entry = withTransferSftp(source, check) { resolveRemotePath(it, remote, false) }
        fun checkSource() {
            check()
            execution.checkCurrent()
            checkExecutionSource(source)
        }
        return object : TransferBackend {
            private fun <T> access(relative: String, block: (SFTPClient, String) -> T): T =
                withTransferSftp(source, ::checkSource) { channel ->
                    if (resolveRemotePath(channel, remote, false) != entry) throw IOException("Transfer remote path changed")
                    val child = transferRemoteChild(channel, entry, relative)
                    block(channel, child)
                }

            override fun stat(relative: String): TransferStat? = access(relative) { channel, child ->
                lstatOrNull(channel, child)?.let(::transferStat)
            }
            override fun list(relative: String): List<String> = access(relative) { channel, child ->
                transferList(channel, child)
            }
            override fun openRead(relative: String): InputStream {
                val child = access(relative) { channel, child ->
                    if (lstatOrNull(channel, child)?.type != FileMode.Type.REGULAR) throw IOException("Regular transfer file required")
                    child
                }
                return identityCheckedRead(openRead(child, execution.fileCacheIdentity), ::checkSource)
            }
            override fun readLink(relative: String): String = access(relative) { channel, child -> channel.readlink(child) }
            override fun isExecutable(relative: String): Boolean = access(relative) { channel, child ->
                val attrs = lstatOrNull(channel, child)
                if (attrs?.type != FileMode.Type.REGULAR) throw IOException("Regular transfer file required")
                attrs.permissions.any { it == FilePermission.USR_X || it == FilePermission.GRP_X || it == FilePermission.OTH_X }
            }
            override fun deleteEntry(relative: String) = access(relative) { channel, child ->
                val attrs = lstatOrNull(channel, child) ?: throw IOException("Transfer source disappeared before removal")
                if (attrs.type == FileMode.Type.DIRECTORY) channel.rmdir(child) else channel.rm(child)
            }
            override fun createStage(kind: TransferKind): TransferStage = access("") { channel, target ->
                val parent = target.substringBeforeLast('/', "").ifEmpty { "/" }
                if (lstatOrNull(channel, parent)?.type != FileMode.Type.DIRECTORY) throw IOException("Transfer target parent required")
                val owned = parent.trimEnd('/') + "/.aicode-transfer-" + java.util.UUID.randomUUID()
                channel.mkdir(owned)
                val stage = RemoteTransferStage(source, execution, owned, target, ::checkSource) {
                    if (resolveRemotePath(it, remote, false) != entry) throw IOException("Transfer remote target changed")
                }
                try {
                    stage.initialize(kind)
                    stage
                } catch (failure: Throwable) {
                    runCatching { stage.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                    runCatching { channel.rmdir(owned) }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }
        }
    }

    private fun <T> withTransferSftp(source: ExecutionSource, check: () -> Unit, block: (SFTPClient) -> T): T =
        runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            check()
            checkExecutionSource(source)
            rootReadChannel(source).use { owned ->
                check()
                block(owned.channel)
            }
        }

    private fun transferStat(attrs: FileAttributes): TransferStat {
        val kind = when (attrs.type) {
            FileMode.Type.REGULAR -> TransferKind.FILE
            FileMode.Type.DIRECTORY -> TransferKind.DIRECTORY
            FileMode.Type.SYMLINK -> TransferKind.LINK
            else -> throw IOException("Unsupported remote transfer entry type")
        }
        val size = if (kind == TransferKind.FILE) attrs.size else null
        if (size != null && size < 0) throw IOException("Invalid remote transfer size")
        return TransferStat(kind, size, attrs.mtime.toString())
    }

    private fun transferList(channel: SFTPClient, path: String): List<String> {
        if (lstatOrNull(channel, path)?.type != FileMode.Type.DIRECTORY) throw IOException("Transfer directory required")
        var count = 0
        val entries = channel.ls(path) { entry ->
            if (entry.name == "." || entry.name == "..") false else {
                if (++count > TRANSFER_MAX_ENTRIES) throw IOException("Transfer directory exceeds the entry limit")
                true
            }
        }
        return checkedTransferNames(entries.map { it.name })
    }

    private fun transferRemoteChild(channel: SFTPClient, root: String, relative: String): String {
        transferRelative(relative)
        var path = root
        if (relative.isNotEmpty()) {
            for (part in relative.split('/')) {
                if (lstatOrNull(channel, path)?.type != FileMode.Type.DIRECTORY) throw IOException("Unsafe transfer parent")
                path = path.trimEnd('/') + "/" + part
            }
        }
        return path
    }

    private inner class RemoteTransferStage(
        private val source: ExecutionSource,
        private val execution: WorkspaceExecutionContext,
        private val owned: String,
        private val target: String,
        private val check: () -> Unit,
        private val checkTarget: (SFTPClient) -> Unit,
    ) : TransferStage {
        private val marker = "$owned/.owner"
        private val token = java.util.UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
        private val payload = "$owned/payload"
        private val previous = "$owned/previous"
        private var preservePrevious = false
        override var committed: Boolean = false
            private set

        fun initialize(kind: TransferKind) {
            withTransferSftp(source, check) { channel ->
                channel.open(marker, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use {
                    it.write(0, token, 0, token.size)
                }
                if (kind == TransferKind.DIRECTORY) channel.mkdir(payload)
            }
        }

        private fun owns(channel: SFTPClient): Boolean {
            if (lstatOrNull(channel, owned)?.type != FileMode.Type.DIRECTORY ||
                lstatOrNull(channel, marker)?.let { it.type != FileMode.Type.REGULAR || it.size != token.size.toLong() } != false
            ) return false
            val found = ByteArray(token.size)
            channel.open(marker).use { file ->
                var offset = 0
                while (offset < found.size) {
                    val count = file.read(offset.toLong(), found, offset, found.size - offset)
                    if (count <= 0) return false
                    offset += count
                }
            }
            return token.contentEquals(found)
        }

        private fun <T> access(relative: String, block: (SFTPClient, String) -> T): T =
            withTransferSftp(source, check) { channel ->
                if (!owns(channel)) throw IOException("Remote transfer staging ownership changed")
                checkTarget(channel)
                val child = transferRemoteChild(channel, payload, relative)
                block(channel, child)
            }

        override fun stat(relative: String): TransferStat? = access(relative) { channel, child -> lstatOrNull(channel, child)?.let(::transferStat) }
        override fun list(relative: String): List<String> = access(relative) { channel, child -> transferList(channel, child) }
        override fun readLink(relative: String): String = access(relative) { channel, child -> channel.readlink(child) }
        override fun isExecutable(relative: String): Boolean = access(relative) { channel, child ->
            val attrs = lstatOrNull(channel, child)
            if (attrs?.type != FileMode.Type.REGULAR) throw IOException("Regular staging file required")
            attrs.permissions.any { it == FilePermission.USR_X || it == FilePermission.GRP_X || it == FilePermission.OTH_X }
        }
        override fun openRead(relative: String): InputStream {
            val child = access(relative) { channel, child ->
                if (lstatOrNull(channel, child)?.type != FileMode.Type.REGULAR) throw IOException("Regular transfer file required")
                child
            }
            return identityCheckedRead(openRead(child, execution.fileCacheIdentity), check)
        }
        override fun mkdir(relative: String) = access(relative) { channel, child -> channel.mkdir(child) }
        override fun writeLink(relative: String, target: String) = access(relative) { channel, child ->
            if (target.length > TRANSFER_MAX_LINK_CHARS || '\u0000' in target) throw IOException("Invalid symbolic link")
            channel.symlink(child, target)
        }
        override fun writeFile(relative: String, writer: (OutputStream) -> Unit) = access(relative) { channel, child ->
            channel.open(child, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { file ->
                var offset = 0L
                val output = object : OutputStream() {
                    override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
                    override fun write(buffer: ByteArray, start: Int, length: Int) {
                        if (start < 0 || length < 0 || start > buffer.size - length) throw IndexOutOfBoundsException()
                        var at = start
                        while (at < start + length) {
                            check()
                            val count = minOf(TRANSFER_BUFFER_BYTES, start + length - at)
                            file.write(offset, buffer, at, count)
                            offset += count
                            at += count
                        }
                    }
                }
                writer(output)
                check()
                if (file.length() != offset) throw IOException("Remote staging write length mismatch")
            }
        }
        override fun setExecutable(relative: String, executable: Boolean) = access(relative) { channel, child ->
            if (lstatOrNull(channel, child)?.type != FileMode.Type.REGULAR) throw IOException("Regular staging file required")
            channel.chmod(child, if (executable) 448 else 384)
            check()
        }
        override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) = access("") { channel, _ ->
            checkBeforeCommit()
            check()
            checkTarget(channel)
            if (!owns(channel)) throw IOException("Remote transfer staging ownership changed")
            if (overwrite && lstatOrNull(channel, target)?.type == FileMode.Type.DIRECTORY) {
                channel.rename(target, previous)
                try {
                    check()
                    checkTarget(channel)
                    if (!owns(channel) || lstatOrNull(channel, target) != null) throw IOException("Remote replacement target changed")
                    channel.rename(payload, target)
                } catch (failure: Throwable) {
                    try {
                        if (!owns(channel) || lstatOrNull(channel, target) != null) throw IOException("Remote replaced directory cannot be restored safely")
                        channel.rename(previous, target)
                    } catch (restore: Throwable) {
                        preservePrevious = true
                        failure.addSuppressed(restore)
                    }
                    throw failure
                }
            } else if (overwrite) {
                channel.rename(payload, target, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
            } else {
                if (lstatOrNull(channel, target) != null) throw IOException("Transfer target appeared before commit")
                channel.rename(payload, target)
            }
            committed = true
        }
        override fun close() {
            // Only owned staging cleanup is cancellation-independent; captured source identity is still mandatory.
            runBlocking(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                execution.checkCurrent()
                checkExecutionSource(source)
                connection.openReadChannel(source.identity).use { channel ->
                    if (!owns(channel) || preservePrevious) return@use
                    fun remove(path: String, depth: Int) {
                        if (depth > TRANSFER_MAX_DEPTH + 1) throw IOException("Staging cleanup depth exceeded")
                        execution.checkCurrent()
                        checkExecutionSource(source)
                        if (!owns(channel)) throw IOException("Remote staging ownership changed")
                        val attrs = lstatOrNull(channel, path) ?: return
                        if (attrs.type == FileMode.Type.DIRECTORY) {
                            for (name in transferList(channel, path)) remove(path.trimEnd('/') + "/" + name, depth + 1)
                            channel.rmdir(path)
                        } else channel.rm(path)
                    }
                    remove(payload, 0)
                    remove(previous, 0)
                    channel.rm(marker)
                    channel.rmdir(owned)
                }
            }
        }
    }

    override fun readFile(path: String): String = String(readAll(toRemotePath(path)), Charsets.UTF_8)

    /**
     * 惰性逐行读取：每次只物化当前一行（单行封顶 64K 字符），读到哪算哪，整文件不进内存。
     * 序列可重复迭代（每次迭代重新打开远程文件）；调用方需在 IO 线程上迭代。
     */
    override fun readLines(path: String): Sequence<String> {
        WorkspaceExecutionContext.current()?.let { execution ->
            execution.requireMode(com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
            return boundedLines { InputStreamReader(openRead(path, execution.fileCacheIdentity)) }
        }
        val remote = toRemotePath(path)
        return sequence {
            val reader = withSftp { sftp ->
                val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
                if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("是目录，无法按文件读取: $remote")
                BoundedLineReader(InputStreamReader(RemoteFileInputStream(sftp.open(remote))))
            }
            try {
                while (true) {
                    val line = onSftp { reader.readLine() } ?: break
                    yield(line.text)
                }
            } finally {
                runCatching { onSftp { reader.close() } }
            }
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun exists(path: String): Boolean = withSftp { it.statExistence(toRemotePath(path)) != null }

    override fun isDirectory(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.DIRECTORY }

    override fun isFile(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.REGULAR }

    override fun fileSize(path: String): Long =
        withSftp { it.statExistence(toRemotePath(path))?.size ?: 0L }

    override fun lastModified(path: String): Long =
        withSftp { (it.statExistence(toRemotePath(path))?.mtime ?: 0L) * 1000L }

    /**
     * 远端内容指纹。**必须按内容哈希而非 mtime**：SFTP 的 mtime 只到秒
     * （[lastModified] 就是把秒乘 1000），同一秒内的两次写入无法区分，会让并发校验漏判。
     * 私有捕获通道流式计算，仍受同一 8MB 上限约束；通道关闭可唤醒阻塞读取。
     */
    override fun fingerprint(path: String): String? = runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
        val execution = WorkspaceExecutionContext.current()
        val source = executionSource() ?: captureExecutionSource()
        fun checkSource() {
            coroutineContext.ensureActive()
            execution?.checkCurrent()
            checkExecutionSource(source)
        }
        checkSource()
        val remote = remotePathFor(path, source.workspaceRoot, source.identity.home)
        val channel = connection.openReadChannel(source.identity)
        val closed = AtomicBoolean()
        fun closeChannel() {
            if (closed.compareAndSet(false, true)) channel.close()
        }
        val cancellation = execution?.onCancellation { closeChannel() }
        var file: RemoteFile? = null
        try {
            checkSource()
            val attrs = channel.statExistence(remote) ?: return@runBlocking null.also { checkSource() }
            checkSource()
            if (attrs.type != FileMode.Type.REGULAR) return@runBlocking null
            if (attrs.size > MAX_REMOTE_READ_BYTES) throw RemoteOutputTooLargeException("Remote file exceeds the fingerprint read limit")
            val opened = channel.open(remote)
            file = opened
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(IO_CHUNK)
            var offset = 0L
            while (true) {
                checkSource()
                val length = minOf(buffer.size.toLong(), MAX_REMOTE_READ_BYTES - offset + 1).toInt()
                val count = opened.read(offset, buffer, 0, length)
                checkSource()
                if (count < 0) break
                if (count == 0) throw IOException("Remote file read made no progress")
                if (offset + count > MAX_REMOTE_READ_BYTES) throw RemoteOutputTooLargeException("Remote file exceeded the read limit during transfer")
                digest.update(buffer, 0, count)
                offset += count
            }
            checkSource()
            "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (failure: Throwable) {
            try { checkSource() } catch (changed: CancellationException) {
                if (changed !== failure) changed.addSuppressed(failure)
                throw changed
            }
            throw failure
        } finally {
            cancellation?.dispose()
            try { closeChannel() } finally { runCatching { file?.close() } }
        }
    }

    override fun permissions(path: String): String =
        withSftp { it.statExistence(toRemotePath(path))?.permissions?.let(::formatPermissions) ?: "---" }

    override fun listFiles(path: String): List<FileEntry> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            sftp.ls(remote).mapNotNull { info ->
                val name = info.name
                if (name == "." || name == "..") return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = info.isDirectory,
                    size = info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                    localFile = null,
                    permissions = formatPermissions(info.attributes.permissions)
                )
            }
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            val result = mutableListOf<String>()
            fun walk(dir: String, rel: String, depth: Int) {
                val entries = runCatching { sftp.ls(dir) }.getOrNull() ?: return
                for (info in entries) {
                    val name = info.name
                    if (name == "." || name == "..") continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (info.isDirectory) {
                        if (depth < maxDepth) walk(info.path, childRel, depth + 1)
                    } else {
                        result += childRel
                    }
                }
            }
            walk(remote, "", 1)
            result
        }
    }

    private fun cacheIdentity(identity: RemoteConnectionIdentity, workspaceRoot: String): String =
        "sftp:${identity.generation}:${identity.home.length}:${identity.home}:$workspaceRoot"

    override fun cacheIdentity(): String {
        WorkspaceExecutionContext.current()?.let {
            it.requireMode(com.aicode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
            return it.fileCacheIdentity
        }
        val identity = connection.captureIdentity()
        val root = currentWorkspaceRoot()
        connection.checkIdentity(identity)
        return cacheIdentity(identity, root)
    }

    override fun agentConfigSourceIdentity(): String =
        agentConfigCache.sourceIdentity(executionSource() ?: captureExecutionSource())

    override fun agentConfigSnapshot(root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot? =
        agentConfigCache.directory(executionSource() ?: captureExecutionSource(), root, kind, forceRefresh)

    override fun invalidateAgentConfig(path: String) = agentConfigCache.invalidation(toRemotePath(path)).invoke()

    override fun openRead(path: String): InputStream = openRead(path, cacheIdentity())

    override fun openRead(path: String, expectedCacheIdentity: String): InputStream =
        openRead(path, expectedCacheIdentity, playbackRoot = null)

    internal data class ReadRoot(
        val source: ExecutionSource,
        val remotePath: String,
        val canonicalPath: String,
    )

    internal fun captureReadRoot(path: String): ReadRoot =
        runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
            val source = executionSource() ?: captureExecutionSource()
            val remote = Paths.get(remotePathFor(path, source.workspaceRoot, source.identity.home)).normalize()
            if (!remote.isAbsolute || remote.parent == null) throw IOException("Directory read root required")
            try {
                rootReadChannel(source).use { owned ->
                    checkReadSource(source)
                    val canonical = Paths.get(owned.channel.canonicalize(remote.toString())).normalize()
                    if (!canonical.isAbsolute || canonical.parent == null) throw IOException("Directory read root required")
                    val attrs = owned.channel.statExistence(canonical.toString())
                        ?: throw NoSuchFileException(File(path))
                    if (attrs.type != FileMode.Type.DIRECTORY) throw IOException("Directory read root required")
                    checkReadSource(source)
                    ReadRoot(source, remote.toString(), canonical.toString())
                }
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                checkReadSource(source)
                throw failure
            }
        }

    internal fun rootReadSize(path: String, root: ReadRoot): Long =
        runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
            try {
                rootReadChannel(root.source).use { owned ->
                    val readable = confinedReadPath(owned.channel, path, root)
                    val size = owned.channel.statExistence(readable)?.size ?: throw NoSuchFileException(File(path))
                    if (size < 0) throw IOException("Invalid remote file size")
                    checkReadSource(root.source)
                    size
                }
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                checkReadSource(root.source)
                throw failure
            }
        }

    internal fun openRootRead(path: String, root: ReadRoot, maxBytes: Long): InputStream =
        runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
            val execution = WorkspaceExecutionContext.current()
            val owned = rootReadChannel(root.source)
            var file: RemoteFile? = null
            try {
                val readable = confinedReadPath(owned.channel, path, root)
                val size = owned.channel.statExistence(readable)?.size ?: throw NoSuchFileException(File(path))
                if (size < 0 || size > maxBytes) throw IOException("File exceeds read limit")
                checkReadSource(root.source)
                val opened = owned.channel.open(readable)
                file = opened
                if (confinedReadPath(owned.channel, path, root) != readable) {
                    throw CancellationException("Remote read target changed")
                }
                val stream = object : InputStream() {
                    private val input = RemoteFileInputStream(opened)
                    private val closed = AtomicBoolean()
                    private val single = ByteArray(1)
                    private var count = 0L
                    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val length = input.read(b, off, minOf(len.toLong(), IO_CHUNK.toLong(), maxBytes - count + 1).toInt())
                        if (length > maxBytes - count) throw IOException("File exceeded read limit during transfer")
                        if (length > 0) count += length
                        return length
                    }
                    override fun close() {
                        if (closed.compareAndSet(false, true)) {
                            try { owned.close() } finally { runCatching { input.close() } }
                        }
                    }
                }
                // An open handle stays bound to the authorized file; later path changes are checked on the next open.
                val checked = identityCheckedRead(stream) { checkReadSource(root.source) }
                execution?.protectRead(checked) ?: checked
            } catch (failure: Throwable) {
                val reported = try { currentCoroutineContext().ensureActive(); checkReadSource(root.source); failure } catch (changed: CancellationException) {
                    if (changed !== failure) changed.addSuppressed(failure)
                    changed
                }
                runCatching { owned.close() }.exceptionOrNull()?.let(reported::addSuppressed)
                runCatching { file?.close() }.exceptionOrNull()?.let(reported::addSuppressed)
                throw reported
            }
        }

    private fun checkReadSource(source: ExecutionSource) {
        WorkspaceExecutionContext.blockingContext()[Job]?.ensureActive()
        WorkspaceExecutionContext.current()?.checkCurrent()
        checkExecutionSource(source)
    }

    private fun confinedReadPath(channel: SFTPClient, path: String, root: ReadRoot): String {
        checkReadSource(root.source)
        val remote = Paths.get(remotePathFor(path, root.source.workspaceRoot, root.source.identity.home)).normalize()
        if (!remote.isAbsolute || !remote.startsWith(Paths.get(root.remotePath)) || remote.toString() == root.remotePath) {
            throw IOException("File outside read root")
        }
        val currentRoot = Paths.get(channel.canonicalize(root.remotePath)).normalize()
        if (currentRoot.toString() != root.canonicalPath) throw CancellationException("Remote read root changed")
        val target = Paths.get(channel.canonicalize(remote.toString())).normalize()
        if (!target.isAbsolute || !target.startsWith(currentRoot) || target == currentRoot) {
            throw IOException("File outside read root")
        }
        // SFTP v3 cannot atomically combine canonicalization with a no-follow open.
        val attrs = channel.statExistence(target.toString()) ?: throw NoSuchFileException(File(path))
        if (attrs.type != FileMode.Type.REGULAR) throw IOException("Existing regular file required")
        checkReadSource(root.source)
        return target.toString()
    }

    private suspend fun rootReadChannel(source: ExecutionSource): RootReadChannel {
        currentCoroutineContext().ensureActive()
        checkReadSource(source)
        val owner = WorkspaceExecutionContext.blockingContext()[Job] ?: currentCoroutineContext()[Job]
        val owned = RootReadChannel(connection.openReadChannel(source.identity), owner)
        try {
            currentCoroutineContext().ensureActive()
            checkReadSource(source)
            return owned
        } catch (failure: Throwable) {
            runCatching { owned.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private class RootReadChannel(val channel: SFTPClient, job: Job?) : AutoCloseable {
        private val closed = AtomicBoolean()
        private var cancellation: DisposableHandle? = null

        init {
            cancellation = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause is CancellationException) runCatching { close() }
            }
        }

        override fun close() {
            cancellation?.dispose()
            if (closed.compareAndSet(false, true)) channel.close()
        }
    }

    internal fun checkPlaybackPath(path: String, root: String, expectedCacheIdentity: String) {
        if (cacheIdentity() != expectedCacheIdentity) throw CancellationException("Remote file source changed")
        withSftp { channel ->
            confinedPlaybackPath(channel, toRemotePath(path), toRemotePath(root))
            if (cacheIdentity() != expectedCacheIdentity) throw CancellationException("Remote file source changed")
        }
    }

    internal fun openPlaybackRead(path: String, root: String, expectedCacheIdentity: String): InputStream =
        openRead(path, expectedCacheIdentity, playbackRoot = root)

    private fun confinedPlaybackPath(channel: SFTPClient, remote: String, root: String): String {
        val canonicalRoot = Paths.get(channel.canonicalize(root)).normalize()
        val canonicalPath = Paths.get(channel.canonicalize(remote)).normalize()
        if (!canonicalRoot.isAbsolute || !canonicalPath.isAbsolute || !canonicalPath.startsWith(canonicalRoot)) {
            throw IOException("Media path outside playback root")
        }
        return canonicalPath.toString()
    }

    private fun openRead(path: String, expectedCacheIdentity: String, playbackRoot: String?, maxBytes: Long? = null): InputStream = runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
        val execution = WorkspaceExecutionContext.current()
        val source = executionSource()
        val identity = source?.identity ?: connection.captureIdentity()
        val root = source?.workspaceRoot ?: currentWorkspaceRoot()
        fun checkSource() {
            if (maxBytes != null) WorkspaceExecutionContext.blockingContext()[Job]?.ensureActive()
            execution?.checkCurrent()
            connection.checkIdentity(identity)
            if (cacheIdentity(identity, root) != expectedCacheIdentity || currentWorkspaceRoot() != root) {
                throw CancellationException("Remote file source changed")
            }
        }
        checkSource()
        val remote = remotePathFor(path, root, identity.home)
        val channel = connection.openReadChannel(identity)
        val channelClosed = AtomicBoolean()
        fun closeChannel() {
            if (channelClosed.compareAndSet(false, true)) channel.close()
        }
        val cancellation = if (maxBytes != null) execution?.onCancellation { closeChannel() } else null
        val file = try {
            checkSource()
            val readable = if (playbackRoot == null) remote else
                confinedPlaybackPath(channel, remote, remotePathFor(playbackRoot, root, identity.home))
            val attrs = channel.statExistence(readable) ?: throw NoSuchFileException(File(remote))
            if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("Is a directory: $remote")
            if (playbackRoot != null && attrs.type != FileMode.Type.REGULAR) throw IOException("Regular media file required")
            checkSource()
            if (maxBytes != null && attrs.size > maxBytes) throw FileReadTooLargeException(maxBytes)
            channel.open(readable)
        } catch (failure: Throwable) {
            val reported = try { checkSource(); failure } catch (changed: CancellationException) {
                if (changed !== failure) changed.addSuppressed(failure)
                changed
            }
            cancellation?.dispose()
            runCatching { closeChannel() }.exceptionOrNull()?.let(reported::addSuppressed)
            throw reported
        }
        val stream = object : InputStream() {
            private val input = RemoteFileInputStream(file)
            private val closed = AtomicBoolean()

            override fun read(): Int = input.read()

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                input.read(b, off, minOf(len, IO_CHUNK))

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                // Closing the private channel wakes an in-flight read before releasing its handle.
                cancellation?.dispose()
                try { closeChannel() } finally { runCatching { input.close() } }
            }
        }
        val checked = identityCheckedRead(stream, ::checkSource)
        execution?.protectRead(checked) ?: checked
    }

    override fun readBytes(path: String): ByteArray = readAll(toRemotePath(path))

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        return openRead(path, cacheIdentity(), playbackRoot = null, maxBytes = maxBytes).use {
            it.readBytesBounded(maxBytes)
        }
    }

    override fun resolvePath(path: String, followFinalLink: Boolean): String =
        withSftp { resolveRemotePath(it, toRemotePath(path), followFinalLink) }

    private fun resolveRemotePath(sftp: SFTPClient, path: String, followFinalLink: Boolean): String {
        val pending = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        var current = "/"
        var links = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            when (part) {
                "." -> continue
                ".." -> {
                    current = current.substringBeforeLast('/', "").ifEmpty { "/" }
                    continue
                }
            }
            val candidate = current.trimEnd('/') + "/" + part
            val attributes = lstatOrNull(sftp, candidate)
            if ((followFinalLink || pending.isNotEmpty()) && attributes?.type == FileMode.Type.SYMLINK) {
                if (++links > 40) throw FileSystemLoopException(path)
                val target = sftp.readlink(candidate)
                if (target.startsWith('/')) current = "/"
                target.split('/').filter { it.isNotEmpty() }.asReversed().forEach(pending::addFirst)
            } else {
                current = candidate
            }
        }
        return current
    }

    private fun lstatOrNull(sftp: SFTPClient, path: String): FileAttributes? = try {
        sftp.lstat(path)
    } catch (failure: SFTPException) {
        if (failure.statusCode != StatusCode.NO_SUCH_FILE && failure.statusCode != StatusCode.NO_SUCH_PATH) throw failure
        null
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        // 与 writeStream 同构：先写 .aicode-part 再改名，避免原地 TRUNC 让并发读者看到半截内容，
        // 也避免中途断开时在目标位置留下半个文件。
        val tmp = tmpPathFor(remote)
        val invalidate = agentConfigCache.invalidation(remote)
        withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            try {
                sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                    writeAll(rf, bytes)
                }
                replaceVia(sftp, tmp, remote)
                invalidate()
            } catch (e: Exception) {
                runCatching { sftp.rm(tmp) }
                throw e
            }
        }
    }

    /**
     * 用 [tmp] 原子替换 [remote]。优先直接 rename（多数 SFTP 服务器支持覆盖式 rename，
     * 这才是真正的原子替换）；仅当服务器拒绝覆盖时才退化为「先删目标再改名」——
     * 后者存在极小的丢失窗口，但好过所有服务器都退化。
     */
    private fun replaceVia(sftp: SFTPClient, tmp: String, remote: String) {
        if (sftp.statExistence(remote) == null) {
            sftp.rename(tmp, remote)
            return
        }
        val renamed = runCatching { sftp.rename(tmp, remote) }.isSuccess
        if (renamed) return
        runCatching { sftp.rm(remote) }
        sftp.rename(tmp, remote)
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val remote = toRemotePath(path)
        // 先落到 .aicode-part 再改名：传输中途断开时不会在目标位置留下半截文件
        val tmp = tmpPathFor(remote)
        val invalidate = agentConfigCache.invalidation(remote)
        return withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            val written = try {
                sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        rf.write(offset, buf, 0, n)
                        offset += n
                    }
                    offset
                }
            } catch (e: Exception) {
                runCatching { sftp.rm(tmp) }
                throw e
            }
            // 目标已存在时先尝试直接覆盖改名，失败再删目标改名（见 replaceVia）。
            replaceVia(sftp, tmp, remote)
            invalidate()
            written
        }
    }

    override fun copyToLocal(path: String): File {
        val remote = toRemotePath(path)
        val tempFile = File.createTempFile("aicode_remote_", ".copy").apply { deleteOnExit() }
        return try {
            withSftp { sftp ->
                if (sftp.statExistence(remote) == null) throw NoSuchFileException(File(remote))
                sftp.get(remote, tempFile.absolutePath)
            }
            tempFile
        } catch (e: Exception) {
            // 用完即删：临时文件不依赖 deleteOnExit（只在进程退出清），失败路径也回收，避免长会话累积。
            runCatching { tempFile.delete() }
            if (e is CancellationException || e is NoSuchFileException) throw e
            FileLogger.e(TAG, "copyToLocal 失败: $remote", e)
            throw NoSuchFileException(File(remote))
        }
    }

    override fun delete(path: String) {
        val remote = toRemotePath(path)
        val invalidate = agentConfigCache.invalidation(remote)
        // 接口约定 delete 只删文件或空目录：目录非空时 rmdir 失败 → 抛异常（与本地 File.delete() 对齐）。
        withSftp { sftp ->
            val entry = resolveRemotePath(sftp, remote, followFinalLink = false)
            val attrs = lstatOrNull(sftp, entry) ?: return@withSftp
            if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(entry) else sftp.rm(entry)
            invalidate()
        }
    }

    override fun deleteRecursively(path: String) {
        val remote = toRemotePath(path)
        val invalidate = agentConfigCache.invalidation(remote)
        withSftp { sftp ->
            val entry = resolveRemotePath(sftp, remote, followFinalLink = false)
            if (lstatOrNull(sftp, entry) == null) return@withSftp
            try { deleteRecursive(sftp, entry) } finally { invalidate() }
        }
    }

    override fun rename(path: String, newPath: String) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        val invalidateFrom = agentConfigCache.invalidation(from)
        val invalidateTo = agentConfigCache.invalidation(to)
        withSftp { sftp ->
            val source = resolveRemotePath(sftp, from, followFinalLink = false)
            val target = resolveRemotePath(sftp, to, followFinalLink = false)
            if (lstatOrNull(sftp, source) == null) throw NoSuchFileException(File(from))
            if (lstatOrNull(sftp, target) != null) throw FileAlreadyExistsException(File(to))
            sftp.rename(source, target)
            invalidateFrom()
            invalidateTo()
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        val invalidate = agentConfigCache.invalidation(to)
        withSftp { sftp ->
            val sourceEntry = resolveRemotePath(sftp, from, followFinalLink = false)
            val source = resolveRemotePath(sftp, from, followFinalLink = true)
            val target = resolveRemotePath(sftp, to, followFinalLink = false)
            val targetIdentity = resolveRemotePath(sftp, to, followFinalLink = true)
            if (lstatOrNull(sftp, source) == null) throw NoSuchFileException(File(from))
            checkCopyMovePaths(listOf(sourceEntry, source), listOf(target, targetIdentity))
            if (lstatOrNull(sftp, target) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                try { deleteRecursive(sftp, target) } finally { invalidate() }
            }
            try { copyRecursive(sftp, source, target) } finally { invalidate() }
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        val invalidateFrom = agentConfigCache.invalidation(from)
        val invalidateTo = agentConfigCache.invalidation(to)
        withSftp { sftp ->
            val source = resolveRemotePath(sftp, from, followFinalLink = false)
            val sourceIdentity = resolveRemotePath(sftp, from, followFinalLink = true)
            val target = resolveRemotePath(sftp, to, followFinalLink = false)
            val targetIdentity = resolveRemotePath(sftp, to, followFinalLink = true)
            if (lstatOrNull(sftp, source) == null) throw NoSuchFileException(File(from))
            checkCopyMovePaths(listOf(source, sourceIdentity), listOf(target, targetIdentity))
            if (lstatOrNull(sftp, target) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                try { deleteRecursive(sftp, target) } finally { invalidateTo() }
            }
            sftp.rename(source, target)
            invalidateFrom()
            invalidateTo()
        }
    }

    private fun checkCopyMovePaths(sources: List<String>, targets: List<String>) {
        if (sources.any { source -> targets.any { target ->
                Paths.get(source).startsWith(Paths.get(target)) || Paths.get(target).startsWith(Paths.get(source))
            } }) {
            throw FilePathOverlapException("Source and destination overlap")
        }
    }

    override fun mkdirs(path: String) {
        val remote = toRemotePath(path)
        val invalidate = agentConfigCache.invalidation(remote)
        withSftp {
            val existed = it.statExistence(remote) != null
            it.mkdirs(remote)
            if (!existed) invalidate()
        }
    }

    override fun parentPath(path: String): String? {
        val remote = toRemotePath(path)
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        return toDisplayPathFromRemote(parent)
    }

    override fun toDisplayPath(path: String): String = toDisplayPathFromRemote(toRemotePath(path))

    /** 读取远程文件全部字节；不存在抛 [NoSuchFileException]，超过 [MAX_REMOTE_READ_BYTES] 抛 [RemoteOutputTooLargeException]。 */
    private fun readAll(remote: String): ByteArray = withSftp { sftp ->
        val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
        if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("是目录，无法按文件读取: $remote")
        if (attrs.size > MAX_REMOTE_READ_BYTES) {
            throw RemoteOutputTooLargeException(
                "远程文件超过 ${MAX_REMOTE_READ_BYTES / 1024 / 1024}MB，请改用 start_line/end_line 分段读取"
            )
        }
        sftp.open(remote).use { rf -> readFully(rf) }
    }

    private fun readFully(rf: RemoteFile): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(IO_CHUNK)
        var offset = 0L
        while (true) {
            val n = rf.read(offset, buf, 0, minOf(buf.size.toLong(), MAX_REMOTE_READ_BYTES - offset + 1).toInt())
            if (n <= 0) break
            if (offset + n > MAX_REMOTE_READ_BYTES) {
                throw RemoteOutputTooLargeException("Remote file exceeded the read limit during transfer")
            }
            out.write(buf, 0, n)
            offset += n
        }
        return out.toByteArray()
    }

    private fun writeAll(rf: RemoteFile, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(IO_CHUNK, bytes.size - offset)
            rf.write(offset.toLong(), bytes, offset, n)
            offset += n
        }
    }

    /** 递归删除（SFTP 无递归删除原语）：后序遍历，目录在子项删完后 rmdir。 */
    private fun deleteRecursive(sftp: SFTPClient, remote: String) {
        val attrs = lstatOrNull(sftp, remote) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (info in sftp.ls(remote)) {
                val name = info.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, remote.trimEnd('/') + "/" + name)
            }
            sftp.rmdir(remote)
        } else {
            sftp.rm(remote)
        }
    }

    /** 递归复制（SFTP 无服务端复制原语）：目录建好后逐项复制，文件流式读写。 */
    private fun copyRecursive(sftp: SFTPClient, from: String, to: String) {
        val attrs = lstatOrNull(sftp, from) ?: throw NoSuchFileException(File(from))
        if (attrs.type == FileMode.Type.SYMLINK) {
            sftp.symlink(to, sftp.readlink(from))
        } else if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.mkdirs(to)
            for (info in sftp.ls(from)) {
                val name = info.name
                if (name == "." || name == "..") continue
                copyRecursive(sftp, from.trimEnd('/') + "/" + name, to.trimEnd('/') + "/" + name)
            }
        } else {
            sftp.open(from).use { src ->
                sftp.open(to, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { dst ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = src.read(offset, buf, 0, buf.size)
                        if (n <= 0) break
                        dst.write(offset, buf, 0, n)
                        offset += n
                    }
                }
            }
        }
    }

    private fun ensureParent(sftp: SFTPClient, remote: String) {
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) sftp.mkdirs(parent)
    }

    /**
     * 原子写的临时文件路径。与 [LocalFileAccess] 的 `.aicode-tmp-*` 命名区分开：
     * 远端只能在目标旁生成名字，所以用「目标名 + 后缀」的形式，且固定后缀便于异常
     * 残留时被识别清理。
     */
    private fun tmpPathFor(remote: String) = "$remote$TMP_FILE_SUFFIX"

    private companion object {
        /** 原子写临时文件后缀（远端用「目标名 + 后缀」，与本地 `File.createTempFile` 策略不同）。 */
        const val TMP_FILE_SUFFIX = ".aicode-part"
    }
}

/** 把 SFTP [RemoteFile] 的随机读适配为顺序 [InputStream]，供 [BoundedLineReader] 惰性逐行读取。 */
private class RemoteFileInputStream(private val file: RemoteFile) : InputStream() {
    private var offset = 0L
    private val single = ByteArray(1)

    override fun read(): Int {
        val n = file.read(offset, single, 0, 1)
        if (n < 0) return -1
        if (n == 0) throw IOException("Remote file read made no progress")
        offset += 1
        return single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.read(offset, b, off, len)
        if (n < 0) return -1
        if (n == 0) throw IOException("Remote file read made no progress")
        offset += n
        return n
    }

    override fun close() = file.close()
}

/** SFTP 权限集合 → 9 位 rwx 字符串（如 `rw-r--r--`）。 */
internal fun formatPermissions(perms: Set<FilePermission>): String {
    fun bit(p: FilePermission, c: Char) = if (p in perms) c else '-'
    return buildString {
        append(bit(FilePermission.USR_R, 'r'))
        append(bit(FilePermission.USR_W, 'w'))
        append(bit(FilePermission.USR_X, 'x'))
        append(bit(FilePermission.GRP_R, 'r'))
        append(bit(FilePermission.GRP_W, 'w'))
        append(bit(FilePermission.GRP_X, 'x'))
        append(bit(FilePermission.OTH_R, 'r'))
        append(bit(FilePermission.OTH_W, 'w'))
        append(bit(FilePermission.OTH_X, 'x'))
    }
}

/** AI 路径 → 远程真实路径：`~/workspace` 前缀映射到 [workspaceRoot]，其它绝对路径原样使用。 */
internal fun remotePathFor(path: String, workspaceRoot: String, remoteHome: String?): String {
    val root = workspaceRoot.trimEnd('/')
    // CONTAINER_ROOT 是 ~/workspace，展开 ~ 后做前缀匹配
    val wsRoot = (remoteHome ?: "~").trimEnd('/') + "/workspace"
    val p = path.trim().let {
        if (it.startsWith("~/")) {
            val home = remoteHome
            if (home != null) home.trimEnd('/') + "/" + it.removePrefix("~/") else it
        } else it
    }
    return when {
        p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
        p.startsWith("$wsRoot/") ->
            root + "/" + p.removePrefix("$wsRoot/")
        p.startsWith("/") -> p
        else -> root + "/" + p
    }
}

/** 远程真实路径 → AI 视角的容器路径（回显用）。 */
internal fun displayPathFor(remotePath: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    return when {
        remotePath == root -> CONTAINER_ROOT
        remotePath.startsWith("$root/") -> CONTAINER_ROOT + "/" + remotePath.removePrefix("$root/")
        else -> remotePath
    }
}
