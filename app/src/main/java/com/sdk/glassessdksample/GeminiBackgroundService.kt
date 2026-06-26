package com.sdk.glassessdksample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sdk.glassessdksample.ui.GeminiLiveService

/**
 * GeminiBackgroundService
 *
 * Keeps GeminiLiveService alive regardless of whether the app is in the
 * foreground, minimised, or the screen is off.
 *
 * Lifecycle:
 *   - Started (startForegroundService) from MainActivity.onCreate so it
 *     survives the Activity being destroyed / screen-off.
 *   - Bound (bindService) by MainActivity so the Activity can get a direct
 *     reference to GeminiLiveService for callbacks.
 *   - Stopped explicitly when the user signs out or the app is fully closed.
 *
 * Wake lock:
 *   PARTIAL_WAKE_LOCK keeps the CPU running while the screen is off so
 *   audio capture + WebSocket stay active (same as ListeningService does for
 *   wake-word detection).
 */
class GeminiBackgroundService : Service() {

    companion object {
        private const val TAG = "GeminiBgService"
        private const val CHANNEL_ID = "imi_gemini_live_channel"
        private const val NOTIF_ID = 1002

        const val ACTION_STOP = "com.aselea.imiglass.ACTION_STOP_GEMINI_BG"
    }

    // Binder given to clients (MainActivity)
    inner class LocalBinder : Binder() {
        fun getGeminiLiveService(): GeminiLiveService? = geminiLiveService
        fun setGeminiLiveService(svc: GeminiLiveService) { geminiLiveService = svc }
        fun clearGeminiLiveService() { geminiLiveService = null }
    }

    private val binder = LocalBinder()

    // The actual GeminiLiveService instance — created by MainActivity (which
    // supplies the callbacks) and handed to us via the binder.
    @Volatile
    private var geminiLiveService: GeminiLiveService? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // This is a foregroundServiceType="microphone" service. On Android 14+
        // (API 34) startForeground() throws if RECORD_AUDIO isn't granted. The
        // app normally defers starting this service until the permission is
        // granted, but the OS can also auto-restart a STICKY service or auto-
        // create it via a bind — so we guard here too and never let it crash
        // the process. If we can't legally run as a microphone FGS, stop self.
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "⏹️ RECORD_AUDIO not granted — cannot run microphone FGS, stopping self")
            stopSelf()
            return
        }

        try {
            startForeground(NOTIF_ID, buildNotification())
            acquireWakeLock()
            Log.d(TAG, "✅ GeminiBackgroundService created — wake lock acquired")
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException.
            Log.e(TAG, "❌ startForeground failed — stopping self: ${e.message}", e)
            stopSelf()
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "🛑 Stop action received — shutting down")
            geminiLiveService?.destroy()
            geminiLiveService = null
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }
        // Restart automatically if killed by the OS so the session recovers.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        geminiLiveService?.destroy()
        geminiLiveService = null
        releaseWakeLock()
        Log.d(TAG, "GeminiBackgroundService destroyed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Wake lock
    // ─────────────────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HeyIMI::GeminiLiveWakeLock"
            ).apply {
                // No timeout — held until explicitly released so audio never
                // drops mid-conversation when the screen turns off.
                acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IMI AI Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "IMI AI is listening and responding"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification brings the app back to the foreground.
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GeminiBackgroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMI AI is active")
            .setContentText("Listening in the background — say \"Hey IMI\"")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }
}
