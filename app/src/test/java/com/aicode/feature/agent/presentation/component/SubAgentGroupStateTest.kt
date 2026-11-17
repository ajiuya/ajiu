package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.agent.presentation.SubAgentInteractionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentGroupStateTest {
    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private class Runtime(private val scope: TestScope) : AutoCloseable {
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + clock)
        private val composition = Composition(NoOpApplier(), recomposer)
        private val runner = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler) + clock) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var frame = 0L
        private var writes = 0
        private val observer = Snapshot.registerGlobalWriteObserver { writes++ }

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent(content)
            settle()
        }

        fun settle() {
            repeat(100) {
                val before = writes
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (clock.hasAwaiters) {
                    clock.sendFrame(++frame * 1_000_000L)
                    scope.runCurrent()
                }
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                if (before == writes && !recomposer.hasPendingWork && !clock.hasAwaiters) return
            }
            error("Group composition did not settle")
        }

        override fun close() {
            composition.dispose()
            scope.runCurrent()
            observer.dispose()
            recomposer.cancel()
            runner.cancel()
        }
    }

    private fun child(turn: String, status: String = "running") = ChatRenderItem(
        message = AgentUIMessage("call-$turn", MessageRole.TOOL, "original", timestamp = 1L),
        key = "$turn:child:same",
        contentType = SUB_AGENT_CAPSULE_CONTENT_TYPE,
        chatTurnKey = turn,
        subAgentCapsule = SubAgentTimelineCapsule("same", "call-$turn", fallbackStatus = status),
    )

    @Test
    fun everyStatusStartsClosedAndMetadataRefreshNeverOverridesManualChoice() = runTest {
        var raw by mutableStateOf(listOf(child("turn:one")))
        lateinit var expansion: StateHolderSubAgentGroupExpansion
        lateinit var projection: SubAgentGroupProjection
        Runtime(this).use { runtime ->
            runtime.setContent {
                expansion = rememberSubAgentGroupExpansion("parent", "group:one")
                projection = projectSubAgentGroup(raw, "parent", expanded = expansion.expanded)
            }
            SubAgentInteractionStatus.entries.forEach { status ->
                raw = listOf(child("turn:one", status.name))
                runtime.settle()
                assertFalse(expansion.expanded)
                assertEquals(1, projection.items.size)
            }
            expansion.toggle()
            runtime.settle()
            val original = expansion
            raw = listOf(child("turn:one", "completed"))
            runtime.settle()
            assertSame(original, expansion)
            assertTrue(expansion.expanded)
            assertEquals(2, projection.items.size)
            raw = emptyList()
            runtime.settle()
            assertTrue(expansion.expanded)
            assertTrue(projection.items.isEmpty())
            expansion.toggle()
            runtime.settle()
            assertFalse(expansion.expanded)
        }
    }

    @Test
    fun newParentNewTurnAndNullSessionNeverBorrowAnOpenChoice() = runTest {
        var session by mutableStateOf<String?>("parent-a")
        var groupKey by mutableStateOf("group:one")
        lateinit var expansion: StateHolderSubAgentGroupExpansion
        Runtime(this).use { runtime ->
            runtime.setContent { expansion = rememberSubAgentGroupExpansion(session, groupKey) }
            listOf("parent-a" to "group:two", "parent-b" to "group:two", null to "group:two", "parent-a" to "group:one").forEach { (nextSession, nextGroup) ->
                expansion.toggle()
                runtime.settle()
                assertTrue(expansion.expanded)
                val previous = expansion
                session = nextSession
                groupKey = nextGroup
                runtime.settle()
                assertFalse(expansion.expanded)
                assertNotSame(previous, expansion)
            }
        }
    }

    @Test
    fun savedExpansionRestoresOnlyForItsVerifiedParentAndTurn() = runTest {
        var session: String? = "parent"
        var groupKey = "group:one"
        var registry = SaveableStateRegistry(null, canBeSaved = { true })
        lateinit var expansion: StateHolderSubAgentGroupExpansion
        val root: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                expansion = rememberSubAgentGroupExpansion(session, groupKey)
            }
        }
        val saved = Runtime(this).use { runtime ->
            runtime.setContent(root)
            expansion.toggle()
            runtime.settle()
            registry.performSave()
        }
        registry = SaveableStateRegistry(saved, canBeSaved = { true })
        Runtime(this).use { runtime ->
            runtime.setContent(root)
            assertTrue(expansion.expanded)
        }
        listOf("foreign-parent" to "group:one", "parent" to "group:two", null to "group:one").forEach { (targetParent, targetGroup) ->
            session = targetParent
            groupKey = targetGroup
            registry = SaveableStateRegistry(null, canBeSaved = { true })
            val slots = Runtime(this).use { runtime ->
                runtime.setContent(root)
                registry.performSave().keys
            }
            registry = SaveableStateRegistry(slots.associateWith { saved.values.single() }, canBeSaved = { true })
            Runtime(this).use { runtime ->
                runtime.setContent(root)
                assertFalse(expansion.expanded)
            }
        }
    }

    @Test
    fun retainedTurnExpansionAndChildDraftSurviveTemporaryOwnershipGapAndRankChange() = runTest {
        val registry = SaveableStateRegistry(null, canBeSaved = { true })
        var raw by mutableStateOf(listOf(child("turn:one"), child("turn:two")))
        var activeTurn by mutableStateOf<String?>("turn:two")
        val expansions = mutableMapOf<String, StateHolderSubAgentGroupExpansion>()
        val drafts = mutableMapOf<String, MutableState<String>>()
        lateinit var projection: SubAgentGroupProjection
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    val holder = rememberSubAgentGroupChildStateHolder("parent")
                    val structure = projectSubAgentGroup(raw, "parent", activeTurnKey = activeTurn)
                    val current = structure.groups.associate { group ->
                        group.key to rememberRetainedSubAgentGroupExpansion("parent", group.key, holder)
                    }
                    expansions.putAll(current)
                    projection = projectSubAgentGroup(raw, "parent", activeTurnKey = activeTurn,
                        expansionOverrides = current.mapValues { it.value.expanded })
                    projection.items.forEach { item ->
                        if (item.subAgentCapsule != null) key(item.key) {
                            holder.SaveableStateProvider(item.key) {
                                drafts[item.key] = rememberSaveable { mutableStateOf("") }
                            }
                        }
                    }
                }
            }
            val groupKeys = projection.groups.map { it.key }
            val childKeys = projection.groups.map { it.childItems.single().key }
            groupKeys.forEach { expansions.getValue(it).toggle() }
            runtime.settle()
            drafts.getValue(childKeys[0]).value = "first turn draft"
            drafts.getValue(childKeys[1]).value = "second turn draft"
            runtime.settle()
            raw = emptyList()
            runtime.settle()
            assertTrue(projection.groups.isEmpty())
            raw = listOf(child("turn:one", "completed"), child("turn:two", "queued"))
            runtime.settle()
            assertEquals(groupKeys, projection.groups.map { it.key })
            assertTrue(groupKeys.all { expansions.getValue(it).expanded })
            assertEquals("first turn draft", drafts.getValue(childKeys[0]).value)
            assertEquals("second turn draft", drafts.getValue(childKeys[1]).value)
            expansions.getValue(groupKeys[0]).toggle()
            runtime.settle()
            assertTrue(projection.bodyItems.none { it.subAgentCapsule != null })
            assertEquals(1, projection.footerItems.count { it.subAgentCapsule != null })
            activeTurn = null
            runtime.settle()
            assertTrue(projection.footerItems.isEmpty())
            assertTrue(expansions.getValue(groupKeys[1]).expanded)
        }
    }

    @Test
    fun childHolderDoesNotLeakDraftWhenAnotherSessionsGroupIsOpened() = runTest {
        var session by mutableStateOf<String?>("parent-a")
        val registry = SaveableStateRegistry(null, canBeSaved = { true })
        lateinit var expansion: StateHolderSubAgentGroupExpansion
        lateinit var draft: MutableState<String>
        Runtime(this).use { runtime ->
            runtime.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    expansion = rememberSubAgentGroupExpansion(session)
                    val holder = rememberSubAgentGroupChildStateHolder(session)
                    if (expansion.expanded) holder.SaveableStateProvider("same-child") {
                        draft = rememberSaveable { mutableStateOf("") }
                    }
                }
            }
            expansion.toggle()
            runtime.settle()
            draft.value = "private first parent"
            runtime.settle()
            listOf<String?>("parent-b", null, "parent-c").forEach { next ->
                session = next
                runtime.settle()
                assertFalse(expansion.expanded)
                expansion.toggle()
                runtime.settle()
                assertEquals("", draft.value)
                draft.value = "private $next"
                runtime.settle()
            }
        }
    }

    @Test
    fun savedChildDraftRestoresForSameParentButNotForAnotherParent() = runTest {
        var session = "parent-a"
        var registry = SaveableStateRegistry(null, canBeSaved = { true })
        lateinit var expansion: StateHolderSubAgentGroupExpansion
        lateinit var draft: MutableState<String>
        val root: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                expansion = rememberSubAgentGroupExpansion(session)
                val holder = rememberSubAgentGroupChildStateHolder(session)
                if (expansion.expanded) holder.SaveableStateProvider("same-child") {
                    draft = rememberSaveable { mutableStateOf("") }
                }
            }
        }
        val saved = Runtime(this).use { runtime ->
            runtime.setContent(root)
            expansion.toggle()
            runtime.settle()
            draft.value = "saved parent-a draft"
            runtime.settle()
            expansion.toggle()
            runtime.settle()
            registry.performSave()
        }
        registry = SaveableStateRegistry(saved, canBeSaved = { true })
        Runtime(this).use { runtime ->
            runtime.setContent(root)
            assertFalse(expansion.expanded)
            expansion.toggle()
            runtime.settle()
            assertEquals("saved parent-a draft", draft.value)
        }
        session = "parent-b"
        registry = SaveableStateRegistry(saved, canBeSaved = { true })
        Runtime(this).use { runtime ->
            runtime.setContent(root)
            assertFalse(expansion.expanded)
            expansion.toggle()
            runtime.settle()
            assertEquals("", draft.value)
        }
    }
}
