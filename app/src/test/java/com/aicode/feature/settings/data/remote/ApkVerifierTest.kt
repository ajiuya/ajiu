package com.aicode.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {

    @Test
    fun differentVersionCodes_takePrecedenceOverEveryVersionNameCombination() {
        val names = listOf(
            null, "", " ", "\t\n", "\u2003", "\u00a0",
            "1.12.9", "1.13.0-rc2", "1.13.0-rc10", "1.13.0", "1.13.0+build.2", "2.0.0"
        )
        val codePairs = listOf(
            1185L to 1184L,
            1183L to 1184L,
            0L to 1184L,
            Long.MAX_VALUE to 1184L,
            1184L to Long.MAX_VALUE
        )
        codePairs.forEach { (archiveCode, installedCode) ->
            names.forEach { archiveName ->
                names.forEach { installedName ->
                    assertEquals(
                        "$archiveCode/$archiveName over $installedCode/$installedName",
                        archiveCode > installedCode,
                        isNewerInstall(archiveCode, archiveName, installedCode, installedName)
                    )
                }
            }
        }
    }

    @Test
    fun equalVersionCodes_compareEveryVersionNamePairInExistingOrder() {
        val versions = listOf(
            "1.12.9",
            "1.13.0-dev.2+old",
            "1.13.0-dev.10+new",
            "1.13.0-rc2",
            "1.13.0-rc10",
            "1.13.0",
            "1.13.1-dev.1",
            "2.0.0"
        )
        listOf(0L, 1184L, Long.MAX_VALUE).forEach { code ->
            versions.forEachIndexed { archiveIndex, archiveName ->
                versions.forEachIndexed { installedIndex, installedName ->
                    assertEquals(
                        "$code/$archiveName over $code/$installedName",
                        archiveIndex > installedIndex,
                        isNewerInstall(code, archiveName, code, installedName)
                    )
                }
            }
        }
    }

    @Test
    fun equalVersionCodes_missingOrBlankNameOnEitherSideIsNotNewer() {
        val missing = listOf(null, "", " ", "\t\n", "\u2003", "\u00a0")
        missing.forEach { name ->
            assertFalse(isNewerInstall(1184, name, 1184, "1.13.0-rc2"))
            assertFalse(isNewerInstall(1184, "1.13.0", 1184, name))
            missing.forEach { other ->
                assertFalse(isNewerInstall(1184, name, 1184, other))
            }
        }
    }

    @Test
    fun equalVersionCodes_releaseOverRcIsNewerButReverseIsNot() {
        assertTrue(isNewerInstall(1184, "1.13.0", 1184, "1.13.0-rc2"))
        assertFalse(isNewerInstall(1184, "1.13.0-rc2", 1184, "1.13.0"))
    }

    @Test
    fun equalVersionCodes_identicalInstalledMetadataIsNotNewer() {
        assertFalse(isNewerInstall(1184, "1.13.0", 1184, "1.13.0"))
        assertFalse(isNewerInstall(1184, "1.13.0-rc2", 1184, "1.13.0-rc2"))
    }

    @Test
    fun equalVersionCodes_buildMetadataDoesNotCreateAnUpdate() {
        listOf("1.13.0", "1.13.0-rc2").forEach { version ->
            assertFalse(isNewerInstall(1184, "$version+new", 1184, "$version+old"))
            assertFalse(isNewerInstall(1184, "$version+new", 1184, version))
            assertFalse(isNewerInstall(1184, version, 1184, "$version+old"))
        }
        assertTrue(isNewerInstall(1184, "1.13.0+release", 1184, "1.13.0-rc2+rc"))
    }

    @Test
    fun equalVersionCodes_prereleaseSuffixesKeepExistingNumericAndUnicodeOrdering() {
        listOf("rc", "RC", "\u03b2").forEach { prefix ->
            assertTrue(isNewerInstall(1184, "1.13.0-${prefix}10", 1184, "1.13.0-${prefix}2"))
            assertFalse(isNewerInstall(1184, "1.13.0-${prefix}2", 1184, "1.13.0-${prefix}10"))
        }
    }
}
