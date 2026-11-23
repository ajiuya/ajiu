package com.aicode.feature.agent.domain.workflow

import java.security.MessageDigest

internal class CompactionCheckpoints(
    private val maxEntries: Int = 4,
    private val maxBytes: Long = 4L * 1024 * 1024,
    private val ttlMs: Long = 60 * 60_000L,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Block(val request: String, val materialBudget: Int, val summary: String)
    data class Failure(val index: Int, val materialBudget: Int, val retryable: Int, val truncated: Int, val code: String)

    class Work internal constructor(val fingerprint: String, internal var touchedAt: Long) {
        private val blocks = mutableListOf<Block>()
        @Volatile internal var attempts = 0
        @Volatile internal var failure: Failure? = null
        internal val bytes: Long @Synchronized get() =
            256L + blocks.sumOf { 96L + it.request.length * 2L + it.summary.length * 2L }
        internal val completedCount: Int @Synchronized get() = blocks.size

        @Synchronized
        internal fun block(index: Int): Block? = blocks.getOrNull(index)

        @Synchronized
        internal fun complete(index: Int, block: Block) {
            check(index == blocks.size)
            blocks += block
            failure = null
        }
    }

    private val entries = LinkedHashMap<String, Work>(4, 0.75f, true)

    @Synchronized
    fun open(sessionId: String, fingerprint: String): Work {
        val time = now()
        entries.entries.removeAll { time - it.value.touchedAt >= ttlMs }
        val previous = entries[sessionId]
        val work = previous?.takeIf { it.fingerprint == fingerprint } ?: Work(fingerprint, time)
        work.touchedAt = time
        entries[sessionId] = work
        trim()
        return work
    }

    @Synchronized
    fun updated(sessionId: String, work: Work) {
        if (entries[sessionId] !== work) return
        work.touchedAt = now()
        trim()
    }

    @Synchronized
    fun clear(sessionId: String) {
        entries.remove(sessionId)
    }

    @Synchronized
    internal fun size(): Int = entries.size

    @Synchronized
    internal fun bytes(): Long = entries.values.sumOf { it.bytes }

    private fun trim() {
        while (entries.size > maxEntries || entries.values.sumOf { it.bytes } > maxBytes) {
            entries.remove(entries.keys.first())
        }
    }
}

internal class CompactionFingerprint {
    private val digest = MessageDigest.getInstance("SHA-256")

    fun add(value: String): CompactionFingerprint {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte()))
        digest.update(bytes)
        return this
    }

    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
