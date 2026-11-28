package com.aicode.feature.agent.domain.container.chroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChrootRuntimeFailureTest {
    @Test
    fun exactPublicErrorFieldsRetainOnlyWhitelistedCodeStageAndErrno() {
        val body = "code=prepare_failed;stage=app_control_authentication;errno=13".toByteArray()
        val failure = ChrootRuntimeFailure.nativeError(body)
        body.fill(0)
        assertEquals(ChrootRuntimeFailureKind.NATIVE_ERROR, failure.kind)
        assertEquals(ChrootRuntimeFailureStage.NATIVE_ERROR, failure.stage)
        assertEquals("prepare_failed", failure.nativeCode)
        assertEquals("app_control_authentication", failure.nativeStage)
        assertEquals(13, failure.errno)
        assertTrue(failure.nativeFailureObserved)
        for (code in listOf("allocation_failed", "capability_rejected", "cleanup_unknown", "descriptor_rejected",
            "launch_rejected", "prepare_failed", "runtime_failed")) {
            assertEquals(code, ChrootRuntimeFailure.nativeError("code=$code;stage=supervision;errno=4095".toByteArray()).nativeCode)
        }
        assertEquals("aarch64", ChrootRuntimeFailure.nativeError("code=capability_rejected;stage=aarch64;errno=1".toByteArray()).nativeStage)
    }

    @Test
    fun writableRootGuestStagesAndLegacyReadonlyStagesRemainBoundedPublicDiagnostics() {
        for (stage in listOf("clone_writable_root", "writable_os_flags", "writable_executable_os",
            "clone_readonly_root", "readonly_os_flags", "readonly_executable_os")) {
            val body = "code=prepare_failed;stage=$stage;errno=13"
            val parsed = ChrootRuntimeFailure.nativeError(body.toByteArray())
            assertEquals(ChrootRuntimeFailureKind.NATIVE_ERROR, parsed.kind)
            assertEquals(stage, parsed.nativeStage)
            assertEquals(13, parsed.errno)
            assertTrue(parsed.nativeFailureObserved)
            assertEquals(parsed, ChrootRuntimeFailure.nativeStderr("ajiu_runtime: $body\n".toByteArray()))
        }
        val legacy = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=legacy_readonly_instance;errno=95".toByteArray())
        assertEquals(ChrootRuntimeFailureKind.NATIVE_ERROR, legacy.kind)
        assertEquals("legacy_readonly_instance", legacy.nativeStage)
        assertEquals(95, legacy.errno)
        val unknown = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=writable_private_target;errno=13".toByteArray())
        assertEquals(ChrootRuntimeFailureKind.INVALID_NATIVE_ERROR, unknown.kind)
        assertFalse(unknown.nativeFailureObserved)
    }

    @Test
    fun unknownOrMalformedPublicFieldsCannotBecomeSafeReportText() {
        val secret = "private_credential_marker"
        val cases = listOf(
            "code=$secret;stage=supervision;errno=5",
            "code=runtime_failed;stage=$secret;errno=5",
            "code=runtime_failed;stage=supervision;errno=$secret",
            "code=runtime_failed;stage=supervision;errno=5;extra=$secret",
            "stage=supervision;code=runtime_failed;errno=5",
            "code=runtime_failed;stage=supervision;errno=0",
            "code=runtime_failed;stage=supervision;errno=4096",
            "code=runtime_failed;stage=supervision;errno=-1",
            "code=runtime_failed;stage=supervision;errno=+1",
            "code=runtime_failed;stage=supervision;errno=05",
            "code=runtime_failed;stage=supervision;errno=5\n",
            "code=runtime_failed;stage=supervision;errno=5\u0000$secret",
            "code=runtime_failed;stage=supervision;errno=5;errno=5"
        ).map { it.toByteArray() } + listOf(
            byteArrayOf(), ByteArray(1025) { 65 },
            "code=runtime_failed;stage=".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + ";errno=5".toByteArray(),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())
        )
        val expected = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.INVALID_NATIVE_ERROR, ChrootRuntimeFailureStage.NATIVE_ERROR)
        for (body in cases) {
            val failure = ChrootRuntimeFailure.nativeError(body)
            assertEquals(expected, failure)
            assertFalse(failure.nativeFailureObserved)
            assertNull(failure.nativeCode)
            assertNull(failure.nativeStage)
            assertNull(failure.errno)
            assertFalse(failure.toString().contains(secret))
        }
    }

    @Test
    fun typedFailureCopiesCannotInjectUnvalidatedStringsIntoReports() {
        val valid = ChrootRuntimeFailure.nativeError("code=runtime_failed;stage=supervision;errno=5".toByteArray())
        rejects { valid.copy(nativeCode = "private_credential_marker") }
        rejects { valid.copy(nativeStage = "private_credential_marker") }
        rejects { valid.copy(errno = 0) }
        rejects { valid.copy(stage = ChrootRuntimeFailureStage.GO) }
        rejects { valid.copy(kind = ChrootRuntimeFailureKind.PROTOCOL) }
        val local = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        rejects { local.copy(nativeCode = "private_credential_marker") }
        assertFalse(local.nativeFailureObserved)
        rejects { local.copy(journalFailure = ChrootJournalFailure(ChrootJournalFailureReason.WRITE, 0)) }
        rejects { local.copy(journalFailure = ChrootJournalFailure(ChrootJournalFailureReason.SYNC, 4096)) }
        assertEquals("ChrootRuntimeFailure(kind=TRANSPORT, stage=HELPER_STDERR, nativeCode=null, nativeStage=null, errno=null, journalFailure=null)", local.toString())
    }

    @Test
    fun journalFailuresRetainOnlyFixedReasonsAndOptionalErrnoWithoutExceptionData() {
        val privateData = "/private/path/private_credential_marker command --token=private_credential_marker"
        for (reason in ChrootJournalFailureReason.entries) {
            for (errno in listOf(null, 1, 28, 4095)) {
                val exception = ChrootJournalException(reason, errno, IOException(privateData))
                exception.addSuppressed(IOException(privateData))
                val failure = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.STARTUP,
                    ChrootRuntimeFailureStage.INTENT_JOURNAL, exception)
                assertEquals(ChrootJournalFailure(reason, errno), failure.journalFailure)
                assertFalse(failure.nativeFailureObserved)
                assertNull(failure.nativeCode)
                assertNull(failure.nativeStage)
                assertNull(failure.errno)
                assertFalse(failure.toString().contains("private_credential_marker"))
                val diagnostic = ChrootRuntimeDiagnostic("0123456789abcdef01234567", "89abcdef0123456789abcdef",
                    failure, null, ChrootCleanupResult.VERIFIED)
                assertEquals("launch=89abcdef0123456789abcdef;failure=STARTUP/INTENT_JOURNAL;journal=${reason.name}" +
                    (errno?.let { ";journalErrno=$it" } ?: "") + ";cleanup=VERIFIED", diagnostic.summary())
                assertFalse(diagnostic.summary().contains(privateData))
            }
        }
    }

    @Test
    fun journalFailureDoesNotInferItsReasonOrErrnoFromExceptionText() {
        val privateData = IOException("journal=NO_SPACE;errno=28;/private_credential_marker")
        val untyped = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.STARTUP,
            ChrootRuntimeFailureStage.INTENT_JOURNAL, privateData as? ChrootJournalException)
        assertNull(untyped.journalFailure)
        for (errno in listOf(-1, 0, 4096, Int.MAX_VALUE)) {
            val typed = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.CLEANUP,
                ChrootRuntimeFailureStage.CLEANUP_JOURNAL, ChrootJournalException(ChrootJournalFailureReason.SYNC, errno, privateData))
            assertEquals(ChrootJournalFailureReason.SYNC, typed.journalFailure!!.reason)
            assertNull(typed.journalFailure.errno)
            assertFalse(typed.toString().contains("private_credential_marker"))
        }
    }

    @Test
    fun exactStderrMirrorUsesTheSameValidatedNativeFieldsAsTheErrorFrame() {
        val body = "code=prepare_failed;stage=app_control_authentication;errno=13"
        val line = "ajiu_runtime: $body\n".toByteArray()
        val parsed = ChrootRuntimeFailure.nativeStderr(line)
        assertEquals(ChrootRuntimeFailure.nativeError(body.toByteArray()), parsed)
        assertTrue(parsed!!.nativeFailureObserved)
        line.fill(0)
        assertEquals("prepare_failed", parsed.nativeCode)
        assertEquals("app_control_authentication", parsed.nativeStage)
        assertEquals(13, parsed.errno)
    }

    @Test
    fun stderrParserRejectsPartialExtraUnknownAndOversizedBytesWithoutResynchronizing() {
        val valid = "ajiu_runtime: code=runtime_failed;stage=supervision;errno=5\n".toByteArray()
        val invalid = listOf(
            byteArrayOf(), valid.copyOf(valid.size - 1), valid.copyOfRange(1, valid.size),
            "private_credential_marker\n".toByteArray(),
            "private_credential_marker\n".toByteArray() + valid,
            valid + "private_credential_marker\n".toByteArray(),
            valid + valid,
            valid.copyOf(valid.size - 1) + byteArrayOf(13, 10),
            "ajiu_runtime: code=runtime_failed;stage=private_credential_marker;errno=5\n".toByteArray(),
            "ajiu_runtime: code=private_credential_marker;stage=supervision;errno=5\n".toByteArray(),
            "ajiu_runtime: code=runtime_failed;stage=supervision;errno=0\n".toByteArray(),
            "ajiu_runtime: code=runtime_failed;stage=supervision;errno=5\u0000\n".toByteArray(),
            "ajiu_runtime: code=runtime_failed;stage=".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + ";errno=5\n".toByteArray(),
            ByteArray(256) { 65 }.apply { this[lastIndex] = 10 },
            valid + ByteArray(256)
        )
        invalid.forEach { assertNull(ChrootRuntimeFailure.nativeStderr(it)) }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Unsafe diagnostic construction must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }
}
