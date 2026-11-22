package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions

class BuiltinDocsSyncTest {
    @get:Rule val temp = TemporaryFolder()
    private val state = BuiltinDocsSync.STATE_FILE

    private inner class Disk : BuiltinDocsSync.Backend {
        val root = temp.newFolder()
        val reads = mutableListOf<String>()
        val hashes = mutableListOf<String>()
        val stats = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        val events = mutableListOf<String>()
        var failWrite: String? = null
        var failReady = false
        var failBackup = false
        var failDelete = false
        var corruptBackup = false
        var afterWrite: (String) -> Unit = {}
        fun file(path: String) = if (path.isEmpty()) root else File(root, path)
        fun put(path: String, text: String) {
            file(path).parentFile!!.mkdirs()
            file(path).writeText(text)
        }
        fun text(path: String) = file(path).readText()
        fun reset() {
            reads.clear(); hashes.clear(); stats.clear(); writes.clear(); deletes.clear(); events.clear()
        }
        override fun type(path: String): BuiltinDocsSync.EntryType? {
            stats += path
            val local = file(path).toPath()
            return when {
                !Files.exists(local, NOFOLLOW_LINKS) -> null
                Files.isSymbolicLink(local) -> BuiltinDocsSync.EntryType.SYMLINK
                Files.isDirectory(local, NOFOLLOW_LINKS) -> BuiltinDocsSync.EntryType.DIRECTORY
                Files.isRegularFile(local, NOFOLLOW_LINKS) -> BuiltinDocsSync.EntryType.FILE
                else -> BuiltinDocsSync.EntryType.OTHER
            }
        }
        override fun readState(): ByteArray {
            reads += state
            check(file(state).length() <= BuiltinDocsSync.MAX_STATE_BYTES)
            return file(state).readBytes()
        }
        override fun fileHash(path: String): String {
            hashes += path
            check(file(path).length() <= BuiltinDocsSync.MAX_DOCUMENT_BYTES)
            return BuiltinDocsSync.sha256(file(path).readBytes())
        }
        override fun createDirectory(path: String) {
            Files.createDirectory(file(path).toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            events += "mkdir:$path"
        }
        override fun replaceAtomically(path: String, bytes: ByteArray, expectedHash: String?) {
            val target = file(path).toPath()
            check((if (Files.exists(target, NOFOLLOW_LINKS)) fileHash(path) else null) == expectedHash)
            if (path == failWrite || (path == state && failReady && bytes.toString(Charsets.UTF_8).contains("\tready\t"))) {
                throw IOException("injected staged write failure")
            }
            val staged = Files.createTempFile(root.toPath(), ".test-part-", ".part",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                Files.write(staged, bytes)
                check(Files.size(staged) == bytes.size.toLong())
                check(BuiltinDocsSync.sha256(Files.readAllBytes(staged)) == BuiltinDocsSync.sha256(bytes))
                Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(staged) }
            writes += path
            events += "write:$path"
            afterWrite(path)
        }
        override fun backupFile(path: String, backupPath: String, expectedHash: String) {
            if (failBackup) throw IOException("injected backup failure")
            val bytes = file(path).readBytes()
            check(BuiltinDocsSync.sha256(bytes) == expectedHash)
            check(file(backupPath).createNewFile())
            file(backupPath).writeBytes(if (corruptBackup) "corrupt".toByteArray() else bytes)
            events += "backup:$path"
        }
        override fun deleteFile(path: String, expectedHash: String) {
            if (failDelete) throw IOException("injected delete failure")
            check(fileHash(path) == expectedHash)
            Files.delete(file(path).toPath())
            deletes += path
            events += "delete:$path"
        }
        fun sync(vararg docs: Pair<String, String>) = BuiltinDocsSync(this).sync(BuiltinDocsSync.snapshot(linkedMapOf(*docs)))
        fun backup(path: String, content: String) = "${BuiltinDocsSync.BACKUP_DIR}/${BuiltinDocsSync.sha256(content.toByteArray())}/$path"
    }

    @Test fun firstSnapshotUploadsAllKnownDocumentsAndLeavesUnknownFiles() {
        val disk = Disk()
        disk.put("user.md", "custom")
        val result = disk.sync("guide/deep/a.md" to "A", "advanced/b.md" to "B")
        assertEquals(BuiltinDocsSync.Result(true, 2, 0), result)
        assertEquals(listOf(state, "advanced/b.md", state, "guide/deep/a.md", state, state), disk.writes)
        assertEquals("A", disk.text("guide/deep/a.md"))
        assertEquals("custom", disk.text("user.md"))
        assertTrue(disk.text(state).startsWith("aicode-docs-v1\tready\t"))
        assertTrue(disk.deletes.isEmpty())
    }

    @Test fun sameSnapshotReadsOnlyReceiptAndUploadsNoBody() {
        val disk = Disk()
        disk.sync("a.md" to "A", "b.md" to "B")
        disk.reset()
        assertEquals(BuiltinDocsSync.Result(false, 0, 0), disk.sync("b.md" to "B", "a.md" to "A"))
        assertEquals(listOf(state), disk.reads)
        assertEquals(listOf("", state), disk.stats)
        assertTrue(disk.hashes.isEmpty())
        assertTrue(disk.events.isEmpty())
    }

    @Test fun changedSnapshotUploadsOnlyChangedOrAddedPayload() {
        val disk = Disk()
        disk.sync("a.md" to "A", "b.md" to "B")
        disk.reset()
        assertEquals(BuiltinDocsSync.Result(true, 2, 0), disk.sync("a.md" to "A2", "b.md" to "B", "c.md" to "C"))
        assertEquals(listOf(state, "a.md", state, "c.md", state, state), disk.writes)
        assertEquals("B", disk.text("b.md"))
    }

    @Test fun sameSnapshotDoesNotOverwriteUserEditButNextAssetsChangeBacksItUp() {
        val disk = Disk()
        disk.sync("a.md" to "A", "b.md" to "B")
        disk.put("a.md", "user A")
        disk.reset()
        assertFalse(disk.sync("a.md" to "A", "b.md" to "B").changed)
        assertEquals("user A", disk.text("a.md"))
        assertTrue(disk.hashes.isEmpty())
        assertEquals(BuiltinDocsSync.Result(true, 2, 0), disk.sync("a.md" to "A", "b.md" to "B2"))
        assertEquals("user A", disk.text(disk.backup("a.md", "user A")))
        assertEquals("A", disk.text("a.md"))
        assertTrue(disk.events.indexOf("backup:a.md") < disk.events.indexOf("write:a.md"))
    }

    @Test fun sameSnapshotDoesNotRepairManualDeletionUntilAssetsChange() {
        val disk = Disk()
        disk.sync("a.md" to "A", "b.md" to "B")
        Files.delete(disk.file("a.md").toPath())
        assertFalse(disk.sync("a.md" to "A", "b.md" to "B").changed)
        assertFalse(disk.file("a.md").exists())
        disk.sync("a.md" to "A", "b.md" to "B2")
        assertEquals("A", disk.text("a.md"))
    }

    @Test fun removalsDeleteOnlyKnownUneditedBuiltinsAndForgetEditedOnes() {
        val disk = Disk()
        disk.sync("old.md" to "old", "edited.md" to "builtin", "keep.md" to "keep")
        disk.put("edited.md", "user")
        disk.put("unknown.md", "private")
        disk.reset()
        assertEquals(BuiltinDocsSync.Result(true, 0, 1), disk.sync("keep.md" to "keep"))
        assertEquals(listOf("old.md"), disk.deletes)
        assertEquals("user", disk.text("edited.md"))
        assertEquals("private", disk.text("unknown.md"))
        assertFalse(disk.text(state).contains("edited.md"))
    }

    @Test fun pathRenameAddsNewAndRemovesOldEvenWhenContentIsEqual() {
        val disk = Disk()
        disk.sync("old.md" to "same")
        assertEquals(BuiltinDocsSync.Result(true, 1, 1), disk.sync("guide/new.md" to "same"))
        assertFalse(disk.file("old.md").exists())
        assertEquals("same", disk.text("guide/new.md"))
    }

    @Test fun missingReceiptDoesNotInferOwnershipOfUnknownOrRemovedPaths() {
        val disk = Disk()
        disk.sync("old.md" to "old", "same.md" to "builtin")
        Files.delete(disk.file(state).toPath())
        disk.put("same.md", "user same")
        disk.reset()
        disk.sync("same.md" to "new")
        assertEquals("old", disk.text("old.md"))
        assertEquals("user same", disk.text(disk.backup("same.md", "user same")))
        assertEquals("new", disk.text("same.md"))
        assertTrue(disk.deletes.isEmpty())
    }

    @Test fun existingBackupMustMatchHashBeforeReplacingUserContent() {
        val disk = Disk()
        disk.put("a.md", "user")
        disk.put(disk.backup("a.md", "user"), "wrong backup")
        assertThrows(IllegalStateException::class.java) { disk.sync("a.md" to "builtin") }
        assertEquals("user", disk.text("a.md"))
        assertEquals("wrong backup", disk.text(disk.backup("a.md", "user")))
    }

    @Test fun newlyCreatedBackupIsReadBackAndVerifiedRatherThanTrusted() {
        val disk = Disk()
        disk.put("a.md", "user")
        disk.corruptBackup = true
        assertThrows(IllegalStateException::class.java) { disk.sync("a.md" to "builtin") }
        assertEquals("user", disk.text("a.md"))
        assertTrue(disk.text(state).contains("\tdirty\t"))
    }

    @Test fun failedBackupKeepsOriginalAndDirtyJournalForRetry() {
        val disk = Disk()
        disk.put("a.md", "user")
        disk.failBackup = true
        assertThrows(IOException::class.java) { disk.sync("a.md" to "builtin") }
        assertEquals("user", disk.text("a.md"))
        assertTrue(disk.text(state).contains("\tdirty\t"))
        disk.failBackup = false
        disk.sync("a.md" to "builtin")
        assertEquals("user", disk.text(disk.backup("a.md", "user")))
        assertEquals("builtin", disk.text("a.md"))
    }

    @Test fun failedDirtyCommitDoesNotMutateAnyDocument() {
        val disk = Disk()
        disk.sync("a.md" to "A")
        val receipt = disk.text(state)
        disk.failWrite = state
        disk.reset()
        assertThrows(IOException::class.java) { disk.sync("a.md" to "B") }
        assertEquals("A", disk.text("a.md"))
        assertEquals(receipt, disk.text(state))
        assertTrue(disk.events.isEmpty())
    }

    @Test fun failedPayloadLeavesOldFileAndRetryUsesDirtyState() {
        val disk = Disk()
        disk.sync("a.md" to "A")
        disk.failWrite = "a.md"
        assertThrows(IOException::class.java) { disk.sync("a.md" to "B") }
        assertEquals("A", disk.text("a.md"))
        assertTrue(disk.text(state).contains("\tdirty\t"))
        disk.failWrite = null
        disk.sync("a.md" to "B")
        assertEquals("B", disk.text("a.md"))
        assertFalse(disk.sync("a.md" to "B").changed)
    }

    @Test fun failedReadyCommitCanRecoverWithoutResendingAlreadyPublishedPayload() {
        val disk = Disk()
        disk.failReady = true
        assertThrows(IOException::class.java) { disk.sync("a.md" to "A") }
        assertEquals("A", disk.text("a.md"))
        assertTrue(disk.text(state).contains("\tdirty\t"))
        disk.failReady = false
        disk.reset()
        assertEquals(BuiltinDocsSync.Result(true, 0, 0), disk.sync("a.md" to "A"))
        assertEquals(listOf(state, state), disk.writes)
    }

    @Test fun interruptedUpgradeCanRollBackAndDeleteOnlyConfirmedPartialAdditions() {
        val disk = Disk()
        disk.sync("stable.md" to "A", "old.md" to "old")
        disk.failWrite = "z.md"
        assertThrows(IOException::class.java) { disk.sync("added.md" to "new", "stable.md" to "B", "z.md" to "fail") }
        assertEquals("new", disk.text("added.md"))
        assertEquals("B", disk.text("stable.md"))
        disk.failWrite = null
        disk.sync("stable.md" to "A", "old.md" to "old")
        assertEquals("A", disk.text("stable.md"))
        assertFalse(disk.file("added.md").exists())
        assertEquals("old", disk.text("old.md"))
    }

    @Test fun dirtyJournalNeverDeletesAnUnreplacedConflictingUserFile() {
        val disk = Disk()
        disk.sync("keep.md" to "K")
        disk.put("user.md", "custom")
        disk.failWrite = "a.md"
        assertThrows(IOException::class.java) { disk.sync("a.md" to "fail", "user.md" to "builtin") }
        disk.failWrite = null
        disk.sync("keep.md" to "K")
        assertEquals("custom", disk.text("user.md"))
        assertFalse("user.md" in disk.deletes)
    }

    @Test fun rollbackPreservesUnprocessedUnknownFileWhoseHashEqualsAttemptedTarget() {
        val disk = Disk()
        disk.sync("keep.md" to "K")
        disk.put("user.md", "same as target")
        disk.failWrite = "a.md"
        assertThrows(IOException::class.java) {
            disk.sync("a.md" to "fail", "keep.md" to "K", "user.md" to "same as target")
        }
        assertTrue(disk.text(state).contains("\tdirty\t"))
        assertFalse(disk.text(state).contains("\tuser.md\n"))
        disk.failWrite = null
        disk.reset()
        disk.sync("keep.md" to "K")
        assertEquals("same as target", disk.text("user.md"))
        assertTrue(disk.deletes.isEmpty())
        assertTrue(disk.events.none { it == "backup:user.md" || it == "delete:user.md" })
    }

    @Test fun matchingUnknownFileIsNotGrantedDirtyOwnershipJustByBeingVisited() {
        val disk = Disk()
        disk.sync("keep.md" to "K")
        disk.put("a-user.md", "matches B")
        disk.failWrite = "z.md"
        assertThrows(IOException::class.java) {
            disk.sync("a-user.md" to "matches B", "keep.md" to "K", "z.md" to "fail")
        }
        assertFalse(disk.text(state).contains("\ta-user.md\n"))
        disk.failWrite = null
        disk.sync("keep.md" to "K")
        assertEquals("matches B", disk.text("a-user.md"))
        assertFalse("a-user.md" in disk.deletes)
    }

    @Test fun publishedFileWithFailedOwnershipReceiptIsPreservedUntilSuccessfulReadyAdoptsIt() {
        val disk = Disk()
        disk.sync("keep.md" to "K")
        disk.afterWrite = { if (it == "added.md") disk.failWrite = state }
        assertThrows(IOException::class.java) { disk.sync("added.md" to "B", "keep.md" to "K") }
        assertEquals("B", disk.text("added.md"))
        assertFalse(disk.text(state).contains("\tadded.md\n"))
        disk.afterWrite = {}
        disk.failWrite = null
        disk.reset()
        disk.sync("keep.md" to "K")
        assertEquals("B", disk.text("added.md"))
        assertTrue(disk.deletes.isEmpty())
        assertEquals(BuiltinDocsSync.Result(true, 0, 0), disk.sync("added.md" to "B", "keep.md" to "K"))
        assertTrue(disk.text(state).contains("\tready\t"))
        assertEquals(BuiltinDocsSync.Result(true, 0, 1), disk.sync("keep.md" to "K"))
        assertFalse(disk.file("added.md").exists())
    }

    @Test fun repeatedInterruptionsRememberBothOldAndNewHashesAndAllPendingPaths() {
        val disk = Disk()
        disk.sync("a.md" to "A")
        disk.failWrite = "z.md"
        assertThrows(IOException::class.java) { disk.sync("a.md" to "B", "b.md" to "B", "z.md" to "fail") }
        assertThrows(IOException::class.java) { disk.sync("c.md" to "C", "z.md" to "fail") }
        disk.failWrite = null
        disk.sync("a.md" to "A")
        assertEquals("A", disk.text("a.md"))
        assertFalse(disk.file("b.md").exists())
        assertFalse(disk.file("c.md").exists())
        assertFalse(disk.text(state).contains("z.md"))
    }

    @Test fun failedDeleteIsRetriedFromDirtyJournal() {
        val disk = Disk()
        disk.sync("old.md" to "old")
        disk.failDelete = true
        assertThrows(IOException::class.java) { disk.sync("new.md" to "new") }
        assertTrue(disk.file("old.md").exists())
        disk.failDelete = false
        disk.sync("new.md" to "new")
        assertFalse(disk.file("old.md").exists())
    }

    @Test fun emptySnapshotIsANoopEvenWithExistingFilesOrDamagedReceipt() {
        val disk = Disk()
        disk.sync("old.md" to "old")
        disk.put(state, "broken")
        disk.reset()
        assertEquals(BuiltinDocsSync.Result(false, 0, 0), disk.sync())
        assertEquals("old", disk.text("old.md"))
        assertEquals("broken", disk.text(state))
        assertTrue(disk.stats.isEmpty())
        assertTrue(disk.events.isEmpty())
    }

    @Test fun corruptReceiptFailsClosedBeforeAnyWriteOrDelete() {
        val disk = Disk()
        disk.sync("a.md" to "A")
        val valid = disk.text(state)
        val hash = "0".repeat(64)
        listOf("partial", valid.replace("ready", "invalid"), valid.replace(valid.split('\t')[2].substringBefore('\n'), hash),
            "aicode-docs-v1\tdirty\t$hash\n$hash\t../x.md\n", valid + "$hash\ta.md\n",
            "aicode-docs-v1\tdirty\t$hash\n$hash,$hash\ta.md\n"
        ).forEach { corrupt ->
            disk.put(state, corrupt)
            disk.reset()
            assertThrows(IllegalArgumentException::class.java) { disk.sync("b.md" to "B") }
            assertTrue(disk.events.isEmpty())
            assertEquals("A", disk.text("a.md"))
        }
    }

    @Test fun malformedUtf8ReceiptIsNotDecodedLossily() {
        val disk = Disk()
        disk.file(state).writeBytes(byteArrayOf(0xc3.toByte(), 0x28, 0x0a))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { disk.sync("a.md" to "A") }
        assertTrue(disk.events.isEmpty())
    }

    @Test fun symlinkedStateDocumentParentOrBackupNeverGetsFollowed() {
        for (boundary in listOf(state, "guide", "guide/a.md", BuiltinDocsSync.BACKUP_DIR)) {
            val disk = Disk()
            val outside = temp.newFolder()
            val path = disk.file(boundary)
            path.parentFile!!.mkdirs()
            if (boundary == BuiltinDocsSync.BACKUP_DIR) disk.put("guide/a.md", "user")
            Files.createSymbolicLink(path.toPath(), outside.toPath())
            assertThrows(IllegalStateException::class.java) { disk.sync("guide/a.md" to "A") }
            assertEquals(0, outside.list()!!.size)
            assertTrue(disk.deletes.isEmpty())
        }
    }

    @Test fun cancellationAfterFirstPayloadLeavesDirtyAndDoesNotReportSuccess() {
        val disk = Disk()
        var cancelled = false
        disk.afterWrite = { if (it == "a.md") cancelled = true }
        val snapshot = BuiltinDocsSync.snapshot(mapOf("a.md" to "A", "b.md" to "B"))
        assertThrows(CancellationException::class.java) {
            BuiltinDocsSync(disk) { if (cancelled) throw CancellationException("fixture cancel") }.sync(snapshot)
        }
        assertTrue(disk.text(state).contains("\tdirty\t"))
        assertFalse(disk.file("b.md").exists())
        disk.afterWrite = {}
        assertEquals(BuiltinDocsSync.Result(true, 1, 0), BuiltinDocsSync(disk).sync(snapshot))
    }

    @Test fun cancellationAfterReadyPublicationStillCannotReturnSuccess() {
        val disk = Disk()
        var cancelled = false
        disk.afterWrite = { if (it == state && disk.text(state).contains("\tready\t")) cancelled = true }
        assertThrows(CancellationException::class.java) {
            BuiltinDocsSync(disk) { if (cancelled) throw CancellationException("fixture boundary cancel") }
                .sync(BuiltinDocsSync.snapshot(mapOf("a.md" to "A")))
        }
        assertTrue(disk.text(state).contains("\tready\t"))
        assertFalse(disk.sync("a.md" to "A").changed)
    }

    @Test fun snapshotFingerprintIncludesPathAndIsIndependentOfInputOrder() {
        val first = BuiltinDocsSync.snapshot(linkedMapOf("a.md" to "same", "b.md" to "B"))
        assertEquals(first.fingerprint, BuiltinDocsSync.snapshot(linkedMapOf("b.md" to "B", "a.md" to "same")).fingerprint)
        assertNotEquals(first.fingerprint, BuiltinDocsSync.snapshot(mapOf("renamed.md" to "same", "b.md" to "B")).fingerprint)
    }

    @Test fun pathTraversalShellCharactersAndReservedNamesAreRejected() {
        listOf("../x.md", "/x.md", "guide/../../x.md", "guide//x.md", "./x.md", "guide/./x.md", "C:/x.md",
            "guide\\x.md", "x\n.md", "x\t.md", "x\u0000.md", "x\u0085.md", "bad;.md", "$(id).md", "`id`.md",
            "a'b.md", "x\".md", "*.md", "not-markdown.txt", "", ".builtin-docs-state-v1/x.md",
            "${BuiltinDocsSync.BACKUP_DIR}/x.md", "${BuiltinDocsSync.STAGING_DIR}/x.md", "x".repeat(256) + ".md"
        ).forEach { path ->
            assertThrows("path=$path", IllegalArgumentException::class.java) { BuiltinDocsSync.snapshot(mapOf(path to "bad")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuiltinDocsSync.snapshot(mapOf("a.md" to "A", "a.md/b.md" to "B"))
        }
    }

    @Test fun snapshotAndJournalBoundsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            BuiltinDocsSync.snapshot((0..BuiltinDocsSync.MAX_DOCUMENTS).associate { "$it.md" to "" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            BuiltinDocsSync.snapshot(mapOf("large.md" to "a".repeat(BuiltinDocsSync.MAX_DOCUMENT_BYTES + 1)))
        }
        val disk = Disk()
        disk.put("a.md", "user")
        val hash = "0".repeat(64)
        disk.put(state, "aicode-docs-v1\tdirty\t$hash\n" +
            (0..BuiltinDocsSync.MAX_TRACKED_FILES).joinToString("") { "$hash\t$it.md\n" })
        disk.reset()
        assertThrows(IllegalArgumentException::class.java) { disk.sync("a.md" to "A") }
        assertEquals("user", disk.text("a.md"))
        assertTrue(disk.events.isEmpty())
    }
}
