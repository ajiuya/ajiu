package com.aicode.feature.plugin.domain

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginContributionManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 10_000L
    private fun manager() = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "host/registry.json")) { now }
    private fun indexed() = PluginInstallOrigin(PluginInstallOriginKind.INDEX, "community", "Community",
        "https://example.com/index.json", "https://example.com/sample/plugin", true)
    @Test fun fullAggregatesAreInactiveUntilNativeTrustAndKeepCapturedIdentity() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        assertTrue(manager.hooks().isEmpty())
        assertTrue(manager.mcpServers().isEmpty())
        assertTrue(manager.providers().isEmpty())
        assertTrue(manager.sessionSources().isEmpty())
        assertTrue(manager.overlays().isEmpty())
        assertTrue(manager.backup().isEmpty())
        assertTrue(manager.shizuku().isEmpty())
        assertTrue(manager.modelAccess().isEmpty())
        val capture = PluginCoreFixtures.activate(manager)
        val contributions: List<PluginContribution<*>> = manager.hooks() + manager.mcpServers() + manager.providers() +
            manager.sessionSources() + manager.overlays() + manager.backup() + manager.shizuku() + manager.modelAccess() + manager.workspaceTools()
        assertEquals(19, contributions.size)
        contributions.forEach {
            assertEquals(capture.installRevision, it.capture.installRevision)
            assertEquals(capture.contentHash, it.capture.contentHash)
            assertEquals(capture.activationEpoch, it.capture.activationEpoch)
            it.capture.checkCurrent()
        }
        assertEquals(1, manager.uiEntries(PluginUiSlots.BACKUP_DATA).size)
        assertEquals(1, manager.hooks(PluginHookEvents.AGENT_TOOL_BEFORE).size)
        manager.disable(capture.id)
        assertTrue(manager.hooks().isEmpty())
        assertTrue(manager.mcpServers().isEmpty())
        contributions.forEach { assertThrows(PluginCaptureException::class.java) { it.capture.checkActive() } }
    }
    @Test fun sourceAndExpectationBecomeInstalledOnlyViaTheBoundPreviewTicket() = runBlocking<Unit> {
        val manager = manager()
        val bytes = PluginFullContributionFixtures.archive()
        val expected = PluginPackageExpectation("sample.plugin", "1.0.0", "sample", "https://example.com/sample/plugin")
        val preview = (manager.preview(bytes.inputStream(), indexed(), expected) as PluginPreviewResult.Success).preview
        assertEquals(indexed(), preview.source)
        assertNull(preview.previousRevision)
        assertTrue(manager.plugins.value.isEmpty())
        val installed = (manager.install(preview.token) as PluginInstallResult.Success).plugin
        assertEquals(indexed(), installed.record.source)
        assertFalse(installed.record.enabled)
        assertNull(installed.record.trustHash)
        assertTrue(installed.errors.isEmpty())
        assertTrue(manager.install(preview.token) is PluginInstallResult.Failure)
        val local = (manager.preview(bytes.inputStream()) as PluginPreviewResult.Success).preview
        assertEquals(installed.record.installRevision, local.previousRevision)
        val replacement = (manager.install(local.token) as PluginInstallResult.Success).plugin
        assertEquals(PluginInstallOrigin(), replacement.record.source)
        assertNotEquals(installed.record.installRevision, replacement.record.installRevision)
    }
    @Test fun expectationRejectsEveryMismatchedFieldAndDoesNotInstallOrChangeOrigin() = runBlocking<Unit> {
        val manager = manager()
        val existing = PluginCoreFixtures.install(manager)
        val correct = PluginPackageExpectation("sample.plugin", "1.0.0", "sample", "https://example.com/sample/plugin")
        for (wrong in listOf(correct.copy(id = "other.plugin"), correct.copy(version = "1.0.1"),
            correct.copy(publisher = "other"), correct.copy(repository = "https://example.com/other/repo"),
            correct.copy(archiveSha256 = "0".repeat(64)))) {
            val result = manager.preview(PluginCoreFixtures.archive().inputStream(), indexed(), wrong) as PluginPreviewResult.Failure
            assertEquals(PluginErrorCode.CONTENT_CHANGED, result.errors.single().code)
            assertEquals(existing.record.installRevision, manager.plugins.value.single().record.installRevision)
            assertEquals(PluginInstallOrigin(), manager.plugins.value.single().record.source)
            assertTrue(File(temporary.root, "plugins/staging").listFiles()!!.isEmpty())
        }
    }
    @Test fun replacingSameVersionInvalidatesOldPreviewEvenAfterUninstallAndReinstall() = runBlocking<Unit> {
        val manager = manager()
        val old = PluginCoreFixtures.install(manager)
        val stale = (manager.preview(PluginCoreFixtures.archive().inputStream(), indexed()) as PluginPreviewResult.Success).preview
        manager.uninstall(old.record.id)
        val replacement = PluginCoreFixtures.install(manager)
        assertEquals(old.record.version, replacement.record.version)
        assertNotEquals(old.record.installRevision, replacement.record.installRevision)
        assertEquals(PluginErrorCode.STALE_CAPTURE, (manager.install(stale.token) as PluginInstallResult.Failure).errors.single().code)
        assertEquals(PluginInstallOrigin(), manager.plugins.value.single().record.source)
    }
    @Test fun untrustedInstallNeverDispatchesHooksAndFirstAuthorizedEnableOrdersEvents() = runBlocking<Unit> {
        val manager = manager()
        val received = mutableListOf<PluginLifecycleKind>()
        manager.registerLifecycleSink { received += it.kind; it.capture.checkCurrent() }
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        assertTrue(received.isEmpty())
        PluginCoreFixtures.activate(manager)
        assertEquals(listOf(PluginLifecycleKind.INSTALLED, PluginLifecycleKind.ENABLED), received)
        manager.disable("sample.plugin")
        PluginCoreFixtures.activate(manager)
        assertEquals(listOf(PluginLifecycleKind.INSTALLED, PluginLifecycleKind.ENABLED, PluginLifecycleKind.DISABLED, PluginLifecycleKind.ENABLED), received)
    }
    @Test fun cleanupIsRevokedBeforeCallbackAndOnlyValidInsideItsLease() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val old = PluginCoreFixtures.activate(manager)
        var lease: PluginCapture? = null
        manager.registerLifecycleSink { event ->
            if (event.kind == PluginLifecycleKind.DISABLED) {
                lease = event.capture
                assertTrue(event.capture.cleanup)
                assertNull(manager.capture(event.pluginId))
                assertThrows(PluginCaptureException::class.java) { old.checkActive() }
                event.capture.checkCurrent()
                withTimeout(1000) { manager.ensureLoaded() }
                assertTrue(manager.hooks().isEmpty())
            }
        }
        assertEquals(PluginOperationResult.Success, manager.disable(old.id))
        assertNotNull(lease)
        assertThrows(PluginCaptureException::class.java) { lease!!.checkActive() }
        assertThrows(PluginCaptureException::class.java) { lease!!.checkCurrent() }
        val renewed = PluginCoreFixtures.activate(manager)
        assertNotEquals(old.activationEpoch, renewed.activationEpoch)
        assertThrows(PluginCaptureException::class.java) { lease!!.checkCurrent() }
    }
    @Test fun cleanupCannotOutliveItsDeadlineOrReadReinstalledPackage() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        PluginCoreFixtures.activate(manager)
        manager.registerLifecycleSink { event ->
            if (event.kind == PluginLifecycleKind.DISABLED) {
                event.capture.checkActive()
                now += PluginManager.CLEANUP_TTL
                assertThrows(PluginCaptureException::class.java) { event.capture.checkActive() }
            }
        }
        assertEquals(PluginOperationResult.Success, manager.disable("sample.plugin"))
    }
    @Test fun uninstallCallbackRunsAfterRevocationBeforeFilesAreRemoved() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val active = PluginCoreFixtures.activate(manager)
        var cleanup: PluginCapture? = null
        manager.registerLifecycleSink { event ->
            assertEquals(PluginLifecycleKind.UNINSTALLED, event.kind)
            cleanup = event.capture
            event.capture.checkCurrent()
            assertTrue(event.capture.installDir.isDirectory)
            assertNull(manager.capture(event.pluginId))
            withTimeout(1000) { manager.ensureLoaded() }
        }
        assertEquals(PluginOperationResult.Success, manager.uninstall(active.id))
        assertNotNull(cleanup)
        assertFalse(active.installDir.exists())
        assertThrows(PluginCaptureException::class.java) { cleanup!!.checkActive() }
    }
    @Test fun untrustedOrTamperedPackagesNeverReceiveCleanupHooks() = runBlocking<Unit> {
        val manager = manager()
        var calls = 0
        manager.registerLifecycleSink { if (it.capture.cleanup) calls++ }
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        manager.disable("sample.plugin")
        manager.uninstall("sample.plugin")
        assertEquals(0, calls)
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        File(capture.installDir, "extra/unreferenced.js").appendText("tampered")
        manager.disable(capture.id)
        assertEquals(0, calls)
    }
}
