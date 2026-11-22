package com.aicode.feature.terminal.domain

import com.termux.terminal.TerminalSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputStateTest {
    @Test
    fun terminalSnapshotsKeepBothEndsWithinTheMemoryBudget() {
        val source = "head-" + "x".repeat(MAX_TERMINAL_OUTPUT_CHARS * 2) + "-tail"
        val bounded = boundTerminalOutput(source)

        assertTrue(bounded.length <= MAX_TERMINAL_OUTPUT_CHARS + 64)
        assertTrue(bounded.startsWith("head-"))
        assertTrue(bounded.endsWith("-tail"))
    }

    @Test
    fun silentReadsReuseTheSameSnapshotAndSameLengthChangesInvalidateIt() {
        val state = TerminalOutputState()
        val screen = Any()
        var builds = 0
        var value = "before"
        val build = { builds++; value }
        val first = state.read(screen, 80, 24, build)
        val revision = state.revision
        repeat(20) {
            assertEquals(revision, state.observe(screen, 80, 24))
            assertSame(first, state.read(screen, 80, 24, build))
        }
        assertEquals(1, builds)
        value = "after!"
        state.changed()
        assertEquals("after!", state.read(screen, 80, 24, build))
        assertEquals(2, builds)
    }

    @Test
    fun screenIdentityAndBothDimensionsInvalidateEvenWithoutAnOutputCallback() {
        val state = TerminalOutputState()
        val screen = Any()
        var builds = 0
        val build = { (++builds).toString() }
        state.read(null, 0, 0, build)
        state.read(screen, 80, 24, build)
        val revision = state.revision
        assertTrue(state.observe(screen, 81, 24) > revision)
        state.read(screen, 81, 24, build)
        state.read(screen, 81, 25, build)
        state.read(Any(), 81, 25, build)
        assertEquals(5, builds)
    }

    @Test
    fun failedBuildDoesNotPoisonTheNextSnapshot() {
        val state = TerminalOutputState()
        val screen = Any()
        runCatching { state.read(screen, 80, 24) { error("not ready") } }
        assertEquals("ready", state.read(screen, 80, 24) { "ready" })
    }

    @Test
    fun markerParserRequiresACompleteIndependentLineAndPreservesTheLastValidCode() {
        assertEquals(23, commandExitCode("log\r\n[command exited: 23]\n"))
        assertEquals(0, commandExitCode("[command exited: 23]\n[command exited: 0]"))
        assertEquals(23, commandExitCode("[command exited: 23]\n[command exited: 999999999999]"))
        assertNull(commandExitCode("echo '[command exited: 23]'"))
        assertNull(commandExitCode("prefix[command exited: 23]"))
        assertNull(commandExitCode("[command exited: 23]suffix"))
        assertNull(commandExitCode("[command exited: -1]"))
        assertNull(commandExitCode("[command exited: 23"))
    }

    @Test
    fun onlyNotifyingBackgroundCommandsStopBackendAndFreezeLogicalExitBeforeTheStop() {
        val session = mockk<TerminalSession>()
        val tab = TerminalTab("term-1", "build", session, true, "build", true, runState = RunState.Running)
        tab.markerExitCode = 37
        every { session.finishIfRunning() } answers {
            assertEquals(RunState.Finished(37), tab.runState)
            assertFalse(tab.finish(137))
        }
        assertTrue(tab.finish(0))
        assertFalse(tab.finish(137))
        assertEquals(RunState.Finished(37), tab.runState)
        verify(exactly = 1) { session.finishIfRunning() }
    }

    @Test
    fun naturalCompletionOfInteractiveAndReusableShellsNeverForcesTheirBackend() {
        for ((background, notify) in listOf(false to false, false to true, true to false)) {
            val session = mockk<TerminalSession>()
            val tab = TerminalTab("term-1", "shell", session, background, null, notify, runState = RunState.Running)
            assertTrue(tab.finish(17))
            assertEquals(RunState.Finished(17), tab.runState)
            verify(exactly = 0) { session.finishIfRunning() }
        }
    }
}
