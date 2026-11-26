package com.aicode.feature.settings.presentation

import com.aicode.R
import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.ContainerImageEntry
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeState
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.DownloadedImageRecord
import com.aicode.feature.settings.presentation.component.buildContainerProfile
import com.aicode.feature.settings.presentation.component.chrootStatusResource
import com.aicode.feature.settings.presentation.component.containerProfileSubmitLabel
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerRuntimeProfileTest {
    private val instance = ChrootInstance("0123456789abcdef01234567", 5, 19, 1234)

    @Test
    fun newChrootIsSeparateFromBuiltinProotAndNeverInheritsProotFlagsOrBindings() {
        val created = requireNotNull(build(runtime = LocalContainerRuntime.CHROOT, useBundled = true))
        assertTrue(created.isChroot)
        assertFalse(created.isBuiltin)
        assertEquals(RootfsSource.Asset("alpine-rootfs.bin"), created.rootfsSource)
        assertTrue(created.extraArgs.isEmpty())
        assertTrue(created.extraBindings.isEmpty())
        assertNull(created.chrootInstance)
        assertEquals(LocalContainerRuntime.PROOT, ContainerProfile.BUILTIN_ALPINE.localRuntime)
        assertEquals(ContainerProfile.DEFAULT_PROOT_ARGS, ContainerProfile.BUILTIN_ALPINE.extraArgs)
    }

    @Test
    fun downloadedUbuntuAndDebianUseTheExplicitRuntimeWithoutImplicitInstanceCreation() {
        for (uri in listOf("file:///private/rootfs_images/ubuntu-base-arm64.tar.gz", "file:///private/rootfs_images/debian-arm64.tar.xz")) {
            val chroot = requireNotNull(build(runtime = LocalContainerRuntime.CHROOT, pickedUri = uri, args = emptyList()))
            assertTrue(chroot.isChroot)
            assertFalse(chroot.isBuiltin)
            assertEquals(RootfsSource.LocalFile(uri), chroot.rootfsSource)
            assertTrue(chroot.extraArgs.isEmpty())
            assertTrue(chroot.extraBindings.isEmpty())
            assertNull(chroot.chrootInstance)
            val proot = requireNotNull(build(runtime = LocalContainerRuntime.PROOT, pickedUri = uri, args = emptyList()))
            assertFalse(proot.isChroot)
            assertEquals(RootfsSource.LocalFile(uri), proot.rootfsSource)
            assertEquals(ContainerProfile.DEFAULT_PROOT_ARGS, proot.extraArgs)
            assertNull(proot.chrootInstance)
        }
    }

    @Test
    fun editingKeepsTheExactChrootInstanceRuntimeSourceAndCreationTime() {
        val original = requireNotNull(build(runtime = LocalContainerRuntime.CHROOT, pickedUri = "file:///private/rootfs_images/import_image"))
            .copy(id = "existing", chrootInstance = instance, createdAt = 400)
        val edited = requireNotNull(build(initial = original, runtime = LocalContainerRuntime.CHROOT, pickedUri = (original.rootfsSource as RootfsSource.LocalFile).uri))
        assertEquals(instance, edited.chrootInstance)
        assertEquals(original.rootfsSource, edited.rootfsSource)
        assertEquals(original.localRuntime, edited.localRuntime)
        assertEquals(400L, edited.createdAt)
        assertNull(build(initial = original, runtime = LocalContainerRuntime.PROOT, pickedUri = "file:///private/rootfs_images/import_image"))
        assertNull(build(initial = original, runtime = LocalContainerRuntime.CHROOT, pickedUri = "file:///private/rootfs_images/other_image"))
        assertNull(build(initial = original, mode = ExecutionMode.REMOTE_SSH, runtime = LocalContainerRuntime.CHROOT))
    }

    @Test
    fun existingProotCannotBeConvertedAndDefaultProotAndSshBehaviorArePreserved() {
        val imported = requireNotNull(build(pickedUri = "file:///private/rootfs_images/import_image", args = emptyList()))
        assertFalse(imported.isChroot)
        assertEquals(ContainerProfile.DEFAULT_PROOT_ARGS, imported.extraArgs)
        assertNull(build(initial = imported, runtime = LocalContainerRuntime.CHROOT, pickedUri = "file:///private/rootfs_images/import_image"))
        val builtin = requireNotNull(build(initial = ContainerProfile.BUILTIN_ALPINE))
        assertTrue(builtin.isBuiltin)
        assertEquals(ContainerProfile.BUILTIN_ALPINE.rootfsSource, builtin.rootfsSource)
        val ssh = requireNotNull(build(mode = ExecutionMode.REMOTE_SSH, runtime = LocalContainerRuntime.CHROOT))
        assertEquals(ExecutionMode.REMOTE_SSH, ssh.mode)
        assertEquals(RootfsSource.RemoteSsh("existing-channel", "/workspace"), ssh.rootfsSource)
        assertEquals(LocalContainerRuntime.PROOT, ssh.localRuntime)
        assertNull(ssh.chrootInstance)
        assertTrue(ssh.extraArgs.isEmpty())
    }

    @Test
    fun unknownAndUnhealthyStatesNeverAppearAsInstalledStoppedOrReady() {
        val installed = LocalRuntimeState(instance, true, true, false, 0, 0, false)
        assertEquals(R.string.container_chroot_status_unauthorized, chrootStatusResource(installed, null))
        assertEquals(R.string.container_chroot_status_not_installed, chrootStatusResource(installed.copy(instance = null), null))
        assertEquals(R.string.container_chroot_status_active, chrootStatusResource(installed.copy(activeLaunches = 1), null))
        assertNotEquals(R.string.container_chroot_status_stopped, chrootStatusResource(installed.copy(busy = false, activeLaunches = 1, authorizedOwners = 1), null))
        assertEquals(R.string.container_chroot_status_unauthorized, chrootStatusResource(installed.copy(authorizedOwners = 1), null))
        assertEquals(R.string.container_chroot_status_busy, chrootStatusResource(installed.copy(busy = true), null))
        assertEquals(R.string.container_chroot_status_installing, chrootStatusResource(installed, ChrootInstanceAction.CREATE))
        assertEquals(R.string.container_chroot_status_cleanup, chrootStatusResource(installed, ChrootInstanceAction.STOP))
        assertEquals(R.string.container_chroot_status_unknown, chrootStatusResource(installed.copy(recoveryRequired = true, activeLaunches = 1), null))
        assertEquals(R.string.container_chroot_status_unknown, chrootStatusResource(installed.copy(journalHealthy = false, authorizedOwners = 1), null))
    }

    @Test
    fun newProfileIdentityUsesUuidAndDeepFreezesAllMutableFormCollections() {
        val bindings = mutableListOf("/selected:/workspace")
        val args = mutableListOf("--kill-on-exit")
        val env = mutableMapOf("SELECTED" to "value")
        val draft = requireNotNull(build(pickedUri = "file:///private/selected.tar.gz")).copy(
            extraBindings = bindings, extraArgs = args, env = env
        )
        val first = newContainerProfile(draft)
        val second = newContainerProfile(draft)
        assertNotEquals(first.id, second.id)
        assertEquals(first.id.removePrefix("custom-"), UUID.fromString(first.id.removePrefix("custom-")).toString())
        assertTrue(first.createdAt > 0)
        bindings.clear()
        args += "--changed"
        env["SELECTED"] = "changed"
        assertEquals(listOf("/selected:/workspace"), first.extraBindings)
        assertEquals(listOf("--kill-on-exit"), first.extraArgs)
        assertEquals(mapOf("SELECTED" to "value"), first.env)
    }

    @Test
    fun downloadedRequestPreservesSelectedRuntimeDiskVersionAndUriWithoutCreatingAnInstance() {
        val entry = ContainerImageEntry("debian-12", "Debian", "12", path = "unused")
        val record = DownloadedImageRecord(entry.id, "file:///private/rootfs_images/debian-12.15.tar.xz", "12.15")
        for (runtime in LocalContainerRuntime.entries) {
            val addition = downloadedContainerProfileAddition(entry, record, record.fileUri, runtime, "12.99")
            assertEquals(runtime, addition.profile.localRuntime)
            assertEquals(RootfsSource.LocalFile(record.fileUri), addition.profile.rootfsSource)
            assertTrue(addition.profile.name.contains("12.15"))
            assertFalse(addition.profile.name.contains("12.99"))
            assertEquals(record, addition.downloadedImage)
            assertFalse(addition.setAsDefault)
            assertNull(addition.profile.chrootInstance)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun downloadRequestRejectsAReplacedSourceInsteadOfUsingLatestUri() {
        val entry = ContainerImageEntry("debian-12", "Debian", "12", path = "unused")
        val record = DownloadedImageRecord(entry.id, "file:///private/replaced.tar.xz", "12.15")
        downloadedContainerProfileAddition(entry, record, "file:///private/selected.tar.xz", LocalContainerRuntime.CHROOT, null)
    }

    @Test
    fun addCreateLabelIsExplicitOnlyForNewChrootAtBothEntrancesNeverEditsOrProot() {
        assertEquals(R.string.container_chroot_add_and_create, containerProfileSubmitLabel(true, true))
        assertEquals(R.string.container_chroot_add_and_create, containerProfileSubmitLabel(true, true, download = true))
        assertEquals(R.string.common_save, containerProfileSubmitLabel(false, true))
        assertEquals(R.string.common_add, containerProfileSubmitLabel(true, false))
        assertEquals(R.string.container_download_import, containerProfileSubmitLabel(true, false, download = true))
        assertEquals(R.string.container_importing, containerProfileSubmitLabel(true, true, importing = true))
    }

    private fun build(
        initial: ContainerProfile? = null,
        mode: ExecutionMode = ExecutionMode.LOCAL_PROOT,
        runtime: LocalContainerRuntime = LocalContainerRuntime.PROOT,
        useBundled: Boolean = false,
        pickedUri: String? = null,
        args: List<String> = listOf("--kill-on-exit")
    ) = buildContainerProfile(
        initial = initial, mode = mode, runtime = runtime, useBundledImage = useBundled,
        name = "example", shellPath = "/bin/sh", bindings = listOf("/outside:/mnt/outside"), args = args,
        env = mapOf("EXAMPLE" to "value"), pickedUri = pickedUri, selectedConnId = "existing-channel", remotePath = "/workspace"
    )
}
