package com.aicode.feature.agent.domain.container.chroot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ProtocolException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootWireProtocolTest {
    private fun encoded(type: ChrootFrameType, body: ByteArray = byteArrayOf()): ByteArray =
        ByteArrayOutputStream().also { ChrootWireProtocol.write(it, type, body) }.toByteArray()

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid frame")
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun writesExactBigEndianNativeFrameHeader() {
        val bytes = encoded(ChrootFrameType.RESIZE, ChrootWireProtocol.resize(100, 40))
        assertArrayEquals(byteArrayOf(65, 74, 67, 82, 0, 1, 0, 5, 0, 0, 0, 4, 0, 100, 0, 40), bytes)
        val frame = checkNotNull(ChrootWireProtocol.read(ByteArrayInputStream(bytes)))
        assertEquals(ChrootFrameType.RESIZE, frame.type)
        assertArrayEquals(byteArrayOf(0, 100, 0, 40), frame.body)
    }

    @Test
    fun allHeaderFragmentsAndShortBodiesFailInsteadOfBecomingEof() {
        val bytes = encoded(ChrootFrameType.READY, ByteArray(32) { 1 })
        for (length in 1 until bytes.size) {
            rejects { ChrootWireProtocol.read(ByteArrayInputStream(bytes.copyOf(length))) }
        }
        assertNull(ChrootWireProtocol.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun oneByteAtATimeReadsRemainComplete() {
        val bytes = encoded(ChrootFrameType.STDOUT, "fixed output".toByteArray())
        val input = object : InputStream() {
            var offset = 0
            override fun read(): Int = if (offset < bytes.size) bytes[offset++].toInt() and 0xff else -1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val byte = read()
                if (byte < 0) return -1
                buffer[off] = byte.toByte()
                return 1
            }
        }
        assertEquals("fixed output", checkNotNull(ChrootWireProtocol.read(input)).body.toString(Charsets.UTF_8))
        assertNull(ChrootWireProtocol.read(input))
    }

    @Test
    fun malformedVersionTypeAndOversizedLengthAreRejectedBeforeBodyRead() {
        val valid = encoded(ChrootFrameType.STOP)
        for (offset in listOf(0, 5, 7)) {
            val invalid = valid.copyOf()
            invalid[offset] = 99
            rejects { ChrootWireProtocol.read(ByteArrayInputStream(invalid)) }
        }
        for (length in listOf(-1, 1048577, 1)) {
            val invalid = valid.copyOf()
            ByteBuffer.wrap(invalid).putInt(8, length)
            rejects { ChrootWireProtocol.read(ByteArrayInputStream(invalid)) }
        }
        rejects { encoded(ChrootFrameType.STDIN, ByteArray(65537)) }
        rejects { encoded(ChrootFrameType.READY, ByteArray(31)) }
    }

    @Test
    fun stdoutAndStderrStaySeparateAcrossAdjacentFrames() {
        val output = ByteArrayOutputStream()
        ChrootWireProtocol.write(output, ChrootFrameType.STDOUT, "response".toByteArray())
        ChrootWireProtocol.write(output, ChrootFrameType.STDERR, "diagnostic".toByteArray())
        val input = ByteArrayInputStream(output.toByteArray())
        val first = checkNotNull(ChrootWireProtocol.read(input))
        val second = checkNotNull(ChrootWireProtocol.read(input))
        assertEquals(ChrootFrameType.STDOUT, first.type)
        assertEquals("response", first.body.toString(Charsets.UTF_8))
        assertEquals(ChrootFrameType.STDERR, second.type)
        assertEquals("diagnostic", second.body.toString(Charsets.UTF_8))
        assertNull(ChrootWireProtocol.read(input))
    }

    @Test
    fun successExitAndVerifiedCleanupAreIndependent() {
        val normal = ChrootWireProtocol.exit(ByteBuffer.allocate(8).putInt(0).putInt(37).array())
        assertEquals(37, normal.code)
        assertNull(normal.signal)
        assertEquals(37, normal.terminalStatus)
        assertEquals(ChrootCleanupResult.UNKNOWN, ChrootWireProtocol.cleanup(byteArrayOf(1)))
        assertEquals(ChrootCleanupResult.VERIFIED, ChrootWireProtocol.cleanup(byteArrayOf(0)))
        val signaled = ChrootWireProtocol.exit(ByteBuffer.allocate(8).putInt(1).putInt(9).array())
        assertNull(signaled.code)
        assertEquals(9, signaled.signal)
        assertEquals(-9, signaled.terminalStatus)
    }

    @Test
    fun invalidExitOrCleanupResultsAreNotAcceptedAsSuccess() {
        rejects { ChrootWireProtocol.exit(ByteArray(7)) }
        rejects { ChrootWireProtocol.exit(ByteBuffer.allocate(8).putInt(2).putInt(0).array()) }
        rejects { ChrootWireProtocol.exit(ByteBuffer.allocate(8).putInt(0).putInt(256).array()) }
        rejects { ChrootWireProtocol.exit(ByteBuffer.allocate(8).putInt(1).putInt(0).array()) }
        rejects { ChrootWireProtocol.cleanup(byteArrayOf(2)) }
        rejects { ChrootWireProtocol.cleanup(byteArrayOf()) }
    }

    @Test
    fun frameCopiesItsInputAndControlsStayWithinNativeRanges() {
        val source = byteArrayOf(1, 2, 3)
        val frame = ChrootFrame(ChrootFrameType.STDOUT, source)
        source.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.body)
        assertTrue(encoded(ChrootFrameType.HEARTBEAT).size == 12)
        assertArrayEquals(byteArrayOf(0, 1, 16, 0), ChrootWireProtocol.resize(1, 4096))
        try {
            ChrootWireProtocol.resize(0, 1)
            throw AssertionError("Expected invalid terminal dimensions")
        } catch (_: IllegalArgumentException) {
        }
    }
}
