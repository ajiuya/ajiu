package com.aicode.feature.backup.data

import com.aicode.feature.workspace.domain.TransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferReader
import com.aicode.feature.workspace.domain.TransferStat
import com.aicode.feature.workspace.domain.transferStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

internal data class BackupFileNode(
    val path: String,
    val stat: TransferStat,
    val bytes: Long = 0,
    val digest: String? = null,
    val executable: Boolean = false
)

internal data class BackupFileTree(val nodes: List<BackupFileNode>) {
    val files: List<BackupFileNode> get() = nodes.filter { it.stat.kind == TransferKind.FILE }
}

internal class BackupFileRestoreProgress {
    var restored = 0
    var skipped = 0
    var conflicts = 0
    var changed = false
}

internal object BackupFileContentFiles {
    private val excludedSkillDirectories = setOf(
        "node_modules", ".venv", "venv", "__pycache__", ".cache", ".git",
        ".pytest_cache", ".mypy_cache", ".ruff_cache", ".builtin-install-receipts-v1"
    )

    fun includeSkill(path: String, kind: TransferKind): Boolean =
        path.split('/').none { it in excludedSkillDirectories || it.startsWith(".aicode-transfer-") || it.startsWith(".builtin-stage-") }

    fun snapshot(
        reader: TransferReader,
        checkCurrent: () -> Unit,
        include: (String, TransferKind) -> Boolean = { _, _ -> true },
        definitionsLayout: Boolean = false
    ): BackupFileTree? {
        checkCurrent()
        if (reader.stat("") == null) return null
        val nodes = arrayListOf<BackupFileNode>()
        var total = 0L
        var pathBytes = 0L
        fun visit(path: String) {
            checkCurrent()
            val before = reader.stat(path) ?: throw IOException("Backup source disappeared")
            if (path.isNotEmpty() && !include(path, before.kind)) return
            val valid = validRelative(path) || definitionsLayout && path.startsWith("definitions/") &&
                validRelative(path.removePrefix("definitions/"))
            if (path.isNotEmpty() && (!valid || nodes.size >= BackupContentArchive.MAX_ENTRIES)) {
                throw IOException("Backup content entry limit exceeded")
            }
            pathBytes += path.length
            if (pathBytes > 2L * 1024 * 1024) throw IOException("Backup content path limit exceeded")
            val index = nodes.size
            nodes += BackupFileNode(path, before)
            when (before.kind) {
                TransferKind.LINK -> throw IOException("Linked backup content is not supported")
                TransferKind.FILE -> {
                    val size = before.size ?: throw IOException("Backup file size is unavailable")
                    if (size !in 0..BackupContentArchive.MAX_FILE_BYTES || total > BackupContentArchive.MAX_SECTION_BYTES - size) {
                        throw IOException("Backup content size limit exceeded")
                    }
                    val executable = reader.isExecutable(path)
                    val digest = reader.openRead(path).use { transferStream(it, null, size, checkCurrent) }
                    if (reader.isExecutable(path) != executable) throw IOException("Backup file permissions changed")
                    nodes[index] = BackupFileNode(path, before, digest.bytes, digest.digest, executable)
                    total += size
                }
                TransferKind.DIRECTORY -> {
                    val children = names(reader, path)
                    children.forEach { name -> visit(child(path, name)) }
                    if (names(reader, path) != children) throw IOException("Backup directory changed")
                }
            }
            checkCurrent()
            if (reader.stat(path) != before) throw IOException("Backup source changed during snapshot")
        }
        visit("")
        checkCurrent()
        return BackupFileTree(nodes)
    }

