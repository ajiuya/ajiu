package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import com.aicode.testing.ChrootVerificationForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootRuntimeManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = "0123456789abcdef01234567"
    private val rootfs = ChrootFileIdentity(5L, 19L)
    private val owner = "chat:session:call"

    private class BytesPipe : InputStream() {
        private val gate = java.lang.Object()
        private val bytes = ArrayDeque<Byte>()
        private var ended = false
        fun offer(next: ByteArray) = synchronized(gate) {
            if (!ended) next.forEach(bytes::addLast)
            gate.notifyAll()
        }
        override fun read(): Int = synchronized(gate) {
            while (bytes.isEmpty() && !ended) gate.wait()
            if (bytes.isEmpty()) -1 else bytes.removeFirst().toInt() and 0xff
        }
        override fun read(target: ByteArray, offset: Int, count: Int): Int {
            if (count == 0) return 0
            val first = read()
            if (first < 0) return -1
            target[offset] = first.toByte()
            return synchronized(gate) {
                var size = 1
                while (size < count && bytes.isNotEmpty()) target[offset + size++] = bytes.removeFirst()
                size
            }
        }
        override fun close() = synchronized(gate) {
            ended = true
            gate.notifyAll()
        }
    }

    private class SnapshotStderr(
        private val bytes: ByteArray,
        private val snapshot: Int = bytes.size - 1,
        private val maximumRead: Int = Int.MAX_VALUE,
        initiallyReadable: Boolean = true
    ) : InputStream() {
        val firstReadStarted = CompletableDeferred<Unit>()
        val firstReads = AtomicInteger()
        val snapshots = AtomicInteger()
        val bulkReads = AtomicInteger()
        val requestedBytes = AtomicInteger()
        private val released = CountDownLatch(if (initiallyReadable) 0 else 1)
        private var offset = 0

        fun release() { released.countDown() }

        override fun read(): Int {
            check(firstReads.incrementAndGet() == 1)
            firstReadStarted.complete(Unit)
            check(released.await(5L, TimeUnit.SECONDS))
            return if (bytes.isEmpty()) -1 else bytes[offset++].toInt() and 0xff
        }

        override fun available(): Int {
            check(snapshots.incrementAndGet() == 1)
            return snapshot
        }

        override fun read(target: ByteArray, targetOffset: Int, count: Int): Int {
            check(bulkReads.incrementAndGet() == 1)
            requestedBytes.set(count)
            val size = minOf(count, maximumRead, bytes.size - offset)
            bytes.copyInto(target, targetOffset, offset, offset + size)
            offset += size
            return size
        }

        override fun close() { release() }
    }

    private class FakeProcess : Process() {
        val stdout = BytesPipe()
        val eofWritten = CompletableDeferred<Unit>()
        val launchWritten = CompletableDeferred<Unit>()
        @Volatile var inputFailure: IOException? = null
        @Volatile var helperErrorStream: InputStream = ByteArrayInputStream(byteArrayOf())
        @Volatile var wrapperStatus: Int? = 0
        val stdin = object : ByteArrayOutputStream() {
            @Synchronized
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                inputFailure?.let { throw it }
                super.write(bytes, offset, count)
            }
            override fun flush() {
                super.flush()
                val frames = inputFrames()
                if (frames.contains(ChrootFrameType.LAUNCH)) launchWritten.complete(Unit)
                if (frames.contains(ChrootFrameType.STDIN_EOF)) eofWritten.complete(Unit)
            }
        }
        val alive = AtomicBoolean(true)
        val destroys = AtomicInteger()
        fun inputFrames(): List<ChrootFrameType> = synchronized(stdin) {
            val input = ByteArrayInputStream(stdin.toByteArray())
            buildList {
                while (true) add((ChrootWireProtocol.read(input) ?: break).type)
            }
        }
        fun frame(type: ChrootFrameType, body: ByteArray = byteArrayOf()) {
            stdout.offer(ByteArrayOutputStream().also { ChrootWireProtocol.write(it, type, body) }.toByteArray())
        }
        fun exit(code: Int = 37) = frame(ChrootFrameType.EXIT, ByteBuffer.allocate(8).putInt(0).putInt(code).array())
        fun finish() {
            alive.set(false)
            stdout.close()
            helperErrorStream.close()
        }
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = helperErrorStream
        override fun getOutputStream(): OutputStream = stdin
        override fun isAlive(): Boolean = alive.get()
        override fun exitValue(): Int = if (alive.get()) throw IllegalThreadStateException() else
            wrapperStatus ?: throw IllegalThreadStateException("Wrapper exit status is unknown")
        override fun waitFor(): Int = throw AssertionError("Wrapper exit is never a guest task exit")
        override fun destroy() { destroys.incrementAndGet() }
    }

    private class FakeFactory : ChrootProcessFactory {
        val process = FakeProcess()
        val processes = Collections.synchronizedList(mutableListOf(process))
        val starts = AtomicInteger()
        val captures = AtomicInteger()
        var onStart: () -> Unit = {}
        var onCapture: () -> Unit = {}
        override fun captureHelper(): ChrootHelperIdentity {
            captures.incrementAndGet()
            onCapture()
            return ChrootHelperIdentity("/trusted/helper", 1L, 2L, 3L, "a".repeat(64))
        }
        override fun start(helper: ChrootHelperIdentity): Process {
            onStart()
            val index = starts.getAndIncrement()
            return synchronized(processes) {
                if (index == processes.size) processes.add(FakeProcess())
                processes[index]
            }
        }
    }

    private class FakeControl : ChrootControlTransport {
        val authenticated = CompletableDeferred<Unit>()
        val awaiting = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        val sent = Collections.synchronizedList(mutableListOf<ChrootFrameType>())
        val closed = AtomicBoolean()
        @Volatile var reject = false
        @Volatile var listenerFailure: IOException? = null
        @Volatile var handshakeFailure: IOException? = null
        var onSend: (ChrootFrameType) -> Unit = {}
        override suspend fun awaitHelper() {
            awaiting.complete(Unit)
            handshakeFailure?.let { throw it }
            authenticated.await()
            accepted.complete(Unit)
        }
        override fun trySend(type: ChrootFrameType, body: ByteArray): Boolean {
            if (closed.get() || reject) return false
            sent.add(type)
            onSend(type)
            return true
        }
        override fun close() { closed.set(true) }
    }

    private data class Fixture(
        val authority: RootAuthorityCoordinator,
        val root: RootAccessState,
        val recovery: ChrootRecoveryRepository,
        val factory: FakeFactory,
        val control: FakeControl,
        val controls: ConcurrentHashMap<String, FakeControl>,
        val scope: CoroutineScope,
        val manager: ChrootRuntimeManager
    ) : AutoCloseable {
        override fun close() {
            synchronized(factory.processes) { factory.processes.toList() }.forEach { it.finish() }
            controls.values.forEach { it.close() }
            scope.cancel()
        }
    }

    private suspend fun TestScope.fixture(
        recoveryFile: File = File(temporary.newFolder(), "journal.bin"),
        configure: (FakeFactory, FakeControl) -> Unit = { _, _ -> }
    ): Fixture {
        val repository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
        val authority = RootAuthorityCoordinator(repository, backgroundScope)
        authority.setEnabled(true)
        val root = authority.accessSnapshot()
        runCurrent()
        val recovery = ChrootRecoveryRepository(recoveryFile)
        val factory = FakeFactory()
        val control = FakeControl()
        configure(factory, control)
        val controls = ConcurrentHashMap<String, FakeControl>().apply { put("89abcdef0123456789abcdef", control) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = ChrootRuntimeManager(authority, factory, recovery, scope, Dispatchers.IO, {
            controls.getOrPut(it.launchId) { FakeControl() }.also { channel -> channel.listenerFailure?.let { throw it } }
        }) {
            System.nanoTime() / 1_000_000L
        }
        return Fixture(authority, root, recovery, factory, control, controls, scope, manager)
    }

    private fun spec(
        owner: String = this.owner,
        id: String = instance,
        launch: String = "89abcdef0123456789abcdef",
        root: ChrootFileIdentity = rootfs,
        token: ByteArray = ByteArray(32) { 1 },
        argv: List<String> = listOf("/bin/sh", "-c", "printf done"),
        identity: ChrootAppIdentity = ChrootAppIdentity(10445, 10445, listOf(3003))
    ) = ChrootLaunch.freeze(
        id, launch, owner, token, root, identity,
        ChrootIoMode.STDIO, 15_000, 30_000, "/", argv, listOf("HOME=/root"), emptyList()
    )

    private suspend fun Fixture.lease(revision: Long = 1L, profile: String = "profile", owner: String = this@ChrootRuntimeManagerTest.owner): String =
        manager.allowBinding(owner, profile, revision, instance, rootfs, authority.accessSnapshot())!!

    @Test fun policyBindingRechecksTheIssuanceBeforeMintingALeaseAndPreservesSingleUsePermits() = runTest {
        fixture().use { fixture ->
            val denied = runCatching {
                fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root) {
                    throw CancellationException("issuance revoked")
                }
            }
            assertTrue(denied.exceptionOrNull() is CancellationException)
            assertEquals(0, fixture.manager.instanceState(instance).authorizedOwners)
            assertEquals(0, fixture.factory.starts.get())
            val issuanceChecks = AtomicInteger()
            val lease = checkNotNull(fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root) {
                issuanceChecks.incrementAndGet()
                Unit
            })
            assertTrue(fixture.manager.isLeaseCurrent(lease))
            assertNull(fixture.manager.authorize(lease, spec(owner = "plugin:another-owner"), 1))
            val launch = spec()
            val permit = checkNotNull(fixture.manager.authorize(lease, launch, 1))
            assertEquals(1, issuanceChecks.get())
            fixture.manager.revokeOwner(owner)
            assertFalse(fixture.manager.isLeaseCurrent(lease))
            assertNull(fixture.manager.launch(lease, permit, launch) {})
            assertNull(fixture.manager.launch(lease, permit, launch) {})
            assertEquals(0, fixture.factory.starts.get())
        }
    }

    private suspend fun <T> awaitSignal(signal: CompletableDeferred<T>): T = withContext(Dispatchers.IO) {
        withTimeout(5_000L) { signal.await() }
    }

    private suspend fun awaitFailure(handle: ChrootLaunchHandle): ChrootRuntimeFailure = withContext(Dispatchers.IO) {
        withTimeout(7_000L) { handle.failure.filterNotNull().first() }
    }

    private suspend fun awaitNativeFailure(handle: ChrootLaunchHandle): ChrootRuntimeFailure = withContext(Dispatchers.IO) {
        withTimeout(5_000L) { handle.nativeFailure.filterNotNull().first() }
    }

    private suspend fun Fixture.pendingLaunch(): ChrootLaunchHandle {
        val lease = lease()
        val permit = manager.authorize(lease, spec(), 1L)!!
        return manager.launch(lease, permit, spec()) {}!!
    }

    private suspend fun readyResult(handle: ChrootLaunchHandle): Boolean = withContext(Dispatchers.IO) {
        withTimeout(5_000L) { handle.awaitReady() }
    }

    private suspend fun cleanupResult(handle: ChrootLaunchHandle): ChrootCleanupResult = withContext(Dispatchers.IO) {
        withTimeout(5_000L) { handle.awaitCleanup() }
    }

    private suspend fun eventually(predicate: () -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(5_000L) { while (!predicate()) delay(5L) }
    }

    private suspend fun Fixture.start(launchSpec: ChrootLaunch = spec()): ChrootLaunchHandle {
        val lease = lease()
        val permit = manager.authorize(lease, launchSpec, 1L)!!
        val handle = manager.launch(lease, permit, launchSpec) {}!!
        eventually { factory.starts.get() == 1 }
        return handle
    }

    private suspend fun Fixture.ready(launchSpec: ChrootLaunch = spec()) {
        factory.process.frame(ChrootFrameType.READY, launchSpec.token())
        control.authenticated.complete(Unit)
        eventually { control.sent.contains(ChrootFrameType.GO) }
    }

    @Test
    fun closingStdinBeforeBothReadyChannelsSendsExactlyOneEofAfterGo() = runTest {
        for (controlFirst in listOf(false, true)) {
            fixture().use { fixture ->
                val handle = fixture.start()
                awaitSignal(fixture.control.awaiting)
                val readiness = async(start = CoroutineStart.UNDISPATCHED) { handle.awaitReady() }
                assertFalse(readiness.isCompleted)
                handle.outputStream.close()
                handle.outputStream.close()
                assertEquals(listOf(ChrootFrameType.LAUNCH), fixture.factory.process.inputFrames())
                if (controlFirst) {
                    fixture.control.authenticated.complete(Unit)
                    awaitSignal(fixture.control.accepted)
                    assertFalse(readiness.isCompleted)
                    assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
                    fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
                } else {
                    fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
                    assertFalse(readiness.isCompleted)
                    assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
                    fixture.control.authenticated.complete(Unit)
                }
                assertTrue(readyResult(handle))
                assertTrue(readiness.await())
                awaitSignal(fixture.factory.process.eofWritten)
                handle.outputStream.close()
                assertEquals(listOf(ChrootFrameType.LAUNCH, ChrootFrameType.STDIN_EOF), fixture.factory.process.inputFrames())
                try {
                    handle.outputStream.write(7)
                    throw AssertionError("A requested stdin EOF must reject later writes")
                } catch (_: IOException) {
                }
                fixture.factory.process.exit(0)
                fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                fixture.factory.process.finish()
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            }
        }
    }

    @Test
    fun closeRacingWithGoUsesTheSameInputLockAndSendsOnlyOneEof() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            val release = CompletableDeferred<Unit>()
            val closing = async(Dispatchers.IO) {
                release.await()
                handle.outputStream.close()
            }
            val preparing = async(Dispatchers.IO) {
                release.await()
                fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
                fixture.control.authenticated.complete(Unit)
            }
            release.complete(Unit)
            closing.await()
            preparing.await()
            assertTrue(readyResult(handle))
            awaitSignal(fixture.factory.process.eofWritten)
            handle.outputStream.close()
            assertEquals(listOf(ChrootFrameType.LAUNCH, ChrootFrameType.STDIN_EOF), fixture.factory.process.inputFrames())
            fixture.factory.process.exit(0)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
        }
    }

    @Test
    fun writeBeforeGoRejectsWithoutLatchingInputClosedAndLaterWriteSucceeds() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            try {
                handle.outputStream.write(1)
                throw AssertionError("Input must not be sent before GO")
            } catch (_: IOException) {
            }
            assertEquals(listOf(ChrootFrameType.LAUNCH), fixture.factory.process.inputFrames())
            fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
            fixture.control.authenticated.complete(Unit)
            assertTrue(readyResult(handle))
            handle.outputStream.write(byteArrayOf(2, 3))
            handle.outputStream.close()
            assertEquals(listOf(ChrootFrameType.LAUNCH, ChrootFrameType.STDIN, ChrootFrameType.STDIN_EOF),
                fixture.factory.process.inputFrames())
            fixture.factory.process.exit(0)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
        }
    }

    @Test
    fun stopBeforeGoResolvesReadinessFalseAndDiscardsPendingEof() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            handle.outputStream.close()
            handle.stop()
            assertFalse(readyResult(handle))
            fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
            fixture.control.authenticated.complete(Unit)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            assertEquals(listOf(ChrootFrameType.LAUNCH), fixture.factory.process.inputFrames())
        }
    }

    @Test
    fun noLeaseOrWrongOwnerProfileInstanceRootfsAndRootGenerationNeverStartsFactory() = runTest {
        fixture().use { fixture ->
            assertNull(fixture.manager.authorize("invented", spec(), 1L))
            assertNull(fixture.manager.launch("invented", "invented", spec()) {})
            assertNull(fixture.manager.activateFromUser("", "profile", 1L, instance, rootfs, fixture.root))
            assertNull(fixture.manager.activateFromUser(owner, "profile", 1L, instance, rootfs, fixture.root.copy(generation = 99L)))
            val lease = fixture.lease()
            assertNull(fixture.manager.authorize(lease, spec(owner = "terminal:other"), 1L))
            assertNull(fixture.manager.authorize(lease, spec(id = "abcdef0123456789abcdef01"), 1L))
            assertNull(fixture.manager.authorize(lease, spec(root = ChrootFileIdentity(5L, 20L)), 1L))
            assertNull(fixture.manager.authorize(lease, spec(), 2L))
            fixture.authority.setEnabled(false)
            fixture.authority.setEnabled(true)
            assertNull(fixture.manager.authorize(lease, spec(), 1L))
            assertEquals(0, fixture.factory.starts.get())
            assertEquals(0, fixture.factory.captures.get())
        }
    }

    @Test
    fun payloadMismatchConsumesPermitAndNoGrantCanBeReplayed() = runTest {
        fixture().use { fixture ->
            val lease = fixture.lease()
            val launch = spec()
            val permit = fixture.manager.authorize(lease, launch, 1L)!!
            for (changed in listOf(
                spec(argv = listOf("/bin/false")),
                spec(token = ByteArray(32) { 2 }),
                spec(identity = ChrootAppIdentity(10445, 10445, listOf(3003, 9997))),
                spec(identity = ChrootAppIdentity(10446, 10446, listOf(3003)))
            )) {
                val changedPermit = fixture.manager.authorize(lease, launch, 1L)!!
                assertNull(fixture.manager.launch(lease, changedPermit, changed) {})
                assertNull(fixture.manager.launch(lease, changedPermit, launch) {})
            }
            assertNull(fixture.manager.launch(lease, permit, spec(argv = listOf("/bin/false"))) {})
            assertNull(fixture.manager.launch(lease, permit, launch) {})
            val wrongLease = fixture.manager.authorize(lease, launch, 1L)!!
            assertNull(fixture.manager.launch("invented", wrongLease, launch) {})
            assertNull(fixture.manager.launch(lease, wrongLease, launch) {})
            assertEquals(0, fixture.factory.starts.get())
            val valid = fixture.manager.authorize(lease, launch, 1L)!!
            assertNotEquals(permit, valid)
            val handle = fixture.manager.launch(lease, valid, launch) {}!!
            assertNull(fixture.manager.launch(lease, valid, launch) {})
            eventually { fixture.factory.starts.get() == 1 }
            fixture.factory.process.stdout.close()
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
        }
    }

    @Test
    fun configurationRevisionHighWaterMarkPreventsAToBToAAndRevokesOldLeases() = runTest {
        fixture().use { fixture ->
            val old = fixture.lease(1L)
            val oldPermit = fixture.manager.authorize(old, spec(), 1L)!!
            val next = fixture.lease(2L)
            assertNull(fixture.manager.launch(old, oldPermit, spec()) {})
            assertNull(fixture.manager.activateFromUser(owner, "profile", 1L, instance, rootfs, fixture.root))
            assertNull(fixture.manager.activateFromUser(owner, "profile", 2L, instance, ChrootFileIdentity(5L, 20L), fixture.root))
            assertNotNull(fixture.manager.authorize(next, spec(), 2L))
            fixture.manager.revokeOwner(owner)
            assertNull(fixture.manager.authorize(next, spec(), 2L))
            assertEquals(0, fixture.factory.starts.get())
        }
    }

    @Test
    fun sourceCaptureFailureConsumesPermitBeforeCreatingAnything() = runTest {
        fixture().use { fixture ->
            val lease = fixture.lease()
            val permit = fixture.manager.authorize(lease, spec(), 1L)!!
            assertNull(fixture.manager.launch(lease, permit, spec()) { throw IllegalStateException("capture changed") })
            assertNull(fixture.manager.launch(lease, permit, spec()) {})
            assertEquals(0, fixture.factory.starts.get())
        }
    }

    @Test
    fun intentIsPersistedBeforeFactoryStartAndGuestExitIsIndependentFromCleanup() = runTest {
        fixture().use { fixture ->
            fixture.factory.onStart = {
                assertEquals(ChrootRecoveryPhase.INTENT, fixture.recovery.snapshot().single().phase)
            }
            val handle = fixture.start()
            fixture.ready()
            fixture.factory.process.frame(ChrootFrameType.STDOUT, "out".toByteArray())
            fixture.factory.process.frame(ChrootFrameType.STDERR, "err".toByteArray())
            fixture.factory.process.exit(37)
            eventually { handle.taskState.value != null }
            assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(37, null)), handle.awaitTaskExit())
            assertEquals(37, handle.exitValue())
            assertNull(handle.cleanupState.value)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootCleanupResult.VERIFIED, handle.awaitCleanup())
            assertEquals("out", handle.inputStream.readBytes().toString(Charsets.UTF_8))
            assertEquals("err", handle.errorStream.readBytes().toString(Charsets.UTF_8))
            assertTrue(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun rootCloseBeforeEitherReadyChannelPreventsGoAndWaitsForStopOnlyCleanup() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            handle.outputStream.close()
            val closing = async(Dispatchers.IO) { fixture.authority.setEnabled(false) }
            runCurrent()
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            assertFalse(readyResult(handle))
            fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
            fixture.control.authenticated.complete(Unit)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            eventually { handle.cleanupState.value != null }
            closing.await()
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
            assertEquals(ChrootCleanupResult.VERIFIED, handle.awaitCleanup())
            assertFalse(fixture.authority.accessSnapshot().enabled)
            assertEquals(listOf(ChrootFrameType.LAUNCH), fixture.factory.process.inputFrames())
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun wrongTokenExtraReadyAndMalformedStdoutAreUnknownWithoutResynchronization() = runTest {
        for (failure in listOf("token", "repeat", "garbage")) {
            fixture().use { fixture ->
                val handle = fixture.start()
                if (failure == "garbage") {
                    fixture.factory.process.stdout.offer(ByteArray(12) { 65 })
                } else if (failure == "token") {
                    fixture.factory.process.frame(ChrootFrameType.READY, ByteArray(32) { 2 })
                } else {
                    fixture.ready()
                    fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
                }
                eventually { handle.cleanupState.value != null }
                assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertEquals(when (failure) {
                    "token" -> ChrootRuntimeFailureStage.READY_TOKEN
                    "repeat" -> ChrootRuntimeFailureStage.FRAME_SEQUENCE
                    else -> ChrootRuntimeFailureStage.FRAME_READ
                }, handle.failure.value?.stage)
                assertFalse(fixture.recovery.canStart(instance))
                fixture.authority.setEnabled(false)
                fixture.authority.setEnabled(true)
                assertNull(fixture.manager.activateFromUser(owner, "profile", 1L, instance, rootfs, fixture.authority.accessSnapshot()))
                assertEquals(0, fixture.factory.process.destroys.get())
            }
        }
    }

    @Test
    fun outputOverflowStopsWithoutBlockingProtocolReaderOrClaimingCleanup() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            repeat(9) { fixture.factory.process.frame(ChrootFrameType.STDOUT, ByteArray(65_536) { 1 }) }
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
            assertTrue(handle.inputStream.available() <= ChrootLaunchHandle.MAX_BUFFER_BYTES)
            assertEquals(ChrootRuntimeFailureStage.OUTPUT_BUFFER, handle.failure.value?.stage)
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun eofAfterSuccessfulTaskExitDoesNotInventVerifiedCleanup() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            fixture.factory.process.exit(0)
            fixture.factory.process.finish()
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(0, null)), handle.awaitTaskExit())
            assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
            assertEquals(ChrootRuntimeFailureStage.HELPER_STDOUT_EOF, handle.failure.value?.stage)
            assertFalse(fixture.recovery.canStart(instance))
        }
    }

    @Test
    fun twoOwnersCanRunConcurrentLaunchesInTheSameInstanceAndStopInstanceStopsBoth() = runTest {
        fixture().use { fixture ->
            val firstSpec = spec()
            val secondSpec = spec(owner = "terminal:second", launch = "abcdef0123456789abcdef01", token = ByteArray(32) { 2 })
            val firstLease = fixture.lease()
            val secondLease = fixture.lease(owner = secondSpec.ownerIdentity)
            assertNull(fixture.manager.authorize(firstLease, secondSpec, 1L))
            assertNull(fixture.manager.authorize(secondLease, firstSpec, 1L))
            val firstPermit = fixture.manager.authorize(firstLease, firstSpec, 1L)!!
            val secondPermit = fixture.manager.authorize(secondLease, secondSpec, 1L)!!
            val first = fixture.manager.launch(firstLease, firstPermit, firstSpec) {}!!
            val second = fixture.manager.launch(secondLease, secondPermit, secondSpec) {}!!
            eventually { fixture.factory.starts.get() == 2 }
            val processes = synchronized(fixture.factory.processes) { fixture.factory.processes.toList() }
            eventually { processes.all { it.stdin.size() > 0 } }
            val pairs = listOf(firstSpec, secondSpec).associate { launchSpec ->
                launchSpec.launchId to processes.single { process ->
                    ChrootWireProtocol.read(ByteArrayInputStream(process.stdin.toByteArray()))?.body
                        ?.contentEquals(launchSpec.payload()) == true
                }
            }
            for (launchSpec in listOf(firstSpec, secondSpec)) {
                val control = fixture.controls.getValue(launchSpec.launchId)
                pairs.getValue(launchSpec.launchId).frame(ChrootFrameType.READY, launchSpec.token())
                control.authenticated.complete(Unit)
                eventually { control.sent.contains(ChrootFrameType.GO) }
            }
            assertTrue(first.isAlive)
            assertTrue(second.isAlive)
            assertTrue(fixture.recovery.blockedInstances.value.isEmpty())
            fixture.manager.stopInstance(instance)
            eventually { fixture.controls.values.all { it.sent.contains(ChrootFrameType.STOP) } }
            pairs.values.forEach {
                it.exit(0)
                it.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                it.finish()
            }
            eventually { first.cleanupState.value != null && second.cleanupState.value != null }
            assertEquals(ChrootCleanupResult.VERIFIED, first.awaitCleanup())
            assertEquals(ChrootCleanupResult.VERIFIED, second.awaitCleanup())
            assertNull(fixture.manager.authorize(firstLease, spec(launch = "abcdefabcdefabcdefabcdef"), 1L))
            assertTrue(processes.all { it.destroys.get() == 0 })
        }
    }

    @Test
    fun ownerRevocationCancelsAnUnconsumedBrokerLaunchWithoutClaimingVerifiedCleanup() = runTest {
        fixture().use { fixture ->
            val created = CompletableDeferred<ChrootBrokerProcess>()
            val proxy = ChrootBrokerProcess {}
            val factory = object : ChrootProcessFactory {
                override fun captureHelper() = ChrootHelperIdentity("/trusted/helper", 1, 2, 3, "a".repeat(64))
                override fun start(helper: ChrootHelperIdentity): Process = proxy.also { created.complete(it) }
            }
            val manager = ChrootRuntimeManager(fixture.authority, factory, fixture.recovery, fixture.scope,
                Dispatchers.IO, { fixture.control }, { System.nanoTime() / 1_000_000L })
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            awaitSignal(created)
            manager.revokeOwner(owner)
            assertFalse(proxy.tryDispatch())
            assertEquals(0, manager.instanceState(instance).authorizedOwners)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertTrue(manager.instanceState(instance).blocked)
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
        }
    }

    @Test
    fun nativeCleanupAndEofWithoutARealZeroWrapperStatusRemainUnknown() = runTest {
        for (status in listOf(126, null)) {
            fixture().use { fixture ->
                val handle = fixture.start()
                fixture.ready()
                fixture.factory.process.exit(37)
                eventually { handle.taskState.value != null }
                fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                fixture.factory.process.wrapperStatus = status
                fixture.factory.process.finish()
                assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(37, null)), handle.awaitTaskExit())
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(ChrootRuntimeFailureStage.HELPER_EXIT_STATUS, handle.failure.value?.stage)
                assertTrue(fixture.manager.instanceState(instance).blocked)
                assertFalse(fixture.recovery.canStart(instance))
            }
        }
    }

    @Test
    fun changedSourceAfterGoRejectsInputAndSynchronouslyMarksStopOnly() = runTest {
        fixture().use { fixture ->
            val current = AtomicBoolean(true)
            val lease = fixture.lease()
            val permit = fixture.manager.authorize(lease, spec(), 1L)!!
            val handle = fixture.manager.launch(lease, permit, spec()) { check(current.get()) }!!
            eventually { fixture.factory.starts.get() == 1 }
            fixture.ready()
            assertTrue(readyResult(handle))
            current.set(false)
            assertTrue(runCatching { handle.outputStream.write(byteArrayOf(7, 8)) }.exceptionOrNull() is IOException)
            assertFalse(handle.resize(80, 24))
            assertFalse(fixture.factory.process.inputFrames().contains(ChrootFrameType.STDIN))
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
        }
    }

    @Test
    fun closingOneTaskRetainsItsOwnerLeaseUntilExplicitInstanceStop() = runTest {
        fixture().use { fixture ->
            val lease = fixture.lease()
            val permit = fixture.manager.authorize(lease, spec(), 1L)!!
            val handle = fixture.manager.launch(lease, permit, spec()) {}!!
            eventually { fixture.factory.starts.get() == 1 }
            fixture.ready()
            assertEquals(1, fixture.manager.instanceState(instance).activeLaunches)
            handle.stop()
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertEquals(0, fixture.manager.instanceState(instance).activeLaunches)
            assertEquals(1, fixture.manager.instanceState(instance).authorizedOwners)
            assertNotNull(fixture.manager.authorize(lease, spec(launch = "abcdefabcdefabcdefabcdef"), 1L))
            assertTrue(fixture.manager.stopAndAwaitInstance(instance).isEmpty())
            assertEquals(0, fixture.manager.instanceState(instance).authorizedOwners)
            assertNull(fixture.manager.authorize(lease, spec(launch = "fedcbafedcbafedcbafedcba"), 1L))
        }
    }

    @Test
    fun instanceStopWaitsForCleanupEvidenceRatherThanOnlyTaskExit() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            val stopped = async(Dispatchers.IO) { fixture.manager.stopAndAwaitInstance(instance) }
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            fixture.factory.process.exit(0)
            eventually { handle.taskState.value != null }
            assertFalse(stopped.isCompleted)
            assertEquals(1, fixture.manager.instanceState(instance).activeLaunches)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            assertEquals(listOf(ChrootCleanupResult.VERIFIED), stopped.await())
            assertEquals(0, fixture.manager.instanceState(instance).activeLaunches)
        }
    }

    @Test
    fun changedCaptureBetweenStartingAndReadyPreventsGo() = runTest {
        fixture().use { fixture ->
            val capture = AtomicBoolean(true)
            val lease = fixture.lease()
            val permit = fixture.manager.authorize(lease, spec(), 1L)!!
            val handle = fixture.manager.launch(lease, permit, spec()) { check(capture.get()) }!!
            eventually { fixture.factory.starts.get() == 1 }
            capture.set(false)
            fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
            fixture.control.authenticated.complete(Unit)
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootCleanupResult.VERIFIED, handle.awaitCleanup())
        }
    }

    @Test
    fun foregroundLossAtCaptureBeforeGoAndHeartbeatFailsClosedWithoutAStopCallback() = runTest {
        for (step in listOf("capture", "before-go", "heartbeat")) {
            val foreground = ChrootVerificationForeground().apply {
                onStarted()
                onResumed()
                onWindowFocusChanged(true)
                checkCurrent()
            }
            val loseFocus = {
                foreground.onWindowFocusChanged(false)
                foreground.onWindowFocusChanged(true)
            }
            fixture(configure = { factory, _ ->
                if (step == "capture") factory.onCapture = loseFocus
            }).use { fixture ->
                val lease = fixture.lease()
                val launch = spec()
                val permit = fixture.manager.authorize(lease, launch, 1L)!!
                val handle = fixture.manager.launch(lease, permit, launch, foreground::checkCurrent)!!
                if (step != "capture") {
                    awaitSignal(fixture.control.awaiting)
                    if (step == "heartbeat") {
                        fixture.factory.process.frame(ChrootFrameType.READY, launch.token())
                        fixture.control.authenticated.complete(Unit)
                        assertTrue(readyResult(handle))
                        eventually { fixture.recovery.snapshot().single().phase == ChrootRecoveryPhase.RUNNING }
                    }
                    loseFocus()
                    if (step == "before-go") {
                        fixture.factory.process.frame(ChrootFrameType.READY, launch.token())
                        fixture.control.authenticated.complete(Unit)
                    }
                    val failure = awaitFailure(handle)
                    assertEquals(if (step == "before-go") ChrootRuntimeFailureStage.GO else ChrootRuntimeFailureStage.CONTROL_WRITE, failure.stage)
                    assertEquals(if (step == "before-go") ChrootRuntimeFailureKind.STARTUP else ChrootRuntimeFailureKind.TRANSPORT, failure.kind)
                    eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
                    fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                    fixture.factory.process.finish()
                }
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertEquals(step == "heartbeat", readyResult(handle))
                assertTrue(foreground.snapshot().lost)
                assertEquals(1, fixture.factory.captures.get())
                assertEquals(if (step == "capture") 0 else 1, fixture.factory.starts.get())
                assertEquals(if (step == "heartbeat") 1 else 0, fixture.control.sent.count { it == ChrootFrameType.GO })
                assertFalse(fixture.control.sent.contains(ChrootFrameType.HEARTBEAT))
                assertEquals(0, fixture.factory.process.destroys.get())
                assertTrue(fixture.recovery.canStart(instance))
            }
        }
    }

    @Test
    fun helperBirthFailureIsUnknownAndDoesNotReuseTheLaunchIntent() = runTest {
        fixture().use { fixture ->
            fixture.factory.onStart = { throw IOException("fixed startup failure") }
            val lease = fixture.lease()
            val permit = fixture.manager.authorize(lease, spec(), 1L)!!
            val handle = fixture.manager.launch(lease, permit, spec()) {}!!
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
            assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
            assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
            assertEquals(ChrootRuntimeFailureStage.PROCESS_START, handle.failure.value?.stage)
            assertNull(fixture.manager.authorize(lease, spec(), 1L))
        }
    }

    @Test
    fun stopWithoutNativeCleanupEvidenceTimesOutUnknownAndNeverKillsTheWrapper() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            handle.stop()
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            assertNull(handle.cleanupState.value)
            eventually { handle.cleanupState.value != null }
            assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
            assertTrue(fixture.factory.process.isAlive)
            assertEquals(ChrootRuntimeFailureKind.TIMEOUT, handle.failure.value?.kind)
            assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
            assertEquals(0, fixture.factory.process.destroys.get())
            assertFalse(fixture.recovery.canStart(instance))
        }
    }

    @Test
    fun startupFailuresRetainFixedStagesWithoutChangingUntouchedOrUnknownCleanup() = runTest {
        val expected = mapOf(
            "capture" to ChrootRuntimeFailureStage.HELPER_CAPTURE,
            "listener" to ChrootRuntimeFailureStage.CONTROL_LISTENER,
            "start" to ChrootRuntimeFailureStage.PROCESS_START,
            "launch" to ChrootRuntimeFailureStage.LAUNCH_WRITE,
            "handshake" to ChrootRuntimeFailureStage.CONTROL_HANDSHAKE
        )
        for ((step, stage) in expected) {
            fixture(configure = { factory, control ->
                val failure = IOException("private_credential_marker")
                when (step) {
                    "capture" -> factory.onCapture = { throw failure }
                    "listener" -> control.listenerFailure = failure
                    "start" -> factory.onStart = { throw failure }
                    "launch" -> factory.process.inputFailure = failure
                    "handshake" -> control.handshakeFailure = failure
                }
            }).use { fixture ->
                val handle = fixture.pendingLaunch()
                val failure = awaitFailure(handle)
                assertEquals(stage, failure.stage)
                assertEquals(if (step in setOf("launch", "handshake")) ChrootRuntimeFailureKind.TRANSPORT else ChrootRuntimeFailureKind.STARTUP, failure.kind)
                assertFalse(failure.toString().contains("private_credential_marker"))
                assertNull(failure.nativeCode)
                assertEquals(if (step in setOf("capture", "listener")) ChrootCleanupResult.VERIFIED else ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(failure, handle.failure.value)
                assertEquals(0, fixture.factory.process.destroys.get())
            }
        }
    }

    @Test
    fun intentJournalFailureIsDistinguishedWithoutStartingAHelper() = runTest {
        val parent = temporary.newFile()
        fixture(File(parent, "journal.bin")).use { fixture ->
            val handle = fixture.pendingLaunch()
            val failure = awaitFailure(handle)
            assertEquals(ChrootRuntimeFailureKind.STARTUP, failure.kind)
            assertEquals(ChrootRuntimeFailureStage.INTENT_JOURNAL, failure.stage)
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertEquals(0, fixture.factory.starts.get())
            assertFalse(fixture.recovery.isHealthy.value)
        }
    }

    @Test
    fun failedGoUsesFixedDiagnosticsAndPreservesStopOnlyUnknownCleanup() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            fixture.control.reject = true
            fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
            fixture.control.authenticated.complete(Unit)
            val failure = awaitFailure(handle)
            assertEquals(ChrootRuntimeFailureStage.GO, failure.stage)
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            assertFalse(readyResult(handle))
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals(failure, handle.failure.value)
            assertFalse(fixture.recovery.canStart(instance))
        }
    }

    @Test
    fun nativeErrorFrameIsRecordedBeforeExistingFailClosedCleanup() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            fixture.factory.process.frame(ChrootFrameType.ERROR,
                "code=prepare_failed;stage=file_worker_resource;errno=13".toByteArray())
            val failure = awaitFailure(handle)
            assertEquals(ChrootRuntimeFailureKind.NATIVE_ERROR, failure.kind)
            assertEquals("prepare_failed", failure.nativeCode)
            assertEquals("file_worker_resource", failure.nativeStage)
            assertEquals(13, failure.errno)
            assertTrue(failure.nativeFailureObserved)
            assertEquals(failure, handle.nativeFailure.value)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(failure, handle.failure.value)
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
        }
    }

    @Test
    fun invalidNativeErrorAndHelperStderrNeverExposeUntrustedBodyOrExceptionMessages() = runTest {
        for (mode in listOf("native", "stderr", "stderr-read")) {
            fixture(configure = { factory, _ ->
                if (mode == "stderr") factory.process.helperErrorStream = ByteArrayInputStream("private_credential_marker".toByteArray())
                if (mode == "stderr-read") factory.process.helperErrorStream = object : InputStream() {
                    override fun read(): Int = throw IOException("private_credential_marker")
                }
            }).use { fixture ->
                val handle = fixture.pendingLaunch()
                if (mode == "native") {
                    awaitSignal(fixture.control.awaiting)
                    fixture.factory.process.frame(ChrootFrameType.ERROR,
                        "code=prepare_failed;stage=private_credential_marker;errno=13".toByteArray())
                }
                val failure = awaitFailure(handle)
                assertEquals(when (mode) {
                    "native" -> ChrootRuntimeFailureStage.NATIVE_ERROR
                    "stderr" -> ChrootRuntimeFailureStage.HELPER_STDERR
                    else -> ChrootRuntimeFailureStage.HELPER_STDERR_READ
                }, failure.stage)
                assertEquals(if (mode == "native") ChrootRuntimeFailureKind.INVALID_NATIVE_ERROR else ChrootRuntimeFailureKind.TRANSPORT, failure.kind)
                assertFalse(failure.nativeFailureObserved)
                assertFalse(failure.toString().contains("private_credential_marker"))
                assertNull(handle.nativeFailure.value)
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(failure, handle.failure.value)
            }
        }
    }

    @Test
    fun failureCollectorsArePublishedOutsideTheSharedRootGate() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            awaitSignal(fixture.control.awaiting)
            val observed = CompletableDeferred<ChrootRuntimeFailure>()
            val otherThreadEntered = AtomicBoolean()
            val collector = fixture.scope.launch(Dispatchers.Unconfined) {
                val failure = handle.failure.filterNotNull().first()
                otherThreadEntered.set(CompletableFuture.supplyAsync<Boolean> {
                    fixture.authority.withCurrent(fixture.root.authorityId, fixture.root.generation) { true } == true
                }.get(2L, TimeUnit.SECONDS))
                observed.complete(failure)
            }
            fixture.factory.process.frame(ChrootFrameType.READY, ByteArray(32) { 2 })
            assertEquals(ChrootRuntimeFailureStage.READY_TOKEN, awaitSignal(observed).stage)
            assertTrue(otherThreadEntered.get())
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            collector.join()
        }
    }

    @Test
    fun handshakeAndPreparedReadyTimeoutsKeepTheirOriginalFiveSecondDeadline() = runTest {
        for (handshakeComplete in listOf(false, true)) {
            fixture().use { fixture ->
                val handle = fixture.start()
                awaitSignal(fixture.control.awaiting)
                if (handshakeComplete) {
                    fixture.control.authenticated.complete(Unit)
                    awaitSignal(fixture.control.accepted)
                }
                val failure = awaitFailure(handle)
                assertEquals(ChrootRuntimeFailureKind.TIMEOUT, failure.kind)
                assertEquals(if (handshakeComplete) ChrootRuntimeFailureStage.PREPARED_READY else ChrootRuntimeFailureStage.CONTROL_HANDSHAKE, failure.stage)
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
                assertEquals(failure, handle.failure.value)
            }
        }
    }

    @Test
    fun completeStderrMirrorAttachesNativeEvidenceWhileKeepingStderrFirstFailure() = runTest {
        val body = "code=prepare_failed;stage=app_control_authentication;errno=13"
        val stderr = SnapshotStderr("ajiu_runtime: $body\n".toByteArray())
        fixture(configure = { factory, _ -> factory.process.helperErrorStream = stderr }).use { fixture ->
            val handle = fixture.pendingLaunch()
            val first = awaitFailure(handle)
            val native = awaitNativeFailure(handle)
            assertEquals(ChrootRuntimeFailureStage.HELPER_STDERR, first.stage)
            assertEquals(ChrootRuntimeFailureKind.TRANSPORT, first.kind)
            assertFalse(first.nativeFailureObserved)
            assertEquals(ChrootRuntimeFailure.nativeError(body.toByteArray()), native)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals(first, handle.failure.value)
            assertEquals(1, stderr.firstReads.get())
            assertEquals(1, stderr.snapshots.get())
            assertEquals(1, stderr.bulkReads.get())
            assertEquals("ajiu_runtime: $body\n".toByteArray().size - 1, stderr.requestedBytes.get())
            assertFalse(readyResult(handle))
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun stdoutNativeErrorWinsBeforeLateStderrMirrorWithoutReplacingEitherSlot() = runTest {
        val firstBody = "code=prepare_failed;stage=file_worker_resource;errno=13"
        val stderr = SnapshotStderr("ajiu_runtime: code=cleanup_unknown;stage=resource_proof;errno=5\n".toByteArray(), initiallyReadable = false)
        fixture(configure = { factory, _ -> factory.process.helperErrorStream = stderr }).use { fixture ->
            val handle = fixture.start()
            awaitSignal(stderr.firstReadStarted)
            awaitSignal(fixture.control.awaiting)
            fixture.factory.process.frame(ChrootFrameType.ERROR, firstBody.toByteArray())
            val native = awaitNativeFailure(handle)
            assertEquals(ChrootRuntimeFailure.nativeError(firstBody.toByteArray()), native)
            assertEquals(native, awaitFailure(handle))
            stderr.release()
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals(native, handle.nativeFailure.value)
            assertEquals(native, handle.failure.value)
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun invalidOrPartialStderrSnapshotsStayUnknownWithoutInventingNativeEvidence() = runTest {
        val valid = "ajiu_runtime: code=prepare_failed;stage=app_control_authentication;errno=13\n".toByteArray()
        val cases = listOf(
            valid.copyOf(valid.size - 1),
            "su: private_credential_marker\n".toByteArray(),
            valid + valid,
            ByteArray(300) { 65 },
            "ajiu_runtime: code=prepare_failed;stage=".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + ";errno=13\n".toByteArray(),
            "ajiu_runtime: code=prepare_failed;stage=private_credential_marker;errno=13\n".toByteArray()
        )
        for (bytes in cases) {
            val stderr = SnapshotStderr(bytes)
            fixture(configure = { factory, _ -> factory.process.helperErrorStream = stderr }).use { fixture ->
                val handle = fixture.pendingLaunch()
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(ChrootRuntimeFailureStage.HELPER_STDERR, handle.failure.value?.stage)
                assertNull(handle.nativeFailure.value)
                assertEquals(1, stderr.firstReads.get())
                assertEquals(1, stderr.snapshots.get())
                assertEquals(if (bytes.size > 255) 0 else 1, stderr.bulkReads.get())
                assertFalse(handle.failure.value.toString().contains("private_credential_marker"))
                assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
                assertEquals(0, fixture.factory.process.destroys.get())
            }
        }
    }

    @Test
    fun stderrAvailableZeroAndShortSnapshotReadNeverWaitForMoreBytesOrRetry() = runTest {
        val valid = "ajiu_runtime: code=prepare_failed;stage=app_control_authentication;errno=13\n".toByteArray()
        for (zeroAvailable in listOf(true, false)) {
            val stderr = SnapshotStderr(valid, snapshot = if (zeroAvailable) 0 else valid.size - 1, maximumRead = 1)
            fixture(configure = { factory, _ -> factory.process.helperErrorStream = stderr }).use { fixture ->
                val handle = fixture.pendingLaunch()
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(ChrootRuntimeFailureStage.HELPER_STDERR, handle.failure.value?.stage)
                assertNull(handle.nativeFailure.value)
                assertEquals(1, stderr.firstReads.get())
                assertEquals(1, stderr.snapshots.get())
                assertEquals(if (zeroAvailable) 0 else 1, stderr.bulkReads.get())
                assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
                assertFalse(readyResult(handle))
            }
        }
    }

    @Test
    fun nativeEvidenceCollectorsRunOutsideTheRootGateAfterStderrHasStoppedGo() = runTest {
        val stderr = SnapshotStderr("ajiu_runtime: code=prepare_failed;stage=app_control_authentication;errno=13\n".toByteArray(), initiallyReadable = false)
        fixture(configure = { factory, _ -> factory.process.helperErrorStream = stderr }).use { fixture ->
            val handle = fixture.start()
            awaitSignal(stderr.firstReadStarted)
            awaitSignal(fixture.control.awaiting)
            val observed = CompletableDeferred<ChrootRuntimeFailure>()
            val otherThreadEntered = AtomicBoolean()
            val collector = fixture.scope.launch(Dispatchers.Unconfined) {
                val failure = handle.nativeFailure.filterNotNull().first()
                otherThreadEntered.set(CompletableFuture.supplyAsync<Boolean> {
                    fixture.authority.withCurrent(fixture.root.authorityId, fixture.root.generation) { true } == true
                }.get(2L, TimeUnit.SECONDS))
                observed.complete(failure)
            }
            stderr.release()
            assertEquals(ChrootRuntimeFailureKind.NATIVE_ERROR, awaitSignal(observed).kind)
            assertTrue(otherThreadEntered.get())
            assertFalse(readyResult(handle))
            assertEquals(ChrootRuntimeFailureStage.HELPER_STDERR, handle.failure.value?.stage)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            collector.join()
        }
    }

    @Test
    fun recoveredPendingIntentBlocksActivationWithoutCreatingRootProcess() = runTest {
        val journal = File(temporary.newFolder(), "journal.bin")
        ChrootRecoveryRepository(journal).recordIntent(instance, "89abcdef0123456789abcdef", rootfs, owner, 1L)
        fixture(journal).use { fixture ->
            assertEquals(setOf(instance), fixture.manager.blockedInstances.value)
            assertNull(fixture.manager.activateFromUser(owner, "profile", 1L, instance, rootfs, fixture.root))
            assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
            assertEquals(0, fixture.factory.starts.get())
        }
    }
}
