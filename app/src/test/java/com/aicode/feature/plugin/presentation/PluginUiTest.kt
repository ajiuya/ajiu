package com.aicode.feature.plugin.presentation

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginError
import com.aicode.feature.plugin.domain.PluginErrorCode
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.runtime.PluginActions
import com.aicode.feature.plugin.runtime.PluginHostGateway
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginPreview
import com.aicode.feature.plugin.domain.PluginRiskSummary
import com.aicode.feature.plugin.domain.PluginSnapshot
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.domain.PluginTrustPreview
import com.aicode.feature.plugin.domain.PluginUiEntry
import com.aicode.feature.plugin.domain.PluginWorkspaceTool
import com.aicode.feature.plugin.domain.SemVer
import com.aicode.feature.plugin.runtime.PluginApprovalOperation
import com.aicode.feature.plugin.runtime.PluginApprovalChoice
import com.aicode.feature.plugin.runtime.PluginHostApproval
import com.aicode.feature.plugin.runtime.PluginDependencyRequest
import java.io.File
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.TextLayoutMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1200dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PluginUiTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test(timeout = 30_000)
    fun previewShowsMetadataRiskAndOnlyInstallsByPreviewToken(): Unit = render {
        screen = { PluginPreviewDialog(preview(), "en", false, { installed += it }, { dismissed++ }) }
        pump(18)
        assertTextVisible("Sample plugin")
        assertTextVisible("Declared publisher")
        assertTextVisible("Sample publisher")
        assertTextVisible("https://example.invalid/repository")
        assertTextVisible(text(R.string.plugin_metadata_warning))
        assertTextVisible(text(R.string.plugin_install_disabled))
        assertTextVisible(text(R.string.plugin_process_warning))
        assertTextVisible(text(R.string.plugin_page_security_hint))
        assertFalse(hasText(text(R.string.plugin_trust_enable)))
        click(R.string.plugin_install)
        assertEquals(listOf("preview-token"), installed)
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun replacementPreviewExplainsTrustResetEvenForTheSameVersion(): Unit = render {
        screen = { PluginPreviewDialog(preview().copy(installedVersion = "1.0.0"), "en", false, { installed += it }, {}) }
        pump(18)
        assertTrue(hasText(text(R.string.plugin_replacement_warning, "1.0.0", "1.0.0")))
        click(R.string.plugin_replace)
        assertEquals(listOf("preview-token"), installed)
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun installedDisabledDetailNeverRunsCodeOrEnablesOnComposition(): Unit = render {
        showDetail(installedPlugin())
        pump(18)
        assertTrue(hasText(text(R.string.plugin_disabled)))
        assertTrue(hasText(text(R.string.plugin_visual_enable_label)))
        assertFalse(hasText(text(R.string.plugin_disable)))
        assertEquals(ToggleableState.Off, tag("plugin-detail-switch-$ID").config[SemanticsProperties.ToggleableState])
        assertTrue(enabled.isEmpty())
        clickTag("plugin-detail-switch-$ID")
        assertEquals(listOf(ID), requestedTrust)
        assertEquals(ToggleableState.Off, tag("plugin-detail-switch-$ID").config[SemanticsProperties.ToggleableState])
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun trustRequiresAcknowledgementAndCancelDoesNotEnable(): Unit = render {
        showTrust()
        pump(18)
        assertDisabled(button(R.string.plugin_trust_enable))
        assertTrue(hasText(text(R.string.plugin_process_warning)))
        assertTrue(hasText(text(R.string.plugin_trust_revision_warning)))
        click(R.string.plugin_cancel)
        assertEquals(1, dismissed)
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun explicitTrustPassesBothPluginIdAndExactToken(): Unit = render {
        showTrust()
        pump(18)
        clickTag("plugin-trust-acknowledge")
        click(R.string.plugin_trust_enable)
        assertEquals(listOf(ID to "trust-one"), enabled)
    }

    @Test(timeout = 30_000)
    fun replacementRevisionRequiresFreshAcknowledgement(): Unit = render {
        showTrust()
        pump(18)
        clickTag("plugin-trust-acknowledge")
        assertEnabled(button(R.string.plugin_trust_enable))
        trust = trust.copy(token = "trust-two", installRevision = "revision-two", contentHash = "b".repeat(64))
        pump(12)
        assertDisabled(button(R.string.plugin_trust_enable))
        assertTrue(enabled.isEmpty())
        clickTag("plugin-trust-acknowledge")
        click(R.string.plugin_trust_enable)
        assertEquals(listOf(ID to "trust-two"), enabled)
    }

    @Test(timeout = 30_000)
    fun trustFailureNeverEnablesEvenAfterAcknowledgement(): Unit = render {
        trust = trust.copy(errors = listOf(PluginError(PluginErrorCode.CONTENT_CHANGED)))
        showTrust()
        pump(18)
        clickTag("plugin-trust-acknowledge")
        assertDisabled(button(R.string.plugin_trust_enable))
        assertTrue(hasText(text(R.string.plugin_error_stale)))
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun activeDetailTargetsDisableAndUninstallById(): Unit = render {
        showDetail(installedPlugin(active = true))
        pump(18)
        assertEquals(ToggleableState.On, tag("plugin-detail-switch-$ID").config[SemanticsProperties.ToggleableState])
        clickTag("plugin-detail-switch-$ID")
        assertFalse(hasText(text(R.string.plugin_uninstall)))
        click(R.string.plugin_center_maintenance)
        click(R.string.plugin_uninstall)
        assertEquals(listOf(ID), disabled)
        assertEquals(listOf(ID), requestedUninstall)
    }

    @Test(timeout = 30_000)
    fun uninstallKeepsPrivateDataByDefaultAndExplicitlyDeletesOnlyWhenSwitched(): Unit = render {
        screen = { PluginUninstallDialog(installedPlugin(), "en", false, { id, delete -> uninstalled += id to delete }, {}) }
        pump(18)
        click(R.string.plugin_uninstall)
        assertEquals(listOf(ID to false), uninstalled)
        clickTag("plugin-keep-data")
        click(R.string.plugin_uninstall)
        assertEquals(listOf(ID to false, ID to true), uninstalled)
    }

    @Test(timeout = 30_000)
    fun allPageKindsAndActionsOpenFromPluginDetailsWithExactReferences(): Unit = render {
        val workspace = PluginWorkspaceTool(ID, "workspace", LocalizedText.single("Workspace page"), LocalizedText.single("Workspace tool"), "play", "general", "workspace.html", ROOT, "1.0.0")
        val configuration = page("config")
        val plugin = installedPlugin(active = true).copy(content = PluginContent(uiEntries = listOf(pageEntry(), action()), configPage = configuration, workspaceTools = listOf(workspace)))
        showDetail(plugin)
        pump(18)
        clickTag("plugin-page-page")
        clickTag("plugin-page-config")
        clickTag("plugin-page-workspace")
        clickTag("plugin-action-action")
        val workspacePage = PluginPage(ID, "workspace", workspace.title, null, "workspace.html", "play", ROOT, "1.0.0")
        assertEquals(listOf(ID to page(), ID to configuration, ID to workspacePage), opened)
        assertSame(configuration, opened[1].second)
        assertEquals(1, nodes(false).count { it.config.getOrNull(SemanticsProperties.TestTag) == "plugin-page-config" })
        assertEquals(listOf(ID to action()), actions)
    }

    @Test(timeout = 30_000)
    fun disabledPagesAndActionsCannotBeInvoked(): Unit = render {
        showDetail(installedPlugin().copy(content = PluginContent(uiEntries = listOf(pageEntry(), action()))))
        pump(18)
        assertFalse(tag("plugin-page-page").config.contains(SemanticsActions.OnClick))
        assertFalse(tag("plugin-action-action").config.contains(SemanticsActions.OnClick))
        assertTrue(opened.isEmpty())
        assertTrue(actions.isEmpty())
    }

    @Test(timeout = 30_000)
    fun everyTypedDomainFailureHasAnExplicitLocalizedReason(): Unit = render {
        for (code in PluginErrorCode.entries) {
            problem = PluginError(code, "field-path").toProblem()
            screen = { PluginProblems(listOf(problem)) }
            pump(6)
            assertTrue(code.name, hasText(text(problem.messageRes)))
            assertTrue(hasText(text(R.string.plugin_error_detail, code.name, "field-path")))
        }
    }

    @Test(timeout = 30_000)
    fun unsupportedContributionsAreDisplayedAsARejectionNotSuccessfulInstall(): Unit = render {
        screen = { PluginProblems(listOf(PluginError(PluginErrorCode.UNSUPPORTED_CONTRIBUTION, "providers").toProblem())) }
        pump(12)
        assertTrue(hasText(text(R.string.plugin_error_unsupported)))
        assertFalse(hasText(text(R.string.plugin_install)))
        assertTrue(installed.isEmpty())
    }

    @Test(timeout = 30_000)
    fun listShowsRegistryCorruptionInsteadOfHidingItAsAnEmptySuccess(): Unit = render {
        screen = { PluginListContent(PluginSnapshot(errors = listOf(PluginError(PluginErrorCode.REGISTRY_CORRUPT))), "en", false, {}, {}, {}) }
        pump(12)
        assertTrue(hasText(text(R.string.plugin_error_registry)))
    }

    @Test(timeout = 30_000)
    fun listShortcutRequestsTrustWithoutOptimisticallyCheckingTheSwitch(): Unit = render {
        listSnapshot = PluginSnapshot(plugins = listOf(installedPlugin()))
        showList()
        pump(18)
        assertEquals(ToggleableState.Off, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
        clickTag("plugin-switch-$ID")
        assertEquals(listOf(ID), requestedTrust)
        assertEquals(ToggleableState.Off, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
        assertTrue(enabled.isEmpty())
        assertTrue(disabled.isEmpty())
        assertTrue(opened.isEmpty())
        showTrust()
        pump(18)
        click(R.string.plugin_cancel)
        showList()
        pump(18)
        assertEquals(1, dismissed)
        assertEquals(ToggleableState.Off, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
        assertEquals(listOf(ID), requestedTrust)
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun listSwitchDisablesOnlyItsExactPluginId(): Unit = render {
        val other = installedPlugin(active = true).let { it.copy(record = it.record.copy(id = "test.other"), manifest = it.manifest?.copy(id = "test.other")) }
        listSnapshot = PluginSnapshot(plugins = listOf(installedPlugin(active = true), other))
        showList()
        pump(18)
        assertEquals(ToggleableState.On, tag("plugin-switch-test.other").config[SemanticsProperties.ToggleableState])
        clickTag("plugin-switch-test.other")
        assertEquals(listOf("test.other"), disabled)
        assertTrue(requestedTrust.isEmpty())
        assertEquals(ToggleableState.On, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
    }

    @Test(timeout = 30_000)
    fun listSwitchCheckedStateRequiresIsActiveRatherThanTheStoredEnabledFlag(): Unit = render {
        val untrusted = installedPlugin(active = true).let { it.copy(record = it.record.copy(trustHash = null)) }
        assertTrue(untrusted.record.enabled)
        assertFalse(untrusted.isActive)
        listSnapshot = PluginSnapshot(plugins = listOf(untrusted))
        showList()
        pump(18)
        assertEquals(ToggleableState.Off, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
        clickTag("plugin-switch-$ID")
        assertEquals(listOf(ID), requestedTrust)
        assertTrue(disabled.isEmpty())
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun listConfigurationIsInertUntilActiveThenPassesTheExactPageReference(): Unit = render {
        val configuration = page("configuration")
        val plugin = installedPlugin().copy(content = PluginContent(configPage = configuration))
        listSnapshot = PluginSnapshot(plugins = listOf(plugin))
        showList()
        pump(18)
        assertDisabled(tag("plugin-configure-$ID"))
        assertTrue(opened.isEmpty())
        listSnapshot = PluginSnapshot(plugins = listOf(installedPlugin(active = true).copy(content = plugin.content)))
        pump(12)
        clickTag("plugin-configure-$ID")
        assertEquals(listOf(ID to configuration), opened)
        assertSame(configuration, opened.single().second)
        assertTrue(requestedTrust.isEmpty())
    }

    @Test(timeout = 30_000)
    fun disabledDetailConfigurationCannotBeInvoked(): Unit = render {
        showDetail(installedPlugin().copy(content = PluginContent(configPage = page("configuration"))))
        pump(18)
        assertDisabled(tag("plugin-page-configuration"))
        assertTrue(opened.isEmpty())
    }

    @Test(timeout = 30_000)
    fun installedSearchAndStatusFiltersNeverHideRegistryErrors(): Unit = render {
        val active = installedPlugin(active = true)
        val disabledPlugin = installedPlugin().let { it.copy(record = it.record.copy(id = "test.disabled"), manifest = it.manifest?.copy(id = "test.disabled")) }
        val invalid = installedPlugin().let { it.copy(record = it.record.copy(id = "test.invalid"), status = PluginStatus.INVALID, errors = listOf(PluginError(PluginErrorCode.CONTENT_CHANGED))) }
        listSnapshot = PluginSnapshot(plugins = listOf(active, disabledPlugin, invalid), errors = listOf(PluginError(PluginErrorCode.REGISTRY_CORRUPT)))
        showList()
        pump(18)
        edit("plugin-installed-search", "missing plugin")
        assertTrue(hasText(text(R.string.plugin_error_registry)))
        assertTrue(hasText(text(R.string.plugin_center_no_results)))
        edit("plugin-installed-search", "")
        clickTag("plugin-filter-enabled")
        assertTrue(hasTag("plugin-row-$ID"))
        assertFalse(hasTag("plugin-row-test.disabled"))
        assertFalse(hasTag("plugin-row-test.invalid"))
        assertTextVisible(text(R.string.plugin_error_registry))
        clickTag("plugin-filter-disabled")
        assertTrue(hasTag("plugin-row-test.disabled"))
        assertFalse(hasTag("plugin-row-$ID"))
        clickTag("plugin-filter-invalid")
        assertTrue(hasTag("plugin-row-test.invalid"))
        assertTextVisible(text(R.string.plugin_error_stale))
        assertTextVisible(text(R.string.plugin_error_registry))
    }

    @Test(timeout = 30_000)
    fun storeCardOpensAnIntroductionWithoutDownloadingAndRequiresExplicitPreview(): Unit = render {
        val item = storeItem()
        storeState = PluginStoreUiState(loading = false, items = listOf(item))
        showStore()
        pump(18)
        clickTag("plugin-store-item-${item.key}")
        assertTrue(downloads.isEmpty())
        assertTextVisible(text(R.string.plugin_center_introduction))
        assertTextVisible("Declared publisher")
        assertTextVisible("Sample publisher")
        assertTextVisible("https://example.invalid/repository")
        assertTrue(downloads.isEmpty())
        clickTag("plugin-store-preview-${item.key}")
        assertEquals(listOf(item), downloads)
        assertSame(item, downloads.single())
        assertTrue(managed.isEmpty())
    }

    @Test(timeout = 30_000)
    fun chineseStoreMetadataStaysLocalizedInCardAndIntroductionWithoutDownloading(): Unit = render {
        val englishName = "Desktop Pet"
        val chineseName = "桌面宠物"
        val englishDescription = "A draggable, interactive desktop pet that reacts to AI run status. Long press for the menu; size and display timing are configurable."
        val chineseDescription = "可拖拽、可点击互动的桌面宠物：跟随 AI 运行状态变化，长按打开菜单，可在配置页调整大小与显示时机。"
        val item = storeItem("jieapi.desktop-pet").let { it.copy(plugin = it.plugin.copy(
            name = LocalizedText(mapOf("default" to englishName, "zh-cn" to chineseName)),
            description = LocalizedText(mapOf("default" to englishDescription, "zh-cn" to chineseDescription))
        )) }
        storeState = PluginStoreUiState(loading = false, items = listOf(item))
        screen = {
            PluginStoreContent(state = storeState, languageTag = "zh", onRefresh = {},
                onDownload = { downloads += it }, onCancel = { downloadCancelled++ },
                onManage = { managed += it }, nativeBusy = nativeBusy)
        }
        pump(18)
        assertTextVisible(chineseName)
        assertTextVisible(chineseDescription)
        val card = tag("plugin-store-item-${item.key}")
        assertTrue(containsText(card, chineseName))
        assertTrue(containsText(card, chineseDescription))
        assertFalse(hasText(englishName))
        assertFalse(hasText(englishDescription))
        assertTrue(downloads.isEmpty())
        clickTag("plugin-store-item-${item.key}")
        assertTextVisible(text(R.string.plugin_center_introduction))
        assertTextVisible(chineseName)
        assertTextVisible(chineseDescription)
        val introduction = tag("plugin-store-introduction", merged = false)
        assertTrue(containsText(introduction, chineseName))
        assertTrue(containsText(introduction, chineseDescription))
        assertFalse(hasText(englishName))
        assertFalse(hasText(englishDescription))
        assertTrue(downloads.isEmpty())
        assertTrue(managed.isEmpty())
        assertTrue(installed.isEmpty())
        assertTrue(requestedTrust.isEmpty())
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun storeGetButtonDownloadsTheExactItem(): Unit = render {
        val item = storeItem()
        storeState = PluginStoreUiState(loading = false, items = listOf(item))
        showStore()
        pump(18)
        clickTag("plugin-store-get-${item.key}")
        assertEquals(listOf(item), downloads)
        assertSame(item, downloads.single())
        assertTrue(managed.isEmpty())
    }

    @Test(timeout = 30_000)
    fun installedStoreManageTargetsPluginWithoutDownloading(): Unit = render {
        val item = storeItem().copy(installedVersion = "1.0.0")
        storeState = PluginStoreUiState(loading = false, items = listOf(item))
        showStore()
        pump(18)
        clickTag("plugin-store-manage-${item.key}")
        assertEquals(listOf(ID), managed)
        assertTrue(downloads.isEmpty())
    }

    @Test(timeout = 30_000)
    fun installedStoreItemWithoutPinnedVersionKeepsManageAndCanExplicitlyDownloadFromIntroduction(): Unit = render {
        val item = storeItem().let { it.copy(plugin = it.plugin.copy(version = null), installedVersion = "1.0.0") }
        storeState = PluginStoreUiState(loading = false, items = listOf(item))
        showStore()
        pump(18)
        assertFalse(hasTag("plugin-store-get-${item.key}"))
        assertFalse(hasText(text(R.string.plugin_store_update)))
        clickTag("plugin-store-manage-${item.key}")
        assertEquals(listOf(ID), managed)
        assertTrue(downloads.isEmpty())
        clickTag("plugin-store-item-${item.key}")
        assertTextVisible(text(R.string.plugin_center_introduction))
        assertTextVisible(text(R.string.plugin_center_get_preview))
        assertFalse(hasText(text(R.string.plugin_store_update)))
        assertTrue(downloads.isEmpty())
        clickTag("plugin-store-preview-${item.key}")
        assertEquals(listOf(item), downloads)
        assertSame(item, downloads.single())
        assertEquals(listOf(ID), managed)
        assertTrue(requestedTrust.isEmpty())
        assertTrue(enabled.isEmpty())
    }

    @Test(timeout = 30_000)
    fun storeUpdateButtonDownloadsTheExactUpdateItem(): Unit = render {
        val item = storeItem().copy(installedVersion = "0.9.0", updateAvailable = true)
        storeState = PluginStoreUiState(loading = false, items = listOf(item))
        showStore()
        pump(18)
        assertTrue(hasText(text(R.string.plugin_store_update)))
        clickTag("plugin-store-get-${item.key}")
        assertEquals(listOf(item), downloads)
        assertSame(item, downloads.single())
        assertTrue(managed.isEmpty())
    }

    @Test(timeout = 30_000)
    fun storeItemErrorsAndRetryBelongOnlyToTheirOwnCard(): Unit = render {
        val first = storeItem()
        val second = storeItem("test.other")
        val problem = PluginProblem("DOWNLOAD_FAILED", R.string.plugin_store_download_error)
        storeState = PluginStoreUiState(loading = false, items = listOf(first, second), itemProblems = mapOf(first.key to problem))
        showStore()
        pump(18)
        assertTrue(containsText(tag("plugin-store-item-${first.key}"), text(R.string.plugin_visual_download_failed)))
        assertFalse(containsText(tag("plugin-store-item-${second.key}"), text(R.string.plugin_visual_download_failed)))
        assertFalse(hasTag("plugin-store-diagnostics-${first.key}"))
        assertFalse(hasTag("plugin-store-retry-${second.key}"))
        clickTag("plugin-store-problem-${first.key}")
        assertTrue(containsText(tag("plugin-store-diagnostics-${first.key}", merged = false), text(problem.messageRes)))
        assertTrue(containsText(tag("plugin-store-diagnostics-${first.key}", merged = false), text(R.string.plugin_error_detail, problem.code, problem.detail)))
        assertTrue(containsText(tag("plugin-store-item-${first.key}"), text(problem.messageRes)))
        assertFalse(containsText(tag("plugin-store-item-${second.key}"), text(problem.messageRes)))
        assertFalse(hasTag("plugin-store-diagnostics-${second.key}"))
        clickTag("plugin-store-retry-${first.key}")
        assertEquals(listOf(first), downloads)
        edit("plugin-store-search", "test.other")
        assertTrue(hasTag("plugin-store-item-${second.key}"))
        assertFalse(hasTag("plugin-store-item-${first.key}"))
        assertFalse(hasText(text(problem.messageRes)))
    }

    @Test(timeout = 30_000)
    fun storeBusyPreventsRepeatedGetsAndOffersCancellationOnlyForTheDownloadingCard(): Unit = render {
        val first = storeItem()
        val second = storeItem("test.other")
        storeState = PluginStoreUiState(loading = false, items = listOf(first, second))
        showStore()
        pump(18)
        clickTag("plugin-store-get-${first.key}")
        storeState = storeState.copy(downloading = first.key)
        pump(12)
        assertFalse(hasTag("plugin-store-get-${first.key}"))
        assertDisabled(tag("plugin-store-get-${second.key}"))
        assertTrue(hasTag("plugin-store-cancel-${first.key}"))
        assertFalse(hasTag("plugin-store-cancel-${second.key}"))
        assertEquals(listOf(first), downloads)
        clickTag("plugin-store-cancel-${first.key}")
        assertEquals(1, downloadCancelled)
        assertEquals(listOf(first), downloads)
        storeState = storeState.copy(downloading = null)
        nativeBusy = true
        pump(12)
        assertDisabled(tag("plugin-store-get-${first.key}"))
        assertDisabled(tag("plugin-store-get-${second.key}"))
        storeState = storeState.copy(preview = PluginStorePreview(preview(), "https://example.invalid/archive.zip"))
        nativeBusy = false
        pump(12)
        assertDisabled(tag("plugin-store-get-${first.key}"))
        assertDisabled(tag("plugin-store-get-${second.key}"))
        assertEquals(listOf(first), downloads)
    }

    @Test(timeout = 30_000)
    fun storeSearchAndCategoriesKeepIndexFailuresVisible(): Unit = render {
        val problem = PluginProblem("INDEX_FAILED", R.string.plugin_store_error)
        storeState = PluginStoreUiState(loading = false, items = listOf(storeItem()), problem = problem)
        showStore()
        pump(18)
        edit("plugin-store-search", "no matches")
        assertTextVisible(text(problem.messageRes))
        assertTextVisible(text(R.string.plugin_store_empty))
        edit("plugin-store-search", "")
        clickTag("plugin-store-filter-ui")
        assertTextVisible(text(problem.messageRes))
        assertFalse(hasTag("plugin-store-item-${storeItem().key}"))
        clickTag("plugin-store-filter-tool")
        assertTrue(hasTag("plugin-store-item-${storeItem().key}"))
        assertTextVisible(text(problem.messageRes))
    }

    @Test(timeout = 30_000)
    fun busyInstalledSwitchAndConfigurationCannotStartNativeOperations(): Unit = render {
        listSnapshot = PluginSnapshot(plugins = listOf(installedPlugin(active = true).copy(content = PluginContent(configPage = page("configuration")))))
        listBusy = true
        showList()
        pump(18)
        assertDisabled(tag("plugin-switch-$ID"))
        assertDisabled(tag("plugin-configure-$ID"))
        assertTrue(disabled.isEmpty())
        assertTrue(requestedTrust.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test(timeout = 30_000)
    fun centerDefaultsToMineAndRestoresTabSearchAndFiltersFromSavedState(): Unit = render {
        val native = mockk<NativePluginViewModel>()
        val store = mockk<PluginStoreViewModel>()
        every { native.snapshot } returns MutableStateFlow(PluginSnapshot(plugins = listOf(installedPlugin())))
        every { native.state } returns MutableStateFlow(NativePluginUiState())
        every { native.capabilityGrants } returns MutableStateFlow(emptyMap())
        every { store.state } returns MutableStateFlow(PluginStoreUiState(loading = false, items = listOf(storeItem())))
        var registry = SaveableStateRegistry(null) { true }
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                PluginManagementTabs(native, store, "en", {}, {})
            }
        }
        screen = content
        pump(18)
        assertEquals(true, tag("plugin-center-mine").config[SemanticsProperties.Selected])
        edit("plugin-installed-search", "sample")
        clickTag("plugin-filter-disabled")
        clickTag("plugin-center-discover")
        edit("plugin-store-search", "test.sample")
        clickTag("plugin-store-filter-tool")
        clickTag("plugin-center-mine")
        assertEquals("sample", tag("plugin-installed-search").config[SemanticsProperties.EditableText].text)
        assertEquals(true, tag("plugin-filter-disabled").config[SemanticsProperties.Selected])
        clickTag("plugin-center-discover")
        assertEquals("test.sample", tag("plugin-store-search").config[SemanticsProperties.EditableText].text)
        assertEquals(true, tag("plugin-store-filter-tool").config[SemanticsProperties.Selected])
        val saved = registry.performSave()
        screen = {}
        pump(12)
        registry = SaveableStateRegistry(saved) { true }
        screen = content
        pump(18)
        assertEquals(true, tag("plugin-center-discover").config[SemanticsProperties.Selected])
        assertEquals("test.sample", tag("plugin-store-search").config[SemanticsProperties.EditableText].text)
        assertEquals(true, tag("plugin-store-filter-tool").config[SemanticsProperties.Selected])
        clickTag("plugin-center-mine")
        assertEquals("sample", tag("plugin-installed-search").config[SemanticsProperties.EditableText].text)
        assertEquals(true, tag("plugin-filter-disabled").config[SemanticsProperties.Selected])
    }

    @Test(timeout = 30_000)
    fun nativeInstallReturnsToMineWithClearedSearchAndFiltersWithoutEnabling(): Unit = installedEvent(nativeInstall = true)

    @Test(timeout = 30_000)
    fun storeInstallReturnsToMineWithClearedSearchAndFiltersWithoutEnabling(): Unit = installedEvent(nativeInstall = false)

    @Test(timeout = 30_000)
    fun permissionOperationFailureIsRenderedInsideThePermissionsSheet(): Unit = render {
        val native = mockk<NativePluginViewModel>()
        val store = mockk<PluginStoreViewModel>()
        val problem = PluginError(PluginErrorCode.STORAGE_FAILED).toProblem()
        every { native.snapshot } returns MutableStateFlow(PluginSnapshot())
        every { native.state } returns MutableStateFlow(NativePluginUiState(
            managementPanel = PluginManagementPanel.PERMISSIONS, problems = listOf(problem)
        ))
        every { native.capabilityGrants } returns MutableStateFlow(emptyMap())
        every { store.state } returns MutableStateFlow(PluginStoreUiState(loading = false))
        screen = { PluginManagementTabs(native, store, "en", {}, {}) }
        pump(18)
        assertTextVisible(text(problem.messageRes))
        assertTrue(containsText(tag("plugin-management-permissions"), text(problem.messageRes)))
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontDarkPreviewKeepsItsCtaVisibleAndStoreCategoriesReachable(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        screen = { PluginPreviewDialog(preview(), "en", false, { installed += it }, { dismissed++ }) }
        pump(18)
        settleLayout()
        val install = button(R.string.plugin_install)
        assertWholeNodeVisible(install)
        val installTop = install.positionInRoot.y
        val body = scrollNode(vertical = true)
        assertTrue(requireNotNull(body.config[SemanticsActions.ScrollBy].action).invoke(0f, 200f))
        settleLayout()
        assertWholeNodeVisible(button(R.string.plugin_install))
        assertEquals(installTop, button(R.string.plugin_install).positionInRoot.y, 0.5f)
        click(R.string.plugin_install)
        assertEquals(listOf("preview-token"), installed)
        assertTrue(enabled.isEmpty())
        screen = {}
        pump(12)
        storeState = PluginStoreUiState(loading = false, items = listOf(storeItem()))
        showStore()
        pump(18)
        settleLayout()
        listOf("all", "tool", "integration", "ui").forEach { category ->
            val testTag = "plugin-store-filter-$category"
            assertWholeNodeVisible(tag(testTag))
            clickTag(testTag)
            assertEquals(true, tag(testTag).config[SemanticsProperties.Selected])
        }
        assertTextVisible(text(R.string.plugin_store_empty))
        assertTrue(downloads.isEmpty())
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun storeCategoriesIn180DpPanelActuallyScrollAndKeepTheLastCategorySelectable(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        storeState = PluginStoreUiState(loading = false, items = listOf(storeItem()))
        screen = {
            Box {
                Box(Modifier.width(180.dp).testTag("plugin-test-store-panel")) {
                    PluginStoreContent(storeState, "en", {}, { downloads += it }, { downloadCancelled++ },
                        onManage = { managed += it }, nativeBusy = nativeBusy)
                }
            }
        }
        pump(18)
        settleLayout()
        assertEquals(180, tag("plugin-test-store-panel").size.width)
        val categories = scrollNode(vertical = false)
        val before = categories.config[SemanticsProperties.HorizontalScrollAxisRange].value()
        assertTrue(requireNotNull(categories.config[SemanticsActions.ScrollBy].action).invoke(200f, 0f))
        settleLayout()
        val scrolled = scrollNode(vertical = false)
        assertTrue(scrolled.config[SemanticsProperties.HorizontalScrollAxisRange].value() > before)
        clickTag("plugin-store-filter-ui")
        assertEquals(true, tag("plugin-store-filter-ui").config[SemanticsProperties.Selected])
        assertWholeNodeVisible(tag("plugin-store-filter-ui"))
        assertTextVisible(text(R.string.plugin_store_empty))
        assertTrue(downloads.isEmpty())
    }

    @Test(timeout = 30_000)
    fun actionFormAcceptsOnlyJsonObjectsAndPassesParsedArguments(): Unit = render {
        screen = { PluginActionDialog(action(), "en", false, { arguments += it }, {}) }
        pump(18)
        for (invalid in listOf("[]", "null", "true", "{", "\"text\"")) {
            edit("plugin-action-arguments", invalid)
            assertDisabled(button(R.string.plugin_run_action))
        }
        edit("plugin-action-arguments", "{\"value\":\"quote\\\"\\nline\"}")
        click(R.string.plugin_run_action)
        assertEquals("quote\"\nline", (arguments.single()["value"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test(timeout = 30_000)
    fun dependencyPreparationRequiresItsOwnNativeConfirmationAndExactRequestId(): Unit = render {
        val request = PluginDependencyRequest("dependency-request", ID, "revision-one", 0, HASH, "npm", Long.MAX_VALUE)
        screen = { PluginDependencyDialog(request, "Sample plugin", false, { dependencyConfirmations += it }, { dismissed++ }) }
        pump(18)
        assertTrue(hasText(text(R.string.plugin_dependencies_warning)))
        assertTrue(hasText("npm"))
        assertTrue(dependencyConfirmations.isEmpty())
        click(R.string.plugin_dependencies_confirm)
        assertEquals(listOf("dependency-request"), dependencyConfirmations)
        assertDisabled(button(R.string.plugin_dependencies_confirm))
    }

    @Test(timeout = 30_000)
    fun cancellingDependencyPreparationDoesNotCreateAConfirmation(): Unit = render {
        val request = PluginDependencyRequest("dependency-request", ID, "revision-one", 0, HASH, "pip", Long.MAX_VALUE)
        screen = { PluginDependencyDialog(request, "Sample plugin", false, { dependencyConfirmations += it }, { dismissed++ }) }
        pump(18)
        click(R.string.plugin_cancel)
        assertTrue(dependencyConfirmations.isEmpty())
        assertEquals(1, dismissed)
    }

    @Test(timeout = 30_000)
    fun nativeApprovalShowsIdentityOperationAndAllowsOnlyOnce(): Unit = render {
        screen = { PluginApprovalDialog(approval, { id, allow -> decisions += id to allow }) }
        pump(18)
        assertTrue(hasText(text(R.string.plugin_approval_identity, "Sample plugin", ID)))
        assertTrue(hasText(text(R.string.plugin_approval_execute_command)))
        assertTrue(hasText("echo container"))
        click(R.string.plugin_approval_allow_once)
        assertDisabled(button(R.string.plugin_approval_allow_once))
        assertEquals(listOf("approval-id" to PluginApprovalChoice.ONCE), decisions)
    }

    @Test(timeout = 30_000)
    fun nativeApprovalDenyAndCloseBothRejectTheCurrentRequest(): Unit = render {
        screen = { PluginApprovalDialog(approval, { id, allow -> decisions += id to allow }) }
        pump(18)
        click(R.string.plugin_approval_deny)
        assertEquals(listOf("approval-id" to PluginApprovalChoice.REJECT), decisions)
        approval = approval.copy(id = "approval-next")
        pump(12)
        requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed()
        pump(6)
        assertEquals(listOf("approval-id" to PluginApprovalChoice.REJECT, "approval-next" to PluginApprovalChoice.REJECT), decisions)
    }

    @Test(timeout = 30_000)
    fun alwaysApprovalDisplaysCompleteScopeAndReturnsTheThirdChoice(): Unit = render {
        val scope = "Plugin sample · read /workspace/project/source with current installation identity"
        approval = approval.copy(canAlwaysAllow = true, scopeSummary = scope)
        screen = { PluginApprovalDialog(approval, { id, choice -> decisions += id to choice }) }
        pump(18)
        assertTrue(hasText(scope))
        click(R.string.plugin_approval_always)
        assertEquals(listOf("approval-id" to PluginApprovalChoice.ALWAYS), decisions)
        assertDisabled(button(R.string.plugin_approval_always))
    }

    @Test(timeout = 30_000)
    fun oneTimeOnlyApprovalNeverOffersAlwaysAllow(): Unit = render {
        screen = { PluginApprovalDialog(approval, { id, choice -> decisions += id to choice }) }
        pump(18)
        assertFalse(hasText(text(R.string.plugin_approval_always)))
    }

    @Test(timeout = 30_000)
    fun standaloneComponentsWithoutAPluginSlotHostDoNotRequireInjectedDependencies(): Unit = render {
        screen = {
            androidx.compose.foundation.layout.Column {
                PluginSlotContent("drawer.footer")
                PluginSlotContent("files.tree.menu")
                androidx.compose.material3.Text("Standalone component")
            }
        }
        pump(18)
        assertTrue(hasText("Standalone component"))
        assertFalse(hasText("Sample action"))
    }

    @Test(timeout = 30_000)
    fun nativeSlotHostRendersOnlyItsSlotAndDispatchesWithTheClickTimeContext(): Unit = render {
        val entry = action()
        val other = entry.copy(localId = "other", slot = "settings.help", title = LocalizedText.single("Other slot"))
        val plugin = installedPlugin(active = true).copy(content = PluginContent(uiEntries = listOf(entry, other)))
        val root = temporary.newFolder()
        val capture = PluginCapture(plugin.record, plugin.manifest!!, plugin.content, root, {}, {})
        val invocation = PluginInvocationContext(capture, null, "/work", "session-original", root, emptyFlow(), sourceIdentity = "fixed-source") {}
        val manager = mockk<PluginManager>()
        val gateway = mockk<PluginHostGateway>()
        val actions = mockk<PluginActions>()
        every { manager.snapshot } returns MutableStateFlow(PluginSnapshot(plugins = listOf(plugin)))
        every { manager.capture(ID) } returns capture
        coEvery { manager.ensureLoaded() } returns Unit
        coEvery { gateway.captureContext(capture, "session-original", null, null) } returns invocation
        coEvery { actions.invoke(capture, "action.run", any(), invocation) } returns JsonPrimitive("finished")
        val host = PluginSlotHost(manager, gateway, actions)
        var slotArgs by mutableStateOf(buildJsonObject { put("sessionId", "session-original"); put("path", "/work/original.txt") })
        try {
            screen = { CompositionLocalProvider(LocalPluginSlotHost provides host) { PluginSlotContent("settings.general", slotArgs) } }
            pump(18)
            assertTrue(hasText("Sample action"))
            assertFalse(hasText("Other slot"))
            clickText("Sample action")
            pump(12)
            assertTrue(hasText(text(R.string.plugin_action_arguments)))
            slotArgs = buildJsonObject { put("sessionId", "session-later"); put("path", "/work/later.txt") }
            pump(12)
            click(R.string.plugin_run_action)
            pump(12)
            coVerify(exactly = 1) { gateway.captureContext(capture, "session-original", null, null) }
            coVerify(exactly = 1) { actions.invoke(capture, "action.run", match {
                it["sessionId"] == JsonPrimitive("session-original") && it["path"] == JsonPrimitive("/work/original.txt")
            }, invocation) }
            assertTrue(hasText(text(R.string.plugin_action_result)))
        } finally { invocation.close() }
    }

    private fun installedEvent(nativeInstall: Boolean): Unit = render {
        val native = mockk<NativePluginViewModel>()
        val store = mockk<PluginStoreViewModel>()
        val previous = installedPlugin(active = true).let { it.copy(record = it.record.copy(id = "test.old"), manifest = it.manifest?.copy(id = "test.old")) }
        val snapshot = MutableStateFlow(PluginSnapshot(plugins = listOf(previous)))
        val nativeUi = MutableStateFlow(NativePluginUiState())
        val storeUi = MutableStateFlow(PluginStoreUiState(loading = false, items = listOf(storeItem())))
        every { native.snapshot } returns snapshot
        every { native.state } returns nativeUi
        every { native.capabilityGrants } returns MutableStateFlow(emptyMap())
        every { store.state } returns storeUi
        every { native.consumeInstalled() } answers { nativeUi.value = nativeUi.value.copy(installedId = null) }
        every { store.consumeInstalled() } answers { storeUi.value = storeUi.value.copy(installedId = null) }
        screen = { PluginManagementTabs(native, store, "en", {}, {}) }
        pump(18)
        edit("plugin-installed-search", "test.old")
        clickTag("plugin-filter-enabled")
        assertTrue(hasTag("plugin-row-test.old"))
        clickTag("plugin-center-discover")
        snapshot.value = PluginSnapshot(plugins = listOf(previous, installedPlugin()))
        if (nativeInstall) nativeUi.value = nativeUi.value.copy(installedId = ID, notice = R.string.plugin_install_disabled)
        else storeUi.value = storeUi.value.copy(installedId = ID, notice = R.string.plugin_install_disabled)
        pump(18)
        settleLayout()
        assertEquals(true, tag("plugin-center-mine").config[SemanticsProperties.Selected])
        assertEquals("", tag("plugin-installed-search").config[SemanticsProperties.EditableText].text)
        assertEquals(true, tag("plugin-filter-all").config[SemanticsProperties.Selected])
        assertWholeNodeVisible(tag("plugin-row-$ID"))
        assertEquals(ToggleableState.Off, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
        if (nativeInstall) {
            assertEquals(R.string.plugin_install_disabled, nativeUi.value.notice)
            assertEquals(null, nativeUi.value.installedId)
            verify(exactly = 1) { native.consumeInstalled() }
            verify(exactly = 0) { store.consumeInstalled() }
        } else {
            assertTextVisible(text(R.string.plugin_install_disabled))
            assertEquals(null, storeUi.value.installedId)
            verify(exactly = 1) { store.consumeInstalled() }
            verify(exactly = 0) { native.consumeInstalled() }
        }
        verify(exactly = 0) { native.prepareTrust(any()) }
        verify(exactly = 0) { native.enable(any(), any()) }
    }

    private class Ui(private val activity: ComponentActivity, private val view: ComposeView, val pump: (Int) -> Unit) {
        var screen by mutableStateOf<@Composable () -> Unit>({})
        var trust by mutableStateOf(trust())
        var approval by mutableStateOf(PluginHostApproval("approval-id", ID, "Sample plugin", PluginApprovalOperation.EXECUTE_COMMAND, "echo container"))
        var problem by mutableStateOf(PluginError(PluginErrorCode.CONTENT_CHANGED).toProblem())
        var listSnapshot by mutableStateOf(PluginSnapshot())
        var listBusy by mutableStateOf(false)
        var storeState by mutableStateOf(PluginStoreUiState(loading = false))
        var nativeBusy by mutableStateOf(false)
        val downloads = mutableListOf<PluginStoreItem>()
        val managed = mutableListOf<String>()
        var downloadCancelled = 0
        val installed = mutableListOf<String>()
        val requestedTrust = mutableListOf<String>()
        val enabled = mutableListOf<Pair<String, String>>()
        val disabled = mutableListOf<String>()
        val requestedUninstall = mutableListOf<String>()
        val uninstalled = mutableListOf<Pair<String, Boolean>>()
        val opened = mutableListOf<Pair<String, PluginPage>>()
        val actions = mutableListOf<Pair<String, PluginUiEntry>>()
        val decisions = mutableListOf<Pair<String, PluginApprovalChoice>>()
        val arguments = mutableListOf<JsonObject>()
        val dependencyConfirmations = mutableListOf<String>()
        var dismissed = 0
        fun showTrust() { screen = { PluginTrustDialog(trust, "en", false, { id, token -> enabled += id to token }, { dismissed++ }) } }
        fun showList() {
            screen = {
                PluginListContent(listSnapshot, "en", listBusy, {}, {}, {},
                    onEnable = { requestedTrust += it }, onDisable = { disabled += it },
                    onOpenPage = { id, page -> opened += id to page })
            }
        }
        fun showStore() {
            screen = {
                PluginStoreContent(storeState, "en", {}, { downloads += it }, { downloadCancelled++ },
                    onManage = { managed += it }, nativeBusy = nativeBusy)
            }
        }
        fun showDetail(plugin: InstalledPlugin) {
            screen = {
                PluginDetailContent(plugin, "en", false, { requestedTrust += it }, { disabled += it }, { requestedUninstall += it }, {},
                    { id, page -> opened += id to page }, { id, action -> actions += id to action }, {})
            }
        }
        fun text(resource: Int, vararg args: Any): String = activity.getString(resource, *args)
        fun nodes(merged: Boolean = true): Collection<SemanticsNode> {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
            val root = requireNotNull(findRoot(dialog ?: view))
            return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = merged)
        }
        fun hasText(text: String): Boolean = nodes(false).any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
        fun hasTag(testTag: String): Boolean = nodes(false).any { it.config.getOrNull(SemanticsProperties.TestTag) == testTag }
        fun containsText(node: SemanticsNode, value: String): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true || node.children.any { containsText(it, value) }
        fun assertTextVisible(value: String) {
            val node = visibleNode(merged = false) { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
            assertTrue("Text is clipped: $value\n${describe(node)}", node.boundsInRoot.width > 0 && node.boundsInRoot.height > 0)
        }
        fun tag(tag: String, merged: Boolean = true): SemanticsNode = visibleNode(merged = merged) { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        fun button(resource: Int): SemanticsNode = visibleNode { node ->
            node.config.contains(SemanticsActions.OnClick) && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text(resource) } == true
        }
        private fun visibleNode(merged: Boolean = true, matches: (SemanticsNode) -> Boolean): SemanticsNode {
            settleLayout()
            repeat(30) {
                val matching = nodes(merged).filter(matches)
                require(matching.size <= 1) { "Expected one semantics target, found ${matching.size}" }
                matching.singleOrNull()?.let { node ->
                    var ancestor = node.parent
                    while (ancestor != null) {
                        val current = ancestor
                        val scroll = current.config.getOrNull(SemanticsActions.ScrollBy)?.action
                        if (scroll != null) {
                            val viewport = current.boundsInRoot
                            val position = node.positionInRoot
                            val vertical = current.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                            val horizontal = current.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                            val dy = if (!vertical) 0f else when {
                                position.y < viewport.top -> position.y - viewport.top
                                position.y >= viewport.bottom -> position.y - viewport.top
                                node.size.height <= viewport.height && position.y + node.size.height > viewport.bottom -> position.y + node.size.height - viewport.bottom
                                else -> 0f
                            }
                            val dx = if (!horizontal) 0f else when {
                                position.x < viewport.left -> position.x - viewport.left
                                position.x >= viewport.right -> position.x - viewport.left
                                node.size.width <= viewport.width && position.x + node.size.width > viewport.right -> position.x + node.size.width - viewport.right
                                else -> 0f
                            }
                            if (dx != 0f || dy != 0f) {
                                assertTrue(scroll.invoke(dx, dy))
                                settleLayout()
                                return@repeat
                            }
                        }
                        ancestor = current.parent
                    }
                    return node
                }
                val scrollables = nodes(false).filter { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
                check(scrollables.isNotEmpty()) { "Semantics target is missing and no scroll container can reveal it" }
                if (it == 0) {
                    scrollables.forEach { node ->
                        val horizontal = node.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                        val vertical = node.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                        node.config.getOrNull(SemanticsActions.ScrollToIndex)?.action?.invoke(0)
                            ?: node.config[SemanticsActions.ScrollBy].action?.invoke(if (horizontal) -10_000f else 0f, if (vertical) -10_000f else 0f)
                    }
                } else {
                    val horizontal = scrollables.firstOrNull { node ->
                        node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { range -> range.value() < range.maxValue() } == true
                    }
                    if (horizontal != null) {
                        horizontal.config[SemanticsActions.ScrollBy].action?.invoke(200f, 0f)
                    } else {
                        scrollables.filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                            .forEach { it.config[SemanticsActions.ScrollBy].action?.invoke(0f, 320f) }
                    }
                }
                settleLayout()
            }
            error("Semantics target did not become visible after scrolling\n" + nodes(false).joinToString("\n") { describe(it) })
        }
        fun scrollNode(vertical: Boolean): SemanticsNode {
            val matches = nodes(false).filter { node ->
                val range = if (vertical) node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    else node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                range?.maxValue()?.let { it > 0f } == true
            }
            check(matches.size == 1) { "Expected one scrollable ${if (vertical) "body" else "category row"}, found ${matches.size}\n" + nodes(false).joinToString("\n") { describe(it) } }
            return matches.single()
        }
        fun settleLayout() {
            var previous: List<String>? = null
            var stableFrames = 0
            repeat(120) {
                pump(1)
                val geometry = nodes(false).map { node ->
                    "${node.id}:${node.positionInRoot}:${node.size}:" +
                        node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value() + ":" +
                        node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value()
                }
                stableFrames = if (geometry == previous) stableFrames + 1 else 0
                if (stableFrames >= 4) return
                previous = geometry
            }
            error("Layout did not settle\n" + nodes(false).joinToString("\n") { describe(it) })
        }
        fun assertWholeNodeVisible(node: SemanticsNode) {
            val window = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: view
            val bounds = Rect(node.positionInRoot.x, node.positionInRoot.y,
                node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height)
            assertTrue(describe(node), bounds.width > 0 && bounds.height > 0 && bounds.left >= 0f && bounds.top >= 0f && bounds.right <= window.width && bounds.bottom <= window.height)
            assertEquals(describe(node), bounds.width, node.boundsInRoot.width, 0.5f)
            assertEquals(describe(node), bounds.height, node.boundsInRoot.height, 0.5f)
        }
        private fun describe(node: SemanticsNode): String = buildString {
            append("dialog=").append(ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { "${it.width}x${it.height}" })
            var current: SemanticsNode? = node
            while (current != null) {
                append("\nnode=").append(current.id).append(" tag=").append(current.config.getOrNull(SemanticsProperties.TestTag))
                    .append(" text=").append(current.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text.take(80) })
                    .append(" pos=").append(current.positionInRoot).append(" size=").append(current.size)
                    .append(" clipped=").append(current.boundsInRoot)
                current.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { append(" v=").append(it.value()).append('/').append(it.maxValue()) }
                current.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.let { append(" h=").append(it.value()).append('/').append(it.maxValue()) }
                current = current.parent
            }
        }
        fun assertEnabled(node: SemanticsNode) { assertFalse(node.config.contains(SemanticsProperties.Disabled)) }
        fun assertDisabled(node: SemanticsNode) { assertTrue(node.config.contains(SemanticsProperties.Disabled)) }
        fun click(resource: Int) { clickNode(button(resource)) }
        fun clickText(value: String) { clickNode(visibleNode { node ->
            node.config.contains(SemanticsActions.OnClick) && node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }) }
        fun clickTag(testTag: String) { clickNode(tag(testTag)) }
        private fun clickNode(node: SemanticsNode) {
            assertEnabled(node)
            assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
            pump(6)
        }
        fun edit(testTag: String, value: String) {
            assertTrue(requireNotNull(tag(testTag).config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
            pump(6)
        }
    }

    private fun render(
        width: Int = 400,
        height: Int = 1200,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        inspect: Ui.() -> Unit
    ): Unit = runTest(timeout = 20.seconds) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        val view = ComposeView(activity)
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler) + clock) { recomposer.runRecomposeAndApplyChanges() }
        var frame = 0L
        fun layout(window: View) {
            window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            window.layout(0, 0, window.measuredWidth, window.measuredHeight)
            findRoot(window)?.measureAndLayoutForTest()
        }
        val ui = Ui(activity, view) { count -> repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            testScheduler.advanceTimeBy(16)
            Snapshot.sendApplyNotifications()
            runCurrent()
            layout(view)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::layout)
            if (clock.hasAwaiters) clock.sendFrame(++frame * 16_000_000L)
            runCurrent()
        } }
        try {
            view.setParentCompositionContext(recomposer)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) { AIEditorTheme(darkTheme = darkTheme) { ui.screen() } }
            }
            activity.setContentView(view)
            ui.pump(12)
            ui.inspect()
        } finally {
            view.disposeComposition()
            recomposer.cancel()
            runner.cancel()
            runCurrent()
            controller.pause().stop().destroy()
        }
    }

    companion object {
        private const val ID = "test.sample"
        private val HASH = "a".repeat(64)
        private val ROOT = File("/root/plugin-test/revision")
        private fun manifest() = PluginManifest(1, ID, LocalizedText.single("Sample plugin"), SemVer(1, 0, 0),
            "Sample publisher", "https://example.invalid/repository", LocalizedText.single("Plugin description"), "MIT", "1.0.0")
        private fun installedPlugin(active: Boolean = false): InstalledPlugin = InstalledPlugin(
            PluginRecord(ID, "1.0.0", active, 1, archiveSha256 = HASH, manifestSha256 = HASH,
                installRevision = "revision-one", contentHash = HASH, fileHashes = emptyMap(), trustHash = if (active) HASH else null),
            if (active) PluginStatus.ENABLED else PluginStatus.DISABLED, manifest()
        )
        private fun risks() = PluginRiskSummary(true, false, false, true, false, false, false)
        private fun preview() = PluginPreview("preview-token", manifest(), PluginContent(), null, HASH, HASH, HASH, risks(), Long.MAX_VALUE)
        private fun trust() = PluginTrustPreview("trust-one", ID, "revision-one", HASH, 0, Long.MAX_VALUE, manifest(), risks())
        private fun storeItem(id: String = ID) = PluginStoreItem(
            PluginIndexPlugin(id, LocalizedText.single("Store $id"), LocalizedText.single("Plugin description"), "Sample publisher", "https://example.invalid/repository", tags = listOf("tool"), version = "1.0.0"),
            PluginIndexSource("test-source", "Test source", "https://example.invalid/index.json", false), null, false, true
        )
        private fun page(id: String = "page") = PluginPage(ID, id, LocalizedText.single("Page $id"), null, "$id.html", "book", ROOT, "1.0.0")
        private fun pageEntry() = PluginUiEntry(ID, "page", LocalizedText.single("Page page"), "settings.general", "book", page = page())
        private fun action() = PluginUiEntry(ID, "action", LocalizedText.single("Sample action"), "settings.general", "play", handler = "action.run")
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
