package com.sdk.glassessdksample.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

/**
 * Manager for storing and retrieving Quick Notes
 * Uses SharedPreferences for persistent storage
 * Supports image attachments saved to internal storage
 */
class QuickNotesManager(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("quick_notes_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val TAG = "QuickNotesManager"
    
    companion object {
        private const val KEY_NOTES = "notes_list"
        private const val NOTES_IMAGE_DIR = "quick_notes_images"
    }
    
    /**
     * Get the directory for storing note images
     */
    private fun getImagesDir(): File {
        val dir = File(context.filesDir, NOTES_IMAGE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    /**
     * Save a bitmap as an image file and return the path
     */
    fun saveNoteImage(bitmap: Bitmap, noteId: String): String {
        val file = File(getImagesDir(), "note_img_${noteId}.jpg")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            Log.d(TAG, "Image saved for note $noteId at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save note image: ${e.message}", e)
        }
        return file.absolutePath
    }
    
    /**
     * Save image from a source file path and return the internal path
     */
    fun copyImageForNote(sourcePath: String, noteId: String): String? {
        return try {
            val sourceBitmap = BitmapFactory.decodeFile(sourcePath)
            if (sourceBitmap != null) {
                saveNoteImage(sourceBitmap, noteId)
            } else {
                Log.e(TAG, "Failed to decode source image: $sourcePath")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image for note: ${e.message}", e)
            null
        }
    }
    
    /**
     * Delete the image file for a note
     */
    private fun deleteNoteImage(noteId: String) {
        val file = File(getImagesDir(), "note_img_${noteId}.jpg")
        if (file.exists()) {
            file.delete()
            Log.d(TAG, "Deleted image for note $noteId")
        }
    }
    
    /**
     * Get all notes, sorted by timestamp (newest first)
     */
    fun getAllNotes(): List<QuickNote> {
        val json = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<QuickNote>>() {}.type
            val notes: List<QuickNote> = gson.fromJson(json, type)
            notes.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading notes: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Add a new note
     */
    fun addNote(note: QuickNote) {
        val notes = getAllNotes().toMutableList()
        notes.add(note)
        saveNotes(notes)
        Log.d(TAG, "Note added: ${note.title} (created by ${note.createdBy}, hasImage=${note.hasImage()})")
    }
    
    /**
     * Create a note with title and content
     */
    fun createNote(title: String, content: String, createdBy: QuickNote.CreatedBy = QuickNote.CreatedBy.USER): QuickNote {
        val note = QuickNote(
            title = title,
            content = content,
            createdBy = createdBy
        )
        addNote(note)
        return note
    }
    
    /**
     * Create a note with title, content, and image
     */
    fun createNoteWithImage(title: String, content: String, imagePath: String?, createdBy: QuickNote.CreatedBy = QuickNote.CreatedBy.USER): QuickNote {
        val note = QuickNote(
            title = title,
            content = content,
            imagePath = imagePath,
            createdBy = createdBy
        )
        addNote(note)
        return note
    }
    
    /**
     * Update an existing note
     */
    fun updateNote(noteId: String, newTitle: String, newContent: String) {
        val notes = getAllNotes().toMutableList()
        val index = notes.indexOfFirst { it.id == noteId }
        if (index != -1) {
            val oldNote = notes[index]
            notes[index] = oldNote.copy(title = newTitle, content = newContent)
            saveNotes(notes)
            Log.d(TAG, "Note updated: $noteId")
        }
    }
    
    /**
     * Update an existing note including image
     */
    fun updateNoteWithImage(noteId: String, newTitle: String, newContent: String, newImagePath: String?) {
        val notes = getAllNotes().toMutableList()
        val index = notes.indexOfFirst { it.id == noteId }
        if (index != -1) {
            val oldNote = notes[index]
            notes[index] = oldNote.copy(title = newTitle, content = newContent, imagePath = newImagePath)
            saveNotes(notes)
            Log.d(TAG, "Note updated with image: $noteId")
        }
    }
    
    /**
     * Delete a note
     */
    fun deleteNote(noteId: String) {
        deleteNoteImage(noteId)  // Clean up image file too
        val notes = getAllNotes().toMutableList()
        notes.removeAll { it.id == noteId }
        saveNotes(notes)
        Log.d(TAG, "Note deleted: $noteId")
    }
    
    /**
     * Search notes by keyword
     */
    fun searchNotes(keyword: String): List<QuickNote> {
        val lowerKeyword = keyword.lowercase()
        return getAllNotes().filter {
            it.title.lowercase().contains(lowerKeyword) ||
            it.content.lowercase().contains(lowerKeyword)
        }
    }
    
    /**
     * Get notes context as a string for AI injection
     */
    fun getNotesContextForAI(): String {
        val notes = getAllNotes()
        if (notes.isEmpty()) {
            return "No quick notes saved yet."
        }
        
        val notesText = notes.take(20).joinToString("\n\n") { note ->
            val imageInfo = if (note.hasImage()) " [Has Photo Attached]" else ""
            "Note: ${note.title}\nContent: ${note.content}$imageInfo\nDate: ${note.getFormattedTimestamp()}"
        }
        
        return """
            User's Quick Notes (${notes.size} total):
            $notesText
            
            You have access to these notes. When the user asks about their notes or mentions something they asked you to remember, refer to these notes.
            You can also create notes with photos attached using the capture_photo_note tool.
        """.trimIndent()
    }
    
    /**
     * Clear all notes
     */
    fun clearAllNotes() {
        // Clean up all image files
        getAllNotes().forEach { deleteNoteImage(it.id) }
        prefs.edit().remove(KEY_NOTES).apply()
        Log.d(TAG, "All notes cleared")
    }
    
    private fun saveNotes(notes: List<QuickNote>) {
        val json = gson.toJson(notes)
        prefs.edit().putString(KEY_NOTES, json).apply()
    }
}
