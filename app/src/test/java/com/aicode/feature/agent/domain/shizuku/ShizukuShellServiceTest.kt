package com.aicode.feature.agent.domain.shizuku

import android.os.Bundle
import android.os.Process
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class ShizukuShellServiceTest {
    @Before
    fun setUp() {
        mockkStatic(Process::class)
        mockkConstructor(Bundle::class)
        every { anyConstructed<Bundle>().putString(any(), any()) } just Runs
        every { anyConstructed<Bundle>().putInt(any(), any()) } just Runs
        mockkConstructor(ProcessBuilder::class)
        every { anyConstructed<ProcessBuilder>().start() } throws AssertionError("Real processes are forbidden in tests")
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun rootUnknownAndApplicationUidsReturnBeforeCreatingAProcess() {
        val service = ShizukuShellService()
        for (uid in listOf(0, -1, 10000, 1000)) {
            every { Process.myUid() } returns uid
            service.exec("never-execute", 1_000)
        }
        verify(exactly = 4) {
            anyConstructed<Bundle>().putInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_SHELL_REQUIRED)
        }
        verify(exactly = 0) { anyConstructed<ProcessBuilder>().start() }
    }
}
