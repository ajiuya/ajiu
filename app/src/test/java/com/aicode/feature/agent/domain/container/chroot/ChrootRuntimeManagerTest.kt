package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import com.aicode.testing.ChrootVerificationForeground
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootRuntimeManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instance = "0123456789abcdef01234567"
    private val rootfs = ChrootFileIdentity(5L, 19L)
    private val owner = "chat:session:call"

    private class BytesPipe(private val onEof: () -> Unit = {}) : InputStream() {
        private val gate = java.lang.Object()
        private val bytes = ArrayDeque<Byte>()
        private var ended = false
        private var afterDrained: (() -> Unit)? = null
        fun offer(next: ByteArray, observeNextRead: (() -> Unit)? = null) = synchronized(gate) {
            if (!ended) {
                next.forEach(bytes::addLast)
                if (observeNextRead != null) {
                    check(afterDrained == null)
                    afterDrained = observeNextRead
                }
            }
            gate.notifyAll()
        }
        override fun read(): Int = synchronized(gate) {
            while (bytes.isEmpty() && !ended) {
                val observation = afterDrained
                afterDrained = null
                observation?.invoke()
                gate.wait()
            }
            if (bytes.isEmpty()) {
                onEof()
                -1
            } else bytes.removeFirst().toInt() and 0xff
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
        @Volatile var onStdoutEof: () -> Unit = {}
        @Volatile var onWrapperExitValue: () -> Unit = {}
        val stdout = BytesPipe { onStdoutEof() }
        val eofWritten = CompletableDeferred<Unit>()
        val launchWritten = CompletableDeferred<Unit>()
        @Volatile var inputFailure: IOException? = null
        @Volatile var beforeInputWrite: () -> Unit = {}
        @Volatile var helperErrorStream: InputStream = ByteArrayInputStream(byteArrayOf())
        @Volatile var wrapperStatus: Int? = 0
        val stdin = object : ByteArrayOutputStream() {
            @Synchronized
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                beforeInputWrite()
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
        fun frame(type: ChrootFrameType, body: ByteArray = byteArrayOf(), observeNextRead: (() -> Unit)? = null) {
            stdout.offer(ByteArrayOutputStream().also { ChrootWireProtocol.write(it, type, body) }.toByteArray(), observeNextRead)
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
        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException()
            val status = wrapperStatus ?: throw IllegalThreadStateException("Wrapper exit status is unknown")
            onWrapperExitValue()
            return status
        }
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

    /** Observes a real IO runnable returning, rather than assuming a submitted STOP already ran. */
    private open class StartupIoDispatcher : CoroutineDispatcher() {
        private val submitted = ThreadLocal<CompletableDeferred<Unit>?>()
        private val returning = ThreadLocal<CompletableDeferred<Unit>?>()

        fun observeSubmission(action: () -> Unit): CompletableDeferred<Unit> {
            val completed = CompletableDeferred<Unit>()
            check(submitted.get() == null)
            submitted.set(completed)
            try { action() } finally { submitted.remove() }
            return completed
        }

        fun observeCurrentReturn(completed: CompletableDeferred<Unit>) {
            check(returning.get() == null)
            returning.set(completed)
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val completion = submitted.get()
            Dispatchers.IO.dispatch(context, Runnable {
                try { block.run() } finally {
                    val returned = returning.get()
                    returning.remove()
                    returned?.complete(Unit)
                    completion?.complete(Unit)
                }
            })
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private class CleanupClockDispatcher : StartupIoDispatcher(), Delay {
        private class Timer(val at: Long, val action: Runnable) : DisposableHandle {
            val cancelled = AtomicBoolean()
            override fun dispose() { cancelled.set(true) }
        }

        private val gate = Any()
        private var time = 0L
        private val timers = mutableListOf<Timer>()
        val cleanupScheduled = CompletableDeferred<Long>()
        val timeoutScheduled = CompletableDeferred<Long>()
        val cleanupSchedules = AtomicInteger()
        val now: Long get() = synchronized(gate) { time }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val timer = schedule(timeMillis, Runnable { continuation.resume(Unit) })
            continuation.invokeOnCancellation { timer.dispose() }
            if (timeMillis == 4_000L || timeMillis == 10_000L) {
                cleanupSchedules.incrementAndGet()
                cleanupScheduled.complete(timeMillis)
            }
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle =
            schedule(timeMillis, block).also { timeoutScheduled.complete(timeMillis) }

        private fun schedule(duration: Long, block: Runnable): Timer = synchronized(gate) {
            Timer(time + duration, block).also(timers::add)
        }

        fun advanceBy(duration: Long) {
            val due = synchronized(gate) {
                time += duration
                timers.filter { it.at <= time }.also { timers.removeAll(it.toSet()) }
            }
            due.forEach { if (!it.cancelled.get()) it.action.run() }
        }
    }

    /** Matches the production transport: STOP cannot succeed before token authentication. */
    private class StartupControl(private val dispatcher: StartupIoDispatcher) : ChrootControlTransport {
        val authentication = CompletableDeferred<Unit>()
        val awaiting = CompletableDeferred<Unit>()
        val authenticationProcessed = CompletableDeferred<Unit>()
        val closed = AtomicBoolean()
        val connected = AtomicBoolean()
        val attempts = Collections.synchronizedList(mutableListOf<ChrootFrameType>())
        val sent = Collections.synchronizedList(mutableListOf<ChrootFrameType>())
        var onStop: () -> Unit = {}

        override suspend fun awaitHelper() {
            if (closed.get()) throw IOException("Fixture listener is closed before connect")
            dispatcher.observeCurrentReturn(awaiting)
            authentication.await()
            if (closed.get()) throw IOException("Fixture listener closed during authentication")
            connected.set(true)
            dispatcher.observeCurrentReturn(authenticationProcessed)
        }

        override fun trySend(type: ChrootFrameType, body: ByteArray): Boolean {
            attempts.add(type)
            if (closed.get() || !connected.get()) return false
            sent.add(type)
            if (type == ChrootFrameType.STOP) onStop()
            return true
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                authentication.completeExceptionally(IOException("Fixture control connect was refused"))
            }
        }
    }

    private data class Fixture(
        val authority: RootAuthorityCoordinator,
        val root: RootAccessState,
        val recovery: ChrootRecoveryRepository,
        val factory: FakeFactory,
        val control: FakeControl,
        val controls: ConcurrentHashMap<String, FakeControl>,
        val scope: CoroutineScope,
        val manager: ChrootRuntimeManager,
        val diagnostics: ChrootRuntimeDiagnostics,
        val diagnosticFile: File
    ) : AutoCloseable {
        override fun close() {
            synchronized(factory.processes) { factory.processes.toList() }.forEach { it.finish() }
            controls.values.forEach { it.close() }
            scope.cancel()
        }
    }

    private suspend fun TestScope.fixture(
        recoveryFile: File = File(temporary.newFolder(), "journal.bin"),
        authorityDecorate: (RootAuthorityCoordinator) -> RootAuthorityCoordinator = { it },
        configure: (FakeFactory, FakeControl) -> Unit = { _, _ -> }
    ): Fixture {
        val repository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
        val coordinator = RootAuthorityCoordinator(repository, backgroundScope)
        coordinator.setEnabled(true)
        val root = coordinator.accessSnapshot()
        runCurrent()
        val authority = authorityDecorate(coordinator)
        val recovery = ChrootRecoveryRepository(recoveryFile)
        val factory = FakeFactory()
        val control = FakeControl()
        configure(factory, control)
        val controls = ConcurrentHashMap<String, FakeControl>().apply { put("89abcdef0123456789abcdef", control) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnosticFile = File(temporary.newFolder(), "failures.bin")
        val diagnostics = ChrootRuntimeDiagnostics(diagnosticFile)
        val manager = ChrootRuntimeManager(authority, factory, recovery, scope, Dispatchers.IO, {
            controls.getOrPut(it.launchId) { FakeControl() }.also { channel -> channel.listenerFailure?.let { throw it } }
        }, { System.nanoTime() / 1_000_000L }, diagnostics, cleanupTimeoutMs = 4_000L)
        return Fixture(authority, root, recovery, factory, control, controls, scope, manager, diagnostics, diagnosticFile)
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

    @Test fun journalMaintenanceRejectsBindingAuthorizationAndLaunchThenRequiresANewLease() = runTest {
        fixture().use { fixture ->
            val lease = fixture.lease()
            val launch = spec()
            val permit = checkNotNull(fixture.manager.authorize(lease, launch, 1))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val maintenance = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.manager.withJournalMaintenance {
                    entered.complete(Unit)
                    release.await()
                    "repaired"
                }
            }
            try {
                awaitSignal(entered)
                assertTrue(fixture.manager.instanceState(instance).blocked)
                assertEquals(0, fixture.manager.instanceState(instance).authorizedOwners)
                assertFalse(fixture.manager.isLeaseCurrent(lease))
                assertNull(fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root))
                assertNull(fixture.manager.authorize(lease, launch, 1))
                assertNull(fixture.manager.launch(lease, permit, launch) {})
                var nestedBlockRan = false
                assertTrue(runCatching { fixture.manager.withJournalMaintenance { nestedBlockRan = true } }
                    .exceptionOrNull() is IllegalStateException)
                assertFalse(nestedBlockRan)
                // A rejected second maintenance request must not lift the first request's guard.
                assertNull(fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root))
                assertEquals(0, fixture.factory.starts.get())
                assertEquals(0, fixture.factory.captures.get())
            } finally {
                release.complete(Unit)
            }
            assertEquals("repaired", maintenance.await())
            assertFalse(fixture.manager.instanceState(instance).blocked)
            assertFalse(fixture.manager.isLeaseCurrent(lease))
            assertNull(fixture.manager.authorize(lease, launch, 1))
            assertNull(fixture.manager.launch(lease, permit, launch) {})
            val replacement = fixture.lease()
            assertNotEquals(lease, replacement)
            assertTrue(fixture.manager.isLeaseCurrent(replacement))
            assertNotNull(fixture.manager.authorize(replacement, launch, 1))
            assertEquals(0, fixture.factory.starts.get())
            assertTrue(fixture.recovery.snapshot().isEmpty())
        }
    }

    @Test fun failedJournalMaintenanceReleasesItsGuardWithoutClearingUnknownOrRevivingOldLeases() = runTest {
        fixture().use { fixture ->
            fixture.recovery.recordIntent(instance, "89abcdef0123456789abcdef", rootfs, owner, fixture.root.generation)
            fixture.recovery.recordCleanup("89abcdef0123456789abcdef", ChrootCleanupResult.UNKNOWN)
            val recorded = fixture.recovery.snapshot()
            val cleanInstance = "abcdef0123456789abcdef01"
            val cleanLease = checkNotNull(fixture.manager.allowBinding(owner, "clean-profile", 1, cleanInstance, rootfs, fixture.root))
            val cleanLaunch = spec(id = cleanInstance, launch = "abcdefabcdefabcdefabcdef")
            val permit = checkNotNull(fixture.manager.authorize(cleanLease, cleanLaunch, 1))
            val failure = IOException("repair could not commit")

            assertEquals(failure, runCatching {
                fixture.manager.withJournalMaintenance<Unit> {
                    assertNull(fixture.manager.allowBinding(owner, "clean-profile", 1, cleanInstance, rootfs, fixture.root))
                    assertNull(fixture.manager.authorize(cleanLease, cleanLaunch, 1))
                    throw failure
                }
            }.exceptionOrNull())

            assertEquals(recorded, fixture.recovery.snapshot())
            assertTrue(fixture.recovery.isHealthy.value)
            assertFalse(fixture.recovery.canStart(instance))
            assertTrue(fixture.manager.instanceState(instance).blocked)
            assertNull(fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root))
            assertFalse(fixture.manager.isLeaseCurrent(cleanLease))
            assertNull(fixture.manager.launch(cleanLease, permit, cleanLaunch) {})
            val replacement = checkNotNull(fixture.manager.allowBinding(owner, "clean-profile", 1, cleanInstance, rootfs, fixture.root))
            assertNotEquals(cleanLease, replacement)
            assertTrue(fixture.manager.isLeaseCurrent(replacement))
            assertNotNull(fixture.manager.authorize(replacement, cleanLaunch, 1))
            assertFalse(fixture.manager.instanceState(cleanInstance).blocked)
            assertEquals(0, fixture.factory.starts.get())
        }
    }

    @Test fun journalMaintenanceWaitsForCleanupBeforeEnteringTheRepairBlock() = runTest {
        fixture().use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            val entered = CompletableDeferred<Unit>()
            val maintenance = async(Dispatchers.IO) {
                fixture.manager.withJournalMaintenance {
                    assertEquals(0, fixture.manager.instanceState(instance).activeLaunches)
                    entered.complete(Unit)
                }
            }
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
            fixture.factory.process.exit(0)
            eventually { handle.taskState.value != null }
            assertFalse(entered.isCompleted)
            assertFalse(maintenance.isCompleted)
            assertNull(fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root))
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            awaitSignal(entered)
            maintenance.await()
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertEquals(0, fixture.manager.instanceState(instance).activeLaunches)
            assertTrue(fixture.manager.isLeaseCurrent(fixture.lease()))
            assertEquals(1, fixture.factory.starts.get())
        }
    }

    @Test fun cancellingJournalMaintenanceRestoresAvailabilityButNotTheOldLease() = runTest {
        fixture().use { fixture ->
            val lease = fixture.lease()
            val entered = CompletableDeferred<Unit>()
            val maintenance = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.manager.withJournalMaintenance {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
            }
            awaitSignal(entered)
            assertNull(fixture.manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root))
            maintenance.cancel()
            maintenance.join()
            assertFalse(fixture.manager.isLeaseCurrent(lease))
            assertFalse(fixture.manager.instanceState(instance).blocked)
            assertTrue(fixture.manager.isLeaseCurrent(fixture.lease()))
            assertTrue(fixture.recovery.snapshot().isEmpty())
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
    fun fastSuccessfulExitAfterGoRemainsReadyWhenStartupResumes() = runTest {
        assertFastExitAfterGoRemainsReady(0)
    }

    @Test
    fun fastNonzeroExitAfterGoRemainsReadyWhenStartupResumes() = runTest {
        assertFastExitAfterGoRemainsReady(37)
    }

    private suspend fun TestScope.assertFastExitAfterGoRemainsReady(exitCode: Int) {
        val goThread = ThreadLocal<Boolean>()
        val startupPaused = CompletableDeferred<Unit>()
        val startupReleased = CompletableDeferred<Unit>()
        val releaseStartup = CountDownLatch(1)
        fixture(
            authorityDecorate = { coordinator ->
                spyk(coordinator).also { decorated ->
                    every { decorated.withCurrent(any(), any(), any<() -> Any?>()) } answers {
                        val result = callOriginal()
                        if (goThread.get() == true) {
                            goThread.remove()
                            startupPaused.complete(Unit)
                            try {
                                check(releaseStartup.await(10L, TimeUnit.SECONDS)) {
                                    "Startup was not released after the GO authority gate"
                                }
                            } finally {
                                startupReleased.complete(Unit)
                            }
                        }
                        result
                    }
                }
            },
            configure = { _, control ->
                control.onSend = { type ->
                    if (type == ChrootFrameType.GO) goThread.set(true)
                }
            }
        ).use { fixture ->
            val handle = fixture.pendingLaunch()
            try {
                awaitSignal(fixture.control.awaiting)
                fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
                fixture.control.authenticated.complete(Unit)
                awaitSignal(startupPaused)
                assertEquals(listOf(ChrootFrameType.GO), fixture.control.sent.toList())
                assertNull(handle.taskState.value)
                assertNull(handle.cleanupState.value)

                fixture.factory.process.exit(exitCode)
                fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                fixture.factory.process.finish()
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(exitCode, null)), handle.awaitTaskExit())
                assertEquals(1L, releaseStartup.count)
                assertFalse(startupReleased.isCompleted)
            } finally {
                releaseStartup.countDown()
                if (startupPaused.isCompleted) awaitSignal(startupReleased)
            }
            assertTrue(readyResult(handle))
            assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(exitCode, null)), handle.taskState.value)
            assertEquals(ChrootCleanupResult.VERIFIED, handle.cleanupState.value)
            assertNull(handle.failure.value)
        }
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
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
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
            eventually { !fixture.authority.accessState.value.enabled }
            assertFalse(readyResult(handle))
            assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
            assertFalse(fixture.control.sent.contains(ChrootFrameType.STOP))
            assertFalse(fixture.control.closed.get())
            fixture.factory.process.frame(ChrootFrameType.READY, spec().token())
            fixture.control.authenticated.complete(Unit)
            eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
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
    fun stoppingBlockedHelperCaptureFinishesWithoutIntentOrLateStartupAndAllowsANewLease() = runTest {
        val dispatcher = StartupIoDispatcher()
        val captureEntered = CompletableDeferred<Unit>()
        val startupReturned = CompletableDeferred<Unit>()
        val releaseCapture = CountDownLatch(1)
        val listenerCalls = AtomicInteger()
        fixture(configure = { factory, _ ->
            factory.onCapture = {
                dispatcher.observeCurrentReturn(startupReturned)
                captureEntered.complete(Unit)
                check(releaseCapture.await(10L, TimeUnit.SECONDS))
            }
        }).use { fixture ->
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, { listenerCalls.incrementAndGet(); fixture.control }, { System.nanoTime() / 1_000_000L },
                fixture.diagnostics, cleanupTimeoutMs = 4_000L)
            val lease = manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            try {
                awaitSignal(captureEntered)
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertEquals(1L, releaseCapture.count)
                assertFalse(startupReturned.isCompleted)
                assertFalse(readyResult(handle))
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertTrue(fixture.recovery.snapshot().isEmpty())
                assertTrue(fixture.recovery.recordedLaunchIds.value.isEmpty())
                assertEquals(0, listenerCalls.get())
                assertEquals(0, fixture.factory.starts.get())
                assertEquals(0, manager.instanceState(instance).activeLaunches)
                assertFalse(manager.instanceState(instance).blocked)
                assertNull(handle.failure.value)

                releaseCapture.countDown()
                awaitSignal(startupReturned)
                assertEquals(ChrootCleanupResult.VERIFIED, handle.cleanupState.value)
                assertNull(handle.failure.value)
                assertTrue(fixture.recovery.snapshot().isEmpty())
                assertTrue(fixture.recovery.recordedLaunchIds.value.isEmpty())
                assertEquals(0, listenerCalls.get())
                assertEquals(0, fixture.factory.starts.get())
                assertTrue(fixture.control.sent.isEmpty())
                val replacement = manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root)!!
                assertNotEquals(lease, replacement)
                assertTrue(manager.isLeaseCurrent(replacement))
                assertNotNull(manager.authorize(replacement, spec(), 1))
            } finally {
                releaseCapture.countDown()
                if (captureEntered.isCompleted) awaitSignal(startupReturned)
            }
        }
    }

    private fun Fixture.withProductionCleanupBudget(dispatcher: CleanupClockDispatcher): Fixture = copy(
        manager = ChrootRuntimeManager(authority, factory, recovery, scope, dispatcher,
            { control }, { dispatcher.now }, diagnostics)
    )

    @Test
    fun productionCleanupBudgetAcceptsCompleteProofAfterNativeSupervisorGrace() = runTest {
        val dispatcher = CleanupClockDispatcher()
        fixture().withProductionCleanupBudget(dispatcher).use { fixture ->
            val stderr = BytesPipe()
            fixture.factory.process.helperErrorStream = stderr
            val handle = fixture.start()
            fixture.ready()
            assertTrue(readyResult(handle))
            fixture.factory.process.exit(0)
            val budget = awaitSignal(dispatcher.cleanupScheduled)
            assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(0, null)), handle.awaitTaskExit())

            dispatcher.advanceBy(4_100L)
            if (budget <= dispatcher.now) cleanupResult(handle)
            assertNull(handle.cleanupState.value)
            assertNull(handle.failure.value)
            dispatcher.advanceBy(2_400L)
            assertEquals(6_500L, dispatcher.now)
            assertNull(handle.cleanupState.value)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()
            dispatcher.advanceBy(20L)

            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertEquals(10_000L, budget)
            assertEquals(1, dispatcher.cleanupSchedules.get())
            assertNull(handle.failure.value)
            assertFalse(fixture.manager.instanceState(instance).blocked)
            assertEquals(ChrootRecoveryPhase.CLOSED, fixture.recovery.snapshot().single().phase)
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun productionCleanupBudgetExpiresUnknownAtTenSecondsWithoutProof() = runTest {
        val dispatcher = CleanupClockDispatcher()
        fixture().withProductionCleanupBudget(dispatcher).use { fixture ->
            fixture.factory.process.helperErrorStream = BytesPipe()
            val handle = fixture.start()
            fixture.ready()
            assertTrue(readyResult(handle))
            fixture.factory.process.exit(0)
            val budget = awaitSignal(dispatcher.cleanupScheduled)

            dispatcher.advanceBy(9_999L)
            if (budget <= dispatcher.now) cleanupResult(handle)
            assertNull(handle.cleanupState.value)
            dispatcher.advanceBy(1L)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals(10_000L, dispatcher.now)
            assertEquals(10_000L, budget)
            assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
            assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(0, null)), handle.awaitTaskExit())
            eventually { fixture.diagnostics.latestUnknown(instance)?.completionEvidence != null }
            val evidence = fixture.diagnostics.latestUnknown(instance)!!.completionEvidence!!
            assertEquals(ChrootStopTrigger.TASK_EXIT, evidence.trigger)
            assertEquals(7, evidence.flags)
            assertTrue(fixture.manager.instanceState(instance).blocked)
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun repeatedStopDoesNotRestartTheProductionCleanupBudget() = runTest {
        val dispatcher = CleanupClockDispatcher()
        fixture().withProductionCleanupBudget(dispatcher).use { fixture ->
            fixture.factory.process.helperErrorStream = BytesPipe()
            val handle = fixture.start()
            fixture.ready()
            assertTrue(readyResult(handle))
            handle.stop()
            val budget = awaitSignal(dispatcher.cleanupScheduled)
            dispatcher.advanceBy(6_500L)
            if (budget <= dispatcher.now) cleanupResult(handle)
            assertNull(handle.cleanupState.value)
            handle.stop()
            handle.stop()
            awaitSignal(dispatcher.observeSubmission { fixture.manager.stopInstance(instance) })
            awaitSignal(dispatcher.observeSubmission { fixture.manager.stopInstance(instance) })
            dispatcher.advanceBy(3_499L)
            assertNull(handle.cleanupState.value)
            dispatcher.advanceBy(1L)

            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals(10_000L, dispatcher.now)
            assertEquals(1, dispatcher.cleanupSchedules.get())
            assertEquals(1, fixture.control.sent.count { it == ChrootFrameType.STOP })
            assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
            eventually { fixture.diagnostics.latestUnknown(instance)?.completionEvidence != null }
            assertEquals(ChrootStopTrigger.STOP, fixture.diagnostics.latestUnknown(instance)!!.completionEvidence!!.trigger)
            assertTrue(fixture.manager.instanceState(instance).blocked)
            assertEquals(0, fixture.factory.process.destroys.get())
        }
    }

    @Test
    fun stopAfterIntentReservationWithoutBirthStillRequiresCleanupEvidence() = runTest {
        val dispatcher = CleanupClockDispatcher()
        val listenerEntered = CompletableDeferred<Unit>()
        val startupReturned = CompletableDeferred<Unit>()
        val releaseListener = CountDownLatch(1)
        fixture().use { fixture ->
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, {
                    dispatcher.observeCurrentReturn(startupReturned)
                    listenerEntered.complete(Unit)
                    check(releaseListener.await(10L, TimeUnit.SECONDS))
                    fixture.control
                }, { dispatcher.now }, fixture.diagnostics)
            val lease = manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            try {
                awaitSignal(listenerEntered)
                assertEquals(ChrootRecoveryPhase.INTENT, fixture.recovery.snapshot().single().phase)
                assertEquals(0, fixture.factory.starts.get())
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                val budget = awaitSignal(dispatcher.cleanupScheduled)
                assertNull(handle.cleanupState.value)
                dispatcher.advanceBy(budget)
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
                assertTrue(manager.instanceState(instance).blocked)
                assertFalse(fixture.recovery.canStart(instance))
                releaseListener.countDown()
                awaitSignal(startupReturned)
                assertEquals(ChrootCleanupResult.UNKNOWN, handle.cleanupState.value)
                assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
                assertEquals(0, fixture.factory.starts.get())
                assertTrue(fixture.control.sent.isEmpty())
            } finally {
                releaseListener.countDown()
                if (listenerEntered.isCompleted) awaitSignal(startupReturned)
            }
        }
    }

    @Test
    fun journalMaintenanceWaitsBeyondTheProductionCleanupDeadline() = runTest {
        val dispatcher = CleanupClockDispatcher()
        val maintenanceDispatcher = CleanupClockDispatcher()
        fixture().withProductionCleanupBudget(dispatcher).use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            assertTrue(readyResult(handle))
            val maintenance = fixture.scope.async(maintenanceDispatcher) {
                fixture.manager.withJournalMaintenance { "settled" }
            }
            val outerBudget = awaitSignal(maintenanceDispatcher.timeoutScheduled)
            awaitSignal(dispatcher.cleanupScheduled)
            maintenanceDispatcher.advanceBy(10_001L)
            if (outerBudget <= maintenanceDispatcher.now) runCatching { maintenance.await() }
            assertFalse(maintenance.isCompleted)
            dispatcher.advanceBy(10_000L)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals("settled", withContext(Dispatchers.IO) { withTimeout(5_000L) { maintenance.await() } })
            assertEquals(12_000L, outerBudget)
            assertEquals(0, fixture.manager.instanceState(instance).activeLaunches)
            assertTrue(fixture.manager.instanceState(instance).blocked)
        }
    }

    @Test
    fun ownerRevocationProvesUndispatchedBrokerLaunchAndClosesItsIntentWithoutInventingTaskExit() = runTest {
        fixture().use { fixture ->
            val created = CompletableDeferred<ChrootBrokerProcess>()
            val proxy = ChrootBrokerProcess {}
            val factory = object : ChrootProcessFactory {
                override fun captureHelper() = ChrootHelperIdentity("/trusted/helper", 1, 2, 3, "a".repeat(64))
                override fun start(helper: ChrootHelperIdentity): Process = proxy.also { created.complete(it) }
            }
            val manager = ChrootRuntimeManager(fixture.authority, factory, fixture.recovery, fixture.scope,
                Dispatchers.IO, { fixture.control }, { System.nanoTime() / 1_000_000L }, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            awaitSignal(created)
            manager.revokeOwner(owner)
            assertFalse(proxy.tryDispatch())
            assertFalse(proxy.tryDispatch())
            assertTrue(proxy.cancelled.get())
            assertFalse(proxy.isAlive)
            assertEquals(0L, proxy.requestId)
            assertEquals(0, manager.instanceState(instance).authorizedOwners)
            assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
            assertFalse(manager.instanceState(instance).blocked)
            assertTrue(fixture.recovery.unknownLaunchIds(instance).isEmpty())
            val recorded = fixture.recovery.snapshot().single()
            assertEquals(spec().launchId, recorded.launchId)
            assertEquals(ChrootRecoveryPhase.CLOSED, recorded.phase)
            assertEquals(ChrootCleanupResult.VERIFIED, recorded.cleanup)
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
            assertFalse(proxy.tryDispatch())
        }
    }

    @Test
    fun stoppingABornHelperDuringLaunchWriteKeepsListenerUntilAuthenticationAndReadyThenStopsWithoutGo() = runTest {
        fixture().use { fixture ->
            val dispatcher = StartupIoDispatcher()
            val control = StartupControl(dispatcher)
            val launchWriteEntered = CompletableDeferred<Unit>()
            val releaseLaunchWrite = CountDownLatch(1)
            val firstWrite = AtomicBoolean(true)
            val process = fixture.factory.process
            process.beforeInputWrite = {
                if (firstWrite.compareAndSet(true, false)) {
                    launchWriteEntered.complete(Unit)
                    check(releaseLaunchWrite.await(5L, TimeUnit.SECONDS))
                }
            }
            control.onStop = {
                // Native pre-GO cancellation still reports the bootstrap EXIT before CLEANUP.
                process.frame(ChrootFrameType.EXIT, ByteBuffer.allocate(8).putInt(1).putInt(15).array())
                process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                process.finish()
            }
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, { control }, { System.nanoTime() / 1_000_000L }, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            try {
                awaitSignal(launchWriteEntered)
                assertEquals(1, fixture.factory.starts.get())
                assertTrue(process.isAlive)
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                assertFalse(readyResult(handle))
                assertFalse("STOP before authentication must retain the listener for this already-born helper", control.closed.get())
                assertTrue("Neither STOP nor GO can be sent before authentication", control.attempts.isEmpty())
                assertNull(handle.cleanupState.value)

                releaseLaunchWrite.countDown()
                awaitSignal(control.awaiting)
                control.authentication.complete(Unit)
                // This fires after the startup continuation processed authentication and suspended for READY.
                awaitSignal(control.authenticationProcessed)
                assertTrue(control.connected.get())
                assertFalse(control.closed.get())
                assertTrue("Authentication alone must not send STOP before native READY", control.attempts.isEmpty())
                assertNull(handle.cleanupState.value)

                process.frame(ChrootFrameType.READY, spec().token())
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertEquals(listOf(ChrootFrameType.STOP), control.sent.toList())
                assertEquals(listOf(ChrootFrameType.LAUNCH), process.inputFrames())
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertNull(handle.failure.value)
                assertTrue(control.closed.get())
                assertFalse(manager.instanceState(instance).blocked)
                assertTrue(fixture.recovery.unknownLaunchIds(instance).isEmpty())
                assertEquals(ChrootRecoveryPhase.CLOSED, fixture.recovery.snapshot().single().phase)
                assertEquals(0, process.destroys.get())
            } finally {
                releaseLaunchWrite.countDown()
                control.close()
            }
        }
    }

    @Test
    fun cancelledBornHelperWithoutAuthenticationStillReachesTheOriginalStopDeadlineUnknown() = runTest {
        fixture().use { fixture ->
            val dispatcher = StartupIoDispatcher()
            val control = StartupControl(dispatcher)
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, { control }, { System.nanoTime() / 1_000_000L }, fixture.diagnostics, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            try {
                awaitSignal(control.awaiting)
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                assertFalse("An unexpired stop grace period must retain the listener", control.closed.get())
                assertTrue(control.attempts.isEmpty())
                assertNull(handle.cleanupState.value)
                // The existing five-second observation budget must see the original four-second stop deadline.
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(ChrootRuntimeFailureKind.TIMEOUT, handle.failure.value?.kind)
                assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertTrue(control.closed.get())
                assertTrue(control.sent.isEmpty())
                assertTrue(manager.instanceState(instance).blocked)
                assertEquals(setOf(spec().launchId), fixture.recovery.unknownLaunchIds(instance))
                assertEquals(0, fixture.factory.process.destroys.get())
                eventually { fixture.diagnostics.latestUnknown(instance)?.completionEvidence != null }
                assertEquals(ChrootStopTrigger.STOP, fixture.diagnostics.latestUnknown(instance)!!.completionEvidence!!.trigger)
            } finally { control.close() }
        }
    }

    @Test
    fun cancelledAuthenticatedHelperWithoutNativeReadyStillReachesTheOriginalStopDeadlineUnknown() = runTest {
        fixture().use { fixture ->
            val dispatcher = StartupIoDispatcher()
            val control = StartupControl(dispatcher)
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, { control }, { System.nanoTime() / 1_000_000L }, fixture.diagnostics, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            try {
                awaitSignal(control.awaiting)
                control.authentication.complete(Unit)
                awaitSignal(control.authenticationProcessed)
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                assertTrue(control.connected.get())
                assertFalse(control.closed.get())
                assertTrue("Authentication without native READY cannot send STOP", control.attempts.isEmpty())
                assertFalse(readyResult(handle))
                assertNull(handle.cleanupState.value)
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertEquals(ChrootRuntimeFailureKind.TIMEOUT, handle.failure.value?.kind)
                assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertTrue(control.closed.get())
                assertTrue(control.sent.isEmpty())
                assertTrue(manager.instanceState(instance).blocked)
                assertEquals(setOf(spec().launchId), fixture.recovery.unknownLaunchIds(instance))
                assertEquals(0, fixture.factory.process.destroys.get())
                eventually { fixture.diagnostics.latestUnknown(instance)?.completionEvidence != null }
                val evidence = fixture.diagnostics.latestUnknown(instance)!!.completionEvidence!!
                assertEquals(ChrootStopTrigger.STOP, evidence.trigger)
                assertEquals(0, evidence.flags and (ChrootCompletionEvidence.READY or ChrootCompletionEvidence.GO))
            } finally { control.close() }
        }
    }

    @Test
    fun cancelledHelperWithNativeReadyBeforeAuthenticationStopsOnlyAfterBothAndNeverGoes() = runTest {
        fixture().use { fixture ->
            val dispatcher = StartupIoDispatcher()
            val control = StartupControl(dispatcher)
            val process = fixture.factory.process
            val nativeReadyProcessed = CompletableDeferred<Unit>()
            control.onStop = {
                process.exit(127)
                process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                process.finish()
            }
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, { control }, { System.nanoTime() / 1_000_000L }, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            try {
                awaitSignal(control.awaiting)
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                process.frame(ChrootFrameType.READY, spec().token()) { nativeReadyProcessed.complete(Unit) }
                // The frame reader requests another header only after accepting this READY.
                awaitSignal(nativeReadyProcessed)
                assertFalse(control.connected.get())
                assertFalse(control.closed.get())
                assertTrue("Native READY without authentication cannot send STOP or GO", control.attempts.isEmpty())
                assertFalse(readyResult(handle))
                assertNull(handle.cleanupState.value)

                control.authentication.complete(Unit)
                awaitSignal(control.authenticationProcessed)
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertEquals(listOf(ChrootFrameType.STOP), control.sent.toList())
                assertEquals(listOf(ChrootFrameType.LAUNCH), process.inputFrames())
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertNull(handle.failure.value)
                assertFalse(manager.instanceState(instance).blocked)
                assertTrue(fixture.recovery.unknownLaunchIds(instance).isEmpty())
                assertEquals(ChrootRecoveryPhase.CLOSED, fixture.recovery.snapshot().single().phase)
                assertEquals(0, process.destroys.get())
            } finally { control.close() }
        }
    }

    @Test
    fun preGoExitWithoutNativeReadyIsRejectedForBothActiveAndCancelledRequests() = runTest {
        for (cancelled in listOf(false, true)) {
            fixture().use { fixture ->
                val dispatcher = StartupIoDispatcher()
                val control = StartupControl(dispatcher)
                val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                    dispatcher, { control }, { System.nanoTime() / 1_000_000L }, cleanupTimeoutMs = 4_000L)
                val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
                val permit = manager.authorize(lease, spec(), 1)!!
                val handle = manager.launch(lease, permit, spec()) {}!!
                try {
                    awaitSignal(control.awaiting)
                    control.authentication.complete(Unit)
                    awaitSignal(control.authenticationProcessed)
                    if (cancelled) awaitSignal(dispatcher.observeSubmission { handle.stop() })
                    assertTrue(control.connected.get())
                    assertTrue(control.attempts.isEmpty())
                    assertNull(handle.taskState.value)
                    fixture.factory.process.exit(127)
                    assertEquals("cancelled=$cancelled", ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                    assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                    assertEquals(ChrootRuntimeFailureKind.PROTOCOL, handle.failure.value?.kind)
                    assertEquals(ChrootRuntimeFailureStage.FRAME_SEQUENCE, handle.failure.value?.stage)
                    assertTrue(control.sent.isEmpty())
                    assertTrue(manager.instanceState(instance).blocked)
                    assertEquals(setOf(spec().launchId), fixture.recovery.unknownLaunchIds(instance))
                    assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
                } finally { control.close() }
            }
        }
    }

    @Test
    fun authenticatedReadyCancellationKeepsItsStopBudgetAfterTheStartupWatchdogRuns() = runTest {
        val dispatcher = StartupIoDispatcher()
        val observeWatchdog = AtomicBoolean()
        val watchdogProcessed = CompletableDeferred<Unit>()
        fixture(authorityDecorate = { coordinator ->
            spyk(coordinator).also { decorated ->
                every { decorated.withLock(any<(RootAccessState) -> Any?>()) } answers {
                    val result = callOriginal()
                    // After authentication/READY/STOP settle, only the pending startup watchdog
                    // enters this authority gate before the four-second stop deadline.
                    if (observeWatchdog.compareAndSet(true, false)) {
                        dispatcher.observeCurrentReturn(watchdogProcessed)
                    }
                    result
                }
            }
        }).use { fixture ->
            val control = StartupControl(dispatcher)
            val process = fixture.factory.process
            // Keep the stderr collector blocked until final cleanup, so it cannot consume the observation gate.
            process.helperErrorStream = BytesPipe()
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                dispatcher, { control }, { System.nanoTime() / 1_000_000L }, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            suspend fun until(deadline: Long) = withContext(Dispatchers.IO) {
                val remaining = deadline - System.nanoTime() / 1_000_000L
                if (remaining > 0) delay(remaining)
            }
            try {
                awaitSignal(control.awaiting)
                val startupObservedAt = System.nanoTime() / 1_000_000L
                until(startupObservedAt + 2_000L)
                val stoppedAt = System.nanoTime() / 1_000_000L
                awaitSignal(dispatcher.observeSubmission { handle.stop() })
                control.authentication.complete(Unit)
                awaitSignal(control.authenticationProcessed)
                val startupSettled = CompletableDeferred<Unit>()
                control.onStop = { dispatcher.observeCurrentReturn(startupSettled) }
                process.frame(ChrootFrameType.READY, spec().token())
                awaitSignal(startupSettled)
                assertEquals(listOf(ChrootFrameType.STOP), control.sent.toList())
                assertFalse(readyResult(handle))
                assertNull(handle.cleanupState.value)
                assertNull(handle.failure.value)
                observeWatchdog.set(true)

                until(startupObservedAt + 5_200L)
                withContext(Dispatchers.IO) {
                    val remainingStopBudget = stoppedAt + 4_000L - System.nanoTime() / 1_000_000L
                    check(remainingStopBudget > 0) { "The original stop deadline expired before the watchdog observation" }
                    withTimeout(remainingStopBudget) { watchdogProcessed.await() }
                }
                assertFalse("The watchdog must have actually executed", observeWatchdog.get())
                assertTrue(System.nanoTime() / 1_000_000L - stoppedAt < 4_000L)
                assertNull("Startup timeout must not replace an authenticated cancellation's stop deadline", handle.failure.value)
                assertNull(handle.cleanupState.value)
                assertFalse(control.closed.get())
                assertEquals(listOf(ChrootFrameType.STOP), control.sent.toList())

                process.exit(127)
                process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                process.finish()
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
                assertNull(handle.failure.value)
                assertFalse(manager.instanceState(instance).blocked)
                assertTrue(fixture.recovery.unknownLaunchIds(instance).isEmpty())
                assertEquals(0, process.destroys.get())
            } finally {
                observeWatchdog.set(false)
                control.close()
            }
        }
    }

    @Test
    fun failedAlreadyDispatchedBrokerProxyCannotUseIsAliveFalseAsProofOfNoHelperBirth() = runTest {
        fixture().use { fixture ->
            val proxy = ChrootBrokerProcess {}
            assertTrue(proxy.tryDispatch())
            proxy.fail(IOException("Descriptor reply failed after dispatch commitment"))
            assertFalse(proxy.isAlive)
            assertFalse(proxy.cancelled.get())
            val factory = object : ChrootProcessFactory {
                override fun captureHelper() = ChrootHelperIdentity("/trusted/helper", 1, 2, 3, "a".repeat(64))
                override fun start(helper: ChrootHelperIdentity): Process = proxy
            }
            val manager = ChrootRuntimeManager(fixture.authority, factory, fixture.recovery, fixture.scope,
                Dispatchers.IO, { fixture.control }, { System.nanoTime() / 1_000_000L }, cleanupTimeoutMs = 4_000L)
            val lease = manager.activateFromUser(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertEquals(ChrootTaskResult.Unknown, handle.awaitTaskExit())
            assertFalse(proxy.tryDispatch())
            assertTrue(manager.instanceState(instance).blocked)
            assertEquals(setOf(spec().launchId), fixture.recovery.unknownLaunchIds(instance))
            assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
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
    fun stopDeadlineRechecksCompleteEvidenceWhileTheLastCollectorIsPausedOutsideTheRootGate() = runTest {
        val observation = ThreadLocal<String>()
        val stdoutCollectorIdle = CompletableDeferred<Boolean>()
        val wrapperCollectorIdle = CompletableDeferred<Unit>()
        val stdoutHadCleanup = AtomicBoolean()
        val lastCollectorPaused = CompletableDeferred<Unit>()
        val lastCollectorReleased = CompletableDeferred<Unit>()
        val releaseLastCollector = CountDownLatch(1)
        val pausedOnce = AtomicBoolean()
        val helperErrors = BytesPipe { observation.set("stderr-eof") }
        fixture(
            authorityDecorate = { coordinator ->
                spyk(coordinator).also { decorated ->
                    every { decorated.withLock(any<(RootAccessState) -> Any?>()) } answers {
                        // Run the real gate and protocol mutation first. The pause below must
                        // happen after its monitor is released so the real deadline can enter.
                        val result = callOriginal()
                        when (observation.get()) {
                            "stdout-eof" -> if (result is Boolean) {
                                stdoutHadCleanup.set(result)
                                observation.set("stdout-finalizer")
                            }
                            "stdout-finalizer" -> if (result == null) {
                                observation.remove()
                                stdoutCollectorIdle.complete(stdoutHadCleanup.get())
                            }
                            "wrapper-exit" -> if (result === Unit) observation.set("wrapper-finalizer")
                            "wrapper-finalizer" -> if (result == null) {
                                observation.remove()
                                wrapperCollectorIdle.complete(Unit)
                            }
                            "stderr-eof" -> if (result === Unit && pausedOnce.compareAndSet(false, true)) {
                                observation.remove()
                                lastCollectorPaused.complete(Unit)
                                try {
                                    check(releaseLastCollector.await(10L, TimeUnit.SECONDS)) {
                                        "The final stderr EOF collector was not released"
                                    }
                                } finally {
                                    lastCollectorReleased.complete(Unit)
                                }
                            }
                        }
                        result
                    }
                }
            },
            configure = { factory, _ ->
                factory.process.helperErrorStream = helperErrors
                factory.process.onStdoutEof = { observation.set("stdout-eof") }
                factory.process.onWrapperExitValue = { observation.set("wrapper-exit") }
            }
        ).use { fixture ->
            try {
                val handle = fixture.start()
                fixture.ready()
                assertTrue(readyResult(handle))
                handle.stop()
                eventually { fixture.control.sent.contains(ChrootFrameType.STOP) }
                fixture.factory.process.exit(0)
                fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))

                // Publish CLEANUP, stdout EOF and the real zero wrapper status, and
                // let both collectors finish their incomplete tryFinish attempts before
                // releasing the final stderr EOF. No Execution fields are fabricated.
                fixture.factory.process.stdout.close()
                assertTrue(awaitSignal(stdoutCollectorIdle))
                fixture.factory.process.alive.set(false)
                awaitSignal(wrapperCollectorIdle)
                assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(0, null)), handle.awaitTaskExit())
                helperErrors.close()
                awaitSignal(lastCollectorPaused)
                assertEquals(1L, releaseLastCollector.count)
                assertFalse(lastCollectorReleased.isCompleted)
                assertTrue(fixture.authority.withCurrent(fixture.root.authorityId, fixture.root.generation) { true } == true)

                // The unchanged four-second deadline must recheck all published proof
                // while the last normal tryFinish callback is still paused outside the gate.
                assertEquals(ChrootCleanupResult.VERIFIED, cleanupResult(handle))
                assertNull(handle.failure.value)
                assertEquals(ChrootRecoveryPhase.CLOSED, fixture.recovery.snapshot().single().phase)
                assertFalse(fixture.manager.instanceState(instance).blocked)
                assertTrue(fixture.recovery.canStart(instance))
                assertEquals(0, fixture.factory.process.destroys.get())
            } finally {
                releaseLastCollector.countDown()
                if (lastCollectorPaused.isCompleted) awaitSignal(lastCollectorReleased)
                observation.remove()
            }
        }
    }

    @Test
    fun deadlineFreezesMissingWrapperExitAndNeverAcceptsLateProofAsRecovery() = runTest {
        val wrapperThread = ThreadLocal<Boolean>()
        val wrapperRead = CompletableDeferred<Unit>()
        val wrapperPublished = CompletableDeferred<Unit>()
        val releaseWrapper = CountDownLatch(1)
        fixture(
            authorityDecorate = { coordinator ->
                spyk(coordinator).also { decorated ->
                    every { decorated.withLock(any<(RootAccessState) -> Any?>()) } answers {
                        val result = callOriginal()
                        if (wrapperThread.get() == true && result === Unit) {
                            wrapperThread.set(false)
                        } else if (wrapperThread.get() == false && result == null) {
                            wrapperThread.remove()
                            wrapperPublished.complete(Unit)
                        }
                        result
                    }
                }
            },
            configure = { factory, _ ->
                factory.process.onWrapperExitValue = {
                    wrapperRead.complete(Unit)
                    check(releaseWrapper.await(10L, TimeUnit.SECONDS))
                    wrapperThread.set(true)
                }
            }
        ).use { fixture ->
            try {
                val handle = fixture.start()
                fixture.ready()
                assertTrue(readyResult(handle))
                fixture.factory.process.exit(0)
                fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
                fixture.factory.process.stdout.close()
                fixture.factory.process.alive.set(false)
                awaitSignal(wrapperRead)
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                eventually { fixture.diagnostics.latestUnknown(instance)?.completionEvidence != null }
                val frozen = fixture.diagnostics.latestUnknown(instance)!!.completionEvidence!!
                assertEquals(ChrootStopTrigger.TASK_EXIT, frozen.trigger)
                assertEquals("HELPER_EXIT", frozen.pending())
                assertEquals(127, frozen.flags)
                assertTrue(fixture.manager.instanceState(instance).blocked)
                releaseWrapper.countDown()
                awaitSignal(wrapperPublished)
                assertEquals(ChrootCleanupResult.UNKNOWN, handle.awaitCleanup())
                assertFalse(fixture.recovery.canStart(instance))
                eventually {
                    val reloaded = ChrootRuntimeDiagnostics(fixture.diagnosticFile)
                    reloaded.isHealthy && reloaded.latestUnknown(instance)?.completionEvidence == frozen
                }
                assertEquals(0, fixture.factory.process.destroys.get())
            } finally {
                releaseWrapper.countDown()
                if (wrapperRead.isCompleted) awaitSignal(wrapperPublished)
            }
        }
    }

    @Test
    fun deadlineRequiresStdoutEofEvenAfterCleanupAndZeroHelperExitStatus() = runTest {
        val wrapperThread = ThreadLocal<Boolean>()
        val wrapperPublished = CompletableDeferred<Unit>()
        fixture(
            authorityDecorate = { coordinator ->
                spyk(coordinator).also { decorated ->
                    every { decorated.withLock(any<(RootAccessState) -> Any?>()) } answers {
                        val result = callOriginal()
                        if (wrapperThread.get() == true && result === Unit) {
                            wrapperThread.set(false)
                        } else if (wrapperThread.get() == false && result == null) {
                            wrapperThread.remove()
                            wrapperPublished.complete(Unit)
                        }
                        result
                    }
                }
            },
            configure = { factory, _ -> factory.process.onWrapperExitValue = { wrapperThread.set(true) } }
        ).use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            assertTrue(readyResult(handle))
            fixture.factory.process.exit(0)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            assertEquals(ChrootTaskResult.Exited(ChrootProcessExit(0, null)), handle.awaitTaskExit())
            fixture.factory.process.alive.set(false)
            awaitSignal(wrapperPublished)
            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            eventually { fixture.diagnostics.latestUnknown(instance)?.completionEvidence != null }
            val frozen = fixture.diagnostics.latestUnknown(instance)!!.completionEvidence!!
            assertEquals("STDOUT_EOF", frozen.pending())
            assertEquals(ChrootCompletionEvidence.ALL_FLAGS xor ChrootCompletionEvidence.STDOUT_ENDED, frozen.flags)
            assertEquals(ChrootCleanupResult.UNKNOWN, handle.cleanupState.value)
            assertEquals(ChrootRuntimeFailureStage.STOP_DEADLINE, handle.failure.value?.stage)
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
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
        val parent = temporary.newFolder()
        fixture(File(parent, "journal.bin")).use { fixture ->
            assertTrue(fixture.recovery.isHealthy.value)
            // Introduce the I/O failure after loading a healthy repository, so the failure
            // occurs while recording intent rather than during the initial parent validation.
            assertTrue(parent.delete())
            parent.writeText("block journal creation")
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
    fun diagnosticDiskFailureDoesNotDelayUnknownStopOrHoldTheRootGate() = runTest {
        fixture().use { fixture ->
            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val file = File(temporary.newFolder(), "failures.bin")
            val diagnostics = ChrootRuntimeDiagnostics(file) {
                writeEntered.countDown()
                check(releaseWrite.await(5, TimeUnit.SECONDS))
                throw IOException("private disk failure")
            }
            val manager = ChrootRuntimeManager(fixture.authority, fixture.factory, fixture.recovery, fixture.scope,
                Dispatchers.IO, { fixture.control }, { System.nanoTime() / 1_000_000L }, diagnostics, cleanupTimeoutMs = 4_000L)
            val lease = manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root)!!
            val permit = manager.authorize(lease, spec(), 1)!!
            val handle = manager.launch(lease, permit, spec()) {}!!
            awaitSignal(fixture.control.awaiting)
            try {
                fixture.factory.process.frame(ChrootFrameType.ERROR,
                    "code=prepare_failed;stage=file_worker_resource;errno=13".toByteArray())
                assertTrue(withContext(Dispatchers.IO) { writeEntered.await(5, TimeUnit.SECONDS) })
                assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
                assertTrue(fixture.authority.withCurrent(fixture.root.authorityId, fixture.root.generation) { true } == true)
                assertTrue(manager.instanceState(instance).blocked)
                assertFalse(fixture.control.sent.contains(ChrootFrameType.GO))
                assertNull(manager.allowBinding(owner, "profile", 1, instance, rootfs, fixture.root))
            } finally {
                releaseWrite.countDown()
            }
            eventually { !diagnostics.isHealthy }
            eventually { manager.failureSummary(instance)?.contains("diagnosticPersistence=UNAVAILABLE") == true }
            assertEquals(ChrootCleanupResult.UNKNOWN, fixture.recovery.snapshot().single().cleanup)
        }
    }

    @Test
    fun cleanupJournalWriteFailurePreservesTheUnknownEntryAndDiagnostic() = runTest {
        val journal = File(temporary.newFolder(), "runtime.bin")
        fixture(journal).use { fixture ->
            val handle = fixture.start()
            fixture.ready()
            assertTrue(readyResult(handle))
            eventually { fixture.recovery.snapshot().single().phase == ChrootRecoveryPhase.RUNNING }
            File(journal.path + ".pending").writeBytes(byteArrayOf(1))
            fixture.factory.process.exit(0)
            fixture.factory.process.frame(ChrootFrameType.CLEANUP, byteArrayOf(0))
            fixture.factory.process.finish()

            assertEquals(ChrootCleanupResult.UNKNOWN, cleanupResult(handle))
            assertFalse(fixture.recovery.isHealthy.value)
            assertEquals(setOf(spec().launchId), fixture.recovery.unknownLaunchIds(instance))
            assertEquals(ChrootRecoveryPhase.UNKNOWN, fixture.recovery.snapshot().single().phase)
            assertEquals(ChrootCleanupResult.UNKNOWN, fixture.recovery.snapshot().single().cleanup)
            assertEquals(ChrootRuntimeFailureStage.CLEANUP_JOURNAL, handle.failure.value!!.stage)
            eventually {
                fixture.manager.failureSummary(instance)?.contains("failure=CLEANUP/CLEANUP_JOURNAL") == true
            }
            assertTrue(fixture.manager.instanceState(instance).blocked)
            assertFalse(fixture.recovery.canStart(instance))
            assertEquals(0, fixture.factory.process.destroys.get())
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
            eventually { fixture.diagnostics.latestUnknown(instance)?.nativeFailure == failure }
            eventually {
                ChrootRuntimeDiagnostics(fixture.diagnosticFile).latestUnknown(instance)?.nativeFailure == failure
            }
            val restored = ChrootRuntimeDiagnostics(fixture.diagnosticFile).latestUnknown(instance)!!
            assertEquals(failure, restored.failure)
            assertTrue(fixture.manager.failureSummary(instance)!!.contains("prepare_failed/file_worker_resource;errno=13"))
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
            eventually {
                ChrootRuntimeDiagnostics(fixture.diagnosticFile).latestUnknown(instance)?.let {
                    it.failure == first && it.nativeFailure == native
                } == true
            }
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
