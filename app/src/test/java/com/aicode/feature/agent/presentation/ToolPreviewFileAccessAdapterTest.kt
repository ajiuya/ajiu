package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.domain.workflow.ToolPreviewImageCache
import com.aicode.feature.agent.presentation.preview.PreviewImageBytes
import com.aicode.feature.agent.presentation.preview.PreviewImageFile
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolPreviewFileAccessAdapterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun privateImagesNeverTouchTheSelectedRemoteBackendEvenWhenEvicted() {
        val root = temporary.newFolder("images")
        val file = File(root, "${"a".repeat(64)}.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:one"
        val reader = captureToolPreviewFileReader(access, null, root)!!
        val image = reader.readVersionedFile(file.absolutePath) as PreviewImageBytes
        assertArrayEquals(file.readBytes(), image.bytes)
        assertEquals("app-private-tool-image", image.key.source)
        file.delete()
        assertNull(reader.readVersionedFile(file.absolutePath))
        assertNull(reader.readVersionedFile(File(root, "../images/${file.name}").path))
        verify(exactly = 0) { access.openRead(any(), any()) }
        verify(exactly = 0) { access.fingerprint(any()) }
    }

    @Test fun anEvictedCachePathUnderTheTrustedSystemAliasNeverFallsThroughToSsh() {
        val actual = temporary.newFolder("actual")
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val root = File(actual, "images").apply { mkdirs() }
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:one"
        val reader = captureToolPreviewFileReader(access, null, root, listOf(File(alias, "images").path))!!
        assertNull(reader.readVersionedFile(File(alias, "images/${"a".repeat(64)}.png").path))
        verify(exactly = 0) { access.openRead(any(), any()) }
    }

    @Test fun privateCacheSymlinksCannotEscapeOrFallThroughToSsh() {
        val root = temporary.newFolder("images")
        val outside = temporary.newFile("outside.png").apply { writeBytes(byteArrayOf(1)) }
        val link = File(root, "${"a".repeat(64)}.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:one"
        assertNull(captureToolPreviewFileReader(access, null, root)!!.readVersionedFile(link.path))
        verify(exactly = 0) { access.openRead(any(), any()) }
    }

    @Test fun remoteReadsUseTheirCapturedIdentityAndHashTheExactBoundedPayload() {
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:one"
        var closed = false
        val bytes = byteArrayOf(9, 8, 7)
        every { access.openRead("/home/user/image.png", "remote:one") } answers {
            object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        }
        val result = captureToolPreviewFileReader(access, null, temporary.newFolder())!!
            .readVersionedFile("/home/user/image.png") as PreviewImageBytes
        assertArrayEquals(bytes, result.bytes)
        assertEquals("remote:one", result.key.source)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, result.key.version)
        assertTrue(closed)
        verify(exactly = 0) { access.fingerprint(any()) }
        verify(exactly = 0) { access.readBytes(any()) }
    }

    @Test fun reconnectBeforeReadDoesNotAccessTheNewServer() {
        val access = mockk<FileAccessProvider>()
        var source = "remote:one"
        every { access.cacheIdentity() } answers { source }
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder())!!
        source = "remote:two"
        assertNull(reader.readVersionedFile("/home/user/image.png"))
        verify(exactly = 0) { access.openRead(any(), any()) }
    }

    @Test fun reconnectDuringReadCannotPublishAndAlwaysClosesTheOldStream() {
        val access = mockk<FileAccessProvider>()
        var source = "remote:one"
        var closed = false
        every { access.cacheIdentity() } answers { source }
        every { access.openRead(any(), "remote:one") } answers {
            object : ByteArrayInputStream(byteArrayOf(1, 2)) {
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    source = "remote:two"
                    return super.read(buffer, off, len)
                }
                override fun close() { closed = true; super.close() }
            }
        }
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder())!!
        assertNull(reader.readVersionedFile("/home/user/image.png"))
        assertTrue(closed)
    }

    @Test fun overLimitStreamsAreClosedWithoutReturningPartialImages() {
        val access = mockk<FileAccessProvider>()
        var closed = false
        every { access.cacheIdentity() } returns "remote:one"
        every { access.openRead(any(), any()) } answers {
            object : InputStream() {
                var remaining = 20 * 1024 * 1024 + 1
                override fun read(): Int = if (remaining-- > 0) 0 else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    return minOf(remaining, len).also { remaining -= it }
                }
                override fun close() { closed = true }
            }
        }
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder())!!
        assertNull(reader.readVersionedFile("/home/user/image.png"))
        assertTrue(closed)
    }

    @Test fun localPayloadIsStreamDecodableAndRevokesWhenTheFileOrMappingChanges() {
        val access = mockk<FileAccessProvider>()
        val local = mockk<FileAccessProvider>()
        var source = "local:workspace:root:home"
        var version = "hash:one"
        val file = temporary.newFile("image.png").apply { writeBytes(byteArrayOf(1, 2)) }
        every { access.cacheIdentity() } answers { source }
        every { local.cacheIdentity() } answers { source }
        every { local.copyToLocal("~/workspace/image.png") } returns file
        every { local.fingerprint("~/workspace/image.png") } answers { version }
        val reader = captureToolPreviewFileReader(access, local, temporary.newFolder())!!
        val result = reader.readVersionedFile("~/workspace/image.png") as PreviewImageFile
        assertEquals(file.canonicalFile, result.file)
        assertTrue(result.isCurrent())
        assertTrue(result.isSameTarget())
        version = "hash:two"
        assertFalse(result.isCurrent())
        source = "local:other:root:home"
        assertFalse(result.isSameTarget())
        verify(exactly = 0) { access.openRead(any(), any()) }
    }

    @Test fun cancellationIsNotMistakenForAnEmptyImage() {
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:one"
        every { access.openRead(any(), any()) } throws CancellationException("old connection")
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder())!!
        assertThrows(CancellationException::class.java) { reader.readVersionedFile("/home/user/image.png") }
    }
}
