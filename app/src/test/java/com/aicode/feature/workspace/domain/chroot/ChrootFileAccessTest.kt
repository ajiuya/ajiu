package com.aicode.feature.workspace.domain.chroot

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.FilePathOverlapException
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.FileAlreadyExistsException
import kotlin.io.NoSuchFileException

class ChrootFileAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun cacheAndAgentConfigIdentityQueriesNeverAcquireOrCloseAWorker() {
        val fixture = Fixture()
        repeat(4) {
            assertEquals("fixture", fixture.access.cacheIdentity())
            assertEquals("fixture", fixture.access.agentConfigSourceIdentity())
        }
        fixture.identity = "changed"
        assertEquals("changed", fixture.access.cacheIdentity())
        assertEquals("changed", fixture.access.agentConfigSourceIdentity())
        assertEquals(10, fixture.identityQueries)
        assertEquals(0, fixture.captures)
        assertEquals(0, fixture.ownerCloses)
        verify(exactly = 0) { fixture.client.close() }
        verify(exactly = 0) { fixture.client.stat(any(), any()) }
    }

    @Test
    fun aFailedPureIdentityCaptureCannotStartAWorker() {
        var captures = 0
        val access = ChrootFileAccess(captureIdentity = { throw CancellationException("source unavailable") }, sessionFactory = {
            captures++
            throw AssertionError("Worker must not be acquired")
        })
        assertThrows(CancellationException::class.java) { access.cacheIdentity() }
        assertThrows(CancellationException::class.java) { access.agentConfigSourceIdentity() }
        assertThrows(CancellationException::class.java) { access.exists("file") }
        assertEquals(0, captures)
    }

    @Test
    fun identityChangesBeforeTheFactoryIsInvokedPreventWorkerAcquisition() {
        var identityQueries = 0
        var captures = 0
        val access = ChrootFileAccess(captureIdentity = {
            if (identityQueries++ == 0) "first" else "second"
        }, sessionFactory = {
            captures++
            throw AssertionError("Worker must not be acquired")
        })
        assertThrows(CancellationException::class.java) { access.exists("file") }
        assertEquals(2, identityQueries)
        assertEquals(0, captures)
    }

    @Test
    fun aSessionFromAnotherIdentityIsClosedBeforeAnyWorkerRequest() {
        val client = mockk<ChrootFileWorkerClient>(relaxed = true)
        var captures = 0
        var ownerCloses = 0
        val access = ChrootFileAccess(captureIdentity = { "expected" }, sessionFactory = {
            captures++
            ChrootFileSession("another", client, temporary.root, {}, emptyList(), closeOwner = { ownerCloses++ })
        })
        assertThrows(CancellationException::class.java) { access.exists("file") }
        assertEquals(1, captures)
        assertEquals(1, ownerCloses)
        verify(exactly = 1) { client.close() }
        verify(exactly = 0) { client.stat(any(), any()) }
    }

    @Test
    fun sourceChangesInsideTheSessionFactoryReleaseTheSessionBeforeAnyRequest() {
        val client = mockk<ChrootFileWorkerClient>(relaxed = true)
        var identity = "first"
        var ownerCloses = 0
        val access = ChrootFileAccess(captureIdentity = { identity }, sessionFactory = {
            ChrootFileSession(identity, client, temporary.root, {}, emptyList(), closeOwner = { ownerCloses++ }).also {
                identity = "second"
            }
        })
        assertThrows(CancellationException::class.java) { access.exists("file") }
        assertEquals(1, ownerCloses)
        verify(exactly = 1) { client.close() }
        verify(exactly = 0) { client.stat(any(), any()) }
    }

    @Test
    fun aFailedFactoryIsRecheckedForSourceChangesWithoutRetryingTheWorker() {
        var identity = "first"
        var captures = 0
        val original = IOException("worker unavailable")
        val access = ChrootFileAccess(captureIdentity = { identity }, sessionFactory = {
            captures++
            identity = "second"
            throw original
        })
        val failure = assertThrows(CancellationException::class.java) { access.exists("file") }
        assertEquals(listOf(original), failure.suppressed.toList())
        assertEquals(1, captures)
    }

    @Test
    fun streamsKeepCheckingThePureIdentityEvenIfTheSessionOwnerCheckIsUnchanged() {
        val client = mockk<ChrootFileWorkerClient>(relaxed = true)
        var identity = "first"
        var captures = 0
        var ownerCloses = 0
        var handleCloses = 0
        every { client.openRead("/root/workspace/file") } returns object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { identity = "second" }
            override fun close() { handleCloses++ }
        }
        val access = ChrootFileAccess(captureIdentity = { identity }, sessionFactory = {
            captures++
            ChrootFileSession("first", client, temporary.root, {}, emptyList(), closeOwner = { ownerCloses++ })
        })
        val input = access.openRead("file", "first")
        assertThrows(CancellationException::class.java) { input.read() }
        input.close()
        assertEquals(1, captures)
        assertEquals(1, handleCloses)
        assertEquals(1, ownerCloses)
        verify(exactly = 1) { client.openRead("/root/workspace/file") }
    }

    @Test
    fun guestPathsExpandWithoutHostMappingOrCollapsingSymlinkParentSegments() {
        val fixture = Fixture()
        val paths = mapOf(
            "~/workspace/a" to "/root/workspace/a", "~" to "/root", "~/notes" to "/root/notes",
            "relative" to "/root/workspace/relative", "/etc/hosts" to "/etc/hosts",
            "dir/link/../file" to "/root/workspace/dir/link/../file"
        )
        paths.forEach { (input, guest) -> fixture.access.exists(input); verify { fixture.client.stat(guest, true) } }
        assertEquals("~/workspace/a", fixture.access.toDisplayPath("/root/workspace/a"))
        assertEquals("~/notes", fixture.access.toDisplayPath("/root/notes"))
        assertEquals("~/workspace", fixture.access.parentPath("relative"))
        assertNull(fixture.access.parentPath("/"))
        assertEquals(paths.size, fixture.captures)
    }

    @Test
    fun mappedHostAliasesKeepGuestSymlinkParentAndTrailingSlashSemantics() {
        var mappings = 0
        val fixture = Fixture(mapPath = { path ->
            mappings++
            val source = "/app/projects/current"
            when {
                path == source -> "/root/workspace"
                path.startsWith("$source/") -> "/root/workspace" + path.removePrefix(source)
                else -> path
            }
        })
        repeat(3) { assertEquals("fixture", fixture.access.cacheIdentity()) }
        assertEquals(0, mappings)
        assertEquals(0, fixture.captures)
        val path = "/app/projects/current/link/../nested//file/"
        fixture.access.exists(path)
        verify(exactly = 1) { fixture.client.stat("/root/workspace/link/../nested//file/", true) }
        fixture.access.exists("/app/projects/current-sibling/file")
        verify(exactly = 1) { fixture.client.stat("/app/projects/current-sibling/file", true) }
        fixture.access.exists("/etc/hosts")
        verify(exactly = 1) { fixture.client.stat("/etc/hosts", true) }
        assertEquals(3, fixture.captures)
        assertEquals(3, fixture.ownerCloses)
        verify(exactly = 3) { fixture.client.close() }
    }

    @Test
    fun reservedGuestTreesAndNulAreRejectedBeforeAcquiringAWorker() {
        val fixture = Fixture()
        for (path in listOf("/proc/self/fd", "/dev/null", "/sys", "/tmp/a", "/run/a", "../../proc/a", "/root/../dev/a")) {
            val failure = assertThrows(ChrootFileOperationException::class.java) { fixture.access.exists(path) }
            assertEquals(13, failure.errno)
            assertEquals("validate-path", failure.operation)
            assertEquals(if (path.startsWith('/')) path else "/root/workspace/$path", failure.guestPath)
            assertTrue(failure.message.orEmpty().contains(failure.guestPath!!))
        }
        assertThrows(IOException::class.java) { fixture.access.openRead("a\u0000b") }
        assertEquals(0, fixture.captures)
        verify(exactly = 0) { fixture.client.stat(any(), any()) }
    }

    @Test
    fun onlyEnoentBecomesMissingAndPermissionReadOnlyStaleAndLoopFailuresPropagate() {
        val fixture = Fixture()
        assertFalse(fixture.access.exists("missing"))
        assertFalse(fixture.access.isFile("missing"))
        assertEquals(0L, fixture.access.fileSize("missing"))
        assertThrows(NoSuchFileException::class.java) { fixture.access.readBytes("missing") }
        for (errno in listOf(13, 30, 16, 116, 40)) {
            fixture.statErrors["/root/workspace/denied"] = errno
            val failure = assertThrows(ChrootFileOperationException::class.java) { fixture.access.exists("denied") }
            assertEquals(errno, failure.errno)
            assertEquals("/root/workspace/denied", failure.guestPath)
            assertEquals("request", failure.operation)
            assertEquals(errno, (failure.cause as ChrootFileOperationException).errno)
            assertFalse(failure.message.orEmpty().contains("/data/"))
            assertEquals(errno, assertThrows(ChrootFileOperationException::class.java) { fixture.access.fingerprint("denied") }.errno)
        }
    }

    @Test
    fun privateConfigFilesAndToolOutputUseGuestPathsAndMissingFilesKeepEnoent() {
        val fixture = Fixture(writable = listOf("/root"), readOnly = listOf("/root/.aicode/docs"))
        fixture.stats["/root/.aicode"] = directory()
        fixture.access.writeFile("~/.aicode/settings.json", "config", false, Charsets.UTF_8)
        assertEquals("config", fixture.access.readFile("~/.aicode/settings.json"))
        fixture.access.writeFile("~/.aicode/tool-output/result.log", "archive", false, Charsets.UTF_8)
        assertEquals("archive", fixture.access.readFile("~/.aicode/tool-output/result.log"))
        assertFalse(fixture.access.exists("~/.aicode/missing.json"))
        val failure = assertThrows(NoSuchFileException::class.java) { fixture.access.readFile("~/.aicode/missing.json") }
        val native = failure.cause as ChrootFileOperationException
        assertEquals(2, native.errno)
        assertEquals("/root/.aicode/missing.json", native.guestPath)
        fixture.file("/root/.aicode/docs/guide.md", "read-only".toByteArray())
        assertEquals("r--", fixture.access.permissions("~/.aicode/docs/guide.md"))
        fixture.writeError = 30
        val denied = assertThrows(ChrootFileOperationException::class.java) {
            fixture.access.writeFile("~/.aicode/docs/guide.md", "replacement", true, Charsets.UTF_8)
        }
        assertEquals(30, denied.errno)
        assertEquals("/root/.aicode/docs/guide.md", denied.guestPath)
        assertEquals("read-only", fixture.access.readFile("~/.aicode/docs/guide.md"))
    }

    @Test
    fun frozenExecutionIdentityMustMatchEvenWithoutAnExplicitExpectedStreamIdentity() = runBlocking {
        val fixture = Fixture()
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "old", fixture.access, null, null) {}
        val failure = runCatching { withContext(execution) { fixture.access.exists("file") } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { fixture.client.stat(any(), any()) }
        assertEquals(0, fixture.captures)
        assertEquals(0, fixture.ownerCloses)
    }

    @Test
    fun metadataKeepsWorkerMillisecondsAndDirectoryTypesWithoutHostFileProbing() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1, 2, 3))
        assertTrue(fixture.access.isFile("file"))
        assertTrue(fixture.access.isDirectory("~/workspace"))
        assertEquals(3L, fixture.access.fileSize("file"))
        assertEquals(123L, fixture.access.lastModified("file"))
        assertEquals("/root/workspace/file", fixture.access.resolvePath("file", false))
        verify { fixture.client.resolve("/root/workspace/file", false) }
    }

    @Test
    fun renameNoReplaceAndMissingSourceErrorsPropagateWithoutDeletingTheDestination() {
        val fixture = Fixture()
        fixture.file("/root/workspace/source", byteArrayOf(1))
        fixture.file("/root/workspace/target", byteArrayOf(2))
        assertThrows(FileAlreadyExistsException::class.java) { fixture.access.rename("source", "target") }
        assertThrows(NoSuchFileException::class.java) { fixture.access.rename("missing", "new") }
        verify(exactly = 0) { fixture.client.rename(any(), any(), any()) }
        verify(exactly = 0) { fixture.client.delete(any(), any()) }
        assertArrayEquals(byteArrayOf(2), fixture.contents.getValue("/root/workspace/target"))
    }

    @Test
    fun expectedIdentityAndSourceChangeNeverCloneAWorkerDuringARead() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1, 2, 3))
        assertThrows(CancellationException::class.java) { fixture.access.openRead("file", "another") }
        verify(exactly = 0) { fixture.client.openRead(any()) }
        assertEquals(0, fixture.captures)
        assertEquals(0, fixture.ownerCloses)
        fixture.afterRead = { fixture.identity = "changed" }
        val input = fixture.access.openRead("file", "fixture")
        assertThrows(CancellationException::class.java) { input.read() }
        input.close()
        assertEquals(1, fixture.captures)
        assertEquals(1, fixture.ownerCloses)
        assertEquals(1, fixture.streamCloses)
        verify(exactly = 1) { fixture.client.openRead("/root/workspace/file") }
    }

    @Test
    fun sourceChangeImmediatelyAfterOpenStillReleasesTheNewHandle() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1))
        fixture.afterOpen = { fixture.identity = "changed" }
        assertThrows(CancellationException::class.java) { fixture.access.openRead("file") }
        assertEquals(1, fixture.streamCloses)
        assertEquals(1, fixture.ownerCloses)
    }

    @Test
    fun eofAndExplicitCloseReleaseOnlyTheOwnedFileSessionOnce() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1, 2))
        val input = fixture.access.openRead("file")
        assertArrayEquals(byteArrayOf(1, 2), input.readBytes())
        assertEquals(-1, input.read())
        input.close()
        input.close()
        assertEquals(1, fixture.streamCloses)
        assertEquals(1, fixture.ownerCloses)
        verify(exactly = 1) { fixture.client.close() }
    }

    @Test
    fun boundedReadsCheckDeclaredSizeGrowthZeroLimitsAndInvalidLimits() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1, 2, 3))
        assertThrows(FileReadTooLargeException::class.java) { fixture.access.readBytes("file", 2L) }
        verify(exactly = 0) { fixture.client.openRead(any()) }
        fixture.stats["/root/workspace/file"] = regular(1L)
        assertThrows(FileReadTooLargeException::class.java) { fixture.access.readBytes("file", 2L) }
        assertEquals(3L, fixture.transferred)
        fixture.file("/root/workspace/empty", byteArrayOf())
        assertArrayEquals(byteArrayOf(), fixture.access.readBytes("empty", 0L))
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.access.readBytes("file", Int.MAX_VALUE.toLong()))
        val captures = fixture.captures
        for (limit in listOf(-1L, Int.MAX_VALUE.toLong() + 1L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { fixture.access.readBytes("file", limit) }
        }
        assertEquals(captures, fixture.captures)
    }

    @Test
    fun defaultWholeFileReadsHaveAnExplicit64MiBLimitAndRejectNoProgress() {
        val fixture = Fixture()
        fixture.stats["/root/workspace/large"] = regular(64L * 1024 * 1024 + 1L)
        val failure = assertThrows(FileReadTooLargeException::class.java) { fixture.access.readFile("large") }
        assertEquals(64L * 1024 * 1024, failure.maxBytes)
        verify(exactly = 0) { fixture.client.openRead(any()) }
        fixture.file("/root/workspace/stalled", byteArrayOf())
        fixture.inputFactory = { object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        } }
        assertThrows(IOException::class.java) { fixture.access.readBytes("stalled", 1L) }
        assertEquals(2, fixture.ownerCloses)
    }

    @Test
    fun readLinesReopensTheCapturedSourcePerIterationAndBoundsIndividualLines() {
        val fixture = Fixture()
        fixture.file("/root/workspace/lines", ("x".repeat(65540) + "\ntail\n").toByteArray())
        val lines = fixture.access.readLines("lines")
        verify(exactly = 0) { fixture.client.openRead(any()) }
        assertEquals(0, fixture.captures)
        repeat(2) {
            val result = lines.toList()
            assertEquals(2, result.size)
            assertEquals(65536, result.first().length)
            assertEquals("tail", result.last())
        }
        assertEquals(2, fixture.streamCloses)
        fixture.identity = "changed"
        assertThrows(CancellationException::class.java) { lines.toList() }
        verify(exactly = 2) { fixture.client.openRead("/root/workspace/lines") }
        assertEquals(2, fixture.captures)
        assertEquals(2, fixture.ownerCloses)
    }

    @Test
    fun fingerprintStreamsSha256AndOnlyDirectoriesOrMissingFilesReturnNull() {
        val fixture = Fixture()
        val bytes = "same-mtime-different-content".toByteArray()
        fixture.file("/root/workspace/file", bytes)
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("sha256:$expected", fixture.access.fingerprint("file"))
        assertNull(fixture.access.fingerprint("missing"))
        assertNull(fixture.access.fingerprint("~/workspace"))
        assertEquals(1, fixture.streamCloses)
        assertEquals(3, fixture.captures)
    }

    @Test
    fun permissionsCombineModeBitsWithFrozenReadOnlyMountsAndEntriesNeverExposeHostFiles() {
        val fixture = Fixture(writable = listOf("/root"), readOnly = listOf("/root/workspace/ro"))
        fixture.stats["/root/workspace/ro/file"] = regular(2L, 0x1ff)
        fixture.stats["/root/workspace/plain"] = regular(2L, 0x124)
        fixture.stats["/root/workspace/write"] = regular(2L, 0x1ff)
        assertEquals("r-x", fixture.access.permissions("ro/file"))
        assertEquals("r--", fixture.access.permissions("plain"))
        assertEquals("rwx", fixture.access.permissions("write"))
        fixture.listings["/root/workspace/ro"] = listOf("file" to regular(2L, 0x1ff), "link" to symlink())
        val entries = fixture.access.listFiles("ro")
        assertTrue(entries.all { it.localFile == null && it.permissions == "r-x" })
        assertFalse(entries.last().isDirectory)
        assertEquals(123L, entries.first().lastModified)
    }

    @Test
    fun recursiveListingHonorsDepthSkipsDirectoryLinksAndPropagatesListingErrors() {
        val fixture = Fixture()
        fixture.listings["/root/workspace"] = listOf("top" to regular(), "sub" to directory(), "loop" to symlink())
        fixture.listings["/root/workspace/sub"] = listOf("child" to regular(), "deep" to directory())
        fixture.listings["/root/workspace/sub/deep"] = listOf("hidden" to regular())
        assertEquals(emptyList<String>(), fixture.access.listFilesRecursive("~/workspace", 0))
        assertEquals(listOf("top"), fixture.access.listFilesRecursive("~/workspace", 1))
        assertEquals(listOf("top", "sub/child"), fixture.access.listFilesRecursive("~/workspace", 2))
        verify(exactly = 0) { fixture.client.list("/root/workspace/loop") }
        verify(exactly = 0) { fixture.client.list("/root/workspace/sub/deep") }
        fixture.listErrors["/root/workspace/sub"] = 13
        assertEquals(13, assertThrows(ChrootFileOperationException::class.java) {
            fixture.access.listFilesRecursive("~/workspace", 2)
        }.errno)
        assertEquals(emptyList<String>(), fixture.access.listFilesRecursive("missing", 3))
    }

    @Test
    fun writesEnsureOnlyMissingParentsStreamChunksAndDoNotCloseCallerInput() {
        val fixture = Fixture()
        val bytes = ByteArray(140000) { (it % 127).toByte() }
        var closed = false
        val source = object : ByteArrayInputStream(bytes) { override fun close() { closed = true } }
        assertEquals(bytes.size.toLong(), fixture.access.writeStream("new/file", source, false))
        assertFalse(closed)
        assertArrayEquals(bytes, fixture.contents.getValue("/root/workspace/new/file"))
        assertTrue(fixture.writeChunks.all { it <= 65536 })
        verify(exactly = 1) { fixture.client.mkdirs("/root/workspace/new") }
        verify(exactly = 0) { fixture.client.mkdirs("/root/workspace") }
        fixture.access.writeBytes("direct", byteArrayOf(9), true)
        fixture.access.writeFile("encoded", "文本", true, Charsets.UTF_16LE)
        assertArrayEquals("文本".toByteArray(Charsets.UTF_16LE), fixture.contents.getValue("/root/workspace/encoded"))
        verify(exactly = 0) { fixture.client.mkdirs("/root/workspace") }
    }

    @Test
    fun writeExistencePermissionAndSourceErrorsAreNotReportedAsCommitSuccess() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(7))
        assertThrows(FileAlreadyExistsException::class.java) { fixture.access.writeBytes("file", byteArrayOf(1), false) }
        fixture.writeError = 30
        assertEquals(30, assertThrows(ChrootFileOperationException::class.java) { fixture.access.writeBytes("file", byteArrayOf(1), true) }.errno)
        fixture.writeError = null
        val source = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { fixture.identity = "changed" }
        }
        assertThrows(CancellationException::class.java) { fixture.access.writeStream("file", source, true) }
        assertArrayEquals(byteArrayOf(7), fixture.contents.getValue("/root/workspace/file"))
    }

    @Test
    fun mkdirsOnAnExistingMountRootIsANoOpButWorkerMountProtectionErrorsRemainVisible() {
        val fixture = Fixture()
        fixture.access.mkdirs("~/workspace")
        verify(exactly = 0) { fixture.client.mkdirs(any()) }
        fixture.deleteError = 16
        assertEquals(16, assertThrows(ChrootFileOperationException::class.java) { fixture.access.deleteRecursively("~/workspace") }.errno)
        verify(exactly = 1) { fixture.client.delete("/root/workspace", true) }
        fixture.renameError = 16
        assertEquals(16, assertThrows(ChrootFileOperationException::class.java) { fixture.access.rename("~/workspace", "/root/other") }.errno)
        verify(exactly = 0) { fixture.client.write(any(), any<InputStream>(), any()) }
    }

    @Test
    fun deletionNeverFollowsFinalLinksAndOnlyRecursiveMissingDeletionIsSilent() {
        val fixture = Fixture()
        fixture.stats["/root/workspace/link"] = symlink()
        fixture.access.delete("link")
        verify { fixture.client.delete("/root/workspace/link", false) }
        fixture.access.deleteRecursively("missing")
        assertThrows(NoSuchFileException::class.java) { fixture.access.delete("missing") }
        fixture.deleteError = 13
        assertEquals(13, assertThrows(ChrootFileOperationException::class.java) { fixture.access.deleteRecursively("missing") }.errno)
        verify(exactly = 0) { fixture.client.resolve("/root/workspace/link", any()) }
    }

    @Test
    fun renameUsesNoReplaceAndResolvedPathsAreOnlyComparisonIdentities() {
        val fixture = Fixture()
        fixture.file("/root/workspace/source", byteArrayOf(1))
        fixture.resolved["/root/workspace/source"] = "/root/workspace/identity-source"
        fixture.resolved["/root/workspace/target"] = "/root/workspace/identity-target"
        fixture.access.rename("source", "target")
        verify(exactly = 1) { fixture.client.rename("/root/workspace/source", "/root/workspace/target", false) }
        verify(exactly = 0) { fixture.client.rename("/root/workspace/identity-source", any(), any()) }
        assertEquals(1, fixture.captures)
    }

    @Test
    fun copyMoveAndRenameRejectEqualAncestorAndSymlinkAliasedDestinationsBeforeMutation() {
        val fixture = Fixture()
        fixture.file("/root/workspace/source", byteArrayOf(1))
        val pairs = listOf("source" to "source", "source" to "source/child", "source/child" to "source")
        for ((source, target) in pairs) {
            assertThrows(FilePathOverlapException::class.java) { fixture.access.copy(source, target, true) }
            assertThrows(FilePathOverlapException::class.java) { fixture.access.move(source, target, true) }
            assertThrows(FilePathOverlapException::class.java) { fixture.access.rename(source, target) }
        }
        fixture.resolved["/root/workspace/alias"] = "/root/workspace/source"
        assertThrows(FilePathOverlapException::class.java) { fixture.access.copy("source", "alias", false) }
        verify(exactly = 0) { fixture.client.rename(any(), any(), any()) }
        verify(exactly = 0) { fixture.client.write(any(), any<InputStream>(), any()) }
        verify(exactly = 0) { fixture.client.delete(any(), any()) }
    }

    @Test
    fun regularCopyUsesOneSessionAndDirectoryCopyRejectsLinksBeforeCreatingAnyTarget() {
        val fixture = Fixture()
        fixture.file("/root/workspace/source", byteArrayOf(1, 2, 3))
        fixture.access.copy("source", "target", false)
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.contents.getValue("/root/workspace/target"))
        assertEquals(1, fixture.captures)
        fixture.stats["/root/workspace/tree"] = directory()
        fixture.listings["/root/workspace/tree"] = listOf("link" to symlink())
        assertEquals(95, assertThrows(ChrootFileOperationException::class.java) { fixture.access.copy("tree", "tree-copy", false) }.errno)
        verify(exactly = 0) { fixture.client.mkdirs("/root/workspace/tree-copy") }
        verify(exactly = 0) { fixture.client.openRead("/root/workspace/tree/link") }
    }

    @Test
    fun directoryCopyPreservesRelativeDepthAndNeverDeletesAnExistingDirectoryForOverwrite() {
        val fixture = Fixture()
        fixture.stats["/root/workspace/tree"] = directory()
        fixture.stats["/root/workspace/tree/sub"] = directory()
        fixture.file("/root/workspace/tree/sub/file", byteArrayOf(1, 2))
        fixture.listings["/root/workspace/tree"] = listOf("sub" to directory())
        fixture.listings["/root/workspace/tree/sub"] = listOf("file" to fixture.stats.getValue("/root/workspace/tree/sub/file"))
        fixture.access.copy("tree", "copy", false)
        assertArrayEquals(byteArrayOf(1, 2), fixture.contents.getValue("/root/workspace/copy/sub/file"))
        fixture.stats["/root/workspace/existing"] = directory()
        assertEquals(95, assertThrows(ChrootFileOperationException::class.java) { fixture.access.copy("tree", "existing", true) }.errno)
        verify(exactly = 0) { fixture.client.delete(any(), any()) }
    }

    @Test
    fun crossDeviceMovePreservesSourceAndDoesNotStartACopy() {
        val fixture = Fixture()
        fixture.file("/root/workspace/source", byteArrayOf(1, 2, 3))
        fixture.stats["/root/workspace/directory"] = directory()
        fixture.stats["/root/workspace/link"] = symlink()
        fixture.renameError = 18
        for (name in listOf("source", "directory", "link")) {
            assertEquals(18, assertThrows(ChrootFileOperationException::class.java) {
                fixture.access.move(name, "$name-target", false)
            }.errno)
            assertTrue(fixture.stats.containsKey("/root/workspace/$name"))
            assertFalse(fixture.stats.containsKey("/root/workspace/$name-target"))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), fixture.contents.getValue("/root/workspace/source"))
        assertTrue(fixture.mutations.isEmpty())
        verify(exactly = 0) { fixture.client.openRead(any()) }
        verify(exactly = 0) { fixture.client.write(any(), any<InputStream>(), any()) }
        verify(exactly = 0) { fixture.client.delete(any(), any()) }
    }

    @Test
    fun sourceReplacementAtCrossDeviceRenameIsPreserved() {
        val fixture = Fixture()
        fixture.file("/root/workspace/source", byteArrayOf(1))
        fixture.renameError = 18
        fixture.beforeRename = {
            fixture.file("/root/workspace/source", byteArrayOf(7, 8))
            fixture.stats["/root/workspace/source"] = regular(2L).copy(inode = 99L)
        }
        assertEquals(18, assertThrows(ChrootFileOperationException::class.java) {
            fixture.access.move("source", "target", false)
        }.errno)
        assertArrayEquals(byteArrayOf(7, 8), fixture.contents.getValue("/root/workspace/source"))
        assertEquals(99L, fixture.stats.getValue("/root/workspace/source").inode)
        assertFalse(fixture.stats.containsKey("/root/workspace/target"))
        verify(exactly = 0) { fixture.client.delete(any(), any()) }
        verify(exactly = 0) { fixture.client.write(any(), any<InputStream>(), any()) }
    }

    @Test
    fun cacheCopiesUseOnlyTheCapturedAppCacheAndFailuresRemoveOnlyTheirOwnTemporaryFile() {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1, 2, 3))
        val local = fixture.access.copyToLocal("file")
        assertEquals(fixture.cache.canonicalFile, local.parentFile.canonicalFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), local.readBytes())
        val before = fixture.cache.listFiles()!!.map { it.name }.toSet()
        fixture.afterRead = { fixture.identity = "changed" }
        assertThrows(CancellationException::class.java) { fixture.access.copyToLocal("file") }
        assertEquals(before, fixture.cache.listFiles()!!.map { it.name }.toSet())
        assertTrue(local.exists())
    }

    @Test
    fun capturedExecutionCancellationClosesOnlyTheOwnedWorkerAndUnblocksAnInFlightRead() = runBlocking {
        val fixture = Fixture()
        fixture.file("/root/workspace/file", byteArrayOf(1))
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        every { fixture.client.close() } answers { closed.countDown() }
        fixture.inputFactory = { object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                check(closed.await(3, TimeUnit.SECONDS)) { "Owned worker was not released" }
                throw IOException("worker closed")
            }
            override fun close() { fixture.streamCloses++ }
        } }
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", fixture.access, null, null) {}
        val job = launch(execution + Dispatchers.IO) { fixture.access.openRead("file").use { it.read() } }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        job.cancelAndJoin()
        assertEquals(0L, closed.count)
        assertEquals(1, fixture.ownerCloses)
        assertEquals(1, fixture.streamCloses)
        assertEquals(1, fixture.captures)
    }

    @Test
    fun aRealClientCommitSizeMismatchIsNotReportedAsAWriteSuccess() {
        val responses = response(1, 1) {
            writeInt(1); writeInt(0x41ff); writeLong(0); writeLong(123); writeLong(1); writeLong(1)
        } + response(7, 2) { writeInt(7) } + response(8, 3) + response(9, 4) { writeLong(2L) }
        var transportCloses = 0
        var ownerCloses = 0
        val client = ChrootFileWorkerClient(ByteArrayInputStream(responses), ByteArrayOutputStream(), {}, { transportCloses++ })
        val access = ChrootFileAccess(captureIdentity = { "fixture" }, sessionFactory = {
            ChrootFileSession("fixture", client, temporary.root, {}, listOf("/root/workspace"), closeOwner = { ownerCloses++ })
        })
        assertThrows(ProtocolException::class.java) { access.writeBytes("file", byteArrayOf(1, 2, 3), true) }
        assertEquals(1, transportCloses)
        assertEquals(1, ownerCloses)
    }

    private inner class Fixture(
        private val writable: List<String> = listOf("/root/workspace"),
        private val readOnly: List<String> = emptyList(),
        mapPath: (String) -> String = { it }
    ) {
        val client = mockk<ChrootFileWorkerClient>()
        val cache = temporary.newFolder()
        val stats = mutableMapOf("/" to directory(), "/root" to directory(), "/root/workspace" to directory(), "/etc" to directory())
        val contents = mutableMapOf<String, ByteArray>()
        val listings = mutableMapOf<String, List<Pair<String, ChrootFileStat>>>()
        val resolved = mutableMapOf<String, String>()
        val statErrors = mutableMapOf<String, Int>()
        val listErrors = mutableMapOf<String, Int>()
        val writeChunks = mutableListOf<Int>()
        val mutations = mutableListOf<String>()
        var identity = "fixture"
        var captures = 0
        var identityQueries = 0
        var ownerCloses = 0
        var streamCloses = 0
        var transferred = 0L
        var writeError: Int? = null
        var renameError: Int? = null
        var deleteError: Int? = null
        var afterRead: () -> Unit = {}
        var afterOpen: () -> Unit = {}
        var afterWrite: () -> Unit = {}
        var beforeRename: () -> Unit = {}
        var inputFactory: (() -> InputStream)? = null
        val access: ChrootFileAccess

        init {
            every { client.close() } returns Unit
            every { client.stat(any(), any()) } answers {
                val path = firstArg<String>()
                statErrors[path]?.let { throw ChrootFileOperationException(it) }
                stats[path] ?: throw ChrootFileOperationException(2)
            }
            every { client.resolve(any(), any()) } answers {
                val path = firstArg<String>()
                resolved[path] ?: path
            }
            every { client.list(any()) } answers {
                val path = firstArg<String>()
                listErrors[path]?.let { throw ChrootFileOperationException(it) }
                listings[path] ?: emptyList()
            }
            every { client.openRead(any()) } answers {
                val path = firstArg<String>()
                val input = inputFactory?.invoke() ?: object : ByteArrayInputStream(contents[path] ?: throw ChrootFileOperationException(2)) {
                    override fun read(): Int = super.read().also { if (it >= 0) { transferred++; afterRead() } }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
                        if (it > 0) { transferred += it; afterRead() }
                    }
                    override fun close() { streamCloses++ }
                }
                afterOpen()
                input
            }
            every { client.mkdirs(any()) } answers { stats[firstArg()] = directory() }
            every { client.write(any(), any<InputStream>(), any()) } answers {
                val path = firstArg<String>()
                val input = secondArg<InputStream>()
                val overwrite = thirdArg<Boolean>()
                writeError?.let { throw ChrootFileOperationException(it) }
                if (stats.containsKey(path) && !overwrite) throw ChrootFileOperationException(17)
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) throw IOException("no progress")
                    writeChunks += count
                    output.write(buffer, 0, count)
                }
                file(path, output.toByteArray())
                mutations += "write:$path"
                afterWrite()
                output.size().toLong()
            }
            every { client.delete(any(), any()) } answers {
                val path = firstArg<String>()
                deleteError?.let { throw ChrootFileOperationException(it) }
                if (stats.remove(path) == null) throw ChrootFileOperationException(2)
                contents.remove(path)
                mutations += "delete:$path"
            }
            every { client.rename(any(), any(), any()) } answers {
                val source = firstArg<String>()
                val target = secondArg<String>()
                beforeRename()
                renameError?.let { throw ChrootFileOperationException(it) }
                if (stats.containsKey(target) && !thirdArg<Boolean>()) throw ChrootFileOperationException(17)
                stats[target] = stats.remove(source) ?: throw ChrootFileOperationException(2)
                contents.remove(source)?.let { contents[target] = it }
            }
            access = ChrootFileAccess(captureIdentity = { identityQueries++; identity }, sessionFactory = {
                captures++
                val captured = identity
                ChrootFileSession(captured, client, cache,
                    { if (identity != captured) throw CancellationException("source changed") }, writable, readOnly,
                    { ownerCloses++ })
            }, mapPath = mapPath)
        }

        fun file(path: String, bytes: ByteArray) {
            contents[path] = bytes
            stats[path] = regular(bytes.size.toLong())
        }
    }

    private fun response(opcode: Int, id: Int, body: DataOutputStream.() -> Unit = {}): ByteArray {
        val payload = ByteArrayOutputStream().also { DataOutputStream(it).use { output -> output.writeInt(0); output.body() } }.toByteArray()
        return ByteArrayOutputStream().also { DataOutputStream(it).use { output ->
            output.writeInt(0x414a4657); output.writeShort(1); output.writeShort(opcode or 0x8000)
            output.writeInt(id); output.writeInt(payload.size); output.write(payload)
        } }.toByteArray()
    }

    private fun directory() = ChrootFileStat(1, 0x41ff, 0L, 123L, 1L, 2L)
    private fun regular(size: Long = 0L, mode: Int = 0x1a4) = ChrootFileStat(2, 0x8000 or mode, size, 123L, 1L, 3L)
    private fun symlink() = ChrootFileStat(3, 0xa1ff, 1L, 123L, 1L, 4L)
}
