package com.aicode.feature.settings.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExecutionModeSelectionRevisionTest {
    @Test
    fun initialLocalModeAndRepeatedSelectionsDoNotAdvanceTheRevision() {
        val holder = ExecutionModeHolder()
        assertEquals(ExecutionMode.LOCAL_PROOT, holder.currentMode())
        assertEquals(0L, holder.modeSelectionRevision)
        repeat(3) { holder.setMode(ExecutionMode.LOCAL_PROOT) }
        assertEquals(0L, holder.modeSelectionRevisions.value)
    }

    @Test
    fun changingModeUpdatesTheExistingModeFlowAndItsRevision() {
        val holder = ExecutionModeHolder()
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(ExecutionMode.REMOTE_SSH, holder.mode.value)
        assertEquals(1L, holder.modeSelectionRevision)
        assertEquals(holder.modeSelectionRevision, holder.modeSelectionRevisions.value)
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(1L, holder.modeSelectionRevision)
    }

    @Test
    fun aRoundTripCannotReuseAnEarlierSelectionRevision() {
        val holder = ExecutionModeHolder()
        holder.setMode(ExecutionMode.REMOTE_SSH)
        val captured = holder.modeSelectionRevision
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(ExecutionMode.REMOTE_SSH, holder.currentMode())
        assertEquals(captured + 2, holder.modeSelectionRevision)
        assertEquals(listOf(captured + 2), holder.modeSelectionRevisions.replayCache)
    }

    @Test
    fun revisionObserversCanInvalidateResourcesIndependentlyOfTheModeFlow() = runTest {
        val holder = ExecutionModeHolder()
        val revisions = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.modeSelectionRevisions.collect { revisions += it }
        }
        holder.setMode(ExecutionMode.REMOTE_SSH)
        holder.setMode(ExecutionMode.LOCAL_PROOT)
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals(listOf(0L, 1L, 2L, 3L), revisions)
    }
}
