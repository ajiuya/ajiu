package com.aicode.feature.plugin.presentation

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.plugin.domain.PluginRuntime
import com.aicode.feature.plugin.runtime.PluginRuntimeManager
import com.aicode.feature.plugin.runtime.PluginHostGateway
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.io.File
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.LocalizedText
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.plugin.domain.PluginManifest
import com.aicode.feature.plugin.domain.SemVer
import com.aicode.feature.plugin.runtime.PluginHostException
import com.aicode.feature.plugin.runtime.PluginInvocationContext
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginTipLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun queuedTipRetainsItsSourceAfterTheCallingInvocationEnds() {
        val tips = PluginTipManager()
        val context = context("test.sample")
        val reply = tips.show(context, buildJsonObject { put("title", "Native tip"); put("text", "Retained message") })
        val tip = tips.tips.value.single()
        assertEquals(tip.tipId, (reply as kotlinx.serialization.json.JsonObject)["tipId"]?.let { (it as JsonPrimitive).content })
        context.close()
        tip.invocation.checkCurrent()
        assertEquals("Native tip", tip.title)
        assertTrue(tip.invocation.allowsTool("readFile"))
        assertFalse(tip.invocation.allowsTool("Root"))
        tips.dismissTip(tip.tipId)
        assertTrue(tips.tips.value.isEmpty())
        assertThrows(CancellationException::class.java) { tip.invocation.checkCurrent() }
    }
    @Test fun replacingOnePluginTipClosesOnlyTheOldTipContext() {
        val tips = PluginTipManager()
        val context = context("test.sample")
        tips.show(context, buildJsonObject { put("text", "old") })
        val old = tips.tips.value.single()
        tips.show(context, buildJsonObject { put("text", "new") })
        val current = tips.tips.value.single()
        assertThrows(CancellationException::class.java) { old.invocation.checkCurrent() }
        current.invocation.checkCurrent()
        tips.dismissTip(old.tipId)
        assertEquals(current.tipId, tips.tips.value.single().tipId)
        tips.dismissTip(current.tipId)
        context.close()
    }
    @Test fun queueBudgetRejectsInsteadOfSilentlyDroppingOtherPlugins() {
        val tips = PluginTipManager()
        val contexts = (1..4).map { context("test.plugin$it") }
        contexts.take(3).forEach { tips.show(it, buildJsonObject { put("text", "queued") }) }
        assertThrows(PluginHostException::class.java) { tips.show(contexts.last(), buildJsonObject { put("text", "overflow") }) }
        assertEquals(3, tips.tips.value.size)
        tips.tips.value.map { it.tipId }.forEach(tips::dismissTip)
        contexts.forEach { it.close() }
    }
    @Test fun invalidActionMetadataNeverQueuesAPartialTip() {
        val tips = PluginTipManager()
        val context = context("test.sample")
        assertThrows(PluginHostException::class.java) { tips.show(context, buildJsonObject { put("text", "tip"); put("action", buildJsonObject { put("label", "Run"); put("handler", "unsafe handler") }) }) }
        assertTrue(tips.tips.value.isEmpty())
        context.close()
    }
    @Test fun actionFailureIsLoggedWithoutTheRawErrorAndReleasesTheTip() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
        val tips = PluginTipManager()
        val context = context("test.sample", withRuntime = true)
        val runtime = mockk<PluginRuntimeManager>()
        coEvery { runtime.invoke(any(), any(), any(), any(), any(), any(), any(), any()) } throws PluginHostException("PLUGIN_TEST_FAILURE", "private runtime detail")
        try {
            tips.show(context, buildJsonObject { put("text", "tip"); put("action", buildJsonObject { put("label", "Run"); put("handler", "test.run") }) })
            val tip = tips.tips.value.single()
            val viewModel = PluginTipsViewModel(tips, runtime, mockk<PluginHostGateway>())
            viewModel.action(tip)
            viewModel.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            verify(exactly = 1) { FileLogger.w("PluginTip", "Tip action failed: PLUGIN_TEST_FAILURE", null) }
            assertTrue(tips.tips.value.isEmpty())
            assertThrows(CancellationException::class.java) { tip.invocation.checkCurrent() }
        } finally { context.close(); unmockkObject(FileLogger); Dispatchers.resetMain() }
    }
    @Test fun actionCancellationIsNotLoggedAsARuntimeFailureAndStillReleasesTheTip() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
        val tips = PluginTipManager()
        val context = context("test.sample", withRuntime = true)
        val runtime = mockk<PluginRuntimeManager>()
        coEvery { runtime.invoke(any(), any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
        try {
            tips.show(context, buildJsonObject { put("text", "tip"); put("action", buildJsonObject { put("label", "Run"); put("handler", "test.run") }) })
            val viewModel = PluginTipsViewModel(tips, runtime, mockk<PluginHostGateway>())
            viewModel.action(tips.tips.value.single())
            viewModel.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll()
            verify(exactly = 0) { FileLogger.w(any(), any(), any()) }
            assertTrue(tips.tips.value.isEmpty())
        } finally { context.close(); unmockkObject(FileLogger); Dispatchers.resetMain() }
    }
    private fun context(id: String, withRuntime: Boolean = false): PluginInvocationContext {
        val root = temporary.newFolder()
        val hash = "a".repeat(64)
        val record = PluginRecord(id, "1.0.0", true, 1, archiveSha256 = hash, manifestSha256 = hash,
            installRevision = UUID.randomUUID().toString(), contentHash = hash, fileHashes = emptyMap(), trustHash = hash)
        val manifest = PluginManifest(1, id, LocalizedText.single("Sample"), SemVer(1, 0, 0), "test", "", LocalizedText.single(""), "MIT", "1.0.0")
        val process = if (withRuntime) PluginRuntime(id, "javascript", root, File(root, "index.js"), listOf("node", "index.js")) else null
        val capture = PluginCapture(record, manifest, PluginContent(runtime = process), root, {}, {})
        return PluginInvocationContext(capture, null, "/work", "native-session", root, emptyFlow(), { it == "readFile" }, sourceIdentity = "fixed-source") {}
    }
}
