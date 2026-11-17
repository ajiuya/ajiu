package com.aicode.feature.filepreview.data

import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import com.aicode.feature.filepreview.domain.PreviewLimits
import com.aicode.feature.filepreview.domain.PreviewTypes
import com.aicode.feature.filepreview.domain.readPreviewText
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface PreviewWebRoute {
    data class Local(val relative: String) : PreviewWebRoute
    data class Asset(val name: String) : PreviewWebRoute
    data object External : PreviewWebRoute
    data object Denied : PreviewWebRoute
}

internal class PreviewWebAssets(
    private val source: PreviewSource,
    path: String,
    private val markdown: Boolean,
    private val assets: AssetManager,
    private val dark: Boolean,
    private val entryMime: String = "",
    private val onError: () -> Unit,
) : AutoCloseable {
    private val token = UUID.randomUUID().toString()
    private val host = "preview-$token.invalid"
    internal val origin = "https://$host"
    private val prefix = "/$token/"
    private val assetBase = "$origin${prefix}markdown/"
    private val closed = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()
    private val documentPath = path.substringBefore('#')
    val entryUrl: String = run {
        val root = source.entryPath.substringBeforeLast('/', "") + "/"
        require(documentPath.startsWith(root))
        val relative = documentPath.removePrefix(root)
        require(relative.isNotEmpty())
        val url = "$origin${prefix}files/${Uri.encode(relative, "/")}"
        if ('#' in path) "$url#${Uri.encode(path.substringAfter('#'))}" else url
    }

    internal fun classify(url: String): PreviewWebRoute {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return PreviewWebRoute.Denied
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null || url.any { it <= ' ' || it == '\\' }
        ) return PreviewWebRoute.Denied
        if (!uri.host.equals(host, ignoreCase = true)) {
            return if (uri.host.lowercase().endsWith(".invalid")) {
                PreviewWebRoute.Denied
            } else {
                PreviewWebRoute.External
            }
        }
        if (scheme != "https" || uri.port != -1) return PreviewWebRoute.Denied
        val rawPath = uri.rawPath ?: return PreviewWebRoute.Denied
        if (!rawPath.startsWith(prefix)) return PreviewWebRoute.Denied
        val suffix = rawPath.removePrefix(prefix)
        if (suffix.startsWith("markdown/")) {
            val name = suffix.removePrefix("markdown/")
            return if (name in MARKDOWN_ASSETS) PreviewWebRoute.Asset(name) else PreviewWebRoute.Denied
        }
        if (!suffix.startsWith("files/")) return PreviewWebRoute.Denied
        val relative = suffix.removePrefix("files/")
        val decoded = Uri.decode(relative)
        if (decoded.isEmpty() || decoded.startsWith('/') ||
            decoded.any { it <= '\u001f' || it == '\u007f' || it in "\\%:?#" } ||
            decoded.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) return PreviewWebRoute.Denied
        return PreviewWebRoute.Local(relative)
    }

    internal fun isCurrentAnchor(url: String): Boolean {
        if (classify(url) !is PreviewWebRoute.Local) return false
        return try {
            val target = URI(url)
            val entry = URI(entryUrl)
            target.rawFragment != null && target.rawPath == entry.rawPath && target.rawQuery == entry.rawQuery
        } catch (_: Exception) {
            false
        }
    }

    internal fun localLink(url: String): String? {
        val route = classify(url) as? PreviewWebRoute.Local ?: return null
        if (!isAvailable()) return null
        return try {
            source.checkCurrent()
            val resolved = source.resolve(route.relative, source.entryPath)
            source.checkCurrent()
            val fragment = URI(url).rawFragment
            if (fragment == null) resolved else "$resolved#${Uri.decode(fragment)}"
        } catch (_: Exception) {
            if (!source.valid.value) reportError()
            null
        }
    }

    internal fun intercept(url: String, method: String = "GET", mainFrame: Boolean = false): WebResourceResponse {
        if (!isAvailable()) return failure(410)
        if (method != "GET") return failure(403)
        return when (val route = classify(url)) {
            is PreviewWebRoute.Local -> serveLocal(route, mainFrame)
            is PreviewWebRoute.Asset -> {
                if (!markdown || mainFrame) return failure(403)
                try {
                    source.checkCurrent()
                    val bytes = readBounded(assets.open("markdown/${route.name}"), PreviewLimits.RESOURCE_BYTES)
                    source.checkCurrent()
                    response(assetMime(route.name), bytes)
                } catch (_: Exception) {
                    reportError()
                    failure(404)
                }
            }
            PreviewWebRoute.External, PreviewWebRoute.Denied -> failure(403)
        }
    }

    private fun serveLocal(route: PreviewWebRoute.Local, mainFrame: Boolean): WebResourceResponse {
        return try {
            source.checkCurrent()
            val resolved = source.resolve(route.relative, source.entryPath)
            source.checkCurrent()
            if (mainFrame && resolved != documentPath) return failure(403)
            val mime = PreviewTypes.mime(resolved, if (resolved == documentPath) entryMime else "")
            if (mainFrame && !markdown && mime !in setOf("text/html", "application/xhtml+xml")) return failDocument(415)
            if (!mainFrame && (mime.startsWith("audio/") || mime.startsWith("video/") ||
                    mime in setOf("text/html", "application/xhtml+xml", "application/pdf"))
            ) return failure(403)
            val limit = if (mainFrame || mime.startsWith("text/")) {
                PreviewLimits.TEXT_BYTES
            } else {
                PreviewLimits.RESOURCE_BYTES
            }
            val bytes = readBounded(source.openRead(resolved), limit)
            source.checkCurrent()
            if (!isAvailable()) return failure(410)
            if (mainFrame && markdown) {
                val template = readBounded(assets.open("markdown/preview.html"), PreviewLimits.TEXT_BYTES)
                    .toString(Charsets.UTF_8)
                val text = readPreviewText(ByteArrayInputStream(bytes)).text
                val html = markdownHtml(template, text, assetBase, dark)
                response("text/html", html.toByteArray(Charsets.UTF_8))
            } else {
                response(mime, bytes)
            }
        } catch (_: PreviewResourceTooLarge) {
            if (mainFrame) reportError()
            failure(413)
        } catch (_: Exception) {
            if (mainFrame || !source.valid.value) reportError()
            failure(404)
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        return track(input).use { stream ->
            val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                if (count == 0) throw IOException("Preview read made no progress")
                output.write(buffer, 0, count)
                if (output.size() > limit) throw PreviewResourceTooLarge()
            }
            output.toByteArray()
        }
    }

    private fun response(mime: String, bytes: ByteArray): WebResourceResponse {
        if (!isAvailable()) return failure(410)
        return WebResourceResponse(
            mime,
            if (mime.startsWith("text/") || mime in setOf("application/javascript", "application/json", "image/svg+xml")) "UTF-8" else null,
            200,
            "OK",
            RESPONSE_HEADERS,
            track(ByteArrayInputStream(bytes)),
        )
    }

    private fun track(input: InputStream): InputStream {
        val stream = object : InputStream() {
            private val streamClosed = AtomicBoolean(false)
            private fun checkOpen() {
                if (streamClosed.get() || !isAvailable()) throw IOException("Preview closed")
                source.checkCurrent()
            }
            override fun read(): Int {
                checkOpen()
                return input.read().also { checkOpen() }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkOpen()
                return input.read(buffer, offset, length).also { checkOpen() }
            }
            override fun skip(count: Long): Long {
                checkOpen()
                return input.skip(count).also { checkOpen() }
            }
            override fun available(): Int {
                checkOpen()
                return input.available()
            }
            override fun close() {
                if (streamClosed.compareAndSet(false, true)) {
                    streams.remove(this)
                    input.close()
                }
            }
        }
        streams.add(stream)
        if (!isAvailable()) {
            stream.close()
            throw IOException("Preview closed")
        }
        return stream
    }

    private fun isAvailable(): Boolean = !closed.get() && source.valid.value

    private fun failDocument(status: Int): WebResourceResponse {
        reportError()
        return failure(status)
    }

    private fun reportError() {
        if (!closed.get() && errorReported.compareAndSet(false, true)) onError()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            streams.toList().forEach { runCatching { it.close() } }
        }
    }

    private class PreviewResourceTooLarge : IOException()

    companion object {
        internal val MARKDOWN_ASSETS = setOf(
            "marked.min.js", "purify.min.js", "highlight.min.js",
            "github-markdown-dark.css", "github-markdown-light.css",
            "highlight-github-dark.min.css", "highlight-github.min.css",
        )
        internal const val CONTENT_SECURITY_POLICY = "default-src 'none'; " +
            "script-src 'self' 'unsafe-inline'; script-src-attr 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; " +
            "connect-src 'none'; form-action 'none'; object-src 'none'; frame-src 'none'; " +
            "child-src 'none'; worker-src 'none'; media-src 'none'; manifest-src 'none'; " +
            "base-uri 'none'; frame-ancestors 'none'; sandbox allow-scripts allow-same-origin"
        private val RESPONSE_HEADERS = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
            "Referrer-Policy" to "no-referrer",
            "X-DNS-Prefetch-Control" to "off",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
            "Cross-Origin-Resource-Policy" to "same-origin",
        )

        internal fun markdownHtml(template: String, text: String, assetBase: String, dark: Boolean): String {
            val base64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            return template
                .replace("__ASSET_BASE__", assetBase)
                .replace("__MD_CSS__", if (dark) "github-markdown-dark.css" else "github-markdown-light.css")
                .replace("__HL_CSS__", if (dark) "highlight-github-dark.min.css" else "highlight-github.min.css")
                .replace("__BG__", if (dark) "#0d1117" else "#ffffff")
                .replace("__MD_B64__", base64)
        }

        private fun assetMime(name: String): String =
            if (name.endsWith(".css")) "text/css" else "application/javascript"

        private fun failure(status: Int): WebResourceResponse = WebResourceResponse(
            "text/plain", "UTF-8", status,
            when (status) {
                410 -> "Gone"
                413 -> "Payload Too Large"
                415 -> "Unsupported Media Type"
                404 -> "Not Found"
                else -> "Forbidden"
            },
            RESPONSE_HEADERS,
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}
