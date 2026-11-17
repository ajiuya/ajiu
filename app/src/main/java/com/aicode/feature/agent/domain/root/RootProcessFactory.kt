package com.aicode.feature.agent.domain.root

import android.os.Process as AndroidProcess
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface RootProcessFactory {
    fun create(command: String): Process
    fun destroy(process: Process)
}

internal class RootPermissionUnavailableException : IOException()

@Singleton
class HostRootProcessFactory @Inject constructor() : RootProcessFactory {
    override fun create(command: String): Process {
        if (AndroidProcess.myUid() == 0 || !File("/system/bin/id").canExecute() ||
            !File("/system/bin/sh").canExecute()
        ) {
            throw RootPermissionUnavailableException()
        }
        val su = SU_PATHS.firstOrNull { File(it).canExecute() }
            ?: throw RootPermissionUnavailableException()
        return ProcessBuilder(su, "-c", buildScript(command))
            .directory(File("/"))
            .redirectErrorStream(true)
            .start()
    }

    override fun destroy(process: Process) {
        runCatching { process.destroy() }
        runCatching { process.destroyForcibly() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    internal companion object {
        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su"
        )

        internal fun buildScript(command: String): String =
            "if [ \"\$(/system/bin/id -u)\" != '0' ]; then " +
                "printf '%s\\n' 'Root permission unavailable: Android UID 0 was not granted.' >&2; " +
                "exit 126; fi; exec /system/bin/sh -c ${shellQuote(command)}"

        private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
