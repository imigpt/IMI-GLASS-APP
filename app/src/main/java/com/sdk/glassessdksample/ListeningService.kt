package com.sdk.glassessdksample

import com.sdk.glassessdksample.ui.HotHelper
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ListeningService : Service() {
    companion object {
        const val CHANNEL_ID = "listening_channel"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.sdk.ACTION_STOP_LISTENING"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Acquire wake lock to keep CPU active for audio capture during sleep
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeyIMI::ListeningWakeLock"
        )
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action
        if (intent?.action == ACTION_STOP) {
            try { HotHelper.getInstance(applicationContext).stop() } catch (_: Exception) {}
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Ensure HotHelper is running to continue listening
        try { HotHelper.getInstance(applicationContext).start() } catch (e: Exception) { }

        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

        val pendingOpenFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingOpen = PendingIntent.getActivity(this, 0, openIntent, pendingOpenFlags)

        val stopIntent = Intent(this, ListeningService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(this, 1, stopIntent, pendingOpenFlags)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hey IMI")
            .setContentText("Listening — continue talking")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Stop", pendingStop)
            .build()

        startForeground(NOTIF_ID, notif)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Listening"
            val chan = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW)
            chan.lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }
}
