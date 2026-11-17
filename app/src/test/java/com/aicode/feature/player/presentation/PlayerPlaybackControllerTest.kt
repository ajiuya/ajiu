package com.aicode.feature.player.presentation

import android.app.Application
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerPlaybackControllerTest {
    private val hosts = mutableListOf<FakeHost>()
    private fun host(id: String) = FakeHost(id).also { hosts += it; PlayerPlaybackController.attach(it) }

    @After fun cleanup() {
        shadowOf(Looper.getMainLooper()).idle()
        hosts.forEach { PlayerPlaybackController.detach(it) }
    }

    @Test fun commandsReachOnlyCurrentOwnerAndPreserveSeekValue() {
        val host = host("current")
        PlayerPlaybackController.dispatch("expired", "play")
        PlayerPlaybackController.dispatch("current", "seek", 42.5)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("seek" to 42.5), host.commands)
    }

    @Test fun replacingHostClosesOldHostButStaleDetachCannotRemoveNewOne() {
        val first = host("first")
        val second = host("second")
        assertTrue(first.stopped)
        PlayerPlaybackController.detach(first)
        assertEquals("second", PlayerPlaybackController.id)
        PlayerPlaybackController.dispatch("first", "stop")
        PlayerPlaybackController.dispatch("second", "play")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("play" to null), second.commands)
    }

    @Test fun observerOnlyReceivesActiveSessionAndCanBeRemoved() {
        val owner = host("owner")
        val events = mutableListOf<Pair<String, PlayerPlaybackState>>()
        val observer = object : PlayerPlaybackController.Observer {
            override fun playbackChanged(id: String, state: PlayerPlaybackState) { events += id to state }
        }
        PlayerPlaybackController.observe(observer)
        PlayerPlaybackController.publish(FakeHost("outsider"), PlayerPlaybackState(playing = true))
        assertEquals(1, events.size)
        PlayerPlaybackController.publish(owner, PlayerPlaybackState(title = "Track", playing = true))
        assertEquals("Track", events.last().second.title)
        PlayerPlaybackController.removeObserver(observer)
        PlayerPlaybackController.publish(owner, PlayerPlaybackState(playing = false))
        assertEquals(2, events.size)
    }

    @Test fun detachedHostReceivesNeitherCommandsNorServiceLoss() {
        val owner = host("owner")
        PlayerPlaybackController.detach(owner)
        PlayerPlaybackController.dispatch("owner", "play")
        PlayerPlaybackController.serviceLost("owner")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(PlayerPlaybackController.id)
        assertTrue(owner.commands.isEmpty())
        assertEquals(0, owner.lostServices)
        assertEquals(PlayerPlaybackState(), PlayerPlaybackController.state)
    }

    @Test fun queuedCommandsAreRecheckedAgainstOwnerAtDeliveryTime() {
        val first = host("first")
        PlayerPlaybackController.dispatch("first", "play")
        val second = host("second")
        PlayerPlaybackController.serviceLost("first")
        PlayerPlaybackController.serviceLost("second")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.commands.isEmpty())
        assertTrue(second.commands.isEmpty())
        assertEquals(0, first.lostServices)
        assertEquals(1, second.lostServices)
    }

    private class FakeHost(override val controllerId: String) : PlayerPlaybackController.Host {
        val commands = mutableListOf<Pair<String, Double?>>()
        var stopped = false
        var lostServices = 0
        override fun command(name: String, value: Double?) { commands += name to value }
        override fun stopFromController() { stopped = true }
        override fun serviceLost() { lostServices++ }
    }
}
