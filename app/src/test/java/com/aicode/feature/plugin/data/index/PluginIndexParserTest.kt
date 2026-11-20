package com.aicode.feature.plugin.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginIndexParserTest {
    @Test fun parsesOriginalUpstreamSchemaAndLocalizedText(): Unit {
        val parsed = PluginIndexParser.parse(index().toByteArray())
        val plugin = parsed.plugins.single()
        assertEquals(1, parsed.schemaVersion)
        assertEquals("Sample", plugin.name.resolve("en-US"))
        assertEquals("示例", plugin.name.resolve("zh-CN"))
        assertEquals("1.0.0", plugin.version)
    }
    @Test fun acceptsSingleStringTextWithoutChangingSchema(): Unit {
        val parsed = PluginIndexParser.parse(index().replace("{\"en\":\"Sample\",\"zh\":\"示例\"}", "\"Sample\"").toByteArray())
        assertEquals("Sample", parsed.plugins.single().name.resolve("fr"))
    }
    @Test fun rejectsDuplicatePluginIdsRatherThanSilentlyReplacing(): Unit {
        val one = index().substringAfter("\"plugins\":[").removeSuffix("]}")
        assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse("{\"schema_version\":1,\"plugins\":[$one,$one]}".toByteArray()) }
    }
    @Test fun rejectsDuplicateJsonKeys(): Unit {
        assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(index().replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1").toByteArray()) }
    }
    @Test fun rejectsFutureIndexSchemaAndInvalidVersion(): Unit {
        for (raw in listOf(index().replace("schema_version\":1", "schema_version\":5"), index().replace("1.0.0", "v1.0.0"))) {
            assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(raw.toByteArray()) }
        }
    }
    @Test fun rejectsRepositoryCredentialsQueriesAndNonGitHubOrigins(): Unit {
        for (url in listOf("https://user@github.com/test/sample", "https://github.com/test/sample?token=value", "https://github.com.evil.invalid/test/sample", "http://github.com/test/sample", "https://github.com/test/sample/extra")) {
            assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(index().replace("https://github.com/test/sample", url).toByteArray()) }
        }
    }
    @Test fun rejectsUnknownTagsAndNormalizedDuplicateLocales(): Unit {
        assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(index().replace("\"tool\"", "\"unknown\"").toByteArray()) }
        assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(index().replace("\"zh\":\"示例\"", "\"EN\":\"other\"").toByteArray()) }
    }
    @Test fun releaseAssetNamesUseOfficialUnderscore(): Unit {
        assertEquals("test.sample_1.2.3.zip", PluginIndexClient.assetName("test.sample", "1.2.3"))
        assertEquals("jieapi.desktop-pet_1.0.0.zip", PluginIndexClient.assetName("jieapi.desktop-pet", "1.0.0"))
        assertEquals("test.sample_1.2.3-rc.1.zip", PluginIndexClient.assetName("test.sample", "1.2.3-rc.1"))
    }
    @Test fun releaseAssetVersionsAcceptBothSeparatorsAndPrereleases(): Unit {
        for (separator in listOf("_", "-")) {
            for (version in listOf("1.2.3", "1.2.3-rc.1", "1.2.3-beta.10", "1.2.3+build.7")) {
                assertEquals(version, PluginIndexClient.assetVersion("test.sample", "test.sample$separator$version.zip"))
            }
            assertEquals("1.0.0", PluginIndexClient.assetVersion("jieapi.desktop-pet", "jieapi.desktop-pet${separator}1.0.0.zip"))
        }
    }
    @Test fun releaseAssetVersionsRejectInvalidVersionsAndArchiveSuffixes(): Unit {
        for (separator in listOf("_", "-")) {
            for (version in listOf("", "v1.2.3", "V1.2.3", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-01", "1.2.3-rc..1", "1.2.3+", "2147483648.0.0")) {
                val name = "test.sample$separator$version.zip"
                assertNull(name, PluginIndexClient.assetVersion("test.sample", name))
            }
            for (suffix in listOf(".zip.sig", ".zip.bak", ".zip/extra", ".ZIP", ".tar.gz", "")) {
                val name = "test.sample${separator}1.2.3$suffix"
                assertNull(name, PluginIndexClient.assetVersion("test.sample", name))
            }
        }
    }
    @Test fun releaseAssetVersionsRequireExactPluginIdAndSeparator(): Unit {
        for (name in listOf("other.sample_1.2.3.zip", "other.sample-1.2.3.zip", "test.sample.extra_1.2.3.zip", "TEST.sample_1.2.3.zip", "test_sample_1.2.3.zip", "../test.sample_1.2.3.zip", "test.sample1.2.3.zip", "test.sample__1.2.3.zip")) {
            assertNull(name, PluginIndexClient.assetVersion("test.sample", name))
        }
    }
    @Test fun rejectsInvalidPluginIds(): Unit {
        for (id in listOf("", "sample", "Test.sample", "test_sample", "test..sample", "../test.sample", "test.sample/other", "test.sample_", "test.1sample")) {
            assertThrows(id, PluginIndexException::class.java) {
                PluginIndexParser.parse(index().replace("\"id\":\"test.sample\"", "\"id\":\"$id\"").toByteArray())
            }
        }
    }
    @Test fun rejectsInvalidSemVerVersions(): Unit {
        for (version in listOf("", "v1.0.0", "1.0", "01.0.0", "1.0.0-01", "1.0.0-rc..1", "1.0.0+", "2147483648.0.0")) {
            assertThrows(version, PluginIndexException::class.java) {
                PluginIndexParser.parse(index().replace("\"version\":\"1.0.0\"", "\"version\":\"$version\"").toByteArray())
            }
        }
    }
    @Test fun acceptsPrereleaseIndexVersion(): Unit {
        val parsed = PluginIndexParser.parse(index().replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.0-rc.1\"").toByteArray())
        assertEquals("1.0.0-rc.1", parsed.plugins.single().version)
    }
    @Test fun normalizedSourceIdentityIgnoresHostCaseAndDefaultTlsPort(): Unit {
        assertEquals(PluginIndexSourceRepository.sourceId("https://EXAMPLE.invalid:443/index.json"), PluginIndexSourceRepository.sourceId("https://example.invalid/index.json"))
        assertNotEquals(PluginIndexSourceRepository.sourceId("https://example.invalid/index.json"), PluginIndexSourceRepository.sourceId("https://example.invalid/other.json"))
        assertEquals("aicode.upstream.community", PluginIndexDefaults.COMMUNITY_ID)
        assertFalse(PluginIndexDefaults.COMMUNITY_ID.contains("official"))
    }
    @Test fun sourceAndMirrorAddressesRequireTlsWithoutCredentialsOrFragments(): Unit {
        for (value in listOf("http://example.invalid/index.json", "https://u:p@example.invalid/index.json", "https://example.invalid/index.json#fragment", "https://example.invalid:80/index.json")) assertFalse(value, PluginIndexAddress.isHttps(value))
        assertThrows(IllegalArgumentException::class.java) { PluginIndexAddress.normalize("https://example.invalid/index.json?token=value") }
        assertTrue(PluginIndexAddress.isHttps("https://example.invalid/index.json"))
    }
    @Test fun indexByteBudgetFailsBeforeParsing(): Unit {
        assertThrows(PluginIndexException::class.java) { PluginIndexParser.parse(ByteArray(PluginIndexParser.MAX_INDEX_BYTES + 1)) }
    }
    @Test fun parsesCommunityDefaultAndMainlandChineseMetadataForAppLanguage(): Unit {
        val parsed = PluginIndexParser.parse(communityIndex().toByteArray())
        val plugin = parsed.plugins.single()
        assertEquals(1, parsed.schemaVersion)
        assertEquals(setOf("default", "zh-cn"), plugin.name.values.keys)
        assertEquals(setOf("default", "zh-cn"), plugin.description.values.keys)
        for (tag in listOf("zh", "zh-Hans-CN")) {
            assertEquals(tag, "桌面宠物", plugin.name.resolve(tag))
            assertEquals(tag, "可拖拽、可点击互动的桌面宠物：跟随 AI 运行状态变化，长按打开菜单，可在配置页调整大小与显示时机。", plugin.description.resolve(tag))
        }
        for (tag in listOf("en", "en-US")) {
            assertEquals(tag, "Desktop Pet", plugin.name.resolve(tag))
            assertEquals(tag, "A draggable, interactive desktop pet that reacts to AI run status. Long press for the menu; size and display timing are configurable.", plugin.description.resolve(tag))
        }
    }
    @Test fun normalizesCommunityLocaleKeysWithCaseAndUnderscoresInBothFields(): Unit {
        for (locale in listOf("ZH_cn", "Zh_Cn", "zh_CN", "ZH-CN")) {
            val raw = communityIndex().replace("\"zh-CN\":", "\"$locale\":").replace("\"default\":", "\"DeFaUlT\":")
            val plugin = PluginIndexParser.parse(raw.toByteArray()).plugins.single()
            assertEquals(locale, setOf("default", "zh-cn"), plugin.name.values.keys)
            assertEquals(locale, setOf("default", "zh-cn"), plugin.description.values.keys)
            for (tag in listOf("zh", "zh-Hans-CN", "ZH_cn")) {
                assertEquals("$locale/$tag", "桌面宠物", plugin.name.resolve(tag))
                assertEquals("$locale/$tag", "可拖拽、可点击互动的桌面宠物：跟随 AI 运行状态变化，长按打开菜单，可在配置页调整大小与显示时机。", plugin.description.resolve(tag))
            }
            assertEquals("Desktop Pet", plugin.name.resolve("en"))
        }
    }
    @Test fun rejectsDuplicateNormalizedChineseLocaleKeysInEitherMetadataField(): Unit {
        for (field in listOf("name", "description")) {
            for (locale in listOf("ZH_cn", "zh_CN", "Zh-cN")) {
                val raw = communityIndex().replace("\"$field\": {", "\"$field\": {\"$locale\":\"Duplicate\",")
                assertThrows("$field/$locale", PluginIndexException::class.java) { PluginIndexParser.parse(raw.toByteArray()) }
            }
        }
    }
    private fun communityIndex(): String = """
        {
          "schema_version": 1,
          "plugins": [{
            "id": "jieapi.desktop-pet",
            "name": {"default": "Desktop Pet", "zh-CN": "桌面宠物"},
            "description": {
              "default": "A draggable, interactive desktop pet that reacts to AI run status. Long press for the menu; size and display timing are configurable.",
              "zh-CN": "可拖拽、可点击互动的桌面宠物：跟随 AI 运行状态变化，长按打开菜单，可在配置页调整大小与显示时机。"
            },
            "author": "jieapi",
            "repository": "https://github.com/jieapi/aicode-plugin-desktop-pet",
            "version": "1.0.0",
            "tags": ["ui"]
          }]
        }
    """.trimIndent()
    private fun index(): String = """{"schema_version":1,"plugins":[{"id":"test.sample","name":{"en":"Sample","zh":"示例"},"description":"Example plugin","author":"test","repository":"https://github.com/test/sample","version":"1.0.0","tags":["tool"]}]}"""
}
