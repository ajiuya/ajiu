package com.aicode.core.net

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.aicode.core.util.FileLogger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NetworkConnectivityObserverTest {
    private val context = mockk<Context>()
    private val connectivityManager = mockk<ConnectivityManager>()
    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
    private val status = NetworkStatusStore()
    private val wifi = ShadowNetwork.newInstance(1)
    private val cellular = ShadowNetwork.newInstance(2)
    private lateinit var globalBefore: NetworkStatus.Snapshot

    private val callback: ConnectivityManager.NetworkCallback
        get() = callbackSlot.captured

    @Before
    fun setUp() {
        globalBefore = NetworkStatus.states.value
        mockkObject(FileLogger)
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.e(any(), any(), any()) } just Runs
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
        assertEquals(globalBefore, NetworkStatus.states.value)
    }

    @Test
    fun initializationReadsInternetAndValidationSeparately() {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities(validated = false))
        val snapshot = status.states.value

        assertTrue(observer.isOnline.value)
        assertTrue(snapshot.isOnline)
        assertEquals(true, snapshot.hasInternet)
        assertEquals(false, snapshot.validated)
        assertEquals(null, snapshot.blocked)
        assertEquals(setOf(NetworkStatus.Transport.WIFI), snapshot.transports)
        assertEquals(1L, snapshot.networkGeneration)
        verify(exactly = 1) { connectivityManager.activeNetwork }
        verify(exactly = 1) { connectivityManager.getNetworkCapabilities(wifi) }
    }

    @Test
    fun recoveryWaitsForCapabilitiesAndEmitsOnce() = runTest {
        val observer = observe()
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }
        assertFalse(observer.isOnline.value)

        callback.onAvailable(wifi)
        assertFalse(observer.isOnline.value)
        assertEquals(null, status.states.value.hasInternet)
        assertEquals(0L, status.states.value.recoveryVersion)
        assertTrue(events.isEmpty())

        callback.onCapabilitiesChanged(wifi, capabilities())
        assertTrue(observer.isOnline.value)
        assertEquals(1L, status.states.value.recoveryVersion)
        assertEquals(1, events.size)

        callback.onLost(wifi)
        assertFalse(observer.isOnline.value)
        assertEquals(1L, status.states.value.recoveryVersion)
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities())
        assertTrue(observer.isOnline.value)
        assertEquals(2L, status.states.value.recoveryVersion)
        assertEquals(2, events.size)
    }

    @Test
    fun newNetworkDiscardsOldCapabilitiesAndBlockedStatus() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities())
        callback.onBlockedStatusChanged(wifi, true)
        assertFalse(observer.isOnline.value)
        val previous = status.states.value
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onAvailable(cellular)
        val available = status.states.value
        assertFalse(available.isOnline)
        assertEquals(previous.networkGeneration + 1, available.networkGeneration)
        assertEquals(null, available.hasInternet)
        assertEquals(null, available.validated)
        assertEquals(null, available.blocked)
        assertTrue(available.transports.isEmpty())
        assertEquals(previous.recoveryVersion, available.recoveryVersion)

        callback.onCapabilitiesChanged(cellular, capabilities(transport = NetworkCapabilities.TRANSPORT_CELLULAR))
        assertTrue(observer.isOnline.value)
        assertEquals(previous.recoveryVersion + 1, status.states.value.recoveryVersion)
        assertEquals(setOf(NetworkStatus.Transport.CELLULAR), status.states.value.transports)
        assertEquals(1, events.size)
    }

    @Test
    fun switchingOnlineNetworksEmitsOneRecoveryAndIgnoresEveryOldCallback() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities())
        val before = status.states.value
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onAvailable(cellular)
        val pending = status.states.value
        callback.onLost(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities())
        callback.onBlockedStatusChanged(wifi, false)
        assertEquals(pending, status.states.value)
        callback.onCapabilitiesChanged(cellular, capabilities(transport = NetworkCapabilities.TRANSPORT_CELLULAR))
        val switched = status.states.value
        assertTrue(observer.isOnline.value)
        assertEquals(before.networkGeneration + 1, switched.networkGeneration)
        assertEquals(before.recoveryVersion + 1, switched.recoveryVersion)
        assertEquals(1, events.size)

        callback.onCapabilitiesChanged(wifi, capabilities(internet = false))
        callback.onBlockedStatusChanged(wifi, true)
        callback.onLost(wifi)
        assertEquals(switched, status.states.value)
        assertTrue(observer.isOnline.value)
        assertEquals(1, events.size)

        verify(exactly = 1) { connectivityManager.activeNetwork }
        verify(exactly = 1) { connectivityManager.getNetworkCapabilities(any()) }
    }

    @Test
    fun duplicateAvailabilityCapabilitiesAndBlockedCallbacksDoNotCreateRecoveries() = runTest {
        val observer = observe()
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities())
        callback.onBlockedStatusChanged(wifi, false)
        val online = status.states.value

        repeat(5) {
            callback.onAvailable(wifi)
            callback.onCapabilitiesChanged(wifi, capabilities())
            callback.onBlockedStatusChanged(wifi, false)
        }
        assertEquals(online, status.states.value)
        assertTrue(observer.isOnline.value)
        assertEquals(1, events.size)

        callback.onBlockedStatusChanged(wifi, true)
        val blocked = status.states.value
        repeat(5) {
            callback.onAvailable(wifi)
            callback.onCapabilitiesChanged(wifi, capabilities())
            callback.onBlockedStatusChanged(wifi, true)
        }
        assertEquals(blocked, status.states.value)
        assertFalse(observer.isOnline.value)
        assertEquals(1, events.size)
    }

    @Test
    fun validationAndTransportChangesAreDiagnosticOnly() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities(validated = false))
        val previous = status.states.value
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onCapabilitiesChanged(wifi, capabilities(validated = true))
        assertEquals(true, status.states.value.validated)
        callback.onCapabilitiesChanged(wifi, capabilities(validated = false, transport = NetworkCapabilities.TRANSPORT_VPN))
        assertEquals(false, status.states.value.validated)
        assertEquals(setOf(NetworkStatus.Transport.VPN), status.states.value.transports)
        assertTrue(observer.isOnline.value)
        assertEquals(previous.recoveryVersion, status.states.value.recoveryVersion)
        assertEquals(previous.networkGeneration, status.states.value.networkGeneration)
        assertTrue(events.isEmpty())
    }

    @Test
    fun internetCapabilityIsRequiredEvenWhenValidated() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities())
        val previous = status.states.value
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onCapabilitiesChanged(wifi, capabilities(internet = false, validated = true))
        assertFalse(observer.isOnline.value)
        assertEquals(false, status.states.value.hasInternet)
        assertEquals(true, status.states.value.validated)
        assertEquals(previous.recoveryVersion, status.states.value.recoveryVersion)

        callback.onCapabilitiesChanged(wifi, capabilities(internet = true, validated = false))
        assertTrue(observer.isOnline.value)
        assertEquals(previous.recoveryVersion + 1, status.states.value.recoveryVersion)
        assertEquals(1, events.size)
    }

    @Test
    fun blockedToUnblockedRecoversExactlyOnceWithoutWaitingForValidation() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities(validated = false))
        val previous = status.states.value
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onBlockedStatusChanged(wifi, true)
        callback.onBlockedStatusChanged(wifi, true)
        assertFalse(observer.isOnline.value)
        assertEquals(true, status.states.value.hasInternet)
        assertEquals(previous.recoveryVersion, status.states.value.recoveryVersion)
        assertTrue(events.isEmpty())

        callback.onBlockedStatusChanged(wifi, false)
        callback.onBlockedStatusChanged(wifi, false)
        assertTrue(observer.isOnline.value)
        assertEquals(false, status.states.value.validated)
        assertEquals(previous.recoveryVersion + 1, status.states.value.recoveryVersion)
        assertEquals(1, events.size)
    }

    @Test
    fun unblockingWithoutInternetDoesNotRecover() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities(internet = false))
        val previous = status.states.value
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onBlockedStatusChanged(wifi, true)
        callback.onBlockedStatusChanged(wifi, false)
        assertFalse(observer.isOnline.value)
        assertEquals(previous.recoveryVersion, status.states.value.recoveryVersion)
        assertTrue(events.isEmpty())
    }

    @Test
    fun callbacksAfterCurrentNetworkIsLostCannotResurrectIt() = runTest {
        val observer = observe(activeNetwork = wifi, initialCaps = capabilities())
        val events = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { observer.recovered.toList(events) }

        callback.onLost(wifi)
        val lost = status.states.value
        callback.onLost(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities())
        callback.onBlockedStatusChanged(wifi, false)

        assertEquals(lost, status.states.value)
        assertFalse(observer.isOnline.value)
        assertEquals(null, lost.blocked)
        assertEquals(null, lost.hasInternet)
        assertTrue(events.isEmpty())
        verify(exactly = 1) { connectivityManager.activeNetwork }
        verify(exactly = 1) { connectivityManager.getNetworkCapabilities(any()) }
    }

    @Test
    @Config(sdk = [26, 28])
    fun olderApisTreatUnknownBlockedAsAllowed() {
        val observer = observe()

        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities(validated = false))

        assertTrue(observer.isOnline.value)
        assertEquals(null, status.states.value.blocked)
        assertEquals(false, status.states.value.validated)
        assertEquals(1L, status.states.value.recoveryVersion)
        verify(exactly = 0) { connectivityManager.getNetworkCapabilities(any()) }
    }

    @Test
    fun registrationFailureIsLoggedWithSafeStateSummary() {
        val error = IllegalStateException("callback unavailable")
        every { connectivityManager.registerDefaultNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } throws error

        val observer = observe(activeNetwork = wifi, initialCaps = capabilities())

        assertTrue(observer.isOnline.value)
        verify(exactly = 1) {
            FileLogger.e(
                "NetworkConnectivity",
                "注册默认网络回调失败，${status.states.value.diagnosticSummary()}",
                error
            )
        }
    }

    private fun observe(
        activeNetwork: Network? = null,
        initialCaps: NetworkCapabilities? = null
    ): NetworkConnectivityObserver {
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityManager.getNetworkCapabilities(any()) } returns initialCaps
        return NetworkConnectivityObserver(context, status)
    }

    private fun capabilities(
        internet: Boolean = true,
        validated: Boolean = false,
        transport: Int = NetworkCapabilities.TRANSPORT_WIFI
    ): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        val shadow = shadowOf(it)
        shadow.clearCapabilities()
        if (internet) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.addTransportType(transport)
    }
}
