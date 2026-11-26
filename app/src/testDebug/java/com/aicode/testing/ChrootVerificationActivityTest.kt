package com.aicode.testing

import android.app.Application
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.aicode.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ChrootVerificationActivityTest {
    @Test
    @Config(qualifiers = "zh")
    fun displaysChineseVerificationMessage() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val message = content.getChildAt(0) as TextView

            assertEquals(1, content.childCount)
            assertEquals(activity.getString(R.string.chroot_verification_visible_message), message.text.toString())
            assertEquals("正在验证chroot，请保持此页面可见，结束后自动关闭", message.text.toString())
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun displaysEnglishVerificationMessage() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val message = content.getChildAt(0) as TextView

            assertEquals(activity.getString(R.string.chroot_verification_visible_message), message.text.toString())
            assertEquals(
                "Verifying chroot. Please keep this page visible; it will close automatically when verification ends.",
                message.text.toString(),
            )
        }
    }

    @Test
    fun keepsScreenOnAfterStartingAndWhileVisible() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).use { controller ->
            controller.create()
            val activity = controller.get()
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            controller.start()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            controller.resume().visible()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    @Test
    fun clearsScreenOnWhenStopped() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            controller.pause()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            controller.stop()

            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    @Test
    fun restoresScreenOnWhenVisibleAgain() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            controller.pause().stop()
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            controller.restart().start().resume().visible()

            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            assertFalse(activity.isFinishing)
        }
    }

    @Test
    fun destroyExplicitlyClearsTheWindowFlag() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).use { controller ->
            controller.create()
            val activity = controller.get()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            controller.destroy()

            assertTrue(activity.isDestroyed)
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }

    @Test
    fun finishReleasesScreenOnThroughStopAndDestroy() {
        Robolectric.buildActivity(ChrootVerificationActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

            activity.finish()
            assertTrue(activity.isFinishing)
            controller.pause().stop()
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            controller.destroy()

            assertTrue(activity.isDestroyed)
            assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
    }
}
