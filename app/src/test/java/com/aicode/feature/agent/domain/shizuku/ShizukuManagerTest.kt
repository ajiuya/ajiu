package com.aicode.feature.agent.domain.shizuku

import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import com.aicode.R
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import moe.shizuku.server.IShizukuService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import rikka.shizuku.Shizuku
import java.util.UUID

class ShizukuManagerTest {
    private var uid = 2000
    private var uidUnknown = false
    private var granted = true
    private var serverGranted = true
    private var binderAlive = true
    private var preV11 = false
    private var installed = true
    private var activeBinder: IBinder? = mockk(relaxed = true)
    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()
    private val remoteService = mockk<IShizukuService>()
    private val binderReceivedListener = slot<Shizuku.OnBinderReceivedListener>()
    private val binderDeadListener = slot<Shizuku.OnBinderDeadListener>()
    private val permissionResultListener = slot<Shizuku.OnRequestPermissionResultListener>()

    @Before
    fun setUp() {
        mockkStatic(Shizuku::class)
        mockkStatic(IShizukuShellService.Stub::class)
        mockkStatic(IShizukuService.Stub::class)
        every { IShizukuService.Stub.asInterface(any()) } returns remoteService
        every { remoteService.checkSelfPermission() } answers { serverGranted }
        every { Shizuku.getUid() } throws AssertionError("Cached SDK UID must not authorize commands")
        every { Shizuku.addBinderReceivedListenerSticky(capture(binderReceivedListener)) } just Runs
        every { Shizuku.addBinderDeadListener(capture(binderDeadListener)) } just Runs
        every { Shizuku.addRequestPermissionResultListener(capture(permissionResultListener)) } just Runs
        every { Shizuku.requestPermission(any()) } just Runs
        every { Shizuku.bindUserService(any(), any()) } throws AssertionError("Real service binding is forbidden in tests")
        every { Shizuku.pingBinder() } answers { binderAlive }
        every { Shizuku.getBinder() } answers { activeBinder }
        every { Shizuku.isPreV11() } answers { preV11 }
        every { remoteService.getUid() } answers {
            if (uidUnknown) throw IllegalStateException("unknown uid")
            uid
        }
        every { Shizuku.checkSelfPermission() } answers {
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) } answers {
            if (!installed) throw PackageManager.NameNotFoundException()
            mockk<PackageInfo>()
        }
        every { packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS) } returns emptyList()
        every { context.getString(R.string.shizuku_shell_required) } returns "Root or shell required"
        every { context.getString(R.string.shizuku_identity_unavailable) } returns "Shizuku identity unavailable"
        every { context.getString(R.string.shizuku_identity_changed) } returns "Shizuku identity changed"
        every { context.getString(R.string.shizuku_approval_required) } returns "Shizuku approval required"
        every { context.getString(R.string.settings_shizuku_hint_denied) } returns "Shizuku permission required"
        every { context.getString(R.string.settings_shizuku_hint_not_running) } returns "Shizuku is not running"
        every { context.getString(R.string.settings_shizuku_hint_not_installed) } returns "Shizuku is not installed"
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun rootAndShellHaveDistinctReadyStatesAndConnectionIdentities() {
        val manager = ShizukuManager(context)
        val shell = requireNotNull(manager.identity.value)
        assertEquals(2000, shell.uid)
        assertEquals(ShizukuState.READY, manager.state.value)
        assertEquals(shell.connectionId, UUID.fromString(shell.connectionId).toString())

        uid = 0
        val root = requireNotNull(manager.accessSnapshot())
        assertEquals(0, root.uid)
        assertEquals(ShizukuState.READY_ROOT, manager.state.value)
        assertNotEquals(shell.connectionId, root.connectionId)
        uid = 2000
        val nextShell = requireNotNull(manager.accessSnapshot())
        assertEquals(ShizukuState.READY, manager.state.value)
        assertNotEquals(shell.connectionId, nextShell.connectionId)
    }

    @Test
    fun unknownUidFailsClosedEvenIfPermissionWasGranted() {
        uidUnknown = true
        val manager = ShizukuManager(context)
        assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, manager.state.value)
        assertNull(manager.identity.value)
    }

    @Test
    fun cachedServiceCannotBeReusedWithUnknownOrUnsupportedUid() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            val original = requireNotNull(manager.identity.value)
            for ((invalid, expectedState, message) in listOf(
                Triple(-1, ShizukuState.IDENTITY_UNAVAILABLE, "Shizuku identity unavailable"),
                Triple(10000, ShizukuState.SHELL_REQUIRED, "Root or shell required"),
                Triple(1000, ShizukuState.SHELL_REQUIRED, "Root or shell required")
            )) {
                cacheService(manager, service, original)
                uid = invalid
                expectFailure(message) { manager.runCommand("never-execute", 1_000) }
                assertEquals(expectedState, manager.state.value)
                assertNull(field(manager, "shellService"))
                assertNull(manager.identity.value)
            }
            uidUnknown = true
            cacheService(manager, service, original)
            expectFailure("Shizuku identity unavailable") { manager.runCommand("never-execute", 1_000) }
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun uidIsRecheckedBeforeCachedPluginServiceExec() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            var checks = 0
            every { remoteService.getUid() } answers { if (++checks == 1) 2000 else 0 }

            expectFailure("Shizuku identity changed") { manager.runCommand("never-execute", 1_000) }

            assertEquals(2, checks)
            assertEquals(ShizukuState.READY_ROOT, manager.state.value)
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun serviceIdentityDenialIsLocalizedAndRevokesOtherPermitsForBothUids() {
        runTest {
            for (allowedUid in listOf(0, 2000)) {
                uid = allowedUid
                val manager = ShizukuManager(context)
                val original = requireNotNull(manager.identity.value)
                val service = fakeService(ShizukuShellService.EXIT_SHELL_REQUIRED)
                cacheService(manager, service)
                val permit = requireNotNull(manager.authorize("session", "never-execute", original))
                val spare = requireNotNull(manager.authorize("session", "other-command", original))

                expectFailure("Shizuku identity changed") {
                    manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                }
                assertNull(field(manager, "shellService"))
                assertNotEquals(original, manager.identity.value)
                expectFailure("Shizuku approval required") {
                    manager.runApprovedCommand("other-command", 1_000, "session", spare)
                }
                verify(exactly = 1) { service.exec("never-execute", 1_000, allowedUid) }
            }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun deniedPermissionDoesNotQueryUidThatWouldThrowSecurityException() {
        granted = false
        every { remoteService.getUid() } throws SecurityException("Permission has not been granted")
        val manager = ShizukuManager(context)
        manager.refreshState()

        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 2) { Shizuku.checkSelfPermission() }
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun deniedPermissionDoesNotQueryUidThatWouldReturnMinusOne() {
        granted = false
        uid = -1
        val manager = ShizukuManager(context)
        manager.requestPermission()

        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 1) { Shizuku.requestPermission(1001) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun requestPermissionUsesOriginalRequestCodeWithoutGrantingOrQueryingUid() {
        granted = false
        every { remoteService.getUid() } throws SecurityException("Permission has not been granted")
        val manager = ShizukuManager(context)
        manager.requestPermission()

        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
        verify(exactly = 1) { Shizuku.requestPermission(any()) }
        verify(exactly = 1) { Shizuku.requestPermission(1001) }
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun permissionGrantedListenerRefreshesToReadyOnlyAfterPermissionIsGranted() {
        for ((allowedUid, expected) in listOf(0 to ShizukuState.READY_ROOT, 2000 to ShizukuState.READY)) {
            uid = allowedUid
            granted = false
            val manager = ShizukuManager(context)
            manager.requestPermission()
            assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
            assertNull(manager.identity.value)

            granted = true
            permissionResultListener.captured.onRequestPermissionResult(1001, PackageManager.PERMISSION_GRANTED)

            assertEquals(expected, manager.state.value)
            assertEquals(allowedUid, manager.identity.value?.uid)
        }
        verify(exactly = 2) { remoteService.getUid() }
        verify(exactly = 2) { Shizuku.requestPermission(1001) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun permissionDeniedListenerKeepsPermissionDeniedWithoutQueryingUid() {
        granted = false
        uidUnknown = true
        val manager = ShizukuManager(context)
        manager.requestPermission()
        permissionResultListener.captured.onRequestPermissionResult(1001, PackageManager.PERMISSION_DENIED)

        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 1) { Shizuku.requestPermission(1001) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun notInstalledCannotRequestPermission() {
        binderAlive = false
        installed = false
        val manager = ShizukuManager(context)
        manager.requestPermission()

        assertEquals(ShizukuState.NOT_INSTALLED, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { Shizuku.requestPermission(any()) }
        verify(exactly = 0) { Shizuku.checkSelfPermission() }
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun stoppedServiceCannotRequestPermission() {
        binderAlive = false
        val manager = ShizukuManager(context)
        manager.requestPermission()

        assertEquals(ShizukuState.NOT_RUNNING, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { Shizuku.requestPermission(any()) }
        verify(exactly = 0) { Shizuku.checkSelfPermission() }
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun preV11CannotRequestPermission() {
        preV11 = true
        val manager = ShizukuManager(context)
        manager.requestPermission()

        assertEquals(ShizukuState.NOT_RUNNING, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { Shizuku.requestPermission(any()) }
        verify(exactly = 0) { Shizuku.checkSelfPermission() }
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun unknownUidCanRecoverToReadyOnRefresh() {
        uid = -1
        val manager = ShizukuManager(context)
        assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, manager.state.value)
        manager.requestPermission()
        uid = 2000
        val original = requireNotNull(manager.accessSnapshot())
        assertEquals(ShizukuState.READY, manager.state.value)
        uidUnknown = true
        manager.refreshState()
        assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, manager.state.value)
        assertNull(manager.identity.value)
        uidUnknown = false
        val recovered = requireNotNull(manager.accessSnapshot())

        assertEquals(ShizukuState.READY, manager.state.value)
        assertNotEquals(original.connectionId, recovered.connectionId)
        verify(exactly = 0) { Shizuku.requestPermission(any()) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun grantedSecurityExceptionIsIdentityUnavailable() {
        every { remoteService.getUid() } throws SecurityException("Identity cannot be read")
        val manager = ShizukuManager(context)
        manager.requestPermission()

        assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, manager.state.value)
        assertNull(manager.identity.value)
        verifyOrder {
            Shizuku.pingBinder()
            Shizuku.isPreV11()
            Shizuku.checkSelfPermission()
            remoteService.checkSelfPermission()
            remoteService.getUid()
        }
        verify(exactly = 0) { Shizuku.requestPermission(any()) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun authorizedUnsupportedUidsCannotBindOrExecute() {
        runTest {
            val service = fakeService()
            for (invalid in listOf(1000, 10000)) {
                uid = invalid
                val manager = ShizukuManager(context)
                assertEquals(ShizukuState.SHELL_REQUIRED, manager.state.value)
                manager.requestPermission()
                for (cached in listOf(false, true)) {
                    if (cached) cacheService(manager, service, ShizukuIdentity(2000, "stale"))
                    expectFailure("Root or shell required") { manager.runCommand("never-execute", 1_000) }
                    assertNull(field(manager, "shellService"))
                    assertNull(manager.identity.value)
                }
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.requestPermission(any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun refreshStateClearsCachedServiceWheneverStateIsNotReady() {
        val manager = ShizukuManager(context)
        val service = fakeService()
        val original = requireNotNull(manager.identity.value)
        for (expected in listOf(
            ShizukuState.PERMISSION_DENIED,
            ShizukuState.IDENTITY_UNAVAILABLE,
            ShizukuState.SHELL_REQUIRED,
            ShizukuState.NOT_RUNNING,
            ShizukuState.NOT_INSTALLED
        )) {
            granted = expected != ShizukuState.PERMISSION_DENIED
            uid = when (expected) {
                ShizukuState.IDENTITY_UNAVAILABLE -> -1
                ShizukuState.SHELL_REQUIRED -> 1000
                else -> 2000
            }
            binderAlive = expected != ShizukuState.NOT_RUNNING && expected != ShizukuState.NOT_INSTALLED
            installed = expected != ShizukuState.NOT_INSTALLED
            cacheService(manager, service, original)
            manager.refreshState()

            assertEquals(expected, manager.state.value)
            assertNull(field(manager, "shellService"))
            assertNull(manager.identity.value)
        }
        verify(exactly = 0) { service.exec(any(), any(), any()) }
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun cachedServiceCannotExecuteAfterPermissionIsRevoked() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            granted = false

            expectFailure("Shizuku permission required") { manager.runCommand("never-execute", 1_000) }

            assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
            assertNull(field(manager, "shellService"))
            assertNull(manager.identity.value)
            verify(exactly = 1) { remoteService.getUid() }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun cachedServiceCannotExecuteAfterBinderDiesEvenWithShellUid() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            binderAlive = false

            expectFailure("Shizuku is not running") { manager.runCommand("never-execute", 1_000) }

            assertEquals(ShizukuState.NOT_RUNNING, manager.state.value)
            assertNull(field(manager, "shellService"))
            assertNull(manager.identity.value)
            verify(exactly = 1) { Shizuku.checkSelfPermission() }
            verify(exactly = 1) { remoteService.getUid() }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun permissionIsRecheckedImmediatelyBeforeCachedServiceExec() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val checks = mutableListOf<String>()
            var permissionChecks = 0
            every { Shizuku.checkSelfPermission() } answers {
                checks += "permission"
                if (++permissionChecks < 3) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            every { remoteService.getUid() } answers {
                checks += "uid"
                2000
            }

            expectFailure("Shizuku permission required") { manager.runCommand("never-execute", 1_000) }

            assertEquals(listOf("permission", "uid", "permission", "uid", "permission"), checks)
            assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun approvedRootCommandExecutesWithExpectedUidZero() {
        runTest {
            uid = 0
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "approved-command", requireNotNull(manager.identity.value)))

            val result = manager.runApprovedCommand("approved-command", 1_000, "session", permit)

            assertEquals(ShizukuCommandResult("fake output", 0), result)
            assertEquals(ShizukuState.READY_ROOT, manager.state.value)
            verify(exactly = 1) { service.exec("approved-command", 1_000, 0) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun approvedShellCommandExecutesWithExpectedUid2000() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "approved-command", requireNotNull(manager.identity.value)))

            val result = manager.runApprovedCommand("approved-command", 1_000, "session", permit)

            assertEquals(ShizukuCommandResult("fake output", 0), result)
            verify(exactly = 1) { service.exec("approved-command", 1_000, 2000) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun pluginShellEntryKeepsWorkingAndClampsTimeout() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)

            assertEquals(0, manager.runCommand("plugin-command", 1).exitCode)
            assertEquals(0, manager.runCommand("plugin-command", Long.MAX_VALUE).exitCode)

            verify(exactly = 1) { service.exec("plugin-command", 1_000, 2000) }
            verify(exactly = 1) { service.exec("plugin-command", 1_800_000, 2000) }
        }
    }

    @Test
    fun pluginRootEntryCannotBorrowOrConsumeChatPermit() {
        runTest {
            uid = 0
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "approved-command", requireNotNull(manager.identity.value)))

            expectFailure("Shizuku approval required") { manager.runCommand("approved-command", 1_000) }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            assertEquals(0, manager.runApprovedCommand("approved-command", 1_000, "session", permit).exitCode)
            verify(exactly = 1) { service.exec("approved-command", 1_000, 0) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun missingOrUnknownPermitCannotUseCachedRootOrShellService() {
        runTest {
            for (allowedUid in listOf(0, 2000)) {
                uid = allowedUid
                val manager = ShizukuManager(context)
                val service = fakeService()
                cacheService(manager, service)
                for (permit in listOf(null, "", UUID.randomUUID().toString())) {
                    expectFailure("Shizuku approval required") {
                        manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                    }
                }
                verify(exactly = 0) { service.exec(any(), any(), any()) }
            }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun crossSessionUseConsumesAndRejectsPermit() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
            for (session in listOf("other-session", "session")) {
                expectFailure("Shizuku approval required") {
                    manager.runApprovedCommand("never-execute", 1_000, session, permit)
                }
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun changedCommandIncludingWhitespaceConsumesAndRejectsPermit() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            for (changed in listOf("never-execute ", "never-execute\n", "different-command")) {
                val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
                expectFailure("Shizuku approval required") {
                    manager.runApprovedCommand(changed, 1_000, "session", permit)
                }
                expectFailure("Shizuku approval required") {
                    manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                }
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun successfulPermitCannotBeReplayed() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "approved-command", requireNotNull(manager.identity.value)))
            manager.runApprovedCommand("approved-command", 1_000, "session", permit)

            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("approved-command", 1_000, "session", permit)
            }

            verify(exactly = 1) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun revokePermitIsIdempotentAndPreventsExecution() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
            manager.revokePermit(permit)
            manager.revokePermit(permit)

            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun authorizeRejectsBlankInputForgedIdentityAndUnavailableIdentity() {
        val manager = ShizukuManager(context)
        val original = requireNotNull(manager.identity.value)
        assertNull(manager.authorize("", "command", original))
        assertNull(manager.authorize(" ", "command", original))
        assertNull(manager.authorize("session", "", original))
        assertNull(manager.authorize("session", "\n", original))
        assertNull(manager.authorize("session", "command", original.copy(uid = 0)))
        assertNull(manager.authorize("session", "command", original.copy(connectionId = UUID.randomUUID().toString())))
        assertNull(manager.authorize("session", "command", original.copy(uid = 10000)))
        granted = false
        assertNull(manager.authorize("session", "command", original))
        assertNull(manager.identity.value)
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun approvedCommandRejectsBothRootToShellAndShellToRootChanges() {
        runTest {
            for ((before, after) in listOf(0 to 2000, 2000 to 0)) {
                uid = before
                val manager = ShizukuManager(context)
                val service = fakeService()
                cacheService(manager, service)
                val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
                uid = after

                expectFailure("Shizuku identity changed") {
                    manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                }
                expectFailure("Shizuku approval required") {
                    manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                }
                assertNull(field(manager, "shellService"))
                verify(exactly = 0) { service.exec(any(), any(), any()) }
            }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun approvedUidIsRecheckedImmediatelyBeforeBinderExecInBothDirections() {
        runTest {
            for ((before, after) in listOf(0 to 2000, 2000 to 0)) {
                every { remoteService.getUid() } returns before
                val manager = ShizukuManager(context)
                val service = fakeService()
                cacheService(manager, service)
                val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
                var checks = 0
                every { remoteService.getUid() } answers { if (++checks < 3) before else after }

                expectFailure("Shizuku identity changed") {
                    manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                }

                assertEquals(3, checks)
                assertEquals(after, manager.identity.value?.uid)
                assertNull(field(manager, "shellService"))
                verify(exactly = 0) { service.exec(any(), any(), any()) }
            }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun mainBinderReplacementRotatesIdentityAndRevokesPendingPermits() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            activeBinder = mockk(relaxed = true)
            val current = requireNotNull(manager.accessSnapshot())

            assertNotEquals(original.connectionId, current.connectionId)
            assertNull(field(manager, "shellService"))
            assertNull(manager.authorize("session", "never-execute", original))
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun stableBinderRefreshAndPermissionListenerDoNotRotateIdentity() {
        for (allowedUid in listOf(0, 2000)) {
            uid = allowedUid
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            repeat(3) { assertEquals(original, manager.accessSnapshot()) }
            binderReceivedListener.captured.onBinderReceived()
            permissionResultListener.captured.onRequestPermissionResult(1001, PackageManager.PERMISSION_GRANTED)

            assertEquals(original, manager.identity.value)
            assertSame(service, field(manager, "shellService"))
        }
    }

    @Test
    fun permissionRecoveryCannotReusePreviousConnectionOrPermit() {
        runTest {
            uid = 0
            val manager = ShizukuManager(context)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            granted = false
            manager.refreshState()
            assertNull(manager.identity.value)
            granted = true
            val recovered = requireNotNull(manager.accessSnapshot())

            assertNotEquals(original.connectionId, recovered.connectionId)
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            assertNull(manager.authorize("session", "never-execute", original))
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun unknownUidRecoveryRevokesPreviouslyIssuedPermit() {
        runTest {
            val manager = ShizukuManager(context)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            uidUnknown = true
            manager.refreshState()
            uidUnknown = false
            val recovered = requireNotNull(manager.accessSnapshot())

            assertNotEquals(original.connectionId, recovered.connectionId)
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun binderDeathListenerRevokesPermitEvenIfSameBinderAlreadyRespondsAgain() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            binderDeadListener.captured.onBinderDead()

            assertNotEquals(original, manager.identity.value)
            assertNull(field(manager, "shellService"))
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun userServiceDisconnectRotatesIdentityAndRejectsOldPermit() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            val connection = connectService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            connection.onServiceDisconnected(null)

            assertNotEquals(original, manager.identity.value)
            assertNull(field(manager, "shellService"))
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun userServiceBinderDeathRevokesPermitWithoutWaitingForDisconnect() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            connectService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            (field(manager, "shellServiceDeathRecipient") as IBinder.DeathRecipient).binderDied()

            assertNotEquals(original, manager.identity.value)
            assertNull(field(manager, "shellService"))
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun replacementUserServiceBinderCannotInheritOldIdentityOrPermit() {
        runTest {
            val manager = ShizukuManager(context)
            val oldService = fakeService()
            val connection = connectService(manager, oldService)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            val replacement = fakeService()
            connection.onServiceConnected(null, replacement.asBinder())

            assertNotEquals(original, manager.identity.value)
            assertNull(field(manager, "shellService"))
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { oldService.exec(any(), any(), any()) }
            verify(exactly = 0) { replacement.exec(any(), any(), any()) }
        }
    }

    @Test
    fun deadCachedServiceBinderRotatesIdentityBeforeExecution() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            every { service.asBinder().pingBinder() } returns false

            expectFailure("Shizuku identity changed") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }

            assertNotEquals(original, manager.identity.value)
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun cachedServiceIsRejectedUnlessBoundToFrozenIdentity() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            setField(manager, "shellServiceIdentity", original.copy(connectionId = UUID.randomUUID().toString()))

            expectFailure("Shizuku identity changed") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    @Test
    fun lateCallbacksFromDisconnectedServiceDoNotInvalidateNewConnection() {
        val manager = ShizukuManager(context)
        val stale = connectService(manager, fakeService())
        stale.onServiceDisconnected(null)
        val replacement = fakeService()
        connectService(manager, replacement)
        val current = requireNotNull(manager.identity.value)

        stale.onServiceDisconnected(null)
        stale.onServiceConnected(null, fakeService().asBinder())

        assertEquals(current, manager.identity.value)
        assertSame(replacement, field(manager, "shellService"))
    }

    @Test
    fun identityIsRecheckedInsideBindMutexBeforeAnyBinding() {
        runTest {
            val manager = ShizukuManager(context)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            val mutex = field(manager, "bindMutex") as Mutex
            mutex.lock()
            val execution = async(start = CoroutineStart.UNDISPATCHED) {
                expectFailure("Shizuku identity changed") {
                    manager.runApprovedCommand("never-execute", 1_000, "session", permit)
                }
            }
            uid = 0
            mutex.unlock()
            execution.await()

            assertEquals(ShizukuState.READY_ROOT, manager.state.value)
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun initialAndRepeatedSameServiceConnectionPreserveApprovedIdentity() {
        runTest {
            val manager = ShizukuManager(context)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "approved-command", original))
            val service = fakeService()
            val connection = connectService(manager, service)
            connection.onServiceConnected(null, service.asBinder())

            assertEquals(original, manager.identity.value)
            assertEquals(0, manager.runApprovedCommand("approved-command", 1_000, "session", permit).exitCode)
            verify(exactly = 1) { service.exec("approved-command", 1_000, 2000) }
        }
    }

    @Test
    fun approvedPermissionRevocationFailsBeforeReadingUidOrExecuting() {
        runTest {
            uid = 0
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
            granted = false
            every { remoteService.getUid() } throws AssertionError("UID cannot be queried without permission")

            expectFailure("Shizuku permission required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            assertNull(manager.identity.value)
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun permissionProbeExceptionFailsClosedWithoutReadingUid() {
        every { Shizuku.checkSelfPermission() } throws SecurityException("permission unavailable")
        val manager = ShizukuManager(context)

        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { remoteService.getUid() }
    }

    @Test
    fun missingMainBinderCannotProduceAnAuthorizedIdentity() {
        activeBinder = null
        val manager = ShizukuManager(context)

        assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, manager.state.value)
        assertNull(manager.accessSnapshot())
        assertNull(manager.authorize("session", "never-execute", ShizukuIdentity(2000, "missing")))
        verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
    }

    @Test
    fun cachedSdkGrantCannotOverrideFreshServicePermissionRevocation() {
        runTest {
            uid = 0
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            serverGranted = false
            every { remoteService.getUid() } throws AssertionError("Fresh denial must precede UID lookup")

            expectFailure("Shizuku permission required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }

            assertNull(manager.identity.value)
            assertNull(field(manager, "shellService"))
            serverGranted = true
            every { remoteService.getUid() } returns 0
            assertNotEquals(original, manager.accessSnapshot())
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun freshPermissionProbeExceptionFailsClosedWithoutUidLookup() {
        every { remoteService.checkSelfPermission() } throws SecurityException("permission revoked")
        val manager = ShizukuManager(context)

        assertEquals(ShizukuState.PERMISSION_DENIED, manager.state.value)
        assertNull(manager.identity.value)
        verify(exactly = 0) { remoteService.getUid() }
        verify(exactly = 0) { Shizuku.getUid() }
    }

    @Test
    fun freshPermissionIsRecheckedImmediatelyBeforeApprovedBinderExec() {
        runTest {
            uid = 0
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val permit = requireNotNull(manager.authorize("session", "never-execute", requireNotNull(manager.identity.value)))
            val checks = mutableListOf<String>()
            var permissionChecks = 0
            every { remoteService.checkSelfPermission() } answers {
                checks += "permission"
                ++permissionChecks < 3
            }
            every { remoteService.getUid() } answers {
                checks += "uid"
                0
            }

            expectFailure("Shizuku permission required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }

            assertEquals(listOf("permission", "uid", "permission", "uid", "permission"), checks)
            assertNull(manager.identity.value)
            assertNull(field(manager, "shellService"))
            verify(exactly = 0) { service.exec(any(), any(), any()) }
            verify(exactly = 0) { Shizuku.bindUserService(any(), any()) }
        }
    }

    @Test
    fun mainBinderChangeDuringSnapshotCannotPublishMixedConnectionIdentity() {
        runTest {
            val manager = ShizukuManager(context)
            val service = fakeService()
            cacheService(manager, service)
            val original = requireNotNull(manager.identity.value)
            val permit = requireNotNull(manager.authorize("session", "never-execute", original))
            every { remoteService.getUid() } answers {
                activeBinder = mockk(relaxed = true)
                2000
            }

            assertNull(manager.accessSnapshot())
            assertEquals(ShizukuState.IDENTITY_UNAVAILABLE, manager.state.value)
            assertNull(field(manager, "shellService"))
            every { remoteService.getUid() } returns 2000
            assertNotEquals(original, manager.accessSnapshot())
            expectFailure("Shizuku approval required") {
                manager.runApprovedCommand("never-execute", 1_000, "session", permit)
            }
            verify(exactly = 0) { service.exec(any(), any(), any()) }
        }
    }

    private fun fakeService(exitCode: Int = 0): IShizukuShellService {
        val bundle = mockk<Bundle>()
        every { bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_FAILURE) } returns exitCode
        every { bundle.getString(ShizukuShellService.KEY_OUTPUT) } returns "fake output"
        val binder = mockk<IBinder>(relaxed = true)
        every { binder.pingBinder() } returns true
        val service = mockk<IShizukuShellService>()
        every { service.asBinder() } returns binder
        every { service.exec(any(), any(), any()) } returns bundle
        every { IShizukuShellService.Stub.asInterface(binder) } returns service
        return service
    }

    private fun cacheService(
        manager: ShizukuManager,
        service: IShizukuShellService,
        identity: ShizukuIdentity = requireNotNull(manager.identity.value)
    ) {
        setField(manager, "shellService", service)
        setField(manager, "shellServiceBinder", service.asBinder())
        setField(manager, "shellServiceIdentity", identity)
    }

    private fun connectService(manager: ShizukuManager, service: IShizukuShellService): ServiceConnection {
        val method = ShizukuManager::class.java.getDeclaredMethod("createServiceConnection", ShizukuIdentity::class.java)
            .apply { isAccessible = true }
        val connection = method.invoke(manager, requireNotNull(manager.identity.value)) as ServiceConnection
        setField(manager, "serviceConnection", connection)
        connection.onServiceConnected(null, service.asBinder())
        assertSame(service, field(manager, "shellService"))
        return connection
    }

    private fun field(manager: ShizukuManager, name: String): Any? =
        ShizukuManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)

    private fun setField(manager: ShizukuManager, name: String, value: Any?) {
        ShizukuManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    private suspend fun expectFailure(message: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: IllegalStateException) {
            assertEquals(message, e.message)
            return
        }
        throw AssertionError("Expected failure: $message")
    }
}
