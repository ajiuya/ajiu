package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.RunningToolActivity
import com.aicode.feature.agent.presentation.RunningToolOutput
import com.aicode.feature.agent.presentation.runningToolActivityOf
import com.aicode.feature.agent.presentation.runningToolOutputOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRunningToolIsolationTest {
    @Test
    fun concurrentProgressOnlyEmitsForTheChangedTool() = runTest {
        val toolA = RunningToolOutput("tool-a", "a-0", "Bash")
        val toolB = RunningToolOutput("tool-b", "b-0", "terminal")
        val source = MutableStateFlow(listOf(toolA, toolB))
        val activities = mutableListOf<RunningToolActivity>()
        val toolAOutputs = mutableListOf<RunningToolOutput?>()
        val toolBOutputs = mutableListOf<RunningToolOutput?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.map(::runningToolActivityOf).distinctUntilChanged().collect { activities += it }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.map { runningToolOutputOf(it, toolA.messageId) }.distinctUntilChanged().collect { toolAOutputs += it }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.map { runningToolOutputOf(it, toolB.messageId) }.distinctUntilChanged().collect { toolBOutputs += it }
        }
        runCurrent()

        val activitySnapshot = activities.toList()
        assertEquals(RunningToolActivity(setOf(toolA.messageId, toolB.messageId), "terminal"), activitySnapshot.last())

        val updatedA = toolA.copy(text = "a-1")
        source.value = listOf(updatedA, toolB)
        runCurrent()
        assertEquals(activitySnapshot, activities)
        assertEquals(listOf(toolA, updatedA), toolAOutputs)
        assertEquals(listOf(toolB), toolBOutputs)

        val updatedB = toolB.copy(text = "b-1")
        source.value = listOf(updatedA, updatedB)
        runCurrent()
        assertEquals(activitySnapshot, activities)
        assertEquals(listOf(toolA, updatedA), toolAOutputs)
        assertEquals(listOf(toolB, updatedB), toolBOutputs)

        val renamedB = updatedB.copy(toolName = "python")
        source.value = listOf(updatedA, renamedB)
        runCurrent()
        assertEquals(
            activitySnapshot + RunningToolActivity(setOf(toolA.messageId, toolB.messageId), "python"),
            activities,
        )

        source.value = listOf(updatedA)
        runCurrent()
        assertEquals(RunningToolActivity(setOf(toolA.messageId), "Bash"), activities.last())
        assertEquals(null, toolBOutputs.last())

        source.value = emptyList()
        runCurrent()
        assertEquals(RunningToolActivity(), activities.last())
    }
}
