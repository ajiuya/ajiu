package com.aicode.core.util

import android.content.Context
import android.content.pm.ApplicationInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLoggerWorkerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun formattingAndMediaRedactionStayOnTheOrderedWorkerAndFlushKeepsAllRecords() {
        val fields = listOf("logDir", "writer", "writerDate", "flushScheduled").associateWith { name ->
            FileLogger::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val executorField = FileLogger::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }
        val executor = executorField.get(FileLogger) as ScheduledThreadPoolExecutor
        FileLogger.flushSync()
        val previous = fields.mapValues { it.value.get(FileLogger) }
        val oldLevel = FileLogger.minLevel
        val workerThreads = CopyOnWriteArrayList<String>()
        mockkObject(MediaRedactor)
        try {
            executor.submit {
                fields.getValue("writer").set(FileLogger, null)
                fields.getValue("writerDate").set(FileLogger, null)
                fields.getValue("flushScheduled").set(FileLogger, false)
                fields.getValue("logDir").set(FileLogger, null)
            }.get(5, TimeUnit.SECONDS)
            val context = mockk<Context> {
                every { getExternalFilesDir(null) } returns temporary.root
                every { filesDir } returns temporary.root
                every { applicationInfo } returns ApplicationInfo().apply { flags = ApplicationInfo.FLAG_DEBUGGABLE }
            }
            every { MediaRedactor.redact(any()) } answers {
                workerThreads += Thread.currentThread().name
                callOriginal()
            }
            FileLogger.setMinLevel(LogLevel.INFO)
            FileLogger.init(context)
            FileLogger.i("worker-test", "First record")
            FileLogger.e("worker-test", "Second data:image/png;base64," + "a".repeat(2_048), IllegalStateException("Expected diagnostic"))
            FileLogger.i("worker-test", "Third record")
            FileLogger.flushSync()

            val text = File(temporary.root, "logs").listFiles()!!.single { it.name.startsWith("log-") }.readText()
            assertTrue(text.indexOf("First record") < text.indexOf("Second data:"))
            assertTrue(text.indexOf("Second data:") < text.indexOf("Third record"))
            assertTrue(text.contains("[base64 omitted: 2048 chars]"))
            assertTrue(text.contains("Expected diagnostic"))
            assertFalse(text.contains("a".repeat(2_048)))
            assertEquals(4, workerThreads.size)
            assertTrue(workerThreads.all { it == "file-logger" })
        } finally {
            FileLogger.flushSync()
            executor.submit {
                (fields.getValue("writer").get(FileLogger) as? BufferedWriter)?.close()
                previous.forEach { (name, value) -> fields.getValue(name).set(FileLogger, value) }
            }.get(5, TimeUnit.SECONDS)
            FileLogger.setMinLevel(oldLevel)
            unmockkObject(MediaRedactor)
        }
    }
}
