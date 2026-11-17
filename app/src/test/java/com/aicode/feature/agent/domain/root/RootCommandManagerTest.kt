package com.aicode.feature.agent.domain.root

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.aicode.feature.settings.data.repository.RootAccessSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class RootCommandManagerTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }
        fun runPending() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private data class Fixture(
        val repository: RootAccessSettingsRepository,
        val factory: FakeRootProcessFactory,
        val manager: RootCommandManager
    )

    private fun TestScope.fixture(
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    ): Fixture {
        val repository = RootAccessSettingsRepository(RootMemoryDataStore(), backgroundScope)
        val factory = FakeRootProcessFactory()
        return Fixture(repository, factory, RootCommandManager(repository, factory, backgroundScope, dispatcher))
    }

    private suspend fun Fixture.permit(command: String = "printf done", session: String = "s1"): String =
        manager.authorize(session, command, manager.accessSnapshot().generation)!!

    @Test
    fun defaultsOffAndCannotBeBypassedWithAnInventedPermit() = runTest {
        val fixture = fixture()
        assertEquals(RootAccessState(), fixture.manager.accessSnapshot())
        assertNull(fixture.manager.authorize("s1", "printf done", 0))
        assertEquals(
            RootCommandManager.EXIT_DISABLED,
            fixture.manager.execute("printf done", 1_000, "s1", UUID.randomUUID().toString()).exitCode
        )
        assertTrue(fixture.factory.commands.isEmpty())
    }

    @Test
    fun authorizationWaitsForPersistedStateAndRejectsWrongGeneration() = runTest {
        val loaded = CompletableDeferred<Preferences>()
        val dataStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { emit(loaded.await()) }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(loaded.await())
        }
        val repository = RootAccessSettingsRepository(dataStore, backgroundScope)
        val factory = FakeRootProcessFactory()
        val manager = RootCommandManager(repository, factory, backgroundScope, StandardTestDispatcher(testScheduler))
        val wrongGeneration = async { manager.authorize("s1", "printf done", 0) }
        val correctGeneration = async { manager.authorize("s1", "printf done", 19) }
        runCurrent()
        assertFalse(wrongGeneration.isCompleted)
        assertFalse(correctGeneration.isCompleted)
        loaded.complete(mutablePreferencesOf(
            booleanPreferencesKey("enabled") to true,
            longPreferencesKey("generation") to 19L
        ))
        assertNull(wrongGeneration.await())
        assertNotNull(correctGeneration.await())
        assertTrue(factory.commands.isEmpty())
    }

    @Test
    fun permitsAreIndependentUuidsAndConsumedExactlyOnce() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        val first = fixture.permit()
        val second = fixture.permit()
        assertEquals(first, UUID.fromString(first).toString())
        assertNotEquals(first, second)
        assertEquals(0, fixture.manager.execute("printf done", 1_000, "s1", first).exitCode)
        assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
            fixture.manager.execute("printf done", 1_000, "s1", first).exitCode)
        fixture.factory.next = FakeRootProcess()
        assertEquals(0, fixture.manager.execute("printf done", 1_000, "s1", second).exitCode)
        assertEquals(2, fixture.factory.commands.size)
        assertEquals(2, fixture.factory.destroyed.size)
    }

    @Test
    fun concurrentExecutionsCannotBothConsumeTheSamePermit() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        fixture.factory.next = FakeRootProcess(output = "", alive = true)
        val permit = fixture.permit()
        val first = async { fixture.manager.execute("printf done", 1_000, "s1", permit) }
        val second = async { fixture.manager.execute("printf done", 1_000, "s1", permit) }
        runCurrent()
        assertFalse(first.isCompleted)
        assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED, second.await().exitCode)
        fixture.manager.setEnabled(false)
        assertEquals(RootCommandManager.EXIT_INTERRUPTED, first.await().exitCode)
        assertEquals(1, fixture.factory.commands.size)
        assertEquals(1, fixture.factory.destroyed.size)
    }

    @Test
    fun permitIsBoundToExactSessionAndUnmodifiedCommandAndMismatchesConsumeIt() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        val command = "printf 'a';\nprintf b"
        for ((session, attemptedCommand) in listOf(
            "s2" to command,
            "s1" to "$command ",
            "s1" to "printf b;\nprintf 'a'"
        )) {
            val permit = fixture.permit(command)
            assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
                fixture.manager.execute(attemptedCommand, 1_000, session, permit).exitCode)
            assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
                fixture.manager.execute(command, 1_000, "s1", permit).exitCode)
        }
        assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
            fixture.manager.execute(command, 1_000, "s1", null).exitCode)
        assertTrue(fixture.factory.commands.isEmpty())
    }

    @Test
    fun offThenOnAndRepeatedOffInvalidateOldGenerations() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        val before = fixture.manager.accessSnapshot()
        val permit = fixture.permit()
        fixture.manager.setEnabled(false)
        fixture.manager.setEnabled(false)
        fixture.manager.setEnabled(true)
        assertEquals(before.generation + 3, fixture.manager.accessSnapshot().generation)
        assertNull(fixture.manager.authorize("s1", "printf done", before.generation))
        assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
            fixture.manager.execute("printf done", 1_000, "s1", permit).exitCode)
        assertTrue(fixture.factory.commands.isEmpty())
    }

    @Test
    fun explicitRevocationAndExternalStateChangesRevokePermits() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        val revoked = fixture.permit()
        fixture.manager.revokePermit(revoked)
        assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
            fixture.manager.execute("printf done", 1_000, "s1", revoked).exitCode)
        val stale = fixture.permit()
        fixture.repository.setEnabled(false)
        fixture.repository.setEnabled(true)
        assertEquals(RootCommandManager.EXIT_APPROVAL_REQUIRED,
            fixture.manager.execute("printf done", 1_000, "s1", stale).exitCode)
        assertTrue(fixture.factory.commands.isEmpty())
    }

    @Test
    fun closingBeforeScheduledStartPreventsCreationEvenAfterReopening() = runTest {
        val processDispatcher = QueuedDispatcher()
        val fixture = fixture(processDispatcher)
        fixture.manager.setEnabled(true)
        val permit = fixture.permit()
        val execution = async { fixture.manager.execute("printf done", 1_000, "s1", permit) }
        runCurrent()
        assertTrue(fixture.factory.commands.isEmpty())
        assertFalse(execution.isCompleted)

        fixture.manager.setEnabled(false)
        fixture.manager.setEnabled(true)
        processDispatcher.runPending()
        assertEquals(RootCommandManager.EXIT_INTERRUPTED, execution.await().exitCode)
        assertTrue(fixture.factory.commands.isEmpty())
    }

    @Test
    fun cancellingBeforeScheduledStartPreventsCreation() = runTest {
        val processDispatcher = QueuedDispatcher()
        val fixture = fixture(processDispatcher)
        fixture.manager.setEnabled(true)
        val permit = fixture.permit()
        val execution = async { fixture.manager.execute("printf done", 1_000, "s1", permit) }
        runCurrent()
        execution.cancelAndJoin()
        processDispatcher.runPending()
        assertTrue(fixture.factory.commands.isEmpty())
    }

    @Test
    fun timeoutDestroysProcessAndAllStreamsExactlyOnceWithoutBlockingRead() = runTest {
        val fixture = fixture()
        val process = FakeRootProcess(output = "", alive = true)
        fixture.factory.next = process
        fixture.manager.setEnabled(true)
        val permit = fixture.permit()
        val execution = async { fixture.manager.execute("printf done", 50, "s1", permit) }
        runCurrent()
        assertFalse(execution.isCompleted)
        advanceTimeBy(50)
        runCurrent()
        assertEquals(RootCommandManager.EXIT_TIMEOUT, execution.await().exitCode)
        fixture.manager.setEnabled(false)
        assertReclaimed(fixture, process)
    }

    @Test
    fun coroutineCancellationImmediatelyDestroysProcessAndStreams() = runTest {
        val fixture = fixture()
        val process = FakeRootProcess(output = "", alive = true)
        fixture.factory.next = process
        fixture.manager.setEnabled(true)
        val permit = fixture.permit()
        val execution = async { fixture.manager.execute("printf done", 60_000, "s1", permit) }
        runCurrent()
        execution.cancelAndJoin()
        fixture.manager.setEnabled(false)
        assertTrue(execution.isCancelled)
        assertReclaimed(fixture, process)
    }

    @Test
    fun switchOffImmediatelyReclaimsRunningProcessBeforeReturning() = runTest {
        val fixture = fixture()
        val process = FakeRootProcess(output = "", alive = true)
        fixture.factory.next = process
        fixture.manager.setEnabled(true)
        val permit = fixture.permit()
        val execution = async { fixture.manager.execute("printf done", 60_000, "s1", permit) }
        runCurrent()
        fixture.manager.setEnabled(false)
        assertReclaimed(fixture, process)
        assertEquals(RootCommandManager.EXIT_INTERRUPTED, execution.await().exitCode)
        assertFalse(fixture.manager.accessSnapshot().enabled)
    }

    @Test
    fun repositoryStateFlowChangeAlsoReclaimsRunningProcess() = runTest {
        val fixture = fixture()
        val process = FakeRootProcess(output = "", alive = true)
        fixture.factory.next = process
        fixture.manager.setEnabled(true)
        val permit = fixture.permit()
        val execution = async { fixture.manager.execute("printf done", 60_000, "s1", permit) }
        runCurrent()
        fixture.repository.setEnabled(false)
        runCurrent()
        assertEquals(RootCommandManager.EXIT_INTERRUPTED, execution.await().exitCode)
        assertReclaimed(fixture, process)
    }

    @Test
    fun outputIsHardCappedWithoutLineBufferingAndUtf8BoundariesArePreserved() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        fixture.factory.next = FakeRootProcess("x".repeat(150_000))
        val capped = fixture.manager.execute("printf done", 1_000, "s1", fixture.permit())
        assertEquals("x".repeat(RootCommandManager.MAX_OUTPUT_CHARS), capped.output)
        assertEquals(0, capped.exitCode)
        val utf8 = "a".repeat(4095) + "汉字🙂" + "b".repeat(100)
        fixture.factory.next = FakeRootProcess(utf8)
        assertEquals(utf8, fixture.manager.execute("printf done", 1_000, "s1", fixture.permit()).output)
    }

    @Test
    fun failedPersistenceKeepsTheManagerBlockedAndRevokesExistingPermits() = runTest {
        val store = RootMemoryDataStore()
        val repository = RootAccessSettingsRepository(store, backgroundScope)
        val factory = FakeRootProcessFactory()
        val manager = RootCommandManager(repository, factory, backgroundScope, StandardTestDispatcher(testScheduler))
        manager.setEnabled(true)
        val before = manager.accessSnapshot()
        val permit = manager.authorize("s1", "printf done", before.generation)!!
        store.failWrites = true
        try {
            manager.setEnabled(false)
            throw AssertionError("Persistence failure must be reported")
        } catch (_: IOException) {
            assertFalse(manager.accessSnapshot().enabled)
        }
        assertNull(manager.authorize("s1", "printf done", before.generation))
        assertEquals(RootCommandManager.EXIT_DISABLED,
            manager.execute("printf done", 1_000, "s1", permit).exitCode)
        assertTrue(factory.commands.isEmpty())
    }

    @Test
    fun startupAndReadFailuresAreBoundedAndDoNotExposeExceptionMessages() = runTest {
        val fixture = fixture()
        fixture.manager.setEnabled(true)
        fixture.factory.failure = IOException("secret command output")
        val startup = fixture.manager.execute("printf done", 1_000, "s1", fixture.permit())
        assertEquals(RootCommandResult("", RootCommandManager.EXIT_FAILURE), startup)
        fixture.factory.failure = RootPermissionUnavailableException()
        assertEquals(RootCommandManager.EXIT_PERMISSION_UNAVAILABLE,
            fixture.manager.execute("printf done", 1_000, "s1", fixture.permit()).exitCode)
        fixture.factory.failure = null
        val process = FakeRootProcess().also { it.failAvailable = true }
        fixture.factory.next = process
        assertEquals(RootCommandManager.EXIT_FAILURE,
            fixture.manager.execute("printf done", 1_000, "s1", fixture.permit()).exitCode)
        assertReclaimed(fixture, process)
    }

    private fun assertReclaimed(fixture: Fixture, process: FakeRootProcess) {
        assertTrue(process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.errorClosed)
        assertTrue(process.outputClosed)
        assertFalse(process.blockingReadAttempted)
        assertEquals(1, fixture.factory.destroyed.count { it === process })
    }
}
