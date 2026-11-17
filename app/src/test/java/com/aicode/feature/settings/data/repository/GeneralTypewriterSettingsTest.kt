package com.aicode.feature.settings.data.repository

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneralTypewriterSettingsTest {
    companion object {
        @ClassRule
        @JvmField
        val temporaryFolder = TemporaryFolder()
        private val context: Context by lazy {
            val filesDir = temporaryFolder.newFolder("files")
            mockk<Context>().also {
                every { it.applicationContext } returns it
                every { it.filesDir } returns filesDir
            }
        }
    }

    @Test
    fun defaultsToThirtyAndPersistsAcrossRepositoryInstances() = runTest {
        val repository = GeneralSettingsRepository(context)
        repository.setTypewriterCharsPerSecond(30)
        assertEquals(30, repository.typewriterCharsPerSecond())
        repository.setTypewriterCharsPerSecond(75)
        assertEquals(75, repository.typewriterCharsPerSecondFlow.first())
        assertEquals(75, GeneralSettingsRepository(context).typewriterCharsPerSecond())
    }

    @Test
    fun playbackRateIsBoundedWithoutChangingOtherPreferences() = runTest {
        val repository = GeneralSettingsRepository(context)
        repository.setEnterToSend(true)
        repository.setCompactionThresholdPercent(85)
        repository.setTypewriterCharsPerSecond(0)
        assertEquals(10, repository.typewriterCharsPerSecond())
        repository.setTypewriterCharsPerSecond(1_000)
        assertEquals(120, repository.typewriterCharsPerSecond())
        assertEquals(true, repository.enterToSendFlow.first())
        assertEquals(85, repository.compactionThresholdPercent())
    }
}
