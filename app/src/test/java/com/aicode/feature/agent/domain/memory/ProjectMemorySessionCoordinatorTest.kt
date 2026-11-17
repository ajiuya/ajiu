package com.aicode.feature.agent.domain.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMemorySessionCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = ProjectMemoryIdentity("remote_ssh", "fixture.invalid", 22, "alice", "/work")
    private fun journal() = ProjectMemoryJournal(File(temporary.root, "journal"))
    private fun coordinator(journal: ProjectMemoryJournal) = ProjectMemorySessionCoordinator(journal, ProjectMemoryResumeResolver(journal))

    @Test fun existingUnboundSessionIsNotSilentlyBoundToTheOnlyRegisteredProject() {
        val journal = journal()
        journal.registerProject(identity, "Demo")
        journal.rememberSessionIdentity("old", identity)
        val selection = coordinator(journal).resolve("old", identity, "fix a bug", true)
        assertNull(selection.projectId)
        assertNull(selection.context)
        assertTrue(selection.allowUnboundDiscovery)
        assertFalse(journal.hasSessionProjectBinding("old", identity))
    }

    @Test fun explicitNullSessionBindingDisablesImplicitDiscoveryAndInheritance() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        journal.bindSessionProject("old", identity, null)
        val selection = coordinator(journal).resolve("old", identity, "next", true, inheritedProjectId = project.id)
        assertNull(selection.projectId)
        assertNull(selection.context)
        assertFalse(selection.allowUnboundDiscovery)
    }

    @Test fun workspaceExplicitNullWinsOverParentSessionWhenCreatingAChat() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        journal.bindSessionProject("parent", identity, project.id)
        journal.setWorkspaceProject(identity, null)
        coordinator(journal).initializeNewSession("new", identity, "parent")
        assertTrue(journal.hasSessionProjectBinding("new", identity))
        assertNull(journal.sessionProject("new", identity))
    }

    @Test fun newSessionSelectionIsFrozenBeforeWorkspaceBindingChanges() {
        val journal = journal()
        val first = journal.registerProject(identity.copy(projectRoot = "/work/one"), "One")
        val second = journal.registerProject(identity.copy(projectRoot = "/work/two"), "Two")
        journal.setWorkspaceProject(identity, first.id)
        val coordinator = coordinator(journal)
        val frozen = coordinator.selectNewSession(identity)
        journal.setWorkspaceProject(identity, second.id)
        val selection = coordinator.resolve("new", identity, "work", true, newSessionSelection = frozen)
        assertEquals(first.id, selection.projectId)
        assertEquals(first, journal.sessionProject("new", identity))
    }

    @Test fun explicitResumeResolvesUniqueCurrentEnvironmentProjectAndPersistsBinding() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        journal.append(project.id, "Last report", "Files changed; tests unverified")
        val selection = coordinator(journal).resolve("old", identity, "继续Demo项目", true)
        assertEquals(project.id, selection.projectId)
        assertTrue(selection.context!!.contains("Last report"))
        assertFalse(selection.allowUnboundDiscovery)
        assertEquals(project, journal.sessionProject("old", identity))
    }

    @Test fun ambiguousExplicitResumeDoesNotReusePreviousBinding() {
        val journal = journal()
        val first = journal.registerProject(identity.copy(projectRoot = "/work/one"), "Demo")
        journal.registerProject(identity.copy(projectRoot = "/work/two"), "Demo")
        journal.bindSessionProject("old", identity, first.id)
        val selection = coordinator(journal).resolve("old", identity, "resume Demo", true)
        assertNull(selection.projectId)
        assertTrue(selection.context!!.contains("candidates"))
        assertFalse(selection.allowUnboundDiscovery)
        assertEquals(first, journal.sessionProject("old", identity))
    }

    @Test fun backgroundNotificationCannotReinterpretResumeTextAsAUserBindingRequest() {
        val journal = journal()
        val first = journal.registerProject(identity.copy(projectRoot = "/work/one"), "One")
        journal.registerProject(identity.copy(projectRoot = "/work/two"), "Two")
        journal.bindSessionProject("session", identity, first.id)
        assertEquals(first.id, coordinator(journal).resolve("session", identity, "resume Two", false).projectId)
    }

    @Test fun readOnlyResolutionDoesNotWriteSessionBinding() {
        val journal = journal()
        val project = journal.registerProject(identity, "Demo")
        val selection = coordinator(journal).resolve("session", identity, "resume Demo", true, persistBinding = false)
        assertEquals(project.id, selection.projectId)
        assertFalse(journal.hasSessionProjectBinding("session", identity))
    }
}
