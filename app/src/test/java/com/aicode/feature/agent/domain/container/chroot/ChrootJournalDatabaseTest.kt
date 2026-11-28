package com.aicode.feature.agent.domain.container.chroot

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ChrootJournalDatabaseTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = nonce(1)
    private val launch = nonce(1_000)
    private val rootfs = ChrootFileIdentity(5L, 19L)

    @Test
    @Config(sdk = [26])
    fun sdkTwentySixLifecyclePersistsCleanupAndRestartWithoutLookupChangingStoreBytes() {
        val journal = journal()
        val repository = ChrootRecoveryRepository(journal)
        assertHealthy(repository)
        repository.recordIntent(instance, launch, rootfs, "terminal:sdk26", 9L)
        repository.recordPhase(launch, ChrootRecoveryPhase.STARTING)
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        val closed = repository.snapshot().single()
        assertEquals(ChrootRecoveryPhase.CLOSED, closed.phase)
        assertEquals(ChrootCleanupResult.VERIFIED, closed.cleanup)
        assertEquals(rootfs, closed.rootfsIdentity)
        assertEquals(9L, closed.rootGeneration)
        val marker = journal.readBytes()
        val committedDatabase = database(journal).readBytes()
        assertTrue(repository.containsLaunchId(launch))
        assertFalse(repository.containsLaunchId(nonce(2_000)))
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(committedDatabase, database(journal).readBytes())

        val restarted = ChrootRecoveryRepository(journal)
        assertHealthy(restarted)
        assertEquals(listOf(closed), restarted.snapshot())
        assertTrue(restarted.containsLaunchId(launch))
        assertFalse(restarted.containsLaunchId(nonce(2_000)))
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(committedDatabase, database(journal).readBytes())
        val unfinished = nonce(1_001)
        restarted.recordIntent(instance, unfinished, rootfs, "chat:sdk26-unfinished", 10L)
        restarted.recordPhase(unfinished, ChrootRecoveryPhase.RUNNING)
        val beforeRestart = restarted.snapshot()

        val recovered = ChrootRecoveryRepository(journal)
        assertHealthy(recovered)
        assertEquals(conservative(beforeRestart), recovered.snapshot())
        assertEquals(setOf(unfinished), recovered.unknownLaunchIds(instance))
        val recoveredDatabase = database(journal).readBytes()
        assertTrue(recovered.containsLaunchId(launch))
        assertTrue(recovered.containsLaunchId(unfinished))
        assertFalse(recovered.containsLaunchId(nonce(2_000)))
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(recoveredDatabase, database(journal).readBytes())
        recovered.recordCleanup(unfinished, ChrootCleanupResult.VERIFIED)
        assertEquals(conservative(beforeRestart), recovered.snapshot())
        assertFalse(pending(journal).exists())
    }

    @Test
    fun newStoreIsLazyAndRestartMakesEveryUnfinishedPhasePermanentlyUnknown() {
        val journal = journal()
        val repository = ChrootRecoveryRepository(journal)
        assertHealthy(repository)
        assertFalse(repository.containsLaunchId(launch))
        assertFalse(journal.exists())
        assertFalse(database(journal).exists())
        assertFalse(backup(journal).exists())
        assertFalse(pending(journal).exists())

        val unfinished = listOf(ChrootRecoveryPhase.INTENT, ChrootRecoveryPhase.STARTING, ChrootRecoveryPhase.RUNNING)
        unfinished.forEachIndexed { index, phase ->
            val id = nonce(1_000 + index)
            repository.recordIntent(instance, id, rootfs, "chat:owner-$index", 9L + index)
            if (phase != ChrootRecoveryPhase.INTENT) repository.recordPhase(id, phase)
            assertStoredState(journal, id, phase, null)
        }
        val alreadyUnknown = nonce(1_003)
        repository.recordIntent(instance, alreadyUnknown, rootfs, "terminal:uncertain", 13L)
        repository.recordCleanup(alreadyUnknown, ChrootCleanupResult.UNKNOWN)
        val closed = nonce(1_004)
        repository.recordIntent(instance, closed, rootfs, "terminal:closed", 14L)
        repository.recordCleanup(closed, ChrootCleanupResult.VERIFIED)
        val expected = conservative(repository.snapshot())
        val marker = journal.readBytes()
        assertEquals(34, marker.size)
        assertEquals(setOf(alreadyUnknown), repository.unknownLaunchIds(instance))

        val restarted = ChrootRecoveryRepository(journal)
        assertHealthy(restarted)
        assertEquals(expected, restarted.snapshot())
        val unknownIds = (1_000..1_003).mapTo(linkedSetOf(), ::nonce)
        assertEquals(unknownIds, restarted.unknownLaunchIds(instance))
        assertEquals(setOf(instance), restarted.blockedInstances.value)
        assertEquals(expected.mapTo(linkedSetOf()) { it.launchId }, restarted.recordedLaunchIds())
        unknownIds.forEach { id ->
            assertTrue(restarted.containsLaunchId(id))
            assertStoredState(journal, id, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
            restarted.recordPhase(id, ChrootRecoveryPhase.STARTING)
            restarted.recordPhase(id, ChrootRecoveryPhase.RUNNING)
            restarted.recordCleanup(id, ChrootCleanupResult.VERIFIED)
        }
        assertEquals(expected, restarted.snapshot())
        assertStoredState(journal, closed, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            restarted.recordIntent(instance, launch, rootfs, "chat:replayed", 99L)
        }
        assertHealthy(restarted)
        val next = nonce(2_000)
        restarted.recordIntent(instance, next, rootfs, "chat:new-nonce", 99L)
        restarted.recordCleanup(next, ChrootCleanupResult.VERIFIED)
        assertEquals(unknownIds, restarted.unknownLaunchIds(instance))
        assertEquals(expected, restarted.snapshot().dropLast(1))
        assertArrayEquals(marker, journal.readBytes())
        assertFalse(pending(journal).exists())
        val again = ChrootRecoveryRepository(journal)
        assertHealthy(again)
        assertEquals(restarted.snapshot(), again.snapshot())
    }

    @Test
    fun maximumVersionOneHistoryMigratesAndAcceptsLaunchesBeyondTenThousand() {
        val journal = journal()
        val entries = List(10_000, ::closedEntry)
        val original = legacyBytes(entries)
        assertTrue(original.size <= 2_000_000)
        journal.writeBytes(original)

        val repository = ChrootRecoveryRepository(journal)
        assertHealthy(repository)
        assertEquals(entries, repository.snapshot())
        assertArrayEquals(original, backup(journal).readBytes())
        assertEquals(sha256(original), sqlString(journal, "SELECT source_hash FROM metadata WHERE singleton=1"))
        assertEquals(markerStoreId(journal), sqlString(journal, "SELECT store_id FROM metadata WHERE singleton=1"))
        assertEquals(1L, sqlLong(journal, "PRAGMA user_version"))
        val marker = journal.readBytes()
        assertEquals(34, marker.size)
        listOf(journal, database(journal), backup(journal)).forEach { file ->
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS))
        }

        val newIds = listOf(nonce(20_000), nonce(20_001))
        newIds.forEach { id ->
            repository.recordIntent(instance, id, rootfs, "chat:after-legacy-limit", 20L)
            repository.recordCleanup(id, ChrootCleanupResult.VERIFIED)
            assertStoredState(journal, id, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        }
        val restarted = ChrootRecoveryRepository(journal)
        assertHealthy(restarted)
        assertEquals(10_002L, rowCount(journal))
        assertEquals(entries, restarted.snapshot().take(entries.size))
        assertEquals(newIds, restarted.snapshot().takeLast(2).map { it.launchId })
        assertEquals(entries.mapTo(linkedSetOf()) { it.launchId } + newIds, restarted.recordedLaunchIds())
        listOf(entries.first().launchId, entries.last().launchId, newIds.last()).forEach { id ->
            assertTrue(restarted.containsLaunchId(id))
            expectFailure(ChrootJournalFailureReason.REPLAY) {
                restarted.recordIntent(instance, id, rootfs, "chat:replay", 21L)
            }
            assertHealthy(restarted)
        }
        assertEquals(10_002L, rowCount(journal))
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(original, backup(journal).readBytes())
        assertFalse(pending(journal).exists())
    }

    @Test
    fun databaseReplacementDuringMarkerPublicationCannotBeAcceptedAsTheMigratedStore() {
        val foreignJournal = journal()
        val foreignLaunch = nonce(900_000)
        val foreignRepository = ChrootRecoveryRepository(foreignJournal)
        foreignRepository.recordIntent(instance, foreignLaunch, rootfs, "chat:foreign-store", 99L)
        foreignRepository.recordCleanup(foreignLaunch, ChrootCleanupResult.VERIFIED)
        assertHealthy(foreignRepository)
        val foreignStore = markerStoreId(foreignJournal)
        val foreignBytes = database(foreignJournal).readBytes()
        val journal = journal()
        val original = legacyBytes(listOf(closedEntry(0)))
        journal.writeBytes(original)
        val replaced = AtomicBoolean(false)
        val retained = File(journal.parentFile, "migration-original.sqlite")

        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (directory == journal.parentFile && journal.length() == 34L && replaced.compareAndSet(false, true)) {
                assertFalse(foreignStore == markerStoreId(journal))
                Files.move(database(journal).toPath(), retained.toPath(), StandardCopyOption.ATOMIC_MOVE)
                Files.copy(database(foreignJournal).toPath(), database(journal).toPath())
            }
            syncDirectory(directory)
        }

        assertTrue(replaced.get())
        assertUnhealthy(repository, ChrootJournalFailureReason.IDENTITY, repairable = false)
        expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(launch) }
        expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(foreignLaunch) }
        expectFailure(ChrootJournalFailureReason.IDENTITY) {
            repository.recordIntent(instance, nonce(2_000), rootfs, "chat:must-not-adopt-replacement", 100L)
        }
        assertTrue(retained.isFile)
        assertEquals(foreignStore, sqlString(journal, "SELECT store_id FROM metadata WHERE singleton=1"))
        assertStoredState(journal, foreignLaunch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertEquals(1L, rowCount(journal))
        assertArrayEquals(foreignBytes, database(journal).readBytes())
        assertArrayEquals(original, backup(journal).readBytes())
        assertFalse(pending(journal).exists())
        assertTrue(archives(journal).isEmpty())
        assertUnhealthy(ChrootRecoveryRepository(journal), ChrootJournalFailureReason.IDENTITY, repairable = false)
        assertArrayEquals(foreignBytes, database(journal).readBytes())
    }

    @Test
    fun legacyEntryAndByteLimitsRemainInputValidationWithoutCreatingANewDatabase() {
        val cases = listOf(
            legacyBytes(List(10_001, ::closedEntry)),
            legacyBytes(emptyList()).copyOf(2_000_001)
        )
        cases.forEach { bytes ->
            val journal = journal()
            journal.writeBytes(bytes)
            val repository = ChrootRecoveryRepository(journal)
            assertUnhealthy(repository, ChrootJournalFailureReason.CORRUPT, repairable = false)
            expectFailure(ChrootJournalFailureReason.CORRUPT) {
                repository.recordIntent(instance, launch, rootfs, "chat:refused", 9L)
            }
            expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
            assertArrayEquals(bytes, journal.readBytes())
            assertFalse(database(journal).exists())
            assertFalse(backup(journal).exists())
            assertFalse(pending(journal).exists())
            assertTrue(archives(journal).isEmpty())
        }
    }

    @Test
    fun oneTransactionHundredThousandClosedFixtureKeepsExactReplayHistoryAndSmallWriteEvidence() {
        val journal = journal()
        journal.writeBytes(legacyBytes(emptyList()))
        assertHealthy(ChrootRecoveryRepository(journal))
        val marker = journal.readBytes()
        val legacyBackup = backup(journal).readBytes()
        insertClosedFixture(journal, 100_000)
        assertEquals(100_000L, rowCount(journal))
        assertTrue(database(journal).length() > 2_000_000L)
        val observedPending = mutableListOf<ByteArray>()
        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (pending(journal).exists()) {
                observedPending += pending(journal).readBytes().also { bytes ->
                    assertTrue("Normal pending must stay below 4 KiB", bytes.size in 1 until 4_096)
                }
            }
            syncDirectory(directory)
        }
        assertHealthy(repository)
        assertTrue(observedPending.isEmpty())
        listOf(1_000, 51_000, 100_999).forEach { index -> assertTrue(repository.containsLaunchId(nonce(index))) }
        assertFalse(repository.containsLaunchId(nonce(200_000)))
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            repository.recordIntent(instance, nonce(1_000), rootfs, "chat:earliest-replay", 99L)
        }
        assertHealthy(repository)
        assertEquals(100_000L, rowCount(journal))
        assertTrue(observedPending.isEmpty())

        val next = nonce(200_000)
        repository.recordIntent(instance, next, rootfs, "chat:after-hundred-thousand", 99L)
        assertStoredState(journal, next, ChrootRecoveryPhase.INTENT, null)
        repository.recordCleanup(next, ChrootCleanupResult.VERIFIED)
        assertStoredState(journal, next, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertEquals(listOf("INTENT", "UPDATE"), observedPending.map(::pendingOperation))
        assertTrue(observedPending.all { it.size < 4_096 })
        assertEquals(34L, journal.length())
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(legacyBackup, backup(journal).readBytes())
        assertFalse(pending(journal).exists())
        assertEquals(100_001L, rowCount(journal))
        assertEquals(100_001L, sqlLong(journal, "SELECT COUNT(*) FROM launches WHERE phase=3 AND cleanup=0"))

        val restarted = ChrootRecoveryRepository(journal)
        assertHealthy(restarted)
        assertTrue(restarted.unknownLaunchIds(instance).isEmpty())
        assertTrue(restarted.blockedInstances.value.isEmpty())
        assertTrue(restarted.containsLaunchId(nonce(1_000)))
        assertTrue(restarted.containsLaunchId(next))
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            restarted.recordIntent(instance, nonce(1_000), rootfs, "chat:earliest-after-restart", 100L)
        }
        assertHealthy(restarted)
        assertEquals(100_001L, rowCount(journal))
        assertArrayEquals(marker, journal.readBytes())
    }

    @Test
    fun hotSQLiteCleanupRollbackKeepsPendingBarrierUntilExplicitRepairForcesUnknown() {
        val source = journal()
        source.writeBytes(legacyBytes(emptyList()))
        val originalRepository = ChrootRecoveryRepository(source)
        assertHealthy(originalRepository)
        insertClosedFixture(source, 2_048)
        val interruptedLaunch = nonce(200_000)
        originalRepository.recordIntent(instance, interruptedLaunch, rootfs, "chat:hot-cleanup", 99L)
        originalRepository.recordPhase(interruptedLaunch, ChrootRecoveryPhase.RUNNING)
        val originalEntries = originalRepository.snapshot()
        val running = originalEntries.single { it.launchId == interruptedLaunch }
        val interrupted = pendingBytes(markerStoreId(source), "UPDATE", running)
        val beforeTransaction = database(source).readBytes()
        val target = journal()
        Files.copy(source.toPath(), target.toPath())
        Files.copy(backup(source).toPath(), backup(target).toPath())
        pending(target).writeBytes(interrupted)
        val pendingKey = fileKey(pending(target))
        val targetMarker = target.readBytes()

        withDatabase(source, write = true) { db ->
            db.execSQL("PRAGMA cache_size=1")
            db.beginTransaction()
            try {
                db.execSQL("UPDATE launches SET phase=3,cleanup=0 WHERE launch_id=?", arrayOf(interruptedLaunch))
                db.execSQL("UPDATE launches SET owner_hash=?", arrayOf("f".repeat(64)))
                db.rawQuery("SELECT phase,cleanup FROM launches WHERE launch_id=?", arrayOf(interruptedLaunch)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                    assertEquals(0, cursor.getInt(1))
                }
                copyHotRollbackFixture(source, target, beforeTransaction)
            } finally {
                db.endTransaction()
            }
        }
        assertStoredState(source, interruptedLaunch, ChrootRecoveryPhase.RUNNING, null)
        assertArrayEquals(beforeTransaction, database(source).readBytes())
        val spilledDatabase = database(target).readBytes()
        val targetDatabaseKey = fileKey(database(target))
        assertTrue(rollbackJournal(target).isFile)

        val restarted = ChrootRecoveryRepository(target)

        assertUnhealthy(restarted, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertEquals(conservative(originalEntries), restarted.snapshot())
        assertEquals(setOf(interruptedLaunch), restarted.unknownLaunchIds(instance))
        assertStoredState(target, interruptedLaunch, ChrootRecoveryPhase.RUNNING, null)
        assertFalse(rollbackJournal(target).exists())
        assertFalse(spilledDatabase.contentEquals(database(target).readBytes()))
        assertArrayEquals(beforeTransaction, database(target).readBytes())
        assertEquals(targetDatabaseKey, fileKey(database(target)))
        assertArrayEquals(targetMarker, target.readBytes())
        assertArrayEquals(interrupted, pending(target).readBytes())
        assertEquals(pendingKey, fileKey(pending(target)))
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { restarted.containsLaunchId(interruptedLaunch) }
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { restarted.recordCleanup(interruptedLaunch, ChrootCleanupResult.VERIFIED) }

        restarted.repairInterruptedWriteFromUser()

        assertHealthy(restarted)
        assertTrue(restarted.containsLaunchId(interruptedLaunch))
        assertEquals(conservative(originalEntries), restarted.snapshot())
        assertStoredState(target, interruptedLaunch, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        assertEquals(2_049L, rowCount(target))
        assertFalse(pending(target).exists())
        assertFalse(failed(target).exists())
        assertArrayEquals(targetMarker, target.readBytes())
        val archive = archives(target).single()
        assertArrayEquals(interrupted, File(archive, "pending.bin").readBytes())
        assertArrayEquals(interrupted, File(archive, "pending-original.bin").readBytes())
        assertEquals(pendingKey, fileKey(File(archive, "pending-original.bin")))
        restarted.recordCleanup(interruptedLaunch, ChrootCleanupResult.VERIFIED)
        assertStoredState(target, interruptedLaunch, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
    }

    @Test
    fun hotSQLiteVersionOneImportRollsBackAndRemigratesThePreservedSourceWithoutAppPending() {
        val source = journal()
        val entries = List(2_048, ::closedEntry) + closedEntry(2_048).copy(phase = ChrootRecoveryPhase.RUNNING, cleanup = null)
        val original = legacyBytes(entries)
        source.writeBytes(original)
        backup(source).writeBytes(original)
        val target = journal()
        target.writeBytes(original)
        backup(target).writeBytes(original)
        val backupKey = fileKey(backup(target))
        val uncommittedStore = nonce(800_000)
        val sourceHash = sha256(original)
        val params = SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setJournalMode("DELETE").setSynchronousMode("EXTRA").build()

        SQLiteDatabase.openDatabase(database(source), params).use { db ->
            db.execSQL("VACUUM")
            val beforeTransaction = database(source).readBytes()
            db.execSQL("PRAGMA cache_size=1")
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE metadata(singleton INTEGER PRIMARY KEY,store_id TEXT NOT NULL,source_hash TEXT NOT NULL)")
                db.execSQL(
                    "CREATE TABLE launches(sequence INTEGER PRIMARY KEY,launch_id TEXT NOT NULL UNIQUE,instance_id TEXT NOT NULL," +
                        "device INTEGER NOT NULL,inode INTEGER NOT NULL,owner_kind TEXT NOT NULL,owner_hash TEXT NOT NULL," +
                        "root_generation INTEGER NOT NULL,phase INTEGER NOT NULL,cleanup INTEGER)"
                )
                db.execSQL("CREATE INDEX unresolved_launches ON launches(sequence) WHERE phase!=3")
                db.execSQL("INSERT INTO metadata(singleton,store_id,source_hash) VALUES(1,?,?)", arrayOf(uncommittedStore, sourceHash))
                entries.forEach { entry ->
                    db.execSQL(
                        "INSERT INTO launches(launch_id,instance_id,device,inode,owner_kind,owner_hash,root_generation,phase,cleanup) " +
                            "VALUES(?,?,?,?,?,?,?,?,?)",
                        arrayOf(entry.launchId, entry.instanceId, entry.rootfsIdentity.device, entry.rootfsIdentity.inode,
                            entry.ownerKind, entry.ownerHash, entry.rootGeneration, entry.phase.ordinal, entry.cleanup?.ordinal)
                    )
                }
                db.execSQL("PRAGMA user_version=1")
                db.rawQuery("SELECT COUNT(*) FROM launches", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2_049L, cursor.getLong(0))
                }
                copyHotRollbackFixture(source, target, beforeTransaction)
            } finally {
                db.endTransaction()
            }
            assertArrayEquals(beforeTransaction, database(source).readBytes())
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
        assertArrayEquals(original, source.readBytes())
        assertArrayEquals(original, backup(source).readBytes())
        assertTrue(rollbackJournal(target).isFile)
        assertFalse(pending(target).exists())
        val targetDatabaseKey = fileKey(database(target))

        val repository = ChrootRecoveryRepository(target)

        assertHealthy(repository)
        assertEquals(conservative(entries), repository.snapshot())
        assertEquals(2_049L, rowCount(target))
        assertEquals(sourceHash, sqlString(target, "SELECT source_hash FROM metadata WHERE singleton=1"))
        assertEquals(markerStoreId(target), sqlString(target, "SELECT store_id FROM metadata WHERE singleton=1"))
        assertFalse(uncommittedStore == markerStoreId(target))
        assertEquals(targetDatabaseKey, fileKey(database(target)))
        assertArrayEquals(original, backup(target).readBytes())
        assertEquals(backupKey, fileKey(backup(target)))
        assertFalse(rollbackJournal(target).exists())
        assertFalse(pending(target).exists())
        assertFalse(failed(target).exists())
        assertTrue(archives(target).isEmpty())
        val unfinished = entries.last().launchId
        assertEquals(setOf(unfinished), repository.unknownLaunchIds(instance))
        assertTrue(repository.containsLaunchId(entries.first().launchId))
        assertTrue(repository.containsLaunchId(unfinished))
        assertStoredState(target, unfinished, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        repository.recordCleanup(unfinished, ChrootCleanupResult.VERIFIED)
        assertEquals(conservative(entries), repository.snapshot())
        val restarted = ChrootRecoveryRepository(target)
        assertHealthy(restarted)
        assertEquals(conservative(entries), restarted.snapshot())
        assertEquals(2_049L, rowCount(target))
        assertArrayEquals(original, backup(target).readBytes())
    }

    @Test
    fun hotSQLiteUncommittedIntentRollbackRequiresRepairToInsertUnknownAndRejectReplay() {
        val source = journal()
        source.writeBytes(legacyBytes(emptyList()))
        assertHealthy(ChrootRecoveryRepository(source))
        insertClosedFixture(source, 2_048)
        val entry = closedEntry(200_000).copy(phase = ChrootRecoveryPhase.INTENT, cleanup = null)
        assertEquals(0L, sqlLong(source, "SELECT COUNT(*) FROM launches WHERE launch_id='${entry.launchId}'"))
        val interrupted = pendingBytes(markerStoreId(source), "INTENT", entry)
        val beforeTransaction = database(source).readBytes()
        val target = journal()
        Files.copy(source.toPath(), target.toPath())
        Files.copy(backup(source).toPath(), backup(target).toPath())
        pending(target).writeBytes(interrupted)
        val pendingKey = fileKey(pending(target))
        val marker = target.readBytes()

        withDatabase(source, write = true) { db ->
            db.execSQL("PRAGMA cache_size=1")
            db.beginTransaction()
            try {
                db.execSQL(
                    "INSERT INTO launches(launch_id,instance_id,device,inode,owner_kind,owner_hash,root_generation,phase,cleanup) " +
                        "VALUES(?,?,?,?,?,?,?,0,NULL)",
                    arrayOf(entry.launchId, entry.instanceId, entry.rootfsIdentity.device, entry.rootfsIdentity.inode,
                        entry.ownerKind, entry.ownerHash, entry.rootGeneration)
                )
                db.execSQL("UPDATE launches SET owner_hash=? WHERE launch_id!=?", arrayOf("f".repeat(64), entry.launchId))
                db.rawQuery("SELECT COUNT(*) FROM launches WHERE launch_id=?", arrayOf(entry.launchId)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1L, cursor.getLong(0))
                }
                copyHotRollbackFixture(source, target, beforeTransaction)
            } finally {
                db.endTransaction()
            }
        }
        assertEquals(0L, sqlLong(source, "SELECT COUNT(*) FROM launches WHERE launch_id='${entry.launchId}'"))
        assertArrayEquals(beforeTransaction, database(source).readBytes())
        val targetDatabaseKey = fileKey(database(target))
        assertTrue(rollbackJournal(target).isFile)

        val restarted = ChrootRecoveryRepository(target)

        assertUnhealthy(restarted, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertEquals(setOf(entry.launchId), restarted.unknownLaunchIds(instance))
        val unknown = entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN)
        assertEquals(unknown, restarted.snapshot().last())
        assertEquals(2_048L, rowCount(target))
        assertEquals(0L, sqlLong(target, "SELECT COUNT(*) FROM launches WHERE launch_id='${entry.launchId}'"))
        assertFalse(rollbackJournal(target).exists())
        assertArrayEquals(beforeTransaction, database(target).readBytes())
        assertEquals(targetDatabaseKey, fileKey(database(target)))
        assertArrayEquals(marker, target.readBytes())
        assertArrayEquals(interrupted, pending(target).readBytes())
        assertEquals(pendingKey, fileKey(pending(target)))
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { restarted.containsLaunchId(entry.launchId) }

        restarted.repairInterruptedWriteFromUser()

        assertHealthy(restarted)
        assertEquals(2_049L, rowCount(target))
        assertTrue(restarted.containsLaunchId(entry.launchId))
        assertStoredState(target, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        assertEquals(unknown, restarted.snapshot().last())
        assertEquals(setOf(entry.launchId), restarted.unknownLaunchIds(instance))
        assertFalse(pending(target).exists())
        assertFalse(failed(target).exists())
        val archive = archives(target).single()
        assertArrayEquals(interrupted, File(archive, "pending-original.bin").readBytes())
        assertEquals(pendingKey, fileKey(File(archive, "pending-original.bin")))
        restarted.recordCleanup(entry.launchId, ChrootCleanupResult.VERIFIED)
        assertStoredState(target, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            restarted.recordIntent(instance, entry.launchId, rootfs, "chat:hot-intent-replay", 100L)
        }
        val again = ChrootRecoveryRepository(target)
        assertHealthy(again)
        assertEquals(2_049L, rowCount(target))
        assertEquals(unknown, again.snapshot().last())
        assertArrayEquals(marker, target.readBytes())
    }

    @Test
    fun cleanupCommittedClosedBeforeDirectorySyncFailureRequiresExplicitRepairToUnknown() {
        val journal = journal()
        val failAfterCommit = AtomicBoolean(false)
        val observedClosedCommit = AtomicBoolean(false)
        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (directory == journal.parentFile && !pending(journal).exists() && failAfterCommit.compareAndSet(true, false)) {
                assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
                observedClosedCommit.set(true)
                throw IOException("injected post-COMMIT directory sync failure")
            }
            syncDirectory(directory)
        }
        val closed = nonce(1_001)
        repository.recordIntent(instance, closed, rootfs, "terminal:already-closed", 7L)
        repository.recordCleanup(closed, ChrootCleanupResult.VERIFIED)
        repository.recordIntent(instance, launch, rootfs, "chat:ambiguous-cleanup", 8L)
        repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        val otherUnfinished = nonce(1_002)
        repository.recordIntent(instance, otherUnfinished, rootfs, "chat:other-unfinished", 9L)
        val marker = journal.readBytes()
        failAfterCommit.set(true)

        expectFailure(ChrootJournalFailureReason.SYNC) { repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED) }

        assertTrue(observedClosedCommit.get())
        assertUnhealthy(repository, ChrootJournalFailureReason.SYNC, repairable = true)
        assertEquals(setOf(launch, otherUnfinished), repository.unknownLaunchIds(instance))
        assertEquals(ChrootRecoveryPhase.UNKNOWN, repository.snapshot().single { it.launchId == launch }.phase)
        assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        val pendingBytes = pending(journal).readBytes()
        val pendingKey = fileKey(pending(journal))
        val committedDatabase = database(journal).readBytes()
        assertTrue(pendingBytes.size < 4_096)
        expectFailure(ChrootJournalFailureReason.SYNC) { repository.containsLaunchId(launch) }
        expectFailure(ChrootJournalFailureReason.SYNC) {
            repository.recordIntent(instance, nonce(2_000), rootfs, "chat:blocked", 10L)
        }

        val restarted = ChrootRecoveryRepository(journal)
        assertUnhealthy(restarted, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertEquals(setOf(launch, otherUnfinished), restarted.unknownLaunchIds(instance))
        assertArrayEquals(pendingBytes, pending(journal).readBytes())
        assertEquals(pendingKey, fileKey(pending(journal)))
        assertArrayEquals(committedDatabase, database(journal).readBytes())
        assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertStoredState(journal, otherUnfinished, ChrootRecoveryPhase.INTENT, null)
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { restarted.recordCleanup(launch, ChrootCleanupResult.VERIFIED) }

        restarted.repairInterruptedWriteFromUser()

        assertHealthy(restarted)
        assertEquals(3L, rowCount(journal))
        assertFalse(pending(journal).exists())
        assertStoredState(journal, closed, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertStoredState(journal, launch, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        assertStoredState(journal, otherUnfinished, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        assertEquals(setOf(launch, otherUnfinished), restarted.unknownLaunchIds(instance))
        assertEquals(setOf(instance), restarted.blockedInstances.value)
        val archive = archives(journal).single()
        assertArrayEquals(pendingBytes, File(archive, "pending.bin").readBytes())
        assertArrayEquals(pendingBytes, File(archive, "pending-original.bin").readBytes())
        assertEquals(pendingKey, fileKey(File(archive, "pending-original.bin")))
        assertEquals("${sha256(pendingBytes)}  pending.bin\n", File(archive, "sha256.txt").readText())
        val repaired = restarted.snapshot()
        restarted.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
        restarted.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertEquals(repaired, restarted.snapshot())
        assertArrayEquals(marker, journal.readBytes())
        val again = ChrootRecoveryRepository(journal)
        assertHealthy(again)
        assertEquals(repaired, again.snapshot())
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            again.recordIntent(instance, launch, rootfs, "chat:repaired-replay", 11L)
        }
    }

    @Test
    fun markerReplacementAtFinalCleanupSyncRestoresPendingAndRestartCannotTrustClosed() {
        listOf(false, true).forEach { throwAfterReplacement ->
            val journal = journal()
            val replaceOnFinalSync = AtomicBoolean(false)
            val replaced = AtomicBoolean(false)
            val repository = ChrootRecoveryRepository(journal) { directory ->
                if (directory == journal.parentFile && !pending(journal).exists() && replaceOnFinalSync.compareAndSet(true, false)) {
                    assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
                    val sameBytes = journal.readBytes()
                    val replacement = File(journal.parentFile, "replacement-marker.bin").apply { writeBytes(sameBytes) }
                    Files.move(replacement.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    replaced.set(true)
                    if (throwAfterReplacement) throw IOException("injected final sync failure after marker replacement")
                }
                syncDirectory(directory)
            }
            repository.recordIntent(instance, launch, rootfs, "chat:marker-replacement", 9L)
            repository.recordPhase(launch, ChrootRecoveryPhase.RUNNING)
            val marker = journal.readBytes()
            val markerKey = fileKey(journal)
            replaceOnFinalSync.set(true)
            val reason = if (throwAfterReplacement) ChrootJournalFailureReason.SYNC else ChrootJournalFailureReason.IDENTITY

            expectFailure(reason) { repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED) }

            assertTrue(replaced.get())
            assertUnhealthy(repository, reason, repairable = false)
            assertEquals(setOf(launch), repository.unknownLaunchIds(instance))
            assertArrayEquals(marker, journal.readBytes())
            assertFalse(markerKey == fileKey(journal))
            assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
            val restoredPending = pending(journal).readBytes()
            assertTrue(restoredPending.size < 4_096)
            assertEquals("UPDATE", pendingOperation(restoredPending))
            expectFailure(reason) { repository.containsLaunchId(launch) }

            val restarted = ChrootRecoveryRepository(journal)
            assertUnhealthy(restarted, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
            assertEquals(setOf(launch), restarted.unknownLaunchIds(instance))
            assertArrayEquals(restoredPending, pending(journal).readBytes())
            assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
            restarted.repairInterruptedWriteFromUser()
            assertHealthy(restarted)
            assertStoredState(journal, launch, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
            restarted.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
            assertStoredState(journal, launch, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
            assertFalse(pending(journal).exists())
            assertEquals(1L, rowCount(journal))
        }
    }

    @Test
    fun repairDirectorySyncFailureRestoresPendingAndAnotherExplicitRepairIsRequired() {
        val journal = journal()
        val failAfterArchiveMove = AtomicBoolean(false)
        closedStore(journal)
        val entry = closedEntry(1)
        val original = pendingBytes(markerStoreId(journal), "INTENT", entry.copy(phase = ChrootRecoveryPhase.INTENT, cleanup = null))
        pending(journal).writeBytes(original)
        val recovering = ChrootRecoveryRepository(journal) { directory ->
            if (directory.name.startsWith("${journal.name}.recovery-") && !pending(journal).exists() &&
                failAfterArchiveMove.compareAndSet(true, false)
            ) throw IOException("injected archive directory sync failure")
            syncDirectory(directory)
        }
        assertUnhealthy(recovering, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        failAfterArchiveMove.set(true)

        expectFailure(ChrootJournalFailureReason.SYNC) { recovering.repairInterruptedWriteFromUser() }

        assertUnhealthy(recovering, ChrootJournalFailureReason.SYNC, repairable = true)
        assertArrayEquals(original, pending(journal).readBytes())
        assertStoredState(journal, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        assertEquals(2L, rowCount(journal))
        val firstArchive = archives(journal).single()
        assertArrayEquals(original, File(firstArchive, "pending-original.bin").readBytes())
        val restarted = ChrootRecoveryRepository(journal)
        assertUnhealthy(restarted, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertArrayEquals(original, pending(journal).readBytes())
        restarted.repairInterruptedWriteFromUser()
        assertHealthy(restarted)
        assertFalse(pending(journal).exists())
        assertEquals(2, archives(journal).size)
        assertArrayEquals(original, File(firstArchive, "pending-original.bin").readBytes())
        assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertStoredState(journal, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        restarted.recordCleanup(entry.launchId, ChrootCleanupResult.VERIFIED)
        assertEquals(setOf(entry.launchId), restarted.unknownLaunchIds(instance))
    }

    @Test
    fun validUncommittedIntentPendingIsNeverAppliedUntilExplicitRepairAndThenIsUnknown() {
        val journal = journal()
        closedStore(journal)
        val entry = closedEntry(1).copy(phase = ChrootRecoveryPhase.INTENT, cleanup = null)
        val original = pendingBytes(markerStoreId(journal), "INTENT", entry)
        pending(journal).writeBytes(original)
        val pendingKey = fileKey(pending(journal))
        val before = database(journal).readBytes()

        val repository = ChrootRecoveryRepository(journal)
        assertUnhealthy(repository, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertEquals(setOf(entry.launchId), repository.unknownLaunchIds(instance))
        assertEquals(1L, rowCount(journal))
        assertArrayEquals(before, database(journal).readBytes())
        assertArrayEquals(original, pending(journal).readBytes())
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.containsLaunchId(entry.launchId) }

        repository.repairInterruptedWriteFromUser()

        assertHealthy(repository)
        assertEquals(2L, rowCount(journal))
        assertTrue(repository.containsLaunchId(entry.launchId))
        assertStoredState(journal, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertEquals(entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN), repository.snapshot().last())
        assertEquals(pendingKey, fileKey(File(archives(journal).single(), "pending-original.bin")))
        repository.recordCleanup(entry.launchId, ChrootCleanupResult.VERIFIED)
        assertStoredState(journal, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            repository.recordIntent(instance, entry.launchId, rootfs, "chat:uncommitted-replay", 99L)
        }
    }

    @Test
    fun versionOnePendingPreservesOriginalEvidenceAndMigratesOnlyAfterExplicitRepair() {
        val journal = journal()
        val entries = listOf(
            closedEntry(0),
            closedEntry(1).copy(phase = ChrootRecoveryPhase.INTENT, cleanup = null),
            closedEntry(2).copy(phase = ChrootRecoveryPhase.STARTING, cleanup = null),
            closedEntry(3).copy(phase = ChrootRecoveryPhase.RUNNING, cleanup = null)
        )
        val expected = entries.map { it.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN) }
        val main = legacyBytes(entries)
        val interrupted = main.copyOf(37)
        journal.writeBytes(main)
        pending(journal).writeBytes(interrupted)
        val pendingKey = fileKey(pending(journal))
        val repository = ChrootRecoveryRepository(journal)
        assertUnhealthy(repository, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertEquals(expected, repository.snapshot())
        assertEquals(entries.mapTo(linkedSetOf()) { it.launchId }, repository.unknownLaunchIds(instance))
        assertArrayEquals(main, journal.readBytes())
        assertArrayEquals(interrupted, pending(journal).readBytes())
        assertFalse(database(journal).exists())
        assertFalse(backup(journal).exists())
        assertTrue(archives(journal).isEmpty())
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) {
            repository.recordIntent(instance, nonce(2_000), rootfs, "chat:before-repair", 99L)
        }

        val restarted = ChrootRecoveryRepository(journal)
        assertUnhealthy(restarted, ChrootJournalFailureReason.INTERRUPTED, repairable = true)
        assertArrayEquals(main, journal.readBytes())
        assertArrayEquals(interrupted, pending(journal).readBytes())
        assertFalse(database(journal).exists())
        restarted.repairInterruptedWriteFromUser()

        assertHealthy(restarted)
        assertEquals(expected, restarted.snapshot())
        assertEquals(entries.mapTo(linkedSetOf()) { it.launchId }, restarted.unknownLaunchIds(instance))
        expected.forEach { entry ->
            assertStoredState(journal, entry.launchId, ChrootRecoveryPhase.UNKNOWN, ChrootCleanupResult.UNKNOWN)
        }
        assertFalse(pending(journal).exists())
        assertEquals(34, journal.readBytes().size)
        assertArrayEquals(legacyBytes(expected), backup(journal).readBytes())
        assertEquals(sha256(backup(journal).readBytes()), sqlString(journal, "SELECT source_hash FROM metadata WHERE singleton=1"))
        val archive = archives(journal).single()
        assertArrayEquals(main, File(archive, "main.bin").readBytes())
        assertArrayEquals(interrupted, File(archive, "pending.bin").readBytes())
        assertArrayEquals(interrupted, File(archive, "pending-original.bin").readBytes())
        assertEquals(pendingKey, fileKey(File(archive, "pending-original.bin")))
        assertEquals("${sha256(main)}  main.bin\n${sha256(interrupted)}  pending.bin\n", File(archive, "sha256.txt").readText())
        val canonical = restarted.snapshot()
        entries.forEach { entry ->
            restarted.recordPhase(entry.launchId, ChrootRecoveryPhase.RUNNING)
            restarted.recordCleanup(entry.launchId, ChrootCleanupResult.VERIFIED)
        }
        assertEquals(canonical, restarted.snapshot())
        val again = ChrootRecoveryRepository(journal)
        assertHealthy(again)
        assertEquals(canonical, again.snapshot())
    }

    @Test
    fun validPendingReplacementPreservesBothConflictingRecordsAndFailedMarkerBlocksRestartRepair() {
        val journal = journal()
        val replacePending = AtomicBoolean(false)
        val replaced = AtomicBoolean(false)
        var originalPending = byteArrayOf()
        var replacementPending = byteArrayOf()
        val repository = ChrootRecoveryRepository(journal) { directory ->
            if (directory == journal.parentFile && pending(journal).exists() && replacePending.compareAndSet(true, false)) {
                originalPending = pending(journal).readBytes()
                val replacement = File(journal.parentFile, "replacement-pending.bin").apply { writeBytes(replacementPending) }
                Files.move(replacement.toPath(), pending(journal).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                replaced.set(true)
            }
            syncDirectory(directory)
        }
        repository.recordIntent(instance, launch, rootfs, "chat:closed-before-conflict", 9L)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        val interruptedLaunch = nonce(1_001)
        repository.recordIntent(instance, interruptedLaunch, rootfs, "chat:interrupted-by-conflict", 10L)
        repository.recordPhase(interruptedLaunch, ChrootRecoveryPhase.RUNNING)
        val entries = repository.snapshot()
        val originalEntry = entries.single { it.launchId == interruptedLaunch }
        val closed = entries.single { it.launchId == launch }
        val storeId = markerStoreId(journal)
        replacementPending = pendingBytes(storeId, "UPDATE", closed)
        val expectedPending = pendingBytes(storeId, "UPDATE", originalEntry)
        assertFalse(expectedPending.contentEquals(replacementPending))
        assertTrue(replacementPending.size < 4_096)
        val marker = journal.readBytes()
        val databaseBytes = database(journal).readBytes()
        replacePending.set(true)

        expectFailure(ChrootJournalFailureReason.IDENTITY) {
            repository.recordCleanup(interruptedLaunch, ChrootCleanupResult.VERIFIED)
        }

        assertTrue(replaced.get())
        assertUnhealthy(repository, ChrootJournalFailureReason.IDENTITY, repairable = false)
        assertEquals(setOf(interruptedLaunch), repository.unknownLaunchIds(instance))
        assertArrayEquals(expectedPending, originalPending)
        assertTrue(originalPending.size < 4_096)
        assertArrayEquals(originalPending, failed(journal).readBytes())
        assertArrayEquals(replacementPending, pending(journal).readBytes())
        val failedKey = fileKey(failed(journal))
        val pendingKey = fileKey(pending(journal))
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(databaseBytes, database(journal).readBytes())
        assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertStoredState(journal, interruptedLaunch, ChrootRecoveryPhase.RUNNING, null)
        assertEquals(2L, rowCount(journal))
        expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(interruptedLaunch) }
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }

        val restarted = ChrootRecoveryRepository(journal)
        assertUnhealthy(restarted, ChrootJournalFailureReason.IDENTITY, repairable = false)
        expectFailure(ChrootJournalFailureReason.IDENTITY) { restarted.containsLaunchId(interruptedLaunch) }
        expectFailure(ChrootJournalFailureReason.IDENTITY) {
            restarted.recordIntent(instance, nonce(2_000), rootfs, "chat:failed-marker-refused", 11L)
        }
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { restarted.repairInterruptedWriteFromUser() }
        assertArrayEquals(originalPending, failed(journal).readBytes())
        assertEquals(failedKey, fileKey(failed(journal)))
        assertArrayEquals(replacementPending, pending(journal).readBytes())
        assertEquals(pendingKey, fileKey(pending(journal)))
        assertArrayEquals(marker, journal.readBytes())
        assertArrayEquals(databaseBytes, database(journal).readBytes())
        assertTrue(archives(journal).isEmpty())
    }

    @Test
    fun malformedTruncatedOversizedAndForeignStorePendingAreNotAutomaticallyOrExplicitlyRepaired() {
        listOf("truncated", "checksum", "operation", "trailing", "oversized", "foreign-store").forEach { case ->
            val journal = journal()
            closedStore(journal)
            val entry = closedEntry(1).copy(phase = ChrootRecoveryPhase.INTENT, cleanup = null)
            val store = markerStoreId(journal)
            val valid = pendingBytes(store, "INTENT", entry)
            val bytes = when (case) {
                "truncated" -> valid.copyOf(valid.size - 1)
                "checksum" -> valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                "operation" -> pendingBytes(store, "REPLAY", entry)
                "trailing" -> pendingBytes(store, "INTENT", entry, byteArrayOf(0))
                "oversized" -> valid.copyOf(4_097)
                else -> pendingBytes(nonce(999_999), "INTENT", entry)
            }
            val reason = if (case == "foreign-store") ChrootJournalFailureReason.IDENTITY else ChrootJournalFailureReason.CORRUPT
            pending(journal).writeBytes(bytes)
            val pendingKey = fileKey(pending(journal))
            val marker = journal.readBytes()
            val databaseBytes = database(journal).readBytes()
            val legacyBackup = backup(journal).readBytes()
            val repository = ChrootRecoveryRepository(journal)
            assertUnhealthy(repository, reason, repairable = false)
            expectFailure(reason) { repository.containsLaunchId(launch) }
            expectFailure(reason) { repository.recordIntent(instance, entry.launchId, rootfs, "chat:refused", 99L) }
            expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
            assertUnhealthy(repository, reason, repairable = false)
            val restarted = ChrootRecoveryRepository(journal)
            assertUnhealthy(restarted, reason, repairable = false)
            assertArrayEquals(case, bytes, pending(journal).readBytes())
            assertEquals(pendingKey, fileKey(pending(journal)))
            assertArrayEquals(case, marker, journal.readBytes())
            assertArrayEquals(case, databaseBytes, database(journal).readBytes())
            assertArrayEquals(case, legacyBackup, backup(journal).readBytes())
            assertEquals(1L, rowCount(journal))
            assertTrue(archives(journal).isEmpty())
        }
    }

    @Test
    fun missingDatabaseOrMarkerNeverRecreatesAnEmptyStoreOrFallsBackToLegacyBackup() {
        listOf("database", "marker").forEach { missing ->
            val journal = journal()
            closedStore(journal)
            val marker = journal.readBytes()
            val databaseBytes = database(journal).readBytes()
            val legacyBackup = backup(journal).readBytes()
            val target = if (missing == "database") database(journal) else journal
            val retained = File(journal.parentFile, "retained-$missing.bin")
            Files.move(target.toPath(), retained.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val repository = ChrootRecoveryRepository(journal)
            assertUnhealthy(repository, ChrootJournalFailureReason.IDENTITY, repairable = false)
            expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(launch) }
            expectFailure(ChrootJournalFailureReason.IDENTITY) {
                repository.recordIntent(instance, nonce(2_000), rootfs, "chat:must-not-reset", 99L)
            }
            expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
            assertFalse(target.exists())
            if (missing == "database") {
                assertArrayEquals(databaseBytes, retained.readBytes())
                assertArrayEquals(marker, journal.readBytes())
            } else {
                assertArrayEquals(marker, retained.readBytes())
                assertArrayEquals(databaseBytes, database(journal).readBytes())
                assertEquals(1L, rowCount(journal))
            }
            assertArrayEquals(legacyBackup, backup(journal).readBytes())
            assertFalse(pending(journal).exists())
            assertTrue(archives(journal).isEmpty())
            assertUnhealthy(ChrootRecoveryRepository(journal), ChrootJournalFailureReason.IDENTITY, repairable = false)
            assertFalse(target.exists())
        }
    }

    @Test
    fun markerAndDatabaseStoreIdMismatchFailClosedWithoutClearingAnyHistory() {
        listOf("marker", "database").forEach { changed ->
            val journal = journal()
            closedStore(journal)
            val foreign = nonce(999_999)
            if (changed == "marker") {
                journal.writeBytes(encoded { writeInt(0x414a434a); writeInt(2); writeUTF(foreign) })
            } else {
                withDatabase(journal, write = true) { db ->
                    db.execSQL("UPDATE metadata SET store_id=? WHERE singleton=1", arrayOf(foreign))
                }
            }
            val marker = journal.readBytes()
            val databaseBytes = database(journal).readBytes()
            val legacyBackup = backup(journal).readBytes()
            val repository = ChrootRecoveryRepository(journal)
            assertUnhealthy(repository, ChrootJournalFailureReason.IDENTITY, repairable = false)
            expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(launch) }
            expectFailure(ChrootJournalFailureReason.IDENTITY) {
                repository.recordIntent(instance, nonce(2_000), rootfs, "chat:foreign-store", 99L)
            }
            expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
            assertArrayEquals(marker, journal.readBytes())
            assertArrayEquals(databaseBytes, database(journal).readBytes())
            assertArrayEquals(legacyBackup, backup(journal).readBytes())
            assertEquals(1L, rowCount(journal))
            assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
            assertTrue(archives(journal).isEmpty())
            assertFalse(pending(journal).exists())
        }
    }

    @Test
    fun corruptSQLiteDatabaseIsPreservedInsteadOfBeingDeletedByThePlatformErrorHandler() {
        val journal = journal()
        closedStore(journal)
        val marker = journal.readBytes()
        val original = database(journal).readBytes()
        val corrupt = original.copyOf(16)
        database(journal).writeBytes(corrupt)
        val databaseKey = fileKey(database(journal))

        val repository = ChrootRecoveryRepository(journal)

        assertUnhealthy(repository, ChrootJournalFailureReason.CORRUPT, repairable = false)
        expectFailure(ChrootJournalFailureReason.CORRUPT) { repository.containsLaunchId(launch) }
        expectFailure(ChrootJournalFailureReason.CORRUPT) {
            repository.recordIntent(instance, nonce(2_000), rootfs, "chat:corrupt-database", 99L)
        }
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
        assertArrayEquals(corrupt, database(journal).readBytes())
        assertEquals(databaseKey, fileKey(database(journal)))
        assertArrayEquals(marker, journal.readBytes())
        assertFalse(pending(journal).exists())
        assertTrue(archives(journal).isEmpty())
        assertUnhealthy(ChrootRecoveryRepository(journal), ChrootJournalFailureReason.CORRUPT, repairable = false)
        assertArrayEquals(corrupt, database(journal).readBytes())
    }

    @Test
    fun symlinkMarkerDatabasePendingSidecarsAndParentAreRejectedWithoutTouchingTargets() {
        listOf("marker", "database", "pending", "-journal", "-wal", "-shm", "parent").forEach { kind ->
            val journal = journal()
            closedStore(journal)
            val marker = journal.readBytes()
            val databaseBytes = database(journal).readBytes()
            val legacyBackup = backup(journal).readBytes()
            val external = temporary.newFile().apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val link: File
            val externalBytes: ByteArray
            if (kind == "parent") {
                val realParent = File(journal.parentFile.parentFile, journal.parentFile.name + "-real")
                Files.move(journal.parentFile.toPath(), realParent.toPath(), StandardCopyOption.ATOMIC_MOVE)
                link = journal.parentFile
                Files.createSymbolicLink(link.toPath(), realParent.toPath())
                externalBytes = external.readBytes()
            } else {
                link = when (kind) {
                    "marker" -> journal
                    "database" -> database(journal)
                    "pending" -> pending(journal)
                    else -> File(database(journal).path + kind)
                }
                if (kind == "marker" || kind == "database") {
                    Files.move(link.toPath(), external.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
                externalBytes = external.readBytes()
                Files.createSymbolicLink(link.toPath(), external.toPath())
            }
            val reason = if (kind == "marker" || kind == "pending") ChrootJournalFailureReason.CORRUPT else ChrootJournalFailureReason.IDENTITY
            val repository = ChrootRecoveryRepository(journal)
            assertUnhealthy(repository, reason, repairable = false)
            expectFailure(reason) {
                repository.recordIntent(instance, nonce(2_000), rootfs, "chat:symlink-refused", 99L)
            }
            expectFailure(reason) { repository.containsLaunchId(launch) }
            expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
            assertTrue(kind, Files.isSymbolicLink(link.toPath()))
            assertArrayEquals(kind, externalBytes, external.readBytes())
            assertArrayEquals(kind, marker, journal.readBytes())
            assertArrayEquals(kind, databaseBytes, database(journal).readBytes())
            assertArrayEquals(kind, legacyBackup, backup(journal).readBytes())
            assertTrue(archives(journal).isEmpty())
        }
    }

    @Test
    fun identicalMarkerOrDatabaseReplacementIsRejectedByAnAlreadyOpenRepository() {
        listOf("marker", "database").forEach { replaced ->
            val journal = journal()
            val repository = closedStore(journal)
            val target = if (replaced == "marker") journal else database(journal)
            val bytes = target.readBytes()
            val originalKey = fileKey(target)
            val replacement = File(journal.parentFile, "replacement-$replaced.bin").apply { writeBytes(bytes) }
            Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertFalse(originalKey == fileKey(target))

            expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(launch) }

            assertUnhealthy(repository, ChrootJournalFailureReason.IDENTITY, repairable = false)
            expectFailure(ChrootJournalFailureReason.IDENTITY) {
                repository.recordIntent(instance, nonce(2_000), rootfs, "chat:replaced-store", 99L)
            }
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(1L, rowCount(journal))
            assertFalse(pending(journal).exists())
            assertTrue(archives(journal).isEmpty())
        }
    }

    @Test
    fun inPlaceDatabaseOverwriteWithTheSameInodeFailsTheLiveRepositoryStoreIdentityCheck() {
        val journal = journal()
        val repository = closedStore(journal)
        val marker = journal.readBytes()
        val markerKey = fileKey(journal)
        val databaseKey = fileKey(database(journal))
        val legacyBackup = backup(journal).readBytes()
        val foreignJournal = journal()
        val foreignLaunch = nonce(900_000)
        val foreignRepository = ChrootRecoveryRepository(foreignJournal)
        foreignRepository.recordIntent(instance, foreignLaunch, rootfs, "chat:in-place-foreign-store", 99L)
        foreignRepository.recordCleanup(foreignLaunch, ChrootCleanupResult.VERIFIED)
        assertHealthy(foreignRepository)
        val foreignStore = markerStoreId(foreignJournal)
        val foreignBytes = database(foreignJournal).readBytes()
        assertFalse(markerStoreId(journal) == foreignStore)

        database(journal).writeBytes(foreignBytes)
        assertEquals(databaseKey, fileKey(database(journal)))
        expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(launch) }

        assertUnhealthy(repository, ChrootJournalFailureReason.IDENTITY, repairable = false)
        expectFailure(ChrootJournalFailureReason.IDENTITY) { repository.containsLaunchId(foreignLaunch) }
        expectFailure(ChrootJournalFailureReason.IDENTITY) {
            repository.recordIntent(instance, nonce(2_000), rootfs, "chat:must-not-adopt-overwritten-store", 100L)
        }
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
        assertEquals(foreignStore, sqlString(journal, "SELECT store_id FROM metadata WHERE singleton=1"))
        assertStoredState(journal, foreignLaunch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        assertEquals(1L, rowCount(journal))
        assertArrayEquals(foreignBytes, database(journal).readBytes())
        assertEquals(databaseKey, fileKey(database(journal)))
        assertArrayEquals(marker, journal.readBytes())
        assertEquals(markerKey, fileKey(journal))
        assertArrayEquals(legacyBackup, backup(journal).readBytes())
        assertFalse(pending(journal).exists())
        assertTrue(archives(journal).isEmpty())
        assertUnhealthy(ChrootRecoveryRepository(journal), ChrootJournalFailureReason.IDENTITY, repairable = false)
        assertArrayEquals(foreignBytes, database(journal).readBytes())
    }

    @Test
    fun nonDirectoryParentConstructionPublishesOpenFailureWithoutChangingTheOriginalFile() {
        val parent = temporary.newFile().apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val original = parent.readBytes()
        val originalKey = fileKey(parent)
        val journal = File(parent, "journal.bin")

        val repository = ChrootRecoveryRepository(journal)

        assertUnhealthy(repository, ChrootJournalFailureReason.OPEN, repairable = false)
        expectFailure(ChrootJournalFailureReason.OPEN) { repository.containsLaunchId(launch) }
        expectFailure(ChrootJournalFailureReason.OPEN) {
            repository.recordIntent(instance, launch, rootfs, "chat:non-directory-parent", 9L)
        }
        expectFailure(ChrootJournalFailureReason.INTERRUPTED) { repository.repairInterruptedWriteFromUser() }
        assertTrue(parent.isFile)
        assertArrayEquals(original, parent.readBytes())
        assertEquals(originalKey, fileKey(parent))
        assertFalse(journal.exists())
        assertFalse(database(journal).exists())
        assertFalse(backup(journal).exists())
        assertFalse(pending(journal).exists())
        assertUnhealthy(ChrootRecoveryRepository(journal), ChrootJournalFailureReason.OPEN, repairable = false)
        assertArrayEquals(original, parent.readBytes())
        assertEquals(originalKey, fileKey(parent))
    }

    @Test
    fun concurrentSameNonceHasExactlyOneSuccessfulIntentAndAllOtherAttemptsAreReplay() {
        val journal = journal()
        val repository = ChrootRecoveryRepository(journal)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val attempts = List(8) { index ->
            executor.submit<Pair<Int, ChrootJournalFailureReason?>> {
                ready.countDown()
                check(start.await(10, TimeUnit.SECONDS))
                try {
                    repository.recordIntent(instance, launch, rootfs, "chat:writer-$index", index.toLong())
                    index to null
                } catch (failure: ChrootJournalException) {
                    index to failure.reason
                }
            }
        }
        val results = try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            attempts.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertEquals(1, results.count { it.second == null })
        assertEquals(7, results.count { it.second == ChrootJournalFailureReason.REPLAY })
        assertHealthy(repository)
        assertEquals(1L, rowCount(journal))
        assertTrue(repository.containsLaunchId(launch))
        assertEquals(setOf(launch), repository.recordedLaunchIds())
        val winner = results.single { it.second == null }.first
        val entry = repository.snapshot().single()
        assertEquals(winner.toLong(), entry.rootGeneration)
        assertEquals(sha256("chat:writer-$winner".toByteArray()), entry.ownerHash)
        assertEquals(ChrootRecoveryPhase.INTENT, entry.phase)
        assertFalse(pending(journal).exists())
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        val restarted = ChrootRecoveryRepository(journal)
        assertHealthy(restarted)
        assertStoredState(journal, launch, ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED)
        expectFailure(ChrootJournalFailureReason.REPLAY) {
            restarted.recordIntent(instance, launch, rootfs, "chat:concurrent-replay", 99L)
        }
        assertEquals(1L, rowCount(journal))
    }

    private fun journal(): File = File(temporary.newFolder(), "journal.bin")
    private fun database(journal: File): File = File(journal.path + ".sqlite")
    private fun pending(journal: File): File = File(journal.path + ".pending")
    private fun failed(journal: File): File = File(journal.path + ".failed")
    private fun rollbackJournal(journal: File): File = File(database(journal).path + "-journal")
    private fun backup(journal: File): File = File(journal.path + ".legacy-v1")
    private fun nonce(value: Int): String = value.toString(16).padStart(24, '0')

    private fun closedEntry(index: Int) = ChrootRecoveryEntry(
        instance, nonce(1_000 + index), ChrootFileIdentity(5L + index, 19L + index),
        "user", (index + 1).toString(16).padStart(64, '0'), 9L + index,
        ChrootRecoveryPhase.CLOSED, ChrootCleanupResult.VERIFIED
    )

    private fun closedStore(journal: File): ChrootRecoveryRepository = ChrootRecoveryRepository(journal).also { repository ->
        repository.recordIntent(instance, launch, rootfs, "chat:fixture", 9L)
        repository.recordCleanup(launch, ChrootCleanupResult.VERIFIED)
        assertHealthy(repository)
    }

    private fun conservative(entries: List<ChrootRecoveryEntry>): List<ChrootRecoveryEntry> = entries.map { entry ->
        if (entry.phase == ChrootRecoveryPhase.CLOSED) entry
        else entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN)
    }

    private fun assertHealthy(repository: ChrootRecoveryRepository) {
        assertTrue(repository.lastFailure.value?.summary(), repository.isHealthy.value)
        assertTrue(repository.canStart(instance))
        assertFalse(repository.canRepairInterruptedWrite.value)
        assertNull(repository.lastFailure.value)
    }

    private fun assertUnhealthy(repository: ChrootRecoveryRepository, reason: ChrootJournalFailureReason, repairable: Boolean) {
        assertFalse(repository.isHealthy.value)
        assertFalse(repository.canStart(instance))
        assertEquals(reason, repository.lastFailure.value?.reason)
        assertEquals(repairable, repository.canRepairInterruptedWrite.value)
    }

    private fun expectFailure(reason: ChrootJournalFailureReason, action: () -> Unit): ChrootJournalException {
        try {
            action()
        } catch (failure: ChrootJournalException) {
            assertEquals(reason, failure.reason)
            return failure
        }
        throw AssertionError("Expected CHROOT journal ${reason.name}")
    }

    private fun <T> withDatabase(journal: File, write: Boolean = false, action: (SQLiteDatabase) -> T): T {
        val flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            if (write) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY
        val params = SQLiteDatabase.OpenParams.Builder().setOpenFlags(flags).apply {
            if (write) {
                setJournalMode("DELETE")
                setSynchronousMode("EXTRA")
            }
        }.build()
        return SQLiteDatabase.openDatabase(database(journal), params).use { db ->
            assertEquals(!write, db.isReadOnly)
            action(db)
        }
    }

    private fun rowCount(journal: File): Long = sqlLong(journal, "SELECT COUNT(*) FROM launches")

    private fun sqlLong(journal: File, sql: String): Long = withDatabase(journal) { db ->
        db.rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val value = cursor.getLong(0)
            assertFalse(cursor.moveToNext())
            value
        }
    }

    private fun sqlString(journal: File, sql: String): String = withDatabase(journal) { db ->
        db.rawQuery(sql, null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val value = cursor.getString(0)
            assertFalse(cursor.moveToNext())
            value
        }
    }

    private fun assertStoredState(journal: File, launchId: String, phase: ChrootRecoveryPhase, cleanup: ChrootCleanupResult?) {
        withDatabase(journal) { db ->
            db.rawQuery("SELECT phase,cleanup FROM launches WHERE launch_id=?", arrayOf(launchId)).use { cursor ->
                assertTrue(launchId, cursor.moveToFirst())
                assertEquals(phase.ordinal, cursor.getInt(0))
                if (cleanup == null) assertTrue(cursor.isNull(1)) else assertEquals(cleanup.ordinal, cursor.getInt(1))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    private fun insertClosedFixture(journal: File, count: Int) {
        withDatabase(journal, write = true) { db ->
            db.beginTransaction()
            try {
                db.compileStatement(
                    "INSERT INTO launches(launch_id,instance_id,device,inode,owner_kind,owner_hash,root_generation,phase,cleanup) " +
                        "VALUES(?,?,?,?,?,?,?,3,0)"
                ).use { statement ->
                    repeat(count) { index ->
                        val entry = closedEntry(index)
                        statement.clearBindings()
                        statement.bindString(1, entry.launchId)
                        statement.bindString(2, entry.instanceId)
                        statement.bindLong(3, entry.rootfsIdentity.device)
                        statement.bindLong(4, entry.rootfsIdentity.inode)
                        statement.bindString(5, entry.ownerKind)
                        statement.bindString(6, entry.ownerHash)
                        statement.bindLong(7, entry.rootGeneration)
                        assertTrue(statement.executeInsert() > 0)
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun copyHotRollbackFixture(source: File, target: File, beforeTransaction: ByteArray) {
        val rawJournal = rollbackJournal(source)
        assertTrue("A spilled transaction must produce a rollback journal", rawJournal.isFile)
        val journalBytes = rawJournal.readBytes()
        assertTrue(journalBytes.size > 512)
        val magic = byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 0x05, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte())
        assertArrayEquals("The rollback journal must have a valid nonzero SQLite header", magic, journalBytes.copyOfRange(0, 8))
        val spilledDatabase = database(source).readBytes()
        assertFalse("The uncommitted transaction must have spilled changes into the main DB", beforeTransaction.contentEquals(spilledDatabase))
        Files.copy(database(source).toPath(), database(target).toPath())
        Files.copy(rawJournal.toPath(), rollbackJournal(target).toPath())
        assertArrayEquals(spilledDatabase, database(target).readBytes())
        assertArrayEquals(journalBytes, rollbackJournal(target).readBytes())
        assertFalse(fileKey(database(source)) == fileKey(database(target)))
        assertFalse(File(database(target).path + "-wal").exists())
        assertFalse(File(database(target).path + "-shm").exists())
    }

    private fun markerStoreId(journal: File): String = DataInputStream(ByteArrayInputStream(journal.readBytes())).use { input ->
        assertEquals(0x414a434a, input.readInt())
        assertEquals(2, input.readInt())
        val store = input.readUTF()
        assertTrue(store.matches(Regex("[0-9a-f]{24}")))
        assertEquals(-1, input.read())
        store
    }

    private fun pendingOperation(bytes: ByteArray): String = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        assertEquals(0x414a5458, input.readInt())
        assertEquals(1, input.readInt())
        input.readUTF()
        input.readUTF()
    }

    private fun legacyBytes(entries: List<ChrootRecoveryEntry>): ByteArray = encoded {
        writeInt(0x414a434a)
        writeInt(1)
        writeInt(entries.size)
        entries.forEach { writeEntry(it) }
    }

    private fun pendingBytes(storeId: String, operation: String, entry: ChrootRecoveryEntry, trailing: ByteArray = byteArrayOf()): ByteArray {
        val body = encoded {
            writeInt(0x414a5458)
            writeInt(1)
            writeUTF(storeId)
            writeUTF(operation)
            writeEntry(entry)
        } + trailing
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun DataOutputStream.writeEntry(entry: ChrootRecoveryEntry) {
        writeUTF(entry.instanceId)
        writeUTF(entry.launchId)
        writeLong(entry.rootfsIdentity.device)
        writeLong(entry.rootfsIdentity.inode)
        writeUTF(entry.ownerKind)
        writeUTF(entry.ownerHash)
        writeLong(entry.rootGeneration)
        writeByte(entry.phase.ordinal)
        writeByte(when (entry.cleanup) {
            null -> -1
            ChrootCleanupResult.VERIFIED -> 0
            ChrootCleanupResult.UNKNOWN -> 1
        })
    }

    private fun encoded(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use(write)
    }.toByteArray()

    private fun archives(journal: File): List<File> = journal.parentFile.listFiles()!!.filter {
        it.name.startsWith("${journal.name}.recovery-")
    }

    private fun fileKey(file: File): Any = checkNotNull(
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ, NOFOLLOW_LINKS).use { it.force(true) }
    }
}
