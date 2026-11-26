package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

class WorkspaceExecutionContext internal constructor(
    val mode: ExecutionMode,
    val fileCacheIdentity: String,
    internal val fileAccess: FileAccessProvider,
    internal val localMapping: WorkspacePathMapper.ExecutionMapping?,
    internal val remoteSource: RemoteSftpFileAccess.ExecutionSource?,
    val localFolderSnapshot: LocalFolderSnapshot = LocalFolderSnapshot.EMPTY,
    val runtimeOwner: RuntimeOwner? = null,
    val localRuntimeCapture: LocalRuntimeCapture? = null,
    internal val profileSnapshot: ContainerProfileSnapshot? = null,
    internal val runtimeWorkspaceSource: WorkspacePathMapper.WorkspaceSource? = null,
    private val cancellationJob: Job? = null,
    private val checkSource: () -> Unit
) : ThreadContextElement<CoroutineContext?>, AbstractCoroutineContextElement(Key) {

    init {
        require(localRuntimeCapture == null || localRuntimeCapture.owner == runtimeOwner)
    }

    fun checkCurrent() {
        checkWorkspaceSource()
        localRuntimeCapture?.checkCurrent()
    }

    internal fun checkStableSource() = checkSource()

    internal fun checkWorkspaceSource() {
        cancellationJob?.takeIf { it.isCancelled }?.ensureActive()
        checkStableSource()
    }

    internal fun ownedBlockingContext(owner: Job): CoroutineContext {
        val context = installed.get()
        val execution = context?.get(Key)
        if (execution != null && execution !== this) throw CancellationException("Workspace execution capture changed")
        val job = context?.get(BlockingOwner)?.job ?: owner
        return (context ?: EmptyCoroutineContext).minusKey(ContinuationInterceptor) + this + job + BlockingOwner(job)
    }

    internal fun <T> useCaptured(block: () -> T): T {
        checkCurrent()
        val current = current()
        if (current != null && current !== this) throw CancellationException("Workspace execution capture changed")
        if (current === this) return block().also { checkCurrent() }
        val context = installed.get() ?: cancellationJob?.let { this + it + BlockingOwner(it) } ?: this
        val previous = updateThreadContext(context)
        return try { block().also { checkCurrent() } } finally { restoreThreadContext(context, previous) }
    }

    @OptIn(InternalCoroutinesApi::class)
    internal fun onCancellation(close: () -> Unit): DisposableHandle? =
        (operationJob() ?: cancellationJob)?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) runCatching(close)
        }

    internal fun protectRead(input: InputStream): InputStream {
        val job = operationJob() ?: cancellationJob
        val check = { job?.ensureActive(); checkCurrent() }
        val checked = identityCheckedRead(input, check)
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        val cancellation = onCancellation {
            if (closed.compareAndSet(false, true)) checked.close()
        }
        return object : FilterInputStream(checked) {
            private fun requireOpen() {
                job?.ensureActive()
                if (closed.get()) throw java.io.IOException("Captured file stream is closed")
            }

            override fun read(): Int = try {
                requireOpen()
                check()
                super.read().also { if (it < 0) close() }
            } catch (failure: Throwable) { close(); throw failure }

            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                requireOpen()
                check()
                checked.read(b, off, len).also { if (it < 0) close() }
            } catch (failure: Throwable) { close(); throw failure }

            override fun close() {
                cancellation?.dispose()
                if (closed.compareAndSet(false, true)) checked.close()
            }
        }
    }

    internal fun requireMode(expected: ExecutionMode) {
        checkCurrent()
        if (mode != expected) throw CancellationException("Workspace execution backend changed")
    }

    override fun updateThreadContext(context: CoroutineContext): CoroutineContext? =
        installed.get().also { installed.set(context + this) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: CoroutineContext?) {
        if (oldState == null) installed.remove() else installed.set(oldState)
    }

    companion object Key : CoroutineContext.Key<WorkspaceExecutionContext> {
        private val installed = ThreadLocal<CoroutineContext?>()

        internal fun current(): WorkspaceExecutionContext? = installed.get()?.get(Key)

        private fun operationJob(): Job? = installed.get()?.let { it[BlockingOwner]?.job ?: it[Job] }

        // Keep the tool job for returned streams; the runBlocking child completes before those streams are read.
        internal fun blockingContext(): CoroutineContext {
            val context = installed.get() ?: return EmptyCoroutineContext
            val execution = context[Key] ?: return EmptyCoroutineContext
            val blocking = context.minusKey(ContinuationInterceptor)
            val job = operationJob() ?: return blocking
            return blocking + execution + job + BlockingOwner(job)
        }
    }

    private class BlockingOwner(val job: Job) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<BlockingOwner>
    }
}

