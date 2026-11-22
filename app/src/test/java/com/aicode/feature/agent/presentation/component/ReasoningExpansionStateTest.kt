package com.aicode.feature.agent.presentation.component

import org.junit.Assert.*
import org.junit.Test

class ReasoningExpansionStateTest {
    @Test
    fun untouchedWindowUsesTheLatestLoadedPreference() {
        val state = ReasoningExpansionState()
        assertFalse(state.expanded(false, null))
        assertTrue(state.expanded(true, null))
        assertFalse(state.expanded(false, null))
    }

    @Test
    fun manuallyCollapsedWindowIsNotReopenedByPreferenceOrStreamingUpdates() {
        val state = ReasoningExpansionState()
        assertTrue(state.expanded(true, null))
        state.select(false)
        repeat(1_000) { assertFalse(state.expanded(true, null)) }
        assertFalse(state.expanded(false, null))
        assertFalse(state.expanded(true, null))
    }

    @Test
    fun manuallyExpandedWindowIsNotClosedByPreferenceChanges() {
        val state = ReasoningExpansionState()
        state.select(true)
        assertTrue(state.expanded(false, null))
        assertTrue(state.expanded(true, null))
        assertTrue(state.expanded(false, null))
    }

    @Test
    fun hostChoiceTakesPrecedenceOverTheLocalChoiceAndPreference() {
        val state = ReasoningExpansionState()
        state.select(true)
        assertFalse(state.expanded(true, false))
        state.select(false)
        assertTrue(state.expanded(false, true))
    }

    @Test
    fun hostRemovalRestoresTheWindowsOwnChoiceInsteadOfLeakingAnotherOverride() {
        val state = ReasoningExpansionState()
        state.select(false)
        assertTrue(state.expanded(true, true))
        assertFalse(state.expanded(true, null))
    }

    @Test
    fun newWindowsDoNotInheritPreviousManualSelection() {
        val first = ReasoningExpansionState()
        first.select(false)
        val next = ReasoningExpansionState()
        assertFalse(first.expanded(true, null))
        assertTrue(next.expanded(true, null))
    }
}
