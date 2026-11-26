package com.aicode.feature.agent.domain.container.chroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ChrootRecoveryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = "0123456789abcdef01234567"
    private val launch = "89abcdef0123456789abcdef"
    private val rootfs = ChrootFileIdentity(5L, 19L)

    private fun file(): File = File(temporary.newFolder(), "journal.bin")

    @Test
    fun unfinishedIntentBecomesUnknownOnRestartAndCannotBeResetByLateSuccess() {
        val file = file()
        val first = ChrootRecoveryRepository(file)
        first.recordIntent(instance, launch, rootfs, "chat:private-owner", 9L)
        assertTrue(first.canStart(instance))
        assertTrue(first.blockedInstances.value.isEmpty())
        val recovered = ChrootRecoveryRepository(file)
        assertTrue(recovered.isHealthy.value)
        assertEquals(setOf(instance), recovered.blockedInstances.value)
        assertEquals(ChrootRecoveryPhase.UNKNOWN, recovered.snapshot().single().phase)
        assertEquals(ChrootCleanupResult.UNKNOWN, recovered.snapshot().single().cleanup)
        recovered.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertFalse(recovered.canStart(instance))
        assertEquals(ChrootCleanupResult.UNKNOWN, recovered.snapshot().single().cleanup)
    }

    @Test
    fun verifiedClosedRecordAllowsAnotherLaunchButNeverReplaysLaunchId() {
        val file = file()
        val repository = ChrootRecoveryRepository(file)
        repository.recordIntent(instance, launch, rootfs, "terminal:user", 3L)
        repository.recordPhase(launch, ChrootRecoveryPhase.STARTING)
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertTrue(repository.canStart(instance))
        val restarted = ChrootRecoveryRepository(file)
        assertTrue(restarted.canStart(instance))
        assertEquals(ChrootRecoveryPhase.CLOSED, restarted.snapshot().single().phase)
        try {
            restarted.recordIntent(instance, launch, rootfs, "terminal:user", 4L)
            throw AssertionError("A launch id must never be replayed")
        } catch (_: IOException) {
        }
        restarted.recordIntent(instance, "abcdef0123456789abcdef01", rootfs, "terminal:user", 4L)
        assertEquals(2, restarted.snapshot().size)
    }

    @Test
    fun concurrentLaunchesShareAnInstanceUntilAnyCleanupBecomesUnknown() {
        val repository = ChrootRecoveryRepository(file())
        val second = "abcdef0123456789abcdef01"
        repository.recordIntent(instance, launch, rootfs, "terminal:first", 3L)
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        repository.recordIntent(instance, second, rootfs, "chat:second", 3L)
        assertEquals(setOf(launch, second), repository.recordedLaunchIds.value)
        assertTrue(repository.canStart(instance))
        repository.recordCleanup(second, ChrootCleanupResult.UNKNOWN)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertFalse(repository.canStart(instance))
        assertEquals(setOf(instance), repository.blockedInstances.value)
    }

    @Test
    fun directorySyncFailureKeepsTheRepositoryFailClosedAndPreservesJournal() {
        val file = file()
        val repository = ChrootRecoveryRepository(file) { throw IOException("sync unavailable") }
        try {
            repository.recordIntent(instance, launch, rootfs, "user:owner", 1L)
            throw AssertionError("Directory sync failure must be reported")
        } catch (_: IOException) {
        }
        assertFalse(repository.isHealthy.value)
        assertTrue(file.exists())
        val recovered = ChrootRecoveryRepository(file)
        assertFalse(recovered.canStart(instance))
        assertEquals(ChrootRecoveryPhase.UNKNOWN, recovered.snapshot().single().phase)
    }

    @Test
    fun journalContainsOnlyOwnerDigestAndNoReusableApprovalOrBusinessPayload() {
        val file = file()
        val repository = ChrootRecoveryRepository(file)
        val owner = "chat:private-owner-credential-placeholder"
        repository.recordIntent(instance, launch, rootfs, owner, 13L)
        val entry = repository.snapshot().single()
        assertEquals("chat", entry.ownerKind)
        assertTrue(entry.ownerHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(rootfs, entry.rootfsIdentity)
        assertEquals(13L, entry.rootGeneration)
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains(owner))
        assertFalse(bytes.contains("private-owner"))
        assertTrue(bytes.contains(entry.ownerHash))
    }

    @Test
    fun truncatedOrInterruptedJournalFailsClosedWithoutDeletingEvidence() {
        val corrupt = file()
        corrupt.writeBytes(byteArrayOf(1, 2, 3))
        val repository = ChrootRecoveryRepository(corrupt)
        assertFalse(repository.isHealthy.value)
        assertFalse(repository.canStart(instance))
        assertEquals(3L, corrupt.length())
        val unfinished = file()
        File(unfinished.path + ".pending").writeBytes(byteArrayOf(9))
        val pending = ChrootRecoveryRepository(unfinished)
        assertFalse(pending.isHealthy.value)
        assertFalse(pending.canStart(instance))
        assertTrue(File(unfinished.path + ".pending").exists())
    }

    @Test
    fun persistenceFailureRemainsFailClosedAfterStorageBecomesWritable() {
        val parent = temporary.newFile()
        val file = File(parent, "journal.bin")
        val repository = ChrootRecoveryRepository(file)
        try {
            repository.recordIntent(instance, launch, rootfs, "user:owner", 1L)
            throw AssertionError("The write failure must be reported")
        } catch (_: IOException) {
        }
        assertFalse(repository.isHealthy.value)
        assertFalse(repository.canStart(instance))
        assertTrue(parent.isFile)
    }
}
