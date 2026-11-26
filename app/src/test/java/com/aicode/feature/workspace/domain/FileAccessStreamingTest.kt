package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.CancellationException

class FileAccessStreamingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun defaultsSupportMemoryProvidersWithoutNewBusinessMethods() {
        val first = MemoryAccess()
        val second = MemoryAccess()
        assertNotEquals(first.cacheIdentity(), second.cacheIdentity())
        first.openRead("image", first.cacheIdentity()).use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        assertEquals(1, first.reads)
    }

    @Test
    fun defaultExpectedIdentityRejectsAChangedSourceBeforeOpening() {
        val access = MemoryAccess()
        assertThrows(CancellationException::class.java) { access.openRead("image", "another") }
        assertEquals(0, access.reads)
    }

    @Test
    fun changedSourceDuringReadIsNotReturnedAndTheOriginalStreamIsClosed() {
        val access = MemoryAccess()
        var source = "first"
        var closed = 0
        access.identity = { source }
        access.stream = {
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { source = "second" }
                override fun close() { closed++ }
            }
        }
        val input = access.openRead("image", "first")
        assertThrows(CancellationException::class.java) { input.readBytes() }
        input.close()
        assertEquals(1, closed)
    }

    @Test
    fun failureAndEofReleaseTheStreamOnce() {
        var closed = 0
        val failure = IOException("read failed")
        val input = identityCheckedRead(object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed++ }
        }) {}
        assertEquals(failure, assertThrows(IOException::class.java) { input.read() })
        input.close()
        assertEquals(1, closed)
        val empty = identityCheckedRead(object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { closed++ }
        }) {}
        assertEquals(-1, empty.read())
        assertEquals(-1, empty.read())
        assertEquals(2, closed)
    }

    @Test
    fun localIdentityUsesCanonicalWorkspaceRootfsAndHomeWhileReadStaysStreaming() {
        val mapper = mockk<WorkspacePathMapper>()
        var root = temporary.newFolder("workspace")
        var rootfs = temporary.newFolder("rootfs")
        val config = temporary.newFolder("aicode")
        var home = "/root"
        every { mapper.captureExecutionMapping(any()) } answers {
            WorkspacePathMapper.ExecutionMapping(
                ContainerProfile.BUILTIN_ALPINE, root.canonicalFile, rootfs.canonicalFile, config.canonicalFile, home
            )
        }
        every { mapper.resolveFile(any(), any()) } answers {
            when (val path = firstArg<String>()) {
                "~/workspace" -> root
                "/" -> rootfs
                "~/" -> File(rootfs, home.removePrefix("/"))
                else -> File(root, path.removePrefix("~/workspace/"))
            }
        }
        val file = File(root, "image.bin").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val access = LocalFileAccess(mapper)
        val original = access.cacheIdentity()
        val input = access.openRead("image.bin", original)
        file.writeBytes(byteArrayOf(9, 8, 7))
        assertArrayEquals(byteArrayOf(9, 8, 7), input.use { it.readBytes() })
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        root = alias
        assertEquals(original, access.cacheIdentity())
        rootfs = temporary.newFolder("profile-two")
        assertNotEquals(original, access.cacheIdentity())
        val profileIdentity = access.cacheIdentity()
        home = "/home/other"
        assertNotEquals(profileIdentity, access.cacheIdentity())
        root = temporary.newFolder("workspace-two")
        assertThrows(CancellationException::class.java) { access.openRead("image.bin", original) }
    }

    @Test
    fun delegatingStreamNeverFallsBackToTheOtherModeAfterOpening() {
        val holder = ExecutionModeHolder()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        var closed = 0
        every { local.cacheIdentity() } returns "local:one"
        every { remote.cacheIdentity() } returns "sftp:two"
        every { local.openRead("image", "local:one") } returns object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun close() { closed++ }
        }
        val access = DelegatingFileAccess(holder, local, remote, mockk<LocalFolderGrantRepository>())
        val input = access.openRead("image", access.cacheIdentity())
        assertEquals(1, input.read())
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertThrows(CancellationException::class.java) { input.read() }
        input.close()
        assertEquals(1, closed)
        verify(exactly = 0) { remote.openRead(any(), any()) }
        assertThrows(CancellationException::class.java) { access.openRead("image", "local:one") }
        verify(exactly = 0) { remote.openRead(any()) }
    }

    private class MemoryAccess : FileAccessProvider {
        var reads = 0
        var identity: (() -> String)? = null
        var stream: (() -> InputStream)? = null
        override fun cacheIdentity(): String = identity?.invoke() ?: super.cacheIdentity()
        override fun openRead(path: String): InputStream = stream?.invoke() ?: super.openRead(path)
        override fun readBytes(path: String): ByteArray { reads++; return byteArrayOf(1, 2, 3) }
        override fun readBytes(path: String, maxBytes: Long): ByteArray {
            require(maxBytes in 0..Int.MAX_VALUE.toLong())
            if (3 > maxBytes) throw FileReadTooLargeException(maxBytes)
            return openRead(path, cacheIdentity()).use { it.readBytesBounded(maxBytes) }
        }
        override fun resolvePath(path: String, followFinalLink: Boolean): String =
            java.nio.file.Paths.get("/").resolve(path).normalize().toString()
        override fun readFile(path: String): String = error("unused")
        override fun readLines(path: String): Sequence<String> = error("unused")
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset): Unit = error("unused")
        override fun exists(path: String): Boolean = error("unused")
        override fun isDirectory(path: String): Boolean = error("unused")
        override fun isFile(path: String): Boolean = error("unused")
        override fun fileSize(path: String): Long = error("unused")
        override fun lastModified(path: String): Long = error("unused")
        override fun fingerprint(path: String): String? = error("unused")
        override fun permissions(path: String): String = error("unused")
        override fun listFiles(path: String): List<FileEntry> = error("unused")
        override fun listFilesRecursive(path: String, maxDepth: Int): List<String> = error("unused")
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean): Unit = error("unused")
        override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long = error("unused")
        override fun copyToLocal(path: String): File = error("unused")
        override fun delete(path: String): Unit = error("unused")
        override fun deleteRecursively(path: String): Unit = error("unused")
        override fun rename(path: String, newPath: String): Unit = error("unused")
        override fun copy(path: String, newPath: String, overwrite: Boolean): Unit = error("unused")
        override fun move(path: String, newPath: String, overwrite: Boolean): Unit = error("unused")
        override fun mkdirs(path: String): Unit = error("unused")
        override fun parentPath(path: String): String? = error("unused")
        override fun toDisplayPath(path: String): String = error("unused")
    }
}
