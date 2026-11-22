package com.aicode.feature.plugin.presentation

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.PluginStoreSettingsRepository
import com.aicode.feature.plugin.data.index.DownloadedPluginPackage
import com.aicode.feature.plugin.data.index.PluginIndexClient
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.data.index.PluginIndexSourceRepository
import com.aicode.feature.plugin.data.index.PluginReleaseAsset
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginInstallResult
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginPreviewResult
import com.aicode.feature.plugin.domain.PluginRiskSummary
import com.aicode.feature.plugin.domain.PluginSnapshot
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.domain.PluginTrustPreview
import com.aicode.feature.plugin.domain.SemVer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginCenterStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val viewModels = mutableListOf<ViewModel>()

    @Before fun setup(): Unit {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
    }

    @After fun cleanup(): Unit {
        viewModels.forEach { it.viewModelScope.cancel() }
        unmockkObject(FileLogger)
        Dispatchers.resetMain()
    }

    @Test fun downloadFailureIsLocalToItemAndRetryPreservesOtherItemErrors(): Unit = runTest {
        val fixture = StoreFixture()
        val first = item()
        val second = item("test.other")
        coEvery { fixture.client.resolveRelease(any()) } throws IOException("offline")
        fixture.viewModel.download(first)
        val firstFailure = fixture.viewModel.state.awaitValue { it.downloading == null && first.key in it.itemProblems }
        assertEquals("DOWNLOAD_FAILED", firstFailure.itemProblems.getValue(first.key).code)
        assertNull(firstFailure.problem)
        assertNull(firstFailure.sourceProblem)
        assertFalse(second.key in firstFailure.itemProblems)
        fixture.viewModel.download(second)
        fixture.viewModel.state.awaitValue { it.downloading == null && it.itemProblems.size == 2 }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<PluginReleaseAsset>()
        coEvery { fixture.client.resolveRelease(first.plugin) } coAnswers { entered.complete(Unit); release.await() }
        fixture.viewModel.download(first)
        awaitIo { entered.await() }
        assertEquals(first.key, fixture.viewModel.state.value.downloading)
        assertEquals(setOf(second.key), fixture.viewModel.state.value.itemProblems.keys)
        fixture.viewModel.cancelDownload()
        val cancelled = fixture.viewModel.state.awaitValue { it.downloading == null }
        assertEquals(setOf(second.key), cancelled.itemProblems.keys)
        assertNull(cancelled.preview)
    }

    @Test fun indexFailureIsNotOverwrittenByPackageOrSourceValidationFailure(): Unit = runTest {
        val fixture = StoreFixture()
        coEvery { fixture.client.fetchIndex(SOURCE, any()) } throws IOException("index unavailable")
        fixture.sourceList.value = listOf(SOURCE)
        val indexFailure = fixture.viewModel.state.awaitValue { !it.loading && it.problem != null }.problem
        assertEquals("INDEX_FAILED", indexFailure?.code)
        coEvery { fixture.client.resolveRelease(any()) } throws IOException("package unavailable")
        fixture.viewModel.download(item())
        fixture.viewModel.state.awaitValue { it.downloading == null && it.itemProblems.isNotEmpty() }
        coEvery { fixture.sources.setCustomSources(any()) } returns Result.failure(IllegalArgumentException("invalid source"))
        coEvery { fixture.settings.setDownloadMirror(any()) } returns Result.success(Unit)
        fixture.viewModel.saveSources("invalid", "")
        val invalid = fixture.viewModel.state.awaitValue { it.sourceProblem != null }
        assertEquals(indexFailure, invalid.problem)
        assertEquals("SOURCE_INVALID", invalid.sourceProblem?.code)
        assertFalse(invalid.sourceSaved)
        assertEquals(setOf(item().key), invalid.itemProblems.keys)
        coEvery { fixture.sources.setCustomSources(any()) } returns Result.success(Unit)
        fixture.viewModel.saveSources("", "")
        val saved = fixture.viewModel.state.awaitValue { it.sourceSaved }
        assertNull(saved.sourceProblem)
        assertEquals(indexFailure, saved.problem)
        assertEquals(invalid.itemProblems, saved.itemProblems)
    }

    @Test fun dismissPreviewDiscardsExactTokenAndClearsPreviewKey(): Unit = runTest {
        val fixture = StoreFixture()
        fixture.viewModel.download(item())
        val staged = fixture.viewModel.state.awaitValue { it.preview != null && it.downloading == null }
        assertEquals(item().key, staged.previewKey)
        assertSame(fixture.preview, staged.preview?.preview)
        assertNull(staged.installedId)
        assertNull(staged.notice)
        coVerify(exactly = 1) { fixture.manager.preview(any(), match { it.sourceId == SOURCE.id }, match {
            it != null && it.id == ID && it.version == "1.0.0" && it.archiveSha256 == HASH
        }) }
        fixture.viewModel.dismissPreview()
        assertNull(fixture.viewModel.state.value.preview)
        assertNull(fixture.viewModel.state.value.previewKey)
        coVerify(exactly = 1) { fixture.manager.discardPreview("preview-token") }
        coVerify(exactly = 0) { fixture.manager.install(any()) }
        coVerify(exactly = 0) { fixture.manager.enable(any(), any()) }
    }

    @Test fun storeInstallClosesPreviewPublishesDisabledNoticeAndConsumesOnlyInstalledId(): Unit = runTest {
        val fixture = StoreFixture()
        fixture.viewModel.download(item())
        fixture.viewModel.state.awaitValue { it.preview != null && it.downloading == null }
        fixture.viewModel.install("wrong-token")
        assertEquals("preview-token", fixture.viewModel.state.value.preview?.preview?.token)
        coVerify(exactly = 0) { fixture.manager.install(any()) }
        val release = CompletableDeferred<PluginInstallResult>()
        coEvery { fixture.manager.install("preview-token") } coAnswers { release.await() }
        fixture.viewModel.install("preview-token")
        assertNull(fixture.viewModel.state.value.preview)
        assertEquals(item().key, fixture.viewModel.state.value.downloading)
        fixture.viewModel.install("preview-token")
        release.complete(PluginInstallResult.Success(installed()))
        val completed = fixture.viewModel.state.awaitValue { it.installedId == ID && it.downloading == null }
        assertEquals(R.string.plugin_install_disabled, completed.notice)
        assertNull(completed.previewKey)
        fixture.viewModel.consumeInstalled()
        assertEquals(completed.copy(installedId = null), fixture.viewModel.state.value)
        coVerify(exactly = 1) { fixture.manager.install("preview-token") }
        coVerify(exactly = 1) { fixture.manager.discardPreview("preview-token") }
        coVerify(exactly = 0) { fixture.manager.prepareTrust(any()) }
        coVerify(exactly = 0) { fixture.manager.enable(any(), any()) }
    }

    @Test fun failedStoreInstallTargetsOriginalCardAndNeverPublishesInstalledId(): Unit = runTest {
        val fixture = StoreFixture()
        fixture.viewModel.download(item())
        fixture.viewModel.state.awaitValue { it.preview != null && it.downloading == null }
        coEvery { fixture.manager.install("preview-token") } returns PluginInstallResult.Failure(listOf(PluginError(PluginErrorCode.CONTENT_CHANGED)))
        fixture.viewModel.install("preview-token")
        val failed = fixture.viewModel.state.awaitValue { it.downloading == null && item().key in it.itemProblems }
        assertEquals(PluginError(PluginErrorCode.CONTENT_CHANGED).toProblem(), failed.itemProblems.getValue(item().key))
        assertNull(failed.problem)
        assertNull(failed.preview)
        assertNull(failed.previewKey)
        assertNull(failed.installedId)
        assertNull(failed.notice)
        coVerify(exactly = 1) { fixture.manager.discardPreview("preview-token") }
    }

    @Test fun inFlightDownloadRejectsDuplicatesAndCancellationDoesNotBecomeAnError(): Unit = runTest {
        val fixture = StoreFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<PluginReleaseAsset>()
        coEvery { fixture.client.resolveRelease(any()) } coAnswers { entered.complete(Unit); release.await() }
        fixture.viewModel.download(item())
        awaitIo { entered.await() }
        fixture.viewModel.download(item())
        fixture.viewModel.download(item("test.other"))
        assertEquals(item().key, fixture.viewModel.state.value.downloading)
        coVerify(exactly = 1) { fixture.client.resolveRelease(any()) }
        fixture.viewModel.cancelDownload()
        val cancelled = fixture.viewModel.state.awaitValue { it.downloading == null }
        assertTrue(cancelled.itemProblems.isEmpty())
        assertNull(cancelled.problem)
        assertNull(cancelled.preview)
        coVerify(exactly = 0) { fixture.client.download(any()) }
        fixture.viewModel.download(item().copy(compatible = false))
        assertNull(fixture.viewModel.state.value.downloading)
        coVerify(exactly = 1) { fixture.client.resolveRelease(any()) }
    }

    @Test fun nativeManagementChangesOnlyPanelAndDoesNotStartPluginOperations(): Unit = runTest {
        val fixture = NativeFixture()
        fixture.viewModel.select(ID)
        val original = fixture.viewModel.state.value
        for (panel in PluginManagementPanel.entries) {
            fixture.viewModel.showManagement(panel)
            assertEquals(original.copy(managementPanel = panel), fixture.viewModel.state.value)
            fixture.viewModel.dismissManagement()
            assertEquals(original, fixture.viewModel.state.value)
        }
        fixture.viewModel.consumeInstalled()
        assertEquals(original, fixture.viewModel.state.value)
        coVerify(exactly = 0) { fixture.manager.ensureLoaded() }
        coVerify(exactly = 0) { fixture.manager.refresh() }
        coVerify(exactly = 0) { fixture.manager.install(any()) }
        coVerify(exactly = 0) { fixture.manager.enable(any(), any()) }
        coVerify(exactly = 0) { fixture.manager.disable(any()) }
        coVerify(exactly = 0) { fixture.manager.uninstall(any(), any()) }
    }

    @Test fun nativeTrustPreparationAndPanelChangesNeverActivateThePlugin(): Unit = runTest {
        val fixture = NativeFixture()
        val trust = PluginTrustPreview("trust-token", ID, "revision-one", HASH, 0, Long.MAX_VALUE, manifest(), risks())
        coEvery { fixture.manager.prepareTrust(ID) } returns trust
        fixture.viewModel.prepareTrust(ID)
        val prepared = fixture.viewModel.state.awaitValue { !it.busy && it.trust != null }
        assertSame(trust, prepared.trust)
        fixture.viewModel.showManagement(PluginManagementPanel.MANAGE)
        fixture.viewModel.dismissManagement()
        assertEquals(prepared, fixture.viewModel.state.value)
        fixture.viewModel.dismissTrust()
        assertEquals(prepared.copy(trust = null), fixture.viewModel.state.value)
        assertFalse(fixture.viewModel.snapshot.value.plugins.single().isActive)
        coVerify(exactly = 1) { fixture.manager.prepareTrust(ID) }
        coVerify(exactly = 0) { fixture.manager.enable(any(), any()) }
    }

    @Test fun nativeInstallClosesPreviewPublishesDisabledNoticeAndConsumesOnlyInstalledId(): Unit = runTest {
        val fixture = NativeFixture()
        fixture.viewModel.preview(fixture.uri)
        fixture.viewModel.state.awaitValue { !it.busy && it.preview != null }
        fixture.viewModel.install("wrong-token")
        assertEquals("preview-token", fixture.viewModel.state.value.preview?.token)
        coVerify(exactly = 0) { fixture.manager.install(any()) }
        val release = CompletableDeferred<PluginInstallResult>()
        coEvery { fixture.manager.install("preview-token") } coAnswers { release.await() }
        fixture.viewModel.install("preview-token")
        assertNull(fixture.viewModel.state.value.preview)
        assertTrue(fixture.viewModel.state.value.busy)
        fixture.viewModel.install("preview-token")
        release.complete(PluginInstallResult.Success(installed()))
        val completed = fixture.viewModel.state.awaitValue { !it.busy && it.installedId == ID }
        assertEquals(ID, completed.selectedId)
        assertEquals(R.string.plugin_install_disabled, completed.notice)
        assertNull(completed.trust)
        fixture.viewModel.showManagement(PluginManagementPanel.MANAGE)
        val withPanel = fixture.viewModel.state.value
        fixture.viewModel.consumeInstalled()
        assertEquals(withPanel.copy(installedId = null), fixture.viewModel.state.value)
        fixture.viewModel.dismissManagement()
        assertEquals(completed.copy(installedId = null), fixture.viewModel.state.value)
        coVerify(exactly = 1) { fixture.manager.install("preview-token") }
        coVerify(exactly = 1) { fixture.manager.discardPreview("preview-token") }
        coVerify(exactly = 0) { fixture.manager.prepareTrust(any()) }
        coVerify(exactly = 0) { fixture.manager.enable(any(), any()) }
    }

    private inner class StoreFixture {
        val client = mockk<PluginIndexClient>()
        val sources = mockk<PluginIndexSourceRepository>()
        val settings = mockk<PluginStoreSettingsRepository>()
        val manager = manager()
        val sourceList = MutableStateFlow<List<PluginIndexSource>>(emptyList())
        val preview = preview()
        val viewModel: PluginStoreViewModel

        init {
            every { sources.sources } returns sourceList
            every { settings.downloadMirror } returns MutableStateFlow("")
            coEvery { client.resolveRelease(any()) } returns ASSET
            coEvery { client.download(any()) } answers {
                DownloadedPluginPackage(temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }, ASSET, HASH, ASSET.url)
            }
            coEvery { manager.preview(any(), any(), any()) } returns PluginPreviewResult.Success(preview)
            coEvery { manager.install("preview-token") } returns PluginInstallResult.Success(installed())
            viewModel = PluginStoreViewModel(client, sources, settings, manager).also { viewModels += it }
        }
    }

    private inner class NativeFixture {
        val manager = manager()
        val uri = Uri.parse("content://plugin-test/archive.zip")
        val viewModel: NativePluginViewModel

        init {
            val capabilities = mockk<PluginCapabilityRepository>()
            val resolver = mockk<ContentResolver>()
            val context = mockk<Context>()
            every { capabilities.grants } returns MutableStateFlow(emptyMap())
            every { context.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            coEvery { manager.preview(any(), any(), any()) } returns PluginPreviewResult.Success(preview())
            coEvery { manager.install("preview-token") } returns PluginInstallResult.Success(installed())
            viewModel = NativePluginViewModel(manager, mockk(), mockk(), mockk(), capabilities, mockk(), mockk(), context)
                .also { viewModels += it }
        }
    }

    private fun manager(): PluginManager = mockk<PluginManager>().also { manager ->
        every { manager.plugins } returns MutableStateFlow(listOf(installed()))
        every { manager.snapshot } returns MutableStateFlow(PluginSnapshot(plugins = listOf(installed())))
        coEvery { manager.ensureLoaded() } returns Unit
        coEvery { manager.discardPreview(any()) } returns Unit
    }

    private suspend fun <T> StateFlow<T>.awaitValue(predicate: (T) -> Boolean): T = awaitIo { first(predicate) }
    private suspend fun <T> awaitIo(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(5_000) { block() } }

    companion object {
        private const val ID = "test.sample"
        private val HASH = "a".repeat(64)
        private val SOURCE = PluginIndexSource("test-source", "Test source", "https://example.invalid/index.json", false)
        private val ASSET = PluginReleaseAsset("1.0.0", "${ID}_1.0.0.zip", "https://example.invalid/archive.zip", 3)
        private fun item(id: String = ID) = PluginStoreItem(
            PluginIndexPlugin(id, LocalizedText.single(id), LocalizedText.single("Plugin description"), "Publisher", "https://example.invalid/repository", version = "1.0.0"),
            SOURCE, null, false, true
        )
        private fun manifest() = PluginManifest(1, ID, LocalizedText.single("Sample plugin"), SemVer(1, 0, 0),
            "Publisher", "https://example.invalid/repository", LocalizedText.single("Plugin description"), "MIT", "1.0.0")
        private fun installed() = InstalledPlugin(
            PluginRecord(ID, "1.0.0", false, 1, archiveSha256 = HASH, manifestSha256 = HASH,
                installRevision = "revision-one", contentHash = HASH, fileHashes = emptyMap()),
            PluginStatus.DISABLED, manifest()
        )
        private fun risks() = PluginRiskSummary(true, false, false, true, false, false, false)
        private fun preview() = PluginPreview("preview-token", manifest(), PluginContent(), null, HASH, HASH, HASH, risks(), Long.MAX_VALUE)
    }
}
