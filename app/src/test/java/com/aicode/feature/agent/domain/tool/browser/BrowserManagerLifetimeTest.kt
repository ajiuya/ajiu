package com.aicode.feature.agent.domain.tool.browser

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.Base64
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebViewFeature
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BrowserManagerLifetimeTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var manager: BrowserManager
    private lateinit var files: FileAccessProvider
    private lateinit var tab: FakeTab
    private val allocations = mutableListOf<Bitmap>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        assertEquals("AQ==", Base64.encodeToString(byteArrayOf(1), Base64.NO_WRAP))
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.w(any(), any(), any()) } returns Unit
        mockkStatic(WebViewFeature::class)
        every { WebViewFeature.isFeatureSupported(any()) } returns false
        // 静态 mock 录制前完成 shadow 类初始化，避免首次用例把 <clinit> 也录为调用。
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).recycle()
        mockkStatic(Bitmap::class)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } answers {
            callOriginal().also { allocations += it }
        }
        files = mockk(relaxed = true)
        manager = BrowserManager(ApplicationProvider.getApplicationContext<Context>(), files, mockk<PathHomeResolver>())
        tab = addTab("existing")
    }

    @After
    fun tearDown() {
        try {
            if (::manager.isInitialized) manager.destroy()
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            allocations.filterNot { it.isRecycled }.forEach { it.recycle() }
            unmockkStatic(Bitmap::class)
            unmockkStatic(WebViewFeature::class)
            unmockkObject(FileLogger)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun domWaitSuccessAndJsDeadlineResultRemoveTheirRequestAndIssueCleanup() = runTest(dispatcher) {
        for (result in listOf("true", "false")) {
            val wait = async { manager.wait("domStable", 1_000, tab.id) }
            runCurrent()
            val id = tab.lifetime.pendingJsCalls.keys.single()
            tab.bridge.resolve(id, result)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(result == "true", wait.await())
            assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
            assertTrue(tab.domWaits.isEmpty())
            assertTrue(tab.scripts.contains(DomStableScript.cleanup(JsonPrimitive(id).toString())))
        }
        assertEquals(2, tab.scripts.count { it.contains("new MutationObserver") })
        verify(exactly = 0) { tab.view.loadUrl(any<String>()) }
    }

    @Test
    fun domWaitNativeDeadlineCleansUpWithoutPollingOrSerializingThePage() = runTest(dispatcher) {
        val wait = async { manager.wait("domStable", 1_000, tab.id) }
        runCurrent()
        val id = tab.lifetime.pendingJsCalls.keys.single()
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(wait.await())
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
        assertTrue(tab.domWaits.isEmpty())
        assertEquals(2, tab.scripts.size)
        assertEquals(DomStableScript.cleanup(JsonPrimitive(id).toString()), tab.scripts.last())
        assertFalse(tab.scripts.any { it.contains("innerHTML") })
    }

    @Test
    fun cancellingDomWaitCleansUpAndDoesNotConsumeALateBridgeResult() = runTest(dispatcher) {
        val wait = launch { manager.wait("domStable", 10_000, tab.id) }
        runCurrent()
        val id = tab.lifetime.pendingJsCalls.keys.single()
        wait.cancelAndJoin()
        tab.bridge.resolve(id, "true")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(wait.isCancelled)
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
        assertTrue(tab.domWaits.isEmpty())
        assertEquals(DomStableScript.cleanup(JsonPrimitive(id).toString()), tab.scripts.last())
    }

    @Test
    fun zeroDomDeadlineDoesNotInstallAnObserver() = runTest(dispatcher) {
        assertFalse(manager.wait("domStable", 0, tab.id))
        assertFalse(manager.wait("domStable", -1, tab.id))
        assertTrue(tab.scripts.isEmpty())
        assertTrue(tab.domWaits.isEmpty())
    }

    @Test
    fun scriptInstallationFailureStillRemovesAndCleansItsDomRequest() = runTest(dispatcher) {
        val failure = IllegalStateException("install failed")
        every { tab.view.evaluateJavascript(match { it.contains("new MutationObserver") }, any()) } throws failure
        try {
            manager.wait("domStable", 1000, tab.id)
            org.junit.Assert.fail("Installation failure must propagate")
        } catch (actual: IllegalStateException) {
            assertEquals(failure.javaClass, actual.javaClass)
            assertEquals(failure.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
        assertTrue(tab.domWaits.isEmpty())
        assertEquals(1, tab.scripts.count { it.contains("var r=window.__bicodeDomWaits") })
    }

    @Test
    fun navigationCleansTheObserverAndCancelsTheOldDocumentWaitBeforeStartingANewOne() = runTest(dispatcher) {
        val old = async { manager.wait("domStable", 10_000, tab.id) }
        runCurrent()
        val oldId = tab.lifetime.pendingJsCalls.keys.single()
        val original = tab.lifetime.generation
        startPage(tab, "about:blank#next")
        runCurrent()
        assertEquals(original + 1, tab.lifetime.generation)
        assertTrue(old.isCancelled)
        assertTrue(tab.domWaits.isEmpty())
        assertTrue(tab.scripts.contains(DomStableScript.cleanup(JsonPrimitive(oldId).toString())))
        val fresh = async { manager.wait("domStable", 10_000, tab.id) }
        runCurrent()
        val freshId = tab.lifetime.pendingJsCalls.keys.single()
        tab.bridge.resolve(oldId, "true")
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(fresh.isCompleted)
        tab.bridge.resolve(freshId, "true")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fresh.await())
    }

    @Test
    fun aTitleCallbackFromTheOldPageCannotSetTitleOrCompleteTheNewNavigation() = runTest(dispatcher) {
        val old = async { manager.reload(tab.id) }
        runCurrent()
        startPage(tab, "about:blank#old")
        tab.client.onPageFinished(tab.view, "about:blank#old")
        val oldTitle = tab.callbacks.last()
        val fresh = async { manager.reload(tab.id) }
        runCurrent()
        startPage(tab, "about:blank#new")
        oldTitle.onReceiveValue("\"STALE\"")
        runCurrent()
        assertTrue(old.isCancelled)
        assertFalse(fresh.isCompleted)
        assertEquals("", manager.getTitle(tab.id))
        tab.client.onPageFinished(tab.view, "about:blank#old")
        assertSame(oldTitle, tab.callbacks.last())
        tab.client.onPageFinished(tab.view, "about:blank#new")
        tab.callbacks.last().onReceiveValue("\"NEW\"")
        assertEquals("about:blank#new", fresh.await())
        assertEquals("NEW", manager.getTitle(tab.id))
        assertNull(tab.field("loadDeferred"))
    }

    @Test
    fun navigationTimeoutAndCallerCancellationReleaseOnlyTheirOwnLoadWaiter() = runTest(dispatcher) {
        val timedOut = async {
            try {
                manager.reload(tab.id)
                false
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                true
            }
        }
        runCurrent()
        val firstLoad = tab.field("loadDeferred") as CompletableDeferred<*>
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(timedOut.await())
        assertTrue(firstLoad.isCancelled)
        assertNull(tab.field("loadDeferred"))
        val cancelled = launch { manager.reload(tab.id) }
        runCurrent()
        val secondLoad = tab.field("loadDeferred") as CompletableDeferred<*>
        cancelled.cancelAndJoin()
        assertTrue(secondLoad.isCancelled)
        assertNull(tab.field("loadDeferred"))
    }

    @Test
    fun failedAsyncEvaluationRemovesItsPendingBridgeRequest() = runTest(dispatcher) {
        val evaluation = async {
            try {
                manager.evaluateJavaScript("Promise.reject('failed')", tab.id)
                org.junit.Assert.fail("Bridge rejection must propagate")
            } catch (actual: RuntimeException) {
                assertEquals("failed", actual.message)
            }
        }
        runCurrent()
        val id = tab.lifetime.pendingJsCalls.keys.single()
        tab.bridge.reject(id, "failed")
        shadowOf(Looper.getMainLooper()).idle()
        evaluation.await()
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
    }

    @Test
    fun asyncEvaluationTimeoutAndCallerCancellationRemoveTheirBridgeRequests() = runTest(dispatcher) {
        val timeout = async {
            try {
                manager.evaluateJavaScript("new Promise(function(){})", tab.id)
                false
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                true
            }
        }
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(timeout.await())
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
        val cancelled = launch { manager.evaluateJavaScript("new Promise(function(){})", tab.id) }
        runCurrent()
        val id = tab.lifetime.pendingJsCalls.keys.single()
        cancelled.cancelAndJoin()
        tab.bridge.resolve(id, "stale")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
    }

    @Test
    fun historyWaitCannotReadTheNextDocumentAfterBeingSuperseded() = runTest(dispatcher) {
        every { tab.view.canGoBack() } returns true
        val history = async { manager.goBack(tab.id) }
        runCurrent()
        val oldLoad = tab.field("loadDeferred") as CompletableDeferred<*>
        val replacement = launch { manager.reload(tab.id) }
        runCurrent()
        val newLoad = tab.field("loadDeferred")
        assertTrue(oldLoad.isCancelled)
        advanceTimeBy(101)
        runCurrent()
        assertTrue(history.isCancelled)
        assertSame(newLoad, tab.field("loadDeferred"))
        verify(exactly = 0) { tab.view.evaluateJavascript(any(), any()) }
        replacement.cancelAndJoin()
        assertNull(tab.field("loadDeferred"))
    }

    @Test
    fun explicitNavigationCancelsOldWorkBeforeTheWebViewEmitsPageStarted() = runTest(dispatcher) {
        val old = async { manager.getViewportInfo(tab.id) }
        runCurrent()
        val late = tab.callbacks.single()
        val navigation = launch { manager.navigate("https://example.test/next", tab.id) }
        runCurrent()
        assertTrue(old.isCancelled)
        assertTrue(tab.field("awaitingPageStart") as Boolean)
        late.onReceiveValue("stale")
        verify(exactly = 1) { tab.view.loadUrl("https://example.test/next") }
        navigation.cancelAndJoin()
        assertNull(tab.field("loadDeferred"))
    }

    @Test
    fun pageStartKeepsTheRequestedNavigationWaiterWhileCancellingOldJsAndDeferredCallbacks() = runTest(dispatcher) {
        val js = async { manager.evaluateJavaScript("Promise.resolve(1)", tab.id) }
        val text = async { manager.getViewportInfo(tab.id) }
        runCurrent()
        val staleCallback = tab.callbacks.last()
        val reload = async { manager.reload(tab.id) }
        runCurrent()
        val load = tab.field("loadDeferred")
        startPage(tab, "about:blank#loaded")
        assertSame(load, tab.field("loadDeferred"))
        staleCallback.onReceiveValue("\"stale\"")
        tab.client.onPageFinished(tab.view, "about:blank#loaded")
        tab.callbacks.last().onReceiveValue("\"loaded\"")
        assertEquals("about:blank#loaded", reload.await())
        assertTrue(js.isCancelled)
        assertTrue(text.isCancelled)
        assertTrue(tab.lifetime.pendingJsCalls.isEmpty())
    }

    @Test
    fun navigationBetweenPollingChecksCannotRunTheNextCheckInTheNewDocument() = runTest(dispatcher) {
        every { tab.view.evaluateJavascript(any(), any()) } answers {
            tab.scripts += firstArg<String>()
            secondArg<ValueCallback<String>?>()?.onReceiveValue("false")
        }
        val old = async { manager.wait("selector=#ready", 10_000, tab.id) }
        runCurrent()
        val checks = tab.scripts.size
        startPage(tab, "about:blank#new")
        advanceTimeBy(600)
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(checks, tab.scripts.size)
    }

    @Test
    fun cancellingAVisualWaitDoesNotFallThroughToDrawingAndIgnoresItsLateCallback() = runTest(dispatcher) {
        val capture = launch { manager.screenshot(tab.id) }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        val callback = tab.visualCallbacks.single()
        capture.cancelAndJoin()
        callback.onComplete(0)
        runCurrent()
        assertTrue(capture.isCancelled)
        verify(exactly = 0) { tab.view.draw(any()) }
        assertTrue(allocations.isEmpty())
    }

    @Test
    fun navigationDuringSecondVisualWaitRecyclesItsBitmapAndRestoresLayerBeforeTheNextPreview() = runTest(dispatcher) {
        every { tab.view.postVisualStateCallback(any(), any()) } answers {
            val callback = secondArg<WebView.VisualStateCallback>()
            tab.visualCallbacks += callback
            if (tab.visualCallbacks.size == 1) callback.onComplete(firstArg())
        }
        val capture = async { manager.screenshot(tab.id) }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        assertEquals(2, tab.visualCallbacks.size)
        assertEquals(1, allocations.size)
        startPage(tab, "about:blank#new")
        runCurrent()
        tab.visualCallbacks.last().onComplete(0)
        assertTrue(capture.isCancelled)
        assertTrue(allocations.single().isRecycled)
        verify { tab.view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        verify(exactly = 0) { tab.view.draw(any()) }
        tab.field("loading", false)
        val preview = manager.captureThumbnail(tab.id)!!
        assertEquals(480, preview.height)
        assertFalse(preview.isRecycled)
        preview.recycle()
    }

    @Test
    fun thumbnailWaitsForTheFormalScreenshotMutexAndCallerCancellationDoesNotChangeTheLayer() = runTest(dispatcher) {
        val capture = launch { manager.screenshot(tab.id) }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        assertEquals(1, tab.visualCallbacks.size)
        val preview = async { manager.captureThumbnail(tab.id) }
        runCurrent()
        verify(exactly = 0) { tab.view.draw(any()) }
        preview.cancelAndJoin()
        assertTrue(capture.isActive)
        capture.cancelAndJoin()
        verify(exactly = 0) { tab.view.setLayerType(any(), any()) }
    }

    @Test
    fun cancellingAThumbnailDuringDrawRecyclesOnlyItsUndeliveredBitmap() = runTest(dispatcher) {
        val shared = manager.captureThumbnail(tab.id)!!
        val preview = async { manager.captureThumbnail(tab.id) }
        every { tab.view.draw(any<Canvas>()) } answers { preview.cancel() }
        runCurrent()
        assertTrue(preview.isCancelled)
        assertEquals(2, allocations.size)
        assertTrue(allocations.last().isRecycled)
        assertFalse(shared.isRecycled)
        shared.recycle()
    }

    @Test
    fun documentChangeDuringThumbnailDrawDropsTheFrameWithoutCancellingThePreviewCaller() = runTest(dispatcher) {
        every { tab.view.draw(any<Canvas>()) } answers { startPage(tab, "about:blank#new") }
        assertNull(manager.captureThumbnail(tab.id))
        assertTrue(allocations.single().isRecycled)
        verify { tab.view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        assertFalse(tab.lifetime.destroyed)
    }

    @Test
    fun closingAHiddenTabCancelsItsCapturesDetachesTheActualParentAndDoesNotRecycleTransferredUiFrames() = runTest(dispatcher) {
        val shared = manager.captureThumbnail(tab.id)!!
        addTab("survivor")
        val parent = mockk<ViewGroup>(relaxed = true)
        every { tab.view.parent } returns parent
        val capture = async { manager.screenshot(tab.id) }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        val late = tab.visualCallbacks.single()
        val queued = async { manager.captureThumbnail(tab.id) }
        runCurrent()
        assertTrue(manager.closeTab(tab.id))
        runCurrent()
        late.onComplete(0)
        assertTrue(capture.isCancelled)
        assertNull(queued.await())
        assertTrue(tab.lifetime.destroyed)
        assertFalse(shared.isRecycled)
        assertEquals(listOf("survivor"), manager.listTabs().map { it.id })
        verifyOrder {
            parent.removeView(tab.view)
            tab.view.stopLoading()
            tab.view.removeJavascriptInterface("__browserBridge__")
            tab.view.destroy()
        }
        shared.recycle()
    }

    @Test
    fun destroyCancelsEveryTabAndDetachesBothVisibleAndHiddenParentsBeforeDestroyingTheirViews() = runTest(dispatcher) {
        val other = addTab("other")
        val firstParent = mockk<ViewGroup>(relaxed = true)
        val secondParent = mockk<ViewGroup>(relaxed = true)
        every { tab.view.parent } returns firstParent
        every { other.view.parent } returns secondParent
        val wait = async { manager.wait("domStable", 10_000, tab.id) }
        val callback = async { manager.getViewportInfo(other.id) }
        runCurrent()
        val late = other.callbacks.single()
        manager.destroy()
        runCurrent()
        late.onReceiveValue("stale")
        assertTrue(wait.isCancelled)
        assertTrue(callback.isCancelled)
        assertTrue(tab.lifetime.destroyed)
        assertTrue(other.lifetime.destroyed)
        assertTrue(tab.domWaits.isEmpty())
        assertTrue(manager.listTabs().isEmpty())
        verifyOrder { firstParent.removeView(tab.view); tab.view.destroy() }
        verifyOrder { secondParent.removeView(other.view); other.view.destroy() }
    }

    @Test
    fun oldBridgeCannotDeliverToAReplacementWebViewWithTheSameTabId() = runTest(dispatcher) {
        val oldBridge = tab.bridge
        val original = CompletableDeferred<String>()
        tab.lifetime.pendingJsCalls["same-call"] = original
        manager.destroy()
        tab = addTab("existing")
        val replacement = CompletableDeferred<String>()
        tab.lifetime.pendingJsCalls["same-call"] = replacement
        oldBridge.resolve("same-call", "stale")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(original.isCancelled)
        assertFalse(replacement.isCompleted)
        tab.bridge.resolve("same-call", "fresh")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("fresh", replacement.await())
    }

    @Test
    fun visualTimeoutFallsBackOnceButLateCallbacksCannotReopenTheCompletedCapture() = runTest(dispatcher) {
        val capture = async { manager.screenshot(tab.id) }
        runCurrent()
        advanceTimeBy(2251)
        assertEquals("image/jpeg", capture.await()!!.mimeType)
        assertEquals(2, tab.visualCallbacks.size)
        assertTrue(allocations.single().isRecycled)
        tab.visualCallbacks.forEach { it.onComplete(0) }
        runCurrent()
        verify(exactly = 1) { tab.view.draw(any()) }
    }

    @Test
    fun formalScreenshotKeepsViewportDimensionsAndNoWrapJpegProtocolOffMain() = runTest(dispatcher) {
        completeVisualsImmediately()
        var encodedOffMain = false
        var flags = -1
        var bytes: ByteArray? = null
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } answers {
            encodedOffMain = Looper.myLooper() != Looper.getMainLooper()
            flags = secondArg()
            bytes = firstArg()
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        try {
            val capture = async { manager.screenshot(tab.id) }
            runCurrent()
            advanceTimeBy(251)
            val image = capture.await()!!
            assertTrue(encodedOffMain)
            assertEquals(Base64.NO_WRAP, flags)
            assertEquals("image/jpeg", image.mimeType)
            assertFalse(image.base64Data.contains('\n'))
            org.junit.Assert.assertArrayEquals(bytes!!, java.util.Base64.getDecoder().decode(image.base64Data))
            val bitmap = allocations.single()
            assertEquals(1200, bitmap.width)
            assertEquals(2400, bitmap.height)
            assertTrue(bitmap.isRecycled)
            verify(exactly = 1) { tab.view.draw(any()) }
            verify(exactly = 0) { files.writeBytes(any(), any(), any()) }
        } finally {
            unmockkStatic(Base64::class)
        }
    }

    @Test
    fun closingDuringEncodingWaitsForTheWorkerBeforeRecyclingOnlyItsOwnedBitmap() = runTest(dispatcher) {
        val shared = manager.captureThumbnail(tab.id)!!
        addTab("survivor")
        completeVisualsImmediately()
        val encoding = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var delivered = false
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } answers {
            encoding.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Encoding test was not released" }
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        val capture = launch { manager.screenshot(tab.id); delivered = true }
        try {
            runCurrent()
            advanceTimeBy(251)
            encoding.await()
            val owned = allocations.last()
            assertTrue(manager.closeTab(tab.id))
            runCurrent()
            assertFalse(owned.isRecycled)
            assertFalse(shared.isRecycled)
            assertFalse(delivered)
            release.countDown()
            capture.join()
            assertTrue(capture.isCancelled)
            assertTrue(owned.isRecycled)
            assertFalse(shared.isRecycled)
            assertFalse(delivered)
        } finally {
            release.countDown()
            capture.cancelAndJoin()
            unmockkStatic(Base64::class)
            shared.recycle()
        }
    }

    @Test
    fun encodingFailureRecyclesItsOwnBitmapAndReleasesTheCaptureMutex() = runTest(dispatcher) {
        completeVisualsImmediately()
        val failure = IllegalStateException("encoding failed")
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any<ByteArray>(), any()) } throws failure
        try {
            val capture = async {
                try {
                    manager.screenshot(tab.id)
                    org.junit.Assert.fail("Encoding failure must propagate")
                } catch (actual: IllegalStateException) {
                    assertEquals(failure.javaClass, actual.javaClass)
                    assertEquals(failure.message, actual.message)
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
                }
            }
            runCurrent()
            advanceTimeBy(251)
            capture.await()
            assertTrue(allocations.single().isRecycled)
            val preview = manager.captureThumbnail(tab.id)!!
            assertEquals(480, preview.height)
            assertFalse(preview.isRecycled)
            preview.recycle()
        } finally {
            unmockkStatic(Base64::class)
        }
    }

    @Test
    fun cancellationAtTheMainToCallerHandoffRecyclesTheUndeliveredPreview() = runTest(dispatcher) {
        val caller = StandardTestDispatcher(testScheduler, "preview-caller")
        var delivered = false
        val preview = async(caller) { manager.captureThumbnail(tab.id).also { delivered = true } }
        every { tab.view.draw(any<Canvas>()) } answers {
            launch(caller) { preview.cancel() }
            Unit
        }
        runCurrent()
        assertTrue(preview.isCancelled)
        assertFalse(delivered)
        assertTrue(allocations.single().isRecycled)
        verify { tab.view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
    }

    private fun completeVisualsImmediately() {
        every { tab.view.postVisualStateCallback(any(), any()) } answers {
            secondArg<WebView.VisualStateCallback>().onComplete(firstArg())
        }
    }

    private fun addTab(id: String): FakeTab {
        val view = mockk<WebView>(relaxed = true) {
            every { width } returns 1200
            every { height } returns 2400
            every { progress } returns 100
            every { url } returns "about:blank"
            every { layerType } returns View.LAYER_TYPE_HARDWARE
            every { settings } returns mockk(relaxed = true) {
                every { userAgentString } returns "fake-test-webview"
            }
        }
        val type = BrowserManager::class.java.declaredClasses.single { it.simpleName == "TabHolder" }
        val holder = type.declaredConstructors.single { it.parameterCount == 15 }.apply { isAccessible = true }
            .newInstance(id, view, mockk<ContextThemeWrapper>(), mockk<MutableContextWrapper>(), "about:blank", "", false, null, false,
                ArrayDeque<BrowserConsoleEntry>(), null, null, null, ConcurrentHashMap<String, CompletableDeferred<String>>(), null)
        @Suppress("UNCHECKED_CAST")
        val tabs = BrowserManager::class.java.getDeclaredField("tabs").apply { isAccessible = true }
            .get(manager) as MutableList<Any>
        tabs += holder
        val clientSlot = slot<WebViewClient>()
        every { view.webViewClient = capture(clientSlot) } returns Unit
        val fake = FakeTab(id, view, holder)
        every { view.evaluateJavascript(any(), any()) } answers {
            fake.scripts += firstArg<String>()
            secondArg<ValueCallback<String>?>()?.let { fake.callbacks += it }
        }
        every { view.postVisualStateCallback(any(), any()) } answers {
            fake.visualCallbacks += secondArg<WebView.VisualStateCallback>()
        }
        BrowserManager::class.java.getDeclaredMethod("configureWebView", type).apply { isAccessible = true }.invoke(manager, holder)
        fake.client = clientSlot.captured
        fake.bridge = manager.BrowserJsBridge(id)
        clearMocks(view, answers = false)
        return fake
    }

    private fun startPage(tab: FakeTab, url: String) {
        every { tab.view.url } returns url
        tab.client.onPageStarted(tab.view, url, null)
    }

    private class FakeTab(val id: String, val view: WebView, private val holder: Any) {
        lateinit var client: WebViewClient
        lateinit var bridge: BrowserManager.BrowserJsBridge
        val scripts = mutableListOf<String>()
        val callbacks = mutableListOf<ValueCallback<String>>()
        val visualCallbacks = mutableListOf<WebView.VisualStateCallback>()
        val lifetime get() = field("lifetime") as BrowserDocumentLifetime
        @Suppress("UNCHECKED_CAST")
        val domWaits get() = field("domWaits") as Set<String>
        fun field(name: String): Any? = holder.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(holder)
        fun field(name: String, value: Any?) {
            holder.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(holder, value)
        }
    }
}
