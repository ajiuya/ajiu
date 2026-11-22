package com.aicode.feature.agent.presentation.component

import com.aicode.feature.agent.presentation.AgentAttachment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatAttachmentPreviewRequestTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun validCacheFileIsUsedAsAStandalonePreviewSnapshot() {
        val cache = temporary.newFolder("cache")
        val file = File(cache, "artifact.apk").apply { writeText("apk") }

        val request = attachmentPreviewRequest(attachment(file.absolutePath), cache)

        assertEquals("~/workspace/artifact.apk", request.path)
        assertEquals(file.canonicalPath, request.cachedPath)
        assertEquals("artifact.apk", request.title)
    }

    @Test
    fun missingCacheFileFallsBackToTheCurrentWorkspacePath() {
        val cache = temporary.newFolder("cache")

        val request = attachment(File(cache, "expired.apk").absolutePath).let { attachmentPreviewRequest(it, cache) }

        assertEquals("~/workspace/artifact.apk", request.path)
        assertTrue(request.cachedPath.isEmpty())
    }

    @Test
    fun fileOutsideCacheCannotBecomeAnAuthorizedPreviewSnapshot() {
        val cache = temporary.newFolder("cache")
        val outside = File(temporary.root, "outside.apk").apply { writeText("apk") }

        val request = attachmentPreviewRequest(attachment(outside.absolutePath), cache)

        assertEquals("~/workspace/artifact.apk", request.path)
        assertTrue(request.cachedPath.isEmpty())
    }

    private fun attachment(localPath: String) = AgentAttachment(
        fileName = "artifact.apk",
        containerPath = "~/workspace/artifact.apk",
        localPath = localPath,
        mimeType = "application/vnd.android.package-archive",
        sizeBytes = 3,
        isImage = false,
    )
}
