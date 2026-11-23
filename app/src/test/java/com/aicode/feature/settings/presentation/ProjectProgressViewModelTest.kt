package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModelStore
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryEntry
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.agent.domain.memory.ProjectMemoryJournal
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectProgressViewModelTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val current = MutableStateFlow<Workspace?>(Workspace("first", "/first"))
    private val environmentChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val workspace = mockk<WorkspaceRepository>()
    private val resolver = mockk<ProjectMemoryIdentityResolver>()
    private val store = ViewModelStore()
    private var endpoint = "profile-1"
    private var transportGeneration = 1
    private lateinit var journal: ProjectMemoryJournal
    private lateinit var viewModel: ProjectProgressViewModel
    private val identity get() = ProjectMemoryIdentity("local_proot", projectRoot = "/first", profileId = endpoint)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { workspace.current } returns current
        every { resolver.environmentChanges } returns environmentChanges
        coEvery { resolver.capture(any(), any(), any()) } coAnswers {
            val root = firstArg<String>()
            val frozenEndpoint = endpoint
            val frozenGeneration = transportGeneration
            ProjectMemoryCapture(ProjectMemoryIdentity("local_proot", projectRoot = root, profileId = frozenEndpoint)) {
                if (endpoint != frozenEndpoint || transportGeneration != frozenGeneration) throw CancellationException("environment changed")
            }
        }
        journal = spyk(ProjectMemoryJournal(temporaryFolder.newFolder("journal")))
        viewModel = ProjectProgressViewModel(journal, workspace, resolver, dispatcher)
        store.put("project-progress", viewModel)
    }

    @After fun tearDown() {
        viewModel.close()
        store.clear()
        settle()
        Dispatchers.resetMain()
    }

    @Test fun noWorkspaceDoesNotScanOrCreateJournalData() {
        current.value = null
        viewModel.open("session"); settle()
        assertNull(viewModel.state.value.workspaceRoot)
        assertFalse(viewModel.state.value.loading)
        viewModel.startRegister()
        viewModel.register("project")
        settle()
        verify(exactly = 0) { journal.listProjects(any()) }
        verify(exactly = 0) { journal.registerProject(any(), any(), any(), any()) }
    }

    @Test fun projectListIsFrozenToTheCurrentEndpointAndSelectionDoesNotBind() {
        val project = journal.registerProject(identity, "first")
        val sibling = journal.registerProject(identity.copy(projectRoot = "/sibling"), "sibling")
        journal.registerProject(identity.copy(profileId = "other-profile"), "private-other")
        viewModel.open("session"); settle()
        assertEquals(setOf(project.id, sibling.id), viewModel.state.value.projects.map { it.id }.toSet())
        assertNull(viewModel.state.value.selectedProjectId)
        assertEquals(project, viewModel.state.value.currentWorkspaceProject)
        assertTrue(viewModel.state.value.projectListReady)
        viewModel.selectProject(sibling); settle()
        assertFalse(viewModel.state.value.canBindSelected)
        viewModel.bindSelected(); settle()
        assertFalse(journal.hasSessionProjectBinding("session", identity))
        viewModel.selectProject(project); settle()
        assertTrue(viewModel.state.value.canBindSelected)
        assertFalse(journal.hasSessionProjectBinding("session", identity))
    }

    @Test fun registerUsesOnlyTheFrozenRootAndBindsTheFrozenSession() {
        viewModel.open("session"); settle()
        viewModel.startRegister()
        viewModel.register("new-project"); settle()
        val project = journal.listProjects().single()
        assertEquals(identity, project.identity)
        assertEquals(project.id, journal.sessionProject("session", identity)?.id)
        assertFalse(journal.hasWorkspaceProjectBinding(identity))
        assertEquals(project.id, viewModel.state.value.selectedProjectId)
        assertNull(viewModel.state.value.form)
    }

    @Test fun existingWorkspaceProjectCannotBeRegisteredAgainOrImplicitlyBound() {
        journal.registerProject(identity, "original")
        viewModel.open(null); settle()
        viewModel.startRegister()
        viewModel.register("replacement"); settle()
        assertNull(viewModel.state.value.form)
        assertEquals("original", journal.listProjects().single().name)
        assertNull(journal.workspaceProject(identity))
        verify(exactly = 0) { journal.bindSessionProject(any(), any(), any()) }
    }

    @Test fun explicitNullSessionBindingDoesNotFallBackToWorkspaceOrAnotherProject() {
        val project = journal.registerProject(identity, "first")
        journal.setWorkspaceProject(identity, project.id)
        journal.bindSessionProject("session", identity, project.id)
        viewModel.open("session"); settle()
        viewModel.unbind(); settle()
        assertTrue(journal.hasSessionProjectBinding("session", identity))
        assertNull(journal.sessionProject("session", identity))
        assertEquals(project.id, journal.workspaceProject(identity)?.id)
        assertEquals(true, viewModel.state.value.associations?.sessionBindingPresent)
        assertNull(viewModel.state.value.associations?.sessionProject)
        assertNull(viewModel.state.value.selectedProjectId)
    }

    @Test fun explicitNullWorkspaceDefaultDoesNotBindASession() {
        val project = journal.registerProject(identity, "first")
        journal.setWorkspaceProject(identity, project.id)
        viewModel.open(null); settle()
        viewModel.unbind(); settle()
        assertTrue(journal.hasWorkspaceProjectBinding(identity))
        assertNull(journal.workspaceProject(identity))
        verify(exactly = 0) { journal.bindSessionProject(any(), any(), any()) }
    }

    @Test fun autoCaptureSwitchPersistsOnlySelectedProject() {
        val first = journal.registerProject(identity, "first")
        val second = journal.registerProject(identity.copy(projectRoot = "/first/child"), "child")
        viewModel.open("session"); settle()
        viewModel.selectProject(second); settle()
        viewModel.setAutoProgressEnabled(false); settle()
        assertTrue(journal.listProjects().single { it.id == first.id }.autoProgressEnabled)
        assertFalse(journal.listProjects().single { it.id == second.id }.autoProgressEnabled)
        assertFalse(viewModel.state.value.selectedProject!!.autoProgressEnabled)
    }

    @Test fun manualHandoffUsesRealAppendAndKeepsBodyAndSessionProvenance() {
        val project = journal.registerProject(identity, "first")
        val body = "Literal history\n\n**not executed**\nRoot, Todo and permissions remain unchanged"
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        viewModel.startHandoff()
        viewModel.saveHandoff("handoff summary", body); settle()
        val entry = journal.listRecent(project.id).single()
        assertEquals("handoff", entry.kind)
        assertEquals("recorded", entry.status)
        assertEquals(body, entry.body)
        assertEquals("session", entry.sourceSessionId)
        assertEquals(listOf(entry.id), viewModel.state.value.entries.map { it.id })
        assertNull(viewModel.state.value.form)
    }

    @Test fun invalidInputAndSensitiveRawFailureKeepTheDraftOpenWithTypedErrors() {
        val project = journal.registerProject(identity, "first")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        viewModel.startHandoff()
        viewModel.saveHandoff("", "body"); settle()
        assertEquals(ProjectProgressError.INVALID_INPUT, viewModel.state.value.error)
        every { journal.append(any(), any(), any(), any(), any(), any(), any()) } throws IOException("credential=do-not-display")
        viewModel.saveHandoff("summary", "body"); settle()
        assertEquals(ProjectProgressError.SAVE_FAILED, viewModel.state.value.error)
        assertEquals(ProjectProgressForm.HANDOFF, viewModel.state.value.form)
        assertFalse(viewModel.state.value.busy)
        assertFalse(viewModel.state.value.toString().contains("do-not-display"))
        assertTrue(journal.listRecent(project.id).isEmpty())
    }

    @Test fun registrationSuccessFollowedByBindingFailureIsNotReportedAsFullSuccess() {
        every { journal.bindSessionProject(any(), any(), any()) } throws IOException("private transport detail")
        viewModel.open("session"); settle()
        viewModel.startRegister()
        viewModel.register("new-project"); settle()
        val registered = journal.listProjects().single()
        assertEquals(ProjectProgressError.REGISTERED_BINDING_FAILED, viewModel.state.value.error)
        assertNull(viewModel.state.value.form)
        assertEquals(registered.id, viewModel.state.value.selectedProjectId)
        assertEquals(registered, viewModel.state.value.currentWorkspaceProject)
        assertTrue(viewModel.state.value.projectListReady)
        assertFalse(viewModel.state.value.busy)
    }

    @Test fun unknownCapacityDoesNotBecomeAFakeZeroOrHideValidProjects() {
        val project = journal.registerProject(identity, "first")
        every { journal.capacity() } throws IOException("private storage detail")
        viewModel.open("session"); settle()
        assertEquals(listOf(project), viewModel.state.value.projects)
        assertNull(viewModel.state.value.capacity)
        assertNull(viewModel.state.value.error)
    }

    @Test fun failedReadIsNotReportedAsEmptySuccessfulHistory() {
        every { journal.listProjects(any()) } throws IOException("private storage detail")
        viewModel.open("session"); settle()
        assertEquals(ProjectProgressError.READ_FAILED, viewModel.state.value.error)
        assertFalse(viewModel.state.value.loading)
        assertFalse(viewModel.state.value.projectListReady)
        assertNull(viewModel.state.value.currentWorkspaceProject)
        viewModel.startRegister()
        viewModel.register("unverified-project")
        settle()
        assertNull(viewModel.state.value.form)
        verify(exactly = 0) { journal.registerProject(any(), any(), any(), any()) }
        assertFalse(viewModel.state.value.toString().contains("private storage detail"))
    }

    @Test fun failedRefreshDoesNotOfferRegistrationBasedOnThePreviousProjectList() {
        journal.registerProject(identity, "original")
        viewModel.open("session"); settle()
        assertEquals(1, viewModel.state.value.projects.size)
        every { journal.listProjects(any()) } throws IOException("private storage detail")
        viewModel.refresh(); settle()
        assertEquals(1, viewModel.state.value.projects.size)
        assertFalse(viewModel.state.value.projectListReady)
        viewModel.startRegister()
        assertNull(viewModel.state.value.form)
    }

    @Test fun revokeRequiresConfirmationAndUsesTheFullReadRevision() {
        val project = journal.registerProject(identity, "first")
        val entry = journal.append(project.id, "summary", "complete body")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        val fresh = journal.edit(entry.id, entry.revision, "fresh summary", "fresh body")
        viewModel.openEntry(viewModel.state.value.entries.single()); settle()
        assertEquals(fresh.revision, viewModel.state.value.detail?.revision)
        viewModel.requestEntryAction(ProjectProgressEntryAction.REVOKE); settle()
        assertFalse(journal.read(entry.id)!!.revoked)
        viewModel.confirmEntryAction(); settle()
        assertTrue(journal.read(entry.id)!!.revoked)
        verify(exactly = 1) { journal.revoke(entry.id, fresh.revision) }
        assertTrue(viewModel.state.value.entries.single().revoked)
    }

    @Test fun archiveRequiresConfirmationAndArchivedHistoryRemainsReadable() {
        val project = journal.registerProject(identity, "first")
        val entry = journal.append(project.id, "summary", "complete body")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        viewModel.openEntry(viewModel.state.value.entries.single()); settle()
        viewModel.requestEntryAction(ProjectProgressEntryAction.ARCHIVE); settle()
        assertFalse(journal.read(entry.id)!!.archived)
        viewModel.dismissConfirmation()
        viewModel.confirmEntryAction(); settle()
        assertFalse(journal.read(entry.id)!!.archived)
        viewModel.requestEntryAction(ProjectProgressEntryAction.ARCHIVE)
        viewModel.confirmEntryAction(); settle()
        assertTrue(journal.read(entry.id)!!.archived)
        viewModel.closeEntry()
        viewModel.showArchive(true); settle()
        assertEquals(listOf(entry.id), viewModel.state.value.entries.map { it.id })
        viewModel.openEntry(viewModel.state.value.entries.single()); settle()
        assertEquals("complete body", viewModel.state.value.detail?.body)
    }

    @Test fun staleRevisionNeverRevokesANewerRecord() {
        val project = journal.registerProject(identity, "first")
        val entry = journal.append(project.id, "summary", "body")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        viewModel.openEntry(viewModel.state.value.entries.single()); settle()
        viewModel.requestEntryAction(ProjectProgressEntryAction.REVOKE)
        journal.edit(entry.id, entry.revision, "changed", "changed body")
        viewModel.confirmEntryAction(); settle()
        assertFalse(journal.read(entry.id)!!.revoked)
        assertEquals(ProjectProgressError.SAVE_FAILED, viewModel.state.value.error)
        assertNull(viewModel.state.value.confirmation)
    }

    @Test fun workspaceSwitchBeforeQueuedWritePreventsRegistrationAndRequiresExplicitReload() {
        viewModel.open("session"); settle()
        viewModel.startRegister()
        viewModel.register("must-not-register")
        current.value = Workspace("second", "/second")
        settle()
        assertTrue(viewModel.state.value.targetChanged)
        assertTrue(journal.listProjects().isEmpty())
        viewModel.startRegister()
        assertNull(viewModel.state.value.form)
        viewModel.open("session"); settle()
        assertFalse(viewModel.state.value.targetChanged)
        assertEquals("/second", viewModel.state.value.workspaceRoot)
    }

    @Test fun samePathOnAnotherEndpointOrTransportGenerationPreventsWrites() {
        repeat(2) { change ->
            viewModel.open("session"); settle()
            viewModel.startRegister()
            if (change == 0) {
                endpoint = "profile-2"
                assertTrue(environmentChanges.tryEmit(Unit))
                settle()
                assertTrue(viewModel.state.value.targetChanged)
                assertNull(viewModel.state.value.form)
            } else transportGeneration++
            viewModel.register("must-not-register"); settle()
            assertTrue(viewModel.state.value.targetChanged)
            assertTrue(journal.listProjects().isEmpty())
        }
    }

    @Test fun workspaceSwitchDuringRegistrationDoesNotBindOrAcceptLateSuccess() {
        every { journal.registerProject(any(), any(), any(), any()) } answers {
            val result = callOriginal()
            current.value = Workspace("second", "/second")
            result
        }
        viewModel.open("session"); settle()
        viewModel.startRegister()
        viewModel.register("first-only"); settle()
        assertTrue(viewModel.state.value.targetChanged)
        assertNull(viewModel.state.value.selectedProjectId)
        verify(exactly = 0) { journal.bindSessionProject(any(), any(), any()) }
        assertEquals("/first", journal.listProjects().single().identity.projectRoot)
    }

    @Test fun oldCaptureCannotPopulateTheReopenedSession() {
        val deferred = CompletableDeferred<ProjectMemoryCapture?>()
        var captures = 0
        coEvery { resolver.capture(any(), any(), any()) } coAnswers {
            if (++captures == 1) deferred.await() else ProjectMemoryCapture(identity) {}
        }
        viewModel.open("old-session"); settle()
        viewModel.open("new-session"); settle()
        deferred.complete(ProjectMemoryCapture(identity) {})
        settle()
        assertEquals("new-session", viewModel.state.value.sessionId)
        assertFalse(viewModel.state.value.loading)
        val project = journal.registerProject(identity, "first")
        viewModel.refresh(); settle()
        viewModel.selectProject(project); settle()
        viewModel.bindSelected(); settle()
        assertFalse(journal.hasSessionProjectBinding("old-session", identity))
        assertEquals(project.id, journal.sessionProject("new-session", identity)?.id)
    }

    @Test fun lateHistoryReadAndClosedDetailCannotPopulateAnotherSelection() {
        val first = journal.registerProject(identity, "first")
        val second = journal.registerProject(identity.copy(projectRoot = "/first/child"), "second")
        journal.append(first.id, "first", "first body")
        val secondEntry = journal.append(second.id, "second", "second body")
        viewModel.open("session"); settle()
        viewModel.selectProject(first)
        viewModel.selectProject(second)
        settle()
        assertEquals(listOf(secondEntry.id), viewModel.state.value.entries.map(ProjectMemoryEntry::id))
        viewModel.openEntry(viewModel.state.value.entries.single())
        viewModel.closeEntry()
        settle()
        assertNull(viewModel.state.value.detail)
        assertNull(viewModel.state.value.selectedEntryId)
    }

    @Test fun truncatedListEntryOpensTheCompleteBodyAndLoadsFurtherPages() {
        val project = journal.registerProject(identity, "first")
        repeat(12) { index -> journal.append(project.id, "entry-$index", "x".repeat(8192)) }
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        assertTrue(viewModel.state.value.entries.any { it.truncated })
        val preview = viewModel.state.value.entries.first { it.truncated }
        viewModel.openEntry(preview); settle()
        assertEquals(8192, viewModel.state.value.detail?.body?.length)
        assertFalse(viewModel.state.value.detail!!.truncated)
        viewModel.closeEntry()
        viewModel.loadMore(); settle()
        assertEquals(12, viewModel.state.value.entries.size)
        assertEquals(12, viewModel.state.value.entries.map { it.id }.distinct().size)
    }

    @Test fun latestHandoffOutsideTheFirstPageOpensTheFullEntryIndependentlyOfTheFilter() {
        val project = journal.registerProject(identity, "first")
        val handoff = journal.handoff(project.id, "Current handoff", "complete handoff body")
        repeat(24) { journal.upsertTurn(project.id, "turn-$it", "Turn $it", "body", "completed") }
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        assertEquals(20, viewModel.state.value.entries.size)
        assertFalse(viewModel.state.value.entries.any { it.id == handoff.id })
        assertEquals(handoff, viewModel.state.value.latestHandoff)
        verify { journal.resumeRecords(project.id, 0) }
        viewModel.setFilter(ProjectProgressFilter.AUTO_PROGRESS); settle()
        viewModel.openEntry(handoff); settle()
        assertEquals(handoff, viewModel.state.value.detail)
        viewModel.closeEntry()
        val hiddenNote = journal.append(project.id, "Hidden note", "not in loaded history")
        viewModel.openEntry(hiddenNote); settle()
        assertNull(viewModel.state.value.selectedEntryId)
        assertNull(viewModel.state.value.detail)
    }

    @Test fun archivedLatestHandoffStaysVisibleAndRevocationFallsBackByCreationTime() {
        val project = journal.registerProject(identity, "first")
        val old = journal.handoff(project.id, "Older handoff", "older body")
        val latest = journal.handoff(project.id, "Latest handoff", "latest body")
        val editedOld = journal.edit(old.id, old.revision, "Edited older handoff", "edited old body")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        assertEquals(latest.id, viewModel.state.value.latestHandoff?.id)
        viewModel.openEntry(latest); settle()
        viewModel.requestEntryAction(ProjectProgressEntryAction.ARCHIVE)
        viewModel.confirmEntryAction(); settle()
        assertTrue(viewModel.state.value.latestHandoff!!.archived)
        assertFalse(viewModel.state.value.entries.any { it.id == latest.id })
        viewModel.closeEntry()
        viewModel.openEntry(viewModel.state.value.latestHandoff!!); settle()
        viewModel.requestEntryAction(ProjectProgressEntryAction.REVOKE)
        viewModel.confirmEntryAction(); settle()
        assertEquals(editedOld, viewModel.state.value.latestHandoff)
        viewModel.closeEntry()
        viewModel.openEntry(editedOld); settle()
        viewModel.requestEntryAction(ProjectProgressEntryAction.REVOKE)
        viewModel.confirmEntryAction(); settle()
        assertNull(viewModel.state.value.latestHandoff)
    }

    @Test fun filtersPageOverMatchingKindsAndResetTheRawOffsetOnCategoryAndArchiveChanges() {
        val project = journal.registerProject(identity, "first")
        val handoffs = (1..23).map { journal.handoff(project.id, "Handoff $it", "body") }
        val archived = journal.archive(handoffs.first().id, handoffs.first().revision)
        repeat(24) { journal.upsertTurn(project.id, "turn-$it", "Turn $it", "x".repeat(8192), "completed") }
        val note = journal.append(project.id, "Note", "body")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        assertTrue(viewModel.state.value.entries.any { it.id == note.id })
        assertTrue(viewModel.state.value.entries.size < 20)
        assertTrue(viewModel.state.value.hasMore)
        viewModel.setFilter(ProjectProgressFilter.HANDOFF); settle()
        assertEquals(20, viewModel.state.value.entries.size)
        assertEquals(20, viewModel.state.value.historyOffset)
        viewModel.loadMore(); settle()
        assertEquals(22, viewModel.state.value.entries.size)
        assertEquals(22, viewModel.state.value.historyOffset)
        assertTrue(viewModel.state.value.hasMore)
        viewModel.loadMore(); settle()
        assertFalse(viewModel.state.value.hasMore)
        assertTrue(viewModel.state.value.entries.all { it.kind == "handoff" })
        viewModel.showArchive(true); settle()
        assertEquals(ProjectProgressFilter.HANDOFF, viewModel.state.value.filter)
        assertEquals(listOf(archived), viewModel.state.value.entries)
        assertEquals(1, viewModel.state.value.historyOffset)
        viewModel.setFilter(ProjectProgressFilter.AUTO_PROGRESS); settle()
        assertTrue(viewModel.state.value.entries.isEmpty())
        assertEquals(0, viewModel.state.value.historyOffset)
        assertFalse(viewModel.state.value.hasMore)
        viewModel.showArchive(false); settle()
        repeat(5) { if (viewModel.state.value.hasMore) { viewModel.loadMore(); settle() } }
        assertEquals(24, viewModel.state.value.entries.size)
        assertEquals(24, viewModel.state.value.historyOffset)
        assertTrue(viewModel.state.value.entries.all { it.kind == "turn" })
        assertFalse(viewModel.state.value.hasMore)
        assertEquals(handoffs.last().id, viewModel.state.value.latestHandoff?.id)
    }

    @Test fun rawPageOffsetAdvancesEvenWhenAChangedJournalReturnsADuplicate() {
        val project = journal.registerProject(identity, "first")
        repeat(21) { journal.append(project.id, "Note $it", "body") }
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        assertEquals(20, viewModel.state.value.historyOffset)
        journal.append(project.id, "New note before next page", "body")
        viewModel.loadMore(); settle()
        assertEquals(21, viewModel.state.value.entries.size)
        assertEquals(22, viewModel.state.value.historyOffset)
        viewModel.loadMore(); settle()
        verify { journal.listRecent(project.id, 20, true, 65536, 22, null) }
        assertFalse(viewModel.state.value.hasMore)
        assertEquals(22, viewModel.state.value.historyOffset)
    }

    @Test fun revokedTurnsRemainInTheAutoFilterAndDoNotUseTheDefaultActiveCount() {
        val project = journal.registerProject(identity, "first")
        val turn = journal.upsertTurn(project.id, "turn", "Turn", "body", "completed")!!
        val revoked = journal.revoke(turn.id, turn.revision)
        assertEquals(0, journal.entryCount(project.id))
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        viewModel.setFilter(ProjectProgressFilter.AUTO_PROGRESS); settle()
        assertEquals(listOf(revoked), viewModel.state.value.entries)
        assertTrue(viewModel.state.value.hasMore)
        viewModel.loadMore(); settle()
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test fun lateAllHistoryCannotPopulateTheHandoffFilter() {
        val project = journal.registerProject(identity, "first")
        val handoff = journal.handoff(project.id, "Handoff", "body")
        journal.upsertTurn(project.id, "turn", "Turn", "body", "completed")
        every { journal.listRecent(project.id, any(), any(), any(), any(), null) } answers {
            val result = callOriginal()
            viewModel.setFilter(ProjectProgressFilter.HANDOFF)
            result
        }
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        assertEquals(ProjectProgressFilter.HANDOFF, viewModel.state.value.filter)
        assertEquals(listOf(handoff), viewModel.state.value.entries)
        assertEquals(1, viewModel.state.value.historyOffset)
        assertFalse(viewModel.state.value.historyLoading)
    }

    @Test fun lateArchivedPageCannotReplaceTheNewActiveSelection() {
        val project = journal.registerProject(identity, "first")
        val active = journal.handoff(project.id, "Active handoff", "body")
        journal.handoff(project.id, "Archived handoff", "body").let { journal.archive(it.id, it.revision) }
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        every { journal.listArchive(project.id, any(), any(), any(), any(), any()) } answers {
            val result = callOriginal()
            viewModel.showArchive(false)
            result
        }
        viewModel.showArchive(true); settle()
        assertFalse(viewModel.state.value.showingArchive)
        assertEquals(listOf(active), viewModel.state.value.entries)
        assertEquals(1, viewModel.state.value.historyOffset)
    }

    @Test fun environmentSwitchDuringAFilteredPageReadDiscardsTheResultAndRequiresReload() {
        val project = journal.registerProject(identity, "first")
        journal.handoff(project.id, "Handoff", "body")
        journal.upsertTurn(project.id, "turn", "Turn", "body", "completed")
        viewModel.open("session"); settle()
        viewModel.selectProject(project); settle()
        every { journal.listRecent(project.id, any(), any(), any(), any(), "turn") } answers {
            val result = callOriginal()
            transportGeneration++
            result
        }
        viewModel.setFilter(ProjectProgressFilter.AUTO_PROGRESS); settle()
        assertTrue(viewModel.state.value.targetChanged)
        assertTrue(viewModel.state.value.entries.isEmpty())
        assertNull(viewModel.state.value.latestHandoff)
        assertNull(viewModel.state.value.selectedProjectId)
        assertEquals(0, viewModel.state.value.historyOffset)
        assertFalse(viewModel.state.value.historyLoading)
        viewModel.loadMore(); settle()
        assertTrue(viewModel.state.value.entries.isEmpty())
    }

    @Test fun lateHandoffAndHistoryFromAnotherEnvironmentCannotPopulateTheReopenedPage() {
        val first = journal.registerProject(identity, "first")
        journal.handoff(first.id, "Old environment handoff", "body")
        val second = journal.registerProject(identity.copy(profileId = "profile-2"), "second")
        val secondHandoff = journal.handoff(second.id, "New environment handoff", "body")
        every { journal.resumeRecords(first.id, 0) } answers {
            val result = callOriginal()
            endpoint = "profile-2"
            viewModel.open("new-session")
            result
        }
        viewModel.open("old-session"); settle()
        viewModel.selectProject(first); settle()
        assertEquals("new-session", viewModel.state.value.sessionId)
        assertEquals(listOf(second), viewModel.state.value.projects)
        assertNull(viewModel.state.value.latestHandoff)
        assertTrue(viewModel.state.value.entries.isEmpty())
        assertFalse(viewModel.state.value.targetChanged)
        viewModel.selectProject(second); settle()
        assertEquals(secondHandoff, viewModel.state.value.latestHandoff)
        assertEquals(listOf(secondHandoff), viewModel.state.value.entries)
    }

    private fun settle() = dispatcher.scheduler.runCurrent()
}
