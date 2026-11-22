package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class RemoteAgentConfigProfile(val id: String, val epoch: Long)

internal data class RemoteAgentConfigServer(val host: String, val port: Int, val username: String, val home: String)

internal data class RemoteAgentConfigKey(
    val server: RemoteAgentConfigServer,
    val connection: RemoteConnectionIdentity,
    val workspace: String,
    val profile: RemoteAgentConfigProfile,
    val kind: AgentConfigKind,
    val root: String,
    val remoteRoot: String
) {
    val identity: String = RemoteAgentConfigProtocol.sha256(
        listOf(server.host, server.port.toString(), server.username, server.home, connection.generation.toString(),
            workspace, profile.id, profile.epoch.toString(), kind.name, root, remoteRoot)
            .joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)
    )
}

internal interface RemoteAgentConfigTransport {
    fun checkIdentity(key: RemoteAgentConfigKey)
    suspend fun manifest(key: RemoteAgentConfigKey): Map<String, RemoteAgentConfigFingerprint>
    suspend fun bodies(key: RemoteAgentConfigKey, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String>
}

internal class RemoteAgentConfigCacheEngine(
    private val transport: RemoteAgentConfigTransport,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val freshnessMillis: Long = 2000,
    private val maxEntries: Int = 24,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxFlights: Int = 4
) {
    private data class State(
        val snapshot: AgentConfigSnapshot,
        val fingerprints: Map<String, RemoteAgentConfigFingerprint>,
        val contents: Map<String, String>
    ) {
        val bytes: Long = contents.entries.sumOf { (path, body) -> (path.length.toLong() + body.length) * 2 + 256 }
    }

    private class Entry {
        var state: State? = null
        var checkedAt: Long = Long.MIN_VALUE
        var token = 0L
        var flight: Flight? = null
    }

    private class Flight(val result: Deferred<AgentConfigSnapshot>, var waiters: Int = 0)

    private val lock = Any()
    private val entries = LinkedHashMap<RemoteAgentConfigKey, Entry>(16, 0.75f, true)
    @Volatile var revision: Long = 0
        private set

    suspend fun directory(key: RemoteAgentConfigKey, forceRefresh: Boolean): AgentConfigSnapshot {
        currentCoroutineContext().ensureActive()
        transport.checkIdentity(key)
        val flight = synchronized(lock) {
            val entry = entries[key] ?: Entry().also {
                if (entries.size >= maxEntries) evictOne()
                if (entries.size >= maxEntries) throw IOException("Too many remote configuration directories in flight")
                entries[key] = it
            }
            val state = entry.state
            if (!forceRefresh && state != null && clockMillis() - entry.checkedAt in 0 until freshnessMillis) {
                transport.checkIdentity(key)
                return state.snapshot
            }
            (entry.flight ?: run {
                if (entries.values.count { it.flight != null } >= maxFlights) throw IOException("Too many remote configuration batches in flight")
                val token = entry.token
                entry.checkedAt = Long.MIN_VALUE
                val deferred = scope.async(start = CoroutineStart.LAZY) { refresh(key, entry, token, state) }
                Flight(deferred).also { entry.flight = it }
            }).also { it.waiters++ }
        }
        try {
            flight.result.start()
            val snapshot = flight.result.await()
            currentCoroutineContext().ensureActive()
            transport.checkIdentity(key)
            synchronized(lock) {
                if (entries[key]?.state?.snapshot !== snapshot) throw CancellationException("Remote configuration invalidated")
            }
            return snapshot
        } finally {
            synchronized(lock) {
                flight.waiters--
                val entry = entries[key]
                if (flight.waiters == 0 && entry?.flight === flight) {
                    entry.flight = null
                    if (!flight.result.isCompleted) flight.result.cancel()
                    if (entry.state == null) entries.remove(key)
                }
                trim()
            }
        }
    }

    fun invalidate(server: RemoteAgentConfigServer, path: String, roots: List<String> = emptyList()) {
        synchronized(lock) {
            if (roots.none { overlaps(it, path) } && entries.keys.none { it.server == server && overlaps(it.remoteRoot, path) }) return
            revision++
            entries.forEach { (key, entry) ->
                if (key.server == server && overlaps(key.remoteRoot, path)) {
                    entry.token++
                    entry.state = null
                    entry.checkedAt = Long.MIN_VALUE
                }
            }
        }
    }

    private suspend fun refresh(key: RemoteAgentConfigKey, entry: Entry, token: Long, previous: State?): AgentConfigSnapshot {
        fun check() {
            transport.checkIdentity(key)
            synchronized(lock) {
                if (entries[key] !== entry || entry.token != token) throw CancellationException("Remote configuration invalidated")
            }
        }
        currentCoroutineContext().ensureActive()
        check()
        val manifest = transport.manifest(key)
        currentCoroutineContext().ensureActive()
        check()
        val changed = manifest.filter { (path, fingerprint) -> previous?.fingerprints?.get(path) != fingerprint }
        val fetched = if (changed.isEmpty()) emptyMap() else transport.bodies(key, changed)
        if (fetched.keys != changed.keys) throw IOException("Incomplete remote configuration bodies")
        val contents = manifest.keys.associateWith { path -> fetched[path] ?: previous?.contents?.get(path)
            ?: throw IOException("Missing remote configuration body") }
        contents.forEach { (path, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            val expected = manifest.getValue(path)
            if (bytes.size != expected.size || RemoteAgentConfigProtocol.sha256(bytes) != expected.sha256) {
                throw IOException("Remote configuration body fingerprint mismatch")
            }
        }
        val revision = RemoteAgentConfigProtocol.sha256(buildString {
            append(key.identity)
            manifest.toSortedMap().forEach { (path, fp) -> append(path.length).append(':').append(path).append(':').append(fp.sha256).append(':').append(fp.size) }
        }.toByteArray(Charsets.UTF_8))
        val files = Collections.unmodifiableMap(contents.mapKeys { (path, _) -> key.root.trimEnd('/') + "/" + path })
        val state = State(AgentConfigSnapshot(key.identity, revision, files), manifest.toMap(), contents)
        if (state.bytes > maxBytes) throw IOException("Remote configuration snapshot exceeds memory limit")
        val context = currentCoroutineContext()
        context.ensureActive()
        check()
        synchronized(lock) {
            context.ensureActive()
            check()
            while (entries.values.sumOf { it.state?.bytes ?: 0L } - (entry.state?.bytes ?: 0L) + state.bytes > maxBytes) {
                if (!evictOne()) throw IOException("Remote configuration cache exceeds memory limit")
            }
            entry.state = state
            entry.checkedAt = clockMillis()
            trim()
        }
        return state.snapshot
    }

    private fun trim() {
        while (entries.size > maxEntries || entries.values.sumOf { it.state?.bytes ?: 0L } > maxBytes) {
            if (!evictOne()) break
        }
    }

    private fun evictOne(): Boolean {
        val victim = entries.entries.firstOrNull { it.value.flight == null } ?: return false
        entries.remove(victim.key)
        return true
    }

    private fun overlaps(left: String, right: String): Boolean = left == right ||
        left.startsWith(right.trimEnd('/') + "/") || right.startsWith(left.trimEnd('/') + "/")
}
