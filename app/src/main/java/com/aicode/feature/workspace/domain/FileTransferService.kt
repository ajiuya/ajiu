package com.aicode.feature.workspace.domain

import android.net.Uri
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.workspace.domain.chroot.ChrootFileAccess
import com.aicode.feature.workspace.domain.chroot.ChrootTransferBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TransferResult {
    data class Completed(val source: String, val target: String, val cut: Boolean) : TransferResult
    data class Conflict(val ticket: TransferConfirmation) : TransferResult
    data class CopiedButSourceNotRemoved(val source: String, val target: String, val message: String) : TransferResult
    data class DirectoryConflict(val source: String, val target: String) : TransferResult
    data class Failed(val reason: String) : TransferResult
}

class TransferConfirmation internal constructor(internal val plan: TransferTicketPlan) {
    val source: String get() = plan.source
    val target: String get() = plan.target
    val cut: Boolean get() = plan.cut
}

class TransferCancellationException internal constructor(
    val result: TransferResult.CopiedButSourceNotRemoved,
    cause: CancellationException,
) : CancellationException(result.message) {
    init { initCause(cause) }
}

internal sealed interface TransferTicketPlan {
    val execution: WorkspaceExecutionContext
    val source: String
    val target: String
    val cut: Boolean
}

internal class TransferPlan(
    override val execution: WorkspaceExecutionContext,
    val capture: RouteCapture,
    override val source: String,
    override val target: String,
    override val cut: Boolean,
    val reader: TransferReader,
    val destination: TransferBackend,
    val sourceVersion: TransferVersion,
    val targetVersion: TransferVersion?,
    val checkPaths: (committed: Boolean) -> Unit,
    val sameBackend: FileAccessProvider? = null,
) : TransferTicketPlan

internal sealed interface ChrootUploadSelection {
    val uri: Uri
    class File(override val uri: Uri) : ChrootUploadSelection
    class Directory(override val uri: Uri) : ChrootUploadSelection
}

internal class ChrootUploadRequest(
    val targetDirectory: String,
    val selection: ChrootUploadSelection,
    val directoryIdentity: ChrootFileAccess.UploadDirectoryIdentity,
    val checkOriginalScope: () -> Unit,
)

internal class ChrootUploadPlan(
    override val execution: WorkspaceExecutionContext,
    val request: ChrootUploadRequest,
    override val source: String,
    override val target: String,
    val name: String,
    val physicalSource: String?,
    val targetEntry: String,
    val targetReal: String,
    val sourceVersion: TransferVersion,
    val targetVersion: TransferVersion,
) : TransferTicketPlan {
    override val cut: Boolean = false
}

