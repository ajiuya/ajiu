package com.aicode.testing

import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFailure
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFailureKind
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFailureStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChrootVerificationEvidenceTest {
    @Test fun originalAssertionRemainsPrimaryAndEveryCleanupRunsInOrder() = runBlocking {
        val primary = assertion("stdio_ready")
        val first = IOException("secret-first-cleanup")
        val second = IllegalStateException("secret-second-cleanup")
        val events = mutableListOf<String>()
        var observed: Throwable? = null
        val failure = runCatching {
            withCleanup(cleanup = listOf(
                { events += "first"; throw first },
                { events += "second"; throw second },
                { events += "last" }
            ), observe = { observed = it }) { throw primary }
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertSame(primary, observed)
        assertEquals(listOf(first, second), primary.suppressed.toList())
        assertEquals(listOf("first", "second", "last"), events)
    }

    @Test fun bodyOnlyFailureIsRethrownWithoutReplacement() = runBlocking {
        val primary = IOException("private-body")
        var cleaned = false
        val failure = runCatching { withCleanup(cleanup = listOf({ cleaned = true })) { throw primary } }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(cleaned)
        assertTrue(primary.suppressed.isEmpty())
    }

    @Test fun cleanupOnlyFailureStillFailsAndKeepsLaterCleanupAsSuppressed() = runBlocking {
        val primary = IOException("private-cleanup")
        val next = IllegalStateException("private-next")
        val failure = runCatching { withCleanup(cleanup = listOf({ throw primary }, { throw next })) { 37 } }.exceptionOrNull()
        assertSame(primary, failure)
        assertEquals(listOf(next), primary.suppressed.toList())
    }

    @Test fun successfulBodyAndCleanupReturnTheOriginalValue() = runBlocking {
        var cleaned = 0
        var observed: Throwable? = IOException()
        assertEquals(37, withCleanup(cleanup = listOf({ cleaned++ }), observe = { observed = it }) { 37 })
        assertEquals(1, cleaned)
        assertEquals(null, observed)
    }

    @Test fun cancellationIsNeverSwallowedAndReportingFailureCannotReplaceIt() = runBlocking {
        val cancelled = CancellationException("private-cancel")
        val cleanup = IOException("private-cleanup")
        val report = IllegalStateException("private-report")
        val failure = runCatching {
            withCleanup(cleanup = listOf({ throw cleanup }), observe = { throw report }) { throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, failure)
        assertEquals(listOf(cleanup, report), cancelled.suppressed.toList())
    }

    @Test fun onlyOwnedWhitelistedAssertionsExposeMarkersAndHelperFramesAreSkipped() {
        val failure = assertion("stdio_ready")
        failure.stackTrace = arrayOf(
            StackTraceElement("com.aicode.testing.ChrootVerificationEvidenceKt", "withCleanup", "ChrootVerificationEvidence.kt", 1),
            StackTraceElement("com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest", "requireTrue", "ChrootRuntimeDeviceTest.kt", 616),
            StackTraceElement("com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest", "access\$requireTrue", "ChrootRuntimeDeviceTest.kt", 616),
            StackTraceElement("com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest", "a_stdioIdentityReadOnlyDnsAndGuestExit37", "ChrootRuntimeDeviceTest.kt", 82)
        )
        val safe = ChrootVerificationEvidence.safeFailure(failure)
        assertEquals("stdio_ready", safe.getValue("marker").jsonPrimitive.content)
        assertEquals("ChrootRuntimeDeviceTest.kt:82", safe.getValue("location").jsonPrimitive.content)
        assertFalse(safe.toString().contains("616"))
        assertThrows(IllegalArgumentException::class.java) { VerificationAssertion.requireTrue(false, "secret_token_not_a_marker") }
    }

    @Test fun ordinaryExceptionsCannotSpoofMarkersOrLeakPrivateMessagesAndFileNames() {
        for (failure in listOf(IOException("token=private;argv=secret"), RuntimeException("stdio_ready"), AssertionError("guest_limits_ro_dns"))) {
            failure.stackTrace = arrayOf(StackTraceElement("com.aicode.private.secret", "hidden", "/data/private-token", 12))
            val safe = ChrootVerificationEvidence.safeFailure(failure)
            assertEquals(setOf("type", "suppressed"), safe.keys)
            for (secret in listOf("private-token", "argv=secret", "stdio_ready", "guest_limits_ro_dns")) assertFalse(safe.toString().contains(secret))
        }
    }

    @Test fun nestedAndSuppressedFailuresHaveFixedDepthCountAndBoundedOutput() {
        val primary = IOException("private-root")
        var current: Throwable = primary
        repeat(20) {
            val cause = IllegalStateException("private-cause-$it")
            current.initCause(cause)
            repeat(8) { index -> current.addSuppressed(IOException("private-suppressed-$index")) }
            current = cause
        }
        val safe = ChrootVerificationEvidence.safeFailure(primary)
        assertEquals(3, safe.getValue("suppressed").jsonArray.size)
        val lastCause = safe.getValue("cause").jsonObject.getValue("cause").jsonObject
        assertFalse(lastCause.containsKey("cause"))
        assertTrue(lastCause.getValue("truncated").jsonPrimitive.boolean)
        assertFalse(safe.toString().contains("private-"))
        assertTrue(safe.toString().toByteArray().size <= ChrootVerificationEvidence.MAX_REPORT_BYTES)
        val report = ChrootVerificationEvidence.boundedReport("x".repeat(10000) + "尾".repeat(10000))
        assertTrue(report.toByteArray().size <= ChrootVerificationEvidence.MAX_REPORT_BYTES)
        assertTrue(report.endsWith("[diagnostics truncated]\n"))
    }

    @Test fun cyclicFailureGraphsAreFiniteAndDoNotPrintThrowableMessages() {
        val first = IOException("private-first")
        val second = IOException("private-second")
        first.initCause(second)
        second.initCause(first)
        first.addSuppressed(second)
        val value = ChrootVerificationEvidence.safeFailure(first).toString()
        assertTrue(value.contains("truncated"))
        assertFalse(value.contains("private-"))
        assertTrue(value.toByteArray().size <= ChrootVerificationEvidence.MAX_REPORT_BYTES)
    }

    @Test fun typedRuntimeProjectionExposesOnlyFiniteFieldsAndNeverGuessesNativeEvidence() {
        val local = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        val localJson = ChrootVerificationEvidence.runtimeFailure(local)
        assertEquals("TRANSPORT", localJson.getValue("kind").jsonPrimitive.content)
        assertEquals("HELPER_STDERR", localJson.getValue("stage").jsonPrimitive.content)
        assertFalse(localJson.getValue("nativeFailureObserved").jsonPrimitive.boolean)
        assertEquals(JsonNull, localJson["nativeCode"])
        assertEquals(JsonNull, localJson["nativeStage"])
        assertEquals(JsonNull, localJson["errno"])
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        val nativeJson = ChrootVerificationEvidence.runtimeFailure(native)
        assertEquals(setOf("kind", "stage", "nativeFailureObserved", "nativeCode", "nativeStage", "errno"), nativeJson.keys)
        assertTrue(nativeJson.getValue("nativeFailureObserved").jsonPrimitive.boolean)
        assertEquals("prepare_failed", nativeJson.getValue("nativeCode").jsonPrimitive.content)
        assertEquals("cgroup2_required", nativeJson.getValue("nativeStage").jsonPrimitive.content)
        assertEquals(13, nativeJson.getValue("errno").jsonPrimitive.int)
    }

    @Test fun dnsDeclarationAndUnobservedGoNeverImplyExecutionOrVerification() {
        val evidence = ChrootVerificationEvidence()
        evidence.attach(nonce(1))
        evidence.recordHandle(nonce(2), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, assertion("stdio_ready"))
        val before = Json.parseToJsonElement(evidence.json()).jsonObject
        val dns = before.getValue("dns").jsonObject
        assertTrue(dns.getValue("queryIncluded").jsonPrimitive.boolean)
        assertFalse(dns.getValue("verified").jsonPrimitive.boolean)
        assertEquals("UNOBSERVED", dns.getValue("state").jsonPrimitive.content)
        assertEquals("UNOBSERVED", before.getValue("handles").jsonArray.single().jsonObject.getValue("goObservation").jsonPrimitive.content)
        evidence.markDnsVerified()
        assertTrue(Json.parseToJsonElement(evidence.json()).jsonObject.getValue("dns").jsonObject.getValue("verified").jsonPrimitive.boolean)
    }

    @Test fun observedGoAndTheFirstFailureAreLatchedAcrossLaterCleanupSnapshots() {
        val evidence = ChrootVerificationEvidence()
        val first = assertion("stdio_ready")
        evidence.recordHandle(nonce(1), true, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNOBSERVED, null, first)
        first.addSuppressed(assertion("final_handle_cleanup"))
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, assertion("final_handle_cleanup"))
        val handle = Json.parseToJsonElement(evidence.json()).jsonObject.getValue("handles").jsonArray.single().jsonObject
        assertEquals("OBSERVED", handle.getValue("goObservation").jsonPrimitive.content)
        val primary = handle.getValue("primaryFailure").jsonObject
        assertEquals("stdio_ready", primary.getValue("marker").jsonPrimitive.content)
        assertEquals("final_handle_cleanup", primary.getValue("suppressed").jsonArray.single().jsonObject.getValue("marker").jsonPrimitive.content)
    }

    @Test fun lateFailedHandleIsPrioritizedAndAllOmissionsAreExplicitWithin16KiB() {
        val evidence = ChrootVerificationEvidence()
        evidence.attach(nonce(1))
        repeat(100) { index -> evidence.recordHandle(nonce(index + 2), true, ChrootVerificationEvidence.TaskState.EXITED,
            ChrootVerificationEvidence.CleanupState.VERIFIED, null, null) }
        evidence.recordHandle(nonce(999), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, assertion("stdio_ready"))
        evidence.recordHandle(nonce(1000), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, null)
        repeat(30) { evidence.recordFailure(ChrootVerificationEvidence.FailureKind.FAILURE,
            ChrootVerificationEvidence.Case.STDIO, assertion("stdio_ready")) }
        val text = evidence.json()
        assertTrue(text.toByteArray().size <= ChrootVerificationEvidence.MAX_JSON_BYTES)
        val value = Json.parseToJsonElement(text).jsonObject
        val handles = value.getValue("handles").jsonArray
        assertEquals(nonce(999), handles.first().jsonObject.getValue("launchId").jsonPrimitive.content)
        assertEquals(102, value.getValue("launchCount").jsonPrimitive.int)
        assertEquals(100, value.getValue("verifiedCleanupCount").jsonPrimitive.int)
        assertEquals(102 - handles.size, value.getValue("omittedHandleCount").jsonPrimitive.int)
        assertEquals(nonce(1000), handles[1].jsonObject.getValue("launchId").jsonPrimitive.content)
        assertEquals(0, value.getValue("omittedFailedHandleCount").jsonPrimitive.int)
        assertEquals(30 - value.getValue("failures").jsonArray.size, value.getValue("omittedFailureCount").jsonPrimitive.int)
        assertTrue(value.getValue("truncated").jsonPrimitive.boolean)
        val outputProjection = evidence.json(6 * 1024)
        assertTrue(outputProjection.toByteArray().size <= 6 * 1024)
        assertTrue(outputProjection.toByteArray().size + evidence.firstFailureReport().toByteArray().size + 512 <=
            ChrootVerificationEvidence.MAX_REPORT_BYTES)
        assertEquals(0, Json.parseToJsonElement(outputProjection).jsonObject.getValue("omittedFailedHandleCount").jsonPrimitive.int)
    }

    @Test fun deepRepeatedSummariesCannotEvictTheFirstNativeFailureWithinTheOutputBudget() {
        val evidence = ChrootVerificationEvidence()
        repeat(80) { index -> evidence.recordHandle(nonce(index + 1), true, ChrootVerificationEvidence.TaskState.EXITED,
            ChrootVerificationEvidence.CleanupState.VERIFIED, null, null) }
        val primary = assertion("stdio_ready")
        repeat(3) {
            val cleanup = IOException("private-cleanup")
            repeat(3) { cleanup.addSuppressed(IOException("private-nested")) }
            primary.addSuppressed(cleanup)
        }
        repeat(12) { evidence.recordFailure(ChrootVerificationEvidence.FailureKind.FAILURE,
            ChrootVerificationEvidence.Case.STDIO, primary) }
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        evidence.recordHandle(nonce(999), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, native, primary)
        val text = evidence.json(6 * 1024)
        assertTrue(text.toByteArray().size <= 6 * 1024)
        val value = Json.parseToJsonElement(text).jsonObject
        val handles = value.getValue("handles").jsonArray
        assertEquals(nonce(999), handles.first().jsonObject.getValue("launchId").jsonPrimitive.content)
        val failure = handles.first().jsonObject.getValue("runtimeFailure").jsonObject
        assertTrue(failure.getValue("nativeFailureObserved").jsonPrimitive.boolean)
        assertEquals("cgroup2_required", failure.getValue("nativeStage").jsonPrimitive.content)
        assertEquals(13, failure.getValue("errno").jsonPrimitive.int)
        assertEquals(81 - handles.size, value.getValue("omittedHandleCount").jsonPrimitive.int)
        assertEquals(12 - value.getValue("failures").jsonArray.size, value.getValue("omittedFailureCount").jsonPrimitive.int)
        assertEquals(0, value.getValue("omittedFailedHandleCount").jsonPrimitive.int)
        assertFalse(text.contains("private-"))
    }

    @Test fun infrastructureFlagsAndInvalidIdentifiersCannotPretendToBeVerifiedEvidence() {
        val evidence = ChrootVerificationEvidence()
        assertFalse(evidence.isAttached())
        evidence.flag(ChrootVerificationEvidence.InfrastructureFlag.EVIDENCE_ABSENT)
        evidence.flag(ChrootVerificationEvidence.InfrastructureFlag.EVIDENCE_WRITE_FAILED)
        assertTrue(evidence.hasInfrastructureFailure())
        assertThrows(IllegalArgumentException::class.java) { evidence.attach("/private/run") }
        assertThrows(IllegalArgumentException::class.java) { evidence.recordSummary("secret", 0, 0, false, false) }
        assertThrows(IllegalArgumentException::class.java) { evidence.recordSummary(null, 1, 2, true, true) }
        val value = Json.parseToJsonElement(evidence.json()).jsonObject
        assertEquals(JsonNull, value["runId"])
        assertFalse(value.getValue("cleanupVerified").jsonPrimitive.boolean)
        assertFalse(value.getValue("rootfsRemoved").jsonPrimitive.boolean)
        assertEquals(2, value.getValue("infrastructureFlags").jsonArray.size)
        assertEquals(ChrootVerificationEvidence.Case.UNKNOWN, ChrootVerificationEvidence.Case.fromMethod("private-method"))
        assertFalse(value.toString().contains("private"))
    }

    @Test fun fixedSingleStartupModeOnlySelectsTheExistingEofMethodWithoutDns() {
        val mode = ChrootVerificationMode.fromArguments(mapOf(
            "class" to "com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest",
            "mode" to "startup-eof", "confirmedScope" to "chroot-startup-eof-v1"
        ))
        assertEquals(ChrootVerificationMode.STARTUP_EOF, mode)
        assertEquals("b_immediateStdinCloseIsDeliveredAfterGo", mode.method)
        assertEquals(1, mode.expectedCount)
        assertFalse(mode.dnsQueryIncluded)
        assertTrue(mode.completeCounts(1, 1, 1))
        assertFalse(mode.completeCounts(6, 6, 6))
        assertFalse(mode.completeCounts(1, 1, 0))
        assertFalse(mode.completeCounts(0, 1, 1))
    }

    @Test fun completeSuiteRemainsSixTestsAndDoesNotAcceptSingleTestCounts() {
        val arguments = mapOf(
            "class" to "com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest",
            "confirmedScope" to "chroot-production-v1"
        )
        for (input in listOf(arguments, arguments + ("mode" to "full"))) {
            val mode = ChrootVerificationMode.fromArguments(input)
            assertEquals(ChrootVerificationMode.FULL, mode)
            assertEquals(null, mode.method)
            assertEquals(6, mode.expectedCount)
            assertTrue(mode.dnsQueryIncluded)
            assertTrue(mode.completeCounts(6, 6, 6))
            assertFalse(mode.completeCounts(1, 1, 1))
            assertFalse(mode.completeCounts(6, 6, 5))
        }
    }

    @Test fun verificationModesRejectOtherScopesClassesMethodsAndExecutionArguments() {
        val arguments = mapOf(
            "class" to "com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest",
            "mode" to "startup-eof", "confirmedScope" to "chroot-startup-eof-v1"
        )
        val cases = listOf(
            arguments - "class", arguments - "confirmedScope", arguments - "mode",
            arguments + ("class" to "com.aicode.other.Test"),
            arguments + ("class" to "com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest#b_immediateStdinCloseIsDeliveredAfterGo"),
            arguments + ("confirmedScope" to "chroot-production-v1"),
            arguments + ("mode" to "full"), arguments + ("mode" to "startup-eof "),
            arguments + ("mode" to "unknown"), arguments + ("method" to "a_stdioIdentityReadOnlyDnsAndGuestExit37"),
            arguments + ("command" to "private-command"), arguments + ("instance" to "old-instance"),
            arguments + ("argv" to "private-argument")
        )
        cases.forEach { input -> assertThrows(IllegalArgumentException::class.java) {
            ChrootVerificationMode.fromArguments(input)
        } }
    }

    @Test fun startupEvidenceCannotDeclareOrMarkDnsVerification() {
        val evidence = ChrootVerificationEvidence(ChrootVerificationMode.STARTUP_EOF.dnsQueryIncluded)
        val before = Json.parseToJsonElement(evidence.json()).jsonObject.getValue("dns").jsonObject
        assertFalse(before.getValue("queryIncluded").jsonPrimitive.boolean)
        assertFalse(before.getValue("verified").jsonPrimitive.boolean)
        assertEquals("UNOBSERVED", before.getValue("state").jsonPrimitive.content)
        assertThrows(IllegalStateException::class.java) { evidence.markDnsVerified() }
        assertFalse(evidence.isDnsVerified())
    }

    @Test fun transportStderrFirstCauseAndIndependentValidatedNativeFailureAreBothReported() {
        val evidence = ChrootVerificationEvidence()
        val first = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, first, assertion("early_eof_ready"))
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, native, null, nativeFailure = native)

        val handle = Json.parseToJsonElement(evidence.json()).jsonObject.getValue("handles").jsonArray.single().jsonObject
        val runtime = handle.getValue("runtimeFailure").jsonObject
        assertEquals("TRANSPORT", runtime.getValue("kind").jsonPrimitive.content)
        assertEquals("HELPER_STDERR", runtime.getValue("stage").jsonPrimitive.content)
        assertFalse(runtime.getValue("nativeFailureObserved").jsonPrimitive.boolean)
        val observed = handle.getValue("nativeFailure").jsonObject
        assertEquals(setOf("kind", "stage", "nativeFailureObserved", "nativeCode", "nativeStage", "errno"), observed.keys)
        assertEquals("NATIVE_ERROR", observed.getValue("kind").jsonPrimitive.content)
        assertTrue(observed.getValue("nativeFailureObserved").jsonPrimitive.boolean)
        assertEquals("prepare_failed", observed.getValue("nativeCode").jsonPrimitive.content)
        assertEquals("cgroup2_required", observed.getValue("nativeStage").jsonPrimitive.content)
        assertEquals(13, observed.getValue("errno").jsonPrimitive.int)
        assertEquals("early_eof_ready", handle.getValue("primaryFailure").jsonObject.getValue("marker").jsonPrimitive.content)
    }

    @Test fun lateNativeSnapshotCannotReplacePrimaryOrPromoteGoCleanupAndDnsEvidence() {
        val evidence = ChrootVerificationEvidence(ChrootVerificationMode.STARTUP_EOF.dnsQueryIncluded)
        val primary = assertion("early_eof_ready")
        val first = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        val later = ChrootRuntimeFailure.nativeError("code=runtime_failed;stage=supervision;errno=5".toByteArray())
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, first, primary)
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, assertion("final_handle_cleanup"), nativeFailure = native)
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, later, null, nativeFailure = later)
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, null)

        val value = Json.parseToJsonElement(evidence.json()).jsonObject
        val handle = value.getValue("handles").jsonArray.single().jsonObject
        assertEquals("UNOBSERVED", handle.getValue("goObservation").jsonPrimitive.content)
        assertEquals("UNKNOWN", handle.getValue("task").jsonPrimitive.content)
        assertEquals("UNKNOWN", handle.getValue("cleanup").jsonPrimitive.content)
        assertEquals("early_eof_ready", handle.getValue("primaryFailure").jsonObject.getValue("marker").jsonPrimitive.content)
        assertEquals("TRANSPORT", handle.getValue("runtimeFailure").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(13, handle.getValue("nativeFailure").jsonObject.getValue("errno").jsonPrimitive.int)
        assertEquals("cgroup2_required", handle.getValue("nativeFailure").jsonObject.getValue("nativeStage").jsonPrimitive.content)
        assertEquals(0, value.getValue("verifiedCleanupCount").jsonPrimitive.int)
        assertFalse(value.getValue("cleanupVerified").jsonPrimitive.boolean)
        assertFalse(value.getValue("rootfsRemoved").jsonPrimitive.boolean)
        val dns = value.getValue("dns").jsonObject
        assertFalse(dns.getValue("queryIncluded").jsonPrimitive.boolean)
        assertFalse(dns.getValue("verified").jsonPrimitive.boolean)
        assertEquals("UNOBSERVED", dns.getValue("state").jsonPrimitive.content)
    }

    @Test fun nonNativeAndInvalidSupplementalFailuresAreNullAndDoNotBlockTheFirstValidNativeEvidence() {
        val evidence = ChrootVerificationEvidence()
        val local = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        val invalid = ChrootRuntimeFailure.nativeError("secret-token=private;argv=private;socket=private".toByteArray())
        for ((index, candidate) in listOf(local, invalid).withIndex()) {
            evidence.recordHandle(nonce(index + 1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
                ChrootVerificationEvidence.CleanupState.UNOBSERVED, null, null, nativeFailure = candidate)
        }
        val before = evidence.json()
        val handles = Json.parseToJsonElement(before).jsonObject.getValue("handles").jsonArray
        assertTrue(handles.all { it.jsonObject.getValue("nativeFailure") == JsonNull })
        for (secret in listOf("secret-token", "private", "argv", "socket")) assertFalse(before.contains(secret))

        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        evidence.recordHandle(nonce(1), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNOBSERVED, null, null, nativeFailure = native)
        val handle = Json.parseToJsonElement(evidence.json()).jsonObject.getValue("handles").jsonArray.first().jsonObject
        assertEquals(nonce(1), handle.getValue("launchId").jsonPrimitive.content)
        assertEquals(JsonNull, handle.getValue("runtimeFailure"))
        assertTrue(handle.getValue("nativeFailure").jsonObject.getValue("nativeFailureObserved").jsonPrimitive.boolean)
        assertEquals("UNOBSERVED", handle.getValue("goObservation").jsonPrimitive.content)
    }

    @Test fun supplementalNativeFailureIsPrioritizedAcrossBothBudgetsAndAfterTheRecordCap() {
        val evidence = ChrootVerificationEvidence(ChrootVerificationMode.STARTUP_EOF.dnsQueryIncluded)
        val first = ChrootRuntimeFailure.local(ChrootRuntimeFailureKind.TRANSPORT, ChrootRuntimeFailureStage.HELPER_STDERR)
        repeat(80) { index -> evidence.recordHandle(nonce(index + 1), true, ChrootVerificationEvidence.TaskState.EXITED,
            ChrootVerificationEvidence.CleanupState.VERIFIED, null, null) }
        repeat(70) { index -> evidence.recordHandle(nonce(index + 100), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, first, null) }
        val primary = assertion("early_eof_ready")
        repeat(3) {
            val cleanup = IOException("private-cleanup")
            repeat(3) { cleanup.addSuppressed(IOException("private-nested")) }
            primary.addSuppressed(cleanup)
        }
        repeat(12) { evidence.recordFailure(ChrootVerificationEvidence.FailureKind.FAILURE,
            ChrootVerificationEvidence.Case.EARLY_EOF, primary) }
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        evidence.recordHandle(nonce(999), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNOBSERVED, null, null, nativeFailure = native)

        for (budget in listOf(6 * 1024, ChrootVerificationEvidence.MAX_JSON_BYTES)) {
            val text = evidence.json(budget)
            assertTrue(text.toByteArray().size <= budget)
            val value = Json.parseToJsonElement(text).jsonObject
            val handles = value.getValue("handles").jsonArray
            val handle = handles.first().jsonObject
            assertEquals(nonce(999), handle.getValue("launchId").jsonPrimitive.content)
            assertEquals(JsonNull, handle.getValue("runtimeFailure"))
            val observed = handle.getValue("nativeFailure").jsonObject
            assertTrue(observed.getValue("nativeFailureObserved").jsonPrimitive.boolean)
            assertEquals("cgroup2_required", observed.getValue("nativeStage").jsonPrimitive.content)
            assertEquals(13, observed.getValue("errno").jsonPrimitive.int)
            assertEquals("UNOBSERVED", handle.getValue("goObservation").jsonPrimitive.content)
            assertEquals("UNKNOWN", handle.getValue("task").jsonPrimitive.content)
            assertEquals("UNOBSERVED", handle.getValue("cleanup").jsonPrimitive.content)
            assertEquals(151, value.getValue("launchCount").jsonPrimitive.int)
            assertEquals(80, value.getValue("verifiedCleanupCount").jsonPrimitive.int)
            assertEquals(151 - handles.size, value.getValue("omittedHandleCount").jsonPrimitive.int)
            assertEquals(71 - handles.count { row ->
                row.jsonObject.getValue("runtimeFailure") != JsonNull || row.jsonObject.getValue("nativeFailure") != JsonNull
            }, value.getValue("omittedFailedHandleCount").jsonPrimitive.int)
            assertEquals(12 - value.getValue("failures").jsonArray.size, value.getValue("omittedFailureCount").jsonPrimitive.int)
            assertTrue(value.getValue("truncated").jsonPrimitive.boolean)
            assertFalse(value.getValue("cleanupVerified").jsonPrimitive.boolean)
            assertFalse(value.getValue("dns").jsonObject.getValue("verified").jsonPrimitive.boolean)
            assertFalse(text.contains("private-"))
        }
    }

    @Test fun foregroundFlagsAreStickyInfrastructureFailuresEvenWithVerifiedCleanupAndDns() {
        for (flag in listOf(ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_NOT_READY,
            ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_LOST)) {
            val evidence = ChrootVerificationEvidence()
            evidence.attach(nonce(1))
            evidence.recordSummary(nonce(2), 1, 1, true, true)
            evidence.markDnsVerified()
            assertFalse(evidence.hasInfrastructureFailure())
            evidence.flag(flag)
            evidence.flag(flag)
            evidence.recordSummary(nonce(2), 1, 1, true, true)
            assertTrue(evidence.hasInfrastructureFailure())
            val value = Json.parseToJsonElement(evidence.json()).jsonObject
            assertTrue(value.getValue("cleanupVerified").jsonPrimitive.boolean)
            assertTrue(value.getValue("rootfsRemoved").jsonPrimitive.boolean)
            assertTrue(value.getValue("dns").jsonObject.getValue("verified").jsonPrimitive.boolean)
            assertEquals(listOf(flag.name), value.getValue("infrastructureFlags").jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun foregroundFlagsAndFixedDenialKeepTheFirstFailureNativeCountsAndBothJsonBudgetsSafe() {
        val evidence = ChrootVerificationEvidence()
        evidence.attach(nonce(1))
        repeat(100) { index -> evidence.recordHandle(nonce(index + 2), true, ChrootVerificationEvidence.TaskState.EXITED,
            ChrootVerificationEvidence.CleanupState.VERIFIED, null, null) }
        val primary = assertion("stdio_ready")
        primary.addSuppressed(IOException("private-original-cleanup"))
        evidence.recordFailure(ChrootVerificationEvidence.FailureKind.FAILURE, ChrootVerificationEvidence.Case.STDIO, primary)
        val firstReport = evidence.firstFailureReport()
        val denial = assertion("verification_foreground_required")
        denial.stackTrace = arrayOf(StackTraceElement("com.aicode.testing.ChrootVerificationForeground",
            "requireUsable", "ChrootVerificationForeground.kt", 24))
        denial.addSuppressed(IOException("private-foreground-token"))
        evidence.recordFailure(ChrootVerificationEvidence.FailureKind.FAILURE, ChrootVerificationEvidence.Case.PTY, denial)
        val native = ChrootRuntimeFailure.nativeError("code=prepare_failed;stage=cgroup2_required;errno=13".toByteArray())
        evidence.recordHandle(nonce(999), false, ChrootVerificationEvidence.TaskState.UNKNOWN,
            ChrootVerificationEvidence.CleanupState.UNKNOWN, null, denial, nativeFailure = native)
        repeat(20) {
            evidence.flag(ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_NOT_READY)
            evidence.flag(ChrootVerificationEvidence.InfrastructureFlag.FOREGROUND_LOST)
        }
        assertEquals(firstReport, evidence.firstFailureReport())
        assertTrue(evidence.hasInfrastructureFailure())
        for (budget in listOf(6 * 1024, ChrootVerificationEvidence.MAX_JSON_BYTES)) {
            val text = evidence.json(budget)
            assertTrue(text.toByteArray().size <= budget)
            val value = Json.parseToJsonElement(text).jsonObject
            assertEquals(listOf("FOREGROUND_NOT_READY", "FOREGROUND_LOST"),
                value.getValue("infrastructureFlags").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(101, value.getValue("launchCount").jsonPrimitive.int)
            assertEquals(100, value.getValue("verifiedCleanupCount").jsonPrimitive.int)
            val handles = value.getValue("handles").jsonArray
            assertEquals(101 - handles.size, value.getValue("omittedHandleCount").jsonPrimitive.int)
            assertEquals(0, value.getValue("omittedFailedHandleCount").jsonPrimitive.int)
            val handle = handles.first().jsonObject
            assertEquals(nonce(999), handle.getValue("launchId").jsonPrimitive.content)
            assertEquals("cgroup2_required", handle.getValue("nativeFailure").jsonObject.getValue("nativeStage").jsonPrimitive.content)
            val projectedDenial = handle.getValue("primaryFailure").jsonObject
            assertEquals("verification_foreground_required", projectedDenial.getValue("marker").jsonPrimitive.content)
            assertEquals("ChrootVerificationForeground.kt:24", projectedDenial.getValue("location").jsonPrimitive.content)
            assertEquals("stdio_ready", value.getValue("failures").jsonArray.first().jsonObject
                .getValue("failure").jsonObject.getValue("marker").jsonPrimitive.content)
            assertFalse(text.contains("private-"))
        }
    }

    private fun assertion(marker: String): VerificationAssertion = try {
        VerificationAssertion.requireTrue(false, marker)
        throw AssertionError("Expected fixed assertion")
    } catch (failure: VerificationAssertion) { failure }

    private fun nonce(value: Int): String = value.toString(16).padStart(24, '0')
}
