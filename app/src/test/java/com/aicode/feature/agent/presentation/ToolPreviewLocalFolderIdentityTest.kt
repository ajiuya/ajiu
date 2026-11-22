package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.presentation.preview.PreviewImageBytes
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.localRootPath
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolPreviewLocalFolderIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val path = localRootPath("folder", "epoch") + "/image.png"

    @Test
    fun localFolderCacheIdentityNeverBecomesTheRemoteReadToken() {
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:captured"
        every { access.openRead(path, "remote:captured") } answers { byteArrayOf(1, 2, 3).inputStream() }
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder(),
            sourceSuffix = ":local-roots:7")!!
        val image = reader.readVersionedFile(path) as PreviewImageBytes
        assertEquals("remote:captured:local-roots:7", reader.source)
        assertEquals(reader.source, image.key.source)
        assertArrayEquals(byteArrayOf(1, 2, 3), image.bytes)
        verify(exactly = 1) { access.openRead(path, "remote:captured") }
        verify(exactly = 0) { access.openRead(path, "remote:captured:local-roots:7") }
    }

    @Test
    fun revocationInvalidatesAlreadyDecodedPayloadAndFurtherReads() {
        var authorized = true
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:captured"
        every { access.openRead(path, "remote:captured") } answers { byteArrayOf(1).inputStream() }
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder(),
            capturedIsCurrent = { authorized })!!
        val image = reader.readVersionedFile(path) as PreviewImageBytes
        assertTrue(image.isCurrent())
        authorized = false
        assertFalse(image.isCurrent())
        assertNull(reader.readVersionedFile(path))
        verify(exactly = 1) { access.openRead(path, "remote:captured") }
    }

    @Test
    fun revocationWhileReadingDoesNotPublishTheBytes() {
        var authorized = true
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:captured"
        every { access.openRead(path, "remote:captured") } answers {
            object : java.io.ByteArrayInputStream(byteArrayOf(1, 2)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { authorized = false }
            }
        }
        val reader = captureToolPreviewFileReader(access, null, temporary.newFolder(),
            capturedIsCurrent = { authorized })!!
        assertNull(reader.readVersionedFile(path))
    }

    @Test
    fun prootModeNeverUsesItsLocalMapperForAVirtualHostFolder() {
        val access = mockk<FileAccessProvider>()
        val proot = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "local:container"
        every { access.openRead(path, "local:container") } answers { byteArrayOf(9).inputStream() }
        val reader = captureToolPreviewFileReader(access, proot, temporary.newFolder())!!
        val image = reader.readVersionedFile(path) as PreviewImageBytes
        assertArrayEquals(byteArrayOf(9), image.bytes)
        verify(exactly = 0) { proot.copyToLocal(any()) }
        verify(exactly = 0) { proot.cacheIdentity() }
        verify(exactly = 1) { access.openRead(path, "local:container") }
    }

    @Test
    fun newGrantRevisionGetsADifferentImageCacheKeyForTheSameLogicalPath() {
        val access = mockk<FileAccessProvider>()
        every { access.cacheIdentity() } returns "remote:captured"
        every { access.openRead(path, "remote:captured") } answers { byteArrayOf(1).inputStream() }
        val cache = temporary.newFolder()
        val before = captureToolPreviewFileReader(access, null, cache, sourceSuffix = ":local-roots:7")!!
            .readVersionedFile(path) as PreviewImageBytes
        val after = captureToolPreviewFileReader(access, null, cache, sourceSuffix = ":local-roots:8")!!
            .readVersionedFile(path) as PreviewImageBytes
        assertNotEquals(before.key, after.key)
        assertEquals(before.key.version, after.key.version)
    }
}
