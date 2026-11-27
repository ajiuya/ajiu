package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteConnectionConfig
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.AgentConfigKind
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ContainerProfileSelectionRevisionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dataStoreOwners = mutableListOf<Job>()

    @After fun closeDataStores() = runBlocking {
        dataStoreOwners.forEach { it.cancelAndJoin() }
    }

    @Test fun actualSelectionAToBToAWithoutCacheQueriesInvalidatesOldFrozenDefinitions() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.newFolder("files")
        val settings = ContainerSettingsRepository(context)
        settings.setActiveProfile("a")
        val firstRevision = settings.activeProfileSelectionRevision
        settings.setActiveProfile("a")
        assertEquals(firstRevision, settings.activeProfileSelectionRevision)
        val connection = mockk<RemoteSshConnection>()
        val workspace = mockk<WorkspaceRepository>()
        val identity = RemoteConnectionIdentity(mockk(), "/home/user")
        every { connection.config } returns RemoteConnectionConfig("host", username = "user", auth = RemoteAuth.Password("fixture"), remoteWorkspacePath = "/project")
        every { connection.captureIdentity() } returns identity
        every { connection.checkIdentity(identity) } returns Unit
        every { workspace.currentPath() } returns "/project"
        val mode = ExecutionModeHolder().apply { setMode(ExecutionMode.REMOTE_SSH) }
        val access = RemoteSftpFileAccess(connection, workspace, RemoteAgentConfigCache(connection, settings, mode, workspace))
        val source = access.captureExecutionSource()
        val frozen = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, access.cacheIdentity(), access, null, source) { access.checkExecutionSource(source) }
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")
        assertEquals(firstRevision + 2, settings.activeProfileSelectionRevision)
        val failure = runCatching { withContext(frozen) { access.agentConfigSnapshot("~/.aicode/agents", AgentConfigKind.AGENTS) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        verify(exactly = 0) { identity.owner.startSession() }
    }

    @Test fun observableSelectionRevisionSurvivesAnAToBToAWithoutCollectingIntermediateProfiles() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.newFolder("files")
        val settings = ContainerSettingsRepository(context)
        assertEquals(0L, settings.activeProfileSelectionRevisions.value)
        settings.setActiveProfile("a")
        val before = settings.activeProfileSelectionRevisions.value
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")
        assertEquals(before + 2, settings.activeProfileSelectionRevisions.value)
        assertEquals(settings.activeProfileSelectionRevision, settings.activeProfileSelectionRevisions.value)
        assertEquals(listOf(settings.activeProfileSelectionRevision), settings.activeProfileSelectionRevisions.replayCache)
    }

    @Test fun selectingTheSameProfileDoesNotEmitANewSourceRevision() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns temporary.newFolder("files")
        val settings = ContainerSettingsRepository(context)
        settings.setActiveProfile("a")
        val before = settings.activeProfileSelectionRevisions.value
        repeat(3) { settings.setActiveProfile("a") }
        assertEquals(before, settings.activeProfileSelectionRevisions.value)
        assertEquals(before, settings.activeProfileSelectionRevision)
    }

    @Test fun sameIdEditsAdvanceConfigurationRevisionWithoutChangingTheSelectionRevision() = runBlocking {
        val settings = newSettings()
        val original = profile("a")
        assertConfigurationRevision(settings, 0L)
        settings.upsertCustomProfile(original)
        assertConfigurationRevision(settings, 1L)
        settings.setActiveProfile(original.id)
        val selectionRevision = settings.activeProfileSelectionRevision
        val withEnvironment = original.copy(env = mapOf("EDITOR" to "vim"))
        val withArguments = withEnvironment.copy(extraArgs = listOf("--kill-on-exit"))
        val withSource = withArguments.copy(rootfsSource = RootfsSource.LocalFile("content://images/replacement"))
        val withRuntime = withSource.copy(localRuntime = LocalContainerRuntime.CHROOT)
        val withInstance = withRuntime.copy(
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 1L, 2L, 1L)
        )
        val reinstalled = withInstance.copy(chrootInstance = withInstance.chrootInstance!!.copy(installGeneration = 2L))

        listOf(withEnvironment, withArguments, withSource, withRuntime, withInstance, reinstalled).forEachIndexed { index, edited ->
            settings.upsertCustomProfile(edited)
            assertConfigurationRevision(settings, index + 2L)
            assertEquals(selectionRevision, settings.activeProfileSelectionRevision)
            assertEquals(edited, settings.activeProfileFlow.first())
        }
    }

    @Test fun upsertingTheSameEffectiveProfileDoesNotAdvanceConfigurationRevision() = runBlocking {
        val settings = newSettings()
        val original = profile("a").copy(createdAt = 0L)
        settings.upsertCustomProfile(original)
        val stored = settings.customProfilesFlow.first().single()
        val revision = settings.profileConfigurationRevision

        repeat(3) { settings.upsertCustomProfile(original) }
        settings.upsertCustomProfile(stored)

        assertConfigurationRevision(settings, revision)
        assertEquals(stored, settings.customProfilesFlow.first().single())
        assertTrue(stored.createdAt > 0L)
    }

    @Test fun inactiveProfileCreationAndEditingAdvanceTheGlobalConfigurationRevision() = runBlocking {
        val settings = newSettings()
        val active = profile("a")
        val inactive = profile("b")
        settings.upsertCustomProfile(active)
        settings.setActiveProfile(active.id)
        val revision = settings.profileConfigurationRevision
        val selectionRevision = settings.activeProfileSelectionRevision

        settings.upsertCustomProfile(inactive)
        assertConfigurationRevision(settings, revision + 1L)
        settings.upsertCustomProfile(inactive.copy(shellPath = "/bin/bash"))
        assertConfigurationRevision(settings, revision + 2L)
        assertEquals(active, settings.activeProfileFlow.first())
        assertEquals(selectionRevision, settings.activeProfileSelectionRevision)
    }

    @Test fun activeSelectionRoundTripsLeaveConfigurationRevisionUnchanged() = runBlocking {
        val settings = newSettings()
        settings.setActiveProfile("a")
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")

        assertConfigurationRevision(settings, 0L)
        assertEquals(3L, settings.activeProfileSelectionRevision)
    }

    @Test fun defaultContainerChangesCountOnlyWhenTheEffectiveDefaultChanges() = runBlocking {
        val settings = newSettings()
        assertEquals(ContainerProfile.BUILTIN_ID, settings.defaultContainerIdFlow.first())
        settings.setDefaultContainerId(ContainerProfile.BUILTIN_ID)
        settings.setDefaultContainerId("")
        settings.setDefaultContainerId("   ")
        assertConfigurationRevision(settings, 0L)

        settings.setDefaultContainerId("a")
        assertConfigurationRevision(settings, 1L)
        repeat(3) { settings.setDefaultContainerId("a") }
        assertConfigurationRevision(settings, 1L)
        settings.setDefaultContainerId("b")
        settings.setDefaultContainerId("a")
        assertConfigurationRevision(settings, 3L)
        settings.setDefaultContainerId("")
        assertConfigurationRevision(settings, 4L)
        settings.setDefaultContainerId(ContainerProfile.BUILTIN_ID)
        assertConfigurationRevision(settings, 4L)
        assertEquals(ContainerProfile.BUILTIN_ID, settings.defaultContainerIdFlow.first())
        assertEquals(0L, settings.activeProfileSelectionRevision)
    }

    @Test fun deletingExistingProfilesChangesConfigurationButDeletingUnknownProfilesDoesNot() = runBlocking {
        val settings = newSettings()
        settings.deleteCustomProfile("missing")
        assertConfigurationRevision(settings, 0L)
        settings.upsertCustomProfile(profile("a"))
        settings.upsertCustomProfile(profile("b"))
        settings.setActiveProfile("a")
        val selectionRevision = settings.activeProfileSelectionRevision

        settings.deleteCustomProfile("b")
        assertConfigurationRevision(settings, 3L)
        settings.deleteCustomProfile("b")
        assertConfigurationRevision(settings, 3L)
        settings.deleteCustomProfile("a")
        assertConfigurationRevision(settings, 4L)
        settings.deleteCustomProfile("missing")
        assertConfigurationRevision(settings, 4L)
        assertEquals(ContainerProfile.BUILTIN_ALPINE, settings.activeProfileFlow.first())
        assertTrue(settings.customProfilesFlow.first().isEmpty())
        assertEquals(selectionRevision, settings.activeProfileSelectionRevision)
    }

    @Test fun listReorderingAndDisplayOrderMigrationDoNotChangeConfigurationRevision() = runBlocking {
        val settings = newSettings()
        val first = profile("a").copy(createdAt = 1L)
        val second = profile("b").copy(createdAt = 2L)
        val third = profile("c").copy(createdAt = 3L)
        listOf(first, second, third).forEach { settings.upsertCustomProfile(it) }
        val revision = settings.profileConfigurationRevision
        val contents = settings.customProfilesFlow.first().associateBy { it.id }

        settings.reorderCustomProfiles(listOf("a", "c", "b"))
        assertEquals(listOf("a", "c", "b"), settings.customProfilesFlow.first().map { it.id })
        assertEquals(contents, settings.customProfilesFlow.first().associateBy { it.id })
        assertConfigurationRevision(settings, revision)
        settings.ensureProfileDisplayOrder()
        assertEquals(listOf("c", "b", "a"), settings.customProfilesFlow.first().map { it.id })
        assertConfigurationRevision(settings, revision)
        settings.reorderCustomProfiles(listOf("b", "a", "c"))
        assertEquals(listOf("b", "a", "c"), settings.customProfilesFlow.first().map { it.id })
        assertEquals(contents, settings.customProfilesFlow.first().associateBy { it.id })
        assertConfigurationRevision(settings, revision)
    }

    @Test fun builtinInitializationOnlyChangesConfigurationWhenAProfileIsAdded() = runBlocking {
        val settings = newSettings()
        settings.ensureBuiltinDefault()
        assertConfigurationRevision(settings, 1L)
        assertEquals(listOf(ContainerProfile.BUILTIN_ALPINE), settings.customProfilesFlow.first())
        settings.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
        assertEquals(listOf(ContainerProfile.BUILTIN_ALPINE), settings.customProfilesFlow.first())
        assertConfigurationRevision(settings, 1L)
        settings.ensureBuiltinDefault()
        assertConfigurationRevision(settings, 1L)
        settings.deleteCustomProfile(ContainerProfile.BUILTIN_ID)
        assertConfigurationRevision(settings, 2L)
        settings.ensureBuiltinDefault()
        assertConfigurationRevision(settings, 2L)
        assertTrue(settings.customProfilesFlow.first().isEmpty())

        val existing = newSettings()
        existing.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
        existing.ensureBuiltinDefault()
        assertConfigurationRevision(existing, 1L)
    }

    @Test fun concurrentSameIdEditsDoNotLoseConfigurationRevisions() = runBlocking {
        val settings = newSettings()
        val original = profile("a")
        settings.upsertCustomProfile(original)
        val revision = settings.profileConfigurationRevision

        (1..8).map { version ->
            async(Dispatchers.Default) {
                settings.upsertCustomProfile(original.copy(env = mapOf("VERSION" to version.toString())))
            }
        }.awaitAll()

        assertConfigurationRevision(settings, revision + 8L)
        assertEquals(0L, settings.activeProfileSelectionRevision)
    }

    @Test fun distinctStoresKeepProfilesAndRevisionsIndependent() = runBlocking {
        val firstStore = newDataStore()
        val secondStore = newDataStore()
        val first = ContainerSettingsRepository(firstStore)
        val second = ContainerSettingsRepository(secondStore)
        val firstProfile = profile("shared").copy(env = mapOf("SOURCE" to "first"))
        val secondProfile = profile("shared").copy(env = mapOf("SOURCE" to "second"))

        first.upsertCustomProfile(firstProfile)
        assertConfigurationRevision(first, 1L)
        assertConfigurationRevision(second, 0L)
        assertTrue(second.customProfilesFlow.first().isEmpty())
        second.upsertCustomProfile(secondProfile)
        assertEquals(listOf(firstProfile), first.customProfilesFlow.first())
        assertEquals(listOf(secondProfile), second.customProfilesFlow.first())
        assertConfigurationRevision(first, 1L)
        assertConfigurationRevision(second, 1L)

        val editedFirst = firstProfile.copy(extraArgs = listOf("--kill-on-exit"))
        first.upsertCustomProfile(editedFirst)
        first.setDefaultContainerId(firstProfile.id)
        assertConfigurationRevision(first, 3L)
        assertConfigurationRevision(second, 1L)
        assertEquals(ContainerProfile.BUILTIN_ID, second.defaultContainerIdFlow.first())
        assertEquals(listOf(secondProfile), second.customProfilesFlow.first())
        second.deleteCustomProfile(secondProfile.id)
        assertConfigurationRevision(first, 3L)
        assertConfigurationRevision(second, 2L)
        assertEquals(listOf(editedFirst), first.customProfilesFlow.first())
        assertTrue(second.customProfilesFlow.first().isEmpty())

        assertEquals(listOf(editedFirst), ContainerSettingsRepository(firstStore).customProfilesFlow.first())
        assertTrue(ContainerSettingsRepository(secondStore).customProfilesFlow.first().isEmpty())
    }

    @Test fun failedPersistenceDoesNotPublishRequestedConfigurationChanges() = runBlocking {
        val blockedDirectory = temporary.newFile("blocked-files-directory")
        val storeFile = File(blockedDirectory, "container.preferences_pb")
        val settings = ContainerSettingsRepository(newDataStore(storeFile))
        assertTrue(blockedDirectory.isFile)
        assertTrue(settings.customProfilesFlow.first().isEmpty())
        assertEquals(ContainerProfile.BUILTIN_ID, settings.defaultContainerIdFlow.first())

        val upsertFailure = runCatching { settings.upsertCustomProfile(profile("a")) }.exceptionOrNull()
        assertTrue("Expected storage IOException, got $upsertFailure", upsertFailure is IOException)
        assertConfigurationRevision(settings, 0L)
        val defaultFailure = runCatching { settings.setDefaultContainerId("a") }.exceptionOrNull()
        assertTrue("Expected storage IOException, got $defaultFailure", defaultFailure is IOException)
        assertConfigurationRevision(settings, 0L)
        val initializationFailure = runCatching { settings.ensureBuiltinDefault() }.exceptionOrNull()
        assertTrue("Expected storage IOException, got $initializationFailure", initializationFailure is IOException)
        assertConfigurationRevision(settings, 0L)
        assertFalse(storeFile.exists())
        assertTrue(settings.customProfilesFlow.first().isEmpty())
        assertEquals(ContainerProfile.BUILTIN_ID, settings.defaultContainerIdFlow.first())
    }

    @Test fun capturedProfilesAreDeeplyImmutableAndSameIdRoundTripsStayInvalid() = runBlocking {
        val settings = newSettings()
        val original = profile("a").copy(
            env = mapOf("EDITOR" to "vi"), extraArgs = listOf("--kill-on-exit"),
            extraBindings = listOf("/app/shared:/shared")
        )
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        val snapshot = settings.captureProfileSnapshot()
        assertEquals(original, snapshot.activeProfile)
        assertEquals(original.id, snapshot.selectedProfileId)
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.activeProfile.env as MutableMap<String, String>)["EDITOR"] = "changed"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.activeProfile.extraArgs as MutableList<String>).add("changed")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.profiles as MutableList<ContainerProfile>).clear()
        }
        settings.checkProfileSnapshot(snapshot)
        settings.upsertCustomProfile(original.copy(env = mapOf("EDITOR" to "vim")))
        settings.upsertCustomProfile(original)
        assertEquals(original, settings.activeProfileFlow.first())
        assertEquals(snapshot.selectionRevision, settings.activeProfileSelectionRevision)
        assertThrows(CancellationException::class.java) { settings.checkProfileSnapshot(snapshot) }
        val afterEdit = settings.captureProfileSnapshot()
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")
        assertThrows(CancellationException::class.java) { settings.checkProfileSnapshot(afterEdit) }
        Unit
    }

    @Test fun profileSnapshotChecksUseOnlyMemoryAndRejectAnotherRepository() = runBlocking {
        val store = mockk<DataStore<Preferences>>()
        every { store.data } returns kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.emptyPreferences())
        val settings = ContainerSettingsRepository(store)
        val snapshot = settings.captureProfileSnapshot()
        every { store.data } throws AssertionError("Source checks cannot access DataStore")
        repeat(5) { settings.checkProfileSnapshot(snapshot) }
        val other = newSettings()
        assertThrows(CancellationException::class.java) { other.checkProfileSnapshot(snapshot) }
        Unit
    }

    @Test fun configurationRevocationRunsSynchronouslyOnlyAfterSuccessfulSourceChanges() = runBlocking {
        val settings = newSettings()
        val revisions = mutableListOf<Pair<Long, Long>>()
        val registration = settings.registerConfigurationRevocation {
            revisions += settings.activeProfileSelectionRevision to settings.profileConfigurationRevision
        }
        val original = profile("a")
        settings.upsertCustomProfile(original)
        assertEquals(listOf(0L to 1L), revisions)
        settings.setActiveProfile("a")
        assertEquals(listOf(0L to 1L, 1L to 1L), revisions)
        settings.upsertCustomProfile(original)
        settings.setActiveProfile("a")
        settings.reorderCustomProfiles(listOf("a"))
        settings.ensureProfileDisplayOrder()
        assertEquals(2, revisions.size)
        settings.upsertCustomProfile(original.copy(env = mapOf("EDITOR" to "vim")))
        assertEquals(1L to 2L, revisions.last())
        settings.setActiveProfile("b")
        settings.setActiveProfile("a")
        assertEquals(listOf(2L to 2L, 3L to 2L), revisions.takeLast(2))
        registration.close()
        settings.deleteCustomProfile("a")
        assertEquals(5, revisions.size)
    }

    @Test fun failedProfileEditsAndSelectionDoNotInvalidateAnExistingSnapshotOrNotifyRevocation() = runBlocking {
        val blocked = temporary.newFile("snapshot-blocked-directory")
        val settings = ContainerSettingsRepository(newDataStore(File(blocked, "container.preferences_pb")))
        val snapshot = settings.captureProfileSnapshot()
        var revoked = 0
        settings.registerConfigurationRevocation { revoked++ }
        assertTrue(runCatching { settings.upsertCustomProfile(profile("a")) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { settings.setActiveProfile("a") }.exceptionOrNull() is IOException)
        settings.checkProfileSnapshot(snapshot)
        assertEquals(0, revoked)
        assertEquals(0L, settings.activeProfileSelectionRevision)
        assertConfigurationRevision(settings, 0L)
    }

    @Test fun chrootInstancePublicationChangesOnlyTheCapturedUninstalledProfile() = runBlocking {
        val settings = newSettings()
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        val untouched = profile("proot")
        settings.upsertCustomProfile(untouched)
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        val snapshot = settings.captureProfileSnapshot()
        val instance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9)
        var revocations = 0
        settings.registerConfigurationRevocation { revocations++ }

        settings.publishChrootInstance(snapshot, original, instance)

        val published = settings.captureProfileSnapshot()
        assertEquals(original.copy(chrootInstance = instance), published.activeProfile)
        assertEquals(untouched, published.profiles.single { it.id == untouched.id })
        assertEquals(snapshot.selectionRevision, published.selectionRevision)
        assertEquals(snapshot.configurationRevision + 1, published.configurationRevision)
        assertEquals(1, revocations)
        assertThrows(CancellationException::class.java) { settings.checkProfileSnapshot(snapshot) }
        assertTrue(runCatching { settings.publishChrootInstance(published, published.activeProfile, instance.copy(installGeneration = 10)) }
            .exceptionOrNull() is CancellationException)
    }

    @Test fun chrootRemovalClearsActiveAndDefaultReferencesInOneSuccessfulPublication() = runBlocking {
        val settings = newSettings()
        val fallback = profile("fallback")
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9))
        settings.upsertCustomProfile(fallback)
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        settings.setDefaultContainerId(original.id)
        val snapshot = settings.captureProfileSnapshot()
        var revocations = 0
        settings.registerConfigurationRevocation { revocations++ }

        settings.publishChrootInstance(snapshot, original, null)

        val published = settings.captureProfileSnapshot()
        assertEquals(listOf(fallback), published.profiles)
        assertEquals(fallback.id, published.selectedProfileId)
        assertEquals(fallback.id, published.defaultContainerId)
        assertEquals(snapshot.selectionRevision + 1, published.selectionRevision)
        assertEquals(snapshot.configurationRevision + 1, published.configurationRevision)
        assertEquals(1, revocations)
    }

    @Test fun removalPublicationRechecksAuthorityAfterWaitingForTheProfileMutationLock() = runBlocking {
        val backing = newDataStore()
        val pause = AtomicBoolean()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = backing.updateData { before ->
                val next = transform(before)
                if (pause.get()) {
                    entered.complete(Unit)
                    release.await()
                }
                next
            }
        }
        val settings = ContainerSettingsRepository(store)
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9))
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        settings.setDefaultContainerId(original.id)
        val snapshot = settings.captureProfileSnapshot()
        val current = AtomicBoolean(true)
        val checks = AtomicInteger()
        val guard: () -> Unit = {
            checks.incrementAndGet()
            if (!current.get()) throw CancellationException("Root authority changed while publication was queued")
        }
        var revocations = 0
        settings.registerConfigurationRevocation { revocations++ }
        pause.set(true)
        val blocker = async { settings.upsertCustomProfile(original) } // No semantic change, but holds profileMutation.
        withTimeout(5_000) { entered.await() }
        guard()
        val publication = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { settings.publishChrootInstance(snapshot, original, null, guard) }.exceptionOrNull()
        }
        try {
            assertFalse(publication.isCompleted)
            assertEquals(1, checks.get())
            current.set(false)
        } finally {
            release.complete(Unit)
        }
        blocker.await()
        assertTrue(withTimeout(5_000) { publication.await() } is CancellationException)
        assertEquals(2, checks.get())
        assertEquals(0, revocations)
        val after = settings.captureProfileSnapshot()
        assertEquals(snapshot.profiles, after.profiles)
        assertEquals(snapshot.selectedProfileId, after.selectedProfileId)
        assertEquals(snapshot.defaultContainerId, after.defaultContainerId)
        assertEquals(snapshot.selectionRevision, after.selectionRevision)
        assertEquals(snapshot.configurationRevision, after.configurationRevision)
        settings.checkProfileSnapshot(snapshot)
    }

    @Test fun removalPublicationRechecksRootAndSessionInsideTheQueuedDataStoreEdit() = runBlocking {
        for (change in listOf("root", "session")) {
            val backing = newDataStore()
            val settings = ContainerSettingsRepository(backing)
            val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT,
                chrootInstance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9))
            settings.upsertCustomProfile(original)
            settings.setActiveProfile(original.id)
            settings.setDefaultContainerId(original.id)
            val snapshot = settings.captureProfileSnapshot()
            val rootCurrent = AtomicBoolean(true)
            val sessionCurrent = AtomicBoolean(true)
            val checks = AtomicInteger()
            val guard: () -> Unit = {
                checks.incrementAndGet()
                if (!rootCurrent.get() || !sessionCurrent.get()) throw CancellationException("$change changed while publication was queued")
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var revocations = 0
            settings.registerConfigurationRevocation { revocations++ }
            // Occupy DataStore without holding the repository mutex or changing profile revisions.
            val blocker = async(Dispatchers.IO) {
                backing.updateData { before ->
                    entered.complete(Unit)
                    release.await()
                    before
                }
            }
            withTimeout(5_000) { entered.await() }
            guard()
            val publication = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { settings.publishChrootInstance(snapshot, original, null, guard) }.exceptionOrNull()
            }
            try {
                assertFalse(publication.isCompleted)
                assertEquals(2, checks.get()) // Caller and mutateProfiles entry have both passed.
                if (change == "root") rootCurrent.set(false) else sessionCurrent.set(false)
            } finally {
                release.complete(Unit)
            }
            blocker.await()
            assertTrue("$change must reject the queued edit", withTimeout(5_000) { publication.await() } is CancellationException)
            assertEquals(3, checks.get())
            assertEquals(0, revocations)
            val after = settings.captureProfileSnapshot()
            assertEquals(snapshot.profiles, after.profiles)
            assertEquals(snapshot.selectedProfileId, after.selectedProfileId)
            assertEquals(snapshot.defaultContainerId, after.defaultContainerId)
            assertEquals(snapshot.selectionRevision, after.selectionRevision)
            assertEquals(snapshot.configurationRevision, after.configurationRevision)
            settings.checkProfileSnapshot(snapshot)
        }
    }

    @Test fun successfulGuardedRemovalAdvancesRevisionWithoutRecheckingItsOwnExpiredSnapshot() = runBlocking {
        val settings = newSettings()
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9))
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        settings.setDefaultContainerId(original.id)
        val snapshot = settings.captureProfileSnapshot()
        val checks = AtomicInteger()

        settings.publishChrootInstance(snapshot, original, null) {
            settings.checkProfileSnapshot(snapshot)
            checks.incrementAndGet()
            Unit
        }

        val after = settings.captureProfileSnapshot()
        assertEquals(2, checks.get())
        assertTrue(after.profiles.isEmpty())
        assertEquals(ContainerProfile.BUILTIN_ID, after.selectedProfileId)
        assertEquals(ContainerProfile.BUILTIN_ID, after.defaultContainerId)
        assertEquals(snapshot.configurationRevision + 1, after.configurationRevision)
        assertEquals(snapshot.selectionRevision + 1, after.selectionRevision)
        assertThrows(CancellationException::class.java) { settings.checkProfileSnapshot(snapshot) }
        Unit
    }

    @Test fun uninstalledChrootRemovalAtomicallyUpdatesOnlyConfigurationAndItsActiveDefaultReferences() = runBlocking {
        for (withFallback in listOf(false, true)) {
            val settings = newSettings()
            val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
            val fallback = profile("fallback")
            if (withFallback) settings.upsertCustomProfile(fallback)
            settings.upsertCustomProfile(original)
            settings.setActiveProfile(original.id)
            settings.setDefaultContainerId(original.id)
            val before = settings.captureProfileSnapshot()
            var checks = 0
            var revocations = 0
            settings.registerConfigurationRevocation { revocations++ }

            settings.removeUninstalledChrootProfile(before, original) {
                settings.checkProfileSnapshot(before)
                checks++
            }

            val after = settings.captureProfileSnapshot()
            val fallbackId = if (withFallback) fallback.id else ContainerProfile.BUILTIN_ID
            assertEquals(if (withFallback) listOf(fallback) else emptyList<ContainerProfile>(), after.profiles)
            assertEquals(fallbackId, after.selectedProfileId)
            assertEquals(fallbackId, after.defaultContainerId)
            assertEquals(before.selectionRevision + 1, after.selectionRevision)
            assertEquals(before.configurationRevision + 1, after.configurationRevision)
            assertEquals(1, checks)
            assertEquals(1, revocations)
            assertThrows(CancellationException::class.java) { settings.checkProfileSnapshot(before) }
        }
    }

    @Test fun removingAnInactiveUninstalledChrootProfilePreservesOtherSelectionAndDefault() = runBlocking {
        val settings = newSettings()
        val selected = profile("selected")
        val default = profile("default")
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        listOf(selected, default, original).forEach { settings.upsertCustomProfile(it) }
        settings.setActiveProfile(selected.id)
        settings.setDefaultContainerId(default.id)
        val before = settings.captureProfileSnapshot()

        settings.removeUninstalledChrootProfile(before, original) { settings.checkProfileSnapshot(before) }

        val after = settings.captureProfileSnapshot()
        assertEquals(before.profiles.filterNot { it.id == original.id }, after.profiles)
        assertEquals(selected.id, after.selectedProfileId)
        assertEquals(default.id, after.defaultContainerId)
        assertEquals(before.selectionRevision, after.selectionRevision)
        assertEquals(before.configurationRevision + 1, after.configurationRevision)
    }

    @Test fun uninstalledChrootRemovalRejectsStaleOrForeignSnapshotsBeforeCallingItsGuard() = runBlocking {
        for (change in listOf("configuration", "selection-round-trip", "repository")) {
            val settings = newSettings()
            val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
            settings.upsertCustomProfile(original)
            settings.setActiveProfile(original.id)
            val reviewed = settings.captureProfileSnapshot()
            val stale = when (change) {
                "configuration" -> {
                    settings.upsertCustomProfile(profile("another"))
                    reviewed
                }
                "selection-round-trip" -> {
                    settings.setActiveProfile("another")
                    settings.setActiveProfile(original.id)
                    reviewed
                }
                else -> newSettings().let { other ->
                    other.upsertCustomProfile(original)
                    other.setActiveProfile(original.id)
                    other.captureProfileSnapshot()
                }
            }
            val current = settings.captureProfileSnapshot()
            var checked = false
            var revocations = 0
            settings.registerConfigurationRevocation { revocations++ }

            assertTrue("$change must reject removal", runCatching {
                settings.removeUninstalledChrootProfile(stale, original) { checked = true }
            }.exceptionOrNull() is CancellationException)

            assertFalse(checked)
            assertEquals(0, revocations)
            assertEquals(current.profiles, settings.customProfilesFlow.first())
            assertConfigurationRevision(settings, current.configurationRevision)
            assertEquals(current.selectionRevision, settings.activeProfileSelectionRevision)
            settings.checkProfileSnapshot(current)
        }
    }

    @Test fun configurationOnlyRemovalRejectsInstalledBuiltinProotAndChangedProfileIdentities() = runBlocking {
        val fresh = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        val installed = fresh.copy(chrootInstance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9))
        for (stored in listOf(installed, fresh.copy(isBuiltin = true), profile("proot"), fresh)) {
            val settings = newSettings()
            settings.upsertCustomProfile(stored)
            val snapshot = settings.captureProfileSnapshot()
            val requested = if (stored == fresh) fresh.copy(rootfsSource = RootfsSource.LocalFile("content://images/changed")) else stored
            var checks = 0

            val failure = runCatching { settings.removeUninstalledChrootProfile(snapshot, requested) { checks++ } }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException || failure is CancellationException)
            assertEquals(0, checks)
            assertEquals(snapshot.profiles, settings.customProfilesFlow.first())
            settings.checkProfileSnapshot(snapshot)
            assertConfigurationRevision(settings, snapshot.configurationRevision)
        }
    }

    @Test fun uninstalledRemovalRechecksAuthorityInsideDataStoreAndLeavesReferencesIntactOnRejection() = runBlocking {
        val backing = newDataStore()
        var invalidateAtWrite = false
        var authorityCurrent = true
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = backing.updateData { before ->
                if (invalidateAtWrite) authorityCurrent = false
                transform(before)
            }
        }
        val settings = ContainerSettingsRepository(store)
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        settings.setDefaultContainerId(original.id)
        val before = settings.captureProfileSnapshot()
        var checks = 0
        var revocations = 0
        settings.registerConfigurationRevocation { revocations++ }
        assertTrue(authorityCurrent)
        invalidateAtWrite = true

        assertTrue(runCatching {
            settings.removeUninstalledChrootProfile(before, original) {
                checks++
                if (!authorityCurrent) throw CancellationException("Root or installation state changed")
            }
        }.exceptionOrNull() is CancellationException)

        assertEquals(1, checks)
        assertEquals(0, revocations)
        val after = settings.captureProfileSnapshot()
        assertEquals(before.profiles, after.profiles)
        assertEquals(before.selectedProfileId, after.selectedProfileId)
        assertEquals(before.defaultContainerId, after.defaultContainerId)
        assertEquals(before.selectionRevision, after.selectionRevision)
        assertEquals(before.configurationRevision, after.configurationRevision)
        settings.checkProfileSnapshot(before)
    }

    @Test fun uninstalledRemovalChecksPersistedProfileAgainEvenWhenLocalRevisionHasNotChanged() = runBlocking {
        val store = newDataStore()
        val settings = ContainerSettingsRepository(store)
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        settings.upsertCustomProfile(original)
        val before = settings.captureProfileSnapshot()
        val installed = original.copy(chrootInstance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9))
        ContainerSettingsRepository(store).upsertCustomProfile(installed)
        settings.checkProfileSnapshot(before)
        var checks = 0

        assertTrue(runCatching {
            settings.removeUninstalledChrootProfile(before, original) { checks++ }
        }.exceptionOrNull() is CancellationException)

        assertEquals(1, checks)
        assertEquals(listOf(installed), settings.customProfilesFlow.first())
        assertConfigurationRevision(settings, before.configurationRevision)
    }

    @Test fun failedUninstalledRemovalPersistenceDoesNotPublishDeletionOrAdvanceAnyRevision() = runBlocking {
        val backing = newDataStore()
        var failWrite = false
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = backing.updateData { before ->
                val next = transform(before)
                if (failWrite) throw IOException("Fixture persistence failed")
                next
            }
        }
        val settings = ContainerSettingsRepository(store)
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        settings.setDefaultContainerId(original.id)
        val before = settings.captureProfileSnapshot()
        var checks = 0
        var revocations = 0
        settings.registerConfigurationRevocation { revocations++ }
        failWrite = true

        assertTrue(runCatching {
            settings.removeUninstalledChrootProfile(before, original) { checks++ }
        }.exceptionOrNull() is IOException)

        assertEquals(1, checks)
        assertEquals(0, revocations)
        val after = settings.captureProfileSnapshot()
        assertEquals(before.profiles, after.profiles)
        assertEquals(before.selectedProfileId, after.selectedProfileId)
        assertEquals(before.defaultContainerId, after.defaultContainerId)
        assertEquals(before.selectionRevision, after.selectionRevision)
        assertEquals(before.configurationRevision, after.configurationRevision)
        settings.checkProfileSnapshot(before)
    }

    @Test fun profilePublicationRejectsStaleConfigurationAndCannotConvertProotIntoChroot() = runBlocking {
        val settings = newSettings()
        val original = profile("root").copy(localRuntime = LocalContainerRuntime.CHROOT)
        settings.upsertCustomProfile(original)
        val snapshot = settings.captureProfileSnapshot()
        val instance = ChrootInstance("0123456789abcdef01234567", 7, 8, 9)
        settings.upsertCustomProfile(original.copy(env = mapOf("EDIT" to "new")))
        val revision = settings.profileConfigurationRevision
        assertTrue(runCatching { settings.publishChrootInstance(snapshot, original, instance) }.exceptionOrNull() is CancellationException)
        assertConfigurationRevision(settings, revision)
        val proot = profile("proot")
        settings.upsertCustomProfile(proot)
        val current = settings.captureProfileSnapshot()
        assertTrue(runCatching { settings.publishChrootInstance(current, proot, instance) }.exceptionOrNull() is CancellationException)
        assertEquals(proot, settings.customProfilesFlow.first().single { it.id == proot.id })
        assertConfigurationRevision(settings, current.configurationRevision)
    }

    @Test fun semanticEditBlocksOldSnapshotsUntilFailedPersistenceHasFinishedWithoutAdvancingRevision() = runBlocking {
        val backing = newDataStore()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pause = java.util.concurrent.atomic.AtomicBoolean()
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = backing.updateData { before ->
                val next = transform(before)
                if (pause.get()) {
                    ready.complete(Unit)
                    release.await()
                    throw IOException("Fixture persistence failed")
                }
                next
            }
        }
        val settings = ContainerSettingsRepository(store)
        val original = profile("a")
        settings.upsertCustomProfile(original)
        val snapshot = settings.captureProfileSnapshot()
        pause.set(true)
        val pending = async {
            runCatching { settings.upsertCustomProfile(original.copy(env = mapOf("EDIT" to "pending"))) }.exceptionOrNull()
        }
        withTimeout(5_000) { ready.await() }
        assertThrows(CancellationException::class.java) { settings.checkProfileSnapshot(snapshot) }
        release.complete(Unit)
        assertTrue(pending.await() is IOException)
        settings.checkProfileSnapshot(snapshot)
        assertConfigurationRevision(settings, snapshot.configurationRevision)
        assertEquals(original, settings.customProfilesFlow.first().single())
    }

    @Test fun noOpAndReorderWritesDoNotTemporarilyInvalidateAnExistingProfileSnapshot() = runBlocking {
        val backing = newDataStore()
        var inspect: (() -> Unit)? = null
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = backing.updateData { before ->
                transform(before).also { inspect?.invoke() }
            }
        }
        val settings = ContainerSettingsRepository(store)
        val original = profile("a")
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        val snapshot = settings.captureProfileSnapshot()
        var checks = 0
        inspect = { settings.checkProfileSnapshot(snapshot); checks++ }
        settings.upsertCustomProfile(original)
        settings.setActiveProfile(original.id)
        settings.reorderCustomProfiles(listOf(original.id))
        settings.ensureProfileDisplayOrder()
        assertEquals(4, checks)
        settings.checkProfileSnapshot(snapshot)
    }

    private fun newSettings(): ContainerSettingsRepository = ContainerSettingsRepository(newDataStore())

    private fun newDataStore(file: File = File(temporary.newFolder(), "container.preferences_pb")): DataStore<Preferences> {
        val owner = SupervisorJob()
        dataStoreOwners += owner
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(owner + Dispatchers.IO)) { file }
    }

    private fun profile(id: String) = ContainerProfile(
        id = id,
        name = id,
        rootfsSource = RootfsSource.LocalFile("content://images/$id"),
        shellPath = "/bin/sh",
        isBuiltin = false,
        createdAt = 123L
    )

    private fun assertConfigurationRevision(settings: ContainerSettingsRepository, expected: Long) {
        assertEquals(expected, settings.profileConfigurationRevision)
        assertEquals(expected, settings.profileConfigurationRevisions.value)
        assertEquals(listOf(expected), settings.profileConfigurationRevisions.replayCache)
    }
}
