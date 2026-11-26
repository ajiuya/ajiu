package com.aicode.testing

import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFailure
import com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeFailureKind
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.IdentityHashMap

internal class VerificationAssertion private constructor(val marker: String) : AssertionError() {
    companion object {
        fun requireTrue(value: Boolean, marker: String) {
            require(marker in MARKERS)
            if (!value) throw VerificationAssertion(marker)
        }

        internal fun isMarker(marker: String): Boolean = marker in MARKERS

        private val MARKERS: Set<String> = setOf(
            "stdio_ready", "stdio_exit", "stdio_stderr", "stdio_markers", "guest_uid_gid", "guest_groups",
            "guest_caps_zero", "guest_no_new_privs", "guest_limits_ro_dns", "early_eof_ready", "early_eof_exit",
            "early_eof_output", "pty_ready", "pty_resize", "pty_exit", "pty_cleanup", "pty_markers", "pty_stderr",
            "file_prep_ready", "file_prep_exit", "file_prep_no_output", "file_worker_ready", "worker_chunk_write",
            "worker_stat", "worker_list", "worker_resolve", "worker_chunk_read", "worker_abort",
            "worker_abort_removes_temporary", "worker_readonly", "worker_proc", "worker_dev", "worker_exit",
            "worker_cleanup", "worker_stderr", "access_dot_duplicate_separator", "access_trailing_separator",
            "access_lexical_control", "access_symlink_parent_semantics", "access_missing_component_must_fail",
            "access_delete_link_nofollow_tail", "access_rename_link_nofollow_tail", "access_follow_tail_directory",
            "access_delete_link_keeps_target", "access_delete_link_removed", "access_rename_link_keeps_target",
            "access_renamed_link_nofollow", "access_renamed_link_reads_target", "access_unlink_renamed_keeps_target",
            "access_write_stream", "access_read_stream", "access_read_limit", "access_copy",
            "worker_guest_release_nonempty", "worker_guest_root_symlink", "worker_guest_root_read",
            "access_forbidden_symlink", "access_proc", "access_dev", "access_readonly", "owners_ready",
            "owners_concurrent", "owner_permit_isolation", "owner_one_exit", "owner_two_exit", "owners_cleanup",
            "owners_output", "owners_stderr", "control_eof_go", "control_eof_cleanup", "revoked_lease_rejected",
            "control_eof_output", "debug_target_required", "arm64_device_required", "arm_asset_required",
            "fixed_asset_required", "fresh_project_required", "fresh_cache_required", "user_enabled_root_required",
            "verification_instance_usable", "verification_source_closed", "verification_root_changed",
            "runtime_cleanup_verified", "final_handle_cleanup", "access_worker_ready", "access_worker_exit",
            "access_worker_cleanup", "access_worker_stderr", "mount_capture_changed", "verification_cleanup_unknown",
            "verification_retained_for_recovery", "bounded_group_list", "real_app_uid_gid", "real_app_groups_line",
            "journal_directory_required", "owned_directory_required", "mount_source_kind", "mount_source_owner",
            "mount_source_ancestor_nofollow", "mount_source_stable", "verification_foreground_required"
        )
    }
}

internal suspend fun <T> withCleanup(
    cleanup: List<suspend () -> Unit>,
    observe: (Throwable?) -> Unit = {},
    body: suspend () -> T
): T {
    var primary: Throwable? = null
    try {
        return body()
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        var reported = primary
        withContext(NonCancellable) {
            for (action in cleanup) {
                try { action() } catch (failure: Throwable) { reported = retainFailure(reported, failure) }
            }
            try { observe(reported) } catch (failure: Throwable) { reported = retainFailure(reported, failure) }
        }
        if (primary == null) reported?.let { throw it }
    }
}

private fun retainFailure(primary: Throwable?, next: Throwable): Throwable {
    if (primary == null) return next
    if (primary !== next) primary.addSuppressed(next)
    return primary
}

