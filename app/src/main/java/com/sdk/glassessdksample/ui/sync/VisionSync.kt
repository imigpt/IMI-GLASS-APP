package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import com.sdk.glassessdksample.ui.DevicePreferenceManager
import com.sdk.glassessdksample.ui.DeviceType
import java.io.File
import java.util.concurrent.Executors

/**
 * Fire-and-forget pusher for vision records (photos + AI descriptions).
 *
 * The local gallery (`<filesDir>/live_gallery`) and description vault remain the
 * UI's source of truth; every capture/description also flows through here so the
 * backend has a copy that survives reinstalls.
 *
 * Photos are keyed locally by file name. After uploading a photo we remember the
 * server record id against that file name, so when the AI description for the
 * same photo arrives later we can attach it to the same record instead of
 * creating a duplicate.
 */
object VisionSync {

    private const val TAG = "VisionSync"
    private const val PREFS = "vision_sync_prefs"

    private val io = Executors.newSingleThreadExecutor()

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceType(ctx: Context): String =
        when (DevicePreferenceManager.getDeviceType(ctx)) {
            DeviceType.MARK1 -> "MARK1"
            else -> "MARK2"
        }

    private fun rememberId(ctx: Context, fileName: String, recordId: String) {
        prefs(ctx).edit().putString("id_$fileName", recordId).apply()
    }

    private fun lookupId(ctx: Context, fileName: String): String? =
        prefs(ctx).getString("id_$fileName", null)

    /**
     * Upload a freshly-captured photo. [source] is "BLE"/"WiFi". Best-effort;
     * the returned server id (if any) is cached against [photoFile]'s name so a
     * later description can reference the same record.
     */
    fun pushPhoto(context: Context, photoFile: File, source: String) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        if (!photoFile.exists()) return
        io.execute {
            try {
                val result = VisionApi(ctx).uploadPhoto(
                    imageFile = photoFile,
                    source = source,
                    deviceType = deviceType(ctx),
                    capturedAt = photoFile.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                )
                when (result) {
                    is VisionApi.Result.Ok -> rememberId(ctx, photoFile.name, result.value.id)
                    is VisionApi.Result.Err -> Log.w(TAG, "pushPhoto -> ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushPhoto failed: ${e.message}")
            }
        }
    }

    /**
     * Push the AI description for a photo (referenced by its file name). If we
     * already uploaded the photo we attach the description to that record;
     * otherwise the backend creates a fresh record from the description alone.
     */
    fun pushDescription(
        context: Context,
        fileName: String,
        description: String,
        userQuery: String? = null,
        sessionId: String? = null
    ) {
        if (description.isBlank()) return
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                val existingId = lookupId(ctx, fileName)
                val result = VisionApi(ctx).saveDescription(
                    id = existingId,
                    deviceType = deviceType(ctx),
                    userQuery = userQuery,
                    aiDescription = description,
                    sessionId = sessionId
                )
                when (result) {
                    is VisionApi.Result.Ok -> if (existingId == null) rememberId(ctx, fileName, result.value.id)
                    is VisionApi.Result.Err -> Log.w(TAG, "pushDescription -> ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushDescription failed: ${e.message}")
            }
        }
    }

    /** Clear every vision record server-side (mirrors a local "delete all"). */
    fun pushDeleteAll(context: Context) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                VisionApi(ctx).deleteAllPhotos()
                prefs(ctx).edit().clear().apply()
            } catch (e: Exception) {
                Log.w(TAG, "pushDeleteAll failed: ${e.message}")
            }
        }
    }
}
