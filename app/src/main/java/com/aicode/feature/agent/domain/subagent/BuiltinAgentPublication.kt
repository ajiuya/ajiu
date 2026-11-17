package com.aicode.feature.agent.domain.subagent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Android forbids hard links for app-owned files. CREATE_NEW claims a name without clobbering it;
 * parsers take a text snapshot under this barrier while the claimed inode receives its payload.
 * External readers and recovery after a hard process kill are not covered by this in-process lock.
 */
internal object BuiltinAgentPublication {
    private val lock = ReentrantReadWriteLock()
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun notifyChanged() { mutableRevision.update { it + 1 } }

    fun <T> read(block: () -> T): T = locked(lock.readLock(), block)
    fun <T> write(block: () -> T): T = locked(lock.writeLock(), block)

    private fun <T> locked(guard: java.util.concurrent.locks.Lock, block: () -> T): T {
        guard.lockInterruptibly()
        return try { block() } finally { guard.unlock() }
    }
}
