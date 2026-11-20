package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginCapture
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginDependencyImageTest {
    @get:Rule val temp = TemporaryFolder()
    private val fixture by lazy { PluginDependencyTestFixture(temp.root) }
    @After fun close() = runBlocking<Unit> { fixture.close() }
    private fun tree(root: File): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
    private fun directory(capture: PluginCapture) = fixture.manager.dependencyDirectory(capture)
    private fun marker(capture: PluginCapture) = File(directory(capture), "prepared-input.sha256")

    @Test fun npmCopiesBothCommonJsAndEsModulesIntoThePrivateExecutionImage() = runBlocking<Unit> {
        val capture = fixture.install()
        val before = tree(capture.installDir)
        fixture.onStart = { launch ->
            File(launch.workingDirectory, "node_modules/fixture-dep/index.js").apply {
                parentFile.mkdirs(); writeText("module.exports = 1;")
            }
            DependencyFakeProcess()
        }
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        val image = PluginDependencyImage.verify(capture, directory(capture))
        assertEquals(File(directory(capture), "image"), image)
        assertEquals(before.getValue("src/main.cjs"), File(image, "src/main.cjs").readBytes().toList())
        assertEquals(before.getValue("src/main.mjs"), File(image, "src/main.mjs").readBytes().toList())
        assertTrue(File(image, "node_modules/fixture-dep/index.js").isFile)
        assertEquals(image.path, fixture.launches.single().workingDirectory)
        assertEquals(before, tree(capture.installDir))
        assertFalse(File(capture.installDir, "node_modules").exists())
    }

    @Test fun esmEntryUsesTheSamePackageRootImageAndRootNodeModules() = runBlocking<Unit> {
        val capture = fixture.install(entry = "src/main.mjs")
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        val image = PluginDependencyImage.verify(capture, directory(capture))
        assertEquals("src/main.mjs", capture.content.runtime!!.command.last())
        assertTrue(File(image, "package.json").isFile)
        assertTrue(File(image, "node_modules").isDirectory)
        assertTrue(File(image, "src/main.mjs").isFile)
    }

    @Test fun npmWithoutALockUsesInstallAndNeverWritesALockIntoTheBundle() = runBlocking<Unit> {
        val capture = fixture.install()
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        val command = fixture.launches.single().command
        assertTrue(command.contains("install"))
        assertTrue(command.contains("--package-lock=false"))
        assertFalse(command.contains("ci"))
        assertFalse(File(capture.installDir, "package-lock.json").exists())
    }

    @Test fun aRootPackageLockIsCopiedUnchangedAndSelectsNpmCi() = runBlocking<Unit> {
        val capture = fixture.install(lock = true)
        val original = File(capture.installDir, "package-lock.json").readText()
        assertTrue(PluginDependencyImage.inputs(capture).hasLock)
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        val image = PluginDependencyImage.verify(capture, directory(capture))
        assertEquals(original, File(image, "package-lock.json").readText())
        assertEquals(original, File(capture.installDir, "package-lock.json").readText())
        assertTrue(fixture.launches.single().command.contains("ci"))
        assertFalse(fixture.launches.single().command.contains("--package-lock=false"))
    }

    @Test fun pipUsesAPrivateSiblingTargetAndLeavesTheWholeBundleUntouched() = runBlocking<Unit> {
        val capture = fixture.install(tool = "pip")
        val before = tree(capture.installDir)
        fixture.onStart = { launch ->
            File(File(launch.workingDirectory).parentFile, "python/fixture_dep/__init__.py").apply {
                parentFile.mkdirs(); writeText("value = 1\n")
            }
            DependencyFakeProcess()
        }
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        val launch = fixture.launches.single()
        val target = launch.command.indexOf("--target")
        assertTrue(target >= 0)
        assertEquals("../python", launch.command[target + 1])
        assertEquals("requirements.txt", launch.command.last())
        assertTrue(File(directory(capture), "python/fixture_dep/__init__.py").isFile)
        assertEquals(File(directory(capture), "image"), PluginDependencyImage.verify(capture, directory(capture)))
        assertEquals(before, tree(capture.installDir))
        assertFalse(File(capture.installDir, "python").exists())
    }

    @Test fun nestedNpmPackageJsonIsRejectedBeforeAnyProcessStarts() = runBlocking<Unit> {
        val capture = fixture.install(input = "nested/package.json")
        val failure = assertThrows(PluginProcessException::class.java) { PluginDependencyImage.inputs(capture) }
        assertEquals("PLUGIN_DEPENDENCY_INPUT", failure.code)
        assertTrue(fixture.launches.isEmpty())
    }

    @Test fun changingThePreparedInputMarkerRejectsTheImage() = runBlocking<Unit> {
        val capture = fixture.install()
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        marker(capture).writeText("0".repeat(64))
        val failure = assertThrows(PluginProcessException::class.java) { PluginDependencyImage.verify(capture, directory(capture)) }
        assertEquals("PLUGIN_IMAGE_CHANGED", failure.code)
    }

    @Test fun changingOneCopiedModuleRejectsTheWholeImage() = runBlocking<Unit> {
        val capture = fixture.install()
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        File(directory(capture), "image/src/main.mjs").appendText("\nchanged")
        val failure = assertThrows(PluginProcessException::class.java) { PluginDependencyImage.verify(capture, directory(capture)) }
        assertEquals("PLUGIN_IMAGE_CHANGED", failure.code)
    }

    @Test fun anUndeclaredRootFileCannotBeAddedToTheExecutionImage() = runBlocking<Unit> {
        val capture = fixture.install()
        assertTrue(fixture.prepare(capture) is PluginDependencyResult.Ready)
        File(directory(capture), "image/injected.js").writeText("module.exports = 1;")
        val failure = assertThrows(PluginProcessException::class.java) { PluginDependencyImage.verify(capture, directory(capture)) }
        assertEquals("PLUGIN_IMAGE_CHANGED", failure.code)
    }

    @Test fun theNpmOutputRootCannotBeASymlinkToAnotherDirectory() = runBlocking<Unit> {
        val capture = fixture.install()
        val dir = directory(capture)
        val image = PluginDependencyImage.copy(capture, dir)
        marker(capture).writeText(PluginDependencyImage.inputs(capture).digest)
        val outside = temp.newFolder("outside-npm")
        Files.createSymbolicLink(File(image, "node_modules").toPath(), outside.toPath())
        assertThrows(PluginProcessException::class.java) { PluginDependencyImage.verify(capture, dir) }
        assertTrue(outside.isDirectory)
    }

    @Test fun thePipOutputRootCannotBeASymlinkToAnotherDirectory() = runBlocking<Unit> {
        val capture = fixture.install(tool = "pip")
        val dir = directory(capture)
        PluginDependencyImage.copy(capture, dir)
        marker(capture).writeText(PluginDependencyImage.inputs(capture).digest)
        val outside = temp.newFolder("outside-python")
        Files.createSymbolicLink(File(dir, "python").toPath(), outside.toPath())
        val failure = assertThrows(PluginProcessException::class.java) { PluginDependencyImage.verify(capture, dir) }
        assertEquals("PLUGIN_IMAGE_CHANGED", failure.code)
        assertTrue(outside.isDirectory)
    }

    @Test fun aCopiedBundleWithoutDependencyOutputIsNotConsideredPrepared() = runBlocking<Unit> {
        val capture = fixture.install()
        val dir = directory(capture)
        PluginDependencyImage.copy(capture, dir)
        marker(capture).writeText(PluginDependencyImage.inputs(capture).digest)
        val failure = assertThrows(PluginProcessException::class.java) { PluginDependencyImage.verify(capture, dir) }
        assertEquals("PLUGIN_IMAGE_CHANGED", failure.code)
    }

    @Test fun replacingOrRemovingALockChangesTheBoundInputDigest() = runBlocking<Unit> {
        val withLock = fixture.install(lock = true)
        val previous = PluginDependencyImage.inputs(withLock).digest
        val withoutLock = fixture.install(version = "1.1.0")
        assertFalse(PluginDependencyImage.inputs(withoutLock).hasLock)
        assertNotEquals(previous, PluginDependencyImage.inputs(withoutLock).digest)
    }
}
