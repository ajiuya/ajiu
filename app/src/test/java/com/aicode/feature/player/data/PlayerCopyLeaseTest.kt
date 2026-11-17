package com.aicode.feature.player.data

import com.aicode.feature.workspace.domain.FileEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PlayerCopyLeaseTest {
    private class Fixture : AutoCloseable {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val channelCloses = AtomicInteger()
        val opens = AtomicInteger()
        private val cancelled = AtomicBoolean()
        val resources = PlayerResources()
        val files = CopyOnWriteArrayList<File>()
        private val source = object : InputStream() {
            private var sent = false
            override fun read(): Int = error("Buffered read expected")
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS))
                if (cancelled.get()) throw IOException("Channel closed")
                if (sent) return -1
                sent = true
                "0123456789".toByteArray().copyInto(bytes, off)
                return 10
            }
            override fun close() {
                if (cancelled.compareAndSet(false, true)) {
                    channelCloses.incrementAndGet()
                    released.countDown()
                }
            }
        }
        private val access = playerFileAccess(identity = { "server:1" }, entries = { listOf(FileEntry("a.mp4", false, 10, 1)) },
            stream = { opens.incrementAndGet(); source })
        val library = PlayerLibrary(TestPlayerFileExecution(access), "~/workspace", "/T/", resources, createCopy = {
            File.createTempFile("player_copy_test_", ".copy").also { files += it }
        }).also { resources.add(it) }

        fun awaitConsumers(count: Int) {
            val mutex = PlayerLibrary::class.java.getDeclaredField("copies").apply { isAccessible = true }.get(library)
            val field = PlayerLibrary::class.java.getDeclaredField("flights").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                val size = synchronized(mutex) {
                    val flight = (field.get(library) as Map<*, *>).values.firstOrNull()
                    if (flight == null) 0 else (flight.javaClass.getDeclaredField("consumers").apply { isAccessible = true }.get(flight) as Set<*>).size
                }
                if (size == count) return
                Thread.yield()
            }
            fail("Expected $count copy consumers")
        }

        override fun close() { resources.close(); released.countDown(); files.forEach { it.delete() } }
    }

    @Test fun oneCancelledConsumerDoesNotCancelSharedCopyAndLastReleaseClosesOwnChannel() {
        val f = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        val firstConnection = PlayerResources()
        val secondConnection = PlayerResources()
        try {
            val first = pool.submit<Long> { f.library.openValidated("~/workspace/a.mp4", connection = firstConnection).use { it.size } }
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit<Long> { f.library.openValidated("~/workspace/a.mp4", connection = secondConnection).use { it.size } }
            f.awaitConsumers(2)
            firstConnection.close()
            assertEquals(0, f.channelCloses.get())
            f.released.countDown()
            assertEquals(10L, second.get(2, TimeUnit.SECONDS).toLong())
            assertThrows(ExecutionException::class.java) { first.get(2, TimeUnit.SECONDS) }
            assertEquals(1, f.opens.get())
            assertEquals(1, f.channelCloses.get())
            f.library.close()
            assertTrue(f.files.all { !it.exists() })
        } finally { firstConnection.close(); secondConnection.close(); f.close(); pool.shutdownNow() }
    }

    @Test fun lastConsumerDisconnectUnblocksReadDeletesPartialCopyAndDoesNotWaitForTimeout() {
        val f = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        val firstConnection = PlayerResources()
        val secondConnection = PlayerResources()
        try {
            val first = pool.submit { f.library.openValidated("~/workspace/a.mp4", connection = firstConnection).close() }
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit { f.library.openValidated("~/workspace/a.mp4", connection = secondConnection).close() }
            f.awaitConsumers(2)
            firstConnection.close()
            assertEquals(0, f.channelCloses.get())
            secondConnection.close()
            assertEquals(1, f.channelCloses.get())
            assertThrows(ExecutionException::class.java) { first.get(2, TimeUnit.SECONDS) }
            assertThrows(ExecutionException::class.java) { second.get(2, TimeUnit.SECONDS) }
            assertTrue(f.files.all { !it.exists() })
            assertEquals(1, f.channelCloses.get())
        } finally { firstConnection.close(); secondConnection.close(); f.close(); pool.shutdownNow() }
    }

    @Test fun headAndInvalidRangeNeverDownloadEvenWithAnotherCopyInFlight() {
        val f = Fixture()
        val pool = Executors.newSingleThreadExecutor()
        val connection = PlayerResources()
        try {
            f.library.openValidated("~/workspace/a.mp4", metadataOnly = true).use { assertNull(it.descriptor); assertEquals(10L, it.size) }
            assertEquals(0, f.opens.get())
            assertEquals(416, assertThrows(PlayerHttpException::class.java) {
                f.library.openValidated("~/workspace/a.mp4") { parseRange("bytes=11-", it) }
            }.status)
            assertEquals(0, f.opens.get())
            val copy = pool.submit { f.library.openValidated("~/workspace/a.mp4", connection = connection).close() }
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            f.library.openValidated("~/workspace/a.mp4", metadataOnly = true).use { assertNull(it.descriptor) }
            assertEquals(1, f.opens.get())
            connection.close()
            assertThrows(ExecutionException::class.java) { copy.get(2, TimeUnit.SECONDS) }
        } finally { connection.close(); f.close(); pool.shutdownNow() }
    }

    @Test fun onlyCopiedBytesAdvanceTheExisting45SecondIdleClock() {
        val now = AtomicLong()
        val activity = PlayerConnectionActivity(now::get)
        activity.headersComplete()
        val f = Fixture()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit { f.library.openValidated("~/workspace/a.mp4", progress = activity::progress).close() }
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            now.set(TimeUnit.SECONDS.toNanos(40))
            f.released.countDown()
            result.get(2, TimeUnit.SECONDS)
            now.set(TimeUnit.SECONDS.toNanos(80))
            assertFalse(activity.timedOut())
            now.set(TimeUnit.SECONDS.toNanos(85))
            assertTrue(activity.timedOut())
            assertEquals(1, f.channelCloses.get())
        } finally { f.close(); pool.shutdownNow() }
    }

    @Test fun sessionInvalidationClosesOwnedStreamAndFailsAllWaitingConsumers() {
        val f = Fixture()
        val pool = Executors.newFixedThreadPool(2)
        val firstConnection = PlayerResources()
        val secondConnection = PlayerResources()
        try {
            val first = pool.submit { f.library.openValidated("~/workspace/a.mp4", connection = firstConnection).close() }
            assertTrue(f.entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit { f.library.openValidated("~/workspace/a.mp4", connection = secondConnection).close() }
            f.awaitConsumers(2)
            f.resources.close()
            assertEquals(1, f.channelCloses.get())
            assertThrows(ExecutionException::class.java) { first.get(2, TimeUnit.SECONDS) }
            assertThrows(ExecutionException::class.java) { second.get(2, TimeUnit.SECONDS) }
            assertTrue(f.files.all { !it.exists() })
            assertThrows(IOException::class.java) { f.library.openValidated("~/workspace/a.mp4") }
            assertEquals(1, f.opens.get())
        } finally { firstConnection.close(); secondConnection.close(); f.close(); pool.shutdownNow() }
    }
}
