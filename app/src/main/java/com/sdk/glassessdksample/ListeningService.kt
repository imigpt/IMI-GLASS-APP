package com.sdk.glassessdksample

import com.sdk.glassessdksample.ui.HotHelper
import android.app.Notification
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
        const val ACTION_STOP = "com.sdk.ACTION_STOP_LISTENING"
        private const val CHANNEL_ID = "imi_listening_channel"
        private const val NOTIF_ID = 1001
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HeyIMI::ListeningWakeLock"
        )
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            try { HotHelper.getInstance(applicationContext).stop() } catch (_: Exception) {}
            wakeLock?.let { if (it.isHeld) it.release() }
            stopSelf()
            return START_NOT_STICKY
        }

        try { HotHelper.getInstance(applicationContext).start() } catch (_: Exception) {}

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IMI Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "IMI wake word detection"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ListeningService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMI is listening")
            .setContentText("Say \"Hey IMI\" to start")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }
}
