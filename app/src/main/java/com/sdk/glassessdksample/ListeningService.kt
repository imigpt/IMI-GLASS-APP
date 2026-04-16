package com.sdk.glassessdksample

import com.sdk.glassessdksample.ui.HotHelper
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class ListeningService : Service() {
    companion object {
        const val ACTION_STOP = "com.sdk.ACTION_STOP_LISTENING"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
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
            stopSelf()
            return START_NOT_STICKY
        }

        // Ensure HotHelper is running to continue listening
        try { HotHelper.getInstance(applicationContext).start() } catch (e: Exception) { }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
