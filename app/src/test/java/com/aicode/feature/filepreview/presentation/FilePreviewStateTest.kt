package com.aicode.feature.filepreview.presentation

import android.app.Application
import androidx.compose.ui.platform.UriHandler
import com.aicode.feature.filepreview.domain.PreviewRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class FilePreviewStateTest {
    private val delegated = mutableListOf<String>()
    private val previews = mutableListOf<String>()
    private val handler = PreviewUriHandler(
        delegate = object : UriHandler {
            override fun openUri(uri: String) { delegated += uri }
        },
        onPreview = { previews += it },
    )

    @Test
    fun typeScriptFilesDoNotOpenAsVideoWithoutAnExplicitTransportStreamMime() {
        listOf("text/plain", "text/typescript", "application/octet-stream", "").forEach { mime ->
            assertFalse(attachmentUsesPlayer("~/workspace/main.ts", mime))
        }
    }

    @Test
    fun transportStreamMimeIsExplicitAndNormalizedForTsFiles() {
        assertTrue(attachmentUsesPlayer("~/workspace/movie.TS", " VIDEO/MP2T ; charset=binary "))
        assertTrue(attachmentUsesPlayer("~/workspace/movie.ts?download=1", "video/mp2t"))
    }

    @Test
    fun realMediaPathKeepsPlaybackIndependentOfTheDisplayTitle() {
        assertTrue(attachmentUsesPlayer("~/workspace/movie.mp4", "application/octet-stream"))
        assertFalse(attachmentUsesPlayer("~/workspace/readme.md", "video/mp4"))
    }

    @Test
    fun reopeningTheSameFileIncrementsRevisionAndCloseOnlyClearsTheRequest() {
        val state = FilePreviewState()
        val request = PreviewRequest("~/workspace/report.md", "Report", "text/markdown", "/cache/report.copy")
        assertNull(state.request)
        assertEquals(0, state.revision)

        state.open(request)
        assertSame(request, state.request)
        assertEquals(1, state.revision)

        val reopened = request.copy()
        state.open(reopened)
        assertEquals(reopened, state.request)
        assertEquals(2, state.revision)

        state.close()
        assertNull(state.request)
        assertEquals(2, state.revision)
        state.close()
        assertNull(state.request)
        assertEquals(2, state.revision)

        state.open(request)
        assertSame(request, state.request)
        assertEquals(3, state.revision)
    }

    @Test
    fun fileLinksPreviewWithPercentDecodingOnlyForFileUrisAndPlusPreserved() {
        val links = linkedMapOf(
            "docs/notes.md" to "docs/notes.md",
            "~/workspace/image.png" to "~/workspace/image.png",
            "/root/workspace/report.pdf" to "/root/workspace/report.pdf",
            "file:///root/workspace/report%20v1+final.pdf" to "/root/workspace/report v1+final.pdf",
            "file:///root/workspace/%E6%8A%A5%E5%91%8A.md#heading" to "/root/workspace/报告.md",
            "file:///root/workspace/once%2520only.md" to "/root/workspace/once%20only.md",
            "docs/report%20v1.md" to "docs/report%20v1.md",
        )
        links.keys.forEach(handler::openUri)
        assertEquals(links.values.toList(), previews)
        assertTrue(delegated.isEmpty())
    }

    @Test
    fun positiveLineSuffixDelegatesTheOriginalUriWhileLineZeroStillPreviews() {
        val links = listOf(
            "notes.md:12",
            "~/workspace/main.kt:1",
            "/root/workspace/app.kt:42#section",
            "file:///root/workspace/report%20v1.md:23",
            "file:///root/workspace/report%20v1.md%3A7#section",
        )
        links.forEach(handler::openUri)
        assertEquals(links, delegated)
        assertTrue(previews.isEmpty())

        handler.openUri("notes.md:0")
        assertEquals(listOf("notes.md"), previews)
        assertEquals(links, delegated)
    }

    @Test
    fun webAndMailUrisAreDelegatedUnchangedEvenWithFilePathsAndLineSuffixes() {
        val links = listOf(
            "https://github.com/a/b/notes.md:12#heading",
            "http://example.com:8080/p/report%20v1.pdf:42",
            "mailto:dev@example.com?subject=notes.md:12",
            "MAILTO:dev@example.com",
        )
        links.forEach(handler::openUri)
        assertEquals(links, delegated)
        assertTrue(previews.isEmpty())
    }
}
