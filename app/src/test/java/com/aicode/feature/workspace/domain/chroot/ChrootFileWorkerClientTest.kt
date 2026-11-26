package com.aicode.feature.workspace.domain.chroot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ChrootFileWorkerClientTest {
    private fun response(opcode: Int, id: Int, errno: Int = 0, body: DataOutputStream.() -> Unit = {}): ByteArray {
        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { it.writeInt(errno); it.body() }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use {
                it.writeInt(0x414a4657); it.writeShort(1); it.writeShort(opcode or 0x8000)
                it.writeInt(id); it.writeInt(payload.size); it.write(payload)
            }
        }.toByteArray()
    }

    private fun statBody(output: DataOutputStream) {
        output.writeInt(2); output.writeInt(0x1a4); output.writeLong(3)
        output.writeLong(123000); output.writeLong(65098); output.writeLong(99)
    }

    @Test
    fun statRequestUsesFrozenFileProtocolAndMetadata() {
        val output = ByteArrayOutputStream()
        val client = ChrootFileWorkerClient(ByteArrayInputStream(response(1, 1) { statBody(this) }), output, {}, {})
        val stat = client.stat("/root/workspace/a")
        assertEquals(3, stat.size)
        assertEquals(65098, stat.device)
        DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            assertEquals(0x414a4657, it.readInt()); assertEquals(1, it.readUnsignedShort())
            assertEquals(1, it.readUnsignedShort()); assertEquals(1, it.readInt())
            val size = it.readInt()
            assertEquals(4 + "/root/workspace/a".length + 4, size)
            val path = ByteArray(it.readInt()).also(it::readFully)
            assertEquals("/root/workspace/a", path.toString(Charsets.UTF_8))
            assertEquals(1, it.readInt()); assertEquals(-1, it.read())
        }
    }

    @Test
    fun permissionErrorsAreNotConvertedIntoMissingFilesOrTransportSuccess() {
        var closed = false
        val data = response(1, 1, 13) + response(1, 2) { statBody(this) }
        val client = ChrootFileWorkerClient(ByteArrayInputStream(data), ByteArrayOutputStream(), {}, { closed = true })
        try {
            client.stat("/root/workspace/a")
            throw AssertionError("Expected EACCES")
        } catch (failure: ChrootFileOperationException) {
            assertEquals(13, failure.errno)
        }
        assertFalse(closed)
        assertEquals(2, client.stat("/root/workspace/b").kind)
    }

    @Test
    fun mismatchedIdsOversizedResponsesAndTrailingBytesCloseTransport() {
        val cases = listOf(
            response(1, 2) { statBody(this) },
            response(2, 1) { statBody(this) },
            response(1, 1) { statBody(this); writeByte(1) },
            response(1, 1, 13) { writeByte(1) }
        )
        cases.forEach { bytes ->
            var closed = false
            val client = ChrootFileWorkerClient(ByteArrayInputStream(bytes), ByteArrayOutputStream(), {}, { closed = true })
            try {
                client.stat("/root/workspace/a")
                throw AssertionError("Expected malformed response")
            } catch (_: ProtocolException) {
            }
            assertTrue(closed)
        }
    }

    @Test
    fun readStreamRequestsBoundedChunksAndClosesOwnedHandleOnEof() {
        val data = response(4, 1) { writeInt(17) } + response(5, 2) { writeInt(3); write(byteArrayOf(1, 2, 3)) } +
            response(5, 3) { writeInt(0) } + response(6, 4)
        val output = ByteArrayOutputStream()
        val client = ChrootFileWorkerClient(ByteArrayInputStream(data), output, {}, {})
        client.openRead("/root/workspace/a").use {
            val result = ByteArray(100000)
            assertEquals(3, it.read(result))
            assertArrayEquals(byteArrayOf(1, 2, 3), result.copyOf(3))
            assertEquals(-1, it.read(result))
            assertEquals(-1, it.read())
        }
        val opcodes = mutableListOf<Int>()
        DataInputStream(ByteArrayInputStream(output.toByteArray())).use {
            while (it.available() > 0) {
                it.readInt(); it.readUnsignedShort(); val op = it.readUnsignedShort(); opcodes += op
                it.readInt(); val body = ByteArray(it.readInt()).also(it::readFully)
                if (op == 5) assertEquals(65536, DataInputStream(ByteArrayInputStream(body)).run { readInt(); readInt() })
            }
        }
        assertEquals(listOf(4, 5, 5, 6), opcodes)
    }

    @Test
    fun writeCommitsVerifiedLengthAndAbortsOnlyItsOwnHandleOnInputFailure() {
        val success = response(7, 1) { writeInt(5) } + response(8, 2) + response(9, 3) { writeLong(3) }
        val client = ChrootFileWorkerClient(ByteArrayInputStream(success), ByteArrayOutputStream(), {}, {})
        assertEquals(3, client.write("/root/workspace/a", ByteArrayInputStream(byteArrayOf(1, 2, 3)), false))
        val output = ByteArrayOutputStream()
        val failure = response(7, 1) { writeInt(7) } + response(10, 2)
        val broken = ChrootFileWorkerClient(ByteArrayInputStream(failure), output, {}, {})
        try {
            broken.write("/root/workspace/a", object : java.io.InputStream() {
                override fun read(): Int = throw IOException("source unavailable")
            }, false)
            throw AssertionError("Expected source error")
        } catch (_: IOException) {
        }
        val bytes = output.toByteArray()
        assertTrue(bytes.isNotEmpty())
        val input = DataInputStream(ByteArrayInputStream(bytes))
        input.skipBytes(12); input.skipBytes(input.readInt())
        input.readInt(); input.readUnsignedShort(); assertEquals(10, input.readUnsignedShort())
    }

    @Test
    fun callbackWriteSplitsLargeBuffersAndCommitsOnlyTheVerifiedByteCount() {
        val bytes = ByteArray(65536 + 3) { (it % 127).toByte() }
        val success = response(7, 1) { writeInt(5) } + response(8, 2) + response(8, 3) + response(9, 4) { writeLong(bytes.size.toLong()) }
        val output = ByteArrayOutputStream()
        val client = ChrootFileWorkerClient(ByteArrayInputStream(success), output, {}, {})
        var escaped: java.io.OutputStream? = null
        assertEquals(bytes.size.toLong(), client.write("/root/workspace/a", false) {
            escaped = it
            it.write(bytes)
        })
        val frames = requestFrames(output.toByteArray())
        assertEquals(listOf(7, 8, 8, 9), frames.map { it.first })
        val counts = frames.filter { it.first == 8 }.map { (_, body) ->
            DataInputStream(ByteArrayInputStream(body)).run { assertEquals(5, readInt()); readInt() }
        }
        assertEquals(listOf(65536, 3), counts)
        val before = output.size()
        assertThrows(IOException::class.java) { escaped!!.write(1) }
        assertEquals(before, output.size())
    }

    @Test
    fun callbackFailureAbortsItsHandleWithoutCommittingOrClosingAnotherWriter() {
        val responses = response(7, 1) { writeInt(7) } + response(8, 2) + response(10, 3)
        val output = ByteArrayOutputStream()
        val client = ChrootFileWorkerClient(ByteArrayInputStream(responses), output, {}, {})
        val failure = assertThrows(IOException::class.java) {
            client.write("/root/workspace/a", false) { it.write(byteArrayOf(1, 2)); throw IOException("source failed") }
        }
        assertEquals("source failed", failure.message)
        val frames = requestFrames(output.toByteArray())
        assertEquals(listOf(7, 8, 10), frames.map { it.first })
        assertEquals(7, DataInputStream(ByteArrayInputStream(frames.last().second)).readInt())
    }

    @Test
    fun callbackCommitLengthMismatchAbortsAndNeverClaimsVerifiedSuccess() {
        val responses = response(7, 1) { writeInt(7) } + response(8, 2) + response(9, 3) { writeLong(1) } + response(10, 4)
        val output = ByteArrayOutputStream()
        val client = ChrootFileWorkerClient(ByteArrayInputStream(responses), output, {}, {})
        assertThrows(ProtocolException::class.java) { client.write("/root/workspace/a", false) { it.write(byteArrayOf(1, 2)) } }
        assertEquals(listOf(7, 8, 9, 10), requestFrames(output.toByteArray()).map { it.first })
    }

    private fun requestFrames(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (input.available() > 0) {
                assertEquals(0x414a4657, input.readInt())
                assertEquals(1, input.readUnsignedShort())
                val opcode = input.readUnsignedShort()
                input.readInt()
                val body = ByteArray(input.readInt()).also(input::readFully)
                frames += opcode to body
            }
        }
        return frames
    }

    @Test
    fun changedSourceOrCancelledCapturePreventsFurtherRequests() {
        var current = true
        val output = ByteArrayOutputStream()
        val client = ChrootFileWorkerClient(ByteArrayInputStream(byteArrayOf()), output,
            { if (!current) throw java.util.concurrent.CancellationException("source changed") }, {})
        current = false
        try {
            client.mkdirs("/root/workspace/a")
            throw AssertionError("Expected source cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
        }
        assertEquals(0, output.size())
    }
}
