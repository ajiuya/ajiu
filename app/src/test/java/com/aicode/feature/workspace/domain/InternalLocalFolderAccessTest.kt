package com.aicode.feature.workspace.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException

class InternalLocalFolderAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var root: String
    private lateinit var snapshot: LocalFolderSnapshot
    private lateinit var access: InternalLocalFolderAccess
    private var current = true

    @Before
    fun prepare() {
        directory = temporary.newFolder("authorized")
        root = localRootPath("grant-one", "epoch-one")
        val grant = LocalFolderGrant("grant-one", "epoch-one", "authorized", root, true)
        snapshot = LocalFolderSnapshot(1, listOf(grant), listOf(CapturedLocalFolderRoot(grant, directory))) {
            if (!current) throw CancellationException("Authorization changed")
        }
        access = InternalLocalFolderAccess(snapshot)
    }

    private fun path(name: String) = "$root/$name"
    private fun link(name: String, target: String) {
        val file = File(directory, name)
        file.parentFile.mkdirs()
        Files.createSymbolicLink(file.toPath(), File(target).toPath())
    }

    @Test
    fun virtualIdentitySurvivesDisplayAndHostMetadataNeverExposesRawFile() {
        File(directory, "binary").writeBytes(byteArrayOf(1, 2, -1))
        assertEquals(path("binary"), access.toDisplayPath(path("binary")))
        assertEquals(root, access.parentPath(path("binary")))
        assertNull(access.parentPath(root))
        val entry = access.listFiles(root).single()
        assertEquals("binary", entry.name)
        assertNull(entry.localFile)
        assertArrayEquals(byteArrayOf(1, 2, -1), access.readBytes(path("binary"), 3))
    }

    @Test
    fun byteReadsAreBoundedAndTheLegacyLimitDoesNotAllocateTheWholeFile() {
        File(directory, "empty").createNewFile()
        File(directory, "bytes").writeBytes(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(), access.readBytes(path("empty"), 0))
        assertThrows(FileReadTooLargeException::class.java) { access.readBytes(path("bytes"), 2) }
        assertThrows(IllegalArgumentException::class.java) { access.readBytes(path("bytes"), -1) }
        java.io.RandomAccessFile(File(directory, "large"), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertThrows(FileReadTooLargeException::class.java) { access.readBytes(path("large")) }
    }

    @Test
    fun linksAreResolvedBeforeParentSegmentsAndCannotLeaveTheAuthorizedRoot() {
        File(directory, "target/sub").mkdirs()
        File(directory, "target/probe").writeText("right")
        File(directory, "probe").writeText("wrong")
        link("relative", "target/sub")
        link("chain", "relative")
        assertEquals("right", access.readFile(path("chain/../probe")))
        assertThrows(IOException::class.java) { access.resolvePath(path("../outside")) }
        link("escape", temporary.root.absolutePath)
        assertThrows(IOException::class.java) { access.readBytes(path("escape/secret"), 64) }
        link("escape-back", "../authorized/probe")
        assertThrows(IOException::class.java) { access.readFile(path("escape-back")) }
    }

    @Test
    fun noFollowKeepsFinalLinkIncludingTrailingSlashAndDeleteNeverTouchesTarget() {
        val outside = temporary.newFolder("outside")
        val keep = File(outside, "keep").apply { writeText("keep") }
        link("escape", outside.absolutePath)
        assertEquals(File(directory, "escape").absolutePath, access.resolvePath(path("escape/"), false))
        access.deleteRecursively(path("escape/"))
        assertEquals("keep", keep.readText())
        assertFalse(Files.exists(File(directory, "escape").toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun linkLoopsFailAtBoundWhileTheFinalEntryRemainsRemovable() {
        link("one", "two")
        link("two", "one")
        assertThrows(FileSystemLoopException::class.java) { access.resolvePath(path("one")) }
        assertEquals(File(directory, "one").absolutePath, access.resolvePath(path("one/"), false))
        access.delete(path("one"))
        assertTrue(Files.isSymbolicLink(File(directory, "two").toPath()))
    }

    @Test
    fun rootAndAliasesCannotBeDeletedRenamedMovedCopiedOrCommittedByTransfer() {
        File(directory, "keep").writeText("keep")
        link("alias", directory.absolutePath)
        for (source in listOf(root, path("alias"), path("child/.."))) {
            assertThrows(IOException::class.java) { access.deleteRecursively(source) }
            assertThrows(IOException::class.java) { access.rename(source, path("renamed")) }
            assertThrows(IOException::class.java) { access.copy(source, path("copied"), true) }
            assertThrows(IOException::class.java) { access.move(source, path("moved"), true) }
            assertThrows(IOException::class.java) { access.transferFile(source) }
        }
        assertEquals("keep", File(directory, "keep").readText())
    }

    @Test
    fun nestedAuthorizedRootsAreProtectedThroughTheirParentGrant() {
        val nested = File(directory, "nested").apply { mkdirs() }
        val keep = File(nested, "keep").apply { writeText("keep") }
        val nestedGrant = LocalFolderGrant("nested", "epoch", "nested", localRootPath("nested", "epoch"), true)
        val roots = snapshot.roots + nestedGrant
        val nestedAccess = InternalLocalFolderAccess(LocalFolderSnapshot(2, roots,
            snapshot.records + CapturedLocalFolderRoot(nestedGrant, nested)) {})
        assertThrows(IOException::class.java) { nestedAccess.deleteRecursively(path("nested")) }
        assertThrows(IOException::class.java) { nestedAccess.move(path("nested"), path("moved"), true) }
        assertEquals("keep", keep.readText())
    }

    @Test
    fun directoryCopyPreservesSafeLinksWithoutExpandingThemAndRejectsEscapingLinksBeforeOverwrite() {
        File(directory, "source/sub").mkdirs()
        File(directory, "source/sub/file").writeText("file")
        link("source/safe", "sub")
        access.copy(path("source"), path("copy"), false)
        assertTrue(Files.isSymbolicLink(File(directory, "copy/safe").toPath()))
        assertEquals("file", File(directory, "copy/sub/file").readText())
        File(directory, "destination").mkdirs()
        File(directory, "destination/keep").writeText("keep")
        link("source/escape", temporary.root.absolutePath)
        assertThrows(IOException::class.java) { access.copy(path("source"), path("destination"), true) }
        assertEquals("keep", File(directory, "destination/keep").readText())
    }

    @Test
    fun copyAndMoveRejectEntryAndResolvedAncestorOverlapBeforeMutation() {
        File(directory, "tree/sub").mkdirs()
        File(directory, "tree/sub/keep").writeText("keep")
        link("alias", "tree")
        for (target in listOf(path("tree"), path("alias/sub/child"), path("alias"))) {
            assertThrows(IOException::class.java) { access.copy(path("tree/sub"), target, true) }
            assertThrows(IOException::class.java) { access.move(path("tree/sub"), target, true) }
            assertEquals("keep", File(directory, "tree/sub/keep").readText())
        }
    }

    @Test
    fun emptyDirectoriesAndListingFailuresAreNotConflated() {
        File(directory, "empty").mkdirs()
        File(directory, "plain").writeText("plain")
        assertTrue(access.listFiles(path("empty")).isEmpty())
        assertThrows(IOException::class.java) { access.listFiles(path("plain")) }
        assertThrows(IOException::class.java) { access.listFiles(path("missing")) }
        access.copy(path("empty"), path("copied"))
        assertTrue(File(directory, "copied").isDirectory)
    }

    @Test
    fun openStreamAndLazyLinesRejectCapturedAuthorizationChange() {
        File(directory, "file").writeText("one\ntwo\n")
        val input = access.openRead(path("file"))
        val lines = access.readLines(path("file"))
        assertEquals('o'.code, input.read())
        current = false
        assertThrows(CancellationException::class.java) { input.read() }
        assertThrows(CancellationException::class.java) { lines.toList() }
        input.close()
    }

    @Test
    fun switchingLinkAfterReadOpensDoesNotReturnBytesFromAnUncapturedTarget() {
        File(directory, "one").writeText("one")
        File(directory, "two").writeText("two")
        link("link", "one")
        val input = access.openRead(path("link"))
        Files.delete(File(directory, "link").toPath())
        link("link", "two")
        assertThrows(CancellationException::class.java) { input.read() }
    }

    @Test
    fun cancelledAtomicWriteLeavesOriginalFileAndRemovesItsStagingFile() {
        File(directory, "file").writeText("original")
        val input = object : ByteArrayInputStream("replacement".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { current = false }
        }
        assertThrows(CancellationException::class.java) { access.writeStream(path("file"), input, true) }
        assertEquals("original", File(directory, "file").readText())
        assertTrue(directory.listFiles()!!.none { it.name.startsWith(".aicode-tmp-") })
    }

    @Test
    fun fingerprintIsStreamedAndAtomicWritesKeepExactBytes() {
        val bytes = ByteArray(100_000) { it.toByte() }
        access.writeBytes(path("data/file"), bytes, false)
        assertArrayEquals(bytes, access.readBytes(path("data/file"), bytes.size.toLong()))
        assertEquals(sha256Fingerprint(bytes), access.fingerprint(path("data/file")))
        access.rename(path("data/file"), path("data/new"))
        assertTrue(access.isFile(path("data/new")))
        assertFalse(access.exists(path("data/file")))
        assertThrows(FileAlreadyExistsException::class.java) { access.writeBytes(path("data/new"), bytes, false) }
    }
}
