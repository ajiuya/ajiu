package com.aicode.feature.terminal.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DelegatingTerminalSessionProviderTest {
    private val holder = ExecutionModeHolder()
    private val localEvents = MutableSharedFlow<TabFinishedEvent>()
    private val remoteEvents = MutableSharedFlow<TabFinishedEvent>()
    private val local = mockk<TerminalSessionManager>()
    private val remote = mockk<RemoteTerminalSessionManager>()
    private lateinit var provider: DelegatingTerminalSessionProvider

    @Before
    fun setUp() {
        every { local.tabFinishedEvents } returns localEvents
        every { remote.tabFinishedEvents } returns remoteEvents
        provider = DelegatingTerminalSessionProvider(holder, local, remote)
    }

    @After
    fun tearDown() {
        val scope = DelegatingTerminalSessionProvider::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(provider) as CoroutineScope
        scope.cancel()
    }

    @Test
    fun bothBackendsStaySubscribedAcrossModesWithoutSyntheticOrDuplicateEvents() = runBlocking<Unit> {
        val received = Channel<TabFinishedEvent>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            provider.tabFinishedEvents.collect { received.send(it) }
        }
        try {
            withTimeout(5_000) {
                localEvents.subscriptionCount.first { it == 1 }
                remoteEvents.subscriptionCount.first { it == 1 }
            }
            for (mode in listOf(ExecutionMode.LOCAL_PROOT, ExecutionMode.REMOTE_SSH, ExecutionMode.LOCAL_PROOT)) {
                holder.setMode(mode)
                assertNull(withTimeoutOrNull(100) { received.receive() })
                assertEquals(1, localEvents.subscriptionCount.value)
                assertEquals(1, remoteEvents.subscriptionCount.value)
                val localEvent = TabFinishedEvent("term-1", "Local", "local", 0, "session-1", "local output",
                    sourceMode = "LOCAL_PROOT", channelGeneration = null)
                val remoteEvent = TabFinishedEvent("term-1", "Remote", "remote", 17, "session-2", "remote output",
                    sourceMode = "REMOTE_SSH", channelGeneration = "captured-generation")
                localEvents.emit(localEvent)
                assertSame(localEvent, withTimeout(5_000) { received.receive() })
                remoteEvents.emit(remoteEvent)
                assertSame(remoteEvent, withTimeout(5_000) { received.receive() })
                assertNull(withTimeoutOrNull(100) { received.receive() })
            }
        } finally {
            collector.cancelAndJoin()
            received.cancel()
        }
    }

    @Test
    fun ownedTerminalLookupAndCleanupStayWithOriginalBackendAfterModeSwitch() = runBlocking<Unit> {
        val owner = TerminalResourceOwner("fixture.plugin", "resource-uuid")
        every { local.pluginTabStatus("term-1", owner) } returns TerminalTabStatus(true, null)
        every { remote.pluginTabStatus("term-1", owner) } returns null
        every { local.writePluginInput("term-1", owner, "input", false) } returns true
        coEvery { local.closePluginTab("term-1", owner) } returns true
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertTrue(provider.writePluginInput("term-1", owner, "input", false))
        assertTrue(provider.closePluginTab("term-1", owner))
        verify(exactly = 1) { local.writePluginInput("term-1", owner, "input", false) }
        coVerify(exactly = 1) { local.closePluginTab("term-1", owner) }
        coVerify(exactly = 0) { remote.closePluginTab(any(), any()) }
        verify(exactly = 0) { remote.writePluginInput(any(), any(), any(), any()) }
    }

    @Test
    fun commandAndInputRoutingStillFollowCurrentMode() = runBlocking<Unit> {
        coEvery { local.startBackgroundCommand("local", "Local", true, "session-1") } returns "term-local"
        coEvery { remote.startBackgroundCommand("remote", "Remote", true, "session-2") } returns "term-remote"
        every { local.sendInput("term-local", "input", false) } returns true
        every { remote.sendInput("term-remote", "input", true) } returns true
        assertEquals("term-local", provider.startBackgroundCommand("local", "Local", true, "session-1"))
        assertTrue(provider.sendInput("term-local", "input", false))
        holder.setMode(ExecutionMode.REMOTE_SSH)
        assertEquals("term-remote", provider.startBackgroundCommand("remote", "Remote", true, "session-2"))
        assertTrue(provider.sendInput("term-remote", "input", true))
        coVerify(exactly = 1) { local.startBackgroundCommand("local", "Local", true, "session-1") }
        coVerify(exactly = 1) { remote.startBackgroundCommand("remote", "Remote", true, "session-2") }
        verify(exactly = 1) { local.sendInput("term-local", "input", false) }
        verify(exactly = 1) { remote.sendInput("term-remote", "input", true) }
    }
}
