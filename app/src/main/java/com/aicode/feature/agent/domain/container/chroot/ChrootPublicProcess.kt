package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCleanup
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

internal class ChrootPublicProcess(
    private val handle: ChrootLaunchHandle,
    scope: CoroutineScope
) : LocalRuntimeProcess() {
    override val instanceId: String get() = handle.instanceId
    override val launchId: String get() = handle.launchId
    private val task = MutableStateFlow(handle.taskState.value?.publicResult())
    private val cleanup = MutableStateFlow(handle.cleanupState.value?.publicResult())
    override val taskState = task.asStateFlow()
    override val cleanupState = cleanup.asStateFlow()

    init {
        scope.launch { task.value = handle.awaitTaskExit().publicResult() }
        scope.launch { cleanup.value = handle.awaitCleanup().publicResult() }
    }

    override suspend fun awaitTaskExit(): LocalRuntimeTaskResult = handle.awaitTaskExit().publicResult().also { task.value = it }
    override suspend fun awaitCleanup(): LocalRuntimeCleanup = handle.awaitCleanup().publicResult().also { cleanup.value = it }
    override fun resize(columns: Int, rows: Int): Boolean = handle.resize(columns, rows)
    override fun signal(signal: Int): Boolean = handle.signal(signal)
    override fun stop() = handle.stop()
    override fun getInputStream(): InputStream = handle.inputStream
    override fun getErrorStream(): InputStream = handle.errorStream
    override fun getOutputStream(): OutputStream = handle.outputStream
    override fun isAlive(): Boolean = handle.isAlive
    override fun waitFor(): Int = handle.waitFor().also { refreshTask() }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = handle.waitFor(timeout, unit).also { if (it) refreshTask() }
    override fun exitValue(): Int = handle.exitValue().also { refreshTask() }

    private fun refreshTask() { task.value = handle.taskState.value?.publicResult() }
    override fun destroy() = stop()
    override fun destroyForcibly(): Process { stop(); return this }

    private fun ChrootTaskResult.publicResult(): LocalRuntimeTaskResult = when (this) {
        ChrootTaskResult.Unknown -> LocalRuntimeTaskResult.Unknown
        is ChrootTaskResult.Exited -> LocalRuntimeTaskResult.Exited(exit.code, exit.signal)
    }

    private fun ChrootCleanupResult.publicResult(): LocalRuntimeCleanup = when (this) {
        ChrootCleanupResult.VERIFIED -> LocalRuntimeCleanup.VERIFIED
        ChrootCleanupResult.UNKNOWN -> LocalRuntimeCleanup.UNKNOWN
    }
}
