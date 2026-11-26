package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspacePathMapperRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sameIdProfileUpdatesRefreshMappingsAndChrootNeverFallsBackToJavaIo() = runBlocking {
        val workspace = mockk<WorkspaceRepository>()
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        val root = temporary.newFolder("workspace")
        val rootfs = temporary.newFolder("rootfs")
        val global = temporary.newFolder("config")
        val initial = ContainerProfile.BUILTIN_ALPINE.copy(id = "same-id", isBuiltin = false)
        val profiles = MutableStateFlow(listOf(initial))
        val selection = MutableStateFlow(initial.id)
        var configurationRevision = 1L
        var canonicalQueries = 0
        val trackedRootfs = object : File(rootfs.path) {
            override fun getCanonicalFile(): File {
                canonicalQueries++
                return super.getCanonicalFile()
            }
        }
        every { workspace.currentPath() } returns root.path
        every { workspace.selection } returns MutableStateFlow(WorkspaceRepository.Selection(null))
        every { installer.rootfsDirFor(any()) } returns trackedRootfs
        every { installer.aicodeDir } returns global
        every { settings.activeProfileIdFlow } returns selection
        every { settings.customProfilesFlow } returns profiles
        every { settings.activeProfileSelectionRevision } returns 1L
        every { settings.profileConfigurationRevision } answers { configurationRevision }
        every { home.home() } returns "/root"
        every { home.expandHome(any()) } answers {
            firstArg<String>().let { if (it == "~") "/root" else if (it.startsWith("~/")) "/root" + it.removePrefix("~") else it }
        }
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        suspend fun awaitProfile(profile: ContainerProfile) = withTimeout(5_000) {
            mapper.runtimeProfileState.first { it.ready && it.profile == profile }
        }
        val initialState = awaitProfile(initial)
        val access = LocalFileAccess(mapper)
        val first = mapper.captureExecutionMapping()
        val firstIdentity = access.cacheIdentity(first)
        val capturedCanonicalQueries = canonicalQueries
        repeat(5) { mapper.checkExecutionMapping(first) }
        assertEquals(capturedCanonicalQueries, canonicalQueries)

        val edited = initial.copy(env = mapOf("EDITOR" to "vim"), extraBindings = listOf("/app/shared:/shared"))
        configurationRevision++
        profiles.value = listOf(edited)
        assertThrows(CancellationException::class.java) { mapper.checkExecutionMapping(first) }
        val editedState = awaitProfile(edited)
        assertTrue(editedState.epoch > initialState.epoch)
        val afterEdit = mapper.captureExecutionMapping()
        assertEquals(edited, afterEdit.profile)
        assertNotEquals(firstIdentity, access.cacheIdentity(afterEdit))

        val chroot = edited.copy(localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 1, 2, 3))
        configurationRevision++
        profiles.value = listOf(chroot)
        val chrootState = awaitProfile(chroot)
        assertTrue(chrootState.epoch > editedState.epoch)
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { mapper.toHostFile("/etc/hosts") }
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { access.cacheIdentity() }
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { access.readFile("~/workspace/file") }
        verify(exactly = 0) { installer.rootfsDirFor(chroot) }

        configurationRevision++
        profiles.value = listOf(edited)
        val restored = awaitProfile(edited)
        assertEquals(editedState.profile, restored.profile)
        assertTrue(restored.epoch > chrootState.epoch)
        assertNotEquals(editedState, restored)
        assertEquals(root, mapper.toHostFile("~/workspace"))
        selection.value = "missing-profile-falls-back-to-same-profile"
        val republished = withTimeout(5_000) { mapper.runtimeProfileState.first { it.epoch > restored.epoch } }
        assertEquals(restored.profile, republished.profile)
    }

    @Test fun coldChrootCacheStaysUnreadyUntilFirstProfileLoadAndNeverReadsRootfs() = runBlocking {
        val workspace = mockk<WorkspaceRepository>()
        val installer = mockk<ContainerInstaller>()
        val settings = mockk<ContainerSettingsRepository>()
        val home = mockk<PathHomeResolver>()
        val profile = ContainerProfile.BUILTIN_ALPINE.copy(id = "uncreated-chroot", isBuiltin = false,
            localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = null)
        val release = CompletableDeferred<Unit>()
        every { settings.activeProfileIdFlow } returns flow { release.await(); emit(profile.id) }
        every { settings.customProfilesFlow } returns MutableStateFlow(listOf(profile))
        val mapper = WorkspacePathMapper(workspace, installer, settings, home)
        val initial = mapper.runtimeProfileState.value
        assertFalse(initial.ready)
        assertEquals(0L, initial.epoch)
        release.complete(Unit)
        val ready = withTimeout(5_000) { mapper.runtimeProfileState.first { it.ready } }
        assertEquals(profile, ready.profile)
        assertTrue(ready.epoch > initial.epoch)
        assertTrue(mapper.isChrootRuntime())
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { mapper.toHostFile("~/workspace") }
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { mapper.toContainerPath("/private/workspace") }
        assertThrows(LocalRuntimeAuthorizationRequired::class.java) { mapper.captureExecutionMapping() }
        verify(exactly = 0) { installer.rootfsDirFor(any()) }
        verify(exactly = 0) { installer.aicodeDir }
        verify(exactly = 0) { workspace.currentPath() }
    }
}
