package com.aicode.feature.agent.domain.container

import com.aicode.feature.settings.data.repository.ExecutionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerProfileRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyLocalProfileWithoutRuntimeFieldsDefaultsToProot() {
        val legacy = """
            {
                "id":"legacy-local",
                "name":"Legacy local",
                "rootfsSource":{
                    "type":"com.aicode.feature.agent.domain.container.RootfsSource.LocalFile",
                    "uri":"content://images/legacy"
                },
                "shellPath":"/bin/sh",
                "extraBindings":["/sdcard:/mnt/sdcard"],
                "extraArgs":["--link2symlink"],
                "env":{"EDITOR":"vi"},
                "isBuiltin":false,
                "createdAt":123
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ContainerProfile.serializer(), legacy)

        assertEquals(ExecutionMode.LOCAL_PROOT, decoded.mode)
        assertEquals(LocalContainerRuntime.PROOT, decoded.localRuntime)
        assertNull(decoded.chrootInstance)
        assertFalse(decoded.isChroot)
        assertEquals(RootfsSource.LocalFile("content://images/legacy"), decoded.rootfsSource)
        assertEquals(listOf("/sdcard:/mnt/sdcard"), decoded.extraBindings)
        assertEquals(listOf("--link2symlink"), decoded.extraArgs)
        assertEquals(mapOf("EDITOR" to "vi"), decoded.env)
        assertEquals(123L, decoded.createdAt)
    }

    @Test
    fun legacySshProfileKeepsItsModeAndDoesNotAcquireALocalInstance() {
        val legacy = """
            {
                "id":"legacy-ssh",
                "name":"Legacy SSH",
                "rootfsSource":{
                    "type":"com.aicode.feature.agent.domain.container.RootfsSource.RemoteSsh",
                    "connectionId":"connection-a",
                    "remoteWorkspacePath":"/srv/project"
                },
                "shellPath":null,
                "isBuiltin":false,
                "mode":"REMOTE_SSH"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ContainerProfile.serializer(), legacy)

        assertEquals(ExecutionMode.REMOTE_SSH, decoded.mode)
        assertEquals(LocalContainerRuntime.PROOT, decoded.localRuntime)
        assertNull(decoded.chrootInstance)
        assertFalse(decoded.isChroot)
        assertEquals(RootfsSource.RemoteSsh("connection-a", "/srv/project"), decoded.rootfsSource)
        assertEquals(decoded, roundTrip(decoded))
        val encoded = json.parseToJsonElement(json.encodeToString(ContainerProfile.serializer(), decoded)).jsonObject
        assertFalse(encoded.containsKey("rootGrant"))
        assertFalse(encoded.containsKey("rootLease"))
        assertFalse(encoded.containsKey("token"))
    }

    @Test
    fun builtinAlpineRetainsItsLegacySourceAndProotDefaults() {
        val builtin = ContainerProfile.BUILTIN_ALPINE

        assertEquals(ContainerProfile.BUILTIN_ID, builtin.id)
        assertTrue(builtin.isBuiltin)
        assertEquals(RootfsSource.Asset("alpine-rootfs.bin"), builtin.rootfsSource)
        assertEquals(ContainerProfile.DEFAULT_PROOT_ARGS, builtin.extraArgs)
        assertEquals(ExecutionMode.LOCAL_PROOT, builtin.mode)
        assertEquals(LocalContainerRuntime.PROOT, builtin.localRuntime)
        assertNull(builtin.chrootInstance)
        assertFalse(builtin.isChroot)
        assertEquals(builtin, roundTrip(builtin))
    }

    @Test
    fun chrootProfilesRoundTripWithExistingAssetAndLocalFileSources() {
        val instance = ChrootInstance("0123456789abcdef01234567", 11L, 22L, 3L)
        val sources = listOf(RootfsSource.Asset("alpine-rootfs.bin"), RootfsSource.LocalFile("content://images/custom"))

        sources.forEach { source ->
            val profile = localProfile().copy(
                rootfsSource = source,
                localRuntime = LocalContainerRuntime.CHROOT,
                chrootInstance = instance
            )
            val decoded = roundTrip(profile)

            assertEquals(profile, decoded)
            assertEquals(instance, decoded.chrootInstance)
            assertNotEquals(profile.id, decoded.chrootInstance!!.id)
            assertTrue(decoded.isChroot)
        }
    }

    @Test
    fun runtimeAndEveryInstanceIdentityComponentParticipateInProfileIdentity() {
        val proot = localProfile()
        val chroot = proot.copy(localRuntime = LocalContainerRuntime.CHROOT)
        val instance = ChrootInstance("0123456789abcdef01234567", 11L, 22L, 3L)
        val installed = chroot.copy(chrootInstance = instance)

        assertEquals(proot.id, chroot.id)
        assertNotEquals(proot, chroot)
        assertNotEquals(
            json.encodeToString(ContainerProfile.serializer(), proot),
            json.encodeToString(ContainerProfile.serializer(), chroot)
        )
        assertNotEquals(chroot, installed)
        listOf(
            instance.copy(id = "abcdef0123456789abcdef01"),
            instance.copy(device = instance.device + 1L),
            instance.copy(inode = instance.inode + 1L),
            instance.copy(installGeneration = instance.installGeneration + 1L)
        ).forEach { changed ->
            assertNotEquals(installed, installed.copy(chrootInstance = changed))
        }
    }

    @Test
    fun choosingChrootDoesNotAllocateAnInstanceFromTheProfileId() {
        val profile = localProfile().copy(
            id = "0123456789abcdef01234567",
            localRuntime = LocalContainerRuntime.CHROOT
        )

        assertTrue(profile.isChroot)
        assertNull(profile.chrootInstance)
        assertNull(roundTrip(profile).chrootInstance)
    }

    @Test
    fun localRuntimeFieldDoesNotCoerceSshProfilesToLocalMode() {
        val profile = localProfile().copy(
            rootfsSource = RootfsSource.RemoteSsh("connection-a", "/srv/project"),
            mode = ExecutionMode.REMOTE_SSH,
            localRuntime = LocalContainerRuntime.CHROOT
        )
        val decoded = roundTrip(profile)

        assertEquals(ExecutionMode.REMOTE_SSH, decoded.mode)
        assertEquals(LocalContainerRuntime.CHROOT, decoded.localRuntime)
        assertFalse(decoded.isChroot)
        assertNull(decoded.chrootInstance)
    }

    @Test
    fun storedInstanceDecodeDefersValidationToSystemCreationAndLaunchBoundaries() {
        val stored = """{"id":"legacy-instance","device":0,"inode":-1,"installGeneration":0,"futureField":true}"""
        val instance = json.decodeFromString(ChrootInstance.serializer(), stored)
        val profile = localProfile().copy(localRuntime = LocalContainerRuntime.CHROOT, chrootInstance = instance)

        assertEquals(ChrootInstance("legacy-instance", 0L, -1L, 0L), instance)
        assertEquals(profile, roundTrip(profile))
    }

    @Test
    fun serializedInstanceContainsOnlyPersistentFilesystemIdentity() {
        val profile = localProfile().copy(
            localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 11L, 22L, 3L)
        )
        val encoded = json.parseToJsonElement(json.encodeToString(ContainerProfile.serializer(), profile)).jsonObject

        assertEquals(
            setOf("id", "device", "inode", "installGeneration"),
            encoded.getValue("chrootInstance").jsonObject.keys
        )
        listOf("hostRootfsPath", "rootGrant", "rootLease", "token", "ownerIdentity").forEach { transientField ->
            assertFalse(encoded.containsKey(transientField))
        }
        assertFalse(encoded.containsKey("isChroot"))
    }

    private fun localProfile() = ContainerProfile(
        id = "custom-profile",
        name = "Custom profile",
        rootfsSource = RootfsSource.LocalFile("content://images/custom"),
        shellPath = "/bin/sh",
        isBuiltin = false,
        createdAt = 123L
    )

    private fun roundTrip(profile: ContainerProfile): ContainerProfile = json.decodeFromString(
        ContainerProfile.serializer(),
        json.encodeToString(ContainerProfile.serializer(), profile)
    )
}