@Singleton
class WorkspaceExecutionContextFactory @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteFileAccess: RemoteSftpFileAccess,
    private val containerSettings: ContainerSettingsRepository,
    private val localFolders: LocalFolderGrantRepository,
    private val runtimeCommands: LocalRuntimeCommands? = null
) {
    suspend fun freeze(
        projectCapture: ProjectMemoryCapture? = null,
        runtimeOwner: RuntimeOwner? = null,
        checkSource: (() -> Unit)? = null
    ): WorkspaceExecutionContext {
        projectCapture?.assertCurrent()
        val inherited = WorkspaceExecutionContext.current()
        if (inherited != null) {
            if ((runtimeOwner == null || inherited.runtimeOwner == runtimeOwner) && checkSource == null) {
                inherited.checkCurrent()
                checkProject(projectCapture, inherited)
                return inherited
            }
            inherited.checkWorkspaceSource()
            checkProject(projectCapture, inherited)
            if (inherited.profileSnapshot == null) {
                throw CancellationException("Workspace source cannot be recaptured for another owner")
            }
        } else {
            localFolders.awaitReady()
        }
        val roots = inherited?.localFolderSnapshot ?: localFolders.snapshot()
        val profile = inherited?.profileSnapshot ?: containerSettings.captureProfileSnapshot()
        val mode = inherited?.mode ?: modeHolder.currentMode()
        val modeRevision = modeHolder.modeSelectionRevision
        val owner = runtimeOwner ?: inherited?.runtimeOwner
        if (mode == ExecutionMode.LOCAL_PROOT && profile.activeProfile.id != profile.selectedProfileId) {
            throw CancellationException("Workspace execution source is not ready")
        }
        val chroot = mode == ExecutionMode.LOCAL_PROOT && profile.activeProfile.isChroot
        if (chroot && owner == null) throw LocalRuntimeAuthorizationRequired()
        val local = inherited?.localMapping
            ?: if (mode == ExecutionMode.LOCAL_PROOT && !chroot) localFileAccess.captureExecutionMapping(profile) else null
        val workspace = inherited?.runtimeWorkspaceSource
            ?: if (chroot) localFileAccess.captureWorkspaceSource() else null
        val remote = inherited?.remoteSource
            ?: if (mode == ExecutionMode.REMOTE_SSH) remoteFileAccess.captureExecutionSource() else null
        fun verifySource() {
            inherited?.checkStableSource()
            checkSource?.invoke()
            if (modeHolder.currentMode() != mode || modeHolder.modeSelectionRevision != modeRevision) {
                throw CancellationException("Workspace execution mode changed")
            }
            containerSettings.checkProfileSnapshot(profile)
            if (local != null) localFileAccess.checkExecutionMapping(local)
            if (workspace != null) localFileAccess.checkWorkspaceSource(workspace)
            if (remote != null) remoteFileAccess.checkExecutionSource(remote)
        }
        verifySource()
        val runtime = if (chroot) {
            val commands = runtimeCommands ?: throw LocalRuntimeAuthorizationRequired()
            val sourceFingerprint = listOf(
                mode.name, modeRevision.toString(), profile.selectedProfileId, profile.selectionRevision.toString(),
                profile.configurationRevision.toString(), workspace!!.sourcePath, workspace.workspaceRoot.path,
                workspace.selection?.id.orEmpty()
            ).joinToString("") { "${it.length}:$it" }
            commands.capture(
                profile.activeProfile, profile.configurationRevision, workspace.workspaceRoot.path,
                requireNotNull(owner), sourceFingerprint, ::verifySource
            )
        } else null
        val backend: FileAccessProvider = when {
            runtime != null -> requireNotNull(runtimeCommands).fileAccess(runtime)
            mode == ExecutionMode.REMOTE_SSH -> remoteFileAccess
            else -> localFileAccess
        }
        val identity = when {
            runtime != null -> backend.cacheIdentity()
            inherited != null -> inherited.fileCacheIdentity
            else -> fileAccess.cacheIdentity()
        }
        if (identity != backend.cacheIdentity() || (local != null && local.profile != profile.activeProfile)) {
            throw CancellationException("Workspace execution source is not ready")
        }
        val execution = WorkspaceExecutionContext(
            mode, identity, backend, local, remote, roots, owner, runtime, profile, workspace,
            WorkspaceExecutionContext.blockingContext()[Job] ?: currentCoroutineContext()[Job], ::verifySource
        )
        checkProject(projectCapture, execution)
        projectCapture?.assertCurrent()
        execution.checkCurrent()
        return execution
    }

    suspend fun freezeForPaths(
        paths: List<String>,
        projectCapture: ProjectMemoryCapture? = null,
        runtimeOwner: RuntimeOwner? = null,
        checkSource: (() -> Unit)? = null
    ): WorkspaceExecutionContext {
        require(paths.isNotEmpty())
        if (paths.any { !isLocalFolderPath(it) }) return freeze(projectCapture, runtimeOwner, checkSource)
        projectCapture?.assertCurrent()
        val inherited = WorkspaceExecutionContext.current()
        inherited?.checkWorkspaceSource()
        localFolders.awaitReady()
        val roots = inherited?.localFolderSnapshot ?: localFolders.snapshot()
        paths.forEach { roots.rootFor(it) }
        val profile = inherited?.profileSnapshot ?: containerSettings.captureProfileSnapshot()
        val mode = inherited?.mode ?: modeHolder.currentMode()
        val modeRevision = modeHolder.modeSelectionRevision
        fun verifySource() {
            inherited?.checkStableSource()
            checkSource?.invoke()
            if (modeHolder.currentMode() != mode || modeHolder.modeSelectionRevision != modeRevision) {
                throw CancellationException("Local folder execution mode changed")
            }
            containerSettings.checkProfileSnapshot(profile)
            roots.checkCurrent()
        }
        verifySource()
        val host = InternalLocalFolderAccess(roots, checkScope = ::verifySource)
        return WorkspaceExecutionContext(
            mode, host.cacheIdentity(), host, null, null, roots, runtimeOwner ?: inherited?.runtimeOwner,
            profileSnapshot = profile,
            cancellationJob = WorkspaceExecutionContext.blockingContext()[Job] ?: currentCoroutineContext()[Job],
            checkSource = ::verifySource
        ).also {
            projectCapture?.assertCurrent()
            it.checkCurrent()
        }
    }

    private fun checkProject(capture: ProjectMemoryCapture?, execution: WorkspaceExecutionContext) {
        if (capture == null) return
        val identity = capture.identity
        val sameMode = identity.mode.equals(execution.mode.name, ignoreCase = true)
        val sameProfile = execution.localRuntimeCapture?.let { it.profileId == identity.profileId }
            ?: execution.localMapping?.let { it.profile.id == identity.profileId } ?: true
        val sameGeneration = execution.remoteSource?.let { it.identity.generation.toString() == capture.channelGeneration } ?: true
        val sameRoot = execution.localRuntimeCapture?.let { File(identity.projectRoot).canonicalPath == it.workspacePath }
            ?: execution.localMapping?.let { File(identity.projectRoot).canonicalFile == it.workspaceRoot }
            ?: execution.remoteSource?.let {
                ProjectMemoryIdentityResolver.normalizeRoot(identity.projectRoot) ==
                    ProjectMemoryIdentityResolver.normalizeRoot(it.workspaceRoot)
            } ?: false
        if (!sameMode || !sameProfile || !sameGeneration || !sameRoot) {
            throw CancellationException("Project execution capture changed")
        }
    }
}
