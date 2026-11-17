package com.aicode.feature.agent.presentation.preview

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolPreviewDisplayTest {
    private data class Case(val args: String, val visible: String)
    private val cases = linkedMapOf(
        "readFile" to Case("""{"path":"/work/public.kt","start_line":2,"end_line":9}""", "public.kt"),
        "sendFile" to Case("""{"paths":["/work/report.pdf"],"names":["final.pdf"]}""", "report.pdf"),
        "viewImage" to Case("""{"images":["/work/photo.png"],"detail":"high"}""", "photo.png"),
        "writeFile" to Case("""{"path":"/work/a","content":"actual new source","overwrite":false}""", "actual new source"),
        "editFile" to Case("""{"path":"/work/a","edits":[{"old_string":"before","new_string":"after","replace_all":true}]}""", "after"),
        "generateImage" to Case("""{"prompt":"blue lake","size":"1024x1024","n":1}""", "blue lake"),
        "Bash" to Case("""{"command":"printf hello","timeout":12}""", "printf hello"),
        "Shizuku" to Case("""{"command":"wm size","timeout":10}""", "wm size"),
        "Root" to Case("""{"command":"id","elevate":true}""", "elevate: true"),
        "terminal" to Case("""{"action":"send","tab_id":"term-1","input":"printf real","submit":false}""", "printf real"),
        "list" to Case("""{"args":"-la /work"}""", "-la /work"),
        "search" to Case("""{"args":"-n public /work"}""", "-n public /work"),
        "history" to Case("""{"action":"search","query":"public caller history","limit":5}""", "public caller history"),
        "loadSkill" to Case("""{"skill_name":"dev-god"}""", "dev-god"),
        "askUserQuestion" to Case("""{"questions":[{"question":"Choose a color?","header":"Color","options":[{"label":"Blue","description":"Ocean"}],"multiSelect":false}]}""", "Choose a color?"),
        "manageMcp" to Case("""{"action":"add_stdio","server_name":"public-server","command":"node","args":["server.js"]}""", "public-server"),
        "websearch" to Case("""{"query":"latest Kotlin","queries":["Kotlin official"]}""", "Kotlin official"),
        "webfetch" to Case("""{"url":"https://example.test/page","format":"text"}""", "example.test/page"),
        "media" to Case("""{"action":"prepare","items":[{"name":"Episode 1","url":"https://example.test/1.mp4","kind":"video"}]}""", "Episode 1"),
        "mediasearch" to Case("""{"queries":["public archives"],"sources":["commons"],"limit":5}""", "public archives"),
        "mediacatalog" to Case("""{"api":"https://example.test/api","query":"nature","episodes":[{"name":"Episode 2","url":"https://example.test/2.mp4"}]}""", "Episode 2"),
        "videosearch" to Case("""{"action":"detail","source":"catalog-a","id":"film-1","page":2}""", "film-1"),
        "goalStatus" to Case("""{"action":"complete","reason":"Verified result"}""", "Verified result"),
        "todo" to Case("""{"items":[{"subject":"Run tests","description":"Model regression","status":"in_progress","priority":1}]}""", "Run tests"),
        "memory" to Case("""{"action":"save","name":"architecture","scope":"project","content":"public architecture","project_id":"project-a"}""", "public architecture"),
        "task" to Case("""{"action":"create","agent":"Reviewer","description":"Review files","prompt":"Inspect public files"}""", "Inspect public files"),
        "messageParent" to Case("""{"message":"Tests completed"}""", "Tests completed"),
        "browser" to Case("""{"action":"navigate","url":"https://example.test","tabId":"tab-1"}""", "example.test"),
        "virtualDisplay" to Case("""{"action":"tap","observation_id":"obs-1","node_id":"button-2","x":15,"y":30}""", "button-2"),
        "ffmpeg" to Case("""{"action":"clip","input":"/work/in.mp4","output":"/work/out.mp4","startTime":"00:01:00","duration":"20"}""", "00:01:00"),
        "scheduleTask" to Case("""{"action":"create","label":"Daily report","prompt":"Summarize work","hour":9,"minute":30,"enabled":true}""", "Daily report"),
    )
    private fun target(name: String = "Bash", args: String = "{}", content: String = "", stage: ToolPreviewStage = ToolPreviewStage.RUNNING) =
        ToolPreviewTarget("id", name, args, content, stage)

    @Test fun all31ProfilesRenderKnownFieldsBeforeOutputAndActualResultsAfterward() {
        assertEquals(31, cases.size)
        assertEquals(cases.keys, toolPreviewProfiles.keys)
        cases.forEach { (name, case) ->
            val waiting = buildToolPreviewDisplay(target(name, case.args))
            assertTrue("$name: ${waiting.copyText}", waiting.copyText.contains(case.visible))
            assertEquals("", waiting.result)
            val finished = buildToolPreviewDisplay(target(name, case.args,
                """{"status":"success","data":{"output":"first\n  indented\nlast"}}""", ToolPreviewStage.COMPLETED))
            assertEquals("$name", "first\n  indented\nlast", finished.result)
            assertEquals(finished.result, finished.body)
            assertEquals(ToolPreviewDiff(), finished.diff)
        }
    }

    @Test fun kindsFollowRc2InsteadOfTreatingEveryTextLookingToolAsText() {
        for (name in listOf("Bash", "terminal", "Root", "Shizuku")) assertEquals(ToolPreviewKind.COMMAND, target(name).kind)
        for (name in listOf("editFile", "writeFile")) assertEquals(ToolPreviewKind.FILE, target(name).kind)
        for (name in listOf("readFile", "search", "list", "webfetch")) assertEquals(ToolPreviewKind.TEXT, target(name).kind)
        for (name in listOf("websearch", "loadSkill", "mcp__inspect")) assertEquals(ToolPreviewKind.GENERAL, target(name).kind)
        assertEquals(ToolPreviewKind.GENERAL, target("browser", """{"action":"getText"}""").kind)
        assertEquals(ToolPreviewKind.BROWSER, target("browser", """{"action":"hover"}""").kind)
        assertEquals(ToolPreviewKind.BROWSER, target("browser", "{}" ).kind)
        assertEquals(ToolPreviewKind.BROWSER, target("browser", """{"action":""}""").kind)
    }

    @Test fun unknownMcpAndSubmitPlanUseOnlyGenericSafeFields() {
        for (name in listOf("brandNewTool", "mcp__server__inspect", "submitPlan")) {
            val display = buildToolPreviewDisplay(target(name,
                """{"action":"inspect","query":"public query","plan":"private-plan","unknownBlob":{"query":"private-nested"},"token":"hidden-value"}""",
                """{"items":[{"title":"public item","token":"private-result","reasoning":["private-thought"]}]}"""))
            assertTrue(display.parameterText.contains("public query"))
            assertTrue(display.result.contains("public item"))
            for (hidden in listOf("private-plan", "private-nested", "hidden-value", "private-result", "private-thought")) assertFalse(display.copyText.contains(hidden))
        }
    }

    @Test fun detailAndCopyNeverRecoverUnredactedArgumentsOrSensitiveFileBodies() {
        val tool = target(args = """{"command":"curl --token private-command","headers":{"X-Example":"private-header"}}""",
            content = "Authorization: Bearer private-output")
        val before = tool.copy()
        val display = buildToolPreviewDisplay(tool)
        for (text in listOf(display.command, display.parameterText, display.result, display.copyText, display.taskArgs, display.taskResult)) {
            assertFalse(text, text.contains("private-"))
        }
        assertEquals(before, tool)
        val sensitive = buildToolPreviewDisplay(target("editFile",
            """{"path":".env","edits":[{"old_string":"old-private","new_string":"new-private"}]}""", "unlabelled-private", ToolPreviewStage.PREPARING))
        assertTrue(sensitive.bodyHidden)
        assertEquals(ToolPreviewDiff(), sensitive.diff)
        assertFalse(sensitive.copyText.contains("private"))
    }

    @Test fun commandsKeepMultilineWhitespaceAndOneCopyWithoutRepeatingParametersAfterResult() {
        val command = "  printf first\nprintf second\n  "
        val args = JsonObject(mapOf("command" to JsonPrimitive(command), "timeout" to JsonPrimitive(20))).toString()
        val display = buildToolPreviewDisplay(target(args = args, content = "first\nsecond"))
        assertEquals(command, display.command)
        assertEquals("$ $command\n\nfirst\nsecond", display.copyText)
        assertFalse(display.copyText.contains("timeout"))
    }

    @Test fun fileDiffAndStructuredResultHunksUseRealSafeContentWithoutSuccessLookingFailureDiff() {
        val write = buildToolPreviewDisplay(target("writeFile", """{"path":"/work/a","content":"one\ntwo","overwrite":false}"""))
        assertEquals("path: /work/a\noverwrite: false\n\n+ one\n+ two", write.body)
        val edits = """{"path":"a.kt","edits":[{"old_string":"before","new_string":"after","replace_all":true}]}"""
        val edit = buildToolPreviewDisplay(target("editFile", edits))
        assertEquals(ToolPreviewDiff("before", "after"), edit.diff)
        assertTrue(edit.parameterText.contains("replace_all: true"))
        val result = buildToolPreviewDisplay(target("editFile", edits,
            """{"status":"success","data":{"path":"a.kt","hunks":[{"start_line":8,"diff":"-old\n+new"},{"start_line":22,"diff":"-before\n+after"}]}}"""))
        assertEquals(listOf(8, 22), result.resultHunks.map { it.startLine })
        assertTrue(result.copyText.contains("-old\n+new"))
        val failed = buildToolPreviewDisplay(target("editFile", edits, "failure", ToolPreviewStage.FAILED))
        assertEquals(ToolPreviewDiff(), failed.diff)
        assertEquals("failure", failed.result)
    }

    @Test fun preparedDraftsHaveOnlyReceivedArgumentsAndNeverOutputImagesOrExecution() {
        val tool = target("writeFile", """{"path":"a.kt","content":"first\nsecond""", "not-executed", ToolPreviewStage.PREPARING)
        val display = buildToolPreviewDisplay(tool)
        assertEquals("first\nsecond", display.diff.added)
        assertEquals("", display.result)
        assertEquals("", display.taskResult)
        assertFalse(display.copyText.contains("not-executed"))
        assertTrue(toolDetailBodyOf(display, "not executed", "hidden", "empty").startsWith("not executed\n"))
    }

    @Test fun outputNormalizationKeepsNestedEnvelopeWarningsPartialAndUnknownBodies() {
        assertEquals("a\nb", normalizeToolOutput("""{"status":"success","data":"{\"status\":\"success\",\"data\":{\"stdout\":\"a\\nb\"}}"}"""))
        val partial = normalizeToolOutput("""{"status":"partial","message":"partial warning","data":{"output":"real output"}}""")
        assertTrue(partial.contains("real output"))
        assertTrue(partial.contains("partial warning"))
        assertEquals("denied", normalizeToolOutput("""{"status":"error","message":"denied"}"""))
        assertEquals("plain\n    output", normalizeToolOutput("plain\n    output"))
        assertEquals("{broken", normalizeToolOutput("{broken"))
    }

    @Test fun commandOutputIsBoundedAtTheTailAndCopyDoesNotShrinkToEightVisibleLines() {
        val output = "old-head\n" + "line\n".repeat(60_000) + "new-tail"
        val display = buildToolPreviewDisplay(target(content = output))
        assertTrue(display.truncated)
        assertFalse(display.copyText.contains("old-head"))
        assertTrue(display.result.endsWith("new-tail"))
        assertTrue(display.result.lineSequence().count() > 8)
        assertTrue(display.copyText.length <= PREVIEW_TEXT_LIMIT * 3)
        assertEquals("b\nc", previewTailLines("a\nb\nc\n", 2))
    }

    @Test fun detailBlocksKeepEveryCharacterEmptyLineAndUnicodePairWithinABoundedLayout() {
        for (raw in listOf("", "\n", "\n\n", "line\n", "line\n\nnext\n", " \t indented\r\n", "x".repeat(1023) + "\uD83D\uDE80" + "y".repeat(8_000),
            List(1_000) { "line $it\n" }.joinToString(""))) {
            val blocks = toolDetailTextBlocks(AnnotatedString(raw))
            assertEquals(raw, blocks.joinToString("") { it.text.text + if (it.newlineAfter) "\n" else "" })
            assertTrue(blocks.all { it.text.length <= 1_024 && it.text.text.count { c -> c == '\n' } <= 16 })
            assertTrue(blocks.none { it.text.firstOrNull()?.isLowSurrogate() == true || it.text.lastOrNull()?.isHighSurrogate() == true })
        }
    }

    @Test fun detailBlocksPreserveDiffStylesAcrossChunkBoundariesAndCopyRemainsWhole() {
        val body = "- " + "removed ".repeat(180) + "\n+ " + "added ".repeat(180)
        val display = ToolPreviewDisplay("editFile", ToolPreviewStage.COMPLETED, result = body,
            resultHunks = listOf(com.aicode.feature.agent.presentation.component.EditHunk(1, body)))
        val blocks = display.terminalBody
        assertTrue(blocks.size > 2)
        assertEquals(body, blocks.joinToString("") { it.text.text + if (it.newlineAfter) "\n" else "" })
        assertEquals(body, display.copyText)
        assertSame(blocks, display.terminalBody)
        val colored = toolDetailTextBlocks(AnnotatedString("a".repeat(2_048),
            spanStyles = listOf(AnnotatedString.Range(SpanStyle(color = Color.Red), 1_020, 1_030))))
        assertEquals(1, colored[0].text.spanStyles.size)
        assertEquals(1, colored[1].text.spanStyles.size)
        assertEquals(1_020, colored[0].text.spanStyles.single().start)
        assertEquals(6, colored[1].text.spanStyles.single().end)
    }

    @Test fun schedulePrefillUsesReferencedWhitelistAndCannotCreateAnExecutableCall() {
        val display = buildToolPreviewDisplay(target("scheduleTask",
            """{"action":"update","taskId":"schedule-a","providerId":"provider-a","reasoningEffort":"low","prefillToolCall":{"toolName":"Bash","arguments":{"command":"printf ok; token=private-command","timeout":20,"mystery":"private-unknown","env":{"RANDOM":"private-env"}}}}"""))
        for (visible in listOf("provider-a", "reasoningEffort: low", "Bash", "printf ok", "timeout: 20")) assertTrue(visible, display.parameterText.contains(visible))
        for (hidden in listOf("private-command", "private-unknown", "private-env")) assertFalse(display.copyText.contains(hidden))
    }

    @Test fun persistedImagesAndBrowserScreenshotPathsStayWithTheirOwnCall() {
        for (name in listOf("viewImage", "generateImage", "virtualDisplay", "task")) {
            val msg = AgentUIMessage(name, MessageRole.TOOL, "result", toolName = name, toolArgs = "{}",
                attachments = listOf(AgentAttachment("a.png", "/work/a.png", "/private/cache/a.png", "image/png", 4, true)))
            assertEquals("/private/cache/a.png", buildToolPreviewTargets(listOf(msg), emptyList(), emptyList()).single().imagePath)
            assertNull(target(name, content = """{"filePath":"/work/guess.png"}""", stage = ToolPreviewStage.COMPLETED).imagePath)
        }
        val shot = target("browser", """{"action":"screenshot","tabId":"tab-1"}""",
            """{"status":"success","data":{"status":"captured","filePath":"/work/own.png"}}""", ToolPreviewStage.COMPLETED)
        assertEquals("/work/own.png", browserScreenshotPath(shot))
        assertNull(browserScreenshotPath(shot.copy(stage = ToolPreviewStage.RUNNING)))
        assertNull(browserScreenshotPath(shot.copy(stage = ToolPreviewStage.FAILED)))
        assertNull(browserScreenshotPath(shot.copy(arguments = """{"action":"click"}""")))
    }

    @Test fun browserFramesRequireAnExecutedSafeCallAndExplicitExistingTabRouting() {
        val tool = target("browser", """{"action":"click","tabId":"tab-7"}""")
        assertEquals("tab-7", browserPreviewTabId(tool))
        for (args in listOf("{}", """{"action":"click"}""", """{"action":"getText","tabId":"tab-7"}""",
            """{"action":"fill","tabId":"tab-7","selector":"#password","value":"hidden"}""")) assertNull(browserPreviewTabId(tool.copy(arguments = args)))
        assertNull(browserPreviewTabId(tool.copy(stage = ToolPreviewStage.PREPARING)))
        assertNull(browserPreviewTabId(tool.copy(stage = ToolPreviewStage.COMPLETED)))
    }

    @Test fun samplingBoundsThumbnailAndDetailsIndependently() {
        assertEquals(1, previewImageSampleSize(100, 200, 480))
        assertEquals(64, previewImageSampleSize(8_000, 16_000, 480))
        assertEquals(8, previewImageSampleSize(8_000, 16_000, 2048))
        assertTrue(previewImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 480) > 1)
    }

    @Test fun partialArgsDoNotSearchUnknownNestedObjectsForPublicFields() {
        val display = buildToolPreviewDisplay(target("mcp__new", """{"unknown":{"query":"must-not-dump"},"action":"inspect"""))
        assertFalse(display.parameterText.contains("must-not-dump"))
        assertTrue("Public partial fields: ${display.parameterText}", display.parameterText.contains("inspect"))
        val safe = safePreviewArgs(target("mcp__new", """{"query":{"custom":"must-not-dump","query":"visible"}}"""))
        assertFalse(safe.toString().contains("must-not-dump"))
        assertTrue(safe.toString().contains("visible"))
        assertNotNull(Json.parseToJsonElement(safe.toString()) as? JsonObject)
    }
}
