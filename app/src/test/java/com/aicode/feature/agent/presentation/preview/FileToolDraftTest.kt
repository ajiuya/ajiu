package com.aicode.feature.agent.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileToolDraftTest {
    @Test
    fun accumulatedSnapshotsReplaceRatherThanAppend() {
        val first = fileToolDraftOf("writeFile", """{"path":"src/a.kt","content":"first""")!!
        val second = fileToolDraftOf("writeFile", """{"path":"src/a.kt","content":"replacement"} """)!!
        assertEquals("first", first.diff.added)
        assertEquals("replacement", second.diff.added)
        assertFalse(second.diff.added.contains("first"))
    }

    @Test
    fun bodyWaitsForACompleteTopLevelPathEvenWhenItArrivesLast() {
        val hidden = fileToolDraftOf("writeFile", """{"content":"safe","path":"src/a""")!!
        assertTrue(hidden.bodyHidden)
        assertEquals("", hidden.diff.added)
        val visible = fileToolDraftOf("writeFile", """{"content":"safe","path":"src/a.kt"}""")!!
        assertFalse(visible.bodyHidden)
        assertEquals("safe", visible.diff.added)
    }

    @Test
    fun readAndEditDraftsAreSupported() {
        assertEquals("src/a.kt", fileToolDraftOf("readFile", """{"path":"src/a.kt","start_line":1}""")!!.path)
        val draft = fileToolDraftOf("editFile", """{"path":"src/a.kt","edits":[{"old_string":"old\nline","new_string":"new\nli""")!!
        assertEquals("old\nline", draft.diff.removed)
        assertEquals("new\nli", draft.diff.added)
        assertNull(fileToolDraftOf("Bash", "{}"))
    }

    @Test
    fun duplicateInvalidOrNestedPathsCannotRevealBodies() {
        for (json in listOf(
            """{"path":"a.kt","path":"b.kt","content":"hidden"}""",
            """{"nested":{"path":"a.kt"},"content":"hidden"}""",
            """{"path":false,"content":"hidden"}""",
            """{"path":"a.kt","content":"bad\q"}""",
            """{"path":"a.kt","content":"hidden"} unexpected""",
        )) {
            assertTrue(json, fileToolDraftOf("writeFile", json)!!.bodyHidden)
        }
    }

    @Test
    fun sensitivePathsAndPartialSecretAssignmentsHideContent() {
        for (path in listOf(".env", "/work/.env.local", "keys/id_rsa", "app/keystore.properties", "credentials.json", "private.key")) {
            val draft = fileToolDraftOf("writeFile", """{"path":"$path","content":"never show"}""")!!
            assertTrue(path, draft.bodyHidden)
            assertEquals("", draft.diff.added)
        }
        val draft = fileToolDraftOf("writeFile", """{"path":"a.txt","content":"password=do-not-show""")!!
        assertFalse(draft.diff.added.contains("do-not-show"))
    }

    @Test
    fun escapesAndIncompleteSurrogatePairsAreHandledWithoutBrokenGlyphs() {
        val complete = fileToolDraftOf("writeFile", """{"path":"a.kt","content":"A\n\uD83D\uDE00"}""")!!
        assertEquals("A\n\uD83D\uDE00", complete.diff.added)
        val partial = fileToolDraftOf("writeFile", """{"path":"a.kt","content":"A\uD83D\uDE""")!!
        assertEquals("A", partial.diff.added)
        val invalid = fileToolDraftOf("writeFile", """{"path":"a.kt","content":"\uDE00"}""")!!
        assertTrue(invalid.bodyHidden)
    }

    @Test
    fun everyPartialCompoundSecretAssignmentIsSafeBeforeClosingQuote() {
        val code = "{\n  \"public\": 1,\n  \"credentials\": {\n    \"value\": \"never-show-value\"\n  }\n}"
        for (cut in 0..code.length) {
            assertFalse("cut=$cut", safeFileDraftText(code.take(cut)).contains("never-show"))
        }
        val raw = kotlinx.serialization.json.JsonObject(mapOf(
            "path" to kotlinx.serialization.json.JsonPrimitive("/safe.json"),
            "content" to kotlinx.serialization.json.JsonPrimitive(code),
        )).toString()
        for (cut in 0..raw.length) {
            val draft = fileToolDraftOf("writeFile", raw.take(cut))!!
            val display = buildToolPreviewDisplay(ToolPreviewTarget("draft", "writeFile", raw.take(cut), "never-run-result", ToolPreviewStage.PREPARING))
            assertFalse(draft.diff.added.contains("never-show"))
            assertFalse(display.copyText.contains("never-show"))
            assertFalse(display.copyText.contains("never-run-result"))
        }
    }

    @Test
    fun aliasesCannotOverrideAnEarlierSensitivePath() {
        val draft = fileToolDraftOf("writeFile", """{"path":".env","file":"safe.txt","content":"private-body"}""")!!
        assertTrue(draft.bodyHidden)
        assertEquals(ToolPreviewDiff(), draft.diff)
    }

    @Test
    fun safetyOnlyParsingUsesTheSameStructuralGateForEveryPartialSnapshotWithoutBuildingDiffs() {
        val snapshots = listOf(
            """{"path":"/work/a.kt","content":"public\nvalue"}""",
            """{"content":"body first","path":"/work/a.kt"}""",
            """{"path":"/work/a.kt","edits":[{"old_string":"old","new_string":"new\uD83D\uDE00"}]}""",
            """{"path":".env","file":"a.kt","content":"hidden"}""",
            """{"path":"a.kt","path":"b.kt","content":"hidden"}""",
            """{"path":"a.kt","content":"bad\q"}""",
            """{"path":"a.kt","content":"bad\uDE00"}""",
            """{"path":"a.kt","content":"hidden"} trailing""",
        )
        for (name in listOf("writeFile", "editFile")) for (source in snapshots) for (cut in 0..source.length) {
            val raw = source.take(cut)
            val full = requireNotNull(fileToolDraftOf(name, raw))
            val checked = requireNotNull(fileToolDraftOf(name, raw, buildDiff = false))
            assertEquals("$name cut=$cut source=$source", full.bodyHidden, checked.bodyHidden)
            assertEquals(ToolPreviewDiff(), checked.diff)
        }
        val large = "{\"path\":\"a.kt\",\"content\":\"" + "x".repeat(PREVIEW_TEXT_LIMIT * 2)
        assertFalse(fileToolDraftOf("writeFile", large, buildDiff = false)!!.bodyHidden)
        assertEquals(ToolPreviewDiff(), fileToolDraftOf("writeFile", large, buildDiff = false)!!.diff)
    }

    @Test
    fun argumentAndBodyBudgetsAreBounded() {
        val draft = fileToolDraftOf("writeFile", "{\"path\":\"a.kt\",\"content\":\"" + "x".repeat(PREVIEW_ARGUMENT_LIMIT * 2))!!
        assertNotNull(draft.path)
        assertTrue(draft.truncated)
        assertTrue(draft.diff.added.length <= PREVIEW_TEXT_LIMIT)
        assertEquals(PREVIEW_TEXT_LIMIT, draft.diff.added.length)
    }
}
