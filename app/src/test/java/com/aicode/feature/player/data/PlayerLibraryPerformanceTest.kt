package com.aicode.feature.player.data

import com.aicode.feature.workspace.domain.FileEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlayerLibraryPerformanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun newCopy() = File.createTempFile("player_remote_", ".copy", temporary.root)

    @Test fun samePathSingleflightDoesNotBlockDifferentPath() {
        val pool = Executors.newFixedThreadPool(3)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val copies = AtomicInteger()
        val access = playerFileAccess(entries = { listOf(FileEntry("a.mp4", false, 10, 0), FileEntry("b.mp4", false, 10, 0)) }, stream = { path ->
            if (path.endsWith("a.mp4")) {
                copies.incrementAndGet()
                blocked.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "aaaaaaaaaa".byteInputStream()
            } else "bbbbbbbbbb".byteInputStream()
        })
        val library = PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(), createCopy = ::newCopy)
        try {
            library.openValidated("~/workspace/b.mp4").use { assertEquals(10L, it.size) }
            val first = pool.submit<Long> { library.openValidated("~/workspace/a.mp4").use { it.size } }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val same = pool.submit<Long> { library.openValidated("~/workspace/a.mp4").use { it.size } }
            val other = pool.submit<Int> { library.openValidated("~/workspace/b.mp4").use { it.descriptor!!.read() } }
            assertEquals(98, other.get(2, TimeUnit.SECONDS).toInt())
            release.countDown()
            assertEquals(10L, first.get(2, TimeUnit.SECONDS).toLong())
            assertEquals(10L, same.get(2, TimeUnit.SECONDS).toLong())
            assertEquals(1, copies.get())
        } finally { release.countDown(); pool.shutdownNow(); library.close() }
    }

    @Test fun remoteMtimeInvalidatesOwnedCopyAndFrozenGenerationRejectsReuse() {
        var identity = "connection:1"
        var mtime = 1L
        val files = mutableListOf<File>()
        val access = playerFileAccess(identity = { identity }, entries = { listOf(FileEntry("a.mp4", false, 10, mtime)) },
            stream = { "0123456789".byteInputStream() })
        fun library() = PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(),
            createCopy = { newCopy().also { files += it } })
        library().use { first ->
            repeat(2) { first.openValidated("~/workspace/a.mp4").close() }
            assertEquals(1, files.size)
            mtime++
            first.openValidated("~/workspace/a.mp4").close()
            assertEquals(2, files.size)
            assertFalse(files[0].exists())
            identity = "connection:2"
            assertThrows(CancellationException::class.java) { first.openValidated("~/workspace/a.mp4") }
            assertEquals(2, files.size)
        }
        assertFalse(files[1].exists())
        library().use { next ->
            next.openValidated("~/workspace/a.mp4").use { assertEquals(10L, it.size) }
            assertEquals(3, files.size)
        }
        assertTrue(files.all { !it.exists() })
    }

    @Test fun localCacheHitRevalidatesEntriesAndResolvedTarget() {
        val directory = temporary.newFolder("local")
        val a = File(directory, "a.mp4").apply { writeText("0123456789") }
        var target = a
        val walks = AtomicInteger()
        val access = playerFileAccess(entries = {
            walks.incrementAndGet()
            listOf(FileEntry("a.mp4", false, a.length(), a.lastModified(), a))
        }, copy = { target })
        val library = PlayerLibrary(TestPlayerFileExecution(access), directory.path, "/T/", PlayerResources(), createCopy = { error("Local reads do not create copies") })
        val outside = temporary.newFile("outside.mp4").apply { writeText("0123456789") }
        library.use {
            it.openValidated(a.path).close()
            val initial = walks.get()
            repeat(20) { library.openValidated(a.path).close() }
            assertTrue("Cache hits must still revalidate the frozen source", walks.get() > initial)
            a.writeText("updated and longer")
            it.openValidated(a.path).use { opened -> assertEquals(a.length(), opened.size) }
            a.writeText("same sized replacement")
            target = outside
            assertEquals(403, assertThrows(PlayerHttpException::class.java) { it.openValidated(a.path) }.status)
        }
    }

    @Test fun generationChangeDuringDiscoveryFailsBeforeOpeningAnyStream() {
        var identity = "connected:1"
        var opens = 0
        val access = playerFileAccess(identity = { identity }, entries = {
            identity = "connected:2"
            listOf(FileEntry("a.mp4", false, 10, 0))
        }, stream = { opens++; "0123456789".byteInputStream() })
        PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(), createCopy = ::newCopy).use {
            assertThrows(CancellationException::class.java) { it.openValidated("~/workspace/a.mp4") }
            assertEquals(0, opens)
        }
    }

    @Test fun generationChangeDuringDownloadNeverPublishesAndDeletesPartialCopy() {
        var identity = "connection:1"
        val owned = File(temporary.root, "partial.copy")
        var closed = false
        val access = playerFileAccess(identity = { identity }, entries = { listOf(FileEntry("a.mp4", false, 10, 0)) }, stream = {
            object : java.io.ByteArrayInputStream("0123456789".toByteArray()) {
                override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                    identity = "connection:2"
                    return super.read(bytes, off, len)
                }
                override fun close() { closed = true; super.close() }
            }
        })
        PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", PlayerResources(), createCopy = { owned }).use {
            assertThrows(CancellationException::class.java) { it.openValidated("~/workspace/a.mp4") }
            assertFalse(owned.exists())
            assertTrue(closed)
        }
    }
}
