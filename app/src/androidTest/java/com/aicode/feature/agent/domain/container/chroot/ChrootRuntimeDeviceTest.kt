package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.os.Build
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerOsDetector
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import com.aicode.feature.workspace.domain.FileReadTooLargeException
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.chroot.ChrootFileOperationException
import com.aicode.feature.workspace.domain.chroot.ChrootFileSession
import com.aicode.feature.workspace.domain.chroot.ChrootFileWorkerClient
import com.aicode.testing.ChrootVerificationInstrumentation
import com.aicode.testing.ChrootVerificationEvidence
import com.aicode.testing.VerificationAssertion
import com.aicode.testing.withCleanup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChrootRuntimeDeviceTest {
    @Test
    fun a_stdioIdentityReadOnlyDnsAndGuestExit37() = runBlocking {
        val fixture = requireFixture()
        fixture.requireUsable()
        val script = """
            set -eu
            export LC_ALL=C
            printf 'identity_begin\n'
            /bin/busybox id -u
            /bin/busybox id -g
            /bin/busybox awk '/^Groups:/{print}' /proc/self/status
            /bin/busybox awk '/^Cap(Inh|Prm|Eff|Bnd|Amb):|^NoNewPrivs:/{print}' /proc/self/status
            printf 'nproc_hard=%s\n' "${'$'}(ulimit -H -u)"
            if /bin/busybox touch /etc/ajiu_verification_readonly > /tmp/ajiu_ro_out 2> /tmp/ajiu_ro_err; then exit 90; fi
            /bin/busybox grep -qi 'read-only file system' /tmp/ajiu_ro_err
            printf 'os_write_rejected\n'
            /bin/busybox nslookup docs.kernel.org 223.5.5.5 > /tmp/ajiu_dns_out 2> /tmp/ajiu_dns_err
            /bin/busybox grep -F 'Name:' /tmp/ajiu_dns_out > /dev/null
            /bin/busybox grep -F 'Address' /tmp/ajiu_dns_out > /dev/null
            printf 'dns_ok\n'
            printf 'identity_end\n'
            printf 'stdio_error_marker\n' >&2
            exit 37
        """.trimIndent()
        val run = fixture.start("stdio", listOf("/bin/sh", "-c", script))
        val drains = fixture.drain(run.handle)
        fixture.verify(listOf(run.handle), listOf(drains.output, drains.error)) {
            requireTrue(fixture.awaitReady(run.handle), "stdio_ready")
            val result = fixture.collect(run.handle, drains)
            fixture.requireExit(result.task, 37, "stdio_exit")
            requireTrue(result.error.contentEquals("stdio_error_marker\n".toByteArray()), "stdio_stderr")
            val lines = result.output.toString(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.toList()
            requireTrue(lines.size == 14 && lines.first() == "identity_begin" && lines.last() == "identity_end", "stdio_markers")
            requireTrue(lines[1] == fixture.identity.uid.toString() && lines[2] == fixture.identity.gid.toString(), "guest_uid_gid")
            requireTrue(parseNumbers(lines[3].substringAfter(':')) == fixture.identity.groups.sorted(), "guest_groups")
            val status = lines.subList(4, 10).associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            for (key in listOf("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb")) {
                requireTrue(status[key]?.matches(Regex("0+")) == true, "guest_caps_zero")
            }
            requireTrue(status["NoNewPrivs"] == "1", "guest_no_new_privs")
            requireTrue(lines[10] == "nproc_hard=1024" && lines[11] == "os_write_rejected" && lines[12] == "dns_ok", "guest_limits_ro_dns")
            ChrootVerificationInstrumentation.recordDnsVerified()
        }
    }

    @Test
    fun b_immediateStdinCloseIsDeliveredAfterGo() = runBlocking {
        val fixture = requireFixture()
        fixture.requireUsable()
        val run = fixture.start("early-eof", listOf("/bin/sh", "-c", "/bin/busybox cat; printf 'early_eof_ok\\n'; exit 0"))
        val drains = fixture.drain(run.handle)
        fixture.verify(listOf(run.handle), listOf(drains.output, drains.error)) {
            run.handle.outputStream.close()
            requireTrue(fixture.awaitReady(run.handle), "early_eof_ready")
            val result = fixture.collect(run.handle, drains)
            fixture.requireExit(result.task, 0, "early_eof_exit")
            requireTrue(result.output.contentEquals("early_eof_ok\n".toByteArray()) && result.error.isEmpty(), "early_eof_output")
        }
    }

    @Test
    fun c_privatePtyResizeCtrlCAndCtrlD() = runBlocking {
        val fixture = requireFixture()
        fixture.requireUsable()
        val script = """
            set -em
            test "${'$'}(/bin/busybox stty size)" = '24 80'
            /bin/busybox stty -echo
            printf 'pty_resize_ready\n'
            IFS= read -r resized
            test "${'$'}resized" = resize
            test "${'$'}(/bin/busybox stty size)" = '40 100'
            printf 'pty_size_ok\n'
            /bin/busybox sh -c 'trap "echo pty_interrupt_ok; exit 0" INT; printf "pty_interrupt_ready\n"; IFS= read -r line; exit 92'
            printf 'pty_eof_ready\n'
            if IFS= read -r line; then exit 91; fi
            printf 'pty_ok\n'
            exit 39
        """.trimIndent()
        val run = fixture.start("pty", listOf("/bin/sh", "-c", script), ChrootIoMode.PTY, timeoutMs = 15_000)
        val pty = MarkerDrain(run.handle.inputStream)
        val reader = fixture.scope.async(Dispatchers.IO) { runInterruptible { pty.read() } }
        val errors = fixture.scope.async(Dispatchers.IO) { readBounded(run.handle.errorStream, MAX_OUTPUT) }
        fixture.verify(listOf(run.handle), listOf(reader, errors)) {
            withTimeout(15_000L) {
                requireTrue(fixture.awaitReady(run.handle), "pty_ready")
                pty.await("pty_resize_ready")
                requireTrue(run.handle.resize(100, 40), "pty_resize")
                fixture.write(run.handle, "resize\n".toByteArray())
                pty.await("pty_interrupt_ready")
                fixture.write(run.handle, byteArrayOf(3))
                pty.await("pty_eof_ready")
                fixture.write(run.handle, byteArrayOf(4))
                fixture.requireExit(run.handle.awaitTaskExit(), 39, "pty_exit")
                requireTrue(fixture.awaitCleanup(run.handle) == ChrootCleanupResult.VERIFIED, "pty_cleanup")
                val text = reader.await().replace("\r\n", "\n")
                requireTrue(text == "pty_resize_ready\npty_size_ok\npty_interrupt_ready\npty_interrupt_ok\npty_eof_ready\npty_ok\n", "pty_markers")
                requireTrue(errors.await().isEmpty(), "pty_stderr")
            }
        }
    }

    @Test
    fun d_productionFileWorkerCrudStreamsAndBoundaryRejections() = runBlocking {
        val fixture = requireFixture()
        fixture.requireUsable()
        val prep = fixture.start("file-prep", listOf("/bin/sh", "-c",
            "set -eu; ln -s /etc/alpine-release /root/workspace/guest-root-link; ln -s /proc/self/status /root/workspace/forbidden-link; " +
                "mkdir -p /root/workspace/path-sub/inner; printf path-ok > /root/workspace/path-sub/value; " +
                "printf lexical-wrong > /root/workspace/value; ln -s /root/workspace/path-sub/inner /root/workspace/dir-link; " +
                "mkdir -p /root/workspace/keep-target; printf keep-safe > /root/workspace/keep-target/value; " +
                "ln -s keep-target /root/workspace/slash-delete-link; ln -s keep-target /root/workspace/slash-rename-link; exit 0"))
        val prepDrains = fixture.drain(prep.handle)
        fixture.verify(listOf(prep.handle), listOf(prepDrains.output, prepDrains.error)) {
            requireTrue(fixture.awaitReady(prep.handle), "file_prep_ready")
            val prepared = fixture.collect(prep.handle, prepDrains)
            fixture.requireExit(prepared.task, 0, "file_prep_exit")
            requireTrue(prepared.output.isEmpty() && prepared.error.isEmpty(), "file_prep_no_output")
        }
        val direct = fixture.start("file-protocol", listOf("/run/ajiu-file-worker"))
        val directErrors = fixture.scope.async(Dispatchers.IO) { readBounded(direct.handle.errorStream, MAX_OUTPUT) }
        val valid = AtomicBoolean(true)
        withCleanup(cleanup = listOf({ valid.set(false) })) {
            fixture.verify(listOf(direct.handle), listOf(directErrors)) {
            requireTrue(fixture.awaitReady(direct.handle), "file_worker_ready")
            val client = ChrootFileWorkerClient(direct.handle.inputStream, direct.handle.outputStream,
                { check(valid.get()) { "verification_file_owner_closed" } }, { direct.handle.outputStream.close() })
            withContext(Dispatchers.IO) {
                runInterruptible {
                    client.use {
                        val directory = "/root/workspace/protocol"
                        val source = "$directory/source.bin"
                        val data = ByteArray(150_000) { (it % 127).toByte() }
                        client.mkdirs(directory)
                        requireTrue(client.write(source, ByteArrayInputStream(data), false) == data.size.toLong(), "worker_chunk_write")
                        requireTrue(client.stat(source).size == data.size.toLong(), "worker_stat")
                        requireTrue(client.list(directory).map { it.first } == listOf("source.bin"), "worker_list")
                        requireTrue(client.resolve(source, true) == source, "worker_resolve")
                        client.openRead(source).use { requireTrue(readBoundedBlocking(it, data.size + 1).contentEquals(data), "worker_chunk_read") }
                        client.rename(source, "$directory/renamed.bin", false)
                        client.delete("$directory/renamed.bin", false)
                        val abandoned = object : InputStream() {
                            override fun read(): Int = throw IOException("verification_abort_source")
                            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = throw IOException("verification_abort_source")
                        }
                        requireRejected("worker_abort") { client.write("$directory/aborted.bin", abandoned, false) }
                        requireTrue(client.list(directory).isEmpty(), "worker_abort_removes_temporary")
                        client.delete(directory, true)
                        requireRejected("worker_readonly", setOf(30)) {
                            client.write("/etc/ajiu-worker-should-not-write", ByteArrayInputStream(byteArrayOf(1)), false)
                        }
                        requireRejected("worker_proc", setOf(13)) { client.stat("/proc/self/status") }
                        requireRejected("worker_dev", setOf(13)) { client.stat("/dev/null") }
                    }
                }
            }
            fixture.requireExit(withTimeout(30_000L) { direct.handle.awaitTaskExit() }, 0, "worker_exit")
            requireTrue(fixture.awaitCleanup(direct.handle) == ChrootCleanupResult.VERIFIED, "worker_cleanup")
            requireTrue(directErrors.await().isEmpty(), "worker_stderr")
            }
        }
        val access = ChrootFileAccess({ fixture.sourceIdentity }, { fixture.fileSession() })
        withContext(Dispatchers.IO) {
            runInterruptible {
                requireTrue(access.readFile("./path-sub//value") == "path-ok", "access_dot_duplicate_separator")
                requireTrue(access.isDirectory("/root/workspace/path-sub/"), "access_trailing_separator")
                requireTrue(access.readFile("/root/workspace/value") == "lexical-wrong", "access_lexical_control")
                requireTrue(access.readFile("dir-link/../value") == "path-ok", "access_symlink_parent_semantics")
                try {
                    access.readFile("path-sub/missing/../value")
                    requireTrue(false, "access_missing_component_must_fail")
                } catch (_: kotlin.io.NoSuchFileException) {
                }
                val targetValue = "/root/workspace/keep-target/value"
                val keptBytes = "keep-safe".toByteArray(Charsets.UTF_8)
                requireTrue(access.resolvePath("slash-delete-link/", false) == "/root/workspace/slash-delete-link", "access_delete_link_nofollow_tail")
                requireTrue(access.resolvePath("slash-rename-link/", false) == "/root/workspace/slash-rename-link", "access_rename_link_nofollow_tail")
                requireTrue(access.isDirectory("slash-delete-link/"), "access_follow_tail_directory")
                access.deleteRecursively("slash-delete-link///")
                requireTrue(access.isDirectory("keep-target") && access.readBytes(targetValue, 64).contentEquals(keptBytes), "access_delete_link_keeps_target")
                requireTrue(!access.exists("slash-delete-link") && access.listFiles("/root/workspace").none { it.name == "slash-delete-link" }, "access_delete_link_removed")
                access.rename("slash-rename-link/", "renamed-link")
                requireTrue(access.readBytes(targetValue, 64).contentEquals(keptBytes), "access_rename_link_keeps_target")
                requireTrue(access.resolvePath("renamed-link/", false) == "/root/workspace/renamed-link", "access_renamed_link_nofollow")
                requireTrue(!access.exists("slash-rename-link") && access.readBytes("renamed-link/value", 64).contentEquals(keptBytes), "access_renamed_link_reads_target")
                access.delete("renamed-link")
                requireTrue(!access.exists("renamed-link") && access.isDirectory("keep-target") &&
                    access.readBytes(targetValue, 64).contentEquals(keptBytes), "access_unlink_renamed_keeps_target")
                val source = "/root/workspace/access/source.bin"
                val data = ByteArray(131_079) { (it % 113).toByte() }
                access.mkdirs("/root/workspace/access")
                requireTrue(access.writeStream(source, ByteArrayInputStream(data), false) == data.size.toLong(), "access_write_stream")
                requireTrue(access.readBytes(source, data.size.toLong()).contentEquals(data), "access_read_stream")
                try {
                    access.readBytes(source, 8)
                    requireTrue(false, "access_read_limit")
                } catch (_: FileReadTooLargeException) {
                }
                access.rename(source, "/root/workspace/access/renamed.bin")
                access.copy("/root/workspace/access/renamed.bin", "/root/workspace/access/copied.bin", false)
                requireTrue(access.readBytes("/root/workspace/access/copied.bin").contentEquals(data), "access_copy")
                val release = access.readBytes("/etc/alpine-release", 64)
                requireTrue(release.isNotEmpty() && release.toString(Charsets.UTF_8).isNotBlank(), "worker_guest_release_nonempty")
                requireTrue(access.resolvePath("/root/workspace/guest-root-link", true) == "/etc/alpine-release", "worker_guest_root_symlink")
                requireTrue(access.readBytes("/root/workspace/guest-root-link", 64).contentEquals(release), "worker_guest_root_read")
                requireRejected("access_forbidden_symlink", setOf(13)) { access.readFile("/root/workspace/forbidden-link") }
                requireRejected("access_proc", setOf(13)) { access.exists("/proc/self/status") }
                requireRejected("access_dev", setOf(13)) { access.exists("/dev/null") }
                requireRejected("access_readonly", setOf(30)) { access.writeBytes("/etc/ajiu-access-should-not-write", byteArrayOf(1), false) }
                access.deleteRecursively("/root/workspace/access")
            }
        }
    }

    @Test
    fun e_twoIndependentOwnersRunInOneInstance() = runBlocking {
        val fixture = requireFixture()
        fixture.requireUsable()
        val first = fixture.start("owner-one", listOf("/bin/sh", "-c", "printf 'owner_one_ready\\n'; IFS= read -r line; test \"${'$'}line\" = first; printf 'owner_one_ok\\n'; exit 41"))
        fixture.verify(listOf(first.handle), emptyList()) {
            val second = fixture.start("owner-two", listOf("/bin/sh", "-c", "printf 'owner_two_ready\\n'; IFS= read -r line; test \"${'$'}line\" = second; printf 'owner_two_ok\\n'; exit 42"))
            val firstOutput = MarkerDrain(first.handle.inputStream)
            val secondOutput = MarkerDrain(second.handle.inputStream)
            val readers = listOf(firstOutput, secondOutput).map { output -> fixture.scope.async(Dispatchers.IO) { runInterruptible { output.read() } } }
            val errors = listOf(first, second).map { run -> fixture.scope.async(Dispatchers.IO) { readBounded(run.handle.errorStream, MAX_OUTPUT) } }
            fixture.verify(listOf(first.handle, second.handle), readers + errors) {
            requireTrue(fixture.awaitReady(first.handle) && fixture.awaitReady(second.handle), "owners_ready")
            firstOutput.await("owner_one_ready")
            secondOutput.await("owner_two_ready")
            requireTrue(first.handle.isAlive && second.handle.isAlive, "owners_concurrent")
            fixture.requireUsable()
            requireTrue(fixture.manager.authorize(first.leaseId, second.spec, REVISION) == null, "owner_permit_isolation")
            fixture.write(first.handle, "first\n".toByteArray())
            fixture.write(second.handle, "second\n".toByteArray())
            fixture.requireExit(withTimeout(30_000L) { first.handle.awaitTaskExit() }, 41, "owner_one_exit")
            fixture.requireExit(withTimeout(30_000L) { second.handle.awaitTaskExit() }, 42, "owner_two_exit")
            requireTrue(fixture.awaitCleanup(first.handle) == ChrootCleanupResult.VERIFIED && fixture.awaitCleanup(second.handle) == ChrootCleanupResult.VERIFIED, "owners_cleanup")
            requireTrue(readers[0].await() == "owner_one_ready\nowner_one_ok\n" && readers[1].await() == "owner_two_ready\nowner_two_ok\n", "owners_output")
            requireTrue(errors.all { it.await().isEmpty() }, "owners_stderr")
        }
        }
    }

    @Test
    fun f_realControlEofStopsNativeAndRevokedLeaseCannotAuthorize() = runBlocking {
        val fixture = requireFixture()
        fixture.requireUsable()
        val run = fixture.start("control-eof", listOf("/bin/sh", "-c", "printf 'control_eof_ready\\n'; IFS= read -r line; exit 93"))
        val output = MarkerDrain(run.handle.inputStream)
        val reader = fixture.scope.async(Dispatchers.IO) { runInterruptible { output.read() } }
        val error = fixture.scope.async(Dispatchers.IO) { readBounded(run.handle.errorStream, MAX_OUTPUT) }
        fixture.verify(listOf(run.handle), listOf(reader, error)) {
            requireTrue(fixture.awaitReady(run.handle), "control_eof_go")
            output.await("control_eof_ready")
            checkNotNull(fixture.controls[run.spec.launchId]) { "verification_control_missing" }.close()
            requireTrue(fixture.awaitCleanup(run.handle) == ChrootCleanupResult.VERIFIED, "control_eof_cleanup")
            withTimeout(30_000L) { run.handle.awaitTaskExit() }
            fixture.manager.revokeLease(run.leaseId)
            fixture.requireUsable()
            requireTrue(fixture.manager.authorize(run.leaseId, fixture.spec("control-replay", listOf("/bin/true")), REVISION) == null, "revoked_lease_rejected")
            requireTrue(reader.await() == "control_eof_ready\n" && error.await().isEmpty(), "control_eof_output")
        }
    }

    private data class Run(val leaseId: String, val spec: ChrootLaunch, val handle: ChrootLaunchHandle)
    private data class Drains(val output: Deferred<ByteArray>, val error: Deferred<ByteArray>)
    private data class Collected(val task: ChrootTaskResult, val output: ByteArray, val error: ByteArray)
    private data class Captured(val file: File, val identity: ChrootFileIdentity, val mode: Int)

    private class Fixture(val context: Context) {
        private val foreground = ChrootVerificationInstrumentation.requireForeground()
        val runId = nonce()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val authority = RootAuthorityCoordinator(RootAccessSettingsRepository(context), scope)
        val identity = appIdentity()
        val journalDirectory = File(context.noBackupFilesDir, "chroot/device-verification/$runId")
        val recovery: ChrootRecoveryRepository
        val factory = ChrootRuntimeProcessFactory(context, ChrootRootBroker(authority))
        val controls = ConcurrentHashMap<String, ChrootControlTransport>()
        val manager: ChrootRuntimeManager
        val installer: ChrootInstanceInstaller
        val handles = Collections.synchronizedList(mutableListOf<ChrootLaunchHandle>())
        val projects: File
        val project: File
        val cache: File
        val profileId = "device-$runId"
        @Volatile var instance: ChrootInstance? = null
        var expectedRoot: RootAccessState? = null
        private val sources = mutableListOf<Captured>()
        private var mounts: List<ChrootMount> = emptyList()
        private val sourceCurrent = AtomicBoolean(true)
        private val goObserved = ConcurrentHashMap.newKeySet<String>()
        @Volatile private var foregroundStop: Deferred<Unit>? = null
        val sourceIdentity get() = "device:$runId:${instance?.id ?: "uninstalled"}:$REVISION"

        init {
            requireTrue(context.packageName == "com.ajiu.debug", "debug_target_required")
            requireTrue(Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a", "arm64_device_required")
            createOwnedDirectories(journalDirectory)
            ChrootVerificationInstrumentation.attachJournal(runId, journalDirectory)
            recovery = ChrootRecoveryRepository(File(journalDirectory, "runtime.bin"), ::syncDirectory)
            manager = ChrootRuntimeManager(authority, factory, recovery, scope, Dispatchers.IO, { spec ->
                ChrootControlChannel(spec.token(), identity.uid).also { controls[spec.launchId] = it }
            }, { System.nanoTime() / 1_000_000L })
            val containers = ContainerInstaller(context, ContainerOsDetector(context))
            requireTrue(containers.ASSET_ROOTFS == "container/arm/alpine-rootfs.bin", "arm_asset_required")
            installer = ChrootInstanceInstaller(
                authority, ChrootArchiveWriter(), { id, identity ->
                    factory.startInstaller(id, identity, factory.currentSession.value
                        ?: throw ChrootRuntimeUnavailable("An explicitly established verification Root session is required"))
                },
                { source ->
                    requireTrue(source == RootfsSource.Asset("alpine-rootfs.bin"), "fixed_asset_required")
                    context.assets.open(containers.ASSET_ROOTFS)
                }, context.filesDir, File(journalDirectory, "installer.bin"), scope, Dispatchers.IO,
                System::currentTimeMillis, AndroidProcess::myUid
            )
            projects = File(context.filesDir, "projects")
            createOwnedDirectories(projects)
            project = File(projects, "ajiu-chroot-device-$runId")
            requireTrue(!Files.exists(project.toPath(), NOFOLLOW_LINKS), "fresh_project_required")
            createOwnedDirectories(project)
            cache = File(context.cacheDir, "chroot-device-$runId")
            requireTrue(!Files.exists(cache.toPath(), NOFOLLOW_LINKS), "fresh_cache_required")
            createOwnedDirectories(cache)
            foreground.attachStopCallback {
                instance?.let { installed ->
                    foregroundStop = scope.async(Dispatchers.IO) { manager.stopInstance(installed.id) }
                }
            }
        }

        suspend fun install() {
            foreground.requireUsable()
            val root = authority.accessSnapshot()
            requireTrue(root.enabled && root.authorityId.isNotBlank(), "user_enabled_root_required")
            requireTrue(factory.currentSession.value != null, "verification_root_session_required")
            expectedRoot = root
            val profile = ContainerProfile(profileId, "Chroot device verification", RootfsSource.Asset("alpine-rootfs.bin"),
                "/bin/sh", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT)
            foreground.requireUsable()
            val installed = installer.installFromUser(profile, root)
            instance = installed
            foreground.requireUsable()
            val home = installer.prepareHome(installed)
            val dns = File(context.filesDir, "chroot-dns/${installed.id}/resolv.conf")
            val homeCapture = capture(home, true)
            val projectCapture = capture(project, true)
            val dnsCapture = capture(dns, false)
            sources.addAll(listOf(homeCapture, projectCapture, dnsCapture))
            mounts = listOf(
                ChrootMount(ChrootMountKind.DIRECTORY, false, home.path, "/root", homeCapture.identity),
                ChrootMount(ChrootMountKind.DIRECTORY, false, project.path, "/root/workspace", projectCapture.identity),
                ChrootMount(ChrootMountKind.FILE, true, dns.path, "/etc/resolv.conf", dnsCapture.identity)
            )
            validateSources()
            record(false, false)
        }

        fun requireUsable() {
            foreground.requireUsable()
            requireTrue(instance != null && recovery.isHealthy.value && recovery.blockedInstances.value.isEmpty() &&
                installer.isHealthy.value && installer.failedInstances.value.isEmpty() && sourceCurrent.get(), "verification_instance_usable")
        }

        private fun checkCurrent() {
            foreground.checkCurrent()
            requireTrue(sourceCurrent.get(), "verification_source_closed")
        }

        fun spec(ownerTag: String, argv: List<String>, mode: ChrootIoMode = ChrootIoMode.STDIO, timeoutMs: Int = 30_000): ChrootLaunch {
            val installed = checkNotNull(instance) { "verification_not_installed" }
            return ChrootLaunch.freeze(installed.id, nonce(), "user:device:$runId:$ownerTag", token(),
                ChrootFileIdentity(installed.device, installed.inode), identity, mode, 5_000, timeoutMs,
                "/root/workspace", argv, listOf("PATH=/bin:/usr/bin", "HOME=/root", "LANG=C", "LC_ALL=C"), mounts)
        }

        suspend fun start(ownerTag: String, argv: List<String>, mode: ChrootIoMode = ChrootIoMode.STDIO, timeoutMs: Int = 30_000): Run {
            requireUsable()
            validateSources()
            val spec = spec(ownerTag, argv, mode, timeoutMs)
            val root = authority.accessSnapshot()
            val initial = checkNotNull(expectedRoot) { "verification_root_missing" }
            requireTrue(root.enabled && root.authorityId == initial.authorityId && root.generation == initial.generation, "verification_root_changed")
            checkCurrent()
            val lease = checkNotNull(manager.activateFromUser(spec.ownerIdentity, profileId, REVISION,
                spec.instanceId, spec.rootfsIdentity, root)) { "verification_activation_rejected" }
            checkCurrent()
            val permit = checkNotNull(manager.authorize(lease, spec, REVISION)) { "verification_permit_rejected" }
            checkCurrent()
            val handle = checkNotNull(manager.launch(lease, permit, spec, ::checkCurrent)) { "verification_launch_rejected" }
            handles.add(handle)
            snapshot(handle, null)
            return Run(lease, spec, handle)
        }

        fun drain(handle: ChrootLaunchHandle): Drains = Drains(
            scope.async(Dispatchers.IO) { readBounded(handle.inputStream, MAX_OUTPUT) },
            scope.async(Dispatchers.IO) { readBounded(handle.errorStream, MAX_OUTPUT) }
        )

        suspend fun awaitReady(handle: ChrootLaunchHandle): Boolean =
            withCleanup(cleanup = listOf({ snapshot(handle, null) })) {
                withTimeout(10_000L) { handle.awaitReady() }.also { if (it) goObserved.add(handle.launchId) }
            }

        suspend fun awaitCleanup(handle: ChrootLaunchHandle): ChrootCleanupResult =
            withCleanup(cleanup = listOf({ snapshot(handle, null) })) {
                withTimeout(15_000L) { handle.awaitCleanup() }
            }

        private fun snapshot(handle: ChrootLaunchHandle, failure: Throwable?) {
            val task = when (handle.taskState.value) {
                null -> ChrootVerificationEvidence.TaskState.UNOBSERVED
                is ChrootTaskResult.Exited -> ChrootVerificationEvidence.TaskState.EXITED
                ChrootTaskResult.Unknown -> ChrootVerificationEvidence.TaskState.UNKNOWN
            }
            val cleanup = when (handle.cleanupState.value) {
                null -> ChrootVerificationEvidence.CleanupState.UNOBSERVED
                ChrootCleanupResult.VERIFIED -> ChrootVerificationEvidence.CleanupState.VERIFIED
                ChrootCleanupResult.UNKNOWN -> ChrootVerificationEvidence.CleanupState.UNKNOWN
            }
            ChrootVerificationInstrumentation.recordHandle(handle.launchId, handle.launchId in goObserved,
                task, cleanup, handle.failure.value, failure, nativeFailure = handle.nativeFailure.value)
        }

        suspend fun <T> verify(current: List<ChrootLaunchHandle>, drains: List<Deferred<*>>, body: suspend () -> T): T =
            withCleanup(
                cleanup = current.map { handle -> suspend { settle(handle) } } + drains.map { drain -> suspend { finishDrain(drain) } },
                observe = { failure -> current.forEach { snapshot(it, failure) } },
                body = body
            )

        private suspend fun finishDrain(drain: Deferred<*>) {
            if (drain.isCompleted) drain.await() else drain.cancelAndJoin()
        }

        suspend fun write(handle: ChrootLaunchHandle, bytes: ByteArray) = withContext(Dispatchers.IO) {
            runInterruptible { handle.outputStream.write(bytes) }
        }

        suspend fun collect(handle: ChrootLaunchHandle, drains: Drains): Collected = withTimeout(30_000L) {
            val task = handle.awaitTaskExit()
            requireTrue(awaitCleanup(handle) == ChrootCleanupResult.VERIFIED, "runtime_cleanup_verified")
            Collected(task, drains.output.await(), drains.error.await())
        }

        fun requireExit(task: ChrootTaskResult, code: Int, marker: String) {
            requireTrue(task is ChrootTaskResult.Exited && task.exit.code == code && task.exit.signal == null, marker)
        }

        suspend fun settle(handle: ChrootLaunchHandle) = withCleanup(
            cleanup = listOf({ snapshot(handle, null) })
        ) {
            if (handle.cleanupState.value == null) handle.stop()
            requireTrue(awaitCleanup(handle) == ChrootCleanupResult.VERIFIED, "final_handle_cleanup")
            validateSources()
        }

        fun fileSession(): ChrootFileSession = runBlocking(Dispatchers.IO) {
            val run = start("file-access-${nonce()}", listOf("/run/ajiu-file-worker"))
            val errors = scope.async(Dispatchers.IO) { readBounded(run.handle.errorStream, MAX_OUTPUT) }
            try {
                requireTrue(awaitReady(run.handle), "access_worker_ready")
                val client = ChrootFileWorkerClient(run.handle.inputStream, run.handle.outputStream,
                    ::checkCurrent, { run.handle.outputStream.close() })
                ChrootFileSession(sourceIdentity, client, cache, ::checkCurrent, listOf("/root/workspace", "/root"),
                    closeOwner = {
                        runBlocking(Dispatchers.IO) {
                            withCleanup(
                                cleanup = listOf({ settle(run.handle) }, { manager.revokeLease(run.leaseId) }, { finishDrain(errors) }),
                                observe = { failure -> snapshot(run.handle, failure) }
                            ) {
                                requireExit(withTimeout(30_000L) { run.handle.awaitTaskExit() }, 0, "access_worker_exit")
                                requireTrue(awaitCleanup(run.handle) == ChrootCleanupResult.VERIFIED, "access_worker_cleanup")
                                requireTrue(errors.await().isEmpty(), "access_worker_stderr")
                            }
                        }
                    })
            } catch (failure: Throwable) {
                withCleanup(
                    cleanup = listOf({ run.handle.stop() }, { settle(run.handle) }, { finishDrain(errors) }),
                    observe = { reported -> snapshot(run.handle, reported) }
                ) { throw failure }
            }
        }

        private fun validateSources() {
            sources.forEach { captured ->
                val current = capture(captured.file, OsConstants.S_ISDIR(captured.mode))
                requireTrue(current.identity == captured.identity && current.mode == captured.mode, "mount_capture_changed")
            }
        }

        suspend fun finish() {
            var verified = 0
            var removed = false
            var allVerified = false
            val current = synchronized(handles) { handles.toList() }
            val settleAll = current.map { handle -> suspend {
                withCleanup(cleanup = emptyList(), observe = { failure -> snapshot(handle, failure) }) {
                    if (handle.cleanupState.value == null) handle.stop()
                    requireTrue(awaitCleanup(handle) == ChrootCleanupResult.VERIFIED, "verification_cleanup_unknown")
                    verified++
                    Unit
                }
            } }
            val removeVerified: suspend () -> Unit = {
                allVerified = verified == current.size && recovery.isHealthy.value && recovery.blockedInstances.value.isEmpty() &&
                    recovery.snapshot().all { it.phase == ChrootRecoveryPhase.CLOSED && it.cleanup == ChrootCleanupResult.VERIFIED } &&
                    installer.isHealthy.value && installer.failedInstances.value.isEmpty()
                if (allVerified && instance != null) {
                    validateSources()
                    installer.removeFromUser(checkNotNull(instance), authority.accessSnapshot())
                    removed = true
                }
                requireTrue(allVerified && (instance == null || removed), "verification_retained_for_recovery")
            }
            withCleanup(cleanup = listOf(
                { foreground.detachStopCallback() },
                { foregroundStop?.await() },
                { record(allVerified, removed) },
                { sourceCurrent.set(false) },
                { withCleanup(cleanup = controls.values.map { control -> suspend { control.close() } }) {} },
                { scope.cancel() }
            )) {
                withCleanup(cleanup = settleAll + removeVerified) {}
            }
        }

        private fun record(cleanup: Boolean, removed: Boolean) {
            val current = synchronized(handles) { handles.toList() }
            ChrootVerificationInstrumentation.recordEvidence(runId, instance?.id, current.size,
                current.count { it.cleanupState.value == ChrootCleanupResult.VERIFIED }, cleanup, removed)
        }
    }

    private class MarkerDrain(private val input: InputStream) {
        private val markers = listOf("pty_resize_ready", "pty_interrupt_ready", "pty_eof_ready", "owner_one_ready", "owner_two_ready", "control_eof_ready")
            .associateWith { CompletableDeferred<Unit>() }
        suspend fun await(marker: String) = withTimeout(10_000L) { checkNotNull(markers[marker]).await() }

        fun read(): String {
            val output = ByteArrayOutputStream()
            val line = StringBuilder()
            try {
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break
                    if (output.size() >= MAX_OUTPUT) throw IOException("verification_output_limit")
                    output.write(byte)
                    if (byte == 10) {
                        markers[line.toString().trimEnd('\r')]?.complete(Unit)
                        line.clear()
                    } else {
                        if (line.length > 1024) throw IOException("verification_marker_limit")
                        line.append(byte.toChar())
                    }
                }
                return output.toString(Charsets.UTF_8.name())
            } finally {
                markers.values.forEach { if (!it.isCompleted) it.completeExceptionally(IOException("verification_marker_missing")) }
            }
        }
    }

    companion object {
        private const val REVISION = 1L
        private const val MAX_OUTPUT = 256 * 1024
        private val random = SecureRandom()
        @Volatile private var fixture: Fixture? = null

        @JvmStatic
        @BeforeClass
        fun prepareProductionFixture() {
            runBlocking {
                check(fixture == null) { "verification_fixture_already_active" }
                ChrootVerificationInstrumentation.requireForeground().requireUsable()
                val prepared = try {
                    Fixture(ChrootVerificationInstrumentation.requireTestContext())
                } catch (failure: Throwable) {
                    withCleanup(cleanup = listOf({
                        ChrootVerificationInstrumentation.recordInfrastructure(ChrootVerificationEvidence.InfrastructureFlag.FIXTURE_UNAVAILABLE)
                    })) { throw failure }
                }
                fixture = prepared
                prepared.install()
            }
        }

        @JvmStatic
        @AfterClass
        fun reclaimVerifiedProductionFixture() {
            runBlocking {
                val current = fixture
                withCleanup(cleanup = listOf({ fixture = null })) {
                    if (current == null) {
                        ChrootVerificationInstrumentation.recordInfrastructure(ChrootVerificationEvidence.InfrastructureFlag.FIXTURE_UNAVAILABLE)
                    } else current.finish()
                }
            }
        }

        private fun requireFixture(): Fixture = checkNotNull(fixture) { "verification_fixture_missing" }

        private fun nonce(): String = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun token(): ByteArray = ByteArray(32).also(random::nextBytes)

        private fun requireTrue(value: Boolean, marker: String) = VerificationAssertion.requireTrue(value, marker)

        private fun parseNumbers(value: String): List<Int> {
            val parts = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            requireTrue(parts.size <= 32 && parts.all { it.matches(Regex("[0-9]+")) }, "bounded_group_list")
            return parts.map { it.toInt() }.sorted()
        }

        private fun appIdentity(): ChrootAppIdentity {
            val uid = AndroidProcess.myUid()
            requireTrue(uid >= 10_000 && Os.getuid() == uid && Os.getgid() == uid, "real_app_uid_gid")
            val status = readBoundedBlocking(FileInputStream("/proc/self/status"), 16 * 1024).toString(Charsets.US_ASCII)
            val groups = status.lineSequence().filter { it.startsWith("Groups:") }.toList()
            requireTrue(groups.size == 1, "real_app_groups_line")
            return ChrootAppIdentity(uid, Os.getgid(), parseNumbers(groups.single().substringAfter(':')))
        }

        private fun syncDirectory(directory: File) {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0)
            try {
                requireTrue(OsConstants.S_ISDIR(Os.fstat(fd).st_mode), "journal_directory_required")
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        }

        private fun createOwnedDirectories(directory: File) {
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
                val parent = checkNotNull(directory.parentFile) { "verification_directory_parent" }
                createOwnedDirectories(parent)
                Files.createDirectory(directory.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            }
            val stat = Os.lstat(directory.path)
            requireTrue(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == AndroidProcess.myUid(), "owned_directory_required")
        }

        private fun capture(file: File, directory: Boolean): Captured {
            val first = Os.lstat(file.path)
            requireTrue(if (directory) OsConstants.S_ISDIR(first.st_mode) else OsConstants.S_ISREG(first.st_mode), "mount_source_kind")
            requireTrue(first.st_uid == AndroidProcess.myUid(), "mount_source_owner")
            var ancestor = file.parentFile
            while (ancestor != null) {
                requireTrue(OsConstants.S_ISDIR(Os.lstat(ancestor.path).st_mode), "mount_source_ancestor_nofollow")
                ancestor = ancestor.parentFile
            }
            val second = Os.lstat(file.path)
            requireTrue(first.st_dev == second.st_dev && first.st_ino == second.st_ino && first.st_mode == second.st_mode &&
                first.st_uid == second.st_uid, "mount_source_stable")
            return Captured(file, ChrootFileIdentity(first.st_dev, first.st_ino), first.st_mode)
        }

        private suspend fun readBounded(input: InputStream, limit: Int): ByteArray = runInterruptible(Dispatchers.IO) {
            readBoundedBlocking(input, limit)
        }

        private fun readBoundedBlocking(input: InputStream, limit: Int): ByteArray = input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count <= 0 || count > limit - output.size()) throw IOException("verification_output_limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        private fun requireRejected(marker: String, errno: Set<Int> = emptySet(), block: () -> Unit) {
            try {
                block()
            } catch (failure: ChrootFileOperationException) {
                requireTrue(errno.isEmpty() || failure.errno in errno, marker)
                return
            } catch (_: IOException) {
                requireTrue(errno.isEmpty(), marker)
                return
            }
            requireTrue(false, marker)
        }
    }
}
