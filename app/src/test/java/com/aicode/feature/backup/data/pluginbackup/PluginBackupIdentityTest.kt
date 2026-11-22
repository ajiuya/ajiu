package com.aicode.feature.backup.data.pluginbackup

import com.aicode.feature.plugin.domain.PluginCaptureException
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOperationResult
import com.aicode.feature.plugin.domain.PluginPreviewResult
import java.io.File
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginBackupIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun manager() = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "host/registry.json"))

    @Test fun disabledInstallHasReadOnlySnapshotButNoExecutableCapture() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        val snapshot = manager.stableInstallSnapshots().single()
        assertEquals(installed.record, snapshot.record)
        assertNull(manager.capture(snapshot.record.id))
        snapshot.checkCurrent()
        assertFalse(snapshot.dataDirectory.exists())
    }

    @Test fun snapshotCannotFollowReinstallationOrActivationChange() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val disabled = manager.stableInstallSnapshots().single()
        PluginCoreFixtures.activate(manager)
        assertThrows(IllegalStateException::class.java) { disabled.checkCurrent() }
        val enabled = manager.stableInstallSnapshots().single()
        PluginCoreFixtures.install(manager)
        assertThrows(IllegalStateException::class.java) { enabled.checkCurrent() }
    }

    @Test fun fenceImmediatelyRevokesCaptureTrustAndCleanupWithoutLifecycleHooks() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val pendingTrust = manager.prepareTrust(capture.id)
        var events = 0
        manager.registerLifecycleSink { events++ }
        manager.withBackupRestoreFence(capture.id, installed.record.installRevision) { fence ->
            fence.checkCurrent()
            assertNull(manager.capture(capture.id))
            assertThrows(PluginCaptureException::class.java) { capture.checkActive() }
            assertNull(manager.plugins.value.single().record.trustHash)
            assertFalse(manager.plugins.value.single().record.enabled)
            assertEquals(PluginErrorCode.STALE_CAPTURE, manager.prepareTrust(capture.id).errors.single().code)
            assertTrue(manager.enable(capture.id, pendingTrust.token) is PluginOperationResult.Failure)
            assertTrue(manager.disable(capture.id) is PluginOperationResult.Failure)
            assertTrue(manager.uninstall(capture.id, true) is PluginOperationResult.Failure)
            assertEquals(0, events)
        }
        val reloaded = manager()
        reloaded.ensureLoaded()
        assertNull(reloaded.capture(capture.id))
        assertNull(reloaded.plugins.value.single().record.trustHash)
        assertEquals(0, events)
    }

    @Test fun staleFenceCompareAndSwapDoesNotRevokeNewerLocalInstall() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.install(manager)
        val active = PluginCoreFixtures.activate(manager)
        val before = manager.snapshot.value
        assertTrue(runCatching {
            manager.withBackupRestoreFence(active.id, old.record.installRevision) { error("must not run") }
        }.isFailure)
        assertSame(before, manager.snapshot.value)
        active.checkCurrent()
    }

    @Test fun fenceBlocksOrdinaryInstallButAllowsItsHostBackupInstall() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.activate(manager)
        val ordinary = (manager.preview(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        val backup = (manager.previewForBackup(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        var newRevision = ""
        manager.withBackupRestoreFence(installed.record.id, installed.record.installRevision) { fence ->
            assertTrue(manager.install(ordinary.token) is PluginInstallResult.Failure)
            val restored = (manager.installForBackup(backup.token, fence) as PluginInstallResult.Success).plugin
            newRevision = restored.record.installRevision
            assertNotEquals(installed.record.installRevision, newRevision)
            assertFalse(restored.record.enabled)
            assertNull(restored.record.trustHash)
            assertNull(manager.capture(restored.record.id))
            fence.snapshot().checkCurrent()
            assertTrue(manager.prepareTrust(restored.record.id).errors.isNotEmpty())
        }
        assertEquals(newRevision, manager.plugins.value.single().record.installRevision)
        assertTrue(manager.prepareTrust(installed.record.id).errors.isEmpty())
    }

    @Test fun backupPreviewIsNotOrdinaryExternalInstallToken() = runBlocking<Unit> {
        val manager = manager()
        val preview = (manager.previewForBackup(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        assertTrue(manager.install(preview.token) is PluginInstallResult.Failure)
        assertTrue(manager.plugins.value.isEmpty())
        assertTrue(File(temporary.root, "plugins/staging").listFiles()!!.isEmpty())
    }

    @Test fun fenceIsPerIdAndOldHandleCannotCommitAfterRelease() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(PluginCoreFixtures.manifest(id = "other.plugin")))
        val other = PluginCoreFixtures.activate(manager, "other.plugin")
        lateinit var oldFence: PluginManager.BackupRestoreFence
        manager.withBackupRestoreFence(installed.record.id, installed.record.installRevision) { fence ->
            oldFence = fence
            other.checkCurrent()
            assertTrue(runCatching { manager.withBackupRestoreFence(installed.record.id, installed.record.installRevision) {} }.isFailure)
            assertEquals(PluginOperationResult.Success, manager.disable(other.id))
        }
        assertThrows(IllegalStateException::class.java) { oldFence.checkCurrent() }
        val preview = (manager.previewForBackup(PluginCoreFixtures.archive().inputStream()) as PluginPreviewResult.Success).preview
        assertTrue(manager.installForBackup(preview.token, oldFence) is PluginInstallResult.Failure)
        assertEquals(installed.record.installRevision, manager.plugins.value.first { it.record.id == installed.record.id }.record.installRevision)
    }

    @Test fun failingFenceBodyReleasesFenceButNeverRevivesPriorTrust() = runBlocking<Unit> {
        val manager = manager()
        val installed = PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        assertTrue(runCatching {
            manager.withBackupRestoreFence(installed.record.id, installed.record.installRevision) { throw java.io.IOException("fixture") }
        }.isFailure)
        assertThrows(PluginCaptureException::class.java) { capture.checkCurrent() }
        assertNull(manager.capture(installed.record.id))
        assertTrue(manager.prepareTrust(installed.record.id).errors.isEmpty())
    }

    @Test fun backupReaderAllowsBoundedRepackedZipAboveExternalLimitWithoutRelaxingNormalPreview() = runBlocking<Unit> {
        val manager = manager()
        val random = Random(42)
        val bytes = PluginCoreFixtures.archive(extra = (1..6).map { index ->
            "payload/chunk$index.bin" to ByteArray(3 * 1024 * 1024).also(random::nextBytes)
        })
        assertTrue(bytes.size > 16 * 1024 * 1024)
        assertTrue(bytes.size < PluginManager.BACKUP_MAX_ARCHIVE_BYTES)
        val regular = manager.preview(bytes.inputStream()) as PluginPreviewResult.Failure
        assertEquals(PluginErrorCode.ARCHIVE_TOO_LARGE, regular.errors.single().code)
        val backup = (manager.previewForBackup(bytes.inputStream()) as PluginPreviewResult.Success).preview
        manager.withBackupRestoreFence(backup.manifest.id, null) { fence ->
            assertTrue(manager.installForBackup(backup.token, fence) is PluginInstallResult.Success)
        }
        assertFalse(manager.plugins.value.single().isActive)
    }

    @Test fun backupReaderRetainsOrdinaryEntryAndPathLimits() = runBlocking<Unit> {
        val manager = manager()
        for (bytes in listOf(
            PluginCoreFixtures.archive(extra = listOf("large.bin" to ByteArray(4 * 1024 * 1024 + 1))),
            PluginCoreFixtures.archive(extra = listOf("one/two/three/four/five/six/seven/eight/nine.bin" to byteArrayOf(1)))
        )) {
            assertTrue(manager.previewForBackup(bytes.inputStream()) is PluginPreviewResult.Failure)
            assertTrue(manager.plugins.value.isEmpty())
        }
    }
}
