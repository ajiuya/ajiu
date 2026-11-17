package com.aicode.feature.player.presentation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.lang.ref.WeakReference
import java.util.UUID

internal object PlayerPlaybackController {
    interface Host {
        val controllerId: String
        fun command(name: String, value: Double? = null)
        fun stopFromController()
        fun serviceLost()
    }
    interface Observer { fun playbackChanged(id: String, state: PlayerPlaybackState) }
    private var host = WeakReference<Host>(null)
    private var observer = WeakReference<Observer>(null)
    private val handler = Handler(Looper.getMainLooper())
    var state = PlayerPlaybackState()
        private set
    val id: String? get() = host.get()?.controllerId
    fun attach(value: Host) {
        host.get()?.takeIf { it !== value }?.stopFromController()
        host = WeakReference(value); state = PlayerPlaybackState()
    }
    fun publish(value: Host, next: PlayerPlaybackState) {
        if (host.get() !== value) return
        state = next; observer.get()?.playbackChanged(value.controllerId, next)
    }
    fun detach(value: Host) {
        if (host.get() !== value) return
        host.clear(); state = PlayerPlaybackState(); observer.clear()
    }
    fun observe(value: Observer) { observer = WeakReference(value); id?.let { value.playbackChanged(it, state) } }
    fun removeObserver(value: Observer) { if (observer.get() === value) observer.clear() }
    fun dispatch(id: String?, command: String, value: Double? = null) {
        handler.post { host.get()?.takeIf { it.controllerId == id }?.let { if (command == "stop") it.stopFromController() else it.command(command, value) } }
    }
    fun serviceLost(id: String?) { host.get()?.takeIf { it.controllerId == id }?.serviceLost() }
}
internal class PlayerPlaybackCoordinator(context: Context, endpoint: Endpoint) : PlayerPlaybackController.Host {
    interface Endpoint {
        fun playerScript(action: String, value: Double? = null)
        fun renderPlayback(state: PlayerPlaybackState)
        fun closePlayer()
        fun backgroundUnavailable()
    }
    override val controllerId = UUID.randomUUID().toString()
    private val app = context.applicationContext
    private val endpoint = WeakReference(endpoint)
    private var closed = false
    private var foreground = true
    private var pip = false
    var state = PlayerPlaybackState()
        private set
    // Chromium owns audio focus; requesting a second focus here would pause its media element.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) command("pause")
        }
    }
    init {
        PlayerPlaybackController.attach(this)
        ContextCompat.registerReceiver(app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    private val continuationAllowed get() = foreground || pip || state.background
    fun update(next: PlayerPlaybackState) {
        if (closed) return
        var accepted = next.copy(background = state.background)
        if (accepted.playing && !continuationAllowed) { endpoint.get()?.playerScript("pause"); accepted = accepted.copy(playing = false) }
        state = accepted; publish()
    }
    fun setVisibility(visible: Boolean, pictureInPicture: Boolean) {
        foreground = visible; pip = pictureInPicture
        if (!continuationAllowed) pause()
        publish()
    }
    fun setBackground(enabled: Boolean) {
        if (closed || enabled == state.background) return
        state = state.copy(background = enabled); publish()
        if (enabled) try {
            ContextCompat.startForegroundService(app, PlayerPlaybackService.startIntent(app, controllerId))
        } catch (_: RuntimeException) {
            state = state.copy(background = false); publish(); endpoint.get()?.backgroundUnavailable()
        } else {
            if (PlayerPlaybackController.id == controllerId) app.stopService(PlayerPlaybackService.startIntent(app, controllerId))
            if (!continuationAllowed) pause()
        }
    }
    override fun command(name: String, value: Double?) {
        if (closed) return
        when (name) {
            "play" -> if (continuationAllowed) endpoint.get()?.playerScript("play")
            "pause" -> pause()
            "next", "previous" -> endpoint.get()?.playerScript(name)
            "seek" -> if (value != null && value.isFinite()) endpoint.get()?.playerScript("seek", value.coerceAtLeast(0.0).let { if (state.duration > 0) it.coerceAtMost(state.duration) else it })
        }
    }
    fun pause() { endpoint.get()?.playerScript("pause"); state = state.copy(playing = false); publish() }
    private fun publish() {
        if (closed) return
        endpoint.get()?.renderPlayback(state); PlayerPlaybackController.publish(this, state)
    }
    override fun stopFromController() { endpoint.get()?.closePlayer() }
    override fun serviceLost() {
        if (closed || !state.background) return
        state = state.copy(background = false)
        if (!continuationAllowed) pause()
        publish()
    }
    fun close() {
        if (closed) return
        pause(); closed = true
        app.unregisterReceiver(noisyReceiver)
        if (PlayerPlaybackController.id == controllerId) app.stopService(PlayerPlaybackService.startIntent(app, controllerId))
        PlayerPlaybackController.detach(this); endpoint.clear()
    }
}
