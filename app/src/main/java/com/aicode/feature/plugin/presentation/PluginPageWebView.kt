package com.aicode.feature.plugin.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aicode.R
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetJavaScriptEnabled")
internal class PluginPageSession(
    context: Context,
    capture: PluginCapture,
    page: PluginPage,
    dark: Boolean,
    checkContext: () -> Unit,
    call: suspend (String, JsonObject) -> JsonElement,
    private val onError: (Int) -> Unit,
    private val themeVariables: () -> Map<String, String> = { emptyMap() }
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var registered = false
    private var started = false
    private var guard: ScriptHandler? = null
    internal val assets = PluginPageAssets(capture, page, checkContext, { fail(R.string.plugin_page_reopen) },
        { path -> context.assets.open("plugin-ui/$path").use { it.readBytes() } }, { PluginThemeTokens.css(themeVariables()) })
    internal val webView = WebView(ContextThemeWrapper(context, if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView))
    private val bridge = PluginPageBridge(webView, assets, scope, {
        capture.checkCurrent()
        checkContext()
    }, call, { fail(R.string.plugin_page_reopen) })

    init {
        webView.apply {
            settings.apply {
                javaScriptEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(true)
                domStorageEnabled = false
                databaseEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                saveFormData = false
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            listOf("searchBoxJavaBridge_", "accessibility", "accessibilityTraversal").forEach(::removeJavascriptInterface)
            setDownloadListener { _, _, _, _, _ -> }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams?): Boolean {
                    callback.onReceiveValue(null)
                    return true
                }
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
                override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = assets.intercept(request.url, request.method, request.isForMainFrame)
                @Deprecated("Legacy request callback")
                override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse = assets.intercept(Uri.parse(url))
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                @Deprecated("Legacy navigation callback")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
                override fun onPageFinished(view: WebView, url: String?) {
                    if (assets.isEntry(url)) applyTheme()
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!assets.isEntry(url)) fail(R.string.plugin_page_reopen)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(R.string.plugin_page_failed)
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) fail(R.string.plugin_page_failed)
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.cancel(); fail(R.string.plugin_page_failed) }
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) { handler.cancel() }
                override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) { request.cancel() }
                override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: android.webkit.SafeBrowsingResponse) {
                    callback.backToSafety(false)
                    fail(R.string.plugin_page_failed)
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { fail(R.string.plugin_page_failed); return true }
            }
        }
    }

    internal fun start() {
        if (started || closed.get()) return
        started = true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            fail(R.string.plugin_page_unsupported)
            return
        }
        try {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(assets.origin)) { view, message, sourceOrigin, mainFrame, _ ->
                val payload = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
                bridge.onMessage(view, sourceOrigin, mainFrame, payload)
            }
            registered = true
            guard = WebViewCompat.addDocumentStartJavaScript(webView, NETWORK_GUARD, setOf(assets.origin))
            webView.settings.javaScriptEnabled = true
            webView.loadUrl(assets.entryUrl)
        } catch (_: Exception) {
            fail(R.string.plugin_page_unsupported)
        }
    }

    internal fun applyTheme() {
        if (!closed.get()) webView.evaluateJavascript(PluginThemeTokens.applyScript(themeVariables()), null)
    }

    internal fun postEvent(name: String, payload: JsonElement) {
        if (closed.get()) return
        val event = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(name)); put("payload", payload) }
        webView.evaluateJavascript("window.__aicodeEvent&&window.__aicodeEvent(${kotlinx.serialization.json.JsonPrimitive(event.toString())})", null)
    }

    internal fun postOverlayMessage(payload: JsonElement) {
        if (!closed.get()) webView.evaluateJavascript("window.__aicodeOverlayMessage&&window.__aicodeOverlayMessage(${kotlinx.serialization.json.JsonPrimitive(payload.toString())})", null)
    }

    internal fun fail(messageRes: Int) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        assets.close()
        bridge.close()
        scope.cancel()
        val notify = Runnable {
            if (!closed.get()) {
                close()
                onError(messageRes)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run() else handler.post(notify)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bridge.close()
        assets.close()
        scope.cancel()
        if (registered) runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME) }
        registered = false
        guard?.let { runCatching { it.remove() } }
        guard = null
        webView.stopLoading()
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }

    companion object {
        internal const val BRIDGE_NAME = "__aicodeBridge"
        internal val NETWORK_GUARD = """
            (function () {
              'use strict';
              var deny = function () { throw new Error('Network API disabled for plugin pages'); };
              ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection', 'WebTransport',
               'WebSocket', 'EventSource', 'Worker', 'SharedWorker'].forEach(function (name) {
                Object.defineProperty(window, name, { value: deny, writable: false, configurable: false });
              });
            })();
        """.trimIndent()
    }
}
