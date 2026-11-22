package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginCoreFixtures
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginDependencyPreparerTest {
    @get:Rule val temp = TemporaryFolder()
    private val fixture by lazy { PluginDependencyTestFixture(temp.root) }
    @After fun close() = runBlocking<Unit> { fixture.close() }
    private fun failed(result: PluginDependencyResult, code: String): PluginDependencyResult.Failed {
        assertTrue(result.toString(), result is PluginDependencyResult.Failed)
        return (result as PluginDependencyResult.Failed).also { assertEquals(code, it.code) }
    }
    private fun bundle(capture: com.aicode.feature.plugin.domain.PluginCapture) = capture.installDir.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(capture.installDir).invariantSeparatorsPath to it.readBytes().toList() }

    @Test fun noConfirmationTokenNeverStartsAPreparationProcess() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        failed(fixture.preparer.prepareDependencies(capture, "unconfirmed", request.inputDigest), "PLUGIN_CONFIRMATION_REQUIRED")
        assertTrue(fixture.launches.isEmpty())
        assertFalse(fixture.manager.areDependenciesPrepared(capture))
    }

    @Test fun aNativeTicketIsBoundToTheExactDescriptorAndConsumedOnlyOnce() = runBlocking<Unit> {
        val capture = fixture.install()
        val before = bundle(capture)
        val request = requireNotNull(fixture.preparer.describe(capture))
        val token = fixture.preparer.confirm(request)
        val repeated = assertThrows(PluginProcessException::class.java) { fixture.preparer.confirm(request) }
        assertEquals("PLUGIN_CONFIRMATION_REQUIRED", repeated.code)
        assertTrue(fixture.preparer.prepareDependencies(capture, token, request.inputDigest) is PluginDependencyResult.Ready)
        failed(fixture.preparer.prepareDependencies(capture, token, request.inputDigest), "PLUGIN_CONFIRMATION_REQUIRED")
        assertEquals(1, fixture.launches.size)
        assertTrue(fixture.manager.areDependenciesPrepared(capture))
        assertEquals(before, bundle(capture))
    }

    @Test fun changingAnyNativeDescriptorFieldInvalidatesItsConfirmation() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        val changed = request.copy(tool = "pip", inputDigest = "different")
        assertThrows(PluginProcessException::class.java) { fixture.preparer.confirm(changed) }
        assertThrows(PluginProcessException::class.java) { fixture.preparer.confirm(request) }
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun anExpiredRequestCannotBeConfirmed() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        fixture.now = request.expiresAt
        val failure = assertThrows(PluginProcessException::class.java) { fixture.preparer.confirm(request) }
        assertEquals("PLUGIN_CONFIRMATION_REQUIRED", failure.code)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun anExpiredConfirmedTicketCannotStartAProcess() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        val token = fixture.preparer.confirm(request)
        fixture.now = request.expiresAt
        failed(fixture.preparer.prepareDependencies(capture, token, request.inputDigest), "PLUGIN_CONFIRMATION_REQUIRED")
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun aTicketForAnOlderInstallationCannotPrepareANewRevision() = runBlocking<Unit> {
        val original = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(original))
        val token = fixture.preparer.confirm(request)
        val replacement = fixture.install(version = "1.1.0")
        assertNotEquals(original.installRevision, replacement.installRevision)
        failed(fixture.preparer.prepareDependencies(replacement, token, request.inputDigest), "PLUGIN_CONFIRMATION_REQUIRED")
        assertTrue(fixture.launches.isEmpty())
        assertFalse(fixture.manager.areDependenciesPrepared(replacement))
    }

    @Test fun wrongInputDigestConsumesTheTicketWithoutStartingAProcess() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        val token = fixture.preparer.confirm(request)
        failed(fixture.preparer.prepareDependencies(capture, token, "different"), "PLUGIN_CONFIRMATION_REQUIRED")
        failed(fixture.preparer.prepareDependencies(capture, token, request.inputDigest), "PLUGIN_CONFIRMATION_REQUIRED")
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun anUntrustedCaptureCannotEvenDescribeAPreparationRequest() = runBlocking<Unit> {
        val capture = fixture.install()
        fixture.manager.disable(capture.id)
        try {
            fixture.preparer.describe(capture)
            fail("expected stale capture")
        } catch (_: com.aicode.feature.plugin.domain.PluginCaptureException) {
            assertTrue(fixture.launches.isEmpty())
        }
    }

    @Test fun revokingTrustAfterDescribeRejectsConfirmationWithoutStarting() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        fixture.manager.disable(capture.id)
        assertThrows(com.aicode.feature.plugin.domain.PluginCaptureException::class.java) { fixture.preparer.confirm(request) }
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun revokingTrustAfterConfirmationRejectsThePreparationWithoutStarting() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        val token = fixture.preparer.confirm(request)
        fixture.manager.disable(capture.id)
        assertTrue(fixture.preparer.prepareDependencies(capture, token, request.inputDigest) is PluginDependencyResult.Failed)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun changingTheBundleAfterConfirmationDoesNotInstallTheChangedInputs() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        val token = fixture.preparer.confirm(request)
        File(capture.installDir, "package.json").appendText("\n ")
        assertTrue(fixture.preparer.prepareDependencies(capture, token, request.inputDigest) is PluginDependencyResult.Failed)
        assertTrue(fixture.launches.isEmpty())
        assertNull(fixture.manager.capture(capture.id))
    }

    @Test fun missingNodeOrPipIsReportedAndDoesNotMarkThePluginReady() = runBlocking<Unit> {
        val capture = fixture.install()
        fixture.onStart = { DependencyFakeProcess(exitCode = 127) }
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.MissingRuntime)
        assertEquals(1, fixture.launches.size)
        assertFalse(fixture.manager.areDependenciesPrepared(capture))
    }

    @Test fun failedInstallDoesNotProduceAReadyMarkerOrChangeTheBundle() = runBlocking<Unit> {
        val capture = fixture.install()
        val before = bundle(capture)
        fixture.onStart = { DependencyFakeProcess(exitCode = 2) }
        failed(fixture.prepare(capture), "PLUGIN_DEPENDENCY_FAILED")
        assertFalse(fixture.manager.areDependenciesPrepared(capture))
        val directory = fixture.manager.dependencyDirectory(capture)
        assertFalse(File(directory, "prepared-input.sha256").exists())
        assertEquals(before, bundle(capture))
    }

    @Test fun alreadyPreparedImagesAreVerifiedWithoutStartingAnotherInstaller() = runBlocking<Unit> {
        val capture = fixture.install()
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        assertEquals(1, fixture.launches.size)
    }

    @Test fun damagedPreparedImagesAreRejectedRatherThanSilentlyRepaired() = runBlocking<Unit> {
        val capture = fixture.install()
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        val directory = fixture.manager.dependencyDirectory(capture)
        File(directory, "image/src/main.cjs").appendText("\nchanged")
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Failed)
        assertEquals(1, fixture.launches.size)
    }

    @Test fun nativePendingRequestsHaveABoundedCapacity() = runBlocking<Unit> {
        val capture = fixture.install()
        repeat(32) { assertNotNull(fixture.preparer.describe(capture)) }
        try {
            fixture.preparer.describe(capture)
            fail("expected pending limit")
        } catch (failure: PluginProcessException) {
            assertEquals("PLUGIN_DEPENDENCY_BUSY", failure.code)
        }
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun changingTheLocalProfileAfterDescribeDoesNotStartAnInstaller() = runBlocking<Unit> {
        val capture = fixture.install()
        val request = requireNotNull(fixture.preparer.describe(capture))
        fixture.localProfile = fixture.localProfile.copy(id = "another-local-container")
        val token = fixture.preparer.confirm(request)
        failed(fixture.preparer.prepareDependencies(capture, token, request.inputDigest), "PLUGIN_DEPENDENCY_TARGET_CHANGED")
        assertTrue(fixture.launches.isEmpty())
        assertFalse(fixture.manager.areDependenciesPrepared(capture))
        assertFalse(File(fixture.manager.dependencyDirectory(capture), "image").exists())
    }

    @Test fun concurrentNativeConfirmationsInstallOnceAndTheSecondVerifiesTheReadyImage() = runBlocking<Unit> {
        val capture = fixture.install()
        val firstRequest = requireNotNull(fixture.preparer.describe(capture))
        val secondRequest = requireNotNull(fixture.preparer.describe(capture))
        val firstToken = fixture.preparer.confirm(firstRequest)
        val secondToken = fixture.preparer.confirm(secondRequest)
        val process = DependencyFakeProcess(initiallyAlive = true)
        fixture.onStart = { launch ->
            File(launch.workingDirectory, "node_modules/prepared-once.txt").apply {
                parentFile.mkdirs(); writeText("first installation")
            }
            process
        }
        val first = async(Dispatchers.IO) {
            fixture.preparer.prepareDependencies(capture, firstToken, firstRequest.inputDigest)
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { process.waiting.await(5, TimeUnit.SECONDS) })
            assertFalse(fixture.manager.areDependenciesPrepared(capture))
            val second = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                fixture.preparer.prepareDependencies(capture, secondToken, secondRequest.inputDigest)
            }
            assertFalse(second.isCompleted)
            assertEquals(1, fixture.launches.size)
            process.destroy()
            assertTrue(withTimeout(10_000) { first.await() } is PluginDependencyResult.Ready)
            assertTrue(withTimeout(10_000) { second.await() } is PluginDependencyResult.Ready)
            assertEquals(1, fixture.launches.size)
            val image = PluginDependencyImage.verify(capture, fixture.manager.dependencyDirectory(capture))
            assertEquals("first installation", File(image, "node_modules/prepared-once.txt").readText())
        } finally { process.destroy() }
    }

    @Test fun pluginsWithoutDependenciesHaveNoPreparationRequest() = runBlocking<Unit> {
        PluginCoreFixtures.install(fixture.manager)
        val capture = PluginCoreFixtures.activate(fixture.manager)
        assertNull(fixture.preparer.describe(capture))
        assertTrue(fixture.launches.isEmpty())
    }
}
