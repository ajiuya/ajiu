package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import com.aicode.feature.agent.presentation.SubAgentInteractionSummary
import org.junit.Assert.*
import org.junit.Test

class SubAgentInteractionUiPolicyTest {
    @Test fun initialCardsAreFoldedAndTerminalRefreshDoesNotUndoManualReopening() {
        SubAgentInteractionStatus.entries.forEach { status ->
            assertFalse(SubAgentCapsuleExpansion().observe(status).expanded)
        }
        for (active in listOf(SubAgentInteractionStatus.RUNNING, SubAgentInteractionStatus.AWAITING_CONFIRMATION, SubAgentInteractionStatus.QUEUED)) {
            for (terminal in SubAgentInteractionStatus.entries.filter { it.isTerminal() }) {
                val folded = SubAgentCapsuleExpansion().observe(active).toggle().observe(terminal)
                assertFalse(folded.expanded)
                var reopened = folded.toggle()
                repeat(5) { reopened = reopened.observe(terminal) }
                assertTrue(reopened.expanded)
            }
        }
    }

    @Test fun fallbackAndTimingDoNotInventProgressOrCompletionTimes() {
        val unknown = resolveSubAgentSummary("child", "Inspect", null, null, "62%")
        assertEquals(SubAgentInteractionStatus.HISTORY, unknown.status)
        assertNull(resolveSubAgentElapsedMs(unknown, Long.MAX_VALUE))
        val running = unknown.copy(status = SubAgentInteractionStatus.RUNNING, startedAtMs = 100)
        assertEquals(25L, resolveSubAgentElapsedMs(running, 125))
        assertNull(resolveSubAgentElapsedMs(running, 99))
        assertEquals(50L, resolveSubAgentElapsedMs(running.copy(finishedAtMs = 150), 1_000))
        assertNull(resolveSubAgentElapsedMs(running.copy(status = SubAgentInteractionStatus.COMPLETED, lastActivityAtMs = 150), 1_000))
    }

    @Test fun namedIdentityIsNotGuessedFromTaskOrTechnicalIdentifiers() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val summary = SubAgentInteractionSummary(id, title = "child session $id", agentName = "Custom agent")
        assertEquals("Custom agent", subAgentDisplayIdentity(summary).name)
        assertEquals("", subAgentVisibleTask(summary))
        assertEquals(SubAgentIdentityKind.UNKNOWN, subAgentDisplayIdentity(summary.copy(agentName = id)).kind)
        assertEquals(SubAgentIdentityKind.GENERIC, subAgentDisplayIdentity(summary.copy(agentName = "generic")).kind)
        assertEquals(SubAgentDisplayIdentity(SubAgentIdentityKind.NAMED, "Explore"),
            subAgentDisplayIdentity(summary.copy(agentName = "Explore")))
    }

    @Test fun nestedTaskAndNotificationToolsUseRawFallbackAndReasoningKindsStayHidden() {
        fun tool(name: String, body: String = "result") = AgentUIMessage("id", MessageRole.TOOL, body, toolName = name)
        listOf("task", " Task ", "functions.task", "api/task", "api:task").forEach {
            assertFalse(canRenderSubAgentToolBody(tool(it)))
        }
        assertFalse(canRenderSubAgentToolBody(tool("Bash", "{\"notifications\":[]}")))
        assertFalse(canRenderSubAgentToolBody(tool("Bash", "<agent-message>private carrier</agent-message>")))
        assertTrue(canRenderSubAgentToolBody(tool("readFile")))
        assertTrue(isSubAgentHiddenReasoningKind(" THINKING "))
        assertFalse(isSubAgentHiddenReasoningKind("MESSAGE_FROM_SUB"))
    }

    @Test fun finiteWindowsExposeAllLoadedEntriesWithoutComposingAllHistory() {
        val entries = (0..1002).toList()
        val first = subAgentRecordWindow(entries, 0)
        assertEquals(listOf(999, 1000, 1001, 1002), first.records)
        assertFalse(first.hasNewer)
        val visited = (0 until first.pageCount).flatMap { subAgentRecordWindow(entries, it).records }
        assertEquals(entries, visited.sorted())
        assertFalse(subAgentRecordWindow(entries, Int.MAX_VALUE).hasOlder)
        assertTrue(subAgentRecordWindow(emptyList<Int>(), 5).records.isEmpty())
    }

    @Test fun longTextAndFencesRemainLosslessAndSurrogatePairsAreNotSplit() {
        val source = "a".repeat(1199) + "😀".repeat(4000) + "\r\nlast!"
        val parts = subAgentTextParts(source)
        assertEquals(source, parts.joinToString("") { it.source })
        assertTrue(parts.all { it.source.length <= 1200 })
        assertTrue(parts.all { !Character.isLowSurrogate(it.source.first()) && !Character.isHighSurrogate(it.source.last()) })
        val code = "```kotlin\n" + (1..160).joinToString("\n") { "val value$it = $it" } + "\n```\n"
        val codeParts = subAgentTextParts(code)
        assertEquals(code, codeParts.joinToString("") { it.source })
        assertTrue(codeParts.all { it.markdown.startsWith("```kotlin\n") && it.markdown.trimEnd().endsWith("```") })
    }
}
