package com.aicode.core.util

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ApkInstallerTest {
    @Test
    fun installIntentUsesPackageArchiveMimeAndReadGrant() {
        val uri = Uri.parse("content://com.ajiu.fileprovider/cache/artifact.apk")

        val intent = ApkInstaller.installIntent(uri)

        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals(uri, intent.data)
        assertEquals(ApkInstaller.APK_MIME_TYPE, intent.type)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
