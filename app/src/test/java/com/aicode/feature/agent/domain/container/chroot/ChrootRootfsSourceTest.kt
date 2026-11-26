package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.ContainerInstaller
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
import java.io.RandomAccessFile
import java.nio.file.Files

class ChrootRootfsSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files by lazy { temporary.newFolder("files") }
    private val images by lazy { File(files, "rootfs_images").apply { mkdirs() } }

    private fun open(value: String) = openChrootRootfsSource(value, files) {
        throw AssertionError("Private files must not use ContentResolver")
    }

    private fun rejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Unsafe archive source was accepted")
        } catch (_: IOException) {
            Unit
        }
    }

    @Test
    fun privateDownloadedAndImportedFilesKeepExactBytes() {
        for (name in listOf("download_alpine.tar.gz", "import_profile.tar.xz")) {
            val image = File(images, name).apply { writeBytes(byteArrayOf(1, 4, 7, 9)) }
            open(image.toURI().toString()).use { input ->
                assertArrayEquals(image.readBytes(), input.readBytes())
                assertFalse(input.markSupported())
            }
        }
    }

    @Test
    fun contentUriStillUsesOnlyTheProvidedResolver() {
        val calls = mutableListOf<String>()
        val value = "content://picker/document/rootfs"
        openChrootRootfsSource(value, files) {
            calls += it
            ByteArrayInputStream(byteArrayOf(5, 6))
        }.use { assertArrayEquals(byteArrayOf(5, 6), it.readBytes()) }
        assertEquals(listOf(value), calls)
    }

    @Test
    fun arbitraryFilesTraversalAuthorityAndNonFileSchemesAreRejected() {
        val outside = temporary.newFile("outside.tar.gz").apply { writeText("archive") }
        val nested = File(images, "nested").apply { mkdirs() }
        val nestedImage = File(nested, "image.tar.gz").apply { writeText("archive") }
        val directory = File(images, "directory").apply { mkdirs() }
        val values = listOf(
            outside.toURI().toString(), nestedImage.toURI().toString(), directory.toURI().toString(),
            images.toURI().toString() + "../rootfs_images/outside.tar.gz",
            "file://other${outside.absolutePath}", outside.toURI().toString() + "?ignored=yes",
            outside.toURI().toString() + "#ignored", "https://example.invalid/rootfs.tar.gz", "content:/document/rootfs"
        )
        values.forEach { value -> rejected { open(value).close() } }
    }

    @Test
    fun symbolicLeafDirectoryAndAncestorAndHardlinkedFilesAreRejected() {
        val original = temporary.newFile("original.tar.gz").apply { writeText("archive") }
        val symbolic = File(images, "symbolic.tar.gz")
        Files.createSymbolicLink(symbolic.toPath(), original.toPath())
        rejected { open(symbolic.toURI().toString()).close() }
        val hardlink = File(images, "hardlink.tar.gz")
        Files.createLink(hardlink.toPath(), original.toPath())
        rejected { open(hardlink.toURI().toString()).close() }

        val otherFiles = temporary.newFolder("other-files")
        Files.createSymbolicLink(File(otherFiles, "rootfs_images").toPath(), images.toPath())
        rejected { openChrootRootfsSource(File(otherFiles, "rootfs_images/original.tar.gz").toURI().toString(), otherFiles) { error("resolver") } }
        val linkedFiles = File(temporary.root, "linked-files")
        Files.createSymbolicLink(linkedFiles.toPath(), files.toPath())
        rejected { openChrootRootfsSource(File(linkedFiles, "rootfs_images/original.tar.gz").toURI().toString(), linkedFiles) { error("resolver") } }
        assertEquals("archive", original.readText())
    }

    @Test
    fun changedContentReplacementAndEarlyCloseCannotBeVerified() {
        val image = File(images, "import_profile.tar.gz").apply { writeText("first") }
        val input = open(image.toURI().toString())
        val timestamp = image.lastModified()
        image.writeText("other")
        image.setLastModified(timestamp)
        rejected { input.use { it.readBytes() } }

        image.writeText("again")
        val replaced = open(image.toURI().toString())
        val replacement = File(images, "replacement").apply { writeText("again") }
        Files.move(replacement.toPath(), image.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        rejected { replaced.use { it.readBytes() } }

        val incomplete = open(image.toURI().toString())
        assertTrue(incomplete.read() >= 0)
        rejected { incomplete.close() }
    }

    @Test
    fun overLimitPrivateImageIsRejectedWithoutReadingItsSparseContents() {
        val image = File(images, "import_large.tar.gz")
        RandomAccessFile(image, "rw").use { it.setLength(1024L * 1024 * 1024 + 1) }
        rejected { open(image.toURI().toString()).close() }
    }

    @Test
    fun privateGzipAndXzStillPassThroughTheExistingArchiveValidator() {
        val archive = ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file", data = "ok".toByteArray()))
        val variants = listOf(
            ChrootArchiveFixtures.gzip(archive) to ContainerInstaller.CompressedFormat.GZIP,
            ChrootArchiveFixtures.compress(archive, ContainerInstaller.CompressedFormat.XZ) to ContainerInstaller.CompressedFormat.XZ
        )
        variants.forEachIndexed { index, (bytes, format) ->
            val image = File(images, "import_$index").apply { writeBytes(bytes) }
            val expected = ByteArrayOutputStream().also { ChrootArchiveWriter().write(ByteArrayInputStream(bytes), format, it) }
            val actual = ByteArrayOutputStream()
            open(image.toURI().toString()).use { ChrootArchiveWriter().write(it, format, actual) }
            assertArrayEquals(expected.toByteArray(), actual.toByteArray())
        }
    }
}
