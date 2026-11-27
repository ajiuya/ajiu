package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteConnectionIdentity
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine.Verdict
import com.aicode.feature.agent.domain.root.RootCommandManager
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SessionPermissionSourceTest {
    private class Fixture {
        val app = mockk<Context> {
            every { getString(any()) } answers { "resource:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers { "resource:${firstArg<Int>()}" }
        }
        val grants = SessionPermissionGrantRepository(app)
        val modes = ExecutionModeHolder()
        val selection = MutableStateFlow(WorkspaceRepository.Selection(Workspace("A", "/work/A", WorkspaceType.INTERNAL)))
        val workspaces = mockk<WorkspaceRepository> { every { selection } returns this@Fixture.selection }
        val remoteIdentity = MutableStateFlow<RemoteConnectionIdentity?>(null)
        val remote = mockk<RemoteSshConnection> { every { identityState } returns remoteIdentity }
        var profile = ContainerProfile.BUILTIN_ALPINE
        var profileRevision = 1L
        var configurationRevision = 1L
        var rules = emptyList<PermissionRule>()
        val owner = Any()
        val settings = mockk<ContainerSettingsRepository> {
            coEvery { captureProfileSnapshot() } answers {
                ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), profileRevision, configurationRevision, owner)
            }
            every { checkProfileSnapshot(any()) } returns Unit
        }
        val ruleRepository = mockk<PermissionRulesRepository> { coEvery { loadEffectiveForCurrentProject() } answers { rules } }
        val engine = ToolPermissionPolicyEngine(ruleRepository, grants, app, mockk<RootCommandManager>(),
            mockk<ShizukuManager>(), modes, settings, workspaces, remote)
        suspend fun evaluate(command: String = "sudo id", session: String = "session-A", tool: String = "Bash", elevate: Boolean = false) =
            engine.evaluate(tool, buildMap {
                put("command", JsonPrimitive(command))
                if (tool == "terminal") put("action", JsonPrimitive("start"))
                if (elevate) put("elevate", JsonPrimitive(true))
            }, session)
        suspend fun remember(command: String = "sudo id", elevate: Boolean = false): String {
            val request = evaluate(command, elevate = elevate)
            assertEquals(Verdict.ASK, request.verdict)
            return requireNotNull(request.sessionGrant).also { grants.allow("session-A", it) }
        }
    }

    @Test fun grantAllowsOnlyTheSameSessionToolAndExactCommand() = runTest {
        val f = Fixture()
        f.remember()
        assertEquals(Verdict.ALLOW, f.evaluate().verdict)
        assertEquals(Verdict.ASK, f.evaluate(session = "session-B").verdict)
        assertEquals(Verdict.ASK, f.evaluate(tool = "terminal").verdict)
        for (command in listOf("sudo id ", "sudo  id", "sudo id -u", "sudo id; echo extra")) {
            assertEquals(command, Verdict.ASK, f.evaluate(command).verdict)
        }
    }

    @Test fun workspaceRoundTripCannotReviveAnOldCommandGrant() = runTest {
        val f = Fixture()
        val original = f.remember()
        f.selection.value = WorkspaceRepository.Selection(Workspace("B", "/work/B", WorkspaceType.INTERNAL))
        assertEquals(Verdict.ASK, f.evaluate().verdict)
        f.selection.value = WorkspaceRepository.Selection(Workspace("A", "/work/A", WorkspaceType.INTERNAL))
        val returned = f.evaluate()
        assertEquals(Verdict.ASK, returned.verdict)
        assertNotEquals(original, returned.sessionGrant)
    }

    @Test fun profileSelectionConfigurationAndModeChangesInvalidateExactApprovals() = runTest {
        val f = Fixture()
        var original = f.remember()
        f.profileRevision += 2
        assertNotEquals(original, f.evaluate().sessionGrant)
        original = f.remember()
        f.configurationRevision++
        assertNotEquals(original, f.evaluate().sessionGrant)
        original = f.remember()
        f.modes.setMode(ExecutionMode.REMOTE_SSH)
        f.modes.setMode(ExecutionMode.LOCAL_PROOT)
        assertNotEquals(original, f.evaluate().sessionGrant)
        assertEquals(Verdict.ASK, f.evaluate().verdict)
    }

    @Test fun sshReconnectChangesIdentityEvenForTheSameWorkspaceAndCommand() = runTest {
        val f = Fixture()
        f.modes.setMode(ExecutionMode.REMOTE_SSH)
        f.remoteIdentity.value = RemoteConnectionIdentity(mockk(), "/home/user")
        val old = f.remember()
        assertEquals(Verdict.ALLOW, f.evaluate().verdict)
        f.remoteIdentity.value = null
        assertEquals(Verdict.ASK, f.evaluate().verdict)
        f.remoteIdentity.value = RemoteConnectionIdentity(mockk(), "/home/user")
        assertEquals(Verdict.ASK, f.evaluate().verdict)
        assertNotEquals(old, f.evaluate().sessionGrant)
    }

    @Test fun explicitDenyOverridesRememberedRootAndElevationApprovals() = runTest {
        val f = Fixture()
        f.remember()
        f.remember("rm -rf /etc", elevate = true)
        for (rule in listOf(PermissionRule("Bash", "*", PermissionDecision.DENY),
            PermissionRule("Bash", "id", PermissionDecision.DENY))) {
            f.rules = listOf(rule)
            assertEquals(Verdict.DENY, f.evaluate().verdict)
        }
        f.rules = listOf(PermissionRule("Bash", "rm", PermissionDecision.DENY))
        assertEquals(Verdict.DENY, f.evaluate("rm -rf /etc", elevate = true).verdict)
        assertNull(f.evaluate("rm -rf /etc", elevate = true).sessionGrant)
    }

    @Test fun missingModeProfileWorkspaceOrSshIdentityCannotCreateSessionGrant() = runTest {
        val f = Fixture()
        fun engine(missing: String) = ToolPermissionPolicyEngine(f.ruleRepository, f.grants, f.app, mockk(), mockk(),
            f.modes.takeUnless { missing == "mode" }, f.settings.takeUnless { missing == "profile" },
            f.workspaces.takeUnless { missing == "workspace" }, f.remote.takeUnless { missing == "remote" })
        for (missing in listOf("mode", "profile", "workspace")) {
            val result = engine(missing).evaluate("Bash", mapOf("command" to JsonPrimitive("sudo id")), "session-A")
            assertEquals(missing, Verdict.ASK, result.verdict)
            assertNull(missing, result.sessionGrant)
        }
        for (workspace in listOf(null, Workspace("empty", ""), Workspace("missing", "/work", available = false))) {
            f.selection.value = WorkspaceRepository.Selection(workspace)
            val result = f.evaluate()
            assertEquals(Verdict.ASK, result.verdict)
            assertNull(result.sessionGrant)
        }
        f.selection.value = WorkspaceRepository.Selection(Workspace("work", "/work"))
        f.modes.setMode(ExecutionMode.REMOTE_SSH)
        assertNull(f.evaluate().sessionGrant)
        f.remoteIdentity.value = RemoteConnectionIdentity(mockk(), "/home/user")
        assertNull(engine("remote").evaluate("Bash", mapOf("command" to JsonPrimitive("sudo id")), "session-A").sessionGrant)
        assertNotNull(f.evaluate().sessionGrant)
    }

    @Test fun oldUnboundKeysNeverAuthorizeNewSourceBoundCommands() = runTest {
        val f = Fixture()
        f.grants.allow("session-A", "root:sudo id")
        f.grants.allow("session-A", "elevate:rm -rf /etc")
        assertEquals(Verdict.ASK, f.evaluate().verdict)
        assertEquals(Verdict.ASK, f.evaluate("rm -rf /etc", elevate = true).verdict)
        assertEquals(Verdict.DENY, f.evaluate("rm -rf /etc").verdict)
    }
}
