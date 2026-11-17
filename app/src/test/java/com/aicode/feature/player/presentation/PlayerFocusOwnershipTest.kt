package com.aicode.feature.player.presentation

import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerFocusOwnershipTest {
    private val coordinators = mutableListOf<PlayerPlaybackCoordinator>()
    private val endpoints = mutableListOf<Endpoint>()
    private fun coordinator(): Pair<PlayerPlaybackCoordinator, Endpoint> {
        val endpoint = Endpoint().also { endpoints += it }
        val player = PlayerPlaybackCoordinator(RuntimeEnvironment.getApplication(), endpoint).also { coordinators += it }
        return player to endpoint
    }

    @Before fun grantReceiverPermission() {
        val app: Application = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @After fun cleanup() {
        coordinators.forEach { it.close() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun hostNeverRequestsOrAbandonsAudioFocus() {
        val app: Application = RuntimeEnvironment.getApplication()
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        val (player, _) = coordinator()
        player.update(PlayerPlaybackState(playing = true, hasVideo = true, readyState = 4))
        player.command("play")
        player.command("pause")
        player.setBackground(true)
        player.setBackground(false)
        player.setVisibility(false, false)
        player.setVisibility(true, false)
        player.close()
        assertNull("Host must not request audio focus", audio.lastAudioFocusRequest)
        assertNull("Host must not abandon audio focus", audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun foregroundPlaybackIsMirroredWithoutPausing() {
        val (player, endpoint) = coordinator()
        player.update(PlayerPlaybackState(playing = true, hasVideo = true, readyState = 4))
        assertTrue(player.state.playing)
        player.setVisibility(true, false)
        assertTrue(player.state.playing)
        assertFalse(player.state.background)
        assertTrue(endpoint.commands.isEmpty())
        assertTrue(endpoint.states.last().playing)
    }

    @Test fun leavingForegroundWithoutBackgroundPermissionPauses() {
        val (player, endpoint) = coordinator()
        player.update(PlayerPlaybackState(playing = true, hasVideo = true, readyState = 4))
        player.setVisibility(false, false)
        assertFalse(player.state.playing)
        assertEquals(listOf("pause" to null), endpoint.commands)
    }

    @Test fun pictureInPictureAllowsPlaybackWithoutEnablingBackgroundService() {
        val (player, endpoint) = coordinator()
        player.update(PlayerPlaybackState(playing = true, hasVideo = true, readyState = 4))
        player.setVisibility(false, true)
        assertTrue(player.state.playing)
        assertFalse(player.state.background)
        assertTrue(endpoint.commands.isEmpty())
        player.setVisibility(false, false)
        assertFalse(player.state.playing)
    }

    @Test fun seekClampsFiniteValuesAndRejectsNonFiniteValues() {
        val (player, endpoint) = coordinator()
        player.update(PlayerPlaybackState(duration = 100.25))
        player.command("seek", -10.0)
        player.command("seek", 200.0)
        player.command("seek", 42.125)
        player.command("seek", Double.NaN)
        player.command("seek", Double.POSITIVE_INFINITY)
        player.command("seek", null)
        assertEquals(listOf("seek" to 0.0, "seek" to 100.25, "seek" to 42.125), endpoint.commands)
    }

    @Test fun noisyBroadcastPausesAndClosedCoordinatorDoesNotReceiveIt() {
        val app: Application = RuntimeEnvironment.getApplication()
        val (player, endpoint) = coordinator()
        player.update(PlayerPlaybackState(playing = true))
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(player.state.playing)
        assertEquals(listOf("pause" to null), endpoint.commands)
        player.close()
        val count = endpoint.commands.size
        app.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, endpoint.commands.size)
    }

    private class Endpoint : PlayerPlaybackCoordinator.Endpoint {
        val commands = mutableListOf<Pair<String, Double?>>()
        val states = mutableListOf<PlayerPlaybackState>()
        override fun playerScript(action: String, value: Double?) { commands += action to value }
        override fun renderPlayback(state: PlayerPlaybackState) { states += state }
        override fun closePlayer() = Unit
        override fun backgroundUnavailable() = Unit
    }
}
