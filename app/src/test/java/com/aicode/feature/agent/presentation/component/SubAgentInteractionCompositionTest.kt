package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aicode.feature.agent.presentation.SubAgentInstructionFailure
import com.aicode.feature.agent.presentation.SubAgentInstructionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentInteractionCompositionTest {
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test fun actualPageComposableReleasesOnCollapseBackgroundHostRevocationAndDisposal() = runTest {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.CREATED }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val composition = Composition(NoOpApplier(), recomposer)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        var expanded by mutableStateOf(false)
        var owned by mutableStateOf(true)
        var calls = 0
        var leases = 0
        var displayed = ""
        var frame = 0L
        var writes = 0
        val observer = Snapshot.registerGlobalWriteObserver { writes++ }
        fun settle() {
            repeat(100) {
                val before = writes
                Snapshot.sendApplyNotifications()
                runCurrent()
                if (clock.hasAwaiters) { clock.sendFrame(++frame * 1_000_000L); runCurrent() }
                Snapshot.sendApplyNotifications()
                runCurrent()
                if (before == writes && !recomposer.hasPendingWork && !clock.hasAwaiters) return
            }
            error("composition did not settle")
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    val page by rememberSubAgentPage("child", expanded, owned, "initial",
                        source = { calls++; flow { emit("full page"); awaitCancellation() } },
                        onFailure = { _, _ -> "error" }, onActive = { if (it) leases++ else leases-- })
                    displayed = page
                }
            }
            settle()
            assertEquals(0, calls)
            expanded = true; settle()
            assertEquals(0, calls)
            owner.registry.currentState = Lifecycle.State.STARTED; settle()
            assertEquals(1, calls)
            assertEquals(1, leases)
            assertEquals("full page", displayed)
            owner.registry.currentState = Lifecycle.State.CREATED; settle()
            assertEquals(0, leases)
            owner.registry.currentState = Lifecycle.State.STARTED; settle()
            assertEquals(2, calls)
            owned = false; settle()
            assertEquals(0, leases)
            assertEquals("initial", displayed)
            owned = true; settle()
            assertEquals(3, calls)
            expanded = false; settle()
            assertEquals(0, leases)
            expanded = true; settle()
            assertEquals(4, calls)
            composition.dispose(); runCurrent()
            assertEquals(0, leases)
        } finally {
            composition.dispose(); observer.dispose(); recomposer.cancel(); runner.cancel()
        }
    }

    @Test fun saveableDraftsAreChildIsolatedAndRestoreFailuresWithoutStuckSending() = runTest {
        var registry = SaveableStateRegistry(null) { true }
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(NoOpApplier(), recomposer)
        lateinit var first: SubAgentInstructionState
        lateinit var second: SubAgentInstructionState
        val root: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                key("a") { first = rememberSubAgentInstructionState("a") }
                key("b") { second = rememberSubAgentInstructionState("b") }
            }
        }
        var saved: Map<String, List<Any?>> = emptyMap()
        try {
            composition.setContent(root)
            first.edit("for a"); second.edit("for b")
            first.send { SubAgentInstructionResult(false, SubAgentInstructionFailure.SEND_FAILED) }
            val ack = CompletableDeferred<SubAgentInstructionResult>()
            val job = launch { second.send { ack.await() } }
            runCurrent()
            assertTrue(second.value.sending)
            saved = registry.performSave()
            job.cancel(); runCurrent()
            assertEquals("for b", second.value.draft)
        } finally { composition.dispose(); recomposer.cancel() }
        registry = SaveableStateRegistry(saved) { true }
        val restoredRecomposer = Recomposer(coroutineContext)
        val restored = Composition(NoOpApplier(), restoredRecomposer)
        try {
            restored.setContent(root)
            assertEquals("for a", first.value.draft)
            assertEquals(SubAgentInstructionFailure.SEND_FAILED, first.value.failure)
            assertEquals("for b", second.value.draft)
            assertFalse(second.value.sending)
        } finally { restored.dispose(); restoredRecomposer.cancel() }
    }
}
