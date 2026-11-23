package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.agent.domain.container.MobileBuildConfigurator
import com.aicode.feature.settings.data.repository.MobileBuildSettingsRepository
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MobileBuildSettingsViewModel @Inject constructor(
    repository: MobileBuildSettingsRepository,
    private val configurator: MobileBuildConfigurator
) : ViewModel() {
    val settings: StateFlow<MobileBuildSettings> = repository.settings

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<Int?>(null)
    val error: StateFlow<Int?> = _error.asStateFlow()

    private val _optionsSavedVersion = MutableStateFlow(0L)
    val optionsSavedVersion: StateFlow<Long> = _optionsSavedVersion.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        updateSettings({ current -> current.copy(enabled = enabled) })
    }

    fun saveOptions(options: MobileBuildSettings) {
        updateSettings({ current ->
            current.copy(
                gradleHeapMb = options.gradleHeapMb,
                daemonEnabled = options.daemonEnabled,
                parallelEnabled = options.parallelEnabled,
                maxWorkers = options.maxWorkers,
                testHeapMb = options.testHeapMb
            )
        }) {
            _optionsSavedVersion.value += 1
        }
    }

    fun clearError() {
        if (!_saving.value) _error.value = null
    }

    private fun updateSettings(
        transform: (MobileBuildSettings) -> MobileBuildSettings,
        onSuccess: () -> Unit = {}
    ) {
        if (!_saving.compareAndSet(expect = false, update = true)) return
        _error.value = null
        viewModelScope.launch {
            try {
                configurator.update(transform)
                onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = R.string.mobile_build_save_failed
            } finally {
                _saving.value = false
            }
        }
    }
}
