package com.aicode.feature.player.data

import java.io.FilterInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal class PlayerConnectionActivity(
    private val nowNanos: () -> Long = System::nanoTime,
    private val headerBudgetNanos: Long = TimeUnit.SECONDS.toNanos(15),
    private val idleBudgetNanos: Long = TimeUnit.SECONDS.toNanos(45)
) {
    private val acceptedAt = nowNanos()
    private var lastProgressAt = acceptedAt
    private var headersReceived = false
    private var expired = false
    @Synchronized fun progress() {
        val now = nowNanos()
        if (expiredAt(now)) throw SocketTimeoutException("Player connection timeout")
        lastProgressAt = now
    }
    @Synchronized fun headersComplete() {
        val now = nowNanos()
        if (expiredAt(now)) throw SocketTimeoutException("Player header timeout")
        headersReceived = true; lastProgressAt = now
    }
    @Synchronized fun timedOut() = expiredAt(nowNanos())
    private fun expiredAt(now: Long): Boolean {
        if (!expired) expired = now - (if (headersReceived) lastProgressAt else acceptedAt) >=
            (if (headersReceived) idleBudgetNanos else headerBudgetNanos)
        return expired
    }
}

internal class PlayerProgressInputStream(input: InputStream, private val progress: () -> Unit) : FilterInputStream(input) {
    override fun read(): Int = `in`.read().also { if (it >= 0) progress() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it > 0) progress() }
}
