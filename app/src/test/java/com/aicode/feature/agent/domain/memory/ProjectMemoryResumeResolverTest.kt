package com.aicode.feature.agent.domain.memory

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMemoryResumeResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work")
    private fun journal() = ProjectMemoryJournal(File(temporary.root, "journal"))

    @Test fun ordinaryRequestsCodeAndLongMessagesDoNotTriggerDiscovery() {
        assertNull(ProjectMemoryResumeResolver.query("fix the memory bug"))
        assertNull(ProjectMemoryResumeResolver.query("resume `Demo`"))
        assertNull(ProjectMemoryResumeResolver.query("resume Demo\nthen run code"))
        assertNull(ProjectMemoryResumeResolver.query("resume " + "x".repeat(241)))
        assertEquals("Demo", ProjectMemoryResumeResolver.query("继续Demo项目"))
        assertEquals("Demo", ProjectMemoryResumeResolver.query("resume Demo."))
    }

    @Test fun candidatesExcludeOtherHostsAccountsProfilesAndSiblings() {
        val journal = journal()
        val valid = journal.registerProject(identity.copy(projectRoot = "/work/one"), "Demo")
        journal.registerProject(identity.copy(host = "other.invalid", projectRoot = "/work/two"), "Demo")
        journal.registerProject(identity.copy(username = "bob", projectRoot = "/work/three"), "Demo")
        journal.registerProject(identity.copy(projectRoot = "/outside"), "Demo")
        val output = ProjectMemoryResumeResolver(journal).resolve("resume Demo", identity)!!
        assertTrue(output.contains(valid.id))
        assertFalse(output.contains("candidates"))
        assertFalse(output.contains("other.invalid"))
    }

    @Test fun ambiguousCandidatesAreBoundedTo7680Utf8BytesEvenWithEscapedUnicodeRoots() {
        val journal = journal()
        repeat(12) { index ->
            journal.registerProject(identity.copy(projectRoot = "/work/${"\"项目".repeat(200)}-$index"), "Demo ${"名".repeat(60)}")
        }
        val output = ProjectMemoryResumeResolver(journal).resolve("resume Demo", identity)!!
        assertTrue(output.toByteArray(Charsets.UTF_8).size <= 7680)
        val candidates = Json.parseToJsonElement(output).jsonObject.getValue("candidates") as JsonArray
        assertTrue(candidates.size in 1..10)
        assertTrue(output.contains("clarification"))
    }

    @Test fun largeHistoryProducesValidBoundedJsonWithFullReadAndOmissionMetadata() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        journal.append(project.id, "Handoff", "\"项目状态\\".repeat(600), kind = "handoff")
        repeat(12) { index -> journal.upsertTurn(project.id, "turn-$index", "待验证".repeat(100), "\"尚未验证\\".repeat(700), "interrupted") }
        val output = ProjectMemoryResumeResolver(journal).resolve("next", identity, project.id)!!
        assertTrue(output.toByteArray(Charsets.UTF_8).size <= 7680)
        val json = Json.parseToJsonElement(output).jsonObject
        assertNotNull(json["handoff"])
        assertTrue(output.contains("omittedUnresolvedTurns"))
        assertTrue(output.contains("fullRead"))
        assertTrue(output.contains("unverified historical report"))
    }

    @Test fun latestHandoffSupersedesOldUnresolvedWorkWithoutClaimingProjectCompletion() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        repeat(10) { journal.upsertTurn(project.id, "old-$it", "Old pending", "Old body", "interrupted") }
        journal.append(project.id, "Current handoff", "Current direction", kind = "handoff")
        journal.append(project.id, "New report", "Current result")
        val output = ProjectMemoryResumeResolver(journal).resolve("next", identity, project.id)!!
        assertTrue(output.contains("Current handoff"))
        assertTrue(output.contains("New report"))
        assertFalse(output.contains("Old body"))
        assertTrue(output.contains("\"projectCompletion\":\"unknown\""))
        assertTrue(output.contains("never the current todo list"))
    }

    @Test fun revokedReportsAreOmittedButArchivedHandoffRemainsReadable() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        val revoked = journal.append(project.id, "Revoked", "Do not inject")
        journal.revoke(revoked.id, revoked.revision)
        val handoff = journal.append(project.id, "Archived handoff", "Report only", kind = "handoff")
        journal.archive(handoff.id, handoff.revision)
        val output = ProjectMemoryResumeResolver(journal).resolve("next", identity, project.id)!!
        assertFalse(output.contains("Do not inject"))
        assertTrue(output.contains("Archived handoff"))
        assertTrue(output.contains("\"archived\":true"))
    }

    @Test fun invalidProjectBindingFailsRatherThanLookingOutsideTheCapturedEnvironment() {
        val journal = journal()
        val foreign = journal.registerProject(identity.copy(host = "other.invalid"), "Demo")
        assertThrows(IllegalArgumentException::class.java) { ProjectMemoryResumeResolver(journal).resolve("next", identity, foreign.id) }
    }
}
