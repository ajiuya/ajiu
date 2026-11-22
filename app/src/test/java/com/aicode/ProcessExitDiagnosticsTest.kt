package com.aicode

import android.app.ApplicationExitInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitDiagnosticsTest {

    @Test
    fun systemOtherExit_isReported() {
        assertTrue(AIEditorApp.EXIT_REASONS_TO_REPORT.contains(ApplicationExitInfo.REASON_OTHER))
    }

    @Test
    fun normalSelfExit_isNotReportedAsAbnormal() {
        assertFalse(AIEditorApp.EXIT_REASONS_TO_REPORT.contains(ApplicationExitInfo.REASON_EXIT_SELF))
    }
}
