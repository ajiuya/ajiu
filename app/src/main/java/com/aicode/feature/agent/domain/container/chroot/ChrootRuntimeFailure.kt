package com.aicode.feature.agent.domain.container.chroot

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal enum class ChrootRuntimeFailureKind {
    NATIVE_ERROR, INVALID_NATIVE_ERROR, STARTUP, PROTOCOL, TRANSPORT, TIMEOUT, CLEANUP, CANCELLED
}

internal enum class ChrootRuntimeFailureStage {
    HELPER_CAPTURE, INTENT_JOURNAL, CONTROL_LISTENER, PROCESS_START, STARTING_JOURNAL,
    LAUNCH_WRITE, CONTROL_HANDSHAKE, PREPARED_READY, GO, RUNNING_JOURNAL, INPUT_WRITE,
    CONTROL_WRITE, NATIVE_ERROR, FRAME_READ, FRAME_SEQUENCE, READY_TOKEN, OUTPUT_BUFFER,
    HELPER_STDOUT_EOF, HELPER_STDERR, HELPER_STDERR_READ, START_DEADLINE, STOP_DEADLINE,
    CLEANUP_EVIDENCE, CLEANUP_JOURNAL, HELPER_EXIT_STATUS
}

internal data class ChrootRuntimeFailure private constructor(
    val kind: ChrootRuntimeFailureKind,
    val stage: ChrootRuntimeFailureStage,
    val nativeCode: String? = null,
    val nativeStage: String? = null,
    val errno: Int? = null,
    val journalFailure: ChrootJournalFailure? = null
) {
    val nativeFailureObserved: Boolean get() = nativeCode != null && nativeStage != null && errno != null

    init {
        require(journalFailure?.errno == null || journalFailure.errno in 1..4095)
        if (kind == ChrootRuntimeFailureKind.NATIVE_ERROR) {
            require(stage == ChrootRuntimeFailureStage.NATIVE_ERROR && nativeCode in nativeCodes &&
                nativeStage in nativeStages && errno != null && errno in 1..4095)
        } else {
            require(nativeCode == null && nativeStage == null && errno == null)
        }
    }

    companion object {
        fun local(
            kind: ChrootRuntimeFailureKind,
            stage: ChrootRuntimeFailureStage,
            journal: ChrootJournalException? = null
        ): ChrootRuntimeFailure {
            require(kind != ChrootRuntimeFailureKind.NATIVE_ERROR)
            val journalFailure = journal?.let { failure ->
                ChrootJournalFailure(failure.reason, failure.systemErrno?.takeIf { it in 1..4095 })
            }
            return ChrootRuntimeFailure(kind, stage, journalFailure = journalFailure)
        }

        fun nativeError(body: ByteArray): ChrootRuntimeFailure {
            val invalid = ChrootRuntimeFailure(ChrootRuntimeFailureKind.INVALID_NATIVE_ERROR, ChrootRuntimeFailureStage.NATIVE_ERROR)
            if (body.size !in 1..1024 || body.any { it == 0.toByte() }) return invalid
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString()
            } catch (_: Exception) {
                return invalid
            }
            val fields = nativeFormat.matchEntire(text)?.groupValues ?: return invalid
            val code = fields[1]
            val stage = fields[2]
            val errno = fields[3].toIntOrNull() ?: return invalid
            if (code !in nativeCodes || stage !in nativeStages || errno !in 1..4095) return invalid
            return ChrootRuntimeFailure(ChrootRuntimeFailureKind.NATIVE_ERROR, ChrootRuntimeFailureStage.NATIVE_ERROR, code, stage, errno)
        }

        fun nativeStderr(bytes: ByteArray): ChrootRuntimeFailure? {
            if (bytes.size !in (stderrPrefix.size + 2)..255 || bytes.last() != '\n'.code.toByte() ||
                bytes.indices.any { bytes[it] == '\r'.code.toByte() || (bytes[it] == '\n'.code.toByte() && it != bytes.lastIndex) } ||
                stderrPrefix.indices.any { bytes[it] != stderrPrefix[it] }
            ) return null
            return nativeError(bytes.copyOfRange(stderrPrefix.size, bytes.lastIndex)).takeIf {
                it.kind == ChrootRuntimeFailureKind.NATIVE_ERROR && it.nativeFailureObserved
            }
        }

        private val stderrPrefix = "ajiu_runtime: ".toByteArray(Charsets.US_ASCII)
        private val nativeFormat = Regex("code=([a-z_]+);stage=([a-z_0-9]+);errno=([1-9][0-9]{0,3})")
        private val nativeCodes = setOf(
            "allocation_failed", "capability_rejected", "cleanup_unknown", "descriptor_rejected",
            "launch_rejected", "prepare_failed", "runtime_failed"
        )
        private val nativeStages = setOf(
            "aarch64", "anchor_close", "anchor_known", "anchor_mount", "anchor_mount_id", "anchor_open",
            "anchor_stat", "anchor_unchanged", "apk_executable_path", "apk_install_prefix", "apk_native_library", "apk_package_directory",
            "apk_package_name", "apk_static_identity", "app_control_authentication", "architecture", "bootstrap_identity", "bootstrap_join_group",
            "bootstrap_join_identity", "capabilities", "capability_last", "capability_range", "cgroup2", "cgroup2_required",
            "cgroup_domain", "cgroup_filesystem", "cgroup_parent", "cgroup_preflight", "cgroup_root_control", "cgroup_root_stable",
            "cgroup_type", "cleanup_cgroup_identity", "cleanup_cgroup_parent", "cleanup_empty_group", "cleanup_identity", "clear_capabilities",
            "clone_readonly_root", "clone_writable_root", "control_channel", "control_state", "drop_ambient", "drop_bounding", "drop_dumpable",
            "drop_gid", "drop_groups", "drop_keepcaps", "drop_securebits", "drop_uid", "entry",
            "exclusive_cgroup", "exclusive_view", "exclusive_view_owner", "file_worker_resource", "frozen_payload", "frozen_source",
            "group_mode", "group_owner", "guest_nproc_hard_limit", "guest_nproc_limit", "host_environment", "inherited_fds",
            "initial_empty", "initial_members", "installed_layout", "installed_mount_identity", "kill_unique_cgroup", "launch_buffer",
            "legacy_readonly_instance",
            "mounted_view", "namespace_cgroup_identity", "namespace_cgroup_root", "namespace_rootfs_identity", "new_mount_namespace", "no_new_privileges",
            "parent_alive", "parent_death", "private_mounts", "private_namespace", "private_tmpfs", "protected_native_file",
            "pty_initial_size", "pty_input_speed", "pty_output_speed", "pty_slave", "pty_slave_identity", "pty_slave_mode",
            "pty_slave_stat", "pty_slave_uid", "pty_termios", "pty_unlock", "readonly_executable_os", "readonly_os_flags",
            "writable_executable_os", "writable_os_flags",
            "redrop_dumpable", "remove_empty_cgroup", "remove_empty_view", "required_capabilities", "resource_proof", "role_namespace",
            "role_namespace_stable", "root_dumpable", "root_identity", "rootfs_instance", "rootfs_parent", "safe_group",
            "single_bootstrap", "source_channel", "source_fd_identity", "source_package_identity", "source_package_prefix", "static_native_elf",
            "subreaper", "supervision", "unmount_private_view", "unmounted_view_identity", "unshare_mount", "verify_bounding_ambient",
            "verify_caps", "verify_gids", "verify_group_count", "verify_groups", "verify_guest_nproc", "verify_ids",
            "verify_nnp", "verify_uids", "verify_zero_caps", "view_layout", "view_unmount_name", "watchdog_namespace"
        )
    }
}
