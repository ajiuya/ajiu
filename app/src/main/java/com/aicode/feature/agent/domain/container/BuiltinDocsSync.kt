package com.aicode.feature.agent.domain.container

import java.nio.ByteBuffer
import java.security.MessageDigest

internal class BuiltinDocsSync(
    private val backend: Backend,
    private val checkActive: () -> Unit = {}
) {
    interface Backend {
        fun type(path: String): EntryType?
        fun readState(): ByteArray
        fun fileHash(path: String): String
        fun createDirectory(path: String)
        fun replaceAtomically(path: String, bytes: ByteArray, expectedHash: String?)
        fun backupFile(path: String, backupPath: String, expectedHash: String)
        fun deleteFile(path: String, expectedHash: String)
    }

    enum class EntryType { FILE, DIRECTORY, SYMLINK, OTHER }

    class Snapshot internal constructor(
        val fingerprint: String,
        internal val documents: Map<String, Document>
    )

    internal class Document(val bytes: ByteArray, val hash: String)
    data class Result(val changed: Boolean, val uploaded: Int, val deleted: Int)

    private class State(
        val ready: Boolean,
        val fingerprint: String,
        val files: Map<String, Set<String>>
    )

    fun sync(snapshot: Snapshot): Result {
        checkActive()
        if (snapshot.documents.isEmpty()) return Result(false, 0, 0)
        val previousBytes = if (ensureParents(STATE_FILE, create = false)) {
            when (type(STATE_FILE)) {
                null -> null
                EntryType.FILE -> backend.readState()
                else -> error("Unsafe built-in docs state file")
            }
        } else null
        val previous = previousBytes?.let(::decode)
        // A ready receipt describes the assets, not a live mirror: leave remote edits/deletions alone until assets change.
        if (previous?.ready == true && previous.fingerprint == snapshot.fingerprint) {
            checkActive()
            return Result(false, 0, 0)
        }

        val hashes = snapshot.documents.mapValues { it.value.hash }
        val previousFiles = previous?.files.orEmpty()
        var verifiedFiles = previousFiles
        var dirty = encode(State(false, snapshot.fingerprint, verifiedFiles))
        val ready = encode(State(true, snapshot.fingerprint, hashes.mapValues { setOf(it.value) }))
        (previousFiles.keys + hashes.keys).forEach { existingHash(it) }
        // Only completed publications confer ownership; an attempted target hash cannot authorize deletion after rollback.
        write(STATE_FILE, dirty, previousBytes?.let(::sha256))
        var uploaded = 0
        snapshot.documents.forEach { (path, document) ->
            checkActive()
            val actual = existingHash(path)
            if (actual != document.hash) {
                val completedFiles = verifiedFiles + (path to (verifiedFiles[path].orEmpty() + document.hash))
                val completedDirty = encode(State(false, snapshot.fingerprint, completedFiles))
                if (actual != null && actual !in previousFiles[path].orEmpty()) backup(path, actual)
                write(path, document.bytes, actual)
                check(existingHash(path) == document.hash) { "Built-in docs publication verification failed" }
                if (completedFiles != verifiedFiles) {
                    write(STATE_FILE, completedDirty, sha256(dirty))
                    verifiedFiles = completedFiles
                    dirty = completedDirty
                }
                uploaded++
            }
        }
        var deleted = 0
        (previousFiles.keys - hashes.keys).forEach { path ->
            val actual = existingHash(path)
            if (actual != null && actual in previousFiles.getValue(path)) {
                checkActive()
                backend.deleteFile(path, actual)
                checkActive()
                deleted++
            }
        }
        write(STATE_FILE, ready, sha256(dirty))
        checkActive()
        return Result(true, uploaded, deleted)
    }

    private fun backup(path: String, actualHash: String) {
        val backupPath = "$BACKUP_DIR/$actualHash/$path"
        ensureParents(backupPath, create = true)
        when (type(backupPath)) {
            null -> {
                checkActive()
                backend.backupFile(path, backupPath, actualHash)
            }
            EntryType.FILE -> Unit
            else -> error("Unsafe built-in docs backup")
        }
        checkActive()
        check(backend.fileHash(backupPath) == actualHash) { "Built-in docs backup conflict" }
    }

    private fun existingHash(path: String): String? {
        if (!ensureParents(path, create = false)) return null
        return when (type(path)) {
            null -> null
            EntryType.FILE -> {
                checkActive()
                backend.fileHash(path).also { checkActive() }
            }
            else -> error("Unsafe built-in docs file")
        }
    }

    private fun type(path: String): EntryType? {
        checkActive()
        return backend.type(path).also { checkActive() }
    }

    private fun ensureParents(path: String, create: Boolean): Boolean {
        val parts = path.split('/').dropLast(1)
        val directories = listOf("") + parts.indices.map { parts.take(it + 1).joinToString("/") }
        for (directory in directories) {
            when (type(directory)) {
                null -> {
                    if (!create) return false
                    checkActive()
                    backend.createDirectory(directory)
                    check(type(directory) == EntryType.DIRECTORY) { "Unsafe built-in docs directory" }
                }
                EntryType.DIRECTORY -> Unit
                else -> error("Unsafe built-in docs directory")
            }
        }
        return true
    }

    private fun write(path: String, bytes: ByteArray, expectedHash: String?) {
        ensureParents(path, create = true)
        check(type(path).let { it == null || it == EntryType.FILE }) { "Unsafe built-in docs file" }
        checkActive()
        backend.replaceAtomically(path, bytes, expectedHash)
        checkActive()
    }

    private fun encode(state: State): ByteArray {
        require(state.files.size <= MAX_TRACKED_FILES && state.files.values.all { it.size in 1..MAX_HASHES_PER_PATH }) {
            "Too many built-in docs journal entries"
        }
        return buildString {
            append(FORMAT).append('\t').append(if (state.ready) "ready" else "dirty")
                .append('\t').append(state.fingerprint).append('\n')
            state.files.toSortedMap().forEach { (path, hashes) ->
                append(hashes.sorted().joinToString(",")).append('\t').append(path).append('\n')
            }
        }.toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_STATE_BYTES) { "Built-in docs state is too large" }
        }
    }

    private fun decode(bytes: ByteArray): State {
        require(bytes.size <= MAX_STATE_BYTES) { "Built-in docs state is too large" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        require(text.endsWith('\n')) { "Incomplete built-in docs state" }
        val lines = text.dropLast(1).split('\n')
        val header = lines.first().split('\t')
        require(header.size == 3 && header[0] == FORMAT && header[1] in setOf("ready", "dirty") &&
            HASH.matches(header[2])) { "Invalid built-in docs state" }
        require(lines.size - 1 <= MAX_TRACKED_FILES) { "Too many built-in docs state entries" }
        val files = linkedMapOf<String, Set<String>>()
        lines.drop(1).forEach { line ->
            val entry = line.split('\t')
            require(entry.size == 2) { "Invalid built-in docs entry" }
            val hashes = entry[0].split(',')
            require(hashes.size in 1..MAX_HASHES_PER_PATH && hashes.all { HASH.matches(it) } &&
                hashes.size == hashes.toSet().size) { "Invalid built-in docs hashes" }
            validateDocumentPath(entry[1])
            require(files.put(entry[1], hashes.toSet()) == null) { "Duplicate built-in docs entry" }
        }
        val ready = header[1] == "ready"
        require(!ready || files.values.all { it.size == 1 }) { "Invalid ready built-in docs hashes" }
        require(!ready || fingerprint(files.mapValues { it.value.single() }) == header[2]) {
            "Invalid built-in docs fingerprint"
        }
        return State(ready, header[2], files)
    }

    companion object {
        internal const val STATE_FILE = ".builtin-docs-state-v1"
        internal const val STAGING_DIR = ".builtin-docs-staging-v1"
        internal const val BACKUP_DIR = ".builtin-docs-backups"
        internal const val MAX_STATE_BYTES = 1024 * 1024
        internal const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
        internal const val MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
        internal const val MAX_DOCUMENTS = 2048
        internal const val MAX_TRACKED_FILES = 4096
        internal const val MAX_HASHES_PER_PATH = 64
        private const val FORMAT = "aicode-docs-v1"
        internal val HASH = Regex("[0-9a-f]{64}")

        fun snapshot(docs: Map<String, String>): Snapshot {
            require(docs.size <= MAX_DOCUMENTS) { "Too many built-in documents" }
            var total = 0L
            val documents = docs.toSortedMap().mapValues { (path, content) ->
                validateDocumentPath(path)
                require(content.length <= MAX_DOCUMENT_BYTES) { "Built-in document is too large" }
                val bytes = content.toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_DOCUMENT_BYTES) { "Built-in document is too large" }
                total += bytes.size
                require(total <= MAX_SNAPSHOT_BYTES) { "Built-in docs snapshot is too large" }
                Document(bytes, sha256(bytes))
            }
            documents.keys.forEach { path ->
                val parts = path.split('/')
                require(parts.indices.drop(1).none { parts.take(it).joinToString("/") in documents }) {
                    "Conflicting built-in docs paths"
                }
            }
            return Snapshot(fingerprint(documents.mapValues { it.value.hash }), documents)
        }

        internal fun validateDocumentPath(path: String) {
            validateRelativePath(path)
            require(path.toByteArray(Charsets.UTF_8).size <= 1024 && path.split('/').size <= 32 &&
                path.endsWith(".md") && !path.substringBefore('/').startsWith('.')) {
                "Invalid built-in docs document path"
            }
        }

        internal fun validateRelativePath(path: String) {
            val parts = path.split('/')
            require(path.toByteArray(Charsets.UTF_8).size <= 2048 && parts.size <= 36 && parts.all { part ->
                part.isNotEmpty() && part != "." && part != ".." &&
                    part.toByteArray(Charsets.UTF_8).size <= 255 &&
                    part.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            }) { "Invalid built-in docs path" }
        }

        internal fun temporaryPath(path: String): String =
            "$STAGING_DIR/${sha256(path.toByteArray(Charsets.UTF_8))}.part"

        private fun fingerprint(files: Map<String, String>): String = sha256(
            files.toSortedMap().entries.joinToString("") { (path, hash) -> "$path\u0000$hash\n" }
                .toByteArray(Charsets.UTF_8)
        )

        internal fun sha256(bytes: ByteArray): String =
            hexDigest(MessageDigest.getInstance("SHA-256").digest(bytes))

        internal fun hexDigest(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
