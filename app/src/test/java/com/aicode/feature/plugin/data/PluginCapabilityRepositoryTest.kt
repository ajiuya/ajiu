package com.aicode.feature.plugin.data

import com.aicode.feature.plugin.domain.*
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginCapabilityRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 10_000L
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private fun manager() = PluginManager(File(temporary.root, "plugins"), File(temporary.root, "host/registry.json")) { now }
    private fun file() = File(temporary.root, "host/capabilities.enc")
    private fun encrypt(bytes: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(bytes)
    }
    private fun decrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    private fun repository(manager: PluginManager) = PluginCapabilityRepository(file(), manager, ::encrypt, ::decrypt) { now }
    @Test fun enablingPluginNeverAutomaticallyGrantsDeclaredCapabilities() = runBlocking<Unit> {
        val manager = manager()
        val repository = repository(manager)
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        repository.ensureLoaded()
        assertTrue(repository.grants.value.isEmpty())
        for (capability in PluginCapability.entries) assertFalse(repository.checkGranted(capture, capability))
        assertFalse(file().exists())
    }
    @Test fun everyCapabilityRequiresNativeSingleUseContentBoundChallenge() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        for (capability in PluginCapability.entries) {
            val challenge = requireNotNull(repository.prepareGrant(capture, capability))
            assertEquals(capture.installRevision, challenge.installRevision)
            assertEquals(capture.contentHash, challenge.contentHash)
            assertEquals(capture.activationEpoch, challenge.activationEpoch)
            assertEquals(capture.record.source, challenge.source)
            assertTrue(challenge.purpose.isNotBlank())
            assertFalse(repository.checkGranted(capture, capability))
            assertEquals(PluginOperationResult.Success, repository.confirmGrant(challenge.nativeToken))
            assertTrue(repository.checkGranted(capture, capability))
            assertTrue(repository.confirmGrant(challenge.nativeToken) is PluginOperationResult.Failure)
        }
        assertEquals(6, repository.grants.value.size)
    }
    @Test fun capabilityTokenIsEncryptedAndSeparateFromTheRegistry() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        val challenge = requireNotNull(repository.prepareGrant(capture, PluginCapability.MODEL_ACCESS))
        assertEquals(PluginOperationResult.Success, repository.confirmGrant(challenge.nativeToken))
        val grant = repository.grants.value.values.single()
        assertFalse(file().readBytes().toString(Charsets.UTF_8).contains(grant.token))
        assertFalse(file().readBytes().toString(Charsets.UTF_8).contains(capture.id))
        assertFalse(File(temporary.root, "host/registry.json").readText().contains(grant.token))
        val reloaded = repository(manager)
        reloaded.ensureLoaded()
        assertTrue(reloaded.checkGranted(capture, PluginCapability.MODEL_ACCESS))
        assertEquals(grant, reloaded.grants.value.values.single())
    }
    @Test fun undeclaredCapabilitiesCannotBeRequestedEvenAfterOrdinaryTrust() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager)
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        for (capability in PluginCapability.entries) {
            assertNull(repository.prepareGrant(capture, capability))
            assertFalse(repository.checkGranted(capture, capability))
        }
        assertTrue(repository.confirmGrant("invented") is PluginOperationResult.Failure)
    }
    @Test fun disablingRevokesAllGrantsChallengesAndOldLeasesCannotReviveOnEnable() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val old = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        val grant = requireNotNull(repository.prepareGrant(old, PluginCapability.MODEL_ACCESS))
        repository.confirmGrant(grant.nativeToken)
        val stale = requireNotNull(repository.prepareGrant(old, PluginCapability.SHIZUKU))
        manager.disable(old.id)
        assertTrue(repository.grants.value.isEmpty())
        assertFalse(repository.checkGranted(old, PluginCapability.MODEL_ACCESS))
        assertTrue(repository.confirmGrant(stale.nativeToken) is PluginOperationResult.Failure)
        val enabled = PluginCoreFixtures.activate(manager)
        assertEquals(old.installRevision, enabled.installRevision)
        assertFalse(repository.checkGranted(enabled, PluginCapability.MODEL_ACCESS))
        assertFalse(repository.checkGranted(old, PluginCapability.MODEL_ACCESS))
        val reloaded = repository(manager)
        reloaded.ensureLoaded()
        assertFalse(reloaded.checkGranted(enabled, PluginCapability.MODEL_ACCESS))
        assertTrue(reloaded.grants.value.isEmpty())
    }
    @Test fun sameVersionSameHashReinstallStillCannotInheritGrantsOrChallenges() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val old = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        repository.confirmGrant(requireNotNull(repository.prepareGrant(old, PluginCapability.BACKUP)).nativeToken)
        val pending = requireNotNull(repository.prepareGrant(old, PluginCapability.OVERLAY))
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val replacement = PluginCoreFixtures.activate(manager)
        assertEquals(old.contentHash, replacement.contentHash)
        assertNotEquals(old.installRevision, replacement.installRevision)
        assertFalse(repository.checkGranted(replacement, PluginCapability.BACKUP))
        assertTrue(repository.confirmGrant(pending.nativeToken) is PluginOperationResult.Failure)
        assertTrue(repository.grants.value.isEmpty())
    }
    @Test fun expiredAndTamperedChallengesCannotProduceAGrant() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        val expired = requireNotNull(repository.prepareGrant(capture, PluginCapability.BACKUP))
        now = expired.expiresAt
        assertTrue(repository.confirmGrant(expired.nativeToken) is PluginOperationResult.Failure)
        val pending = requireNotNull(repository.prepareGrant(capture, PluginCapability.SHIZUKU))
        File(capture.installDir, "extra/unreferenced.js").appendText("changed")
        assertTrue(repository.confirmGrant(pending.nativeToken) is PluginOperationResult.Failure)
        assertNull(repository.prepareGrant(capture, PluginCapability.SHIZUKU))
        assertTrue(repository.grants.value.isEmpty())
    }
    @Test fun explicitRevokeOnlyAffectsTheSelectedCapabilityAndPersists() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        for (capability in listOf(PluginCapability.MODEL_ACCESS, PluginCapability.BACKUP)) {
            repository.confirmGrant(requireNotNull(repository.prepareGrant(capture, capability)).nativeToken)
        }
        assertEquals(PluginOperationResult.Success, repository.revoke(capture.id, PluginCapability.MODEL_ACCESS))
        assertFalse(repository.checkGranted(capture, PluginCapability.MODEL_ACCESS))
        assertTrue(repository.checkGranted(capture, PluginCapability.BACKUP))
        val reloaded = repository(manager)
        reloaded.ensureLoaded()
        assertFalse(reloaded.checkGranted(capture, PluginCapability.MODEL_ACCESS))
        assertTrue(reloaded.checkGranted(capture, PluginCapability.BACKUP))
        assertEquals(PluginOperationResult.Success, reloaded.revoke(capture.id))
        assertFalse(reloaded.checkGranted(capture, PluginCapability.BACKUP))
    }
    @Test fun cleanupCaptureCanNeverReceiveOrReuseCapabilities() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        repository.confirmGrant(requireNotNull(repository.prepareGrant(capture, PluginCapability.BACKUP)).nativeToken)
        var called = false
        manager.registerLifecycleSink { event ->
            called = true
            assertTrue(event.capture.cleanup)
            assertFalse(repository.checkGranted(event.capture, PluginCapability.BACKUP))
            assertNull(repository.prepareGrant(event.capture, PluginCapability.BACKUP))
        }
        manager.disable(capture.id)
        assertTrue(called)
    }
    @Test fun encryptedStoreTamperingAndSymlinksArePreservedAndFailClosed() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        repository.confirmGrant(requireNotNull(repository.prepareGrant(capture, PluginCapability.BACKUP)).nativeToken)
        val damaged = file().readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file().writeBytes(damaged)
        val bad = repository(manager)
        bad.ensureLoaded()
        assertFalse(bad.checkGranted(capture, PluginCapability.BACKUP))
        assertNull(bad.prepareGrant(capture, PluginCapability.BACKUP))
        assertArrayEquals(damaged, file().readBytes())
        Files.delete(file().toPath())
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(file().toPath(), outside.toPath())
        val linked = repository(manager)
        linked.ensureLoaded()
        assertNull(linked.prepareGrant(capture, PluginCapability.BACKUP))
        assertTrue(Files.isSymbolicLink(file().toPath()))
        assertEquals("keep", outside.readText())
    }
    @Test fun preparingDependenciesDoesNotGrantAnyCapabilities() = runBlocking<Unit> {
        val manager = manager()
        PluginCoreFixtures.install(manager, PluginFullContributionFixtures.archive())
        val capture = PluginCoreFixtures.activate(manager)
        val repository = repository(manager)
        manager.dependencyDirectory(capture)
        assertEquals(PluginOperationResult.Success, manager.markDependenciesPrepared(capture))
        assertTrue(manager.areDependenciesPrepared(capture))
        for (capability in PluginCapability.entries) assertFalse(repository.checkGranted(capture, capability))
        assertTrue(repository.grants.value.isEmpty())
    }
}
