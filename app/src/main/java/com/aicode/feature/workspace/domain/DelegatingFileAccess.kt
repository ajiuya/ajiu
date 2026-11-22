package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 */
@Singleton
class DelegatingFileAccess @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteSftpFileAccess: RemoteSftpFileAccess,
    private val localFolders: LocalFolderGrantRepository
) : FileAccessProvider {

    private fun delegate(): FileAccessProvider {
        WorkspaceExecutionContext.current()?.let {
            it.checkCurrent()
            return it.fileAccess
        }
        return if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remoteSftpFileAccess else localFileAccess
    }

    private fun pathDelegate(path: String): FileAccessProvider {
        if (!isLocalFolderPath(path)) return delegate()
        val execution = WorkspaceExecutionContext.current()
        val roots = execution?.localFolderSnapshot ?: localFolders.snapshot()
        roots.rootFor(path)
        val base = delegate()
        val identity = base.cacheIdentity()
        return InternalLocalFolderAccess(roots, execution) {
            execution?.checkCurrent()
            if (delegate() !== base || base.cacheIdentity() != identity) {
                throw java.util.concurrent.CancellationException("File execution source changed")
            }
        }
    }

    private fun pairDelegate(path: String, newPath: String): FileAccessProvider {
        if (!isLocalFolderPath(path) && !isLocalFolderPath(newPath)) return delegate()
        if (isLocalFolderPath(path) != isLocalFolderPath(newPath)) {
            throw java.io.IOException("Cross-backend operations require a captured file transfer")
        }
        val source = pathDelegate(path)
        (WorkspaceExecutionContext.current()?.localFolderSnapshot ?: localFolders.snapshot()).rootFor(newPath)
        return source
    }

    internal fun capturePathSource(path: String): FilePathSource {
        val execution = WorkspaceExecutionContext.current()
        val base = delegate()
        val expected = base.cacheIdentity()
        val roots = if (isLocalFolderPath(path)) execution?.localFolderSnapshot ?: localFolders.snapshot() else null
        val grant = roots?.rootFor(path)?.grant
        val identity = grant?.let { "$expected:local-folder:${it.id}:${it.epoch}:${roots.revision}" } ?: expected
        fun checkBase() {
            execution?.checkCurrent()
            if (delegate() !== base || base.cacheIdentity() != expected) {
                throw java.util.concurrent.CancellationException("File execution source changed")
            }
        }
        return FilePathSource(identity, expected, verifyAuthorization = {
            checkBase()
            roots?.rootFor(path)
        }) {
            checkBase()
            roots?.rootFor(path, verifyAuthorization = false)
        }.also { it.checkCurrent() }
    }

    override fun cacheIdentity(): String = delegate().cacheIdentity()

    override fun agentConfigSourceIdentity(): String = delegate().agentConfigSourceIdentity()

    override fun agentConfigSnapshot(root: String, kind: AgentConfigKind, forceRefresh: Boolean): AgentConfigSnapshot? =
        pathDelegate(root).agentConfigSnapshot(root, kind, forceRefresh)

    override fun invalidateAgentConfig(path: String) = pathDelegate(path).invalidateAgentConfig(path)

    override fun openRead(path: String): InputStream = openRead(path, cacheIdentity())

    override fun openRead(path: String, expectedCacheIdentity: String): InputStream {
        val execution = WorkspaceExecutionContext.current()
        val base = delegate()
        val source = pathDelegate(path)
        fun checkSource() {
            execution?.checkCurrent()
            if (delegate() !== base || base.cacheIdentity() != expectedCacheIdentity) {
                throw java.util.concurrent.CancellationException("File execution mode changed")
            }
        }
        checkSource()
        val sourceIdentity = if (source is InternalLocalFolderAccess) source.cacheIdentity() else expectedCacheIdentity
        return identityCheckedRead(source.openRead(path, sourceIdentity), ::checkSource)
    }

    override fun readFile(path: String): String = pathDelegate(path).readFile(path)

    override fun readLines(path: String): Sequence<String> = pathDelegate(path).readLines(path)

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        pathDelegate(path).writeFile(path, content, overwrite, encoding)

    override fun exists(path: String): Boolean = pathDelegate(path).exists(path)

    override fun isDirectory(path: String): Boolean = pathDelegate(path).isDirectory(path)

    override fun isFile(path: String): Boolean = pathDelegate(path).isFile(path)

    override fun fileSize(path: String): Long = pathDelegate(path).fileSize(path)

    override fun lastModified(path: String): Long = pathDelegate(path).lastModified(path)

    override fun fingerprint(path: String): String? = pathDelegate(path).fingerprint(path)

    override fun permissions(path: String): String = pathDelegate(path).permissions(path)

    override fun listFiles(path: String): List<FileEntry> = pathDelegate(path).listFiles(path)

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        pathDelegate(path).listFilesRecursive(path, maxDepth)

    override fun readBytes(path: String): ByteArray = pathDelegate(path).readBytes(path)

    override fun readBytes(path: String, maxBytes: Long): ByteArray {
        require(maxBytes in 0..Int.MAX_VALUE.toLong())
        val execution = WorkspaceExecutionContext.current()
        val base = delegate()
        val source = pathDelegate(path)
        val identity = base.cacheIdentity()
        fun checkSource() {
            execution?.checkCurrent()
            if (delegate() !== base || base.cacheIdentity() != identity) {
                throw java.util.concurrent.CancellationException("File execution source changed")
            }
        }
        checkSource()
        return source.readBytes(path, maxBytes).also { checkSource() }
    }

    override fun resolvePath(path: String, followFinalLink: Boolean): String =
        pathDelegate(path).resolvePath(path, followFinalLink)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        pathDelegate(path).writeBytes(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        pathDelegate(path).writeStream(path, input, overwrite)

    override fun copyToLocal(path: String): File = pathDelegate(path).copyToLocal(path)

    override fun delete(path: String) = pathDelegate(path).delete(path)

    override fun deleteRecursively(path: String) = pathDelegate(path).deleteRecursively(path)

    override fun rename(path: String, newPath: String) = pairDelegate(path, newPath).rename(path, newPath)

    override fun copy(path: String, newPath: String, overwrite: Boolean) = pairDelegate(path, newPath).copy(path, newPath, overwrite)

    override fun move(path: String, newPath: String, overwrite: Boolean) = pairDelegate(path, newPath).move(path, newPath, overwrite)

    override fun mkdirs(path: String) = pathDelegate(path).mkdirs(path)

    override fun parentPath(path: String): String? = pathDelegate(path).parentPath(path)

    override fun toDisplayPath(path: String): String = pathDelegate(path).toDisplayPath(path)
}
