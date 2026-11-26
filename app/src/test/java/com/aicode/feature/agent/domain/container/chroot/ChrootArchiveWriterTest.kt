package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.ContainerInstaller.CompressedFormat
import org.apache.commons.compress.archivers.tar.TarUtils
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class ChrootArchiveWriterTest {
    private data class Record(val kind: Int, val path: String, val mode: Int, val uid: Int, val gid: Int, val data: ByteArray)

    private fun encode(tar: ByteArray, limits: ChrootArchiveWriter.Limits = ChrootArchiveWriter.Limits(), format: CompressedFormat = CompressedFormat.GZIP): Pair<ByteArray, ChrootArchiveWriter.Result> {
        val output = ByteArrayOutputStream()
        val result = ChrootArchiveWriter(limits).write(ByteArrayInputStream(ChrootArchiveFixtures.compress(tar, format)), format, output)
        return output.toByteArray() to result
    }

    private fun decode(stream: ByteArray): List<Record> = DataInputStream(ByteArrayInputStream(stream)).use { input ->
        val magic = ByteArray(8).also(input::readFully)
        assertArrayEquals(byteArrayOf(65, 74, 73, 70, 0, 1, 0, 0), magic)
        val records = mutableListOf<Record>()
        while (true) {
            val kind = input.readUnsignedByte()
            assertEquals(0, input.readUnsignedByte())
            val size = input.readUnsignedShort()
            val mode = input.readInt()
            val uid = input.readInt()
            val gid = input.readInt()
            val dataSize = input.readLong()
            if (kind == 255) {
                assertEquals(listOf(0L, 0L, 0L, 0L, 0L), listOf(size.toLong(), mode.toLong(), uid.toLong(), gid.toLong(), dataSize))
                assertEquals(-1, input.read())
                return@use records
            }
            assertTrue(kind in 1..4 && size in 1..4096)
            assertTrue(dataSize in 0..128L * 1024 * 1024)
            val path = ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
            val data = ByteArray(dataSize.toInt()).also(input::readFully)
            records.add(Record(kind, path, mode, uid, gid, data))
        }
        @Suppress("UNREACHABLE_CODE")
        records
    }

    private fun reject(tar: ByteArray, limits: ChrootArchiveWriter.Limits = ChrootArchiveWriter.Limits()) {
        val output = ByteArrayOutputStream()
        try {
            ChrootArchiveWriter(limits).write(ByteArrayInputStream(ChrootArchiveFixtures.gzip(tar)), CompressedFormat.GZIP, output)
            throw AssertionError("Unsafe archive was accepted")
        } catch (_: IOException) {
        }
        val stream = output.toByteArray()
        assertFalse(stream.size >= 32 && stream.takeLast(24).toByteArray().contentEquals(byteArrayOf(255.toByte()) + ByteArray(23)))
    }

    @Test
    fun writesExactBigEndianAjifBytesAndLeavesCallerStreamsOpen() {
        val tar = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("x", data = byteArrayOf(0, 255.toByte()), mode = 416, uid = 65535, gid = 42))
        var inputClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(ChrootArchiveFixtures.gzip(tar)) {
            override fun close() { inputClosed = true; super.close() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        val result = ChrootArchiveWriter().write(input, CompressedFormat.GZIP, output)
        val expected = byteArrayOf(65, 74, 73, 70, 0, 1, 0, 0, 2, 0, 0, 1,
            0, 0, 1, 160.toByte(), 0, 0, 255.toByte(), 255.toByte(), 0, 0, 0, 42,
            0, 0, 0, 0, 0, 0, 0, 2, 120, 0, 255.toByte(), 255.toByte()) + ByteArray(23)
        assertArrayEquals(expected, output.toByteArray())
        assertEquals(expected.size.toLong(), result.streamBytes)
        assertEquals(1, result.rawHeaderCount)
        assertEquals(2L, result.fileBytes)
        assertFalse(inputClosed)
        assertFalse(outputClosed)
    }

    @Test
    fun preservesAllTypesOwnersStickyDirectoriesAndGuestOnlySymlinkTargets() {
        val tar = ChrootArchiveFixtures.archive(
            ChrootArchiveFixtures.entry(".", type = '5'),
            ChrootArchiveFixtures.entry("././dir/", type = '5', mode = 1023),
            ChrootArchiveFixtures.entry("./dir/file", data = "payload".toByteArray(), uid = 1, gid = 42),
            ChrootArchiveFixtures.entry("absolute", type = '2', target = "/guest/../target"),
            ChrootArchiveFixtures.entry("relative", type = '2', target = "../../outside"),
            ChrootArchiveFixtures.entry("hard", type = '1', target = "dir/file")
        )
        val (stream, result) = encode(tar)
        val records = decode(stream)
        assertEquals(listOf(1, 2, 3, 3, 4), records.map { it.kind })
        assertEquals(listOf("dir", "dir/file", "absolute", "relative", "hard"), records.map { it.path })
        assertEquals(1023, records[0].mode)
        assertEquals(1, records[1].uid)
        assertEquals(42, records[1].gid)
        assertEquals("/guest/../target", records[2].data.toString(Charsets.UTF_8))
        assertEquals("../../outside", records[3].data.toString(Charsets.UTF_8))
        assertEquals("dir/file", records[4].data.toString(Charsets.UTF_8))
        assertEquals(1, result.skippedRootDirectories)
        assertEquals(6, result.rawHeaderCount)
    }

    private fun assertRootGuestRegularFileMode(mode: Long, uid: Long, gid: Long) {
        // Synthetic bytes only; these permission bits are never applied to the test host.
        val data = "synthetic package executable\n".toByteArray(Charsets.UTF_8)
        val tar = ChrootArchiveFixtures.archive(
            ChrootArchiveFixtures.entry("usr/bin/package-helper", data = data, mode = mode, uid = uid, gid = gid)
        )
        for (format in listOf(CompressedFormat.GZIP, CompressedFormat.XZ)) {
            val (stream, result) = encode(tar, format = format)
            val record = decode(stream).single()
            assertEquals(2, record.kind)
            assertEquals("usr/bin/package-helper", record.path)
            assertEquals(mode.toInt(), record.mode)
            assertEquals(uid.toInt(), record.uid)
            assertEquals(gid.toInt(), record.gid)
            assertArrayEquals(data, record.data)
            assertEquals(1, result.entryCount)
            assertEquals(1, result.rawHeaderCount)
            assertEquals(data.size.toLong(), result.fileBytes)
            assertEquals(stream.size.toLong(), result.streamBytes)
        }
    }

    @Test
    fun defaultAllowRootGuestPreservesSetuidRegularFileWithoutMasking() {
        assertRootGuestRegularFileMode(mode = 0x9ed, uid = 0, gid = 0) // 04755
    }

    @Test
    fun defaultAllowRootGuestPreservesSetgidRegularFileAndOwnerWithoutMasking() {
        assertRootGuestRegularFileMode(mode = 0x5ed, uid = 0, gid = 42) // 02755
    }

    @Test
    fun defaultAllowRootGuestPreservesCombinedSetidRegularFileWithoutMasking() {
        assertRootGuestRegularFileMode(mode = 0xde8, uid = 0, gid = 42) // 06750
    }

    @Test
    fun defaultAllowRootGuestPreservesSetgidStickyDirectoryWithoutMasking() {
        val tar = ChrootArchiveFixtures.archive(
            ChrootArchiveFixtures.entry("var/cache/package-shared", type = '5', mode = 0x7fd, uid = 0, gid = 42) // 03775
        )
        val (stream, result) = encode(tar)
        val record = decode(stream).single()
        assertEquals(1, record.kind)
        assertEquals("var/cache/package-shared", record.path)
        assertEquals(0x7fd, record.mode)
        assertEquals(0, record.uid)
        assertEquals(42, record.gid)
        assertArrayEquals(byteArrayOf(), record.data)
        assertEquals(1, result.entryCount)
        assertEquals(0L, result.fileBytes)
    }

    @Test
    fun rejectsNonCanonicalAndDuplicatePathsIncludingDuplicateRoot() {
        for (path in listOf("/absolute", "../escape", "a/../b", "./../escape", "", ".", "a/./b", "a//b", "./")) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry(path)))
        }
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("x"), ChrootArchiveFixtures.entry("./x")))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry(".", type = '5'), ChrootArchiveFixtures.entry("./.", type = '5')))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("safe\u0000hidden")))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("link", type = '2', target = "safe\u0000hidden")))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("path" to "safe\u0000hidden"), ChrootArchiveFixtures.entry("fallback")))
    }

    @Test
    fun rejectsSpecialSparseAndNonFilePayloadBeforeWritingRecords() {
        for (type in listOf('3', '4', '6', 'S', '7', 's', 'Z')) {
            val output = ByteArrayOutputStream()
            try {
                ChrootArchiveWriter().write(ByteArrayInputStream(ChrootArchiveFixtures.gzip(ChrootArchiveFixtures.archive(
                    ChrootArchiveFixtures.entry("special", type = type, declaredSize = Long.MAX_VALUE)
                ))), CompressedFormat.GZIP, output)
                throw AssertionError("Unsupported type was accepted")
            } catch (_: IOException) {
                assertEquals(8, output.size())
            }
        }
        for (key in listOf("GNU.sparse.map", "GNU.sparse.size", "GNU.sparse.major", "SCHILY.realsize")) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax(key to "999999999999999999999999999999999999"), ChrootArchiveFixtures.entry("sparse")))
        }
        for (type in listOf('5', '2', '1')) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("entry", type = type, target = "target", declaredSize = 1)))
        }
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("size" to "1"), ChrootArchiveFixtures.entry(".", type = '5')))
    }

    @Test
    fun rejectsForbiddenModesAndOutOfRangeOwnersWithoutMasking() {
        for (type in listOf('5', '0', '2', '1')) {
            for (mode in listOf(4096L, -1L)) {
                reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("entry", type = type, target = "target", mode = mode)))
            }
        }
        // Regular files/directories preserve ordinary setid bits above; link headers still cannot request them.
        for (type in listOf('2', '1')) {
            for (mode in listOf(2541L, 1517L)) {
                reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("entry", type = type, target = "target", mode = mode)))
            }
        }
        for (type in listOf('0', '2', '1')) reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("entry", type = type, target = "target", mode = 1005)))
        for (value in listOf(-1L, 65536L)) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("entry", uid = value)))
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("entry", gid = value)))
        }
        for ((key, value) in listOf("uid" to "broken", "gid" to "-1", "uid" to "65536", "size" to "-1")) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax(key to value), ChrootArchiveFixtures.entry("entry")))
        }
    }

    @Test
    fun hardlinksRequireExactPrecedingRegularFileNotLinksOrDirectories() {
        for (target in listOf("future", "/file", "../file", "dir/../file", "./file", "file/", "", "file\u0000hidden")) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file"), ChrootArchiveFixtures.entry("link", type = '1', target = target)))
        }
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("link", type = '1', target = "file"), ChrootArchiveFixtures.entry("file")))
        for (type in listOf('2', '5', '1')) {
            reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file"), ChrootArchiveFixtures.entry("other", type = type, target = "file"),
                ChrootArchiveFixtures.entry("link", type = '1', target = "other")))
        }
    }

    @Test
    fun boundedPaxAndGnuLongNamesProduceSameStreamAndCountTheirRawHeaders() {
        val name = "dir/" + "x".repeat(150)
        val gnu = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("././@LongLink", type = 'L', data = (name + '\u0000').toByteArray()),
            ChrootArchiveFixtures.entry("fallback", data = "ok".toByteArray()))
        val pax = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("path" to name, "uid" to "65535", "gid" to "42"),
            ChrootArchiveFixtures.entry("fallback", data = "ok".toByteArray(), uid = 65535, gid = 42))
        val gnuRecords = decode(encode(gnu).first)
        val (paxStream, result) = encode(pax)
        assertEquals(name, gnuRecords.single().path)
        assertEquals(name, decode(paxStream).single().path)
        assertEquals(65535, decode(paxStream).single().uid)
        assertEquals(42, decode(paxStream).single().gid)
        assertEquals(2, result.rawHeaderCount)
        assertEquals(512L, result.metadataBytes)
        reject(pax, ChrootArchiveWriter.Limits(archiveHeaders = 1))
        reject(pax, ChrootArchiveWriter.Limits(metadataBytes = 511))
        val rootsAndPax = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry(".", type = '5'), ChrootArchiveFixtures.pax("comment" to "x"), ChrootArchiveFixtures.entry("file"))
        assertEquals(3, encode(rootsAndPax).second.rawHeaderCount)
        reject(rootsAndPax, ChrootArchiveWriter.Limits(archiveHeaders = 2))
        val prefix = ChrootArchiveFixtures.entry("././@LongLink", type = 'L', data = "name\u0000".toByteArray())
        encode(ChrootArchiveFixtures.archive(*(List(16) { prefix } + ChrootArchiveFixtures.entry("fallback")).toTypedArray()))
        reject(ChrootArchiveFixtures.archive(*(List(17) { prefix } + ChrootArchiveFixtures.entry("fallback")).toTypedArray()))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("pax", type = 'x', declaredSize = 65537)))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("long", type = 'L', data = "safe\u0000hidden\u0000".toByteArray()), ChrootArchiveFixtures.entry("fallback")))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("long", type = 'L', data = "no-terminator".toByteArray()), ChrootArchiveFixtures.entry("fallback")))
    }

    @Test
    fun enforcesUtf8ByteComponentPathTargetNamesAndFileBudgets() {
        val invalid = ChrootArchiveFixtures.entry("fallback").copyOf().also {
            it[0] = 255.toByte()
            ChrootArchiveFixtures.checksum(it)
        }
        reject(ChrootArchiveFixtures.archive(invalid))
        val unicode = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("目录/文件", data = "ok".toByteArray()))
        assertEquals("目录/文件", decode(encode(unicode).first).single().path)
        reject(unicode, ChrootArchiveWriter.Limits(pathBytes = 12))
        val component255 = "文".repeat(85)
        encode(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("path" to component255), ChrootArchiveFixtures.entry("fallback")))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("path" to component255 + "x"), ChrootArchiveFixtures.entry("fallback")))
        val path4096 = (List(15) { "x".repeat(255) } + "y".repeat(254) + "z").joinToString("/")
        assertEquals(4096, path4096.toByteArray().size)
        encode(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("path" to path4096), ChrootArchiveFixtures.entry("fallback")))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("path" to path4096 + "z"), ChrootArchiveFixtures.entry("fallback")))
        encode(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("linkpath" to "x".repeat(4096)), ChrootArchiveFixtures.entry("link", type = '2')))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("linkpath" to "x".repeat(4097)), ChrootArchiveFixtures.entry("link", type = '2')))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("link", type = '2', target = "文")), ChrootArchiveWriter.Limits(linkBytes = 2))
        val names = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("./a"), ChrootArchiveFixtures.entry("文"))
        assertEquals(6L, encode(names, ChrootArchiveWriter.Limits(namesBytes = 6)).second.namesBytes)
        reject(names, ChrootArchiveWriter.Limits(namesBytes = 5))
        val files = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("a", data = "123".toByteArray()), ChrootArchiveFixtures.entry("b", data = "45".toByteArray()))
        val limits = ChrootArchiveWriter.Limits(entries = 2, fileBytes = 3, totalFileBytes = 5)
        assertEquals(5L, encode(files, limits).second.fileBytes)
        reject(files, limits.copy(entries = 1))
        reject(files, limits.copy(fileBytes = 2))
        reject(files, limits.copy(totalFileBytes = 4))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("a", declaredSize = 128L * 1024 * 1024 + 1)))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.pax("uid" to "999999999999999999999999999999999"), ChrootArchiveFixtures.entry("a")))
    }

    @Test
    fun rejectsTruncatedDataBadChecksumCompressionAndEndMarkers() {
        reject("not a tar archive".toByteArray())
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file", declaredSize = 4096)))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file", declaredSize = -1)))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file").also { it[148] = 'x'.code.toByte() }))
        reject(ChrootArchiveFixtures.entry("file"))
        reject(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file")) + byteArrayOf(1))
        val compressed = ChrootArchiveFixtures.gzip(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file")))
        try {
            ChrootArchiveWriter().write(ByteArrayInputStream(compressed.copyOf(compressed.size - 3)), CompressedFormat.GZIP, ByteArrayOutputStream())
            throw AssertionError("Truncated gzip trailer was accepted")
        } catch (_: IOException) {
        }
        val tar = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file", data = byteArrayOf(1, 2, 3)))
        assertArrayEquals(encode(tar).first, encode(tar, format = CompressedFormat.XZ).first)
    }

    @Test
    fun gzipOptionalMetadataIsBoundedAndExtraMembersCannotEndSuccessfully() {
        val tar = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file"))
        val compressed = ChrootArchiveFixtures.gzip(tar)
        val header = compressed.copyOfRange(0, 10).also { it[3] = 8 }
        val named = header + "archive.tar\u0000".toByteArray() + compressed.copyOfRange(10, compressed.size)
        val output = ByteArrayOutputStream()
        ChrootArchiveWriter().write(ByteArrayInputStream(named), CompressedFormat.GZIP, output)
        assertArrayEquals(encode(tar).first, output.toByteArray())
        val oversized = header + ByteArray(65536) { 65 }
        for (input in listOf(oversized, compressed + compressed, compressed + byteArrayOf(1))) {
            val partial = ByteArrayOutputStream()
            try {
                ChrootArchiveWriter().write(ByteArrayInputStream(input), CompressedFormat.GZIP, partial)
                throw AssertionError("Unbounded or trailing gzip input was accepted")
            } catch (_: IOException) {
            }
            assertFalse(partial.size() >= 32 && partial.toByteArray().takeLast(24).toByteArray().contentEquals(byteArrayOf(255.toByte()) + ByteArray(23)))
        }
        val resolver = encode(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("etc/resolv.conf"))).second
        assertTrue(resolver.containsResolvConf)
        assertEquals(1, resolver.nativeEntryCount)
    }

    @Test
    fun realArmAssetMatchesPythonGoldenStreamByteForByteDigest() {
        val asset = listOf(File("src/_armAssets/container/arm/alpine-rootfs.bin"), File("app/src/_armAssets/container/arm/alpine-rootfs.bin")).first { it.isFile }
        val output = ByteArrayOutputStream()
        val result = asset.inputStream().use { ChrootArchiveWriter().write(it, CompressedFormat.GZIP, output) }
        val bytes = output.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("c91777d29b90113e55c60a989317e03103433521f1a7281619f5ca92af3226a0", sha)
        assertEquals(8_155_613L, result.streamBytes)
        assertEquals(521, result.rawHeaderCount)
        assertEquals(520, result.entryCount)
        assertEquals(1, result.skippedRootDirectories)
        assertEquals(9964L, result.namesBytes)
        assertEquals(0L, result.metadataBytes)
        assertFalse(result.containsResolvConf)
        assertEquals(521, result.nativeEntryCount)
        val records = decode(bytes)
        assertEquals(mapOf(1 to 97, 2 to 88, 3 to 335), records.groupingBy { it.kind }.eachCount())
        assertEquals(result.fileBytes, records.filter { it.kind == 2 }.sumOf { it.data.size.toLong() })
    }
}

