package com.aicode.feature.agent.domain.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectProgressTerminalTrackerTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val journal: ProjectMemoryJournal, val identity: ProjectMemoryIdentity, val project: ProjectMemoryProject)
    private fun fixture(remote: Boolean = false): Fixture {
        val root = temporary.newFolder("workspace")
        val identity = if (remote) ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work")
            else ProjectMemoryIdentity("local_proot", projectRoot = root.canonicalPath, profileId = "local")
        val journal = ProjectMemoryJournal(File(temporary.root, "journal"))
        return Fixture(journal, identity, journal.registerProject(identity, "Demo"))
    }
    private fun collector(f: Fixture, turn: String = "turn", generation: String? = null) =
        ProjectProgressCollector(f.journal, f.identity, turn, true, f.project.id, sourceSessionId = "session", channelGeneration = generation)
    private val args = "{\"action\":\"start\"}"
    private val result = "{\"status\":\"success\",\"data\":{\"tab_id\":\"tab-1\"}}"

    @Test fun completionBeforeStartIsCorrelatedExactlyOnceWithinTheSameOwnerAndMode() {
        val f = fixture()
        val collector = collector(f)
        val tracker = ProjectProgressTerminalTracker()
        tracker.register("session", collector)
        collector.record("terminal", args, false, "call")
        tracker.finished("session", "tab-1", 0, "local_proot")
        tracker.started("session", collector, args, result, false)
        collector.save("completed")
        val entry = f.journal.listRecent(f.project.id).single()
        assertTrue(entry.body.contains("exit_code=0"))
        tracker.finished("session", "tab-1", 1, "local_proot")
        assertEquals(entry, f.journal.read(entry.id))
    }

    @Test fun missingWrongModeAndForeignSessionCannotFinishOwnedWork() {
        val f = fixture()
        val collector = collector(f)
        val tracker = ProjectProgressTerminalTracker()
        tracker.register("session", collector)
        collector.record("terminal", args, false, "call")
        tracker.started("session", collector, args, result, false)
        collector.save("completed")
        tracker.finished("session", "tab-1", 0)
        tracker.finished("other-session", "tab-1", 0, "local_proot")
        tracker.finished("session", "tab-1", 0, "remote_ssh", "foreign")
        assertEquals("background_pending", f.journal.listRecent(f.project.id).single().status)
        tracker.finished("session", "tab-1", 0, "local_proot")
        assertEquals("completed", f.journal.listRecent(f.project.id).single().status)
    }

    @Test fun sshReconnectGenerationIsRuntimeOnlyAndPreventsCrossConnectionCompletion() {
        val f = fixture(remote = true)
        val collector = collector(f, generation = "generation-one")
        val tracker = ProjectProgressTerminalTracker()
        tracker.register("session", collector)
        collector.record("terminal", args, false, "call")
        tracker.started("session", collector, args, result, false)
        collector.save("completed")
        tracker.finished("session", "tab-1", 0, "remote_ssh")
        tracker.finished("session", "tab-1", 0, "remote_ssh", "generation-two")
        assertEquals("background_pending", f.journal.listRecent(f.project.id).single().status)
        tracker.finished("session", "tab-1", 0, "remote_ssh", "generation-one")
        assertEquals("completed", f.journal.listRecent(f.project.id).single().status)
        assertFalse(f.journal.exportHistory().contains("generation-one"))
    }

    @Test fun releaseRetainsAnOwnedBackgroundTaskButDeletionBlocksEveryLateCallback() {
        val f = fixture()
        val collector = collector(f)
        val tracker = ProjectProgressTerminalTracker()
        tracker.register("session", collector)
        collector.record("terminal", args, false, "call")
        tracker.started("session", collector, args, result, false)
        collector.save("completed")
        tracker.release("session", collector)
        tracker.clearSession("session")
        tracker.finished("session", "tab-1", 0, "local_proot")
        collector.save("completed")
        assertEquals("background_pending", f.journal.listRecent(f.project.id).single().status)
        assertEquals(0, f.journal.recoverProgress("restart"))
    }

    @Test fun failedOrUnregisteredStartsCannotClaimTerminalOwnership() {
        val f = fixture()
        val collector = collector(f)
        val tracker = ProjectProgressTerminalTracker()
        collector.record("terminal", args, false, "call")
        tracker.started("session", collector, args, result, false)
        tracker.register("session", collector)
        tracker.started("session", collector, args, result, true)
        collector.save("completed")
        tracker.finished("session", "tab-1", 0, "local_proot")
        assertEquals("background_pending", f.journal.listRecent(f.project.id).single().status)
    }
}
