package com.aicode.feature.agent.domain.container

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SshResourceAcquisitionTest {
    @Test
    fun failedSetupClosesItsResourceAndRetainsTheOriginalFailure() {
        val resource = mockk<AutoCloseable>()
        val failure = IOException("setup")
        val closeFailure = IOException("close")
        every { resource.close() } throws closeFailure
        val actual = runCatching { withSshSetup(resource) { throw failure } }.exceptionOrNull()
        assertSame(failure, actual)
        assertEquals(listOf(closeFailure), actual!!.suppressed.toList())
        verify(exactly = 1) { resource.close() }
    }

    @Test
    fun successfulSetupTransfersOwnershipWithoutClosing() {
        val resource = mockk<AutoCloseable>()
        assertSame(resource, withSshSetup(resource) { it })
        verify(exactly = 0) { resource.close() }
    }

    @Test
    fun cancellationDuringOpenClosesAnUnpublishedResource() = runTest {
        val resource = mockk<AutoCloseable>(relaxed = true)
        val dispatcher = StandardTestDispatcher(testScheduler)
        var delivered = false
        lateinit var job: Job
        job = launch(start = CoroutineStart.LAZY) {
            acquireSshResource(dispatcher, close = { it: AutoCloseable -> it.close() }) {
                job.cancel(CancellationException("cancel while opening"))
                resource
            }
            delivered = true
        }
        job.start()
        testScheduler.advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse(delivered)
        verify(exactly = 1) { resource.close() }
    }

    @Test
    fun promptCancellationOnDispatcherReturnClosesTheDiscardedHandle() = runTest {
        val resource = mockk<AutoCloseable>(relaxed = true)
        val caller = StandardTestDispatcher(testScheduler)
        val opener = StandardTestDispatcher(testScheduler)
        var delivered = false
        val parent = Job()
        val job = launch(caller + parent) {
            acquireSshResource(opener, close = { it: AutoCloseable -> it.close() }) {
                // Queue cancellation before withContext dispatches the successful result back.
                launch(caller) { parent.cancel() }
                resource
            }
            delivered = true
        }
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(delivered)
        verify(exactly = 1) { resource.close() }
    }
}
