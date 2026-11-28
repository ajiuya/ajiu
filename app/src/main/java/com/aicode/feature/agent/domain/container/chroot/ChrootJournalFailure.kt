package com.aicode.feature.agent.domain.container.chroot

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

internal enum class ChrootJournalFailureReason {
    OPEN, READ, WRITE, SYNC, NO_SPACE, CORRUPT, INTERRUPTED, IDENTITY, REPLAY, MIGRATION
}

/** Only bounded, non-sensitive fields may enter runtime diagnostics. */
internal data class ChrootJournalFailure(val reason: ChrootJournalFailureReason, val errno: Int? = null) {
    init { require(errno == null || errno in 1..4095) }

    fun summary(): String = "journal=${reason.name}" + (errno?.let { ";journalErrno=$it" } ?: "")
}

internal class ChrootJournalException(
    val reason: ChrootJournalFailureReason,
    val systemErrno: Int? = null,
    cause: Throwable? = null
) : IOException("CHROOT journal ${reason.name}", cause) {
    val diagnostic: ChrootJournalFailure get() = ChrootJournalFailure(reason, systemErrno?.takeIf { it in 1..4095 })
}

internal fun journalException(failure: Exception, fallback: ChrootJournalFailureReason): ChrootJournalException {
    if (failure is ChrootJournalException) return failure
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = failure
    var errno: Int? = null
    var reason = fallback
    while (current != null && seen.size < 16 && seen.add(current)) {
        when (current) {
            is SQLiteFullException -> reason = ChrootJournalFailureReason.NO_SPACE
            is SQLiteDatabaseCorruptException, is java.io.EOFException, is java.io.UTFDataFormatException -> reason = ChrootJournalFailureReason.CORRUPT
            is ErrnoException -> errno = current.errno.takeIf { it in 1..4095 }
        }
        current = current.cause
    }
    if (errno == 28 || errno == 122) reason = ChrootJournalFailureReason.NO_SPACE
    return ChrootJournalException(reason, errno, failure)
}
