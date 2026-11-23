package com.aicode.feature.settings.data.repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.aicode.feature.settings.domain.model.MobileBuildSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MobileBuildSettingsRepositoryTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferences = context.getSharedPreferences("mobile_build_settings", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun defaultsAreOffWithThreeGiBOneWorkerAndOneGiBTestHeap() {
        val expected = MobileBuildSettings(
            enabled = false,
            gradleHeapMb = 3072,
            daemonEnabled = false,
            parallelEnabled = false,
            maxWorkers = 1,
            testHeapMb = 1024
        )

        assertEquals(expected, MobileBuildSettings())
        assertEquals(expected, MobileBuildSettingsRepository(context).settings.value)
        assertEquals(listOf(2048, 2560, 3072), MobileBuildSettings.GRADLE_HEAP_OPTIONS)
        assertEquals(listOf(1, 2, 3, 4), MobileBuildSettings.WORKER_OPTIONS)
        assertEquals(listOf(512, 768, 1024, 1536, 2048), MobileBuildSettings.TEST_HEAP_OPTIONS)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun everyPresetAndBooleanCombinationPersistsAndRestoresAcrossRepositories() {
        val repository = MobileBuildSettingsRepository(context)

        for (heap in listOf(2048, 2560, 3072)) {
            for (workers in 1..4) {
                for (testHeap in listOf(512, 768, 1024, 1536, 2048)) {
                    for (flags in 0..7) {
                        val expected = MobileBuildSettings(
                            enabled = (flags and 1) != 0,
                            gradleHeapMb = heap,
                            daemonEnabled = (flags and 2) != 0,
                            parallelEnabled = (flags and 4) != 0,
                            maxWorkers = workers,
                            testHeapMb = testHeap
                        )

                        repository.save(expected)

                        assertEquals(expected, repository.settings.value)
                        assertEquals(expected, MobileBuildSettingsRepository(context).settings.value)
                        assertEquals(expected.enabled, preferences.getBoolean("enabled", !expected.enabled))
                        assertEquals(heap, preferences.getInt("gradle_heap_mb", -1))
                        assertEquals(expected.daemonEnabled, preferences.getBoolean("daemon_enabled", !expected.daemonEnabled))
                        assertEquals(expected.parallelEnabled, preferences.getBoolean("parallel_enabled", !expected.parallelEnabled))
                        assertEquals(workers, preferences.getInt("max_workers", -1))
                        assertEquals(testHeap, preferences.getInt("test_heap_mb", -1))
                    }
                }
            }
        }
    }

    @Test
    fun disablingKeepsEverySelectedParameterForTheNextEnable() {
        val selected = MobileBuildSettings(
            enabled = true,
            gradleHeapMb = 2560,
            daemonEnabled = true,
            parallelEnabled = true,
            maxWorkers = 4,
            testHeapMb = 1536
        )
        val repository = MobileBuildSettingsRepository(context)
        repository.save(selected)

        repository.save(selected.copy(enabled = false))

        val restored = MobileBuildSettingsRepository(context)
        assertEquals(selected.copy(enabled = false), repository.settings.value)
        assertEquals(selected.copy(enabled = false), restored.settings.value)
        restored.save(restored.settings.value.copy(enabled = true))
        assertEquals(selected, MobileBuildSettingsRepository(context).settings.value)
    }

    @Test
    fun unsupportedNumericValuesAreRejectedWithoutChangingStorageOrState() {
        val repository = MobileBuildSettingsRepository(context)
        val selected = MobileBuildSettings(enabled = true, gradleHeapMb = 2560, maxWorkers = 3, testHeapMb = 768)
        repository.save(selected)
        val stored = preferences.all.toMap()
        val invalid = buildList {
            listOf(Int.MIN_VALUE, -1, 0, 2049, 2500, 4096, Int.MAX_VALUE).forEach {
                add(selected.copy(gradleHeapMb = it))
            }
            listOf(Int.MIN_VALUE, -1, 0, 5, Int.MAX_VALUE).forEach {
                add(selected.copy(maxWorkers = it))
            }
            listOf(Int.MIN_VALUE, -1, 0, 513, 1023, 2049, Int.MAX_VALUE).forEach {
                add(selected.copy(testHeapMb = it))
            }
        }

        invalid.forEach { settings ->
            assertThrows(settings.toString(), IllegalArgumentException::class.java) { repository.save(settings) }
            assertEquals(selected, repository.settings.value)
            assertEquals(stored, preferences.all)
            assertEquals(selected, MobileBuildSettingsRepository(context).settings.value)
        }
    }

    @Test
    fun unsupportedStoredNumbersFallBackIndividuallyWithoutResettingValidFields() {
        assertTrue(
            preferences.edit()
                .putBoolean("enabled", true)
                .putInt("gradle_heap_mb", 2500)
                .putBoolean("daemon_enabled", true)
                .putBoolean("parallel_enabled", true)
                .putInt("max_workers", 0)
                .putInt("test_heap_mb", -1)
                .commit()
        )

        assertEquals(
            MobileBuildSettings(enabled = true, daemonEnabled = true, parallelEnabled = true),
            MobileBuildSettingsRepository(context).settings.value
        )
        assertTrue(preferences.edit().putInt("gradle_heap_mb", 2048).putInt("max_workers", 4).commit())
        assertEquals(
            MobileBuildSettings(enabled = true, gradleHeapMb = 2048, daemonEnabled = true, parallelEnabled = true, maxWorkers = 4),
            MobileBuildSettingsRepository(context).settings.value
        )
    }

    @Test
    fun failedCommitRestoresPreferencesEvenWhenTheFailedCommitChangedTheirInMemoryValues() {
        val original = MobileBuildSettings(gradleHeapMb = 2048, maxWorkers = 2, testHeapMb = 512)
        val next = original.copy(enabled = true, gradleHeapMb = 2560, daemonEnabled = true, parallelEnabled = true, maxWorkers = 4, testHeapMb = 1536)
        MobileBuildSettingsRepository(context).save(original)
        val stored = preferences.all.toMap()
        val failingPreferences = mockk<SharedPreferences>()
        every { failingPreferences.getBoolean(any(), any()) } answers {
            preferences.getBoolean(firstArg(), secondArg())
        }
        every { failingPreferences.getInt(any(), any()) } answers {
            preferences.getInt(firstArg(), secondArg())
        }
        var commits = 0
        every { failingPreferences.edit() } answers {
            val delegate = preferences.edit()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putBoolean(any(), any()) } answers {
                delegate.putBoolean(firstArg(), secondArg())
                editor
            }
            every { editor.putInt(any(), any()) } answers {
                delegate.putInt(firstArg(), secondArg())
                editor
            }
            every { editor.commit() } answers {
                assertTrue(delegate.commit())
                commits++
                if (commits == 1) {
                    assertEquals(next, MobileBuildSettingsRepository(context).settings.value)
                    false
                } else {
                    true
                }
            }
            editor
        }
        val failingContext = mockk<Context>()
        every { failingContext.getSharedPreferences("mobile_build_settings", Context.MODE_PRIVATE) } returns failingPreferences
        val repository = MobileBuildSettingsRepository(failingContext)

        val failure = assertThrows(IOException::class.java) { repository.save(next) }

        assertTrue(failure.suppressed.isEmpty())
        assertEquals(2, commits)
        assertEquals(original, repository.settings.value)
        assertEquals(original, MobileBuildSettingsRepository(failingContext).settings.value)
        assertEquals(original, MobileBuildSettingsRepository(context).settings.value)
        assertEquals(stored, preferences.all)
    }

    @Test
    fun failedRestoreIsReportedWithoutPublishingTheRequestedSettings() {
        val original = MobileBuildSettings(gradleHeapMb = 2048, maxWorkers = 2, testHeapMb = 512)
        MobileBuildSettingsRepository(context).save(original)
        val stored = preferences.all.toMap()
        val editor = mockk<SharedPreferences.Editor>()
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.commit() } returns false
        val failingPreferences = mockk<SharedPreferences>()
        every { failingPreferences.getBoolean(any(), any()) } answers {
            preferences.getBoolean(firstArg(), secondArg())
        }
        every { failingPreferences.getInt(any(), any()) } answers {
            preferences.getInt(firstArg(), secondArg())
        }
        every { failingPreferences.edit() } returns editor
        val failingContext = mockk<Context>()
        every { failingContext.getSharedPreferences("mobile_build_settings", Context.MODE_PRIVATE) } returns failingPreferences
        val repository = MobileBuildSettingsRepository(failingContext)

        val failure = assertThrows(IOException::class.java) { repository.save(original.copy(enabled = true)) }

        assertEquals(1, failure.suppressed.size)
        assertTrue(failure.suppressed.single() is IOException)
        assertEquals(original, repository.settings.value)
        assertFalse(repository.settings.value.enabled)
        assertEquals(stored, preferences.all)
        assertEquals(original, MobileBuildSettingsRepository(context).settings.value)
        verify(exactly = 2) { editor.commit() }
    }
}
