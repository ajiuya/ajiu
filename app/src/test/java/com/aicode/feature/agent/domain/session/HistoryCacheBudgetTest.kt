package com.aicode.feature.agent.domain.session

import androidx.room.InvalidationTracker
import com.aicode.core.util.ImageDownscaler
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.presentation.AgentAttachment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryCacheBudgetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val database = mockk<AgentDatabase> {
        every { invalidationTracker } returns mockk<InvalidationTracker>(relaxed = true)
    }
    private val persistence = MessagePersistenceUseCase(dao, database)

    @Test
    fun switchingSessionsKeepsOnlyEightCachedHistoriesWithoutChangingTheirText() = runTest {
        coEvery { dao.getMessagesBySessionOnce(any()) } answers {
            val session = firstArg<String>()
            listOf(row(session, "Complete text for $session"))
        }
        val first = persistence.buildHistory("session-0", "[pending]")
        assertSame(first, persistence.buildHistory("session-0", "[pending]"))
        repeat(8) { persistence.buildHistory("session-${it + 1}", "[pending]") }
        assertEquals(8, persistence.cachedHistoryEntries)
        assertTrue(persistence.cachedHistoryBytes in 1..(32L * 1024 * 1024))
        assertEquals(first, persistence.buildHistory("session-0", "[pending]"))
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session-0") }
    }

    @Test
    fun oversizeHistoryIsReturnedWholeButNotKeptInTheCache() = runTest {
        val content = "x".repeat(17 * 1024 * 1024)
        coEvery { dao.getMessagesBySessionOnce("large") } returns listOf(row("large", content))
        val result = persistence.buildHistory("large", "[pending]")
        assertEquals(content, (result.single() as AgentMessage.UserMessage).content)
        assertEquals(0, persistence.cachedHistoryEntries)
        assertEquals(0L, persistence.cachedHistoryBytes)
    }

    @Test
    fun releasingSessionPreventsAnInFlightBuilderFromPublishingOldContent() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { dao.getMessagesBySessionOnce("session") } coAnswers {
            entered.complete(Unit)
            release.await()
            listOf(row("session", "Obsolete content"))
        } andThen listOf(row("session", "Current content"))
        val build = async { persistence.buildHistory("session", "[pending]") }
        entered.await()
        persistence.releaseSessionCache("session")
        release.complete(Unit)
        val result = build.await()
        assertEquals("Current content", (result.single() as AgentMessage.UserMessage).content)
        coVerify(exactly = 2) { dao.getMessagesBySessionOnce("session") }
    }

    @Test
    fun imageEntryEvictionAndReleaseKeepTheByteLedgerExact() = runTest {
        mockkObject(ImageDownscaler)
        try {
            every { ImageDownscaler.downscaleToBase64(any(), any()) } returns ("image/png" to "encoded")
            val files = (0..12).map { index -> temporary.newFile("image-$index.png").apply { writeBytes(byteArrayOf(index.toByte())) } }
            coEvery { dao.getMessagesBySessionOnce(any()) } answers {
                val index = firstArg<String>().substringAfter("session-").toInt()
                val file = files[index]
                val attachment = AgentAttachment(file.name, "~/workspace/${file.name}", file.path, "image/png", file.length(), true)
                listOf(row("session-$index", "Image $index").copy(attachmentsJson = Json.encodeToString(listOf(attachment))))
            }
            files.indices.forEach { persistence.buildHistory("session-$it", "[pending]") }
            val expected = files.drop(1).sumOf { file ->
                val key = "${file.path}:${file.length()}:${file.lastModified()}"
                128L + (key.length.toLong() + "image/png".length + "encoded".length) * 2
            }
            assertEquals(12, persistence.cachedImageEntries)
            assertEquals(expected, persistence.cachedImageBytes)
            verify(exactly = 13) { ImageDownscaler.downscaleToBase64(any(), any()) }
            persistence.buildHistory("session-0", "[pending]")
            verify(exactly = 14) { ImageDownscaler.downscaleToBase64(any(), any()) }
            assertEquals(12, persistence.cachedImageEntries)
            persistence.releaseSessionCache("session-0")
            assertEquals(0, persistence.cachedImageEntries)
            assertEquals(0L, persistence.cachedImageBytes)
            assertFalse(files.any { !it.exists() })
        } finally {
            unmockkObject(ImageDownscaler)
        }
    }

    private fun row(session: String, content: String) =
        AgentMessageEntity("user-$session", session, "USER", content, 1)
}
