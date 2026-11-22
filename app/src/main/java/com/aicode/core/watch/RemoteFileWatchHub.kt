package com.aicode.core.watch

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@Singleton
class RemoteFileWatchHub @Inject constructor() {
    private class Key(val execution: WorkspaceExecutionContext, val root: String, val recursive: Boolean, val rules: IgnoreRules) {
        private val source = Triple(execution.remoteSource, execution.fileCacheIdentity, execution.localFolderSnapshot.revision)
        override fun equals(other: Any?): Boolean = other is Key && source == other.source && root == other.root && recursive == other.recursive && rules == other.rules
        override fun hashCode(): Int = listOf(source, root, recursive, rules).hashCode()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val poller = SharedSnapshotPoller<Key, ScopeSnapshot>(scope, 2000, ::snapshot)

    fun watch(execution: WorkspaceExecutionContext, rootPath: String, recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT): Flow<FileChangeBatch> = flow {
        execution.requireMode(ExecutionMode.REMOTE_SSH)
        val root = rootPath.trimEnd('/').ifEmpty { "/" }
        if (!root.startsWith('/') || root.split('/').any { it == "." || it == ".." } || "//" in root || root.any { it.isISOControl() }) throw IOException("Invalid remote watch root")
        val rules = IgnoreRules.of(filter, if (filter.followGitignore) withContext(Dispatchers.IO + execution) {
            val path = root.trimEnd('/') + "/.gitignore"
            try { execution.fileAccess.readBytes(path, 64 * 1024).toString(Charsets.UTF_8).lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.toList() }
            catch (failure: CancellationException) { throw failure }
            catch (_: IOException) { emptyList() }
        } else emptyList())
        val key = Key(execution, root, recursive, rules)
        val events = PendingFileChanges(4096)
        val lease = poller.subscribe(key) { before, now ->
            if (!before.complete || !now.complete) events.invalidate()
            for ((path, kind) in diffSnapshot(before.entries, now.entries)) {
                if (kind == ChangeKind.DELETED && !now.complete) continue
                events.offer(path, kind)
            }
        }
        try {
            while (true) {
                events.wakeup.receive()
                currentCoroutineContext().ensureActive()
                execution.checkCurrent()
                val (changes, truncated) = events.drain(512)
                if (changes.isNotEmpty() || truncated) emit(FileChangeBatch(changes.map { (path, kind) -> FileChange(ChangeRoot.OTHER, path, path, kind) }, truncated))
            }
        } finally {
            lease.close()
            events.wakeup.cancel()
        }
    }

    private fun snapshot(key: Key): ScopeSnapshot = key.execution.useCaptured {
        val backend = key.execution.fileAccess as? RemoteSftpFileAccess ?: throw IOException("Remote watch backend required")
        key.execution.checkCurrent()
        if (backend.resolvePath(key.root, false) != key.root || backend.resolvePath(key.root, true) != key.root) throw IOException("Remote watch root changed or follows a link")
        val tree = backend.transferBackend(key.root, key.execution::checkCurrent)
        val entries = linkedMapOf<String, String>()
        var complete = true
        fun visit(relative: String, depth: Int) {
            key.execution.checkCurrent()
            if (entries.size >= 4096 || depth > 64) { complete = false; return }
            try {
                val stat = tree.stat(relative) ?: return
                val path = if (relative.isEmpty()) key.root else key.root.trimEnd('/') + "/" + relative
                entries[path] = "${stat.kind}:${stat.modified}:${stat.size}:${stat.entryIdentity}"
                if (stat.kind != TransferKind.DIRECTORY || relative.isNotEmpty() && (!key.recursive || key.rules.isIgnoredDir(relative.split('/')))) return
                for (name in tree.list(relative)) {
                    if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name || name.any { it.isISOControl() }) { complete = false; continue }
                    visit(if (relative.isEmpty()) name else "$relative/$name", depth + 1)
                    if (entries.size >= 4096) { complete = false; break }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: IOException) { complete = false }
        }
        visit("", 0)
        key.execution.checkCurrent()
        ScopeSnapshot(entries, complete)
    }
}
