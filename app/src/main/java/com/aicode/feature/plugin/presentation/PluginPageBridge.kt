package com.aicode.feature.plugin.presentation

import android.net.Uri
import android.webkit.WebView
import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginJson
import com.aicode.feature.plugin.runtime.PluginProcessException
import com.aicode.feature.plugin.runtime.PluginHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class PluginPageBridge(
    private val webView: WebView,
    private val assets: PluginPageAssets,
    private val scope: CoroutineScope,
    private val checkCurrent: () -> Unit,
    private val call: suspend (String, JsonObject) -> JsonElement,
    private val onInvalid: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val activeIds = mutableSetOf<String>()

    fun onMessage(view: WebView, sourceOrigin: Uri, mainFrame: Boolean, message: String) {
        if (closed.get() || view !== webView || !mainFrame || !assets.isOrigin(sourceOrigin) ||
            !assets.isEntry(view.url) || message.length > 64 * 1024
        ) return
        val request = try { PluginJson.parse(message, maxDepth = 32) as? JsonObject } catch (_: Exception) { null } ?: return
        val idValue = request["id"] as? JsonPrimitive ?: return
        if ((!idValue.isString && (idValue === JsonNull || idValue.booleanOrNull != null)) ||
            idValue.content.isBlank() || idValue.content.length > 128
        ) return
        val id = idValue.content
        if (id in activeIds || activeIds.size >= 32) return
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (method.isBlank() || method.length > 128) return
        val params = request["params"] as? JsonObject ?: return
        val owner = generation.get()
        activeIds += id
        scope.launch {
            try {
                val response = withContext(ioDispatcher) {
                    checkCurrent()
                    val result = call(method, params)
                    checkCurrent()
                    buildJsonObject { put("ok", true); put("data", result) }
                }
                deliver(owner, id, response)
            } catch (cancelled: CancellationException) {
                if (isActive) onInvalid() else throw cancelled
            } catch (_: PluginCaptureException) {
                onInvalid()
            } catch (failure: PluginProcessException) {
                if (failure.code in STALE_CODES) onInvalid()
                else deliver(owner, id, error(failure.code, method))
            } catch (failure: PluginHostException) {
                if (failure.code in STALE_CODES) onInvalid()
                else deliver(owner, id, error(failure.code, method))
            } catch (_: Exception) {
                deliver(owner, id, error("INTERNAL", method))
            } finally {
                activeIds -= id
            }
        }
    }

    private suspend fun deliver(owner: Long, id: String, response: JsonObject) {
        if (!scope.isActive || closed.get() || generation.get() != owner) return
        try {
            withContext(ioDispatcher) { checkCurrent() }
        } catch (cancelled: CancellationException) {
            if (scope.isActive) onInvalid() else throw cancelled
            return
        } catch (_: Exception) {
            onInvalid()
            return
        }
        if (scope.isActive && !closed.get() && generation.get() == owner && assets.isEntry(webView.url)) {
            webView.evaluateJavascript(replyScript(id, response), null)
        }
    }

    override fun close() {
        closed.set(true)
        generation.incrementAndGet()
    }

    private fun error(code: String, method: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code.take(80))
            put("message", webView.context.getString(runtimeProblem(code, "", method).messageRes))
        })
    }

    companion object {
        private val STALE_CODES = setOf("STALE_CAPTURE", "CONTENT_CHANGED", "STALE_CONTEXT", "CONTEXT_CHANGED", "PLUGIN_DISABLED", "NOT_INSTALLED")
        internal fun replyScript(id: String, response: JsonObject): String {
            fun quote(value: String): String = JsonPrimitive(value).toString()
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
            return "window.__aicodeResolve && window.__aicodeResolve(${quote(id)},${quote(response.toString())});"
        }
    }
}
