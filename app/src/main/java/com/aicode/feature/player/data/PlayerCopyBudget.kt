package com.aicode.feature.player.data

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerCopyBudget @Inject constructor() {
    private var reserved = 0L
    private var pending = 0L
    @Synchronized internal fun reserve(bytes: Long, directory: File): Reservation {
        val capacity = directory.totalSpace / 2
        if (bytes < 0 || bytes > capacity - reserved) throw IOException("Player copy byte limit exceeded")
        if (bytes > directory.usableSpace - MIN_FREE_BYTES - pending) throw IOException("Insufficient space for player copy")
        reserved += bytes
        pending += bytes
        return Reservation(bytes)
    }
    internal inner class Reservation(private val bytes: Long) : AutoCloseable {
        private var remaining = bytes
        private var released = false
        fun written(count: Int) = synchronized(this@PlayerCopyBudget) {
            if (!released) { remaining -= count; pending -= count }
        }
        override fun close() = synchronized(this@PlayerCopyBudget) {
            if (!released) { released = true; reserved -= bytes; pending -= remaining }
        }
    }
    internal fun checkDisk(directory: File) {
        if (directory.usableSpace < MIN_FREE_BYTES) throw IOException("Insufficient space for player copy")
    }
    internal companion object { const val MIN_FREE_BYTES = 64L * 1024 * 1024 }
}
