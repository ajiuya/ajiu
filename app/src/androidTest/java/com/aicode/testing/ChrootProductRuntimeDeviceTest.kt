package com.aicode.testing

import android.system.Os
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import com.aicode.testing.ChrootProductTrace.Kind
import com.aicode.testing.ChrootProductTrace.Phase
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.presentation.ChrootConfirmation
import com.aicode.feature.settings.presentation.ChrootSettingsViewModel
import com.aicode.feature.workspace.domain.CapturedLocalFolderRoot
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.FileTransferService
import com.aicode.feature.workspace.domain.FileUploadInputs
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.LocalFolderSnapshot
import com.aicode.feature.workspace.domain.TransferResult
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.localRootPath
import com.aicode.feature.workspace.domain.readBytesBounded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicBoolean

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChrootProductRuntimeDeviceTest {
    @Test fun a_noSessionAndUserCreate() = runBlocking {
        fixture().runCase(ChrootProductCase.A_CREATE) {
            productRequire(factory.bootstrapCount == 1 && factory.currentSession.value != null &&
                recovery.snapshot().isEmpty() && factory.registeredProcessCount.value == 0, "prepared_without_product_tasks")
            createFromUser()
        }
    }

    @Test fun b_pendingPrepareCancelAndOwnerBinding() = runBlocking {
        fixture().runCase(ChrootProductCase.B_AUTHORIZATION) {
            val installed = checkNotNull(instance)
            val bootstrap = factory.bootstrapCount
            val launches = recovery.snapshot().size
            val cancelOwner = RuntimeOwner(RuntimeOwnerKind.AGENT, "cancel-$runId")
            val cancelCurrent = AtomicBoolean(true)
            val request = pending(cancelOwner) {
                checkCurrent()
                if (!cancelCurrent.get()) throw CancellationException("product_cancel_source_changed")
            }
            productRequire(facade.pendingAuthorizations.value.single() === request &&
                manager.instanceState(installed.id).authorizedOwners == 0, "pending_without_activation")
            evidence.check("pendingWithoutLease", true)
            val prepared = facade.prepareAuthorization(request)
            productRequire(facade.pendingAuthorizations.value.single() === prepared &&
                factory.bootstrapCount == bootstrap && recovery.snapshot().size == launches &&
                manager.instanceState(installed.id).authorizedOwners == 0, "prepare_without_root_bootstrap")
            evidence.check("prepareWithoutBootstrap", true)
            val store = ViewModelStore()
            val viewModel = withContext(Dispatchers.Main) {
                ChrootSettingsViewModel(facade, settings, rootSettings).also {
                    store.put("product", it)
                    it.requestAuthorization(prepared)
                }
            }
            withCleanup(cleanup = listOf({ withContext(Dispatchers.Main) { store.clear() } })) {
                val confirmed = withTimeout(5_000L) {
                    viewModel.uiState.first { it.confirmation is ChrootConfirmation.Authorization && it.workingProfileId == null }
                }
                val pendingConfirmation = (confirmed.confirmation as ChrootConfirmation.Authorization).authorization
                productRequire(facade.pendingAuthorizations.value.any { it === pendingConfirmation }, "view_model_real_pending_token")
                withContext(Dispatchers.Main) {
                    viewModel.cancelConfirmation()
                    viewModel.confirm()
                }
                productRequire(viewModel.uiState.value.confirmation == null && viewModel.uiState.value.workingProfileId == null &&
                    manager.instanceState(installed.id).authorizedOwners == 0 && factory.bootstrapCount == bootstrap &&
                    recovery.snapshot().size == launches, "view_model_cancel_no_activation")
                evidence.check("viewModelCancelWithoutActivation", true)
                cancelCurrent.set(false)
                productRequire(runCatching { facade.prepareAuthorization(pendingConfirmation) }.isFailure,
                    "cancelled_source_request_rejected")
            }
            commands.revokeOwner(cancelOwner)
            val authorized = authorizeFromUser(pending())
            productRequire(authorized.owner == owner && manager.instanceState(installed.id).authorizedOwners == 1 &&
                facade.state(installedProfile()).authorizedOwners == 1 &&
                commands.capture(installedProfile(), settings.profileConfigurationRevision, project.canonicalPath,
                    owner, sourceFingerprint, ::checkCurrent).digest == authorized.digest, "exact_owner_capture_reused")
            evidence.check("exactOwnerAuthorized", true)
            val otherOwner = RuntimeOwner(RuntimeOwnerKind.PLUGIN, owner.scopeId)
            val otherRequest = pending(otherOwner)
            productRequire(otherRequest.owner == otherOwner && otherRequest.digest != authorized.digest &&
                manager.instanceState(installed.id).authorizedOwners == 1, "other_owner_not_authorized")
            evidence.check("otherOwnerRejected", true)
            commands.revokeOwner(otherOwner)
            val otherCurrent = AtomicBoolean(true)
            val otherFingerprint = pending(owner, "$sourceFingerprint-other") {
                checkCurrent()
                if (!otherCurrent.get()) throw CancellationException("product_other_source_changed")
            }
            productRequire(otherFingerprint.sourceFingerprint != sourceFingerprint && otherFingerprint.digest != authorized.digest &&
                manager.instanceState(installed.id).authorizedOwners == 1, "other_fingerprint_not_authorized")
            evidence.check("otherFingerprintRejected", true)
            otherCurrent.set(false)
            productRequire(runCatching { facade.prepareAuthorization(otherFingerprint) }.isFailure,
                "other_fingerprint_pending_revoked")
            productRequire(factory.bootstrapCount == bootstrap && recovery.snapshot().size == launches &&
                facade.pendingAuthorizations.value.isEmpty(), "authorization_no_background_launch")
            authorized.checkCurrent()
        }
    }

    @Test fun c_stdioExit37AndPublicEarliestEof() = runBlocking {
        fixture().runCase(ChrootProductCase.C_STDIO) {
            val script = """
                set -eu
                export LC_ALL=C
                printf 'identity_begin\n'
                /bin/busybox id -u
                /bin/busybox id -g
                /bin/busybox awk '/^Groups:/{print}' /proc/self/status
                /bin/busybox awk '/^Cap(Inh|Prm|Eff|Bnd|Amb):|^NoNewPrivs:/{print}' /proc/self/status
                if /bin/busybox touch /etc/ajiu_product_readonly > /tmp/ro_out 2> /tmp/ro_err; then exit 90; fi
                /bin/busybox grep -qi 'read-only file system' /tmp/ro_err
                printf 'system_ro\n'
                printf 'nameserver 223.5.5.5\nnameserver 223.6.6.6\n' > /tmp/expected_dns
                /bin/busybox cmp -s /etc/resolv.conf /tmp/expected_dns
                printf 'dns_offline\n'
                test "${'$'}HOME" = /root
                test "${'$'}PWD" = /root/workspace
                printf 'home_ok\nidentity_end\n'
                printf 'product_stderr\n' >&2
                exit 37
            """.trimIndent()
            val process = startStdio(listOf("/bin/sh", "-c", script))
            useProcess(process) {
                val output = collect(process)
                requireExit(output.task, 37, "stdio_task_exit_37")
                productRequire(output.stderr.contentEquals("product_stderr\n".toByteArray()), "stdio_exact_stderr")
                val lines = output.stdout.toString(Charsets.US_ASCII).lineSequence().filter(String::isNotEmpty).toList()
                productRequire(lines.size == 14 && lines.first() == "identity_begin" && lines.last() == "identity_end", "stdio_identity_markers")
                productRequire(lines[1] == AndroidIdentity.uid().toString() && lines[2] == AndroidIdentity.uid().toString(), "guest_real_app_uid_gid")
                val groups = parseGroups(lines[3].substringAfter(':'))
                productRequire(groups == AndroidIdentity.groups(), "guest_real_app_groups")
                evidence.check("guestUidGroups", true)
                val status = lines.subList(4, 10).associate { it.substringBefore(':') to it.substringAfter(':').trim() }
                productRequire(listOf("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb").all {
                    status[it]?.matches(Regex("0+")) == true
                }, "guest_all_caps_zero")
                productRequire(status["NoNewPrivs"] == "1", "guest_no_new_privs")
                productRequire(lines[10] == "system_ro" && lines[11] == "dns_offline" && lines[12] == "home_ok", "guest_readonly_dns_home")
                evidence.check("guestCapsZero", true)
                evidence.check("guestNoNewPrivs", true)
                evidence.check("guestSystemReadOnly", true)
                evidence.check("guestFixedDnsOffline", true)
                evidence.check("guestHome", true)
                evidence.check("stdioExit37", true)
            }
            val eof = startStdio(listOf("/bin/sh", "-c", "/bin/busybox cat; printf 'public_eof_ok\\n'; exit 0"))
            useProcess(eof) {
                eof.outputStream.close()
                val output = collect(eof)
                requireExit(output.task, 0, "public_earliest_eof_task")
                productRequire(output.stdout.contentEquals("public_eof_ok\n".toByteArray()) && output.stderr.isEmpty(), "public_earliest_eof_output")
                evidence.check("publicEarliestEof", true)
                evidence.check("publicEofAfterFacadeReady", true)
                evidence.check("preAttachEofCoveredByFixedFdSuite", true)
            }
        }
    }

    @Test fun d_ptyResizeAndCtrlCD() = runBlocking {
        fixture().runCase(ChrootProductCase.D_PTY) {
            val interrupt = """
                set -m
                test "${'$'}(/bin/busybox stty size)" = '24 80' || exit 90
                /bin/busybox stty -echo || exit 91
                printf 'pty_resize_ready\n'
                IFS= read -r resized
                test "${'$'}resized" = resize || exit 92
                test "${'$'}(/bin/busybox stty size)" = '40 100' || exit 93
                printf 'pty_resize_ok\n'
                /bin/busybox sh -c 'trap "echo pty_interrupt_ok; exit 43" INT; printf "pty_interrupt_ready\n"; IFS= read -r line; exit 94'
                test "${'$'}?" = 43 || exit 95
                printf 'pty_ctrl_c_ok\n'
                exit 39
            """.trimIndent()
            val first = startPty(listOf("/bin/sh", "-c", interrupt))
            useProcess(first) {
                val markers = MarkerReader(first.inputStream, listOf("pty_resize_ready", "pty_interrupt_ready"))
                val output = scope.async(Dispatchers.IO) { runInterruptible { markers.read() } }
                val errors = scope.async(Dispatchers.IO) { ChrootProductFixture.readBounded(first.errorStream) }
                withCleanup(cleanup = listOf({ settle(first) }, { output.cancelAndJoin() }, { errors.cancelAndJoin() })) {
                    markers.await("pty_resize_ready")
                    productRequire(first.resize(100, 40), "pty_real_resize")
                    write(first, "resize\n".toByteArray())
                    markers.await("pty_interrupt_ready")
                    write(first, byteArrayOf(3))
                    requireExit(withTimeout(15_000L) { first.awaitTaskExit() }, 39, "pty_ctrl_c_task_exit_39")
                    settle(first)
                    productRequire(output.await().replace("\r\n", "\n") ==
                        "pty_resize_ready\npty_resize_ok\npty_interrupt_ready\npty_interrupt_ok\npty_ctrl_c_ok\n" &&
                        errors.await().isEmpty(), "pty_ctrl_c_exact_output")
                    evidence.check("ptyResize", true)
                    evidence.check("ptyCtrlC", true)
                }
            }
            val second = startPty(listOf("/bin/sh", "-c", "set -eu; /bin/busybox stty -echo; " +
                "printf 'pty_eof_ready\\n'; if IFS= read -r line; then exit 96; fi; printf 'pty_eof_ok\\n'; exit 40"))
            useProcess(second) {
                val markers = MarkerReader(second.inputStream, listOf("pty_eof_ready"))
                val output = scope.async(Dispatchers.IO) { runInterruptible { markers.read() } }
                val errors = scope.async(Dispatchers.IO) { ChrootProductFixture.readBounded(second.errorStream) }
                withCleanup(cleanup = listOf({ settle(second) }, { output.cancelAndJoin() }, { errors.cancelAndJoin() })) {
                    markers.await("pty_eof_ready")
                    write(second, byteArrayOf(4))
                    requireExit(withTimeout(15_000L) { second.awaitTaskExit() }, 40, "pty_ctrl_d_task_exit_40")
                    settle(second)
                    productRequire(output.await().replace("\r\n", "\n") == "pty_eof_ready\npty_eof_ok\n" &&
                        errors.await().isEmpty(), "pty_ctrl_d_exact_output")
                    evidence.check("ptyCtrlD", true)
                }
            }
        }
    }

    @Test fun e_realFileWorkerStreamsAndTransfers() = runBlocking {
        fixture().runCase(ChrootProductCase.E_FILES) {
            val capture = authorizedCapture()
            val before = recovery.snapshot().size
            val access = commands.fileAccess(capture)
            productRequire(access.cacheIdentity() == capture.digest && recovery.snapshot().size == before, "file_access_creation_no_worker")
            val host = File(isolatedContext.filesDir, "transfer-host")
            ChrootProductFixture.createFreshDirectory(host, isolatedContext.filesDir)
            val hostStat = Os.lstat(host.path)
            val grant = LocalFolderGrant("product", "epoch-$runId", "App-private fixture", localRootPath("product", "epoch-$runId"), true)
            fun checkHost() {
                checkCurrent()
                val now = Os.lstat(host.path)
                productRequire(now.st_dev == hostStat.st_dev && now.st_ino == hostStat.st_ino &&
                    now.st_uid == AndroidIdentity.uid() && now.st_mode == hostStat.st_mode, "synthetic_host_fixture_identity")
            }
            val roots = LocalFolderSnapshot(1, listOf(grant), listOf(CapturedLocalFolderRoot(grant, host)),
                { checkHost() }, { checkHost() }, ::checkHost)
            val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, access.cacheIdentity(), access, null, null,
                roots, owner, capture, settings.captureProfileSnapshot(), cancellationJob = currentCoroutineContext()[Job], checkSource = ::checkCurrent)
            val service = FileTransferService(FileBackendRouting(), FileUploadInputs(context), WorkspaceFileLock())
            withContext(Dispatchers.IO + execution) {
                val data = ByteArray(131_079) { (it % 113).toByte() }
                trace.phase(Phase.E_STREAM_WRITE)
                trace.event(Kind.BEGIN)
                access.mkdirs("/root/workspace/streams")
                productRequire(access.writeStream("/root/workspace/streams/source.bin", ByteArrayInputStream(data), false) == data.size.toLong(),
                    "file_worker_stream_write")
                trace.event(Kind.END)
                trace.phase(Phase.E_STREAM_READ)
                trace.event(Kind.BEGIN)
                access.openRead("/root/workspace/streams/source.bin").use {
                    productRequire(it.readBytesBounded(data.size.toLong()).contentEquals(data), "file_worker_stream_read")
                }
                productRequire(runCatching { access.readBytes("/root/workspace/streams/source.bin", 8) }.exceptionOrNull() is FileReadTooLargeException,
                    "file_worker_stream_read_limit")
                access.rename("/root/workspace/streams/source.bin", "/root/workspace/streams/renamed.bin")
                productRequire(access.fileSize("/root/workspace/streams/renamed.bin") == data.size.toLong(), "file_worker_stream_rename")
                evidence.check("fileWorkerStreams", true)
                trace.event(Kind.END)
                trace.phase(Phase.FILE_UPLOAD)
                trace.event(Kind.BEGIN)
                access.mkdirs("/root/workspace/transfers")
                val hostData = ByteArray(4099) { (it % 83).toByte() }
                writeNew(File(host, "source.bin"), hostData)
                val fileTransfer = service.transfer(execution, "${grant.rootPath}/source.bin", "/root/workspace/transfers/file.bin", false, checkScope = ::checkHost)
                productRequire(fileTransfer is TransferResult.Completed &&
                    access.readBytes("/root/workspace/transfers/file.bin", hostData.size.toLong()).contentEquals(hostData), "ordinary_cross_backend_file_transfer")
                trace.event(Kind.END)
                trace.phase(Phase.FILE_DOWNLOAD)
                trace.event(Kind.BEGIN)
                val downloaded = service.transfer(execution, "/root/workspace/transfers/file.bin", "${grant.rootPath}/download.bin", false,
                    checkScope = ::checkHost)
                productRequire(downloaded is TransferResult.Completed && Files.readAllBytes(File(host, "download.bin").toPath()).contentEquals(hostData),
                    "ordinary_file_transfer_download")
                evidence.check("ordinaryFileTransfer", true)
                trace.event(Kind.END)
                trace.phase(Phase.DIRECTORY_TRANSFER)
                trace.event(Kind.BEGIN)
                val tree = File(host, "tree")
                ChrootProductFixture.createFreshDirectory(tree, host)
                val nested = File(tree, "nested")
                ChrootProductFixture.createFreshDirectory(nested, host)
                writeNew(File(tree, "empty"), byteArrayOf())
                writeNew(File(nested, "value.bin"), hostData)
                productRequire(Files.isDirectory(tree.toPath(), NOFOLLOW_LINKS) && Files.isDirectory(nested.toPath(), NOFOLLOW_LINKS) &&
                    Files.isRegularFile(File(nested, "value.bin").toPath(), NOFOLLOW_LINKS), "ordinary_non_link_directory_source")
                val directoryTransfer = service.transfer(execution, "${grant.rootPath}/tree", "/root/workspace/transfers/tree", false,
                    checkScope = ::checkHost)
                productRequire(directoryTransfer is TransferResult.Completed &&
                    access.readBytes("/root/workspace/transfers/tree/nested/value.bin", hostData.size.toLong()).contentEquals(hostData) &&
                    access.fileSize("/root/workspace/transfers/tree/empty") == 0L, "non_link_cross_backend_directory_transfer")
                evidence.check("nonLinkDirectoryTransfer", true)
                trace.event(Kind.END)
                trace.phase(Phase.CONTENT_URI)
                trace.event(Kind.BEGIN)
                access.mkdirs("/root/workspace/uploads")
                val upload = File(isolatedContext.filesDir, "product-upload.bin")
                writeNew(upload, data)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", upload)
                productRequire(uri.scheme == "content", "app_private_content_uri")
                trace.event(Kind.END)
                trace.phase(Phase.CONTENT_UPLOAD)
                trace.event(Kind.BEGIN)
                val results = service.uploadFiles(execution, "/root/workspace/uploads", listOf(uri), ::checkHost)
                trace.event(Kind.END)
                trace.phase(Phase.CONTENT_VERIFY)
                trace.event(Kind.BEGIN)
                productRequire(results.size == 1 && results.single() is TransferResult.Completed &&
                    access.readBytes("/root/workspace/uploads/product-upload.bin", data.size.toLong()).contentEquals(data), "app_private_content_stream_upload")
                evidence.check("appPrivateContentUpload", true)
                evidence.check("hostRoutingSyntheticFixture", true)
                evidence.check("safUserGrantExercised", false)
                productRequire(project.listFiles()?.none { it.name.startsWith(".aicode-transfer-") } == true &&
                    File(project, "transfers").listFiles()?.none { it.name.startsWith(".aicode-transfer-") } == true &&
                    File(project, "uploads").listFiles()?.none { it.name.startsWith(".aicode-transfer-") } == true,
                    "all_owned_transfer_stages_removed")
                trace.event(Kind.END)
                trace.phase(Phase.E_FILES)
            }
        }
    }

    @Test fun f_idleDisconnectNoBackgroundRebootstrapAndVerifiedRemove() = runBlocking {
        fixture().runCase(ChrootProductCase.F_DISCONNECT_REMOVE) {
            disconnectAndReconnectFromUser()
            val process = startStdio(listOf("/bin/sh", "-c", "printf 'reconnected_ok\\n'; exit 41"))
            useProcess(process) {
                val output = collect(process)
                requireExit(output.task, 41, "reconnected_task_exit_41")
                productRequire(output.stdout.contentEquals("reconnected_ok\n".toByteArray()) && output.stderr.isEmpty(), "reconnected_real_output")
            }
            revokeStopAndRemoveFromUser()
        }
    }

    private class MarkerReader(private val input: InputStream, markers: List<String>) {
        private val signals = markers.associateWith { CompletableDeferred<Unit>() }
        suspend fun await(marker: String) = withTimeout(10_000L) { checkNotNull(signals[marker]).await() }
        fun read(): String {
            val bytes = ByteArrayOutputStream()
            val line = StringBuilder()
            try {
                while (true) {
                    val next = input.read()
                    if (next < 0) break
                    productRequire(bytes.size() < ChrootProductFixture.MAX_OUTPUT, "pty_output_bound")
                    bytes.write(next)
                    if (next == 10) {
                        signals[line.toString().trimEnd('\r')]?.complete(Unit)
                        line.clear()
                    } else {
                        productRequire(line.length < 1024, "pty_marker_bound")
                        line.append(next.toChar())
                    }
                }
                return bytes.toString(Charsets.UTF_8.name())
            } finally {
                signals.values.forEach { if (!it.isCompleted) it.completeExceptionally(ChrootProductAssertion("pty_marker_missing")) }
            }
        }
    }

    private object AndroidIdentity {
        fun uid(): Int = android.os.Process.myUid()
        fun groups(): List<Int> {
            val text = File("/proc/self/status").inputStream().use { it.readBytesBounded(16 * 1024L).toString(Charsets.US_ASCII) }
            val lines = text.lineSequence().filter { it.startsWith("Groups:") }.toList()
            productRequire(lines.size == 1, "app_groups_line")
            return parseGroups(lines.single().substringAfter(':'))
        }
    }

    companion object {
        @Volatile private var active: ChrootProductFixture? = null

        @JvmStatic @BeforeClass fun requireDriverFixture() {
            productRequire(active == null, "product_fixture_not_reused")
            active = ChrootProductVerificationInstrumentation.requireFixture()
        }

        @JvmStatic @AfterClass fun preserveOrSettleCurrentFixture() {
            runBlocking {
                val current = active
                try { current?.afterTests() }
                catch (failure: Throwable) { throw ChrootProductFixture.safeFailure("product_after_class", failure) }
                finally { active = null }
            }
        }

        private fun fixture(): ChrootProductFixture = checkNotNull(active) { "product_driver_fixture_required" }

        private fun parseGroups(value: String): List<Int> {
            val parts = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            productRequire(parts.size <= 32 && parts.all { it.matches(Regex("[0-9]{1,9}")) }, "bounded_real_groups")
            return parts.map(String::toInt).sorted()
        }

        private fun writeNew(file: File, bytes: ByteArray) {
            Files.newOutputStream(file.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.write(bytes) }
        }
    }
}
