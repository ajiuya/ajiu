package com.aicode.feature.agent.domain.memory

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectProgressCollectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val journal: ProjectMemoryJournal, val identity: ProjectMemoryIdentity, val project: ProjectMemoryProject)
    private fun fixture(remote: Boolean = false): Fixture {
        val root = temporary.newFolder("workspace")
        val identity = if (remote) ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work")
            else ProjectMemoryIdentity("local_proot", projectRoot = root.canonicalPath, profileId = "local")
        val journal = ProjectMemoryJournal(File(temporary.root, "journal"))
        return Fixture(journal, identity, journal.registerProject(identity, "Demo"))
    }
    private fun collector(fixture: Fixture, enabled: Boolean = true, projectId: String? = fixture.project.id, discovery: Boolean = true) =
        ProjectProgressCollector(fixture.journal, fixture.identity, "turn", enabled, projectId, discovery,
            sourceSessionId = "session", channelGeneration = if (fixture.identity.mode == "remote_ssh") "generation-one" else null)
    private fun args(path: String) = buildJsonObject { put("path", path) }.toString()

    @Test fun onlyBoundedSafeProjectionIsAcceptedFromToolArguments() {
        val raw = buildJsonObject {
            put("path", "src/Main.kt"); put("content", "never retain file body")
            put("command", "never retain full command"); put("privateReasoning", "never retain thought")
        }
        val projection = ProjectProgressCollector.projectArguments("writeFile", raw)!!
        assertEquals(setOf("path"), (Json.parseToJsonElement(projection) as JsonObject).keys)
        assertFalse(projection.contains("never retain"))
        assertNull(ProjectProgressCollector.projectArguments("Bash", raw))
        assertNull(ProjectProgressCollector.projectArguments("Root", raw))
        assertNull(ProjectProgressCollector.projectArguments("submitPlan", raw))
    }

    @Test fun actualStartedAndFinishedEventsRecordAttemptsWithoutCommandsOrLogs() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.recordStarted("call-1", "Bash")
        collector.recordFinished("call-1", false)
        collector.record("Bash", "{\"command\":\"never retain command\",\"stdout\":\"never retain output\"}", false, "call-1")
        collector.save("completed")
        val entry = fixture.journal.listRecent(fixture.project.id).single()
        assertEquals("completed", entry.status)
        assertTrue(entry.body.contains("Tool attempt: Bash"))
        assertTrue(entry.body.contains("reported_success"))
        assertTrue(entry.body.contains("not proof of build/test success"))
        assertFalse(entry.body.contains("never retain"))
        assertEquals("session", entry.sourceSessionId)
    }

    @Test fun duplicateStartFinishAndArgumentEventsDoNotAdvanceTheStoredCheckpoint() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.recordStarted("call-1", "writeFile")
        collector.recordStarted("call-1", "writeFile")
        collector.recordFinished("call-1", false)
        collector.recordFinished("call-1", true)
        collector.record("writeFile", args("src/Main.kt"), false, "call-1")
        val file = File(temporary.root, "journal").listFiles()!!.single { it.name.startsWith("progress-") }
        val before = file.readText()
        collector.recordStarted("call-1", "writeFile")
        collector.record("writeFile", args("src/Other.kt"), false, "call-1")
        collector.checkpoint()
        assertEquals(before, file.readText())
        collector.save("completed")
        val body = fixture.journal.listRecent(fixture.project.id).single().body
        assertEquals(1, Regex("Tool attempt: writeFile").findAll(body).count())
        assertFalse(body.contains("Other.kt"))
    }

    @Test fun unmatchedFinishesOrDeniedToolsCannotInventSuccessfulWork() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.recordFinished("never-started", false)
        collector.recordStarted("root", "Root")
        collector.recordStarted("plan", "submitPlan")
        collector.recordOutcome(true, "{\"code\":\"USER_REJECTED\"}")
        collector.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
    }

    @Test fun unfinishedActualAttemptsAndCancellationSaveInterruptedUnknown() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.recordStarted("call-1", "editFile")
        collector.recordOutcome(true, "{\"code\":\"CANCELLED\",\"message\":\"must not retain error output\"}")
        collector.save("completed")
        val entry = fixture.journal.listRecent(fixture.project.id).single()
        assertEquals("interrupted", entry.status)
        assertTrue(entry.body.contains("unknown / needs_verification"))
        assertFalse(entry.body.contains("must not retain"))
    }

    @Test fun fileAttributionRejectsEscapesHiddenFilesAndSensitiveNames() {
        val fixture = fixture()
        val collector = collector(fixture)
        listOf("../outside.txt", ".env", "private_key.pem", "credentials.txt").forEach { collector.record("writeFile", args(it), false) }
        collector.record("writeFile", args("src/Main.kt"), false)
        collector.save("completed")
        val body = fixture.journal.listRecent(fixture.project.id).single().body
        assertTrue(body.contains("src/Main.kt"))
        assertFalse(body.contains("outside.txt"))
        assertFalse(body.contains(".env"))
        assertFalse(body.contains("private_key"))
        assertFalse(body.contains("credentials"))
    }

    @Test fun todoIsAHistoricalFilteredSnapshotAndExplicitClearIsPreserved() {
        val fixture = fixture()
        val collector = collector(fixture)
        val items = JsonArray(listOf(
            buildJsonObject { put("subject", "Verify behavior"); put("status", "in_progress"); put("description", "never retain description") },
            buildJsonObject { put("subject", "api_key=not-a-real-key"); put("status", "pending") }
        ))
        val projection = ProjectProgressCollector.projectArguments("todo", mapOf("items" to items))!!
        assertFalse(projection.contains("not-a-real-key"))
        assertFalse(projection.contains("never retain"))
        collector.record("todo", projection, false)
        collector.record("todo", ProjectProgressCollector.projectArguments("todo", mapOf("items" to JsonArray(emptyList()))), false)
        collector.save("completed")
        val body = fixture.journal.listRecent(fixture.project.id).single().body
        assertTrue(body.contains("historical report, not the current todo list"))
        assertTrue(body.contains("explicitly cleared"))
        assertFalse(body.contains("Verify behavior"))
    }

    @Test fun failedTodoOperationDoesNotReplaceHistoricalSnapshot() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.record("todo", "{\"items\":[{\"subject\":\"Verify behavior\",\"status\":\"pending\"}]}", false)
        collector.record("todo", "{\"items\":[]}", true)
        collector.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).single().body.contains("pending: Verify behavior"))
    }

    @Test fun registeredProjectAndExplicitUnbindControlsAutomaticCollection() {
        val fixture = fixture()
        val collector = collector(fixture, projectId = null, discovery = false)
        collector.recordStarted("call-1", "Bash")
        collector.record("writeFile", args("src/Main.kt"), false)
        collector.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
        assertEquals(1, fixture.journal.listProjects().size)
    }

    @Test fun projectDisabledOrInvocationDisabledNeverWritesTurnHistory() {
        val fixture = fixture()
        fixture.journal.setAutoProgressEnabled(fixture.project.id, false)
        val enabled = collector(fixture)
        enabled.recordStarted("call", "Bash")
        enabled.save("completed")
        val disabled = collector(fixture, enabled = false)
        disabled.record("writeFile", args("src/Main.kt"), false)
        disabled.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
    }

    @Test fun uncorrelatedTerminalStartIsBackgroundPendingNotCompleted() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.record("terminal", "{\"action\":\"start\",\"command\":\"not retained\"}", false, "start")
        collector.save("completed")
        val entry = fixture.journal.listRecent(fixture.project.id).single()
        assertEquals("background_pending", entry.status)
        assertTrue(entry.body.contains("ownership/result unknown"))
        assertFalse(entry.body.contains("not retained"))
    }

    @Test fun correlatedBackgroundExitZeroRemainsAnUnverifiedProcessResult() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.record("terminal", "{\"action\":\"start\"}", false, "start")
        assertTrue(collector.backgroundStarted("tab-1"))
        collector.save("completed")
        assertEquals("background_pending", fixture.journal.listRecent(fixture.project.id).single().status)
        collector.backgroundFinished("tab-1", 0)
        val entry = fixture.journal.listRecent(fixture.project.id).single()
        assertEquals("completed", entry.status)
        assertTrue(entry.body.contains("exit_code=0 (process exit only; build/test unverified)"))
        assertFalse(entry.body.contains("tests passed"))
        val before = entry.revision
        collector.backgroundFinished("tab-1", 1)
        assertEquals(before, fixture.journal.read(entry.id)!!.revision)
    }

    @Test fun remoteUnboundDiscoveryCannotAutoRegisterOrAttributeAProject() {
        val fixture = fixture(remote = true)
        val collector = collector(fixture, projectId = null)
        collector.recordStarted("call", "Bash")
        collector.record("writeFile", args("/work/Main.kt"), false)
        collector.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
        assertEquals(1, fixture.journal.listProjects().size)
    }

    @Test fun sameTurnExplicitUnbindStopsNewActivityFromUsingTheFrozenOldProject() {
        val fixture = fixture()
        val selection = ProjectMemoryTurnOverride("session", "turn")
        val collector = ProjectProgressCollector(fixture.journal, fixture.identity, "turn", true, fixture.project.id,
            sourceSessionId = "session", turnOverride = selection)
        selection.bind(null) { }
        collector.recordStarted("late", "Bash")
        collector.record("writeFile", args("src/Main.kt"), false)
        collector.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
    }

    @Test fun sameTurnExplicitBindingEnablesPreviouslyUnboundCollectionWithoutChangingWorkspace() {
        val fixture = fixture()
        val childIdentity = fixture.identity.copy(projectRoot = File(fixture.identity.projectRoot, "child").canonicalPath)
        val child = fixture.journal.registerProject(childIdentity, "Child")
        val selection = ProjectMemoryTurnOverride("session", "turn")
        val collector = ProjectProgressCollector(fixture.journal, fixture.identity, "turn", true, null, false,
            sourceSessionId = "session", turnOverride = selection)
        selection.bind(child.id) { }
        collector.record("writeFile", args("child/Main.kt"), false)
        collector.save("completed")
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
        assertTrue(fixture.journal.listRecent(child.id).single().body.contains("Main.kt"))
    }

    @Test fun blockPreventsLateCallbacksFromRecreatingDeletedSessionCheckpoints() {
        val fixture = fixture()
        val collector = collector(fixture)
        collector.recordStarted("call", "Bash")
        collector.block()
        collector.recordFinished("call", false)
        collector.save("completed")
        collector.checkpoint()
        assertTrue(fixture.journal.listRecent(fixture.project.id).isEmpty())
        assertEquals(0, fixture.journal.recoverProgress("restart"))
    }
}