@Singleton
class FileTransferService @Inject constructor(
    private val routing: FileBackendRouting,
    private val uploads: FileUploadInputs,
    private val fileLock: WorkspaceFileLock,
) {
    suspend fun transfer(
        execution: WorkspaceExecutionContext,
        source: String,
        target: String,
        cut: Boolean,
        confirmation: TransferConfirmation? = null,
        checkScope: () -> Unit,
    ): TransferResult {
        if (confirmation != null) {
            if (confirmation.source != source || confirmation.target != target || confirmation.cut != cut) {
                return TransferResult.Failed("Transfer confirmation does not match this operation")
            }
            return confirm(execution, confirmation, checkScope)
        }
        return runTransfer(execution, checkScope) { check, record ->
            val capture = routing.capture(execution, listOf(source, target), check)
            capture.guardTransfer(source, target)
            if (cut) capture.guardMutablePath(source)
            val from = capture.endpoint(source)
            val to = capture.endpoint(target)
            val checkPaths = bindPaths(capture, from, to, cut)
            val reader = backend(from, check)
            val destination = backend(to, check)
            val initial = reader.stat("") ?: throw IOException("Transfer source does not exist")
            val existing = destination.stat("")
            val same = if (from.identity == to.identity) from.provider else null
            if (same == null && (initial.kind == TransferKind.DIRECTORY || existing?.kind == TransferKind.DIRECTORY) && existing != null) {
                return@runTransfer TransferResult.DirectoryConflict(source, target)
            }
            if (same == null && initial.kind == TransferKind.LINK) {
                throw IOException("A top-level symbolic link cannot be transferred between backends")
            }
            val versionReader = if (same != null && !cut && initial.kind == TransferKind.LINK) backend(from, check, true) else reader
            val version = transferVersion(versionReader, check) ?: throw IOException("Transfer source disappeared")
            val targetVersion = transferVersion(destination, check)
            checkPaths(false)
            val plan = TransferPlan(execution, capture, source, target, cut, versionReader, destination, version, targetVersion, checkPaths, same)
            if (targetVersion != null) TransferResult.Conflict(TransferConfirmation(plan)) else execute(plan, check, record)
        }
    }

    suspend fun confirm(
        execution: WorkspaceExecutionContext,
        confirmation: TransferConfirmation,
        checkScope: () -> Unit,
    ): TransferResult {
        val plan = confirmation.plan
        execution.checkCurrent()
        plan.execution.checkCurrent()
        if (execution.fileCacheIdentity != plan.execution.fileCacheIdentity || execution.mode != plan.execution.mode) {
            throw CancellationException("Transfer confirmation execution changed")
        }
        return when (plan) {
            is TransferPlan -> runTransfer(plan.execution, checkScope) { check, record ->
                plan.capture.checkCurrent()
                plan.checkPaths(false)
                if (transferVersion(plan.reader, check) != plan.sourceVersion || transferVersion(plan.destination, check) != plan.targetVersion) {
                    throw IOException("Transfer source or target changed after confirmation was requested")
                }
                execute(plan, check, record)
            }
            is ChrootUploadPlan -> confirmUpload(execution, plan, checkScope)
        }
    }

    private suspend fun confirmUpload(
        execution: WorkspaceExecutionContext,
        plan: ChrootUploadPlan,
        checkScope: () -> Unit,
    ): TransferResult {
        val original = plan.execution
        if (execution.runtimeOwner != original.runtimeOwner ||
            execution.localRuntimeCapture?.digest != original.localRuntimeCapture?.digest ||
            execution.localRuntimeCapture?.instance != original.localRuntimeCapture?.instance ||
            execution.localRuntimeCapture?.configurationRevision != original.localRuntimeCapture?.configurationRevision) {
            throw CancellationException("Upload confirmation execution changed")
        }
        return runTransfer(original, { plan.request.checkOriginalScope(); checkScope() }) { check, _ ->
            val request = plan.request
            val capture = routing.capture(original, listOf(request.targetDirectory), check)
            val provider = capture.endpoint(request.targetDirectory).transferBackend as? ChrootFileAccess
                ?: throw CancellationException("Upload confirmation backend changed")
            provider.captureUploadDirectory(request.targetDirectory) { check(); capture.checkCurrent() }.use { directory ->
                if (directory.identity != request.directoryIdentity) throw IOException("Upload target directory changed after confirmation was requested")
                val checkBatch = { check(); capture.checkCurrent(); directory.checkCurrent() }
                val input = when (val selection = request.selection) {
                    is ChrootUploadSelection.File -> uploads.files(listOf(selection.uri), checkBatch).single()
                    is ChrootUploadSelection.Directory -> uploads.directory(selection.uri, checkBatch)
                }
                upload(original, capture, request.targetDirectory, input, checkBatch, request, plan)
            }
        }
    }

    suspend fun uploadFiles(
        execution: WorkspaceExecutionContext,
        targetDir: String,
        uris: List<Uri>,
        checkScope: () -> Unit,
    ): List<TransferResult> = withContext(Dispatchers.IO + execution) {
        val check = { WorkspaceExecutionContext.blockingContext()[Job]?.ensureActive(); execution.checkCurrent(); checkScope() }
        val results = ArrayList<TransferResult>()
        var directoryCleanupUnknown = false
        try {
            val capture = routing.capture(execution, listOf(targetDir), check)
            val targetDirectory = capture.endpoint(targetDir)
            val provider = targetDirectory.transferBackend
            if (provider is ChrootFileAccess) {
                val pending = ArrayList<TransferResult>()
                val directory = provider.captureUploadDirectory(targetDir) { check(); capture.checkCurrent() }
                var failure: Throwable? = null
                try {
                    val checkBatch = { check(); capture.checkCurrent(); directory.checkCurrent() }
                    val inputs = uploads.files(uris, checkBatch)
                    if (inputs.size != uris.size) throw IOException("Upload selection changed")
                    for ((uri, input) in uris.zip(inputs)) {
                        checkBatch()
                        val request = ChrootUploadRequest(targetDir, ChrootUploadSelection.File(uri), directory.identity, checkScope)
                        pending += upload(execution, capture, targetDir, input, checkBatch, request)
                    }
                } catch (problem: Throwable) {
                    failure = problem
                    throw problem
                } finally {
                    try { directory.close() } catch (cleanup: Throwable) {
                        directoryCleanupUnknown = true
                        val primary = failure
                        if (primary == null) throw cleanup
                        if (primary !== cleanup) primary.addSuppressed(cleanup)
                    }
                    if (!directoryCleanupUnknown) results += pending
                }
                return@withContext results
            }
            val identity = targetDirectory.resolvePath(true)
            val entry = targetDirectory.resolvePath(false)
            fun checkBatch() {
                check()
                capture.checkCurrent()
                if (targetDirectory.resolvePath(true) != identity || targetDirectory.resolvePath(false) != entry) {
                    throw IOException("Upload target directory changed")
                }
            }
            val inputs = uploads.files(uris, ::checkBatch)
            for (input in inputs) {
                checkBatch()
                results += upload(execution, capture, targetDir, input, ::checkBatch)
            }
            results
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: IOException) {
            results + TransferResult.Failed(if (directoryCleanupUnknown) {
                "Upload directory worker cleanup could not be confirmed; targets may already have been committed"
            } else failure.message ?: "Unable to upload files")
        } catch (failure: SecurityException) {
            results + TransferResult.Failed(if (directoryCleanupUnknown) {
                "Upload directory worker cleanup could not be confirmed; targets may already have been committed"
            } else failure.message ?: "Upload permission denied")
        }
    }

    suspend fun uploadDirectory(
        execution: WorkspaceExecutionContext,
        targetDir: String,
        treeUri: Uri,
        checkScope: () -> Unit,
    ): TransferResult = runTransfer(execution, checkScope) { check, _ ->
        val capture = routing.capture(execution, listOf(targetDir), check)
        val directory = capture.endpoint(targetDir)
        val provider = directory.transferBackend
        if (provider is ChrootFileAccess) {
            return@runTransfer provider.captureUploadDirectory(targetDir) { check(); capture.checkCurrent() }.use { validation ->
                val checkBatch = { check(); capture.checkCurrent(); validation.checkCurrent() }
                val request = ChrootUploadRequest(targetDir, ChrootUploadSelection.Directory(treeUri), validation.identity, checkScope)
                upload(execution, capture, targetDir, uploads.directory(treeUri, checkBatch), checkBatch, request)
            }
        }
        val identity = directory.resolvePath(true)
        val entry = directory.resolvePath(false)
        fun checkBatch() {
            check()
            capture.checkCurrent()
            if (directory.resolvePath(true) != identity || directory.resolvePath(false) != entry) throw IOException("Upload target directory changed")
        }
        upload(execution, capture, targetDir, uploads.directory(treeUri, ::checkBatch), ::checkBatch)
    }

    private suspend fun upload(
        execution: WorkspaceExecutionContext,
        capture: RouteCapture,
        targetDir: String,
        input: FileUploadInput,
        check: () -> Unit,
        request: ChrootUploadRequest? = null,
        confirmation: ChrootUploadPlan? = null,
    ): TransferResult {
        val target = targetDir.trimEnd('/') + "/" + input.name
        if (confirmation != null && (input.source != confirmation.source || input.name != confirmation.name ||
                input.physicalSource != confirmation.physicalSource || target != confirmation.target)) {
            throw IOException("Upload source selection changed after confirmation was requested")
        }
        val endpoint = capture.endpoint(target)
        capture.guardMutablePath(target)
        if (!endpoint.provider.isDirectory(targetDir)) throw IOException("Upload target directory does not exist")
        val targetEntry = endpoint.resolvePath(false)
        val targetReal = endpoint.resolvePath(true)
        if (confirmation != null && (targetEntry != confirmation.targetEntry || targetReal != confirmation.targetReal)) {
            throw IOException("Upload target path changed after confirmation was requested")
        }
        fun checkPaths(committed: Boolean) {
            check()
            capture.guardMutablePath(target)
            if (endpoint.resolvePath(false) != targetEntry || endpoint.resolvePath(true) != (if (committed) targetEntry else targetReal)) throw IOException("Upload target path changed")
            if (input.physicalSource != null && endpoint.physicalNamespace == "host") {
                val root = input.physicalSource.trimEnd('/')
                for (destination in listOf(targetEntry, targetReal)) {
                    val path = destination.trimEnd('/')
                    if (root == path || root.startsWith("$path/") || path.startsWith("$root/")) {
                        throw FilePathOverlapException("Upload source and destination overlap")
                    }
                }
            }
        }
        checkPaths(false)
        val destination = backend(endpoint, check)
        val sourceStat = input.reader.stat("") ?: throw IOException("Upload source is unavailable")
        val targetStat = destination.stat("")
        if (targetStat != null && (sourceStat.kind == TransferKind.DIRECTORY || targetStat.kind == TransferKind.DIRECTORY)) {
            return TransferResult.DirectoryConflict(input.source, target)
        }
        val version = transferVersion(input.reader, check) ?: throw IOException("Upload source disappeared")
        val targetVersion = transferVersion(destination, check)
        checkPaths(false)
        val plan = TransferPlan(execution, capture, input.source, target, false, input.reader, destination, version, targetVersion, ::checkPaths)
        if (confirmation != null) {
            if (version != confirmation.sourceVersion || targetVersion != confirmation.targetVersion) {
                throw IOException("Upload source or target changed after confirmation was requested")
            }
            return execute(plan, check)
        }
        if (targetVersion == null) return execute(plan, check)
        val ticket: TransferTicketPlan = if (request == null) plan else ChrootUploadPlan(execution, request, input.source, target,
            input.name, input.physicalSource, targetEntry, targetReal, version, targetVersion)
        return TransferResult.Conflict(TransferConfirmation(ticket))
    }

    private suspend fun execute(plan: TransferPlan, check: () -> Unit, record: (TransferProgress) -> Unit = {}): TransferResult {
        val targetKey = plan.capture.endpoint(plan.target).provider.toDisplayPath(plan.target)
        val sourceKey = if (plan.cut || plan.sameBackend != null) plan.capture.endpoint(plan.source).provider.toDisplayPath(plan.source) else null
        return fileLock.withLock(listOf(sourceKey, targetKey)) { executeLocked(plan, check, record) }
    }

    private fun executeLocked(plan: TransferPlan, check: () -> Unit, record: (TransferProgress) -> Unit): TransferResult {
        fun checkCurrent() { check(); plan.capture.checkCurrent() }
        if (plan.sameBackend != null) {
            checkCurrent()
            plan.checkPaths(false)
            if (transferVersion(plan.reader, ::checkCurrent) != plan.sourceVersion || transferVersion(plan.destination, ::checkCurrent) != plan.targetVersion) {
                throw IOException("Transfer source or target changed before commit")
            }
            checkCurrent()
            if (plan.cut) plan.sameBackend.move(plan.source, plan.target, plan.targetVersion != null)
            else plan.sameBackend.copy(plan.source, plan.target, plan.targetVersion != null)
            return TransferResult.Completed(plan.source, plan.target, plan.cut)
        }
        val progress = TransferProgress(plan.source, plan.target, plan.cut)
        record(progress)
        return TransferOperation(plan.reader, plan.destination, plan.source, plan.target, plan.cut, ::checkCurrent,
            { plan.checkPaths(false) }, progress, { plan.checkPaths(true) }).run(plan.sourceVersion, plan.targetVersion)
    }

    private suspend fun runTransfer(
        execution: WorkspaceExecutionContext,
        checkScope: () -> Unit,
        block: suspend (check: () -> Unit, record: (TransferProgress) -> Unit) -> TransferResult,
    ): TransferResult {
        var progress: TransferProgress? = null
        return try {
            withContext(Dispatchers.IO + execution) {
                currentCoroutineContext().ensureActive()
                block({ WorkspaceExecutionContext.blockingContext()[Job]?.ensureActive(); execution.checkCurrent(); checkScope() }) { progress = it }
            }
        } catch (failure: CancellationException) {
            if (failure is TransferCancellationException) throw failure
            val started = progress
            if (started != null && started.cut && started.committed) {
                throw TransferCancellationException(started.incomplete("The operation was cancelled after commit; source removal ${if (started.removed) "completed" else if (started.deleting) "may be partial" else "has not started"}"), failure)
            }
            throw failure
        } catch (failure: IOException) {
            TransferResult.Failed(failure.message ?: "File transfer failed")
        } catch (failure: SecurityException) {
            TransferResult.Failed(failure.message ?: "File transfer permission denied")
        }
    }

    private fun bindPaths(capture: RouteCapture, source: RoutedFileEndpoint, target: RoutedFileEndpoint, cut: Boolean): (Boolean) -> Unit {
        val sourceEntry = source.resolvePath(false)
        val sourceReal = followedIdentity(source)
        val targetEntry = target.resolvePath(false)
        val targetReal = followedIdentity(target)
        return { committed ->
            capture.checkCurrent()
            capture.guardTransfer(source.path, target.path)
            if (cut) capture.guardMutablePath(source.path)
            if (source.resolvePath(false) != sourceEntry || followedIdentity(source) != sourceReal ||
                target.resolvePath(false) != targetEntry || followedIdentity(target) != (if (committed) targetEntry else targetReal)
            ) throw IOException("Transfer physical path changed")
        }
    }

    private fun followedIdentity(endpoint: RoutedFileEndpoint): String = try {
        endpoint.resolvePath(true)
    } catch (_: LocalFolderContainmentException) {
        endpoint.resolvePath(false)
    } catch (_: java.nio.file.FileSystemLoopException) {
        endpoint.resolvePath(false)
    }

    private fun backend(endpoint: RoutedFileEndpoint, check: () -> Unit, followRoot: Boolean = false): TransferBackend = when (val provider = endpoint.transferBackend) {
        is LocalFileAccess -> HostTransferBackend({ relative, follow -> provider.transferFile(logicalChild(endpoint.path, relative), follow || (followRoot && relative.isEmpty())) }, check)
        is InternalLocalFolderAccess -> HostTransferBackend({ relative, follow -> provider.readFileForTransfer(logicalChild(endpoint.path, relative), follow || (followRoot && relative.isEmpty())) }, check)
        is RemoteSftpFileAccess -> provider.transferBackend(if (followRoot) endpoint.resolvePath(true) else endpoint.path, check)
        is ChrootFileAccess -> provider.transferBackend(if (followRoot) endpoint.resolvePath(true) else endpoint.path, check)
        else -> throw IOException("This backend does not support safe file transfers")
    }

    private fun logicalChild(path: String, relative: String): String = if (relative.isEmpty()) path else path.trimEnd('/') + "/" + transferRelative(relative)
}

