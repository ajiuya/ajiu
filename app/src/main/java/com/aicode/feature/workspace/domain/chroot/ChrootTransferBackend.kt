package com.aicode.feature.workspace.domain.chroot

import com.aicode.feature.workspace.domain.TransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferStage
import com.aicode.feature.workspace.domain.TransferStat
import com.aicode.feature.workspace.domain.TransferVersion
import com.aicode.feature.workspace.domain.checkedTransferNames
import com.aicode.feature.workspace.domain.identityCheckedRead
import com.aicode.feature.workspace.domain.transferRelative
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class ChrootTransferBackend(
    private val root: String,
    private val sessionFactory: () -> ChrootFileAccess.Operation,
    private val check: () -> Unit
) : TransferBackend {
    private val entry = sessionFactory().use { operation ->
        check()
        operation.request(root) { it.resolve(root, false) }
    }

    fun validateSource(version: TransferVersion) {
        check()
        if (version.nodes.any { it.stat.kind == TransferKind.LINK }) {
            throw IOException("CHROOT transfers do not support symbolic link trees")
        }
    }

    private fun checkRoot(operation: ChrootFileAccess.Operation) {
        check()
        operation.checkCurrent()
        if (operation.request(root) { it.resolve(root, false) } != entry) {
            throw IOException("Chroot transfer path changed")
        }
    }

    private fun <T> access(relative: String, block: (ChrootFileAccess.Operation, String) -> T): T =
        sessionFactory().use { operation ->
            checkRoot(operation)
            block(operation, child(operation, entry, relative)).also { checkRoot(operation) }
        }

    override fun stat(relative: String): TransferStat? = access(relative) { operation, path ->
        statOrNull(operation, path)?.let(::transferStat)
    }

    override fun list(relative: String): List<String> = access(relative) { operation, path -> list(operation, path) }

    override fun openRead(relative: String): InputStream {
        val operation = sessionFactory()
        return try {
            checkRoot(operation)
            val path = child(operation, entry, relative)
            val input = read(operation, path) { checkRoot(operation) }
            val closed = AtomicBoolean()
            identityCheckedRead(object : InputStream() {
                override fun read(): Int = input.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = input.read(b, off, len)
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    try { input.close() } finally { operation.close() }
                }
            }) { checkRoot(operation) }
        } catch (failure: Throwable) {
            runCatching { operation.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun readLink(relative: String): String = throw IOException("CHROOT transfers do not support symbolic links")

    override fun isExecutable(relative: String): Boolean = access(relative) { operation, path ->
        val stat = statOrNull(operation, path) ?: throw IOException("Chroot transfer source disappeared")
        if (stat.kind != FILE) throw IOException("Regular chroot transfer file required")
        stat.mode and 0x49 != 0
    }

    override fun deleteEntry(relative: String) = access(relative) { operation, path ->
        operation.request(path) { it.delete(path, false) }
    }

    override fun createStage(kind: TransferKind): TransferStage {
        if (kind == TransferKind.LINK) throw IOException("CHROOT staging does not support symbolic links")
        val operation = sessionFactory()
        var stage: Stage? = null
        return try {
            checkRoot(operation)
            val parent = entry.substringBeforeLast('/', "").ifEmpty { "/" }
            val parentStat = statOrNull(operation, parent) ?: throw IOException("Chroot transfer target parent is unavailable")
            if (parentStat.kind != DIRECTORY) throw IOException("Chroot transfer target parent is not a directory")
            val owned = parent.trimEnd('/') + "/.aicode-transfer-" + UUID.randomUUID()
            if (statOrNull(operation, owned) != null) throw IOException("Chroot transfer staging already exists")
            Stage(operation, owned, parent, parentStat).also {
                stage = it
                it.initialize(kind)
            }
        } catch (failure: Throwable) {
            runCatching { stage?.close() ?: operation.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private inner class Stage(
        private val operation: ChrootFileAccess.Operation,
        private val owned: String,
        private val parent: String,
        private val parentIdentity: ChrootFileStat
    ) : TransferStage {
        private val marker = "$owned/.owner"
        private val payload = "$owned/payload"
        private val token = UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
        private val closed = AtomicBoolean()
        private val entries = linkedMapOf<String, ChrootFileStat>()
        private var ownedIdentity: ChrootFileStat? = null
        private var markerIdentity: ChrootFileStat? = null
        override var committed = false
            private set

        fun initialize(kind: TransferKind) {
            operation.request(owned) { it.mkdirs(owned) }
            ownedIdentity = statOrNull(operation, owned)?.takeIf { it.kind == DIRECTORY }
                ?: throw IOException("Chroot transfer staging directory is unavailable")
            if (list(operation, owned).isNotEmpty()) throw IOException("Chroot transfer staging is not empty")
            operation.request(marker) { client ->
                if (client.write(marker, false) { it.write(token) } != token.size.toLong()) {
                    throw IOException("Chroot transfer ownership marker size changed")
                }
            }
            markerIdentity = statOrNull(operation, marker)?.takeIf { it.kind == FILE }
                ?: throw IOException("Chroot transfer ownership marker is unavailable")
            checkOwned()
            if (list(operation, owned) != listOf(".owner")) throw IOException("Chroot transfer staging ownership changed")
            if (kind == TransferKind.DIRECTORY) mkdir("")
        }

        private fun checkDirectory() {
            checkRoot(operation)
            if (!sameEntry(parentIdentity, statOrNull(operation, parent)) ||
                !sameEntry(ownedIdentity, statOrNull(operation, owned))) {
                throw IOException("Chroot transfer staging directory identity changed")
            }
        }

        private fun checkOwned() {
            checkDirectory()
            if (!sameEntry(markerIdentity, statOrNull(operation, marker))) {
                throw IOException("Chroot transfer staging ownership changed")
            }
            val found = read(operation, marker, ::checkDirectory).use { input ->
                val bytes = ByteArray(token.size)
                var at = 0
                while (at < bytes.size) {
                    val count = input.read(bytes, at, bytes.size - at)
                    if (count <= 0) throw IOException("Chroot transfer ownership marker changed")
                    at += count
                }
                if (input.read() != -1) throw IOException("Chroot transfer ownership marker changed")
                bytes
            }
            if (!MessageDigest.isEqual(token, found)) throw IOException("Chroot transfer staging ownership changed")
        }

        private fun path(relative: String): String {
            checkOwned()
            return child(operation, payload, relative)
        }

        override fun stat(relative: String): TransferStat? = statOrNull(operation, path(relative))?.let(::transferStat)
        override fun list(relative: String): List<String> = list(operation, path(relative))
        override fun openRead(relative: String): InputStream = read(operation, path(relative), ::checkOwned)
        override fun readLink(relative: String): String = throw IOException("CHROOT staging does not support symbolic links")
        override fun isExecutable(relative: String): Boolean {
            val stat = statOrNull(operation, path(relative)) ?: throw IOException("Chroot staging file is unavailable")
            if (stat.kind != FILE) throw IOException("Regular chroot staging file required")
            return stat.mode and 0x49 != 0
        }

        override fun mkdir(relative: String) {
            val path = path(relative)
            if (statOrNull(operation, path) != null) throw IOException("Chroot staging entry already exists")
            operation.request(path) { it.mkdirs(path) }
            val identity = statOrNull(operation, path)?.takeIf { it.kind == DIRECTORY }
                ?: throw IOException("Chroot staging directory is unavailable")
            if (list(operation, path).isNotEmpty()) throw IOException("Chroot staging directory was replaced")
            entries[path] = identity
            checkOwned()
        }

        override fun writeFile(relative: String, writer: (OutputStream) -> Unit) {
            val path = path(relative)
            if (statOrNull(operation, path) != null) throw IOException("Chroot staging entry already exists")
            operation.request(path) { client ->
                client.write(path, false) { output ->
                    writer(object : OutputStream() {
                        override fun write(value: Int) { checkOwned(); output.write(value) }
                        override fun write(b: ByteArray, off: Int, len: Int) { checkOwned(); output.write(b, off, len) }
                        override fun close() = output.close()
                    })
                    checkOwned()
                }
            }
            entries[path] = statOrNull(operation, path)?.takeIf { it.kind == FILE }
                ?: throw IOException("Chroot staging file is unavailable")
            checkOwned()
        }

        override fun writeLink(relative: String, target: String) {
            throw IOException("CHROOT staging does not support symbolic links")
        }

        override fun commit(overwrite: Boolean, checkBeforeCommit: () -> Unit) {
            checkOwned()
            checkBeforeCommit()
            checkOwned()
            val staged = statOrNull(operation, payload) ?: throw IOException("Chroot staging payload disappeared")
            val existing = statOrNull(operation, entry)
            if (existing != null && (!overwrite || existing.kind != FILE || staged.kind != FILE)) {
                throw IOException("Chroot transfer target cannot be replaced")
            }
            operation.request(payload) {
                it.rename(payload, entry, overwrite)
                committed = true
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            var failure: Throwable? = null
            try {
                checkOwned()
                val remaining = if (committed) emptyMap() else entries.toMap()
                val expectedChildren = remaining.keys.filter { it.substringBeforeLast('/') == owned }
                    .map { it.substringAfterLast('/') } + ".owner"
                if (list(operation, owned) != expectedChildren.sorted()) throw IOException("Chroot staging contains unowned entries")
                for ((path, identity) in remaining) {
                    checkOwned()
                    if (!sameEntry(identity, statOrNull(operation, path))) throw IOException("Chroot staging entry identity changed")
                    if (identity.kind == DIRECTORY) {
                        val expected = remaining.keys.filter { it.substringBeforeLast('/') == path }.map { it.substringAfterLast('/') }
                        if (list(operation, path) != expected.sorted()) throw IOException("Chroot staging contains unowned entries")
                    }
                }
                for ((path, identity) in remaining.entries.toList().asReversed()) {
                    checkOwned()
                    if (!sameEntry(identity, statOrNull(operation, path))) throw IOException("Chroot staging entry identity changed")
                    operation.request(path) { it.delete(path, false) }
                }
                checkOwned()
                operation.request(marker) { it.delete(marker, false) }
                checkDirectory()
                if (list(operation, owned).isNotEmpty()) throw IOException("Chroot staging contains unowned entries")
                operation.request(owned) { it.delete(owned, false) }
            } catch (problem: Throwable) {
                failure = IOException("Chroot transfer staging cleanup is UNKNOWN; no replacement worker was started", problem)
            }
            try { operation.close() } catch (cleanup: Throwable) {
                if (failure == null) failure = IOException("Chroot transfer worker cleanup is UNKNOWN", cleanup)
                else failure.addSuppressed(cleanup)
            }
            failure?.let { throw it }
        }
    }

    private companion object {
        const val DIRECTORY = 1
        const val FILE = 2
        const val LINK = 3

        fun sameEntry(expected: ChrootFileStat?, current: ChrootFileStat?): Boolean = expected != null && current != null &&
            expected.kind == current.kind && expected.device == current.device && expected.inode == current.inode

        fun statOrNull(operation: ChrootFileAccess.Operation, path: String): ChrootFileStat? = operation.request(path) { client ->
            try { client.stat(path, false) } catch (failure: ChrootFileOperationException) {
                if (failure.errno != 2) throw failure
                null
            }
        }

        fun transferStat(stat: ChrootFileStat): TransferStat = TransferStat(
            when (stat.kind) {
                DIRECTORY -> TransferKind.DIRECTORY
                FILE -> TransferKind.FILE
                LINK -> throw IOException("CHROOT transfers do not support symbolic link trees")
                else -> throw IOException("Unsupported chroot transfer entry")
            },
            if (stat.kind == FILE) stat.size else null, stat.modifiedMs.toString(), "${stat.device}:${stat.inode}"
        )

        fun list(operation: ChrootFileAccess.Operation, path: String): List<String> {
            if (statOrNull(operation, path)?.kind != DIRECTORY) throw IOException("Chroot transfer directory required")
            return checkedTransferNames(operation.request(path) { it.list(path) }.map { (name, stat) ->
                transferStat(stat)
                name
            })
        }

        fun child(operation: ChrootFileAccess.Operation, root: String, relative: String): String {
            transferRelative(relative)
            var path = root
            if (relative.isNotEmpty()) for (part in relative.split('/')) {
                if (statOrNull(operation, path)?.kind != DIRECTORY) throw IOException("Unsafe chroot transfer parent")
                path = path.trimEnd('/') + "/" + part
            }
            return path
        }

        fun read(operation: ChrootFileAccess.Operation, path: String, check: () -> Unit): InputStream {
            if (statOrNull(operation, path)?.kind != FILE) throw IOException("Regular chroot transfer file required")
            val input = operation.request(path) { it.openRead(path) }
            val guarded = identityCheckedRead(input) { operation.checkCurrent(); check() }
            return operation.execution?.protectRead(guarded) ?: guarded
        }
    }
}
