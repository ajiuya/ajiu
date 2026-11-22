package com.aicode.feature.plugin.runtime

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChange
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.workspace.domain.FileBackendRouting
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginFileWatchApiTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data by lazy { temporary.newFolder("data") }
    private val capture by lazy { PluginCapture(PluginRecord("fixture.watcher", "1.0.0", true, 1,
        archiveSha256 = "archive", manifestSha256 = "manifest", fileHashes = emptyMap(), installRevision = "revision", contentHash = "hash"),
        mockk(), PluginContent(), temporary.newFolder("install"), {}, {}) }
    private fun context(source: Any = "same") = PluginInvocationContext(capture, null, "", null, data, MutableSharedFlow(), sourceIdentity = source) {}
    private fun watch(id: String) = buildJsonObject { put("subscriptionId", id); put("path", "data://") }

    @Test fun pageLifetimeOutlivesWatchCallAndInvisiblePageReleasesAndResumesOwnedRoot() = runBlocking<Unit> {
        val hub = mockk<FileChangeHub>()
        val source = MutableSharedFlow<FileChangeBatch>()
        every { hub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } returns source
        val gateway = mockk<PluginHostGateway>()
        coEvery { gateway.authorize(any(), any(), any(), any(), any(), any()) } returns Unit
        val service = PluginFileWatchApi(Provider { gateway }, PluginFileBridge(FileBackendRouting()), hub)
        val received = Channel<PluginFileWatchEvent>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { service.events.collect { received.send(it) } }
        try {
            val owner = context()
            service.watch(owner, "page-uuid", watch("one"))
            withTimeout(5000) { source.subscriptionCount.first { it == 1 } }
            val batch = FileChangeBatch(listOf(FileChange(ChangeRoot.OTHER, data.resolve("a").path, "data://a", ChangeKind.CREATED)))
            source.emit(batch)
            val event = withTimeout(5000) { received.receive() }
            assertSame(owner, event.sourceContext); assertSame(batch, event.batch); assertTrue(service.isCurrent(event))
            service.setPageVisible("page-uuid", false)
            assertFalse(service.isCurrent(event))
            withTimeout(5000) { source.subscriptionCount.first { it == 0 } }
            service.setPageVisible("page-uuid", true)
            withTimeout(5000) { source.subscriptionCount.first { it == 1 } }
            assertFalse(service.isCurrent(event))
            source.emit(batch)
            assertTrue(service.isCurrent(withTimeout(5000) { received.receive() }))
            service.closePage("page-uuid")
            withTimeout(5000) { source.subscriptionCount.first { it == 0 } }
            verify(exactly = 2) { hub.watchHostDir(data, "data://", any(), false, any(), any(), any()) }
        } finally { service.closePage("page-uuid"); observer.cancelAndJoin() }
    }

    @Test fun replacementGenerationAndForeignSourceCannotUseOriginalSubscription() = runBlocking<Unit> {
        val hub = mockk<FileChangeHub>()
        val source = MutableSharedFlow<FileChangeBatch>()
        every { hub.watchHostDir(any(), any(), any(), any(), any(), any(), any()) } returns source
        val gateway = mockk<PluginHostGateway>()
        coEvery { gateway.authorize(any(), any(), any(), any(), any(), any()) } returns Unit
        val service = PluginFileWatchApi(Provider { gateway }, PluginFileBridge(FileBackendRouting()), hub)
        val received = Channel<PluginFileWatchEvent>(Channel.UNLIMITED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { service.events.collect { received.send(it) } }
        try {
            val owner = context()
            service.watch(owner, "page", watch("same"))
            withTimeout(5000) { source.subscriptionCount.first { it == 1 } }
            source.emit(FileChangeBatch(emptyList(), true))
            val old = withTimeout(5000) { received.receive() }
            assertTrue(service.isCurrent(old))
            try { service.unwatch(context("different"), "page", "same"); fail("Foreign owner cannot unsubscribe") }
            catch (failure: PluginHostException) { assertEquals("WATCH_NOT_FOUND", failure.code) }
            service.watch(context(), "page", watch("same"))
            assertFalse(service.isCurrent(old))
            service.unwatch(context(), "page", "same")
            withTimeout(5000) { source.subscriptionCount.first { it == 0 } }
        } finally { service.closePage("page"); observer.cancelAndJoin() }
    }
}
