package com.aicode.feature.player.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class PlayerConnectionActivityTest {
    private class Clock {
        var now = 0L
        fun seconds(value: Long) { now += TimeUnit.SECONDS.toNanos(value) }
    }

    @Test fun continuouslyActiveStreamSurvivesFarBeyond120Seconds() {
        val clock = Clock()
        val activity = PlayerConnectionActivity({ clock.now })
        activity.headersComplete()
        repeat(360) {
            clock.seconds(10)
            activity.progress()
            assertFalse(activity.timedOut())
        }
        assertEquals(TimeUnit.HOURS.toNanos(1), clock.now)
    }

    @Test fun noReadOrWriteProgressFor45SecondsExpiresAndCannotRevive() {
        val clock = Clock()
        val activity = PlayerConnectionActivity({ clock.now })
        activity.headersComplete()
        clock.seconds(44)
        assertFalse(activity.timedOut())
        clock.seconds(1)
        assertTrue(activity.timedOut())
        assertThrows(SocketTimeoutException::class.java) { activity.progress() }
    }

    @Test fun requestHeaderHasAbsolute15SecondBudgetDespiteTrickledBytes() {
        val clock = Clock()
        val activity = PlayerConnectionActivity({ clock.now })
        repeat(14) {
            clock.seconds(1)
            activity.progress()
            assertFalse(activity.timedOut())
        }
        clock.seconds(1)
        assertTrue(activity.timedOut())
        assertThrows(SocketTimeoutException::class.java) { activity.headersComplete() }
    }

    @Test fun completingHeadersStartsAFreshIdleBudget() {
        val clock = Clock()
        val activity = PlayerConnectionActivity({ clock.now })
        clock.seconds(14)
        activity.headersComplete()
        clock.seconds(44)
        assertFalse(activity.timedOut())
        activity.progress()
        clock.seconds(44)
        assertFalse(activity.timedOut())
        clock.seconds(1)
        assertTrue(activity.timedOut())
    }

    @Test fun responseStreamingReportsActualReadsAndWritesForLongTransfers() {
        val clock = Clock()
        val activity = PlayerConnectionActivity({ clock.now })
        activity.headersComplete()
        val destination = ByteArrayOutputStream()
        val response = PlayerHttpResponse(destination, false, activity::progress)
        response.headers(200)
        var reads = 0
        response.stream(object : InputStream() {
            override fun read(): Int = error("Expected bulk reads")
            override fun read(bytes: ByteArray, off: Int, len: Int): Int {
                if (reads == 20) return -1
                reads++
                clock.seconds(10)
                bytes[off] = 65
                return 1
            }
        })
        assertEquals(20, reads)
        assertEquals(TimeUnit.SECONDS.toNanos(200), clock.now)
        assertFalse(activity.timedOut())
        assertEquals("A".repeat(20), destination.toString().substringAfter("\r\n\r\n"))
        clock.seconds(45)
        assertTrue(activity.timedOut())
    }

    @Test fun progressInputCountsBytesButNotEofOrZeroLengthReads() {
        var progress = 0
        val input = PlayerProgressInputStream(byteArrayOf(1, 2, 3).inputStream()) { progress++ }
        input.use {
            assertEquals(0, it.read(ByteArray(0), 0, 0))
            assertEquals(0, progress)
            assertEquals(1, it.read())
            assertEquals(2, it.read(ByteArray(10), 0, 10))
            assertEquals(-1, it.read())
            assertEquals(2, progress)
        }
    }
}
