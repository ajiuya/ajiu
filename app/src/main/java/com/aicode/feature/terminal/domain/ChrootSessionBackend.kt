package com.aicode.feature.terminal.domain

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeProcess
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeTaskResult
import com.termux.terminal.SessionBackend
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ChrootSessionBackend(private val task: LocalRuntimeProcess) : SessionBackend {
    private val closed = AtomicBoolean()

    override fun getInputStream(): InputStream = task.inputStream

    override fun getOutputStream(): OutputStream = task.outputStream

    override fun resize(columns: Int, rows: Int) {
        if (!closed.get()) task.resize(columns, rows)
    }

    override fun waitForExit(): Int = waitForExitOutcome().status

    override fun waitForExitOutcome(): SessionBackend.ExitOutcome {
        task.waitFor()
        return when (val result = task.taskState.value) {
            is LocalRuntimeTaskResult.Exited -> SessionBackend.ExitOutcome.fromLegacyStatus(result.terminalStatus)
            LocalRuntimeTaskResult.Unknown, null -> SessionBackend.ExitOutcome.unknown()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true) && task.taskState.value == null) task.stop()
    }
}