    fun copyToDirectory(reader: TransferReader, tree: BackupFileTree, destination: File, checkCurrent: () -> Unit) {
        BackupContentArchive.checkDirectory(destination)
        for (node in tree.nodes) {
            checkCurrent()
            if (reader.stat(node.path) != node.stat) throw IOException("Backup source changed before staging")
            val file = if (node.path.isEmpty()) destination else File(destination, node.path)
            when (node.stat.kind) {
                TransferKind.DIRECTORY -> {
                    if (node.path.isNotEmpty()) Files.createDirectory(file.toPath())
                    BackupContentArchive.checkDirectory(file)
                }
                TransferKind.FILE -> {
                    if (node.path.isEmpty()) throw IOException("A backup tree must be a directory")
                    val target = BackupContentArchive.safeFile(destination, node.path)
                    Files.newOutputStream(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                        val copied = reader.openRead(node.path).use { transferStream(it, output, node.bytes, checkCurrent) }
                        if (copied.digest != node.digest) throw IOException("Backup source hash changed")
                    }
                    if (target.canExecute() != node.executable && !target.setExecutable(node.executable, true)) {
                        throw IOException("Cannot preserve backup file permissions")
                    }
                    if (reader.isExecutable(node.path) != node.executable) throw IOException("Backup source permissions changed")
                }
                TransferKind.LINK -> throw IOException("Linked backup content is not supported")
            }
            if (reader.stat(node.path) != node.stat) throw IOException("Backup source changed during staging")
        }
        checkCurrent()
    }

    fun restore(
        source: TransferReader,
        tree: BackupFileTree,
        targetAt: (String) -> TransferBackend,
        overwrite: Boolean,
        progress: BackupFileRestoreProgress,
        checkCurrent: () -> Unit,
        afterCommit: () -> Unit = {}
    ) {
        val blocked = arrayListOf<String>()
        for (node in tree.nodes) {
            checkCurrent()
            if (node.path.isNotEmpty() && blocked.any { it.isEmpty() || node.path == it || node.path.startsWith("$it/") }) {
                if (node.stat.kind == TransferKind.FILE) { progress.skipped++; progress.conflicts++ }
                continue
            }
            val target = targetAt(node.path)
            val before = target.stat("")
            if (node.stat.kind == TransferKind.DIRECTORY) {
                if (before?.kind == TransferKind.DIRECTORY) continue
                if (before != null) {
                    blocked += node.path
                    progress.conflicts++
                    continue
                }
                target.createStage(TransferKind.DIRECTORY).use { stage ->
                    stage.commit(overwrite = false) {
                        checkCurrent()
                        if (target.stat("") != null) throw IOException("Backup directory target appeared")
                    }
                    progress.changed = true
                    afterCommit()
                }
                continue
            }
            if (node.stat.kind != TransferKind.FILE) throw IOException("Invalid backup restore entry")
            if (before != null && (!overwrite || before.kind != TransferKind.FILE)) {
                progress.skipped++
                progress.conflicts++
                continue
            }
            val previous = if (before != null) snapshot(target, checkCurrent) else null
            target.createStage(TransferKind.FILE).use { stage ->
                stage.writeFile("") { output ->
                    if (source.stat(node.path) != node.stat) throw IOException("Backup input changed")
                    val digest = source.openRead(node.path).use { transferStream(it, output, node.bytes, checkCurrent) }
                    if (digest.digest != node.digest || source.stat(node.path) != node.stat ||
                        source.isExecutable(node.path) != node.executable
                    ) throw IOException("Backup input verification failed")
                }
                stage.setExecutable("", node.executable)
                val staged = snapshot(stage, checkCurrent)?.nodes?.singleOrNull()
                    ?: throw IOException("Invalid backup staging file")
                if (staged.digest != node.digest || staged.bytes != node.bytes || staged.executable != node.executable) {
                    throw IOException("Backup staging verification failed")
                }
                stage.commit(overwrite = before != null) {
                    checkCurrent()
                    if (snapshot(target, checkCurrent) != previous) throw IOException("Backup restore target changed")
                    if (source.stat(node.path) != node.stat) throw IOException("Backup input changed before commit")
                }
                progress.restored++
                progress.changed = true
                afterCommit()
            }
        }
        checkCurrent()
    }

    fun validRelative(path: String): Boolean = path.length <= 4096 && path.split('/').let { parts ->
        parts.size <= 64 && parts.all(BackupContentArchive::validPart)
    }

    private fun names(reader: TransferReader, path: String): List<String> {
        val names = reader.list(path)
        if (names.size > BackupContentArchive.MAX_ENTRIES || names.toSet().size != names.size ||
            names.any { !BackupContentArchive.validPart(it) }
        ) throw IOException("Invalid backup directory entries")
        return names.sorted()
    }

    private fun child(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"
}
