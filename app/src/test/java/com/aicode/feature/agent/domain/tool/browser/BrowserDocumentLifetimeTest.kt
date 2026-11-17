package com.aicode.feature.agent.domain.tool.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserDocumentLifetimeTest {
    @Test
    fun navigationCancelsJsCallbacksAndDocumentOperationsWithoutCancellingTheCaller() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val original = lifetime.generation
        val js = CompletableDeferred<String>()
        lifetime.pendingJsCalls["old"] = js
        val callback = lifetime.callback<String>(original)
        var released = false
        val operation = async {
            lifetime.operation {
                try { awaitCancellation() } finally { released = true }
            }
        }
        runCurrent()
        lifetime.invalidate()
        runCurrent()
        assertEquals(original + 1, lifetime.generation)
        assertTrue(js.isCancelled)
        assertTrue(callback.isCancelled)
        assertTrue(operation.isCancelled)
        assertTrue(released)
        assertTrue(lifetime.pendingJsCalls.isEmpty())
        assertFalse(lifetime.destroyed)
        assertEquals("new", lifetime.operation { "new" })
    }

    @Test
    fun lateCallbackCannotCompleteEitherTheCancelledOrReplacementDocumentWaiter() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val oldGeneration = lifetime.generation
        val old = lifetime.callback<String>(oldGeneration)
        lifetime.invalidate()
        val current = lifetime.callback<String>(lifetime.generation)
        lifetime.complete(oldGeneration, old, "stale")
        lifetime.complete(oldGeneration, current, "wrong-generation")
        assertTrue(old.isCancelled)
        assertFalse(current.isCompleted)
        lifetime.complete(lifetime.generation, current, "current")
        assertEquals("current", current.await())
        lifetime.release(old)
        lifetime.release(current)
    }

    @Test
    fun releasedCallbackIgnoresLateCompletionAndDoesNotRetainAWaiter() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val generation = lifetime.generation
        val released = lifetime.callback<Unit>(generation)
        lifetime.release(released)
        lifetime.complete(generation, released, Unit)
        assertTrue(released.isCancelled)
        val next = lifetime.callback<String>(generation)
        lifetime.complete(generation, next, "next")
        assertEquals("next", next.await())
        lifetime.release(next)
    }

    @Test
    fun capturesUseOneMutexButAnotherWebViewCanCaptureIndependently() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val unrelated = BrowserDocumentLifetime()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            lifetime.capture {
                events += "first-start"
                release.await()
                events += "first-end"
            }
        }
        runCurrent()
        val second = async { lifetime.capture { events += "second" } }
        runCurrent()
        unrelated.capture { events += "unrelated" }
        assertEquals(listOf("first-start", "unrelated"), events)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first-start", "unrelated", "first-end", "second"), events)
    }

    @Test
    fun navigationCancelsBothTheCaptureOwnerAndQueuedCapturesThenAllowsTheNewDocument() = runTest {
        val lifetime = BrowserDocumentLifetime()
        var ownerReleased = false
        var queuedEntered = false
        val owner = async {
            lifetime.capture {
                try { awaitCancellation() } finally { ownerReleased = true }
            }
        }
        runCurrent()
        val queued = async { lifetime.capture { queuedEntered = true } }
        runCurrent()
        lifetime.invalidate()
        runCurrent()
        assertTrue(owner.isCancelled)
        assertTrue(queued.isCancelled)
        assertTrue(ownerReleased)
        assertFalse(queuedEntered)
        assertEquals(1L, lifetime.capture { it })
    }

    @Test
    fun queuedCallerCancellationDoesNotCancelTheCaptureOwnerOrLeaveTheMutexLocked() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val release = CompletableDeferred<Unit>()
        val owner = launch { lifetime.capture { release.await() } }
        runCurrent()
        val queued = launch { lifetime.capture { fail("Cancelled waiter entered capture") } }
        runCurrent()
        queued.cancelAndJoin()
        assertTrue(owner.isActive)
        release.complete(Unit)
        owner.join()
        assertEquals("next", lifetime.capture { "next" })
    }

    @Test
    fun captureFailureReleasesItsMutex() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val failure = IllegalStateException("draw failed")
        try {
            lifetime.capture<Unit> { throw failure }
            fail("Failure must propagate")
        } catch (actual: IllegalStateException) {
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
        assertEquals("recovered", lifetime.capture { "recovered" })
    }

    @Test
    fun closeCancelsDocumentWorkAndRejectsFurtherOperationsOnlyForThisWebView() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val unrelated = BrowserDocumentLifetime()
        val js = CompletableDeferred<String>()
        lifetime.pendingJsCalls["old"] = js
        val callback = lifetime.callback<String>(lifetime.generation)
        val owner = async { lifetime.capture { awaitCancellation() } }
        runCurrent()
        lifetime.close()
        runCurrent()
        assertTrue(lifetime.destroyed)
        assertTrue(js.isCancelled)
        assertTrue(callback.isCancelled)
        assertTrue(owner.isCancelled)
        assertTrue(lifetime.callback<Unit>(lifetime.generation).isCancelled)
        try {
            lifetime.operation { fail("Closed WebView must not run operations") }
            fail("Closed WebView must reject operations")
        } catch (_: CancellationException) {
            assertEquals("unrelated", unrelated.capture { "unrelated" })
        }
    }

    @Test
    fun generationCheckRejectsAValueProducedAfterNavigation() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val result = async {
            lifetime.operation {
                lifetime.invalidate()
                "stale"
            }
        }
        runCurrent()
        assertTrue(result.isCancelled)
    }

    @Test
    fun internalDocumentCancellationDropsOnlyThePreviewFrame() = runTest {
        val lifetime = BrowserDocumentLifetime()
        val preview = async {
            captureBrowserPreviewFrame {
                lifetime.capture { awaitCancellation() }
            }
        }
        runCurrent()
        lifetime.invalidate()
        assertNull(preview.await())
        assertEquals("fresh", captureBrowserPreviewFrame { lifetime.capture { "fresh" } })
    }

    @Test
    fun externalPreviewCancellationStillPropagatesToItsCaller() = runTest {
        val lifetime = BrowserDocumentLifetime()
        var returned = false
        val preview = launch(start = CoroutineStart.UNDISPATCHED) {
            captureBrowserPreviewFrame { lifetime.capture { awaitCancellation() } }
            returned = true
        }
        preview.cancelAndJoin()
        assertTrue(preview.isCancelled)
        assertFalse(returned)
        assertEquals("fresh", lifetime.capture { "fresh" })
    }
}
