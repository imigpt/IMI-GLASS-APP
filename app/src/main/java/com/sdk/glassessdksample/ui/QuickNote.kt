package com.sdk.glassessdksample.ui

import java.util.UUID

/**
 * Quick Note data model for storing user notes and AI-generated reminders
 * Supports optional image attachment (stored as file path)
 */
data class QuickNote(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val imagePath: String? = null,  // Path to attached image file (null = no image)
    val timestamp: Long = System.currentTimeMillis(),
    val createdBy: CreatedBy = CreatedBy.USER
) {
    enum class CreatedBy {
        USER,    // Manually created by user
        AI       // Automatically created by AI
    }
    
    fun hasImage(): Boolean = !imagePath.isNullOrBlank()
    
    fun getFormattedTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}
