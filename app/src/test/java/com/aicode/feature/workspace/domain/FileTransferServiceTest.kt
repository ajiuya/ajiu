package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CancellationException

class FileTransferServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun host(file: File, check: () -> Unit = {}): HostTransferBackend = HostTransferBackend({ relative, _ ->
        if (relative.isEmpty()) file else File(file, relative)
    }, check)

    private fun perform(source: TransferReader, target: TransferBackend, cut: Boolean = false, check: () -> Unit = {}): TransferResult {
        val version = transferVersion(source, check) ?: throw IOException("Missing fixture source")
        val previous = transferVersion(target, check)
        return TransferOperation(source, target, "source", "target", cut, check, {}).run(version, previous)
    }

    @Test
    fun emptyAndLargeFilesTransferWithoutWholeFileBuffers() {
        val parent = temporary.newFolder()
        val empty = File(parent, "empty").apply { createNewFile() }
        val emptyTarget = File(parent, "empty-copy")
        assertTrue(perform(host(empty), host(emptyTarget)) is TransferResult.Completed)
        assertEquals(0L, emptyTarget.length())
        val large = File(parent, "large")
        large.outputStream().use { out -> repeat(300) { out.write(ByteArray(TRANSFER_BUFFER_BYTES) { (it % 251).toByte() }) } }
        val requests = mutableListOf<Int>()
        val original = host(large)
        val source = object : TransferReader by original {
            override fun openRead(relative: String): InputStream {
                val input = original.openRead(relative)
                return object : InputStream() {
                    override fun read(): Int = throw AssertionError("Single byte reads are not needed")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        requests += len
                        return input.read(b, off, len)
                    }
                    override fun close() = input.close()
                }
            }
        }
        val target = File(parent, "large-copy")
        assertTrue(perform(source, host(target)) is TransferResult.Completed)
        assertEquals(large.length(), target.length())
        assertTrue(requests.isNotEmpty() && requests.all { it <= TRANSFER_BUFFER_BYTES })
        assertEquals(transferVersion(original) {}?.nodes?.first()?.digest, transferVersion(host(target)) {}?.nodes?.first()?.digest)
        assertNoStages(parent)
    }

    @Test
    fun zeroProgressShortReadAndGrowthAllFail() {
        val zero = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        assertThrows(IOException::class.java) { transferStream(zero, null, null) {} }
        assertThrows(IOException::class.java) { transferStream(byteArrayOf(1).inputStream(), null, 2) {} }
        assertThrows(IOException::class.java) { transferStream(byteArrayOf(1, 2).inputStream(), null, 1) {} }
        assertEquals(0L, transferStream(byteArrayOf().inputStream(), null, null) {}.bytes)
    }

    @Test
    fun unknownLengthUsesEofAndStillHashesAllBytes() {
        val data = ByteArray(TRANSFER_BUFFER_BYTES + 3) { (it % 123).toByte() }
        val sink = object : OutputStream() {
            var bytes = 0L
            override fun write(b: Int) { bytes++ }
            override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
        }
        val result = transferStream(data.inputStream(), sink, null) {}
        assertEquals(data.size.toLong(), result.bytes)
        assertEquals(result.bytes, sink.bytes)
        assertEquals(sha256Fingerprint(data).removePrefix("sha256:"), result.digest)
    }

    @Test
    fun stageIsExclusiveAndCloseNeverDeletesTheTarget() {
        val parent = temporary.newFolder()
        val target = File(parent, "target").apply { writeText("keep") }
        val first = host(target).createStage(TransferKind.FILE)
        val second = host(target).createStage(TransferKind.FILE)
        assertEquals(2, parent.listFiles()!!.count { it.name.startsWith(".aicode-transfer-") })
        first.writeFile("") { it.write("first".toByteArray()) }
        second.writeFile("") { it.write("second".toByteArray()) }
        first.close()
        assertEquals(1, parent.listFiles()!!.count { it.name.startsWith(".aicode-transfer-") })
        assertEquals("keep", target.readText())
        second.close()
        assertEquals("keep", target.readText())
        assertNoStages(parent)
    }

    @Test
    fun cleanupRefusesAStageWhoseOwnedDirectoryWasReplaced() {
        val parent = temporary.newFolder()
        val target = File(parent, "target")
        val stage = host(target).createStage(TransferKind.FILE)
        stage.writeFile("") { it.write(1) }
        val owned = parent.listFiles()!!.single()
        val relocated = File(parent, "original-stage")
        Files.move(owned.toPath(), relocated.toPath())
        owned.mkdir()
        File(owned, "foreign").writeText("keep")
        stage.close()
        assertEquals("keep", File(owned, "foreign").readText())
        assertTrue(File(relocated, "payload").exists())
        assertFalse(target.exists())
    }

    @Test
    fun newTargetAppearingBeforeCommitIsPreserved() {
        val parent = temporary.newFolder()
        val target = File(parent, "target")
        val stage = host(target).createStage(TransferKind.FILE)
        try {
            stage.writeFile("") { it.write("new".toByteArray()) }
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                stage.commit(false) { target.writeText("concurrent") }
            }
            assertEquals("concurrent", target.readText())
        } finally { stage.close() }
        assertNoStages(parent)
    }

    @Test
    fun failedCommitAndFailedWritePreserveSourceAndOldTarget() {
        for (duringWrite in listOf(false, true)) {
            val parent = temporary.newFolder()
            val source = File(parent, "source").apply { writeText("new") }
            val target = File(parent, "target").apply { writeText("keep") }
            val destination = host(target)
            val broken = object : TransferBackend by destination {
                override fun createStage(kind: TransferKind): TransferStage {
                    val stage = destination.createStage(kind)
                    return object : TransferStage by stage {
                        override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) {
                            checkBeforeCommit()
                            throw IOException("Atomic rename is unsupported")
                        }
                        override fun writeFile(relative: String, writer: (OutputStream) -> Unit) {
                            if (duringWrite) stage.writeFile(relative) { throw IOException("No space left") }
                            else stage.writeFile(relative, writer)
                        }
                    }
                }
            }
            assertThrows(IOException::class.java) { perform(host(source), broken, true) }
            assertEquals("new", source.readText())
            assertEquals("keep", target.readText())
            assertNoStages(parent)
        }
    }

    @Test
    fun cancellationDuringCopyCleansOwnedStageAndKeepsBothSides() {
        val parent = temporary.newFolder()
        val source = File(parent, "source").apply { writeText("new") }
        val target = File(parent, "target").apply { writeText("keep") }
        val backend = host(source)
        var copying = false
        val cancelled = object : TransferBackend by backend {
            override fun openRead(relative: String): InputStream {
                val input = backend.openRead(relative)
                return if (!copying) input else object : InputStream() {
                    override fun read(): Int = throw CancellationException("scope changed")
                    override fun read(b: ByteArray, off: Int, len: Int): Int = throw CancellationException("scope changed")
                    override fun close() = input.close()
                }
            }
        }
        val version = transferVersion(cancelled) {}!!
        val previous = transferVersion(host(target)) {}
        copying = true
        assertThrows(CancellationException::class.java) {
            TransferOperation(cancelled, host(target), "source", "target", true, {}, {}).run(version, previous)
        }
        assertEquals("new", source.readText())
        assertEquals("keep", target.readText())
        assertNoStages(parent)
    }

    @Test
    fun sourceDigestChangeWithIdenticalSizeAndTimestampIsNotAccepted() {
        val parent = temporary.newFolder()
        val source = File(parent, "source").apply { writeText("old") }
        val target = File(parent, "target").apply { writeText("keep") }
        val originalTime = source.lastModified()
        val reader = host(source)
        val version = transferVersion(reader) {}!!
        val previous = transferVersion(host(target)) {}
        source.writeText("new")
        source.setLastModified(originalTime)
        assertThrows(IOException::class.java) {
            TransferOperation(reader, host(target), "source", "target", true, {}, {}).run(version, previous)
        }
        assertEquals("new", source.readText())
        assertEquals("keep", target.readText())
        assertNoStages(parent)
    }

    @Test
    fun childLinksAreCopiedAsLinksWithoutExpandingCyclesOrOutsideTrees() {
        val parent = temporary.newFolder()
        val source = File(parent, "tree").apply { mkdir() }
        File(source, "file").writeText("content")
        val outside = temporary.newFolder()
        File(outside, "secret").writeText("outside")
        Files.createSymbolicLink(File(source, "cycle").toPath(), File(".").toPath())
        Files.createSymbolicLink(File(source, "outside").toPath(), outside.toPath())
        val target = File(parent, "copy")
        assertTrue(perform(host(source), host(target)) is TransferResult.Completed)
        assertEquals(".", Files.readSymbolicLink(File(target, "cycle").toPath()).toString())
        assertEquals(outside.toPath(), Files.readSymbolicLink(File(target, "outside").toPath()))
        assertEquals(4, transferVersion(host(target)) {}!!.nodes.size)
        assertEquals("outside", File(outside, "secret").readText())
    }

    @Test
    fun sourceIsRemovedOnlyAfterPublishedTreeHasBeenVerified() {
        val parent = temporary.newFolder()
        val source = File(parent, "tree").apply { mkdir() }
        File(source, "a").writeText("first")
        File(source, "b").writeText("second")
        val target = File(parent, "copy")
        val original = host(source)
        var removed = 0
        val checked = object : TransferBackend by original {
            override fun deleteEntry(relative: String) {
                assertEquals("first", File(target, "a").readText())
                assertEquals("second", File(target, "b").readText())
                removed++
                original.deleteEntry(relative)
            }
        }
        assertTrue(perform(checked, host(target), true) is TransferResult.Completed)
        assertEquals(3, removed)
        assertFalse(Files.exists(source.toPath(), NOFOLLOW_LINKS))
        assertNoStages(parent)
    }

    @Test
    fun partialSourceDeletionReturnsAnExplicitResultInsteadOfSuccess() {
        val parent = temporary.newFolder()
        val source = File(parent, "tree").apply { mkdir() }
        File(source, "a").writeText("first")
        File(source, "b").writeText("second")
        val target = File(parent, "copy")
        val original = host(source)
        var removed = 0
        val broken = object : TransferBackend by original {
            override fun deleteEntry(relative: String) {
                if (++removed == 2) throw IOException("Source permission denied")
                original.deleteEntry(relative)
            }
        }
        val result = perform(broken, host(target), true) as TransferResult.CopiedButSourceNotRemoved
        assertTrue(result.message.contains("Source permission denied"))
        assertEquals("first", File(source, "a").readText())
        assertFalse(File(source, "b").exists())
        assertEquals("first", File(target, "a").readText())
        assertEquals("second", File(target, "b").readText())
        assertNoStages(parent)
    }

    @Test
    fun lateCancellationAfterDeleteStartsCarriesThePartialSourceResult() {
        val parent = temporary.newFolder()
        val source = File(parent, "tree").apply { mkdir() }
        File(source, "a").writeText("first")
        File(source, "b").writeText("second")
        val target = File(parent, "copy")
        val original = host(source)
        var cancel = false
        val reader = object : TransferBackend by original {
            override fun deleteEntry(relative: String) {
                original.deleteEntry(relative)
                cancel = true
            }
        }
        val failure = assertThrows(TransferCancellationException::class.java) {
            perform(reader, host(target), true) { if (cancel) throw CancellationException("Changed workspace") }
        }
        assertTrue(failure.result.message.contains("may be partially removed"))
        assertTrue(File(source, "a").exists())
        assertFalse(File(source, "b").exists())
        assertTrue(File(target, "a").exists())
        assertTrue(File(target, "b").exists())
        assertNoStages(parent)
    }

    @Test
    fun cancellationImmediatelyAfterCommitDoesNotDeleteSourceOrClaimSuccess() {
        val parent = temporary.newFolder()
        val source = File(parent, "source").apply { writeText("content") }
        val target = File(parent, "target")
        val destination = host(target)
        var cancel = false
        val backend = object : TransferBackend by destination {
            override fun createStage(kind: TransferKind): TransferStage {
                val stage = destination.createStage(kind)
                return object : TransferStage by stage {
                    override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) {
                        stage.commit(overwrite, checkBeforeCommit)
                        cancel = true
                    }
                }
            }
        }
        val failure = assertThrows(TransferCancellationException::class.java) {
            perform(host(source), backend, true) { if (cancel) throw CancellationException("Cancelled") }
        }
        assertTrue(failure.result.message.contains("before source removal"))
        assertEquals("content", source.readText())
        assertEquals("content", target.readText())
        assertNoStages(parent)
    }

    @Test
    fun manifestRejectsExcessiveDepthAndDuplicateOrInvalidEntryNames() {
        val root = temporary.newFolder()
        var child = root
        repeat(TRANSFER_MAX_DEPTH + 1) { child = File(child, "d").apply { mkdir() } }
        assertThrows(IOException::class.java) { transferVersion(host(root)) {} }
        for (names in listOf(listOf("same", "same"), listOf("../escape"), List(TRANSFER_MAX_ENTRIES + 1) { "f$it" })) {
            val invalid = object : TransferReader by host(root) {
                override fun list(relative: String): List<String> = names
            }
            assertThrows(IOException::class.java) { transferVersion(invalid) {} }
        }
    }

    @Test
    fun crossBackendDirectoryConflictKeepsBothTrees() = runBlocking {
        val fixture = ServiceFixture()
        val source = File(fixture.left, "tree").apply { mkdir() }
        File(source, "new").writeText("new")
        val target = File(fixture.right, "tree").apply { mkdir() }
        File(target, "keep").writeText("keep")
        val result = fixture.service.transfer(fixture.execution, fixture.source("tree"), fixture.target("tree"), true) {}
        assertTrue(result is TransferResult.DirectoryConflict)
        assertEquals("new", File(source, "new").readText())
        assertEquals("keep", File(target, "keep").readText())
        assertNoStages(fixture.right)
    }

    @Test
    fun confirmationBindsWholeFileVersionsAndCanBeUsedInANewSuspendCall() = runBlocking {
        val fixture = ServiceFixture()
        val source = File(fixture.left, "source").apply { writeText("new") }
        val target = File(fixture.right, "target").apply { writeText("old") }
        val result = fixture.service.transfer(fixture.execution, fixture.source("source"), fixture.target("target"), false) {} as TransferResult.Conflict
        assertEquals(fixture.source("source"), result.ticket.source)
        assertEquals("old", target.readText())
        val completed = fixture.service.confirm(fixture.execution, result.ticket) {}
        assertTrue(completed is TransferResult.Completed)
        assertEquals("new", target.readText())
        assertEquals("new", source.readText())
        assertNoStages(fixture.right)
    }

    @Test
    fun targetDigestChangeAtTheSameSizeAndMtimeInvalidatesTicket() = runBlocking {
        val fixture = ServiceFixture()
        File(fixture.left, "source").writeText("new")
        val target = File(fixture.right, "target").apply { writeText("old") }
        val oldTime = target.lastModified()
        val ticket = (fixture.service.transfer(fixture.execution, fixture.source("source"), fixture.target("target"), false) {} as TransferResult.Conflict).ticket
        target.writeText("NEW")
        target.setLastModified(oldTime)
        assertTrue(fixture.service.confirm(fixture.execution, ticket) {} is TransferResult.Failed)
        assertEquals("NEW", target.readText())
        assertNoStages(fixture.right)
    }

    @Test
    fun replacingASymlinkTargetKeepsItsReferentAndAllowsVerifiedSourceRemoval() = runBlocking {
        val fixture = ServiceFixture()
        val source = File(fixture.left, "source").apply { writeText("new") }
        val referent = File(fixture.right, "referent").apply { writeText("keep") }
        val target = File(fixture.right, "target")
        Files.createSymbolicLink(target.toPath(), File("referent").toPath())
        val ticket = (fixture.service.transfer(fixture.execution, fixture.source("source"), fixture.target("target"), true) {} as TransferResult.Conflict).ticket
        assertTrue(fixture.service.confirm(fixture.execution, ticket) {} is TransferResult.Completed)
        assertFalse(Files.isSymbolicLink(target.toPath()))
        assertEquals("new", target.readText())
        assertEquals("keep", referent.readText())
        assertFalse(source.exists())
        assertNoStages(fixture.right)
    }

    @Test
    fun scopeRevisionChangeAndBackendAbaInvalidateConfirmation() = runBlocking {
        val fixture = ServiceFixture()
        File(fixture.left, "source").writeText("new")
        val target = File(fixture.right, "target").apply { writeText("old") }
        val revision = fixture.revision
        val ticket = (fixture.service.transfer(fixture.execution, fixture.source("source"), fixture.target("target"), false) {
            if (fixture.revision != revision) throw CancellationException("Scope revision changed")
        } as TransferResult.Conflict).ticket
        fixture.revision++
        fixture.revision++
        assertThrows(CancellationException::class.java) { runBlocking { fixture.service.confirm(fixture.execution, ticket) {} } }
        assertEquals("old", target.readText())
        assertNoStages(fixture.right)
    }

    @Test
    fun differentAuthorizedRootsForTheSamePhysicalFileCannotTransferOntoThemselves() = runBlocking {
        val fixture = ServiceFixture(samePhysicalRoot = true)
        val source = File(fixture.left, "file").apply { writeText("keep") }
        val result = fixture.service.transfer(fixture.execution, fixture.source("file"), fixture.target("file"), true) {}
        assertTrue(result is TransferResult.Failed)
        assertEquals("keep", source.readText())
        assertNoStages(fixture.left)
    }

    @Test
    fun authorizedSourceRootCanBeCopiedButCannotBeCut() = runBlocking {
        val fixture = ServiceFixture()
        File(fixture.left, "file").writeText("content")
        assertTrue(fixture.service.transfer(fixture.execution, fixture.source(""), fixture.target("copy"), false) {} is TransferResult.Completed)
        assertEquals("content", File(fixture.right, "copy/file").readText())
        assertTrue(fixture.service.transfer(fixture.execution, fixture.source(""), fixture.target("cut"), true) {} is TransferResult.Failed)
        assertTrue(File(fixture.left, "file").exists())
        assertFalse(File(fixture.right, "cut").exists())
    }

    @Test
    fun topLevelCrossBackendLinkIsExplicitlyRejectedAndPreserved() = runBlocking {
        val fixture = ServiceFixture()
        File(fixture.left, "file").writeText("content")
        Files.createSymbolicLink(File(fixture.left, "link").toPath(), File("file").toPath())
        assertTrue(fixture.service.transfer(fixture.execution, fixture.source("link"), fixture.target("link"), true) {} is TransferResult.Failed)
        assertTrue(Files.isSymbolicLink(File(fixture.left, "link").toPath()))
        assertFalse(Files.exists(File(fixture.right, "link").toPath(), NOFOLLOW_LINKS))
    }

    private fun assertNoStages(parent: File) {
        assertTrue(parent.listFiles()!!.none { it.name.startsWith(".aicode-transfer-") })
    }

    private inner class ServiceFixture(samePhysicalRoot: Boolean = false) {
        val left = temporary.newFolder()
        val right = if (samePhysicalRoot) left else temporary.newFolder()
        var revision = 0
        val leftGrant = LocalFolderGrant("left", "e1", "Left", localRootPath("left", "e1"), true)
        val rightGrant = LocalFolderGrant("right", "e1", "Right", localRootPath("right", "e1"), true)
        private val snapshot = LocalFolderSnapshot(1, listOf(leftGrant, rightGrant), listOf(
            CapturedLocalFolderRoot(leftGrant, left), CapturedLocalFolderRoot(rightGrant, right)
        )) {}
        val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "execution", mockk(), null, null, snapshot) {}
        val service = FileTransferService(FileBackendRouting(), mockk(), WorkspaceFileLock())
        fun source(child: String) = leftGrant.rootPath + if (child.isEmpty()) "" else "/$child"
        fun target(child: String) = rightGrant.rootPath + if (child.isEmpty()) "" else "/$child"
    }
}
