package com.sdk.glassessdksample.ui

import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object BatteryStatusStore {

    const val ACTION_BATTERY_UPDATED = "com.sdk.glassessdksample.action.BATTERY_UPDATED"
    const val EXTRA_BATTERY_LEVEL = "extra_battery_level"
    const val EXTRA_BATTERY_UPDATED_AT = "extra_battery_updated_at"

    private const val PREFS_NAME = "imi_prefs"
    private const val KEY_BATTERY_LEVEL = "glass_battery_level"
    private const val KEY_BATTERY_UPDATED_AT = "glass_battery_updated_at"

    fun saveBatteryLevel(context: Context, level: Int) {
        val safeLevel = level.coerceIn(0, 100)
        val updatedAt = System.currentTimeMillis()

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BATTERY_LEVEL, safeLevel)
            .putLong(KEY_BATTERY_UPDATED_AT, updatedAt)
            .apply()

        val intent = Intent(ACTION_BATTERY_UPDATED).apply {
            putExtra(EXTRA_BATTERY_LEVEL, safeLevel)
            putExtra(EXTRA_BATTERY_UPDATED_AT, updatedAt)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun getBatteryLevel(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BATTERY_LEVEL)) {
            return null
        }

        val level = prefs.getInt(KEY_BATTERY_LEVEL, -1)
        return level.takeIf { it in 0..100 }
    }

    fun getBatteryUpdatedAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BATTERY_UPDATED_AT)) {
            return null
        }

        val updatedAt = prefs.getLong(KEY_BATTERY_UPDATED_AT, -1L)
        return updatedAt.takeIf { it > 0L }
    }
}