internal class TransferProgress(val source: String, val target: String, val cut: Boolean) {
    var committed = false
    var deleting = false
    var removed = false

    fun incomplete(reason: String): TransferResult.CopiedButSourceNotRemoved = TransferResult.CopiedButSourceNotRemoved(
        source, target, "Copied '$source' to '$target', but source removal must be checked. $reason"
    )
}

internal class TransferOperation(
    private val source: TransferReader,
    private val target: TransferBackend,
    private val sourceLabel: String,
    private val targetLabel: String,
    private val cut: Boolean,
    private val check: () -> Unit,
    private val checkPaths: () -> Unit,
    private val progress: TransferProgress = TransferProgress(sourceLabel, targetLabel, cut),
    private val checkAfterCommitPaths: () -> Unit = checkPaths,
) {
    fun run(version: TransferVersion, expectedTarget: TransferVersion?): TransferResult {
        (target as? ChrootTransferBackend)?.validateSource(version)
        val stage = target.createStage(version.kind)
        var failure: Throwable? = null
        try {
            check()
            checkPaths()
            for (node in version.nodes) {
                check()
                if (source.stat(node.relative) != node.stat) throw IOException("Transfer source changed before copy")
                when (node.stat.kind) {
                    TransferKind.DIRECTORY -> if (node.relative.isNotEmpty()) stage.mkdir(node.relative)
                    TransferKind.LINK -> {
                        if (source.readLink(node.relative) != node.link) throw IOException("Transfer symbolic link changed")
                        stage.writeLink(node.relative, node.link ?: throw IOException("Missing symbolic link content"))
                    }
                    TransferKind.FILE -> stage.writeFile(node.relative) { output ->
                        val copied = source.openRead(node.relative).use { transferStream(it, output, node.bytes, check) }
                        if (copied.digest != node.digest) throw IOException("Transfer source digest changed during copy")
                    }
                }
            }
            val staged = transferVersion(stage, check) ?: throw IOException("Transfer staging content is unavailable")
            if (!version.sameContent(staged)) throw IOException("Transfer staging content verification failed")
            stage.commit(expectedTarget != null) {
                check()
                checkPaths()
                if (transferVersion(source, check) != version) throw IOException("Transfer source changed before commit")
                if (transferVersion(target, check) != expectedTarget) throw IOException("Transfer target changed before commit")
                check()
                checkPaths()
            }
            progress.committed = true
            check()
            checkAfterCommitPaths()
            val published = transferVersion(target, check) ?: throw IOException("Committed transfer target is unavailable")
            if (!version.sameContent(published)) throw IOException("Committed transfer target verification failed; source was preserved")
            if (!cut) return TransferResult.Completed(sourceLabel, targetLabel, false)
            val removable = source as? TransferBackend ?: throw IOException("Transfer source cannot be removed")
            if (transferVersion(source, check) != version) throw IOException("Copied source changed before removal")
            checkAfterCommitPaths()
            check()
            for (node in version.nodes.asReversed()) {
                check()
                checkAfterCommitPaths()
                val current = source.stat(node.relative) ?: throw IOException("Copied source disappeared during removal")
                if (node.stat.kind == TransferKind.DIRECTORY) {
                    if (current.kind != TransferKind.DIRECTORY || current.entryIdentity != node.stat.entryIdentity || source.list(node.relative).isNotEmpty()) {
                        throw IOException("Copied source directory changed during removal")
                    }
                } else {
                    if (current != node.stat) throw IOException("Copied source entry changed during removal")
                    if (node.stat.kind == TransferKind.LINK) {
                        if (source.readLink(node.relative) != node.link) throw IOException("Copied source link changed during removal")
                    } else {
                        val hash = source.openRead(node.relative).use { transferStream(it, null, node.bytes, check) }
                        if (hash.digest != node.digest) throw IOException("Copied source content changed during removal")
                    }
                }
                check()
                checkAfterCommitPaths()
                progress.deleting = true
                removable.deleteEntry(node.relative)
            }
            progress.removed = true
            return TransferResult.Completed(sourceLabel, targetLabel, true)
        } catch (cancelled: CancellationException) {
            progress.committed = progress.committed || stage.committed
            failure = cancelled
            if (progress.committed && cut && !progress.removed) {
                throw TransferCancellationException(progress.incomplete(if (progress.deleting) "Removal was cancelled after source deletion began; source may be partially removed" else "Operation was cancelled before source removal"), cancelled)
            }
            throw cancelled
        } catch (problem: Exception) {
            progress.committed = progress.committed || stage.committed
            failure = problem
            if (problem !is IOException && problem !is SecurityException) throw problem
            if (progress.committed && cut && !progress.removed) return progress.incomplete(problem.message ?: "Source removal failed")
            if (progress.committed) throw IOException("Target was committed, but verification failed: ${problem.message}", problem)
            throw problem
        } finally {
            try { stage.close() } catch (cleanup: Exception) {
                if (failure != null) failure.addSuppressed(cleanup)
                else if (progress.committed) throw IOException("Target was committed, but staging cleanup failed: ${cleanup.message}", cleanup)
                else throw cleanup
            }
        }
    }
}
