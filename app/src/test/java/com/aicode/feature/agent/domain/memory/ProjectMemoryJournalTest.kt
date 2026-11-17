package com.aicode.feature.agent.domain.memory

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMemoryJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun journal() = ProjectMemoryJournal(File(temporary.root, "journal"))
    private fun identity(root: String = "/work/demo", host: String = "fixture.invalid") =
        ProjectMemoryIdentity("remote_ssh", host, 22, "alice", root)
    private fun project(journal: ProjectMemoryJournal) = journal.registerProject(identity(), "Demo", listOf("sample"))

    @Test fun stableNormalizedIdentityDistinguishesHostAccountPortAndWorkspace() {
        val journal = journal()
        val first = project(journal)
        assertEquals(first.id, journal().registerProject(identity("/work/./child/../demo/", "FIXTURE.INVALID"), "Rename").id)
        val variants = listOf(identity("/other/demo"), identity(host = "other.invalid"), identity().copy(port = 2222), identity().copy(username = "bob"))
        variants.forEach { assertNotEquals(first.id, journal.registerProject(it, "Demo").id) }
        assertEquals(listOf(first), journal.findProjects("SAMPLE"))
    }

    @Test fun localProfilesCannotMergeEvenWhenRootIsIdentical() {
        val journal = journal()
        val local = ProjectMemoryIdentity("local_proot", projectRoot = temporary.root.path, profileId = "one")
        assertNotEquals(journal.registerProject(local, "One").id, journal.registerProject(local.copy(profileId = "two"), "Two").id)
        assertThrows(IllegalArgumentException::class.java) { journal.registerProject(local.copy(profileId = ""), "Invalid") }
    }

    @Test fun registrationDefaultsEnabledAndAliasesDistinguishOmissionFromClear() {
        val journal = journal()
        val first = project(journal)
        assertTrue(first.autoProgressEnabled)
        val renamed = journal.registerProject(identity(), "Renamed", updateExisting = true)
        assertEquals(first.aliases, renamed.aliases)
        val cleared = journal.registerProject(identity(), "Renamed", emptyList(), updateExisting = true)
        assertTrue(cleared.aliases.isEmpty())
        assertEquals(cleared, journal.registerProject(identity(), "Automatic name", listOf("ignored")))
    }

    @Test fun bindingStatesDistinguishAbsentRememberedAndExplicitlyNull() {
        val journal = journal()
        val project = project(journal)
        assertNull(journal.sessionIdentity("session"))
        assertFalse(journal.hasSessionProjectBinding("session", identity()))
        assertEquals("none", journal.associations("session", identity()).sessionBindingState)
        journal.rememberSessionIdentity("session", identity())
        assertEquals(identity(), journal.sessionIdentity("session"))
        assertFalse(journal.hasSessionProjectBinding("session", identity()))
        assertEquals("unbound", journal.associations("session", identity()).sessionBindingState)
        journal.bindSessionProject("session", identity(), project.id)
        assertEquals(project, journal.sessionProject("session", identity()))
        journal.bindSessionProject("session", identity(), null)
        assertTrue(journal().hasSessionProjectBinding("session", identity()))
        assertNull(journal().sessionProject("session", identity()))
        assertEquals("explicit_null", journal.associations("session", identity()).sessionBindingState)
    }

    @Test fun sessionBindingsCannotRetargetAnotherEndpointOrSiblingProject() {
        val journal = journal()
        val project = project(journal)
        journal.bindSessionProject("session", identity(), project.id)
        assertThrows(IllegalStateException::class.java) { journal.bindSessionProject("session", identity(host = "other.invalid"), null) }
        val sibling = journal.registerProject(identity("/outside"), "Sibling")
        assertThrows(IllegalStateException::class.java) { journal.bindSessionProject("other", identity(), sibling.id) }
        assertEquals(project, journal.sessionProject("session", identity()))
    }

    @Test fun workspaceExplicitNullIsPersistentAndSeparateFromSessionBinding() {
        val journal = journal()
        val project = project(journal)
        assertFalse(journal.hasWorkspaceProjectBinding(identity()))
        journal.setWorkspaceProject(identity(), null)
        journal.bindSessionProject("session", identity(), project.id)
        val associations = journal().associations("session", identity())
        assertTrue(associations.workspaceBindingPresent)
        assertNull(associations.workspaceProject)
        assertEquals(project, associations.sessionProject)
    }

    @Test fun sequenceDedupAndTerminalStateProtectAgainstDelayedBackgroundCallbacks() {
        val journal = journal()
        val id = project(journal).id
        val pending = journal.upsertTurn(id, "turn", "Pending", "Reported activity", "background_pending", 4, "session")!!
        assertEquals(pending, journal.upsertTurn(id, "turn", "Old", "Stale body", "background_pending", 3, "session"))
        assertEquals(pending, journal.upsertTurn(id, "turn", "Duplicate", "Other body", "background_pending", 4, "session"))
        val finished = journal.upsertTurn(id, "turn", "Finished", "Still unverified", "completed", 5, "session")!!
        assertEquals(2, finished.revision)
        assertEquals(finished, journal.upsertTurn(id, "turn", "Late failure", "Late", "failed", 6, "session"))
        assertEquals(1, journal.listRecent(id).size)
        assertThrows(IllegalStateException::class.java) { journal.upsertTurn(id, "turn", "Foreign", "", "completed", 7, "other") }
    }

    @Test fun editedRevokedArchivedAndManualEntriesSurviveAutomaticUpdates() {
        val journal = journal()
        val id = project(journal).id
        val edited = journal.upsertTurn(id, "edited", "Pending", "", "background_pending")!!.let { journal.edit(it.id, it.revision, "Correction", "Keep") }
        val revoked = journal.upsertTurn(id, "revoked", "Pending", "", "background_pending")!!.let { journal.revoke(it.id, it.revision) }
        val archived = journal.upsertTurn(id, "archived", "Pending", "", "background_pending")!!.let { journal.archive(it.id, it.revision) }
        val manual = journal.append(id, "Manual", "Keep", turnId = "manual")
        listOf(edited, revoked, archived, manual).forEach {
            assertEquals(it, journal.upsertTurn(id, it.turnId!!, "Late", "Overwrite", "completed", 20))
        }
        assertFalse(journal.listRecent(id).any { it.revoked || it.archived })
    }

    @Test fun revisionConflictIsAtomicAndArchiveRemainsReadable() {
        val journal = journal()
        val id = project(journal).id
        val first = journal.append(id, "Summary", "Body")
        val edited = journal.edit(first.id, 1, "Correction", "Corrected")
        val before = journal.exportHistory()
        assertThrows(IllegalStateException::class.java) { journal.revoke(first.id, 1) }
        assertEquals(before, journal.exportHistory())
        val archived = journal.archive(edited.id, edited.revision)
        assertEquals(0, journal.capacity().activeEntries)
        assertEquals(1, journal.capacity().archivedEntries)
        assertEquals(archived, journal.read(first.id))
        assertEquals(listOf(archived), journal.listArchive(id))
        assertEquals(listOf(archived), journal.history(id))
    }

    @Test fun literalSearchAndBoundedPagesRetainNewestEntryWithTruncationMetadata() {
        val journal = journal()
        val id = project(journal).id
        journal.append(id, "Old", "short")
        val newest = journal.append(id, "Kotlin Build", "Report ".repeat(500))
        val page = journal.listRecent(id, maxChars = 128)
        assertEquals(newest.id, page.single().id)
        assertTrue(page.single().truncated)
        assertEquals(128, page.single().summary.length + page.single().body.length)
        assertEquals(listOf(newest), journal.search(id, "KOTLIN\tReport"))
        assertTrue(journal.search(id, "Kotlin absent").isEmpty())
        assertTrue(journal.search(id, "").isEmpty())
        assertTrue(journal.listRecent(id, maxChars = 1).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { journal.search(id, "x".repeat(513)) }
        assertThrows(IllegalArgumentException::class.java) { journal.listRecent(id, offset = -1) }
    }

    @Test fun kindFilteringPrecedesPagingAndCharacterBudgetForRecentAndArchive() {
        val journal = journal()
        val id = project(journal).id
        val handoffs = (1..23).map { journal.handoff(id, "Handoff $it", "Handoff body") }
        val turns = (1..24).map { journal.upsertTurn(id, "turn-$it", "Turn $it", "x".repeat(8192), "completed")!! }
        val note = journal.append(id, "Note", "Keep in all records")
        val first = journal.listRecent(id, limit = 20, maxChars = 65536, kind = "handoff")
        val second = journal.listRecent(id, limit = 20, maxChars = 65536, offset = first.size, kind = "handoff")
        assertEquals(20, first.size)
        assertEquals(3, second.size)
        assertEquals(handoffs.reversed(), first + second)
        assertTrue(journal.listRecent(id, maxChars = 65536).any { it.id == note.id })
        assertTrue(journal.listRecent(id, maxChars = 65536, kind = "turn").all { it.kind == "turn" })
        (handoffs + turns + note).forEach { journal.archive(it.id, it.revision) }
        val archived = journal.listArchive(id, limit = 20, maxChars = 65536, kind = "handoff")
        val archiveRemainder = journal.listArchive(id, limit = 20, offset = archived.size, maxChars = 65536, kind = "handoff")
        assertEquals(20, archived.size)
        assertEquals(3, archiveRemainder.size)
        assertEquals(handoffs.map { it.id }.toSet(), (archived + archiveRemainder).map { it.id }.toSet())
        assertTrue((archived + archiveRemainder).all { it.archived && it.kind == "handoff" })
    }

    @Test fun filtersKeepRevocationVisibilityAndLiteralArchiveSearchSemantics() {
        val journal = journal()
        val id = project(journal).id
        val handoff = journal.handoff(id, "Handoff match", "body")
        val turn = journal.upsertTurn(id, "turn", "Turn match", "body", "completed")!!
        val revoked = journal.revoke(turn.id, turn.revision)
        journal.append(id, "Note match", "body")
        assertTrue(journal.listRecent(id, kind = "turn").isEmpty())
        assertEquals(listOf(revoked), journal.listRecent(id, includeRevoked = true, kind = "turn"))
        assertEquals(setOf("note", "turn", "handoff"), journal.listRecent(id, includeRevoked = true).map { it.kind }.toSet())
        val archivedTurn = journal.archive(revoked.id, revoked.revision)
        journal.archive(handoff.id, handoff.revision)
        assertEquals(listOf(archivedTurn), journal.listArchive(id, query = "MATCH", kind = "turn"))
        assertTrue(journal.listArchive(id, query = "absent", kind = "handoff").isEmpty())
        assertEquals(setOf("turn", "handoff"), journal.listArchive(id).map { it.kind }.toSet())
    }

    @Test fun unsupportedKindsAreRejectedAtBothQueryBoundaries() {
        val journal = journal()
        val id = project(journal).id
        listOf("note", "auto", "manual", "", "TURN").forEach { kind ->
            assertThrows(IllegalArgumentException::class.java) { journal.listRecent(id, kind = kind) }
            assertThrows(IllegalArgumentException::class.java) { journal.listArchive(id, kind = kind) }
        }
        assertTrue(journal.listRecent(id, kind = null).isEmpty())
        assertTrue(journal.listArchive(id, kind = null).isEmpty())
    }

    @Test fun historyRoundTripExcludesBindingsCheckpointsAndRuntimeState() {
        val journal = journal()
        val project = project(journal)
        val note = journal.handoff(project.id, "Report", "Unverified", sourceSessionId = "source")
        journal.bindSessionProject("bound-session", identity(), project.id)
        journal.setWorkspaceProject(identity(), project.id)
        journal.checkpointProgress("pending", identity(), listOf(ProjectProgressDraft(project.id, "Pending fact")), listOf("tab-1"), 1, "bound-session")
        val text = journal.exportHistory()
        assertFalse(text.contains("bound-session"))
        assertFalse(text.contains("pendingTabs"))
        assertFalse(text.contains("processId"))
        val imported = ProjectMemoryJournal(File(temporary.root, "imported"))
        imported.importHistory(text)
        assertEquals(note, imported.read(note.id))
        assertNull(imported.sessionIdentity("bound-session"))
        assertFalse(imported.hasWorkspaceProjectBinding(identity()))
        assertEquals(0, imported.recoverProgress("another-process"))
    }

    @Test fun importConflictsAndChangedPreviewNeverOverwriteLocalHistory() {
        val source = journal()
        val id = project(source).id
        val entry = source.append(id, "Original", "Body")
        val destination = ProjectMemoryJournal(File(temporary.root, "destination"))
        destination.importHistory(source.exportHistory())
        val version = destination.historyVersion()
        destination.edit(entry.id, 1, "Local edit", "Keep")
        val before = destination.exportHistory()
        assertThrows(IllegalStateException::class.java) { destination.importHistory(source.exportHistory(), version) }
        assertThrows(IllegalStateException::class.java) { destination.importHistory(source.exportHistory()) }
        assertEquals(before, destination.exportHistory())
    }

    @Test fun importedSnapshotsRejectUnknownMetadataTruncatedEntriesAndDuplicateScope() {
        val journal = journal()
        val project = project(journal)
        journal.append(project.id, "Report", "Body")
        val parsed = Json.parseToJsonElement(journal.exportHistory()).jsonObject
        assertThrows(IllegalArgumentException::class.java) { journal.validateHistory(JsonObject(parsed + ("checkpoints" to JsonArray(emptyList()))).toString()) }
        assertThrows(IllegalArgumentException::class.java) { journal.validateHistory(journal.exportHistory().replace("\"truncated\":false", "\"truncated\":true")) }
        val projects = parsed.getValue("projects") as JsonArray
        val duplicate = JsonObject(projects.single().jsonObject + ("id" to JsonPrimitive(UUID.randomUUID().toString())))
        assertThrows(IllegalArgumentException::class.java) { journal.validateHistory(JsonObject(parsed + ("projects" to JsonArray(projects + duplicate))).toString()) }
    }

    @Test fun capacityValidationRejectsOversizedActiveSnapshotBeforeChangingDisk() {
        val journal = journal()
        val id = project(journal).id
        val parsed = Json.parseToJsonElement(journal.exportHistory()).jsonObject
        val entries = (0..2048).map { index ->
            Json.parseToJsonElement(Json.encodeToString(ProjectMemoryEntry("${id}_${UUID.randomUUID()}", id,
                createdAt = index.toLong(), kind = "note", summary = "Entry", body = "", status = "recorded")))
        }
        val before = journal.exportHistory()
        assertThrows(IllegalArgumentException::class.java) { journal.importHistory(JsonObject(parsed + ("entries" to JsonArray(entries))).toString()) }
        assertEquals(before, journal.exportHistory())
    }

    @Test fun failuresBeforeRenameLeaveCommittedJsonIntactAndNoTemporaryFiles() {
        val fail = AtomicBoolean(false)
        val root = File(temporary.root, "atomic")
        val journal = ProjectMemoryJournal(root, beforeCommit = { if (fail.get()) throw IOException("injected") })
        val id = project(journal).id
        val before = journal.exportHistory()
        fail.set(true)
        assertThrows(IOException::class.java) { journal.append(id, "Not committed", "") }
        assertEquals(before, ProjectMemoryJournal(root).exportHistory())
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun independentInstancesSerializeConcurrentWritesAndSameTurnReceipts() {
        val id = project(journal()).id
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writes = (1..16).map { number -> pool.submit<ProjectMemoryEntry> { journal().append(id, "Entry $number", "") } }
            assertEquals(16, writes.map { it.get().id }.distinct().size)
            val duplicates = (1..8).map { pool.submit<ProjectMemoryEntry?> { journal().upsertTurn(id, "same", "Done", "Unverified", "completed", 1) } }
            assertEquals(1, duplicates.map { it.get()!!.id }.distinct().size)
            assertEquals(17, journal().entryCount(id))
        } finally { pool.shutdownNow() }
    }

    @Test fun symlinkStateAndLockAreRejectedWithoutTouchingUserFiles() {
        val journal = journal()
        val id = project(journal).id
        val outside = temporary.newFile("outside").apply { writeText("untouched") }
        val state = File(temporary.root, "journal/state.json")
        Files.delete(state.toPath())
        Files.createSymbolicLink(state.toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { journal.append(id, "Unsafe", "") }
        assertEquals("untouched", outside.readText())
        val other = File(temporary.root, "lock-journal").apply { mkdir() }
        Files.createSymbolicLink(File(other, "journal.lock").toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { ProjectMemoryJournal(other).listProjects() }
        assertEquals("untouched", outside.readText())
    }

    @Test fun trustedFilesPrefixMayResolveSystemAliasButControlledDescendantsCannotLink() {
        val real = temporary.newFolder("real")
        val alias = File(temporary.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        val directory = File(alias, "aicode/memory/project-journal")
        assertTrue(ProjectMemoryJournal(directory, alias).listProjects().isEmpty())
        val linked = File(real, "linked")
        Files.createSymbolicLink(linked.toPath(), File(real, "aicode").toPath())
        assertThrows(IllegalStateException::class.java) { ProjectMemoryJournal(File(linked, "memory/project-journal"), real).listProjects() }
        assertThrows(IllegalStateException::class.java) { ProjectMemoryJournal(directory).listProjects() }
    }

    @Test fun traversalAndUnknownFilesCannotBecomeDeleteTargets() {
        val journal = journal()
        project(journal)
        val root = File(temporary.root, "journal")
        val unknown = File(root, "progress-user.json").apply { writeText("user-owned") }
        assertThrows(IllegalArgumentException::class.java) { journal.read("../state.json") }
        assertThrows(IllegalArgumentException::class.java) { journal.listRecent("../outside") }
        journal.clearSession("unknown")
        journal.recoverProgress("restart")
        assertEquals("user-owned", unknown.readText())
    }

    @Test fun oldProcessRecoveryOnlyRecordsInterruptedUnknownAndIsIdempotent() {
        val journal = journal()
        val project = project(journal)
        journal.checkpointProgress("turn", identity(), listOf(ProjectProgressDraft(project.id, "writeFile: reported_success: Main.kt")), emptyList(), 2, "session")
        assertEquals(0, journal.recoverProgress())
        assertEquals(1, journal().recoverProgress("different-process"))
        val recovered = journal.listRecent(project.id).single()
        assertEquals("interrupted", recovered.status)
        assertEquals("session", recovered.sourceSessionId)
        assertTrue(recovered.body.contains("unknown / needs_verification"))
        assertEquals(0, journal.recoverProgress("different-process"))
        assertEquals(recovered, journal.read(recovered.id))
    }

    @Test fun recoveryNeverOverwritesFinishedOrUserControlledRecords() {
        val journal = journal()
        val project = project(journal)
        val completed = journal.upsertTurn(project.id, "done", "Completed turn", "Unverified", "completed", 3)!!
        val manual = journal.append(project.id, "Manual", "Keep", turnId = "manual")
        listOf(completed, manual).forEach { journal.checkpointProgress(it.turnId!!, identity(), listOf(ProjectProgressDraft(project.id, "Old draft")), emptyList(), 4) }
        assertEquals(0, journal.recoverProgress("restart"))
        assertEquals(completed, journal.read(completed.id))
        assertEquals(manual, journal.read(manual.id))
    }

    @Test fun disabledCollectionDefersOldCheckpointUntilExplicitlyEnabled() {
        val journal = journal()
        val project = project(journal)
        journal.checkpointProgress("turn", identity(), listOf(ProjectProgressDraft(project.id, "Pending")), emptyList(), 1)
        journal.setAutoProgressEnabled(project.id, false)
        assertEquals(0, journal.recoverProgress("restart"))
        assertNull(journal.upsertTurn(project.id, "new", "Disabled", "", "completed"))
        journal.setAutoProgressEnabled(project.id, true)
        assertEquals(1, journal.recoverProgress("restart"))
    }

    @Test fun checkpointSequenceDedupAndOwnerConflictsProtectTheLatestDraft() {
        val journal = journal()
        val project = project(journal)
        journal.checkpointProgress("turn", identity(), listOf(ProjectProgressDraft(project.id, "Latest fact")), emptyList(), 4, "session")
        journal.checkpointProgress("turn", identity(), listOf(ProjectProgressDraft(project.id, "Stale fact")), emptyList(), 3, "session")
        assertThrows(IllegalStateException::class.java) {
            journal.checkpointProgress("turn", identity(host = "other.invalid"), listOf(ProjectProgressDraft(project.id, "Foreign fact")), emptyList(), 5, "session")
        }
        assertEquals(1, journal.recoverProgress("restart"))
        assertTrue(journal.listRecent(project.id).single().body.contains("Latest fact"))
        assertFalse(journal.listRecent(project.id).single().body.contains("Stale fact"))
    }

    @Test fun deletingSessionOnlyRemovesOwnedMetadataAndKeepsHistory() {
        val journal = journal()
        val project = project(journal)
        val entry = journal.append(project.id, "Keep history", "", sourceSessionId = "session")
        journal.bindSessionProject("session", identity(), project.id)
        journal.checkpointProgress("turn", identity(), listOf(ProjectProgressDraft(project.id, "Pending")), emptyList(), 1, "session")
        journal.clearSession("session")
        assertNull(journal.sessionIdentity("session"))
        assertEquals(0, journal.recoverProgress("restart"))
        assertEquals(entry, journal.read(entry.id))
    }
}
