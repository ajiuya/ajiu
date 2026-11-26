package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.ContainerInstaller
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarUtils
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

class ChrootArchiveWriter internal constructor(private val limits: Limits) {
    @Inject
    constructor() : this(Limits())

    internal data class Limits(
        val entries: Int = 65_536,
        val fileBytes: Long = 128L * 1024 * 1024,
        val totalFileBytes: Long = 512L * 1024 * 1024,
        val pathBytes: Int = 4096,
        val linkBytes: Int = 4096,
        val namesBytes: Long = 16L * 1024 * 1024,
        val archiveHeaders: Int = 65_536,
        val metadataBytes: Long = 16L * 1024 * 1024
    ) {
        fun validate() {
            require(entries in 0..65_536 && archiveHeaders in 0..65_536)
            require(fileBytes in 0..128L * 1024 * 1024 && totalFileBytes in 0..512L * 1024 * 1024)
            require(pathBytes in 0..4096 && linkBytes in 0..4096)
            require(namesBytes in 0..16L * 1024 * 1024 && metadataBytes in 0..16L * 1024 * 1024)
        }
    }

    data class Result(
        val entryCount: Int,
        val rawHeaderCount: Int,
        val skippedRootDirectories: Int,
        val fileBytes: Long,
        val namesBytes: Long,
        val metadataBytes: Long,
        val streamBytes: Long,
        val containsResolvConf: Boolean = false
    ) {
        val nativeEntryCount: Int get() = entryCount + if (containsResolvConf) 0 else 1
    }

    fun write(input: InputStream, format: ContainerInstaller.CompressedFormat, out: OutputStream): Result {
        limits.validate()
        var compressedBytes = 0L
        val source = object : FilterInputStream(input) {
            private fun count(size: Int) {
                checkInterrupted()
                if (size > 0) compressedBytes += size
                if (compressedBytes > MAX_COMPRESSED_BYTES) invalid("Compressed archive input limit exceeded")
            }
            override fun read(): Int = `in`.read().also { count(if (it < 0) 0 else 1) }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                `in`.read(bytes, offset, length).also(::count)
            override fun close() = Unit
        }
        var verifyEnd: () -> Unit = {}
        val decoded = when (format) {
            ContainerInstaller.CompressedFormat.GZIP -> {
                val checked = BufferedInputStream(source)
                checked.mark(CHUNK_BYTES)
                val header = ByteArray(10)
                readFully(checked, header)
                val flags = header[3].toInt() and 255
                if (header[0] != 31.toByte() || header[1] != 139.toByte() || header[2] != 8.toByte() ||
                    flags and 224 != 0
                ) invalid("Invalid gzip header")
                var headerBytes = 10
                fun nextHeaderByte(): Int {
                    if (++headerBytes > CHUNK_BYTES) invalid("Gzip header limit exceeded")
                    checkInterrupted()
                    return checked.read().takeIf { it >= 0 } ?: invalid("Truncated gzip header")
                }
                if (flags and 4 != 0) {
                    val extraLength = nextHeaderByte() or (nextHeaderByte() shl 8)
                    repeat(extraLength) { nextHeaderByte() }
                }
                if (flags and 8 != 0) while (nextHeaderByte() != 0) Unit
                if (flags and 16 != 0) while (nextHeaderByte() != 0) Unit
                if (flags and 2 != 0) repeat(2) { nextHeaderByte() }
                checked.reset()
                verifyEnd = {
                    if (checked.read() != -1) invalid("Concatenated gzip members or trailing compressed data are unsupported")
                }
                GzipCompressorInputStream(checked, false)
            }
            ContainerInstaller.CompressedFormat.XZ -> XZCompressorInputStream(source, true, 65_536)
        }
        return decoded.use { encode(it, out, verifyEnd) }
    }

