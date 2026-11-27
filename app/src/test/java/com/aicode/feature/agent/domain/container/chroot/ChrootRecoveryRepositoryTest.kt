package com.aicode.feature.agent.domain.container.chroot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChrootRecoveryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = "0123456789abcdef01234567"
    private val launch = "89abcdef0123456789abcdef"
    private val rootfs = ChrootFileIdentity(5L, 19L)

    private fun file(): File = File(temporary.newFolder(), "journal.bin")

    @Test
    fun unfinishedIntentBecomesUnknownOnRestartAndAllowsNewNonceWithoutRewritingHistory() {
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
        val unknown = recovered.snapshot().single()
        recovered.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        recovered.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertTrue(recovered.canStart(instance))
        assertEquals(unknown, recovered.snapshot().single())
        expectIo { recovered.recordIntent(instance, launch, rootfs, "chat:private-owner", 10L) }
        val nextLaunch = "abcdef0123456789abcdef01"
        recovered.recordIntent(instance, nextLaunch, rootfs, "chat:new-owner", 10L)
        recovered.recordPhase(nextLaunch, ChrootRecoveryPhase.RUNNING)
        recovered.recordCleanup(nextLaunch, ChrootCleanupResult.VERIFIED)
        assertEquals(unknown, recovered.snapshot().single { it.launchId == launch })
        assertEquals(ChrootRecoveryPhase.CLOSED, recovered.snapshot().single { it.launchId == nextLaunch }.phase)
        assertEquals(ChrootCleanupResult.VERIFIED, recovered.snapshot().single { it.launchId == nextLaunch }.cleanup)
        assertEquals(setOf(launch), recovered.unknownLaunchIds(instance))
        assertEquals(setOf(launch, nextLaunch), recovered.recordedLaunchIds.value)
        assertEquals(setOf(instance), recovered.blockedInstances.value)
        assertEquals(recovered.snapshot(), ChrootRecoveryRepository(file).snapshot())
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
    fun concurrentLaunchesKeepIndependentCleanupAndAllowNewNonceAfterUnknown() {
        val repository = ChrootRecoveryRepository(file())
        val second = "abcdef0123456789abcdef01"
        repository.recordIntent(instance, launch, rootfs, "terminal:first", 3L)
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        repository.recordIntent(instance, second, rootfs, "chat:second", 3L)
        assertEquals(setOf(launch, second), repository.recordedLaunchIds.value)
        assertTrue(repository.canStart(instance))
        repository.recordCleanup(second, ChrootCleanupResult.UNKNOWN)
        assertTrue(repository.canStart(instance))
        assertEquals(ChrootRecoveryPhase.RUNNING, repository.snapshot().single { it.launchId == launch }.phase)
        val third = "abcdefabcdefabcdefabcdef"
        repository.recordIntent(instance, third, rootfs, "chat:third", 3L)
        repository.recordPhase(third, ChrootRecoveryPhase.RUNNING)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertEquals(ChrootRecoveryPhase.CLOSED, repository.snapshot().single { it.launchId == launch }.phase)
        assertEquals(ChrootCleanupResult.VERIFIED, repository.snapshot().single { it.launchId == launch }.cleanup)
        assertEquals(ChrootRecoveryPhase.UNKNOWN, repository.snapshot().single { it.launchId == second }.phase)
        assertEquals(ChrootCleanupResult.UNKNOWN, repository.snapshot().single { it.launchId == second }.cleanup)
        assertEquals(ChrootRecoveryPhase.RUNNING, repository.snapshot().single { it.launchId == third }.phase)
        assertEquals(setOf(launch, second, third), repository.recordedLaunchIds.value)
        assertEquals(setOf(second), repository.unknownLaunchIds(instance))
        assertEquals(setOf(instance), repository.blockedInstances.value)
        expectIo { repository.recordIntent(instance, second, rootfs, "chat:second", 3L) }
    }

    @Test
    fun diagnosticLookupReadsThePublishedUnknownIdsWhileAnotherJournalSyncIsBlocked() {
        val pauseSync = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = ChrootRecoveryRepository(file()) {
            if (pauseSync.get()) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        repository.recordIntent(instance, launch, rootfs, "chat:first", 1)
        repository.recordCleanup(launch, ChrootCleanupResult.UNKNOWN)
        val otherInstance = "abcdef0123456789abcdef01"
        val otherLaunch = "abcdefabcdefabcdefabcdef"
        val executor = Executors.newFixedThreadPool(2)
        pauseSync.set(true)
        val writing = executor.submit { repository.recordIntent(otherInstance, otherLaunch, rootfs, "chat:second", 1) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val ids = executor.submit<Set<String>> { repository.unknownLaunchIds(instance) }.get(1, TimeUnit.SECONDS)
            assertEquals(setOf(launch), ids)
            assertTrue(repository.unknownLaunchIds(otherInstance).isEmpty())
        } finally {
            release.countDown()
            writing.get(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
        assertEquals(setOf(launch), repository.unknownLaunchIds(instance))
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertEquals(setOf(launch), repository.unknownLaunchIds(instance))
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
        assertFalse(repository.canStart(instance))
        expectIo { repository.recordIntent(instance, "abcdef0123456789abcdef01", rootfs, "user:next", 1L) }
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
        expectIo { repository.recordIntent(instance, launch, rootfs, "user:owner", 1L) }
        assertEquals(3L, corrupt.length())
        val unfinished = file()
        File(unfinished.path + ".pending").writeBytes(byteArrayOf(9))
        val pending = ChrootRecoveryRepository(unfinished)
        assertFalse(pending.isHealthy.value)
        assertFalse(pending.canStart(instance))
        expectIo { pending.recordIntent(instance, launch, rootfs, "user:owner", 1L) }
        assertTrue(File(unfinished.path + ".pending").exists())
    }

    @Test
    fun interruptedWriteRetainsEveryMainEntryReadOnlyBeforeUserRepair() {
        val journal = file()
        val originalEntries = legacyEntries(876)
        val mainBytes = legacyBytes(originalEntries)
        assertEquals(131412, mainBytes.size)
        val pendingBytes = mainBytes.copyOf(45188)
        journal.writeBytes(mainBytes)
        val pending = File(journal.path + ".pending").apply { writeBytes(pendingBytes) }

        val repository = ChrootRecoveryRepository(journal)

        assertFalse(repository.isHealthy.value)
        assertEquals(876, repository.snapshot().size)
        assertEquals(conservativeEntries(originalEntries), repository.snapshot())
        assertEquals(originalEntries.mapTo(linkedSetOf()) { it.launchId }, repository.recordedLaunchIds.value)
        assertArrayEquals(mainBytes, journal.readBytes())
        assertArrayEquals(pendingBytes, pending.readBytes())
        assertFalse(repository.canStart(originalEntries.first().instanceId))
    }

    @Test
    fun truncatedPendingRepairPreservesClosedUnknownAndOriginalEvidence() {
        val journal = file()
        val original = legacyEntries(876)
        val mainBytes = legacyBytes(original)
        val pendingBytes = mainBytes.copyOf(45188)
        journal.writeBytes(mainBytes)
        val pending = File(journal.path + ".pending").apply { writeBytes(pendingBytes) }
        val pendingKey = Files.readAttributes(pending.toPath(), BasicFileAttributes::class.java).fileKey()
        val repository = ChrootRecoveryRepository(journal)
        assertTrue(repository.canRepairInterruptedWrite.value)

        repository.repairInterruptedWriteFromUser()

        assertTrue(repository.isHealthy.value)
        assertFalse(repository.canRepairInterruptedWrite.value)
        assertFalse(pending.exists())
        assertEquals(conservativeEntries(original), repository.snapshot())
        assertTrue(repository.canStart(original.first().instanceId))
        original.filter { it.phase != ChrootRecoveryPhase.CLOSED }.forEach {
            assertTrue(repository.canStart(it.instanceId))
            assertTrue(repository.unknownLaunchIds(it.instanceId).contains(it.launchId))
        }
        assertArrayEquals(legacyBytes(conservativeEntries(original)), journal.readBytes())
        val archive = archives(journal).single()
        assertArrayEquals(mainBytes, File(archive, "main.bin").readBytes())
        assertArrayEquals(pendingBytes, File(archive, "pending.bin").readBytes())
        assertArrayEquals(pendingBytes, File(archive, "pending-original.bin").readBytes())
        assertEquals(pendingKey, Files.readAttributes(File(archive, "pending-original.bin").toPath(), BasicFileAttributes::class.java).fileKey())
        assertEquals("${sha256(mainBytes)}  main.bin\n${sha256(pendingBytes)}  pending.bin\n", File(archive, "sha256.txt").readText())
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(archive.toPath()))
        listOf(journal, File(archive, "main.bin"), File(archive, "pending.bin"), File(archive, "sha256.txt")).forEach {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(it.toPath()))
        }
        val restarted = ChrootRecoveryRepository(journal)
        assertTrue(restarted.isHealthy.value)
        assertEquals(repository.snapshot(), restarted.snapshot())
    }

    @Test
    fun corruptWrongVersionTrailingDuplicateAndMissingMainAreNotRepairable() {
        val cases = listOf(
            byteArrayOf(1, 2, 3), legacyBytes(legacyEntries(), version = 2),
            legacyBytes(legacyEntries()) + byteArrayOf(0),
            legacyBytes(listOf(legacyEntries().first(), legacyEntries().first())), null
        )
        cases.forEach { mainBytes ->
            val journal = file()
            mainBytes?.let(journal::writeBytes)
            val pending = File(journal.path + ".pending").apply { writeBytes(legacyBytes(legacyEntries())) }
            val pendingBytes = pending.readBytes()
            val repository = ChrootRecoveryRepository(journal)
            assertFalse(repository.isHealthy.value)
            assertFalse(repository.canRepairInterruptedWrite.value)
            expectIo { repository.repairInterruptedWriteFromUser() }
            assertFalse(repository.isHealthy.value)
            assertTrue(repository.snapshot().isEmpty())
            assertTrue(archives(journal).isEmpty())
            if (mainBytes == null) assertFalse(journal.exists()) else assertArrayEquals(mainBytes, journal.readBytes())
            assertArrayEquals(pendingBytes, pending.readBytes())
        }
    }

    @Test
    fun lateSuccessAndPhaseCallbacksCannotClearUnknownAfterRepair() {
        val journal = file()
        val original = legacyEntries()
        journal.writeBytes(legacyBytes(original))
        File(journal.path + ".pending").writeBytes(byteArrayOf(1))
        val repository = ChrootRecoveryRepository(journal)
        repository.repairInterruptedWriteFromUser()
        val canonical = journal.readBytes()
        original.drop(1).forEach { entry ->
            repository.recordPhase(entry.launchId, ChrootRecoveryPhase.RUNNING)
            repository.recordCleanup(entry.launchId, ChrootCleanupResult.VERIFIED)
            assertEquals(setOf(entry.launchId), repository.unknownLaunchIds(entry.instanceId))
        }
        assertEquals(conservativeEntries(original), repository.snapshot())
        assertArrayEquals(canonical, journal.readBytes())
    }

    @Test
    fun changedMainAndIdenticalReplacementAfterLoadAreRejected() {
        listOf(false, true).forEach { sameBytes ->
            val journal = file()
            val original = legacyBytes(legacyEntries())
            journal.writeBytes(original)
            val pending = File(journal.path + ".pending").apply { writeBytes(byteArrayOf(1, 2)) }
            val repository = ChrootRecoveryRepository(journal)
            assertTrue(repository.canRepairInterruptedWrite.value)
            val replacement = if (sameBytes) original else legacyBytes(legacyEntries().map { it.copy(rootGeneration = 99) })
            if (sameBytes) {
                val swapped = File(journal.parentFile, "replacement.bin").apply { writeBytes(replacement) }
                Files.move(swapped.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else journal.writeBytes(replacement)
            expectIo { repository.repairInterruptedWriteFromUser() }
            assertFalse(repository.isHealthy.value)
            assertFalse(repository.canRepairInterruptedWrite.value)
            assertArrayEquals(replacement, journal.readBytes())
            assertArrayEquals(byteArrayOf(1, 2), pending.readBytes())
            assertTrue(archives(journal).isEmpty())
        }
    }

    @Test
    fun replacementDuringEvidenceSyncIsRejectedBeforeCanonicalWrite() {
        val journal = file()
        val original = legacyBytes(legacyEntries())
        val changed = legacyBytes(legacyEntries().map { it.copy(rootGeneration = 99) })
        journal.writeBytes(original)
        val pending = File(journal.path + ".pending").apply { writeBytes(byteArrayOf(1)) }
        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (directory.name.startsWith("${journal.name}.recovery-")) journal.writeBytes(changed)
        }
        expectIo { repository.repairInterruptedWriteFromUser() }
        assertFalse(repository.isHealthy.value)
        assertFalse(repository.canRepairInterruptedWrite.value)
        assertArrayEquals(changed, journal.readBytes())
        assertArrayEquals(byteArrayOf(1), pending.readBytes())
        assertArrayEquals(original, File(archives(journal).single(), "main.bin").readBytes())
    }

    @Test
    fun mainReplacementAtFinalDirectorySyncCannotPublishHealthy() {
        val journal = file()
        journal.writeBytes(legacyBytes(legacyEntries()))
        val pending = File(journal.path + ".pending").apply { writeBytes(byteArrayOf(1)) }
        val changed = legacyBytes(legacyEntries().map { it.copy(rootGeneration = 99) })
        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (directory == journal.parentFile && !pending.exists()) journal.writeBytes(changed)
        }
        expectIo { repository.repairInterruptedWriteFromUser() }
        assertFalse(repository.isHealthy.value)
        assertFalse(repository.canRepairInterruptedWrite.value)
        assertArrayEquals(changed, journal.readBytes())
        assertEquals(conservativeEntries(legacyEntries()), repository.snapshot())
        assertArrayEquals(byteArrayOf(1), File(archives(journal).single(), "pending-original.bin").readBytes())
    }

    @Test
    fun evidenceDirectorySyncFailureLeavesOriginalFilesAndAllowsExplicitRetry() {
        val journal = file()
        val mainBytes = legacyBytes(legacyEntries())
        journal.writeBytes(mainBytes)
        val pending = File(journal.path + ".pending").apply { writeBytes(byteArrayOf(1)) }
        val fail = AtomicBoolean(true)
        val repository = ChrootRecoveryRepository(journal) { if (fail.get()) throw IOException("evidence sync unavailable") }
        expectIo { repository.repairInterruptedWriteFromUser() }
        assertFalse(repository.isHealthy.value)
        assertTrue(repository.canRepairInterruptedWrite.value)
        assertArrayEquals(mainBytes, journal.readBytes())
        assertArrayEquals(byteArrayOf(1), pending.readBytes())
        val firstArchive = archives(journal).single()
        assertArrayEquals(mainBytes, File(firstArchive, "main.bin").readBytes())
        fail.set(false)
        repository.repairInterruptedWriteFromUser()
        assertTrue(repository.isHealthy.value)
        assertEquals(2, archives(journal).size)
        assertTrue(firstArchive.isDirectory)
    }

    @Test
    fun canonicalWriteDirectorySyncFailureRemainsUnhealthyUntilExplicitRetry() {
        val journal = file()
        val original = legacyEntries()
        val mainBytes = legacyBytes(original)
        journal.writeBytes(mainBytes)
        val pending = File(journal.path + ".pending").apply { writeBytes(byteArrayOf(1, 2)) }
        val fail = AtomicBoolean(true)
        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (directory == journal.parentFile && !journal.readBytes().contentEquals(mainBytes) && fail.get()) {
                throw IOException("canonical directory sync unavailable")
            }
        }
        expectIo { repository.repairInterruptedWriteFromUser() }
        assertFalse(repository.isHealthy.value)
        assertTrue(repository.canRepairInterruptedWrite.value)
        assertEquals(conservativeEntries(original), repository.snapshot())
        assertArrayEquals(byteArrayOf(1, 2), pending.readBytes())
        assertArrayEquals(mainBytes, File(archives(journal).single(), "main.bin").readBytes())
        fail.set(false)
        repository.repairInterruptedWriteFromUser()
        assertTrue(repository.isHealthy.value)
        assertFalse(pending.exists())
        assertEquals(conservativeEntries(original), repository.snapshot())
    }

    @Test
    fun pendingArchiveSyncFailureRestoresBlockingMarkerAndAllowsRetry() {
        val journal = file()
        journal.writeBytes(legacyBytes(legacyEntries()))
        val pending = File(journal.path + ".pending").apply { writeBytes(byteArrayOf(1, 2)) }
        val fail = AtomicBoolean(true)
        val repository = ChrootRecoveryRepository(journal) {
            if (!pending.exists() && fail.get()) throw IOException("archive sync unavailable")
        }
        expectIo { repository.repairInterruptedWriteFromUser() }
        assertFalse(repository.isHealthy.value)
        assertTrue(repository.canRepairInterruptedWrite.value)
        assertArrayEquals(byteArrayOf(1, 2), pending.readBytes())
        assertArrayEquals(byteArrayOf(1, 2), File(archives(journal).single(), "pending-original.bin").readBytes())
        fail.set(false)
        repository.repairInterruptedWriteFromUser()
        assertTrue(repository.isHealthy.value)
        assertFalse(pending.exists())
    }

    @Test
    fun sameProcessFailedCleanupReloadsMainWithoutPromotingRunningLaunchToVerified() {
        val journal = file()
        val fail = AtomicBoolean(false)
        val repository = ChrootRecoveryRepository(journal) { if (fail.get()) throw IOException("sync unavailable") }
        repository.recordIntent(instance, launch, rootfs, "chat:owner", 3)
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        fail.set(true)
        expectIo { repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED) }
        assertFalse(repository.isHealthy.value)
        assertTrue(repository.canRepairInterruptedWrite.value)
        assertEquals(ChrootRecoveryPhase.UNKNOWN, repository.snapshot().single().phase)
        fail.set(false)
        repository.repairInterruptedWriteFromUser()
        assertTrue(repository.isHealthy.value)
        assertTrue(repository.canStart(instance))
        assertEquals(ChrootCleanupResult.UNKNOWN, repository.snapshot().single().cleanup)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertEquals(ChrootCleanupResult.UNKNOWN, repository.snapshot().single().cleanup)
    }

    @Test
    fun sameProcessPendingCollisionCanRecoverWithoutReconstructingRepository() {
        val journal = file()
        val repository = ChrootRecoveryRepository(journal)
        repository.recordIntent(instance, launch, rootfs, "user:owner", 3)
        val mainBytes = journal.readBytes()
        File(journal.path + ".pending").writeBytes(mainBytes.copyOf(20))
        expectIo { repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING) }
        assertFalse(repository.isHealthy.value)
        assertTrue(repository.canRepairInterruptedWrite.value)
        assertEquals(ChrootRecoveryPhase.UNKNOWN, repository.snapshot().single().phase)
        repository.repairInterruptedWriteFromUser()
        assertTrue(repository.isHealthy.value)
        assertTrue(repository.canStart(instance))
        assertEquals(setOf(launch), repository.unknownLaunchIds(instance))
    }

    @Test
    fun symlinkMainPendingAndParentNeverOverwriteTheirTargets() {
        listOf("main", "pending", "parent").forEach { target ->
            val journal = file()
            val original = legacyBytes(legacyEntries())
            val external = temporary.newFile().apply { writeBytes(original) }
            when (target) {
                "main" -> {
                    Files.createSymbolicLink(journal.toPath(), external.toPath())
                    File(journal.path + ".pending").writeBytes(byteArrayOf(1))
                }
                "pending" -> {
                    journal.writeBytes(original)
                    Files.createSymbolicLink(File(journal.path + ".pending").toPath(), external.toPath())
                }
                else -> {
                    val real = temporary.newFolder()
                    File(real, journal.name).writeBytes(original)
                    File(real, journal.name + ".pending").writeBytes(byteArrayOf(1))
                    Files.delete(journal.parentFile.toPath())
                    Files.createSymbolicLink(journal.parentFile.toPath(), real.toPath())
                }
            }
            val repository = ChrootRecoveryRepository(journal)
            assertFalse(repository.isHealthy.value)
            assertFalse(repository.canRepairInterruptedWrite.value)
            expectIo { repository.repairInterruptedWriteFromUser() }
            assertArrayEquals(original, external.readBytes())
            if (target != "main") assertArrayEquals(original, journal.readBytes())
        }
    }

    @Test
    fun oversizedMainOrPendingAndExhaustedArchiveBudgetRefuseRecovery() {
        listOf("main", "pending", "archives").forEach { obstacle ->
            val journal = file()
            journal.writeBytes(if (obstacle == "main") ByteArray(2_000_001) else legacyBytes(legacyEntries()))
            val pending = File(journal.path + ".pending").apply {
                writeBytes(if (obstacle == "pending") ByteArray(2_000_001) else byteArrayOf(1))
            }
            if (obstacle == "archives") repeat(16) {
                File(journal.parentFile, "${journal.name}.recovery-kept-$it").mkdir()
            }
            val mainBefore = journal.readBytes()
            val pendingBefore = pending.readBytes()
            val repository = ChrootRecoveryRepository(journal)
            assertFalse(repository.isHealthy.value)
            assertFalse(repository.canRepairInterruptedWrite.value)
            expectIo { repository.repairInterruptedWriteFromUser() }
            assertArrayEquals(mainBefore, journal.readBytes())
            assertArrayEquals(pendingBefore, pending.readBytes())
            assertEquals(if (obstacle == "archives") 16 else 0, archives(journal).size)
        }
    }

    @Test
    fun explicitRepairSerializesOrdinaryWritesOnTheSameGate() {
        val journal = file()
        journal.writeBytes(legacyBytes(legacyEntries()))
        File(journal.path + ".pending").writeBytes(byteArrayOf(1))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        val repository = ChrootRecoveryRepository(journal) {
            if (pauseOnce.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        val repairing = executor.submit { repository.repairInterruptedWriteFromUser() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(repository.isHealthy.value)
            assertFalse(repository.canRepairInterruptedWrite.value)
            val writing = executor.submit {
                writerStarted.countDown()
                repository.recordIntent(instance, launch, rootfs, "user:after-repair", 4)
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(writing.isDone)
            release.countDown()
            repairing.get(5, TimeUnit.SECONDS)
            writing.get(5, TimeUnit.SECONDS)
            assertTrue(repository.isHealthy.value)
            assertTrue(repository.recordedLaunchIds.value.contains(launch))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun bufferedNewWritesKeepTheVersionOneWireFormatAndPrivateMode() {
        val journal = file()
        val repository = ChrootRecoveryRepository(journal)
        repository.recordIntent(instance, launch, rootfs, "terminal:fixture", 17)
        assertArrayEquals(legacyBytes(repository.snapshot()), journal.readBytes())
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        assertArrayEquals(legacyBytes(repository.snapshot()), journal.readBytes())
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertArrayEquals(legacyBytes(repository.snapshot()), journal.readBytes())
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(journal.toPath(), NOFOLLOW_LINKS))
        val restarted = ChrootRecoveryRepository(journal)
        assertTrue(restarted.isHealthy.value)
        assertEquals(repository.snapshot(), restarted.snapshot())
    }

    private fun archives(journal: File): List<File> = journal.parentFile.listFiles()!!.filter {
        it.name.startsWith("${journal.name}.recovery-")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun expectIo(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected fail-closed IOException")
        } catch (_: IOException) {
        }
    }

    // Independent version-1 fixtures: retain the deployed wire format, including old owner fields.
    private fun legacyEntries(count: Int = 4): List<ChrootRecoveryEntry> = List(count) { index ->
        val phase = when (index % 4) {
            0 -> ChrootRecoveryPhase.CLOSED
            1 -> ChrootRecoveryPhase.INTENT
            2 -> ChrootRecoveryPhase.STARTING
            else -> ChrootRecoveryPhase.RUNNING
        }
        ChrootRecoveryEntry(
            "%024x".format(index % 4 + 1), "%024x".format(index + 1000),
            ChrootFileIdentity(5L + index, 19L + index), "user", "%064x".format(index + 1),
            9L + index, phase, if (phase == ChrootRecoveryPhase.CLOSED) ChrootCleanupResult.VERIFIED else null
        )
    }

    private fun conservativeEntries(source: List<ChrootRecoveryEntry>) = source.map { entry ->
        if (entry.phase == ChrootRecoveryPhase.CLOSED && entry.cleanup == ChrootCleanupResult.VERIFIED) entry
        else entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN)
    }

    private fun legacyBytes(entries: List<ChrootRecoveryEntry>, version: Int = 1): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(0x414a434a)
            output.writeInt(version)
            output.writeInt(entries.size)
            entries.forEach { entry ->
                output.writeUTF(entry.instanceId)
                output.writeUTF(entry.launchId)
                output.writeLong(entry.rootfsIdentity.device)
                output.writeLong(entry.rootfsIdentity.inode)
                output.writeUTF(entry.ownerKind)
                output.writeUTF(entry.ownerHash)
                output.writeLong(entry.rootGeneration)
                output.writeByte(entry.phase.ordinal)
                output.writeByte(when (entry.cleanup) {
                    null -> -1
                    ChrootCleanupResult.VERIFIED -> 0
                    ChrootCleanupResult.UNKNOWN -> 1
                })
            }
        }
        return bytes.toByteArray()
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
