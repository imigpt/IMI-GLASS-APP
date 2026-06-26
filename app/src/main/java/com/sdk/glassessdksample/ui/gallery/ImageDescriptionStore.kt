package com.sdk.glassessdksample.ui.gallery

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Central vault that stores every AI-generated image description in a single JSON file.
 * Hidden from main navigation – accessible only via a secret long-press in LiveGalleryActivity.
 *
 * Vault file: <filesDir>/image_description_vault/vault.json
 *
 * Schema per entry:
 *   { "fileName": "img_001.jpg", "timestamp": 1700000000000, "description": "A cup of coffee..." }
 *
 * Key API:
 *   - save(context, fileName, description)   – upsert an entry
 *   - getAll(context)                        – all entries, newest first
 *   - getLast(context)                       – description of the most recent image or null
 *   - buildAIContext(context)                – formatted block injected into AI system prompt
 */
object ImageDescriptionStore {

    private const val TAG = "ImageDescriptionStore"
    private const val VAULT_DIR  = "image_description_vault"
    private const val VAULT_FILE = "vault.json"

    data class DescEntry(
        val fileName: String,
        val timestamp: Long,
        val description: String
    ) {
        val displayTime: String
            get() = SimpleDateFormat("MMM dd yyyy, HH:mm", Locale.getDefault())
                .format(Date(timestamp))
    }

    // ── private helpers ────────────────────────────────────────────────────

    private fun vaultFile(context: Context): File {
        val dir = File(context.filesDir, VAULT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, VAULT_FILE)
    }

    private fun readAll(context: Context): MutableList<DescEntry> {
        return try {
            val file = vaultFile(context)
            if (!file.exists()) return mutableListOf()
            val json = JSONArray(file.readText())
            val list = mutableListOf<DescEntry>()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                list.add(
                    DescEntry(
                        fileName    = obj.optString("fileName"),
                        timestamp   = obj.optLong("timestamp"),
                        description = obj.optString("description")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Read vault failed: ${e.message}")
            mutableListOf()
        }
    }

    private fun writeAll(context: Context, entries: List<DescEntry>) {
        try {
            val json = JSONArray()
            entries.forEach { e ->
                json.put(JSONObject().apply {
                    put("fileName", e.fileName)
                    put("timestamp", e.timestamp)
                    put("description", e.description)
                })
            }
            vaultFile(context).writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Write vault failed: ${e.message}")
        }
    }

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Insert or update the description for [fileName].
     * The entry is moved to the top (newest) if it already exists.
     */
    fun save(context: Context, fileName: String, description: String) {
        val entries = readAll(context).toMutableList()
        entries.removeAll { it.fileName == fileName }        // remove old entry if any
        entries.add(0, DescEntry(fileName, System.currentTimeMillis(), description))
        writeAll(context, entries)
        Log.d(TAG, "Vault saved: $fileName")

        // Mirror the AI description to the backend vision record (fire-and-forget).
        com.sdk.glassessdksample.ui.sync.VisionSync.pushDescription(context, fileName, description)
    }

    /** All entries, most-recent first. */
    fun getAll(context: Context): List<DescEntry> = readAll(context)

    /**
     * Description for the most recently saved image, or null if vault is empty.
     */
    fun getLast(context: Context): DescEntry? = readAll(context).firstOrNull()

    /**
     * Formatted multi-line block injected into the Gemini system prompt.
     * Mirrors buildGalleryContext() in GeminiAIClient but reads from the vault JSON.
     */
    fun buildAIContext(context: Context): String {
        val entries = readAll(context).take(50)
        if (entries.isEmpty()) return ""
        val lines = entries.mapIndexed { idx, e ->
            "  [Photo ${idx + 1}] ${e.displayTime}  \"${e.fileName}\" — ${e.description}"
        }.joinToString("\n")
        return """

========== AI IMAGE DESCRIPTION VAULT (${entries.size} photos) ==========
These are YOUR stored notes for the user's smart-glasses photos.
Treat them as facts you already know — NEVER say you cannot see or access photos.

QUERY RULES:
- "last photo / latest image / most recent picture" → use [Photo 1] (most recent)
- "first photo / earliest"                          → use last entry
- Describe something and ask if it matches          → search all entries
- NEVER say "I cannot access" — you DO have the descriptions below.

$lines
=============================================================="""
    }

    /**
     * Sync-import any existing .desc.txt sidecar files into the vault
     * (called once on LiveGalleryActivity start so legacy data is included).
     */
    fun importLegacySidecarFiles(context: Context) {
        try {
            val galleryDir = File(context.filesDir, "live_gallery")
            if (!galleryDir.exists()) return
            val descFiles = galleryDir.listFiles { f -> f.name.endsWith(".desc.txt") } ?: return
            val existing  = readAll(context).map { it.fileName }.toSet()
            var imported  = 0
            for (df in descFiles) {
                val photoName = df.nameWithoutExtension.removeSuffix(".desc") + ".jpg"
                if (photoName in existing) continue
                val desc = df.readText().trim()
                if (desc.isNotEmpty()) {
                    val ts = df.lastModified()
                    val entries = readAll(context).toMutableList()
                    entries.add(DescEntry(photoName, ts, desc))
                    entries.sortByDescending { it.timestamp }
                    writeAll(context, entries)
                    imported++
                }
            }
            if (imported > 0) Log.i(TAG, "Imported $imported legacy sidecar files into vault")
        } catch (e: Exception) {
            Log.e(TAG, "Legacy import failed: ${e.message}")
        }
    }
}
