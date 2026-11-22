package com.aicode.feature.plugin.presentation

import com.aicode.R
import com.aicode.feature.plugin.data.index.NoMatchingReleaseException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginStoreProblemsTest {
    @Test fun missingReleaseHasItsOwnMessageInsteadOfANetworkError() {
        val problem = PluginStoreViewModel.downloadProblem(NoMatchingReleaseException("matching HTTPS release asset not found"))
        assertEquals("RELEASE_NOT_FOUND", problem.code)
        assertEquals(R.string.plugin_store_release_not_found, problem.messageRes)
        assertEquals("", problem.detail)
    }

    @Test fun downloadFailureDoesNotExposeExceptionMessagesOrSignedUrls() {
        val problem = PluginStoreViewModel.downloadProblem(IOException("download URL contains private query parameters"))
        assertEquals("DOWNLOAD_FAILED", problem.code)
        assertEquals(R.string.plugin_store_download_error, problem.messageRes)
        assertEquals("IOException", problem.detail)
        assertFalse(problem.detail.contains("private"))
    }
}
