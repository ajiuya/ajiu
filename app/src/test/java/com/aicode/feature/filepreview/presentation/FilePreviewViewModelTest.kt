package com.aicode.feature.filepreview.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.aicode.feature.filepreview.data.PreviewSource
import com.aicode.feature.filepreview.data.PreviewSourceFactory
import com.aicode.feature.filepreview.domain.PreviewRequest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FilePreviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val factory = mockk<PreviewSourceFactory>()
    private val store = ViewModelStore()
    private val openings = mutableListOf<Job>()
    private val gates = mutableListOf<OpenGate>()
    private lateinit var viewModel: FilePreviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { factory.close() } just Runs
        viewModel = FilePreviewViewModel(factory)
        store.put("file-preview", viewModel)
    }

    @After
    fun tearDown() {
        try {
            viewModel.close()
            store.clear()
            gates.forEach { it.release.complete(Unit) }
            runTest(dispatcher) {
                openings.forEach { opening -> awaitIo { opening.join() } }
                runCurrent()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun replacedUninterruptibleOpenClosesItsLateSourceWithoutPublishingOrFailingTheNewRequest() = runTest(dispatcher) {
        val oldRequest = PreviewRequest("old.md")
        val newRequest = PreviewRequest("new.md")
        val oldValid = MutableStateFlow(true)
        val newValid = MutableStateFlow(true)
        val oldSource = source(oldValid)
        val newSource = source(newValid)
        val gate = gatedOpen(oldRequest) { oldSource }
        coEvery { factory.open(newRequest, any()) } returns newSource
        val observed = observeLoads()
        runCurrent()

        val oldOpening = open(oldRequest)
        runCurrent()
        awaitIo { gate.entered.await() }
        assertSame(PreviewLoad.Loading, viewModel.load.value)

        val newOpening = open(newRequest)
        runCurrent()
        awaitIo { newOpening.join() }
        runCurrent()
        val expected = PreviewLoad.Ready(newRequest, newSource)
        assertEquals(expected, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, expected), observed)
        assertTrue(oldOpening.isCancelled)
        assertFalse(oldOpening.isCompleted)

        gate.release.complete(Unit)
        awaitIo { oldOpening.join() }
        runCurrent()
        verify(exactly = 1) { oldSource.close() }
        verify(exactly = 0) { oldSource.valid }
        verify(exactly = 0) { newSource.close() }
        assertEquals(expected, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, expected), observed)

        oldValid.value = false
        runCurrent()
        assertEquals(expected, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, expected), observed)
        coVerify(exactly = 1) { factory.open(oldRequest, any()) }
        coVerify(exactly = 1) { factory.open(newRequest, any()) }
    }

    @Test
    fun readySourceInvalidationPublishesEnvironmentFailureAndClosesExactlyOnce() = runTest(dispatcher) {
        val request = PreviewRequest("report.md", "Report", "text/markdown", "/cache/report.copy")
        val valid = MutableStateFlow(true)
        val source = source(valid)
        coEvery { factory.open(request, any()) } returns source
        val observed = observeLoads()
        runCurrent()

        val opening = open(request)
        runCurrent()
        awaitIo { opening.join() }
        runCurrent()
        val ready = PreviewLoad.Ready(request, source)
        assertEquals(ready, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, ready), observed)
        verify(exactly = 0) { source.close() }

        valid.value = false
        runCurrent()
        val failed = PreviewLoad.Failed(request, environmentChanged = true)
        assertEquals(failed, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, ready, failed), observed)
        verify(exactly = 1) { source.close() }

        viewModel.close()
        runCurrent()
        assertSame(PreviewLoad.Loading, viewModel.load.value)
        valid.value = true
        valid.value = false
        runCurrent()
        assertEquals(listOf(PreviewLoad.Loading, ready, failed, PreviewLoad.Loading), observed)
        verify(exactly = 1) { source.close() }
        coVerify(exactly = 1) { factory.open(request, any()) }
    }

    @Test
    fun closeWhileOpenIsUninterruptibleCleansTheLateSourceAndNeverPublishesIt() = runTest(dispatcher) {
        val request = PreviewRequest("late.pdf")
        val valid = MutableStateFlow(true)
        val source = source(valid)
        val gate = gatedOpen(request) { source }
        val observed = observeLoads()
        runCurrent()

        val opening = open(request)
        runCurrent()
        awaitIo { gate.entered.await() }
        viewModel.close()
        runCurrent()
        assertTrue(opening.isCancelled)
        assertFalse(opening.isCompleted)
        verify(exactly = 0) { source.close() }

        gate.release.complete(Unit)
        awaitIo { opening.join() }
        runCurrent()
        assertSame(PreviewLoad.Loading, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading), observed)
        verify(exactly = 1) { source.close() }
        verify(exactly = 0) { source.valid }

        valid.value = false
        viewModel.close()
        runCurrent()
        assertSame(PreviewLoad.Loading, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading), observed)
        verify(exactly = 1) { source.close() }
        coVerify(exactly = 1) { factory.open(request, any()) }
    }

    @Test
    fun failingReplacementDoesNotReuseTheOldSourceOrLetItsWatcherOverwriteTheFailure() = runTest(dispatcher) {
        val oldRequest = PreviewRequest("previous.html")
        val failedRequest = PreviewRequest("missing.html", "Missing", "text/html", "/cache/missing.copy")
        val oldValid = MutableStateFlow(true)
        val oldSource = source(oldValid)
        coEvery { factory.open(oldRequest, any()) } returns oldSource
        val gate = gatedOpen(failedRequest) { throw IOException("Preview unavailable") }
        val observed = observeLoads()
        runCurrent()

        val oldOpening = open(oldRequest)
        runCurrent()
        awaitIo { oldOpening.join() }
        runCurrent()
        val ready = PreviewLoad.Ready(oldRequest, oldSource)
        assertEquals(ready, viewModel.load.value)

        val failedOpening = open(failedRequest)
        runCurrent()
        awaitIo { gate.entered.await() }
        assertSame(PreviewLoad.Loading, viewModel.load.value)
        verify(exactly = 1) { oldSource.close() }
        oldValid.value = false
        runCurrent()
        assertSame(PreviewLoad.Loading, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, ready, PreviewLoad.Loading), observed)

        gate.release.complete(Unit)
        awaitIo { failedOpening.join() }
        runCurrent()
        val failed = PreviewLoad.Failed(failedRequest, environmentChanged = false)
        assertEquals(failed, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, ready, PreviewLoad.Loading, failed), observed)
        verify(exactly = 1) { oldSource.close() }
        coVerify(exactly = 1) { factory.open(oldRequest, any()) }
        coVerify(exactly = 1) { factory.open(failedRequest, any()) }

        oldValid.value = true
        oldValid.value = false
        runCurrent()
        assertEquals(failed, viewModel.load.value)
        assertEquals(listOf(PreviewLoad.Loading, ready, PreviewLoad.Loading, failed), observed)
    }

    @Test
    fun cachedReplacementClosesThePreviousAuthorizationSourceBeforeOpening() = runTest(dispatcher) {
        val guest = PreviewRequest("guest.md")
        val cached = PreviewRequest("cached.md", cachedPath = "/cache/cached.md")
        val events = mutableListOf<String>()
        coEvery { factory.open(guest, any()) } throws IOException("Authorization required")
        every { factory.close() } answers { events += "close" }
        coEvery { factory.open(cached, any()) } answers {
            events += "open"
            source(MutableStateFlow(true))
        }
        val first = open(guest)
        runCurrent()
        awaitIo { first.join() }
        runCurrent()
        assertTrue(viewModel.load.value is PreviewLoad.Failed)
        assertTrue(events.isEmpty())
        val second = open(cached)
        assertEquals(listOf("close"), events)
        runCurrent()
        awaitIo { second.join() }
        runCurrent()
        assertEquals(listOf("close", "open"), events)
        assertTrue(viewModel.load.value is PreviewLoad.Ready)
    }

    private fun source(valid: MutableStateFlow<Boolean>): PreviewSource = mockk<PreviewSource>().also { source ->
        every { source.valid } returns valid
        every { source.close() } just Runs
    }

    private fun open(request: PreviewRequest): Job {
        viewModel.open(request)
        return requireNotNull(viewModel.viewModelScope.coroutineContext[Job]).children.single { it.isActive }
            .also { openings += it }
    }

    private fun TestScope.observeLoads(): MutableList<PreviewLoad> = mutableListOf<PreviewLoad>().also { observed ->
        backgroundScope.launch(dispatcher) { viewModel.load.collect { observed += it } }
    }

    private fun gatedOpen(request: PreviewRequest, result: () -> PreviewSource): OpenGate = OpenGate().also { gate ->
        gates += gate
        coEvery { factory.open(request, any()) } coAnswers {
            withContext(NonCancellable) {
                gate.entered.complete(Unit)
                gate.release.await()
            }
            result()
        }
    }

    private suspend fun awaitIo(block: suspend () -> Unit) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }
    }

    private class OpenGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }
}
