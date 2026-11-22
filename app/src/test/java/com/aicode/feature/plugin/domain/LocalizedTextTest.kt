package com.aicode.feature.plugin.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizedTextTest {
    @Test fun exactLocalePrecedesBaseAndChineseAliases() {
        val text = LocalizedText(mapOf(
            "default" to "Fallback", "zh" to "通用中文", "zh-hans" to "简体中文",
            "zh-cn" to "大陆中文", "zh-hans-cn" to "精确简体中文",
            "en" to "English", "en-us" to "American English"
        ))
        assertEquals("精确简体中文", text.resolve("zh-Hans-CN"))
        assertEquals("大陆中文", text.resolve("zh-CN"))
        assertEquals("American English", text.resolve("en-US"))
    }

    @Test fun baseLanguagePrecedesChineseAliases() {
        val text = LocalizedText(mapOf(
            "default" to "Fallback", "zh" to "通用中文", "zh-hans" to "简体中文",
            "zh-hant" to "繁體中文", "zh-cn" to "大陆中文", "en" to "English"
        ))
        for (tag in listOf("zh-Hans-CN", "zh-Hant-HK", "zh-TW", "zh-SG")) {
            assertEquals(tag, "通用中文", text.resolve(tag))
        }
        assertEquals("English", text.resolve("en-GB"))
    }

    @Test fun genericChineseResolvesMainlandMetadata() {
        val text = LocalizedText(mapOf("default" to "Desktop Pet", "zh-cn" to "桌面宠物"))
        assertEquals("桌面宠物", text.resolve("zh"))
    }

    @Test fun simplifiedScriptAndRegionResolveMainlandMetadata() {
        val text = LocalizedText(mapOf("default" to "Desktop Pet", "zh-cn" to "桌面宠物"))
        assertEquals("桌面宠物", text.resolve("zh-Hans-CN"))
    }

    @Test fun mainlandAndSingaporeChineseResolveSimplifiedScriptMetadata() {
        val text = LocalizedText(mapOf(
            "default" to "Desktop Pet", "zh-hans" to "桌面宠物", "zh-hant" to "桌面寵物"
        ))
        for (tag in listOf("zh-CN", "zh-SG")) {
            assertEquals(tag, "桌面宠物", text.resolve(tag))
        }
    }

    @Test fun genericChineseResolvesSingaporeMetadataWhenOtherSimplifiedAliasesAreAbsent() {
        val text = LocalizedText(mapOf("default" to "Desktop Pet", "zh-sg" to "桌面宠物"))
        assertEquals("桌面宠物", text.resolve("zh"))
        assertEquals("桌面宠物", text.resolve("zh-Hans-CN"))
    }

    @Test fun traditionalRegionsResolveTraditionalScriptMetadata() {
        val text = LocalizedText(mapOf(
            "default" to "Desktop Pet", "zh-hans" to "桌面宠物", "zh-hant" to "桌面寵物"
        ))
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO")) {
            assertEquals(tag, "桌面寵物", text.resolve(tag))
        }
    }

    @Test fun traditionalScriptResolvesRegionalMetadata() {
        for (locale in listOf("zh-tw", "zh-hk", "zh-mo")) {
            val text = LocalizedText(mapOf(
                "default" to "Desktop Pet", "zh-cn" to "桌面宠物", locale to "桌面寵物"
            ))
            for (tag in listOf("zh-Hant", "zh-Hant-CN", "zh-Hant-SG")) {
                assertEquals("$locale/$tag", "桌面寵物", text.resolve(tag))
            }
        }
    }

    @Test fun chineseScriptAliasesPrecedeRegionalAliases() {
        val text = LocalizedText(mapOf(
            "default" to "Fallback", "zh-sg" to "新加坡中文", "zh-cn" to "大陆中文",
            "zh-hans" to "简体中文", "zh-mo" to "澳門中文", "zh-hk" to "香港中文",
            "zh-tw" to "臺灣中文", "zh-hant" to "繁體中文"
        ))
        assertEquals("简体中文", text.resolve("zh"))
        assertEquals("繁體中文", text.resolve("zh-Hant-CN"))
    }

    @Test fun chineseRegionalAliasesPreserveTheirOrder() {
        val simplified = LocalizedText(mapOf(
            "default" to "Fallback", "zh-sg" to "新加坡中文", "zh-cn" to "大陆中文"
        ))
        val traditional = LocalizedText(mapOf(
            "default" to "Fallback", "zh-mo" to "澳門中文", "zh-hk" to "香港中文", "zh-tw" to "臺灣中文"
        ))
        val hongKong = LocalizedText(mapOf(
            "default" to "Fallback", "zh-mo" to "澳門中文", "zh-hk" to "香港中文"
        ))
        assertEquals("大陆中文", simplified.resolve("zh"))
        assertEquals("臺灣中文", traditional.resolve("zh-Hant"))
        assertEquals("香港中文", hongKong.resolve("zh-Hant"))
    }

    @Test fun explicitSimplifiedScriptOverridesTraditionalRegion() {
        val text = LocalizedText(mapOf(
            "default" to "Desktop Pet", "zh-hans" to "桌面宠物", "zh-hant" to "桌面寵物",
            "zh-tw" to "臺灣桌寵", "zh-hk" to "香港桌寵", "zh-mo" to "澳門桌寵"
        ))
        for (tag in listOf("zh-Hans-TW", "zh-Hans-HK", "zh-Hans-MO")) {
            assertEquals(tag, "桌面宠物", text.resolve(tag))
        }
    }

    @Test fun missingSimplifiedMetadataKeepsOriginalDefault() {
        val text = LocalizedText(mapOf(
            "default" to "Desktop Pet", "en" to "English pet", "zh-hant" to "桌面寵物",
            "zh-tw" to "臺灣桌寵", "zh-hk" to "香港桌寵", "zh-mo" to "澳門桌寵"
        ))
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN", "zh-Hans-TW")) {
            assertEquals(tag, "Desktop Pet", text.resolve(tag))
        }
    }

    @Test fun missingTraditionalMetadataKeepsOriginalDefault() {
        val text = LocalizedText(mapOf(
            "default" to "Desktop Pet", "en" to "English pet", "zh-hans" to "简体桌宠",
            "zh-cn" to "大陆桌宠", "zh-sg" to "新加坡桌宠"
        ))
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN")) {
            assertEquals(tag, "Desktop Pet", text.resolve(tag))
        }
    }

    @Test fun absentChineseMetadataKeepsOriginalDefault() {
        val text = LocalizedText(mapOf("default" to "Desktop Pet", "en" to "English pet"))
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-TW", "zh-HK", "zh-Hans-CN", "zh-Hant")) {
            assertEquals(tag, "Desktop Pet", text.resolve(tag))
        }
    }

    @Test fun requestedLanguageTagsNormalizeCaseAndUnderscores() {
        val text = LocalizedText(mapOf(
            "default" to "Desktop Pet", "zh-cn" to "桌面宠物", "zh-hant" to "桌面寵物"
        ))
        for (tag in listOf("zh_CN", "ZH-cn", "  Zh_cN  ")) {
            assertEquals(tag, "zh-cn", LocalizedText.normalize(tag))
            assertEquals(tag, "桌面宠物", text.resolve(tag))
        }
        for (tag in listOf("ZH_hANS_cn", "  Zh_Hans_CN  ")) {
            assertEquals(tag, "zh-hans-cn", LocalizedText.normalize(tag))
            assertEquals(tag, "桌面宠物", text.resolve(tag))
        }
        assertEquals("桌面寵物", text.resolve("  ZH_hAnT_hk  "))
    }

    @Test fun unmatchedNonChineseLocalesPreferDefaultToEnglish() {
        val text = LocalizedText(linkedMapOf(
            "zh-cn" to "桌面宠物", "en" to "English pet", "default" to "Desktop Pet"
        ))
        for (tag in listOf("fr-FR", "ja", "de-DE")) {
            assertEquals(tag, "Desktop Pet", text.resolve(tag))
        }
    }

    @Test fun unmatchedNonChineseLocalesUseEnglishWithoutDefault() {
        val text = LocalizedText(linkedMapOf("zh-cn" to "桌面宠物", "en" to "Desktop Pet"))
        for (tag in listOf("fr-FR", "ja", "de-DE")) {
            assertEquals(tag, "Desktop Pet", text.resolve(tag))
        }
    }

    @Test fun unmatchedNonChineseLocalesUseFirstEntryWithoutDefaultOrEnglish() {
        val text = LocalizedText(linkedMapOf("de" to "Erster Eintrag", "fr" to "Deuxième entrée"))
        assertEquals("Erster Eintrag", text.resolve("es-MX"))
        assertEquals("Erster Eintrag", text.resolve("ja"))
    }
}
