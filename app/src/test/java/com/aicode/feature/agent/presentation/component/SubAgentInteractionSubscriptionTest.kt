package com.aicode.feature.agent.presentation.component

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentInteractionSubscriptionTest {
    @Test fun coldFactoryRequiresExpandedForegroundOwnedHostAndCancelsImmediatelyWhenHidden() = runTest {
        val visibility = MutableStateFlow(SubAgentPageVisibility(false, false, true))
        var calls = 0
        var activeSources = 0
        val leases = mutableListOf<Boolean>()
        val pages = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subAgentVisiblePageFlow(visibility, -1, source = {
                calls++
                flow {
                    activeSources++
                    try { emit(42); awaitCancellation() } finally { activeSources-- }
                }
            }, onFailure = { _, _ -> -2 }, onActive = { leases += it }).collect { pages += it }
        }
        runCurrent()
        assertEquals(0, calls)
        visibility.value = SubAgentPageVisibility(true, false, true)
        runCurrent()
        assertEquals(0, calls)
        visibility.value = SubAgentPageVisibility(true, true, false)
        runCurrent()
        assertEquals(0, calls)
        visibility.value = SubAgentPageVisibility(true, true, true)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, activeSources)
        repeat(4) { visibility.value = SubAgentPageVisibility(true, true, true) }
        runCurrent()
        assertEquals(1, calls)
        visibility.value = SubAgentPageVisibility(false, true, true)
        runCurrent()
        assertEquals(0, activeSources)
        assertEquals(-1, pages.last())
        visibility.value = SubAgentPageVisibility(true, true, true)
        runCurrent()
        visibility.value = SubAgentPageVisibility(true, false, true)
        runCurrent()
        assertEquals(0, activeSources)
        assertEquals(listOf(true, false, true, false), leases)
        job.cancelAndJoin()
    }

    @Test fun separateCardsHaveIndependentBalancedLeasesOnDisposal() = runTest {
        var count = 0
        val visibility = MutableStateFlow(SubAgentPageVisibility(true, true, true))
        fun collectOne() = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subAgentVisiblePageFlow(visibility, 0,
                source = { flow { emit(1); awaitCancellation() } }, onFailure = { _, _ -> -1 },
                onActive = { if (it) count++ else count-- }).collect {}
        }
        val first = collectOne()
        val second = collectOne()
        runCurrent()
        assertEquals(2, count)
        first.cancelAndJoin()
        assertEquals(1, count)
        second.cancelAndJoin()
        assertEquals(0, count)
    }

    @Test fun factoryAndCollectionErrorsAreVisibleAndLoadedContentIsRetained() = runTest {
        val visibility = MutableStateFlow(SubAgentPageVisibility(true, true, true))
        val pages = mutableListOf<String>()
        var calls = 0
        val leases = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subAgentVisiblePageFlow(visibility, "initial", source = {
                calls++
                if (calls == 1) error("factory")
                flow { emit("full body"); error("read") }
            }, onFailure = { previous, error -> "$previous:${error.message}" },
                onActive = { leases += it }).collect { pages += it }
        }
        runCurrent()
        assertEquals("initial:factory", pages.last())
        visibility.value = SubAgentPageVisibility(false, true, true)
        runCurrent()
        visibility.value = SubAgentPageVisibility(true, true, true)
        runCurrent()
        assertEquals("full body:read", pages.last())
        assertEquals(listOf(true, false, true, false), leases)
        job.cancelAndJoin()
    }
}
