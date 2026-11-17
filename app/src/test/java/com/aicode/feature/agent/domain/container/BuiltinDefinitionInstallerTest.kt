package com.aicode.feature.agent.domain.container

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class BuiltinDefinitionInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val agents = linkedMapOf("探索员.md" to agent("探索员"), "审核员.md" to agent("审核员"))
    private val skills = mapOf("dev-god" to linkedMapOf(
        "SKILL.md" to "---\nname: dev-god\n---\nInstructions".toByteArray(),
        "source/deep/source.md" to "source".toByteArray(),
        "docs/guide.md" to "guide".toByteArray(),
        "scripts/run.py" to "raise RuntimeError('must never run')".toByteArray()
    ))
    private fun agent(name: String) = "---\nname: $name\n---\nPrompt".toByteArray()
    private fun root() = File(temp.newFolder(), "aicode")
    private fun install(root: File) = LocalBuiltinDefinitionStore.install(root, agents, skills)
    private fun receipt(root: File, name: String) = File(root, "${BuiltinDefinitionPaths.RECEIPTS}/$name")
    private fun cleanStage(root: File) = assertFalse(root.walkTopDown().any { it.name.startsWith(".builtin-stage-") })

    @Test fun installsWholePackagesWithPrivateNonExecutableFilesAndNeverUpgrades() {
        val root = root()
        assertTrue(install(root).entries.values.all { it == BuiltinInstallStatus.INSTALLED })
        agents.forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "agents/$name").readBytes()) }
        skills.getValue("dev-god").forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "skills/dev-god/$name").readBytes()) }
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(File(root, "skills/dev-god/scripts/run.py").toPath()))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(File(root, "skills/dev-god").toPath()))
        val upgraded = agents.mapValues { (_, bytes) -> bytes + "\nUpgrade".toByteArray() }
        assertTrue(LocalBuiltinDefinitionStore.install(root, upgraded, skills).entries.values.all { it == BuiltinInstallStatus.ALREADY_HANDLED })
        agents.forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "agents/$name").readBytes()) }
        cleanStage(root)
    }

    @Test fun preservesExistingPathsWholeIncludingEmptySkillAndDisabledConfiguration() {
        val root = root()
        File(root, "agents").mkdirs()
        File(root, "agents/探索员.md").writeText("my custom prompt")
        File(root, "agents/explore.md").writeText("---\nname: Explore\n---\nold user definition")
        File(root, "skills/dev-god").mkdirs()
        File(root, "agents.json").writeText("{\"disabled\":[\"探索员\",\"Explore\"]}")
        File(root, "skills.json").writeText("{\"disabled\":[\"dev-god\"]}")
        val result = install(root)
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, result.entries["agents/探索员.md"])
        assertEquals(BuiltinInstallStatus.INSTALLED, result.entries["agents/审核员.md"])
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, result.entries["skills/dev-god"])
        assertEquals("my custom prompt", File(root, "agents/探索员.md").readText())
        assertEquals("---\nname: Explore\n---\nold user definition", File(root, "agents/explore.md").readText())
        assertEquals(0, File(root, "skills/dev-god").list()!!.size)
        assertEquals("{\"disabled\":[\"探索员\",\"Explore\"]}", File(root, "agents.json").readText())
        assertEquals("{\"disabled\":[\"dev-god\"]}", File(root, "skills.json").readText())
    }

    @Test fun preservesLogicalNameInDifferentFilenameAndNestedSkillDirectory() {
        val root = root()
        File(root, "agents").mkdirs()
        File(root, "agents/old-filename.md").writeBytes(agent("审核员"))
        val customSkill = File(root, "skills/collection/custom-folder/SKILL.md")
        customSkill.parentFile!!.mkdirs()
        customSkill.writeText("---\nname: DEV-GOD\n---\nuser instructions")
        val result = install(root)
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, result.entries["agents/审核员.md"])
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, result.entries["skills/dev-god"])
        assertFalse(File(root, "agents/审核员.md").exists())
        assertFalse(File(root, "skills/dev-god").exists())
        assertTrue(customSkill.readText().contains("user instructions"))
        File(root, "agents/old-filename.md").delete()
        customSkill.delete()
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, install(root).entries["agents/审核员.md"])
        assertFalse(File(root, "agents/审核员.md").exists())
    }

    @Test fun successfulOfferDeletionStaysDeletedAndOtherScopesAndServersRemainIndependent() {
        val first = root()
        install(first)
        File(first, "agents/探索员.md").delete()
        val skill = File(first, "skills/dev-god")
        skill.walkBottomUp().forEach { assertTrue(it.delete()) }
        val repeat = LocalBuiltinDefinitionStore.install(first, agents + ("新名字.md" to agent("新名字")), skills)
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, repeat.entries["agents/探索员.md"])
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, repeat.entries["skills/dev-god"])
        assertFalse(skill.exists())
        assertFalse(File(first, "agents/探索员.md").exists())
        assertTrue(File(first, "agents/新名字.md").isFile)
        val second = root()
        assertTrue(install(second).entries.values.all { it == BuiltinInstallStatus.INSTALLED })
        val project = temp.newFolder()
        File(project, ".aicode/agents").mkdirs()
        File(project, ".aicode/agents/探索员.md").writeText("project wins")
        install(second)
        assertEquals("project wins", File(project, ".aicode/agents/探索员.md").readText())
    }

    @Test fun changedUserPayloadIsNotRestoredAfterRestart() {
        val root = root()
        install(root)
        File(root, "agents/探索员.md").writeText("changed")
        File(root, "skills/dev-god/SKILL.md").delete()
        File(root, "skills/dev-god/user.txt").writeText("mine")
        install(root)
        assertEquals("changed", File(root, "agents/探索员.md").readText())
        assertFalse(File(root, "skills/dev-god/SKILL.md").exists())
        assertEquals("mine", File(root, "skills/dev-god/user.txt").readText())
    }

    @Test fun candidateLinksAreAdoptedWithoutReadingOrWritingTheirTargets() {
        for (samePath in listOf(true, false)) {
            val root = root()
            File(root, "agents").mkdirs()
            File(root, "skills").mkdirs()
            val outside = File(temp.newFolder(), "not-created")
            val agentPath = File(root, if (samePath) "agents/探索员.md" else "agents/unknown.md")
            val skillPath = File(root, if (samePath) "skills/dev-god" else "skills/unknown")
            Files.createSymbolicLink(agentPath.toPath(), outside.toPath())
            Files.createSymbolicLink(skillPath.toPath(), outside.toPath())
            val result = install(root)
            assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, result.entries["agents/探索员.md"])
            assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, result.entries["skills/dev-god"])
            assertTrue(Files.isSymbolicLink(agentPath.toPath()))
            assertTrue(Files.isSymbolicLink(skillPath.toPath()))
            assertFalse(outside.exists())
        }
    }

    @Test fun trustedAppParentSymlinkPublishesIntoCanonicalDirectory() {
        val appParent = temp.newFolder()
        val alias = File(temp.newFolder(), "app-files-alias")
        Files.createSymbolicLink(alias.toPath(), appParent.toPath())
        val root = File(alias, "aicode")
        assertTrue(install(root).entries.values.all { it == BuiltinInstallStatus.INSTALLED })
        val canonicalRoot = File(appParent, "aicode")
        agents.forEach { (name, bytes) -> assertArrayEquals(bytes, File(canonicalRoot, "agents/$name").readBytes()) }
        skills.getValue("dev-god").forEach { (name, bytes) -> assertArrayEquals(bytes, File(canonicalRoot, "skills/dev-god/$name").readBytes()) }
        assertTrue(Files.isSymbolicLink(alias.toPath()))
        assertFalse(Files.isSymbolicLink(canonicalRoot.toPath()))
        cleanStage(canonicalRoot)
    }

    @Test fun aicodeSymlinkBelowTrustedAppParentAliasIsStillRejected() {
        val appParent = temp.newFolder()
        val alias = File(temp.newFolder(), "app-files-alias")
        Files.createSymbolicLink(alias.toPath(), appParent.toPath())
        val outside = temp.newFolder()
        val root = File(alias, "aicode")
        Files.createSymbolicLink(File(appParent, "aicode").toPath(), outside.toPath())
        assertTrue(install(root).entries.values.all { it == BuiltinInstallStatus.FAILED })
        assertTrue(Files.isSymbolicLink(File(appParent, "aicode").toPath()))
        assertEquals(0, outside.list()!!.size)
    }

    @Test fun rootCategoryReceiptAndSkillInstructionLinksNeverEscapeBoundary() {
        for (boundary in listOf("root", "agents", "skills", BuiltinDefinitionPaths.RECEIPTS)) {
            val root = root()
            val outside = temp.newFolder()
            if (boundary == "root") Files.createSymbolicLink(root.toPath(), outside.toPath())
            else { root.mkdirs(); Files.createSymbolicLink(File(root, boundary).toPath(), outside.toPath()) }
            val result = install(root)
            assertTrue(result.entries.filterKeys { boundary !in listOf("agents", "skills") || it.startsWith("$boundary/") }
                .values.all { it == BuiltinInstallStatus.FAILED })
            assertEquals(0, outside.list()!!.size)
        }
        val root = root()
        File(root, "skills/custom").mkdirs()
        val outside = temp.newFile().apply { writeText("---\nname: dev-god\n---\nprivate") }
        Files.createSymbolicLink(File(root, "skills/custom/SKILL.md").toPath(), outside.toPath())
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, install(root).entries["skills/dev-god"])
        assertTrue(outside.readText().contains("private"))
    }

    @Test fun oversizedOrObjectTaggedCandidatesFailClosedAndAreNotConstructed() {
        for (payload in listOf("x".repeat(BuiltinDefinitionPaths.MAX_DEFINITION_BYTES + 1), "---\nname: !!java.lang.ProcessBuilder {}\n---\nbody")) {
            val root = root()
            File(root, "agents").mkdirs()
            File(root, "agents/custom.md").writeText(payload)
            assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, install(root).entries["agents/探索员.md"])
            assertFalse(File(root, "agents/探索员.md").exists())
            assertEquals(payload, File(root, "agents/custom.md").readText())
        }
    }

    @Test fun partialStagedCopyFailureLeavesNoDiscoverablePayloadOrReceiptAndRetries() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        val failing = object : BuiltinDefinitionStore by delegate {
            override fun writeNew(path: String, bytes: ByteArray) {
                if (path.endsWith("source.md")) {
                    delegate.writeNew(path, byteArrayOf(1))
                    throw IOException("partial copy")
                }
                delegate.writeNew(path, bytes)
            }
        }
        assertEquals(BuiltinInstallStatus.FAILED, BuiltinDefinitionInstaller(failing).install(skills = skills).entries["skills/dev-god"])
        assertFalse(File(root, "skills/dev-god").exists())
        assertFalse(receipt(root, "skills/dev-god").exists())
        cleanStage(root)
        assertEquals(BuiltinInstallStatus.INSTALLED, install(root).entries["skills/dev-god"])
    }

    @Test fun cancellationLeavesNoReceiptAndDoesNotConsumeRetry() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        val cancel = object : BuiltinDefinitionStore by delegate {
            override fun writeNew(path: String, bytes: ByteArray) {
                delegate.writeNew(path, bytes)
                if (path.endsWith("source.md")) throw CancellationException("cancelled")
            }
        }
        assertThrows(CancellationException::class.java) { BuiltinDefinitionInstaller(cancel).install(skills = skills) }
        assertFalse(File(root, "skills/dev-god").exists())
        assertFalse(receipt(root, "skills/dev-god").exists())
        cleanStage(root)
        assertEquals(BuiltinInstallStatus.INSTALLED, install(root).entries["skills/dev-god"])
    }

    @Test fun receiptFailureNeverPublishesAndSuccessfulDeletionNeverResurrects() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        val receipts = agents.keys.map { "${BuiltinDefinitionPaths.RECEIPTS}/agents/${it.removeSuffix(".md")}" }.toSet() +
            "${BuiltinDefinitionPaths.RECEIPTS}/skills/dev-god"
        var publications = 0
        val failing = object : BuiltinDefinitionStore by delegate {
            override fun writeNew(path: String, bytes: ByteArray) {
                if (path in receipts) throw IOException("receipt disk full")
                delegate.writeNew(path, bytes)
            }
            override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                publications++
                return delegate.publish(staged, target, directory)
            }
        }
        assertTrue(BuiltinDefinitionInstaller(failing).install(agents, skills).entries.values.all { it == BuiltinInstallStatus.FAILED })
        assertEquals(0, publications)
        agents.keys.forEach { assertFalse(File(root, "agents/$it").exists()) }
        assertFalse(File(root, "skills/dev-god").exists())
        receipts.forEach { assertFalse(File(root, it).exists()) }
        cleanStage(root)
        assertTrue(install(root).entries.values.all { it == BuiltinInstallStatus.INSTALLED })
        File(root, "agents/探索员.md").delete()
        File(root, "skills/dev-god").walkBottomUp().forEach { assertTrue(it.delete()) }
        assertTrue(install(root).entries.values.all { it == BuiltinInstallStatus.ALREADY_HANDLED })
        assertFalse(File(root, "agents/探索员.md").exists())
        assertFalse(File(root, "skills/dev-god").exists())
    }

    @Test fun receiptWriteFailureAfterCreationKeepsTombstoneWithoutPublishing() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        var publications = 0
        val uncertainClaim = object : BuiltinDefinitionStore by delegate {
            override fun writeNew(path: String, bytes: ByteArray) {
                delegate.writeNew(path, bytes)
                if (path == "${BuiltinDefinitionPaths.RECEIPTS}/skills/dev-god") throw IOException("receipt force failed")
            }
            override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                publications++
                return delegate.publish(staged, target, directory)
            }
        }
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, BuiltinDefinitionInstaller(uncertainClaim).install(skills = skills).entries["skills/dev-god"])
        assertEquals(0, publications)
        assertTrue(receipt(root, "skills/dev-god").isFile)
        assertFalse(File(root, "skills/dev-god").exists())
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, install(root).entries["skills/dev-god"])
        assertFalse(File(root, "skills/dev-god").exists())
        cleanStage(root)
    }

    @Test fun publishFailureBeforeExposureReleasesOwnedReceiptAndRetryPreservesUserClaims() {
        for (userClaims in listOf(false, true)) {
            val root = root()
            val delegate = LocalBuiltinDefinitionStore(root)
            val failing = object : BuiltinDefinitionStore by delegate {
                override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                    assertTrue(receipt(root, "skills/dev-god").isFile)
                    throw IOException("publish failed before exposure")
                }
            }
            assertEquals(BuiltinInstallStatus.FAILED, BuiltinDefinitionInstaller(failing).install(skills = skills).entries["skills/dev-god"])
            assertFalse(File(root, "skills/dev-god").exists())
            assertFalse(receipt(root, "skills/dev-god").exists())
            cleanStage(root)
            if (userClaims) {
                File(root, "skills/dev-god").mkdirs()
                File(root, "skills/dev-god/user.txt").writeText("user won")
            }
            val expected = if (userClaims) BuiltinInstallStatus.PRESERVED_EXISTING else BuiltinInstallStatus.INSTALLED
            assertEquals(expected, install(root).entries["skills/dev-god"])
            if (userClaims) {
                assertEquals(listOf("user.txt"), File(root, "skills/dev-god").list()!!.toList())
                assertEquals("user won", File(root, "skills/dev-god/user.txt").readText())
            } else skills.getValue("dev-god").forEach { (name, bytes) ->
                assertArrayEquals(bytes, File(root, "skills/dev-god/$name").readBytes())
            }
            assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, install(root).entries["skills/dev-god"])
        }
    }

    @Test fun failedPublishWithVisibleTargetRetainsReceiptAfterUserDeletion() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        val uncertainPublish = object : BuiltinDefinitionStore by delegate {
            override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                assertTrue(receipt(root, "skills/dev-god").isFile)
                assertTrue(delegate.publish(staged, target, directory))
                throw IOException("publication acknowledgement failed")
            }
        }
        assertEquals(BuiltinInstallStatus.FAILED, BuiltinDefinitionInstaller(uncertainPublish).install(skills = skills).entries["skills/dev-god"])
        assertTrue(receipt(root, "skills/dev-god").isFile)
        skills.getValue("dev-god").forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "skills/dev-god/$name").readBytes()) }
        File(root, "skills/dev-god").walkBottomUp().forEach { assertTrue(it.delete()) }
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, install(root).entries["skills/dev-god"])
        assertFalse(File(root, "skills/dev-god").exists())
    }

    @Test fun concurrentReceiptWinnerIsTheOnlyInstallerAllowedToPublish() {
        val root = root()
        val winnerStore = LocalBuiltinDefinitionStore(root)
        val loserStore = LocalBuiltinDefinitionStore(root)
        var winner: BuiltinInstallReport? = null
        var loserPublications = 0
        val loser = object : BuiltinDefinitionStore by loserStore {
            override fun writeNew(path: String, bytes: ByteArray) {
                if (path == "${BuiltinDefinitionPaths.RECEIPTS}/skills/dev-god") {
                    winner = BuiltinDefinitionInstaller(winnerStore).install(skills = skills)
                }
                loserStore.writeNew(path, bytes)
            }
            override fun publish(staged: String, target: String, directory: Boolean): Boolean {
                loserPublications++
                return loserStore.publish(staged, target, directory)
            }
        }
        assertEquals(BuiltinInstallStatus.ALREADY_HANDLED, BuiltinDefinitionInstaller(loser).install(skills = skills).entries["skills/dev-god"])
        assertEquals(BuiltinInstallStatus.INSTALLED, winner!!.entries["skills/dev-god"])
        assertEquals(0, loserPublications)
        skills.getValue("dev-god").forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "skills/dev-god/$name").readBytes()) }
        cleanStage(root)
    }

    @Test fun logicalUserSaveDuringStagingWinsAndAbandonedTreesAreNeverPruned() {
        val root = root()
        val delegate = LocalBuiltinDefinitionStore(root)
        delegate.ensureDirectory(BuiltinDefinitionPaths.RECEIPTS)
        val old = File(root, "${BuiltinDefinitionPaths.RECEIPTS}/old-stage/user.md")
        old.parentFile!!.mkdirs()
        old.writeText("leave alone")
        val saving = object : BuiltinDefinitionStore by delegate {
            override fun writeNew(path: String, bytes: ByteArray) {
                delegate.writeNew(path, bytes)
                if (path.endsWith("source.md")) {
                    File(root, "skills/mine").mkdirs()
                    File(root, "skills/mine/SKILL.md").writeText("---\nname: dev-god\n---\nuser")
                }
            }
        }
        assertEquals(BuiltinInstallStatus.PRESERVED_EXISTING, BuiltinDefinitionInstaller(saving).install(skills = skills).entries["skills/dev-god"])
        assertFalse(File(root, "skills/dev-god").exists())
        assertEquals("leave alone", old.readText())
    }

    @Test fun completeManifestValidationPrecedesAllFilesystemWrites() {
        val root = root()
        for (path in listOf("../escape", "/escape", "a/../../escape", "refs\\escape", "a//b", "a/./b", "a\nfile")) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBuiltinDefinitionStore.install(root, agents, mapOf("dev-god" to mapOf("SKILL.md" to byteArrayOf(), path to byteArrayOf())))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalBuiltinDefinitionStore.install(root, mapOf("coder.md" to agent("编码员"))) }
        assertThrows(IllegalArgumentException::class.java) { LocalBuiltinDefinitionStore.install(root, agents, mapOf("dev-god" to mapOf("docs/a.md" to byteArrayOf()))) }
        assertThrows(IllegalArgumentException::class.java) { LocalBuiltinDefinitionStore.install(root, agents, mapOf("dev-god" to mapOf("SKILL.md" to byteArrayOf(), "a" to byteArrayOf(), "a/b" to byteArrayOf()))) }
        assertFalse(root.exists())
    }

    @Test fun concurrentInstallersOnlyPublishOneWholeVersion() {
        val root = root()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reports = pool.invokeAll((1..8).map { Callable { install(root) } }).map { it.get() }
            for (key in agents.keys.map { "agents/$it" } + "skills/dev-god") {
                assertEquals(1, reports.count { it.entries[key] == BuiltinInstallStatus.INSTALLED })
                assertEquals(7, reports.count { it.entries[key] == BuiltinInstallStatus.ALREADY_HANDLED })
            }
            skills.getValue("dev-god").forEach { (name, bytes) -> assertArrayEquals(bytes, File(root, "skills/dev-god/$name").readBytes()) }
            cleanStage(root)
        } finally { pool.shutdownNow() }
    }

    @Test fun receiptSymlinkIsNeverAcceptedAsATombstone() {
        val root = root()
        val receipt = receipt(root, "agents/探索员")
        receipt.parentFile!!.mkdirs()
        val outside = temp.newFile()
        Files.createSymbolicLink(receipt.toPath(), outside.toPath())
        assertEquals(BuiltinInstallStatus.FAILED, install(root).entries["agents/探索员.md"])
        assertFalse(File(root, "agents/探索员.md").exists())
        assertTrue(Files.exists(receipt.toPath(), NOFOLLOW_LINKS))
        assertEquals(0L, outside.length())
    }
}
