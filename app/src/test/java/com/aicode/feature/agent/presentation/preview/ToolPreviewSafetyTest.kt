package com.aicode.feature.agent.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPreviewSafetyTest {
    @Test
    fun nestedSecretsAndHeadersAreRedacted() {
        val safe = safeToolPreviewText("""{"token":"tok-value","password":"pw-value","headers":{"X-Example":"header-value"},"nested":{"api_key":"key-value"},"action":"read"}""")
        for (value in listOf("tok-value", "pw-value", "header-value", "key-value")) assertFalse(safe, safe.contains(value))
        assertTrue(safe.contains("read"))
        assertFalse(safeToolPreviewText("request headers: {\nX-Example: hidden-header\n}").contains("hidden-header"))
    }

    @Test
    fun commandFlagsHeadersUrlCredentialsAndPrivateKeysAreRedacted() {
        val text = """
            curl --token 'flag-value' --password=pw-value -H 'X-Example: header-value'
            Authorization: Bearer auth-value
            https://user:pass@example.invalid/path?token=query-value
            -----BEGIN PRIVATE KEY-----
            private-value
            -----END PRIVATE KEY-----
        """.trimIndent()
        val safe = safeToolPreviewText(text)
        for (value in listOf("flag-value", "pw-value", "header-value", "auth-value", "query-value", "user:pass", "private-value")) {
            assertFalse(value, safe.contains(value))
        }
    }

    @Test
    fun hiddenReasoningAndLargeBodiesUseOneBoundedSafeProjection() {
        val safe = safeToolPreviewText("""{"reasoning":"private-thought","content":"${"long-body".repeat(300)}","action":"read"}""")
        assertFalse(safe.contains("private-thought"))
        assertTrue(safe.contains("long-body"))
        assertTrue(safe.length <= PREVIEW_TEXT_LIMIT)
        assertTrue(safe.contains("read"))
        assertEquals("…", safeToolPreviewText("{" + "x".repeat(PREVIEW_ARGUMENT_LIMIT)))
        assertEquals("…", safeToolPreviewText("{\"headers\":{\"X-Example\":\"partial"))
    }

    @Test
    fun nestedJsonEncodedInsideAStringIsAlsoSanitized() {
        val safe = safeToolPreviewText("""{"data":"{\"headers\":{\"X-Example\":\"encoded-secret\"},\"action\":\"read\"}"}""")
        assertFalse(safe.contains("encoded-secret"))
    }

    @Test
    fun sensitiveFileReadsAndShellReadsHideOutputAndCommand() {
        val read = ToolPreviewTarget("id", "readFile", """{"path":"/work/.env"}""", "PASSWORD=hidden", ToolPreviewStage.COMPLETED)
        assertTrue(hidesPreviewBody(read))
        assertEquals("", buildToolPreviewDisplay(read).result)
        val shell = ToolPreviewTarget("id", "Bash", """{"command":"cat /work/.env"}""", "hidden", ToolPreviewStage.RUNNING)
        assertTrue(hidesPreviewBody(shell))
        assertEquals("", buildToolPreviewDisplay(shell).command)
    }

    @Test
    fun sensitivePathArraysCannotExposeRasterImagesThroughHashedCacheNames() {
        val image = ToolPreviewTarget("id", "viewImage", """{"images":["/work/secrets.png"]}""",
            "private raster body", ToolPreviewStage.COMPLETED, imagePath = "/cache/hash.png")
        assertTrue(hidesPreviewBody(image))
        assertTrue(buildToolPreviewDisplay(image).bodyHidden)
        assertEquals("", buildToolPreviewDisplay(image).result)
        assertFalse(buildToolPreviewDisplay(image).copyText.contains("private raster body"))
        val send = image.copy(name = "sendFile", arguments = """{"paths":["/work/public.png","/work/.env"]}""")
        assertTrue(hidesPreviewBody(send))
        assertFalse(hidesPreviewBody(image.copy(arguments = """{"images":["/work/public.png"]}""")))
    }

    @Test
    fun overBudgetArgumentsCannotBypassTheSensitiveBodyGate() {
        val args = """{"command":"cat /work/.env","padding":"${"x".repeat(PREVIEW_ARGUMENT_LIMIT)}"}"""
        val target = ToolPreviewTarget("id", "Bash", args, "unlabelled private body", ToolPreviewStage.COMPLETED)
        assertTrue(hidesPreviewBody(target))
        assertTrue(buildToolPreviewDisplay(target).bodyHidden)
        assertEquals("", buildToolPreviewDisplay(target).result)
        assertFalse(buildToolPreviewDisplay(target).copyText.contains("unlabelled private body"))
    }

    @Test
    fun credentialBearingBrowserActionsCannotExposeRasterFrames() {
        val browser = ToolPreviewTarget("id", "browser", """{"action":"fill","tabId":"tab-1","selector":"#password","value":"hidden"}""", "", ToolPreviewStage.RUNNING)
        assertTrue(hidesPreviewBody(browser))
        assertNull(browserPreviewTabId(browser))
        assertTrue(hidesPreviewBody(browser.copy(arguments = """{"action":"evaluate","tabId":"tab-1","headers":{"X-Example":"hidden"}}""")))
    }

    @Test
    fun titlesNeverReuseArgsOrUnredactedCredentialBearingModelTitles() {
        val target = ToolPreviewTarget("id", "Bash", """{"command":"curl --token hidden"}""", "", ToolPreviewStage.COMPLETED, actionTitle = "hidden")
        assertNull(safePreviewActionTitle(target))
        val nested = target.copy(arguments = """{"headers":{"X-Example":"hidden"}}""")
        assertNull(safePreviewActionTitle(nested))
        val safe = target.copy(arguments = """{"command":"pwd"}""", actionTitle = "Inspect directory")
        assertEquals("Inspect directory", safePreviewActionTitle(safe))
    }

    @Test
    fun ansiOscControlsAndBidiOverridesCannotReachDisplayOrCopy() {
        val raw = "\u001b[31mpublic\u001b[0m\u001b]52;c;private-clipboard\u0007\u0000\u202E token=hidden-value"
        val safe = safeToolPreviewText(raw)
        assertTrue(safe.contains("public"))
        for (hidden in listOf("private-clipboard", "hidden-value", "\u001b", "\u0000", "\u202E")) assertFalse(safe.contains(hidden))
    }

    @Test
    fun ordinarySecretWordIsNotTreatedAsAFileButSensitiveShellPathsHideBody() {
        val ordinary = ToolPreviewTarget("id", "Bash", """{"command":"printf secret"}""", "public", ToolPreviewStage.RUNNING)
        assertFalse(hidesPreviewBody(ordinary))
        assertEquals("public", buildToolPreviewDisplay(ordinary).result)
        val read = ordinary.copy(arguments = """{"command":"cat /work/private.pem"}""", content = "unlabelled private body")
        assertTrue(hidesPreviewBody(read))
        assertFalse(buildToolPreviewDisplay(read).copyText.contains("unlabelled"))
    }

    @Test
    fun oneArgumentVersionReusesItsSafeArgumentsAndFileDraftAcrossDisplayStages() {
        val target = ToolPreviewTarget("call", "editFile",
            """{"path":"/work/a.kt","edits":[{"old_string":"old","new_string":"new"}],"tool_title":"Update source"}""",
            "", ToolPreviewStage.PREPARING)
        val safety = ToolPreviewSafety(target.name, target.arguments)
        val draft = requireNotNull(safety.fileDraft)
        val args = safety.safeArgs
        assertFalse(safety.bodyHidden)
        assertSame(args, safety.safeArgs)
        assertSame(draft, safety.fileDraft)
        val preparing = buildToolPreviewDisplay(target, safety)
        val running = buildToolPreviewDisplay(target.copy(stage = ToolPreviewStage.RUNNING), safety)
        assertSame(draft.diff, preparing.diff)
        assertSame(draft.diff, running.diff)
        assertEquals("Update source", safePreviewActionTitle(target, safety))
        assertEquals("new", running.diff.added)
        assertSame(args, safety.safeArgs)
    }

    @Test
    fun partialBrowserTabRoutingNeverBecomesEligibleForCachedFrames() {
        val target = ToolPreviewTarget("call", "browser", """{"action":"click","tabId":"tab-1""",
            "", ToolPreviewStage.RUNNING)
        assertNull(ToolPreviewSafety(target.name, target.arguments).browserTabId)
        assertNull(previewFrameKey(target, 480))
    }

    @Test
    fun imagePathsNeverAcceptNetworkUrlsOrCredentialFiles() {
        for (path in listOf("https://example.invalid/image.png", "//host/image.png", "/work/secrets.png", "/work/a\nb.png")) {
            assertFalse(path, isLocalPreviewImagePath(path))
        }
        assertTrue(isLocalPreviewImagePath("/data/user/0/app/cache/image.png"))
        assertTrue(isLocalPreviewImagePath("~/.aicode/browser-screenshots/a.jpg"))
    }
}
