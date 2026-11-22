package com.aicode.feature.plugin.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.isDebugInspectorInfoEnabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.aicode.R
import com.aicode.core.theme.AIEditorTheme
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.data.index.PluginIndexPlugin
import com.aicode.feature.plugin.data.index.PluginIndexSource
import com.aicode.feature.plugin.domain.InstalledPlugin
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginInstallOrigin
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.PluginNativeCapabilityChallenge
import com.aicode.feature.plugin.domain.PluginPage
import com.aicode.feature.plugin.domain.PluginSnapshot
import com.aicode.feature.plugin.domain.PluginStatus
import com.aicode.feature.plugin.domain.SemVer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w400dp-h1200dp", application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class PluginVisualLayoutTest {
    @Test(timeout = 30_000)
    fun legacyBitmapProbeRecordsActualProductionDrawWithoutCallingPartialRasterAScreenshot(): Unit = render {
        fun pixelCounts(bitmap: Bitmap): Pair<Int, Int> {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.count { it != Color.TRANSPARENT } to pixels.toSet().size
        }
        fun primitive(draw: (Canvas, Paint) -> Unit): Int {
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 24f })
                return pixelCounts(bitmap).first
            } finally { bitmap.recycle() }
        }
        val rectanglePixels = primitive { canvas, paint -> canvas.drawRect(8f, 8f, 56f, 56f, paint) }
        val roundedRectanglePixels = primitive { canvas, paint -> canvas.drawRoundRect(RectF(8f, 8f, 56f, 56f), 12f, 12f, paint) }
        val textPixels = primitive { canvas, paint -> canvas.drawText("Plugin", 4f, 36f, paint) }
        assertTrue("Bitmap pixel access must detect the supported rectangle control", rectanglePixels > 0)
        val completePrimitiveRaster = roundedRectanglePixels > 0 && textPixels > 0
        showCenter()
        pump(18)
        settle()
        val scenes = linkedMapOf<String, Pair<Pair<Int, Int>, Throwable?>>()
        fun capture(name: String) {
            settle()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val error = runCatching { view.draw(Canvas(bitmap)) }.exceptionOrNull()
                val counts = pixelCounts(bitmap)
                scenes[name] = counts to error
                if (completePrimitiveRaster && error == null && counts.second > 2) {
                    File(reportDirectory(), "$name-compose-capture.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            } finally { bitmap.recycle() }
        }
        capture("mine")
        click("plugin-center-discover")
        capture("discover")
        showDetail(plugin())
        pump(18)
        capture("detail")
        val measured = Paint().apply { textSize = 24f }.measureText("WW")
        val receipt = buildJsonObject {
            put("graphicsMode", "LEGACY")
            put("sdk", 28)
            put("os", System.getProperty("os.name"))
            put("architecture", System.getProperty("os.arch"))
            put("rectangleControlPixels", rectanglePixels)
            put("roundedRectanglePixels", roundedRectanglePixels)
            put("textGlyphPixels", textPixels)
            put("measureTextTwoWideGlyphsAt24px", measured)
            put("completePrimitiveRaster", completePrimitiveRaster)
            put("productionComposeDraws", buildJsonObject {
                scenes.forEach { (name, value) -> put(name, buildJsonObject {
                    put("nonTransparentPixels", value.first.first)
                    put("uniqueColors", value.first.second)
                    value.second?.let {
                        put("drawError", "${it.javaClass.name}: ${it.message}")
                        put("drawStackTrace", it.stackTraceToString())
                    }
                }) }
            })
            put("trueScreenshotExported", completePrimitiveRaster && scenes.values.all { it.second == null && it.first.second > 2 })
            put("semanticOrHtmlRedrawUsed", false)
            put("devicePixelEquivalenceClaimed", false)
            put("deviceScreenshotRequired", true)
            put("interpretation", if (completePrimitiveRaster) {
                "Production Compose Canvas captures require manual pixel review and are not Android device pixel baselines."
            } else {
                "The rectangle control writes pixels, but missing text or rounded-shape rasterization makes blank or partial production bitmaps unsuitable as UI screenshots. No screenshot PNG is exported."
            })
        }
        File(reportDirectory(), "bitmap-capability.json").writeText(receipt.toString())
        assertEquals("LEGACY drawText records history instead of glyph pixels", 0, textPixels)
        assertEquals("LEGACY drawRoundRect records history instead of rounded pixels", 0, roundedRectanglePixels)
        assertFalse(completePrimitiveRaster)
        assertEquals(setOf("mine", "discover", "detail"), scenes.keys)
        scenes.forEach { (name, observation) ->
            assertEquals("$name remains a blank Bitmap in this LEGACY renderer", 0 to 1, observation.first)
            val error = observation.second
            assertTrue("$name should preserve the observed unsupported-raster failure: $error", error is NullPointerException)
            assertTrue(requireNotNull(error).message.orEmpty().contains("Bitmap.setHasAlpha"))
            assertFalse(File(reportDirectory(), "$name-compose-capture.png").exists())
        }
    }

    @Test(timeout = 30_000)
    fun roundedSegmentsHaveExactlyTwoTabLayoutsAndNoIndicatorSibling(): Unit = render {
        showCenter()
        pump(18)
        settle()
        assertSegments()
        assertEquals(true, tag("plugin-center-mine").config[SemanticsProperties.Selected])
        assertEquals(false, tag("plugin-center-discover").config[SemanticsProperties.Selected])
        click("plugin-center-discover")
        assertSegments()
        assertEquals(false, tag("plugin-center-mine").config[SemanticsProperties.Selected])
        assertEquals(true, tag("plugin-center-discover").config[SemanticsProperties.Selected])
        recordGeometry("segments", "plugin-center-segments", "plugin-center-mine", "plugin-center-discover")
    }

    @Test(timeout = 30_000)
    fun installedCardKeepsIdentityAndSwitchInHeaderAndTwoTextActionsInFooter(): Unit = render {
        showList(plugin())
        pump(18)
        settle()
        assertInstalledCard()
        assertEquals(ToggleableState.On, tag("plugin-switch-$ID").config[SemanticsProperties.ToggleableState])
        click("plugin-configure-$ID")
        assertEquals(listOf(ID to page()), opened)
        click("plugin-detail-$ID")
        assertEquals(listOf(ID), selected)
        assertTrue(requestedTrust.isEmpty())
        assertTrue(disabled.isEmpty())
        recordGeometry("installed", "plugin-header-$ID", "plugin-name-$ID", "plugin-icon-$ID", "plugin-switch-$ID",
            "plugin-footer-$ID", "plugin-configure-$ID", "plugin-detail-$ID")
    }

    @Test(timeout = 30_000)
    fun storeGetRemainsRightOfIdentityInsideTheSameHeader(): Unit = render {
        val item = storeItem()
        showStore(item)
        pump(18)
        settle()
        assertStoreHeader(item)
        recordGeometry("discover", "plugin-store-item-${item.key}", "plugin-store-header-${item.key}",
            "plugin-store-name-${item.key}", "plugin-store-icon-${item.key}", "plugin-store-get-${item.key}")
    }

    @Test(timeout = 30_000)
    fun detailIdentityEnableAndPrimaryConfigurationShareOneCardInOrder(): Unit = render {
        showDetail(plugin())
        pump(18)
        settle()
        assertDetailIdentityCard()
        assertEquals(ToggleableState.On, tag("plugin-detail-switch-$ID").config[SemanticsProperties.ToggleableState])
        click("plugin-page-configuration")
        assertEquals(listOf(ID to page()), opened)
        assertTrue(requestedTrust.isEmpty())
        assertTrue(disabled.isEmpty())
        recordGeometry("detail", "plugin-detail-identity-card", "plugin-detail-identity", "plugin-detail-enable-row",
            "plugin-detail-switch-$ID", "plugin-page-configuration")
    }

    @Test(timeout = 30_000)
    fun detailMaintenanceAndSafetyStayCollapsedUntilExplicitlyOpened(): Unit = render {
        showDetail(plugin())
        pump(18)
        settle()
        listOf(R.string.plugin_uninstall, R.string.plugin_replace_zip, R.string.plugin_operation_grants_clear,
            R.string.plugin_process_warning).forEach { assertFalse(hasText(label(it))) }
        assertTrue(requestedUninstall.isEmpty())
        assertTrue(clearedGrants.isEmpty())
        click("plugin-detail-maintenance")
        listOf(R.string.plugin_uninstall, R.string.plugin_replace_zip, R.string.plugin_operation_grants_clear)
            .forEach { assertTrue(hasText(label(it))) }
        clickLabel(R.string.plugin_operation_grants_clear)
        assertEquals(listOf(ID), clearedGrants)
        assertTrue(requestedUninstall.isEmpty())
        click("plugin-detail-maintenance")
        assertFalse(hasText(label(R.string.plugin_uninstall)))
        click("plugin-detail-safety")
        assertTrue(hasText(label(R.string.plugin_process_warning)))
        assertTrue(requestedTrust.isEmpty())
    }

    @Test(timeout = 30_000)
    fun permissionStatusRowsRequestTheExistingNativeChallengeWithoutGranting(): Unit = render {
        showCapabilities(pluginWithAutostart())
        pump(18)
        settle()
        val row = tag("plugin-capability-AUTOSTART")
        val status = tag("plugin-capability-status-AUTOSTART")
        assertEquals(label(R.string.plugin_visual_not_granted), status.config[SemanticsProperties.Text].single().text)
        assertFalse(row.config.contains(SemanticsProperties.ToggleableState))
        assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch })
        assertDescendant(status, row)
        assertContained(status, row)
        val layout = layoutChildren(row.layoutInfo)
        assertEquals("Permission row keeps icon, identity, status, spacing and native chevron", 6, layout.size)
        assertTrue("Chevron remains after the status", layout.last().width > 0 && layout.last().height > 0)
        click("plugin-capability-AUTOSTART")
        assertEquals(listOf(ID to PluginCapability.AUTOSTART), requestedCapabilities)
        assertTrue(revokedCapabilities.isEmpty())
        assertEquals(label(R.string.plugin_visual_not_granted), tag("plugin-capability-status-AUTOSTART").config[SemanticsProperties.Text].single().text)
        assertFalse(hasTag("plugin-capability-acknowledge"))
        assertTrue(requestedTrust.isEmpty())
    }

    @Test(timeout = 30_000)
    fun grantedPermissionOpensDetailsAndRevokesOnlyAfterItsExplicitNativeButton(): Unit = render {
        showCapabilities(pluginWithAutostart(), granted = true)
        pump(18)
        settle()
        assertEquals(label(R.string.plugin_visual_granted), tag("plugin-capability-status-AUTOSTART").config[SemanticsProperties.Text].single().text)
        click("plugin-capability-AUTOSTART")
        assertTrue(hasTag("plugin-capability-detail-AUTOSTART"))
        assertTrue(revokedCapabilities.isEmpty())
        assertTrue(requestedCapabilities.isEmpty())
        assertTrue(requestedTrust.isEmpty())
        click("plugin-capability-revoke-AUTOSTART")
        assertEquals(listOf(ID to PluginCapability.AUTOSTART), revokedCapabilities)
        assertTrue(requestedCapabilities.isEmpty())
        assertFalse(hasTag("plugin-capability-detail-AUTOSTART"))
    }

    @Test(timeout = 30_000)
    fun permissionRowsAreInertForDisabledOrBusyPlugins(): Unit = render {
        listOf(false to false, true to true).forEach { (active, busy) ->
            screen = {}
            pump(8)
            showCapabilities(pluginWithAutostart(active), busy = busy)
            pump(18)
            settle()
            val row = tag("plugin-capability-AUTOSTART")
            assertTrue(row.config.contains(SemanticsProperties.Disabled))
            assertFalse(row.config.contains(SemanticsActions.OnClick))
            assertFalse(row.config.contains(SemanticsProperties.ToggleableState))
        }
        assertTrue(requestedCapabilities.isEmpty())
        assertTrue(revokedCapabilities.isEmpty())
        assertTrue(requestedTrust.isEmpty())
    }

    @Test(timeout = 30_000)
    fun nativeCapabilityDialogStillRequiresAcknowledgementAndTheExactChallengeToken(): Unit = render {
        val confirmed = mutableListOf<String>()
        val challenge = PluginNativeCapabilityChallenge("visual-native-challenge", ID, "visual-revision", HASH, 0,
            PluginCapability.AUTOSTART, "Launch on app startup", PluginInstallOrigin(), Long.MAX_VALUE)
        screen = { PluginCapabilityDialog(challenge, false, { confirmed += it }, {}) }
        pump(18)
        settle()
        assertTrue(hasText(label(R.string.plugin_capability_warning)))
        assertTrue(hasText(ID))
        assertTrue(confirmed.isEmpty())
        assertTrue(button(R.string.plugin_capability_confirm).config.contains(SemanticsProperties.Disabled))
        click("plugin-capability-acknowledge")
        clickLabel(R.string.plugin_capability_confirm)
        assertEquals(listOf("visual-native-challenge"), confirmed)
        assertTrue(requestedTrust.isEmpty())
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontInstalledCardKeepsLongNameSwitchAndBothFooterActionsInside(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        val name = "Long plugin name ".repeat(80)
        showList(plugin(name = name))
        pump(18)
        settle()
        assertInstalledCard()
        assertLegacyTextContract("installed-narrow-large", tag("plugin-name-$ID"), name, 2)
        listOf("plugin-icon-$ID", "plugin-name-$ID", "plugin-switch-$ID", "plugin-configure-$ID", "plugin-detail-$ID")
            .forEach { assertVisible(tag(it)) }
        recordGeometry("installed-narrow-large", "plugin-header-$ID", "plugin-name-$ID", "plugin-icon-$ID", "plugin-switch-$ID",
            "plugin-footer-$ID", "plugin-configure-$ID", "plugin-detail-$ID")
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontStoreKeepsLongNameAndGetInsideHeader(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        val item = storeItem(name = "Long remote ports plugin ".repeat(80))
        showStore(item)
        pump(18)
        settle()
        assertStoreHeader(item)
        assertLegacyTextContract("discover-narrow-large", tag("plugin-store-name-${item.key}"), item.plugin.name.resolve("en"), 1)
        assertVisible(tag("plugin-store-get-${item.key}"))
        assertVisible(tag("plugin-store-name-${item.key}"))
        recordGeometry("discover-narrow-large", "plugin-store-header-${item.key}", "plugin-store-name-${item.key}",
            "plugin-store-icon-${item.key}", "plugin-store-get-${item.key}")
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontDetailKeepsIdentityCardAndNativeSwitchAndConfigurationContained(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        showDetail(plugin(name = "Long desktop companion ".repeat(80)))
        pump(18)
        settle()
        assertDetailIdentityCard()
        reveal("plugin-page-configuration")
        assertVisible(tag("plugin-page-configuration"))
        reveal("plugin-detail-switch-$ID")
        assertVisible(tag("plugin-detail-switch-$ID"))
        assertTrue(requestedTrust.isEmpty())
        assertTrue(disabled.isEmpty())
        recordGeometry("detail-narrow-large", "plugin-detail-identity-card", "plugin-detail-identity", "plugin-detail-enable-row",
            "plugin-detail-switch-$ID", "plugin-page-configuration")
    }

    @Test(timeout = 30_000)
    @Config(qualifiers = "w320dp-h640dp")
    fun narrowLargeFontSegmentsKeepBothTabsFullyReachable(): Unit = render(
        width = 320, height = 640, fontScale = 1.5f, darkTheme = true
    ) {
        showCenter()
        pump(18)
        settle()
        assertSegments()
        assertVisible(tag("plugin-center-mine"))
        assertVisible(tag("plugin-center-discover"))
        click("plugin-center-discover")
        assertEquals(true, tag("plugin-center-discover").config[SemanticsProperties.Selected])
    }

    private class Ui(val activity: ComponentActivity, val view: ComposeView, val pump: (Int) -> Unit) {
        var screen by mutableStateOf<@Composable () -> Unit>({})
        val requestedTrust = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        val opened = mutableListOf<Pair<String, PluginPage>>()
        val selected = mutableListOf<String>()
        val requestedUninstall = mutableListOf<String>()
        val clearedGrants = mutableListOf<String>()
        val requestedCapabilities = mutableListOf<Pair<String, PluginCapability>>()
        val revokedCapabilities = mutableListOf<Pair<String, PluginCapability>>()
        fun showCenter() {
            val native = mockk<NativePluginViewModel>()
            val store = mockk<PluginStoreViewModel>()
            every { native.snapshot } returns MutableStateFlow(PluginSnapshot(plugins = listOf(plugin())))
            every { native.state } returns MutableStateFlow(NativePluginUiState())
            every { native.capabilityGrants } returns MutableStateFlow(emptyMap())
            every { store.state } returns MutableStateFlow(PluginStoreUiState(loading = false, items = listOf(storeItem())))
            screen = { PluginManagementTabs(native, store, "en", {}, { selected += it }) }
        }
        fun showList(value: InstalledPlugin) {
            screen = { PluginListContent(PluginSnapshot(plugins = listOf(value)), "en", false, {}, {}, { selected += it },
                onEnable = { requestedTrust += it }, onDisable = { disabled += it },
                onOpenPage = { id, page -> opened += id to page }) }
        }
        fun showStore(item: PluginStoreItem = storeItem()) {
            screen = { PluginStoreContent(PluginStoreUiState(loading = false, items = listOf(item)), "en", {}, {}, {}) }
        }
        fun showDetail(value: InstalledPlugin) {
            screen = { PluginDetailContent(value, "en", false, { requestedTrust += it }, { disabled += it },
                { requestedUninstall += it }, {}, { id, page -> opened += id to page }, { _, _ -> }, {},
                onClearOperationGrants = { clearedGrants += value.record.id }) }
        }
        fun showCapabilities(value: InstalledPlugin, granted: Boolean = false, busy: Boolean = false) {
            screen = {
                Column {
                    PluginCapabilityRows(value, if (granted) setOf("$ID:AUTOSTART") else emptySet(), busy,
                        isGranted = { _, _ -> granted }, prepare = { id, capability -> requestedCapabilities += id to capability },
                        revoke = { id, capability -> revokedCapabilities += id to capability })
                }
            }
        }
        fun nodes(): Collection<SemanticsNode> {
            val window = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: view
            return requireNotNull(findRoot(window)).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
        }
        fun hasTag(value: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun hasText(value: String): Boolean = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun tag(value: String): SemanticsNode = nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
        fun label(resource: Int): String = activity.getString(resource)
        fun button(resource: Int): SemanticsNode = nodes().single { node ->
            node.config.contains(SemanticsActions.OnClick) && node.containsText(label(resource))
        }
        fun clickLabel(resource: Int) {
            assertTrue(requireNotNull(button(resource).config[SemanticsActions.OnClick].action).invoke())
            pump(8)
            settle()
        }
        fun click(value: String) {
            reveal(value)
            val node = tag(value)
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            pump(8)
            settle()
        }
        fun settle() {
            var previous: List<String>? = null
            var stable = 0
            repeat(120) {
                pump(1)
                val current = nodes().map {
                    "${it.id}:${it.positionInRoot}:${it.size}:" +
                        it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value()
                }
                stable = if (current == previous) stable + 1 else 0
                if (stable >= 4) return
                previous = current
            }
            error("Production layout did not settle")
        }
        fun reveal(value: String) {
            settle()
            repeat(24) {
                val node = nodes().singleOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
                if (node != null) {
                    var ancestor = node.parent
                    while (ancestor != null) {
                        val current = ancestor
                        val scroll = current.config.getOrNull(SemanticsActions.ScrollBy)?.action
                        if (scroll != null && current.config.contains(SemanticsProperties.VerticalScrollAxisRange)) {
                            val viewport = current.boundsInRoot
                            val bounds = node.unclippedBounds()
                            val dy = when {
                                bounds.top < viewport.top -> bounds.top - viewport.top
                                bounds.height <= viewport.height && bounds.bottom > viewport.bottom -> bounds.bottom - viewport.bottom
                                bounds.top >= viewport.bottom -> bounds.top - viewport.top
                                else -> 0f
                            }
                            if (dy != 0f) {
                                assertTrue(scroll.invoke(0f, dy))
                                settle()
                                return@repeat
                            }
                        }
                        ancestor = current.parent
                    }
                    return
                }
                val scroll = nodes().firstOrNull { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                    ?: error("Missing production target $value")
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, if (it == 0) -10_000f else 240f))
                settle()
            }
            error("Production target $value did not become visible")
        }
        fun assertContained(child: SemanticsNode, parent: SemanticsNode) {
            val inner = child.unclippedBounds()
            val outer = parent.unclippedBounds()
            assertTrue("$inner not contained in $outer", inner.width > 0 && inner.height > 0 &&
                inner.left >= outer.left - 0.5f && inner.top >= outer.top - 0.5f &&
                inner.right <= outer.right + 0.5f && inner.bottom <= outer.bottom + 0.5f)
        }
        fun assertVisible(node: SemanticsNode) {
            val bounds = node.unclippedBounds()
            val window = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: view
            assertTrue("$bounds outside ${window.width}x${window.height}", bounds.left >= 0f && bounds.top >= 0f &&
                bounds.right <= window.width && bounds.bottom <= window.height && bounds.width > 0 && bounds.height > 0)
            assertEquals(bounds.width, node.boundsInRoot.width, 0.5f)
            assertEquals(bounds.height, node.boundsInRoot.height, 0.5f)
        }
        fun assertSameBand(first: SemanticsNode, second: SemanticsNode) {
            val a = first.unclippedBounds()
            val b = second.unclippedBounds()
            assertTrue("$a and $b do not share a horizontal band", min(a.bottom, b.bottom) - max(a.top, b.top) > 0f)
        }
        fun assertDescendant(node: SemanticsNode, ancestor: SemanticsNode) {
            var current: SemanticsNode? = node
            while (current != null) {
                if (current.id == ancestor.id) return
                current = current.parent
            }
            error("Node ${node.id} is not in card ${ancestor.id}")
        }
        fun assertSegments() {
            val segments = tag("plugin-center-segments")
            val mine = tag("plugin-center-mine")
            val discover = tag("plugin-center-discover")
            assertTrue(segments.config.contains(SemanticsProperties.SelectableGroup))
            listOf(mine, discover).forEach {
                assertEquals(Role.Tab, it.config[SemanticsProperties.Role])
                assertDescendant(it, segments)
                assertContained(it, segments)
                assertRoundedShape(it, 8f)
            }
            assertRoundedShape(segments, 12f)
            val children = layoutChildren(segments.layoutInfo)
            assertEquals("Only two tab containers; no M3 underline indicator or divider sibling", 2, children.size)
            assertEquals(setOf(mine.layoutInfo.semanticsId, discover.layoutInfo.semanticsId), children.map { it.semanticsId }.toSet())
            assertEquals(mine.size.width.toFloat(), discover.size.width.toFloat(), 1f)
            assertEquals(mine.positionInRoot.y, discover.positionInRoot.y, 0.5f)
            assertTrue(mine.unclippedBounds().right <= discover.unclippedBounds().left + 0.5f)
        }
        fun assertInstalledCard() {
            val header = tag("plugin-header-$ID")
            val name = tag("plugin-name-$ID")
            val icon = tag("plugin-icon-$ID")
            val switch = tag("plugin-switch-$ID")
            listOf(name, icon, switch).forEach { assertDescendant(it, header); assertContained(it, header) }
            assertSameBand(name, switch)
            assertSameBand(icon, name)
            assertEquals(icon.unclippedBounds().center.y, switch.unclippedBounds().center.y, 0.5f)
            assertTrue(icon.unclippedBounds().right < name.unclippedBounds().left)
            assertTrue(name.unclippedBounds().right < switch.unclippedBounds().left)
            val footer = tag("plugin-footer-$ID")
            val configuration = tag("plugin-configure-$ID")
            val details = tag("plugin-detail-$ID")
            assertTrue(header.unclippedBounds().bottom <= footer.unclippedBounds().top)
            listOf(configuration, details).forEach {
                assertDescendant(it, footer)
                assertContained(it, footer)
                assertEquals(Role.Button, it.config[SemanticsProperties.Role])
                assertTrue("Footer action must not fill the card width", it.size.width < footer.size.width * 0.6f)
            }
            assertEquals(configuration.unclippedBounds().center.y, details.unclippedBounds().center.y, 0.5f)
            assertTrue(configuration.unclippedBounds().right < details.unclippedBounds().left)
        }
        fun assertStoreHeader(item: PluginStoreItem) {
            val header = tag("plugin-store-header-${item.key}")
            val name = tag("plugin-store-name-${item.key}")
            val icon = tag("plugin-store-icon-${item.key}")
            val get = tag("plugin-store-get-${item.key}")
            listOf(name, icon, get).forEach { assertDescendant(it, header); assertContained(it, header) }
            assertSameBand(name, get)
            assertEquals(icon.unclippedBounds().center.y, get.unclippedBounds().center.y, 0.5f)
            assertTrue(icon.unclippedBounds().right < name.unclippedBounds().left)
            assertTrue(name.unclippedBounds().right < get.unclippedBounds().left)
            assertTrue(get.size.width < header.size.width * 0.5f)
        }
        fun assertDetailIdentityCard() {
            val card = tag("plugin-detail-identity-card")
            val identity = tag("plugin-detail-identity")
            val enable = tag("plugin-detail-enable-row")
            val switch = tag("plugin-detail-switch-$ID")
            val configuration = tag("plugin-page-configuration")
            listOf(identity, enable, switch, configuration).forEach { assertDescendant(it, card); assertContained(it, card) }
            assertDescendant(switch, enable)
            assertTrue(identity.unclippedBounds().bottom <= enable.unclippedBounds().top)
            assertTrue(enable.unclippedBounds().bottom <= configuration.unclippedBounds().top)
            assertEquals(card.size.width - 32f, configuration.size.width.toFloat(), 0.5f)
            assertRoundedShape(configuration, 12f)
        }
        fun recordGeometry(name: String, vararg tags: String) {
            val receipt = buildJsonObject {
                put("source", "actual production Compose layout and unmerged semantics")
                put("screenshot", false)
                put("deviceGlyphMetrics", false)
                put("viewportWidth", view.width)
                put("viewportHeight", view.height)
                put("nodes", buildJsonObject {
                    tags.forEach { value ->
                        val node = tag(value)
                        val bounds = node.unclippedBounds()
                        put(value, buildJsonObject {
                            put("left", bounds.left); put("top", bounds.top); put("right", bounds.right); put("bottom", bounds.bottom)
                            put("fontScale", node.layoutInfo.density.fontScale)
                            put("semanticsId", node.id)
                            node.parent?.let { put("parentSemanticsId", it.id) }
                        })
                    }
                })
            }
            File(reportDirectory(), "geometry-$name.json").writeText(receipt.toString())
        }
        fun assertLegacyTextContract(name: String, node: SemanticsNode, expectedText: String, maxLines: Int) {
            val layout = textLayout(node)
            val input = layout.layoutInput
            val paragraph = layout.multiParagraph
            val receipt = buildJsonObject {
                put("source", "actual production TextLayoutResult")
                put("text", input.text.text)
                put("textLength", input.text.length)
                put("layoutWidth", layout.size.width)
                put("layoutHeight", layout.size.height)
                put("nodeWidth", node.size.width)
                put("nodeHeight", node.size.height)
                put("constraints", input.constraints.toString())
                put("fontSize", input.style.fontSize.toString())
                put("lineHeight", input.style.lineHeight.toString())
                put("fontFamily", input.style.fontFamily.toString())
                put("density", input.density.density)
                put("fontScale", input.density.fontScale)
                put("softWrap", input.softWrap)
                put("maxLines", input.maxLines)
                put("overflow", input.overflow.toString())
                put("lineCount", layout.lineCount)
                put("didOverflowWidth", layout.didOverflowWidth)
                put("didOverflowHeight", layout.didOverflowHeight)
                put("hasVisualOverflow", layout.hasVisualOverflow)
                put("didExceedMaxLines", paragraph.didExceedMaxLines)
                put("minIntrinsicWidth", paragraph.minIntrinsicWidth)
                put("maxIntrinsicWidth", paragraph.maxIntrinsicWidth)
                put("lines", buildJsonObject {
                    repeat(layout.lineCount) { line -> put(line.toString(), buildJsonObject {
                        put("start", layout.getLineStart(line))
                        put("end", layout.getLineEnd(line))
                        put("visibleEnd", layout.getLineEnd(line, visibleEnd = true))
                        put("left", layout.getLineLeft(line))
                        put("right", layout.getLineRight(line))
                        put("width", paragraph.getLineWidth(line))
                        put("top", layout.getLineTop(line))
                        put("bottom", layout.getLineBottom(line))
                        put("ellipsized", layout.isLineEllipsized(line))
                    }) }
                })
                put("sdk28ShadowLineBreaker", "Returns one break at text.length without evaluating available widths")
                put("deviceEllipsisVerified", false)
            }
            File(reportDirectory(), "text-layout-$name.json").writeText(receipt.toString())
            assertEquals(expectedText, input.text.text)
            assertEquals(expectedText, node.config[SemanticsProperties.Text].single().text)
            assertEquals(maxLines, input.maxLines)
            assertEquals(TextOverflow.Ellipsis, input.overflow)
            assertEquals(1.5f, input.density.fontScale, 0f)
            assertTrue(layout.size.width <= node.size.width && layout.size.height <= node.size.height)
            assertEquals("SDK28 LEGACY StaticLayout records a single break, not real line wrapping", 1, layout.lineCount)
            assertFalse("The shadow's one-break result cannot establish actual ellipsis overflow", layout.hasVisualOverflow)
        }
        fun textLayout(node: SemanticsNode): TextLayoutResult {
            val result = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(result))
            return result.single()
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
        val previousInspector = isDebugInspectorInfoEnabled
        isDebugInspectorInfoEnabled = true
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
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    AIEditorTheme(darkTheme = darkTheme) { ui.screen() }
                }
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
            isDebugInspectorInfoEnabled = previousInspector
        }
    }

    companion object {
        private const val ID = "visual.sample"
        private val HASH = "a".repeat(64)
        private val ROOT = File("/root/plugin-visual-test/revision")
        private fun plugin(active: Boolean = true, name: String = "Desktop companion"): InstalledPlugin = InstalledPlugin(
            PluginRecord(ID, "1.0.0", active, 1, archiveSha256 = HASH, manifestSha256 = HASH,
                installRevision = "visual-revision", contentHash = HASH, fileHashes = emptyMap(), trustHash = if (active) HASH else null),
            if (active) PluginStatus.ENABLED else PluginStatus.DISABLED,
            PluginManifest(1, ID, LocalizedText.single(name), SemVer(1, 0, 0), "Sample publisher",
                "https://example.invalid/repository", LocalizedText.single("A desktop companion with custom appearance and interactions."), "MIT", "1.0.0"),
            PluginContent(configPage = page())
        )
        private fun pluginWithAutostart(active: Boolean = true): InstalledPlugin = plugin(active).let {
            it.copy(manifest = it.manifest!!.copy(permissions = mapOf(PluginCapability.AUTOSTART to "Launch on app startup")))
        }
        private fun page() = PluginPage(ID, "configuration", LocalizedText.single("Configuration"), null,
            "configuration.html", "settings", ROOT, "1.0.0")
        private fun storeItem(name: String = "Remote ports") = PluginStoreItem(
            PluginIndexPlugin(ID, LocalizedText.single(name), LocalizedText.single("Inspect services and open mapped ports."),
                "Sample publisher", "https://example.invalid/repository", tags = listOf("tool"), version = "1.0.0"),
            PluginIndexSource("visual-source", "Visual source", "https://example.invalid/index.json", false),
            null, false, true
        )
        private fun reportDirectory() = File("build/reports/aji-plugin-visual-alignment").also { it.mkdirs() }
        private fun assertRoundedShape(node: SemanticsNode, radius: Float) {
            val shapes = node.layoutInfo.getModifierInfo().flatMap { info ->
                val inspected = info.modifier as? InspectableValue
                inspected?.inspectableElements?.mapNotNull { it.value as? Shape }?.toList().orEmpty()
            }
            val outlines = shapes.map { it.createOutline(Size(node.size.width.toFloat(), node.size.height.toFloat()), LayoutDirection.Ltr, Density(1f)) }
            assertTrue("Expected actual rounded Shape radius=$radius; shapes=$shapes", outlines.any { outline ->
                outline is Outline.Rounded && listOf(outline.roundRect.topLeftCornerRadius, outline.roundRect.topRightCornerRadius,
                    outline.roundRect.bottomLeftCornerRadius, outline.roundRect.bottomRightCornerRadius)
                    .all { abs(it.x - radius) <= 0.5f && abs(it.y - radius) <= 0.5f }
            })
        }
        @Suppress("UNCHECKED_CAST")
        private fun layoutChildren(info: LayoutInfo): List<LayoutInfo> =
            info.javaClass.getMethod("getChildrenInfo").invoke(info) as List<LayoutInfo>
        private fun SemanticsNode.containsText(value: String): Boolean =
            config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true || children.any { it.containsText(value) }
        private fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInRoot.x, positionInRoot.y,
            positionInRoot.x + size.width, positionInRoot.y + size.height)
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { index -> findRoot(view.getChildAt(index))?.let { return it } }
            return null
        }
    }
}
