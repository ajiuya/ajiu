package com.aicode.feature.plugin.presentation

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.aicode.R
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.SemVer
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginProcessException
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginPageSecurityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun originsArePluginAndInstallRevisionSpecificNotVersionSpecific(): Unit {
        val first = fixture("revision-one")
        val reinstall = fixture("revision-two")
        val other = fixture("revision-one", "sample.other")
        assertNotEquals(first.assets.origin, reinstall.assets.origin)
        assertNotEquals(first.assets.origin, other.assets.origin)
        assertTrue(first.assets.entryUrl.contains("installRevision=revision-one"))
        assertTrue(first.assets.isEntry(first.assets.entryUrl))
        assertFalse(first.assets.isEntry(first.assets.entryUrl.replace("revision-one", "revision-two")))
    }

    @Test fun onlyPackagedAssetsAndTheMinimalHostSdkCanBeServed(): Unit {
        val fixture = fixture()
        val html = fixture.assets.intercept(Uri.parse(fixture.assets.entryUrl), mainFrame = true)
        assertEquals(200, html.statusCode)
        assertEquals("text/html", html.mimeType)
        assertEquals("<html><body>Package</body></html>", html.data.bufferedReader().readText())
        assertEquals(PluginPageAssets.CSP, html.responseHeaders["Content-Security-Policy"])
        val script = fixture.assets.intercept(Uri.parse(fixture.assets.origin + "/plugin/script.js"))
        assertEquals(200, script.statusCode)
        assertEquals("window.packageLoaded=true;", script.data.bufferedReader().readText())
        val sdk = fixture.assets.intercept(Uri.parse(fixture.assets.origin + "/__aicode/aicode.js"))
        assertEquals(200, sdk.statusCode)
        assertEquals(PluginPageSdk.SCRIPT, sdk.data.bufferedReader().readText())
        assertEquals(403, fixture.assets.intercept(Uri.parse(fixture.assets.origin + "/__aicode/vue.js")).statusCode)
    }

    @Test fun externalFileContentWrongRevisionAndTraversalRequestsNeverFallBackToNetwork(): Unit {
        val fixture = fixture()
        val urls = listOf(
            "https://example.invalid/remote.js", "http://example.invalid/script.js", "file:///etc/passwd",
            "content://storage/item", "data:text/html,hello", "https://appassets.androidplatform.net/plugin/page.html",
            fixture.assets.origin + "/plugin/missing.js", fixture.assets.origin + "/plugin/%2e%2e/plugin.json",
            fixture.assets.origin + "/plugin/%252e%252e/secret", fixture.assets.origin + "/plugin/a%2fb%2f..%2fscript.js",
            fixture.assets.origin + "/__aicode/eruda.js", fixture.assets.origin + "/other/script.js"
        )
        urls.forEach { assertEquals(it, 403, fixture.assets.intercept(Uri.parse(it)).statusCode) }
        assertEquals(403, fixture.assets.intercept(Uri.parse(fixture.assets.entryUrl), method = "POST", mainFrame = true).statusCode)
        assertEquals(403, fixture.assets.intercept(Uri.parse(fixture.assets.entryUrl), mainFrame = false).statusCode)
        assertEquals(403, fixture.assets.intercept(Uri.parse(fixture.assets.entryUrl.replace("revision-one", "revision-two")), mainFrame = true).statusCode)
    }

    @Test fun packageChangesAndClosedSourcesRejectEverySubsequentResource(): Unit {
        val fixture = fixture()
        File(fixture.capture.installDir, "script.js").writeText("changed")
        assertEquals(403, fixture.assets.intercept(Uri.parse(fixture.assets.origin + "/plugin/script.js")).statusCode)
        assertTrue(fixture.invalidations > 0)
        fixture.assets.close()
        assertEquals(403, fixture.assets.intercept(Uri.parse(fixture.assets.entryUrl), mainFrame = true).statusCode)
    }

    @Test fun bridgeRequiresMainFrameExactOriginAndExactWebViewReference(): Unit = runTest {
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        val other = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        other.loadUrl(fixture.assets.entryUrl)
        var calls = 0
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent,
            { _, _ -> calls++; JsonPrimitive("ok") }, { fixture.invalidations++ }, dispatcher)
        try {
            val valid = Uri.parse(fixture.assets.origin)
            val hostile = listOf(
                "https://${fixture.assets.host}.evil.invalid", "http://${fixture.assets.host}", "https://${fixture.assets.host}:444",
                "https://other.invalid", "file://${fixture.assets.host}", "https://user@${fixture.assets.host}", "null"
            )
            hostile.forEach { bridge.onMessage(view, Uri.parse(it), true, message()) }
            bridge.onMessage(view, valid, false, message())
            bridge.onMessage(other, valid, true, message())
            runCurrent()
            assertEquals(0, calls)
            assertNull(shadowOf(view).lastEvaluatedJavascript)
            bridge.onMessage(view, valid, true, message())
            runCurrent()
            assertEquals(1, calls)
            assertTrue(shadowOf(view).lastEvaluatedJavascript.startsWith("window.__aicodeResolve"))
        } finally { bridge.close(); owner.cancel(); view.destroy(); other.destroy() }
    }

    @Test fun bridgePassesCapturedMethodAndJsonArgumentsAndEncodesUntrustedReplyData(): Unit = runTest {
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        val id = "id\");window.evil=true;//\n"
        val result = buildJsonObject { put("value", "\");evil();\n\u2028\u2029</script>") }
        var received: Pair<String, JsonObject>? = null
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent,
            { method, args -> received = method to args; result }, { fixture.invalidations++ }, dispatcher)
        val args = buildJsonObject { put("path", "data://settings.json") }
        try {
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, buildJsonObject {
                put("id", id); put("method", "fs.read"); put("params", args)
            }.toString())
            runCurrent()
            assertEquals("fs.read" to args, received)
            val expected = PluginPageBridge.replyScript(id, buildJsonObject { put("ok", true); put("data", result) })
            assertEquals(expected, shadowOf(view).lastEvaluatedJavascript)
            assertFalse(expected.contains('\n'))
            assertFalse(expected.contains('\u2028'))
            assertFalse(expected.contains('\u2029'))
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    @Test fun unsupportedApprovalAndOtherHostCallsReturnTypedFailureNotSuccess(): Unit = runTest {
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        val received = mutableListOf<String>()
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent, { method, _ ->
            received += method
            throw PluginHostException("METHOD_NOT_SUPPORTED", "Unsupported host API")
        }, { fixture.invalidations++ }, dispatcher)
        try {
            for (method in listOf("tools.resolve", "chat.resolveToolApproval", "overlay.create", "providers.setApiKey", "unknown")) {
                bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message(method = method))
                runCurrent()
                val expected = PluginPageBridge.replyScript("request-one", buildJsonObject {
                    put("ok", false)
                    put("error", buildJsonObject { put("code", "METHOD_NOT_SUPPORTED"); put("message", view.context.getString(R.string.plugin_error_not_supported)) })
                })
                assertEquals(expected, shadowOf(view).lastEvaluatedJavascript)
            }
            assertEquals(5, received.size)
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    @Test fun closingOrCancellingThePageDropsQueuedAndLateReplies(): Unit = runTest {
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        val result = CompletableDeferred<JsonElement>()
        var entered = false
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent,
            { _, _ -> entered = true; result.await() }, { fixture.invalidations++ }, dispatcher)
        try {
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message())
            runCurrent()
            assertTrue(entered)
            bridge.close()
            owner.cancel()
            result.complete(JsonPrimitive("late"))
            runCurrent()
            assertNull(shadowOf(view).lastEvaluatedJavascript)
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    @Test fun replacingOrDisablingContentWhileCallWaitsInvalidatesRatherThanDelivering(): Unit = runTest {
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        val result = CompletableDeferred<JsonElement>()
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent,
            { _, _ -> result.await() }, { fixture.invalidations++ }, dispatcher)
        try {
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message())
            runCurrent()
            fixture.current = false
            result.complete(JsonPrimitive("stale"))
            runCurrent()
            assertNull(shadowOf(view).lastEvaluatedJavascript)
            assertTrue(fixture.invalidations > 0)
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    @Test fun switchingExecutionContextRefusesOldBridgeWithoutSelectingANewWorkspace(): Unit = runTest {
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        var stale = false
        var calls = 0
        val bridge = PluginPageBridge(view, fixture.assets, owner, {
            fixture.capture.checkCurrent()
            if (stale) throw kotlinx.coroutines.CancellationException("Context changed")
        }, { _, _ -> calls++; JsonPrimitive("ok") }, { fixture.invalidations++ }, dispatcher)
        try {
            stale = true
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message())
            runCurrent()
            assertEquals(0, calls)
            assertTrue(fixture.invalidations > 0)
            assertNull(shadowOf(view).lastEvaluatedJavascript)
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    @Test fun everyPageInstanceUsesANewOriginEvenForTheSamePluginRevision(): Unit {
        val fixture = fixture()
        val page = fixture.capture.content.configPage!!
        val other = PluginPageAssets(fixture.capture, page, {}, {})
        assertNotEquals(fixture.assets.origin, other.origin)
    }

    @Test fun localVueRouterMarkdownAndUiAssetsAreServedWithoutRemoteFallback(): Unit {
        val fixture = fixture()
        val page = fixture.capture.content.configPage!!
        val assets = PluginPageAssets(fixture.capture, page, {}, {}, { path -> "host:$path".toByteArray() })
        for (path in PluginPageAssets.HOST_ASSETS) {
            val response = assets.intercept(Uri.parse(assets.origin + "/__aicode/" + path))
            assertEquals(path, 200, response.statusCode)
            assertEquals("host:$path", response.data.bufferedReader().readText())
        }
        assertEquals(403, assets.intercept(Uri.parse(assets.origin + "/__aicode/unknown.js")).statusCode)
    }

    @Test fun vueCompilerCspKeepsRemoteNetworkingFramesAndWorkersDisabled(): Unit {
        assertTrue(PluginPageAssets.CSP.contains("'unsafe-eval'"))
        for (directive in listOf("connect-src 'none'", "frame-src 'none'", "worker-src 'none'", "form-action 'none'")) assertTrue(directive, PluginPageAssets.CSP.contains(directive))
        assertFalse(PluginPageAssets.CSP.contains("https:"))
        assertFalse(PluginPageSdk.SCRIPT.contains("resolveToolApproval"))
        assertFalse(PluginPageSdk.SCRIPT.contains("resolvePlanApproval"))
        assertFalse(PluginPageSdk.SCRIPT.contains("tools.resolve"))
    }

    @Test
    @Config(qualifiers = "zh")
    fun overlayCapabilityFailureUsesChineseGuidanceAndPreservesCode(): Unit = runTest {
        val raw = "Grant this capability in native plugin settings"
        val script = assertBridgeFailure("zh", "overlay.create", PluginHostException("CAPABILITY_REQUIRED", raw),
            "CAPABILITY_REQUIRED", R.string.plugin_error_overlay_capability_required)
        assertTrue(script.contains("尚未授权系统浮层。"))
        assertFalse(script.contains(raw))
    }

    @Test
    @Config(qualifiers = "zh")
    fun prefixedCreateAndUpdateShareOverlayCapabilityGuidance(): Unit = runTest {
        for (method in listOf("host.overlay.create", "overlay.update", "host.overlay.update")) {
            val raw = "Grant this capability in native plugin settings"
            val script = assertBridgeFailure("zh", method, PluginHostException("CAPABILITY_REQUIRED", raw),
                "CAPABILITY_REQUIRED", R.string.plugin_error_overlay_capability_required)
            assertTrue(script.contains("尚未授权系统浮层。"))
            assertFalse(script.contains(raw))
        }
    }

    @Test
    @Config(qualifiers = "zh")
    fun systemOverlayPermissionFailureUsesDistinctSystemGuidance(): Unit = runTest {
        val raw = "System overlay permission is required"
        val script = assertBridgeFailure("zh", "overlay.create", PluginHostException("OVERLAY_SYSTEM_PERMISSION_MISSING", raw),
            "OVERLAY_SYSTEM_PERMISSION_MISSING", R.string.plugin_error_overlay_system_permission)
        val pageContext = localizedContext("zh")
        assertTrue(script.contains("系统悬浮窗权限未开启。"))
        assertFalse(script.contains(pageContext.getString(R.string.plugin_error_overlay_capability_required)))
        assertFalse(script.contains(pageContext.getString(R.string.plugin_error_capability_required)))
        assertFalse(script.contains(raw))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishPageContextGetsEnglishOverlayGuidance(): Unit = runTest {
        val raw = "Grant this capability in native plugin settings"
        val script = assertBridgeFailure("en", "overlay.create", PluginHostException("CAPABILITY_REQUIRED", raw),
            "CAPABILITY_REQUIRED", R.string.plugin_error_overlay_capability_required)
        val chinese = localizedContext("zh").getString(R.string.plugin_error_overlay_capability_required)
        val english = localizedContext("en").getString(R.string.plugin_error_overlay_capability_required)
        assertNotEquals(chinese, english)
        assertTrue(script.contains("Overlay capability has not been granted."))
        assertFalse(script.contains(chinese))
        assertFalse(script.contains(raw))
    }

    @Test
    @Config(qualifiers = "zh")
    fun unknownHostFailurePreservesCodeWithoutLeakingRawDetail(): Unit = runTest {
        val raw = "Private host path /private/plugin/settings.json; secret=host-do-not-show"
        val script = assertBridgeFailure("zh", "overlay.create", PluginHostException("PRIVATE_HOST_FAILURE", raw),
            "PRIVATE_HOST_FAILURE", R.string.plugin_error_runtime)
        assertTrue(script.contains("插件进程或宿主调用失败。"))
        assertFalse(script.contains(raw))
        assertFalse(script.contains("host-do-not-show"))
        assertFalse(script.contains("/private/plugin/settings.json"))
    }

    @Test
    @Config(qualifiers = "zh")
    fun knownProcessFailureUsesLocalizedRuntimeMessage(): Unit = runTest {
        val raw = "Node runtime unavailable at /private/runtime/process; secret=process-do-not-show"
        for (language in listOf("zh", "en")) {
            val script = assertBridgeFailure(language, "app.info", PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", raw),
                "PLUGIN_RUNTIME_UNAVAILABLE", R.string.plugin_error_runtime_missing)
            assertFalse(script.contains(raw))
            assertFalse(script.contains("process-do-not-show"))
            assertFalse(script.contains("/private/runtime/process"))
        }
    }

    @Test
    @Config(qualifiers = "zh")
    fun unexpectedBridgeFailureUsesGenericLocalizedMessage(): Unit = runTest {
        val raw = "Unexpected internal diagnostic; secret=internal-do-not-show"
        for (language in listOf("zh", "en")) {
            val script = assertBridgeFailure(language, "overlay.create", IllegalStateException(raw),
                "INTERNAL", R.string.plugin_error_runtime)
            assertFalse(script.contains(raw))
            assertFalse(script.contains("internal-do-not-show"))
            assertFalse(script.contains(IllegalStateException::class.java.simpleName))
        }
    }

    @Test fun staleHostFailuresInvalidateWithoutRepliesOrEnablingPlugin(): Unit = runTest {
        for (code in listOf("STALE_CAPTURE", "CONTENT_CHANGED", "STALE_CONTEXT", "CONTEXT_CHANGED", "PLUGIN_DISABLED", "NOT_INSTALLED")) {
            assertStaleFailure(PluginHostException(code, "Private stale host diagnostic"))
        }
    }

    @Test fun staleProcessFailuresInvalidateWithoutRepliesOrEnablingPlugin(): Unit = runTest {
        for (code in listOf("STALE_CAPTURE", "CONTENT_CHANGED", "STALE_CONTEXT", "CONTEXT_CHANGED", "PLUGIN_DISABLED", "NOT_INSTALLED")) {
            assertStaleFailure(PluginProcessException(code, "Private stale process diagnostic"))
        }
    }

    private fun localizedContext(language: String): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    )

    private suspend fun TestScope.assertBridgeFailure(
        language: String,
        method: String,
        failure: Exception,
        expectedCode: String,
        expectedMessageRes: Int
    ): String {
        val originalLocales = context.resources.configuration.locales.toLanguageTags()
        val fixture = fixture()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(localizedContext(language))
        view.loadUrl(fixture.assets.entryUrl)
        val received = mutableListOf<String>()
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent, { calledMethod, _ ->
            received += calledMethod
            throw failure
        }, { fixture.invalidations++ }, dispatcher)
        try {
            assertEquals(language, view.context.resources.configuration.locales[0].language)
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message(method))
            runCurrent()
            val expected = PluginPageBridge.replyScript("request-one", buildJsonObject {
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", expectedCode)
                    put("message", view.context.getString(expectedMessageRes))
                })
            })
            val script = shadowOf(view).lastEvaluatedJavascript
            assertEquals(expected, script)
            assertEquals(listOf(method), received)
            assertEquals(0, fixture.invalidations)
            assertEquals(originalLocales, context.resources.configuration.locales.toLanguageTags())
            return script
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    private suspend fun TestScope.assertStaleFailure(failure: Exception): Unit {
        val fixture = fixture(enabled = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val view = WebView(context)
        view.loadUrl(fixture.assets.entryUrl)
        val received = mutableListOf<String>()
        val bridge = PluginPageBridge(view, fixture.assets, owner, fixture.capture::checkCurrent, { method, _ ->
            received += method
            throw failure
        }, { fixture.invalidations++; fixture.current = false }, dispatcher)
        try {
            assertFalse(fixture.capture.record.enabled)
            assertFalse(view.settings.javaScriptEnabled)
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message("overlay.create"))
            runCurrent()
            assertEquals(listOf("overlay.create"), received)
            assertEquals(1, fixture.invalidations)
            assertFalse(fixture.current)
            assertFalse(fixture.capture.record.enabled)
            assertFalse(view.settings.javaScriptEnabled)
            assertNull(shadowOf(view).lastEvaluatedJavascript)
            bridge.onMessage(view, Uri.parse(fixture.assets.origin), true, message("overlay.create"))
            runCurrent()
            assertEquals(listOf("overlay.create"), received)
            assertEquals(2, fixture.invalidations)
            assertFalse(fixture.current)
            assertFalse(fixture.capture.record.enabled)
            assertFalse(view.settings.javaScriptEnabled)
            assertNull(shadowOf(view).lastEvaluatedJavascript)
        } finally { bridge.close(); owner.cancel(); view.destroy() }
    }

    private class Fixture(val capture: PluginCapture, val assets: PluginPageAssets, var current: Boolean = true, var invalidations: Int = 0)
    private fun fixture(revision: String = "revision-one", id: String = "sample.plugin", enabled: Boolean = true): Fixture {
        val root = temporary.newFolder()
        File(root, "page.html").writeText("<html><body>Package</body></html>")
        File(root, "script.js").writeText("window.packageLoaded=true;")
        val hashes = PluginFiles.hashTree(root)
        val hash = PluginFiles.contentHash(hashes)
        val record = PluginRecord(id, "1.0.0", enabled, 1, archiveSha256 = hash, manifestSha256 = hash,
            installRevision = revision, contentHash = hash, fileHashes = hashes, trustHash = hash)
        val manifest = PluginManifest(1, id, LocalizedText.single("Sample"), SemVer(1, 0, 0), "test", "", LocalizedText.single(""), "MIT", "1.0.0")
        val page = PluginPage(id, "page", LocalizedText.single("Page"), null, "page.html", "book", root, "1.0.0")
        lateinit var result: Fixture
        fun check() {
            if (!result.current) throw PluginCaptureException(PluginError(PluginErrorCode.STALE_CAPTURE))
        }
        val capture = PluginCapture(record, manifest, PluginContent(configPage = page), root,
            { check(); if (!PluginFiles.verify(root, hashes)) throw PluginCaptureException(PluginError(PluginErrorCode.CONTENT_CHANGED)) }, { check() })
        val assets = PluginPageAssets(capture, page, { check() }, { result.invalidations++ })
        result = Fixture(capture, assets)
        return result
    }
    private fun message(method: String = "app.info"): String = buildJsonObject {
        put("id", "request-one"); put("method", method); put("params", JsonObject(emptyMap()))
    }.toString()
}
