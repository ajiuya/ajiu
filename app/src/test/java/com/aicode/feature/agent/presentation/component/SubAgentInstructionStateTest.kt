package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.mutableStateOf
import com.aicode.feature.agent.presentation.SubAgentInstructionFailure
import com.aicode.feature.agent.presentation.SubAgentInstructionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentInstructionStateTest {
    private fun state(draft: String) = SubAgentInstructionState(mutableStateOf(SubAgentInstructionUiState(draft)))

    @Test fun draftRemainsUntilAcknowledgedAndConcurrentClickDoesNotDuplicateDelivery() = runTest {
        val state = state("  exact instruction\n```code  ")
        val ack = CompletableDeferred<SubAgentInstructionResult>()
        val deliveries = mutableListOf<String>()
        val job = launch { state.send { deliveries += it; ack.await() } }
        runCurrent()
        assertTrue(state.value.sending)
        assertEquals("  exact instruction\n```code  ", state.value.draft)
        state.send { error("concurrent click") }
        assertEquals(1, deliveries.size)
        ack.complete(SubAgentInstructionResult(true))
        job.join()
        assertEquals("", state.value.draft)
        assertTrue(state.value.sentNotice)
        assertFalse(state.value.sending)
    }

    @Test fun identicalTextCanBeSentAgainAsADistinctInstruction() = runTest {
        val state = state("same body")
        val deliveries = mutableListOf<String>()
        repeat(2) {
            state.send { deliveries += it; SubAgentInstructionResult(true) }
            state.edit("same body")
        }
        assertEquals(listOf("same body", "same body"), deliveries)
    }

    @Test fun everyFailureAndCancellationRetainsDraftAndChildrenAreIsolated() = runTest {
        val first = state("first")
        val second = state("second")
        SubAgentInstructionFailure.entries.forEach { failure ->
            first.send { SubAgentInstructionResult(false, failure) }
            assertEquals("first", first.value.draft)
            assertEquals(failure, first.value.failure)
        }
        first.send { throw IllegalStateException("transport") }
        assertEquals(SubAgentInstructionFailure.SEND_FAILED, first.value.failure)
        val job = launch { first.send { CompletableDeferred<SubAgentInstructionResult>().await() } }
        runCurrent()
        assertTrue(first.value.sending)
        assertFalse(second.value.sending)
        job.cancelAndJoin()
        assertFalse(first.value.sending)
        assertFalse(first.value.sentNotice)
        assertEquals("first", first.value.draft)
        assertEquals("second", second.value.draft)
    }

    @Test fun acknowledgementDoesNotClearNewerDraftEvenWhenItHasIdenticalText() = runTest {
        val state = state("same")
        val ack = CompletableDeferred<SubAgentInstructionResult>()
        val job = launch { state.send { ack.await() } }
        runCurrent()
        state.edit("retyped")
        state.edit("same")
        ack.complete(SubAgentInstructionResult(true))
        job.join()
        assertEquals("same", state.value.draft)
        state.edit(" \n\t ")
        state.send { error("whitespace must not send") }
        assertEquals(SubAgentInstructionFailure.EMPTY, state.value.failure)
    }
}
