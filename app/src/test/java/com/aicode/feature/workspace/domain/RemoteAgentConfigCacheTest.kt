package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAgentConfigCacheTest {
    private fun key(workspace: String = "/project", profile: RemoteAgentConfigProfile = RemoteAgentConfigProfile("a", 1)) = RemoteAgentConfigKey(
        RemoteAgentConfigServer("host.example", 22, "user", "/home/user"), RemoteConnectionIdentity(mockk(), "/home/user"),
        workspace, profile, AgentConfigKind.AGENTS, "~/.aicode/agents", "/home/user/.aicode/agents"
    )

    private class Transport : RemoteAgentConfigTransport {
        var contents = mapOf("one.md" to "original")
        var identityCheck: (RemoteAgentConfigKey) -> Unit = {}
        var onManifest: suspend () -> Unit = {}
        var onBodies: suspend () -> Unit = {}
        val manifests = AtomicInteger()
        val bodyBatches = mutableListOf<Set<String>>()
        override fun checkIdentity(key: RemoteAgentConfigKey) = identityCheck(key)
        override suspend fun manifest(key: RemoteAgentConfigKey): Map<String, RemoteAgentConfigFingerprint> {
            manifests.incrementAndGet()
            onManifest()
            return contents.mapValues { (_, body) -> RemoteAgentConfigFingerprint(RemoteAgentConfigProtocol.sha256(body.toByteArray()), body.toByteArray().size) }
        }
        override suspend fun bodies(key: RemoteAgentConfigKey, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String> {
            synchronized(bodyBatches) { bodyBatches += expected.keys.toSet() }
            onBodies()
            return expected.keys.associateWith { contents.getValue(it) }
        }
    }

    @Test fun warmTtlAndForcedRefreshFetchOnlyChangedBodies() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope, { now })
            val key = key()
            val first = engine.directory(key, false)
            now = 1999
            assertSame(first, engine.directory(key, false))
            assertEquals(1, transport.manifests.get())
            now = 2000
            assertEquals(first, engine.directory(key, false))
            assertEquals(2, transport.manifests.get())
            engine.directory(key, true)
            assertEquals(3, transport.manifests.get())
            assertEquals(listOf(setOf("one.md")), transport.bodyBatches)
            transport.contents = mapOf("one.md" to "original", "two.md" to "new")
            val updated = engine.directory(key, true)
            assertEquals(setOf("two.md"), transport.bodyBatches.last())
            assertEquals(2, updated.files.size)
        } finally { scope.cancel() }
    }

    @Test fun changeWithinBatchIsRejectedAndFailedForceCannotWarmOldSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope)
            val key = key()
            engine.directory(key, false)
            transport.contents = mapOf("one.md" to "changed")
            transport.onBodies = { transport.contents = mapOf("one.md" to "corrupt") }
            assertTrue(runCatching { engine.directory(key, true) }.exceptionOrNull() is IOException)
            transport.onManifest = { throw IOException("offline") }
            assertTrue(runCatching { engine.directory(key, false) }.exceptionOrNull() is IOException)
        } finally { scope.cancel() }
    }

    @Test fun invalidationDuringReadCannotPublishOrReviveOnFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope)
            val key = key()
            engine.directory(key, false)
            transport.contents = mapOf("one.md" to "new")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            transport.onBodies = { entered.complete(Unit); release.await() }
            val refresh = async { runCatching { engine.directory(key, true) }.exceptionOrNull() }
            entered.await()
            engine.invalidate(key.server, "${key.remoteRoot}/one.md")
            release.complete(Unit)
            assertTrue(refresh.await() is CancellationException)
            transport.onManifest = { throw IOException("offline") }
            assertTrue(runCatching { engine.directory(key, false) }.exceptionOrNull() is IOException)
        } finally { scope.cancel() }
    }

    @Test fun globalInvalidationReachesOtherWorkspaceAliasesButNotOtherServers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope)
            val first = key()
            val second = first.copy(workspace = "/another")
            val other = first.copy(server = first.server.copy(host = "other.example"))
            engine.directory(first, false); engine.directory(second, false)
            val unaffected = engine.directory(other, false)
            engine.invalidate(first.server, "${first.remoteRoot}/one.md")
            transport.onManifest = { throw IOException("offline") }
            assertSame(unaffected, engine.directory(other, false))
            assertTrue(runCatching { engine.directory(first, false) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { engine.directory(second, false) }.exceptionOrNull() is IOException)
        } finally { scope.cancel() }
    }

    @Test fun evictedOrNeverLoadedDirectoriesHaveNoOldStateToResurrectAfterInvalidate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope, maxEntries = 1)
            val first = key()
            engine.directory(first, false)
            engine.directory(first.copy(workspace = "/evict"), false)
            engine.invalidate(first.server, "${first.remoteRoot}/one.md", listOf(first.remoteRoot))
            transport.onManifest = { throw IOException("offline") }
            assertTrue(runCatching { engine.directory(first, false) }.exceptionOrNull() is IOException)
            assertTrue(runCatching { engine.directory(first.copy(workspace = "/never"), false) }.exceptionOrNull() is IOException)
        } finally { scope.cancel() }
    }

    @Test fun identityReconnectAndProfileEpochMustNotBorrowWarmSnapshots() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope)
            val first = key()
            var active = first
            transport.identityCheck = { if (it != active) throw CancellationException("stale") }
            val old = engine.directory(first, false)
            val profileB = first.copy(profile = RemoteAgentConfigProfile("b", 2))
            active = profileB
            assertNotEquals(old.identity, engine.directory(active, false).identity)
            active = first.copy(profile = RemoteAgentConfigProfile("a", 3))
            assertNotEquals(old.identity, engine.directory(active, false).identity)
            active = active.copy(connection = RemoteConnectionIdentity(mockk(), first.server.home))
            assertNotEquals(old.identity, engine.directory(active, false).identity)
            assertTrue(runCatching { engine.directory(first, false) }.exceptionOrNull() is CancellationException)
            assertEquals(4, transport.manifests.get())
        } finally { scope.cancel() }
    }

    @Test fun parallelWaitersShareOneFlightAndOnlyLastCancellationClosesItsOperation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val entered = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            transport.onManifest = { entered.complete(Unit); try { awaitCancellation() } finally { closed.complete(Unit) } }
            val engine = RemoteAgentConfigCacheEngine(transport, scope)
            val key = key()
            val first = async(start = CoroutineStart.UNDISPATCHED) { engine.directory(key, false) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.directory(key, true) }
            entered.await()
            first.cancelAndJoin()
            assertFalse(closed.isCompleted)
            assertEquals(1, transport.manifests.get())
            second.cancelAndJoin()
            closed.await()
        } finally { scope.cancel() }
    }

    @Test fun unrelatedFileDoesNotExpireWarmDirectory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = Transport()
            val engine = RemoteAgentConfigCacheEngine(transport, scope)
            val key = key()
            val snapshot = engine.directory(key, false)
            engine.invalidate(key.server, "/project/readme.txt")
            assertSame(snapshot, engine.directory(key, false))
            assertEquals(1, transport.manifests.get())
            assertEquals(0L, engine.revision)
        } finally { scope.cancel() }
    }
}
