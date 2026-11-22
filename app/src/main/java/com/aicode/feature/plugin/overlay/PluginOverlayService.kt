package com.aicode.feature.plugin.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PluginOverlayService : Service() {
    @Inject lateinit var overlays: PluginOverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> overlays.onScreenStateChanged(false)
                Intent.ACTION_SCREEN_ON -> overlays.onScreenStateChanged(true)
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.plugins_overlay_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.plugins_overlay_channel_desc); setShowBadge(false)
        })
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        overlays.onScreenStateChanged(getSystemService(PowerManager::class.java)?.isInteractive ?: true)
        if (foreground()) overlays.onServiceStarted()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CLOSE_ALL) { overlays.closeAll(); stopSelf(); return START_NOT_STICKY }
        if (!foreground()) return START_NOT_STICKY
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (!overlays.hasOverlays()) stopSelf() }, 1500)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { overlays.closeAll(); stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        overlays.onServiceStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun foreground(): Boolean {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this, PluginOverlayService::class.java).setAction(CLOSE_ALL), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.plugins_overlay_notification_title)).setContentText(getString(R.string.plugins_overlay_notification_text))
            .setContentIntent(open).addAction(0, getString(R.string.plugins_overlay_notification_close), close).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        return runCatching { startForeground(41, notification) }.onFailure { stopSelf() }.isSuccess
    }
    private companion object {
        const val CHANNEL = "plugin_overlay"
        const val CLOSE_ALL = "com.aicode.action.CLOSE_PLUGIN_OVERLAYS"
    }
}
