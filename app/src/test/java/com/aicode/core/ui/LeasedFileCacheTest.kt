package com.aicode.core.ui

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LeasedFileCacheTest {
    private fun file(bytes: Int = 4): File = File.createTempFile("leased-image-test-", ".cache").apply {
        writeBytes(ByteArray(bytes) { 1 })
    }

    @Test
    fun readersShareOneDownloadAndTheLastReleaseAllowsTtlEviction() = runTest {
        var now = 0L
        val cache = LeasedFileCache<String>(2, 100, 10, { now }, backgroundScope)
        val downloaded = file()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        try {
            val first = async { cache.acquire("same") { loads++; gate.await(); LeasedFileCache.Resource(downloaded, true) } }
            val second = async { cache.acquire("same") { error("duplicate download") } }
            runCurrent()
            gate.complete(Unit)
            val one = first.await()
            val two = second.await()
            assertEquals(1, loads)
            assertSame(one.file, two.file)
            now = 50
            cache.trim()
            assertTrue(downloaded.isFile)
            one.close()
            one.close()
            assertEquals(1, cache.stats().readers)
            now = 100
            cache.trim()
            assertTrue(downloaded.isFile)
            two.close()
            now = 110
            cache.trim()
            assertFalse(downloaded.exists())
            assertEquals(0, cache.stats().entries)
        } finally { downloaded.delete() }
    }

    @Test
    fun oversizeIsStillReadableUntilItsLeaseCloses() = runTest {
        val cache = LeasedFileCache<String>(1, 2, 10, scope = backgroundScope)
        val downloaded = file(64)
        try {
            val task = async { cache.acquire("large") { LeasedFileCache.Resource(downloaded, true) } }
            runCurrent()
            val lease = task.await()
            cache.trim()
            assertEquals(64, lease.file.readBytes().size)
            assertEquals(64L, cache.stats().bytes)
            lease.close()
            assertFalse(downloaded.exists())
            assertEquals(0L, cache.stats().bytes)
        } finally { downloaded.delete() }
    }

    @Test
    fun cacheNeverDeletesMappedWorkspaceOrBorrowedAttachmentFiles() = runTest {
        var now = 0L
        val cache = LeasedFileCache<String>(1, 1, 10, { now }, backgroundScope)
        val borrowed = file(16)
        try {
            val task = async { cache.acquire("local") { LeasedFileCache.Resource(borrowed, false) } }
            runCurrent()
            task.await().close()
            now = 100
            cache.trim()
            assertEquals(0, cache.stats().entries)
            assertTrue(borrowed.isFile)
            assertEquals(16, borrowed.readBytes().size)
        } finally { borrowed.delete() }
    }

    @Test
    fun cancellationIsIsolatedAndTheLastConsumerCancelsUnneededDownload() = runTest {
        val cache = LeasedFileCache<String>(2, 100, 10, scope = backgroundScope)
        var stopped = 0
        val first = async { cache.acquire("pending") { try { awaitCancellation() } finally { stopped++ } } }
        val second = async { cache.acquire("pending") { error("duplicate download") } }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(0, stopped)
        assertEquals(1, cache.stats().readers)
        second.cancelAndJoin()
        runCurrent()
        assertEquals(1, stopped)
        assertEquals(0, cache.stats().entries)
    }

    @Test
    fun lruDoesNotUnlinkPinnedReadersAndSourceIdentitySeparatesEqualPaths() = runTest {
        val cache = LeasedFileCache<Pair<String, String>>(1, 100, 10, scope = backgroundScope)
        val firstFile = file()
        val secondFile = file()
        try {
            val first = async { cache.acquire("source-a" to "/same.png") { LeasedFileCache.Resource(firstFile, true) } }
            val second = async { cache.acquire("source-b" to "/same.png") { LeasedFileCache.Resource(secondFile, true) } }
            runCurrent()
            val one = first.await()
            val two = second.await()
            assertNotEquals(one.file, two.file)
            cache.trim()
            assertTrue(firstFile.exists())
            assertTrue(secondFile.exists())
            two.close()
            assertFalse(secondFile.exists())
            assertTrue(firstFile.exists())
            one.close()
        } finally { firstFile.delete(); secondFile.delete() }
    }
}