    private fun encode(input: InputStream, out: OutputStream, verifyEnd: () -> Unit): Result {
        var written = 0L
        val counted = object : FilterOutputStream(out) {
            override fun write(value: Int) {
                out.write(value)
                written++
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                out.write(bytes, offset, count)
                written += count
            }
        }
        val output = DataOutputStream(counted)
        val seen = HashSet<String>()
        val regularFiles = HashSet<String>()
        val global = HashMap<String, String>()
        val local = ArrayList<Map<String, String>>()
        var headers = 0
        var roots = 0
        var names = 0L
        var files = 0L
        var metadata = 0L
        var metadataDepth = 0
        val header = ByteArray(BLOCK_BYTES)
        val buffer = ByteArray(CHUNK_BYTES)
        output.write(byteArrayOf(65, 74, 73, 70, 0, 1, 0, 0))
        while (true) {
            readFully(input, header)
            if (header.all { it == 0.toByte() }) {
                if (metadataDepth != 0) invalid("Tar metadata has no following entry")
                readFully(input, header)
                if (header.any { it != 0.toByte() }) invalid("Invalid tar end marker")
                var trailing = 0
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) invalid("Archive input made no progress")
                    trailing += count
                    if (trailing > CHUNK_BYTES || (0 until count).any { buffer[it] != 0.toByte() }) {
                        invalid("Unsupported trailing tar data")
                    }
                }
                break
            }
            if (++headers > limits.archiveHeaders) invalid("Raw archive header count limit exceeded")
            val flag = header[156].toInt() and 0xff
            if (flag !in SUPPORTED_TYPES) invalid("Special, sparse or unknown tar entry")
            val rawName = field(header, 0, 100)
            val rawLink = field(header, 157, 100)
            val prefix = field(header, 345, 155)
            val magic = header.copyOfRange(257, 265)
            val posix = magic.contentEquals(byteArrayOf(117, 115, 116, 97, 114, 0, 48, 48))
            val gnu = magic.contentEquals(byteArrayOf(117, 115, 116, 97, 114, 32, 32, 0))
            if (!posix && !gnu) invalid("Only USTAR and bounded GNU/PAX archives are supported")
            val entry = try {
                TarArchiveEntry(header)
            } catch (e: RuntimeException) {
                throw IOException("Invalid tar header", e)
            }
            if (!entry.isCheckSumOK) invalid("Invalid tar header checksum")
            val rawSize = entry.size
            if (rawSize < 0) invalid("Negative tar data length")
            if (flag in METADATA_TYPES) {
                if (rawSize > CHUNK_BYTES || ++metadataDepth > MAX_METADATA_DEPTH) invalid("Tar metadata buffer limit exceeded")
                if ((flag == 76 || flag == 75) && rawSize !in 1..4097L) invalid("Invalid GNU long-name data length")
                val padded = paddedSize(rawSize)
                if (metadata + padded > limits.metadataBytes) invalid("Total tar metadata limit exceeded")
                metadata += padded
                val data = ByteArray(rawSize.toInt())
                readFully(input, data)
                padding(input, rawSize, buffer)
                when (flag) {
                    76, 75 -> {
                        if (data.isEmpty() || data.size > 4097 || data.last() != 0.toByte() ||
                            data.dropLast(1).any { it == 0.toByte() }
                        ) invalid("Invalid GNU long-name terminator or length")
                        val value = utf8(data.copyOf(data.size - 1))
                        local.add(mapOf((if (flag == 76) "path" else "linkpath") to value))
                    }
                    120 -> local.add(pax(data))
                    103 -> {
                        pax(data).forEach { (key, value) ->
                            if (value.isEmpty()) global.remove(key) else global[key] = value
                        }
                    }
                }
                continue
            }
            metadataDepth = 0
            val kind = when (flag) {
                53 -> 1
                0, 48 -> 2
                50 -> 3
                49 -> 4
                else -> invalid("Unsupported tar entry")
            }
            if (kind != 2 && rawSize != 0L) invalid("Non-file tar entry has data")
            val effective = HashMap(global)
            local.asReversed().forEach(effective::putAll)
            local.clear()
            var name = effective["path"] ?: if (posix && prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            if (kind == 1) name = name.trimEnd('/')
            var leading = 0
            while (name.startsWith("./", leading)) leading += 2
            if (leading > 0) name = name.substring(leading)
            val uid = number(effective["uid"], entry.longUserId)
            val gid = number(effective["gid"], entry.longGroupId)
            val size = number(effective["size"], rawSize)
            val mode = try {
                TarUtils.parseOctalOrBinary(header, 100, 8)
            } catch (e: RuntimeException) {
                throw IOException("Invalid tar mode", e)
            }
            if (uid !in 0..65_535L || gid !in 0..65_535L) invalid("Owner identifier out of range")
            // Preserve ordinary Unix setid permissions for package files/directories; links stay restricted.
            // The runtime may mount these nosuid: storing metadata is not permission to elevate via exec.
            val modeMask = when (kind) {
                1 -> 0xfffL // 07777: directories may also retain sticky.
                2 -> 0xdffL // 06777: regular files retain setuid/setgid, not sticky or inode type bits.
                else -> 0x1ffL // 0777
            }
            if (mode < 0 || mode and modeMask.inv() != 0L) invalid("Forbidden tar permission bits")
            if (size < 0 || kind != 2 && size != 0L) invalid("Invalid tar data length")
            if (kind == 1 && name == ".") {
                if (++roots > 1) invalid("Duplicate normalized root directory")
                continue
            }
            val path = relativePath(name, limits.pathBytes)
            if (name in seen) invalid("Duplicate normalized archive path")
            if (seen.size >= limits.entries) invalid("Entry count limit exceeded")
            if (names + path.size + 1 > limits.namesBytes) invalid("Total path name bytes limit exceeded")
            val data: ByteArray?
            val length: Long
            when (kind) {
                2 -> {
                    if (size > limits.fileBytes || files + size > limits.totalFileBytes) invalid("File data limit exceeded")
                    data = null
                    length = size
                }
                3, 4 -> {
                    val target = effective["linkpath"] ?: rawLink
                    data = text(target, limits.linkBytes, "link target")
                    if (kind == 4) {
                        relativePath(target, limits.pathBytes)
                        if (target !in regularFiles) invalid("Hardlink target is not a preceding regular file")
                    }
                    length = data.size.toLong()
                }
                else -> {
                    data = byteArrayOf()
                    length = 0
                }
            }
            record(output, kind, path.size, mode.toInt(), uid.toInt(), gid.toInt(), length)
            output.write(path)
            if (data == null) {
                var remaining = size
                while (remaining > 0) {
                    checkInterrupted()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (count <= 0) invalid("Truncated tar file data")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                padding(input, size, buffer)
                regularFiles.add(name)
                files += size
            } else {
                output.write(data)
            }
            seen.add(name)
            names += path.size + 1
        }
        verifyEnd()
        record(output, 255, 0, 0, 0, 0, 0)
        return Result(seen.size, headers, roots, files, names, metadata, written, "etc/resolv.conf" in seen)
    }

