package com.aicode.feature.agent.presentation.filebrowse

import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowseVisibilityBoundaryTest {
    @Test
    fun retryingAnUnavailableSourceAcrossAVisibilityBoundaryReadsAgainAndRejectsTheCancelledSnapshot() = runTest {
        val fixture = FileBrowseControllerTest.Fixture(this, Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val blocked = AtomicBoolean()
        val ready = AtomicBoolean()
        val root = WorkspacePathMapper.CONTAINER_ROOT
        fixture.onCapture = { if (!ready.get()) throw CancellationException("Source is not ready") }
        fixture.onList = { path ->
            if (path == root && blocked.compareAndSet(false, true)) {
                val owner = requireNotNull(WorkspaceExecutionContext.current())
                val cancellation = owner.onCancellation { cancelled.complete(Unit) }
                try {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                } finally {
                    cancellation?.dispose()
                    returned.complete(Unit)
                }
            }
        }
        fixture.controller.setScope(fixture.key())
        fixture.subscribe()
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { fixture.controller.state.first { it is FileBrowseState.Error } }
            }
            assertTrue(fixture.calls.isEmpty())
            ready.set(true)
            fixture.controller.refresh()
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { entered.await() } }
            assertEquals(1, fixture.calls[root])
            fixture.hide()
            advanceTimeBy(5_000)
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(5_000) { cancelled.await() } }
            assertTrue(fixture.controller.state.value is FileBrowseState.Error)
            fixture.tree[root] = listOf(FileEntry("fresh", false, 1, 1))
            fixture.show(resetScope = false)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    fixture.controller.state.first { state ->
                        state is FileBrowseState.Success && state.nodes.any { it.path == "$root/fresh" }
                    }
                }
            }
            assertEquals(2, fixture.calls[root])
            release.countDown()
            withContext(Dispatchers.Default) { withTimeout(5_000) { returned.await() } }
            runCurrent()
            assertTrue("$root/fresh" in fixture.paths())
            assertFalse("$root/a" in fixture.paths())
            assertEquals(2, fixture.calls[root])
        } finally {
            release.countDown()
        }
    }
}
