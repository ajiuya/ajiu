package com.aicode.feature.agent.domain.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.aicode.R
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import rikka.shizuku.Shizuku

class ShizukuManagerTest {
    private var uid = 2000
    private var uidUnknown = false
    private var granted = true
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        mockkStatic(Shizuku::class)
        every { Shizuku.addBinderReceivedListenerSticky(any()) } just Runs
        every { Shizuku.addBinderDeadListener(any()) } just Runs
        every { Shizuku.addRequestPermissionResultListener(any()) } just Runs
        every { Shizuku.pingBinder() } returns true
        every { Shizuku.isPreV11() } returns false
        every { Shizuku.getUid() } answers {
            if (uidUnknown) throw IllegalStateException("unknown uid")
            uid
        }
        every { Shizuku.checkSelfPermission() } answers {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        every { context.getString(R.string.shizuku_shell_required) } returns "ADB shell required"
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun onlyUid2000CanBecomeReady() {
        val manager = ShizukuManager(context)
        assertEquals(ShizukuState.READY, manager.state.value)
        for (invalid in listOf(0, -1, 10000, 1000)) {
            uid = invalid
            manager.refreshState()
            assertEquals(ShizukuState.SHELL_REQUIRED, manager.state.value)
        }
        uid = 2000
        granted = false
        manager.refreshState()
        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
    }

    @Test
    fun unknownUidFailsClosedEvenIfPermissionWasGranted() {
        uidUnknown = true
        val manager = ShizukuManager(context)
        assertEquals(ShizukuState.SHELL_REQUIRED, manager.state.value)
    }

    @Test
    fun cachedServiceCannotBeReusedAfterUidSwitchesToRootOrUnknown() = runTest {
        val manager = ShizukuManager(context)
        val service = mockk<IShizukuShellService>()
        for (invalid in listOf(0, -1)) {
            ShizukuManager::class.java.getDeclaredField("shellService").apply {
                isAccessible = true
                set(manager, service)
            }
            uid = invalid
            try {
                manager.runCommand("never-execute", 1_000)
                throw AssertionError("Invalid identity must be rejected")
            } catch (e: IllegalStateException) {
                assertEquals("ADB shell required", e.message)
            }
        }
        verify(exactly = 0) { service.exec(any(), any()) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun uidIsRecheckedImmediatelyBeforeCachedServiceExec() = runTest {
        val manager = ShizukuManager(context)
        val service = mockk<IShizukuShellService>()
        ShizukuManager::class.java.getDeclaredField("shellService").apply {
            isAccessible = true
            set(manager, service)
        }
        var checks = 0
        every { Shizuku.getUid() } answers { if (++checks == 1) 2000 else 0 }
        try {
            manager.runCommand("never-execute", 1_000)
            throw AssertionError("Changed identity must be rejected")
        } catch (e: IllegalStateException) {
            assertEquals("ADB shell required", e.message)
        }
        verify(exactly = 0) { service.exec(any(), any()) }
    }

    @Test
    fun onlyFakeBinderExecutesAndServiceIdentityDenialIsLocalized() = runTest {
        val manager = ShizukuManager(context)
        val bundle = mockk<Bundle>()
        every { bundle.getInt(ShizukuShellService.KEY_EXIT_CODE) } returns ShizukuShellService.EXIT_SHELL_REQUIRED
        val service = mockk<IShizukuShellService> {
            every { exec("never-execute", 1_000) } returns bundle
        }
        ShizukuManager::class.java.getDeclaredField("shellService").apply {
            isAccessible = true
            set(manager, service)
        }
        try {
            manager.runCommand("never-execute", 1_000)
            throw AssertionError("Service must reject non-shell identity")
        } catch (e: IllegalStateException) {
            assertEquals("ADB shell required", e.message)
        }
        assertEquals(ShizukuState.SHELL_REQUIRED, manager.state.value)
        verify(exactly = 1) { service.exec("never-execute", 1_000) }
    }
}
