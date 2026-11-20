package com.aicode.feature.plugin.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PluginManifestParserTest {
    private fun error(text: String) = (PluginManifestParser.parse(text) as ManifestParseResult.Failure).errors.first().code

    @Test fun acceptsUpstreamLocalizedMetadataAndNamespacedId() {
        val manifest = PluginCoreFixtures.manifestValue(PluginCoreFixtures.manifest())
        assertEquals("Sample ZH", manifest.name.resolve("zh-CN"))
        assertEquals("Sample", manifest.name.resolve("fr-FR"))
        assertEquals("sample.plugin", manifest.id)
        assertEquals("1.0.0", manifest.version.toString())
    }
    @Test fun requiredMetadataCannotBeOmitted() {
        val root = PluginJson.parse(PluginCoreFixtures.manifest()) as JsonObject
        for (key in listOf("manifestVersion", "id", "name", "publisher", "version", "description", "license", "repository", "engines", "contributes")) {
            assertEquals(key, PluginErrorCode.FIELD_MISSING, error(JsonObject(root.filterKeys { it != key }).toString()))
        }
    }
    @Test fun refusesEveryUnsupportedContributionEvenWhenEmptyOrNull() {
        for (key in listOf("hooks", "mcp", "providers", "sessionSources", "shizuku", "overlay", "backup", "modelAccess", "review", "transform")) {
            for (value in listOf("[]", "{}", "null")) {
                val code = error(PluginCoreFixtures.manifest("\"$key\":$value"))
                if (key in setOf("review", "transform")) assertEquals(key, PluginErrorCode.UNSUPPORTED_CONTRIBUTION, code)
                else assertNotEquals(key, PluginErrorCode.UNSUPPORTED_CONTRIBUTION, code)
            }
        }
    }
    @Test fun refusesUnsupportedTopLevelCapabilityRatherThanIgnoringIt() {
        assertEquals(PluginErrorCode.UNSUPPORTED_CONTRIBUTION,
            error(PluginCoreFixtures.replaceField(PluginCoreFixtures.manifest(), "hooks", JsonObject(emptyMap()))))
    }
    @Test fun rejectsUnknownRootAndNestedFields() {
        assertEquals(PluginErrorCode.UNSUPPORTED_FIELD,
            error(PluginCoreFixtures.replaceField(PluginCoreFixtures.manifest(), "unknown", JsonPrimitive(true))))
        assertEquals(PluginErrorCode.UNSUPPORTED_FIELD,
            error(PluginCoreFixtures.manifest(runtime = """{"type":"python","entry":"main.py","extra":true}""")))
    }
    @Test fun rejectsDuplicateJsonKeysIncludingEscapedKeys() {
        assertEquals(PluginErrorCode.MANIFEST_NOT_JSON, error(PluginCoreFixtures.manifest().replace("\"manifestVersion\":1", "\"manifestVersion\":1,\"manifestVersion\":1")))
        assertEquals(PluginErrorCode.MANIFEST_NOT_JSON, error("""{"id":"a","\u0069d":"b"}"""))
    }
    @Test fun refusesIncompatibleApiAndUnsupportedManifestVersion() {
        assertEquals(PluginErrorCode.API_INCOMPATIBLE, error(PluginCoreFixtures.manifest().replace("^1.0.0", "^2.0.0")))
        assertEquals(PluginErrorCode.UNSUPPORTED_MANIFEST_VERSION, error(PluginCoreFixtures.manifest().replace("\"manifestVersion\":1", "\"manifestVersion\":2")))
    }
    @Test fun rejectsPublisherMismatchAndRepositoryCredentials() {
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.replaceField(PluginCoreFixtures.manifest(), "publisher", JsonPrimitive("other"))))
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.replaceField(PluginCoreFixtures.manifest(), "repository", JsonPrimitive("https://user:secret@example.com/path"))))
    }
    @Test fun runtimeRequiresTypedResidentAndArgv() {
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.manifest(runtime = """{"type":"python","entry":"main.py","resident":"true"}""")))
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.manifest(runtime = """{"type":"python","entry":"main.py","command":"python3 main.py"}""")))
        val manifest = PluginCoreFixtures.manifestValue(PluginCoreFixtures.manifest(runtime = """{"type":"python","entry":"main.py","command":["python3","main.py"],"resident":true,"dependencies":{"tool":"pip","file":"requirements.txt"}}"""))
        assertEquals(listOf("python3", "main.py"), manifest.runtime?.command)
        assertEquals(true, manifest.runtime?.resident)
    }
    @Test fun rejectsUnsafePathsAndWrongEntryExtension() {
        for (path in listOf("/a", "../a", "a/../b", "a//b", "a\\b", "C:/a", "a\u0000b", "a\nb", "a/./b", "a/")) {
            assertFalse(path, PluginManifestParser.isSafeRelativePath(path))
        }
        assertFalse(PluginManifestParser.isSafeRelativePath((1..9).joinToString("/") { "a" }))
        assertTrue(PluginManifestParser.isSafeRelativePath("assets/lib/main.js"))
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.manifest(runtime = """{"type":"python","entry":"main.js"}""")))
    }
    @Test fun duplicateContributionIdsAreCaseInsensitive() {
        val value = PluginCoreFixtures.manifest(""""skills":[{"id":"hello","file":"a.md"},{"id":"HELLO","file":"b.md"}]""")
        assertEquals(PluginErrorCode.DUPLICATE_ID, error(value))
    }
    @Test fun supportedSlotsAndConfigPageAreAccepted() {
        for (slot in PluginUiSlots.ALL) {
            val action = slot in PluginUiSlots.ACTION_ONLY
            val data = slot in PluginUiSlots.DATA_ONLY
            val entry = when { data -> ""; action -> ",\"handler\":\"run\""; else -> ",\"page\":\"page.html\"" }
            val backup = if (data) """, "backup":{"purpose":"Export data"},"hooks":[{"id":"export","event":"backup.export.before","handler":"export"}]""" else ""
            val manifest = PluginCoreFixtures.manifestValue(PluginCoreFixtures.manifest(
                """ "uiEntries":[{"id":"entry","slot":"$slot","title":"Entry"$entry}],"configPage":"config.html"$backup """,
                runtime = if (action || data) """{"type":"javascript","entry":"main.js"}""" else null))
            assertEquals(slot, manifest.uiEntries.single().slot)
            assertEquals("config.html", manifest.configPage)
        }
    }
    @Test fun actionAndToolNeedRuntimeAndUnknownSlotsFail() {
        assertEquals(PluginErrorCode.FIELD_MISSING, error(PluginCoreFixtures.manifest(""""uiEntries":[{"id":"run","slot":"chat.header.menu","title":"Run","handler":"run"}]""")))
        assertEquals(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, error(PluginCoreFixtures.manifest(""""uiEntries":[{"id":"run","slot":"unknown.slot","title":"Run","page":"a.html"}]""")))
    }
    @Test fun manifestAndJsonDepthAreBounded() {
        assertEquals(PluginErrorCode.MANIFEST_TOO_LARGE, error(" ".repeat(PluginManifestParser.MAX_MANIFEST_BYTES + 1)))
        assertEquals(PluginErrorCode.MANIFEST_NOT_JSON, error("[".repeat(30) + "0" + "]".repeat(30)))
    }
    @Test fun localizedNormalizedDuplicateAndControlsFail() {
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.replaceField(PluginCoreFixtures.manifest(), "name",
            JsonObject(mapOf("zh_CN" to JsonPrimitive("a"), "zh-cn" to JsonPrimitive("b"))))))
        assertEquals(PluginErrorCode.FIELD_INVALID, error(PluginCoreFixtures.replaceField(PluginCoreFixtures.manifest(), "name", JsonPrimitive("a\nb"))))
    }
    @Test fun errorDetailIsBoundedAndControlFree() {
        val error = PluginError(PluginErrorCode.CONTENT_INVALID, "x\n\u0000".repeat(200))
        assertEquals(160, error.detail.length)
        assertFalse(error.detail.any { it.isISOControl() })
    }
}
