package com.aicode.feature.player.presentation

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.player.data.PlayerServer
import com.aicode.feature.player.data.PlayerSession
import com.aicode.feature.player.domain.PlayerMediaTypes
import com.aicode.feature.player.domain.PlayerRequest
import com.aicode.feature.player.domain.PlayerRequestCodec
import dagger.hilt.android.AndroidEntryPoint
import java.io.ByteArrayInputStream
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.json.JSONArray
import org.json.JSONObject

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {
    @Inject lateinit var server: PlayerServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var creation: Job? = null
    private var generation = 0
    private var navigation = 0
    private var request: PlayerRequest? = null
    private var session: PlayerSession? = null
    @Volatile private var policy: PlayerSessionPolicy? = null
    @Volatile private var grantedContent = emptySet<String>()
    private var webView: WebView? = null
    private lateinit var root: FrameLayout
    private lateinit var page: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var loading: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var retryButton: Button
    private var coordinator: PlayerPlaybackCoordinator? = null
    private var backgroundAvailable = false
    private var fullPageAvailable = false
    private var autoPip = false
    private var visible = false
    private var inPip = false
    private var enteringPip = false
    private var pageReady = false
    private var failed = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pageFullscreen = false
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var chooserCallback: ValueCallback<Array<Uri>>? = null
    private var chooserGeneration = -1
    private var chooserNavigation = -1
    private var chooserMultiple = false
    private var documentInFlight = false
    private var documentValidation: Job? = null
    private var pipSignature: PlayerPipSignature? = null
    private var pipParamsCache: PictureInPictureParams? = null
    private val endpoint = object : PlayerPlaybackCoordinator.Endpoint {
        override fun playerScript(action: String, value: Double?) = runPlayerScript(action, value)
        override fun renderPlayback(state: PlayerPlaybackState) = renderState(state)
        override fun closePlayer() = this@PlayerActivity.closePlayer()
        override fun backgroundUnavailable() = showToast(R.string.player_background_unavailable)
    }
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(PlayerPlaybackService.EXTRA_OWNER) != coordinator?.controllerId) return
            val command = intent?.getStringExtra(EXTRA_COMMAND) ?: return
            if (command in setOf("play", "pause", "next", "previous")) coordinator?.command(command)
        }
    }
    private val documentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        documentInFlight = false
        val callback = chooserCallback ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK || chooserGeneration != generation || chooserNavigation != navigation || !isTrustedPage()) {
            cancelChooser(); return@registerForActivityResult
        }
        val selected = mutableListOf<Uri>()
        result.data?.clipData?.let { clip -> for (i in 0 until clip.itemCount.coerceAtMost(PlayerRequestCodec.MAX_ITEMS)) selected += clip.getItemAt(i).uri }
        result.data?.data?.let { selected += it }
        val expected = generation; val expectedNavigation = navigation; val multiple = chooserMultiple
        documentValidation = scope.launch {
            val accepted = withContext(Dispatchers.IO) {
                selected.distinct().filter { it.scheme == "content" && isMediaDocument(it) }.let { if (multiple) it else it.take(1) }
            }
            if (chooserCallback !== callback) return@launch
            chooserCallback = null; documentValidation = null
            val current = generation == expected && navigation == expectedNavigation && isTrustedPage()
            val uris = if (current) accepted else emptyList()
            grantedContent = grantedContent + uris.map(Uri::toString)
            callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        buildUi(); immersive()
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PIP_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    pageFullscreen -> setPageFullscreen(false)
                    inPip -> moveTaskToBack(true)
                    webView?.canGoBack() == true -> goBackWithinSession()
                    else -> closePlayer()
                }
            }
        })
        if (intent.action == ACTION_RESTORE) { finish(); return }
        decodeRequest(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RESTORE) {
            if (intent.getStringExtra(PlayerPlaybackService.EXTRA_OWNER) == coordinator?.controllerId) setIntent(intent)
            return
        }
        setIntent(intent); decodeRequest(intent)
    }
    private fun decodeRequest(intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_REQUEST)
        val decoded = raw?.takeIf { it.length <= MAX_REQUEST && it.toByteArray().size <= MAX_REQUEST }?.let(PlayerRequestCodec::decode)
        request = decoded
        if (decoded == null) { releaseSession(); showError(R.string.player_error_request) } else createSession()
    }
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(4), dp(2)); setBackgroundColor(Color.DKGRAY) }
        topBar.addView(TextView(this).apply {
            text = "×"; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
            contentDescription = getString(R.string.player_close); setOnClickListener { closePlayer() }
        }, LinearLayout.LayoutParams(-2, -2))
        titleView = TextView(this).apply { setText(R.string.player_title); setTextColor(Color.WHITE); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(TextView(this).apply {
            text = "⋮"; setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
            contentDescription = getString(R.string.player_menu); setOnClickListener { showMenu(this) }
        }, LinearLayout.LayoutParams(-2, -2))
        column.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        page = FrameLayout(this); column.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
            addView(ProgressBar(this@PlayerActivity)); addView(TextView(this@PlayerActivity).apply { setText(R.string.player_loading); setTextColor(Color.WHITE) })
        }
        page.addView(loading, FrameLayout.LayoutParams(-1, -1))
        errorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true }
        errorText = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER }; errorPanel.addView(errorText)
        retryButton = Button(this).apply { setText(R.string.player_retry); setOnClickListener { createSession() } }; errorPanel.addView(retryButton)
        errorPanel.addView(Button(this).apply { setText(R.string.player_close); setOnClickListener { closePlayer() } })
        page.addView(errorPanel, FrameLayout.LayoutParams(-1, -1)); setContentView(root)
    }
    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val items = mutableListOf<Pair<Int, () -> Unit>>()
        if (fullPageAvailable) items += R.string.player_full_page to { openFullPage() }
        items += R.string.player_retry to { createSession() }
        items += R.string.player_pip to { enterPip() }
        items += (if (coordinator?.state?.background == true) R.string.player_background_on else R.string.player_background_playback) to {
            if (!backgroundAvailable) showToast(R.string.player_background_unavailable) else coordinator?.setBackground(coordinator?.state?.background != true)
        }
        items += (if (autoPip) R.string.player_auto_pip_on else R.string.player_auto_pip) to { autoPip = !autoPip }
        items += R.string.player_close to { closePlayer() }
        items.forEachIndexed { index, pair -> popup.menu.add(0, index, index, pair.first) }
        popup.setOnMenuItemClickListener { items.getOrNull(it.itemId)?.second?.invoke(); true }; popup.show()
    }
    private fun createSession() {
        val payload = request ?: return
        releaseSession(); failed = false; pageReady = false
        loading.visibility = View.VISIBLE; loading.bringToFront(); errorPanel.visibility = View.GONE
        val expected = generation
        creation = scope.launch(Dispatchers.IO) {
            var created: PlayerSession? = null
            var adopted = false
            try {
                created = server.createSession(payload)
                val guard = PlayerSessionPolicy(created.url, created.origin)
                withContext(Dispatchers.Main.immediate) {
                    if (generation != expected || isFinishing || isDestroyed) return@withContext
                    val active = requireNotNull(created)
                    session = active; policy = guard; adopted = true
                    coordinator = PlayerPlaybackCoordinator(this@PlayerActivity, endpoint)
                    coordinator?.setVisibility(visible, inPip)
                    active.onClosed { handler.post { if (session === active) { releaseSession(); showError(R.string.player_error_workspace) } } }
                    createWebView(guard)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { withContext(Dispatchers.Main.immediate) { if (generation == expected) showError(R.string.player_error_session) } }
            finally { if (!adopted) created?.close() }
        }
    }
    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(guard: PlayerSessionPolicy) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { releaseSession(); showError(R.string.player_error_webview); return }
        val view = WebView(this); webView = view
        view.setBackgroundColor(Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false; allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false; setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false; displayZoomControls = false; safeBrowsingEnabled = true
        }
        WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(guard.originRule)) { source, message, sourceOrigin, isMainFrame, _ ->
            if (source !== webView || !isMainFrame || !guard.sameOrigin(sourceOrigin.toString()) || !isTrustedPage()) return@addWebMessageListener
            val payload = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (payload.length <= MAX_BRIDGE) handleMessage(payload)
        }
        view.webViewClient = createWebViewClient(); view.webChromeClient = createChromeClient()
        view.setDownloadListener { _, _, _, _, _ -> showToast(R.string.player_external_media_blocked) }
        page.addView(view, 0, FrameLayout.LayoutParams(-1, -1)); loading.bringToFront(); backgroundAvailable = true
        view.loadUrl(guard.entryUrl); handler.postDelayed(loadTimeout, 30_000)
    }
    private fun createWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (view !== webView) return true
            val url = request.url.toString()
            if (request.isForMainFrame && policy?.allowsPage(url) == true) return false
            if (!request.isForMainFrame && policy?.allowsRequest(url) == true) return false
            if (request.isForMainFrame && request.hasGesture() && PlayerSessionPolicy.isExternalPage(url)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)) }.onFailure { showToast(R.string.player_external_open_failed) }
            } else if (request.isForMainFrame) showToast(R.string.player_external_media_blocked)
            return true
        }
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (policy?.allowsRequest(url) == true) return null
            if (!request.isForMainFrame && request.url.scheme == "content" && url in grantedContent) return null
            val origin = policy?.originRule
            if (!request.isForMainFrame && origin != null && url.startsWith("blob:$origin/")) return null
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (view !== webView) return
            if (url == null || policy?.allowsPage(url) != true) { view.stopLoading(); showError(R.string.player_error_navigation); return }
            pageReady = false; navigation++; cancelChooser(); fullPageAvailable = false
            coordinator?.update(coordinator?.state?.copy(playing = false, hasVideo = false, readyState = 0) ?: PlayerPlaybackState())
            handler.removeCallbacks(loadTimeout); handler.postDelayed(loadTimeout, 30_000)
        }
        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView || !isTrustedPage() || failed) return
            pageReady = true; handler.removeCallbacks(loadTimeout); loading.visibility = View.GONE
            fullPageAvailable = Uri.parse(url).lastPathSegment != "index.html"
            if (!visible && !inPip && coordinator?.state?.background != true) { coordinator?.pause(); view.onPause() }
        }
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view === webView && request.isForMainFrame) showError(R.string.player_error_load, error.errorCode)
        }
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (view === webView && request.isForMainFrame) showError(R.string.player_error_http, errorResponse.statusCode)
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel(); if (view === webView) showError(R.string.player_error_load, error.primaryError)
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) { releaseSession(); showError(R.string.player_error_renderer) }; return true
        }
    }
    private fun createChromeClient() = object : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || callback == null || !isTrustedPage() || inPip || failed || customView != null) { callback?.onCustomViewHidden(); return }
            customView = view; customCallback = callback; savedOrientation = requestedOrientation
            setPageFullscreen(true); root.addView(view, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)); immersive()
        }
        override fun onHideCustomView() = hideCustomView()
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            FileLogger.i("AjiuPlayer", "console_level=${message.messageLevel().ordinal}"); return true
        }
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            if (documentInFlight) { callback.onReceiveValue(null); return true }
            cancelChooser()
            if (view !== webView || !isTrustedPage() || params.mode !in setOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE)) { callback.onReceiveValue(null); return true }
            chooserMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
            chooserCallback = callback; chooserGeneration = generation; chooserNavigation = navigation; documentInFlight = true
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*", "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, chooserMultiple).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { documentLauncher.launch(intent) } catch (_: RuntimeException) { documentInFlight = false; cancelChooser(); showToast(R.string.player_file_picker_failed) }
            return true
        }
    }
    private fun handleMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val data = message.optJSONObject("payload") ?: message.optJSONObject("data") ?: message
        when (message.optString("type")) {
            "state" -> {
                if (failed) { if (data.optBoolean("playing")) coordinator?.pause(); return }
                val previous = coordinator?.state ?: return
                fun seconds(key: String, fallback: Double) = data.optDouble(key, fallback).takeIf { it.isFinite() }?.coerceIn(0.0, 315_576_000.0) ?: fallback
                coordinator?.update(previous.copy(title = data.optString("title", previous.title).take(512),
                    playing = data.optBoolean("playing", previous.playing), position = seconds("position", previous.position), duration = seconds("duration", previous.duration),
                    hasVideo = data.optBoolean("hasVideo", previous.hasVideo), readyState = data.optInt("readyState", previous.readyState).coerceIn(0, 4),
                    videoWidth = data.optInt("videoWidth", previous.videoWidth).coerceIn(0, 16384), videoHeight = data.optInt("videoHeight", previous.videoHeight).coerceIn(0, 16384)))
            }
            "pip" -> if (data.optBoolean("enabled", true)) enterPip()
            "fullscreen" -> if (!failed) { val enabled = data.optBoolean("enabled", data.optBoolean("active", true)); if (!enabled) hideCustomView(); setPageFullscreen(enabled) }
            "orientation" -> if (!inPip) requestedOrientation = when (data.optString("value", data.optString("orientation"))) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "auto", "unlock" -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                else -> return
            }
        }
    }
    private fun renderState(state: PlayerPlaybackState) {
        val title = state.title.ifBlank { getString(R.string.player_title) }
        if (titleView.text.toString() != title) titleView.text = title
        if (state.playing && (visible || inPip)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (supportsPip() && state.canPictureInPicture) {
            val signature = state.pipSignature(coordinator?.controllerId.orEmpty())
            if (signature != pipSignature) runCatching { setPictureInPictureParams(pipParams(state)) }
        }
    }
    private fun runPlayerScript(action: String, value: Double? = null) {
        if (!isTrustedPage()) return
        val script = when (action) {
            "pause", "resume", "destroy" -> "window.AjiuPlayer&&window.AjiuPlayer.$action()"
            "play", "next", "previous" -> "window.AjiuPlayer&&window.AjiuPlayer.command('$action')"
            "seek" -> if (value != null && value.isFinite()) "window.AjiuPlayer&&window.AjiuPlayer.command('seek',$value)" else return
            else -> return
        }
        webView?.evaluateJavascript("try{$script}catch(e){}", null)
    }
    private fun isTrustedPage() = webView?.url?.let { policy?.allowsPage(it) } == true
    private fun openFullPage() {
        val guard = policy ?: return
        val view = webView ?: return
        if (!isTrustedPage() || failed) return
        val url = view.url ?: return
        val expected = generation; val expectedNavigation = navigation
        fullPageAvailable = false; coordinator?.pause(); cancelChooser()
        view.evaluateJavascript(PlayerHandoffScript.capture(JSONObject.quote(url))) { result ->
            if (webView !== view || policy !== guard || generation != expected || navigation != expectedNavigation || view.url != url || !isTrustedPage()) return@evaluateJavascript
            if (result != "true") { fullPageAvailable = true; showToast(R.string.player_full_page_failed); return@evaluateJavascript }
            pageReady = false; loading.visibility = View.VISIBLE; loading.bringToFront(); view.loadUrl(guard.fullPageBase() + "?handoff=1")
        }
    }
    private fun goBackWithinSession() {
        val view = webView ?: return
        val history = view.copyBackForwardList()
        val previous = history.getItemAtIndex(history.currentIndex - 1)?.url
        if (previous != null && policy?.allowsPage(previous) == true) { coordinator?.pause(); view.goBack() } else closePlayer()
    }
    private fun supportsPip() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private fun enterPip() {
        val state = coordinator?.state ?: return
        if (inPip || enteringPip || failed || !pageReady || !visible || !state.canPictureInPicture || !supportsPip()) { showToast(R.string.player_pip_unavailable); return }
        hideCustomView(); enteringPip = true
        try { if (!enterPictureInPictureMode(pipParams(state))) { enteringPip = false; showToast(R.string.player_pip_unavailable) } }
        catch (_: RuntimeException) { enteringPip = false; showToast(R.string.player_pip_unavailable) }
    }
    private fun pipParams(state: PlayerPlaybackState): PictureInPictureParams {
        val signature = state.pipSignature(coordinator?.controllerId.orEmpty())
        pipParamsCache?.takeIf { signature == pipSignature }?.let { return it }
        val ratio = (if (state.videoWidth > 0 && state.videoHeight > 0) state.videoWidth.toDouble() / state.videoHeight else 16.0 / 9.0).coerceIn(1.0 / 2.39, 2.39)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).setActions(listOf(
            pipAction("previous", R.string.player_previous), pipAction(if (state.playing) "pause" else "play", if (state.playing) R.string.player_pause else R.string.player_play), pipAction("next", R.string.player_next))).build().also { pipSignature = signature; pipParamsCache = it }
    }
    private fun pipAction(command: String, label: Int): RemoteAction {
        val id = coordinator?.controllerId.orEmpty()
        val pending = PendingIntent.getBroadcast(this, 31 * id.hashCode() + command.hashCode(), Intent(PIP_ACTION).setPackage(packageName)
            .putExtra(EXTRA_COMMAND, command).putExtra(PlayerPlaybackService.EXTRA_OWNER, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(this, R.drawable.ic_thinking), getString(label), getString(label), pending)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (autoPip && coordinator?.state?.playing == true && chooserCallback == null) enterPip() }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode; enteringPip = false; updateChromeVisibility(); coordinator?.setVisibility(visible, inPip)
        if (inPip) webView?.onResume() else { immersive(); handler.postDelayed({ if (!inPip && !visible && !isFinishing && !isDestroyed) closePlayer() }, 500) }
    }
    override fun onStart() { super.onStart(); visible = true; coordinator?.setVisibility(true, inPip); webView?.onResume() }
    override fun onResume() { super.onResume(); immersive(); coordinator?.setVisibility(true, inPip); webView?.onResume() }
    override fun onStop() {
        visible = false
        val pip = inPip || isInPictureInPictureMode || enteringPip
        coordinator?.setVisibility(false, pip)
        if (!pip && coordinator?.state?.background != true) webView?.onPause()
        if (!pip) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); if (!inPip) immersive() }
    private fun setPageFullscreen(enabled: Boolean) { pageFullscreen = enabled; updateChromeVisibility(); if (!inPip) immersive() }
    private fun updateChromeVisibility() { topBar.visibility = if (inPip || pageFullscreen) View.GONE else View.VISIBLE }
    private fun hideCustomView() {
        setPageFullscreen(false)
        val view = customView ?: return
        customView = null; (view.parent as? ViewGroup)?.removeView(view)
        val callback = customCallback; customCallback = null; callback?.onCustomViewHidden(); requestedOrientation = savedOrientation; immersive()
    }
    @Suppress("DEPRECATION") private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    private val loadTimeout = Runnable { if (!pageReady && !failed) showError(R.string.player_error_timeout) }
    private fun showError(resource: Int, code: Int? = null) {
        failed = true; pageReady = false; handler.removeCallbacks(loadTimeout)
        coordinator?.pause(); coordinator?.setBackground(false); hideCustomView(); loading.visibility = View.GONE
        errorText.text = if (code == null) getString(resource) else getString(resource, code)
        retryButton.isEnabled = request != null; errorPanel.visibility = View.VISIBLE; errorPanel.bringToFront(); fullPageAvailable = false
    }
    private fun isMediaDocument(uri: Uri) = runCatching {
        val type = contentResolver.getType(uri).orEmpty().lowercase()
        if (type.startsWith("audio/") || type.startsWith("video/") || type in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml")) return@runCatching true
        if (type.isNotBlank() && type !in setOf("application/octet-stream", "binary/octet-stream")) return@runCatching false
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        PlayerMediaTypes.supports(name)
    }.getOrDefault(false)
    private fun cancelChooser() { documentValidation?.cancel(); documentValidation = null; chooserCallback?.onReceiveValue(null); chooserCallback = null }
    private fun closePlayer() { releaseSession(); finish() }
    private fun releaseSession() {
        generation++; creation?.cancel(); creation = null; handler.removeCallbacksAndMessages(null); cancelChooser()
        coordinator?.close(); coordinator = null; pipSignature = null; pipParamsCache = null; hideCustomView()
        val view = webView; val trusted = isTrustedPage(); val oldSession = session
        webView = null; session = null; policy = null; grantedContent = emptySet()
        disposeRenderer(view, trusted, oldSession)
        backgroundAvailable = false; autoPip = false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun disposeRenderer(view: WebView?, trusted: Boolean, oldSession: PlayerSession?) {
        val cleanup = Handler(Looper.getMainLooper())
        var cleaned = false
        fun finishCleanup() {
            if (cleaned) return
            cleaned = true; cleanup.removeCallbacksAndMessages(null)
            if (view != null) {
                runCatching { view.stopLoading(); (view.parent as? ViewGroup)?.removeView(view); view.removeAllViews(); view.destroy() }
            }
            if (oldSession != null) CoroutineScope(Dispatchers.IO).launch { oldSession.close() }
        }
        if (view == null) { finishCleanup(); return }
        view.visibility = View.GONE; view.webChromeClient = null; view.webViewClient = WebViewClient()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) runCatching { WebViewCompat.removeWebMessageListener(view, BRIDGE) }
        if (!trusted) { finishCleanup(); return }
        cleanup.postDelayed({ finishCleanup() }, 250)
        runCatching { view.evaluateJavascript("try{window.AjiuPlayer&&window.AjiuPlayer.pause()}catch(e){};try{window.AjiuPlayer&&window.AjiuPlayer.destroy()}catch(e){}") { finishCleanup() } }.onFailure { finishCleanup() }
    }
    override fun onDestroy() { releaseSession(); scope.cancel(); unregisterReceiver(pipReceiver); super.onDestroy() }
    private fun showToast(resource: Int) = Toast.makeText(this, resource, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    companion object {
        const val EXTRA_REQUEST = "com.aicode.player.REQUEST_JSON"
        internal const val ACTION_RESTORE = "com.aicode.player.RESTORE"
        private const val EXTRA_COMMAND = "com.aicode.player.COMMAND"
        private const val PIP_ACTION = "com.aicode.player.PIP_COMMAND"
        private const val BRIDGE = "AjiuNative"
        private const val MAX_REQUEST = 512 * 1024
        private const val MAX_BRIDGE = 1024 * 1024
        @JvmStatic fun open(context: Context, request: PlayerRequest) {
            val encoded = runCatching { PlayerRequestCodec.json.encodeToString(PlayerRequestCodec.validate(request)) }.getOrNull()
            if (encoded == null || encoded.length > MAX_REQUEST || encoded.toByteArray().size > MAX_REQUEST) { Toast.makeText(context, R.string.player_error_request, Toast.LENGTH_LONG).show(); return }
            runCatching { context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_REQUEST, encoded).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) }
                .onFailure { Toast.makeText(context, R.string.player_error_session, Toast.LENGTH_LONG).show() }
        }
    }
}
