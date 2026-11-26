package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

internal class WorkspaceRootRead @Inject constructor(
    private val pathMapper: WorkspacePathMapper,
    private val remoteAccess: RemoteSftpFileAccess,
) {
    fun capture(
        rootPath: String,
        execution: WorkspaceExecutionContext,
        ownerContext: CoroutineContext,
    ): RootRead = runBlocking(ownerContext + execution.ownedBlockingContext(requireNotNull(ownerContext[Job])) + Dispatchers.IO) {
        execution.checkCurrent()
        if (rootPath == "/" || rootPath == "~" || rootPath == "~/") throw IOException("Bounded directory read root required")
        if (isLocalFolderPath(rootPath)) {
            val host = InternalLocalFolderAccess(execution.localFolderSnapshot, execution)
            val root = host.directoryForWatch(rootPath).canonicalFile
            if (root.parentFile == null) throw IOException("Bounded directory read root required")
            RootRead(rootPath, execution, ownerContext, pathMapper, remoteAccess, root, null, host).also {
                execution.checkCurrent()
                host.cacheIdentity()
            }
        } else when (execution.mode) {
            ExecutionMode.LOCAL_PROOT -> {
                val guest = execution.fileAccess as? ChrootFileAccess
                if (guest != null) {
                    RootRead(rootPath, execution, ownerContext, pathMapper, remoteAccess, null, null,
                        guestRoot = guest.captureReadRoot(rootPath))
                } else {
                    if (execution.localRuntimeCapture != null) throw IOException("Captured chroot file backend required")
                    val root = pathMapper.resolveFile(rootPath).canonicalFile
                    if (root.parentFile == null || !root.isDirectory) throw IOException("Readable directory root required")
                    RootRead(rootPath, execution, ownerContext, pathMapper, remoteAccess, root, null)
                }
            }
            ExecutionMode.REMOTE_SSH -> RootRead(
                rootPath, execution, ownerContext, pathMapper, remoteAccess, null,
                remoteAccess.captureReadRoot(rootPath),
            )
        }.also { execution.checkCurrent() }
    }
}

internal class RootRead internal constructor(
    private val rootPath: String,
    private val execution: WorkspaceExecutionContext,
    private val ownerContext: CoroutineContext,
    private val pathMapper: WorkspacePathMapper,
    private val remoteAccess: RemoteSftpFileAccess,
    private val localRoot: File?,
    private val remoteRoot: RemoteSftpFileAccess.ReadRoot?,
    private val hostAccess: InternalLocalFolderAccess? = null,
    private val captureOwner: Job = requireNotNull(ownerContext[Job]),
    private val guestRoot: ChrootFileAccess.BoundedReadRoot? = null,
) {
    fun forOperation(context: CoroutineContext): RootRead = inContext {
        requireNotNull(context[Job]).ensureActive()
        RootRead(rootPath, execution, context, pathMapper, remoteAccess, localRoot, remoteRoot, hostAccess,
            captureOwner, guestRoot)
    }

    private fun <T> inContext(block: () -> T): T =
        runBlocking(ownerContext + execution.ownedBlockingContext(requireNotNull(ownerContext[Job])) + Dispatchers.IO) {
            coroutineContext.ensureActive()
            captureOwner.ensureActive()
            execution.checkCurrent()
            hostAccess?.cacheIdentity()
            val result = block()
            try {
                coroutineContext.ensureActive()
                captureOwner.ensureActive()
                execution.checkCurrent()
                hostAccess?.cacheIdentity()
                result
            } catch (failure: Throwable) {
                if (result is InputStream) runCatching { result.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

    fun checkCurrent() = inContext {
        if (localRoot != null) checkLocalRoot()
        guestRoot?.checkCurrent()
        Unit
    }

    private fun resolveLocal(path: String): File =
        (hostAccess?.readFileForTransfer(path) ?: pathMapper.resolveFile(path)).canonicalFile

    private fun checkLocalRoot() {
        val root = localRoot ?: throw IOException("Not a local read root")
        if (resolveLocal(rootPath) != root || !root.isDirectory) {
            throw CancellationException("Read root changed")
        }
    }

    private fun checkHostStreamRoot() {
        val grant = execution.localFolderSnapshot.rootFor(rootPath, verifyAuthorization = false)
        val parsed = requireNotNull(parseLocalFolderPath(rootPath))
        val root = File(grant.directory, parsed.segments.joinToString("/")).canonicalFile
        if (root != localRoot || !root.isDirectory) throw CancellationException("Read root changed")
    }

    private fun localTarget(path: String): File {
        val root = localRoot ?: throw IOException("Not a local read root")
        checkLocalRoot()
        val target = resolveLocal(path)
        if (!target.toPath().startsWith(root.toPath()) || target == root) {
            throw IOException("File outside read root")
        }
        if (!Files.isRegularFile(target.toPath())) throw IOException("Existing regular file required: $path")
        return target
    }

    fun localFile(path: String): File? = inContext {
        if (localRoot == null) null else localTarget(path)
    }

    fun size(path: String): Long = inContext {
        guestRoot?.size(path) ?: localRoot?.let { localTarget(path).length() }
            ?: remoteAccess.rootReadSize(path, requireNotNull(remoteRoot))
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun protectCaptureOwner(input: InputStream): InputStream {
        val checked = identityCheckedRead(input) { captureOwner.ensureActive() }
        val cancellation = captureOwner.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) runCatching { checked.close() }
        }
        return object : InputStream() {
            private fun readChecked(read: () -> Int): Int = try {
                read().also { if (it < 0) close() }
            } catch (failure: Throwable) {
                runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            override fun read(): Int = readChecked { checked.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int = readChecked { checked.read(b, off, len) }
            override fun close() {
                cancellation.dispose()
                checked.close()
            }
        }
    }

    fun openRead(path: String, maxBytes: Long): InputStream = inContext {
        val result = if (guestRoot != null) {
            execution.protectRead(identityCheckedRead(guestRoot.openRead(path, maxBytes)) { inContext {} })
        } else if (localRoot == null) {
            remoteAccess.openRootRead(path, requireNotNull(remoteRoot), maxBytes)
        } else {
            val target = localTarget(path)
            if (target.length() > maxBytes) throw IOException("File exceeds read limit")
            val input = hostAccess?.openRead(path) ?: target.inputStream()
            try {
                if (localTarget(path) != target) throw CancellationException("Read target changed")
                val bounded = object : InputStream() {
                    private var count = 0L
                    private val single = ByteArray(1)
                    override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
                        if (len == 0) return 0
                        val remaining = maxBytes - count
                        val limit = if (remaining >= len) len else (remaining + 1).toInt()
                        val length = input.read(b, off, limit)
                        if (length > remaining) throw IOException("File exceeded read limit during transfer")
                        if (length > 0) count += length
                        return length
                    }
                    override fun close() = input.close()
                }
                execution.protectRead(identityCheckedRead(bounded) {
                    inContext {
                        if (hostAccess != null) checkHostStreamRoot()
                        else if (localTarget(path) != target) throw CancellationException("Read target changed")
                    }
                })
            } catch (failure: Throwable) {
                runCatching { input.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        protectCaptureOwner(result)
    }
}
