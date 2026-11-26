package com.aicode.feature.workspace.domain.chroot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ChrootFileOperationException(val errno: Int) : IOException("Chroot file operation rejected: $errno")

internal data class ChrootFileStat(
    val kind: Int,
    val mode: Int,
    val size: Long,
    val modifiedMs: Long,
    val device: Long,
    val inode: Long
)

internal class ChrootFileWorkerClient(
    private val input: InputStream,
    private val output: OutputStream,
    private val checkCurrent: () -> Unit,
    private val closeTransport: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val lane = Any()
    private var nextRequestId = 1

    fun stat(path: String, followFinal: Boolean = true): ChrootFileStat = request(1, {
        string(path)
        writeInt(if (followFinal) 1 else 0)
    }) { stat() }

    fun list(path: String): List<Pair<String, ChrootFileStat>> = request(2, { string(path) }) {
        val count = readInt()
        if (count !in 0..10000) throw ProtocolException("Invalid chroot directory count")
        List(count) {
            val name = string()
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name) {
                throw ProtocolException("Invalid chroot directory name")
            }
            name to stat()
        }
    }

    fun resolve(path: String, followFinal: Boolean): String = request(3, {
        string(path)
        writeInt(if (followFinal) 1 else 0)
    }) { string().also { if (!it.startsWith('/')) throw ProtocolException("Invalid resolved chroot path") } }

    fun openRead(path: String): InputStream {
        val handle = request(4, { string(path) }) { handle() }
        return object : InputStream() {
            private var released = false
            private var exhausted = false
            private val single = ByteArray(1)

            override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (length == 0) return 0
                if (exhausted) return -1
                if (released) throw IOException("Chroot read stream closed")
                try {
                    val bytes = request(5, { writeInt(handle); writeInt(minOf(length, MAX_CHUNK)) }) {
                        val count = readInt()
                        if (count !in 0..minOf(length, MAX_CHUNK)) throw ProtocolException("Invalid chroot read length")
                        ByteArray(count).also(::readFully)
                    }
                    if (bytes.isEmpty()) {
                        exhausted = true
                        close()
                        return -1
                    }
                    bytes.copyInto(buffer, offset)
                    return bytes.size
                } catch (failure: Throwable) {
                    runCatching { close() }
                    throw failure
                }
            }

            override fun close() {
                if (!released) {
                    released = true
                    request(6, { writeInt(handle) }) { Unit }
                }
            }
        }
    }

    fun write(path: String, source: InputStream, overwrite: Boolean): Long {
        val handle = request(7, { string(path); writeInt(if (overwrite) 1 else 0) }) { handle() }
        var committed = false
        try {
            val buffer = ByteArray(MAX_CHUNK)
            var total = 0L
            while (true) {
                checkCurrent()
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) throw IOException("File upload made no progress")
                total += count
                if (total > MAX_WRITE_BYTES) throw IOException("Chroot file upload exceeds the stream limit")
                request(8, { writeInt(handle); writeInt(count); write(buffer, 0, count) }) { Unit }
            }
            val result = request(9, { writeInt(handle) }) { readLong() }
            if (result != total) throw ProtocolException("Chroot file commit size mismatch")
            committed = true
            return result
        } finally {
            if (!committed) runCatching { request(10, { writeInt(handle) }) { Unit } }
        }
    }

    fun write(path: String, overwrite: Boolean, writer: (OutputStream) -> Unit): Long {
        val handle = request(7, { string(path); writeInt(if (overwrite) 1 else 0) }) { handle() }
        var committed = false
        var accepting = true
        var total = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
                if (!accepting) throw IOException("Chroot staging write stream closed")
                var at = offset
                while (at < offset + length) {
                    checkCurrent()
                    val count = minOf(MAX_CHUNK, offset + length - at)
                    if (total > MAX_WRITE_BYTES - count) throw IOException("Chroot file upload exceeds the stream limit")
                    request(8, { writeInt(handle); writeInt(count); write(buffer, at, count) }) { Unit }
                    total += count
                    at += count
                }
            }
            override fun close() { accepting = false }
        }
        try {
            writer(sink)
            accepting = false
            checkCurrent()
            val result = request(9, { writeInt(handle) }) { readLong() }
            if (result != total) throw ProtocolException("Chroot file commit size mismatch")
            committed = true
            return result
        } finally {
            accepting = false
            if (!committed) runCatching { request(10, { writeInt(handle) }) { Unit } }
        }
    }

    fun mkdirs(path: String) = request(11, { string(path) }) { Unit }

    fun delete(path: String, recursive: Boolean) = request(12, {
        string(path)
        writeInt(if (recursive) 1 else 0)
    }) { Unit }

    fun rename(source: String, target: String, overwrite: Boolean) = request(13, {
        string(source)
        string(target)
        writeInt(if (overwrite) 1 else 0)
    }) { Unit }

    private fun <T> request(opcode: Int, encode: DataOutputStream.() -> Unit, decode: DataInputStream.() -> T): T = synchronized(lane) {
        if (closed.get()) throw IOException("Chroot file transport closed")
        checkCurrent()
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { it.encode() }
        val body = buffer.toByteArray()
        if (body.size > MAX_FRAME) throw IOException("Chroot file request is oversized")
        val requestId = nextRequestId
        nextRequestId = if (requestId == Int.MAX_VALUE) 1 else requestId + 1
        val timeout = deadlines.schedule({ close() }, 30, TimeUnit.SECONDS)
        try {
            DataOutputStream(output).apply {
                writeInt(MAGIC)
                writeShort(1)
                writeShort(opcode)
                writeInt(requestId)
                writeInt(body.size)
                write(body)
                flush()
            }
            val response = DataInputStream(input)
            val magic = response.readInt()
            val version = response.readUnsignedShort()
            val responseOpcode = response.readUnsignedShort()
            val responseId = response.readInt()
            val size = response.readInt()
            if (magic != MAGIC || version != 1 || responseOpcode != (opcode or 0x8000) ||
                responseId != requestId || size !in 4..MAX_FRAME) {
                throw ProtocolException("Invalid chroot file response header")
            }
            val payload = ByteArray(size).also(response::readFully)
            checkCurrent()
            val result = DataInputStream(ByteArrayInputStream(payload))
            val errno = result.readInt()
            if (errno !in 0..4095) throw ProtocolException("Invalid chroot file errno")
            if (errno != 0) {
                if (result.available() != 0) throw ProtocolException("Unexpected chroot file error body")
                throw ChrootFileOperationException(errno)
            }
            val value = result.decode()
            if (result.available() != 0) throw ProtocolException("Trailing chroot file response bytes")
            checkCurrent()
            value
        } catch (failure: ChrootFileOperationException) {
            throw failure
        } catch (failure: Throwable) {
            close()
            throw failure
        } finally {
            timeout.cancel(false)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeTransport()
    }

    private fun DataOutputStream.string(value: String) {
        require('\u0000' !in value)
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(encoded.remaining() in 1..4096)
        val bytes = ByteArray(encoded.remaining()).also(encoded::get)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.string(): String {
        val length = readInt()
        if (length !in 1..4096) throw ProtocolException("Invalid chroot file text length")
        val bytes = ByteArray(length).also(::readFully)
        val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        if ('\u0000' in value) throw ProtocolException("Invalid chroot file text")
        return value
    }

    private fun DataInputStream.stat(): ChrootFileStat {
        val stat = ChrootFileStat(readInt(), readInt(), readLong(), readLong(), readLong(), readLong())
        if (stat.kind !in 1..3 || stat.mode !in 0..0xffff || stat.size < 0 || stat.device <= 0 || stat.inode <= 0) {
            throw ProtocolException("Invalid chroot file metadata")
        }
        return stat
    }

    private fun DataInputStream.handle(): Int = readInt().also {
        if (it <= 0) throw ProtocolException("Invalid chroot file handle")
    }

    companion object {
        private const val MAGIC = 0x414a4657
        private const val MAX_FRAME = 1024 * 1024
        private const val MAX_CHUNK = 64 * 1024
        private const val MAX_WRITE_BYTES = 512L * 1024 * 1024
        private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "chroot-file-deadline").apply { isDaemon = true }
        }
    }
}
