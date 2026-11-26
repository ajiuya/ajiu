package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class UiFileExecutionSource(
    origin: String,
    sourceKey: String,
    private val scopeJob: Job,
    private val checkSource: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val runtimeOwner = RuntimeOwner(RuntimeOwnerKind.FILE, "${origin.take(48)}:${UUID.randomUUID()}:" +
        MessageDigest.getInstance("SHA-256").digest(sourceKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) })

    fun checkCurrent() {
        scopeJob.ensureActive()
        if (closed.get()) throw CancellationException("UI file source closed")
        checkSource()
    }

    override fun close() {
        closed.set(true)
    }
}
