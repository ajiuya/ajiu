package com.aicode.feature.agent.presentation.filebrowse

import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import android.net.Uri
import com.aicode.feature.agent.presentation.BrowseClipboard
import com.aicode.feature.agent.presentation.BrowseOperationNotice
import com.aicode.feature.agent.presentation.BrowseUploadTarget
import com.aicode.feature.agent.presentation.FileBrowseState
import com.aicode.feature.agent.presentation.FileTreeNode
import com.aicode.feature.agent.presentation.FileTreeRoot
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.FileBackendRouting
import com.aicode.feature.workspace.domain.FileTransferService
import com.aicode.feature.workspace.domain.LocalFolderGrant
import com.aicode.feature.workspace.domain.TransferConfirmation
import com.aicode.feature.workspace.domain.TransferCancellationException
import com.aicode.feature.workspace.domain.TransferResult
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.isValidFileEntryName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class FileBrowseScope(
    val workspace: String,
    val profileId: String,
    val mode: ExecutionMode,
    val fileIdentity: String,
    val localMapping: WorkspacePathMapper.ExecutionMapping?,
    val localFolders: List<LocalFolderGrant> = emptyList(),
    val localFolderRevision: Long = 0,
    val selectionRevision: Long = 0,
    val runtimeOwner: RuntimeOwner? = null,
    val grantedFolderScope: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class FileBrowseController(
    private val ownerScope: CoroutineScope,
    private val fileAccess: FileAccessProvider,
    private val executionFactory: WorkspaceExecutionContextFactory,
    private val routing: FileBackendRouting,
    private val transfers: FileTransferService,
    private val currentSelectionRevision: () -> Long,
    private val watch: (String) -> Flow<Unit>,
    private val watchLocalFolder: (String) -> Flow<Unit>,
    private val loadExpansion: (String) -> Set<String>,
    private val saveExpansion: (String, Set<String>) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onNotice: (BrowseOperationNotice) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mailbox = Mailbox()
    private val _state = MutableStateFlow<FileBrowseState>(FileBrowseState.Loading)
    val state: StateFlow<FileBrowseState> = _state.asStateFlow()
    private val _expanded = MutableStateFlow(setOf(WORKSPACE_ROOT))
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()
    private val _transferActive = MutableStateFlow(false)
    val transferActive: StateFlow<Boolean> = _transferActive.asStateFlow()
    private val _clipboard = MutableStateFlow<BrowseClipboard?>(null)
    val clipboard: StateFlow<BrowseClipboard?> = _clipboard.asStateFlow()
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    private var revision = -1L
    private var scope: FileBrowseScope? = null
    private var active = false
    private var root = FileTreeRoot.Container("", null, false)
    private val cache = mutableMapOf<String, List<FileEntry>>()
    private val errors = mutableMapOf<String, String?>()
    private var ignorePatterns = emptyList<String>()
    private val dirty = DirtyDirectories()
    private val watches = mutableMapOf<String, Job>()
    private var nextWatch = 0L
    private var nextRequest = 0L
    private var reading: ReadRequest? = null
    private var readJob: Job? = null
    private var nextReadRetry = 0
    private var writing: WriteRequest? = null
    private var writeJob: Job? = null
    private val pendingWrites = ArrayDeque<WritePlan>()
    private var nextClipboard = 0L
    private var boundClipboard: BoundClipboard? = null
    private var conflict: PasteConfirmation? = null
    private val uploadConfirmations = ArrayDeque<TransferConfirmation>()
    private var uploadHadFailures = false

    init {
        ownerScope.launch {
            SharingStarted.WhileSubscribed(5_000).command(_state.subscriptionCount).collect { command ->
                mailbox.setActive(command == SharingCommand.START)
            }
        }
        ownerScope.launch {
            try {
                for (ignored in mailbox.wakeup) {
                    val input = mailbox.drain()
                    if (input.revision != revision) resetScope(input.revision, input.scope, input.unavailable)
                    input.root?.let { root = it }
                    input.active?.let { subscribed ->
                        if (active != subscribed) {
                            active = subscribed
                            if (active) dirty.markAll() else stopBrowsing()
                        }
                    }
                    dirty.merge(input.dirty)
                    input.read?.let(::acceptRead)
                    input.write?.let(::acceptWrite)
                    for (command in input.commands) {
                        if (mailbox.isCurrent(revision)) handle(command)
                    }
                    if (writing == null && pendingWrites.isNotEmpty()) startWrite(pendingWrites.removeFirst())
                    reconcileWatches()
                    publish()
                    startRead()
                }
            } finally {
                stopAllIo()
                mailbox.close()
            }
        }
    }

    // A transition advances the token even for A -> B -> A before the owner is scheduled.
    fun setScope(scope: FileBrowseScope?) = mailbox.setScope(scope)
    fun updateScope(scope: FileBrowseScope) = mailbox.updateScope(scope)
    fun setUnavailable(detail: String?) = mailbox.setScope(null, FileBrowseState.Error(detail))
    fun setRoot(root: FileTreeRoot.Container) = mailbox.setRoot(root)
    fun refresh() = mailbox.invalidate(null)
    fun toggle(path: String) = enqueue(Command.Toggle(path))
    fun create(parent: String, name: String, directory: Boolean, onResult: (Boolean) -> Unit) =
        enqueue(Command.Create(parent, name, directory, onResult))
    fun rename(path: String, name: String, onResult: (Boolean) -> Unit) =
        enqueue(Command.Rename(path, name, onResult))
    fun delete(path: String, onResult: (Boolean) -> Unit) = enqueue(Command.Delete(path, onResult))
    fun copy(path: String, name: String, cut: Boolean) = enqueue(Command.Copy(path, name, cut))
    fun clearClipboard() = enqueue(Command.ClearClipboard)
    fun paste(targetDir: String, onResult: (Boolean) -> Unit) = enqueue(Command.Paste(targetDir, onResult))
    fun confirmOverwrite() = enqueue(Command.ConfirmOverwrite)
    fun clearConflict() = enqueue(Command.ClearConflict)
    fun prepareUpload(targetDir: String, selectionRevision: Long): BrowseUploadTarget? =
        mailbox.prepareUpload(targetDir, selectionRevision)
    fun upload(target: BrowseUploadTarget, uris: List<Uri>, directory: Boolean) {
        if (uris.isEmpty()) return
        enqueue(Command.Upload(target, uris.toList(), directory))
    }
    fun cancelTransfer() = enqueue(Command.CancelTransfer)

    internal fun invalidateDirectory(path: String) = mailbox.invalidate(path)
    internal val cachedDirectoryCount: Int get() = cache.size
    internal val pendingDirectoryCount: Int get() = mailbox.pendingDirectoryCount
    internal val pendingFullRefresh: Boolean get() = mailbox.pendingFullRefresh

    private fun enqueue(command: Command) {
        if (!mailbox.enqueue(command)) command.onResult?.invoke(false)
    }

    private fun resetScope(token: Long, next: FileBrowseScope?, unavailable: FileBrowseState?) {
        stopAllIo()
        revision = token
        scope = next
        cache.clear()
        errors.clear()
        ignorePatterns = emptyList()
        dirty.clear()
        dirty.markAll()
        _expanded.value = next?.let { loadExpansion(it.workspace).toSet() } ?: setOf(WORKSPACE_ROOT)
        boundClipboard = null
        _clipboard.value = null
        clearConfirmation()
        _state.value = unavailable ?: FileBrowseState.Loading
    }

    private fun stopRead(requeue: Boolean) {
        if (requeue) reading?.paths?.forEach(dirty::mark)
        reading = null
        nextReadRetry = 0
        readJob?.cancel()
        readJob = null
        _expandingPath.value = null
    }

    private fun stopBrowsing() {
        stopRead(requeue = false)
        watches.forEach { (path, job) -> mailbox.unwatch(path); job.cancel() }
        watches.clear()
    }

    private fun stopAllIo() {
        stopBrowsing()
        writing = null
        writeJob?.cancel()
        writeJob = null
        pendingWrites.clear()
        _fileOpPaths.value = emptySet()
        _transferActive.value = false
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Toggle -> {
                if (scope?.localFolders.orEmpty().any { !it.available && within(command.path, it.rootPath) }) return
                if (command.path == _expandingPath.value) return
                val expanded = _expanded.value
                if (command.path in expanded) {
                    stopRead(requeue = true)
                    _expanded.value = expanded.filterNot { within(it, command.path) }.toSet()
                    dirty.removeTree(command.path)
                    val keepRoot = command.path in rootPaths()
                    cache.keys.filter { within(it, command.path) && (!keepRoot || it != command.path) }.forEach(cache::remove)
                    errors.keys.filter { within(it, command.path) && (!keepRoot || it != command.path) }.forEach(errors::remove)
                } else {
                    _expanded.value = expanded + command.path
                    _expandingPath.value = command.path
                    dirty.mark(command.path)
                }
                persistExpansion()
            }
            is Command.Copy -> {
                if (scope == null || command.path in rootPaths()) return
                val value = BrowseClipboard(command.path, command.name, command.cut)
                boundClipboard = BoundClipboard(++nextClipboard, revision, value)
                _clipboard.value = value
                clearConfirmation()
            }
            Command.ClearClipboard -> {
                boundClipboard = null
                _clipboard.value = null
                clearConfirmation()
            }
            Command.ClearConflict -> clearConfirmation()
            Command.CancelTransfer -> {
                if (writing?.plan?.transfer == true) writeJob?.cancel()
                pendingWrites.removeAll { it.transfer }
                _fileOpPaths.value = writing?.plan?.busy.orEmpty() + pendingWrites.flatMap { it.busy }
                clearConfirmation()
            }
            else -> {
                if (scope == null || pendingWrites.size >= MAX_COMMANDS) {
                    command.onResult?.invoke(false)
                    return
                }
                prepareWrite(command)?.let { plan ->
                    if (writing == null) startWrite(plan) else {
                        pendingWrites.addLast(plan)
                        _fileOpPaths.value = _fileOpPaths.value + plan.busy
                    }
                }
            }
        }
    }

    private fun prepareWrite(command: Command): WritePlan? {
        fun invalid(): WritePlan? { command.onResult?.invoke(false); return null }
        val checkScope = captureScopeCheck()
        return when (command) {
            is Command.Create -> {
                val target = child(command.parent, command.name) ?: return invalid()
                WritePlan(setOf(target), setOf(command.parent), onResult = command.onResult) {
                    if (fileAccess.exists(target)) WriteOutcome.Rejected else {
                        if (command.directory) fileAccess.mkdirs(target) else fileAccess.writeFile(target, "", overwrite = false)
                        if (!command.directory || fileAccess.isDirectory(target)) WriteOutcome.Success else WriteOutcome.Rejected
                    }
                }
            }
            is Command.Rename -> {
                if (command.path in rootPaths()) return invalid()
                val parent = parent(command.path) ?: return invalid()
                val target = child(parent, command.name) ?: return invalid()
                WritePlan(setOf(command.path, target), setOf(parent), relocation = command.path to target,
                    onResult = command.onResult) {
                    routing.capture(it, listOf(command.path, target), checkScope).apply {
                        guardMutablePath(command.path)
                        guardMutablePath(target)
                        checkCurrent()
                    }
                    fileAccess.rename(command.path, target)
                    WriteOutcome.Success
                }
            }
            is Command.Delete -> {
                if (command.path in rootPaths()) return invalid()
                val parent = parent(command.path) ?: return invalid()
                WritePlan(setOf(command.path), setOf(parent), deleted = command.path, onResult = command.onResult) {
                    routing.capture(it, listOf(command.path), checkScope).apply {
                        guardMutablePath(command.path)
                        checkCurrent()
                    }
                    fileAccess.deleteRecursively(command.path)
                    WriteOutcome.Success
                }
            }
            is Command.Paste -> {
                val clip = boundClipboard?.takeIf { it.revision == revision } ?: return invalid()
                val target = child(command.targetDir, clip.value.sourceName) ?: return invalid()
                if (within(target, clip.value.sourcePath)) return invalid()
                pastePlan(clip, target, command.onResult)
            }
            Command.ConfirmOverwrite -> {
                val confirmation = conflict ?: return null
                conflict = null
                _pasteConflict.value = null
                if (confirmation.revision != revision ||
                    (confirmation.clip != null && confirmation.clip != boundClipboard)) return null
                confirmationPlan(confirmation, checkScope)
            }
            is Command.Upload -> {
                if (!mailbox.isCurrent(command.target.revision) ||
                    command.target.selectionRevision != currentSelectionRevision() ||
                    command.directory && command.uris.size != 1) {
                    onNotice(BrowseOperationNotice.INVALID_SELECTION)
                    return null
                }
                uploadHadFailures = false
                WritePlan(setOf(command.target.targetDir), setOf(command.target.targetDir),
                    changedTree = command.target.targetDir, transfer = true, onResult = {}) { execution ->
                    val results = if (command.directory) listOf(transfers.uploadDirectory(
                        execution, command.target.targetDir, command.uris.single(), checkScope
                    )) else transfers.uploadFiles(execution, command.target.targetDir, command.uris, checkScope)
                    WriteOutcome.Transferred(results, upload = true)
                }
            }
            else -> null
        }
    }

    private fun pastePlan(clip: BoundClipboard, target: String, onResult: (Boolean) -> Unit): WritePlan {
        val source = clip.value.sourcePath
        val parents = setOfNotNull(parent(target), parent(source).takeIf { clip.value.isCut })
        val busy = if (clip.value.isCut) setOf(source, target) else setOf(target)
        val checkScope = captureScopeCheck()
        return WritePlan(busy, parents, changedTree = target,
            relocation = if (clip.value.isCut) source to target else null, clip = clip,
            target = target, transfer = true, onResult = onResult) { execution ->
            WriteOutcome.Transferred(listOf(transfers.transfer(execution, source, target,
                clip.value.isCut, checkScope = checkScope)))
        }
    }

    private fun confirmationPlan(confirmation: PasteConfirmation, checkScope: () -> Unit): WritePlan {
        val ticket = confirmation.ticket
        return WritePlan(setOf(ticket.source, ticket.target), setOfNotNull(parent(ticket.source), parent(ticket.target)),
            changedTree = ticket.target,
            relocation = if (ticket.cut) ticket.source to ticket.target else null,
            clip = confirmation.clip, target = ticket.target, transfer = true, onResult = {}) { execution ->
            WriteOutcome.Transferred(listOf(transfers.confirm(execution, ticket, checkScope)),
                upload = confirmation.clip == null)
        }
    }

    private fun captureScopeCheck(): () -> Unit {
        val token = revision
        val selection = scope?.selectionRevision
        return {
            if (!mailbox.isCurrent(token) || currentSelectionRevision() != selection) {
                throw CancellationException("File browse selection changed")
            }
        }
    }

    private fun startWrite(plan: WritePlan) {
        val captured = scope ?: return
        if (!mailbox.isCurrent(revision)) return
        stopRead(requeue = true)
        val request = WriteRequest(++nextRequest, revision, captured, plan)
        writing = request
        _transferActive.value = plan.transfer
        _fileOpPaths.value = plan.busy + pendingWrites.flatMap { it.busy }
        writeJob = ownerScope.launch {
            var outcome: WriteOutcome = WriteOutcome.Cancelled
            var checkCurrent: (() -> Unit)? = null
            try {
                outcome = frozen(captured, request.revision) { execution ->
                    checkCurrent = execution::checkCurrent
                    plan.block(execution)
                }
            } catch (cancelled: TransferCancellationException) {
                outcome = WriteOutcome.Transferred(listOf(cancelled.result))
                throw cancelled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
                outcome = WriteOutcome.Failed
            } finally {
                mailbox.completeWrite(WriteResult(request, outcome, checkCurrent))
            }
        }
    }

    private fun acceptWrite(result: WriteResult) {
        if (writing?.id != result.request.id || !mailbox.isCurrent(result.request.revision)) return
        writing = null
        writeJob = null
        _transferActive.value = false
        _fileOpPaths.value = pendingWrites.flatMapTo(linkedSetOf()) { it.busy }
        val plan = result.request.plan
        try {
            result.checkCurrent?.invoke()
        } catch (cancelled: CancellationException) {
            plan.parents.forEach(dirty::mark)
            plan.changedTree?.let { tree -> visibleDirectories().filter { within(it, tree) }.forEach(dirty::mark) }
            return
        }
        when (result.outcome) {
            WriteOutcome.Success -> {
                plan.changedTree?.let { removeCachedTree(it) }
                plan.relocation?.let { (source, target) -> relocate(source, target) }
                plan.deleted?.let { removeTree(it) }
                if (plan.clip != null && boundClipboard == plan.clip) {
                    boundClipboard = null
                    _clipboard.value = null
                }
                plan.parents.forEach(dirty::mark)
                plan.onResult(true)
            }
            is WriteOutcome.Transferred -> acceptTransfer(plan, result.outcome)
            WriteOutcome.Failed, WriteOutcome.Rejected -> {
                plan.parents.forEach(dirty::mark)
                plan.changedTree?.let { tree -> visibleDirectories().filter { within(it, tree) }.forEach(dirty::mark) }
                onNotice(BrowseOperationNotice.FAILED)
                plan.onResult(false)
            }
            WriteOutcome.Cancelled -> {
                plan.parents.forEach(dirty::mark)
                plan.changedTree?.let { tree -> visibleDirectories().filter { within(it, tree) }.forEach(dirty::mark) }
            }
        }
    }

    private fun acceptTransfer(plan: WritePlan, outcome: WriteOutcome.Transferred) {
        var completed = 0
        var failed = false
        for (result in outcome.results) {
            when (result) {
                is TransferResult.Completed -> {
                    completed++
                    removeCachedTree(result.target)
                    if (result.cut) relocate(result.source, result.target)
                }
                is TransferResult.Conflict -> {
                    if (plan.clip != null && plan.clip == boundClipboard) {
                        conflict = PasteConfirmation(plan.clip, result.ticket, revision)
                        _pasteConflict.value = result.ticket.source to result.ticket.target
                    } else if (outcome.upload) {
                        uploadConfirmations.addLast(result.ticket)
                    }
                }
                is TransferResult.CopiedButSourceNotRemoved -> {
                    failed = true
                    removeCachedTree(result.target)
                    removeCachedTree(result.source)
                    onNotice(BrowseOperationNotice.COPIED_SOURCE_REMAINS)
                    if (plan.clip == boundClipboard) {
                        boundClipboard = null
                        _clipboard.value = null
                    }
                }
                is TransferResult.DirectoryConflict -> {
                    failed = true
                    onNotice(BrowseOperationNotice.DIRECTORY_CONFLICT)
                }
                is TransferResult.Failed -> {
                    failed = true
                    onFailure(java.io.IOException(result.reason))
                    onNotice(BrowseOperationNotice.FAILED)
                }
            }
        }
        if (outcome.upload && failed) uploadHadFailures = true
        if (completed > 0 || failed) plan.parents.forEach(dirty::mark)
        if (failed) plan.changedTree?.let { tree ->
            visibleDirectories().filter { within(it, tree) }.forEach(dirty::mark)
        }
        if (completed > 0 && plan.clip != null && plan.clip == boundClipboard) {
            boundClipboard = null
            _clipboard.value = null
        }
        if (conflict == null && uploadConfirmations.isNotEmpty()) {
            val ticket = uploadConfirmations.removeFirst()
            conflict = PasteConfirmation(null, ticket, revision)
            _pasteConflict.value = ticket.source to ticket.target
        }
        if (outcome.upload && !uploadHadFailures && !failed && completed > 0 && completed == outcome.results.size &&
            conflict == null && uploadConfirmations.isEmpty()) onNotice(BrowseOperationNotice.UPLOAD_COMPLETED)
        if (failed || completed == outcome.results.size) plan.onResult(!failed && completed > 0)
    }

    private suspend fun <T> frozen(captured: FileBrowseScope, token: Long, block: suspend (WorkspaceExecutionContext) -> T): T {
        if (!mailbox.isCurrent(token)) throw CancellationException("File browse scope changed")
        val execution = withContext(ioDispatcher) {
            if (captured.grantedFolderScope) executionFactory.freezeForPaths(
                captured.localFolders.filter { it.available }.map { it.rootPath }, runtimeOwner = captured.runtimeOwner
            ) else executionFactory.freeze(runtimeOwner = captured.runtimeOwner)
        }
        val mapping = execution.localMapping?.let { it.copy(profile = it.profile.copy(name = "")) }
        if (!mailbox.isCurrent(token) || execution.mode != captured.mode || execution.fileCacheIdentity != captured.fileIdentity ||
            (captured.runtimeOwner != null && execution.runtimeOwner != captured.runtimeOwner) ||
            mapping != captured.localMapping || (mapping != null && mapping.profile.id != captured.profileId) ||
            execution.localFolderSnapshot.revision != captured.localFolderRevision ||
            currentSelectionRevision() != captured.selectionRevision
        ) throw CancellationException("File browse source changed")
        return withContext(ioDispatcher + execution) {
            currentCoroutineContext().ensureActive()
            execution.checkCurrent()
            if (!mailbox.isCurrent(token)) throw CancellationException("File browse scope changed")
            block(execution).also {
                execution.checkCurrent()
                currentCoroutineContext().ensureActive()
                if (!mailbox.isCurrent(token) || currentSelectionRevision() != captured.selectionRevision) {
                    throw CancellationException("File browse scope changed")
                }
            }
        }
    }

    private fun startRead() {
        val captured = scope ?: return
        if (!active || reading != null || writing != null || !mailbox.isCurrent(revision)) return
        val visible = visibleDirectories()
        val batch = dirty.drain()
        val paths = if (batch.full) visible else batch.paths.intersect(visible)
        val deferred = if (batch.full) _expanded.value - visible else batch.paths - visible
        deferred.filter { it in _expanded.value }.forEach(::removeCachedTree)
        if (paths.isEmpty()) return
        val request = ReadRequest(++nextRequest, revision, captured, paths, nextReadRetry)
        nextReadRetry = 0
        reading = request
        readJob = ownerScope.launch {
            var result = ReadResult(request, emptyList(), cancelled = true)
            try {
                val directories = paths.map { path -> readDirectory(captured, request.revision, path) }
                result = ReadResult(request, directories)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                mailbox.completeRead(result)
            }
        }
    }

    private suspend fun readDirectory(captured: FileBrowseScope, token: Long, path: String): DirectoryResult = try {
        frozen(captured, token) { execution ->
            val entries = fileAccess.listFiles(path).distinctBy { it.name }.sortedWith(ORDER)
            val ignores = if (path == WORKSPACE_ROOT) {
                try {
                    val gitignore = childPrefix(path) + ".gitignore"
                    if (!fileAccess.exists(gitignore)) emptyList() else fileAccess.readFile(gitignore).lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                        .map { it.removeSuffix("/") }.toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onFailure(failure)
                    null
                }
            } else null
            DirectoryResult(path, entries, ignores, checkCurrent = execution::checkCurrent)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
        DirectoryResult(path, null, failure = failure.message)
    }

    private fun acceptRead(result: ReadResult) {
        if (reading?.id != result.request.id || !mailbox.isCurrent(result.request.revision)) return
        reading = null
        readJob = null
        if (result.cancelled) {
            retryReadableDirectories(result.request)
            return
        }
        try {
            result.directories.forEach { it.checkCurrent?.invoke() }
        } catch (cancelled: CancellationException) {
            retryReadableDirectories(result.request)
            return
        }
        for (directory in result.directories) {
            if (directory.path !in visibleDirectories()) continue
            if (directory.entries != null) {
                cache[directory.path] = directory.entries
                errors.remove(directory.path)
                directory.ignores?.let { ignorePatterns = it }
                pruneChildren(directory.path, directory.entries)
            } else errors[directory.path] = directory.failure
        }
        if (_expandingPath.value in result.request.paths) _expandingPath.value = null
        visibleDirectories().filter { it !in cache && it !in errors }.forEach(dirty::mark)
    }

    private fun retryReadableDirectories(request: ReadRequest) {
        val readable = request.paths.intersect(visibleDirectories())
        if (request.retry == 0 && readable.isNotEmpty()) {
            readable.forEach(dirty::mark)
            nextReadRetry = 1
        } else {
            readable.forEach { errors[it] = null }
            _expandingPath.value = null
        }
    }

    private fun visibleDirectories(): Set<String> = buildSet {
        fun descend(path: String) {
            if (path !in _expanded.value) return
            add(path)
            cache[path].orEmpty().filter { it.isDirectory }.forEach { entry ->
                val child = childPrefix(path) + entry.name
                if (child in _expanded.value) descend(child)
            }
        }
        descend(WORKSPACE_ROOT)
        if (FS_ROOT in _expanded.value) descend(FS_ROOT)
        scope?.localFolders?.filter { it.available }?.forEach { descend(it.rootPath) }
    }

    private fun rootPaths(): Set<String> = setOf(WORKSPACE_ROOT, FS_ROOT) +
        scope?.localFolders.orEmpty().map { it.rootPath }

    private fun isLocalFolder(path: String) = scope?.localFolders.orEmpty().any { within(path, it.rootPath) }

    private fun reconcileWatches() {
        val visible = visibleDirectories()
        val wanted = if (active) {
            visible.filterTo(linkedSetOf()) {
                isLocalFolder(it) || scope?.mode == ExecutionMode.LOCAL_PROOT && scope?.grantedFolderScope != true
            }.also { if (scope?.mode == ExecutionMode.LOCAL_PROOT && scope?.grantedFolderScope != true) it += WORKSPACE_ROOT }
        } else emptySet()
        (watches.keys - wanted).forEach { path ->
            mailbox.unwatch(path)
            watches.remove(path)?.cancel()
        }
        val token = revision
        for (path in wanted - watches.keys) {
            val lease = ++nextWatch
            mailbox.watch(path, lease)
            val localFolder = isLocalFolder(path)
            watches[path] = ownerScope.launch {
                val changes = if (localFolder) watchLocalFolder(path) else watch(path)
                changes.collect { mailbox.invalidateWatched(path, token, lease) }
            }
        }
    }

    private fun publish() {
        if (scope == null || !mailbox.isCurrent(revision)) return
        if (WORKSPACE_ROOT in _expanded.value && WORKSPACE_ROOT !in cache && scope?.localFolders.isNullOrEmpty()) {
            if (WORKSPACE_ROOT in errors) _state.value = FileBrowseState.Error(errors[WORKSPACE_ROOT])
            return
        }
        val nodes = mutableListOf<FileTreeNode>()
        fun append(path: String, depth: Int, parts: List<String>, patterns: List<String>) {
            if (path !in _expanded.value) return
            for (entry in cache[path].orEmpty()) {
                val child = childPrefix(path) + entry.name
                val relative = parts + entry.name
                val expanded = entry.isDirectory && child in _expanded.value
                nodes += FileTreeNode(entry, child, depth, isExpanded = expanded,
                    hasError = expanded && child in errors,
                    ignored = patterns.isNotEmpty() && GitIgnoreMatcher.isIgnored(patterns, relative))
                if (expanded) append(child, depth + 1, relative, patterns)
            }
        }
        fun appendRoot(root: FileTreeRoot, path: String) {
            nodes += FileTreeNode(FileEntry(root.label, true, 0, 0), path, 0, root,
                path in _expanded.value, hasError = path in _expanded.value && path in errors)
        }
        appendRoot(FileTreeRoot.Workspace, WORKSPACE_ROOT)
        append(WORKSPACE_ROOT, 1, emptyList(), ignorePatterns)
        appendRoot(root, FS_ROOT)
        append(FS_ROOT, 1, emptyList(), emptyList())
        scope?.localFolders.orEmpty().forEach { folder ->
            val localRoot = FileTreeRoot.LocalFolder(folder.id, folder.label, folder.available)
            nodes += FileTreeNode(FileEntry(folder.label, true, 0, 0), folder.rootPath, 0, localRoot,
                folder.rootPath in _expanded.value, hasError = !folder.available || folder.rootPath in errors)
            if (folder.available) append(folder.rootPath, 1, emptyList(), emptyList())
        }
        _state.value = FileBrowseState.Success(nodes)
    }

    private fun pruneChildren(path: String, entries: List<FileEntry>) {
        val directories = entries.filter { it.isDirectory }.mapTo(hashSetOf()) { childPrefix(path) + it.name }
        val tracked = cache.keys + errors.keys + _expanded.value
        val removed = tracked.filter { candidate ->
            candidate != path && candidate.startsWith(childPrefix(path)) &&
                (childPrefix(path) + candidate.removePrefix(childPrefix(path)).substringBefore('/')) !in directories
        }
        removed.forEach(::removeTree)
    }

    private fun removeCachedTree(path: String) {
        cache.keys.filter { within(it, path) }.forEach(cache::remove)
        errors.keys.filter { within(it, path) }.forEach(errors::remove)
    }

    private fun removeTree(path: String) {
        removeCachedTree(path)
        val updated = _expanded.value.filterNot { within(it, path) }.toSet()
        if (updated != _expanded.value) {
            _expanded.value = updated
            persistExpansion()
        }
        dirty.removeTree(path)
    }

    private fun relocate(source: String, target: String) {
        fun moved(path: String) = target + path.removePrefix(source)
        val entries = cache.filterKeys { within(it, source) }
        val failures = errors.filterKeys { within(it, source) }
        removeCachedTree(source)
        entries.forEach { (path, children) -> cache[moved(path)] = children }
        failures.forEach { (path, detail) -> errors[moved(path)] = detail }
        _expanded.value = _expanded.value.mapTo(linkedSetOf()) { if (within(it, source)) moved(it) else it }
        dirty.relocate(source, target)
        dirty.merge(mailbox.relocate(source, target, revision))
        watches.keys.filter { within(it, source) || within(it, target) }.forEach { watches.remove(it)?.cancel() }
        persistExpansion()
    }

    private fun persistExpansion() {
        scope?.let { saveExpansion(it.workspace, _expanded.value.toSet()) }
    }

    private fun clearConfirmation() {
        conflict = null
        uploadConfirmations.clear()
        uploadHadFailures = false
        _pasteConflict.value = null
    }

    private sealed interface Command {
        val onResult: ((Boolean) -> Unit)? get() = null
        data class Toggle(val path: String) : Command
        data class Create(val parent: String, val name: String, val directory: Boolean, override val onResult: (Boolean) -> Unit) : Command
        data class Rename(val path: String, val name: String, override val onResult: (Boolean) -> Unit) : Command
        data class Delete(val path: String, override val onResult: (Boolean) -> Unit) : Command
        data class Copy(val path: String, val name: String, val cut: Boolean) : Command
        data class Paste(val targetDir: String, override val onResult: (Boolean) -> Unit) : Command
        data class Upload(val target: BrowseUploadTarget, val uris: List<Uri>, val directory: Boolean) : Command
        data object CancelTransfer : Command
        data object ClearClipboard : Command
        data object ClearConflict : Command
        data object ConfirmOverwrite : Command
    }

    private data class BoundClipboard(val id: Long, val revision: Long, val value: BrowseClipboard)
    private data class PasteConfirmation(val clip: BoundClipboard?, val ticket: TransferConfirmation, val revision: Long)
    private sealed interface WriteOutcome {
        data object Success : WriteOutcome
        data object Rejected : WriteOutcome
        data object Failed : WriteOutcome
        data object Cancelled : WriteOutcome
        data class Transferred(val results: List<TransferResult>, val upload: Boolean = false) : WriteOutcome
    }
    private data class WritePlan(
        val busy: Set<String>, val parents: Set<String>, val changedTree: String? = null,
        val relocation: Pair<String, String>? = null, val deleted: String? = null,
        val clip: BoundClipboard? = null, val target: String? = null, val transfer: Boolean = false,
        val onResult: (Boolean) -> Unit, val block: suspend (WorkspaceExecutionContext) -> WriteOutcome
    )
    private data class WriteRequest(val id: Long, val revision: Long, val scope: FileBrowseScope, val plan: WritePlan)
    private data class WriteResult(val request: WriteRequest, val outcome: WriteOutcome, val checkCurrent: (() -> Unit)?)
    private data class ReadRequest(
        val id: Long, val revision: Long, val scope: FileBrowseScope, val paths: Set<String>, val retry: Int
    )
    private data class DirectoryResult(
        val path: String, val entries: List<FileEntry>?, val ignores: List<String>? = null,
        val failure: String? = null, val checkCurrent: (() -> Unit)? = null
    )
    private data class ReadResult(val request: ReadRequest, val directories: List<DirectoryResult>, val cancelled: Boolean = false)
    private data class DirtyBatch(val paths: Set<String>, val full: Boolean)

    private class DirtyDirectories {
        private val paths = linkedSetOf<String>()
        private var full = false
        val size get() = paths.size
        val isFull get() = full
        fun mark(path: String) {
            if (full) return
            if (path in paths || paths.size < MAX_DIRTY_DIRECTORIES) paths += path else markAll()
        }
        fun markAll() { paths.clear(); full = true }
        fun merge(batch: DirtyBatch) { if (batch.full) markAll() else batch.paths.forEach(::mark) }
        fun removeTree(path: String) { paths.removeAll { within(it, path) } }
        fun relocate(source: String, target: String) {
            if (full) return
            val relocated = paths.map { if (within(it, source)) target + it.removePrefix(source) else it }
            paths.clear()
            relocated.forEach(::mark)
        }
        fun clear() { paths.clear(); full = false }
        fun drain() = DirtyBatch(paths.toSet(), full).also { clear() }
    }

    private data class Input(
        val revision: Long, val scope: FileBrowseScope?, val unavailable: FileBrowseState?,
        val root: FileTreeRoot.Container?, val active: Boolean?,
        val dirty: DirtyBatch, val commands: List<Command>, val read: ReadResult?, val write: WriteResult?
    )

    private class Mailbox {
        val wakeup = Channel<Unit>(Channel.CONFLATED)
        private var revision = 0L
        private var scope: FileBrowseScope? = null
        private var unavailable: FileBrowseState? = null
        private var root: FileTreeRoot.Container? = null
        private var active: Boolean? = null
        private val dirty = DirtyDirectories()
        private val commands = ArrayDeque<Command>()
        private val watchLeases = mutableMapOf<String, Long>()
        private var read: ReadResult? = null
        private var write: WriteResult? = null
        @Synchronized fun setScope(next: FileBrowseScope?, error: FileBrowseState? = null) {
            revision++
            scope = next
            unavailable = error
            watchLeases.clear()
            commands.clear()
            dirty.clear()
            read = null
            write = null
            wakeup.trySend(Unit)
        }
        @Synchronized fun updateScope(next: FileBrowseScope) {
            if (scope != next) setScope(next)
        }
        @Synchronized fun setRoot(value: FileTreeRoot.Container) { root = value; wakeup.trySend(Unit) }
        @Synchronized fun setActive(value: Boolean) { active = value; wakeup.trySend(Unit) }
        @Synchronized fun invalidate(path: String?, expectedRevision: Long = revision) {
            if (expectedRevision != revision) return
            if (path == null) dirty.markAll() else dirty.mark(path)
            wakeup.trySend(Unit)
        }
        @Synchronized fun watch(path: String, lease: Long) { watchLeases[path] = lease }
        @Synchronized fun unwatch(path: String) { watchLeases.remove(path) }
        @Synchronized fun invalidateWatched(path: String, expectedRevision: Long, lease: Long) {
            if (watchLeases[path] == lease) invalidate(path, expectedRevision)
        }
        @Synchronized fun relocate(source: String, target: String, expectedRevision: Long): DirtyBatch {
            if (expectedRevision != revision) return DirtyBatch(emptySet(), false)
            watchLeases.keys.removeAll { within(it, source) || within(it, target) }
            dirty.relocate(source, target)
            return dirty.drain()
        }
        @Synchronized fun enqueue(command: Command): Boolean {
            if (commands.size >= MAX_COMMANDS || wakeup.isClosedForSend) return false
            commands.addLast(command)
            wakeup.trySend(Unit)
            return true
        }
        @Synchronized fun completeRead(result: ReadResult) {
            if (result.request.revision != revision || (read?.request?.id ?: -1) > result.request.id) return
            read = result
            wakeup.trySend(Unit)
        }
        @Synchronized fun completeWrite(result: WriteResult) {
            if (result.request.revision != revision || (write?.request?.id ?: -1) > result.request.id) return
            write = result
            wakeup.trySend(Unit)
        }
        @Synchronized fun isCurrent(expected: Long) = expected == revision
        @Synchronized fun prepareUpload(targetDir: String, selectionRevision: Long): BrowseUploadTarget? {
            val captured = scope ?: return null
            if (captured.selectionRevision != selectionRevision || wakeup.isClosedForSend) return null
            return BrowseUploadTarget(targetDir, revision, selectionRevision)
        }
        val pendingDirectoryCount: Int @Synchronized get() = dirty.size
        val pendingFullRefresh: Boolean @Synchronized get() = dirty.isFull
        @Synchronized fun drain(): Input = Input(revision, scope, unavailable, root, active, dirty.drain(), commands.toList(), read, write).also {
            root = null
            active = null
            commands.clear()
            read = null
            write = null
        }
        @Synchronized fun close() { commands.clear(); watchLeases.clear(); read = null; write = null; wakeup.close() }
    }

    companion object {
        internal const val MAX_DIRTY_DIRECTORIES = 128
        private const val MAX_COMMANDS = 32
        private const val WORKSPACE_ROOT = WorkspacePathMapper.CONTAINER_ROOT
        private const val FS_ROOT = "/"
        private val ORDER = compareByDescending<FileEntry> { it.isDirectory }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }
        private fun childPrefix(path: String) = if (path.endsWith('/')) path else "$path/"
        private fun within(path: String, directory: String) = path == directory || path.startsWith(childPrefix(directory))
        private fun child(parent: String, name: String): String? =
            if (isValidFileEntryName(name)) childPrefix(parent) + name.trim() else null
        private fun parent(path: String): String? = path.trimEnd('/').substringBeforeLast('/', "")
            .let { if (it.isEmpty() && path.startsWith('/') && path != "/") "/" else it.ifEmpty { null } }
    }
}
