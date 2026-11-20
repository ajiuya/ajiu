package com.aicode.feature.agent.domain.shizuku

import android.os.Bundle
import android.os.Process
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class ShizukuShellServiceTest {
    @Before
    fun setUp() {
        mockkStatic(Process::class)
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putString(any(), any()) } just Runs
        every { anyConstructed<Bundle>().putInt(any(), any()) } just Runs
        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().redirectErrorStream(true) } answers { self as ProcessBuilder }
        every { anyConstructed<ProcessBuilder>().start() } throws AssertionError("Real processes are forbidden in tests")
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun unknownAndApplicationUidsReturnBeforeCreatingAProcess() {
        val service = ShizukuShellService()
        for (uid in listOf(-1, 10000, 1000)) {
            every { Process.myUid() } returns uid
            for (expectedUid in listOf(0, 2000, uid)) {
                service.exec("never-execute", 1_000, expectedUid)
            }
        }
        verify(exactly = 9) {
            anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_SHELL_REQUIRED)
        }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().redirectErrorStream(any()) }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
    }

    @Test
    fun rootProcessRejectsShellExpectedUidBeforeCreatingAProcess() {
        every { Process.myUid() } returns 0

        ShizukuShellService().exec("never-execute", 1_000, 2000)

        verify(exactly = 1) {
            anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_SHELL_REQUIRED)
        }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().redirectErrorStream(any()) }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
    }

    @Test
    fun shellProcessRejectsRootExpectedUidBeforeCreatingAProcess() {
        every { Process.myUid() } returns 2000

        ShizukuShellService().exec("never-execute", 1_000, 0)

        verify(exactly = 1) {
            anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_SHELL_REQUIRED)
        }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().redirectErrorStream(any()) }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
    }

    @Test
    fun permittedProcessUidsRejectUnsupportedExpectedUids() {
        val service = ShizukuShellService()
        for (uid in listOf(0, 2000)) {
            every { Process.myUid() } returns uid
            for (expectedUid in listOf(-1, 1000, 10000)) {
                service.exec("never-execute", 1_000, expectedUid)
            }
        }
        verify(exactly = 6) {
            anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_SHELL_REQUIRED)
        }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
    }

    @Test
    fun matchingRootUidExecutesOnlyFakeProcessAndReturnsItsOutput() {
        every { Process.myUid() } returns 0
        val process = fakeProcess()

        ShizukuShellService().exec("fake-command", 1_000, 0)

        verify(exactly = 1) { anyConstructed<ProcessBuilder>().start() }
        verify(exactly = 1) { process.waitFor(1_000L, TimeUnit.MILLISECONDS) }
        verify(exactly = 1) { anyConstructed<Bundle>().putString(ShizukuShellService.KEY_OUTPUT, "fake output\n") }
        verify(exactly = 1) { anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, 0) }
        verify(exactly = 1) { process.destroy() }
    }

    @Test
    fun matchingShellUidExecutesOnlyFakeProcessAndReturnsItsOutput() {
        every { Process.myUid() } returns 2000
        val process = fakeProcess()

        ShizukuShellService().exec("fake-command", 1_000, 2000)

        verify(exactly = 1) { anyConstructed<ProcessBuilder>().start() }
        verify(exactly = 1) { anyConstructed<Bundle>().putString(ShizukuShellService.KEY_OUTPUT, "fake output\n") }
        verify(exactly = 1) { anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, 0) }
        verify(exactly = 1) { process.destroy() }
    }

    @Test
    fun timeoutStillDestroysOnlyTheFakeProcessAndKeepsTimeoutResult() {
        every { Process.myUid() } returns 0
        val process = fakeProcess(finished = false)

        ShizukuShellService().exec("fake-command", 1_000, 0)

        verify(exactly = 1) { process.destroyForcibly() }
        verify(exactly = 1) { anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_TIMEOUT) }
        verify(exactly = 0) { process.exitValue() }
        verify(exactly = 1) { process.destroy() }
    }

    private fun fakeProcess(finished: Boolean = true): java.lang.Process {
        val process = mockk<java.lang.Process>()
        every { process.inputStream } returns ByteArrayInputStream("fake output\n".toByteArray())
        every { process.waitFor(any<Long>(), TimeUnit.MILLISECONDS) } returns finished
        every { process.exitValue() } returns 0
        every { process.destroyForcibly() } returns process
        every { process.destroy() } just Runs
        every { anyConstructed<ProcessBuilder>().start() } returns process
        return process
    }
}
