package com.aicode.feature.filepreview.presentation

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aicode.R
import com.aicode.feature.filepreview.data.PreviewSource
import com.aicode.feature.filepreview.data.PreviewWebAssets
import com.aicode.feature.filepreview.data.PreviewWebRoute
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
internal fun PreviewWebView(
    source: PreviewSource,
    path: String,
    markdown: Boolean,
    modifier: Modifier = Modifier,
    onLocalLink: (String) -> Unit,
    onExternalLink: (String) -> Unit,
    onError: () -> Unit,
    mimeType: String = "",
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val localLink by rememberUpdatedState(onLocalLink)
    val externalLink by rememberUpdatedState(onExternalLink)
    val error by rememberUpdatedState(onError)
    val session = remember(source, path, markdown, mimeType) {
        PreviewWebSession(
            context, source, path, markdown, dark,
            onLocalLink = { localLink(it) },
            onExternalLink = { externalLink(it) },
            onError = { error() },
            mimeType = mimeType,
        )
    }

    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    LaunchedEffect(session) {
        source.valid.collect { valid -> if (!valid) session.fail() }
    }
    key(session) {
        AndroidView(factory = { session.webView }, modifier = modifier, update = {})
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal class PreviewWebSession(
    context: Context,
    private val source: PreviewSource,
    path: String,
    markdown: Boolean,
    dark: Boolean,
    private val onLocalLink: (String) -> Unit,
    private val onExternalLink: (String) -> Unit,
    private val onError: () -> Unit,
    mimeType: String = "",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val navigationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val navigationGeneration = AtomicLong()
    private var started = false
    private var networkGuard: ScriptHandler? = null
    internal val assets = PreviewWebAssets(source, path, markdown, context.assets, dark, mimeType, ::fail)
    internal val webView = WebView(
        ContextThemeWrapper(context, if (dark) R.style.Theme_AICode_WebView_Dark else R.style.Theme_AICode_WebView)
    ).apply {
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
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            saveFormData = false
            loadsImagesAutomatically = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        removeJavascriptInterface("searchBoxJavaBridge_")
        removeJavascriptInterface("accessibility")
        removeJavascriptInterface("accessibilityTraversal")
        setBackgroundColor(if (dark) 0xFF0D1117.toInt() else 0xFFFFFFFF.toInt())
        setDownloadListener { _, _, _, _, _ -> }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
            override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams?): Boolean {
                callback.onReceiveValue(null)
                return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                result.cancel()
                return true
            }
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                result.cancel()
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                assets.intercept(request.url.toString(), request.method, request.isForMainFrame)

            @Deprecated("Legacy WebView callback")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse = assets.intercept(url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (closed.get() || errorReported.get() || !source.valid.value || !request.isForMainFrame || request.isRedirect) return true
                if (assets.isCurrentAnchor(url)) {
                    if (request.hasGesture()) navigationGeneration.incrementAndGet()
                    return false
                }
                if (!request.hasGesture()) return true
                val generation = navigationGeneration.incrementAndGet()
                when (assets.classify(url)) {
                    is PreviewWebRoute.Local -> navigationScope.launch {
                        val resolved = withContext(ioDispatcher) { assets.localLink(url) }
                        if (isActive && !closed.get() && !errorReported.get() && source.valid.value &&
                            navigationGeneration.get() == generation
                        ) resolved?.let(onLocalLink)
                    }
                    PreviewWebRoute.External -> onExternalLink(url)
                    else -> Unit
                }
                return true
            }

            @Deprecated("Legacy callback has no trusted gesture information")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = closed.get() || !assets.isCurrentAnchor(url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (url == null || assets.classify(url) !is PreviewWebRoute.Local) fail()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) fail()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                fail()
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String?, realm: String?) {
                handler.cancel()
            }

            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) { request.cancel() }

            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: android.webkit.SafeBrowsingResponse) {
                callback.backToSafety(false)
                fail()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail()
                return true
            }
        }
    }

    internal fun start() {
        if (started || closed.get()) return
        started = true
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                fail()
                return
            }
            networkGuard = WebViewCompat.addDocumentStartJavaScript(webView, NETWORK_GUARD, setOf(assets.origin))
            webView.settings.javaScriptEnabled = true
            webView.loadUrl(assets.entryUrl)
        } catch (_: Exception) {
            fail()
        }
    }

    internal fun fail() {
        if (closed.get() || !errorReported.compareAndSet(false, true)) return
        assets.close()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            close()
            onError()
        } else {
            mainHandler.post {
                if (!closed.get()) {
                    close()
                    onError()
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        navigationGeneration.incrementAndGet()
        navigationScope.cancel()
        assets.close()
        webView.stopLoading()
        webView.setDownloadListener(null)
        webView.webChromeClient = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        networkGuard?.let { runCatching { it.remove() } }
        networkGuard = null
        webView.destroy()
    }

    internal companion object {
        internal val NETWORK_GUARD = """
            (function () {
              'use strict';
              var deny = function () { throw new Error('Network API disabled in preview'); };
              ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection',
               'RTCDataChannel', 'RTCIceTransport', 'RTCDtlsTransport', 'RTCSctpTransport',
               'RTCRtpSender', 'RTCRtpReceiver', 'RTCRtpTransceiver', 'WebTransport'].forEach(function (name) {
                Object.defineProperty(window, name, { value: deny, writable: false, configurable: false });
              });
            })();
        """.trimIndent()
    }
}
