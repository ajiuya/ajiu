package com.aicode.feature.browser.presentation

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.aicode.R
import com.aicode.feature.agent.domain.container.PortMapping
import com.aicode.feature.agent.domain.container.PortMappingError
import com.aicode.feature.agent.domain.container.PortMappingState
import com.aicode.feature.agent.domain.container.PortMappingStatus
import com.aicode.feature.agent.domain.container.SshPortMappingManager
import com.aicode.feature.agent.domain.tool.browser.BrowserManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserPortMappingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val state = MutableStateFlow(PortMappingState(remoteMode = true, available = true))
    private val addresses = mutableMapOf<String, String>()
    private val store = ViewModelStore()
    private lateinit var mappings: SshPortMappingManager
    private lateinit var browser: BrowserManager
    private lateinit var viewModel: BrowserViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mappings = mockk(relaxed = true)
        browser = mockk(relaxed = true)
        every { mappings.state } returns state
        every { mappings.address(any()) } answers { addresses[firstArg()] }
        viewModel = BrowserViewModel(browser, mappings)
        store.put("browser", viewModel)
    }

    @After
    fun tearDown() {
        try {
            store.clear()
            dispatcher.scheduler.runCurrent()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun invalidRemotePortsNeverReachTheManager() = runTest(dispatcher) {
        val messages = observeMessages()
        val invalid = listOf("", " ", "0", "-1", "65536", "2147483648", "1.5", "abc", "+80", "８０")
        invalid.forEach { viewModel.addPortMapping(it, "") }
        runCurrent()
        assertEquals(List(invalid.size) { R.string.port_mapping_error_invalid_port }, messages)
        verify(exactly = 0) { mappings.add(any(), any()) }
    }

    @Test
    fun invalidLocalPortsNeverTurnIntoAnAutomaticAssignment() = runTest(dispatcher) {
        val messages = observeMessages()
        val invalid = listOf(" ", "0", "-1", "65536", "2147483648", "1.5", "abc", "+80", "８０")
        invalid.forEach { viewModel.addPortMapping("3000", it) }
        runCurrent()
        assertEquals(List(invalid.size) { R.string.port_mapping_error_invalid_port }, messages)
        verify(exactly = 0) { mappings.add(any(), any()) }
    }

    @Test
    fun emptyLocalPortRequestsAutomaticAssignmentAndBothPortBoundsAreAccepted() = runTest(dispatcher) {
        val messages = observeMessages()
        viewModel.addPortMapping("1", "")
        viewModel.addPortMapping("65535", "1")
        viewModel.addPortMapping("1", "65535")
        runCurrent()
        verify(exactly = 1) { mappings.add(1, null) }
        verify(exactly = 1) { mappings.add(65535, 1) }
        verify(exactly = 1) { mappings.add(1, 65535) }
        assertTrue(messages.isEmpty())
    }

    @Test
    fun bothBrowserViewModelsExposeTheSameSingletonState() = runTest(dispatcher) {
        val second = BrowserViewModel(browser, mappings)
        store.put("workbench-browser", second)
        assertSame(state, viewModel.portMappingState)
        assertSame(viewModel.portMappingState, second.portMappingState)
        for (status in PortMappingStatus.entries) {
            state.value = state.value.copy(mappings = listOf(mapping(status)))
            assertEquals(state.value, viewModel.portMappingState.value)
            assertEquals(state.value, second.portMappingState.value)
        }
        assertSame(browser, viewModel.browserManager)
    }

    @Test
    fun copyingUsesTheCurrentActualAddressRatherThanTheOldRowPort() = runTest(dispatcher) {
        state.value = state.value.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE, localPort = 3100)))
        addresses[ID] = "http://127.0.0.1:51000"
        val copied = mutableListOf<String>()
        viewModel.copyPortMapping(ID) { copied += it }
        assertEquals(listOf("http://127.0.0.1:51000"), copied)
        verify(exactly = 1) { mappings.address(ID) }
        coVerify(exactly = 0) { browser.navigate(any(), any()) }
    }

    @Test
    fun aClosedAddressCannotBeCopiedFromAStaleActiveRow() = runTest(dispatcher) {
        val messages = observeMessages()
        state.value = state.value.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
        val copied = mutableListOf<String>()
        viewModel.copyPortMapping(ID) { copied += it }
        runCurrent()
        assertTrue(copied.isEmpty())
        assertEquals(listOf(R.string.port_mapping_error_connection_changed), messages)
        coVerify(exactly = 0) { browser.navigate(any(), any()) }
    }

    @Test
    fun everyCopyRechecksTheManagerInsteadOfCachingTheFirstAddress() = runTest(dispatcher) {
        val messages = observeMessages()
        val copied = mutableListOf<String>()
        addresses[ID] = "http://127.0.0.1:51000"
        viewModel.copyPortMapping(ID) { copied += it }
        addresses.clear()
        viewModel.copyPortMapping(ID) { copied += it }
        runCurrent()
        assertEquals(listOf("http://127.0.0.1:51000"), copied)
        assertEquals(listOf(R.string.port_mapping_error_connection_changed), messages)
        verify(exactly = 2) { mappings.address(ID) }
    }

    @Test
    fun openingUsesTheCurrentActualAddressWhenTheNavigationCoroutineStarts() = runTest(dispatcher) {
        state.value = state.value.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE, localPort = 3100)))
        addresses[ID] = "http://127.0.0.1:51000"
        viewModel.openPortMapping(ID)
        coVerify(exactly = 0) { browser.navigate(any(), any()) }
        addresses[ID] = "http://127.0.0.1:52000"
        runCurrent()
        coVerify(exactly = 1) { browser.navigate("http://127.0.0.1:52000", null) }
        coVerify(exactly = 0) { browser.navigate("http://127.0.0.1:51000", any()) }
        coVerify(exactly = 0) { browser.navigate("http://127.0.0.1:3100", any()) }
    }

    @Test
    fun aConnectionChangeBeforeTheQueuedOpenStartsRejectsTheOldUrl() = runTest(dispatcher) {
        val messages = observeMessages()
        state.value = state.value.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
        addresses[ID] = "http://127.0.0.1:51000"
        viewModel.openPortMapping(ID)
        addresses.clear()
        runCurrent()
        coVerify(exactly = 0) { browser.navigate(any(), any()) }
        assertEquals(listOf(R.string.port_mapping_error_connection_changed), messages)
    }

    @Test
    fun navigationFailuresEmitOnlyALocalizedResource() = runTest(dispatcher) {
        val messages = observeMessages()
        addresses[ID] = "http://127.0.0.1:51000"
        coEvery { browser.navigate(any(), any()) } throws IllegalStateException("private SSH diagnostic")
        viewModel.openPortMapping(ID)
        runCurrent()
        assertEquals(listOf(R.string.port_mapping_error_connection_failed), messages)
    }

    @Test
    fun navigationCancellationDoesNotBecomeAnErrorMessage() = runTest(dispatcher) {
        val messages = observeMessages()
        addresses[ID] = "http://127.0.0.1:51000"
        coEvery { browser.navigate(any(), any()) } throws CancellationException("cancelled")
        viewModel.openPortMapping(ID)
        runCurrent()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun closeOnlyTargetsTheSelectedMapping() = runTest(dispatcher) {
        for (status in PortMappingStatus.entries) {
            state.value = state.value.copy(mappings = listOf(mapping(status)))
            viewModel.closePortMapping(ID)
        }
        verify(exactly = PortMappingStatus.entries.size) { mappings.close(ID) }
        verify(exactly = 0) { mappings.closeAll() }
    }

    @Test
    fun clearingOneViewModelDoesNotCloseTheSharedMappingsOrTheOtherBrowser() = runTest(dispatcher) {
        val secondStore = ViewModelStore()
        val second = BrowserViewModel(browser, mappings)
        secondStore.put("workbench-browser", second)
        try {
            addresses[ID] = "http://127.0.0.1:51000"
            state.value = state.value.copy(mappings = listOf(mapping(PortMappingStatus.ACTIVE)))
            store.clear()
            runCurrent()
            assertEquals(state.value, second.portMappingState.value)
            val copied = mutableListOf<String>()
            second.copyPortMapping(ID) { copied += it }
            assertEquals(listOf("http://127.0.0.1:51000"), copied)
            verify(exactly = 0) { mappings.close(any()) }
            verify(exactly = 0) { mappings.closeAll() }
        } finally {
            secondStore.clear()
        }
        verify(exactly = 0) { mappings.closeAll() }
    }

    @Test
    fun everyTypedManagerErrorHasItsOwnLocalizedResource() {
        val expected = mapOf(
            PortMappingError.INVALID_PORT to R.string.port_mapping_error_invalid_port,
            PortMappingError.LOCAL_MODE to R.string.port_mapping_error_local_mode,
            PortMappingError.NOT_CONNECTED to R.string.port_mapping_error_not_connected,
            PortMappingError.PORT_IN_USE to R.string.port_mapping_error_port_in_use,
            PortMappingError.TARGET_UNREACHABLE to R.string.port_mapping_error_target_unreachable,
            PortMappingError.CONNECTION_FAILED to R.string.port_mapping_error_connection_failed,
            PortMappingError.LIMIT_REACHED to R.string.port_mapping_error_limit_reached,
            PortMappingError.CONNECTION_CHANGED to R.string.port_mapping_error_connection_changed,
            PortMappingError.LISTEN_FAILED to R.string.port_mapping_error_listen_failed
        )
        assertEquals(PortMappingError.entries.toSet(), expected.keys)
        expected.forEach { (error, resource) -> assertEquals(resource, error.messageRes()) }
    }

    private fun TestScope.observeMessages(): MutableList<Int> = mutableListOf<Int>().also { messages ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.portMappingMessages.collect { messages += it }
        }
    }

    companion object {
        private const val ID = "mapping-one"
        private fun mapping(status: PortMappingStatus, localPort: Int = 51000) = PortMapping(
            id = ID,
            remotePort = 3000,
            localPort = if (status == PortMappingStatus.ACTIVE) localPort else null,
            status = status,
            error = if (status == PortMappingStatus.FAILED) PortMappingError.CONNECTION_CHANGED else null
        )
    }
}
