package com.aicode.feature.agent.presentation.component

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.player.domain.PlayerRequestCodec
import com.aicode.feature.player.presentation.PlayerActivity
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class MediaAttachmentOpeningTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val files = mockk<FileAccessProvider>()

    @Test fun videoAttachmentOpensPlayerWithoutDownloadingThroughTheUi() = runTest {
        openSentAttachment(application, attachment("clip.mp4", "video/mp4"), files)

        val intent = shadowOf(application).nextStartedActivity
        assertEquals(PlayerActivity::class.java.name, intent.component?.className)
        val request = requireNotNull(PlayerRequestCodec.decode(requireNotNull(intent.getStringExtra(PlayerActivity.EXTRA_REQUEST))))
        assertEquals("~/workspace/media/clip.mp4", request.src)
        assertEquals("~/workspace/media", request.root)
        verify(exactly = 0) { files.copyToLocal(any()) }
    }

    @Test fun audioMimeOpensExtensionlessSourceAsAudio() = runTest {
        openSentAttachment(application, attachment("recording", "audio/ogg"), files)

        val intent = shadowOf(application).nextStartedActivity
        val request = requireNotNull(PlayerRequestCodec.decode(requireNotNull(intent.getStringExtra(PlayerActivity.EXTRA_REQUEST))))
        assertEquals("audio", request.items.single().kind)
        assertEquals("~/workspace/media/recording", request.items.single().url)
        verify(exactly = 0) { files.copyToLocal(any()) }
    }

    @Test fun mediaUsesTheCurrentContainerPathInsteadOfAStaleHostCopy() = runTest {
        val attachment = attachment("episode.webm", "video/webm").copy(localPath = "/unrelated/stale/episode.webm")
        openSentAttachment(application, attachment, files)

        val intent = shadowOf(application).nextStartedActivity
        val raw = requireNotNull(intent.getStringExtra(PlayerActivity.EXTRA_REQUEST))
        assertTrue(raw.contains("~/workspace/media/episode.webm"))
        assertTrue(!raw.contains("/unrelated/stale"))
        verify(exactly = 0) { files.copyToLocal(any()) }
    }

    @Test fun invalidMediaPathIsRejectedWithoutLaunchingOrReadingAFile() = runTest {
        openSentAttachment(application, attachment("clip.mp4", "video/mp4").copy(containerPath = "~/workspace/../clip.mp4"), files)

        assertNull(shadowOf(application).nextStartedActivity)
        verify(exactly = 0) { files.copyToLocal(any()) }
    }

    private fun attachment(name: String, mime: String) = AgentAttachment(
        fileName = name,
        containerPath = "~/workspace/media/$name",
        localPath = "",
        mimeType = mime,
        sizeBytes = 10,
        isImage = false
    )
}
