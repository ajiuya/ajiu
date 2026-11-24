package com.aicode.feature.plugin.presentation

import android.app.Application
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aicode.R
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.SemVer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class PluginPageSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sessions = mutableListOf<PluginPageSession>()
    private val errors = mutableListOf<Int>()
    private val guard = mockk<ScriptHandler>(relaxed = true)

    @Before fun setup() {
        mockkStatic(WebViewFeature::class, WebViewCompat::class)
        every { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } returns true
        every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns true
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } returns Unit
        every { WebViewCompat.removeWebMessageListener(any(), any()) } returns Unit
        every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns guard
    }

    @After fun cleanup() {
        sessions.forEach { it.close() }
        unmockkStatic(WebViewFeature::class, WebViewCompat::class)
    }

    @Test fun unsupportedSecureListenerFailsClosedWithoutAnyJavascriptInterfaceFallback() {
        every { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } returns false
        val session = session()
        session.start()
        assertEquals(listOf(R.string.plugin_page_unsupported), errors)
        assertFalse(session.webView.settings.javaScriptEnabled)
        assertNull(shadowOf(session.webView).lastLoadedUrl)
        assertNull(shadowOf(session.webView).getJavascriptInterface("__aicodeBridge"))
        assertTrue(shadowOf(session.webView).wasDestroyCalled())
        verify(exactly = 0) { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) }
    }

    @Test fun listenerRegistrationFailureDoesNotEnableJavascriptOrLoadHtml() {
        every { WebViewCompat.addWebMessageListener(any(), any(), any(), any()) } throws IllegalStateException("Unsupported")
        val session = session()
        session.start()
        assertEquals(listOf(R.string.plugin_page_unsupported), errors)
        assertFalse(session.webView.settings.javaScriptEnabled)
        assertNull(shadowOf(session.webView).lastLoadedUrl)
        assertNull(shadowOf(session.webView).getJavascriptInterface("__aicodeBridge"))
    }

    @Test fun exactIndependentOriginIsRegisteredBeforeEnablingJavascriptAndLoadingContent() {
        val session = session()
        var registered = false
        every { WebViewCompat.addWebMessageListener(session.webView, "__aicodeBridge", setOf(session.assets.origin), any()) } answers {
            assertFalse(session.webView.settings.javaScriptEnabled)
            assertNull(shadowOf(session.webView).lastLoadedUrl)
            registered = true
        }
        session.start()
        assertTrue(registered)
        assertTrue(session.webView.settings.javaScriptEnabled)
        assertEquals(session.assets.entryUrl, shadowOf(session.webView).lastLoadedUrl)
        session.close()
        session.close()
        verify(exactly = 1) { WebViewCompat.removeWebMessageListener(session.webView, "__aicodeBridge") }
        verify(exactly = 1) { guard.remove() }
        assertTrue(shadowOf(session.webView).wasDestroyCalled())
    }

    @Test fun settingsDisableFilesystemWindowsStorageAndMixedContentAccess() {
        val session = session()
        with(session.webView.settings) {
            assertFalse(allowFileAccess)
            assertFalse(allowContentAccess)
            assertFalse(allowFileAccessFromFileURLs)
            assertFalse(allowUniversalAccessFromFileURLs)
            assertFalse(domStorageEnabled)
            assertFalse(databaseEnabled)
            assertFalse(javaScriptCanOpenWindowsAutomatically)
            assertTrue(supportMultipleWindows())
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
            assertEquals(WebSettings.LOAD_NO_CACHE, cacheMode)
            assertTrue(mediaPlaybackRequiresUserGesture)
        }
        assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(session.webView))
        assertTrue(shadowOf(session.webView).createdPorts.isEmpty())
        for (name in listOf("searchBoxJavaBridge_", "accessibility", "accessibilityTraversal", "__aicodeBridge")) {
            assertNull(shadowOf(session.webView).getJavascriptInterface(name))
        }
    }

    @Test fun navigationAndEveryNonPackageRequestAreExplicitlyRejected() {
        val session = session()
        val client = shadowOf(session.webView).webViewClient
        assertTrue(client.shouldOverrideUrlLoading(session.webView, request(session.assets.entryUrl)))
        assertTrue(client.shouldOverrideUrlLoading(session.webView, request("https://example.invalid/page")))
        val denied = client.shouldInterceptRequest(session.webView, request("https://example.invalid/remote.js"))
        assertEquals(403, denied?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(session.webView, request("content://storage/item"))?.statusCode)
        assertEquals(403, client.shouldInterceptRequest(session.webView, request("file:///etc/passwd"))?.statusCode)
    }

    private fun request(url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    private fun session(): PluginPageSession {
        val root = temporary.newFolder()
        File(root, "page.html").writeText("<html>Package</html>")
        val hashes = PluginFiles.hashTree(root)
        val hash = PluginFiles.contentHash(hashes)
        val record = PluginRecord("sample.plugin", "1.0.0", true, 1, archiveSha256 = hash, manifestSha256 = hash,
            installRevision = "revision-one", contentHash = hash, fileHashes = hashes, trustHash = hash)
        val manifest = PluginManifest(1, record.id, LocalizedText.single("Sample"), SemVer(1, 0, 0), "test", "", LocalizedText.single(""), "MIT", "1.0.0")
        val page = PluginPage(record.id, "page", LocalizedText.single("Page"), null, "page.html", "book", root, "1.0.0")
        val capture = PluginCapture(record, manifest, PluginContent(configPage = page), root, {}, {})
        return PluginPageSession(context, capture, page, false, {}, { _, _ -> JsonPrimitive("ok") }, errors::add).also { sessions += it }
    }
}
