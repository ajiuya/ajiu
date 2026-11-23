package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MobileBuildSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("mobile_build_settings", Context.MODE_PRIVATE)
    private val defaults = MobileBuildSettings()
    private val _settings = MutableStateFlow(
        MobileBuildSettings(
            enabled = prefs.getBoolean("enabled", defaults.enabled),
            gradleHeapMb = prefs.getInt("gradle_heap_mb", defaults.gradleHeapMb)
                .takeIf { it in MobileBuildSettings.GRADLE_HEAP_OPTIONS } ?: defaults.gradleHeapMb,
            daemonEnabled = prefs.getBoolean("daemon_enabled", defaults.daemonEnabled),
            parallelEnabled = prefs.getBoolean("parallel_enabled", defaults.parallelEnabled),
            maxWorkers = prefs.getInt("max_workers", defaults.maxWorkers)
                .takeIf { it in MobileBuildSettings.WORKER_OPTIONS } ?: defaults.maxWorkers,
            testHeapMb = prefs.getInt("test_heap_mb", defaults.testHeapMb)
                .takeIf { it in MobileBuildSettings.TEST_HEAP_OPTIONS } ?: defaults.testHeapMb
        )
    )
    val settings: StateFlow<MobileBuildSettings> = _settings.asStateFlow()

    internal fun save(settings: MobileBuildSettings) {
        require(settings.gradleHeapMb in MobileBuildSettings.GRADLE_HEAP_OPTIONS)
        require(settings.maxWorkers in MobileBuildSettings.WORKER_OPTIONS)
        require(settings.testHeapMb in MobileBuildSettings.TEST_HEAP_OPTIONS)
        val previous = _settings.value
        if (!write(settings)) {
            // SharedPreferences updates its in-memory map even when the disk commit fails.
            val failure = IOException("Unable to save mobile build settings")
            if (!write(previous)) failure.addSuppressed(IOException("Unable to restore mobile build preferences"))
            throw failure
        }
        _settings.value = settings
    }

    private fun write(settings: MobileBuildSettings): Boolean = prefs.edit()
        .putBoolean("enabled", settings.enabled)
        .putInt("gradle_heap_mb", settings.gradleHeapMb)
        .putBoolean("daemon_enabled", settings.daemonEnabled)
        .putBoolean("parallel_enabled", settings.parallelEnabled)
        .putInt("max_workers", settings.maxWorkers)
        .putInt("test_heap_mb", settings.testHeapMb)
        .commit()
}
