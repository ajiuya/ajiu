package com.aicode.feature.agent.domain.container.chroot

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.nio.ByteBuffer

internal enum class ChrootFrameType(val wire: Int) {
    LAUNCH(1), GO(2), STDIN(3), STDIN_EOF(4), RESIZE(5), SIGNAL(6), HEARTBEAT(7), STOP(8),
    READY(128), STDOUT(129), STDERR(130), EXIT(131), CLEANUP(132), ERROR(133)
}

internal class ChrootFrame(val type: ChrootFrameType, body: ByteArray) {
    val body: ByteArray = body.copyOf()
}

internal object ChrootWireProtocol {
    private const val MAGIC = 0x414a4352
    private const val VERSION = 1
    private const val MAX_DATA = 64 * 1024

    fun read(input: InputStream): ChrootFrame? {
        val first = input.read()
        if (first < 0) return null
        val bytes = ByteArray(12)
        bytes[0] = first.toByte()
        try {
            DataInputStream(input).readFully(bytes, 1, 11)
        } catch (_: EOFException) {
            throw ProtocolException("Incomplete runtime frame header")
        }
        val header = ByteBuffer.wrap(bytes)
        if (header.int != MAGIC || header.short.toInt() != VERSION) {
            throw ProtocolException("Unsupported runtime frame")
        }
        val wire = header.short.toInt() and 0xffff
        val type = ChrootFrameType.entries.firstOrNull { it.wire == wire }
            ?: throw ProtocolException("Unsupported runtime frame type")
        val length = header.int
        validateLength(type, length)
        val body = ByteArray(length)
        try {
            DataInputStream(input).readFully(body)
        } catch (_: EOFException) {
            throw ProtocolException("Incomplete runtime frame body")
        }
        return ChrootFrame(type, body)
    }

    fun write(output: OutputStream, type: ChrootFrameType, body: ByteArray = byteArrayOf()) {
        validateLength(type, body.size)
        synchronized(output) {
            val stream = DataOutputStream(output)
            stream.writeInt(MAGIC)
            stream.writeShort(VERSION)
            stream.writeShort(type.wire)
            stream.writeInt(body.size)
            stream.write(body)
            stream.flush()
        }
    }

    fun resize(columns: Int, rows: Int): ByteArray {
        require(columns in 1..4096 && rows in 1..4096)
        return ByteBuffer.allocate(4).putShort(columns.toShort()).putShort(rows.toShort()).array()
    }

    fun exit(body: ByteArray): ChrootProcessExit {
        if (body.size != 8) throw ProtocolException("Invalid runtime exit frame")
        val buffer = ByteBuffer.wrap(body)
        val kind = buffer.int
        val code = buffer.int
        if (kind !in 0..1 || code < 0 || (kind == 0 && code > 255) || (kind == 1 && code !in 1..64)) {
            throw ProtocolException("Invalid runtime exit status")
        }
        return ChrootProcessExit(if (kind == 0) code else null, if (kind == 1) code else null)
    }

    fun cleanup(body: ByteArray): ChrootCleanupResult {
        if (body.size != 1 || body[0].toInt() !in 0..1) throw ProtocolException("Invalid runtime cleanup frame")
        return if (body[0].toInt() == 0) ChrootCleanupResult.VERIFIED else ChrootCleanupResult.UNKNOWN
    }

    private fun validateLength(type: ChrootFrameType, length: Int) {
        val valid = length in 0..ChrootLaunch.MAX_PAYLOAD_BYTES && when (type) {
            ChrootFrameType.LAUNCH -> length > 0
            ChrootFrameType.GO, ChrootFrameType.STDIN_EOF, ChrootFrameType.HEARTBEAT, ChrootFrameType.STOP -> length == 0
            ChrootFrameType.STDIN, ChrootFrameType.STDOUT, ChrootFrameType.STDERR -> length <= MAX_DATA
            ChrootFrameType.RESIZE -> length == 4
            ChrootFrameType.SIGNAL, ChrootFrameType.CLEANUP -> length == 1
            ChrootFrameType.READY -> length == 32
            ChrootFrameType.EXIT -> length == 8
            ChrootFrameType.ERROR -> length in 1..1024
        }
        if (!valid) throw ProtocolException("Invalid runtime frame length")
    }
}

data class ChrootProcessExit(val code: Int?, val signal: Int?) {
    val terminalStatus: Int get() = code ?: -checkNotNull(signal)
}

enum class ChrootCleanupResult { VERIFIED, UNKNOWN }
