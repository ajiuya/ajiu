package com.aicode.feature.terminal.domain

import android.system.Os
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalTaskScopeResolver @Inject constructor(
    private val executionFactory: WorkspaceExecutionContextFactory
) {
    suspend fun capture(): TerminalTaskScope? = capture(executionFactory.freeze())

    suspend fun capture(execution: WorkspaceExecutionContext): TerminalTaskScope? =
        captureTerminalTaskScope(execution)
}

internal suspend fun captureTerminalTaskScope(execution: WorkspaceExecutionContext): TerminalTaskScope? =
    withContext(Dispatchers.IO) {
        execution.checkCurrent()
        if (execution.mode != ExecutionMode.LOCAL_PROOT) return@withContext null
        val mapping = execution.localMapping ?: return@withContext null
        if (mapping.profile.mode != ExecutionMode.LOCAL_PROOT || mapping.profile.rootfsSource is RootfsSource.RemoteSsh) {
            return@withContext null
        }
        val scope = try {
            val rootfs = mapping.rootfsRoot.canonicalFile
            val workspace = mapping.workspaceRoot.canonicalFile
            val marker = File(rootfs, if (mapping.profile.isBuiltin) ".installed" else ".installed_custom")
            if (!marker.isFile) return@withContext null
            val markerIdentity = terminalHistoryMarkerIdentity(marker)
            val markerBytes = Files.newInputStream(marker.toPath()).use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 4096) return@withContext null
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
            check(terminalHistoryMarkerIdentity(marker) == markerIdentity)
            TerminalTaskScope(
                profileId = mapping.profile.id,
                rootfsSourceIdentity = terminalHistoryDigest(Json.encodeToString<RootfsSource>(mapping.profile.rootfsSource).toByteArray(Charsets.UTF_8)),
                rootfsPath = rootfs.path,
                rootfsIdentity = terminalHistoryFileIdentity(rootfs, directory = true) + ":" + markerIdentity + ":" + terminalHistoryDigest(markerBytes),
                workspaceRoot = workspace.path,
                workspaceIdentity = terminalHistoryFileIdentity(workspace, directory = true)
            ).also { it.validate() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        execution.checkCurrent()
        scope
    }

internal fun terminalHistoryFileIdentity(file: File, directory: Boolean): String {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(if (directory) attributes.isDirectory else attributes.isRegularFile)
    return attributes.fileKey()?.toString()
        ?: Os.lstat(file.path).let { "${it.st_dev}:${it.st_ino}" }
}

private fun terminalHistoryMarkerIdentity(file: File): String {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    check(attributes.isRegularFile)
    return "${terminalHistoryFileIdentity(file, directory = false)}:${attributes.creationTime()}:${attributes.lastModifiedTime()}:${attributes.size()}"
}

private fun terminalHistoryDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
