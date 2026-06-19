package com.sdk.glassessdksample

import com.sdk.glassessdksample.ui.BluetoothEvent
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
import android.util.Log
import androidx.core.app.NotificationCompat
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ListeningService : Service() {
    companion object {
        const val ACTION_STOP = "com.sdk.ACTION_STOP_LISTENING"
        const val ACTION_WAKE_WORD_DETECTED = "com.sdk.glassessdksample.ACTION_WAKE_WORD_DETECTED"
        private const val TAG = "ListeningService"
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

        // Subscribe to wake word events so we can forward them to MainActivity even
        // when the Activity is stopped (minimised / screen off).
        EventBus.getDefault().register(this)
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
        EventBus.getDefault().unregister(this)
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * Receive HotHelper's wake-word event on the background thread.
     * If MainActivity is alive it will also receive this (it subscribes in onStart/onStop).
     * But when it's stopped we bring it back to the foreground here.
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onBluetoothEvent(event: BluetoothEvent) {
        if (event.type == BluetoothEvent.EventType.VOICE_TEXT) {
            val text = event.data as? String ?: return
            if (text.trim().lowercase() == "wake up") {
                Log.i(TAG, "🔥 Wake word received in ListeningService — bringing MainActivity to foreground")
                val activityIntent = Intent(applicationContext, MainActivity::class.java).apply {
                    action = ACTION_WAKE_WORD_DETECTED
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(activityIntent)
            }
        }
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
