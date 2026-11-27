package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.data.PluginOperationGrantRepository
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.every
import io.mockk.mockk
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginAlwaysApprovalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val empty = JsonObject(emptyMap())
    private fun manager() = PluginManager(File(temporary.root, "packages"), File(temporary.root, "host/registry.json"))
    private fun file() = File(temporary.root, "operation-grants.enc")
    private fun repository() = PluginOperationGrantRepository(file(),
        { "AICODEENC1:" + Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) },
        { Base64.getDecoder().decode(it.removePrefix("AICODEENC1:")).toString(Charsets.UTF_8) })
    private suspend fun source(manager: PluginManager): PluginCapture {
        PluginCoreFixtures.install(manager)
        return PluginCoreFixtures.activate(manager)
    }
    private fun context(manager: PluginManager, capture: PluginCapture, target: String = "local:workspace-A:profile-A") =
        PluginInvocationContext(capture, null, "", "session-A", manager.dataDirectory(capture.id), emptyFlow(), persistentIdentity = target) {}
    private fun command(value: String) = buildJsonObject { put("command", value) }
    private fun path(value: String, body: String) = buildJsonObject { put("method", "fs.write"); put("path", value); put("bytes", body.length) }

    @Test fun sessionScopeBindsRealChatRunAndCanonicalOperationArguments() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager)
        fun invocation(session: String? = "chat-A") = PluginInvocationContext(capture, null, "", session,
            manager.dataDirectory(capture.id), emptyFlow(), sourceIdentity = "source-A") {}
        val first = invocation()
        val args = buildJsonObject { put("provider", "A"); put("model", "B") }
        val reordered = buildJsonObject { put("model", "B"); put("provider", "A") }
        val expected = first.sessionApprovalScope("model.start", args, "identity-A")
        assertNotNull(expected)
        assertEquals(expected, invocation().sessionApprovalScope("model.start", reordered, "identity-A"))
        assertNotEquals(expected, invocation("chat-B").sessionApprovalScope("model.start", args, "identity-A"))
        assertNotEquals(expected, first.sessionApprovalScope("model.start", command("different"), "identity-A"))
        assertNotEquals(expected, first.sessionApprovalScope("model.start", args, "identity-B"))
        val run = invocation(null)
        val runScope = run.sessionApprovalScope("Bash", command("pwd"), "identity-A")
        assertEquals(runScope, run.child().sessionApprovalScope("Bash", command("pwd"), "identity-A"))
        assertEquals(runScope, run.retainSource().sessionApprovalScope("Bash", command("pwd"), "identity-A"))
        assertNotEquals(runScope, invocation(null).sessionApprovalScope("Bash", command("pwd"), "identity-A"))
        assertNull(context(manager, capture).sessionApprovalScope("Bash", command("pwd"), "identity-A"))
        for (tool in listOf("Root", "submitPlan", "planMode", "askUserQuestion")) {
            assertNull(first.sessionApprovalScope(tool, empty, "identity-A"))
        }
    }

    @Test fun sessionScopeChangesWithChrootRuntimeAuthorityDigest() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager)
        fun invocation(runtimeDigest: String): PluginInvocationContext {
            val runtime = mockk<LocalRuntimeCapture> { every { digest } returns runtimeDigest }
            val execution = mockk<WorkspaceExecutionContext>(relaxed = true) { every { localRuntimeCapture } returns runtime }
            return PluginInvocationContext(capture, execution, "", "chat-A",
                manager.dataDirectory(capture.id), emptyFlow(), sourceIdentity = "source-A") {}
        }
        val args = command("pwd")
        val original = invocation("root-epoch-A").sessionApprovalScope("Bash", args, "identity-A")
        assertEquals(original, invocation("root-epoch-A").sessionApprovalScope("Bash", args, "identity-A"))
        assertNotEquals(original, invocation("root-epoch-B").sessionApprovalScope("Bash", args, "identity-A"))
    }

    @Test fun nativeAlwaysDecisionPersistsAndChecksItsSourceAgain() = runBlocking<Unit> {
        val coordinator = PluginApprovalCoordinator()
        var saved = 0
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.awaitApproval("sample.plugin", "Sample", PluginApprovalOperation.WRITE_FILES, "full target", emptyFlow(), {},
                canAlwaysAllow = true, scopeSummary = "writeFile") { saved++ }
        }
        val request = requireNotNull(coordinator.pending.value)
        assertTrue(request.canAlwaysAllow); assertEquals("writeFile", request.scopeSummary)
        coordinator.resolve(request.id, PluginApprovalChoice.ALWAYS)
        assertTrue(result.await()); assertEquals(1, saved); assertNull(coordinator.pending.value)
    }

    @Test fun onceAndRejectNeverWriteAnAlwaysGrant() = runBlocking<Unit> {
        val coordinator = PluginApprovalCoordinator()
        var saved = 0
        for (choice in listOf(PluginApprovalChoice.ONCE, PluginApprovalChoice.REJECT)) {
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.awaitApproval("sample.plugin", "Sample", PluginApprovalOperation.EXECUTE_TOOL, "target", emptyFlow(), {},
                    canAlwaysAllow = true) { saved++ }
            }
            coordinator.resolve(requireNotNull(coordinator.pending.value).id, choice)
            assertEquals(choice != PluginApprovalChoice.REJECT, result.await())
        }
        assertEquals(0, saved)
    }

    @Test fun protectedApprovalCannotBeTurnedIntoAlwaysThroughANativeCallback() = runBlocking<Unit> {
        val coordinator = PluginApprovalCoordinator()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.awaitApproval("sample.plugin", "Sample", PluginApprovalOperation.EXECUTE_COMMAND, "root", emptyFlow(), {})
        }
        val request = requireNotNull(coordinator.pending.value)
        assertFalse(request.canAlwaysAllow)
        coordinator.resolve(request.id, PluginApprovalChoice.ALWAYS)
        assertFalse(result.isCompleted)
        coordinator.resolve(request.id, false)
        assertFalse(result.await())
    }

    @Test fun cancellationAndFailedPersistenceDoNotLeaveAResolvedRequest() = runBlocking<Unit> {
        val coordinator = PluginApprovalCoordinator()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.awaitApproval("sample.plugin", "Sample", PluginApprovalOperation.EXECUTE_TOOL, "target", emptyFlow(), {},
                canAlwaysAllow = true) { throw IllegalStateException("storage unavailable") }
        }
        pending.cancelAndJoin(); assertNull(coordinator.pending.value)
    }

    @Test fun rememberedCommandRequiresTheSameCompleteCommandAndEnvironment() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager); val repository = repository(); val original = context(manager, capture)
        repository.remember(original, "Bash", command("git status"))
        assertTrue(repository.isAllowed(context(manager, capture), "Bash", command("git status")))
        assertFalse(repository.isAllowed(original, "Bash", command("git status; git clean -fd")))
        assertFalse(repository.isAllowed(context(manager, capture, "ssh:server-B:workspace-A"), "Bash", command("git status")))
        assertFalse(repository.isAllowed(original, "Shizuku", command("git status")))
    }

    @Test fun fileGrantCoversOnlyTheSameMethodAndConfirmedPath() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager); val repository = repository(); val context = context(manager, capture)
        repository.remember(context, "writeFile", path("~/workspace/result.txt", "first"))
        assertTrue(repository.isAllowed(context, "writeFile", path("~/workspace/result.txt", "new longer content")))
        assertFalse(repository.isAllowed(context, "writeFile", path("~/workspace/other.txt", "first")))
        assertFalse(repository.isAllowed(context, "writeFile", buildJsonObject { put("method", "fs.delete"); put("path", "~/workspace/result.txt") }))
    }

    @Test fun rememberedOperationsReloadWithoutStoringRawCommandOrValues() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager); val context = context(manager, capture)
        repository().remember(context, "Bash", command("do-safe-work private-command-argument"))
        val text = file().readText()
        assertFalse(text.contains("private-command-argument")); assertFalse(text.contains("do-safe-work"))
        assertTrue(text.startsWith("AICODEENC1:"))
        assertTrue(repository().isAllowed(context, "Bash", command("do-safe-work private-command-argument")))
    }

    @Test fun disableThenEnableCannotReviveAnOldAlwaysGrant() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager)
        repository().remember(context(manager, capture), "Bash", command("pwd"))
        manager.disable(capture.id)
        val again = PluginCoreFixtures.activate(manager)
        assertFalse(repository().isAllowed(context(manager, again), "Bash", command("pwd")))
    }

    @Test fun sameVersionReinstallationInvalidatesRememberedOperations() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager)
        repository().remember(context(manager, capture), "Bash", command("pwd"))
        PluginCoreFixtures.install(manager)
        val replacement = PluginCoreFixtures.activate(manager)
        assertNotEquals(capture.installRevision, replacement.installRevision)
        assertFalse(repository().isAllowed(context(manager, replacement), "Bash", command("pwd")))
    }

    @Test fun revokeRemovesOnlyTheNamedPluginsOperations() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager); val context = context(manager, capture)
        val repository = repository(); repository.remember(context, "Bash", command("pwd"))
        repository.revoke("other.plugin")
        assertTrue(repository.isAllowed(context, "Bash", command("pwd")))
        repository.revoke(capture.id)
        assertFalse(repository.isAllowed(context, "Bash", command("pwd")))
        assertFalse(repository().isAllowed(context, "Bash", command("pwd")))
    }

    @Test fun rootAndPlanDecisionsCannotBeRemembered() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager); val context = context(manager, capture)
        val repository = repository()
        for (method in listOf("Root", "submitPlan")) {
            assertFalse(repository.canRemember(context, method))
            assertFalse(repository.isAllowed(context, method, empty))
            try { repository.remember(context, method, empty); fail("Protected operation") }
            catch (expected: PluginHostException) { assertEquals("SYSTEM_PROTECTED", expected.code) }
        }
    }

    @Test fun objectKeyOrderingDoesNotChangeAnOtherwiseIdenticalDecision() = runBlocking<Unit> {
        val manager = manager(); val capture = source(manager); val context = context(manager, capture); val repository = repository()
        repository.remember(context, "model.start", buildJsonObject { put("provider", "A"); put("model", "B") })
        assertTrue(repository.isAllowed(context, "model.start", buildJsonObject { put("model", "B"); put("provider", "A") }))
        assertFalse(repository.isAllowed(context, "model.start", buildJsonObject { put("provider", "A"); put("model", "C") }))
    }
}
