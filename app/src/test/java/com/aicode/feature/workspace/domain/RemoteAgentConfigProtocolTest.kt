package com.aicode.feature.workspace.domain

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteAgentConfigProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun encoded(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private fun encoded(value: String) = encoded(value.toByteArray())
    private fun manifest(path: String, body: ByteArray) = "AICODE_CONFIG_1 M\nF\t${encoded(path)}\t${RemoteAgentConfigProtocol.sha256(body)}\t${body.size}\nEND\n".toByteArray()
    private fun bodies(path: String, body: ByteArray) = "AICODE_CONFIG_1 B\nB\t${encoded(path)}\t${encoded(body)}\nEND\n".toByteArray()
    private fun execute(command: String, input: ByteArray = byteArrayOf()): Pair<Int, ByteArray> {
        val process = ProcessBuilder("sh", "-c", command).start()
        try {
            process.outputStream.use { it.write(input) }
            val output = process.inputStream.use { RemoteAgentConfigProtocol.readLimited(it, 1024 * 1024) {} }
            process.errorStream.use { RemoteAgentConfigProtocol.readLimited(it, 65536) {} }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            return process.exitValue() to output
        } finally { process.destroyForcibly() }
    }

    @Test fun realShellRoundTripQuotesUnicodeNewlinesAndShellMetacharacters() {
        val root = temporary.newFolder("root ' \$() ; space\n目录").canonicalFile
        val dir = File(root, "nested ' \$(printf hacked)\nname").apply { mkdirs() }
        val text = "---\nname: quoted\n---\n完整指令\n\$ARGUMENTS"
        File(dir, "SKILL.md").writeText(text)
        val (exit, bytes) = execute(RemoteAgentConfigProtocol.manifestCommand(root.path, AgentConfigKind.SKILLS))
        assertEquals(0, exit)
        val entries = RemoteAgentConfigProtocol.manifest(bytes, AgentConfigKind.SKILLS)
        assertEquals(listOf("${dir.name}/SKILL.md"), entries.keys.toList())
        val (bodyExit, bodyBytes) = execute(RemoteAgentConfigProtocol.bodiesCommand(root.path), RemoteAgentConfigProtocol.request(entries.keys))
        assertEquals(0, bodyExit)
        assertEquals(text, RemoteAgentConfigProtocol.bodies(bodyBytes, entries).values.single())
        assertFalse(File(root, "hacked").exists())
    }

    @Test fun realManifestPreservesSkillPriorityAndAgentTopLevelOnly() {
        val root = temporary.newFolder().canonicalFile
        File(root, "one").mkdirs()
        File(root, "one/CLAUDE.md").writeText("legacy")
        File(root, "one/skill.MD").writeText("preferred")
        File(root, "a.md").writeText("agent")
        File(root, "one/nested.md").writeText("not top-level")
        val skills = execute(RemoteAgentConfigProtocol.manifestCommand(root.path, AgentConfigKind.SKILLS))
        assertEquals(listOf("one/skill.MD"), RemoteAgentConfigProtocol.manifest(skills.second, AgentConfigKind.SKILLS).keys.toList())
        val agents = execute(RemoteAgentConfigProtocol.manifestCommand(root.path, AgentConfigKind.AGENTS))
        assertEquals(listOf("a.md"), RemoteAgentConfigProtocol.manifest(agents.second, AgentConfigKind.AGENTS).keys.toList())
    }

    @Test fun realShellMarksFileDirectorySymlinksAndRootAliasesAsUncacheable() {
        val root = temporary.newFolder().canonicalFile
        val outside = temporary.newFolder().canonicalFile
        File(outside, "SKILL.md").writeText("private")
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "SKILL.md").toPath(), File(outside, "SKILL.md").toPath())
        val manifest = execute(RemoteAgentConfigProtocol.manifestCommand(root.path, AgentConfigKind.SKILLS))
        assertEquals(31, manifest.first)
        val body = execute(RemoteAgentConfigProtocol.bodiesCommand(root.path), RemoteAgentConfigProtocol.request(listOf("linked/SKILL.md")))
        assertEquals(31, body.first)
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        assertEquals(31, execute(RemoteAgentConfigProtocol.manifestCommand(alias.path, AgentConfigKind.SKILLS)).first)
    }

    @Test fun bodyChangedAfterManifestIsNotAccepted() {
        val before = "before".toByteArray()
        val expected = RemoteAgentConfigProtocol.manifest(manifest("a.md", before), AgentConfigKind.AGENTS)
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.bodies(bodies("a.md", "after!".toByteArray()), expected) }
    }

    @Test fun binaryAndMalformedUtf8BodiesAreRejected() {
        for (body in listOf(byteArrayOf(0, 1, 2), byteArrayOf(0xc3.toByte(), 0x28))) {
            val expected = RemoteAgentConfigProtocol.manifest(manifest("a.md", body), AgentConfigKind.AGENTS)
            assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.bodies(bodies("a.md", body), expected) }
        }
    }

    @Test fun badManifestDuplicateAndUnexpectedPathsAreRejected() {
        val valid = manifest("a.md", "body".toByteArray()).toString(Charsets.UTF_8)
        val line = valid.lines()[1]
        for (text in listOf(valid.replace("END\n", "$line\nEND\n"), valid.replace("AICODE_CONFIG_1", "other"),
            valid.replace(Regex("[0-9a-f]{64}"), "broken"), valid.removeSuffix("END\n"))) {
            assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(text.toByteArray(), AgentConfigKind.AGENTS) }
        }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(manifest("nested/a.md", byteArrayOf()), AgentConfigKind.AGENTS) }
    }

    @Test fun requestRejectsTraversalNulAndOversizeButAllowsQuotedNames() {
        for (path in listOf("../a", "/a", "a/../b", "a//b", "a/./b", "a\u0000b", "x".repeat(RemoteAgentConfigProtocol.MAX_PATH_BYTES + 1))) {
            assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.request(listOf(path)) }
        }
        assertTrue(RemoteAgentConfigProtocol.request(listOf("a ' \$()\n/SKILL.md")).isNotEmpty())
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifestCommand("relative", AgentConfigKind.SKILLS) }
    }

    @Test fun byteFileAndBatchLimitsRejectBeforePublishing() {
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.readLimited(ByteArray(11).inputStream(), 10) {} }
        val oversized = manifest("a.md", byteArrayOf()).toString(Charsets.UTF_8).replace("\t0\n", "\t${RemoteAgentConfigProtocol.MAX_FILE_BYTES + 1}\n")
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.manifest(oversized.toByteArray(), AgentConfigKind.AGENTS) }
        val expected = RemoteAgentConfigProtocol.manifest(manifest("a.md", "body".toByteArray()), AgentConfigKind.AGENTS)
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.bodies("AICODE_CONFIG_1 B\nEND\n".toByteArray(), expected) }
        assertThrows(IOException::class.java) { RemoteAgentConfigProtocol.request(List(RemoteAgentConfigProtocol.MAX_FILES + 1) { "$it.md" }) }
    }
}
