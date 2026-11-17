package com.aicode.feature.agent.domain.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootProcessFactoryTest {
    @Test
    fun scriptChecksAndroidUidBeforeExecutingCommand() {
        val script = HostRootProcessFactory.buildScript("printf done")
        assertTrue(script.startsWith("if [ \"\$(/system/bin/id -u)\" != '0' ]; then "))
        assertTrue(script.contains("exit 126; fi; exec /system/bin/sh -c 'printf done'"))
        assertTrue(script.indexOf("/system/bin/id -u") < script.indexOf("exec /system/bin/sh"))
    }

    @Test
    fun scriptQuotesEntireOriginalCommandWithoutInterpolatingItsContents() {
        val command = "printf 'a';\nprintf \"\$HOME\"; \$(command); `command` \\\nnext"
        val quoted = "'" + command.replace("'", "'\"'\"'") + "'"
        val script = HostRootProcessFactory.buildScript(command)
        assertEquals(quoted, script.substringAfter("exec /system/bin/sh -c "))
        assertEquals("''", HostRootProcessFactory.buildScript("").substringAfter("exec /system/bin/sh -c "))
    }

    @Test
    fun hostDestroyClosesAllFakeStreamsWithoutStartingAnyProcess() {
        val process = FakeRootProcess(output = "", alive = true)
        HostRootProcessFactory().destroy(process)
        assertTrue(process.destroyed)
        assertTrue(process.inputClosed)
        assertTrue(process.errorClosed)
        assertTrue(process.outputClosed)
    }
}
