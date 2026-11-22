package com.aicode.feature.plugin.runtime

import com.aicode.feature.backup.domain.PluginBackupSectionMeta
import com.aicode.feature.backup.domain.PluginBackupSectionStatus
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginBackupArchiveServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manager = mockk<PluginManager>()
    private val runtime = mockk<PluginRuntimeManager>()
    private val gateway = mockk<PluginHostGateway>()
    private val capabilities = mockk<PluginCapabilityRepository>()
    private val service = PluginBackupArchiveService(manager, runtime, Provider { gateway }, capabilities)
    private val meta = PluginBackupSectionMeta("fixture.backup", "Fixture data", 1, 3)
    private fun archive(name: String, bytes: ByteArray = "abc".toByteArray(), symlink: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            val entry = if (symlink) TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = "/outside" }
                else TarArchiveEntry(name).apply { size = bytes.size.toLong() }
            tar.putArchiveEntry(entry)
            if (!symlink) tar.write(bytes)
            tar.closeArchiveEntry()
        }
        return out.toByteArray()
    }
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Expected malicious or oversized archive rejection") } catch (_: IOException) { }
    }

    @Test fun sectionPathsRejectTraversalSchemeLinksAndUnboundedDepth() {
        for (path in listOf("", "/root", "../file", "a/../b", "a//b", "a/./b", "a\\b", "data://file", "a\u0000b", (1..17).joinToString("/") { "x" })) {
            assertFalse(path, PluginBackupArchiveService.isValidSectionPath(path))
        }
        assertTrue(PluginBackupArchiveService.isValidSectionPath("memory/global.md"))
    }

    @Test fun actualReadCountDoesNotTrustHeaderSizeAndCancellationStopsTheStream() {
        val out = ByteArrayOutputStream()
        assertEquals(3L, PluginBackupArchiveService.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), out, 3, 4) {})
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
        for ((bytes, declared, maximum) in listOf(Triple(5, 4L, 4L), Triple(3, 4L, 4L), Triple(5, 5L, 4L))) {
            try { PluginBackupArchiveService.copyBounded(ByteArrayInputStream(ByteArray(bytes)), null, declared, maximum) {}; fail("Expected actual size rejection") }
            catch (_: IOException) { }
        }
        var checks = 0
        try {
            PluginBackupArchiveService.copyBounded(ByteArrayInputStream(ByteArray(100_000)), null, 100_000, 100_000) {
                if (++checks == 3) throw CancellationException("Native authority revoked")
            }
            fail("Cancellation must not be swallowed")
        } catch (_: CancellationException) { assertEquals(3, checks) }
    }

    @Test fun missingSelectionNeverInvokesPluginAndReturnsAnExplicitTypedResult() = runBlocking<Unit> {
        val batch = service.prepareImport(listOf(meta), emptySet())
        TarArchiveInputStream(ByteArrayInputStream(archive("plugins/fixture.backup/file"))).use { tar -> service.readImport(tar, tar.nextEntry, batch) }
        assertEquals(PluginBackupSectionStatus.NOT_SELECTED, batch.results.single().status)
        verify(exactly = 0) { manager.capture(any()) }
        assertTrue(batch.sections.isEmpty())
        service.cleanupImport(batch)
    }

    @Test fun realTarNamespaceSymlinkDuplicateAndMissingMetadataAreRejected() = runBlocking<Unit> {
        for (name in listOf("plugins/fixture.backup/../escape", "plugins/fixture.backup/a/../../escape", "plugins/other.backup/file", "plugins/fixture.backup/data://file")) {
            val batch = service.prepareImport(listOf(meta), emptySet())
            invalid { TarArchiveInputStream(ByteArrayInputStream(archive(name))).use { tar -> service.readImport(tar, tar.nextEntry, batch) } }
        }
        val batch = service.prepareImport(listOf(meta), emptySet())
        invalid { TarArchiveInputStream(ByteArrayInputStream(archive("plugins/fixture.backup/link", symlink = true))).use { tar -> service.readImport(tar, tar.nextEntry, batch) } }
        val duplicate = archive("plugins/fixture.backup/file")
        TarArchiveInputStream(ByteArrayInputStream(duplicate)).use { tar -> service.readImport(tar, tar.nextEntry, batch) }
        invalid { TarArchiveInputStream(ByteArrayInputStream(duplicate)).use { tar -> service.readImport(tar, tar.nextEntry, batch) } }
    }

    @Test fun selectedFilesWriteOnlyInsideOwnedStagingAndCannotFollowAReplacedParent() = runBlocking<Unit> {
        val data = temporary.newFolder("data")
        val directory = data.resolve("backup-import").apply { mkdir() }
        val capture = PluginCapture(PluginRecord(meta.pluginId, "1.0.0", true, 1, archiveSha256 = "archive", manifestSha256 = "manifest",
            installRevision = "revision", contentHash = "hash", fileHashes = emptyMap()), mockk(), PluginContent(), temporary.newFolder("install"), {}, {})
        val context = PluginInvocationContext(capture, null, "", null, data, emptyFlow()) {}
        every { capabilities.checkGranted(capture, any()) } returns true
        val section = PluginBackupArchiveService.Section(context, directory, meta,
            Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
        val batch = PluginBackupArchiveService.ImportSections(listOf(meta), setOf(meta.pluginId))
        batch.sections[meta.pluginId] = section
        TarArchiveInputStream(ByteArrayInputStream(archive("plugins/fixture.backup/nested/file"))).use { tar -> service.readImport(tar, tar.nextEntry, batch) }
        assertEquals("abc", directory.resolve("nested/file").readText())
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(directory.resolve("alias").toPath(), outside.toPath())
        invalid { TarArchiveInputStream(ByteArrayInputStream(archive("plugins/fixture.backup/alias/escape"))).use { tar -> service.readImport(tar, tar.nextEntry, batch) } }
        assertTrue(outside.list()!!.isEmpty())
        service.cleanupImport(batch)
        assertFalse(directory.exists()); assertTrue(outside.exists())
    }

    @Test fun importMetadataCannotSilentlySelectUndeclaredOrDuplicatePlugins() = runBlocking<Unit> {
        invalid { service.prepareImport(listOf(meta), setOf("other.backup")) }
        invalid { service.prepareImport(listOf(meta, meta), emptySet()) }
        invalid { service.prepareImport(listOf(meta.copy(bytes = PluginBackupArchiveService.MAX_TOTAL_BYTES + 1)), emptySet()) }
        invalid { service.prepareImport(listOf(meta.copy(pluginId = "../outside")), emptySet()) }
    }
}
