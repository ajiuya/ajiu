package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorization
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootRuntimeFacadeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = ChrootInstance("0123456789abcdef01234567", 5, 19, 42)
    private val profile = ContainerProfile("profile", "fixture", RootfsSource.Asset("alpine-rootfs.bin"),
        null, isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = instance)
    private val workspace = "/data/user/0/com.ajiu/files/projects/fixture"
    private val owner = RuntimeOwner(RuntimeOwnerKind.AGENT, "session-1")

    private data class Fixture(
        val facade: ChrootRuntimeFacade,
        val manager: ChrootRuntimeManager,
        val authority: RootAuthorityCoordinator,
        val factory: ChrootProcessFactory,
        val sources: ChrootAppSourceCapture,
        val scope: CoroutineScope,
        val revision: MutableStateFlow<Long>,
        val revokeConfiguration: () -> Unit,
        val runtimeFactory: ChrootRuntimeProcessFactory,
        val session: MutableStateFlow<ChrootRuntimeSession?>,
        val revokeSession: () -> Unit,
        val installer: ChrootInstanceInstaller,
        val settings: ContainerSettingsRepository,
        val snapshot: ContainerProfileSnapshot,
        val unpublishedInstalls: MutableStateFlow<Set<String>>,
        val recovery: ChrootRecoveryRepository
    ) : AutoCloseable {
        override fun close() { scope.cancel() }
    }

    private suspend fun TestScope.fixture(
        profile: ContainerProfile = this@ChrootRuntimeFacadeTest.profile,
        controlFactory: (ChrootLaunch) -> ChrootControlTransport = { error("No background request may start a control channel") }
    ): Fixture {
        val rootRepository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
        val authority = RootAuthorityCoordinator(rootRepository, backgroundScope)
        authority.setEnabled(true)
        authority.accessSnapshot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recovery = ChrootRecoveryRepository(File(temporary.newFolder(), "runtime.bin"))
        val factory = mockk<ChrootProcessFactory>()
        val manager = ChrootRuntimeManager(authority, factory, recovery, scope, Dispatchers.IO,
            controlFactory, { System.nanoTime() / 1_000_000 })
        val installer = mockk<ChrootInstanceInstaller>()
        every { installer.isHealthy } returns MutableStateFlow(true)
        every { installer.failedInstances } returns MutableStateFlow(emptySet())
        val unpublishedInstalls = MutableStateFlow<Set<String>>(emptySet())
        every { installer.unpublishedInstalls } returns unpublishedInstalls
        val revision = MutableStateFlow(7L)
        val settings = mockk<ContainerSettingsRepository>()
        every { settings.profileConfigurationRevision } answers { revision.value }
        every { settings.profileConfigurationRevisions } returns revision
        val snapshot = ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), 1L, revision.value, Any())
        coEvery { settings.captureProfileSnapshot() } returns snapshot
        every { settings.checkProfileSnapshot(snapshot) } answers {
            if (snapshot.configurationRevision != revision.value) throw CancellationException("profile changed")
        }
        coEvery { settings.publishChrootInstance(snapshot, profile, any()) } returns Unit
        val configurationRevocation = AtomicReference<() -> Unit>({})
        every { settings.registerConfigurationRevocation(any()) } answers {
            configurationRevocation.set(firstArg())
            AutoCloseable { configurationRevocation.set({}) }
        }
        val runtimeFactory = mockk<ChrootRuntimeProcessFactory>()
        val session = MutableStateFlow<ChrootRuntimeSession?>(null)
        every { runtimeFactory.currentSession } returns session
        val sessionRevocation = AtomicReference<() -> Unit>({})
        every { runtimeFactory.registerSessionRevocation(any()) } answers {
            sessionRevocation.set(firstArg())
            AutoCloseable { sessionRevocation.set({}) }
        }
        every { runtimeFactory.checkSession(any()) } answers {
            if (session.value !== firstArg<ChrootRuntimeSession>()) throw CancellationException("session revoked")
        }
        coEvery { runtimeFactory.ensureFromUser(any()) } coAnswers {
            session.value ?: ChrootRuntimeSession("user-session", firstArg()).also { session.value = it }
        }
        coEvery { runtimeFactory.ensureSession(any()) } coAnswers {
            session.value ?: ChrootRuntimeSession("policy-session", firstArg()).also { session.value = it }
        }
        val sources = mockk<ChrootAppSourceCapture>()
        every { sources.capture(profile, workspace) } returns ChrootAppSources(profile, workspace,
            "/data/user/0/com.ajiu/files/chroot-home/${instance.id}", ChrootAppIdentity(10445, 10445, listOf(3003)),
            emptyList(), "nameserver 223.5.5.5\n")
        return Fixture(ChrootRuntimeFacade(authority, manager, recovery, installer, settings, runtimeFactory, sources::capture,
            temporary.newFolder(), scope),
            manager, authority, factory, sources, scope, revision, { configurationRevocation.get().invoke() },
            runtimeFactory, session, { session.value = null; sessionRevocation.get().invoke() },
            installer, settings, snapshot, unpublishedInstalls, recovery)
    }

    @Test fun defaultAllowChrootFirstCaptureReturnsCurrentBindingWithoutPendingAuthorization() = runTest {
        fixture().use { fixture ->
            val root = fixture.authority.accessSnapshot()
            assertTrue(root.enabled)
            // Only the broker session exists; this owner has never been manually authorized.
            val session = ChrootRuntimeSession("policy-session", root)
            fixture.session.value = session
            assertTrue(fixture.facade.state(profile).installed)
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)

            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}

            capture.checkCurrent()
            assertEquals(owner, capture.owner)
            assertEquals(profile.id, capture.profileId)
            assertEquals(7L, capture.configurationRevision)
            assertEquals(instance, capture.instance)
            assertEquals(workspace, capture.workspacePath)
            assertTrue(capture.digest.isNotBlank())
            assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
            assertEquals(1, fixture.facade.state(profile).authorizedOwners)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            assertSame(session, fixture.session.value)
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
            verify(exactly = 0) { fixture.factory.captureHelper() }
            verify(exactly = 0) { fixture.factory.start(any()) }
        }
    }

    @Test fun defaultAllowChrootSecondOwnerGetsAnIndependentBindingWithoutManualAuthorization() = runTest {
        fixture().use { fixture ->
            fixture.session.value = ChrootRuntimeSession("policy-session", fixture.authority.accessSnapshot())
            val other = RuntimeOwner(RuntimeOwnerKind.PLUGIN, owner.scopeId)

            val first = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            val second = fixture.facade.capture(profile, 7, workspace, other, "source-1") {}

            first.checkCurrent()
            second.checkCurrent()
            assertEquals(owner, first.owner)
            assertEquals(other, second.owner)
            assertNotEquals(first.digest, second.digest)
            assertFalse(first.binding === second.binding)
            assertEquals(2, fixture.manager.instanceState(instance.id).authorizedOwners)
            assertEquals(2, fixture.facade.state(profile).authorizedOwners)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())

            fixture.facade.revokeOwner(owner)
            assertTrue(runCatching { first.checkCurrent() }.exceptionOrNull() is CancellationException)
            second.checkCurrent()
            assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
            assertEquals(1, fixture.facade.state(profile).authorizedOwners)
            val repeated = fixture.facade.capture(profile, 7, workspace, other, "source-1") {}
            repeated.checkCurrent()
            assertEquals(second.digest, repeated.digest)
            assertSame(second.binding, repeated.binding)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
            verify(exactly = 0) { fixture.factory.captureHelper() }
            verify(exactly = 0) { fixture.factory.start(any()) }
        }
    }

    @Test fun defaultAllowChrootRootOffAndUnknownCleanupRejectWithoutPendingOrBootstrap() = runTest {
        val rejections = listOf(false, true).map { unknownCleanup ->
            fixture().use { fixture ->
                val reason = if (unknownCleanup) "UNKNOWN cleanup" else "Root disabled"
                if (unknownCleanup) {
                    val launchId = "89abcdef0123456789abcdef"
                    fixture.recovery.recordIntent(instance.id, launchId, ChrootFileIdentity(instance.device, instance.inode),
                        owner.identity, fixture.authority.accessSnapshot().generation)
                    fixture.recovery.recordCleanup(launchId, ChrootCleanupResult.UNKNOWN)
                    assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
                    assertTrue(fixture.facade.state(profile).journalHealthy)
                    assertTrue(fixture.facade.state(profile).recoveryRequired)
                } else {
                    fixture.authority.setEnabled(false)
                    assertFalse(fixture.facade.state(profile).rootEnabled)
                }
                assertNull(fixture.session.value)

                val failure = runCatching {
                    fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
                }.exceptionOrNull()

                coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
                coVerify(exactly = 0) { fixture.runtimeFactory.ensureSession(any()) }
                verify(exactly = 0) { fixture.factory.captureHelper() }
                verify(exactly = 0) { fixture.factory.start(any()) }
                assertNull(fixture.session.value)
                assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
                assertEquals(0, fixture.facade.state(profile).authorizedOwners)
                Triple(reason, failure, fixture.facade.pendingAuthorizations.value.size)
            }
        }
        for ((reason, failure, pendingCount) in rejections) {
            assertTrue("$reason must fail closed", failure is IOException || failure is CancellationException)
            assertFalse("$reason must not request owner authorization", failure is LocalRuntimeAuthorizationRequired)
            assertEquals("$reason must not publish pending authorization", 0, pendingCount)
        }
    }

    private class TaskProcess : Process() {
        private val bytes = LinkedBlockingQueue<Int>()
        private val alive = AtomicBoolean(true)
        private val stdout = object : InputStream() {
            override fun read(): Int = bytes.take()
            override fun close() { bytes.offer(-1) }
        }
        private val stdin = ByteArrayOutputStream()
        fun frame(type: ChrootFrameType, body: ByteArray) {
            ByteArrayOutputStream().also { ChrootWireProtocol.write(it, type, body) }.toByteArray()
                .forEach { bytes.offer(it.toInt() and 255) }
        }
        fun finish() { alive.set(false); stdout.close() }
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = stdin
        override fun isAlive(): Boolean = alive.get()
        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else 0
        override fun waitFor(): Int = throw AssertionError("Wrapper status is not a guest task exit")
        override fun destroy() = finish()
    }

    @Test fun defaultAllowChrootCaptureRemainsLaunchableAfterItsIssuanceTicketIsReleased() = runTest {
        val process = TaskProcess()
        val launch = AtomicReference<ChrootLaunch>()
        val went = AtomicBoolean()
        fixture(controlFactory = { spec ->
            launch.set(spec)
            object : ChrootControlTransport {
                override suspend fun awaitHelper() = Unit
                override fun trySend(type: ChrootFrameType, body: ByteArray): Boolean {
                    if (type == ChrootFrameType.GO && went.compareAndSet(false, true)) {
                        process.frame(ChrootFrameType.EXIT, ByteBuffer.allocate(8).putInt(0).putInt(0).array())
                        process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                        process.finish()
                    }
                    return true
                }
                override fun close() = Unit
            }
        }).use { fixture ->
            every { fixture.factory.captureHelper() } returns ChrootHelperIdentity("/trusted/helper", 1, 2, 3, "a".repeat(64))
            every { fixture.factory.start(any()) } answers {
                process.frame(ChrootFrameType.READY, launch.get().token())
                process
            }
            try {
                val captured = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
                val repeated = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
                assertSame(captured.binding, repeated.binding)
                val task = awaitIo { fixture.facade.startStdio(captured, "/bin/true", emptyList()) }
                assertEquals(com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult.Exited(0, null),
                    awaitIo { task.awaitTaskExit() })
                assertEquals(LocalRuntimeCleanup.VERIFIED, awaitIo { task.awaitCleanup() })
                captured.checkCurrent()
                assertTrue(went.get())
                assertEquals(owner.identity, launch.get().ownerIdentity)
                assertEquals(10445, launch.get().appIdentity.uid)
                verify(exactly = 1) { fixture.factory.start(any()) }
                assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
            } finally { process.finish() }
        }
    }

    @Test fun defaultAllowChrootLegacyNativeFailureIsLocalizedOnlyAfterTheControlledLaunch() = runTest {
        val process = TaskProcess()
        fixture(controlFactory = {
            object : ChrootControlTransport {
                override suspend fun awaitHelper() = Unit
                override fun trySend(type: ChrootFrameType, body: ByteArray) = true
                override fun close() = Unit
            }
        }).use { fixture ->
            every { fixture.factory.captureHelper() } returns ChrootHelperIdentity("/trusted/helper", 1, 2, 3, "a".repeat(64))
            every { fixture.factory.start(any()) } answers {
                process.frame(ChrootFrameType.ERROR,
                    "code=prepare_failed;stage=legacy_readonly_instance;errno=95".toByteArray())
                process.finish()
                process
            }
            try {
                val captured = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
                verify(exactly = 0) { fixture.factory.start(any()) }
                val failure = awaitIo { runCatching { fixture.facade.startStdio(captured, "/bin/true", emptyList()) }.exceptionOrNull() }
                assertTrue(failure is IOException)
                assertTrue(failure?.message.orEmpty().contains("legacy read-only format"))
                assertTrue(fixture.manager.instanceState(instance.id).blocked)
                assertTrue(fixture.facade.state(profile).recoveryRequired)
                coVerify(exactly = 0) { fixture.installer.removeFromUser(any(), any(), any(), any()) }
            } finally { process.finish() }
        }
    }

    private suspend fun <T> awaitIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        withTimeout(5_000) { block() }
    }

    @Test fun defaultAllowChrootColdSessionUsesNeutralPolicyEnsureAndSingleflightsTheSameDigest() = runTest {
        fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { fixture.runtimeFactory.ensureSession(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                ChrootRuntimeSession("cold-policy-session", firstArg()).also { fixture.session.value = it }
            }
            val calls = List(12) {
                async(Dispatchers.IO) { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }
            }
            try {
                awaitIo { entered.await() }
                assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
                release.complete(Unit)
                val captures = awaitIo { calls.awaitAll() }
                captures.forEach { capture ->
                    capture.checkCurrent()
                    assertEquals(owner, capture.owner)
                    assertEquals(captures.first().digest, capture.digest)
                    assertSame(captures.first().binding, capture.binding)
                }
                assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
                assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
                coVerify(exactly = 1) { fixture.runtimeFactory.ensureSession(any()) }
                coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
                verify(exactly = 0) { fixture.factory.start(any()) }
            } finally { release.complete(Unit) }
        }
    }

    @Test fun defaultAllowChrootEveryRevocationDuringEnsurePreventsLateBindingPublication() = runTest {
        for (revocation in listOf("owner", "stop", "configuration", "session", "root", "unknown", "source")) {
            fixture().use { fixture ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val sourceCurrent = AtomicBoolean(true)
                coEvery { fixture.runtimeFactory.ensureSession(any()) } coAnswers {
                    entered.complete(Unit)
                    release.await()
                    ChrootRuntimeSession("late-policy-session", firstArg()).also { fixture.session.value = it }
                }
                val call = async(Dispatchers.IO) {
                    runCatching {
                        fixture.facade.capture(profile, 7, workspace, owner, "source-1") {
                            if (!sourceCurrent.get()) throw CancellationException("source changed")
                        }
                    }
                }
                try {
                    awaitIo { entered.await() }
                    when (revocation) {
                        "owner" -> fixture.facade.revokeOwner(owner)
                        "stop" -> assertEquals(LocalRuntimeCleanup.VERIFIED, fixture.facade.stopInstance(instance))
                        "configuration" -> fixture.revokeConfiguration() // Selection changes need not increment configuration revision.
                        "session" -> fixture.revokeSession()
                        "root" -> { fixture.authority.setEnabled(false); fixture.authority.setEnabled(true) }
                        "unknown" -> {
                            val launch = "89abcdef0123456789abcdef"
                            fixture.recovery.recordIntent(instance.id, launch, ChrootFileIdentity(instance.device, instance.inode),
                                owner.identity, fixture.authority.accessSnapshot().generation)
                            fixture.recovery.recordCleanup(launch, ChrootCleanupResult.UNKNOWN)
                        }
                        "source" -> sourceCurrent.set(false)
                    }
                    release.complete(Unit)
                    val failure = awaitIo { call.await() }.exceptionOrNull()
                    assertTrue("$revocation must reject the waiting capture", failure is CancellationException || failure is IOException)
                    assertFalse(failure is LocalRuntimeAuthorizationRequired)
                    assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
                    assertEquals(0, fixture.facade.state(profile).authorizedOwners)
                    assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
                    coVerify(exactly = 1) { fixture.runtimeFactory.ensureSession(any()) }
                    coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
                    verify(exactly = 0) { fixture.factory.captureHelper() }
                    verify(exactly = 0) { fixture.factory.start(any()) }
                } finally { release.complete(Unit) }
            }
        }
    }

    @Test fun defaultAllowChrootAnOldEnsureCannotRemoveOrReplaceTheNextIssuanceFlight() = runTest {
        fixture().use { fixture ->
            val oldEntered = CompletableDeferred<Unit>()
            val oldRelease = CompletableDeferred<Unit>()
            val nextEntered = CompletableDeferred<Unit>()
            val nextRelease = CompletableDeferred<Unit>()
            val ensures = AtomicInteger()
            coEvery { fixture.runtimeFactory.ensureSession(any()) } coAnswers {
                if (ensures.incrementAndGet() == 1) { oldEntered.complete(Unit); oldRelease.await() }
                else { nextEntered.complete(Unit); nextRelease.await() }
                fixture.session.value ?: ChrootRuntimeSession("replacement-policy", firstArg()).also { fixture.session.value = it }
            }
            val old = async(Dispatchers.IO) {
                runCatching { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }
            }
            try {
                awaitIo { oldEntered.await() }
                fixture.facade.revokeOwner(owner)
                val next = async(Dispatchers.IO) { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }
                awaitIo { nextEntered.await() }
                oldRelease.complete(Unit)
                assertTrue(awaitIo { old.await() }.exceptionOrNull() is CancellationException)
                assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
                val followerReady = CompletableDeferred<Unit>()
                val followerChecks = AtomicInteger()
                val follower = async(Dispatchers.IO) {
                    fixture.facade.capture(profile, 7, workspace, owner, "source-1") {
                        if (followerChecks.incrementAndGet() == 3) followerReady.complete(Unit)
                    }
                }
                awaitIo { followerReady.await() }
                nextRelease.complete(Unit)
                val captures = awaitIo { listOf(next, follower).awaitAll() }
                captures.forEach { it.checkCurrent() }
                assertSame(captures[0].binding, captures[1].binding)
                assertEquals(2, ensures.get())
                assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
            } finally { oldRelease.complete(Unit); nextRelease.complete(Unit) }
        }
    }

    @Test fun defaultAllowChrootAJoinedCallerStillChecksItsOwnSourceAfterEnsure() = runTest {
        fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val joined = CompletableDeferred<Unit>()
            val current = AtomicBoolean(true)
            val checks = AtomicInteger()
            coEvery { fixture.runtimeFactory.ensureSession(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                ChrootRuntimeSession("joined-policy", firstArg()).also { fixture.session.value = it }
            }
            val first = async(Dispatchers.IO) { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }
            try {
                awaitIo { entered.await() }
                val follower = async(Dispatchers.IO) {
                    runCatching {
                        fixture.facade.capture(profile, 7, workspace, owner, "source-1") {
                            if (!current.get()) throw CancellationException("joined source revoked")
                            if (checks.incrementAndGet() == 3) joined.complete(Unit)
                        }
                    }
                }
                awaitIo { joined.await() }
                current.set(false)
                release.complete(Unit)
                awaitIo { first.await() }.checkCurrent()
                assertTrue(awaitIo { follower.await() }.exceptionOrNull() is CancellationException)
                assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
                coVerify(exactly = 1) { fixture.runtimeFactory.ensureSession(any()) }
            } finally { release.complete(Unit) }
        }
    }

    @Test fun defaultAllowChrootRevokingAnotherOwnerDoesNotCancelAnIndependentIssuance() = runTest {
        fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val other = RuntimeOwner(RuntimeOwnerKind.FILE, "file-owner")
            coEvery { fixture.runtimeFactory.ensureSession(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                ChrootRuntimeSession("independent-owner", firstArg()).also { fixture.session.value = it }
            }
            val waiting = async(Dispatchers.IO) { fixture.facade.capture(profile, 7, workspace, other, "file-source") {} }
            try {
                awaitIo { entered.await() }
                fixture.facade.revokeOwner(owner)
                release.complete(Unit)
                val capture = awaitIo { waiting.await() }
                capture.checkCurrent()
                assertEquals(other, capture.owner)
                assertEquals(1, fixture.manager.instanceState(instance.id).authorizedOwners)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun defaultAllowChrootDigestSeparatesFixedRootGuestPolicyFromOldCapturedSemantics() = runTest {
        fixture().use { fixture ->
            val root = fixture.authority.accessSnapshot()
            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            fun expected(policy: String?): String {
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { output ->
                    fun text(value: String) { val encoded = value.toByteArray(); output.writeInt(encoded.size); output.write(encoded) }
                    if (policy != null) text(policy)
                    text(owner.identity); text(profile.id); output.writeLong(7); text("source-1")
                    text(instance.id); output.writeLong(instance.device); output.writeLong(instance.inode); output.writeLong(instance.installGeneration)
                    text(root.authorityId); output.writeLong(root.generation); output.writeBoolean(root.enabled)
                    text(profile.shellPath.orEmpty()); text(workspace); text(capture.homePath); text("nameserver 223.5.5.5\n")
                    output.writeInt(10445); output.writeInt(10445); output.writeInt(3003); output.writeInt(-1)
                    profile.env.toSortedMap().forEach { (key, value) -> text(key); text(value) }
                    output.writeInt(-1)
                }
                return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            assertEquals(expected("root-guest-rw-v1"), capture.digest)
            assertNotEquals(expected(null), capture.digest)
        }
    }

    @Test fun defaultAllowChrootReadOnlyMountIdentityChangesInvalidateTheCapturedLaunchBeforeHelperStart() = runTest {
        fixture().use { fixture ->
            val original = fixture.sources.capture(profile, workspace)
            val dns = ChrootMount(ChrootMountKind.FILE, true, "/private/resolv.conf", "/etc/resolv.conf", ChrootFileIdentity(9, 10))
            val mounts = mutableListOf(dns)
            every { fixture.sources.capture(profile, workspace) } returns original.copy(mounts = mounts)
            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            mounts[0] = dns.copy(readOnly = false)
            val failure = runCatching { fixture.facade.startStdio(capture, "/bin/true", emptyList()) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
            verify(exactly = 0) { fixture.factory.captureHelper() }
            verify(exactly = 0) { fixture.factory.start(any()) }
        }
    }

    @Test fun defaultAllowChrootMissingRealSuAndUnhealthyInstallerNeverBecomeAuthorizationRequests() = runTest {
        fixture().use { fixture ->
            coEvery { fixture.runtimeFactory.ensureSession(any()) } throws ChrootRuntimeUnavailable("Existing device root is unavailable")
            val failure = runCatching { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }.exceptionOrNull()
            assertTrue(failure is ChrootRuntimeUnavailable)
            assertFalse(failure is LocalRuntimeAuthorizationRequired)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
            verify(exactly = 0) { fixture.factory.start(any()) }
        }
        fixture().use { fixture ->
            every { fixture.installer.isHealthy } returns MutableStateFlow(false)
            assertTrue(runCatching { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }.exceptionOrNull() is IOException)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureSession(any()) }
            verify(exactly = 0) { fixture.factory.start(any()) }
        }
    }

    @Test fun unpublishedRootInstallationBlocksFreshProfileStateAndCreateBeforeUserRootStart() = runTest {
        val fresh = profile.copy(chrootInstance = null)
        fixture(fresh).use { fixture ->
            val initial = fixture.facade.state(fresh)
            assertFalse(initial.installed)
            assertFalse(initial.recoveryRequired)
            assertFalse(initial.busy)
            assertEquals(0, initial.activeLaunches)
            fixture.unpublishedInstalls.value = setOf(instance.id)
            val state = fixture.facade.state(fresh)
            assertTrue(state.recoveryRequired)
            assertFalse(state.installed)
            assertTrue(runCatching { fixture.facade.createInstanceFromUser(fresh, fixture.authority.accessSnapshot()) }.isFailure)
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
            coVerify(exactly = 0) { fixture.installer.installFromUser(any(), any(), any(), any()) }
        }
    }

    @Test fun policyBindingsAreMemoryOnlyAndNeverPublishOwnerConfirmationRequests() = runTest {
        fixture().use { fixture ->
            repeat(70) { index ->
                fixture.facade.capture(profile, 7, workspace,
                    RuntimeOwner(RuntimeOwnerKind.AGENT, "session-$index"), "workspace-generation-1") {}.checkCurrent()
            }
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            assertEquals(70, fixture.manager.instanceState(instance.id).authorizedOwners)
            verify(exactly = 0) { fixture.factory.captureHelper() }
            verify(exactly = 0) { fixture.factory.start(any()) }
            coVerify(exactly = 1) { fixture.runtimeFactory.ensureSession(any()) }
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
        }
        fixture().use { restarted ->
            assertEquals(0, restarted.manager.instanceState(instance.id).authorizedOwners)
            assertTrue(restarted.facade.pendingAuthorizations.value.isEmpty())
        }
    }

    @Test fun bindingsRemainExactOwnerAndSourceWithoutBorrowingAnotherGeneration() = runTest {
        fixture().use { fixture ->
            val captured = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            captured.checkCurrent()
            val repeated = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            assertEquals(captured.digest, repeated.digest)
            assertSame(captured.binding, repeated.binding)
            val nextSource = fixture.facade.capture(profile, 7, workspace, owner, "source-2") {}
            val otherKind = fixture.facade.capture(profile, 7, workspace,
                RuntimeOwner(RuntimeOwnerKind.PLUGIN, owner.scopeId), "source-1") {}
            nextSource.checkCurrent()
            otherKind.checkCurrent()
            assertNotEquals(captured.digest, nextSource.digest)
            assertNotEquals(captured.digest, otherKind.digest)
            assertFalse(captured.binding === nextSource.binding)
            assertFalse(captured.binding === otherKind.binding)
            assertEquals(2, fixture.facade.state(profile).authorizedOwners)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
            verify(exactly = 0) { fixture.factory.captureHelper() }
        }
    }

    @Test fun staleSourcesCannotBindAndRevokedCaptureFailsSynchronously() = runTest {
        fixture().use { fixture ->
            val current = AtomicBoolean(false)
            val check = { if (!current.get()) throw CancellationException("source changed") }
            assertTrue(runCatching { fixture.facade.capture(profile, 7, workspace, owner, "source-1", check) }
                .exceptionOrNull() is CancellationException)
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureSession(any()) }
            current.set(true)
            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1", check)
            capture.checkCurrent()
            fixture.facade.revokeOwner(owner)
            assertTrue(runCatching { capture.checkCurrent() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
            assertEquals(0, fixture.facade.state(profile).authorizedOwners)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
        }
    }

    @Test fun brokerReconnectionInTheSameRootGenerationCannotReviveAnOldLease() = runTest {
        fixture().use { fixture ->
            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            val original = fixture.session.value!!
            fixture.revokeSession()
            assertTrue(runCatching { capture.checkCurrent() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
            fixture.session.value = ChrootRuntimeSession("replacement-session", original.rootState)
            assertTrue(runCatching { capture.checkCurrent() }.isFailure)
            val replacement = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            replacement.checkCurrent()
            assertFalse(capture.binding === replacement.binding)
            assertTrue(runCatching { capture.checkCurrent() }.isFailure)
            coVerify(exactly = 1) { fixture.runtimeFactory.ensureSession(any()) }
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
        }
    }

    @Test fun fileBackendCreationAndCacheIdentityNeverStartAWorker() = runTest {
        fixture().use { fixture ->
            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            val access = fixture.facade.fileAccess(capture)
            assertEquals(capture.digest, access.cacheIdentity())
            verify(exactly = 0) { fixture.factory.captureHelper() }
            verify(exactly = 0) { fixture.factory.start(any()) }
            fixture.facade.revokeOwner(owner)
            assertTrue(runCatching { access.cacheIdentity() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun legacyOrForgedAuthorizationTokensCannotMintBindingsAndMountedIdentityIsRechecked() = runTest {
        fixture().use { fixture ->
            val forged = LocalRuntimeAuthorization(RuntimeOwner(RuntimeOwnerKind.PLUGIN, "other"), profile.id,
                7, instance, fixture.authority.accessSnapshot(), workspace, "/private/home", "forged-digest",
                emptyList(), "source-1", Any())
            assertTrue(runCatching { fixture.facade.prepareAuthorization(forged) }.isFailure)
            assertTrue(runCatching { fixture.facade.authorizeFromUser(forged) }.isFailure)
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureSession(any()) }
            coEvery { fixture.runtimeFactory.ensureSession(any()) } coAnswers {
                every { fixture.sources.capture(profile, workspace) } returns ChrootAppSources(profile, workspace,
                    "/data/user/0/com.ajiu/files/chroot-home/${instance.id}", ChrootAppIdentity(10445, 10445, listOf(3003)),
                    listOf(ChrootMount(ChrootMountKind.DIRECTORY, false, workspace, "/root/workspace",
                        ChrootFileIdentity(99, 101))), "nameserver 223.5.5.5\n")
                ChrootRuntimeSession("changed-sources", firstArg()).also { fixture.session.value = it }
            }
            assertTrue(runCatching { fixture.facade.capture(profile, 7, workspace, owner, "source-1") {} }
                .exceptionOrNull() is CancellationException)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
        }
    }

    @Test fun instanceCreationPassesTheConfirmedSessionAndPublishesOnceInsideTheInstallerTransaction() = runTest {
        val fresh = profile.copy(chrootInstance = null)
        fixture(fresh).use { fixture ->
            val root = fixture.authority.accessSnapshot()
            val confirmed = ChrootRuntimeSession("confirmed-create", root)
            fixture.session.value = confirmed
            coEvery { fixture.installer.installFromUser(fresh, root, any(), any()) } coAnswers {
                assertSame(confirmed, thirdArg<ChrootRuntimeSession?>())
                arg<suspend (ChrootInstance) -> Unit>(3).invoke(instance)
                instance
            }
            assertEquals(instance, fixture.facade.createInstanceFromUser(fresh, root))
            coVerify(exactly = 1) {
                fixture.installer.installFromUser(fresh, root, match { it === confirmed }, any())
            }
            coVerify(exactly = 1) { fixture.settings.publishChrootInstance(fixture.snapshot, fresh, instance) }
        }
    }

    @Test fun instanceRemovalPassesTheConfirmedSessionAndPublishesOnceInsideTheInstallerTransaction() = runTest {
        fixture().use { fixture ->
            val root = fixture.authority.accessSnapshot()
            val confirmed = ChrootRuntimeSession("confirmed-remove", root)
            fixture.session.value = confirmed
            coEvery { fixture.installer.removeFromUser(instance, root, any(), any()) } coAnswers {
                assertSame(confirmed, thirdArg<ChrootRuntimeSession?>())
                arg<suspend () -> Unit>(3).invoke()
            }
            fixture.facade.removeInstanceFromUser(profile, root)
            coVerify(exactly = 1) {
                fixture.installer.removeFromUser(instance, root, match { it === confirmed }, any())
            }
            coVerify(exactly = 1) { fixture.settings.publishChrootInstance(fixture.snapshot, profile, null) }
        }
    }

    @Test fun sameRootReconnectionAtTheInstallerBoundaryRetainsTheOriginalSession() = runTest {
        for (remove in listOf(false, true)) {
            val selected = if (remove) profile else profile.copy(chrootInstance = null)
            fixture(selected).use { fixture ->
                val root = fixture.authority.accessSnapshot()
                val confirmed = ChrootRuntimeSession("confirmed-transaction", root)
                fixture.session.value = confirmed
                val checkedSession = AtomicReference<ChrootRuntimeSession?>()
                fun changeConnection(expected: ChrootRuntimeSession?) {
                    checkedSession.set(expected)
                    fixture.revokeSession()
                    fixture.session.value = ChrootRuntimeSession("replacement-session", confirmed.rootState)
                    fixture.runtimeFactory.checkSession(requireNotNull(expected))
                }
                if (remove) {
                    coEvery { fixture.installer.removeFromUser(instance, root, any(), any()) } coAnswers {
                        changeConnection(thirdArg())
                        arg<suspend () -> Unit>(3).invoke()
                    }
                } else {
                    coEvery { fixture.installer.installFromUser(selected, root, any(), any()) } coAnswers {
                        changeConnection(thirdArg())
                        arg<suspend (ChrootInstance) -> Unit>(3).invoke(instance)
                        instance
                    }
                }
                val failure = runCatching {
                    if (remove) fixture.facade.removeInstanceFromUser(selected, root)
                    else fixture.facade.createInstanceFromUser(selected, root)
                }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertSame(confirmed, checkedSession.get())
                assertNotEquals(confirmed.id, fixture.session.value!!.id)
                assertEquals(confirmed.rootState, fixture.session.value!!.rootState)
                coVerify(exactly = 1) { fixture.runtimeFactory.ensureFromUser(root) }
                coVerify(exactly = 0) { fixture.settings.publishChrootInstance(any(), any(), any()) }
            }
        }
    }

    @Test fun configurationAndRootRevocationCannotReuseAnOldCapture() = runTest {
        fixture().use { fixture ->
            val capture = fixture.facade.capture(profile, 7, workspace, owner, "source-1") {}
            fixture.revision.value = 8
            fixture.revokeConfiguration()
            assertTrue(runCatching { capture.checkCurrent() }.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
            assertEquals(0, fixture.facade.state(profile).authorizedOwners)
            fixture.authority.setEnabled(false)
            assertFalse(fixture.facade.state(profile).rootEnabled)
            assertTrue(fixture.facade.pendingAuthorizations.value.isEmpty())
            assertEquals(0, fixture.manager.instanceState(instance.id).authorizedOwners)
            assertTrue(runCatching { fixture.facade.capture(profile, 8, workspace, owner, "source-1") {} }.isFailure)
            coVerify(exactly = 1) { fixture.runtimeFactory.ensureSession(any()) }
            coVerify(exactly = 0) { fixture.runtimeFactory.ensureFromUser(any()) }
        }
    }
}
