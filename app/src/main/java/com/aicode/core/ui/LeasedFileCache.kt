package com.aicode.core.ui

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class LeasedFileCache<K : Any>(
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val idleTtlMs: Long,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    data class Resource(val file: File, val owned: Boolean)

    class Lease internal constructor(val file: File, private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private class Slot(val task: Deferred<Resource>) {
        var resource: Resource? = null
        var readers = 0
        var bytes = 0L
        var lastUsed = 0L
    }

    private val lock = Any()
    private val slots = LinkedHashMap<K, Slot>(16, 0.75f, true)

    init {
        require(maxEntries > 0 && maxBytes > 0 && idleTtlMs > 0)
    }

    suspend fun acquire(key: K, load: suspend () -> Resource): Lease {
        currentCoroutineContext().ensureActive()
        val slot = synchronized(lock) {
            (slots[key] ?: createSlot(key, load)).also {
                it.readers++
                it.lastUsed = clockMs()
            }
        }
        try {
            slot.task.start()
            val resource = slot.task.await()
            currentCoroutineContext().ensureActive()
            trim()
            return Lease(resource.file) { release(key, slot) }
        } catch (error: Throwable) {
            release(key, slot)
            throw error
        }
    }

    private fun createSlot(key: K, load: suspend () -> Resource): Slot {
        lateinit var slot: Slot
        val task = scope.async(start = CoroutineStart.LAZY) {
            val resource = load()
            var published = false
            try {
                currentCoroutineContext().ensureActive()
                val bytes = resource.file.length()
                synchronized(lock) {
                    if (slots[key] !== slot || slot.readers == 0) throw CancellationException("File lease abandoned")
                    slot.resource = resource
                    slot.bytes = bytes
                    published = true
                }
                resource
            } finally {
                if (!published && resource.owned) resource.file.delete()
            }
        }
        slot = Slot(task)
        slots[key] = slot
        task.invokeOnCompletion { error ->
            if (error != null) {
                val failed = synchronized(lock) {
                    if (slots[key] === slot) slots.remove(key)
                    slot.resource.also { slot.resource = null }
                }
                if (failed?.owned == true) failed.file.delete()
            }
        }
        return slot
    }

    private fun release(key: K, slot: Slot) {
        val abandoned = synchronized(lock) {
            slot.readers--
            slot.lastUsed = clockMs()
            if (slot.readers == 0 && !slot.task.isCompleted) {
                if (slots[key] === slot) slots.remove(key)
                slot.task.cancel()
                slot.resource.also { slot.resource = null }?.takeIf { it.owned }
            } else null
        }
        abandoned?.file?.delete()
        trim()
    }

    fun trim() {
        val removed = mutableListOf<Resource>()
        synchronized(lock) {
            var bytes = slots.values.sumOf { it.bytes }
            val now = clockMs()
            val iterator = slots.entries.iterator()
            while (iterator.hasNext()) {
                val slot = iterator.next().value
                val resource = slot.resource ?: continue
                if (slot.readers != 0) continue
                if (slot.bytes > maxBytes || slots.size > maxEntries || bytes > maxBytes || now - slot.lastUsed >= idleTtlMs) {
                    bytes -= slot.bytes
                    iterator.remove()
                    if (resource.owned) removed += resource
                }
            }
        }
        removed.forEach { it.file.delete() }
    }

    data class Stats(val entries: Int, val bytes: Long, val readers: Int)

    fun stats(): Stats = synchronized(lock) {
        Stats(slots.size, slots.values.sumOf { it.bytes }, slots.values.sumOf { it.readers })
    }
}
