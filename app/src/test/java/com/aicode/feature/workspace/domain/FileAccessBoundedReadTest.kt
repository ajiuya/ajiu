package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class FileAccessBoundedReadTest {
    @Test
    fun exactLimitAndEmptyZeroLimitAreAccepted() {
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readBytesBounded(0))
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesBounded(3))
        assertArrayEquals(byteArrayOf(1), ByteArrayInputStream(byteArrayOf(1)).readBytesBounded(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun nonEmptyZeroLimitReadsOnlyOneProbeByteAndCloses() {
        var transferred = 0
        var closed = 0
        val input = object : ByteArrayInputStream(ByteArray(100)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) transferred += it }
            override fun close() { closed++ }
        }
        val failure = assertThrows(FileReadTooLargeException::class.java) {
            input.use { it.readBytesBounded(0) }
        }
        assertEquals(0L, failure.maxBytes)
        assertEquals(1, transferred)
        assertEquals(1, closed)
    }

    @Test
    fun growingStreamTransfersAtMostLimitPlusOneWithShortReads() {
        val limit = 17_000L
        var transferred = 0L
        var largestEnd = 0L
        var closed = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk read required")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestEnd = maxOf(largestEnd, transferred + len)
                val count = minOf(len, 13)
                b.fill(7, off, off + count)
                transferred += count
                return count
            }
            override fun close() { closed++ }
        }
        assertThrows(FileReadTooLargeException::class.java) { input.use { it.readBytesBounded(limit) } }
        assertEquals(limit + 1, transferred)
        assertEquals(limit + 1, largestEnd)
        assertEquals(1, closed)
    }

    @Test
    fun zeroProgressFailsInsteadOfSpinningAndCloses() {
        var calls = 0
        var closed = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Bulk read required")
            override fun read(b: ByteArray, off: Int, len: Int): Int { calls++; return 0 }
            override fun close() { closed++ }
        }
        assertThrows(IOException::class.java) { input.use { it.readBytesBounded(20) } }
        assertEquals(1, calls)
        assertEquals(1, closed)
    }

    @Test
    fun invalidLimitsAreRejectedBeforeAnyRead() {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int { reads++; return -1 }
        }
        for (limit in listOf(-1L, Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { input.readBytesBounded(limit) }
        }
        assertEquals(0, reads)
    }

    @Test
    fun cancelledIdentityReadClosesAndNeverReturnsPartialBytes() {
        var current = true
        var closed = 0
        val input = identityCheckedRead(object : ByteArrayInputStream(ByteArray(4)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { current = false }
            override fun close() { closed++ }
        }) { if (!current) throw CancellationException("Source changed") }
        assertThrows(CancellationException::class.java) { input.use { it.readBytesBounded(4) } }
        assertEquals(1, closed)
    }

    @Test
    fun boundedDelegationUsesTheSelectedMethodAndForwardsResolutionFlag() {
        val holder = ExecutionModeHolder()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        every { local.cacheIdentity() } returns "local"
        every { remote.cacheIdentity() } returns "remote"
        every { local.readBytes("image", 0L) } returns byteArrayOf()
        every { remote.readBytes("image", 3L) } returns byteArrayOf(1, 2, 3)
        every { local.resolvePath("link/", false) } returns "/host/link"
        every { remote.resolvePath("link/", true) } returns "/remote/real"
        val access = DelegatingFileAccess(holder, local, remote, mockk<LocalFolderGrantRepository>())
        assertArrayEquals(byteArrayOf(), access.readBytes("image", 0))
        assertEquals("/host/link", access.resolvePath("link/", false))
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertArrayEquals(byteArrayOf(1, 2, 3), access.readBytes("image", 3))
        assertEquals("/remote/real", access.resolvePath("link/"))
        verify(exactly = 0) { local.readBytes(any()) }
        verify(exactly = 0) { remote.readBytes(any()) }
    }

    @Test
    fun switchingModeDuringBoundedReadDoesNotReadTheNewBackend() {
        val holder = ExecutionModeHolder()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        every { local.cacheIdentity() } returns "local"
        every { local.readBytes("image", 3L) } answers {
            holder.setMode(ExecutionMode.REMOTE_SSH)
            byteArrayOf(1, 2, 3)
        }
        val access = DelegatingFileAccess(holder, local, remote, mockk<LocalFolderGrantRepository>())
        assertThrows(CancellationException::class.java) { access.readBytes("image", 3) }
        verify(exactly = 0) { remote.readBytes(any(), any()) }
        verify(exactly = 0) { remote.openRead(any(), any()) }
        verify(exactly = 0) { remote.cacheIdentity() }
    }

    @Test
    fun changedBackendIdentityDuringBoundedReadIsRejected() {
        val holder = ExecutionModeHolder()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        var identity = "old"
        every { local.cacheIdentity() } answers { identity }
        every { local.readBytes("image", 3L) } answers { identity = "new"; byteArrayOf(1, 2, 3) }
        val access = DelegatingFileAccess(holder, local, remote, mockk<LocalFolderGrantRepository>())
        assertThrows(CancellationException::class.java) { access.readBytes("image", 3) }
        verify(exactly = 0) { remote.readBytes(any(), any()) }
    }
}
