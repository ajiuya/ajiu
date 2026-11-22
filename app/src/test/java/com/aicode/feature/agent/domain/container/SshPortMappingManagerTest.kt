package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.SSHClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SshPortMappingManagerTest {
    @Test fun pluginLeaseCannotInspectOrCloseUserOrOtherPluginMappings() = runTest {
        val fixture = Fixture(backgroundScope)
        runCurrent()
        val mine = PluginPortMappingOwner("fixture.first", "lease-one")
        val other = PluginPortMappingOwner("fixture.second", "lease-two")
        val user = fixture.manager.add(3000)
        val owned = fixture.manager.addOwned(3001, null, mine, requireNotNull(fixture.currentIdentity)) {}
        runCurrent()
        assertNull(fixture.manager.ownedMapping(mine, user))
        assertNull(fixture.manager.ownedMapping(other, owned))
        assertFalse(fixture.manager.closeOwned(other, owned))
        assertFalse(fixture.manager.closeOwned(mine, user))
        assertEquals("fixture.first", fixture.manager.ownedMapping(mine, owned)?.ownerPluginId)
        val close = async { fixture.manager.closeOwned(mine, owned) }
        runCurrent()
        assertTrue(close.await())
        assertTrue(fixture.manager.state.value.mappings.any { it.id == user && it.status == PortMappingStatus.ACTIVE })
        assertFalse(fixture.manager.state.value.mappings.any { it.id == owned })
        fixture.manager.closeAll()
        runCurrent()
    }

    @Before fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any(), any()) } just Runs
    }
    @After fun release() { unmockkObject(FileLogger) }

    private class Keepalive : PortMappingKeepalive {
        override val states = MutableStateFlow(PortMappingProtection(protected = false, suspended = false))
        val owners = linkedSetOf<String>()
        var acquired = 0
        var released = 0
        var onAcquire: () -> Unit = {}
        override fun snapshot() = states.value
        override fun acquire(owner: String) {
            acquired++
            assertTrue(owners.add(owner))
            onAcquire()
        }
        override fun release(owner: String) {
            released++
            assertTrue(owners.remove(owner))
        }
    }

    private class Resource(override val localPort: Int = 34000) : PortMappingResource {
        val closed = CompletableDeferred<Unit>()
        var closes = 0
        var probes = 0
        var listens = 0
        var probeFailure: Throwable? = null
        var listenFailure: Throwable? = null
        var onProbe: () -> Unit = {}
        var onListening: () -> Unit = {}
        var onClose: () -> Unit = {}
        override suspend fun probe(checkCurrent: () -> Unit) {
            probes++
            checkCurrent()
            onProbe()
            probeFailure?.let { throw it }
            checkCurrent()
        }
        override suspend fun listen(checkCurrent: () -> Unit, onListening: () -> Unit) {
            listens++
            checkCurrent()
            this.onListening()
            listenFailure?.let { throw it }
            onListening()
            awaitCancellation()
        }
        override fun close() { closes++; onClose(); closed.complete(Unit) }
    }

    private class Factory : PortMappingTransport {
        data class Open(val identity: RemoteConnectionIdentity, val remotePort: Int, val localPort: Int?, val disconnect: () -> Unit)
        val opens = mutableListOf<Open>()
        val resources = mutableListOf<Resource>()
        var acquire: suspend (Open) -> Resource = { Resource(34000 + opens.size).also { resources += it } }
        override suspend fun open(
            identity: RemoteConnectionIdentity,
            remotePort: Int,
            localPort: Int?,
            checkCurrent: () -> Unit,
            onDisconnected: () -> Unit
        ): PortMappingResource {
            val open = Open(identity, remotePort, localPort, onDisconnected)
            opens += open
            checkCurrent()
            return acquire(open)
        }
    }

    private class Fixture(
        scope: CoroutineScope,
        transform: (Flow<RemoteConnectionConfig?>) -> Flow<RemoteConnectionConfig?> = { it }
    ) {
        val config = RemoteConnectionConfig("fixture.invalid", username = "fixture",
            auth = RemoteAuth.Password("fixture-only"), remoteWorkspacePath = "/workspace")
        val active = MutableStateFlow<RemoteConnectionConfig?>(config)
        val identities = MutableStateFlow<RemoteConnectionIdentity?>(RemoteConnectionIdentity(mockk<SSHClient>(), "/home/fixture"))
        var currentIdentity = identities.value
        var actualConfig: RemoteConnectionConfig? = config
        val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
        val selections = MutableStateFlow(0L)
        var selectionRevision = 0L
        val settings = mockk<ContainerSettingsRepository>()
        val connection = mockk<RemoteSshConnection>()
        val keepalive = Keepalive()
        val factory = Factory()
        val manager: SshPortMappingManager
        init {
            every { settings.activeProfileSelectionRevisions } returns selections
            every { settings.activeProfileSelectionRevision } answers { selectionRevision }
            every { connection.identityState } returns identities
            every { connection.config } answers { actualConfig }
            every { connection.captureIdentity() } answers {
                currentIdentity ?: throw IllegalStateException("not connected")
            }
            every { connection.checkIdentity(any()) } answers {
                if (firstArg<RemoteConnectionIdentity>() !== currentIdentity || currentIdentity == null) {
                    throw CancellationException("identity changed")
                }
            }
            manager = SshPortMappingManager(connection, mode, settings, transform(active), keepalive, factory, scope)
        }
        fun row(id: String) = manager.state.value.mappings.single { it.id == id }
        fun reconnect(emit: Boolean = true) {
            currentIdentity = RemoteConnectionIdentity(mockk(), "/home/fixture")
            if (emit) identities.value = currentIdentity
        }
        fun profileRoundTrip(emit: Boolean = true) {
            selectionRevision += 2
            if (emit) selections.value = selectionRevision
        }
    }

    private fun TestScope.fixture() = Fixture(backgroundScope)

    @Test fun idleManagerDoesNotOpenTransportOrAcquireLease(): Unit = runTest {
        val f = fixture()
        runCurrent()
        assertTrue(f.manager.state.value.available)
        assertTrue(f.manager.state.value.remoteMode)
        assertTrue(f.manager.state.value.mappings.isEmpty())
        assertTrue(f.factory.opens.isEmpty())
        assertEquals(0, f.keepalive.acquired)
    }

    @Test fun automaticPortPublishesActualLoopbackAddressOnlyAfterListening(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        assertEquals(PortMappingStatus.CONNECTING, f.row(id).status)
        assertNull(f.manager.address(id))
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, f.row(id).status)
        assertEquals(34001, f.row(id).localPort)
        assertEquals("http://127.0.0.1:34001", f.manager.address(id))
        assertNull(f.factory.opens.single().localPort)
        assertEquals(8080, f.factory.opens.single().remotePort)
        assertFalse(f.row(id).backgroundProtected)
    }

    @Test fun requestedPortIsForwardedToTransportUnchanged(): Unit = runTest {
        val f = fixture()
        f.manager.add(65535, 43000)
        runCurrent()
        assertEquals(65535, f.factory.opens.single().remotePort)
        assertEquals(43000, f.factory.opens.single().localPort)
    }

    @Test fun invalidRemotePortsNeverReserveTransportOrLease(): Unit = runTest {
        val f = fixture()
        listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE).forEach { port ->
            val id = f.manager.add(port)
            assertEquals(PortMappingError.INVALID_PORT, f.row(id).error)
        }
        runCurrent()
        assertTrue(f.factory.opens.isEmpty())
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun invalidLocalPortsIncludingExplicitZeroAreRejected(): Unit = runTest {
        val f = fixture()
        listOf(-1, 0, 65536).forEach { port ->
            assertEquals(PortMappingError.INVALID_PORT, f.row(f.manager.add(8080, port)).error)
        }
        runCurrent()
        assertTrue(f.factory.opens.isEmpty())
    }

    @Test fun localModeRejectsNewMappings(): Unit = runTest {
        val f = fixture()
        f.mode.setMode(ExecutionMode.LOCAL_PROOT)
        val id = f.manager.add(8080)
        assertEquals(PortMappingError.LOCAL_MODE, f.row(id).error)
        assertFalse(f.manager.state.value.available)
        assertFalse(f.manager.state.value.remoteMode)
        runCurrent()
        assertTrue(f.factory.opens.isEmpty())
    }

    @Test fun disconnectedIdentityRejectsNewMappings(): Unit = runTest {
        val f = fixture()
        f.currentIdentity = null
        f.identities.value = null
        assertEquals(PortMappingError.NOT_CONNECTED, f.row(f.manager.add(8080)).error)
        runCurrent()
        assertTrue(f.factory.opens.isEmpty())
    }

    @Test fun nullOrMismatchedActiveConfigurationRejectsNewMappings(): Unit = runTest {
        val f = fixture()
        f.active.value = null
        assertEquals(PortMappingError.NOT_CONNECTED, f.row(f.manager.add(8080)).error)
        f.active.value = f.config.copy(host = "replacement.invalid")
        assertEquals(PortMappingError.NOT_CONNECTED, f.row(f.manager.add(8080)).error)
        runCurrent()
        assertTrue(f.factory.opens.isEmpty())
    }

    @Test fun fourPendingReservationsBlockFifthBeforeAcquisitionStarts(): Unit = runTest {
        val f = fixture()
        repeat(4) { f.manager.add(8000 + it) }
        val fifth = f.manager.add(9000)
        assertEquals(PortMappingError.LIMIT_REACHED, f.row(fifth).error)
        assertEquals(4, f.manager.state.value.mappings.count { it.status == PortMappingStatus.CONNECTING })
        runCurrent()
        assertEquals(4, f.factory.opens.size)
        assertEquals(4, f.keepalive.owners.size)
    }

    @Test fun failureRowsAreBoundedAndDoNotConsumeFourReservations(): Unit = runTest {
        val f = fixture()
        repeat(100) { f.manager.add(0) }
        assertEquals(16, f.manager.state.value.mappings.size)
        repeat(4) { f.manager.add(8080 + it) }
        runCurrent()
        assertEquals(20, f.manager.state.value.mappings.size)
        assertEquals(4, f.factory.opens.size)
    }

    @Test fun closingLastActiveMappingReturnsLastLeaseAndKeepsNoAddress(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.manager.close(id)
        runCurrent()
        assertTrue(f.factory.resources.single().closed.isCompleted)
        assertTrue(f.keepalive.owners.isEmpty())
        assertEquals(1, f.keepalive.released)
        assertNull(f.manager.address(id))
        assertTrue(f.manager.state.value.mappings.isEmpty())
    }

    @Test fun closeBeforeJobStartsNeverAcquiresTransportOrLease(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        f.manager.close(id)
        runCurrent()
        assertTrue(f.factory.opens.isEmpty())
        assertEquals(0, f.keepalive.acquired)
        assertTrue(f.manager.state.value.mappings.isEmpty())
    }

    @Test fun closeAllBeforeAnyMappedAddressCancelsAllPendingReservations(): Unit = runTest {
        val f = fixture()
        repeat(4) { f.manager.add(8080 + it) }
        f.manager.closeAll()
        runCurrent()
        assertTrue(f.manager.state.value.mappings.isEmpty())
        assertTrue(f.factory.opens.isEmpty())
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun lateResourceAfterCloseAllIsClosedRatherThanPublished(): Unit = runTest {
        val f = fixture()
        val acquired = CompletableDeferred<Resource>()
        val late = Resource()
        f.factory.acquire = { withContext(NonCancellable) { acquired.await() } }
        f.manager.add(8080)
        runCurrent()
        assertEquals(1, f.keepalive.owners.size)
        f.manager.closeAll()
        runCurrent()
        assertTrue(f.keepalive.owners.isEmpty())
        acquired.complete(late)
        runCurrent()
        assertTrue(late.closed.isCompleted)
        assertEquals(0, late.probes)
        assertEquals(0, late.listens)
        assertTrue(f.manager.state.value.mappings.isEmpty())
    }

    @Test fun nonCooperativeClosingAcquisitionsRetainFourSlotBudgetUntilResourcesReturn(): Unit = runTest {
        val f = fixture()
        val returned = CompletableDeferred<Unit>()
        val late = mutableListOf<Resource>()
        f.factory.acquire = {
            withContext(NonCancellable) { returned.await() }
            Resource().also { late += it }
        }
        repeat(4) { index ->
            val id = f.manager.add(8080 + index)
            runCurrent()
            f.manager.close(id)
            runCurrent()
        }
        repeat(20) {
            val rejected = f.manager.add(9000)
            assertEquals(PortMappingError.LIMIT_REACHED, f.row(rejected).error)
            f.manager.close(rejected)
        }
        assertEquals(4, f.factory.opens.size)
        f.manager.closeAll()
        returned.complete(Unit)
        runCurrent()
        assertEquals(4, late.size)
        assertTrue(late.all { it.closed.isCompleted })
        f.factory.acquire = { Resource(35000) }
        val fresh = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, f.row(fresh).status)
    }

    @Test fun lateOldFinalizerCannotCloseReplacementWithSameRequestedPort(): Unit = runTest {
        val f = fixture()
        val first = CompletableDeferred<Resource>()
        val old = Resource(40000)
        val fresh = Resource(40000)
        var firstCall = true
        f.factory.acquire = {
            if (firstCall) { firstCall = false; withContext(NonCancellable) { first.await() } } else fresh
        }
        val oldId = f.manager.add(8080, 40000)
        runCurrent()
        f.manager.close(oldId)
        val newId = f.manager.add(8080, 40000)
        runCurrent()
        first.complete(old)
        runCurrent()
        assertTrue(old.closed.isCompleted)
        assertFalse(fresh.closed.isCompleted)
        assertEquals(PortMappingStatus.ACTIVE, f.row(newId).status)
        assertEquals("http://127.0.0.1:40000", f.manager.address(newId))
        assertEquals(1, f.keepalive.owners.size)
    }

    @Test fun lateOldDisconnectCallbackCannotAffectReplacement(): Unit = runTest {
        val f = fixture()
        val oldId = f.manager.add(8080)
        runCurrent()
        val oldDisconnect = f.factory.opens.single().disconnect
        f.manager.close(oldId)
        val freshId = f.manager.add(8080)
        runCurrent()
        oldDisconnect()
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, f.row(freshId).status)
        assertFalse(f.factory.resources.last().closed.isCompleted)
        assertEquals(1, f.keepalive.owners.size)
    }

    @Test fun profileSameConfigurationRoundTripInvalidatesActiveMapping(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.profileRoundTrip()
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertNull(f.manager.address(id))
        assertTrue(f.factory.resources.single().closed.isCompleted)
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun synchronousSelectionRevisionFencesAddressBeforeFlowIsDelivered(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.profileRoundTrip(emit = false)
        assertNull(f.manager.address(id))
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        runCurrent()
        assertTrue(f.factory.resources.single().closed.isCompleted)
    }

    @Test fun modeRoundTripDoesNotReviveOldMapping(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.mode.setMode(ExecutionMode.LOCAL_PROOT)
        f.mode.setMode(ExecutionMode.REMOTE_SSH)
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertNull(f.manager.address(id))
        assertTrue(f.manager.state.value.available)
    }

    @Test fun sameHostIdentityReconnectInvalidatesActiveMapping(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.reconnect()
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertTrue(f.factory.resources.single().closed.isCompleted)
    }

    @Test fun synchronousIdentityCheckFencesAddressBeforeIdentityFlowDelivery(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.reconnect(emit = false)
        assertNull(f.manager.address(id))
        runCurrent()
        assertTrue(f.factory.resources.single().closed.isCompleted)
    }

    @Test fun activeConfigurationBecomingInvalidClosesOldMappingEvenIfSharedSshStaysConnected(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.active.value = null
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertFalse(f.manager.state.value.available)
        assertTrue(f.factory.resources.single().closed.isCompleted)
    }

    @Test fun configurationReplacementClosesOldMappingAndNeverUsesNewHostImplicitly(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.active.value = f.config.copy(host = "next.invalid")
        runCurrent()
        assertNull(f.manager.address(id))
        assertEquals(1, f.factory.opens.size)
        assertTrue(f.factory.resources.single().closed.isCompleted)
    }

    @Test fun synchronousConnectionConfigurationCheckFencesOldAddress(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.actualConfig = f.config.copy(port = 2222)
        assertNull(f.manager.address(id))
        runCurrent()
        assertTrue(f.factory.resources.single().closed.isCompleted)
    }

    @Test fun sourceChangedInsideProbeIsNeverPublishedAsActive(): Unit = runTest {
        val f = fixture()
        val resource = Resource().apply { onProbe = { f.profileRoundTrip(emit = false) } }
        f.factory.acquire = { resource }
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertEquals(0, resource.listens)
        assertNull(f.manager.address(id))
        assertTrue(resource.closed.isCompleted)
    }

    @Test fun sourceChangedAtListenerPublicationIsNeverExposed(): Unit = runTest {
        val f = fixture()
        val resource = Resource().apply { onListening = { f.profileRoundTrip(emit = false) } }
        f.factory.acquire = { resource }
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertNull(f.manager.address(id))
        assertTrue(resource.closed.isCompleted)
    }

    @Test fun sourceChangedDuringPendingAcquireClosesLateResource(): Unit = runTest {
        val f = fixture()
        val acquired = CompletableDeferred<Resource>()
        val late = Resource()
        f.factory.acquire = { withContext(NonCancellable) { acquired.await() } }
        val id = f.manager.add(8080)
        runCurrent()
        f.profileRoundTrip()
        acquired.complete(late)
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertTrue(late.closed.isCompleted)
        assertEquals(0, late.listens)
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun targetProbeFailureKeepsTypedFailureAndClosesTransport(): Unit = runTest {
        val f = fixture()
        val resource = Resource().apply { probeFailure = PortMappingFailure(PortMappingError.TARGET_UNREACHABLE) }
        f.factory.acquire = { resource }
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingStatus.FAILED, f.row(id).status)
        assertEquals(PortMappingError.TARGET_UNREACHABLE, f.row(id).error)
        assertEquals(0, resource.listens)
        assertTrue(resource.closed.isCompleted)
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun listenerFailureKeepsTypedFailureAndClosesTransport(): Unit = runTest {
        val f = fixture()
        val resource = Resource().apply { listenFailure = PortMappingFailure(PortMappingError.LISTEN_FAILED) }
        f.factory.acquire = { resource }
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingError.LISTEN_FAILED, f.row(id).error)
        assertNull(f.manager.address(id))
        assertTrue(resource.closed.isCompleted)
        assertEquals(1, f.keepalive.released)
    }

    @Test fun connectionFailureKeepsNoRawErrorTextOrCredentialInState(): Unit = runTest {
        val f = fixture()
        f.factory.acquire = { throw IOException("fixture-only secret should not escape") }
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_FAILED, f.row(id).error)
        assertFalse(f.manager.state.value.toString().contains("secret"))
        assertFalse(f.manager.state.value.toString().contains("fixture.invalid"))
        assertFalse(f.manager.state.value.toString().contains("fixture-only"))
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun localConflictDoesNotConsumeActiveReservationAfterFailure(): Unit = runTest {
        val f = fixture()
        f.factory.acquire = { throw PortMappingFailure(PortMappingError.PORT_IN_USE) }
        val id = f.manager.add(8080, 40000)
        runCurrent()
        assertEquals(PortMappingError.PORT_IN_USE, f.row(id).error)
        f.factory.acquire = { Resource() }
        repeat(4) { f.manager.add(8100 + it) }
        runCurrent()
        assertEquals(4, f.manager.state.value.mappings.count { it.status == PortMappingStatus.ACTIVE })
    }

    @Test fun closingOneMappingKeepsOtherTransportAndLeaseActive(): Unit = runTest {
        val f = fixture()
        val first = f.manager.add(8080)
        val second = f.manager.add(9090)
        runCurrent()
        f.manager.close(first)
        runCurrent()
        assertTrue(f.factory.resources.first().closed.isCompleted)
        assertFalse(f.factory.resources.last().closed.isCompleted)
        assertNotNull(f.manager.address(second))
        assertEquals(1, f.keepalive.owners.size)
    }

    @Test fun serviceRefusalAllowsMappingButNeverClaimsBackgroundProtection(): Unit = runTest {
        val f = fixture()
        f.keepalive.states.value = PortMappingProtection(protected = false, suspended = false)
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, f.row(id).status)
        assertFalse(f.row(id).backgroundProtected)
        assertEquals(1, f.keepalive.acquired)
    }

    @Test fun serviceProtectionChangesAreReflectedWithoutReacquiringLease(): Unit = runTest {
        val f = fixture()
        val id = f.manager.add(8080)
        runCurrent()
        f.keepalive.states.value = PortMappingProtection(protected = true, suspended = false)
        assertTrue(f.row(id).backgroundProtected)
        f.keepalive.states.value = PortMappingProtection(protected = false, suspended = false)
        assertFalse(f.row(id).backgroundProtected)
        assertEquals(1, f.keepalive.acquired)
    }

    @Test fun initialSuspendedValueDoesNotCloseOrdinaryMappingOrClaimProtection(): Unit = runTest {
        val f = fixture()
        f.keepalive.states.value = PortMappingProtection(protected = false, suspended = true)
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, f.row(id).status)
        assertFalse(f.row(id).backgroundProtected)
        assertEquals(1, f.keepalive.acquired)
    }

    @Test fun userTaskRemovalClosesActiveAndPendingWithoutRestartingThem(): Unit = runTest {
        val f = fixture()
        f.manager.add(8080)
        runCurrent()
        val acquired = CompletableDeferred<Resource>()
        val late = Resource()
        f.factory.acquire = { withContext(NonCancellable) { acquired.await() } }
        f.manager.add(9090)
        runCurrent()
        f.keepalive.states.value = PortMappingProtection(protected = false, suspended = true)
        assertTrue(f.manager.state.value.mappings.isEmpty())
        runCurrent()
        assertTrue(f.keepalive.owners.isEmpty())
        acquired.complete(late)
        runCurrent()
        f.keepalive.states.value = PortMappingProtection(protected = false, suspended = false)
        runCurrent()
        assertTrue(late.closed.isCompleted)
        assertTrue(f.manager.state.value.mappings.isEmpty())
        assertEquals(2, f.keepalive.acquired)
    }

    @Test fun closeDuringLeaseAcquisitionReturnsLateLeaseExactlyOnce(): Unit = runTest {
        val f = fixture()
        f.keepalive.onAcquire = { f.manager.closeAll() }
        f.manager.add(8080)
        runCurrent()
        assertEquals(1, f.keepalive.acquired)
        assertEquals(1, f.keepalive.released)
        assertTrue(f.keepalive.owners.isEmpty())
        assertTrue(f.factory.opens.isEmpty())
    }

    @Test fun resolverFailureRevokesExistingMappingAndMakesSourceUnavailable(): Unit = runTest {
        val fail = CompletableDeferred<Unit>()
        val f = Fixture(backgroundScope) { upstream -> flow {
            emitAll(upstream.take(1))
            fail.await()
            throw IOException("fixture private configuration must not be logged")
        } }
        val id = f.manager.add(8080)
        runCurrent()
        fail.complete(Unit)
        runCurrent()
        assertEquals(PortMappingError.CONNECTION_CHANGED, f.row(id).error)
        assertFalse(f.manager.state.value.available)
        assertTrue(f.factory.resources.single().closed.isCompleted)
        assertTrue(f.keepalive.owners.isEmpty())
    }

    @Test fun closingBlockedTransportDoesNotBlockStateOrOtherMappingOperations(): Unit = kotlinx.coroutines.runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = Resource().apply {
            onClose = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        }
        val f = Fixture(scope)
        var first = true
        f.factory.acquire = { if (first) { first = false; blocked } else Resource(34002) }
        try {
            val id = f.manager.add(8080)
            withTimeout(5_000) { f.manager.state.first { it.mappings.any { row -> row.id == id && row.status == PortMappingStatus.ACTIVE } } }
            val close = async(Dispatchers.Default) { f.manager.close(id) }
            withTimeout(5_000) { close.await() }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(f.manager.state.value.mappings.isEmpty())
            val second = f.manager.add(9090)
            withTimeout(5_000) { f.manager.state.first { it.mappings.any { row -> row.id == second && row.status == PortMappingStatus.ACTIVE } } }
            assertNotNull(f.manager.address(second))
            withTimeout(5_000) { async(Dispatchers.Default) { f.manager.close(second) }.await() }
            assertTrue(f.manager.state.value.mappings.isEmpty())
        } finally {
            release.countDown()
            f.manager.closeAll()
            scope.cancel()
            withTimeout(5_000) { blocked.closed.await() }
        }
    }

    @Test fun twoUiOwnersSeeOneSharedMappingAndDetachingOneDoesNotCloseIt(): Unit = runTest {
        val f = fixture()
        val firstStates = mutableListOf<PortMappingState>()
        val secondStates = mutableListOf<PortMappingState>()
        val first = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.manager.state.collect { firstStates += it } }
        val second = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { f.manager.state.collect { secondStates += it } }
        val id = f.manager.add(8080)
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, firstStates.last().mappings.single().status)
        assertEquals(firstStates.last(), secondStates.last())
        first.cancel()
        runCurrent()
        assertEquals(PortMappingStatus.ACTIVE, f.row(id).status)
        assertFalse(f.factory.resources.single().closed.isCompleted)
        second.cancel()
        runCurrent()
        assertNotNull(f.manager.address(id))
    }
}
