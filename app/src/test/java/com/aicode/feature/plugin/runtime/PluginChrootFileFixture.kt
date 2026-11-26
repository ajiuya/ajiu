package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.chroot.ChrootFileOperationException
import com.aicode.feature.workspace.domain.chroot.ChrootFileSession
import com.aicode.feature.workspace.domain.chroot.ChrootFileStat
import com.aicode.feature.workspace.domain.chroot.ChrootFileWorkerClient
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

internal class PluginChrootFileFixture(
    val guest: File,
    val hostWorkspace: File,
    roots: LocalFolderSnapshot
) {
    val workspace = File(guest, "root/workspace").apply { mkdirs() }
    val hostHome = File(guest.parentFile, "phone-home-${guest.name}").absolutePath
    val client = mockk<ChrootFileWorkerClient>()
    private val owner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, "plugin-file-fixture:${guest.name}")
    var identity = "chroot:${guest.name}"
    var sourceCurrent = true
    var failCleanupAfterPayload = false
    var payloadWritten = false
    var onPayload: () -> Unit = {}
    var sessions = 0
    var closedSessions = 0
    var sessionsAtUnknownCleanup: Int? = null
    var stages = 0
    val writes = mutableListOf<String>()
    val access: ChrootFileAccess
    val execution: WorkspaceExecutionContext

    init {
        File(guest, "root/.aicode/plugins").mkdirs()
        every { client.close() } returns Unit
        every { client.stat(any(), any()) } answers { stat(file(firstArg()), secondArg()) }
        every { client.resolve(any(), any()) } answers {
            val value = file(firstArg())
            val resolved = if (secondArg<Boolean>()) value.canonicalFile else File(value.parentFile.canonicalFile, value.name)
            "/" + guest.toPath().relativize(resolved.toPath()).toString()
        }
        every { client.list(any()) } answers {
            val directory = file(firstArg())
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) throw ChrootFileOperationException(20)
            directory.listFiles()!!.map { it.name to stat(it, false) }
        }
        every { client.openRead(any()) } answers {
            val entry = file(firstArg())
            if (!Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)) throw ChrootFileOperationException(22)
            entry.inputStream()
        }
        every { client.mkdirs(any()) } answers {
            val entry = file(firstArg())
            if (entry.name.startsWith(".aicode-transfer-")) stages++
            if (!entry.mkdirs() && !entry.isDirectory) throw ChrootFileOperationException(20)
        }
        every { client.write(any<String>(), any<InputStream>(), any<Boolean>()) } answers {
            val source = secondArg<InputStream>()
            client.write(firstArg<String>(), thirdArg<Boolean>()) { output -> source.copyTo(output) }
        }
        every { client.write(any<String>(), any<Boolean>(), any<(OutputStream) -> Unit>()) } answers {
            val path = firstArg<String>()
            val entry = file(path)
            val staged = File.createTempFile(".fake-worker-", ".tmp", entry.parentFile)
            try {
                staged.outputStream().use { thirdArg<(OutputStream) -> Unit>()(it) }
                if (!secondArg<Boolean>() && Files.exists(entry.toPath(), NOFOLLOW_LINKS)) throw ChrootFileOperationException(17)
                Files.move(staged.toPath(), entry.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                writes += path
                if (entry.name == "payload") { payloadWritten = true; onPayload() }
                entry.length()
            } finally { staged.delete() }
        }
        every { client.rename(any(), any(), any()) } answers {
            val source = file(firstArg())
            val target = file(secondArg())
            if (!thirdArg<Boolean>() && Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw ChrootFileOperationException(17)
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            Unit
        }
        every { client.delete(any(), any()) } answers {
            if (secondArg<Boolean>()) throw AssertionError("A plugin transfer must not request recursive worker deletion")
            Files.delete(file(firstArg()).toPath())
        }
        access = ChrootFileAccess({ identity }, {
            if (!sourceCurrent) throw CancellationException("Plugin file source changed")
            sessions++
            val sessionIdentity = identity
            val hadPayload = payloadWritten
            val closed = AtomicBoolean()
            ChrootFileSession(sessionIdentity, client, guest, {
                if (!sourceCurrent || identity != sessionIdentity) throw CancellationException("Plugin file source changed")
            }, listOf("/root/workspace", "/root"), closeOwner = {
                if (closed.compareAndSet(false, true)) closedSessions++
                if (failCleanupAfterPayload && payloadWritten && !hadPayload) {
                    sessionsAtUnknownCleanup = sessions
                    throw IOException("Worker cleanup is UNKNOWN")
                }
            })
        }, { path ->
            when {
                path == hostWorkspace.path || path.startsWith(hostWorkspace.path + "/") -> "/root/workspace" + path.removePrefix(hostWorkspace.path)
                path == hostHome || path.startsWith("$hostHome/") -> "/root" + path.removePrefix(hostHome)
                else -> path
            }
        })
        val capture = LocalRuntimeCapture(owner, "phone", 1L, ChrootInstance(guest.name, 1, 2, 3),
            hostWorkspace.path, hostHome, identity, Any()) {
            if (!sourceCurrent) throw CancellationException("Plugin file source changed")
        }
        execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, identity, access, null, null, roots, owner, capture) {}
    }

    private fun file(path: String) = File(guest, path.removePrefix("/"))

    private fun stat(entry: File, follow: Boolean): ChrootFileStat {
        val options = if (follow) emptyArray() else arrayOf(NOFOLLOW_LINKS)
        val attrs = try { Files.readAttributes(entry.toPath(), BasicFileAttributes::class.java, *options) }
            catch (_: java.nio.file.NoSuchFileException) { throw ChrootFileOperationException(2) }
        val kind = when {
            attrs.isSymbolicLink -> 3
            attrs.isDirectory -> 1
            attrs.isRegularFile -> 2
            else -> throw ChrootFileOperationException(95)
        }
        val inode = (Files.getAttribute(entry.toPath(), "unix:ino", *options) as Number).toLong()
        return ChrootFileStat(kind, if (kind == 1) 0x41c0 else 0x8180, attrs.size(), attrs.lastModifiedTime().toMillis(), 1, inode)
    }
}
