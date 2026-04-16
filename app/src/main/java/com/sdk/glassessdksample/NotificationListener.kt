package com.sdk.glassessdksample

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

data class NotificationItem(
    val appName: String,
    val title: String?,
    val text: String?,
    val time: Long,
    val packageName: String
)

class NotificationListener : NotificationListenerService() {

    private val TAG = "NotificationListener"
    private val MAX_NOTIFICATIONS = 50 // Keep last 50 notifications

    companion object {
        const val PREFS_NAME = "NotificationPrefs"
        const val KEY_NOTIFICATIONS = "notifications"
        const val ACTION_NEW_NOTIFICATION = "com.sdk.glassessdksample.NEW_NOTIFICATION"

        fun getRecentNotifications(context: Context): List<NotificationItem> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_NOTIFICATIONS, "[]") ?: "[]"
            val type = object : TypeToken<List<NotificationItem>>() {}.type
            return Gson().fromJson(json, type) ?: emptyList()
        }

        fun clearNotifications(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_NOTIFICATIONS, "[]").apply()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return
            val packageName = sbn.packageName ?: return

            // Skip our own notifications
            if (packageName == applicationContext.packageName) return

            // Get app name
            val pm = packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) {
                packageName
            }

            // Extract notification content
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

            // Skip empty notifications
            if (title.isNullOrBlank() && text.isNullOrBlank()) return

            val notificationItem = NotificationItem(
                appName = appName,
                title = title,
                text = text,
                time = System.currentTimeMillis(),
                packageName = packageName
            )

            Log.d(TAG, "📬 New notification: $appName - $title")

            // Save to preferences
            saveNotification(notificationItem)

            // Broadcast to MainActivity if needed
            val intent = Intent(ACTION_NEW_NOTIFICATION)
            intent.putExtra("appName", appName)
            intent.putExtra("title", title)
            intent.putExtra("text", text)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optional: Handle notification removal
    }

    private fun saveNotification(item: NotificationItem) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_NOTIFICATIONS, "[]") ?: "[]"
            val type = object : TypeToken<MutableList<NotificationItem>>() {}.type
            val notifications: MutableList<NotificationItem> = Gson().fromJson(json, type) ?: mutableListOf()

            // Add new notification at the beginning
            notifications.add(0, item)

            // Keep only the last MAX_NOTIFICATIONS
            if (notifications.size > MAX_NOTIFICATIONS) {
                notifications.subList(MAX_NOTIFICATIONS, notifications.size).clear()
            }

            // Save back to preferences
            val newJson = Gson().toJson(notifications)
            prefs.edit().putString(KEY_NOTIFICATIONS, newJson).apply()

        } catch (e: Exception) {
            Log.e(TAG, "Error saving notification: ${e.message}")
        }
    }

    fun formatNotificationsForAI(context: Context, limit: Int = 10): String {
        val notifications = getRecentNotifications(context)
        if (notifications.isEmpty()) {
            return "No recent notifications."
        }

        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("Recent notifications:\n\n")

        notifications.take(limit).forEachIndexed { index, notif ->
            val time = dateFormat.format(Date(notif.time))
            sb.append("${index + 1}. ${notif.appName} at $time\n")
            if (!notif.title.isNullOrBlank()) {
                sb.append("   Title: ${notif.title}\n")
            }
            if (!notif.text.isNullOrBlank()) {
                sb.append("   Message: ${notif.text}\n")
            }
            sb.append("\n")
        }

        return sb.toString().trim()
    }
}
