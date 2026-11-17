package com.aicode.feature.agent.domain.memory

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMemoryPathResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private val resolver = ProjectMemoryPathResolver()
    private fun local(root: File) = ProjectMemoryIdentity("local_proot", projectRoot = root.canonicalPath, profileId = "local")

    @Test fun frozenLocalRootHandlesRelativeWorkspaceAliasAndCanonicalAbsolutePaths() {
        val root = temporary.newFolder("workspace")
        val frozen = resolver.freeze(local(root))
        val expected = File(root, "src/Main.kt").canonicalPath
        assertEquals(expected, frozen("src/Main.kt"))
        assertEquals(expected, frozen("~/workspace/src/Main.kt"))
        assertEquals(expected, frozen("/root/workspace/src/Main.kt"))
        assertEquals(expected, frozen(expected))
        assertNull(frozen("/etc/passwd"))
    }

    @Test fun localSymlinkEscapeAndSiblingPrefixAreRejected() {
        val root = temporary.newFolder("workspace")
        val outside = temporary.newFolder("workspace-other")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        val frozen = resolver.freeze(local(root))
        assertNull(frozen("escape/file.txt"))
        assertNull(frozen(File(outside, "file.txt").path))
        assertNull(frozen("../workspace-other/file.txt"))
    }

    @Test fun inRootSymlinkResolvesToTheCanonicalProjectPath() {
        val root = temporary.newFolder("workspace")
        val inside = File(root, "inside").apply { mkdir() }
        Files.createSymbolicLink(File(root, "link").toPath(), inside.toPath())
        assertEquals(File(inside, "file.txt").path, resolver.resolve(local(root), "link/file.txt"))
    }

    @Test fun remotePathsUseLexicalAttributionWithoutClaimingSymlinkIsolation() {
        val remote = ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work/demo")
        val frozen = resolver.freeze(remote)
        assertEquals("/work/demo/src/Main.kt", frozen("src/./Main.kt"))
        assertEquals("/work/demo/src/Main.kt", frozen("~/workspace/src/Main.kt"))
        assertNull(frozen("/work/demo-other/file"))
        assertNull(frozen("../other/file"))
        assertNull(frozen("~other/file"))
    }

    @Test fun invalidPathsAndRootUnderflowAreRejected() {
        val root = temporary.newFolder("workspace")
        val frozen = resolver.freeze(local(root))
        listOf("", "\u0000", "file\nother", "x".repeat(4097), "src\\file").forEach { assertNull(frozen(it)) }
        val ssh = ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work")
        assertNull(resolver.freeze(ssh)("/../../work/file"))
    }
}
