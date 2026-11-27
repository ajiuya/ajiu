package com.aicode.feature.agent.domain.container.chroot

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChrootAppSourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun uid(file: File): Int = (Files.getAttribute(file.toPath(), "unix:uid", NOFOLLOW_LINKS) as Number).toInt()

    @Test
    fun firstCapturePreparesTheSameOutputDirectoryUsedByAppCleanupAndReusesIt() {
        val files = temporary.newFolder()
        val output = prepareChrootToolOutputDirectory(files, uid(files))
        assertEquals(File(files, "aicode/tool-output"), output)
        assertTrue(Files.isDirectory(output.toPath(), NOFOLLOW_LINKS))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(output.toPath(), NOFOLLOW_LINKS))
        val saved = File(output, "previous.log").apply { writeText("kept") }
        val inode = Files.getAttribute(output.toPath(), "unix:ino", NOFOLLOW_LINKS)
        assertEquals(output, prepareChrootToolOutputDirectory(files, uid(files)))
        assertEquals(inode, Files.getAttribute(output.toPath(), "unix:ino", NOFOLLOW_LINKS))
        assertEquals("kept", saved.readText())
        assertFalse(File(files, "chroot-home").exists())
    }

    @Test
    fun hostConfigAndOutputRejectSymlinksAndFilesWithoutChangingTheirTargets() {
        for (name in listOf("aicode", "aicode/tool-output")) {
            for (symbolic in listOf(false, true)) {
                val root = temporary.newFolder()
                val target = temporary.newFolder()
                val sentinel = File(target, "private").apply { writeText("preserved") }
                val entry = File(root, name)
                if (entry.parentFile != root) assertTrue(entry.parentFile.mkdir())
                if (symbolic) Files.createSymbolicLink(entry.toPath(), target.toPath()) else entry.writeText("ordinary file")
                assertThrows(IOException::class.java) { prepareChrootToolOutputDirectory(root, uid(root)) }
                assertEquals("preserved", sentinel.readText())
                assertEquals(listOf("private"), target.list()!!.toList())
                if (symbolic) assertTrue(Files.isSymbolicLink(entry.toPath())) else assertEquals("ordinary file", entry.readText())
            }
        }
    }

    @Test
    fun foreignOwnerAndSymlinkedAncestorAreRejectedBeforeCreatingDirectories() {
        val files = temporary.newFolder()
        assertThrows(IOException::class.java) { prepareChrootToolOutputDirectory(files, uid(files) + 1) }
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), files.toPath())
        assertThrows(IOException::class.java) { prepareChrootToolOutputDirectory(alias, uid(files)) }
        assertFalse(File(files, "aicode").exists())
        assertFalse(File(files, ".aicode").exists())
    }
}
