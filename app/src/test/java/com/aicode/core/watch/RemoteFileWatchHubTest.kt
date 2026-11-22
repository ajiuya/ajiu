package com.aicode.core.watch

import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.RemoteAgentConfigProfile
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.TransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferStat
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RemoteFileWatchHubTest {
    @Test(timeout = 15_000) fun lateProfileOnSameSshRootDoesNotJoinAnUnreleasedStaleLease() = runBlocking<Unit> {
        val access = mockk<RemoteSftpFileAccess>()
        val backend = mockk<TransferBackend>()
        val firstScan = CountDownLatch(1)
        val freshScan = CountDownLatch(1)
        val stamp = AtomicReference("before")
        val stale = java.util.concurrent.atomic.AtomicBoolean()
        every { access.resolvePath("/workspace", any()) } returns "/workspace"
        every { access.transferBackend("/workspace", any()) } returns backend
        every { backend.stat("") } returns TransferStat(TransferKind.DIRECTORY, null, "dir")
        every { backend.list("") } returns listOf("file")
        every { backend.stat("file") } answers {
            val value = stamp.get()
            if (WorkspaceExecutionContext.current()?.remoteSource?.agentConfigProfile?.epoch == 1L) firstScan.countDown() else freshScan.countDown()
            TransferStat(TransferKind.FILE, 3, value)
        }
        val identity = RemoteConnectionIdentity(mockk(), "/home/fixture")
        fun execution(epoch: Long) = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "same-cache", access, null,
            RemoteSftpFileAccess.ExecutionSource(identity, "/workspace", RemoteAgentConfigProfile("same-profile", epoch))) {
                if (epoch == 1L && stale.get()) throw kotlinx.coroutines.CancellationException("Old profile capture was revoked")
            }
        val hub = RemoteFileWatchHub()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            hub.watch(execution(1), "/workspace", false, WatchFilter(followGitignore = false)).collect { error("Old source cannot receive new generation events") }
        }
        assertTrue(firstScan.await(5, TimeUnit.SECONDS))
        stale.set(true)
        val received = Channel<FileChangeBatch>(Channel.UNLIMITED)
        val fresh = launch(start = CoroutineStart.UNDISPATCHED) {
            hub.watch(execution(2), "/workspace", false, WatchFilter(followGitignore = false)).collect { received.send(it) }
        }
        try {
            assertTrue("New profile must start scanning before old lease is released", freshScan.await(5, TimeUnit.SECONDS))
            assertTrue(old.isActive)
            stamp.set("after")
            val batch = withTimeout(5000) { received.receive() }
            assertEquals("/workspace/file", batch.changes.single().containerPath)
            assertEquals(ChangeKind.MODIFIED, batch.changes.single().kind)
        } finally { fresh.cancelAndJoin(); old.cancelAndJoin() }
    }

    @Test(timeout = 15_000) fun equivalentCapturedSshSourcesShareScanButAnotherGenerationDoesNot() = runBlocking<Unit> {
        val access = mockk<RemoteSftpFileAccess>()
        val backend = mockk<TransferBackend>()
        val scans = AtomicInteger()
        val stamp = AtomicReference("one")
        val baseline = CountDownLatch(1)
        every { access.resolvePath("/workspace", any()) } returns "/workspace"
        every { access.transferBackend("/workspace", any()) } returns backend
        every { backend.stat("") } answers { scans.incrementAndGet(); TransferStat(TransferKind.DIRECTORY, null, "dir") }
        every { backend.list("") } returns listOf("file")
        every { backend.stat("file") } answers {
            val value = stamp.get()
            baseline.countDown()
            TransferStat(TransferKind.FILE, 3, value)
        }
        val identity = RemoteConnectionIdentity(mockk(), "/home/fixture")
        fun execution(source: RemoteConnectionIdentity) = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, "fixture", access, null,
            RemoteSftpFileAccess.ExecutionSource(source, "/workspace", RemoteAgentConfigProfile("fixture", 1))) {}
        val hub = RemoteFileWatchHub()
        val received = Channel<FileChangeBatch>(Channel.UNLIMITED)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { hub.watch(execution(identity), "/workspace/", false, WatchFilter(followGitignore = false)).collect { received.send(it) } }
        assertTrue(baseline.await(5, TimeUnit.SECONDS))
        val second = launch(start = CoroutineStart.UNDISPATCHED) { hub.watch(execution(identity), "/workspace", false, WatchFilter(followGitignore = false)).collect { received.send(it) } }
        assertEquals(1, scans.get())
        stamp.set("two")
        val a = withTimeout(5000) { received.receive() }
        val b = withTimeout(5000) { received.receive() }
        assertEquals(2, scans.get())
        assertEquals(a, b); assertEquals("/workspace/file", a.changes.single().containerPath)
        assertEquals(ChangeKind.MODIFIED, a.changes.single().kind)
        first.cancelAndJoin(); second.cancelAndJoin()
        val next = launch(start = CoroutineStart.UNDISPATCHED) { hub.watch(execution(RemoteConnectionIdentity(mockk(), "/home/fixture")),
            "/workspace", false, WatchFilter(followGitignore = false)).collect { received.send(it) } }
        withTimeout(5000) { while (scans.get() < 3) yield() }
        assertTrue(received.tryReceive().isFailure)
        next.cancelAndJoin()
    }
}