internal object ChrootArchiveFixtures {
    fun entry(name: String, type: Char = '0', data: ByteArray = byteArrayOf(), target: String = "", mode: Long = 493, uid: Long = 0, gid: Long = 0, declaredSize: Long = data.size.toLong()): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 100)
        nameBytes.copyInto(header)
        TarUtils.formatLongOctalOrBinaryBytes(mode, header, 100, 8)
        TarUtils.formatLongOctalOrBinaryBytes(uid, header, 108, 8)
        TarUtils.formatLongOctalOrBinaryBytes(gid, header, 116, 8)
        TarUtils.formatLongOctalOrBinaryBytes(declaredSize, header, 124, 12)
        TarUtils.formatLongOctalOrBinaryBytes(0, header, 136, 12)
        header[156] = type.code.toByte()
        target.toByteArray(Charsets.UTF_8).also { require(it.size <= 100) }.copyInto(header, 157)
        byteArrayOf(117, 115, 116, 97, 114, 32, 32, 0).copyInto(header, 257)
        checksum(header)
        return header + data + ByteArray((512 - data.size % 512) % 512)
    }

    fun checksum(header: ByteArray) {
        (148 until 156).forEach { header[it] = 32 }
        TarUtils.formatCheckSumOctalBytes(header.take(512).sumOf { it.toLong() and 255 }, header, 148, 8)
    }

    fun pax(vararg fields: Pair<String, String>): ByteArray {
        val data = fields.joinToString("") { (key, value) ->
            val body = " $key=$value\n"
            var size = body.toByteArray(Charsets.UTF_8).size + 1
            while (size != body.toByteArray(Charsets.UTF_8).size + size.toString().length) size = body.toByteArray(Charsets.UTF_8).size + size.toString().length
            "$size$body"
        }.toByteArray(Charsets.UTF_8)
        return entry("PaxHeaders/entry", type = 'x', data = data)
    }

    fun archive(vararg entries: ByteArray): ByteArray = entries.fold(ByteArrayOutputStream()) { output, bytes ->
        output.apply { write(bytes) }
    }.apply { write(ByteArray(1024)) }.toByteArray()

    fun gzip(tar: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        GZIPOutputStream(output).use { it.write(tar) }
    }.toByteArray()

    fun compress(tar: ByteArray, format: CompressedFormat): ByteArray = when (format) {
        CompressedFormat.GZIP -> gzip(tar)
        CompressedFormat.XZ -> ByteArrayOutputStream().also { output -> XZCompressorOutputStream(output).use { it.write(tar) } }.toByteArray()
    }
}
