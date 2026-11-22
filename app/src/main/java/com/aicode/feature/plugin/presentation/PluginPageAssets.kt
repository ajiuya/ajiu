package com.aicode.feature.plugin.presentation

import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginManifestParser
import com.aicode.feature.plugin.domain.PluginPage
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class PluginPageAssets(
    private val capture: PluginCapture,
    private val page: PluginPage,
    private val checkContext: () -> Unit,
    private val onInvalid: () -> Unit,
    private val readHostAsset: (String) -> ByteArray? = { null },
    private val themeCss: () -> String = { "" }
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val identity = MessageDigest.getInstance("SHA-256")
        .digest("${capture.id}\u0000${capture.installRevision}\u0000${page.localId}\u0000${page.pageFile}\u0000${java.util.UUID.randomUUID()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val host = "plugin-${identity.take(48)}.invalid"
    val origin = "https://$host"
    val entryUrl = "$origin/plugin/${Uri.encode(page.pageFile, "/")}?installRevision=${Uri.encode(capture.installRevision)}"
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(host)
        .setHttpAllowed(false)
        .addPathHandler("/plugin/", WebViewAssetLoader.PathHandler { path -> servePackage(path) })
        .addPathHandler("/__aicode/", WebViewAssetLoader.PathHandler { path ->
            when {
                path == "aicode.js" -> response("application/javascript", PluginPageSdk.SCRIPT.toByteArray(Charsets.UTF_8))
                path == "theme.css" -> response("text/css", themeCss().toByteArray(Charsets.UTF_8))
                path in HOST_ASSETS -> readHostAsset(path)?.let { response(mime(path), it) } ?: denied()
                else -> denied()
            }
        }).build()

    init {
        require(page.pluginId == capture.id && page.installDir == capture.installDir)
        require(capture.content.pages.any { it == page } || capture.content.overlay?.surfaces?.any {
            it.pluginId == page.pluginId && it.localId == page.localId && it.pageFile == page.pageFile && it.installDir == page.installDir
        } == true)
    }

    fun isOrigin(source: Uri): Boolean = source.scheme == "https" && source.host == host &&
        source.port == -1 && source.userInfo == null && source.query == null && source.fragment == null &&
        source.path.orEmpty() in setOf("", "/")

    fun isEntry(url: String?): Boolean {
        if (url == null) return false
        return try {
            val candidate = URI(url)
            val entry = URI(entryUrl)
            candidate.scheme == entry.scheme && candidate.rawAuthority == entry.rawAuthority &&
                candidate.rawPath == entry.rawPath && candidate.rawQuery == entry.rawQuery && candidate.rawUserInfo == null
        } catch (_: Exception) { false }
    }

    fun intercept(uri: Uri, method: String = "GET", mainFrame: Boolean = false): WebResourceResponse {
        if (closed.get() || method != "GET") return denied()
        val parsed = try { URI(uri.toString()) } catch (_: Exception) { return denied() }
        if (parsed.scheme != "https" || parsed.host != host || parsed.port != -1 || parsed.rawUserInfo != null ||
            parsed.rawFragment != null || uri.toString().any { it <= ' ' || it == '\\' }
        ) return denied()
        val rawPath = parsed.rawPath ?: return denied()
        val decoded = Uri.decode(rawPath)
        if (decoded.contains('%') || decoded.any { it <= '\u001f' || it == '\u007f' || it in "\\:?#" } ||
            decoded.split('/').drop(1).any { it.isEmpty() || it == "." || it == ".." }
        ) return denied()
        if (mainFrame && !isEntry(uri.toString())) return denied()
        if (!mainFrame && mime(decoded) in setOf("text/html", "image/svg+xml")) return denied()
        return try {
            capture.checkCurrent()
            checkContext()
            val result = loader.shouldInterceptRequest(uri) ?: denied()
            capture.checkCurrent()
            checkContext()
            if (closed.get()) denied() else result
        } catch (_: Exception) {
            onInvalid()
            denied()
        }
    }

    private fun servePackage(path: String): WebResourceResponse {
        if (!PluginManifestParser.isSafeRelativePath(path) || !capture.record.fileHashes.containsKey(path)) return denied()
        return try {
            val bytes = PluginFiles.readRegular(capture.installDir, path, 4 * 1024 * 1024)
            if (PluginFiles.sha256(bytes) != capture.record.fileHashes[path]) {
                onInvalid()
                return denied()
            }
            response(mime(path), bytes)
        } catch (_: Exception) {
            onInvalid()
            denied()
        }
    }

    override fun close() { closed.set(true) }

    private fun response(mime: String, bytes: ByteArray) = WebResourceResponse(
        mime, "UTF-8", 200, "OK", HEADERS, ByteArrayInputStream(bytes)
    )

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", HEADERS, ByteArrayInputStream(ByteArray(0)))

    private fun mime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    companion object {
        // Vue's packaged runtime compiler needs eval; the origin still cannot load remote code or connect to the network.
        internal const val CSP = "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; font-src 'self'; connect-src 'none'; frame-src 'none'; child-src 'none'; " +
            "worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        internal val HOST_ASSETS = setOf("vue.global.prod.js", "vue-router.global.prod.js", "aicode-ui-vue.js", "ui.css", "markdown/marked.min.js", "markdown/purify.min.js", "markdown/highlight.min.js", "markdown/github-markdown-dark.css", "markdown/github-markdown-light.css", "markdown/highlight-github-dark.min.css", "markdown/highlight-github.min.css")
        internal val HEADERS = mapOf(
            "Content-Security-Policy" to CSP,
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Permissions-Policy" to "geolocation=(), camera=(), microphone=(), usb=(), payment=()"
        )
    }
}
