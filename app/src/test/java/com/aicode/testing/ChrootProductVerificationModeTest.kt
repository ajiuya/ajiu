package com.aicode.testing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootProductVerificationModeTest {
    private fun arguments() = mapOf(
        "class" to ChrootProductVerificationMode.SUITE,
        "mode" to ChrootProductVerificationMode.MODE,
        "confirmedScope" to ChrootProductVerificationMode.SCOPE
    )

    @Test fun onlyTheExactOfflineSuiteAndScopeAreAccepted() {
        ChrootProductVerificationMode.checkArguments(arguments())
        for (key in arguments().keys) {
            assertThrows(IllegalArgumentException::class.java) { ChrootProductVerificationMode.checkArguments(arguments() - key) }
            for (value in listOf(null, "", "full", "startup-eof", "../other", arguments().getValue(key) + "extra")) {
                assertThrows(IllegalArgumentException::class.java) {
                    ChrootProductVerificationMode.checkArguments(arguments() + (key to value))
                }
            }
        }
    }

    @Test fun ExtraClassesMethodsPathsAndInstrumentationParametersDoNotExpandAuthorization() {
        for (key in listOf("package", "method", "debug", "path", "command", "numShards", "elevate")) {
            assertThrows(IllegalArgumentException::class.java) {
                ChrootProductVerificationMode.checkArguments(arguments() + (key to "value"))
            }
        }
        for (suffix in listOf("#method", ",other.Class", ".*")) {
            assertThrows(IllegalArgumentException::class.java) {
                ChrootProductVerificationMode.checkArguments(arguments() + ("class" to ChrootProductVerificationMode.SUITE + suffix))
            }
        }
    }

    @Test fun ExactlyFiveFdAndSixProductTestsMustHaveStartedAndFinished() {
        assertTrue(ChrootProductVerificationMode.countsMatch(5, 6, 11, 11))
        for (fd in listOf(0, 4, 6, 11)) assertFalse(ChrootProductVerificationMode.countsMatch(fd, 6, 11, 11))
        for (product in listOf(0, 5, 7, 11)) assertFalse(ChrootProductVerificationMode.countsMatch(5, product, 11, 11))
        for (count in listOf(0, 10, 12)) {
            assertFalse(ChrootProductVerificationMode.countsMatch(5, 6, count, 11))
            assertFalse(ChrootProductVerificationMode.countsMatch(5, 6, 11, count))
        }
    }
}
