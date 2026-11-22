package com.aicode.feature.plugin.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.aicode.core.theme.semanticColors
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import com.aicode.feature.plugin.runtime.PluginFileWatchApi
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@HiltViewModel
class PluginPageResourcesViewModel @Inject constructor(val watches: PluginFileWatchApi, val overlays: PluginPageOverlayState) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePluginPage(
    capture: PluginCapture,
    page: PluginPage,
    languageTag: String,
    captureContext: suspend (PluginCapture) -> PluginInvocationContext,
    call: suspend (PluginInvocationContext, String, JsonObject) -> JsonElement,
    onClose: () -> Unit,
    embedded: Boolean = false
) {
    var invocation by remember(capture, page) { mutableStateOf<PluginInvocationContext?>(null) }
    var errorRes by remember(capture, page) { mutableStateOf<Int?>(null) }
    val latestCaptureContext by rememberUpdatedState(captureContext)
    val latestCall by rememberUpdatedState(call)
    LaunchedEffect(capture, page) {
        try {
            invocation = withContext(Dispatchers.IO) {
                capture.checkCurrent()
                latestCaptureContext(capture).also { it.checkCurrent(); capture.checkCurrent() }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            errorRes = R.string.plugin_page_reopen
        } catch (_: Exception) {
            errorRes = R.string.plugin_page_reopen
        }
    }
    val content: @Composable () -> Unit = {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(page.title.resolve(languageTag)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.plugin_close)) } }
            )
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val source = invocation
                val error = errorRes
                when {
                    error != null -> Column(Modifier.padding(Spacing.lg)) { Text(stringResource(error), color = MaterialTheme.colorScheme.error) }
                    source == null -> Text(stringResource(R.string.plugin_page_loading), modifier = Modifier.padding(Spacing.lg))
                    else -> ActivePluginPage(capture, page, source, { method, params -> latestCall(source, method, params) }, { errorRes = it }, onClose)
                }
            }
        }
    }
    if (embedded) content() else Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { content() }
}

@Composable
private fun ActivePluginPage(
    capture: PluginCapture,
    page: PluginPage,
    invocation: PluginInvocationContext,
    call: suspend (String, JsonObject) -> JsonElement,
    onError: (Int) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val resources: PluginPageResourcesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val scope = rememberCoroutineScope()
    val overlayOwner = remember(capture, page, invocation) { java.util.UUID.randomUUID().toString() }
    val density = LocalDensity.current.density
    var modalOverlay by remember(capture, page, invocation) { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val latestError by rememberUpdatedState(onError)
    val latestClose by rememberUpdatedState(onClose)
    val theme = PluginThemeTokens.variables(MaterialTheme.colorScheme, MaterialTheme.semanticColors, LocalDensity.current.fontScale)
    val latestTheme by rememberUpdatedState(theme)
    val latestCall by rememberUpdatedState(call)
    var subscriptions by remember(capture, page, invocation) { mutableStateOf<PluginPageFileSubscriptions?>(null) }
    val session = remember(capture, page, invocation) {
        PluginPageSession(context, capture, page, dark, invocation::checkCurrent,
            { method, params ->
                when (method) {
                    "ui.close" -> { withContext(Dispatchers.Main.immediate) { latestClose() }; JsonNull }
                    "ui.overlay" -> {
                        withContext(Dispatchers.Main.immediate) { modalOverlay = resources.overlays.update(overlayOwner, params) }
                        JsonNull
                    }
                    "fs.watch", "fs.unwatch" -> subscriptions?.call(method, params)
                        ?: throw com.aicode.feature.plugin.runtime.PluginHostException("CONTEXT_CHANGED", "Page subscriptions are not attached")
                    else -> latestCall(method, params)
                }
            }, { latestError(it) }, { latestTheme })
    }
    DisposableEffect(session) {
        val owned = PluginPageFileSubscriptions(resources.watches, invocation, scope, session::postEvent)
        subscriptions = owned
        session.start()
        onDispose { resources.overlays.close(overlayOwner); subscriptions = null; owned.close(); session.close(); invocation.close() }
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, session) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { subscriptions?.setVisible(true); session.webView.onResume() }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { subscriptions?.setVisible(false); session.webView.onPause() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler {
        session.webView.evaluateJavascript("window.__aicodeBack ? window.__aicodeBack() : false") { consumed ->
            if (consumed != "true") { if (modalOverlay) { resources.overlays.close(overlayOwner); modalOverlay = false } else latestClose() }
        }
    }
    LaunchedEffect(session, invocation) {
        invocation.changes.collect {
            try {
                invocation.checkCurrent()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                session.fail(R.string.plugin_page_reopen)
            } catch (_: Exception) {
                session.fail(R.string.plugin_page_reopen)
            }
        }
    }
    LaunchedEffect(session, theme) { session.applyTheme() }
    key(session) { AndroidView(factory = { session.webView }, modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow() / density
        resources.overlays.setBounds(overlayOwner, androidx.compose.ui.geometry.Rect(origin, androidx.compose.ui.geometry.Size(coordinates.size.width / density, coordinates.size.height / density)))
    }.then(if (modalOverlay) Modifier.background(MaterialTheme.colorScheme.surface) else Modifier), update = {}) }
}
