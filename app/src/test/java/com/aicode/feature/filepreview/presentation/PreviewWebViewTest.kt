package com.aicode.feature.filepreview.presentation

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aicode.feature.filepreview.data.PreviewSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewWebViewTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val source = mockk<PreviewSource>(relaxed = true)
    private val localLinks = mutableListOf<String>()
    private val externalLinks = mutableListOf<String>()
    private val sessions = mutableListOf<PreviewWebSession>()
    private var errors = 0
    private val networkGuard = mockk<ScriptHandler>(relaxed = true)
    private val valid = MutableStateFlow(true)
    private val mainDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
    private val ioDispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    @Before fun supportDocumentStart() {
        Dispatchers.setMain(mainDispatcher)
        mockkStatic(WebViewFeature::class, WebViewCompat::class)
        every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns true
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns networkGuard
    }

    private fun session(): PreviewWebSession {
        every { source.entryPath } returns "/root/workspace/site/index.html"
        every { source.valid } returns valid
        every { source.resolve(any(), any()) } answers { "/root/workspace/site/${Uri.decode(firstArg<String>())}" }
        return PreviewWebSession(context, source, source.entryPath, false, false,
            onLocalLink = localLinks::add, onExternalLink = externalLinks::add, onError = { errors++ },
            ioDispatcher = ioDispatcher,
        ).also { sessions += it }
    }

    private fun request(url: String, gesture: Boolean = true, mainFrame: Boolean = true, method: String = "GET", redirect: Boolean = false): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = redirect
            override fun hasGesture(): Boolean = gesture
            override fun getMethod(): String = method
            override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
        }

    @After fun cleanup() {
        sessions.forEach { it.close() }
        mainDispatcher.scheduler.runCurrent()
        ioDispatcher.scheduler.runCurrent()
        mainDispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        unmockkStatic(WebViewFeature::class, WebViewCompat::class)
        Dispatchers.resetMain()
    }

    @Test fun settingsEnableLocalJavascriptWithoutFilesystemStorageBridgeOrSharedCookies() {
        val session = session()
        session.start()
        val webView = session.webView
        with(webView.settings) {
            assertTrue(javaScriptEnabled)
            assertFalse(javaScriptCanOpenWindowsAutomatically)
            assertTrue(supportMultipleWindows())
            assertFalse(domStorageEnabled)
            assertFalse(databaseEnabled)
            assertFalse(allowFileAccess)
            assertFalse(allowContentAccess)
            assertFalse(allowFileAccessFromFileURLs)
            assertFalse(allowUniversalAccessFromFileURLs)
            assertFalse(blockNetworkLoads)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
            assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
            assertTrue(mediaPlaybackRequiresUserGesture)
            assertFalse(saveFormData)
        }
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
        listOf("searchBoxJavaBridge_", "accessibility", "accessibilityTraversal", "Android", "Agent").forEach {
            assertNull(shadowOf(webView).getJavascriptInterface(it))
        }
        assertTrue(shadowOf(webView).createdPorts.isEmpty())
        assertTrue(Uri.parse(session.assets.entryUrl).host!!.endsWith(".invalid"))
    }

    @Test fun documentStartNetworkGuardIsRegisteredBeforeJavascriptOrAnyPageLoads() {
        val session = session()
        val view = session.webView
        var registered = false
        every { WebViewCompat.addDocumentStartJavaScript(view, any(), setOf(session.assets.origin)) } answers {
            assertNull(shadowOf(view).lastLoadedUrl)
            assertFalse(view.settings.javaScriptEnabled)
            assertEquals(PreviewWebSession.NETWORK_GUARD, secondArg<String>())
            registered = true
            networkGuard
        }
        session.start()
        assertTrue(registered)
        assertTrue(view.settings.javaScriptEnabled)
        assertEquals(session.assets.entryUrl, shadowOf(view).lastLoadedUrl)
        session.close()
        verify(exactly = 1) { networkGuard.remove() }
        listOf("RTCPeerConnection", "webkitRTCPeerConnection", "mozRTCPeerConnection", "RTCDataChannel", "WebTransport").forEach {
            assertTrue(PreviewWebSession.NETWORK_GUARD.contains(it))
        }
        assertTrue(PreviewWebSession.NETWORK_GUARD.contains("writable: false, configurable: false"))
    }

    @Test fun unsupportedOrFailedDocumentStartGuardNeverEnablesJavascriptOrLoadsHtml() {
        every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns false
        val unsupported = session()
        unsupported.start()
        assertFalse(unsupported.webView.settings.javaScriptEnabled)
        assertNull(shadowOf(unsupported.webView).lastLoadedUrl)
        assertTrue(shadowOf(unsupported.webView).wasDestroyCalled())
        assertEquals(1, errors)
        every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns true
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } throws IllegalStateException("Unavailable")
        val failed = session()
        failed.start()
        assertFalse(failed.webView.settings.javaScriptEnabled)
        assertNull(shadowOf(failed.webView).lastLoadedUrl)
        assertEquals(2, errors)
    }

    @Test fun startingMoreThanOnceDoesNotReloadOrResetTheCurrentPage() {
        val session = session()
        session.start()
        val firstLoad = shadowOf(session.webView).lastLoadedUrl
        session.webView.loadUrl(session.assets.entryUrl + "#position")
        session.start()
        assertEquals(session.assets.entryUrl, firstLoad)
        assertEquals(session.assets.entryUrl + "#position", shadowOf(session.webView).lastLoadedUrl)
        session.close()
        assertTrue(shadowOf(session.webView).wasDestroyCalled())
        verify(exactly = 0) { source.close() }
    }

    @Test fun localAndExternalNavigationOnlyInvokeCallbacksForUserMainFrameGestures() {
        val session = session()
        val view = session.webView
        val client = shadowOf(view).webViewClient
        val local = session.assets.entryUrl.substringBeforeLast('/') + "/next.html"
        val external = "https://example.com/page"
        assertTrue(client.shouldOverrideUrlLoading(view, request(local, gesture = false)))
        assertTrue(client.shouldOverrideUrlLoading(view, request(external, gesture = false)))
        assertTrue(client.shouldOverrideUrlLoading(view, request(external, mainFrame = false)))
        assertTrue(client.shouldOverrideUrlLoading(view, request(local, mainFrame = false)))
        assertTrue(localLinks.isEmpty())
        assertTrue(externalLinks.isEmpty())
        assertTrue(client.shouldOverrideUrlLoading(view, request(local)))
        assertTrue(localLinks.isEmpty())
        verify(exactly = 0) { source.checkCurrent() }
        mainDispatcher.scheduler.runCurrent()
        verify(exactly = 0) { source.checkCurrent() }
        ioDispatcher.scheduler.runCurrent()
        assertTrue(localLinks.isEmpty())
        mainDispatcher.scheduler.runCurrent()
        assertEquals(listOf("/root/workspace/site/next.html"), localLinks)
        assertTrue(client.shouldOverrideUrlLoading(view, request(external)))
        assertEquals(listOf(external), externalLinks)
        assertEquals(null, shadowOf(view).lastLoadedUrl)
    }

    @Test fun localNavigationIsDiscardedIfTheSessionClosesBeforeMainDelivery() {
        val session = session()
        val view = session.webView
        val local = session.assets.entryUrl.substringBeforeLast('/') + "/next.html"
        assertTrue(shadowOf(view).webViewClient.shouldOverrideUrlLoading(view, request(local)))
        mainDispatcher.scheduler.runCurrent()
        ioDispatcher.scheduler.runCurrent()
        assertTrue(localLinks.isEmpty())
        session.close()
        mainDispatcher.scheduler.runCurrent()
        assertTrue(localLinks.isEmpty())
        verify(exactly = 0) { source.close() }
    }

    @Test fun invalidatingSourceDiscardsValidatedLinksBeforeMainDelivery() {
        val session = session()
        val view = session.webView
        val local = session.assets.entryUrl.substringBeforeLast('/') + "/next.html"
        assertTrue(shadowOf(view).webViewClient.shouldOverrideUrlLoading(view, request(local)))
        mainDispatcher.scheduler.runCurrent()
        ioDispatcher.scheduler.runCurrent()
        valid.value = false
        mainDispatcher.scheduler.runCurrent()
        assertTrue(localLinks.isEmpty())
    }

    @Test fun newerUserNavigationSupersedesPendingValidatedLocalLinks() {
        val session = session()
        val view = session.webView
        val client = shadowOf(view).webViewClient
        val base = session.assets.entryUrl.substringBeforeLast('/') + "/"
        assertTrue(client.shouldOverrideUrlLoading(view, request(base + "first.html")))
        mainDispatcher.scheduler.runCurrent()
        ioDispatcher.scheduler.runCurrent()
        assertTrue(client.shouldOverrideUrlLoading(view, request(base + "second.html")))
        mainDispatcher.scheduler.runCurrent()
        assertTrue(localLinks.isEmpty())
        ioDispatcher.scheduler.runCurrent()
        mainDispatcher.scheduler.runCurrent()
        assertEquals(listOf("/root/workspace/site/second.html"), localLinks)
    }

    @Test fun redirectsCannotTriggerLocalOrExternalCallbacksEvenWithGestureFlag() {
        val session = session()
        val view = session.webView
        val client = shadowOf(view).webViewClient
        val local = session.assets.entryUrl.substringBeforeLast('/') + "/next.html"
        assertTrue(client.shouldOverrideUrlLoading(view, request(local, redirect = true)))
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://example.com/page", redirect = true)))
        mainDispatcher.scheduler.runCurrent()
        ioDispatcher.scheduler.runCurrent()
        mainDispatcher.scheduler.runCurrent()
        assertTrue(localLinks.isEmpty())
        assertTrue(externalLinks.isEmpty())
        verify(exactly = 0) { source.checkCurrent() }
    }

    @Test fun anchorsStayLocalButSchemesTokensAndLegacyCallbacksCannotTriggerNavigation() {
        val session = session()
        val view = session.webView
        val client = shadowOf(view).webViewClient
        assertFalse(client.shouldOverrideUrlLoading(view, request(session.assets.entryUrl + "#section", gesture = false)))
        val another = session()
        listOf(
            "javascript:alert(1)", "intent://open#Intent;end", "data:text/html,hello",
            "file:///etc/passwd", "content://files/private", another.assets.entryUrl,
        ).forEach { assertTrue(client.shouldOverrideUrlLoading(view, request(it))) }
        @Suppress("DEPRECATION")
        assertTrue(client.shouldOverrideUrlLoading(view, "https://example.com/page"))
        assertTrue(localLinks.isEmpty())
        assertTrue(externalLinks.isEmpty())
    }

    @Test fun networkRequestsNeverFallBackToWebViewNetwork() {
        val session = session()
        val view = session.webView
        listOf("https://example.com/image.png", "http://example.com/logic.js", "https://localhost/api").forEach {
            val response = shadowOf(view).webViewClient.shouldInterceptRequest(view, request(it, mainFrame = false))
            assertNotNull(response)
            assertEquals(403, response!!.statusCode)
        }
        verify(exactly = 0) { source.openRead(any()) }
    }

    @Test fun windowsPermissionsGeolocationAndFileSelectionAreDenied() {
        val session = session()
        val view = session.webView
        val chrome = requireNotNull(shadowOf(view).webChromeClient)
        assertFalse(chrome.onCreateWindow(view, false, true, null))
        val permission = mockk<PermissionRequest>(relaxed = true)
        chrome.onPermissionRequest(permission)
        verify(exactly = 1) { permission.deny() }
        val location = mockk<GeolocationPermissions.Callback>(relaxed = true)
        chrome.onGeolocationPermissionsShowPrompt("https://example.com", location)
        verify { location.invoke("https://example.com", false, false) }
        val files = mockk<ValueCallback<Array<Uri>>>(relaxed = true)
        assertTrue(chrome.onShowFileChooser(view, files, null))
        verify { files.onReceiveValue(null) }
    }

    @Test fun rendererFailureDestroysItsOwnViewAndSignalsOneError() {
        val session = session()
        val view = session.webView
        val client = shadowOf(view).webViewClient
        assertTrue(client.onRenderProcessGone(view, mockk<RenderProcessGoneDetail>()))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, errors)
        assertTrue(shadowOf(view).wasDestroyCalled())
        session.fail()
        assertEquals(1, errors)
        assertEquals(410, session.assets.intercept(session.assets.entryUrl, mainFrame = true).statusCode)
        verify(exactly = 0) { source.close() }
    }
}
