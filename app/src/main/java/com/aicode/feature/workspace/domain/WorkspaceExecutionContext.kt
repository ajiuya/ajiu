package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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
    private val checkSource: () -> Unit
) : ThreadContextElement<CoroutineContext?>, AbstractCoroutineContextElement(Key) {

    fun checkCurrent() {
        checkSource()
    }

    internal fun ownedBlockingContext(owner: Job): CoroutineContext = this + owner + BlockingOwner(owner)

    @OptIn(InternalCoroutinesApi::class)
    internal fun onCancellation(close: () -> Unit): DisposableHandle? =
        operationJob()?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause is CancellationException) runCatching(close)
        }

    internal fun protectRead(input: InputStream): InputStream {
        val job = operationJob()
        val checked = identityCheckedRead(input) { job?.ensureActive(); checkCurrent() }
        val cancellation = onCancellation { checked.close() }
        return object : FilterInputStream(checked) {
            override fun read(): Int = try {
                super.read().also { if (it < 0) close() }
            } catch (failure: Throwable) { close(); throw failure }

            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                checked.read(b, off, len).also { if (it < 0) close() }
            } catch (failure: Throwable) { close(); throw failure }

            override fun close() {
                cancellation?.dispose()
                checked.close()
            }
        }
    }

    internal fun requireMode(expected: ExecutionMode) {
        checkCurrent()
        if (mode != expected) throw CancellationException("Workspace execution backend changed")
    }

    override fun updateThreadContext(context: CoroutineContext): CoroutineContext? =
        installed.get().also { installed.set(context) }

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
            val job = operationJob() ?: return execution
            return execution + job + BlockingOwner(job)
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
    private val containerSettings: ContainerSettingsRepository
) {
    suspend fun freeze(projectCapture: ProjectMemoryCapture? = null): WorkspaceExecutionContext {
        projectCapture?.assertCurrent()
        WorkspaceExecutionContext.current()?.let { current ->
            current.checkCurrent()
            checkProject(projectCapture, current)
            return current
        }
        val mode = modeHolder.currentMode()
        val profileId = if (mode == ExecutionMode.LOCAL_PROOT) containerSettings.activeProfileIdFlow.first() else null
        val local = if (mode == ExecutionMode.LOCAL_PROOT) localFileAccess.captureExecutionMapping() else null
        val remote = if (mode == ExecutionMode.REMOTE_SSH) remoteFileAccess.captureExecutionSource() else null
        val backend: FileAccessProvider = if (mode == ExecutionMode.REMOTE_SSH) remoteFileAccess else localFileAccess
        val identity = fileAccess.cacheIdentity()
        if (identity != backend.cacheIdentity() || (local != null && local.profile.id != profileId)) {
            throw CancellationException("Workspace execution source is not ready")
        }
        val execution = WorkspaceExecutionContext(mode, identity, backend, local, remote) {
            if (modeHolder.currentMode() != mode) throw CancellationException("Workspace execution mode changed")
            if (local != null && localFileAccess.captureExecutionMapping() != local) {
                throw CancellationException("Local workspace execution mapping changed")
            }
            if (remote != null) remoteFileAccess.checkExecutionSource(remote)
        }
        checkProject(projectCapture, execution)
        projectCapture?.assertCurrent()
        execution.checkCurrent()
        return execution
    }

    private fun checkProject(capture: ProjectMemoryCapture?, execution: WorkspaceExecutionContext) {
        if (capture == null) return
        val identity = capture.identity
        val sameMode = identity.mode.equals(execution.mode.name, ignoreCase = true)
        val sameProfile = execution.localMapping?.let { it.profile.id == identity.profileId } ?: true
        val sameGeneration = execution.remoteSource?.let { it.identity.generation.toString() == capture.channelGeneration } ?: true
        val sameRoot = execution.localMapping?.let { File(identity.projectRoot).canonicalFile == it.workspaceRoot }
            ?: execution.remoteSource?.let {
                ProjectMemoryIdentityResolver.normalizeRoot(identity.projectRoot) ==
                    ProjectMemoryIdentityResolver.normalizeRoot(it.workspaceRoot)
            } ?: false
        if (!sameMode || !sameProfile || !sameGeneration || !sameRoot) {
            throw CancellationException("Project execution capture changed")
        }
    }
}
