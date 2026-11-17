package com.aicode.feature.agent.domain.tool.memory

import android.content.Context
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.agent.domain.memory.ProjectMemoryPathResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryTurnOverride
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryJournalActionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val appContext = mockk<Context> {
        every { getString(any()) } answers { "resource-${firstArg<Int>()}" }
        every { getString(any(), any(), any()) } answers { "resource-${firstArg<Int>()}" }
    }
    private fun identity() = ProjectMemoryIdentity("local_proot", projectRoot = temporary.root.canonicalPath, profileId = "local")
    private fun journal() = ProjectMemoryJournal(File(temporary.root, "journal"))
    private fun actions(journal: ProjectMemoryJournal) = MemoryJournalActions(journal,
        ProjectMemoryIdentityResolver(ExecutionModeHolder(), mockk(), mockk()), ProjectMemoryPathResolver(), appContext)
    private fun context(capture: ProjectMemoryCapture = ProjectMemoryCapture(identity()) { }) = AgentContext(null, null,
        temporary.root.canonicalPath, null, sessionId = "session", inputMessageId = "turn", projectMemoryIdentity = capture.identity,
        projectMemoryCapture = capture, projectMemoryTurnOverride = ProjectMemoryTurnOverride("session", "turn"))
    private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> = pairs.associate { it.first to JsonPrimitive(it.second) }
    private fun success(result: ToolResult) = (result as ToolResult.Success).data as JsonObject

    @Test fun onlyTheExplicitReadWhitelistDropsModifyCapability() {
        val tool = MemoryTool(mockk(), appContext, actions(journal()))
        (MemoryJournalActions.READ_ACTIONS + setOf("read", "list")).forEach { action ->
            assertEquals(setOf(ToolCapability.READ_AGENT_CONFIG), tool.effectiveCapabilities(args("action" to action)))
        }
        (MemoryJournalActions.ACTIONS - MemoryJournalActions.READ_ACTIONS + setOf("save", "edit", "delete", "unknown", "PROJECTS", " projects")).forEach { action ->
            assertTrue(ToolCapability.MODIFY_AGENT_CONFIG in tool.effectiveCapabilities(args("action" to action)))
        }
        assertTrue(ToolCapability.MODIFY_AGENT_CONFIG in tool.effectiveCapabilities(mapOf("action" to JsonObject(emptyMap()))))
    }

    @Test fun legacyListUsesTheOriginalRepositoryWithoutRequiringJournalCapture() = runTest {
        val repository = mockk<MemoryRepository> { every { listMemories(temporary.root.canonicalPath) } returns emptyList() }
        val tool = MemoryTool(repository, appContext, actions(journal()))
        val legacy = AgentContext(null, null, temporary.root.canonicalPath, null)
        assertTrue(tool.executeWithContext(args("action" to "list"), legacy) is ToolResult.Success)
        verify(exactly = 1) { repository.listMemories(temporary.root.canonicalPath) }
        assertTrue(journal().listProjects().isEmpty())
    }

    @Test fun legacySaveRetainsItsScopeAndOverwriteProtocol() = runTest {
        val repository = mockk<MemoryRepository> {
            every { saveMemory("conventions", "summary", "body", MemoryScope.GLOBAL, temporary.root.canonicalPath) } returns true
        }
        val tool = MemoryTool(repository, appContext, actions(journal()))
        val result = tool.executeWithContext(args("action" to "save", "name" to "conventions", "description" to "summary", "content" to "body", "scope" to "global"),
            AgentContext(null, null, temporary.root.canonicalPath, null))
        assertTrue(result is ToolResult.Success)
        verify(exactly = 1) { repository.saveMemory("conventions", "summary", "body", MemoryScope.GLOBAL, temporary.root.canonicalPath) }
    }

    @Test fun missingFrozenCaptureCannotReadOrMutateJournalState() = runTest {
        val journal = journal()
        val result = actions(journal).execute("projects", emptyMap(), context().copy(projectMemoryCapture = null)) as ToolResult.Error
        assertEquals("MEMORY_NO_PROJECT", result.code)
        assertTrue(result.message.startsWith("resource-"))
        assertTrue(journal.listProjects().isEmpty())
    }

    @Test fun registrationAndSessionBindingStayWithinTheFrozenRoot() = runTest {
        val journal = journal()
        val actions = actions(journal)
        val context = context()
        val registered = success(actions.execute("register_project", args("name" to "Demo", "project_root" to "~/workspace"), context))
        val id = (registered.getValue("project_id") as JsonPrimitive).content
        success(actions.execute("bind_project", args("project_id" to id), context))
        assertEquals(id, journal.sessionProject("session", identity())!!.id)
        assertEquals(id, context.projectMemoryTurnOverride!!.get()!!.projectId)
        val outside = actions.execute("register_project", args("name" to "Outside", "project_root" to "../outside"), context) as ToolResult.Error
        assertEquals("MEMORY_SCOPE_MISMATCH", outside.code)
        assertEquals(1, journal.listProjects().size)
    }

    @Test fun explicitNullBindingDoesNotFallBackToTheOnlyProjectOnTheSameTurn() = runTest {
        val journal = journal()
        journal.registerProject(identity(), "Demo")
        val context = context()
        val actions = actions(journal)
        success(actions.execute("bind_project", args("project_id" to ""), context))
        val recent = actions.execute("recent", emptyMap(), context) as ToolResult.Error
        assertEquals("MEMORY_PROJECT_AMBIGUOUS", recent.code)
        assertTrue(journal.hasSessionProjectBinding("session", identity()))
        assertNull(journal.sessionProject("session", identity()))
    }

    @Test fun staleRevisionAndSensitiveReportsReturnLocalizedErrorsWithoutOverwrite() = runTest {
        val journal = journal()
        val project = journal.registerProject(identity(), "Demo")
        val entry = journal.append(project.id, "Report", "Keep")
        val actions = actions(journal)
        val stale = actions.execute("revoke", args("entry_id" to entry.id) + ("revision" to JsonPrimitive(2)), context()) as ToolResult.Error
        assertEquals("MEMORY_CONFLICT", stale.code)
        val sensitive = actions.execute("append", args("project_id" to project.id, "description" to "Report", "content" to "Authorization: Bearer fixture-only"), context()) as ToolResult.Error
        assertEquals("MEMORY_INVALID_ARGUMENT", sensitive.code)
        assertEquals(entry, journal.read(entry.id))
        assertEquals(1, journal.entryCount(project.id))
    }

    @Test fun captureIsCheckedAroundIoAndStaleCaptureIsNeverRecapturedFromLiveSettings() = runTest {
        val journal = journal()
        var checks = 0
        val capture = ProjectMemoryCapture(identity()) { checks++; if (checks >= 3) throw CancellationException("stale") }
        try {
            actions(journal).execute("projects", emptyMap(), context(capture))
            fail("Stale capture must cancel the operation")
        } catch (_: CancellationException) { }
        assertEquals(3, checks)
        assertTrue(journal.listProjects().isEmpty())
    }

    @Test fun unsupportedHistoryBusinessToolIsNotAddedToMemoryProtocol() {
        val tool = MemoryTool(mockk(), appContext, actions(journal()))
        assertFalse("history" in MemoryJournalActions.ACTIONS)
        assertFalse("history" in tool.parameters.getValue("action").enum.orEmpty())
        listOf("read", "save", "edit", "delete", "list").forEach { assertTrue(it in tool.parameters.getValue("action").enum.orEmpty()) }
    }
}
