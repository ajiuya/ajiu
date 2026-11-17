package com.aicode.feature.agent.domain.memory

import org.junit.Assert.*
import org.junit.Test

class ProjectMemoryRuntimeTest {
    @Test fun explicitNullTurnOverrideIsDifferentFromNoSelection() {
        val turnOverride = ProjectMemoryTurnOverride("session", "turn")
        assertNull(turnOverride.get())
        turnOverride.bind(null) { }
        assertNotNull(turnOverride.get())
        assertNull(turnOverride.get()!!.projectId)
        assertSame(turnOverride, turnOverride.forTurn("session", "turn"))
        assertNull(turnOverride.forTurn("session", "next").get())
        assertNull(turnOverride.forTurn("other", "turn").get())
    }

    @Test fun failedPersistenceDoesNotPublishATurnOverride() {
        val turnOverride = ProjectMemoryTurnOverride("session", "turn")
        assertThrows(IllegalStateException::class.java) { turnOverride.bind("project") { error("conflict") } }
        assertNull(turnOverride.get())
    }

    @Test fun invocationBindingsStaySessionOwnedAndReleaseDoesNotAffectOtherSessions() {
        val bindings = ProjectMemoryRuntimeBindings()
        val first = ProjectMemoryIdentity("local_proot", projectRoot = "/work/one", profileId = "one")
        val second = first.copy(projectRoot = "/work/two")
        bindings.remember("one", first, "first context", "first-project")
        bindings.remember("two", second, "second context", "second-project")
        bindings.release("one")
        assertNull(bindings.get("one"))
        assertEquals(second, bindings.get("two")!!.identity)
        assertEquals("second context", bindings.get("two")!!.context)
    }
}
