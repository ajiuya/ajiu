package com.termux.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBackendOutcomeTest {
    @Test
    fun prootAndSshLegacyBackendsKeepZeroNonzeroAndNegatedSignals() {
        for (status in listOf(0, 23, -1, -15)) {
            val backend = object : SessionBackend {
                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun resize(columns: Int, rows: Int) = Unit
                override fun waitForExit() = status
                override fun close() = Unit
            }
            val result = backend.waitForExitOutcome()
            assertTrue(result.isKnown)
            assertEquals(status, result.status)
        }
    }

    @Test
    fun unknownDescriptionDoesNotMisreportASignalOrSuccessfulExit() {
        val unknown = SessionBackend.ExitOutcome.unknown()
        val description = description(unknown)
        assertFalse(unknown.isKnown)
        assertTrue(description.contains("exit status unknown"))
        assertFalse(description.contains("signal 1"))
        assertFalse(description.contains("code 0"))
        assertEquals("\r\n[Process completed (signal 1) - press Enter]",
            description(SessionBackend.ExitOutcome.fromLegacyStatus(-1)))
        assertEquals("\r\n[Process completed - press Enter]",
            description(SessionBackend.ExitOutcome.fromLegacyStatus(0)))
    }

    private fun description(outcome: SessionBackend.ExitOutcome): String =
        TerminalSession::class.java.getDeclaredMethod("exitDescription", SessionBackend.ExitOutcome::class.java)
            .apply { isAccessible = true }.invoke(null, outcome) as String
}
