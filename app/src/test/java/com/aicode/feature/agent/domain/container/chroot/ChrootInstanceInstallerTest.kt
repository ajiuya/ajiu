package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeRemoval
import com.aicode.feature.agent.domain.root.RootAccessState
import com.aicode.feature.agent.domain.root.RootAuthorityCoordinator
import com.aicode.feature.agent.domain.root.RootMemoryDataStore
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootInstanceInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "0123456789abcdef01234567"
    private val identity = ChrootFileIdentity(5, 19)
    private val instance get() = ChrootInstance(id, identity.device, identity.inode, 1234)
    private val image = ChrootArchiveFixtures.gzip(ChrootArchiveFixtures.archive(ChrootArchiveFixtures.entry("file", data = "ok".toByteArray())))
    private val profile = ContainerProfile(
        id = "profile-id", name = "test", rootfsSource = RootfsSource.LocalFile("content://test/archive"),
        shellPath = null, isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT
    )

    private class FakeProcess(
        private val result: () -> String,
        private val exit: Int = 0,
        private val error: ByteArray = byteArrayOf(),
        private val finishOnClose: Boolean = true,
        private val closeFailure: IOException? = null
    ) : Process() {
        val closed = AtomicBoolean()
        val destroyed = AtomicInteger()
        val waited = AtomicBoolean()
        val writes = ByteArrayOutputStream()
        val ended = CountDownLatch(1)
        var onClose: () -> Unit = {}
        private val input = object : InputStream() {
            private var bytes: ByteArrayInputStream? = null
            override fun read(): Int {
                if (bytes == null) { ended.await(); bytes = ByteArrayInputStream(result().toByteArray()) }
                return bytes!!.read()
            }
            override fun read(target: ByteArray, offset: Int, count: Int): Int {
                if (bytes == null) { ended.await(); bytes = ByteArrayInputStream(result().toByteArray()) }
                return bytes!!.read(target, offset, count)
            }
            override fun close() { ended.countDown() }
        }
        private val output = object : OutputStream() {
            override fun write(value: Int) {
                if (closed.get()) throw IOException("closed")
                synchronized(writes) { writes.write(value) }
            }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (closed.get()) throw IOException("closed")
                synchronized(writes) { writes.write(bytes, offset, count) }
            }
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    onClose()
                    if (finishOnClose) ended.countDown()
                    closeFailure?.let { throw it }
                }
            }
        }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(error)
        override fun getOutputStream(): OutputStream = output
        override fun waitFor(): Int {
            ended.await()
            waited.set(true)
            return exit
        }
        override fun exitValue(): Int = if (ended.count == 0L) exit else throw IllegalThreadStateException()
        override fun isAlive(): Boolean = ended.count != 0L
        override fun destroy() { destroyed.incrementAndGet(); ended.countDown() }
    }

    private class BlockingSource : InputStream() {
        val entered = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val released = CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            released.await()
            return -1
        }
        override fun close() { closed.set(true); released.countDown() }
    }

    private data class Fixture(
        val installer: ChrootInstanceInstaller,
        val authority: RootAuthorityCoordinator,
        val root: RootAccessState,
        val scope: CoroutineScope,
        val files: File,
        val journal: File,
        val process: FakeProcess,
        val starts: AtomicInteger,
        val inputs: AtomicInteger,
        val calls: MutableList<Pair<String, ChrootFileIdentity?>>
    ) : AutoCloseable {
        override fun close() {
            process.ended.countDown()
            scope.cancel()
        }
    }

    private suspend fun TestScope.fixture(
        process: FakeProcess = FakeProcess({ native() }),
        input: () -> InputStream = { ByteArrayInputStream(image) },
        timeout: Long = 2000,
        rollback: Long = 250,
        newId: () -> String = { id },
        files: File = temporary.newFolder(),
        journal: File = File(temporary.newFolder(), "installer.bin"),
        enabled: Boolean = true,
        startBound: ((String, ChrootFileIdentity?, ChrootRuntimeSession) -> Process)? = null,
        checkRecoverySession: ((ChrootRuntimeSession) -> Unit)? = null,
        startRecoveryBound: ((String, ChrootFileIdentity, ChrootRuntimeSession, () -> Unit) -> Process)? = null
    ): Fixture {
        val repository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
        val authority = RootAuthorityCoordinator(repository, backgroundScope)
        authority.setEnabled(enabled)
        val root = authority.accessSnapshot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val starts = AtomicInteger()
        val inputs = AtomicInteger()
        val calls = mutableListOf<Pair<String, ChrootFileIdentity?>>()
        val installer = ChrootInstanceInstaller(
            authority, ChrootArchiveWriter(), { instance, remove ->
                starts.incrementAndGet()
                synchronized(calls) { calls.add(instance to remove) }
                process
            }, { inputs.incrementAndGet(); input() }, files, journal, scope, Dispatchers.IO, { 1234 }, { 10445 }, newId, timeout, rollback,
            startBound = startBound,
            checkRecoverySession = checkRecoverySession,
            startRecoveryBound = startRecoveryBound
        )
        return Fixture(installer, authority, root, scope, files, journal, process, starts, inputs, calls)
    }

    private fun native(
        passed: Boolean = true,
        cleanup: String = "installed",
        path: String? = "/data/local/ajiu-chroot-rootfs-$id",
        device: Long = identity.device,
        inode: Long = identity.inode,
        entries: Int = 2,
        bytes: Int = 2,
        stage: String = "root_mode",
        errno: Int = if (passed) 0 else 22
    ): String = """{"passed":$passed,"stage":"$stage","errno":$errno,"entries":$entries,"file_bytes":$bytes,"cleanup_state":"$cleanup","device":$device,"inode":$inode,"created_directory":${path?.let { "\"$it\"" } ?: "null"}}"""

    private fun writeInstallerJournal(journal: File, version: Int, operation: Int, target: ChrootInstance = instance) {
        DataOutputStream(journal.outputStream()).use { output ->
            output.writeInt(0x414a4950)
            output.writeInt(version)
            output.writeInt(1)
            output.writeUTF(target.id)
            output.writeByte(operation)
            output.writeLong(target.installGeneration)
            output.writeLong(target.device)
            output.writeLong(target.inode)
        }
    }

    private fun assertRecoveryIntent(journal: File, target: ChrootInstance = instance) {
        DataInputStream(journal.inputStream()).use { input ->
            assertEquals(0x414a4950, input.readInt())
            assertEquals(2, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(target.id, input.readUTF())
            assertEquals(2, input.readUnsignedByte())
            assertEquals(target.installGeneration, input.readLong())
            assertEquals(target.device, input.readLong())
            assertEquals(target.inode, input.readLong())
            assertEquals(-1, input.read())
        }
    }

    private suspend fun fails(block: suspend () -> Unit): Throwable {
        try {
            block()
            throw AssertionError("Operation unexpectedly succeeded")
        } catch (failure: AssertionError) {
            throw failure
        } catch (failure: Exception) {
            return failure
        }
    }

    private suspend fun eventually(predicate: () -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(3000) { while (!predicate()) delay(5) }
    }

    @Test
    fun closedChangedAuthorityAndInvalidProfilesNeverStartFactoryOrOpenImage() = runTest {
        fixture().use { f ->
            fails { f.installer.installFromUser(profile, f.root.copy(authorityId = "other")) }
            fails { f.installer.installFromUser(profile, f.root.copy(generation = f.root.generation + 1)) }
            fails { f.installer.installFromUser(profile.copy(id = "../escape"), f.root) }
            fails { f.installer.installFromUser(profile.copy(localRuntime = LocalContainerRuntime.PROOT), f.root) }
            fails { f.installer.installFromUser(profile.copy(chrootInstance = instance), f.root) }
            fails { f.installer.installFromUser(profile.copy(rootfsSource = RootfsSource.RemoteSsh("ssh", "/")), f.root) }
            f.authority.setEnabled(false)
            fails { f.installer.installFromUser(profile, f.root) }
            assertEquals(0, f.starts.get())
            assertEquals(0, f.inputs.get())
            assertTrue(f.installer.failedInstances.value.isEmpty())
        }
    }

    @Test
    fun verifiedInstallSendsExactArchiveAndCreatesOnlyDedicatedAppHomeAndDns() = runTest {
        fixture().use { f ->
            val result = f.installer.installFromUser(profile, f.root)
            assertEquals(instance, result)
            assertEquals(listOf(id to null), f.calls)
            assertEquals(1, f.inputs.get())
            val expected = ByteArrayOutputStream().also { ChrootArchiveWriter().write(ByteArrayInputStream(image), com.aicode.feature.agent.domain.container.ContainerInstaller.CompressedFormat.GZIP, it) }.toByteArray()
            assertArrayEquals(expected, f.process.writes.toByteArray())
            assertTrue(f.process.closed.get())
            assertTrue(f.process.waited.get())
            assertEquals(0, f.process.destroyed.get())
            assertTrue(File(f.files, "chroot-home/$id/workspace").isDirectory)
            assertTrue(File(f.files, "chroot-home/$id/.aicode/skills").isDirectory)
            assertTrue(File(f.files, "chroot-home/$id/.aicode/tool-output").isDirectory)
            assertEquals("nameserver 223.5.5.5\nnameserver 223.6.6.6\n", File(f.files, "chroot-dns/$id/resolv.conf").readText())
            assertFalse(File(f.files, "rootfs").exists())
            assertFalse(File(f.files, "aicode").exists())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertEquals(File(f.files, "chroot-home/$id"), f.installer.prepareHome(result))
            f.authority.setEnabled(false)
            assertFalse(f.authority.accessSnapshot().enabled)
        }
    }

    @Test
    fun wrongPathIdentityCountsExitOrMalformedJsonAreUnknownNotReady() = runTest {
        val cases = listOf(
            native(path = "/data/local/ajiu-chroot-rootfs-${"a".repeat(24)}") to 0,
            native(device = 0) to 0,
            native(inode = 0) to 0,
            native(entries = 3) to 0,
            native(bytes = 3) to 0,
            native() to 3,
            native(cleanup = "cleanup_unknown") to 5,
            "not JSON" to 0,
            native().replace("\"passed\":true", "\"passed\":false,\"passed\":true") to 0,
            native().replace("\"device\":5", "\"device\":\"5\"") to 0,
            (native() + native()) to 0
        )
        for ((result, exit) in cases) {
            fixture(FakeProcess({ result }, exit)).use { f ->
                fails { f.installer.installFromUser(profile, f.root) }
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(f.journal.isFile)
                assertFalse(File(f.files, "chroot-home/$id").exists())
            }
        }
    }

    @Test
    fun explicitNativeRollbackClearsPendingButDoesNotHideInstallFailure() = runTest {
        fixture(FakeProcess({ native(passed = false, cleanup = "removed") }, 3)).use { f ->
            fails { f.installer.installFromUser(profile, f.root) }
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertTrue(f.installer.isHealthy.value)
            assertEquals(0, f.process.destroyed.get())
        }
        fixture(FakeProcess({ native(passed = false, cleanup = "not_created", path = null, device = 0, inode = 0) }, 3)).use { f ->
            fails { f.installer.installFromUser(profile, f.root) }
            assertTrue(f.installer.failedInstances.value.isEmpty())
        }
    }

    @Test
    fun invalidArchiveClosesInputAllowsNativeRollbackAndNeverReturnsReady() = runTest {
        fixture(FakeProcess({ native(passed = false, cleanup = "removed") }, 3), input = { ByteArrayInputStream("not an archive".toByteArray()) }).use { f ->
            fails { f.installer.installFromUser(profile, f.root) }
            assertTrue(f.process.closed.get())
            assertEquals(0, f.process.writes.size())
            assertEquals(0, f.process.destroyed.get())
            assertTrue(f.installer.failedInstances.value.isEmpty())
        }
    }

    @Test
    fun revocationMarksStopThenClosesInputWithoutDestroyingWrapperBeforeRollback() = runTest {
        val input = BlockingSource()
        fixture(FakeProcess({ native(passed = false, cleanup = "removed") }, 3), { input }).use { f ->
            val installing = async { fails { f.installer.installFromUser(profile, f.root) } }
            eventually { input.entered.count == 0L }
            f.authority.setEnabled(false)
            installing.await()
            assertTrue(f.process.closed.get())
            assertTrue(input.closed.get())
            assertEquals(0, f.process.destroyed.get())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertFalse(f.authority.accessSnapshot().enabled)
        }
    }

    @Test
    fun callerCancellationAlsoClosesInputAndWaitsForRollback() = runTest {
        val input = BlockingSource()
        fixture(FakeProcess({ native(passed = false, cleanup = "removed") }, 3), { input }).use { f ->
            val installing = async { f.installer.installFromUser(profile, f.root) }
            eventually { input.entered.count == 0L }
            installing.cancel()
            installing.join()
            assertTrue(f.process.closed.get())
            assertTrue(input.closed.get())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertEquals(0, f.process.destroyed.get())
        }
    }

    @Test
    fun timeoutAndOversizedStdioRemainUnknownWithinBoundedBudget() = runTest {
        fixture(FakeProcess({ native() }, finishOnClose = false), timeout = 50, rollback = 50).use { f ->
            val started = System.nanoTime()
            fails { f.installer.installFromUser(profile, f.root) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000)
            assertTrue(f.process.closed.get())
            assertEquals(setOf(id), f.installer.failedInstances.value)
        }
        for (process in listOf(FakeProcess({ "x".repeat(16 * 1024 + 1) }), FakeProcess({ native() }, error = ByteArray(16 * 1024 + 1)))) {
            fixture(process).use { f ->
                fails { f.installer.installFromUser(profile, f.root) }
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(f.process.closed.get())
            }
        }
    }

    @Test
    fun coldPendingIsUnknownBlocksReuseAndDoesNotAutomaticallyRemove() = runTest {
        val files = temporary.newFolder()
        val journal = File(temporary.newFolder(), "journal.bin")
        fixture(FakeProcess({ native(cleanup = "cleanup_unknown", passed = false) }, 5), files = files, journal = journal).use { f ->
            fails { f.installer.installFromUser(profile, f.root) }
            assertEquals(setOf(id), f.installer.failedInstances.value)
        }
        fixture(files = files, journal = journal).use { recovered ->
            assertEquals(setOf(id), recovered.installer.failedInstances.value)
            assertEquals(0, recovered.starts.get())
            fails { recovered.installer.installFromUser(profile, recovered.root) }
            fails { recovered.installer.removeFromUser(instance, recovered.root) }
            fails { recovered.installer.prepareHome(instance) }
            assertEquals(0, recovered.starts.get())
            val stored = journal.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains(recovered.root.authorityId))
            assertFalse(stored.contains("content://"))
            assertFalse(stored.contains("--install"))
        }
    }

    @Test
    fun corruptOrUnfinishedJournalFailsClosedWithoutStartingRoot() = runTest {
        for (unfinished in listOf(false, true)) {
            val journal = File(temporary.newFolder(), "journal.bin")
            if (unfinished) File(journal.path + ".pending").writeBytes(byteArrayOf(1)) else journal.writeBytes(byteArrayOf(1))
            fixture(journal = journal).use { f ->
                assertFalse(f.installer.isHealthy.value)
                fails { f.installer.installFromUser(profile, f.root) }
                assertEquals(0, f.starts.get())
            }
        }
    }

    @Test
    fun removeUsesOnlyCapturedIdentityAndDoesNotDeleteAppHomeOrOpenImage() = runTest {
        fixture(FakeProcess({ native(cleanup = "removed") })).use { f ->
            val home = f.installer.prepareHome(instance)
            File(home, "user-data").writeText("keep")
            f.installer.removeFromUser(instance, f.root)
            assertEquals(listOf(id to identity), f.calls)
            assertEquals(0, f.inputs.get())
            assertEquals(0, f.process.writes.size())
            assertEquals("keep", File(home, "user-data").readText())
            assertTrue(f.installer.failedInstances.value.isEmpty())
        }
        fixture(FakeProcess({ native(cleanup = "removed", inode = 20) })).use { f ->
            fails { f.installer.removeFromUser(instance, f.root) }
            assertEquals(setOf(id), f.installer.failedInstances.value)
        }
    }

    @Test
    fun verifiedInstallationPublishesBeforeClearingIntentAndFailedPublicationSurvivesRestart() = runTest {
        val files = temporary.newFolder()
        val journal = File(temporary.newFolder(), "publish.bin")
        fixture(files = files, journal = journal).use { f ->
            var published = false
            fails {
                f.installer.installFromUser(profile, f.root) { installed ->
                    assertEquals(instance, installed)
                    assertTrue(f.process.waited.get())
                    assertTrue(File(files, "chroot-home/$id/workspace").isDirectory)
                    assertTrue(journal.length() > 12)
                    published = true
                    throw IOException("Profile binding was not persisted")
                }
            }
            assertTrue(published)
            assertEquals(setOf(id), f.installer.failedInstances.value)
            assertTrue(f.installer.isHealthy.value)
        }
        fixture(files = files, journal = journal).use { recovered ->
            assertEquals(setOf(id), recovered.installer.failedInstances.value)
            fails { recovered.installer.installFromUser(profile, recovered.root) }
            fails { recovered.installer.removeFromUser(instance, recovered.root) }
            assertEquals(0, recovered.starts.get())
        }
    }

    @Test
    fun unpublishedInstallationBlocksAllocatingAnotherInstanceBeforeAnyAdditionalNativeStart() = runTest {
        val allocations = AtomicInteger()
        fixture(newId = { if (allocations.getAndIncrement() == 0) id else "abcdef0123456789abcdef01" }).use { f ->
            fails { f.installer.installFromUser(profile, f.root) { throw IOException("publication failed") } }
            assertEquals(setOf(id), f.installer.unpublishedInstalls.value)
            val starts = f.starts.get()
            fails { f.installer.installFromUser(profile, f.root) }
            fails { f.installer.installFromUser(profile.copy(id = "another-profile"), f.root) }
            assertEquals(starts, f.starts.get())
            assertEquals(1, allocations.get())
            assertTrue(f.journal.length() > 12)
        }
    }

    @Test
    fun verifiedRemovalDoesNotDiscardPendingIntentIfProfileRemovalCannotBePublished() = runTest {
        val files = temporary.newFolder()
        val journal = File(temporary.newFolder(), "remove-publish.bin")
        fixture(FakeProcess({ native(cleanup = "removed") }), files = files, journal = journal).use { f ->
            var published = false
            fails {
                f.installer.removeFromUser(instance, f.root) {
                    assertEquals(listOf(id to identity), f.calls)
                    published = true
                    throw IOException("Profile removal was not persisted")
                }
            }
            assertTrue(published)
            assertEquals(setOf(id), f.installer.failedInstances.value)
        }
        fixture(files = files, journal = journal).use { recovered ->
            assertEquals(setOf(id), recovered.installer.failedInstances.value)
            fails { recovered.installer.removeFromUser(instance, recovered.root) }
            assertEquals(0, recovered.starts.get())
        }
    }

    @Test
    fun rootRevocationDuringPublicationCannotClearThePendingIntent() = runTest {
        for (removing in listOf(false, true)) {
            val process = FakeProcess({ native(cleanup = if (removing) "removed" else "installed") })
            fixture(process).use { f ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val operation = async {
                    fails {
                        if (removing) f.installer.removeFromUser(instance, f.root) {
                            entered.complete(Unit)
                            release.await()
                        } else f.installer.installFromUser(profile, f.root) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                }
                entered.await()
                val revocation = async { f.authority.setEnabled(false) }
                eventually { !f.authority.accessState.value.enabled }
                release.complete(Unit)
                operation.await()
                revocation.await()
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(f.journal.length() > 12)
                assertEquals(1, f.starts.get())
                assertEquals(0, f.process.destroyed.get())
            }
        }
    }

    @Test
    fun successfulPublicationClearsIntentOnlyAfterItsCallbackReturns() = runTest {
        fixture().use { f ->
            val published = mutableListOf<ChrootInstance>()
            val installed = f.installer.installFromUser(profile, f.root) {
                published += it
                assertTrue(f.journal.length() > 12)
            }
            assertEquals(listOf(installed), published)
            assertEquals(12L, f.journal.length())
            assertTrue(f.installer.failedInstances.value.isEmpty())
        }
        fixture(FakeProcess({ native(cleanup = "removed") })).use { f ->
            var published = false
            f.installer.removeFromUser(instance, f.root) {
                published = true
                assertTrue(f.journal.length() > 12)
            }
            assertTrue(published)
            assertEquals(12L, f.journal.length())
            assertTrue(f.installer.failedInstances.value.isEmpty())
        }
    }

    @Test
    fun installCarriesExactCapturedSessionAfterIntentAndRetainsPendingWhenThatSessionChanges() = runTest {
        val files = temporary.newFolder()
        val journal = File(temporary.newFolder(), "session-install.bin")
        val boundCalls = AtomicInteger()
        val boundProcessesCreated = AtomicInteger()
        val publications = AtomicInteger()
        lateinit var oldSession: ChrootRuntimeSession
        var currentSession: ChrootRuntimeSession? = null
        fixture(files = files, journal = journal, startBound = { instanceId, removeIdentity, session ->
            boundCalls.incrementAndGet()
            assertEquals(id, instanceId)
            assertNull(removeIdentity)
            assertTrue(journal.length() > 12)
            assertSame(oldSession, session)
            assertSame(oldSession, currentSession)
            currentSession = ChrootRuntimeSession("new-install-session", oldSession.rootState.copy())
            assertEquals(oldSession.rootState, currentSession!!.rootState)
            assertNotSame(oldSession, currentSession)
            if (session !== currentSession) throw ChrootRuntimeUnavailable("Root runtime session changed before dispatch")
            boundProcessesCreated.incrementAndGet()
            FakeProcess({ native() })
        }).use { f ->
            oldSession = ChrootRuntimeSession("old-install-session", f.root)
            currentSession = oldSession
            val failure = fails {
                f.installer.installFromUser(profile, f.root, oldSession) { publications.incrementAndGet() }
            }
            assertTrue(failure is ChrootRuntimeUnavailable)
            assertEquals("Root runtime session changed before dispatch", failure.message)
            assertEquals(1, boundCalls.get())
            assertEquals(0, boundProcessesCreated.get())
            assertEquals(0, f.starts.get())
            assertEquals(0, f.inputs.get())
            assertTrue(f.calls.isEmpty())
            assertFalse(f.process.waited.get())
            assertEquals(0, f.process.writes.size())
            assertEquals(0, publications.get())
            assertEquals(setOf(id), f.installer.failedInstances.value)
            assertTrue(journal.length() > 12)
            assertTrue(f.installer.isHealthy.value)
            assertEquals(f.root, f.authority.accessSnapshot())
        }
        val retained = journal.readBytes()
        fixture(files = files, journal = journal).use { recovered ->
            assertEquals(setOf(id), recovered.installer.failedInstances.value)
            assertEquals(0, recovered.starts.get())
            assertArrayEquals(retained, journal.readBytes())
        }
    }

    @Test
    fun removeCarriesExactCapturedSessionAndIdentityAfterIntentAndRetainsPendingOnReplacement() = runTest {
        val files = temporary.newFolder()
        val journal = File(temporary.newFolder(), "session-remove.bin")
        val boundCalls = AtomicInteger()
        val boundProcessesCreated = AtomicInteger()
        val publications = AtomicInteger()
        lateinit var oldSession: ChrootRuntimeSession
        var currentSession: ChrootRuntimeSession? = null
        fixture(files = files, journal = journal, startBound = { instanceId, removeIdentity, session ->
            boundCalls.incrementAndGet()
            assertEquals(id, instanceId)
            assertEquals(identity, removeIdentity)
            assertTrue(journal.length() > 12)
            assertSame(oldSession, session)
            assertSame(oldSession, currentSession)
            currentSession = ChrootRuntimeSession("new-remove-session", oldSession.rootState.copy())
            assertEquals(oldSession.rootState, currentSession!!.rootState)
            assertNotSame(oldSession, currentSession)
            if (session !== currentSession) throw ChrootRuntimeUnavailable("Root runtime session changed before dispatch")
            boundProcessesCreated.incrementAndGet()
            FakeProcess({ native(cleanup = "removed") })
        }).use { f ->
            oldSession = ChrootRuntimeSession("old-remove-session", f.root)
            currentSession = oldSession
            val failure = fails {
                f.installer.removeFromUser(instance, f.root, oldSession) { publications.incrementAndGet() }
            }
            assertTrue(failure is ChrootRuntimeUnavailable)
            assertEquals("Root runtime session changed before dispatch", failure.message)
            assertEquals(1, boundCalls.get())
            assertEquals(0, boundProcessesCreated.get())
            assertEquals(0, f.starts.get())
            assertEquals(0, f.inputs.get())
            assertTrue(f.calls.isEmpty())
            assertFalse(f.process.waited.get())
            assertEquals(0, f.process.writes.size())
            assertEquals(0, publications.get())
            assertEquals(setOf(id), f.installer.failedInstances.value)
            assertTrue(journal.length() > 12)
            assertTrue(f.installer.isHealthy.value)
            assertEquals(f.root, f.authority.accessSnapshot())
        }
        val retained = journal.readBytes()
        fixture(files = files, journal = journal).use { recovered ->
            assertEquals(setOf(id), recovered.installer.failedInstances.value)
            assertEquals(0, recovered.starts.get())
            assertArrayEquals(retained, journal.readBytes())
        }
    }

    @Test
    fun productionStyleBoundStarterRejectsMissingSessionWithoutFallingBackForInstallOrRemove() = runTest {
        for (removing in listOf(false, true)) {
            val files = temporary.newFolder()
            val journal = File(temporary.newFolder(), "missing-session.bin")
            val boundCalls = AtomicInteger()
            val boundProcessesCreated = AtomicInteger()
            val publications = AtomicInteger()
            fixture(files = files, journal = journal, startBound = { _, _, _ ->
                boundCalls.incrementAndGet()
                boundProcessesCreated.incrementAndGet()
                FakeProcess({ native(cleanup = if (removing) "removed" else "installed") })
            }).use { f ->
                val failure = fails {
                    if (removing) f.installer.removeFromUser(instance, f.root) { publications.incrementAndGet() }
                    else f.installer.installFromUser(profile, f.root) { publications.incrementAndGet() }
                }
                assertTrue(failure is ChrootRuntimeUnavailable)
                assertEquals("Installer requires the user-authorized Root session", failure.message)
                assertEquals(0, boundCalls.get())
                assertEquals(0, boundProcessesCreated.get())
                assertEquals(0, f.starts.get())
                assertEquals(0, f.inputs.get())
                assertTrue(f.calls.isEmpty())
                assertFalse(f.process.waited.get())
                assertEquals(0, publications.get())
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(journal.length() > 12)
            }
            val retained = journal.readBytes()
            fixture(files = files, journal = journal).use { recovered ->
                assertEquals(setOf(id), recovered.installer.failedInstances.value)
                assertEquals(0, recovered.starts.get())
                assertArrayEquals(retained, journal.readBytes())
            }
        }
    }

    @Test
    fun queuedRecoveryRechecksItsSourceAfterAnotherInstancesTransactionReleasesTheMutex() = runTest {
        val otherId = "abcdef0123456789abcdef01"
        val process = FakeProcess({ native(path = "/data/local/ajiu-chroot-rootfs-$otherId") })
        val recoveryStarts = AtomicInteger()
        fixture(process, newId = { otherId }, startRecoveryBound = { _, _, _, _ ->
            recoveryStarts.incrementAndGet()
            FakeProcess({ native(cleanup = "removed") })
        }).use { f ->
            val firstPublishing = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val first = async {
                f.installer.installFromUser(profile, f.root) {
                    firstPublishing.complete(Unit)
                    releaseFirst.await()
                }
            }
            try {
                firstPublishing.await()
                val sourceCurrent = AtomicBoolean(true)
                val checks = AtomicInteger()
                val publications = AtomicInteger()
                val session = ChrootRuntimeSession("reviewed", f.root)
                // Same dispatcher as the installer and UNDISPATCHED enter the real locked mutex
                // before this async returns; no mocked facade or delay is needed to create the queue.
                val queued = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    fails {
                        f.installer.recoverAndRemoveFromUser(instance, f.root, session, checkCurrent = {
                            checks.incrementAndGet()
                            if (!sourceCurrent.get()) throw CancellationException("Recovery configuration changed while queued")
                        }) { publications.incrementAndGet() }
                    }
                }
                assertEquals(0, checks.get())
                assertFalse(queued.isCompleted)
                assertEquals(0, recoveryStarts.get())
                sourceCurrent.set(false)
                releaseFirst.complete(Unit)
                assertEquals(otherId, first.await().id)
                assertTrue(queued.await() is CancellationException)
                assertEquals(1, checks.get())
                assertEquals(0, recoveryStarts.get())
                assertEquals(0, publications.get())
                assertEquals(1, f.starts.get())
                assertEquals(12L, f.journal.length())
                assertTrue(f.installer.failedInstances.value.isEmpty())
                assertTrue(f.installer.removalPending.value.isEmpty())
            } finally {
                releaseFirst.complete(Unit)
            }
        }
    }

    @Test
    fun recoveryForwardsTheLiveCheckAndSuccessfulPublicationMayChangeItsOwnConfigurationRevision() = runTest {
        val process = FakeProcess({ native(cleanup = "removed") })
        val sourceCurrent = AtomicBoolean(true)
        val checkCurrent: () -> Unit = {
            if (!sourceCurrent.get()) throw CancellationException("Profile revision changed")
        }
        fixture(process, startRecoveryBound = { _, _, _, dispatchCheck ->
            assertSame(checkCurrent, dispatchCheck)
            dispatchCheck()
            process
        }).use { f ->
            val publications = AtomicInteger()
            assertEquals(LocalRuntimeRemoval.REMOVED,
                f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("reviewed", f.root), checkCurrent) {
                    publications.incrementAndGet()
                    sourceCurrent.set(false)
                })
            assertEquals(1, publications.get())
            assertEquals(12L, f.journal.length())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertTrue(f.installer.removalPending.value.isEmpty())
        }
    }

    @Test
    fun recoveryRechecksSourceAfterNativeExitBeforeAcceptingReceiptOrPublishingRemoval() = runTest {
        for (cleanup in listOf("reboot_required", "removed")) {
            val sourceCurrent = AtomicBoolean(true)
            val process = FakeProcess({ native(cleanup = cleanup) })
            process.onClose = { sourceCurrent.set(false) }
            fixture(process, startRecoveryBound = { _, _, _, _ -> process }).use { f ->
                val publications = AtomicInteger()
                val failure = fails {
                    f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("reviewed", f.root), checkCurrent = {
                        if (!sourceCurrent.get()) throw CancellationException("Recovery source changed while native was running")
                    }) { publications.incrementAndGet() }
                }
                assertTrue(failure is CancellationException)
                assertEquals(0, publications.get())
                assertRecoveryIntent(f.journal)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertEquals(setOf(id), f.installer.removalPending.value)
            }
        }
    }

    @Test
    fun recoveryReceiptPersistsWithoutPublicationAndOnlyExactRecoveryCanResumeAfterRestart() = runTest {
        val files = temporary.newFolder()
        val journal = File(temporary.newFolder(), "recovery.bin")
        val publications = AtomicInteger()
        val recoveryStarts = AtomicInteger()
        val receipt = FakeProcess({ native(cleanup = "reboot_required") })
        lateinit var session: ChrootRuntimeSession
        lateinit var installer: ChrootInstanceInstaller
        fixture(receipt, files = files, journal = journal, startRecoveryBound = { target, targetIdentity, captured, _ ->
            assertEquals(id, target)
            assertEquals(identity, targetIdentity)
            assertSame(session, captured)
            assertRecoveryIntent(journal)
            assertEquals(setOf(id), installer.failedInstances.value)
            recoveryStarts.incrementAndGet()
            receipt
        }).use { f ->
            installer = f.installer
            session = ChrootRuntimeSession("receipt-session", f.root)
            val home = installer.prepareHome(instance)
            File(home, "user-data").writeText("keep")
            assertEquals(LocalRuntimeRemoval.RETRY_REQUIRED,
                installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() })
            assertRecoveryIntent(journal)
            assertEquals(setOf(id), installer.removalPending.value)
            assertEquals(setOf(id), installer.failedInstances.value)
            assertTrue(installer.unpublishedInstalls.value.isEmpty())
            assertTrue(installer.isHealthy.value)
            assertEquals("keep", File(home, "user-data").readText())
            assertEquals(0, publications.get())
            assertEquals(0, f.starts.get())
            assertEquals(0, f.inputs.get())
            assertEquals(0, receipt.writes.size())
            assertEquals(0, receipt.destroyed.get())
            fails { installer.removeFromUser(instance, f.root, session) }
            fails { installer.prepareHome(instance) }
            assertEquals(1, recoveryStarts.get())
        }
        val retained = journal.readBytes()
        val removed = FakeProcess({ native(cleanup = "removed") })
        fixture(removed, files = files, journal = journal, startRecoveryBound = { target, targetIdentity, captured, _ ->
            assertEquals(id, target)
            assertEquals(identity, targetIdentity)
            assertSame(session, captured)
            assertRecoveryIntent(journal)
            assertEquals(setOf(id), installer.failedInstances.value)
            recoveryStarts.incrementAndGet()
            removed
        }).use { f ->
            installer = f.installer
            session = ChrootRuntimeSession("after-restart-session", f.root)
            assertEquals(setOf(id), installer.removalPending.value)
            assertEquals(setOf(id), installer.failedInstances.value)
            assertArrayEquals(retained, journal.readBytes())
            for (changed in listOf(instance.copy(device = identity.device + 1), instance.copy(inode = identity.inode + 1),
                instance.copy(installGeneration = instance.installGeneration + 1))) {
                fails { installer.recoverAndRemoveFromUser(changed, f.root, session) { publications.incrementAndGet() } }
                assertArrayEquals(retained, journal.readBytes())
            }
            fails { installer.removeFromUser(instance, f.root, session) }
            assertEquals(1, recoveryStarts.get())
            assertEquals(LocalRuntimeRemoval.REMOVED,
                installer.recoverAndRemoveFromUser(instance, f.root, session) {
                    assertRecoveryIntent(journal)
                    assertEquals(setOf(id), installer.failedInstances.value)
                    publications.incrementAndGet()
                })
            assertEquals(2, recoveryStarts.get())
            assertEquals(1, publications.get())
            assertEquals(12L, journal.length())
            assertTrue(installer.failedInstances.value.isEmpty())
            assertTrue(installer.removalPending.value.isEmpty())
            assertEquals("keep", File(files, "chroot-home/$id/user-data").readText())
            assertEquals(0, f.starts.get())
            assertEquals(0, f.inputs.get())
        }
    }

    @Test
    fun recoveryNeverAdoptsPendingInstallAndJournalV1CannotSmuggleRecoveryOperations() = runTest {
        val recoveryStarts = AtomicInteger()
        for (version in listOf(1, 2)) for (operation in listOf(0)) {
            val journal = File(temporary.newFolder(), "ordinary-pending.bin")
            writeInstallerJournal(journal, version, operation)
            val retained = journal.readBytes()
            fixture(journal = journal, startRecoveryBound = { _, _, _, _ ->
                recoveryStarts.incrementAndGet()
                FakeProcess({ native(cleanup = "removed") })
            }).use { f ->
                assertTrue(f.installer.isHealthy.value)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(f.installer.removalPending.value.isEmpty())
                val session = ChrootRuntimeSession("recovery-session", f.root)
                fails { f.installer.recoverAndRemoveFromUser(instance, f.root, session) }
                fails { f.installer.removeFromUser(instance, f.root, session) }
                assertArrayEquals(retained, journal.readBytes())
                assertEquals(0, f.starts.get())
            }
        }
        for ((version, operation) in listOf(1 to 2, 2 to 3, 3 to 2)) {
            val journal = File(temporary.newFolder(), "invalid-version-operation.bin")
            writeInstallerJournal(journal, version, operation)
            val retained = journal.readBytes()
            fixture(journal = journal, startRecoveryBound = { _, _, _, _ ->
                recoveryStarts.incrementAndGet()
                FakeProcess({ native(cleanup = "removed") })
            }).use { f ->
                assertFalse(f.installer.isHealthy.value)
                fails { f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("invalid", f.root)) }
                assertArrayEquals(retained, journal.readBytes())
                assertEquals(0, f.starts.get())
            }
        }
        assertEquals(0, recoveryStarts.get())
    }

    @Test
    fun sameIdentityRecoveryUpgradesLegacyOrdinaryRemovalAndPublishesNativeRemovedInOneRound() = runTest {
        for (version in listOf(1, 2)) {
            val journal = File(temporary.newFolder(), "ordinary-remove-v$version.bin")
            writeInstallerJournal(journal, version, operation = 1)
            val starts = AtomicInteger()
            val publications = AtomicInteger()
            val process = FakeProcess({ native(cleanup = "removed") })
            fixture(process, journal = journal, startRecoveryBound = { target, targetIdentity, _, _ ->
                assertEquals(id, target)
                assertEquals(identity, targetIdentity)
                assertRecoveryIntent(journal)
                starts.incrementAndGet()
                process
            }).use { f ->
                val session = ChrootRuntimeSession("same-identity-retry", f.root)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(f.installer.removalPending.value.isEmpty())
                assertEquals(LocalRuntimeRemoval.REMOVED,
                    f.installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() })
                assertEquals(1, starts.get())
                assertEquals(1, publications.get())
                assertEquals(12L, journal.length())
                assertTrue(f.installer.failedInstances.value.isEmpty())
                assertTrue(f.installer.removalPending.value.isEmpty())
                assertEquals(0, f.starts.get())
            }
        }
    }

    @Test
    fun recoveryRejectsOrdinaryRemovalWithDifferentIdentityWithoutChangingItsJournal() = runTest {
        val stored = instance.copy(device = identity.device + 1)
        for (version in listOf(1, 2)) {
            val journal = File(temporary.newFolder(), "mismatched-remove-v$version.bin")
            writeInstallerJournal(journal, version, operation = 1, target = stored)
            val retained = journal.readBytes()
            val recoveryStarts = AtomicInteger()
            fixture(journal = journal, startRecoveryBound = { _, _, _, _ ->
                recoveryStarts.incrementAndGet()
                FakeProcess({ native(cleanup = "removed") })
            }).use { f ->
                val failure = fails {
                    f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("mismatch", f.root))
                }
                assertTrue(failure is IOException)
                assertArrayEquals(retained, journal.readBytes())
                assertEquals(0, recoveryStarts.get())
                assertEquals(0, f.starts.get())
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertTrue(f.installer.removalPending.value.isEmpty())
            }
        }
    }

    @Test
    fun recoveryBusyHintRequiresExactIdentityAndNeverMasksIdentityMismatch() = runTest {
        val exact = native(passed = false, cleanup = "cleanup_unknown", stage = "recovery_busy", errno = 16)
        val mismatches = listOf(
            native(passed = false, cleanup = "cleanup_unknown", path = "/data/local/other", stage = "recovery_busy", errno = 16),
            native(passed = false, cleanup = "cleanup_unknown", device = identity.device + 1, stage = "recovery_busy", errno = 16),
            native(passed = false, cleanup = "cleanup_unknown", inode = identity.inode + 1, stage = "recovery_busy", errno = 16),
            native(passed = false, cleanup = "cleanup_unknown", stage = "root_mode", errno = 16),
            native(passed = false, cleanup = "cleanup_unknown", stage = "recovery_busy", errno = 22)
        )
        val results = listOf(exact to true) + mismatches.map { it to false }
        for ((result, isExactBusy) in results) {
            val process = FakeProcess({ result }, exit = 16)
            fixture(process, startRecoveryBound = { _, _, _, _ -> process }).use { f ->
                val publications = AtomicInteger()
                val failure = fails {
                    f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("busy", f.root)) {
                        publications.incrementAndGet()
                    }
                }
                assertTrue(failure is IOException)
                assertEquals(isExactBusy, failure is ChrootRemovalBusyException)
                if (isExactBusy) assertTrue(failure.message.orEmpty().contains("still in use"))
                else assertFalse(failure.message.orEmpty().contains("still in use"))
                assertEquals(0, publications.get())
                assertRecoveryIntent(f.journal)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertEquals(setOf(id), f.installer.removalPending.value)
                assertEquals(0, f.starts.get())
            }
        }
    }

    @Test
    fun recoveryRejectsUnverifiedResultsTransportFailureTimeoutAndFailedEof() = runTest {
        val processes = mutableListOf(
            FakeProcess({ native(cleanup = "cleanup_unknown", passed = false) }, 5),
            FakeProcess({ native(cleanup = "installed") }),
            FakeProcess({ "{}" }),
            FakeProcess({ throw IOException("broken stdout") }),
            FakeProcess({ native(cleanup = "reboot_required") }, error = ByteArray(16 * 1024 + 1)),
            FakeProcess({ native(cleanup = "reboot_required") }, finishOnClose = false),
            FakeProcess({ native(cleanup = "removed") }, closeFailure = IOException("EOF failed")),
            FakeProcess({ native(cleanup = "reboot_required") }, closeFailure = IOException("EOF failed"))
        )
        for (cleanup in listOf("reboot_required", "removed")) {
            processes += FakeProcess({ native(cleanup = cleanup, passed = false) })
            processes += FakeProcess({ native(cleanup = cleanup) }, 5)
            processes += FakeProcess({ native(cleanup = cleanup, path = "/data/local/other") })
            processes += FakeProcess({ native(cleanup = cleanup, device = identity.device + 1) })
            processes += FakeProcess({ native(cleanup = cleanup, inode = identity.inode + 1) })
        }
        for (process in processes) {
            fixture(process, timeout = 100, rollback = 50, startRecoveryBound = { _, _, _, _ -> process }).use { f ->
                val publications = AtomicInteger()
                fails {
                    f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("recovery", f.root)) {
                        publications.incrementAndGet()
                    }
                }
                assertEquals(0, publications.get())
                assertRecoveryIntent(f.journal)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertEquals(setOf(id), f.installer.removalPending.value)
                assertEquals(0, f.starts.get())
                assertEquals(0, f.inputs.get())
                assertEquals(0, process.destroyed.get())
            }
        }
    }

    @Test
    fun partialRecoveryFailureRetainsOnlyItsIdentityAndCanRetryWithoutOrdinaryRemoval() = runTest {
        val failed = FakeProcess({ native(passed = false, cleanup = "cleanup_unknown") }, 5)
        val removed = FakeProcess({ native(cleanup = "removed") })
        val recoveryStarts = AtomicInteger()
        fixture(failed, startRecoveryBound = { _, _, _, _ ->
            if (recoveryStarts.getAndIncrement() == 0) failed else removed
        }).use { f ->
            val session = ChrootRuntimeSession("recovery", f.root)
            val publications = AtomicInteger()
            fails { f.installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() } }
            assertRecoveryIntent(f.journal)
            fails { f.installer.removeFromUser(instance, f.root, session) }
            assertEquals(1, recoveryStarts.get())
            assertEquals(0, f.starts.get())
            assertEquals(LocalRuntimeRemoval.REMOVED,
                f.installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() })
            assertEquals(1, publications.get())
            assertEquals(2, recoveryStarts.get())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertTrue(f.installer.removalPending.value.isEmpty())
        }
    }

    @Test
    fun legacyRecoveryReceiptCanBeRetriedInTheSameBootUntilNativeRemovalCompletes() = runTest {
        val receipt = FakeProcess({ native(cleanup = "reboot_required") })
        val removed = FakeProcess({ native(cleanup = "removed") })
        val starts = AtomicInteger()
        val publications = AtomicInteger()
        fixture(receipt, startRecoveryBound = { _, _, _, _ ->
            if (starts.getAndIncrement() == 0) receipt else removed
        }).use { f ->
            val session = ChrootRuntimeSession("same-boot-retry", f.root)
            assertEquals(LocalRuntimeRemoval.RETRY_REQUIRED,
                f.installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() })
            assertEquals(setOf(id), f.installer.removalPending.value)
            assertEquals(0, publications.get())
            assertRecoveryIntent(f.journal)

            assertEquals(LocalRuntimeRemoval.REMOVED,
                f.installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() })
            assertEquals(2, starts.get())
            assertEquals(1, publications.get())
            assertEquals(12L, f.journal.length())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertTrue(f.installer.removalPending.value.isEmpty())
        }
    }

    @Test
    fun failedRecoveryRetryKeepsTheDurableRequestVisibleAsPending() = runTest {
        val receipt = FakeProcess({ native(cleanup = "reboot_required") })
        val failed = FakeProcess({ native(cleanup = "cleanup_unknown", passed = false) }, 5)
        val starts = AtomicInteger()
        fixture(receipt, startRecoveryBound = { _, _, _, _ ->
            if (starts.getAndIncrement() == 0) receipt else failed
        }).use { f ->
            val session = ChrootRuntimeSession("reviewed", f.root)
            assertEquals(LocalRuntimeRemoval.RETRY_REQUIRED, f.installer.recoverAndRemoveFromUser(instance, f.root, session))
            assertEquals(setOf(id), f.installer.removalPending.value)
            fails { f.installer.recoverAndRemoveFromUser(instance, f.root, session) }
            assertEquals(setOf(id), f.installer.removalPending.value)
            assertEquals(setOf(id), f.installer.failedInstances.value)
            assertRecoveryIntent(f.journal)
            assertEquals(2, starts.get())
        }
    }

    @Test
    fun recoverySessionReplacementAfterNativeExitNeverPublishesOrClearsTheIntent() = runTest {
        for (cleanup in listOf("reboot_required", "removed")) {
            lateinit var session: ChrootRuntimeSession
            var currentSession: ChrootRuntimeSession? = null
            val process = FakeProcess({ native(cleanup = cleanup) })
            process.onClose = { currentSession = ChrootRuntimeSession("replacement", session.rootState.copy()) }
            fixture(process, checkRecoverySession = { captured ->
                if (captured !== currentSession) throw ChrootRuntimeUnavailable("Recovery session replaced")
            }, startRecoveryBound = { _, _, captured, _ ->
                assertSame(session, captured)
                process
            }).use { f ->
                session = ChrootRuntimeSession("reviewed", f.root)
                currentSession = session
                val publications = AtomicInteger()
                val failure = fails {
                    f.installer.recoverAndRemoveFromUser(instance, f.root, session) { publications.incrementAndGet() }
                }
                assertTrue(failure is ChrootRuntimeUnavailable)
                assertEquals(0, publications.get())
                assertRecoveryIntent(f.journal)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertEquals(setOf(id), f.installer.removalPending.value)
                assertEquals(f.root, f.authority.accessSnapshot())
            }
        }
    }

    @Test
    fun recoveryRejectsChangedAuthorityAndSessionRootBeforeRecordingIntentOrStarting() = runTest {
        val recoveryStarts = AtomicInteger()
        fixture(startRecoveryBound = { _, _, _, _ ->
            recoveryStarts.incrementAndGet()
            FakeProcess({ native(cleanup = "reboot_required") })
        }).use { f ->
            val session = ChrootRuntimeSession("reviewed", f.root)
            for (changed in listOf(f.root.copy(authorityId = "other"), f.root.copy(generation = f.root.generation + 1),
                f.root.copy(enabled = false))) {
                fails { f.installer.recoverAndRemoveFromUser(instance, changed, session) }
                fails { f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("stale", changed)) }
            }
            f.authority.setEnabled(false)
            fails { f.installer.recoverAndRemoveFromUser(instance, f.root, session) }
            assertEquals(0, recoveryStarts.get())
            assertEquals(0, f.starts.get())
            assertFalse(f.journal.exists())
            assertTrue(f.installer.failedInstances.value.isEmpty())
            assertTrue(f.installer.removalPending.value.isEmpty())
        }
    }

    @Test
    fun verifiedRecoveryReceiptSettlesRevocationWithoutClaimingInstanceCleanupOrPublishingRemoval() = runTest {
        val process = FakeProcess({ native(cleanup = "reboot_required") })
        val proofChecked = CountDownLatch(1)
        val releaseCheck = CountDownLatch(1)
        fixture(process, checkRecoverySession = {
            if (process.waited.get() && proofChecked.count != 0L) {
                proofChecked.countDown()
                releaseCheck.await()
            }
        }, startRecoveryBound = { _, _, _, _ -> process }).use { f ->
            try {
                val publications = AtomicInteger()
                val operation = async {
                    fails {
                        f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("reviewed", f.root)) {
                            publications.incrementAndGet()
                        }
                    }
                }
                eventually { proofChecked.count == 0L }
                // The installer and latch run on IO. Keep revocation's timeout on the same
                // real clock so runTest cannot skip it while the proof check is still held.
                val revocation = async(Dispatchers.IO) { f.authority.setEnabled(true) }
                eventually { !f.authority.accessState.value.enabled }
                releaseCheck.countDown()
                operation.await()
                revocation.await()
                assertEquals(0, publications.get())
                assertRecoveryIntent(f.journal)
                assertEquals(setOf(id), f.installer.failedInstances.value)
                assertEquals(setOf(id), f.installer.removalPending.value)
                // The receipt only proves this installer helper settled. The instance stays blocked,
                // while revocation does not incorrectly leave the entire Root authority unusable.
                assertTrue(f.authority.accessSnapshot().enabled)
                assertEquals(0, f.starts.get())
                assertEquals(0, process.destroyed.get())
            } finally {
                releaseCheck.countDown()
            }
        }
    }

    @Test
    fun recoveryRemovedButFailedProfilePublicationRetainsItsRecoverableIntent() = runTest {
        val process = FakeProcess({ native(cleanup = "removed") })
        fixture(process, startRecoveryBound = { _, _, _, _ -> process }).use { f ->
            val publications = AtomicInteger()
            fails {
                f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("reviewed", f.root)) {
                    publications.incrementAndGet()
                    throw IOException("Profile removal was not persisted")
                }
            }
            assertEquals(1, publications.get())
            assertRecoveryIntent(f.journal)
            assertEquals(setOf(id), f.installer.failedInstances.value)
            assertEquals(setOf(id), f.installer.removalPending.value)
            assertTrue(f.installer.isHealthy.value)
        }
    }

    @Test
    fun recoveryRequiresItsIndependentStarterAndNeverFallsBackToOrdinaryInstallOrRemove() = runTest {
        val ordinaryBoundStarts = AtomicInteger()
        fixture(startBound = { _, _, _ ->
            ordinaryBoundStarts.incrementAndGet()
            FakeProcess({ native(cleanup = "removed") })
        }).use { f ->
            val publications = AtomicInteger()
            val failure = fails {
                f.installer.recoverAndRemoveFromUser(instance, f.root, ChrootRuntimeSession("recovery-session", f.root)) {
                    publications.incrementAndGet()
                }
            }
            assertTrue(failure is ChrootRuntimeUnavailable)
            assertEquals(0, ordinaryBoundStarts.get())
            assertEquals(0, f.starts.get())
            assertEquals(0, f.inputs.get())
            assertEquals(0, publications.get())
            assertRecoveryIntent(f.journal)
            assertEquals(setOf(id), f.installer.failedInstances.value)
            assertEquals(setOf(id), f.installer.removalPending.value)
        }
    }

    @Test
    fun appHomeNeverAdoptsUnmarkedOrMismatchedDirectoriesOrSymlinks() = runTest {
        fixture().use { f ->
            File(f.files, "chroot-home/$id").mkdirs()
            fails { f.installer.prepareHome(instance) }
        }
        fixture().use { f ->
            f.installer.prepareHome(instance)
            val conf = File(f.files, "chroot-dns/$id/resolv.conf")
            conf.writeText("different")
            fails { f.installer.prepareHome(instance) }
            assertEquals("different", conf.readText())
        }
        fixture().use { f ->
            val outside = temporary.newFolder()
            Files.createSymbolicLink(File(f.files, "chroot-home").toPath(), outside.toPath())
            fails { f.installer.prepareHome(instance) }
            assertTrue(outside.listFiles()!!.isEmpty())
        }
        fixture().use { f ->
            f.installer.prepareHome(instance)
            fails { f.installer.prepareHome(instance.copy(inode = 20)) }
        }
    }
}
