package com.aicode.feature.filepreview.presentation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aicode.feature.filepreview.data.PreviewPaths
import com.aicode.feature.filepreview.data.PreviewSource
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExternalFileIntentTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val uri = Uri.parse("content://com.ajiu.fileprovider/cache/artifact.apk")

    @Test
    fun externalShareChooserCarriesStreamUriGrantAndNewTaskFlag() {
        val chooser = externalShareIntent(application, uri, "application/vnd.android.package-archive", "artifact.apk")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send?.action)
        assertEquals(uri, send?.getParcelableExtra(Intent.EXTRA_STREAM))
        assertTrue(send!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(send.clipData)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun externalViewCarriesUriMimeGrantAndNewTaskFlag() {
        val intent = externalViewIntent(uri, "application/vnd.android.package-archive")

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNotNull(intent.clipData)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun externalLaunchDelegatesToSystemWithoutPackageVisibilityPreflight() {
        val context = mockk<Context>(relaxed = true)
        val intent = externalViewIntent(uri, "application/vnd.android.package-archive")

        startExternalIntent(context, intent)

        verify(exactly = 0) { context.packageManager }
        verify(exactly = 1) { context.startActivity(intent) }
    }

    @Test
    fun sharedFileUsesTheExistingLocalSnapshotWithoutCopyingIt() = runBlocking {
        val file = File(application.cacheDir, "artifact.apk").canonicalFile.apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val source = PreviewSource(
            PreviewPaths.entry("artifact.apk"), file.length(), Job(), null, null, file,
            application.cacheDir.canonicalFile,
        ) {}

        try {
            assertEquals(file, sharedFile(application.cacheDir, source, source.entryPath, "published-name.apk"))
            assertEquals(4L, file.length())
        } finally {
            source.close()
            file.delete()
        }
    }
}
