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
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Paths
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
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
    private val workspaceRepository: WorkspaceRepository
) : FileAccessProvider {

    private val sftpMutex get() = connection.sftpOperationMutex

    /** 当前选中工作区在远程服务器上的真实路径（如 /data/.../test/111）。 */
    private fun currentWorkspaceRoot(): String {
        val cfg = connection.config ?: throw IllegalStateException("SSH 未连接")
        // currentPath() 远程模式返回选中工作区的远程绝对路径；未选中时回退到 remoteWorkspacePath
        val path = workspaceRepository.currentPath()
        return if (path.isNotBlank() && path != "/") path else cfg.remoteWorkspacePath.trimEnd('/')
    }

    internal data class ExecutionSource(val identity: RemoteConnectionIdentity, val workspaceRoot: String)

    internal fun captureExecutionSource(): ExecutionSource {
        val identity = connection.captureIdentity()
        return ExecutionSource(identity, currentWorkspaceRoot()).also(::checkExecutionSource)
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
        if (e !is SFTPException && e !is NoSuchFileException && e !is FileAlreadyExistsException) {
            runCatching { connection.invalidateSftp() }
        }
        throw e
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

    private fun openRead(path: String, expectedCacheIdentity: String, playbackRoot: String?): InputStream = runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
        val execution = WorkspaceExecutionContext.current()
        val source = executionSource()
        val identity = source?.identity ?: connection.captureIdentity()
        val root = source?.workspaceRoot ?: currentWorkspaceRoot()
        fun checkSource() {
            execution?.checkCurrent()
            connection.checkIdentity(identity)
            if (cacheIdentity(identity, root) != expectedCacheIdentity || currentWorkspaceRoot() != root) {
                throw CancellationException("Remote file source changed")
            }
        }
        checkSource()
        val remote = remotePathFor(path, root, identity.home)
        val channel = connection.openReadChannel(identity)
        val file = try {
            checkSource()
            val readable = if (playbackRoot == null) remote else
                confinedPlaybackPath(channel, remote, remotePathFor(playbackRoot, root, identity.home))
            val attrs = channel.statExistence(readable) ?: throw NoSuchFileException(File(remote))
            if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("Is a directory: $remote")
            if (playbackRoot != null && attrs.type != FileMode.Type.REGULAR) throw IOException("Regular media file required")
            checkSource()
            channel.open(readable)
        } catch (failure: Throwable) {
            val reported = try { checkSource(); failure } catch (changed: CancellationException) {
                if (changed !== failure) changed.addSuppressed(failure)
                changed
            }
            runCatching { channel.close() }.exceptionOrNull()?.let(reported::addSuppressed)
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
                try { channel.close() } finally { runCatching { input.close() } }
            }
        }
        val checked = identityCheckedRead(stream, ::checkSource)
        execution?.protectRead(checked) ?: checked
    }

    override fun readBytes(path: String): ByteArray = readAll(toRemotePath(path))

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        // 与 writeStream 同构：先写 .aicode-part 再改名，避免原地 TRUNC 让并发读者看到半截内容，
        // 也避免中途断开时在目标位置留下半个文件。
        val tmp = tmpPathFor(remote)
        withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            try {
                sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                    writeAll(rf, bytes)
                }
                replaceVia(sftp, tmp, remote)
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
        // 接口约定 delete 只删文件或空目录：目录非空时 rmdir 失败 → 抛异常（与本地 File.delete() 对齐）。
        withSftp { sftp ->
            val attrs = sftp.statExistence(remote) ?: return@withSftp
            if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(remote) else sftp.rm(remote)
        }
    }

    override fun deleteRecursively(path: String) {
        val remote = toRemotePath(path)
        withSftp { sftp -> deleteRecursive(sftp, remote) }
    }

    override fun rename(path: String, newPath: String) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        withSftp { sftp ->
            if (sftp.statExistence(from) == null) throw NoSuchFileException(File(from))
            if (sftp.statExistence(to) != null) throw FileAlreadyExistsException(File(to))
            sftp.rename(from, to)
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val from = Paths.get(toRemotePath(path)).normalize().toString()
        val to = Paths.get(toRemotePath(newPath)).normalize().toString()
        withSftp { sftp ->
            val source = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
            if (to == from || (source.type == FileMode.Type.DIRECTORY && Paths.get(to).startsWith(Paths.get(from)))) {
                throw IOException("destination is the source or its descendant: $newPath")
            }
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            copyRecursive(sftp, from, to)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val from = Paths.get(toRemotePath(path)).normalize().toString()
        val to = Paths.get(toRemotePath(newPath)).normalize().toString()
        withSftp { sftp ->
            val source = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
            if (to == from || (source.type == FileMode.Type.DIRECTORY && Paths.get(to).startsWith(Paths.get(from)))) {
                throw IOException("destination is the source or its descendant: $newPath")
            }
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            sftp.rename(from, to)
        }
    }

    override fun mkdirs(path: String) {
        val remote = toRemotePath(path)
        withSftp { it.mkdirs(remote) }
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
        val attrs = sftp.statExistence(remote) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (info in sftp.ls(remote)) {
                val name = info.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, info.path)
            }
            sftp.rmdir(remote)
        } else {
            sftp.rm(remote)
        }
    }

    /** 递归复制（SFTP 无服务端复制原语）：目录建好后逐项复制，文件流式读写。 */
    private fun copyRecursive(sftp: SFTPClient, from: String, to: String) {
        val attrs = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
        if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.mkdirs(to)
            for (info in sftp.ls(from)) {
                val name = info.name
                if (name == "." || name == "..") continue
                copyRecursive(sftp, info.path, "$to/$name")
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
        if (n <= 0) return -1
        offset += 1
        return single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.read(offset, b, off, len)
        if (n <= 0) return -1
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