internal enum class ChrootVerificationMode(
    val argument: String,
    val confirmedScope: String,
    val expectedCount: Int,
    val method: String?,
    val dnsQueryIncluded: Boolean
) {
    FULL("full", "chroot-production-v1", 6, null, true),
    STARTUP_EOF("startup-eof", "chroot-startup-eof-v1", 1, "b_immediateStdinCloseIsDeliveredAfterGo", false);

    fun completeCounts(run: Int, started: Int, finished: Int): Boolean =
        run == expectedCount && started == expectedCount && finished == expectedCount

    companion object {
        private const val TEST_CLASS = "com.aicode.feature.agent.domain.container.chroot.ChrootRuntimeDeviceTest"

        fun fromArguments(arguments: Map<String, String?>): ChrootVerificationMode {
            require(arguments.keys.all { it in setOf("class", "confirmedScope", "mode") })
            require(arguments["class"] == TEST_CLASS)
            val mode = when (arguments["mode"]) {
                null, "full" -> FULL
                "startup-eof" -> STARTUP_EOF
                else -> throw IllegalArgumentException("Unsupported fixed verification mode")
            }
            require(arguments["confirmedScope"] == mode.confirmedScope)
            return mode
        }
    }
}

internal class ChrootVerificationEvidence(val dnsQueryIncluded: Boolean = true) {
    enum class InfrastructureFlag { EVIDENCE_ABSENT, EVIDENCE_WRITE_FAILED, FIXTURE_UNAVAILABLE, FOREGROUND_NOT_READY, FOREGROUND_LOST }
    enum class TaskState { UNOBSERVED, EXITED, UNKNOWN }
    enum class CleanupState { UNOBSERVED, VERIFIED, UNKNOWN }
    enum class FailureKind { FAILURE, ASSUMPTION, INFRASTRUCTURE }
    enum class Case {
        STDIO, EARLY_EOF, PTY, FILE_WORKER, OWNERS, CONTROL_EOF, BEFORE_CLASS, AFTER_CLASS, UNKNOWN;

        companion object {
            fun fromMethod(method: String?): Case = when (method) {
                "a_stdioIdentityReadOnlyDnsAndGuestExit37" -> STDIO
                "b_immediateStdinCloseIsDeliveredAfterGo" -> EARLY_EOF
                "c_privatePtyResizeCtrlCAndCtrlD" -> PTY
                "d_productionFileWorkerCrudStreamsAndBoundaryRejections" -> FILE_WORKER
                "e_twoIndependentOwnersRunInOneInstance" -> OWNERS
                "f_realControlEofStopsNativeAndRevokedLeaseCannotAuthorize" -> CONTROL_EOF
                "prepareProductionFixture" -> BEFORE_CLASS
                "reclaimVerifiedProductionFixture" -> AFTER_CLASS
                else -> UNKNOWN
            }
        }
    }

    private data class Handle(
        val launchId: String,
        var goObserved: Boolean,
        var task: TaskState,
        var cleanup: CleanupState,
        var runtimeFailure: ChrootRuntimeFailure?,
        var primary: Throwable?,
        var nativeFailure: ChrootRuntimeFailure?
    )

    private var runId: String? = null
    private var instanceId: String? = null
    private var dnsVerified = false
    private var launchCount = 0
    private var verifiedCleanupCount = 0
    private var cleanupVerified = false
    private var rootfsRemoved = false
    private val handles = linkedMapOf<String, Handle>()
    private val failures = mutableListOf<JsonObject>()
    private var failureCount = 0
    private val flags = linkedSetOf<InfrastructureFlag>()

    @Synchronized fun attach(run: String) { require(nonce.matches(run)); check(runId == null || runId == run); runId = run }

    @Synchronized fun recordSummary(instance: String?, launched: Int, verified: Int, cleanup: Boolean, removed: Boolean) {
        require(instance == null || nonce.matches(instance))
        require(launched >= 0 && verified in 0..launched)
        instanceId = instance
        launchCount = launched
        verifiedCleanupCount = verified
        cleanupVerified = cleanup
        rootfsRemoved = removed
    }

    @Synchronized fun markDnsVerified() { check(dnsQueryIncluded); dnsVerified = true }
    @Synchronized fun flag(flag: InfrastructureFlag) { flags += flag }
    @Synchronized fun hasInfrastructureFailure(): Boolean = flags.isNotEmpty()
    @Synchronized fun isAttached(): Boolean = runId != null
    @Synchronized fun isDnsVerified(): Boolean = dnsVerified

