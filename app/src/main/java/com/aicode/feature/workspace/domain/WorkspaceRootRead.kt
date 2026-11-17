package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        when (execution.mode) {
            ExecutionMode.LOCAL_PROOT -> {
                val root = pathMapper.resolveFile(rootPath).canonicalFile
                if (root.parentFile == null || !root.isDirectory) throw IOException("Readable directory root required")
                RootRead(rootPath, execution, ownerContext, pathMapper, remoteAccess, root, null)
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
) {
    fun forOperation(context: CoroutineContext): RootRead = RootRead(
        rootPath, execution, context, pathMapper, remoteAccess, localRoot, remoteRoot,
    )

    private fun <T> inContext(block: () -> T): T =
        runBlocking(ownerContext + execution.ownedBlockingContext(requireNotNull(ownerContext[Job])) + Dispatchers.IO) {
            coroutineContext.ensureActive()
            execution.checkCurrent()
            val result = block()
            try {
                execution.checkCurrent()
                result
            } catch (failure: Throwable) {
                if (result is InputStream) runCatching { result.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

    fun checkCurrent() = inContext {
        if (localRoot != null && (pathMapper.resolveFile(rootPath).canonicalFile != localRoot || !localRoot.isDirectory)) {
            throw CancellationException("Read root changed")
        }
    }

    private fun localTarget(path: String): File {
        val root = localRoot ?: throw IOException("Not a local read root")
        if (pathMapper.resolveFile(rootPath).canonicalFile != root || !root.isDirectory) {
            throw CancellationException("Read root changed")
        }
        val target = pathMapper.resolveFile(path).canonicalFile
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
        localRoot?.let { localTarget(path).length() }
            ?: remoteAccess.rootReadSize(path, requireNotNull(remoteRoot))
    }

    fun openRead(path: String, maxBytes: Long): InputStream = inContext {
        if (localRoot == null) {
            remoteAccess.openRootRead(path, requireNotNull(remoteRoot), maxBytes)
        } else {
            val target = localTarget(path)
            if (target.length() > maxBytes) throw IOException("File exceeds read limit")
            val input = target.inputStream()
            try {
                if (localTarget(path) != target) throw CancellationException("Read target changed")
                execution.protectRead(identityCheckedRead(input) { inContext { localTarget(path) } })
            } catch (failure: Throwable) {
                runCatching { input.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
