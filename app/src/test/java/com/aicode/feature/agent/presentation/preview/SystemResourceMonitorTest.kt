package com.aicode.feature.agent.presentation.preview

import org.junit.Assert.*
import org.junit.Test

class SystemResourceMonitorTest {
    @Test fun cpuStatParsesLastParenthesisAndOnlyCurrentProcessUserAndSystemTicks() {
        val tail = listOf("R") + List(10) { "0" } + listOf("120", "30", "900", "800")
        assertEquals(150L, SystemResourceMonitor.parseSelfCpuTicks("1 (name with ) spaces) ${tail.joinToString(" ")}"))
        assertNull(SystemResourceMonitor.parseSelfCpuTicks("broken"))
        assertNull(SystemResourceMonitor.parseSelfCpuTicks("1 (app) R 0"))
    }

    @Test fun irixCpuCanExceed100PercentAndUnavailableOrResumedBaselineIsUnknown() {
        val window = AppCpuWindow(100)
        assertNull(window.sample(100, 1_000_000_000))
        assertEquals(200f, window.sample(300, 2_000_000_000)!!, 0.001f)
        assertEquals(150f, window.sample(400, 3_000_000_000)!!, 0.001f)
        window.reset()
        assertNull(window.sample(500, 20_000_000_000))
        assertNull(window.sample(null, 21_000_000_000))
        assertNull(AppCpuWindow(null).sample(100, 1_000_000_000))
    }

    @Test fun pssIncludesSwapPssAndMalformedOrMissingDataStaysUnknown() {
        assertEquals(125L, SystemResourceMonitor.parseTotalPssKb("Pss: 100 kB\nSwapPss: 25 kB\nRss: 999 kB"))
        assertEquals(100L, SystemResourceMonitor.parseTotalPssKb("Pss: 100 kB"))
        assertNull(SystemResourceMonitor.parseTotalPssKb("Rss: 999 kB"))
        assertNull(SystemResourceMonitor.parseTotalPssKb("Pss: -1 kB"))
        assertNull(SystemResourceMonitor.parseTotalPssKb("Pss: unavailable kB"))
    }

    @Test fun hudIsOnlyVisibleForRealRunningCommandsAndNeverPreparingFailedOrHidden() {
        val tool = ToolPreviewTarget("id", "Bash", "{}", "", ToolPreviewStage.RUNNING)
        assertTrue(showResourceHud(tool, true))
        assertFalse(showResourceHud(tool, false))
        for (stage in listOf(ToolPreviewStage.PREPARING, ToolPreviewStage.COMPLETED, ToolPreviewStage.FAILED)) {
            assertFalse(showResourceHud(tool.copy(stage = stage), true))
        }
        assertFalse(showResourceHud(tool.copy(name = "readFile"), true))
    }
}
