package com.aicode.feature.agent.domain.container.chroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ChrootRuntimeDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = "0123456789abcdef01234567"
    private val launch = "89abcdef0123456789abcdef"
    private val first = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
    private val native = ChrootRuntimeFailure.nativeError("code=runtime_failed;stage=supervision;errno=22".toByteArray())

    @Test fun preservesFirstFailureAndLateNativeEvidenceAcrossReloadWithoutClearingUnknown() {
        val file = File(temporary.newFolder(), "failures.bin")
        val store = ChrootRuntimeDiagnostics(file)
        store.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN)
        store.record(instance, launch, native, native, ChrootCleanupResult.VERIFIED)
        store.record(instance, launch,
            ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TIMEOUT, ChrootRuntimeFailureStage.STOP_DEADLINE))
        val reloaded = ChrootRuntimeDiagnostics(file)
        assertTrue(reloaded.isHealthy)
        val evidence = reloaded.latestUnknown(instance)!!
        assertEquals(first, evidence.failure)
        assertEquals(native, evidence.nativeFailure)
        assertEquals(ChrootCleanupResult.UNKNOWN, evidence.cleanup)
        assertEquals("launch=$launch;failure=TRANSPORT/HELPER_STDERR;native=runtime_failed/supervision;errno=22;cleanup=UNKNOWN",
            evidence.summary())
        assertNull(reloaded.latestUnknown("abcdef0123456789abcdef01"))
    }

    @Test fun legacyVersionOneFailureSurvivesUpgradeAndNewEvidenceWrite() {
        val file = File(temporary.newFolder(), "failures.bin")
        DataOutputStream(file.outputStream()).use {
            it.writeInt(0x414a4446); it.writeInt(1); it.writeInt(1)
            it.writeUTF(instance); it.writeUTF(launch)
            it.writeBoolean(true); it.writeUTF("TIMEOUT"); it.writeUTF("STOP_DEADLINE")
            it.writeBoolean(false); it.writeUTF("UNKNOWN")
        }
        val legacyBytes = file.readBytes()
        assertEquals(99, legacyBytes.size)
        val store = ChrootRuntimeDiagnostics(file)
        assertTrue(store.isHealthy)
        assertTrue(legacyBytes.contentEquals(file.readBytes()))
        val original = store.latestUnknown(instance)!!
        assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, original.failure!!.stage)
        assertNull(original.completionEvidence)
        assertEquals("launch=$launch;failure=TIMEOUT/STOP_DEADLINE;cleanup=UNKNOWN", original.summary())
        store.record(instance, launch, nativeFailure = native)
        val upgraded = ChrootRuntimeDiagnostics(file)
        assertTrue(upgraded.isHealthy)
        assertEquals(original.failure, upgraded.latestUnknown(instance)!!.failure)
        assertEquals(native, upgraded.latestUnknown(instance)!!.nativeFailure)
        assertNull(upgraded.latestUnknown(instance)!!.completionEvidence)
    }

    @Test fun deadlineEvidenceFreezesMissingProofWithoutLateUpdatesInventingCompletion() {
        val file = File(temporary.newFolder(), "failures.bin")
        val firstEvidence = ChrootCompletionEvidence(ChrootStopTrigger.TASK_EXIT,
            ChrootCompletionEvidence.READY or ChrootCompletionEvidence.GO or ChrootCompletionEvidence.TASK_EXIT or
                ChrootCompletionEvidence.CLEANUP_SEEN or ChrootCompletionEvidence.CLEANUP_VERIFIED or
                ChrootCompletionEvidence.STDOUT_ENDED or ChrootCompletionEvidence.STDERR_ENDED)
        val store = ChrootRuntimeDiagnostics(file)
        store.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN, completionEvidence = firstEvidence)
        store.record(instance, launch, cleanup = ChrootCleanupResult.VERIFIED,
            completionEvidence = ChrootCompletionEvidence(ChrootStopTrigger.HELPER_EXIT, ChrootCompletionEvidence.ALL_FLAGS))
        val reloaded = ChrootRuntimeDiagnostics(file)
        assertTrue(reloaded.isHealthy)
        val evidence = reloaded.latestUnknown(instance)!!
        assertEquals(firstEvidence, evidence.completionEvidence)
        assertEquals(ChrootCleanupResult.UNKNOWN, evidence.cleanup)
        assertTrue(evidence.summary().endsWith(";trigger=TASK_EXIT;proof=127;pending=HELPER_EXIT"))
        val pendingCases = mapOf(
            0 to "CLEANUP,STDOUT_EOF,STDERR_EOF,HELPER_EXIT",
            ChrootCompletionEvidence.CLEANUP_SEEN to "CLEANUP_VERIFIED,STDOUT_EOF,STDERR_EOF,HELPER_EXIT",
            ChrootCompletionEvidence.HELPER_EXITED to "CLEANUP,STDOUT_EOF,STDERR_EOF,HELPER_STATUS",
            ChrootCompletionEvidence.ALL_FLAGS to "NONE"
        )
        pendingCases.forEach { (flags, pending) ->
            assertEquals(pending, ChrootCompletionEvidence(ChrootStopTrigger.CLEANUP, flags).pending())
        }
    }

    @Test fun unknownEvidenceFlagsAreRejectedWithoutOverwritingCommittedBytes() {
        val file = File(temporary.newFolder(), "failures.bin")
        val store = ChrootRuntimeDiagnostics(file)
        store.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN,
            completionEvidence = ChrootCompletionEvidence(ChrootStopTrigger.STOP, 0))
        val malformed = file.readBytes().also { it[it.lastIndex - 1] = 2 }
        file.writeBytes(malformed)
        val reloaded = ChrootRuntimeDiagnostics(file)
        assertFalse(reloaded.isHealthy)
        assertTrue(reloaded.snapshot().isEmpty())
        assertTrue(malformed.contentEquals(file.readBytes()))
        assertTrue(runCatching { ChrootCompletionEvidence(ChrootStopTrigger.STOP, -1) }.isFailure)
        assertTrue(runCatching { ChrootCompletionEvidence(ChrootStopTrigger.STOP, 512) }.isFailure)
        assertTrue(runCatching { ChrootCompletionEvidence(ChrootStopTrigger.STOP, ChrootCompletionEvidence.CLEANUP_VERIFIED) }.isFailure)
        assertTrue(runCatching { ChrootCompletionEvidence(ChrootStopTrigger.STOP, ChrootCompletionEvidence.HELPER_STATUS_VERIFIED) }.isFailure)
    }

    @Test fun normalVerifiedTasksDoNotCreateDiagnosticFilesOrEvictFailures() {
        val file = File(temporary.newFolder(), "failures.bin")
        val store = ChrootRuntimeDiagnostics(file)
        store.record(instance, launch, cleanup = ChrootCleanupResult.VERIFIED)
        assertFalse(file.exists())
        assertTrue(store.snapshot().isEmpty())
        store.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN)
        val original = file.readBytes()
        repeat(100) { store.record(instance, "%024x".format(it), cleanup = ChrootCleanupResult.VERIFIED) }
        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals(1, store.snapshot().size)
    }

    @Test fun keepsAtMost64FailedTasksAndLessThan64KiBOnDisk() {
        val file = File(temporary.newFolder(), "failures.bin")
        val store = ChrootRuntimeDiagnostics(file)
        val evidence = ChrootCompletionEvidence(ChrootStopTrigger.TASK_EXIT, ChrootCompletionEvidence.ALL_FLAGS)
        repeat(80) { store.record(instance, "%024x".format(it), first, native, ChrootCleanupResult.UNKNOWN, evidence) }
        assertEquals(64, store.snapshot().size)
        val reloaded = ChrootRuntimeDiagnostics(file)
        assertTrue(reloaded.isHealthy)
        assertEquals((16 until 80).map { "%024x".format(it) }, reloaded.snapshot().map { it.launchId })
        assertTrue(file.length() in 12..65_536L)
        assertTrue(reloaded.snapshot().all { it.completionEvidence == evidence })
    }

    @Test fun corruptOrOversizedFilesAreUnavailableWithoutTrustingPartialEvidence() {
        for (corruption in listOf("trailing", "version", "oversized", "native")) {
            val file = File(temporary.newFolder(), "failures.bin")
            ChrootRuntimeDiagnostics(file).record(instance, launch, first, native, ChrootCleanupResult.UNKNOWN)
            when (corruption) {
                "trailing" -> file.appendBytes(byteArrayOf(0))
                "version" -> file.writeBytes(file.readBytes().also { it[7] = 3 })
                "oversized" -> file.writeBytes(ByteArray(65_537))
                "native" -> file.writeBytes(String(file.readBytes(), Charsets.ISO_8859_1)
                    .replace("supervision", "SECRETVALUE!").toByteArray(Charsets.ISO_8859_1))
            }
            val original = file.readBytes()
            val reloaded = ChrootRuntimeDiagnostics(file)
            assertFalse(corruption, reloaded.isHealthy)
            assertTrue(reloaded.snapshot().isEmpty())
            reloaded.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN)
            assertEquals(first, reloaded.latestUnknown(instance)!!.failure)
            assertTrue(original.contentEquals(file.readBytes()))
            assertFalse(reloaded.latestUnknown(instance)!!.summary().contains("SECRETVALUE"))
        }
    }

    @Test fun committedFirstFailureRemainsReadableBesideCrashPendingWithoutInventingCleanup() {
        val directory = temporary.newFolder()
        val file = File(directory, "failures.bin")
        ChrootRuntimeDiagnostics(file).record(instance, launch, first)
        val committed = file.readBytes()
        val pending = File(file.path + ".pending").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val journal = File(directory, "runtime.bin")
        ChrootRecoveryRepository(journal).recordIntent(instance, launch, ChrootFileIdentity(5, 19), "chat:fixture", 3)
        val recoveredJournal = ChrootRecoveryRepository(journal)
        assertFalse(recoveredJournal.canStart(instance))

        val restored = ChrootRuntimeDiagnostics(file)
        assertFalse(restored.isHealthy)
        val evidence = restored.latestForLaunches(instance, recoveredJournal.unknownLaunchIds(instance))!!
        assertEquals(first, evidence.failure)
        assertNull(evidence.cleanup)
        assertTrue(evidence.summary().contains("cleanup=UNRECORDED"))
        restored.record(instance, launch, nativeFailure = native)
        assertTrue(committed.contentEquals(file.readBytes()))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(pending.readBytes()))
        assertEquals(native, restored.latestForLaunches(instance, setOf(launch))!!.nativeFailure)
    }

    @Test fun pendingWriteAndSymlinksNeverOverwriteAnotherFile() {
        for (linkTarget in listOf("file", "pending")) {
            val directory = temporary.newFolder()
            val file = File(directory, "failures.bin")
            val sentinel = File(directory, "sentinel").apply { writeText("must survive") }
            Files.createSymbolicLink((if (linkTarget == "file") file else File(file.path + ".pending")).toPath(), sentinel.toPath())
            val store = ChrootRuntimeDiagnostics(file)
            assertFalse(store.isHealthy)
            store.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN)
            assertEquals("must survive", sentinel.readText())
            assertTrue(Files.isSymbolicLink((if (linkTarget == "file") file else File(file.path + ".pending")).toPath()))
        }
    }

    @Test fun writeFailureRetainsMemoryEvidenceAndDoesNotChangeRuntimeRecoveryJournal() {
        val directory = temporary.newFolder()
        val file = File(directory, "failures.bin")
        val journal = File(directory, "runtime.bin")
        val recovery = ChrootRecoveryRepository(journal)
        recovery.recordIntent(instance, launch, ChrootFileIdentity(5, 19), "chat:fixture", 3)
        recovery.recordCleanup(launch, ChrootCleanupResult.UNKNOWN)
        val journalBefore = journal.readBytes()
        val store = ChrootRuntimeDiagnostics(file) { throw IOException("private path must not be recorded") }
        store.record(instance, launch, first, cleanup = ChrootCleanupResult.UNKNOWN)
        assertFalse(store.isHealthy)
        assertEquals(first, store.latestUnknown(instance)!!.failure)
        assertTrue(journalBefore.contentEquals(journal.readBytes()))
        assertFalse(ChrootRecoveryRepository(journal).canStart(instance))
        assertFalse(store.latestUnknown(instance)!!.summary().contains("private"))
    }

    @Test fun concurrentNativeAndCleanupUpdatesPreserveBothAcrossReload() {
        val file = File(temporary.newFolder(), "failures.bin")
        val store = ChrootRuntimeDiagnostics(file)
        store.record(instance, launch, first)
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val nativeUpdate = executor.submit { check(start.await(5, TimeUnit.SECONDS)); store.record(instance, launch, nativeFailure = native) }
            val cleanupUpdate = executor.submit { check(start.await(5, TimeUnit.SECONDS)); store.record(instance, launch, cleanup = ChrootCleanupResult.UNKNOWN) }
            start.countDown()
            nativeUpdate.get(5, TimeUnit.SECONDS)
            cleanupUpdate.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        val restored = ChrootRuntimeDiagnostics(file).latestUnknown(instance)!!
        assertEquals(first, restored.failure)
        assertEquals(native, restored.nativeFailure)
        assertEquals(ChrootCleanupResult.UNKNOWN, restored.cleanup)
    }

    @Test fun invalidNativePayloadIsReducedToFixedClassificationWithoutSavingRawData() {
        val file = File(temporary.newFolder(), "failures.bin")
        val secret = "secret argv token and private stderr"
        val invalid = ChrootRuntimeFailure.nativeError(secret.toByteArray())
        val store = ChrootRuntimeDiagnostics(file)
        store.record(instance, launch, invalid, cleanup = ChrootCleanupResult.UNKNOWN)
        val restored = ChrootRuntimeDiagnostics(file).latestUnknown(instance)!!
        assertEquals(ChrootRuntimeFailureKind.INVALID_NATIVE_ERROR, restored.failure!!.kind)
        assertNull(restored.nativeFailure)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(secret))
        assertFalse(restored.summary().contains(secret))
    }
}
