package com.aicode.feature.backup.data

import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class BackupContentArchiveTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun rawMemoryBytesRoundtripKeepsFrontmatterNonAsciiAndLineEndings() {
        val source = temporary.newFolder("memory-source")
        val raw = "---\r\nname: 中文记忆\r\ndescription: café\r\n---\n\n正文：不改换行。\r\n尾行\n\n".toByteArray(Charsets.UTF_8)
        File(source, "project.md").writeBytes(raw)
        val section = prepared("memory", source)
        val batch = batch(listOf(section.metadata), setOf("memory"))

        consume(export(section), batch)
        BackupContentArchive.finishImport(batch)

        assertEquals(1, section.metadata.fileCount)
        assertEquals(raw.size.toLong(), section.metadata.bytes)
        assertArrayEquals(raw, File(batch.directories.getValue("memory"), "project.md").readBytes())
        assertEquals(setOf("project.md"), batch.files.getValue("memory").keys)
    }

    @Test
    fun skillsRoundtripIncludesCompanionsEmptyDirectoriesAndOwnerExecuteBit() {
        val source = temporary.newFolder("skills-source")
        assertTrue(File(source, "definitions/sample/scripts").mkdirs())
        assertTrue(File(source, "definitions/sample/references/empty").mkdirs())
        File(source, "definitions/sample/SKILL.md").writeText("---\nname: sample\n---\n技能正文\n")
        val script = File(source, "definitions/sample/scripts/run.sh").apply { writeText("#!/bin/sh\nprintf fixture\n") }
        Files.setPosixFilePermissions(script.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val companion = byteArrayOf(0, 1, 2, -1)
        File(source, "definitions/sample/references/data.bin").writeBytes(companion)
        File(source, "skills.json").writeText("{\"disabled\":[\"other\"]}\n")
        val section = prepared("skills", source, BackupContentKind.SKILLS)
        val batch = batch(listOf(section.metadata), setOf("skills"))

        consume(export(section), batch)
        BackupContentArchive.finishImport(batch)

        val restored = batch.directories.getValue("skills")
        assertEquals(4, section.metadata.fileCount)
        assertArrayEquals(File(source, "definitions/sample/SKILL.md").readBytes(), File(restored, "definitions/sample/SKILL.md").readBytes())
        assertArrayEquals(companion, File(restored, "definitions/sample/references/data.bin").readBytes())
        assertArrayEquals(File(source, "skills.json").readBytes(), File(restored, "skills.json").readBytes())
        assertTrue(File(restored, "definitions/sample/references/empty").isDirectory)
        assertTrue(File(restored, "definitions/sample/references/empty").list()!!.isEmpty())
        val permissions = Files.getPosixFilePermissions(File(restored, "definitions/sample/scripts/run.sh").toPath())
        assertTrue(OWNER_EXECUTE in permissions)
        assertFalse(GROUP_EXECUTE in permissions)
        assertFalse(OTHERS_EXECUTE in permissions)
    }

    @Test
    fun selectedAndUnselectedSectionsUseIsolatedStaging() {
        val selected = prepared("selected", temporary.newFolder("selected-source").apply { resolve("same.md").writeText("selected") })
        val ignored = prepared("ignored", temporary.newFolder("ignored-source").apply { resolve("same.md").writeText("ignored") })
        val batch = batch(listOf(selected.metadata, ignored.metadata), setOf("selected"))

        consume(export(selected, ignored), batch)
        BackupContentArchive.finishImport(batch)

        assertEquals(setOf("selected"), batch.directories.keys)
        assertEquals("selected", batch.directories.getValue("selected").resolve("same.md").readText())
        assertFalse(File(batch.root, "ignored").exists())
        assertEquals(listOf("selected"), batch.root.list()!!.toList())
        assertEquals(setOf("selected", "ignored"), batch.files.keys)
        assertEquals(digest("ignored".toByteArray()), batch.files.getValue("ignored").getValue("same.md").second)
    }

    @Test
    fun unselectedSectionTamperingStillFailsDigestWithoutWritingPayload() {
        val expected = FileSpec("memory.md", "abc".toByteArray())
        val batch = batch(listOf(meta("memory", 1, 3)))
        consume(archive(manifestEntry("memory", expected), fileEntry("memory", expected.copy(payload = "xyz".toByteArray()))), batch)

        rejected { BackupContentArchive.finishImport(batch) }

        assertTrue(batch.directories.isEmpty())
        assertTrue(batch.root.list()!!.isEmpty())
        assertEquals(digest("xyz".toByteArray()), batch.files.getValue("memory").getValue("memory.md").second)
    }

    @Test
    fun manifestDigestMismatchIsRejectedForSelectedPayload() {
        val file = FileSpec("memory.md", "abc".toByteArray(), sha256 = "0".repeat(64))
        val batch = batch(listOf(meta("memory", 1, 3)), setOf("memory"))
        consume(archive(manifestEntry("memory", file), fileEntry("memory", file)), batch)

        rejected { BackupContentArchive.finishImport(batch) }

        assertArrayEquals(file.payload, batch.directories.getValue("memory").resolve(file.path).readBytes())
    }

    @Test
    fun manifestSizeMismatchIsRejectedEvenWhenDigestMatches() {
        val file = FileSpec("memory.md", "abc".toByteArray(), bytes = 2)
        val batch = batch(listOf(meta("memory", 1, 3)))
        consume(archive(manifestEntry("memory", file), fileEntry("memory", file)), batch)

        rejected { BackupContentArchive.finishImport(batch) }

        assertEquals(3L, batch.files.getValue("memory").getValue("memory.md").first)
    }

    @Test
    fun metadataFileAndByteCountersMustMatchTheValidatedManifest() {
        val file = FileSpec("memory.md", "abc".toByteArray())
        val bytes = archive(manifestEntry("memory", file), fileEntry("memory", file))
        for (metadata in listOf(meta("memory", 0, 3), meta("memory", 1, 2), meta("memory", 2, 3))) {
            val batch = batch(listOf(metadata))
            consume(bytes, batch)
            rejected { BackupContentArchive.finishImport(batch) }
        }
    }

    @Test
    fun everyDeclaredSectionRequiresItsManifestIncludingUnselectedEmptySections() {
        val metadata = listOf(meta("present"), meta("missing"))
        val batch = batch(metadata, setOf("present"))
        consume(archive(manifestEntry("present")), batch)

        val failure = rejected { BackupContentArchive.finishImport(batch) }

        assertTrue(failure is IOException)
        assertEquals("Missing content manifest", failure.message)
        assertFalse(File(batch.root, "missing").exists())
    }

    @Test
    fun contentFilesAndManifestsMustBelongToDeclaredSections() {
        for (entry in listOf(manifestEntry("undeclared"), fileEntry("undeclared", FileSpec("note.md", byteArrayOf(1))))) {
            val batch = batch(listOf(meta("declared")))
            rejected { consume(archive(entry), batch) }
            assertTrue(batch.root.list()!!.isEmpty())
            assertTrue(batch.files.isEmpty())
            assertTrue(batch.manifestBytes.isEmpty())
        }
    }

    @Test
    fun invalidRelativePathsNeverWritePayload() {
        val outside = File(temporary.root, "escape.md")
        for (relative in listOf("../escape.md", "a/../../escape.md", "/absolute.md", "a\\escape.md", "a//escape.md", "a/./escape.md")) {
            val batch = batch(listOf(meta("memory", 1, 1)), setOf("memory"))
            val entry = regular("content/memory/files/$relative", byteArrayOf(1))
            rejected { consume(archive(entry), batch) }
            assertTrue(relative, batch.directories.getValue("memory").list()!!.isEmpty())
            assertFalse(outside.exists())
            assertTrue(batch.files.isEmpty())
        }
    }

    @Test
    fun duplicateFilesAndManifestsAreRejectedInsteadOfOverwritten() {
        for (entry in listOf(manifestEntry("memory"), fileEntry("memory", FileSpec("note.md", "abc".toByteArray())))) {
            val batch = batch(listOf(meta("memory", 1, 3)), setOf("memory"))
            rejected { consume(archive(entry, entry), batch) }
            if (entry.first.name.endsWith("note.md")) {
                assertEquals("abc", batch.directories.getValue("memory").resolve("note.md").readText())
                assertEquals(1, batch.files.getValue("memory").size)
            } else {
                assertEquals(setOf("memory"), batch.manifestBytes.keys)
            }
        }
    }

    @Test
    fun duplicateMetadataIdsAndUndeclaredSelectionAreRejectedBeforeCreatingStages() {
        val root = temporary.newFolder("invalid-metadata-stage")
        rejected { BackupContentArchive.prepareImport(listOf(meta("same"), meta("same")), BackupContentRestoreSelection(), root) }
        assertTrue(root.list()!!.isEmpty())
        rejected { BackupContentArchive.prepareImport(listOf(meta("known")), BackupContentRestoreSelection(setOf("unknown")), root) }
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun symbolicHardAndSpecialTarEntriesAreRejectedWithoutWritingTargets() {
        for (type in listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK, TarConstants.LF_FIFO, TarConstants.LF_CHR, TarConstants.LF_BLK)) {
            val batch = batch(listOf(meta("memory")), setOf("memory"))
            val entry = TarArchiveEntry("content/memory/files/unsafe", type).apply { linkName = "../../outside" }
            rejectHeaderWithoutPayload(entry, batch)
            assertTrue(batch.directories.getValue("memory").list()!!.isEmpty())
            assertTrue(batch.files.isEmpty())
        }
    }

    @Test
    fun selectedStagingCannotFollowASymlinkParentOrLeaf() {
        val outside = temporary.newFolder("outside")
        val existing = File(outside, "existing.md").apply { writeText("keep") }
        for (leaf in listOf(false, true)) {
            val batch = batch(listOf(meta("memory", 1, 3)), setOf("memory"))
            val root = batch.directories.getValue("memory")
            val relative = if (leaf) "alias" else "alias/escape.md"
            Files.createSymbolicLink(File(root, "alias").toPath(), if (leaf) existing.toPath() else outside.toPath())
            val input = archive(fileEntry("memory", FileSpec(relative, "abc".toByteArray())))

            rejected { consume(input, batch) }

            assertEquals("keep", existing.readText())
            assertEquals(listOf("existing.md"), outside.list()!!.toList())
            assertTrue(batch.files.isEmpty())
        }
    }

    @Test
    fun exportCannotTreatASymlinkAsPayload() {
        val source = temporary.newFolder("linked-source")
        val outside = temporary.newFile("outside.md").apply { writeText("private") }
        Files.createSymbolicLink(File(source, "alias.md").toPath(), outside.toPath())

        rejected { BackupContentArchive.metadata("memory", BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL, "Memory", source) }

        assertEquals("private", outside.readText())
    }

    @Test
    fun missingOrUnreadableSourceCannotBeReportedAsAnEmptySnapshot() {
        val missing = File(temporary.root, "missing-source")
        rejected { BackupContentArchive.metadata("memory", BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL, "Memory", missing) }
        val directory = temporary.newFolder("unreadable-source")
        val unreadable = object : File(directory.path) {
            override fun listFiles(): Array<File>? = null
        }

        val failure = rejected { BackupContentArchive.metadata("memory", BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL, "Memory", unreadable) }

        assertTrue(failure is IOException)
        assertEquals("Cannot list backup content", failure.message)
    }

    @Test
    fun truncatedPayloadIsRejectedInsteadOfAcceptedAsAnEmptyFile() {
        val entry = TarArchiveEntry("content/memory/files/note.md").apply { size = 3 }
        val truncated = header(entry) + byteArrayOf(1, 2)
        val batch = batch(listOf(meta("memory", 1, 3)))

        rejected {
            TarArchiveInputStream(ByteArrayInputStream(truncated)).use { tar ->
                BackupContentArchive.readImport(tar, checkNotNull(tar.nextEntry), batch)
            }
        }

        assertTrue(batch.files.isEmpty())
    }

    @Test
    fun oversizedFileAndManifestHeadersAreRejectedBeforeReadingAnyPayload() {
        val batch = batch(listOf(meta("memory")))
        rejectHeaderWithoutPayload(TarArchiveEntry("content/memory/files/large.bin").apply { size = BackupContentArchive.MAX_FILE_BYTES + 1 }, batch)
        rejectHeaderWithoutPayload(TarArchiveEntry("content/memory/manifest.json").apply { size = 4L * 1024 * 1024 + 1 }, batch)
        assertTrue(batch.files.isEmpty())
        assertTrue(batch.manifestBytes.isEmpty())
        assertTrue(batch.root.list()!!.isEmpty())
    }

    @Test
    fun maximumFileHeaderReachesStreamingReadAndReadFailurePropagates() {
        val batch = batch(listOf(meta("memory", 1, BackupContentArchive.MAX_FILE_BYTES)))
        val entry = TarArchiveEntry("content/memory/files/maximum.bin").apply { size = BackupContentArchive.MAX_FILE_BYTES }
        val input = HeaderOnlyInput(entry, IOException("fixture payload failure"))
        TarArchiveInputStream(input).use { tar ->
            val parsed = checkNotNull(tar.nextEntry)
            val failure = rejected { BackupContentArchive.readImport(tar, parsed, batch) }
            assertTrue(failure is IOException)
            assertEquals("fixture payload failure", failure.message)
        }
        assertTrue(input.payloadReads > 0)
        assertEquals(BackupContentArchive.MAX_FILE_BYTES, batch.bytes)
        assertTrue(batch.files.isEmpty())
    }

    @Test
    fun cumulativeManifestBudgetRejectsTheFifthMaximumHeaderBeforePayload() {
        val metadata = (1..5).map { meta("section-$it") }
        val batch = batch(metadata)
        val manifestBytes = ByteArray(4 * 1024 * 1024) { ' '.code.toByte() }
        val empty = manifest().toByteArray(Charsets.UTF_8)
        empty.copyInto(manifestBytes)
        for (section in metadata.take(4)) {
            val entry = TarArchiveEntry("content/${section.id}/manifest.json").apply { size = manifestBytes.size.toLong() }
            TarArchiveInputStream(SequenceInputStream(ByteArrayInputStream(header(entry)), ByteArrayInputStream(manifestBytes))).use { tar ->
                BackupContentArchive.readImport(tar, checkNotNull(tar.nextEntry), batch)
            }
        }
        assertEquals(16L * 1024 * 1024, batch.manifestBytes.values.sumOf { it.size.toLong() })
        rejectHeaderWithoutPayload(TarArchiveEntry("content/section-5/manifest.json").apply { size = manifestBytes.size.toLong() }, batch)
        assertEquals(4, batch.manifestBytes.size)
        assertTrue(batch.directories.isEmpty())
    }

    @Test
    fun totalStreamByteAndEntryBudgetsRejectHeadersBeforePayload() {
        val bytesBatch = batch(listOf(meta("memory")))
        bytesBatch.bytes = BackupContentArchive.MAX_TOTAL_BYTES
        rejectHeaderWithoutPayload(TarArchiveEntry("content/memory/files/extra.bin").apply { size = 1 }, bytesBatch)
        assertEquals(BackupContentArchive.MAX_TOTAL_BYTES, bytesBatch.bytes)
        val entriesBatch = batch(listOf(meta("memory")))
        entriesBatch.entries = BackupContentArchive.MAX_ENTRIES * 4
        rejectHeaderWithoutPayload(TarArchiveEntry("content/memory/files/extra.bin").apply { size = 1 }, entriesBatch)
        assertTrue(bytesBatch.files.isEmpty())
        assertTrue(entriesBatch.files.isEmpty())
    }

    @Test
    fun sectionStreamBudgetsRejectAnAdditionalFileWithoutLargeAllocations() {
        val bytesBatch = batch(listOf(meta("memory")))
        bytesBatch.files["memory"] = (1..4).associateTo(linkedMapOf()) {
            "prior-$it.bin" to (BackupContentArchive.MAX_FILE_BYTES to "a".repeat(64))
        }
        rejected { consume(archive(fileEntry("memory", FileSpec("extra.bin", byteArrayOf(1)))), bytesBatch) }
        val countBatch = batch(listOf(meta("memory")))
        countBatch.files["memory"] = (1..BackupContentArchive.MAX_ENTRIES).associateTo(linkedMapOf()) {
            "prior-$it.bin" to (0L to digest(byteArrayOf()))
        }
        rejected { consume(archive(fileEntry("memory", FileSpec("extra.bin", byteArrayOf()))), countBatch) }
        assertTrue(bytesBatch.directories.isEmpty())
        assertTrue(countBatch.directories.isEmpty())
    }

    @Test
    fun metadataBudgetsRejectNegativeOversizedAndExcessiveDeclarations() {
        BackupContentArchive.validateMetadata(listOf(meta("edge", BackupContentArchive.MAX_ENTRIES, BackupContentArchive.MAX_SECTION_BYTES)))
        BackupContentArchive.validateMetadata((1..4).map { meta("edge-$it", bytes = BackupContentArchive.MAX_SECTION_BYTES) })
        for (sections in listOf(
            listOf(meta("negative-files", -1)),
            listOf(meta("negative-bytes", bytes = -1)),
            listOf(meta("files", BackupContentArchive.MAX_ENTRIES + 1)),
            listOf(meta("section-bytes", bytes = BackupContentArchive.MAX_SECTION_BYTES + 1)),
            (1..5).map { meta("total-bytes-$it", bytes = BackupContentArchive.MAX_SECTION_BYTES) },
            (1..5).map { meta("total-files-$it", BackupContentArchive.MAX_ENTRIES) },
            (1..513).map { meta("sections-$it") }
        )) {
            rejected { BackupContentArchive.validateMetadata(sections) }
        }
    }

    @Test
    fun manifestDeclarationsRejectDuplicateUndeclaredAndInvalidPaths() {
        val file = FileSpec("note.md", byteArrayOf())
        val invalidManifests = listOf(
            manifest(file, file),
            manifest(directories = listOf("empty", "empty")),
            manifest(file, directories = listOf("note.md")),
            manifest(FileSpec("../escape", byteArrayOf())),
            manifest(directories = listOf("/absolute")),
            manifest(file.copy(sha256 = "not-a-digest")),
            manifest(file.copy(bytes = -1)),
            manifest(file.copy(bytes = BackupContentArchive.MAX_FILE_BYTES + 1)),
            "{\"directories\":[],\"files\":[],\"unexpected\":true}"
        )
        for (text in invalidManifests) {
            val batch = batch(listOf(meta("memory")))
            consume(archive(regular("content/memory/manifest.json", text.toByteArray())), batch)
            rejected { BackupContentArchive.finishImport(batch) }
            assertTrue(batch.root.list()!!.isEmpty())
        }
        val batch = batch(listOf(meta("memory")))
        consume(archive(manifestEntry("memory"), fileEntry("memory", file)), batch)
        rejected { BackupContentArchive.finishImport(batch) }
    }

    @Test
    fun directoriesMustMatchTheManifestIncludingEmptyDirectories() {
        for (includeHeader in listOf(false, true)) {
            val batch = batch(listOf(meta("memory")), setOf("memory"))
            val entry = regular("content/memory/manifest.json", manifest(directories = if (includeHeader) emptyList() else listOf("empty")).toByteArray())
            val entries = if (includeHeader) arrayOf(entry, TarArchiveEntry("content/memory/files/empty/") to byteArrayOf()) else arrayOf(entry)
            consume(archive(*entries), batch)
            rejected { BackupContentArchive.finishImport(batch) }
        }
    }

    @Test
    fun tarExecuteBitsCannotOverrideANonExecutableManifest() {
        val file = FileSpec("script.sh", "printf safe\n".toByteArray())
        val batch = batch(listOf(meta("memory", 1, file.bytes)), setOf("memory"))
        val entry = fileEntry("memory", file).also { it.first.mode = 511 }
        consume(archive(manifestEntry("memory", file), entry), batch)
        BackupContentArchive.finishImport(batch)

        val permissions = Files.getPosixFilePermissions(batch.directories.getValue("memory").resolve(file.path).toPath())
        assertFalse(OWNER_EXECUTE in permissions)
        assertFalse(GROUP_EXECUTE in permissions)
        assertFalse(OTHERS_EXECUTE in permissions)
    }

    @Test
    fun relativePathLimitsAllowFixedWrappersWithoutWeakeningTraversalChecks() {
        val userPath = (List(63) { "a".repeat(63) } + "b".repeat(64)).joinToString("/")
        assertEquals(4096, userPath.length)
        assertTrue(BackupContentArchive.validRelative(userPath))
        assertTrue(BackupContentArchive.validRelative("definitions/$userPath"))
        assertTrue(BackupContentArchive.validRelative("data/$userPath"))
        assertFalse(BackupContentArchive.validRelative("definitions/${userPath}x"))
        assertFalse(BackupContentArchive.validRelative("definitions/$userPath/extra"))
        assertFalse(BackupContentArchive.validRelative("a".repeat(256)))
        for (path in listOf("", ".", "..", "a/../b", "/a", "a\\b", "a\u0000b", "a\nb")) {
            assertFalse(path, BackupContentArchive.validRelative(path))
        }
    }

    @Test
    fun exportRejectsCountersAndContentThatChangedSincePreparation() {
        val source = temporary.newFolder("changing-source")
        source.resolve("memory.md").writeText("abc")
        val section = prepared("memory", source)
        source.resolve("memory.md").appendText("d")
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            rejected { BackupContentArchive.writeExport(tar, section) }
        }
        assertEquals(0, TarArchiveInputStream(ByteArrayInputStream(output.toByteArray())).use { tar -> if (tar.nextEntry == null) 0 else 1 })
    }

    @Test
    fun exportPropagatesCancellationFromTheCurrentSourceGuard() {
        val source = temporary.newFolder("cancelled-source")
        source.resolve("memory.md").writeText("abc")
        val original = prepared("memory", source)
        val section = PreparedBackupContent(original.metadata, source) { throw CancellationException("source changed") }
        TarArchiveOutputStream(ByteArrayOutputStream()).use { tar ->
            try {
                BackupContentArchive.writeExport(tar, section)
                throw AssertionError("Cancellation must propagate")
            } catch (cancelled: CancellationException) {
                assertEquals("source changed", cancelled.message)
            }
        }
    }

    private data class FileSpec(
        val path: String,
        val payload: ByteArray,
        val bytes: Long = payload.size.toLong(),
        val sha256: String = digest(payload),
        val executable: Boolean = false
    )

    private fun meta(id: String, count: Int = 0, bytes: Long = 0) =
        BackupContentSectionMeta(id, BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL, id, count, bytes)

    private fun prepared(id: String, source: File, kind: BackupContentKind = BackupContentKind.MEMORY) = PreparedBackupContent(
        BackupContentArchive.metadata(id, kind, BackupContentLocation.PHONE_GLOBAL, id, source), source
    )

    private fun batch(metadata: List<BackupContentSectionMeta>, selected: Set<String> = emptySet()) = BackupContentArchive.prepareImport(
        metadata, BackupContentRestoreSelection(selected), temporary.newFolder()
    )

    private fun export(vararg sections: PreparedBackupContent): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            sections.forEach { BackupContentArchive.writeExport(tar, it) }
        }
        return output.toByteArray()
    }

    private fun archive(vararg entries: Pair<TarArchiveEntry, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            entries.forEach { (entry, payload) ->
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    private fun regular(name: String, bytes: ByteArray) = TarArchiveEntry(name).apply { size = bytes.size.toLong() } to bytes

    private fun fileEntry(id: String, file: FileSpec) = regular("content/$id/files/${file.path}", file.payload)

    private fun manifestEntry(id: String, vararg files: FileSpec) = regular("content/$id/manifest.json", manifest(*files).toByteArray())

    private fun manifest(vararg files: FileSpec, directories: List<String> = emptyList()): String = buildJsonObject {
        put("directories", buildJsonArray { directories.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("files", buildJsonArray {
            files.forEach { file ->
                add(buildJsonObject {
                    put("path", file.path)
                    put("bytes", file.bytes)
                    put("sha256", file.sha256)
                    put("executable", file.executable)
                })
            }
        })
    }.toString()

    private fun consume(bytes: ByteArray, batch: BackupContentArchive.ImportSections) {
        TarArchiveInputStream(ByteArrayInputStream(bytes)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                BackupContentArchive.readImport(tar, entry, batch)
                entry = tar.nextEntry
            }
        }
    }

    private fun rejectHeaderWithoutPayload(entry: TarArchiveEntry, batch: BackupContentArchive.ImportSections) {
        val input = HeaderOnlyInput(entry)
        TarArchiveInputStream(input).use { tar ->
            val parsed = checkNotNull(tar.nextEntry)
            assertEquals(entry.name, parsed.name)
            assertEquals(0, input.payloadReads)
            rejected { BackupContentArchive.readImport(tar, parsed, batch) }
            assertEquals(0, input.payloadReads)
        }
    }

    private fun rejected(block: () -> Unit): Exception {
        try {
            block()
        } catch (failure: IllegalArgumentException) {
            return failure
        } catch (failure: IOException) {
            return failure
        }
        throw AssertionError("Expected invalid backup content to be rejected")
    }

    private class HeaderOnlyInput(entry: TarArchiveEntry, private val failure: IOException? = null) : InputStream() {
        private val bytes = header(entry)
        private var position = 0
        var payloadReads = 0
            private set

        override fun read(): Int {
            if (position < bytes.size) return bytes[position++].toInt() and 0xff
            payloadReads++
            throw failure ?: AssertionError("Header rejection must not read payload")
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return read()
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(target, offset, position, position + count)
            position += count
            return count
        }
    }

    private companion object {
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun header(entry: TarArchiveEntry): ByteArray = ByteArray(512).also { entry.writeEntryHeader(it) }
    }
}
