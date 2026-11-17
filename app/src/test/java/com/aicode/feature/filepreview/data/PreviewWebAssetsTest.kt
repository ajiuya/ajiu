package com.aicode.feature.filepreview.data

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.filepreview.domain.PreviewLimits
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PreviewWebAssetsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val valid = MutableStateFlow(true)
    private val source = mockk<PreviewSource>(relaxed = true)
    private val sessions = mutableListOf<PreviewWebAssets>()
    private var errors = 0

    private fun session(path: String = "/root/workspace/site/index.html", markdown: Boolean = false): PreviewWebAssets {
        every { source.entryPath } returns "/root/workspace/site/index.html"
        every { source.valid } returns valid
        every { source.resolve(any(), any()) } answers {
            "/root/workspace/site/${Uri.decode(firstArg<String>())}"
        }
        return PreviewWebAssets(source, path, markdown, context.assets, false) { errors++ }
            .also { sessions += it }
    }

    @After fun cleanup() { sessions.forEach { it.close() } }

    @Test fun classifiesOnlyOwnTokenLocalPathsAndKnownMarkdownAssets() {
        val web = session(markdown = true)
        val base = web.entryUrl.substringBeforeLast('/') + "/"
        assertEquals(PreviewWebRoute.Local("index.html"), web.classify(web.entryUrl))
        assertEquals(PreviewWebRoute.Local("image%20one.png"), web.classify(base + "image%20one.png"))
        assertEquals(PreviewWebRoute.Local("image+one.png"), web.classify(base + "image+one.png"))
        assertEquals(PreviewWebRoute.External, web.classify("https://example.com/page"))
        assertEquals(PreviewWebRoute.External, web.classify("http://example.com/page"))
        val assetBase = base.replace("/files/", "/markdown/")
        PreviewWebAssets.MARKDOWN_ASSETS.forEach {
            assertEquals(PreviewWebRoute.Asset(it), web.classify(assetBase + it))
        }
        listOf("preview.html", "../prompts/system.md", "other.js", "%6darked.min.js").forEach {
            assertEquals(PreviewWebRoute.Denied, web.classify(assetBase + it))
        }
    }

    @Test fun deniesSchemesAbsolutePathsTraversalAndResidualEncoding() {
        val web = session()
        val base = web.entryUrl.substringBeforeLast('/') + "/"
        listOf(
            "../secret", "./image.png", "%2e%2e/secret", "%2E%2E%2fsecret", "%252e%252e/secret",
            "%252fetc/passwd", "%2fetc/passwd", "a/%2e%2e/secret", "a//b.png",
            "a%5cb.png", "%00.png", "%0a.png", "http%3a%2f%2fexample.com/x", "image%2520one.png",
        ).forEach { relative ->
            assertEquals(relative, PreviewWebRoute.Denied, web.classify(base + relative))
        }
        listOf(
            "file:///etc/passwd", "content://com.aicode.private/data", "javascript:alert(1)",
            "intent://open#Intent;end", "data:text/html,<script></script>", "blob:https://example.com/id",
            "ftp://example.com/a", "//example.com/a", "https://user:password@example.com/a",
        ).forEach { assertEquals(it, PreviewWebRoute.Denied, web.classify(it)) }
        val uri = URI(web.entryUrl)
        assertEquals(PreviewWebRoute.Denied, web.classify(web.entryUrl.replace("https:", "http:")))
        assertEquals(PreviewWebRoute.Denied, web.classify(web.entryUrl.replace(uri.host, "${uri.host}:443")))
        assertEquals(PreviewWebRoute.Denied, web.classify("https://${uri.host}/etc/passwd"))
        assertEquals(PreviewWebRoute.Denied, web.classify("https://${uri.host}/assets/prompts/system.md"))
    }

    @Test fun tokensAndOriginsAreIsolatedAcrossSessions() {
        val one = session()
        val two = session()
        assertNotEquals(URI(one.entryUrl).host, URI(two.entryUrl).host)
        assertNotEquals(URI(one.entryUrl).path, URI(two.entryUrl).path)
        assertEquals(PreviewWebRoute.Denied, one.classify(two.entryUrl))
        val otherToken = "https://${URI(one.entryUrl).host}${URI(two.entryUrl).rawPath}"
        assertEquals(PreviewWebRoute.Denied, one.classify(otherToken))
        assertEquals(403, one.intercept(two.entryUrl).statusCode)
        assertEquals(403, one.intercept(otherToken).statusCode)
    }

    @Test fun everyNetworkOrUnknownRequestFailsClosedWithoutReadingTheSource() {
        val web = session()
        listOf(
            "https://example.com/image.png", "http://example.com/script.js", "https://localhost/private",
            "file:///root/workspace/private", "content://files/private", "data:text/html,hello", "invalid",
        ).forEach {
            val response = web.intercept(it)
            assertEquals(403, response.statusCode)
            assertEquals("no-store", response.responseHeaders["Cache-Control"])
            assertEquals("nosniff", response.responseHeaders["X-Content-Type-Options"])
        }
        assertEquals(403, web.intercept(web.entryUrl, "POST", true).statusCode)
        verify(exactly = 0) { source.openRead(any()) }
    }

    @Test fun localLinksResolveOnceThroughSourceAndAnchorsStayInTheCurrentDocument() {
        val web = session()
        val base = web.entryUrl.substringBeforeLast('/') + "/"
        assertEquals("/root/workspace/site/next one.md#heading one", web.localLink(base + "next%20one.md#heading%20one"))
        verify(exactly = 1) { source.resolve("next%20one.md", "/root/workspace/site/index.html") }
        assertTrue(web.isCurrentAnchor(web.entryUrl + "#section"))
        assertFalse(web.isCurrentAnchor(base + "other.html#section"))
        assertFalse(web.isCurrentAnchor("https://example.com${URI(web.entryUrl).rawPath}#section"))
        assertFalse(web.isCurrentAnchor(web.entryUrl + "?query=changed#section"))
        assertNull(web.localLink("javascript:alert(1)"))
    }

    @Test fun resourcesHaveRealMimeTypesAndNeverBecomeExecutableHtml() {
        val web = session()
        val base = web.entryUrl.substringBeforeLast('/') + "/"
        every { source.openRead(any()) } answers { ByteArrayInputStream("resource".toByteArray()) }
        mapOf(
            "image.png" to "image/png", "data.json" to "application/json",
            "style.css" to "text/css", "vector.svg" to "image/svg+xml",
        ).forEach { (name, mime) ->
            val response = web.intercept(base + name)
            assertEquals(name, 200, response.statusCode)
            assertEquals(name, mime, response.mimeType)
            assertEquals("resource", response.data.bufferedReader().use { it.readText() })
        }
        val script = web.intercept(base + "logic.js")
        assertEquals(200, script.statusCode)
        assertTrue(script.mimeType in setOf("application/javascript", "text/javascript"))
        assertEquals(403, web.intercept(base + "other.html").statusCode)
        assertEquals(403, web.intercept(base + "movie.mp4").statusCode)
        assertEquals(403, web.intercept(base + "sound.mp3").statusCode)
        assertEquals(403, web.intercept(base + "paper.pdf").statusCode)
        assertEquals(403, web.intercept(base + "data.json", mainFrame = true).statusCode)
    }

    @Test fun xhtmlEntryKeepsItsRealDocumentMime() {
        val web = session(path = "/root/workspace/site/index.xhtml")
        every { source.openRead(any()) } answers {
            ByteArrayInputStream("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>local</body></html>".toByteArray())
        }
        val response = web.intercept(web.entryUrl, mainFrame = true)
        assertEquals(200, response.statusCode)
        assertEquals("application/xhtml+xml", response.mimeType)
    }

    @Test fun extensionlessEntryUsesItsExplicitMimeWithoutUpgradingSiblingResources() {
        val web = session()
        every { source.entryPath } returns "/root/workspace/site/download"
        every { source.openRead(any()) } answers { ByteArrayInputStream("<html>local</html>".toByteArray()) }
        val typed = PreviewWebAssets(source, "/root/workspace/site/download", false, context.assets, false, "text/html") { errors++ }
            .also { sessions += it }
        val entry = typed.intercept(typed.entryUrl, mainFrame = true)
        assertEquals(200, entry.statusCode)
        assertEquals("text/html", entry.mimeType)
        val sibling = typed.intercept(typed.entryUrl.substringBeforeLast('/') + "/resource")
        assertEquals(200, sibling.statusCode)
        assertEquals("application/octet-stream", sibling.mimeType)
        assertEquals(403, typed.intercept(typed.entryUrl.substringBeforeLast('/') + "/other.json", mainFrame = true).statusCode)
        web.close()
    }

    @Test fun entryRejectsNonHtmlMimeInsteadOfExecutingIt() {
        val web = session(path = "/root/workspace/site/data.json")
        assertEquals(415, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        assertEquals(1, errors)
        verify(exactly = 0) { source.openRead(any()) }
    }

    @Test fun entryEnforcesTextLimitAndResourcesEnforceTheirOwnLimit() {
        val web = session()
        val base = web.entryUrl.substringBeforeLast('/') + "/"
        every { source.openRead(any()) } answers { ByteArrayInputStream(ByteArray(PreviewLimits.TEXT_BYTES + 1)) }
        assertEquals(413, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        assertEquals(1, errors)
        every { source.openRead(any()) } answers { ByteArrayInputStream(ByteArray(PreviewLimits.RESOURCE_BYTES + 1)) }
        assertEquals(413, web.intercept(base + "image.png").statusCode)
    }

    @Test fun missingDeniedOrInvalidatedSourcesNeverReturnSuccessfulResources() {
        val web = session()
        every { source.openRead(any()) } throws java.io.FileNotFoundException()
        assertEquals(404, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        every { source.resolve(any(), any()) } throws SecurityException("Outside preview root")
        assertEquals(404, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        assertEquals(1, errors)
        valid.value = false
        assertEquals(410, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        assertNull(web.localLink(web.entryUrl))
    }

    @Test fun sourceIsRecheckedAfterReadingAndClosedResponseStreamsRejectFurtherReads() {
        val web = session()
        every { source.openRead(any()) } answers {
            object : ByteArrayInputStream("<html>private</html>".toByteArray()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    return super.read(bytes, offset, length).also { valid.value = false }
                }
            }
        }
        assertNotEquals(200, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        valid.value = true
        every { source.openRead(any()) } answers { ByteArrayInputStream("<html>private</html>".toByteArray()) }
        val response = web.intercept(web.entryUrl, mainFrame = true)
        valid.value = false
        assertTrue(runCatching { response.data.read() }.isFailure)
        valid.value = true
        val another = web.intercept(web.entryUrl, mainFrame = true)
        web.close()
        assertTrue(runCatching { another.data.read() }.isFailure)
        assertEquals(410, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        verify(exactly = 0) { source.close() }
    }

    @Test fun closingSessionCancelsAnInFlightRead() {
        val web = session()
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int {
                entered.countDown()
                if (!released.await(3, TimeUnit.SECONDS)) throw java.io.IOException("Read not closed")
                throw java.io.IOException("Closed")
            }
            override fun close() { released.countDown() }
        }
        every { source.openRead(any()) } returns stream
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Int> { web.intercept(web.entryUrl, mainFrame = true).statusCode }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            web.close()
            assertNotEquals(200, result.get(3, TimeUnit.SECONDS))
            assertEquals(0L, released.count)
        } finally {
            web.close()
            executor.shutdownNow()
        }
    }

    @Test fun markdownOnlyExposesSevenBundledRuntimeAssets() {
        val web = session(markdown = true)
        val base = web.entryUrl.substringBeforeLast('/').replace("/files", "/markdown") + "/"
        assertEquals(7, PreviewWebAssets.MARKDOWN_ASSETS.size)
        PreviewWebAssets.MARKDOWN_ASSETS.forEach {
            val response = web.intercept(base + it)
            assertEquals(it, 200, response.statusCode)
            assertTrue(response.data.use { stream -> stream.read() } >= 0)
            assertEquals(if (it.endsWith(".js")) "application/javascript" else "text/css", response.mimeType)
        }
        assertEquals(403, web.intercept(base + "preview.html").statusCode)
        assertEquals(403, web.intercept(base + "marked.min.js", mainFrame = true).statusCode)
        val html = session()
        assertEquals(403, html.intercept(html.entryUrl.substringBeforeLast('/').replace("/files", "/markdown") + "/marked.min.js").statusCode)
    }

    @Test fun markdownTemplateKeepsUntrustedTextInBase64AndPurifiesRenderedHtml() {
        val web = session(path = "/root/workspace/site/README.md", markdown = true)
        val text = "# 预览\n</script><script>window.nativeBridge.pwn()</script>\n<img src=x onerror=alert(1)>"
        every { source.openRead(any()) } answers { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }
        val response = web.intercept(web.entryUrl, mainFrame = true)
        assertEquals(200, response.statusCode)
        assertEquals("text/html", response.mimeType)
        val html = response.data.bufferedReader().use { it.readText() }
        assertFalse(html.contains("window.nativeBridge"))
        val base64 = html.substringAfter("<script id=\"md-source\" type=\"text/plain\">").substringBefore("</script>")
        assertEquals(text, String(Base64.getDecoder().decode(base64), Charsets.UTF_8))
        assertTrue(html.contains("DOMPurify.sanitize(raw"))
        assertTrue(html.contains("purify.min.js"))
        assertTrue(html.contains("marked.min.js"))
        assertTrue(html.contains("highlight.min.js"))
        assertFalse(html.contains("__MD_B64__"))
        assertFalse(html.contains("__ASSET_BASE__"))
    }

    @Test fun markdownRejectsBinaryDataInsteadOfReplacingInvalidText() {
        val web = session(path = "/root/workspace/site/README.md", markdown = true)
        every { source.openRead(any()) } answers { ByteArrayInputStream(byteArrayOf(0, 1, 2)) }
        assertEquals(404, web.intercept(web.entryUrl, mainFrame = true).statusCode)
        assertEquals(1, errors)
    }

    @Test fun everyResponseCarriesRestrictiveCspWithoutEvalOrExternalSources() {
        val web = session()
        every { source.openRead(any()) } answers { ByteArrayInputStream("<html></html>".toByteArray()) }
        val response = web.intercept(web.entryUrl, mainFrame = true)
        val csp = response.responseHeaders.getValue("Content-Security-Policy")
        listOf(
            "default-src 'none'", "script-src 'self' 'unsafe-inline'", "connect-src 'none'",
            "form-action 'none'", "object-src 'none'", "frame-src 'none'", "worker-src 'none'",
            "media-src 'none'", "base-uri 'none'", "img-src 'self' data:", "font-src 'self' data:",
        ).forEach { assertTrue(it, csp.contains(it)) }
        assertFalse(csp.contains("unsafe-eval"))
        assertFalse(csp.contains("https:"))
        assertFalse(csp.contains("file:"))
        assertFalse(csp.contains("content:"))
        assertFalse(csp.contains("script-src data:"))
    }
}