    @Synchronized fun recordHandle(
        launchId: String,
        goObserved: Boolean,
        task: TaskState,
        cleanup: CleanupState,
        runtimeFailure: ChrootRuntimeFailure?,
        primary: Throwable?,
        nativeFailure: ChrootRuntimeFailure? = null
    ) {
        require(nonce.matches(launchId))
        val observedNative = nativeFailure?.takeIf {
            it.kind == ChrootRuntimeFailureKind.NATIVE_ERROR && it.nativeFailureObserved
        }
        val current = handles[launchId]
        if (current == null) handles[launchId] = Handle(launchId, goObserved, task, cleanup, runtimeFailure, primary, observedNative) else {
            current.goObserved = current.goObserved || goObserved
            current.task = task
            current.cleanup = cleanup
            if (current.runtimeFailure == null) current.runtimeFailure = runtimeFailure
            if (current.primary == null) current.primary = primary
            if (current.nativeFailure == null) current.nativeFailure = observedNative
        }
        launchCount = handles.size
        verifiedCleanupCount = handles.values.count { it.cleanup == CleanupState.VERIFIED }
    }

    @Synchronized fun recordFailure(kind: FailureKind, case: Case, failure: Throwable) {
        failureCount++
        if (failures.size < 12) failures += buildJsonObject {
            put("kind", kind.name)
            put("case", case.name)
            put("failure", safeFailure(failure))
        }
    }

    @Synchronized fun firstFailureReport(): String {
        val bytes = failures.firstOrNull()?.toString().orEmpty().toByteArray(Charsets.UTF_8)
        return bytes.copyOf(minOf(bytes.size, 1024)).toString(Charsets.UTF_8)
    }

    @Synchronized fun json(maxBytes: Int = MAX_JSON_BYTES): String {
        require(maxBytes in 6 * 1024..MAX_JSON_BYTES)
        var included = handles.values.sortedByDescending {
            when {
                it.nativeFailure != null -> 2
                it.primary != null || it.runtimeFailure != null || it.cleanup == CleanupState.UNKNOWN -> 1
                else -> 0
            }
        }.take(64)
        var includedFailures = failures.toList()
        var compact = false
        fun compactFailure(value: JsonObject): JsonObject = JsonObject(value.filterKeys { it != "cause" && it != "suppressed" } +
            ("truncated" to JsonPrimitive(true)))
        while (true) {
            val value = buildJsonObject {
                put("schemaVersion", 1)
                put("runId", runId?.let(::JsonPrimitive) ?: JsonNull)
                put("instanceId", instanceId?.let(::JsonPrimitive) ?: JsonNull)
                put("launchCount", launchCount)
                put("verifiedCleanupCount", verifiedCleanupCount)
                put("cleanupVerified", cleanupVerified)
                put("rootfsRemoved", rootfsRemoved)
                put("dns", buildJsonObject {
                    put("queryIncluded", dnsQueryIncluded)
                    put("verified", dnsVerified)
                    put("state", if (dnsVerified) "VERIFIED" else "UNOBSERVED")
                })
                put("handles", JsonArray(included.map { handle -> buildJsonObject {
                    put("launchId", handle.launchId)
                    put("goObservation", if (handle.goObserved) "OBSERVED" else "UNOBSERVED")
                    put("task", handle.task.name)
                    put("cleanup", handle.cleanup.name)
                    put("runtimeFailure", handle.runtimeFailure?.let(::runtimeFailure) ?: JsonNull)
                    put("nativeFailure", handle.nativeFailure?.let(::runtimeFailure) ?: JsonNull)
                    put("primaryFailure", handle.primary?.let { primary ->
                        safeFailure(primary).let { if (compact) compactFailure(it) else it }
                    } ?: JsonNull)
                } }))
                put("omittedHandleCount", handles.size - included.size)
                put("omittedFailedHandleCount", handles.values.count { it.primary != null || it.runtimeFailure != null || it.nativeFailure != null || it.cleanup == CleanupState.UNKNOWN } -
                    included.count { it.primary != null || it.runtimeFailure != null || it.nativeFailure != null || it.cleanup == CleanupState.UNKNOWN })
                put("failures", JsonArray(includedFailures.map { failure ->
                    if (!compact) failure else JsonObject(failure + ("failure" to compactFailure(failure.getValue("failure") as JsonObject)))
                }))
                put("failureDetailsTruncated", compact)
                put("omittedFailureCount", failureCount - includedFailures.size)
                put("truncated", compact || included.size != handles.size || includedFailures.size != failureCount)
                put("infrastructureFlags", JsonArray(flags.map { JsonPrimitive(it.name) }))
            }.toString()
            if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
            val normal = included.indexOfLast { it.primary == null && it.runtimeFailure == null && it.nativeFailure == null && it.cleanup != CleanupState.UNKNOWN }
            when {
                normal >= 0 -> included = included.filterIndexed { index, _ -> index != normal }
                includedFailures.size > 1 -> includedFailures = includedFailures.dropLast(1)
                included.size > 1 -> included = included.dropLast(1)
                !compact -> compact = true
                includedFailures.isNotEmpty() -> includedFailures = emptyList()
                else -> error("Bounded verification evidence could not be projected")
            }
        }
    }

