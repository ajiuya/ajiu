package com.aicode.feature.plugin.runtime

import android.system.Os
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PluginInvocationContext internal constructor(
    val capture: PluginCapture,
    val execution: WorkspaceExecutionContext?,
    val workspacePath: String,
    val sessionId: String?,
    internal val dataDirectory: File,
    private val sourceChanges: Flow<Unit>,
    internal val allowsTool: (String) -> Boolean = { true },
    val sourceIdentity: Any = MISSING_SOURCE,
    val background: Boolean = false,
    val persistentIdentity: String = "",
    val allowIdleCallbacks: Boolean = false,
    internal val backgroundAuthority: Any? = null,
    val runtimeOwner: RuntimeOwner? = null,
    private val invocationAuthority: Any = Any(),
    private val sourceCheck: () -> Unit
) : AutoCloseable {
    val invocationId: String = UUID.randomUUID().toString()
    private val closed = MutableStateFlow(false)
    val changes: Flow<Unit> = merge(sourceChanges, closed.map { Unit })
    override fun close() { closed.value = true }

    internal fun retainSource(): PluginInvocationContext {
        checkCurrent()
        return PluginInvocationContext(capture, execution, workspacePath, sessionId, dataDirectory,
            sourceChanges, allowsTool, sourceIdentity, background, persistentIdentity, allowIdleCallbacks, backgroundAuthority, runtimeOwner, invocationAuthority, sourceCheck)
    }

    internal fun child(): PluginInvocationContext = PluginInvocationContext(capture, execution, workspacePath, sessionId,
        dataDirectory, changes, allowsTool, sourceIdentity, background, persistentIdentity, allowIdleCallbacks, backgroundAuthority,
        runtimeOwner, invocationAuthority, ::checkCurrent)

    private val dataIdentity = directoryIdentity()

    private fun directoryIdentity(): String {
        val path = dataDirectory.toPath()
        PluginFiles.checkDirectoryChain(path)
        return Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
            ?: Os.lstat(dataDirectory.absolutePath).let { "${it.st_dev}:${it.st_ino}" }
    }

    fun checkCurrent() {
        if (closed.value) throw CancellationException("Plugin invocation is closed")
        capture.checkActive()
        execution?.checkCurrent()
        execution?.localFolderSnapshot?.checkCurrent()
        sourceCheck()
        capture.checkActive()
    }

    fun checkSourceCurrent() = checkCurrent()

    internal fun checkIntegrity() {
        checkCurrent()
        capture.checkCurrent()
        val currentIdentity = try { directoryIdentity() } catch (failure: java.io.IOException) {
            throw CancellationException("Plugin data directory is unavailable", failure)
        } catch (failure: android.system.ErrnoException) {
            throw CancellationException("Plugin data directory is unavailable", failure)
        }
        if (currentIdentity != dataIdentity) throw CancellationException("Plugin data directory changed")
        checkCurrent()
    }

    internal fun sessionApprovalScope(tool: String, arguments: JsonObject, policyIdentity: Any,
        sessionArguments: JsonElement = arguments): PluginSessionApprovalScope? {
        checkIntegrity()
        if (sourceIdentity === MISSING_SOURCE || capture.cleanup || tool in setOf("Root", "submitPlan", "planMode", "askUserQuestion")) return null
        val target = pluginSessionOperationFingerprint(tool, arguments, sessionArguments)
        val chatSession = sessionId?.takeIf { it.isNotBlank() }
        return PluginSessionApprovalScope(capture.id, capture.installRevision, capture.contentHash, capture.activationEpoch,
            chatSession, invocationAuthority.takeIf { chatSession == null },
            listOf(sourceIdentity, dataIdentity, execution?.localRuntimeCapture?.digest), target, policyIdentity)
    }

    internal fun approvalDetails(action: String): String {
        val text = listOfNotNull("${capture.id} @ ${capture.manifest.version}", execution?.mode?.name,
            workspacePath.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it).toString() },
            sessionId?.let { JsonPrimitive(it).toString() }, action).joinToString("\n")
        val details = buildString {
            for (ch in text) {
                if (Character.getType(ch) == Character.FORMAT.toInt() || ch == '\u2028' || ch == '\u2029') {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else append(ch)
            }
        }
        if (details.length > PluginApprovalCoordinator.MAX_DETAILS) {
            throw PluginHostException("APPROVAL_TOO_LARGE", "Approval details cannot be displayed in full")
        }
        return details
    }

    private val runtimeAuthority by lazy {
        val owner = runtimeOwner ?: throw PluginHostException("CONTEXT_REQUIRED", "A plugin runtime source owner is required")
        if (sourceIdentity === MISSING_SOURCE) throw PluginHostException("CONTEXT_REQUIRED", "A plugin execution source is required")
        val authority = PluginRuntimeContext(workspacePath, sessionId, invocationId, ::checkCurrent)
            .withExecutionSource(sourceIdentity, owner, execution?.localRuntimeCapture, execution?.profileSnapshot?.activeProfile?.takeIf {
                it.mode == com.aicode.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT
            } ?: execution?.localMapping?.profile)
        val bound = authority.withBackgroundAuthority(backgroundAuthority ?: invocationAuthority)
        if (allowIdleCallbacks) bound.withIdleCallbacks() else bound
    }
    internal fun runtimeContext(): PluginRuntimeContext = runtimeAuthority.also { checkCurrent() }

    private companion object {
        val MISSING_SOURCE = Any()
    }

    internal suspend fun <T> whileCurrent(block: suspend () -> T): T = coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) { checkIntegrity(); block().also { checkIntegrity() } }
        val revocation = launch(start = CoroutineStart.UNDISPATCHED) {
            changes.collect {
                try { checkCurrent() } catch (stale: Exception) {
                    operation.cancel(CancellationException("Plugin authority was revoked", stale))
                }
            }
        }
        try { operation.await() } finally { revocation.cancelAndJoin() }
    }
}

class PluginHostException(val code: String, message: String) : Exception(message)
