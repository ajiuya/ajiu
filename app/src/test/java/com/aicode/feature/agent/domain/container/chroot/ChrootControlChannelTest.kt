package com.aicode.feature.agent.domain.container.chroot

import java.io.ByteArrayInputStream
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootControlChannelTest {
    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected invalid control input")
        } catch (_: IllegalArgumentException) {
        } catch (_: ProtocolException) {
        }
    }

    @Test
    fun abstractNameIsDerivedOnlyFromOneFrozenNonzeroToken() {
        val token = ByteArray(32) { (it + 1).toByte() }
        val name = ChrootControlChannel.nameFor(token)
        assertEquals("ajiu-chroot-control-0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20", name)
        assertTrue(name.length < 108)
        rejects { ChrootControlChannel.nameFor(ByteArray(32)) }
        rejects { ChrootControlChannel.nameFor(ByteArray(31) { 1 }) }
    }

    @Test
    fun controlFramesAreAtMostSixteenBytesAndMatchNativeWire() {
        for (type in listOf(ChrootFrameType.GO, ChrootFrameType.HEARTBEAT, ChrootFrameType.STOP)) {
            val bytes = ChrootControlChannel.encodeControl(type, byteArrayOf())
            assertEquals(12, bytes.size)
            assertEquals(type, checkNotNull(ChrootWireProtocol.read(ByteArrayInputStream(bytes))).type)
        }
        val size = ChrootWireProtocol.resize(100, 40)
        val bytes = ChrootControlChannel.encodeControl(ChrootFrameType.RESIZE, size)
        assertEquals(16, bytes.size)
        assertArrayEquals(size, checkNotNull(ChrootWireProtocol.read(ByteArrayInputStream(bytes))).body)
        for (signal in listOf(1, 2, 15)) {
            assertEquals(13, ChrootControlChannel.encodeControl(ChrootFrameType.SIGNAL, byteArrayOf(signal.toByte())).size)
        }
    }

    @Test
    fun controlConnectionCannotSendLaunchBusinessDataOrNativeResults() {
        for (type in listOf(ChrootFrameType.LAUNCH, ChrootFrameType.STDIN, ChrootFrameType.STDIN_EOF,
                            ChrootFrameType.READY, ChrootFrameType.STDOUT, ChrootFrameType.STDERR,
                            ChrootFrameType.EXIT, ChrootFrameType.CLEANUP, ChrootFrameType.ERROR)) {
            rejects { ChrootControlChannel.encodeControl(type, byteArrayOf()) }
        }
    }

    @Test
    fun malformedSignalsResizeAndUnexpectedControlBodiesAreRejected() {
        for (signal in listOf(0, 3, 9, 19, 64, -1)) {
            rejects { ChrootControlChannel.encodeControl(ChrootFrameType.SIGNAL, byteArrayOf(signal.toByte())) }
        }
        rejects { ChrootControlChannel.encodeControl(ChrootFrameType.SIGNAL, byteArrayOf()) }
        rejects { ChrootControlChannel.encodeControl(ChrootFrameType.RESIZE, byteArrayOf(0, 0, 0, 40)) }
        rejects { ChrootControlChannel.encodeControl(ChrootFrameType.RESIZE, byteArrayOf(32, 0, 0, 40)) }
        rejects { ChrootControlChannel.encodeControl(ChrootFrameType.GO, byteArrayOf(1)) }
    }
}