    companion object {
        const val MAX_JSON_BYTES = 16 * 1024
        const val MAX_REPORT_BYTES = 8 * 1024
        private val nonce = Regex("[0-9a-f]{24}")
        private val files = setOf(
            "ChrootRuntimeDeviceTest.kt", "ChrootVerificationInstrumentation.kt", "ChrootRuntimeManager.kt",
            "ChrootLaunchHandle.kt", "ChrootRuntimeFailure.kt", "ChrootRuntimeProcessFactory.kt", "ChrootControlChannel.kt",
            "ChrootInstanceInstaller.kt", "ChrootArchiveWriter.kt", "ChrootRecoveryRepository.kt", "ChrootFileAccess.kt",
            "ChrootFileSession.kt", "ChrootFileWorkerClient.kt", "ChrootVerificationForeground.kt"
        )
        private val helpers = setOf("requireTrue", "requireRejected", "requireExit", "safeFailure", "withCleanup")

        fun safeFailure(failure: Throwable): JsonObject {
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var nodes = 0
            fun node(current: Throwable, depth: Int): JsonObject = buildJsonObject {
                put("type", current.javaClass.name.take(192))
                if (!seen.add(current) || ++nodes > 12) { put("truncated", true); return@buildJsonObject }
                if (current is VerificationAssertion && VerificationAssertion.isMarker(current.marker)) put("marker", current.marker)
                current.stackTrace.firstOrNull { frame ->
                    frame.className.startsWith("com.aicode.") && frame.fileName in files && frame.lineNumber in 1..1_000_000 &&
                        helpers.none { frame.methodName == it || frame.methodName == "access\$$it" }
                }?.let { put("location", "${it.fileName}:${it.lineNumber}") }
                if (depth < 2 && nodes < 12) {
                    current.cause?.let { put("cause", node(it, depth + 1)) }
                    put("suppressed", JsonArray(current.suppressed.take(3).map { node(it, depth + 1) }))
                }
                if (current.suppressed.size > 3 || depth == 2 && (current.cause != null || current.suppressed.isNotEmpty())) {
                    put("truncated", true)
                }
            }
            return node(failure, 0)
        }

        fun runtimeFailure(failure: ChrootRuntimeFailure): JsonObject = buildJsonObject {
            put("kind", failure.kind.name)
            put("stage", failure.stage.name)
            val native = if (failure.nativeFailureObserved && failure.kind == ChrootRuntimeFailureKind.NATIVE_ERROR) {
                ChrootRuntimeFailure.nativeError("code=${failure.nativeCode};stage=${failure.nativeStage};errno=${failure.errno}".toByteArray(Charsets.UTF_8))
                    .takeIf { it.nativeFailureObserved }
            } else null
            put("nativeFailureObserved", native != null)
            put("nativeCode", native?.nativeCode?.let(::JsonPrimitive) ?: JsonNull)
            put("nativeStage", native?.nativeStage?.let(::JsonPrimitive) ?: JsonNull)
            put("errno", native?.errno?.let(::JsonPrimitive) ?: JsonNull)
        }

        fun boundedReport(value: String): String {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return if (bytes.size <= MAX_REPORT_BYTES) value else bytes.copyOf(MAX_REPORT_BYTES - 32).toString(Charsets.UTF_8) + "\n[diagnostics truncated]\n"
        }
    }
}
