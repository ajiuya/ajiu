package com.aicode.feature.workspace.domain.remote

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncIndexJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dir get() = File(temporary.root, "index").apply { mkdirs() }
    private fun file(suffix: String) = File(dir, "mount.$suffix")
    private val first = FileFingerprint(10, 100, "a")
    private val second = FileFingerprint(20, 200, "b")

    private class FaultIo : SyncIndexIo() {
        var appendFailure = false
        var truncateFailure = false
        var failAtomicSuffix: String? = null
        var writes = 0
        override fun atomicWrite(target: File, bytes: ByteArray) {
            writes++
            if (target.extension == failAtomicSuffix) throw IOException("injected atomic publication failure")
            super.atomicWrite(target, bytes)
        }
        override fun append(journal: File, payload: ByteArray) {
            writes++
            if (appendFailure) {
                FileOutputStream(journal, true).use { it.write(byteArrayOf(0, 0, 0)); it.fd.sync() }
                throw IOException("injected partial append failure")
            }
            super.append(journal, payload)
        }
        override fun truncate(journal: File, length: Long) {
            if (truncateFailure) throw IOException("injected rollback failure")
            super.truncate(journal, length)
        }
    }

    @Test fun legacyMapIsReadWithoutWritingAndMigrationNeverOverwritesOriginalBytes() {
        val old = """{"entries":{"size":10,"mtimeMs":100,"sha256":"a"},"sequence":{"size":20,"mtimeMs":200,"sha256":"b"}}"""
        file("json").writeText(old)
        val store = SyncIndexStore(dir)
        assertEquals(mapOf("entries" to first, "sequence" to second), store.load("mount"))
        assertFalse(file("snapshot").exists())
        store.applyChanges("mount", mapOf("new" to first))
        assertEquals(old, file("json").readText())
        assertEquals(setOf("entries", "sequence", "new"), SyncIndexStore(dir).load("mount").keys)
    }

    @Test fun deltaReplaySupportsUpsertDeleteAndEmptyReplacement() {
        val store = SyncIndexStore(dir)
        store.save("mount", mapOf("a" to first, "b" to second))
        store.applyChanges("mount", mapOf("a" to null, "c" to second))
        assertEquals(mapOf("b" to second, "c" to second), SyncIndexStore(dir).load("mount"))
        store.save("mount", emptyMap())
        assertTrue(SyncIndexStore(dir).load("mount").isEmpty())
    }

    @Test fun zeroOpsNeitherMigratesLegacyNorWritesAnEmptyJournalFrame() {
        val io = FaultIo()
        file("json").writeText("""{"a":{"size":10,"mtimeMs":100,"sha256":"a"}}""")
        val store = SyncIndexStore(dir, io)
        store.save("mount", mapOf("a" to first))
        store.applyChanges("mount", mapOf("missing" to null))
        assertEquals(0, io.writes)
        assertFalse(file("snapshot").exists())
        store.applyChanges("mount", mapOf("b" to second))
        val length = file("journal").length()
        val writes = io.writes
        store.applyChanges("mount", mapOf("b" to second))
        assertEquals(length, file("journal").length())
        assertEquals(writes, io.writes)
    }

    @Test fun failedAppendDoesNotPublishMemoryAndRollsBackBeforeTheNextTransaction() {
        val io = FaultIo()
        val store = SyncIndexStore(dir, io)
        store.save("mount", mapOf("a" to first))
        val durable = file("journal").readBytes()
        io.appendFailure = true
        assertThrows(IOException::class.java) { store.applyChanges("mount", mapOf("b" to second)) }
        assertEquals(mapOf("a" to first), store.load("mount"))
        assertArrayEquals(durable, file("journal").readBytes())
        assertEquals(mapOf("a" to first), SyncIndexStore(dir).load("mount"))
        io.appendFailure = false
        store.applyChanges("mount", mapOf("c" to second))
        assertEquals(setOf("a", "c"), SyncIndexStore(dir).load("mount").keys)
    }

    @Test fun failedRollbackPreventsAppendingBehindATornRecordUntilRepairSucceeds() {
        val io = FaultIo()
        val store = SyncIndexStore(dir, io)
        store.save("mount", mapOf("a" to first))
        io.appendFailure = true; io.truncateFailure = true
        assertThrows(IOException::class.java) { store.applyChanges("mount", mapOf("b" to second)) }
        val torn = file("journal").readBytes()
        io.appendFailure = false
        assertThrows(IOException::class.java) { store.applyChanges("mount", mapOf("c" to second)) }
        assertArrayEquals(torn, file("journal").readBytes())
        assertEquals(mapOf("a" to first), store.load("mount"))
        io.truncateFailure = false
        store.applyChanges("mount", mapOf("c" to second))
        assertEquals(setOf("a", "c"), SyncIndexStore(dir).load("mount").keys)
    }

    @Test fun failedLegacyMigrationLeavesTheLegacyStateAndFileUntouchedAndCanRetry() {
        val old = """{"a":{"size":10,"mtimeMs":100,"sha256":"a"}}"""
        file("json").writeText(old)
        val io = FaultIo().apply { failAtomicSuffix = "snapshot" }
        val store = SyncIndexStore(dir, io)
        assertThrows(IOException::class.java) { store.applyChanges("mount", mapOf("b" to second)) }
        assertEquals(mapOf("a" to first), store.load("mount"))
        assertEquals(old, file("json").readText())
        assertFalse(file("journal").exists())
        io.failAtomicSuffix = null
        store.applyChanges("mount", mapOf("b" to second))
        assertEquals(setOf("a", "b"), SyncIndexStore(dir).load("mount").keys)
    }

    @Test fun compactionProducesAnAtomicSnapshotAndRotatesJournalWithoutLosingDeletes() {
        val store = SyncIndexStore(dir, compactAfterFrames = 2)
        store.save("mount", mapOf("a" to first))
        store.applyChanges("mount", mapOf("a" to null, "b" to second))
        assertEquals(0L, file("journal").length())
        assertEquals(mapOf("b" to second), SyncIndexStore(dir).load("mount"))
        store.applyChanges("mount", mapOf("c" to first))
        assertEquals(setOf("b", "c"), SyncIndexStore(dir).load("mount").keys)
    }

    @Test fun failedCompactionKeepsTheAlreadyDurableBatchReadable() {
        val io = FaultIo()
        val store = SyncIndexStore(dir, io, compactAfterFrames = 1)
        store.save("mount", mapOf("a" to first))
        io.failAtomicSuffix = "snapshot"
        store.applyChanges("mount", mapOf("b" to second))
        assertEquals(setOf("a", "b"), store.load("mount").keys)
        assertEquals(setOf("a", "b"), SyncIndexStore(dir).load("mount").keys)
        io.failAtomicSuffix = null
        store.applyChanges("mount", mapOf("c" to first))
        assertEquals(0L, file("journal").length())
    }

    @Test fun snapshotPublishedBeforeFailedRotationReplaysOldFramesOnlyOnce() {
        val io = FaultIo()
        val store = SyncIndexStore(dir, io, compactAfterFrames = 2)
        store.save("mount", mapOf("a" to first))
        io.failAtomicSuffix = "journal"
        store.applyChanges("mount", mapOf("a" to null, "b" to second))
        assertTrue(file("journal").length() > 0)
        assertEquals(mapOf("b" to second), SyncIndexStore(dir).load("mount"))
        io.failAtomicSuffix = null
        store.applyChanges("mount", mapOf("c" to first))
        assertEquals(setOf("b", "c"), SyncIndexStore(dir).load("mount").keys)
    }

    @Test fun partialTailIsNotTrustedOrDestroyedOnReadAndCanBeRecoveredBeforeAppend() {
        SyncIndexStore(dir).save("mount", mapOf("a" to first))
        FileOutputStream(file("journal"), true).use { it.write(byteArrayOf(0, 1, 2)) }
        val damaged = file("journal").readBytes()
        val store = SyncIndexStore(dir)
        assertEquals(mapOf("a" to first), store.load("mount"))
        assertTrue(store.needsReconcile("mount"))
        assertArrayEquals(damaged, file("journal").readBytes())
        store.applyChanges("mount", mapOf("recovered" to second))
        assertEquals(mapOf("a" to first, "recovered" to second), SyncIndexStore(dir).load("mount"))
    }

    @Test fun badChecksumNeverPublishesAnUnverifiedRecord() {
        SyncIndexStore(dir).save("mount", mapOf("a" to first))
        RandomAccessFile(file("journal"), "rw").use { input ->
            input.seek(4); val checksum = input.readInt(); input.seek(4); input.writeInt(checksum xor 1)
        }
        val damaged = file("journal").readBytes()
        val store = SyncIndexStore(dir)
        assertTrue(store.load("mount").isEmpty())
        assertTrue(store.needsReconcile("mount"))
        assertArrayEquals(damaged, file("journal").readBytes())
    }

    @Test fun validCrcWithSequenceGapStillInvalidatesTheIndex() {
        SyncIndexStore(dir).save("mount", mapOf("a" to first))
        val payload = """{"sequence":3,"changes":{"bad":{"size":1,"mtimeMs":1}}}""".toByteArray()
        DataOutputStream(FileOutputStream(file("journal"), true)).use {
            it.writeInt(payload.size); it.writeInt(CRC32().apply { update(payload) }.value.toInt()); it.write(payload)
        }
        val store = SyncIndexStore(dir)
        assertEquals(mapOf("a" to first), store.load("mount"))
        assertTrue(store.needsReconcile("mount"))
    }

    @Test fun corruptSnapshotDoesNotResurrectTheStaleLegacyFileOrReplayWithoutABaseline() {
        file("json").writeText("""{"stale":{"size":10,"mtimeMs":100}}""")
        val store = SyncIndexStore(dir)
        store.applyChanges("mount", mapOf("stale" to null, "new" to second))
        val snapshot = file("snapshot").readText().replace("crc\":", "crc\":9")
        file("snapshot").writeText(snapshot)
        val journal = file("journal").readBytes()
        val broken = SyncIndexStore(dir)
        assertTrue(broken.load("mount").isEmpty())
        assertThrows(IllegalStateException::class.java) { broken.save("mount", mapOf("x" to first)) }
        assertArrayEquals(journal, file("journal").readBytes())
        assertEquals(snapshot, file("snapshot").readText())
    }

    @Test fun unknownBadLegacyDataStaysUntouchedAndCannotBecomeASyncedFingerprint() {
        val bad = "{invalid legacy json"
        file("json").writeText(bad)
        val store = SyncIndexStore(dir)
        assertTrue(store.load("mount").isEmpty())
        assertThrows(IllegalStateException::class.java) { store.applyChanges("mount", mapOf("x" to first)) }
        assertEquals(bad, file("json").readText())
        assertFalse(file("snapshot").exists())
    }

    @Test fun partialTailRetainsTheDurableObservationAndSuccessfulPrefixAcrossRepair() {
        val store = SyncIndexStore(dir)
        store.initializeObservationBaseline("mount", mapOf("unchanged" to first, "offline-change" to second))
        store.applyChanges("mount", mapOf("synced" to first))
        FileOutputStream(file("journal"), true).use { it.write(byteArrayOf(0, 0, 0, 20, 1)) }
        val recovered = SyncIndexStore(dir)
        assertEquals(mapOf("synced" to first), recovered.load("mount"))
        assertEquals(mapOf("unchanged" to first, "offline-change" to second), recovered.observationBaseline("mount"))
        recovered.applyChanges("mount", mapOf("new" to second))
        val restarted = SyncIndexStore(dir)
        assertEquals(setOf("synced", "new"), restarted.load("mount").keys)
        assertEquals(mapOf("unchanged" to first, "offline-change" to second), restarted.observationBaseline("mount"))
    }

    @Test fun explicitClearRecoversAnUnwritableCorruptSnapshotWithoutChangingTheLegacyFile() {
        val legacy = """{"old":{"size":10,"mtimeMs":100}}"""
        file("json").writeText(legacy)
        file("snapshot").writeText("invalid snapshot")
        val store = SyncIndexStore(dir)
        assertTrue(store.load("mount").isEmpty())
        assertThrows(IllegalStateException::class.java) { store.applyChanges("mount", mapOf("x" to first)) }
        store.clear("mount")
        assertTrue(SyncIndexStore(dir).load("mount").isEmpty())
        store.applyChanges("mount", mapOf("x" to first))
        assertEquals(mapOf("x" to first), SyncIndexStore(dir).load("mount"))
        assertEquals(legacy, file("json").readText())
    }

    @Test fun observationBaselineSurvivesRestartButNeverCountsAsASuccessfulSync() {
        val store = SyncIndexStore(dir)
        store.initializeObservationBaseline("mount", mapOf("existing" to first))
        assertTrue(store.load("mount").isEmpty())
        val reloaded = SyncIndexStore(dir)
        assertEquals(mapOf("existing" to first), reloaded.observationBaseline("mount"))
        reloaded.applyChanges("mount", mapOf("existing" to first))
        assertEquals(mapOf("existing" to first), SyncIndexStore(dir).load("mount"))
        assertEquals(emptyMap<String, FileFingerprint>(), SyncIndexStore(dir).observationBaseline("mount"))
    }

    @Test fun failedBaselinePublicationDoesNotPublishTheObservationInMemory() {
        val io = FaultIo().apply { failAtomicSuffix = "snapshot" }
        val store = SyncIndexStore(dir, io)
        assertThrows(IOException::class.java) { store.initializeObservationBaseline("mount", mapOf("existing" to first)) }
        assertEquals(null, store.observationBaseline("mount"))
        io.failAtomicSuffix = null
        store.initializeObservationBaseline("mount", mapOf("existing" to first))
        assertNotNull(SyncIndexStore(dir).observationBaseline("mount"))
    }
}
