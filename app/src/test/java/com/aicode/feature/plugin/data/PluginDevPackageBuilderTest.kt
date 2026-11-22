package com.aicode.feature.plugin.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginDevPackageBuilderTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun developmentDirectoryUsesTheRealPackageLayoutAndSkipsBuildDependencies() = runBlocking {
        val source = temporary.newFolder("source")
        File(source, "plugin.json").writeText("{}")
        File(source, "pages").mkdir()
        File(source, "pages/index.html").writeText("<html>test</html>")
        File(source, "node_modules").mkdir()
        File(source, "node_modules/ignored.js").writeText("ignored")
        val target = temporary.newFile("package.zip")
        PluginDevPackageBuilder.zip(source, target)
        ZipFile(target).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("pages/index.html", "plugin.json"), names)
            assertFalse(names.any { it.contains("node_modules") })
            assertEquals("<html>test</html>", zip.getInputStream(zip.getEntry("pages/index.html")).bufferedReader().readText())
        }
    }
    @Test fun developmentSymlinksCannotLeakFilesOutsideTheDirectory() = runBlocking {
        val source = temporary.newFolder("links")
        val outside = temporary.newFile("outside.txt").apply { writeText("private") }
        Files.createSymbolicLink(File(source, "leak.txt").toPath(), outside.toPath())
        var rejected = false
        try { PluginDevPackageBuilder.zip(source, temporary.newFile("links.zip")) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }
    @Test fun targetOutsideSourceDoesNotBecomeAPackageEntry() = runBlocking {
        val source = temporary.newFolder("clean")
        File(source, "plugin.json").writeText("{}")
        val target = temporary.newFile("external.zip")
        PluginDevPackageBuilder.zip(source, target)
        ZipFile(target).use { assertEquals(listOf("plugin.json"), it.entries().asSequence().map { entry -> entry.name }.toList()) }
    }
    @Test fun rejectsNonDirectorySourceBeforeWritingAnyEntries() = runBlocking {
        val source = temporary.newFile("not-directory")
        var rejected = false
        try { PluginDevPackageBuilder.zip(source, temporary.newFile("rejected.zip")) } catch (_: IllegalStateException) { rejected = true } catch (_: IllegalArgumentException) { rejected = true } catch (_: java.io.IOException) { rejected = true }
        assertTrue(rejected)
    }
}
