package com.aicode.feature.agent.domain.container

import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.MobileBuildSettingsRepository
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.nio.file.Files
import java.util.Properties

class MobileBuildConfiguratorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val enabled = MobileBuildSettings(enabled = true)

    @Test
    fun propertiesPreserveAapt2OriginalHeapAndAllOriginalContent() {
        val original = "# user settings and 注释\n" +
            "android.aapt2FromMavenOverride=/opt/android/aapt2\n" +
            "org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8\n" +
            "custom.value=keep\\ value\n"

        val updated = MobileBuildGradleFiles.properties(original, enabled)

        assertTrue(updated.startsWith(original + "\n\n" + MobileBuildGradleFiles.BEGIN + "\n"))
        assertTrue(updated.contains("org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8\n"))
        val parsed = loadProperties(updated)
        assertEquals("/opt/android/aapt2", parsed.getProperty("android.aapt2FromMavenOverride"))
        assertEquals("keep value", parsed.getProperty("custom.value"))
        assertTrue(parsed.getProperty("org.gradle.jvmargs").startsWith("-Xmx3072m "))
        assertEquals("false", parsed.getProperty("org.gradle.daemon"))
        assertEquals("false", parsed.getProperty("org.gradle.parallel"))
        assertEquals("1", parsed.getProperty("org.gradle.workers.max"))
        assertEquals("false", parsed.getProperty("org.gradle.configuration-cache"))
        assertEquals("in-process", parsed.getProperty("kotlin.compiler.execution.strategy"))
        assertEquals("true", parsed.getProperty("aicodeMobileBuild"))
        assertEquals("1024m", parsed.getProperty("aicodeMobileTestHeap"))
        assertEquals(original, MobileBuildGradleFiles.properties(updated, enabled.copy(enabled = false)))
    }

    @Test
    fun twoAndAHalfGiBIsWrittenAs2560Megabytes() {
        val parsed = loadProperties(MobileBuildGradleFiles.properties("", enabled.copy(gradleHeapMb = 2560)))

        assertTrue(parsed.getProperty("org.gradle.jvmargs").startsWith("-Xmx2560m "))
        assertFalse(parsed.getProperty("org.gradle.jvmargs").contains("2.5"))
    }

    @Test
    fun enablingAndDisablingRestoresOriginalBytesIncludingLineEndingsAndContinuations() {
        val originals = listOf(
            "",
            "key=value",
            "key=value\n",
            "# original\r\nandroid.aapt2FromMavenOverride=/aapt2\r\norg.gradle.jvmargs=-Xmx5g\r\n",
            "# original\r\nkey=value",
            "trailing=continued\\",
            "trailing=continued\\\n",
            "trailing=continued\\\r\n",
            "first=one\nsecond=two\n\n"
        )

        originals.forEach { original ->
            val updated = MobileBuildGradleFiles.properties(original, enabled)
            assertTrue(updated.startsWith(original))
            assertEquals("true", loadProperties(updated).getProperty("aicodeMobileBuild"))
            assertEquals("1024m", loadProperties(updated).getProperty("aicodeMobileTestHeap"))
            assertEquals(updated, MobileBuildGradleFiles.properties(updated, enabled))
            val restored = MobileBuildGradleFiles.properties(updated, enabled.copy(enabled = false))
            assertEquals(original.toByteArray(Charsets.UTF_8).toList(), restored.toByteArray(Charsets.UTF_8).toList())
            assertEquals(original, MobileBuildGradleFiles.properties(original, enabled.copy(enabled = false)))
        }
    }

    @Test
    fun enablingAndDisablingPreservesLatin1AndUtf8OriginalFileBytes() {
        val originals = listOf(
            "custom=".toByteArray() + byteArrayOf(0xE9.toByte(), 0xFF.toByte()) + "\r\norg.gradle.jvmargs=-Xmx6g".toByteArray(),
            "# 构建配置\r\ncustom=é\r\norg.gradle.jvmargs=-Xmx5g".toByteArray(Charsets.UTF_8)
        )
        originals.forEach { original ->
            val rootfs = temporary.newFolder()
            val properties = propertiesFile(rootfs)
            properties.parentFile!!.mkdirs()
            properties.writeBytes(original)

            MobileBuildGradleFiles.changes(rootfs, enabled).forEach { it.write(it.after) }
            assertArrayEquals(original, properties.readBytes().take(original.size).toByteArray())
            val changed = enabled.copy(gradleHeapMb = 2560, testHeapMb = 768)
            MobileBuildGradleFiles.changes(rootfs, changed).forEach { it.write(it.after) }
            assertArrayEquals(original, properties.readBytes().take(original.size).toByteArray())
            assertEquals("768m", loadProperties(properties.readText(Charsets.ISO_8859_1)).getProperty("aicodeMobileTestHeap"))
            MobileBuildGradleFiles.changes(rootfs, changed.copy(enabled = false)).forEach { it.write(it.after) }

            assertArrayEquals(original, properties.readBytes())
        }
    }

    @Test
    fun changedParametersReplaceExactlyOneManagedBlock() {
        val original = "org.gradle.jvmargs=-Xmx6g\nandroid.aapt2FromMavenOverride=/aapt2"
        val first = MobileBuildGradleFiles.properties(original, enabled)
        val next = enabled.copy(
            gradleHeapMb = 2560,
            daemonEnabled = true,
            parallelEnabled = true,
            maxWorkers = 4,
            testHeapMb = 1536
        )

        val updated = MobileBuildGradleFiles.properties(first, next)

        assertEquals(1, occurrences(updated, MobileBuildGradleFiles.BEGIN))
        assertEquals(1, occurrences(updated, MobileBuildGradleFiles.END))
        assertEquals(MobileBuildGradleFiles.properties(original, next), updated)
        assertEquals(updated, MobileBuildGradleFiles.properties(updated, next))
        val parsed = loadProperties(updated)
        assertTrue(parsed.getProperty("org.gradle.jvmargs").startsWith("-Xmx2560m "))
        assertEquals("true", parsed.getProperty("org.gradle.daemon"))
        assertEquals("true", parsed.getProperty("org.gradle.parallel"))
        assertEquals("4", parsed.getProperty("org.gradle.workers.max"))
        assertEquals("1536m", parsed.getProperty("aicodeMobileTestHeap"))
        assertEquals(original, MobileBuildGradleFiles.properties(updated, next.copy(enabled = false)))
    }

    @Test
    fun propertiesAppendedAfterTheManagedBlockRemainSeparateAndRepeatedUpdatesDoNotAddNewlines() {
        val suffix = "android.aapt2FromMavenOverride=/sdk/aapt2\n"
        for (original in listOf("foo=bar", "foo=bar\\", "foo=bar\r\n")) {
            val withAppendedProperties = MobileBuildGradleFiles.properties(original, enabled) + suffix
            val expectedBase = original + "\n\n" + suffix
            val disabled = MobileBuildGradleFiles.properties(withAppendedProperties, enabled.copy(enabled = false))
            val changed = enabled.copy(gradleHeapMb = 2560, testHeapMb = 768)
            var updated = MobileBuildGradleFiles.properties(withAppendedProperties, changed)

            assertEquals(expectedBase, disabled)
            for (content in listOf(disabled, updated)) {
                val parsed = loadProperties(content)
                assertEquals("bar", parsed.getProperty("foo"))
                assertEquals("/sdk/aapt2", parsed.getProperty("android.aapt2FromMavenOverride"))
            }
            assertEquals("768m", loadProperties(updated).getProperty("aicodeMobileTestHeap"))
            repeat(3) {
                val synchronized = MobileBuildGradleFiles.properties(updated, changed)
                assertEquals(updated, synchronized)
                updated = synchronized
            }
            assertEquals(1, occurrences(updated, MobileBuildGradleFiles.BEGIN))
            assertEquals(1, occurrences(updated, MobileBuildGradleFiles.END))
            assertEquals(expectedBase, MobileBuildGradleFiles.properties(updated, changed.copy(enabled = false)))
        }
    }

    @Test
    fun malformedDuplicateAndReversedMarkersAreRejectedEvenWhenDisabling() {
        val begin = MobileBuildGradleFiles.BEGIN
        val end = MobileBuildGradleFiles.END
        val invalid = listOf(
            "\n\n$begin\nkey=value\n",
            "\n\n$end\n",
            "\n\n$begin\n$begin\n$end\n",
            "\n\n$begin\n$end\n$end\n",
            "\n\n$begin\n$end\n\n\n$begin\n$end\n",
            "\n\n$end\n$begin\n",
            "$begin\nkey=value\n$end\n",
            "key=value\n$begin\n$end\n",
            "key=value\n\n$begin\n$end",
            "key=value\r\n$begin\n$end\n"
        )

        invalid.forEach { original ->
            for (settings in listOf(enabled, enabled.copy(enabled = false))) {
                assertThrows(original, IOException::class.java) {
                    MobileBuildGradleFiles.properties(original, settings)
                }
            }
        }
    }

    @Test
    fun generatedInitScriptConfiguresTestHeapAndOneForkForEveryProject() {
        val rootfs = temporary.newFolder("rootfs")
        val changes = MobileBuildGradleFiles.changes(rootfs, enabled.copy(testHeapMb = 768))
        val script = changes.single { it.file == initFile(rootfs) }
        val properties = changes.single { it.file == propertiesFile(rootfs) }

        assertEquals(null, script.before)
        assertEquals(null, properties.before)
        assertTrue(script.after.contains("import org.gradle.api.tasks.testing.Test"))
        assertTrue(script.after.contains("gradle.projectsEvaluated"))
        assertTrue(script.after.contains("root.findProperty('aicodeMobileBuild')?.toString() == 'true'"))
        assertTrue(script.after.contains("root.findProperty('aicodeMobileTestHeap')"))
        assertTrue(Regex("root\\.allprojects\\s*\\{").containsMatchIn(script.after))
        assertTrue(Regex("tasks\\.withType\\(Test\\)\\.configureEach\\s*\\{").containsMatchIn(script.after))
        assertTrue(Regex("maxHeapSize\\s*=\\s*heap").containsMatchIn(script.after))
        assertTrue(Regex("maxParallelForks\\s*=\\s*1\\b").containsMatchIn(script.after))
        assertEquals("768m", loadProperties(properties.after).getProperty("aicodeMobileTestHeap"))
        assertTrue(fileSnapshot(rootfs).isEmpty())

        changes.forEach { it.write(it.after) }

        assertEquals(script.after, initFile(rootfs).readText())
        assertEquals(properties.after, propertiesFile(rootfs).readText())
        assertTrue(MobileBuildGradleFiles.changes(rootfs, enabled.copy(testHeapMb = 768)).isEmpty())
    }

    @Test
    fun disabledConfigurationDoesNotGenerateAnyFilesOrDirectories() {
        val rootfs = temporary.newFolder("rootfs")

        assertTrue(MobileBuildGradleFiles.changes(rootfs, MobileBuildSettings()).isEmpty())

        assertFalse(File(rootfs, "root").exists())
        assertTrue(fileSnapshot(rootfs).isEmpty())
    }

    @Test
    fun repeatedFileSynchronizationIsANoOpAndDisablingRestoresExistingProperties() {
        val rootfs = temporary.newFolder("rootfs")
        val original = "android.aapt2FromMavenOverride=/aapt2\r\norg.gradle.jvmargs=-Xmx4g"
        writeFile(propertiesFile(rootfs), original)
        MobileBuildGradleFiles.changes(rootfs, enabled).forEach { it.write(it.after) }
        val synchronized = fileSnapshot(rootfs)

        assertTrue(MobileBuildGradleFiles.changes(rootfs, enabled).isEmpty())
        assertEquals(synchronized, fileSnapshot(rootfs))
        val disabling = MobileBuildGradleFiles.changes(rootfs, enabled.copy(enabled = false))
        assertEquals(1, disabling.size)
        assertEquals(propertiesFile(rootfs), disabling.single().file)
        disabling.forEach { it.write(it.after) }

        assertEquals(original, propertiesFile(rootfs).readText())
        assertTrue(MobileBuildGradleFiles.changes(rootfs, enabled.copy(enabled = false)).isEmpty())
    }

    @Test
    fun aConflictingInitScriptIsRejectedWithoutChangingExistingFiles() {
        val rootfs = temporary.newFolder("rootfs")
        writeFile(propertiesFile(rootfs), "org.gradle.jvmargs=-Xmx6g\n")
        writeFile(initFile(rootfs), "// user-owned init script\n")
        val before = fileSnapshot(rootfs)

        assertThrows(IOException::class.java) { MobileBuildGradleFiles.changes(rootfs, enabled) }

        assertEquals(before, fileSnapshot(rootfs))
    }

    @Test
    fun directoriesAtManagedFilePathsAreRejectedWithoutChangingTheirContents() {
        for (relative in listOf("root/.gradle/gradle.properties", "root/.gradle/init.d/${MobileBuildGradleFiles.INIT_SCRIPT_NAME}")) {
            val rootfs = temporary.newFolder()
            val directory = File(rootfs, relative)
            assertTrue(directory.mkdirs())
            writeFile(File(directory, "keep.txt"), "keep")
            val before = fileSnapshot(rootfs)

            assertThrows(relative, IOException::class.java) { MobileBuildGradleFiles.changes(rootfs, enabled) }

            assertEquals(before, fileSnapshot(rootfs))
        }
    }

    @Test
    fun symlinksAtAnyManagedPathComponentAreRejectedWithoutTouchingTheirTargets() {
        val paths = listOf(
            "root",
            "root/.gradle",
            "root/.gradle/gradle.properties",
            "root/.gradle/init.d",
            "root/.gradle/init.d/${MobileBuildGradleFiles.INIT_SCRIPT_NAME}"
        )
        paths.forEach { relative ->
            val rootfs = temporary.newFolder()
            val outside = temporary.newFolder()
            val target = if (relative.endsWith("gradle.properties") || relative.endsWith(MobileBuildGradleFiles.INIT_SCRIPT_NAME)) {
                File(outside, "target").also { writeFile(it, "keep") }
            } else {
                writeFile(File(outside, "keep.txt"), "keep")
                outside
            }
            val link = File(rootfs, relative)
            link.parentFile!!.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())
            val before = fileSnapshot(outside)

            assertThrows(relative, IOException::class.java) { MobileBuildGradleFiles.changes(rootfs, enabled) }

            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertEquals(before, fileSnapshot(outside))
            assertEquals(listOf("keep"), before.values.map { String(it.toByteArray(), Charsets.UTF_8) })
        }
    }

    @Test
    fun aSymlinkUsedAsTheRootfsIsRejected() {
        val outside = temporary.newFolder("outside-rootfs")
        writeFile(propertiesFile(outside), "keep=value\n")
        val rootfs = File(temporary.root, "linked-rootfs")
        Files.createSymbolicLink(rootfs.toPath(), outside.toPath())
        val before = fileSnapshot(outside)

        assertThrows(IOException::class.java) { MobileBuildGradleFiles.changes(rootfs, enabled) }

        assertEquals(before, fileSnapshot(outside))
    }

    @Test
    fun prepareOnlyTouchesAnInstalledLocalProotProfileAndIsIdempotent() {
        val profile = localProfile("local")
        val rootfs = temporary.newFolder("rootfs")
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs), enabled)
        val original = "org.gradle.jvmargs=-Xmx5g\n"
        writeFile(propertiesFile(rootfs), original)

        fixture.configurator.prepare(profile)
        val first = fileSnapshot(rootfs)
        fixture.configurator.prepare(profile)

        assertEquals(first, fileSnapshot(rootfs))
        assertEquals(1, occurrences(propertiesFile(rootfs).readText(), MobileBuildGradleFiles.BEGIN))
        assertEquals("true", loadProperties(propertiesFile(rootfs).readText()).getProperty("aicodeMobileBuild"))
        assertTrue(MobileBuildGradleFiles.changes(rootfs, enabled).isEmpty())
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun prepareSkipsSshModeAndRemoteSshSourceIndependentlyWithoutConsultingRootfs() {
        val profiles = listOf(
            localProfile("ssh-mode").copy(mode = ExecutionMode.REMOTE_SSH),
            localProfile("remote-source").copy(rootfsSource = RootfsSource.RemoteSsh("connection", "/workspace")),
            localProfile("remote-both").copy(
                mode = ExecutionMode.REMOTE_SSH,
                rootfsSource = RootfsSource.RemoteSsh("connection", "/workspace")
            )
        )
        val fixture = Fixture(profiles, emptyMap(), enabled)

        profiles.forEach { fixture.configurator.prepare(it) }

        verify(exactly = 0) { fixture.installer.isInstalledFor(any()) }
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun prepareSkipsUninstalledLocalProfilesWithoutResolvingTheirRootfs() {
        val profile = localProfile("not-installed")
        val fixture = Fixture(listOf(profile), emptyMap(), enabled)

        fixture.configurator.prepare(profile)

        verify(exactly = 1) { fixture.installer.isInstalledFor(profile) }
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun prepareWithDefaultsDoesNotCreateGradleFiles() {
        val profile = localProfile("local")
        val rootfs = temporary.newFolder("rootfs")
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs))

        fixture.configurator.prepare(profile)

        assertTrue(fileSnapshot(rootfs).isEmpty())
        assertFalse(File(rootfs, "root").exists())
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun updateSynchronizesAllInstalledLocalProfilesIncludingBuiltinAndDeduplicatesIds() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val missing = localProfile("missing")
        val remoteMode = localProfile("remote-mode").copy(mode = ExecutionMode.REMOTE_SSH)
        val remoteSource = localProfile("remote-source").copy(rootfsSource = RootfsSource.RemoteSsh("connection", "/workspace"))
        val roots = listOf(first, second, ContainerProfile.BUILTIN_ALPINE).associate { it.id to temporary.newFolder() }
        roots.values.forEach { writeFile(propertiesFile(it), "user=keep\n") }
        val fixture = Fixture(listOf(first, second, first, missing, remoteMode, remoteSource), roots)
        val selected = enabled.copy(gradleHeapMb = 2560, daemonEnabled = true, parallelEnabled = true, maxWorkers = 3, testHeapMb = 768)
        every { fixture.settingsRepository.save(any()) } answers {
            roots.values.forEach { root ->
                assertEquals("768m", loadProperties(propertiesFile(root).readText()).getProperty("aicodeMobileTestHeap"))
                assertTrue(initFile(root).isFile)
            }
            fixture.state.value = firstArg()
        }

        fixture.configurator.update { selected }

        assertEquals(selected, fixture.state.value)
        roots.values.forEach { root ->
            val content = propertiesFile(root).readText()
            assertTrue(content.startsWith("user=keep\n"))
            assertEquals(1, occurrences(content, MobileBuildGradleFiles.BEGIN))
            assertTrue(loadProperties(content).getProperty("org.gradle.jvmargs").startsWith("-Xmx2560m "))
            assertEquals("3", loadProperties(content).getProperty("org.gradle.workers.max"))
        }
        verify(exactly = 1) { fixture.settingsRepository.save(selected) }
        verify(exactly = 1) { fixture.installer.isInstalledFor(first) }
        verify(exactly = 1) { fixture.installer.rootfsDirFor(ContainerProfile.BUILTIN_ALPINE) }
        verify(exactly = 0) { fixture.installer.isInstalledFor(remoteMode) }
        verify(exactly = 0) { fixture.installer.isInstalledFor(remoteSource) }
        verify(exactly = 0) { fixture.installer.rootfsDirFor(missing) }
    }

    @Test
    fun updateWithAnEmptyProfileListStillSynchronizesInstalledBuiltin() = runTest {
        val rootfs = temporary.newFolder("builtin-rootfs")
        val fixture = Fixture(emptyList(), mapOf(ContainerProfile.BUILTIN_ID to rootfs))

        fixture.configurator.update { it.copy(enabled = true) }

        assertTrue(initFile(rootfs).isFile)
        assertEquals("true", loadProperties(propertiesFile(rootfs).readText()).getProperty("aicodeMobileBuild"))
        verify(exactly = 1) { fixture.settingsRepository.save(enabled) }
    }

    @Test
    fun disablingThroughUpdateRestoresEveryOriginalFileAndRetainsSelectedParameters() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val roots = listOf(first, second).associate { it.id to temporary.newFolder() }
        val originals = roots.mapValues { (id, _) -> "# $id\r\norg.gradle.jvmargs=-Xmx6g" }
        roots.forEach { (id, root) -> writeFile(propertiesFile(root), originals.getValue(id)) }
        val fixture = Fixture(listOf(first, second), roots)
        val selected = enabled.copy(gradleHeapMb = 2560, daemonEnabled = true, parallelEnabled = true, maxWorkers = 4, testHeapMb = 2048)
        fixture.configurator.update { selected }

        fixture.configurator.update { it.copy(enabled = false) }

        assertEquals(selected.copy(enabled = false), fixture.state.value)
        roots.forEach { (id, root) -> assertEquals(originals.getValue(id), propertiesFile(root).readText()) }
        val before = roots.mapValues { fileSnapshot(it.value) }
        roots.forEach { (id, _) -> fixture.configurator.prepare(if (id == first.id) first else second) }
        assertEquals(before, roots.mapValues { fileSnapshot(it.value) })
    }

    @Test
    fun invalidUpdateDoesNotSaveOrTouchInstalledFiles() = runTest {
        val profile = localProfile("local")
        val rootfs = temporary.newFolder("rootfs")
        writeFile(propertiesFile(rootfs), "keep=value\n")
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs))
        val before = fileSnapshot(rootfs)
        val invalid = listOf(
            enabled.copy(gradleHeapMb = 2500),
            enabled.copy(gradleHeapMb = 0),
            enabled.copy(maxWorkers = 0),
            enabled.copy(maxWorkers = 5),
            enabled.copy(testHeapMb = -1),
            enabled.copy(testHeapMb = 513)
        )

        invalid.forEach { settings ->
            expectFailure<IllegalArgumentException> { fixture.configurator.update { settings } }
        }

        assertEquals(MobileBuildSettings(), fixture.state.value)
        assertEquals(before, fileSnapshot(rootfs))
        verify(exactly = 0) { fixture.installer.isInstalledFor(any()) }
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun preflightFailureInALaterProfileDoesNotModifyEarlierProfilesOrSaveSettings() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val roots = listOf(first, second).associate { it.id to temporary.newFolder() }
        roots.values.forEach { writeFile(propertiesFile(it), "keep=value\n") }
        writeFile(initFile(roots.getValue(second.id)), "// user-owned\n")
        val before = roots.mapValues { fileSnapshot(it.value) }
        val fixture = Fixture(listOf(first, second), roots)

        expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertEquals(before, roots.mapValues { fileSnapshot(it.value) })
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun aWriteFailureInALaterProfileRollsBackEarlierFilesWithoutSavingSettings() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val roots = listOf(first, second).associate { it.id to temporary.newFolder() }
        roots.values.forEach { writeFile(propertiesFile(it), "org.gradle.jvmargs=-Xmx6g\r\nuser=keep") }
        writeFile(File(roots.getValue(second.id), "root/.gradle/init.d"), "not a directory")
        val before = roots.mapValues { fileSnapshot(it.value) }
        val fixture = Fixture(listOf(first, second), roots)

        val failure = expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertTrue(failure.suppressed.isEmpty())
        assertEquals(before, roots.mapValues { fileSnapshot(it.value) })
        assertFalse(initFile(roots.getValue(first.id)).exists())
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun persistenceFailureRollsBackExistingBytesAndRemovesNewlyCreatedFiles() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val roots = listOf(first, second).associate { it.id to temporary.newFolder() }
        writeFile(propertiesFile(roots.getValue(first.id)), "org.gradle.jvmargs=-Xmx6g\r\nuser=keep")
        writeFile(initFile(roots.getValue(first.id)), MobileBuildGradleFiles.INIT_SCRIPT)
        val before = roots.mapValues { fileSnapshot(it.value) }
        val fixture = Fixture(listOf(first, second), roots)
        val failure = IOException("simulated persistence failure")
        every { fixture.settingsRepository.save(any()) } throws failure

        val thrown = expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertSame(failure, generateSequence<Throwable>(thrown) { it.cause }.firstOrNull { it === failure })
        assertTrue(failure.suppressed.isEmpty())
        assertEquals(before, roots.mapValues { fileSnapshot(it.value) })
        assertFalse(initFile(roots.getValue(second.id)).exists())
        assertFalse(propertiesFile(roots.getValue(second.id)).exists())
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 1) { fixture.settingsRepository.save(enabled) }
    }

    @Test
    fun externalChangeBetweenPlanningAndWritingIsPreservedAndPreventsPersistence() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val firstRoot = temporary.newFolder("first-rootfs")
        val secondRoot = temporary.newFolder("second-rootfs")
        writeFile(propertiesFile(firstRoot), "original=value\n")
        val fixture = Fixture(listOf(first, second), mapOf(first.id to firstRoot, second.id to secondRoot))
        every { fixture.installer.rootfsDirFor(second) } answers {
            propertiesFile(firstRoot).writeText("external=value\n")
            secondRoot
        }

        expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertEquals("external=value\n", propertiesFile(firstRoot).readText())
        assertFalse(initFile(firstRoot).exists())
        assertTrue(fileSnapshot(secondRoot).isEmpty())
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun replacingGradleDirectoryWithASymlinkAfterPlanningDoesNotTouchTheExternalTargetOrSave() = runTest {
        val first = localProfile("first")
        val second = localProfile("second")
        val firstRoot = temporary.newFolder("first-rootfs")
        val secondRoot = temporary.newFolder("second-rootfs")
        val external = temporary.newFolder("external")
        val moved = File(temporary.root, "original-gradle")
        writeFile(propertiesFile(firstRoot), "original=value\n")
        val gradleDirectory = propertiesFile(firstRoot).parentFile!!
        val before = fileSnapshot(gradleDirectory)
        val fixture = Fixture(listOf(first, second), mapOf(first.id to firstRoot, second.id to secondRoot))
        every { fixture.installer.rootfsDirFor(second) } answers {
            Files.move(gradleDirectory.toPath(), moved.toPath())
            Files.createSymbolicLink(gradleDirectory.toPath(), external.toPath())
            secondRoot
        }

        expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertTrue(Files.isSymbolicLink(gradleDirectory.toPath()))
        assertTrue(external.listFiles().orEmpty().isEmpty())
        assertEquals(before, fileSnapshot(moved))
        assertTrue(fileSnapshot(secondRoot).isEmpty())
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun rollbackRejectsAReplacedDirectoryEvenIfTheExternalFileMatchesTheAppliedContent() = runTest {
        val profile = localProfile("local")
        val rootfs = temporary.newFolder("rootfs")
        val external = temporary.newFolder("external")
        val moved = File(temporary.root, "applied-gradle")
        val original = "original=value\n"
        writeFile(propertiesFile(rootfs), original)
        writeFile(initFile(rootfs), MobileBuildGradleFiles.INIT_SCRIPT)
        writeFile(File(external, "gradle.properties"), MobileBuildGradleFiles.properties(original, enabled))
        val externalBefore = fileSnapshot(external)
        val gradleDirectory = propertiesFile(rootfs).parentFile!!
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs))
        val failure = IOException("simulated persistence failure")
        var appliedSnapshot: Map<String, List<Byte>>? = null
        every { fixture.settingsRepository.save(any()) } answers {
            assertArrayEquals(File(external, "gradle.properties").readBytes(), propertiesFile(rootfs).readBytes())
            appliedSnapshot = fileSnapshot(gradleDirectory)
            Files.move(gradleDirectory.toPath(), moved.toPath())
            Files.createSymbolicLink(gradleDirectory.toPath(), external.toPath())
            throw failure
        }

        val thrown = expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertSame(failure, generateSequence<Throwable>(thrown) { it.cause }.firstOrNull { it === failure })
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is IOException)
        assertTrue(Files.isSymbolicLink(gradleDirectory.toPath()))
        assertEquals(externalBefore, fileSnapshot(external))
        assertEquals(appliedSnapshot, fileSnapshot(moved))
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 1) { fixture.settingsRepository.save(enabled) }
    }

    @Test
    fun rollbackDoesNotOverwriteAnExternalEditAfterFilesWereApplied() = runTest {
        val profile = localProfile("local")
        val rootfs = temporary.newFolder("rootfs")
        writeFile(propertiesFile(rootfs), "original=value\n")
        writeFile(initFile(rootfs), MobileBuildGradleFiles.INIT_SCRIPT)
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs))
        val failure = IOException("simulated persistence failure")
        every { fixture.settingsRepository.save(any()) } answers {
            propertiesFile(rootfs).writeText("external=value\n")
            throw failure
        }

        val thrown = expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertSame(failure, generateSequence<Throwable>(thrown) { it.cause }.firstOrNull { it === failure })
        assertEquals("external=value\n", propertiesFile(rootfs).readText())
        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is IOException)
        assertEquals(MobileBuildSettings(), fixture.state.value)
    }

    @Test
    fun redirectedHomesAreSkippedWithoutResolvingRootfs() {
        val profiles = listOf(
            localProfile("home").copy(env = mapOf("HOME" to "/home/user")),
            localProfile("gradle-home").copy(env = mapOf("GRADLE_USER_HOME" to "/work/.gradle"))
        )
        val roots = profiles.associate { it.id to temporary.newFolder() }
        val fixture = Fixture(profiles, roots, enabled)

        profiles.forEach { fixture.configurator.prepare(it) }

        assertTrue(roots.values.all { fileSnapshot(it).isEmpty() })
        verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
    }

    @Test
    fun bindingsRedirectingRootOrGradleHomeAreRejectedWithoutChangingFiles() {
        val bindings = listOf(
            "/host:/root",
            "/host:/root/",
            "/host:/root/.gradle",
            "/host:/root/.gradle/init.d!",
            "/host:/root/work/../.gradle",
            "/host:/",
            "/host:/root/../root/.gradle"
        )
        bindings.forEach { binding ->
            val profile = localProfile("bound").copy(extraBindings = listOf(binding))
            val rootfs = temporary.newFolder()
            val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs), enabled)

            assertThrows(binding, IOException::class.java) { fixture.configurator.prepare(profile) }

            assertTrue(fileSnapshot(rootfs).isEmpty())
            verify(exactly = 0) { fixture.settingsRepository.save(any()) }
        }
    }

    @Test
    fun aBindingThroughASymlinkAliasIsRejectedWithoutSavingOrChangingTheTarget() = runTest {
        val profile = localProfile("bound").copy(extraBindings = listOf("/host:/gradle-alias"))
        val rootfs = temporary.newFolder("rootfs")
        writeFile(propertiesFile(rootfs), "keep=value\n")
        Files.createSymbolicLink(File(rootfs, "gradle-alias").toPath(), propertiesFile(rootfs).parentFile!!.toPath())
        val before = fileSnapshot(propertiesFile(rootfs).parentFile!!)
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs))

        expectFailure<IOException> { fixture.configurator.update { enabled } }

        assertEquals(before, fileSnapshot(propertiesFile(rootfs).parentFile!!))
        assertTrue(Files.isSymbolicLink(File(rootfs, "gradle-alias").toPath()))
        assertFalse(initFile(rootfs).exists())
        assertEquals(MobileBuildSettings(), fixture.state.value)
        verify(exactly = 0) { fixture.settingsRepository.save(any()) }
    }

    @Test
    fun bindingTargetsCrossingAnotherExtraBindingOrBuiltinMountAreRejectedWithoutSaving() = runTest {
        val overlapping = listOf("/host-one:/alias", "/host-two:/alias/root-link/.gradle")
        val bindings = buildList {
            add(overlapping)
            add(overlapping.reversed())
            listOf("/root/.aicode", "/root/workspace", "/dev", "/proc", "/sys", "/system").forEach { prefix ->
                add(listOf("/host:$prefix/root-link/.gradle"))
            }
        }
        bindings.forEach { extraBindings ->
            val profile = localProfile("bound").copy(extraBindings = extraBindings)
            val rootfs = temporary.newFolder()
            writeFile(propertiesFile(rootfs), "original=value\n")
            val before = fileSnapshot(rootfs)
            val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs))

            expectFailure<IOException> { fixture.configurator.update { enabled } }

            assertEquals(before, fileSnapshot(rootfs))
            assertFalse(initFile(rootfs).exists())
            assertEquals(MobileBuildSettings(), fixture.state.value)
            verify(exactly = 0) { fixture.settingsRepository.save(any()) }
        }
    }

    @Test
    fun nonAbsoluteBindingTargetsAreRejectedWithoutCreatingFiles() {
        listOf("/host:relative", "relative-only", "/host:").forEach { binding ->
            val profile = localProfile("bound").copy(extraBindings = listOf(binding))
            val rootfs = temporary.newFolder()
            val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs), enabled)

            assertThrows(binding, IOException::class.java) { fixture.configurator.prepare(profile) }

            assertTrue(fileSnapshot(rootfs).isEmpty())
            verify(exactly = 0) { fixture.settingsRepository.save(any()) }
        }
    }

    @Test
    fun extraArgsThatCanBindOrResetRootfsAreRejectedBeforeAnyFileAccess() {
        val arguments = listOf(
            listOf("-b", "/host:/root"),
            listOf("-b/host:/root"),
            listOf("--bind=/host:/root"),
            listOf("--bind", "/host:/root/.gradle"),
            listOf("-r", "/other-root"),
            listOf("-r/other-root"),
            listOf("--rootfs=/other-root"),
            listOf("--rootfs", "/other-root"),
            listOf("-R", "/other-root"),
            listOf("-S", "/other-root")
        )
        arguments.forEach { args ->
            val profile = localProfile("extra-args").copy(extraArgs = args)
            val rootfs = temporary.newFolder()
            val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs), enabled)

            assertThrows(args.toString(), IOException::class.java) { fixture.configurator.prepare(profile) }

            assertTrue(fileSnapshot(rootfs).isEmpty())
            verify(exactly = 0) { fixture.installer.rootfsDirFor(any()) }
        }
    }

    @Test
    fun harmlessBindingsDefaultArgsAndExplicitDefaultHomesStillAllowConfiguration() {
        val profile = localProfile("local").copy(
            extraBindings = listOf("/sdcard:/mnt/sdcard", "/host:/root/project"),
            extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS + "--root-id",
            env = mapOf("HOME" to "/root", "GRADLE_USER_HOME" to "/root/.gradle")
        )
        val rootfs = temporary.newFolder("rootfs")
        val fixture = Fixture(listOf(profile), mapOf(profile.id to rootfs), enabled)

        fixture.configurator.prepare(profile)

        assertTrue(initFile(rootfs).isFile)
        assertEquals("true", loadProperties(propertiesFile(rootfs).readText()).getProperty("aicodeMobileBuild"))
    }

    private class Fixture(
        profiles: List<ContainerProfile>,
        roots: Map<String, File>,
        initial: MobileBuildSettings = MobileBuildSettings()
    ) {
        val state = MutableStateFlow(initial)
        val settingsRepository = mockk<MobileBuildSettingsRepository>()
        private val containerSettingsRepository = mockk<ContainerSettingsRepository>()
        val installer = mockk<ContainerInstaller>()
        val configurator: MobileBuildConfigurator

        init {
            every { settingsRepository.settings } returns state
            every { settingsRepository.save(any()) } answers { state.value = firstArg() }
            every { containerSettingsRepository.customProfilesFlow } returns flowOf(profiles)
            every { installer.isInstalledFor(any()) } answers { firstArg<ContainerProfile>().id in roots }
            every { installer.rootfsDirFor(any()) } answers { roots.getValue(firstArg<ContainerProfile>().id) }
            configurator = MobileBuildConfigurator(settingsRepository, containerSettingsRepository, installer)
        }
    }

    private fun localProfile(id: String) = ContainerProfile(
        id = id,
        name = id,
        rootfsSource = RootfsSource.LocalFile("content://test/$id"),
        shellPath = null,
        isBuiltin = false
    )

    private fun propertiesFile(rootfs: File) = File(rootfs, "root/.gradle/gradle.properties")

    private fun initFile(rootfs: File) = File(rootfs, "root/.gradle/init.d/${MobileBuildGradleFiles.INIT_SCRIPT_NAME}")

    private fun writeFile(file: File, content: String) {
        file.parentFile!!.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun loadProperties(content: String) = Properties().apply { load(StringReader(content)) }

    private fun occurrences(content: String, needle: String) = Regex(Regex.escape(needle)).findAll(content).count()

    private fun fileSnapshot(root: File): Map<String, List<Byte>> = root.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw AssertionError("Expected ${T::class.java.name}, got ${failure.javaClass.name}", failure)
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
