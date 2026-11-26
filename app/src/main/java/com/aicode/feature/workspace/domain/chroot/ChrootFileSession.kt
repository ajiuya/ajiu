package com.aicode.feature.workspace.domain.chroot

import java.io.File
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class ChrootFileSession(
    val identity: String,
    val client: ChrootFileWorkerClient,
    val cacheDirectory: File,
    checkCurrent: () -> Unit,
    writablePaths: List<String>,
    readOnlyPaths: List<String> = DEFAULT_READ_ONLY_PATHS,
    private val closeOwner: () -> Unit
) : AutoCloseable {
    private val checkSource = checkCurrent
    private val closed = AtomicBoolean()
    val writablePaths: List<String> = freezePaths(writablePaths)
    val readOnlyPaths: List<String> = freezePaths(readOnlyPaths)

    init {
        require(identity.isNotBlank())
    }

    fun checkCurrent() {
        if (closed.get()) throw CancellationException("Chroot file session closed")
        checkSource()
    }

    fun isWritable(identityPath: String): Boolean {
        val writable = writablePaths.filter { contains(it, identityPath) }.maxOfOrNull { it.length } ?: return false
        val readOnly = readOnlyPaths.filter { contains(it, identityPath) }.maxOfOrNull { it.length } ?: -1
        return writable > readOnly
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        try {
            client.close()
        } catch (closedClient: Throwable) {
            failure = closedClient
        }
        try {
            closeOwner()
        } catch (closedOwner: Throwable) {
            if (failure == null) failure = closedOwner else failure.addSuppressed(closedOwner)
        }
        failure?.let { throw it }
    }

    private companion object {
        val DEFAULT_READ_ONLY_PATHS = listOf(
            "/etc/resolv.conf", "/root/.aicode/skills", "/root/.aicode/agents",
            "/root/.aicode/docs", "/root/.aicode/bin", "/root/.aicode/lib", "/root/.aicode/prompts.custom"
        )

        fun freezePaths(paths: List<String>): List<String> = Collections.unmodifiableList(paths.map { path ->
            require(path.startsWith('/') && '\u0000' !in path)
            require(path.split('/').none { it == "." || it == ".." })
            "/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        }.distinct())

        fun contains(root: String, path: String): Boolean =
            root == "/" || path == root || path.startsWith("$root/")
    }
}
