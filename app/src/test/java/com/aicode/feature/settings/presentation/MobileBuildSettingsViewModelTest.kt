package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModelStore
import com.aicode.R
import com.aicode.feature.agent.domain.container.MobileBuildConfigurator
import com.aicode.feature.settings.data.repository.MobileBuildSettingsRepository
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MobileBuildSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<MobileBuildSettingsRepository>()
    private val configurator = mockk<MobileBuildConfigurator>()
    private val current = MutableStateFlow(MobileBuildSettings())
    private val store = ViewModelStore()
    private lateinit var viewModel: MobileBuildSettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.settings } returns current
        successfulUpdates()
        viewModel = MobileBuildSettingsViewModel(repository, configurator)
        store.put("mobile-build", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun settingsExposeTheRepositoryFlowAndTurningOffKeepsEveryOption() {
        val selected = options().copy(enabled = true)
        current.value = selected
        assertSame(current, viewModel.settings)
        viewModel.setEnabled(false)
        assertTrue(viewModel.saving.value)
        settle()
        assertEquals(selected.copy(enabled = false), viewModel.settings.value)
        assertFalse(viewModel.saving.value)
        assertNull(viewModel.error.value)
        assertEquals(0L, viewModel.optionsSavedVersion.value)
        viewModel.setEnabled(true)
        settle()
        assertEquals(selected, viewModel.settings.value)
        assertEquals(0L, viewModel.optionsSavedVersion.value)
    }

    @Test fun optionsAreCopiedOntoTheLatestEnabledValueInsideTheTransform() {
        val staleOptions = options().copy(enabled = false)
        viewModel.saveOptions(staleOptions)
        current.value = current.value.copy(enabled = true)
        settle()
        assertEquals(staleOptions.copy(enabled = true), viewModel.settings.value)
        assertEquals(1L, viewModel.optionsSavedVersion.value)

        viewModel.saveOptions(staleOptions.copy(enabled = true, testHeapMb = 512))
        current.value = current.value.copy(enabled = false)
        settle()
        assertEquals(staleOptions.copy(enabled = false, testHeapMb = 512), viewModel.settings.value)
        assertEquals(2L, viewModel.optionsSavedVersion.value)
    }

    @Test fun failedOptionsSaveKeepsSettingsAndPublishesOnlyALocalizedErrorUntilRetry() {
        val initial = current.value
        coEvery { configurator.update(any()) } throws IOException("credential=do-not-display")
        viewModel.saveOptions(options())
        assertTrue(viewModel.saving.value)
        settle()
        assertEquals(initial, viewModel.settings.value)
        assertEquals(R.string.mobile_build_save_failed, viewModel.error.value)
        assertFalse(viewModel.saving.value)
        assertEquals(0L, viewModel.optionsSavedVersion.value)
        assertFalse(viewModel.error.value.toString().contains("do-not-display"))

        successfulUpdates()
        viewModel.saveOptions(options())
        assertNull(viewModel.error.value)
        assertTrue(viewModel.saving.value)
        settle()
        assertEquals(options(), viewModel.settings.value)
        assertNull(viewModel.error.value)
        assertFalse(viewModel.saving.value)
        assertEquals(1L, viewModel.optionsSavedVersion.value)
    }

    @Test fun failedEnableDoesNotOptimisticallyFlipTheSwitchOrSignalASavedOptionsPanel() {
        coEvery { configurator.update(any()) } throws IOException("private transport detail")
        viewModel.setEnabled(true)
        settle()
        assertFalse(viewModel.settings.value.enabled)
        assertFalse(viewModel.saving.value)
        assertEquals(R.string.mobile_build_save_failed, viewModel.error.value)
        assertEquals(0L, viewModel.optionsSavedVersion.value)
        viewModel.clearError()
        assertNull(viewModel.error.value)
    }

    @Test fun rapidRequestsAreRejectedBeforeTheFirstCoroutineEvenStarts() {
        val finish = CompletableDeferred<Unit>()
        coEvery { configurator.update(any()) } coAnswers {
            val transform = firstArg<(MobileBuildSettings) -> MobileBuildSettings>()
            finish.await()
            current.value = transform(current.value)
        }
        viewModel.setEnabled(true)
        viewModel.setEnabled(false)
        viewModel.saveOptions(options())
        assertTrue(viewModel.saving.value)
        settle()
        coVerify(exactly = 1) { configurator.update(any()) }
        viewModel.setEnabled(false)
        viewModel.saveOptions(options())
        settle()
        coVerify(exactly = 1) { configurator.update(any()) }
        assertEquals(MobileBuildSettings(), current.value)

        finish.complete(Unit)
        settle()
        assertFalse(viewModel.saving.value)
        assertEquals(MobileBuildSettings(enabled = true), current.value)
        assertEquals(0L, viewModel.optionsSavedVersion.value)

        viewModel.saveOptions(options())
        settle()
        coVerify(exactly = 2) { configurator.update(any()) }
        assertEquals(options().copy(enabled = true), current.value)
        assertEquals(1L, viewModel.optionsSavedVersion.value)
    }

    @Test fun cancellationReleasesSavingWithoutReportingSuccess() {
        coEvery { configurator.update(any()) } throws CancellationException("cancelled")
        viewModel.saveOptions(options())
        settle()
        assertFalse(viewModel.saving.value)
        assertNull(viewModel.error.value)
        assertEquals(MobileBuildSettings(), current.value)
        assertEquals(0L, viewModel.optionsSavedVersion.value)
    }

    private fun successfulUpdates() {
        coEvery { configurator.update(any()) } coAnswers {
            val transform = firstArg<(MobileBuildSettings) -> MobileBuildSettings>()
            current.value = transform(current.value)
        }
    }

    private fun settle() = dispatcher.scheduler.runCurrent()

    private fun options() = MobileBuildSettings(
        gradleHeapMb = 2560,
        daemonEnabled = true,
        parallelEnabled = true,
        maxWorkers = 3,
        testHeapMb = 1536
    )
}
