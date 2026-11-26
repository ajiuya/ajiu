package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChrootPublicProcessTest {
    private fun handle(id: String, stop: () -> Unit = {}) = ChrootLaunchHandle(
        "0123456789abcdef01234567", id, {}, {}, { _, _ -> true }, { true }, stop
    )

    @Test fun processWaitUnknownNeverInventsSignalAndCleanupRemainsIndependent() = runTest {
        val handle = handle("89abcdef0123456789abcdef")
        val process = ChrootPublicProcess(handle, this)
        handle.completeTask(ChrootTaskResult.Unknown)
        assertEquals(-1, process.waitFor())
        assertEquals(LocalRuntimeTaskResult.Unknown, process.taskState.value)
        assertNull(process.cleanupState.value)
        assertFalse(process.isAlive)
        handle.completeCleanup(ChrootCleanupResult.UNKNOWN)
        assertEquals(LocalRuntimeCleanup.UNKNOWN, process.awaitCleanup())
        runCurrent()
        assertEquals(LocalRuntimeTaskResult.Unknown, process.taskState.value)
    }

    @Test fun knownExitAndSignalHaveDistinctTypedMetadata() = runTest {
        val signaled = handle("89abcdef0123456789abcdef")
        val exited = handle("abcdef0123456789abcdef01")
        val signalProcess = ChrootPublicProcess(signaled, this)
        val exitProcess = ChrootPublicProcess(exited, this)
        signaled.completeTask(ChrootTaskResult.Exited(ChrootProcessExit(null, 1)))
        exited.completeTask(ChrootTaskResult.Exited(ChrootProcessExit(1, null)))
        assertEquals(-1, signalProcess.waitFor())
        assertEquals(1, exitProcess.waitFor())
        assertEquals(LocalRuntimeTaskResult.Exited(null, 1), signalProcess.taskState.value)
        assertEquals(LocalRuntimeTaskResult.Exited(1, null), exitProcess.taskState.value)
        signaled.completeCleanup(ChrootCleanupResult.VERIFIED)
        exited.completeCleanup(ChrootCleanupResult.VERIFIED)
        assertEquals(LocalRuntimeCleanup.VERIFIED, signalProcess.awaitCleanup())
        assertEquals(LocalRuntimeCleanup.VERIFIED, exitProcess.awaitCleanup())
    }

    @Test fun closeAndDestroyOnlyStopTheirOwnLaunchOnce() = runTest {
        val firstStops = AtomicInteger()
        val secondStops = AtomicInteger()
        val first = handle("89abcdef0123456789abcdef") { firstStops.incrementAndGet() }
        val second = handle("abcdef0123456789abcdef01") { secondStops.incrementAndGet() }
        val firstProcess = ChrootPublicProcess(first, this)
        val secondProcess = ChrootPublicProcess(second, this)
        firstProcess.close()
        firstProcess.destroy()
        firstProcess.destroyForcibly()
        assertEquals(1, firstStops.get())
        assertEquals(0, secondStops.get())
        assertTrue(secondProcess.isAlive)
        first.completeTask(ChrootTaskResult.Unknown)
        first.completeCleanup(ChrootCleanupResult.VERIFIED)
        second.completeTask(ChrootTaskResult.Exited(ChrootProcessExit(0, null)))
        second.completeCleanup(ChrootCleanupResult.VERIFIED)
        runCurrent()
    }

    @Test fun ownerIdentityIsTypedBoundedAndNotPrintedInDiagnostics() {
        val session = RuntimeOwner(RuntimeOwnerKind.AGENT, "session-1")
        assertEquals("chat:session-1", session.identity)
        assertNotEquals(session, RuntimeOwner(RuntimeOwnerKind.PLUGIN, "session-1"))
        assertFalse(session.toString().contains("session-1"))
        assertTrue(runCatching { RuntimeOwner(RuntimeOwnerKind.AGENT, " ") }.isFailure)
        assertTrue(runCatching { RuntimeOwner(RuntimeOwnerKind.AGENT, "a\nb") }.isFailure)
        assertTrue(runCatching { RuntimeOwner(RuntimeOwnerKind.AGENT, "中".repeat(129)) }.isFailure)
        assertTrue(runCatching { LocalRuntimeTaskResult.Exited(null, null) }.isFailure)
        assertTrue(runCatching { LocalRuntimeTaskResult.Exited(0, 1) }.isFailure)
    }
}
