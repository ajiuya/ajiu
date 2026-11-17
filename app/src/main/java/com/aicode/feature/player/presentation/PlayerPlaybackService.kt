package com.aicode.feature.player.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import com.aicode.R

internal class PlayerPlaybackService : Service(), PlayerPlaybackController.Observer {
    private lateinit var mediaSession: MediaSession
    private var ownerId: String? = null
    private var foregroundStarted = false
    private var notificationSignature: Pair<String, Boolean>? = null
    private var metadataSignature: Pair<String, Long>? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.player_notification_channel), NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); setShowBadge(false) })
        mediaSession = MediaSession(this, "AjiuPlayer").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = send("play")
                override fun onPause() = send("pause")
                override fun onSkipToNext() = send("next")
                override fun onSkipToPrevious() = send("previous")
                override fun onSeekTo(pos: Long) = send("seek", pos / 1000.0)
                override fun onStop() = send("stop")
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    return handleMediaKey(event)
                }
            })
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(EXTRA_OWNER)
        when (playerServiceStartDecision(requestedId, PlayerPlaybackController.id, PlayerPlaybackController.state.background, foregroundStarted)) {
            PlayerServiceStartDecision.IGNORE_STALE -> return START_NOT_STICKY
            PlayerServiceStartDecision.STOP_ORPHAN -> {
                if (!foregroundStarted) { ownerId = requestedId; startForeground(NOTIFICATION_ID, notification(PlayerPlaybackController.state)); foregroundStarted = true }
                stopSelf(startId); return START_NOT_STICKY
            }
            PlayerServiceStartDecision.ACCEPT -> Unit
        }
        if (ownerId != requestedId) { metadataSignature = null; notificationSignature = null }
        ownerId = requestedId
        startForeground(NOTIFICATION_ID, notification(PlayerPlaybackController.state)); foregroundStarted = true
        mediaSession.isActive = true
        mediaSession.setSessionActivity(contentIntent()); mediaSession.setMediaButtonReceiver(actionIntent(Intent.ACTION_MEDIA_BUTTON))
        PlayerPlaybackController.observe(this)
        when (intent?.action) {
            ACTION_PLAY -> send("play"); ACTION_PAUSE -> send("pause"); ACTION_NEXT -> send("next")
            ACTION_PREVIOUS -> send("previous"); ACTION_STOP -> send("stop")
            Intent.ACTION_MEDIA_BUTTON -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)?.let(::handleMediaKey)
            }
        }
        return START_NOT_STICKY
    }
    private fun send(command: String, value: Double? = null) { PlayerPlaybackController.dispatch(ownerId, command, value) }
    private fun handleMediaKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> send("play"); KeyEvent.KEYCODE_MEDIA_PAUSE -> send("pause")
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> send(if (PlayerPlaybackController.state.playing) "pause" else "play")
            KeyEvent.KEYCODE_MEDIA_NEXT -> send("next"); KeyEvent.KEYCODE_MEDIA_PREVIOUS -> send("previous"); KeyEvent.KEYCODE_MEDIA_STOP -> send("stop")
            else -> return false
        }
        return true
    }
    override fun playbackChanged(id: String, state: PlayerPlaybackState) {
        if (id != ownerId) return
        if (!state.background) { stopSelf(); return }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(
            if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            (state.position * 1000).toLong(), if (state.playing) 1f else 0f, SystemClock.elapsedRealtime()).build())
        if (metadataSignature != state.metadataSignature) {
            metadataSignature = state.metadataSignature
            mediaSession.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifBlank { getString(R.string.player_title) }).putLong(MediaMetadata.METADATA_KEY_DURATION, (state.duration * 1000).toLong()).build())
        }
        if (notificationSignature != (state.title to state.playing) && foregroundStarted) {
            notificationSignature = state.title to state.playing
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
        }
    }
    private fun notification(state: PlayerPlaybackState): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_thinking).setContentTitle(state.title.ifBlank { getString(R.string.player_title) })
        .setContentText(getString(if (state.playing) R.string.player_playing else R.string.player_paused))
        .setContentIntent(contentIntent()).setDeleteIntent(actionIntent(ACTION_STOP)).setCategory(Notification.CATEGORY_TRANSPORT)
        .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(state.playing)
        .addAction(Notification.Action.Builder(R.drawable.ic_thinking, getString(R.string.player_previous), actionIntent(ACTION_PREVIOUS)).build())
        .addAction(Notification.Action.Builder(R.drawable.ic_thinking, getString(if (state.playing) R.string.player_pause else R.string.player_play), actionIntent(if (state.playing) ACTION_PAUSE else ACTION_PLAY)).build())
        .addAction(Notification.Action.Builder(R.drawable.ic_thinking, getString(R.string.player_next), actionIntent(ACTION_NEXT)).build())
        .addAction(Notification.Action.Builder(R.drawable.ic_thinking, getString(R.string.player_close), actionIntent(ACTION_STOP)).build())
        .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2)).build()
    private fun contentIntent() = PendingIntent.getActivity(this, ownerId.hashCode(), Intent(this, PlayerActivity::class.java)
        .setAction(PlayerActivity.ACTION_RESTORE).putExtra(EXTRA_OWNER, ownerId)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun actionIntent(action: String) = PendingIntent.getService(this, 31 * ownerId.hashCode() + action.hashCode(),
        startIntent(this, ownerId).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun onTaskRemoved(rootIntent: Intent?) { send("stop"); stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        PlayerPlaybackController.removeObserver(this); PlayerPlaybackController.serviceLost(ownerId)
        if (::mediaSession.isInitialized) { mediaSession.isActive = false; mediaSession.setCallback(null); mediaSession.release() }
        stopForeground(STOP_FOREGROUND_REMOVE); getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        foregroundStarted = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        internal const val EXTRA_OWNER = "com.aicode.player.OWNER"
        private const val CHANNEL = "ajiu_player_playback"
        private const val NOTIFICATION_ID = 19733
        private const val ACTION_PLAY = "com.aicode.player.PLAY"
        private const val ACTION_PAUSE = "com.aicode.player.PAUSE"
        private const val ACTION_NEXT = "com.aicode.player.NEXT"
        private const val ACTION_PREVIOUS = "com.aicode.player.PREVIOUS"
        private const val ACTION_STOP = "com.aicode.player.STOP"
        internal fun startIntent(context: Context, id: String?): Intent = Intent(context, PlayerPlaybackService::class.java).putExtra(EXTRA_OWNER, id)
    }
}
