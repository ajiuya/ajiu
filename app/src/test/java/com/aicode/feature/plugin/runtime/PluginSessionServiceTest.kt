package com.aicode.feature.plugin.runtime

import android.app.Application
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PluginSessionServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: PluginConversationFixture
    private val registry = PluginSessionRegistry()
    private val events = PluginSessionEventBus()
    private val host = PluginSessionHostService(registry, events)

    @Before fun setup() = runBlocking<Unit> { fixture = PluginConversationFixture(temporary.root); fixture.install() }
    private fun list(id: String = "external") = buildJsonObject {
        put("sourceId", "history"); put("sessions", JsonArray(listOf(buildJsonObject {
            put("sessionId", id); put("title", "External"); put("running", true); put("updatedAt", 42)
        })))
    }
    private fun sourceId() = fixture.capture.content.sessionSources.single().key
    private fun error(block: () -> Any?): String = try { block(); throw AssertionError("Expected denial") }
        catch (failure: PluginHostException) { failure.code }

    @Test fun asynchronousPublisherProjectsSidebarWithoutWritingNativeSessions() {
        val context = fixture.context(background = true)
        host.setSessions(context, list())
        val group = registry.groups.value.single()
        assertFalse(group.loading)
        assertEquals("sample.plugin", group.pluginId)
        assertEquals("${sourceId()}|external", group.sessions.single().id)
        assertTrue(group.sessions.single().running)
        host.setThinking(context, buildJsonObject {
            put("sourceId", "history"); put("sessionId", "external"); put("visible", true); put("text", "Working")
        })
        assertEquals("Working", registry.thinking.value.getValue("${sourceId()}|external"))
    }

    @Test fun unknownExternalSessionCannotPublishEventsOrThinking() = runBlocking<Unit> {
        val context = fixture.context()
        host.setSessions(context, list())
        assertEquals("SESSION_NOT_FOUND", error { host.setThinking(context, buildJsonObject {
            put("sourceId", "history"); put("sessionId", "unknown"); put("visible", true)
        }) })
        try {
            host.pushEvent(context, buildJsonObject { put("sourceId", "history"); put("sessionId", "unknown"); put("type", "turn-end") })
            fail("Expected unknown external session denial")
        } catch (failure: PluginHostException) { assertEquals("SESSION_NOT_FOUND", failure.code) }
    }

    @Test fun pageAndEventAreOwnerBoundAndDoNotPromoteToolOutputToExecution() = runBlocking<Unit> {
        val context = fixture.context()
        host.setSessions(context, list())
        val pending = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { events.events.first() } }
        host.pushEvent(context, buildJsonObject {
            put("sourceId", "history"); put("sessionId", "external"); put("type", "tool-output"); put("callId", "remote-call"); put("text", "display only")
        })
        val event = pending.await()
        assertEquals(fixture.capture.installRevision, event.installRevision)
        assertEquals(fixture.capture.activationEpoch, event.activationEpoch)
        assertSame(context.sourceIdentity, event.sourceIdentity)
        event.checkCurrent()
        fixture.manager.disable(fixture.capture.id)
        try { event.checkCurrent(); fail("Old event must be revoked") } catch (_: IllegalStateException) { }
    }

    @Test fun workspaceOrSessionChangeCannotReuseInteractiveRegistry() {
        host.setSessions(fixture.context(), list())
        assertEquals("RESOURCE_NOT_FOUND", error {
            registry.requireSession(fixture.context(workspace = "/other"), sourceId(), "external")
        })
        assertEquals("RESOURCE_NOT_FOUND", error {
            registry.requireSession(fixture.context(session = "chat-B"), sourceId(), "external")
        })
    }

    @Test fun disableEnableDropsOldProjectionAndCannotReviveThinking() = runBlocking<Unit> {
        host.setSessions(fixture.context(), list())
        fixture.manager.disable(fixture.capture.id)
        registry.pruneRevoked()
        assertTrue(registry.groups.value.isEmpty())
        fixture.capture = PluginCoreFixtures.activate(fixture.manager)
        val context = fixture.context()
        val definition = fixture.capture.content.sessionSources.single()
        registry.ensureSource(context, definition)
        assertTrue(registry.groups.value.single().sessions.isEmpty())
        assertEquals("SESSION_NOT_FOUND", error { registry.requireSession(context, definition.key, "external") })
    }

    @Test fun pageParserRejectsAnotherSessionAndKeepsHistoryBlocks() {
        val page = buildJsonObject {
            put("sessionId", "external"); put("messages", JsonArray(listOf(buildJsonObject {
                put("id", "message"); put("role", "assistant"); put("blocks", JsonArray(listOf(
                    buildJsonObject { put("type", "text"); put("text", "Hello") },
                    buildJsonObject { put("type", "tool"); put("name", "readFile"); put("input", "path"); put("output", "remote content") }
                )))
            })))
        }
        assertEquals(2, PluginSessionCodec.page(page, "external").messages.single().blocks.size)
        assertEquals("SESSION_FORBIDDEN", error { PluginSessionCodec.page(page, "other") })
    }

    @Test fun malformedAndOversizedSessionListsAreRejectedNotSilentlyTruncated() {
        val context = fixture.context()
        val tooMany = buildJsonObject { put("sourceId", "history"); put("sessions", JsonArray(List(1001) {
            buildJsonObject { put("sessionId", "s$it") }
        })) }
        assertEquals("PARAM_INVALID", error { host.setSessions(context, tooMany) })
        assertEquals("PARAM_INVALID", error { host.setSessions(context, list("bad|id")) })
    }
}
