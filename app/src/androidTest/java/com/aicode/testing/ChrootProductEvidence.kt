package com.aicode.testing

import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.aicode.feature.agent.domain.container.chroot.ChrootCleanupResult
import com.aicode.feature.agent.domain.container.chroot.ChrootRecoveryEntry
import com.aicode.feature.agent.domain.container.chroot.ChrootRecoveryPhase
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.SecureRandom
import java.util.concurrent.CancellationException

internal enum class ChrootProductCase {
    A_CREATE, B_AUTHORIZATION, C_STDIO, D_PTY, E_FILES, F_DISCONNECT_REMOVE
}

internal class ChrootProductAssertion(val stage: String, val code: String = "ASSERTION") : AssertionError(stage) {
    init {
        require(stage.matches(Regex("[a-z][a-z0-9_]{0,79}")))
        require(code in setOf("ASSERTION", "CANCELLED", "IO", "SECURITY", "TIMEOUT", "UNEXPECTED", "BLOCKED"))
    }
}

internal fun productRequire(value: Boolean, stage: String) {
    if (!value) throw ChrootProductAssertion(stage)
}

internal class ChrootProductEvidence(
    private val runId: String,
    private val directory: File,
    private val trace: ChrootProductTrace
) {
    private val cases = linkedMapOf<ChrootProductCase, String>()
    private val checks = linkedMapOf<String, Boolean>()
    private val counts = linkedMapOf<String, Long>()
    private val failures = mutableListOf<Pair<String, String>>()
    private val tasks = linkedMapOf<String, Pair<LocalRuntimeTaskResult?, LocalRuntimeCleanup?>>()
    private var uid: Int? = null
    private var gid: Int? = null
    private var groups: List<Int> = emptyList()
    private var directoryIdentity: Pair<Long, Long>? = null
    private var evidenceIdentity: Pair<Long, Long>? = null
    private var writeFailed = false

    init {
        productRequire(runId.matches(Regex("[0-9a-f]{24}")), "evidence_run_id")
    }

    @Synchronized fun attach() {
        productRequire(directoryIdentity == null, "evidence_attach_once")
        val stat = Os.lstat(directory.path)
        productRequire(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
            stat.st_mode and 511 == 448, "evidence_private_directory")
        directoryIdentity = stat.st_dev to stat.st_ino
        persist()
    }

    @Synchronized fun check(name: String, value: Boolean) {
        requireKey(name)
        checks[name] = value
    }

    @Synchronized fun count(name: String, value: Long) {
        requireKey(name)
        productRequire(value >= 0, "evidence_nonnegative_count")
        counts[name] = value
    }

    @Synchronized fun identity(uid: Int, gid: Int, groups: List<Int>) {
        productRequire(uid >= 10_000 && gid == uid && groups.size <= 32 && groups.all { it >= 0 }, "evidence_app_identity")
        this.uid = uid
        this.gid = gid
        this.groups = groups.sorted().toList()
    }

    @Synchronized fun caseStarted(case: ChrootProductCase) {
        productRequire(cases[case] == null, "case_no_rerun")
        cases[case] = "STARTED"
        trace.event(ChrootProductTrace.Kind.CASE_START)
    }

    @Synchronized fun casePassed(case: ChrootProductCase) {
        cases[case] = "PASSED"
        trace.event(ChrootProductTrace.Kind.CASE_PASS)
    }

    @Synchronized fun caseFailed(case: ChrootProductCase, failure: Throwable, blocked: Boolean = false) {
        cases[case] = if (blocked) "BLOCKED" else "FAILED"
        this.failure("case_${case.name.lowercase()}", failure)
        trace.event(ChrootProductTrace.Kind.CASE_FAIL)
    }

    @Synchronized fun failure(stage: String, failure: Throwable) {
        requireKey(stage)
        val safeStage = (failure as? ChrootProductAssertion)?.stage ?: stage
        val code = when (failure) {
            is ChrootProductAssertion -> failure.code
            is kotlinx.coroutines.TimeoutCancellationException -> "TIMEOUT"
            is CancellationException -> "CANCELLED"
            is SecurityException -> "SECURITY"
            is IOException -> "IO"
            else -> "UNEXPECTED"
        }
        trace.failure(ChrootProductTrace.FailureCode.valueOf(code))
        if (failures.size < 16) failures += safeStage to code
    }

    @Synchronized fun task(launchId: String, task: LocalRuntimeTaskResult?, cleanup: LocalRuntimeCleanup?) {
        productRequire(launchId.matches(Regex("[0-9a-f]{24}")), "evidence_launch_id")
        productRequire(launchId in tasks || tasks.size < 24, "evidence_task_bound")
        tasks[launchId] = task to cleanup
    }

    @Synchronized fun journal(entries: List<ChrootRecoveryEntry>) {
        counts["launchCount"] = entries.size.toLong()
        counts["verifiedCleanupCount"] = entries.count { it.cleanup == ChrootCleanupResult.VERIFIED }.toLong()
        counts["unknownCleanupCount"] = entries.count { it.cleanup == ChrootCleanupResult.UNKNOWN || it.phase == ChrootRecoveryPhase.UNKNOWN }.toLong()
        counts["unobservedCleanupCount"] = entries.count { it.cleanup == null }.toLong()
        counts["closedJournalCount"] = entries.count { it.phase == ChrootRecoveryPhase.CLOSED }.toLong()
        checks["allJournalClosedVerified"] = entries.all {
            it.phase == ChrootRecoveryPhase.CLOSED && it.cleanup == ChrootCleanupResult.VERIFIED
        }
    }

    @Synchronized fun isSuccessful(): Boolean = !writeFailed && failures.isEmpty() &&
        ChrootProductCase.entries.all { cases[it] == "PASSED" } &&
        REQUIRED_CHECKS.all { checks[it] == true } && counts["bootstrapCount"] == 2L &&
        counts["explicitUserBootstrapCount"] == 2L && counts["verifiedBrokerShutdownCount"] == 2L &&
        counts["unknownCleanupCount"] == 0L && counts["unobservedCleanupCount"] == 0L &&
        counts["launchCount"] == counts["verifiedCleanupCount"] &&
        counts["launchCount"] == counts["closedJournalCount"] &&
        counts["runtimeWrapperCount"] == counts["launchCount"] &&
        counts["wrapperCount"] == counts["realWrapperExitZeroCount"]

    @Synchronized fun json(): String {
        val result = JSONObject().apply {
            put("schema", "chroot-product-offline-v1")
            put("runId", runId)
            put("successful", isSuccessful())
            put("rootSettingsChanged", false)
            put("fixedDnsQueryIncluded", false)
            put("appArtifactsRetained", true)
            put("evidenceWriteFailed", writeFailed)
            put("bootstrapMaximum", 2)
            put("trace", JSONObject(trace.snapshot().json()))
            put("cases", JSONObject().also { value -> ChrootProductCase.entries.forEach { value.put(it.name, cases[it] ?: "NOT_RUN") } })
            put("checks", JSONObject().also { value -> checks.forEach { (key, observed) -> value.put(key, observed) } })
            put("counts", JSONObject().also { value -> counts.forEach { (key, observed) -> value.put(key, observed) } })
            put("appIdentity", JSONObject().apply {
                put("uid", uid ?: JSONObject.NULL)
                put("gid", gid ?: JSONObject.NULL)
                put("groups", JSONArray(groups))
            })
            put("publicTasks", JSONArray().also { array -> tasks.forEach { (id, observation) ->
                array.put(JSONObject().apply {
                    put("launchId", id)
                    val task = observation.first
                    put("task", when (task) {
                        null -> "UNOBSERVED"
                        LocalRuntimeTaskResult.Unknown -> "UNKNOWN"
                        is LocalRuntimeTaskResult.Exited -> "EXITED"
                    })
                    if (task is LocalRuntimeTaskResult.Exited) {
                        put("code", task.code ?: JSONObject.NULL)
                        put("signal", task.signal ?: JSONObject.NULL)
                    }
                    put("cleanup", observation.second?.name ?: "UNOBSERVED")
                })
            } })
            put("failures", JSONArray().also { array -> failures.forEach { (stage, code) ->
                array.put(JSONObject().put("stage", stage).put("code", code))
            } })
        }.toString()
        productRequire(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "evidence_json_bound")
        return result
    }

    @Synchronized fun persist() {
        try {
            val expected = directoryIdentity ?: throw ChrootProductAssertion("evidence_not_attached")
            fun checkDirectory() {
                val stat = Os.lstat(directory.path)
                productRequire(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
                    stat.st_mode and 511 == 448 && (stat.st_dev to stat.st_ino) == expected, "evidence_directory_identity")
            }
            checkDirectory()
            val bytes = json().toByteArray(Charsets.UTF_8)
            val suffix = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val staged = File(directory, ".evidence-$suffix.part")
            val fd = Os.open(staged.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
            val owned: Pair<Long, Long>
            try {
                val stat = Os.fstat(fd)
                productRequire(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                    stat.st_nlink == 1L && stat.st_mode and 511 == 384, "evidence_file_identity")
                owned = stat.st_dev to stat.st_ino
                var at = 0
                while (at < bytes.size) {
                    val count = Os.write(fd, bytes, at, bytes.size - at)
                    productRequire(count > 0, "evidence_write_progress")
                    at += count
                }
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
            val target = File(directory, "evidence.json")
            val previous = evidenceIdentity
            if (previous == null) productRequire(!Files.exists(target.toPath(), NOFOLLOW_LINKS), "evidence_fresh_file")
            else {
                val stat = Os.lstat(target.path)
                productRequire(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                    stat.st_nlink == 1L && stat.st_mode and 511 == 384 &&
                    (stat.st_dev to stat.st_ino) == previous, "evidence_previous_identity")
            }
            checkDirectory()
            Os.rename(staged.path, target.path)
            evidenceIdentity = owned
            val parent = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try { Os.fsync(parent) } finally { Os.close(parent) }
        } catch (failure: Throwable) {
            writeFailed = true
            this.failure("evidence_persistence", failure)
            throw ChrootProductAssertion("evidence_persistence", "IO")
        }
    }

    private fun requireKey(value: String) = productRequire(value.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,79}")), "evidence_fixed_key")

    companion object {
        const val MAX_BYTES = 12 * 1024
        private val random = SecureRandom()
        private val REQUIRED_CHECKS = setOf(
            "noSessionStartRejected", "preparedFromUser", "installedApkArtifactsVerified", "rootPeerHandshakeVerified",
            "nativeInstallerVerified", "sourceMountsExactlyThree", "freshProfileState",
            "createPublishedByCas", "pendingWithoutLease", "prepareWithoutBootstrap", "viewModelCancelWithoutActivation",
            "exactOwnerAuthorized", "otherOwnerRejected", "otherFingerprintRejected", "guestUidGroups",
            "guestCapsZero", "guestNoNewPrivs", "guestSystemReadOnly", "guestFixedDnsOffline", "guestHome",
            "stdioExit37", "publicEarliestEof", "ptyResize", "ptyCtrlC", "ptyCtrlD", "fileWorkerStreams",
            "ordinaryFileTransfer", "nonLinkDirectoryTransfer", "appPrivateContentUpload", "noBackgroundSu",
            "idleDisconnectVerified", "oldCaptureRejected", "userReconnected", "revokeOwnerVerified",
            "rootfsRemoved", "profileRemoved", "allJournalClosedVerified", "allRealWrappersExitZero",
            "rootAuthorityUnchanged", "brokerClosed", "bootstrapBudgetMatched", "finished"
        )
    }
}
