package com.aicode.feature.agent.domain.container.chroot

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File

/** Indexed private storage, separate from chat/backup databases. No lifetime row-count limit. */
internal class ChrootJournalDatabase(private val file: File, private val expectedStoreId: String? = null) {
    internal data class Identity(val storeId: String, val sourceHash: String)

    // Connections are scoped to an operation. This also makes explicit repair and simulated
    // process restarts independent of a stale SQLite connection or an unbounded page cache.
    private fun <T> connection(create: Boolean = false, write: Boolean = false, block: (SQLiteDatabase) -> T): T {
        val flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS or
            (if (write || create) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY) or
            (if (create) SQLiteDatabase.CREATE_IF_NECESSARY else 0)
        // The platform's default corruption handler deletes database files. Recovery evidence
        // must instead remain in place for inspection; never use destructive auto-recreation.
        val handler = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("CHROOT journal is corrupt") }
        val opened = if (android.os.Build.VERSION.SDK_INT >= 28 && (write || create)) {
            // Configure on OPEN: Android 9's compatibility WAL would otherwise rewrite the
            // database header before the first query, even for a point identity lookup.
            SQLiteDatabase.openDatabase(file, SQLiteDatabase.OpenParams.Builder().addOpenFlags(flags)
                .setErrorHandler(handler).setJournalMode("DELETE").setSynchronousMode("EXTRA").build())
        } else SQLiteDatabase.openDatabase(file.path, null, flags, handler)
        return opened.use { db ->
            if (write || create) {
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                    if (!cursor.moveToFirst() || !cursor.getString(0).equals("delete", ignoreCase = true)) {
                        throw ChrootJournalException(ChrootJournalFailureReason.OPEN)
                    }
                }
                db.execSQL("PRAGMA synchronous=EXTRA")
                db.rawQuery("PRAGMA synchronous", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getInt(0) != 3) {
                        throw ChrootJournalException(ChrootJournalFailureReason.SYNC)
                    }
                }
            }
            db.rawQuery("PRAGMA busy_timeout=5000", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 5000) {
                    throw ChrootJournalException(ChrootJournalFailureReason.OPEN)
                }
            }
            if (expectedStoreId != null) {
                db.rawQuery("SELECT store_id FROM metadata WHERE singleton=1", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != expectedStoreId || cursor.moveToNext()) {
                        throw ChrootJournalException(ChrootJournalFailureReason.IDENTITY)
                    }
                }
            }
            block(db)
        }
    }

    fun identity(recoverRollbackJournal: Boolean = false): Identity? = connection(write = recoverRollbackJournal) { db ->
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { tables ->
            if (!tables.moveToFirst()) return@connection null
        }
        db.rawQuery("PRAGMA user_version", null).use { version ->
            if (!version.moveToFirst() || version.getInt(0) != 1) {
                throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            }
        }
        db.rawQuery("SELECT store_id, source_hash FROM metadata WHERE singleton=1", null).use { cursor ->
            if (!cursor.moveToFirst()) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            val identity = Identity(cursor.getString(0), cursor.getString(1))
            if (cursor.moveToNext() || !NONCE.matches(identity.storeId) || !identity.sourceHash.matches(Regex("[0-9a-f]{64}"))) {
                throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
            }
            identity
        }
    }

    fun initialize(identity: Identity, entries: List<ChrootRecoveryEntry>) = connection(create = true, write = true) { db ->
        db.beginTransaction()
        try {
            db.execSQL("CREATE TABLE metadata (singleton INTEGER PRIMARY KEY CHECK(singleton=1), store_id TEXT NOT NULL, source_hash TEXT NOT NULL)")
            db.execSQL("""CREATE TABLE launches (
                sequence INTEGER PRIMARY KEY,
                launch_id TEXT NOT NULL UNIQUE,
                instance_id TEXT NOT NULL,
                device INTEGER NOT NULL,
                inode INTEGER NOT NULL,
                owner_kind TEXT NOT NULL,
                owner_hash TEXT NOT NULL,
                root_generation INTEGER NOT NULL CHECK(root_generation>=0),
                phase INTEGER NOT NULL CHECK(phase BETWEEN 0 AND 4),
                cleanup INTEGER,
                CHECK((phase=3 AND cleanup=0) OR (phase=4 AND cleanup=1) OR
                    (phase BETWEEN 0 AND 2 AND cleanup IS NULL))
            )""".trimIndent())
            db.execSQL("CREATE INDEX unresolved_launches ON launches(sequence) WHERE phase!=3")
            db.execSQL("INSERT INTO metadata(singleton,store_id,source_hash) VALUES(1,?,?)", arrayOf(identity.storeId, identity.sourceHash))
            db.compileStatement(INSERT).use { statement ->
                entries.forEach { entry ->
                    statement.clearBindings()
                    bindEntry(statement, entry)
                    statement.executeInsert()
                }
            }
            db.execSQL("PRAGMA user_version=1")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun contains(launchId: String): Boolean = connection { db ->
        db.rawQuery("SELECT 1 FROM launches WHERE launch_id=?", arrayOf(launchId)).use { it.moveToFirst() }
    }

    fun find(launchId: String): ChrootRecoveryEntry? = connection { db ->
        db.rawQuery("SELECT $COLUMNS FROM launches WHERE launch_id=?", arrayOf(launchId)).use { cursor ->
            if (cursor.moveToFirst()) row(cursor) else null
        }
    }

    fun snapshot(unresolvedOnly: Boolean = false): List<ChrootRecoveryEntry> = connection { db ->
        val where = if (unresolvedOnly) " WHERE phase!=3" else ""
        db.rawQuery("SELECT $COLUMNS FROM launches$where ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }
    }

    fun insert(entry: ChrootRecoveryEntry, beforeCommit: () -> Unit) = transaction(beforeCommit) { db ->
        try {
            db.compileStatement(INSERT).use { statement -> bindEntry(statement, entry); statement.executeInsert() }
        } catch (failure: SQLiteConstraintException) {
            throw ChrootJournalException(ChrootJournalFailureReason.REPLAY, cause = failure)
        }
    }

    fun update(launchId: String, phase: ChrootRecoveryPhase, cleanup: ChrootCleanupResult?, beforeCommit: () -> Unit) =
        transaction(beforeCommit) { db ->
            db.execSQL("UPDATE launches SET phase=?, cleanup=? WHERE launch_id=? AND phase NOT IN(3,4)",
                arrayOf(phase.ordinal, cleanup?.ordinal, launchId))
        }

    fun recover(pendingEntry: ChrootRecoveryEntry?, beforeCommit: () -> Unit) = transaction(beforeCommit) { db ->
        // A failed cleanup COMMIT may have put CLOSED on disk. The durable pending record is
        // exact evidence of uncertainty for that ID, so it overrides CLOSED only for that row.
        pendingEntry?.let { entry ->
            db.compileStatement(INSERT.replace("INSERT INTO", "INSERT OR IGNORE INTO")).use { statement ->
                bindEntry(statement, entry.copy(phase = ChrootRecoveryPhase.UNKNOWN, cleanup = ChrootCleanupResult.UNKNOWN))
                statement.executeInsert()
            }
            db.execSQL("UPDATE launches SET phase=4, cleanup=1 WHERE launch_id=?", arrayOf(entry.launchId))
        }
        db.execSQL("UPDATE launches SET phase=4, cleanup=1 WHERE phase!=3 AND phase!=4")
    }

    private fun transaction(beforeCommit: () -> Unit, block: (SQLiteDatabase) -> Unit) = connection(write = true) { db ->
        db.beginTransaction()
        try {
            block(db)
            beforeCommit()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun bindEntry(statement: android.database.sqlite.SQLiteStatement, entry: ChrootRecoveryEntry) {
        statement.bindString(1, entry.launchId)
        statement.bindString(2, entry.instanceId)
        statement.bindLong(3, entry.rootfsIdentity.device)
        statement.bindLong(4, entry.rootfsIdentity.inode)
        statement.bindString(5, entry.ownerKind)
        statement.bindString(6, entry.ownerHash)
        statement.bindLong(7, entry.rootGeneration)
        statement.bindLong(8, entry.phase.ordinal.toLong())
        if (entry.cleanup == null) statement.bindNull(9) else statement.bindLong(9, entry.cleanup.ordinal.toLong())
    }

    private fun row(cursor: android.database.Cursor): ChrootRecoveryEntry {
        val phase = ChrootRecoveryPhase.entries.getOrNull(cursor.getInt(7))
            ?: throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        val cleanup = if (cursor.isNull(8)) null else ChrootCleanupResult.entries.getOrNull(cursor.getInt(8))
            ?: throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        val entry = ChrootRecoveryEntry(cursor.getString(1), cursor.getString(0), ChrootFileIdentity(cursor.getLong(2), cursor.getLong(3)),
            cursor.getString(4), cursor.getString(5), cursor.getLong(6), phase, cleanup)
        if (!NONCE.matches(entry.instanceId) || !NONCE.matches(entry.launchId) ||
            !entry.ownerKind.matches(Regex("[a-z]{1,24}")) || !entry.ownerHash.matches(Regex("[0-9a-f]{64}")) ||
            entry.rootGeneration < 0 || phase == ChrootRecoveryPhase.CLOSED && cleanup != ChrootCleanupResult.VERIFIED ||
            phase == ChrootRecoveryPhase.UNKNOWN && cleanup != ChrootCleanupResult.UNKNOWN ||
            phase.ordinal < 3 && cleanup != null) throw ChrootJournalException(ChrootJournalFailureReason.CORRUPT)
        return entry
    }

    private companion object {
        val NONCE = Regex("[0-9a-f]{24}")
        const val COLUMNS = "launch_id,instance_id,device,inode,owner_kind,owner_hash,root_generation,phase,cleanup"
        const val INSERT = "INSERT INTO launches($COLUMNS) VALUES(?,?,?,?,?,?,?,?,?)"
    }
}
