package com.aicode.feature.agent.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.domain.command.PendingChrootSkillMenuFixture
import com.aicode.feature.agent.domain.command.SlashCommandKind
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import io.mockk.coEvery
import io.mockk.every
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChatSkillMenuStartupTest {
    private data class RefreshAttempt(val delegated: Boolean, val failure: Throwable?)

    private val dispatcher = StandardTestDispatcher()
    private val unhandledFailures = CopyOnWriteArrayList<Throwable>()
    private val refreshes = Channel<RefreshAttempt>(Channel.UNLIMITED)
    private val refreshCount = AtomicInteger()
    private lateinit var fixture: SubAgentViewModelQueueFixture
    private lateinit var source: PendingChrootSkillMenuFixture
    private lateinit var testThread: Thread
    private var originalExceptionHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        testThread = Thread.currentThread()
        originalExceptionHandler = testThread.uncaughtExceptionHandler
        testThread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure ->
            unhandledFailures.add(failure)
        }
    }

    @After
    fun tearDown() {
        try {
            if (::fixture.isInitialized) fixture.close()
            dispatcher.scheduler.runCurrent()
        } finally {
            refreshes.close()
            testThread.uncaughtExceptionHandler = originalExceptionHandler
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingChrootStartupAndWorkspaceChangeKeepBuiltinAndHostSkillsWithoutUnhandledFailures() = runTest(dispatcher) {
        createFixture()
        val vm = fixture.viewModel
        val vmJob = checkNotNull(vm.viewModelScope.coroutineContext[Job])
        try {
            val startup = awaitRefresh()
            runCurrent()
            assertTrue(startup.delegated)
            assertSuccessfulMenu(startup, "host-skill")
            assertEquals(1, refreshCount.get())

            source.selectDefinitions("fixture:workspace", "workspace-skill")
            vm.setWorkspace("/work")
            val workspace = awaitRefresh()
            runCurrent()
            assertTrue(workspace.delegated)
            assertSuccessfulMenu(workspace, "workspace-skill")
            assertEquals(2, refreshCount.get())
            assertNull(source.registry.resolve("/host-skill"))
        } finally {
            fixture.closeUi()
            runCurrent()
            assertTrue(vmJob.isCancelled)
            source.assertNoRuntimeOrFileAccessCalls()
        }
    }

    @Test
    fun pendingChrootManualRefreshKeepsBuiltinAndHostSkillsWithoutUnhandledFailures() = runTest(dispatcher) {
        createFixture(skipStartupRefresh = true)
        val vm = fixture.viewModel
        val vmJob = checkNotNull(vm.viewModelScope.coroutineContext[Job])
        try {
            val startup = awaitRefresh()
            runCurrent()
            assertEquals(RefreshAttempt(false, null), startup)
            assertTrue(unhandledFailures.isEmpty())
            assertEquals(1, refreshCount.get())

            source.selectDefinitions("fixture:manual", "manual-skill")
            vm.refreshSlashCommands()
            val manual = awaitRefresh()
            runCurrent()
            assertTrue(manual.delegated)
            assertSuccessfulMenu(manual, "manual-skill")
            assertEquals(2, refreshCount.get())
        } finally {
            fixture.closeUi()
            runCurrent()
            assertTrue(vmJob.isCancelled)
            source.assertNoRuntimeOrFileAccessCalls()
        }
    }

    @Test
    fun pendingChrootProfileConfigurationChangeReloadsHostSkillWithoutUnhandledFailures() = runTest(dispatcher) {
        createFixture()
        val vmJob = checkNotNull(fixture.viewModel.viewModelScope.coroutineContext[Job])
        try {
            val startup = awaitRefresh()
            runCurrent()
            assertSuccessfulMenu(startup, "host-skill")
            val before = refreshCount.get()

            source.changeProfileConfiguration("fixture:configuration", "configuration-skill")
            runCurrent()
            val changed = awaitRefresh()
            runCurrent()

            assertTrue(changed.delegated)
            assertSuccessfulMenu(changed, "configuration-skill")
            assertTrue(refreshCount.get() > before)
            assertNull(source.registry.resolve("/host-skill"))
        } finally {
            fixture.closeUi()
            runCurrent()
            assertTrue(vmJob.isCancelled)
            source.assertNoRuntimeOrFileAccessCalls()
        }
    }

    private fun createFixture(skipStartupRefresh: Boolean = false) {
        fixture = SubAgentViewModelQueueFixture(
            ApplicationProvider.getApplicationContext<Context>(),
            configureBeforeStart = {
                source = PendingChrootSkillMenuFixture(executionModeHolder, dependencies.containerSettingsRepository)
                source.assertPendingWithoutExecutionOwner()
                every { commands.commands } answers { source.registry.commands }
                every { commands.resolve(any()) } answers { source.registry.resolve(firstArg()) }
                coEvery { commands.refresh() } coAnswers {
                    val number = refreshCount.incrementAndGet()
                    if (skipStartupRefresh && number == 1) {
                        check(refreshes.trySend(RefreshAttempt(false, null)).isSuccess)
                    } else {
                        val result = runCatching {
                            source.assertPendingWithoutExecutionOwner()
                            source.registry.refresh()
                        }
                        check(refreshes.trySend(RefreshAttempt(true, result.exceptionOrNull())).isSuccess)
                        result.getOrThrow()
                    }
                }
            }
        )
    }

    private suspend fun awaitRefresh(): RefreshAttempt = withContext(Dispatchers.Default) {
        withTimeout(5_000) { refreshes.receive() }
    }

    private fun assertSuccessfulMenu(attempt: RefreshAttempt, expectedSkill: String) {
        val failures = unhandledFailures.toList()
        try {
            assertEquals("The real ViewModel must not leak an asynchronous menu refresh failure", emptyList<Throwable>(), failures)
        } catch (assertion: AssertionError) {
            failures.firstOrNull()?.let { assertion.initCause(it) }
            throw assertion
        }
        attempt.failure?.let { failure ->
            throw AssertionError("The real ViewModel menu refresh must complete normally", failure)
        }
        assertEquals(SlashCommandRegistry.RefreshState.Ready, source.registry.refreshState.value)
        assertEquals(listOf("usage", expectedSkill), fixture.viewModel.slashCommands.value.map { it.name })
        assertEquals(listOf(SlashCommandKind.ACTION, SlashCommandKind.SKILL), fixture.viewModel.slashCommands.value.map { it.kind })
        assertNull(source.registry.resolve("/disabled-fixture"))
        val resolved = source.registry.resolve("/$expectedSkill args") as SlashCommandRegistry.ResolvedCommand.SkillCommand
        assertEquals("Fixture instructions for $expectedSkill", resolved.skill.instructions)
        assertEquals("args", resolved.args)
        source.assertPendingWithoutExecutionOwner()
        source.assertNoRuntimeOrFileAccessCalls()
    }
}