    private fun pax(data: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var offset = 0
        while (offset < data.size) {
            var end = offset
            var length = 0L
            while (end < data.size && data[end].toInt() in 48..57) {
                length = length * 10 + data[end].toInt() - 48
                if (length > data.size) invalid("Invalid PAX record length")
                end++
            }
            if (end == offset || end >= data.size || data[end] != 32.toByte() || length <= end - offset + 2 ||
                length > data.size - offset
            ) invalid("Invalid PAX record length")
            val stop = offset + length.toInt()
            if (data[stop - 1] != 10.toByte()) invalid("Invalid PAX record terminator")
            val record = utf8(data.copyOfRange(end + 1, stop - 1))
            val separator = record.indexOf('=')
            if (separator <= 0 || '\u0000' in record) invalid("Invalid PAX key or value")
            val key = record.substring(0, separator)
            val value = record.substring(separator + 1)
            if (key.startsWith("GNU.sparse.") || key == "SCHILY.realsize" ||
                key == "SCHILY.filetype" && value == "sparse"
            ) invalid("Sparse tar metadata is unsupported")
            if (key == "hdrcharset" && value !in setOf("UTF-8", "ISO-IR 10646 2000 UTF-8")) {
                invalid("Non-UTF-8 tar metadata is unsupported")
            }
            if (key in PAX_FIELDS) {
                if (key in result) invalid("Duplicate PAX field")
                result[key] = value
            }
            offset = stop
        }
        return result
    }

