package com.aicode.feature.plugin.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PluginSessionApprovalTest {
    private val scope = PluginSessionApprovalScope("sample.plugin", "install-A", "content-A", 1,
        "chat-A", null, "source-A", "command-A", "identity-A")

    private suspend fun request(coordinator: PluginApprovalCoordinator, scope: PluginSessionApprovalScope?,
        check: () -> Unit = {}, validate: suspend () -> Unit = {}, remember: suspend () -> Unit = {}) =
        coordinator.awaitApproval("sample.plugin", "Sample", PluginApprovalOperation.EXECUTE_COMMAND,
            "full command", emptyFlow(), check, canAlwaysAllow = true, sourceIdentity = scope?.sourceIdentity,
            sessionScope = scope, validateSession = validate, remember = remember)

    @Test fun equalLengthPrivateContentsAndHeaderValuesRequireNewSessionApproval() = runTest {
        val cases = listOf(
            Triple("writeFile", buildJsonObject { put("path", "data://result"); put("bytes", 5) }, "content"),
            Triple("clipboard.write", buildJsonObject { put("characters", 5) }, "text"),
            Triple("http.request", buildJsonObject { put("url", "https://example.com/"); put("bytes", 5) }, "body")
        )
        for ((tool, summary, field) in cases) {
            val coordinator = PluginApprovalCoordinator()
            val firstArgs = buildJsonObject { put(field, "alpha") }
            val changedArgs = buildJsonObject { put(field, "bravo") }
            val approved = scope.copy(target = pluginSessionOperationFingerprint(tool, summary, firstArgs))
            val first = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, approved) }
            coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.ALWAYS_SESSION)
            assertTrue(first.await())
            assertTrue(request(coordinator, scope.copy(target = pluginSessionOperationFingerprint(tool, summary, firstArgs))))
            val changed = async(start = CoroutineStart.UNDISPATCHED) {
                request(coordinator, scope.copy(target = pluginSessionOperationFingerprint(tool, summary, changedArgs)))
            }
            coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.REJECT)
            assertFalse(changed.await())
        }
        val summary = buildJsonObject { put("bytes", 5) }
        fun http(body: String, header: String) = buildJsonObject {
            put("body", body); put("headers", buildJsonObject { put("Authorization", header) })
        }
        val first = pluginSessionOperationFingerprint("http.request", summary, http("alpha", "token-a"))
        assertNotEquals(first, pluginSessionOperationFingerprint("http.request", summary, http("alpha", "token-b")))
        assertNotEquals(first, pluginSessionOperationFingerprint("http.request", summary, http("bravo", "token-a")))
        assertFalse(first.contains("alpha")); assertFalse(first.contains("token-a"))
    }

    @Test fun objectKeyOrderDoesNotChangeExactOperationFingerprint() {
        val first = buildJsonObject { put("a", 1); put("b", 2) }
        val reordered = buildJsonObject { put("b", 2); put("a", 1) }
        assertEquals(pluginSessionOperationFingerprint("operation", first), pluginSessionOperationFingerprint("operation", reordered))
        assertNotEquals(pluginSessionOperationFingerprint("operation", first), pluginSessionOperationFingerprint("other", first))
    }

    @Test fun sessionChoiceReusesOnlyItsScopeAndNeverCallsPersistentRemember() = runTest {
        val coordinator = PluginApprovalCoordinator()
        var remembered = 0
        var validated = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            request(coordinator, scope, validate = { validated++ }, remember = { remembered++ })
        }
        val shown = requireNotNull(coordinator.pending.value)
        assertTrue(shown.canAllowSession)
        assertFalse(shown.pluginRunSession)
        assertTrue(shown.canAlwaysAllow)
        coordinator.resolve(shown.id, PluginApprovalChoice.ALWAYS_SESSION)
        assertTrue(pending.await())
        assertTrue(request(coordinator, scope, validate = { validated++ }, remember = { remembered++ }))
        assertNull(coordinator.pending.value)
        assertEquals(2, validated)
        assertEquals(0, remembered)
    }

    @Test fun sessionInstallContentActivationSourceTargetAndPolicyIdentityAreAllIsolated() = runTest {
        val coordinator = PluginApprovalCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, scope) }
        coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.ALWAYS_SESSION)
        assertTrue(first.await())
        for (changed in listOf(scope.copy(sessionId = "chat-B"), scope.copy(pluginId = "another.plugin"),
            scope.copy(installRevision = "install-B"), scope.copy(contentHash = "content-B"),
            scope.copy(activationEpoch = 2), scope.copy(sourceIdentity = "source-B"),
            scope.copy(target = "command-B"), scope.copy(policyIdentity = "identity-B"))) {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, changed) }
            val shown = requireNotNull(coordinator.pending.value)
            assertFalse(pending.isCompleted)
            coordinator.resolve(shown.id, PluginApprovalChoice.REJECT)
            assertFalse(pending.await())
        }
    }

    @Test fun freshCoordinatorCannotRestoreSessionDecisions() = runTest {
        val coordinator = PluginApprovalCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, scope) }
        coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.ALWAYS_SESSION)
        assertTrue(first.await())
        val restarted = PluginApprovalCoordinator()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { request(restarted, scope) }
        restarted.resolve(requireNotNull(restarted.pending.value).id, PluginApprovalChoice.REJECT)
        assertFalse(pending.await())
    }

    @Test fun runSessionWithoutChatIsConfinedToItsInvocationAuthority() = runTest {
        val coordinator = PluginApprovalCoordinator()
        val run = scope.copy(sessionId = null, runIdentity = Any())
        val pending = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, run) }
        val shown = requireNotNull(coordinator.pending.value)
        assertTrue(shown.canAllowSession)
        assertTrue(shown.pluginRunSession)
        coordinator.resolve(shown.id, PluginApprovalChoice.ALWAYS_SESSION)
        assertTrue(pending.await())
        assertTrue(request(coordinator, run))
        val next = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, run.copy(runIdentity = Any())) }
        coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.REJECT)
        assertFalse(next.await())
    }

    @Test fun onceRejectAndMissingScopeCannotCreateSessionApproval() = runTest {
        val coordinator = PluginApprovalCoordinator()
        for (choice in listOf(PluginApprovalChoice.ONCE, PluginApprovalChoice.REJECT)) {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, scope) }
            coordinator.resolve(requireNotNull(coordinator.pending.value).id, choice)
            assertEquals(choice == PluginApprovalChoice.ONCE, pending.await())
        }
        val unavailable = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, null) }
        val shown = requireNotNull(coordinator.pending.value)
        assertFalse(shown.canAllowSession)
        coordinator.resolve(shown.id, PluginApprovalChoice.ALWAYS_SESSION)
        assertFalse(unavailable.isCompleted)
        coordinator.resolve(shown.id, PluginApprovalChoice.REJECT)
        assertFalse(unavailable.await())
    }

    @Test fun denyDuringValidationDoesNotRememberSessionChoice() = runTest {
        val coordinator = PluginApprovalCoordinator()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                request(coordinator, scope, validate = { throw PluginHostException("SYSTEM_DENIED", "Denied") })
                "allowed"
            } catch (failure: PluginHostException) { failure.code }
        }
        coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.ALWAYS_SESSION)
        assertEquals("SYSTEM_DENIED", pending.await())
        val retry = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, scope) }
        coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.REJECT)
        assertFalse(retry.await())
    }

    @Test fun sourceChangeWhileWaitingCannotSaveSessionScope() = runTest {
        val coordinator = PluginApprovalCoordinator()
        val changes = MutableStateFlow(Unit)
        var current = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                coordinator.awaitApproval("sample.plugin", "Sample", PluginApprovalOperation.EXECUTE_COMMAND,
                    "command", changes, { if (!current) throw CancellationException("Source changed") }, sessionScope = scope)
                false
            } catch (_: CancellationException) { true }
        }
        val id = requireNotNull(coordinator.pending.value).id
        current = false
        coordinator.resolve(id, PluginApprovalChoice.ALWAYS_SESSION)
        assertTrue(pending.await())
        val retry = async(start = CoroutineStart.UNDISPATCHED) { request(coordinator, scope) }
        coordinator.resolve(requireNotNull(coordinator.pending.value).id, PluginApprovalChoice.REJECT)
        assertFalse(retry.await())
    }
}
