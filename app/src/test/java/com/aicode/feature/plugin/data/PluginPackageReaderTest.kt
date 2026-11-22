package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginErrorCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginPackageReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun destination() = File(temporary.root, "staging/package")
    private fun rejected(bytes: ByteArray, limits: PluginPackageReader.Limits = PluginPackageReader.Limits()): PluginErrorCode {
        val dir = destination()
        val result = PluginPackageReader(limits).read(bytes.inputStream(), dir) as PackageReadResult.Failed
        assertFalse("owned stage must be cleaned", dir.exists())
        return result.error.code
    }
    @Test fun extractsAndHashesEveryFileNotOnlyManifest() {
        val archive = PluginCoreFixtures.archive(extra = listOf("assets/extra.bin" to byteArrayOf(0, 1, 2)))
        val result = PluginPackageReader().read(archive.inputStream(), destination()) as PackageReadResult.Ok
        assertEquals(3, result.pkg.fileHashes.size)
        assertEquals(PluginFiles.sha256(byteArrayOf(0, 1, 2)), result.pkg.fileHashes["assets/extra.bin"])
        assertEquals(PluginFiles.sha256(archive), result.pkg.archiveSha256)
        assertEquals(PluginFiles.contentHash(result.pkg.fileHashes), result.pkg.contentHash)
        assertTrue(PluginFiles.verify(result.pkg.dir, result.pkg.fileHashes))
    }
    @Test fun hostileArchiveNamesAreRejectedBeforeWritingOutsideStage() {
        for (path in listOf("../escape", "/absolute", "a/../../escape", "a\\escape", "C:/escape", "a//escape", "a/./escape", "a\u0000escape", "a\nescape")) {
            assertEquals(path, PluginErrorCode.ARCHIVE_UNSAFE_PATH, rejected(PluginCoreFixtures.zip(listOf(path to byteArrayOf(1)))))
        }
        assertFalse(File(temporary.root, "escape").exists())
    }
    @Test fun deepArchivePathIsRejected() {
        assertEquals(PluginErrorCode.ARCHIVE_UNSAFE_PATH,
            rejected(PluginCoreFixtures.zip(listOf((1..9).joinToString("/") { "x" } to byteArrayOf(1)))))
    }
    @Test fun caseDuplicateEntriesAreRejected() {
        assertEquals(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY,
            rejected(PluginCoreFixtures.zip(listOf("a.txt" to byteArrayOf(1), "A.txt" to byteArrayOf(2)))))
    }
    @Test fun exactDuplicateEntryNamesAreRejected() {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            repeat(2) {
                zip.putArchiveEntry(ZipArchiveEntry("duplicate"))
                zip.write(byteArrayOf(it.toByte()))
                zip.closeArchiveEntry()
            }
        }
        assertEquals(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, rejected(output.toByteArray()))
    }
    @Test fun caseConflictingParentDirectoriesAreRejected() {
        assertEquals(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY,
            rejected(PluginCoreFixtures.zip(listOf("Dir/a" to byteArrayOf(1), "dir/b" to byteArrayOf(2)))))
    }
    @Test fun fileDirectoryCollisionIsRejectedInEitherOrder() {
        val entries = listOf("node" to byteArrayOf(1), "node/file" to byteArrayOf(2))
        assertEquals(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, rejected(PluginCoreFixtures.zip(entries)))
        assertEquals(PluginErrorCode.ARCHIVE_DUPLICATE_ENTRY, rejected(PluginCoreFixtures.zip(entries.reversed())))
    }
    @Test fun unixSymlinkEntryIsExplicitlyRejected() {
        val output = ByteArrayOutputStream()
        ZipArchiveOutputStream(output).use { zip ->
            val link = ZipArchiveEntry("link").apply { unixMode = UnixStat.LINK_FLAG or 511 }
            zip.putArchiveEntry(link)
            zip.write("../outside".toByteArray())
            zip.closeArchiveEntry()
        }
        assertEquals(PluginErrorCode.ARCHIVE_SYMLINK, rejected(output.toByteArray()))
    }
    @Test fun limitsApplyToActualArchiveBytes() {
        val archive = PluginCoreFixtures.archive()
        assertEquals(PluginErrorCode.ARCHIVE_TOO_LARGE, rejected(archive,
            PluginPackageReader.Limits(maxArchiveBytes = archive.size.toLong() - 1)))
    }
    @Test fun entryAndTotalInflationLimitsAreEnforced() {
        val archive = PluginCoreFixtures.zip(listOf("blob" to ByteArray(4000)))
        assertEquals(PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE, rejected(archive, PluginPackageReader.Limits(maxEntryBytes = 3999)))
        val total = PluginCoreFixtures.zip(listOf("a" to ByteArray(3000), "b" to ByteArray(3000)))
        assertEquals(PluginErrorCode.ARCHIVE_TOO_LARGE, rejected(total, PluginPackageReader.Limits(maxTotalBytes = 5999)))
    }
    @Test fun forgedUncompressedSizeCannotBypassActualStreamLimit() {
        val archive = PluginCoreFixtures.zip(listOf("blob" to ByteArray(6000)))
        val central = (0..archive.size - 28).first { index ->
            archive[index] == 0x50.toByte() && archive[index + 1] == 0x4b.toByte() &&
                archive[index + 2] == 0x01.toByte() && archive[index + 3] == 0x02.toByte()
        }
        repeat(4) { archive[central + 24 + it] = 0 }
        assertEquals(PluginErrorCode.ARCHIVE_ENTRY_TOO_LARGE,
            rejected(archive, PluginPackageReader.Limits(maxEntryBytes = 4000)))
    }
    @Test fun entryCountIncludesDirectories() {
        val bytes = PluginCoreFixtures.zip(listOf("dir/" to byteArrayOf(), "dir/file" to byteArrayOf(1)))
        assertEquals(PluginErrorCode.ARCHIVE_TOO_MANY_ENTRIES, rejected(bytes, PluginPackageReader.Limits(maxEntries = 1)))
    }
    @Test fun noProgressStreamFailsAndCleansOnlyItsOwnStage() {
        val zero = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        val user = File(temporary.root, "user/data").apply { parentFile.mkdirs(); writeText("keep") }
        val result = PluginPackageReader().read(zero, destination()) as PackageReadResult.Failed
        assertEquals(PluginErrorCode.ARCHIVE_UNREADABLE, result.error.code)
        assertFalse(destination().exists())
        assertEquals("keep", user.readText())
    }
    @Test fun existingTargetIsNeitherModifiedNorDeleted() {
        val target = destination().apply { mkdirs() }
        val user = File(target, "user.txt").apply { writeText("owned by user") }
        val result = PluginPackageReader().read(PluginCoreFixtures.archive().inputStream(), target) as PackageReadResult.Failed
        assertEquals(PluginErrorCode.STORAGE_FAILED, result.error.code)
        assertEquals("owned by user", user.readText())
    }
    @Test fun rejectsSymlinkStageWithoutFollowingOrDeletingIt() {
        val user = temporary.newFolder("user")
        File(user, "keep").writeText("keep")
        destination().parentFile.mkdirs()
        Files.createSymbolicLink(destination().toPath(), user.toPath())
        val result = PluginPackageReader().read(PluginCoreFixtures.archive().inputStream(), destination()) as PackageReadResult.Failed
        assertEquals(PluginErrorCode.STORAGE_FAILED, result.error.code)
        assertTrue(Files.isSymbolicLink(destination().toPath()))
        assertEquals("keep", File(user, "keep").readText())
    }
    @Test fun symlinkStageParentCannotRedirectExtractionOrCleanup() {
        val outside = temporary.newFolder("outside-stage")
        File(outside, "keep").writeText("keep")
        Files.createSymbolicLink(destination().parentFile.toPath(), outside.toPath())
        val result = PluginPackageReader().read(PluginCoreFixtures.archive().inputStream(), destination()) as PackageReadResult.Failed
        assertEquals(PluginErrorCode.ARCHIVE_UNREADABLE, result.error.code)
        assertEquals(listOf("keep"), outside.list()!!.toList())
    }
    @Test fun missingOversizedOrCorruptManifestDoesNotLeaveExtractedFiles() {
        assertEquals(PluginErrorCode.MANIFEST_MISSING, rejected(PluginCoreFixtures.zip(listOf("file" to byteArrayOf(1)))))
        assertEquals(PluginErrorCode.MANIFEST_TOO_LARGE, rejected(PluginCoreFixtures.zip(listOf("plugin.json" to ByteArray(256 * 1024 + 1)))))
        assertEquals(PluginErrorCode.ARCHIVE_UNREADABLE, rejected(PluginCoreFixtures.zip(listOf("plugin.json" to byteArrayOf(0xc3.toByte(), 0x28)))))
    }
    @Test fun nonZipAndTruncatedArchiveAreRejected() {
        assertEquals(PluginErrorCode.ARCHIVE_UNREADABLE, rejected("not a tar or zip".toByteArray()))
        val archive = PluginCoreFixtures.archive()
        assertEquals(PluginErrorCode.ARCHIVE_UNREADABLE, rejected(archive.copyOf(archive.size / 2)))
    }
}