    private fun number(value: String?, fallback: Long): Long {
        if (value == null) return fallback
        if (value.isEmpty() || value.any { it !in '0'..'9' }) invalid("Invalid numeric PAX metadata")
        return value.toLongOrNull() ?: invalid("Numeric PAX metadata overflow")
    }

    private fun relativePath(value: String, limit: Int): ByteArray {
        val bytes = text(value, limit, "path")
        if (value.split('/').any { it.isEmpty() || it == "." || it == ".." || it.toByteArray(Charsets.UTF_8).size > 255 }) {
            invalid("Path is not a strict relative path or component exceeds 255 bytes")
        }
        return bytes
    }

    private fun text(value: String, limit: Int, label: String): ByteArray {
        if (value.isEmpty() || '\u0000' in value) invalid("Empty or NUL-containing $label")
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..limit) invalid("Invalid $label byte length")
        return bytes
    }

    private fun field(bytes: ByteArray, start: Int, length: Int): String {
        val end = start + length
        val firstNul = (start until end).firstOrNull { bytes[it] == 0.toByte() } ?: end
        if ((firstNul until end).any { bytes[it] != 0.toByte() }) invalid("Embedded NUL in tar path or link field")
        return utf8(bytes.copyOfRange(start, firstNul))
    }

    private fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw IOException("Invalid UTF-8 tar metadata", e)
    }

    private fun padding(input: InputStream, size: Long, buffer: ByteArray) {
        val count = (paddedSize(size) - size).toInt()
        readFully(input, buffer, count)
        if ((0 until count).any { buffer[it] != 0.toByte() }) invalid("Invalid tar data padding")
    }

    private fun readFully(input: InputStream, bytes: ByteArray, size: Int = bytes.size) {
        var offset = 0
        while (offset < size) {
            checkInterrupted()
            val count = input.read(bytes, offset, size - offset)
            if (count <= 0) invalid("Truncated tar archive")
            offset += count
        }
    }

    private fun record(output: DataOutputStream, kind: Int, pathSize: Int, mode: Int, uid: Int, gid: Int, size: Long) {
        output.writeByte(kind)
        output.writeByte(0)
        output.writeShort(pathSize)
        output.writeInt(mode)
        output.writeInt(uid)
        output.writeInt(gid)
        output.writeLong(size)
    }

    private fun paddedSize(size: Long): Long = (size + BLOCK_BYTES - 1) / BLOCK_BYTES * BLOCK_BYTES

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Archive writing was cancelled")
    }

    private fun invalid(message: String): Nothing = throw IOException(message)

    private companion object {
        const val BLOCK_BYTES = 512
        const val CHUNK_BYTES = 65_536
        const val MAX_METADATA_DEPTH = 16
        const val MAX_COMPRESSED_BYTES = 1024L * 1024 * 1024
        val SUPPORTED_TYPES = setOf(0, 48, 49, 50, 53, 75, 76, 103, 120)
        val METADATA_TYPES = setOf(75, 76, 103, 120)
        val PAX_FIELDS = setOf("path", "linkpath", "uid", "gid", "size")
    }
}
