package com.sdk.glassessdksample.ui.gallery

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * LiveGalleryManager – central store for all photos captured from the glasses.
 *
 * Every time a photo arrives (via BLE in MainActivity or via WiFi in VisionChatActivity)
 * call [savePhoto] to persist a copy.  [LiveGalleryActivity] reads from the same
 * directory so every captured image appears automatically in the Live Gallery.
 *
 * Storage location:  <filesDir>/live_gallery/
 */
object LiveGalleryManager {

    private const val TAG = "LiveGalleryManager"
    private const val GALLERY_DIR = "live_gallery"
    private val DATE_FMT = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    // ── LocalBroadcast constants ────────────────────────────────────────────
    /** Broadcast sent while a photo is being saved (show spinner). */
    const val ACTION_PHOTO_SAVING   = "com.sdk.glassessdksample.GALLERY_PHOTO_SAVING"
    /** Broadcast sent after a photo has been saved (refresh grid + hide spinner). */
    const val ACTION_PHOTO_SAVED    = "com.sdk.glassessdksample.GALLERY_PHOTO_SAVED"
    /** Extra: human-readable status message (String). */
    const val EXTRA_STATUS_MSG      = "status_msg"
    /** Extra: absolute path of the saved file (String). */
    const val EXTRA_FILE_PATH       = "file_path"
    /** Extra: label identifying the source ("BLE", "WiFi"). */
    const val EXTRA_SOURCE          = "source"

    // ─────────────────────────────────────────────────────────────────────────

    /** Returns (or creates) the private gallery directory for this app. */
    fun getGalleryDir(context: Context): File {
        val dir = File(context.filesDir, GALLERY_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Save raw JPEG bytes to the gallery.
     *
     * @param context  Used to resolve [filesDir].
     * @param bytes    Raw JPEG / PNG image bytes.
     * @param source   Short label for the source ("BLE" or "WiFi").  Added to filename.
     * @return         The saved [File], or null on failure.
     */
    fun savePhoto(context: Context, bytes: ByteArray, source: String = "Glasses"): File? {
        // ── Notify gallery: saving started ───────────────────────────────────
        broadcast(context, ACTION_PHOTO_SAVING,
            "⬇️ Saving photo from $source...", null, source)

        return try {
            val dir = getGalleryDir(context)
            val timestamp = DATE_FMT.format(Date())
            val safeSrc = source.replace("[^A-Za-z0-9]".toRegex(), "")
            val file = File(dir, "IMG_${timestamp}_${safeSrc}.jpg")
            file.writeBytes(bytes)
            Log.i(TAG, "✅ Photo saved to Live Gallery: ${file.name} (${bytes.size} bytes)")

            // ── Notify gallery: saved successfully ───────────────────────────
            broadcast(context, ACTION_PHOTO_SAVED,
                "✅ Photo saved from $source", file.absolutePath, source)

            // ── Mirror to backend (fire-and-forget; no-op if not logged in) ──
            com.sdk.glassessdksample.ui.sync.VisionSync.pushPhoto(context, file, source)
            file
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save photo to Live Gallery: ${e.message}", e)
            broadcast(context, ACTION_PHOTO_SAVED,
                "❌ Save failed: ${e.message}", null, source)
            null
        }
    }

    /**
     * Copy an existing [File] into the gallery.
     *
     * @return  The saved [File], or null on failure.
     */
    fun copyToGallery(context: Context, sourceFile: File, source: String = "Glasses"): File? {
        return try {
            if (!sourceFile.exists()) return null
            savePhoto(context, sourceFile.readBytes(), source)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy file to Live Gallery: ${e.message}", e)
            null
        }
    }

    /**
     * Return all gallery images sorted with the newest first.
     */
    fun getAllPhotos(context: Context): List<File> {
        val dir = getGalleryDir(context)
        return dir.listFiles { f -> f.extension.lowercase() in listOf("jpg", "jpeg", "png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Delete a single photo from the gallery. */
    fun deletePhoto(file: File): Boolean {
        return try {
            val deleted = file.delete()
            Log.i(TAG, if (deleted) "🗑️ Deleted: ${file.name}" else "⚠️ Could not delete: ${file.name}")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting photo: ${e.message}")
            false
        }
    }

    /** Delete all photos from the gallery. */
    fun deleteAll(context: Context): Int {
        var count = 0
        getAllPhotos(context).forEach { f -> if (f.delete()) count++ }
        clearDownloadedTracking(context)
        Log.i(TAG, "🗑️ Deleted $count photos from Live Gallery")
        return count
    }

    /** Total count of gallery photos. */
    fun photoCount(context: Context): Int = getAllPhotos(context).size

    /**
     * Return a set of original source filenames already saved in the gallery.
     * Used by WiFi sync to skip files that have already been downloaded.
     * We store the original filename in the EXIF or a sidecar file; for simplicity,
     * we use a SharedPreferences set keyed by the original glass filename.
     */
    fun getDownloadedFileNames(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences("live_gallery_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("downloaded_files", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    /** Mark an original glass filename as already downloaded. */
    fun markFileDownloaded(context: Context, originalFileName: String) {
        val prefs = context.getSharedPreferences("live_gallery_prefs", Context.MODE_PRIVATE)
        val existing = getDownloadedFileNames(context)
        existing.add(originalFileName)
        prefs.edit().putStringSet("downloaded_files", existing).apply()
    }

    /** Check if a specific glass file has already been downloaded. */
    fun isAlreadyDownloaded(context: Context, originalFileName: String): Boolean {
        return getDownloadedFileNames(context).contains(originalFileName)
    }

    /** Clear the downloaded filenames tracking (e.g. when gallery is cleared). */
    private fun clearDownloadedTracking(context: Context) {
        val prefs = context.getSharedPreferences("live_gallery_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("downloaded_files").apply()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun broadcast(
        context: Context,
        action: String,
        statusMsg: String,
        filePath: String?,
        source: String
    ) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_STATUS_MSG, statusMsg)
            putExtra(EXTRA_SOURCE, source)
            if (filePath != null) putExtra(EXTRA_FILE_PATH, filePath)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }
}
